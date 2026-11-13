package com.aliya.hyfilemgr.filemgr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件列表缩略图加载器。
 *
 * <p>支持三类：
 * <ul>
 *   <li>图片：BitmapFactory 采样解码（按目标尺寸 inSampleSize，避免 OOM）</li>
 *   <li>视频：MediaMetadataRetriever 取首帧</li>
 *   <li>APK：PackageManager 取应用图标（未安装也能从包内读）</li>
 * </ul>
 *
 * <p>三级缓存：LruCache 内存 → 磁盘（cacheDir/thumbs）→ 源文件。
 * 加载在固定线程池内进行，完成后回主线程 setImageBitmap。
 * 通过 ImageView 的 tag 校验防止复用错位（RecyclerView 滚动快速时必需）。</p>
 */
public final class ThumbLoader {

    /** 缩略图目标边长（px 基准，实际按 density 缩放由调用方决定） */
    private static final int TARGET_PX = 96;
    /** 磁盘缓存上限：按**字节**控制（旧实现按文件数，4000 个 ≈80MB 且与注释 200MB 不符） */
    private static final long DISK_MAX_BYTES = 200L * 1024 * 1024;

    /**
     * 进行中的解码任务。同一 cacheKey 被多个 ViewHolder 同时请求时
     * 复用一个 Future，避免多线程重复解码同一文件（并发写同一 .jpg
     * 还会产生半写文件，之后 readDisk 永久空白）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.CompletableFuture<Bitmap>> IN_FLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b == null ? 0 : b.getByteCount();
                }
            };

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "thumb-" + COUNTER.incrementAndGet());
                t.setPriority(Thread.MIN_PRIORITY);   // 不抢 UI 线程
                t.setDaemon(true);
                return t;
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThumbLoader() { }

    /** 该条目是否有缩略图（按扩展名快速判定） */
    public static boolean supports(String name) {
        if (name == null) return false;
        String low = name.toLowerCase(Locale.ROOT);
        // ico 是图标容器（自研解码器）；exe/dll/mun/cpl 走 PE 资源提取
        return isImage(low) || isVideo(low) || low.endsWith(".apk")
                || low.endsWith(".ico")
                || com.aliya.hyfilemgr.icon.PeIconExtractor.supports(low);
    }

    public static boolean isImage(String low) {
        return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                || low.endsWith(".webp") || low.endsWith(".bmp") || low.endsWith(".gif")
                || low.endsWith(".heic") || low.endsWith(".heif") || low.endsWith(".avif");
    }

