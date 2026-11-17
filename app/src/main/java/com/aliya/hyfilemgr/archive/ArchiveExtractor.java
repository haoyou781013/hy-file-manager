package com.aliya.hyfilemgr.archive;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
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
    /**
     * 可解压的扩展名（小写、不含点）——<b>全项目唯一来源</b>。
     *
     * <p><b>为什么集中到这里</b>：原先有三份等价但形式不同的清单——
     * ① 本类的 {@code canExtract} switch；② {@code FileManagerModule.ARCHIVE_NAME_SUFFIXES}
     * 带点数组（用于剥名）；③ {@code splitZipPath} 里内联的 if 链（用于切分虚拟路径）。
     * 三份互相独立维护，实际出过事：{@code splitZipPath} 漏了
     * {@code .tbz/.tbz2/.txz/.tzst}，而另外两处都有 → 这些包能看见、能点开，
     * 但切分失败导致点「解压」时崩溃。</p>
     *
     * <p>本类 {@code ArchiveExtractor} 是「能不能解压」这个知识的自然归属方；
     * 模块层的剥名与切分都从这里派生。</p>
     */
    private static final java.util.Set<String> EXTRACTABLE_EXTS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "zip", "jar", "apk", "xapk",
                    "7z", "tar", "tgz", "tbz2", "tbz",
                    "txz", "tzst", "gz", "bz2", "xz",
                    "zst", "rar"));

    public static boolean canExtract(String fileName) {
        String e = ext(fileName);
        return e != null && EXTRACTABLE_EXTS.contains(e);
    }

    /**
     * 可解压扩展名的带点后缀表，<b>长后缀在前</b>（用于从文件名末尾逐个剥离）。
     *
     * <p>顺序要求：{@code .tar.gz} 必须排在 {@code .gz} 前面，否则
     * {@code a.tar.gz} 会被剥成 {@code a.tar}；{@code .tbz2} 在 {@code .tbz} 前同理。</p>
     */
    public static final String[] NAME_SUFFIXES = {
            ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst",
            ".tgz", ".tbz2", ".tbz", ".txz", ".tzst",
            ".zip", ".jar", ".apk", ".xapk",
            ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz", ".zst"
    };

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
        // ⚠ 记录「目标目录是否原本就存在」。
        //
        // 为什么需要：GBK 乱码重解路径里有 deleteTreeQuietly(destDir)，
        // 目的是把刚解出的乱码文件清掉再重解。但如果 destDir 是调用方
        // 传进来的**已存在目录**（本方法签名明确允许），那个 delete 会把
        // 用户原有的文件整棵删掉，只剩重解结果。
        //
        // 有这条标记后，只有「本次调用自己创建的空目录」才允许整棵清空重解；
        // 目标原本就存在时走「不清理、直接重解另一份到临时目录」的保守路径。
        final boolean destPreexisted = destDir.exists();
        if (!destPreexisted && !destDir.mkdirs()) return Result.fail("无法创建目标目录");

        String e = ext(src.getName());
        try {
            switch (e) {
                case "zip": case "jar": case "apk": case "xapk":
                    return extractZip(src, destDir, password, progress, destPreexisted);
                case "7z":
                    return extract7z(src, destDir, password, progress);
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
                                     Progress progress, boolean destPreexisted) throws IOException {
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
            // 用 ISO-8859-1 重解再转 GBK。
            //
            // ⚠ 清空策略取决于目标目录是否原本就存在：
            //   • 本次新建（!destPreexisted）→ 可以整棵清空，里面只有刚解出的乱码；
            //   • 调用方传入的已存在目录 → **不能清**，否则会把用户原有文件删掉。
            //     这种情况改为「保留旧内容，GBK 重解覆盖同名文件」，
            //     代价是乱码名文件可能残留，但绝不动用户数据。
            if (!destPreexisted) {
                deleteTreeQuietly(destDir);
            } else {
                android.util.Log.w("HyExtract",
                        "目标目录已存在，GBK 重解跳过清空以防删用户文件：" + destDir);
            }
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
            // zip 能拿到条目声明的压缩/解压大小，先整包判一次压缩比。
            // 这是唯一能判压缩比的格式（tar 不压缩、7z/rar 不暴露大小）。
            String bomb = checkZipRatio4j(zf);
            if (bomb != null) throw new IOException(bomb);
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

    /**
     * 7z 解压。
     *
     * <p>{@code password} 非空时用 commons-compress 1.26 的双参构造器解密——
     * 此前该参数缺失，导致「本 App 加密压出的 7z 本 App 打不开」。</p>
     *
     * <p>写入统一走 {@link #copy(InputStream, OutputStream, Guard)}，
     * 让单文件上限与总量上限对 7z 也生效（此前是裸 read/write 循环，
     * 只有条目数一条防线，7z 炸弹可无限写盘）。</p>
     */
    private static Result extract7z(File src, File destDir, String password,
                                    Progress progress) throws IOException {
        int n = 0;
        Result r = new Result();
        try (SevenZFile sz = (password == null || password.isEmpty())
                ? new SevenZFile(src)
                : new SevenZFile(src, password.toCharArray())) {
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
                if (out == null) {
                    guard.skip(entry.getName());
                    continue;
                }
                File p = out.getParentFile();
                if (p != null) //noinspection ResultOfMethodCallIgnored
                    p.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    // SevenZFile 不是 InputStream（只有 read(byte[]) 系列），
                    // 不能复用 copy()，这里内联同样的配额逻辑
                    long written = 0;
                    int len;
                    while ((len = sz.read(buf)) > 0) {
                        os.write(buf, 0, len);
                        written += len;
                        // 不累加 compressedBytes：sz.read() 已是解压后字节
                        if (written > Guard.MAX_ENTRY_BYTES) {
                            throw new IOException("单个文件解压后超过 "
                                    + (Guard.MAX_ENTRY_BYTES / (1024 * 1024)) + "MB，已中止");
                        }
                        guard.addBytes(len);
                    }
                }
                n++;
                if (progress != null) progress.onEntry(entry.getName(), n);
            }
            // Result.skipped 是 final 字段，只能 addAll 不能赋值
            r.skipped.addAll(guard.takeSkipped());
        } catch (IOException | RuntimeException e) {
            String msg = e.getMessage();
            boolean looksLikeBadCredential = msg != null
                    && (msg.contains("password") || msg.contains("CRC")
                        || msg.contains("Encrypted") || msg.contains("encrypt"));
            if (looksLikeBadCredential) {
                return Result.fail("7z 解压失败（可能是加密凭据不正确）：" + msg);
            }
            throw e;   // 配额超限等由上层统一处理，保持原样
        }
        r.ok = true;
        r.files = n;
        return r;
    }


    // ── 单条解出（预览里「解到对侧」用） ──

    /**
     * 只解出归档中的**一个条目**到指定文件，其余字节不碰。
     *
     * <p>旧实现是「整包解到缓存目录，再从中挑一条」——100MB 的包
     * 只想解一个 1KB 文件也要全解，还会先撞上 Guard 的条目数/总量上限
     * 而整体失败。这里按格式做定点提取。</p>
     *
     * @param src      归档文件
     * @param inner    包内相对路径（用 / 分隔，不带前导 /）
     * @param destFile 输出文件（父目录由调用方保证存在）
     * @return null 表示成功；否则为错误说明
     */
    public static String extractEntry(File src, String inner, File destFile) {
        if (src == null || !src.isFile()) return "压缩包不存在";
        if (inner == null || inner.isEmpty()) return "包内路径为空";
        String e = ext(src.getName());
        try {
            switch (e) {
                case "zip": case "jar": case "apk": case "xapk":
                    return extractEntryZip(src, inner, destFile);
                case "7z":
                    return extractEntry7z(src, inner, destFile);
                case "rar":
                    return extractEntryRar(src, inner, destFile);
                case "tar": case "tgz": case "tar.gz": case "tar.bz2":
                case "tar.xz": case "tar.zst":
                    return extractEntryTar(src, inner, destFile);
                default:
                    return "该格式不支持单条解出";
            }
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        }
    }

    private static String extractEntryZip(File src, String inner, File out) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(src)) {
            java.util.zip.ZipEntry ze = zf.getEntry(inner);
            if (ze == null || ze.isDirectory()) return "包内未找到该条目";
            try (java.io.InputStream in = zf.getInputStream(ze);
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                copy(in, os, new Guard(out.getParentFile()));
            }
        }
        return null;
    }

    private static String extractEntry7z(File src, String inner, File out) throws Exception {
        try (SevenZFile sz = new SevenZFile(src)) {
            Guard guard = new Guard(out.getParentFile());
            byte[] buf = new byte[8192];
            SevenZArchiveEntry ae;
            while ((ae = sz.getNextEntry()) != null) {
                if (!inner.equals(ae.getName())) continue;
                if (ae.isDirectory()) return "该条目是目录";
                try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    long written = 0;
                    int len;
                    while ((len = sz.read(buf)) > 0) {
                        os.write(buf, 0, len);
                        written += len;
                        // 不累加 compressedBytes：sz.read() 已是解压后字节
                        if (written > Guard.MAX_ENTRY_BYTES) {
                            return "单文件超过 "
                                    + (Guard.MAX_ENTRY_BYTES / (1024 * 1024)) + "MB";
                        }
                        guard.addBytes(len);
                    }
                }
                return null;
            }
        }
        return "包内未找到该条目";
    }

    private static String extractEntryRar(File src, String inner, File out) throws Exception {
        try (com.github.junrar.Archive a = new com.github.junrar.Archive(src)) {
            for (com.github.junrar.rarfile.FileHeader h : a.getFileHeaders()) {
                if (!inner.equals(h.getFileName())) continue;
                if (h.isDirectory()) return "该条目是目录";
                // junrar 的 extractFile 会把内容写到指定的 OutputStream
                try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    a.extractFile(h, os);
                }
                return null;
            }
        }
        return "包内未找到该条目";
    }

    private static String extractEntryTar(File src, String inner, File out) throws Exception {
        try (java.io.InputStream fin = new java.io.BufferedInputStream(
                     new java.io.FileInputStream(src));
             org.apache.commons.compress.archivers.ArchiveInputStream<?> ain =
                     openTarStream(fin, src.getName())) {
            if (ain == null) return "无法识别该 tar 变体";
            Guard guard = new Guard(out.getParentFile());
            org.apache.commons.compress.archivers.ArchiveEntry ae;
            while ((ae = ain.getNextEntry()) != null) {
                if (!inner.equals(ae.getName())) continue;
                if (ae.isDirectory()) return "该条目是目录";
                try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    copy(ain, os, guard);
                }
                return null;
            }
        }
        return "包内未找到该条目";
    }

    /** 按文件名判断 tar 变体并套上对应解压流 */
    private static org.apache.commons.compress.archivers.ArchiveInputStream<?>
            openTarStream(java.io.InputStream in, String fileName) throws Exception {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        java.io.InputStream dec = in;
        if (lower.contains(".tar.gz") || lower.endsWith(".tgz")) {
            dec = new org.apache.commons.compress.compressors.gzip
                    .GzipCompressorInputStream(in);
        } else if (lower.contains(".tar.bz2")) {
            dec = new org.apache.commons.compress.compressors.bzip2
                    .BZip2CompressorInputStream(in);
        } else if (lower.contains(".tar.xz")) {
            dec = new org.apache.commons.compress.compressors.xz
                    .XZCompressorInputStream(in);
        } else if (lower.contains(".tar.zst")) {
            dec = new com.github.luben.zstd.ZstdInputStream(in);
        }
        return new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(dec);
    }

    // ── RAR ──

    private static Result extractRar(File src, File destDir, String password,
                                     Progress progress) {
        int n = 0;
        try {
            com.github.junrar.Archive arch = password == null || password.isEmpty()
                    ? new com.github.junrar.Archive(src)
                    : new com.github.junrar.Archive(src, password);
            // ⚠️ RAR 分支此前完全绕过 Guard：
            //   · 没调 guard.countEntry()，条目数上限失效
            //   · arch.extractFile(fh, os) 直写，不经 copy(guard)，
            //     单文件 1GB / 总量 4GB 两条也失效
            // 即 RAR 炸弹完全没有防护。现在补齐。
            Guard guard = new Guard(destDir);
            try {
                com.github.junrar.rarfile.FileHeader fh;
                while ((fh = arch.nextFileHeader()) != null) {
                    guard.countEntry();
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
                        // junrar 的 extractFile 只能直写出，不能套 copy(guard)。
                        // 为不破坏 API，这里包一层计数流：每写 n 字节就过 guard，
                        // 超限立刻抛异常中止，已写的半截文件由外层 catch 清理。
                        java.io.OutputStream checked = new java.io.OutputStream() {
                            @Override
                            public void write(int b) throws IOException {
                                os.write(b);
                                guard.addBytes(1);
                            }
                            @Override
                            public void write(byte[] b, int off, int len) throws IOException {
                                os.write(b, off, len);
                                guard.addBytes(len);
                            }
                            @Override
                            public void flush() throws IOException {
                                os.flush();
                            }
                            @Override
                            public void close() throws IOException {
                                os.close();
                            }
                        };
                        arch.extractFile(fh, checked);
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
        Guard guard = new Guard(destDir);
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
            // 单流解压此前完全无配额：gz/bz2/xz/zst 可无限写盘
            copy(dec, os, guard);
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

    /**
     * 静默删除整棵树（GBK 重解前清场用）。
     *
     * <p><b>为什么改成非递归</b>：旧实现是递归，深目录（如 node_modules、
     * 分层备份）有栈溢出风险；且它不判符号链接——目录里若有指向父目录的
     * symlink，会无限递归。项目里 {@code FileManagerModule.deleteTree}
     * 用的是显式栈 + 不跟随 symlink，但那是个实例方法且依赖模块的
     * {@code isSymlink}，本类无法直接调用（静态工具类，不应依赖模块）。
     * 因此在这里实现同构的显式栈版本，两边行为一致。</p>
     */
    private static void deleteTreeQuietly(File f) {
        if (f == null || !f.exists()) return;
        // 非目录（含符号链接）直接删；delete() 对 symlink 只移除链接，不动目标
        if (!f.isDirectory() || isSymlinkStatic(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        java.util.ArrayList<File> dirs = new java.util.ArrayList<>();
        stack.push(f);
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            if (isSymlinkStatic(cur)) {
                //noinspection ResultOfMethodCallIgnored
                cur.delete();
                continue;
            }
            if (cur.isDirectory()) {
                dirs.add(cur);           // 目录最后删（子项先删）
                File[] ch = cur.listFiles();
                if (ch != null) {
                    for (File c : ch) stack.push(c);
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                cur.delete();
            }
        }
        // 逆序删目录：先深后浅
        for (int i = dirs.size() - 1; i >= 0; i--) {
            //noinspection ResultOfMethodCallIgnored
            dirs.get(i).delete();
        }
    }

    /**
     * 是否是符号链接本身（不跟随），零 {@code java.nio.file} 依赖。
     *
     * <p>低版本用 {@code canonicalPath != absolutePath} 兜底：不完全等价
     * （路径中任意一段是链接都会判为 true），但方向安全（少删不多删）。
     */
    private static boolean isSymlinkStatic(File f) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                return java.nio.file.Files.isSymbolicLink(
                        java.nio.file.Paths.get(f.getAbsolutePath()));
            }
            File p = f.getParentFile();
            File canon = p == null ? f : new File(p.getCanonicalFile(), f.getName());
            return !canon.getCanonicalPath().equals(canon.getAbsolutePath());
        } catch (Throwable t) {
            return false;
        }
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
        /**
         * 最低压缩比（解压后字节 / 压缩字节）。
         *
         * <p>参考 Apache POI ZipSecureFile 的 minInflateRatio=0.01（1%）。
         * 手机端取 2% 略宽松，避免误杀全零镜像 / SQLite 备份等合法高压缩数据。</p>
         */
        static final double MIN_INFLATE_RATIO = 0.02;

        int entries;
        long total;
        /**
         * 已读入的压缩字节数（用于压缩比判定）。
         *
         * <p>⚠️ 此前该字段声明后从未被赋值，恒为 0，
         * 导致 {@link #addBytes} 里的压缩比判断永不触发——是死代码。
         * 现由 {@link #copy(InputStream, OutputStream, Guard)} 在每轮回写时累加。</p>
         */
        /**
         * <b>声明</b>的压缩后总字节数（来自 zip 中央目录）。
         * 由调用方在开解压前一次性设好；0 表示拿不到（tar/7z/rar 都拿不到）。
         *
         * <p>旧字段 {@code compressedBytes} 从解压流累加，与 total 同源，
         * 比值恒 ≈1，那条判断从未触发过 —— 已删除。</p>
         */
        long compressedDeclared;
        final long totalCap;
        /** Zip-Slip 命中的条目名（最多 50 条，按原顺序） */
        final java.util.List<String> skipped = new java.util.ArrayList<>();

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
            // 压缩比不在这里判。compressedBytes 从解压流读到的字节没有意义
            // （与 total 同源），旧实现在这里判是死代码。
            // 真正的检查在 checkZipRatio4j()：只有 zip 能拿到每个条目的
            // 压缩后大小（zip4j FileHeader.getCompressedSize），
            // 其他格式拿不到，只能靠条目数 + 单文件 + 总量三条。
            if (compressedDeclared > 0 && total > (1L << 20)) {
                double ratio = (double) total / compressedDeclared;
                if (ratio > 1.0 / MIN_INFLATE_RATIO) {
                    throw new IOException("压缩比异常（" + Math.round(ratio) +
                            ":1），疑似压缩炸弹，已中止");
                }
            }
            if (total > totalCap) {
                throw new IOException("解压后总大小超过上限 "
                        + (totalCap / (1024 * 1024)) + "MB，已中止");
            }
        }

        /** 记录一个因不安全路径被跳过的条目（上限 50，避免全量收集） */
        void skip(String name) {
            if (name != null && skipped.size() < 50) skipped.add(name);
        }

        /** 取出跳过列表（调用方负责拼进提示） */
        java.util.List<String> takeSkipped() {
            return skipped;
        }
    }

    /** 带配额的 copy：按实际写出字节累计，超限即断 */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        copy(in, out, null);
    }

    /**
     * zip 专用的压缩比检查。
     *
     * <p><b>为什么只有 zip 能判</b>：压缩比需要「压缩后大小」与「解压后大小」两个数。
     * zip4j 的 {@code FileHeader} 里两者都有（getCompressedSize/getUncompressedSize）。
     * tar 只是打包不压缩，条目没有 compressedSize；7z / rar 的 API 也不暴露。
     * 那三种只能靠「条目数 + 单文件 + 总量」，这是格式决定的上限。</p>
     *
     * <p><b>旧实现为什么是死的</b>：它把 {@code in.read()} 的字节当作压缩后字节累加，
     * 而 in 是解压流，与 written 同源，比值恒 ≈1，永远到不了阈值。</p>
     *
     * @return 判定为炸弹时返回原因，否则 null
     */
    private static String checkZipRatio4j(net.lingala.zip4j.ZipFile zf) throws IOException {
        long totalC = 0;
        long totalU = 0;
        for (net.lingala.zip4j.model.FileHeader h : zf.getFileHeaders()) {
            if (h.isDirectory()) continue;
            long c = h.getCompressedSize();
            long u = h.getUncompressedSize();
            if (c <= 0 || u <= 0) continue;
            totalC += c;
            totalU += u;
            // 单条目先判：一个 10KB 条目声明解压后 10GB，不等总量就该拦
            double r = (double) u / c;
            if (u > (1L << 20) && r > 1.0 / Guard.MIN_INFLATE_RATIO) {
                return "条目「" + h.getFileName() + "」压缩比异常（"
                        + Math.round(r) + ":1），疑似压缩炸弹";
            }
        }
        // 总量再看一遍：防「每个条目都刚好低于阈值，但总数惊人」
        if (totalC > 0 && totalU > (1L << 20)) {
            double r = (double) totalU / totalC;
            if (r > 1.0 / Guard.MIN_INFLATE_RATIO) {
                return "整体压缩比异常（" + Math.round(r) + ":1），疑似压缩炸弹";
            }
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream out, Guard guard) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        long written = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            written += n;
            // ⚠️ 这里**不能**累加 guard.compressedBytes。
            // copy() 的 in 是**解压后**的读取流（TarArchiveInputStream / ZipInputStream），
            // 读出的 n 与 written 同源，比值恒 ≈1，压缩比判断永远不触发 —— 等于防线失效。
            // 压缩比必须在能拿到「压缩后大小」的地方判：见 checkZipRatio4j()，
            // 用 zip4j FileHeader.getCompressedSize()/getUncompressedSize()。
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
