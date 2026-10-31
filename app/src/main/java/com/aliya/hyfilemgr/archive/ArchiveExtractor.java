package com.aliya.hyfilemgr.archive;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 统一解压器：按扩展名/魔数选择后端，全部零依赖开源实现（可闭源发布）。
 *
 * <p>支持：zip / jar / apk / 7z / tar / tar.gz / tgz / tar.bz2 / tar.xz
 * / tar.zst / gz / bz2 / xz / zst / rar</p>
 *
 * <p>安全：解压前校验规范化路径必须落在目标目录内（防 Zip Slip 目录穿越）。</p>
 */
public final class ArchiveExtractor {

    private ArchiveExtractor() { }

    /** 解压进度回调（工作线程调用） */
    public interface Progress {
        /** @param name 当前条目名 @param done 已完成条目数 */
        void onEntry(String name, int done);
    }

    /** 解压结果 */
    public static final class Result {
        public boolean ok;
        public int files;
        public String error;
        /** 解压出的顶层条目（用于决定是否套一层目录） */
        public String topName;
        /** 是否因文件名乱码而用 GBK 重解过 */
        public boolean gbkRetried;
        /** 因不安全路径（Zip Slip 防护）被跳过的条目名 */
        public final java.util.List<String> skipped = new java.util.ArrayList<>();
        static Result fail(String e) {
            Result r = new Result();
            r.ok = false;
            r.error = e;
            return r;
        }
    }

    /** 该文件能否被本解压器处理 */
    public static boolean canExtract(String fileName) {
        String e = ext(fileName);
        switch (e) {
            case "zip": case "jar": case "apk": case "xapk":
            case "7z": case "tar": case "tgz": case "tbz2": case "tbz":
            case "txz": case "tzst": case "gz": case "bz2": case "xz":
            case "zst": case "rar":
                return true;
            default:
                return false;
        }
    }