    public static boolean isVideo(String low) {
        return low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".webm")
                || low.endsWith(".avi") || low.endsWith(".mov") || low.endsWith(".3gp")
                || low.endsWith(".flv") || low.endsWith(".ts") || low.endsWith(".m4v");
    }

    /**
     * 异步加载缩略图到 ImageView。
     *
     * @param iv  目标 ImageView（会在加载完成时校验其 tag 防错位）
     * @param key 缓存键：优先用文件绝对路径 + mtime（文件变了自动失效）
     */
    /** 解码失败时的回调（由调用方回退为类型图标） */
    public interface OnFail {
        void onFail(ImageView iv);
    }

    public static void load(Context ctx, final ImageView iv, final String path,
                            final String name, final long mtime, final OnFail onFail) {
        if (iv == null || path == null) return;
        final String cacheKey = path + "@" + mtime;
        iv.setTag(cacheKey);

        // ① 内存
        Bitmap b = MEM.get(cacheKey);
        if (b != null) {
            iv.setColorFilter(null);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(b);
            return;
        }

        // 不要 setImageBitmap(null)：适配器已预先设过类型图标，
        // 清掉会让快速滚动时闪白一帧。
        iv.setColorFilter(null);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

        final Context app = ctx.getApplicationContext();
        final String key = cacheKey;

        // in-flight 去重：同 key 只解码一次。
        // 旧实现用 FutureTask + 两个池任务（一个跑、一个 get() 阻塞），
        // 实测吞吐 3061ms → 4585ms（下降 50%），因为 3 线程里约 2/3 在
        // FutureTask.get() 空转。改用 CompletableFuture：
        // 只在池里跑一个解码任务，回调通过 thenAccept 派发到主线程。
        final java.util.concurrent.CompletableFuture<Bitmap> future;
        java.util.concurrent.CompletableFuture<Bitmap> existing = IN_FLIGHT.get(key);
        if (existing != null) {
            future = existing;
        } else {
            java.util.concurrent.CompletableFuture<Bitmap> created =
                    new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.CompletableFuture<Bitmap> prev = IN_FLIGHT.putIfAbsent(key, created);
            if (prev != null) {
                future = prev;
            } else {
                future = created;
                POOL.execute(() -> {
                    Bitmap decoded = null;
                    try {
                        decoded = readDisk(app, key);
                        if (decoded == null) {
                            decoded = decode(app, path, name);
                            if (decoded != null) writeDisk(app, key, decoded);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        IN_FLIGHT.remove(key);
                    }
                    created.complete(decoded);
                });
            }
        }

        future.thenAccept(fin -> MAIN.post(() -> {
            // 防错位：加载完成时 ImageView 已被复用或已脱离窗口 → 丢弃
            if (!iv.isAttachedToWindow()) return;
            if (!cacheKey.equals(iv.getTag())) return;
            if (fin == null) {
                if (onFail != null) onFail.onFail(iv);
                return;
            }
            iv.setColorFilter(null);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(fin);
        }));
    }

    // ── 解码 ──

    /** 兼容旧调用：不关心失败 */
    public static void load(Context ctx, ImageView iv, String path,
                            String name, long mtime) {
        load(ctx, iv, path, name, mtime, null);
    }

    private static Bitmap decode(Context ctx, String path, String name) {
        String low = name == null ? path.toLowerCase(Locale.ROOT)
                : name.toLowerCase(Locale.ROOT);
        try {
            // ⓪ ZIP 虚拟路径（zip://包/条目）：先解出单条目到缓存再当普通图片读。
            // 不解出的话 BitmapFactory 拿到 "zip://…" 字符串会直接失败，
            // 列表里所有压缩包内图片都只能显示通用图标。
            String realPath = path;
            if (path != null && path.startsWith("zip://")) {
                realPath = extractZipEntryForThumb(ctx, path);
                if (realPath == null) return null;
            }
            // ① ICO：Android BitmapFactory 不支持该格式，必须走自研解码器。
            // 放在 isImage 之前——.ico 同时也在 isImage 的扩展名列表里。
            if (low.endsWith(".ico")) {
                com.aliya.hyfilemgr.icon.IcoDecoder.Result r =
                        com.aliya.hyfilemgr.icon.IcoDecoder.decode(realPath, TARGET_PX * 2);
                return r == null ? null : scaleTo(r.bitmap, TARGET_PX);
            }
            // ② Windows PE（exe/dll/mun/cpl）：从 .rsrc 节提取图标
            if (com.aliya.hyfilemgr.icon.PeIconExtractor.supports(low)) {
                Bitmap bm = com.aliya.hyfilemgr.icon.PeIconExtractor.extractIcon(realPath, TARGET_PX * 2);
                return bm == null ? null : scaleTo(bm, TARGET_PX);
            }
            if (isImage(low)) return decodeImage(realPath);
            if (isVideo(low)) return decodeVideo(realPath);
            if (low.endsWith(".apk")) {
                Bitmap bm = decodeApk(realPath);
                if (bm == null) bm = decodeApkDirect(realPath);
                return bm;
            }
        } catch (Throwable ignored) {
            // 解码失败静默（列表里保留类型图标即可，不弹错误打扰）
        }
        return null;
    }

    private static Bitmap decodeImage(String path) {
        // 先只读尺寸
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, TARGET_PX);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // 省一半内存，缩略图足够
        return BitmapFactory.decodeFile(path, o);
    }

    /**
     * 视频首帧缩略图。
     *
     * <p><b>为什么不取 timeUs=0</b>：大量视频开头有 0.3～1 秒黑场（导演/编码器习惯），
     * 取 0 时刻就得到一张纯黑图（用户反馈的「部分视频缩略图纯黑」）。
     * 也不能只靠「取到非黑帧就换」的启发式（老视频可能中间也有黑场）。</p>
     *
     * <p>做法：按视频时长取 <b>10%</b> 位置（封顶 3 秒），避开片头黑场；
     * 取不到时长就回退到 1 秒处；仍失败才回退 0 时刻。
     * 同时遇到「像素全黑」的结果再往后找一次，兼顾黑场位置异常的视频。</p>
     */
    private static Bitmap decodeVideo(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            long durMs = 0;
            try {
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durMs = Long.parseLong(d);
            } catch (Throwable ignored) { }

            // 目标时刻：时长的 10%，封顶 3 秒；拿不到时长则用 1 秒
            long tMs = durMs > 0 ? Math.min(3000L, durMs / 10) : 1000L;
            Bitmap f = frameAt(r, tMs);
            // 全黑（片头黑场比预期长）→ 再往后找一次
            if (isAllBlack(f) && durMs > 0) {
                Bitmap f2 = frameAt(r, Math.min(10000L, durMs / 4));
                if (f2 != null && !isAllBlack(f2)) {
                    if (f != null && !f.isRecycled()) f.recycle();
                    f = f2;
                }
            }
            // 最后兑底：0 时刻（至少比没有强）
            if (f == null) f = frameAt(r, 0);
            return f == null ? null : scaleTo(f, TARGET_PX);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 取指定时刻的帧（API 27+ 用 scaled 重载省内存） */
    private static Bitmap frameAt(MediaMetadataRetriever r, long timeMs) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                Bitmap b = r.getScaledFrameAtTime(timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_PX, TARGET_PX);
                if (b != null) return b;
            }
            return r.getFrameAtTime(timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 判断帧是否几乎全黑（片头黑场的典型表现）。
     *
     * <p>采样而非常遍历：缩略图只需判断大概，抽样 200 个点足够快；
     * 逐像素在 4K 帧上会是几十毫秒级开销，而缩略图加载是列表批量并发。</p>
     */
    private static boolean isAllBlack(Bitmap b) {
        if (b == null || b.isRecycled()) return true;
        try {
            int w = b.getWidth(), h = b.getHeight();
            if (w <= 0 || h <= 0) return true;
            int stepX = Math.max(1, w / 20), stepY = Math.max(1, h / 10);
            long sum = 0;
            int n = 0;
            for (int y = 0; y < h; y += stepY) {
                for (int x = 0; x < w; x += stepX) {
                    int p = b.getPixel(x, y);
                    int lum = ((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
                    sum += lum;
                    n++;
                }
            }
            return n > 0 && (sum / n) < 12;   // 平均亮度 < 12/255 视为黑
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 解出 APK 的应用图标。
     *
     * <p>要点：Android 9+ 必须在 {@code getPackageArchiveInfo} 传
     * {@link android.content.pm.PackageManager#GET_ACTIVITIES}（非 0），
     * 否则返回的 ApplicationInfo 不含资源路径，{@code loadIcon()} 拿不到图；
     * 同时对 ApplicationInfo 显式设置 sourceDir / publicSourceDir。</p>
     */
    private static Bitmap decodeApk(String path) {
        Context app = HOLDER;
        if (app == null) return null;
        android.content.pm.PackageManager pm = app.getPackageManager();
        android.content.pm.PackageInfo pi = null;
        // 先试带 GET_ACTIVITIES（Android 9+ 加载图标必需），退回 0
        try {
            pi = pm.getPackageArchiveInfo(path,
                    android.content.pm.PackageManager.GET_ACTIVITIES);
        } catch (Throwable ignored) {
        }
        if (pi == null) {
            try {
                pi = pm.getPackageArchiveInfo(path, 0);
            } catch (Throwable ignored) {
            }
        }
        if (pi == null || pi.applicationInfo == null) return null;
        android.content.pm.ApplicationInfo ai = pi.applicationInfo;
        // 未安装包：必须显式给路径，loadIcon 才能定位资源
        ai.sourceDir = path;
        ai.publicSourceDir = path;

        android.graphics.drawable.Drawable d = null;
        try {
            d = ai.loadIcon(pm);
        } catch (Throwable ignored) {
        }
        if (d == null) {
            try {
                d = pm.getApplicationIcon(ai);
            } catch (Throwable ignored) {
            }
        }
        if (d == null) return null;

        // 按目标尺寸渲染（考虑 intrinsic 可能为 0 或负数）
        int s = Math.max(TARGET_PX, Math.max(d.getIntrinsicWidth(), d.getIntrinsicHeight()));
        if (s <= 0) s = TARGET_PX;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        d.setBounds(0, 0, s, s);
        d.draw(c);
        return scaleTo(b, TARGET_PX);
    }

    /**
     * 直接解析 APK 拿图标（不经 PackageManager）。
     *
     * <p>读取 AndroidManifest → android:icon 资源 id →
     * resources.arsc 查路径 → 从 zip 取 PNG/WebP 字节解码。
     * 当 {@link #decodeApk(String)} 失败（系统限制或包异常）时使用。</p>
     */
    private static Bitmap decodeApkDirect(String path) {
        try {
            com.aliya.hyfilemgr.apkicon.ApkIconParser.Result r =
                    com.aliya.hyfilemgr.apkicon.ApkIconParser.parse(path);
            if (r == null || r.data == null || r.data.length == 0) return null;
            android.graphics.BitmapFactory.Options o =
                    new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(r.data, 0, r.data.length, o);
            int sample = 1;
            int big = Math.max(o.outWidth, o.outHeight);
            while (big / (sample * 2) >= TARGET_PX) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(
                    r.data, 0, r.data.length, o);
            if (bm == null) return null;
            return scaleTo(bm, TARGET_PX);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解开 {@code zip://<包路径>/<条目>} 到 cacheDir/zipthumb 并返回本地路径。
     *
     * <p><b>为什么要单独放 zipthumb 目录</b>：ThumbLoader 自己的磁盘缓存是
     * 「缩略图」（已缩到 96px 的 jpg），而这里解出的是原图，不能混在一起。
     * 查看器的 zipview 也不共用——那里是给原图看的，尺寸大。
     * 缩略图用的副本只用一次，靠磁盘空间自然淘汰（不值得加 LRU）。</p>
     *
     * @return 本地路径；失败 null
     */
    private static String extractZipEntryForThumb(Context ctx, String virtPath) {
        try {
            String rest = virtPath.substring("zip://".length());
            String lower = rest.toLowerCase(Locale.ROOT);
            int cut = -1;
            for (int i = rest.length() - 1; i >= 0; i--) {
                if (rest.charAt(i) != '/') continue;
                String before = lower.substring(0, i);
                if (before.endsWith(".zip") || before.endsWith(".jar")
                        || before.endsWith(".apk") || before.endsWith(".epub")) {
                    cut = i;
                    break;
                }
            }
            if (cut <= 0) return null;
            String zipPath = rest.substring(0, cut);
            String inner = rest.substring(cut + 1);
            if (inner.isEmpty()) return null;

            File dir = new File(ctx.getCacheDir(), "zipthumb");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String base = inner.substring(inner.lastIndexOf('/') + 1);
            // 缓存名用 SHA-256 前 16 字符而非 hashCode：
            // 本项目自己的 diskFile 就专门注释过 hashCode 只有 32 位、
            // 按生日逢论约 7.7 万个 key 就有 50% 碰撞，碰撞后果是
            // 「显示别的图的缩略图」或写入时覆盖别人的缓存。
            // 同时把 inner 完整路径也编进去——不同子目录下的同名文件
            // （如 a/cover.jpg 与 b/cover.jpg）必须分开，否则缩略图串位。
            String key = sha256Hex(zipPath + "\u0000" + inner).substring(0, 16) + "_" + base;
            File out = new File(dir, key);
            if (out.isFile() && out.length() > 0) return out.getAbsolutePath();

            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(new File(zipPath))) {
                java.util.zip.ZipEntry ze = zf.getEntry(inner);
                if (ze == null || ze.isDirectory()) return null;
                try (java.io.InputStream in = zf.getInputStream(ze);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap scaleTo(Bitmap src, int max) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int big = Math.max(w, h);
        if (big <= max) return src;
        float f = (float) max / big;
        Bitmap out = Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)), true);
        if (out != src) src.recycle();
        return out;
    }

    private static int sampleSize(int w, int h, int target) {
        int s = 1;
        while (w / s > target * 2 && h / s > target * 2) s *= 2;
        return s;
    }

    // ── 磁盘缓存 ──

    /**
     * 磁盘缓存文件名。
     *
     * <p><b>不能用 String.hashCode()</b>：它只有 32 位，按生日悖论
     * 约 7.7 万个 key 就有 50% 概率碰撞。碰撞后果是<b>显示别人的缩略图</b>
     * 或 writeDisk 覆盖另一个文件的缓存 —— 用户在大目录里会随机看到错图。</p>
     *
     * <p>改用 SHA-256 取前 128 位（32 个 hex 字符）。碰撞概率降到
     * 2^-128 量级，工程上视为不可能。</p>
     */
    private static File diskFile(Context ctx, String key) {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        if (!dir.exists() && !dir.mkdirs()) return null;
        String fn = sha256Hex(key).substring(0, 32) + ".jpg";
        return new File(dir, fn);
    }

    /** SHA-256 十六进制（小写）。失败时回退到 hashCode，保证不崩。 */
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
            // 理论上不会发生（Android 必有 SHA-256）；退路仍是 hashCode
            return Integer.toHexString(s.hashCode());
        }
    }

    private static Bitmap readDisk(Context ctx, String key) {
        File f = diskFile(ctx, key);
        if (f == null || !f.isFile() || f.length() == 0) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b != null) MEM.put(key, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeDisk(Context ctx, String key, Bitmap b) {
        File f = diskFile(ctx, key);
        if (f == null) return;
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Throwable ignored) {
        }
        MEM.put(key, b);
        trimDisk(ctx);
    }

    /** 磁盘缓存超量时按最后修改时间清掉最旧的一批（按字节统计） */
    private static void trimDisk(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        File[] all = dir.listFiles();
        if (all == null || all.length == 0) return;
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= DISK_MAX_BYTES) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long target = DISK_MAX_BYTES * 8 / 10;   // 一次清到 80%，避免频繁 trim
        for (File f : all) {
            if (total <= target) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    // APK 解码需要 Context，用静态持有（Application 级，不会泄漏）
    private static volatile Context HOLDER;

    /** 首次使用前调用一次（传 Application context） */
    public static void init(Context app) {
        HOLDER = app;
    }
}
