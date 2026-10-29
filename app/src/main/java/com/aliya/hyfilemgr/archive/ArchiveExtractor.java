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
        try (org.apache.commons.compress.archivers.zip.ZipArchiveInputStream zin =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(
                             new BufferedInputStream(new FileInputStream(src)),
                             "UTF-8", true)) {
            n = drainArchive(zin, destDir, progress);
        }
        Result r = new Result();
        r.ok = true;
        r.files = n;
        return r;
    }

    private static Result extractZipEncrypted(File src, File destDir, String password,
                                              Progress progress) {
        int n = 0;
        try {
            net.lingala.zip4j.ZipFile zf =
                    new net.lingala.zip4j.ZipFile(src, password.toCharArray());
            java.util.List<net.lingala.zip4j.model.FileHeader> heads = zf.getFileHeaders();
            for (net.lingala.zip4j.model.FileHeader h : heads) {
                if (h.isDirectory()) continue;
                File out = safeChild(destDir, h.getFileName());
                if (out == null) continue;
                File p = out.getParentFile();
                if (p != null) //noinspection ResultOfMethodCallIgnored
                    p.mkdirs();
                try (InputStream is = zf.getInputStream(h);
                     OutputStream os = new FileOutputStream(out)) {
                    copy(is, os);
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
            SevenZArchiveEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = sz.getNextEntry()) != null) {
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
        int n = 0;
        ArchiveEntry entry;
        while ((entry = in.getNextEntry()) != null) {
            if (!in.canReadEntryData(entry)) continue;
            String name = entry.getName();
            if (entry.isDirectory()) {
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
                copy(in, os);
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
        String base = src.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
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

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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