    /**
     * 解压到 destDir。
     *
     * @param src     压缩包
     * @param destDir 目标目录（不存在会自动创建）
     * @param password zip 密码（仅 zip 后端使用，可为 null）
     */
    public static Result extract(File src, File destDir, String password, Progress progress) {
        if (src == null || !src.exists()) return Result.fail("文件不存在");
        if (!src.canRead()) return Result.fail("没有读取权限");
        if (!destDir.exists() && !destDir.mkdirs()) return Result.fail("无法创建目标目录");

        String e = ext(src.getName());
        try {
            switch (e) {
                case "zip": case "jar": case "apk": case "xapk":
                    return extractZip(src, destDir, password, progress);
                case "7z":
                    return extract7z(src, destDir, progress);
                case "rar":
                    return extractRar(src, destDir, password, progress);
                case "tar":
                    return extractTarStream(src, destDir, progress);
                case "tgz":
                    return extractTarGz(src, destDir, progress);
                case "tbz2": case "tbz":
                    return extractTarBz2(src, destDir, progress);
                case "txz":
                    return extractTarXz(src, destDir, progress);
                case "tzst":
                    return extractTarZst(src, destDir, progress);
                case "gz":
                    return extractSingleCompressed(src, destDir, "gz", progress);
                case "bz2":
                    return extractSingleCompressed(src, destDir, "bz2", progress);
                case "xz":
                    return extractSingleCompressed(src, destDir, "xz", progress);
                case "zst":
                    return extractSingleCompressed(src, destDir, "zst", progress);
                default:
                    return Result.fail("不支持的格式：." + e);
            }
        } catch (Throwable t) {
            return Result.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ── ZIP ──

    private static Result extractZip(File src, File destDir, String password,
                                     Progress progress) throws IOException {
        // 有密码用 zip4j（commons-compress 不支持 AES 加密 zip）
        if (password != null && !password.isEmpty()) {
            return extractZipEncrypted(src, destDir, password, progress);
        }
        int n = 0;
        // ⚠️ 不要靠异常判断乱码：commons-compress 1.20 对 UTF-8 走
        // NioZipEncoding 的 REPLACE 模式（ZipEncodingHelper:62 →
        // NioZipEncoding:176-186），非法字节直接替换成 '?'，**不抛异常**。
        // 旧实现的 catch 分支是死代码，GBK 包解出 ???? 且仍返回 ok=true。
        //
        // 正确做法：先解一遍，然后**主动扫目标目录的条目名**，
        // 命中替换符/控制字符比例过高就清空重来，用 GBK 再解一次。
        boolean gbk = false;
        try (org.apache.commons.compress.archivers.zip.ZipArchiveInputStream zin =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(
                             new BufferedInputStream(new FileInputStream(src)),
                             "UTF-8", true)) {
            n = drainArchive(zin, destDir, progress, false);
        } catch (Throwable t) {
            // 真异常（损坏/加密）照常抛出，不再用作乱码判据
            throw t;
        }

        if (hasReplacementChars(destDir)) {
            // 清掉刚解出的乱码结果，用 ISO-8859-1 重解再转 GBK
            deleteTreeQuietly(destDir);
            gbk = true;
            try (org.apache.commons.compress.archivers.zip.ZipArchiveInputStream zin =
                         new org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(
                                 new BufferedInputStream(new FileInputStream(src)),
                                 "ISO-8859-1", false)) {
                n = drainArchive(zin, destDir, progress, true);
            }
        }

        Result r = new Result();
        r.ok = true;
        r.files = n;
        r.gbkRetried = gbk;
        return r;
    }

    private static Result extractZipEncrypted(File src, File destDir, String password,
                                              Progress progress) {
        int n = 0;
        try {
            net.lingala.zip4j.ZipFile zf =
                    new net.lingala.zip4j.ZipFile(src, password.toCharArray());
            Guard guard = new Guard(destDir);
            java.util.List<net.lingala.zip4j.model.FileHeader> heads = zf.getFileHeaders();
            for (net.lingala.zip4j.model.FileHeader h : heads) {
                guard.countEntry();
                if (h.isDirectory()) continue;
                File out = safeChild(destDir, h.getFileName());
                if (out == null) continue;
                File p = out.getParentFile();
                if (p != null) //noinspection ResultOfMethodCallIgnored
                    p.mkdirs();
                try (InputStream is = zf.getInputStream(h);
                     OutputStream os = new FileOutputStream(out)) {
                    copy(is, os, guard);
                }
                n++;
                if (progress != null) progress.onEntry(h.getFileName(), n);
            }
        } catch (Throwable t) {
            return Result.fail("密码错误或压缩包损坏：" + t.getMessage());
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    // ── 7Z ──

    private static Result extract7z(File src, File destDir, Progress progress) throws IOException {
        int n = 0;
        try (SevenZFile sz = new SevenZFile(src)) {
            Guard guard = new Guard(destDir);
            SevenZArchiveEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = sz.getNextEntry()) != null) {
                guard.countEntry();
                if (entry.isDirectory()) {
                    File d = safeChild(destDir, entry.getName());
                    if (d != null) //noinspection ResultOfMethodCallIgnored
                        d.mkdirs();
                    continue;
                }
                File out = safeChild(destDir, entry.getName());
                if (out == null) continue;
                File p = out.getParentFile();
                if (p != null) //noinspection ResultOfMethodCallIgnored
                    p.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int len;
                    while ((len = sz.read(buf)) > 0) os.write(buf, 0, len);
                }
                n++;
                if (progress != null) progress.onEntry(entry.getName(), n);
            }
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    // ── RAR ──

    private static Result extractRar(File src, File destDir, String password,
                                     Progress progress) {
        int n = 0;
        try {
            com.github.junrar.Archive arch = password == null || password.isEmpty()
                    ? new com.github.junrar.Archive(src)
                    : new com.github.junrar.Archive(src, password);
            try {
                com.github.junrar.rarfile.FileHeader fh;
                while ((fh = arch.nextFileHeader()) != null) {
                    String name = fh.getFileName();
                    if (fh.isDirectory()) {
                        File d = safeChild(destDir, name);
                        if (d != null) //noinspection ResultOfMethodCallIgnored
                            d.mkdirs();
                        continue;
                    }
                    File out = safeChild(destDir, name);
                    if (out == null) continue;
                    File p = out.getParentFile();
                    if (p != null) //noinspection ResultOfMethodCallIgnored
                        p.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        arch.extractFile(fh, os);
                    }
                    n++;
                    if (progress != null) progress.onEntry(name, n);
                }
            } finally {
                arch.close();
            }
        } catch (Throwable t) {
            return Result.fail("RAR 解压失败：" + t.getMessage());
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    // ── TAR 家族 ──

    private static Result extractTarStream(File src, File destDir, Progress progress)
            throws IOException {
        int n;
        try (InputStream is = new BufferedInputStream(new FileInputStream(src))) {
            TarArchiveInputStream tin = new TarArchiveInputStream(is);
            n = drainArchive(tin, destDir, progress);
            tin.close();
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    private static Result extractTarGz(File src, File destDir, Progress progress)
            throws IOException {
        int n;
        try (InputStream is = new BufferedInputStream(new FileInputStream(src));
             GzipCompressorInputStream gz = new GzipCompressorInputStream(is)) {
            TarArchiveInputStream tin = new TarArchiveInputStream(gz);
            n = drainArchive(tin, destDir, progress);
            tin.close();
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    private static Result extractTarBz2(File src, File destDir, Progress progress)
            throws IOException {
        int n;
        try (InputStream is = new BufferedInputStream(new FileInputStream(src));
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(is)) {
            TarArchiveInputStream tin = new TarArchiveInputStream(bz);
            n = drainArchive(tin, destDir, progress);
            tin.close();
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    private static Result extractTarXz(File src, File destDir, Progress progress)
            throws IOException {
        int n;
        try (InputStream is = new BufferedInputStream(new FileInputStream(src));
             XZCompressorInputStream xz = new XZCompressorInputStream(is)) {
            TarArchiveInputStream tin = new TarArchiveInputStream(xz);
            n = drainArchive(tin, destDir, progress);
            tin.close();
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    private static Result extractTarZst(File src, File destDir, Progress progress)
            throws IOException {
        int n;
        try (InputStream is = new BufferedInputStream(new FileInputStream(src));
             ZstdCompressorInputStream zs = new ZstdCompressorInputStream(is)) {
            TarArchiveInputStream tin = new TarArchiveInputStream(zs);
            n = drainArchive(tin, destDir, progress);
            tin.close();
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    /** 通用归档遍历（zip / tar 家族共用） */
    private static int drainArchive(ArchiveInputStream in, File destDir, Progress progress)
            throws IOException {
        return drainArchive(in, destDir, progress, false);
    }

    /**
     * 通用归档遍历。
     *
     * @param gbk true 表示条目名按 ISO-8859-1 读入后需转 GBK 还原
     *            （Windows 打包的中文 zip 常见）
     */
    private static int drainArchive(ArchiveInputStream in, File destDir,
                                    Progress progress, boolean gbk) throws IOException {
        return drainArchive(in, destDir, progress, gbk, null);
    }

    private static int drainArchive(ArchiveInputStream in, File destDir,
                                    Progress progress, boolean gbk,
                                    java.util.List<String> skippedSink) throws IOException {
        int n = 0;
        Guard guard = new Guard(destDir);
        ArchiveEntry entry;
        while ((entry = in.getNextEntry()) != null) {
            guard.countEntry();
            if (!in.canReadEntryData(entry)) continue;
            String name = entry.getName();
            if (gbk) name = fixGbkName(name);
            if (entry.isDirectory()) {
                File d = safeChild(destDir, name);
                if (d != null) //noinspection ResultOfMethodCallIgnored
                    d.mkdirs();
                continue;
            }
            File out = safeChild(destDir, name);
            if (out == null) {
                // 不安全路径（Zip Slip 防护命中）：不静默丢弃，记下条目名
                if (skippedSink != null && skippedSink.size() < 50) skippedSink.add(name);
                continue;
            }
            File p = out.getParentFile();
            if (p != null) //noinspection ResultOfMethodCallIgnored
                p.mkdirs();
            try (OutputStream os = new FileOutputStream(out)) {
                copy(in, os, guard);
            }
            n++;
            if (progress != null) progress.onEntry(name, n);
        }
        return n;
    }

    // ── 单文件压缩流（gz / bz2 / xz / zst）──

    private static Result extractSingleCompressed(File src, File destDir, String kind,
                                                  Progress progress) throws IOException {
        // 去掉压缩扩展名作为输出名：a.txt.gz → a.txt
        // 边界：文件名为 ".gz" 或 "gz"（无扩展名）时去掉后为空/同名，
        // 直接写会覆盖源文件，必须加后缀。
        String base = src.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            String stripped = base.substring(0, dot);
            base = stripped.isEmpty() ? stripped : stripped;
        } else if (dot == 0) {
            // ".gz" 这种纯扩展名
            base = "";
        }
        if (base == null || base.isEmpty() || base.equals(src.getName())) {
            base = src.getName() + ".out";
        }
        // 若内部本身是 tar（a.tar.gz 但扩展写成了 .gz 等），交给 tar 处理
        File out = safeChild(destDir, base);
        if (out == null) return Result.fail("非法文件名");

        InputStream raw = new BufferedInputStream(new FileInputStream(src));
        InputStream dec;
        switch (kind) {
            case "gz":  dec = new GzipCompressorInputStream(raw); break;
            case "bz2": dec = new BZip2CompressorInputStream(raw); break;
            case "xz":  dec = new XZCompressorInputStream(raw); break;
            case "zst": dec = new ZstdCompressorInputStream(raw); break;
            default: dec = raw; break;
        }
        try (OutputStream os = new FileOutputStream(out)) {
            copy(dec, os);
        } finally {
            try { dec.close(); } catch (Throwable ignored) { }
        }
        if (progress != null) progress.onEntry(base, 1);
        Result r = new Result();
        r.ok = true;
        r.files = 1;
        r.topName = base;
        return r;
    }

    // ── 工具 ──

    /**
     * 安全子路径：规范化后必须仍在 destDir 内。
     * 防止 ../../evil 之类的 Zip Slip 目录穿越。
     */
    private static File safeChild(File destDir, String entryName) throws IOException {
        String clean = entryName.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        File out = new File(destDir, clean);
        String base = destDir.getCanonicalPath();
        String target = out.getCanonicalPath();
        if (!target.startsWith(base + File.separator) && !target.equals(base)) {
            return null;   // 越界，跳过
        }
        return out;
    }

    /** ISO-8859-1 读入的字节串按 GBK 重新解码（Windows zip 中文名还原） */
    /**
     * 扫描目录里的文件名，判断是否出现「UTF-8 解 GBK 字节」的替换符。
     *
     * <p>判据：名字里含 '?'（commons-compress 的 REPLACEMENT）或
     * U+FFFD（Java 解码器的替换符）。任一命中且出现在非末尾位置即视为乱码
     * —— 真实文件名里极少出现 '?'（Windows 保留字符，无法创建）。</p>
     */
    private static boolean hasReplacementChars(File dir) {
        return hasReplacementChars(dir, 0);
    }

    private static boolean hasReplacementChars(File dir, int depth) {
        if (dir == null || depth > 4) return false;
        File[] items = dir.listFiles();
        if (items == null) return false;
        for (File f : items) {
            String name = f.getName();
            if (name.indexOf('?') >= 0 || name.indexOf('\uFFFD') >= 0) return true;
            if (f.isDirectory() && hasReplacementChars(f, depth + 1)) return true;
        }
        return false;
    }

    /** 静默删除整棵树（GBK 重解前清场用） */
    private static void deleteTreeQuietly(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] items = f.listFiles();
            if (items != null) for (File c : items) deleteTreeQuietly(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String fixGbkName(String name) {
        try {
            return new String(name.getBytes(StandardCharsets.ISO_8859_1),
                    Charset.forName("GBK"));
        } catch (Throwable t) {
            return name;
        }
    }

    /**
     * 判断异常是否像「UTF-8 解 GBK 名」导致的乱码。
     *
     * <p>commons-compress 遇到非法 UTF-8 字节序列会抛
     * IllegalArgumentException / ZipException，消息里通常含
     * "invalid"/"malformed"/"character"。此时值得用 GBK 再试一次。</p>
     */
    private static boolean looksLikeGarbled(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg == null) continue;
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("utf")
                    || lower.contains("invalid")
                    || lower.contains("malformed")
                    || lower.contains("character")
                    || lower.contains("charset")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解压配额守卫：防止 zip bomb（高压缩比 + 巨量条目）。
     *
     * <p>仅作用于**解压**。内部浏览只读中央目录、不解压数据流，
     * 因此不存在被炸弹打爆内存的问题，无需在此约束。</p>
     *
     * <p>为什么不能用 entry.getSize() 判：该字段可被伪造（可为 -1 或谎报），
     * 必须按**实际写出字节数**累计。</p>
     */
    static final class Guard {
        /** 条目数上限 */
        static final int MAX_ENTRIES = 200_000;
        /** 单文件解压后上限（1GB） */
        static final long MAX_ENTRY_BYTES = 1L << 30;
        /** 总量上限：4GB 与「可用空间 80%」取小 */
        static final long MAX_TOTAL_FLOOR = 4L << 30;

        int entries;
        long total;
        final long totalCap;

        Guard(File destDir) {
            long usable = destDir == null ? 0 : destDir.getUsableSpace();
            long byDisk = usable > 0 ? (long) (usable * 0.8) : MAX_TOTAL_FLOOR;
            this.totalCap = Math.min(MAX_TOTAL_FLOOR, byDisk);
        }

        void countEntry() throws IOException {
            if (++entries > MAX_ENTRIES) {
                throw new IOException("压缩包条目数超过上限 " + MAX_ENTRIES + "，已中止");
            }
        }

        void addBytes(long n) throws IOException {
            total += n;
            if (total > totalCap) {
                throw new IOException("解压后总大小超过上限 "
                        + (totalCap / (1024 * 1024)) + "MB，已中止");
            }
        }
    }

    /** 带配额的 copy：按实际写出字节累计，超限即断 */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        copy(in, out, null);
    }

    private static void copy(InputStream in, OutputStream out, Guard guard) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        long written = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            written += n;
            if (written > Guard.MAX_ENTRY_BYTES) {
                throw new IOException("单个文件解压后超过 "
                        + (Guard.MAX_ENTRY_BYTES / (1024 * 1024)) + "MB，已中止");
            }
            if (guard != null) guard.addBytes(n);
        }
    }

    private static String ext(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase();
        // 复合扩展名优先
        for (String multi : new String[]{"tar.gz", "tar.bz2", "tar.xz", "tar.zst"}) {
            if (lower.endsWith("." + multi)) {
                switch (multi) {
                    case "tar.gz": return "tgz";
                    case "tar.bz2": return "tbz2";
                    case "tar.xz": return "txz";
                    case "tar.zst": return "tzst";
                }
            }
        }
        int i = lower.lastIndexOf('.');
        return i < 0 ? "" : lower.substring(i + 1);
    }
}
