package com.aliya.hyfilemgr.archive;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ZIP 解出缓存的统一管理与生命周期。
 *
 * <p><b>为什么需要这个类</b>（2026-11-13）：此前三处各自往 cacheDir 解压缩包条目
 * —— 图片查看器 {@code zipview}、缩略图 {@code zipthumb}、播放器 {@code zipmedia}
 * —— 但**没有任何一处负责删**。后果：</p>
 * <ul>
 *   <li>浏览一个几百 MB 的压缩包，点开几张图/一个视频，缓存就涨几百 MB 且永不回收；
 *       用户反馈「缓存没清理到，占了大量空间」</li>
 *   <li>设置 → 存储管理里的「ZIP 浏览缓存」只清 {@code zipview}+{@code zipnested}，
 *       {@code zipthumb}、{@code zipmedia} 完全漏掉</li>
 * </ul>
 *
 * <p><b>生命周期策略</b>（用户要求「部分缓存改为退出即销毁」）：</p>
 * <ul>
 *   <li><b>per-archive 追踪</b>：记录每个压缩包解出过哪些文件，
 *       用户退出该压缩包（离开 {@code zip://} 路径）时精确删掉它解出的缓存</li>
 *   <li><b>总大小上限</b>：兜底 300 MB，超了按最久未使用淘汰
 *       （防止用户一直待在同一个包里、或崩溃导致没触发退出清理）</li>
 *   <li><b>存储管理可清</b>：{@link #clearAll} 清全部三个目录，
 *       让设置页的「ZIP 浏览缓存」真正覆盖完整</li>
 * </ul>
 *
 * <p><b>文件名规则</b>：SHA-256(包路径 + \0 + 条目名) 前 16 字符 + 条目基名。
 * 不用 {@code String.hashCode()}——它只有 32 位，按生日悖论约 7.7 万个 key
 * 就有 50% 碰撞，碰撞后果是「显示/播放到别的文件」。</p>
 */
public final class ZipCache {

    /** 图片查看器解出的原图（尺寸大） */
    public static final String DIR_VIEW = "zipview";
    /** 列表缩略图用的原图副本 */
    public static final String DIR_THUMB = "zipthumb";
    /** 播放器解出的音视频 */
    public static final String DIR_MEDIA = "zipmedia";
    /** 嵌套压缩包解出目录（历史遗留，一并纳入统一清理） */
    public static final String DIR_NESTED = "zipnested";

    private static final String[] ALL_DIRS = {DIR_VIEW, DIR_THUMB, DIR_MEDIA, DIR_NESTED};

    /** 缓存总量上限（字节）：超了按最后访问时间淘汰 */
    private static final long MAX_TOTAL_BYTES = 300L * 1024 * 1024;

    /**
     * 每个压缩包解出过哪些文件：包绝对路径 → 文件列表。
     *
     * <p>用绝对路径做 key 而不是 File 对象，避免不同 File 实例 equals 语义差异。
     * 并发容器：解出可能发生在下载/解码线程，清理发生在 UI 线程。</p>
     */
    private static final ConcurrentHashMap<String, List<File>> PER_ARCHIVE =
            new ConcurrentHashMap<>();

    private ZipCache() { }

    /**
     * 解出指定条目到缓存（已存在则直接复用）。
     *
     * @param ctx     任意 Context（内部取 Application 的 cacheDir）
     * @param dirName {@link #DIR_VIEW} / {@link #DIR_THUMB} / {@link #DIR_MEDIA}
     * @param zipPath 压缩包绝对路径
     * @param inner   包内条目路径
     * @return 本地文件；失败返回 null
     */
    public static File extract(Context ctx, String dirName, String zipPath, String inner) {
        if (ctx == null || zipPath == null || inner == null || inner.isEmpty()) return null;
        try {
            File dir = new File(ctx.getApplicationContext().getCacheDir(), dirName);
            if (!dir.exists() && !dir.mkdirs()) return null;

            String base = inner.substring(inner.lastIndexOf('/') + 1);
            if (base.isEmpty()) return null;
            // 基名可能含非法字符（Windows 打包的条目偶有），替换掉
            base = base.replaceAll("[\\\\/:*?\"<>|]", "_");

            File out = new File(dir, keyOf(zipPath, inner) + "_" + base);
            if (out.isFile() && out.length() > 0) {
                touch(out);              // 更新访问时间，供 LRU 淘汰参考
                return out;
            }

            try (ZipFile zf = new ZipFile(new File(zipPath))) {
                ZipEntry ze = zf.getEntry(inner);
                if (ze == null || ze.isDirectory()) return null;
                try (InputStream in = zf.getInputStream(ze);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    // 缓冲区大一点：媒体文件常有几十 MB，减少 syscall
                    byte[] buf = new byte[131072];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
            register(zipPath, out);
            trimIfNeeded(ctx);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解出并返回路径字符串（失败 null），供调用方直接喂给 BitmapFactory / ExoPlayer */
    public static String extractPath(Context ctx, String dirName, String zipPath, String inner) {
        File f = extract(ctx, dirName, zipPath, inner);
        return f == null ? null : f.getAbsolutePath();
    }

    /**
     * 用户离开某个压缩包时调用：删掉它解出的所有缓存。
     *
     * <p>这是「退出即销毁」的主路径。由模块在路径从 {@code zip://A/...} 切到
     * 非 A 的路径时触发（见 FileManagerModule.maybePurgeArchiveCache）。</p>
     *
     * @return 释放的字节数
     */
    public static long purgeArchive(String zipPath) {
        if (zipPath == null) return 0;
        List<File> files = PER_ARCHIVE.remove(zipPath);
        if (files == null) return 0;
        long freed = 0;
        for (File f : files) {
            try {
                if (f != null && f.isFile()) {
                    long len = f.length();
                    if (f.delete()) freed += len;
                }
            } catch (Throwable ignored) { }
        }
        return freed;
    }

    /** 清空全部 ZIP 解出缓存（设置 → 存储管理 → ZIP 浏览缓存） */
    public static long clearAll(Context ctx) {
        long freed = 0;
        PER_ARCHIVE.clear();
        for (String d : ALL_DIRS) {
            freed += deleteDir(new File(ctx.getApplicationContext().getCacheDir(), d));
        }
        return freed;
    }

    /** 各 ZIP 缓存目录合计字节数（存储管理页展示用） */
    public static long totalSize(Context ctx) {
        long sum = 0;
        for (String d : ALL_DIRS) {
            sum += dirSize(new File(ctx.getApplicationContext().getCacheDir(), d));
        }
        return sum;
    }

    /** 单个目录字节数（存储页分项展示） */
    public static long dirSizeOf(Context ctx, String dirName) {
        return dirSize(new File(ctx.getApplicationContext().getCacheDir(), dirName));
    }

    // ── 内部 ──

    private static void register(String zipPath, File f) {
        PER_ARCHIVE.computeIfAbsent(zipPath,
                k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(f);
    }

    private static void touch(File f) {
        try {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) { }
    }

    /** 超过总上限时按最后修改时间从旧到新删（LRU 近似） */
    private static void trimIfNeeded(Context ctx) {
        try {
            List<File> all = new ArrayList<>();
            long total = 0;
            for (String d : ALL_DIRS) {
                File dir = new File(ctx.getApplicationContext().getCacheDir(), d);
                File[] kids = dir.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    if (k.isFile()) {
                        all.add(k);
                        total += k.length();
                    }
                }
            }
            if (total <= MAX_TOTAL_BYTES) return;
            all.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            long target = MAX_TOTAL_BYTES * 8 / 10;   // 一次清到 80%，避免频繁 trim
            for (File f : all) {
                if (total <= target) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
            // 同步剔除已被删掉的引用
            for (List<File> list : PER_ARCHIVE.values()) {
                list.removeIf(f -> f == null || !f.exists());
            }
        } catch (Throwable ignored) { }
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        long sum = 0;
        for (File k : kids) sum += k.isDirectory() ? dirSize(k) : k.length();
        return sum;
    }

    private static long deleteDir(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long freed = 0;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) freed += deleteDir(k);
                else {
                    long len = k.length();
                    if (k.delete()) freed += len;
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        return freed;
    }

    /** 缓存键：SHA-256(包路径 \0 条目) 前 16 字符 */
    private static String keyOf(String zipPath, String inner) {
        return sha256Hex(zipPath + "\u0000" + inner).substring(0, 16);
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * 从 {@code zip://<包路径>/<条目>} 拆出包路径与条目名。
     *
     * <p>切分点选「最后一个以 .zip/.jar/.apk/.epub 结尾的段之前」——
     * 包内路径本身可能含 .zip 字样（嵌套包），但只有紧跟 {@code /} 的那个才是包边界。</p>
     *
     * @return {包路径, 条目名}；无法拆分返回 null
     */
    public static String[] splitVirtual(String virtPath) {
        if (virtPath == null || !virtPath.startsWith("zip://")) return null;
        String rest = virtPath.substring("zip://".length());
        String lower = rest.toLowerCase(Locale.ROOT);
        for (int i = rest.length() - 1; i >= 0; i--) {
            if (rest.charAt(i) != '/') continue;
            String before = lower.substring(0, i);
            if (before.endsWith(".zip") || before.endsWith(".jar")
                    || before.endsWith(".apk") || before.endsWith(".epub")) {
                return new String[]{rest.substring(0, i), rest.substring(i + 1)};
            }
        }
        return null;
    }

    /** 该虚拟路径指向的包（用于「退出包时清理」判定）；非 zip 路径返回 null */
    public static String archiveOf(String path) {
        String[] p = splitVirtual(path);
        return p == null ? null : p[0];
    }
}
