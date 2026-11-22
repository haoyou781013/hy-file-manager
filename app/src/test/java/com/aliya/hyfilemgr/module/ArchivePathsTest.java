package com.aliya.hyfilemgr.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ArchivePaths} 的单元测试。
 *
 * <p><b>为什么这批测试重要</b>：{@code splitZipPath} 曾因后缀表漏了
 * {@code .tbz/.tbz2/.txz/.tzst} 导致点解压时 {@code StringIndexOutOfBounds}
 * 崩溃（CHANGELOG 有记录）。这类「解析强约束字符串」的函数如果没有用例，
 * 下次改后缀表还会踩同一个坑。</p>
 */
public class ArchivePathsTest {

    // ══════════ normalizeEntryName ══════════

    @Test
    public void normalize_backslashToSlash() {
        assertEquals("a/b/c", ArchivePaths.normalizeEntryName("a\\b\\c"));
    }

    @Test
    public void normalize_stripsLeadingDotSlash() {
        assertEquals("a/b", ArchivePaths.normalizeEntryName("./a/b"));
    }

    @Test
    public void normalize_stripsLeadingSlash() {
        assertEquals("a/b", ArchivePaths.normalizeEntryName("/a/b"));
    }

    @Test
    public void normalize_collapsesDoubleSlash() {
        assertEquals("a/b", ArchivePaths.normalizeEntryName("a//b"));
    }

    @Test
    public void normalize_stripsTrailingSlash() {
        // 目录条目常以 / 结尾（tar 的 "./sub/"）：
        // 保留尾斜杠会让按 / 切分时 rel 为空，条目丢失
        assertEquals("sub", ArchivePaths.normalizeEntryName("./sub/"));
        assertEquals("a/b", ArchivePaths.normalizeEntryName("a/b/"));
    }

    @Test
    public void normalize_nullSafe() {
        assertEquals("", ArchivePaths.normalizeEntryName(null));
    }

    @Test
    public void normalize_emptyInput() {
        assertEquals("", ArchivePaths.normalizeEntryName(""));
    }

    // ══════════ isSingleStreamExt ══════════

    @Test
    public void singleStream_gz_bz2_xz_zst() {
        assertTrue(ArchivePaths.isSingleStreamExt("gz"));
        assertTrue(ArchivePaths.isSingleStreamExt("bz2"));
        assertTrue(ArchivePaths.isSingleStreamExt("xz"));
        assertTrue(ArchivePaths.isSingleStreamExt("zst"));
    }

    @Test
    public void singleStream_tarIsNotSingle() {
        // tar 是归档格式（多条目），不是单流压缩
        assertFalse(ArchivePaths.isSingleStreamExt("tar"));
        assertFalse(ArchivePaths.isSingleStreamExt("zip"));
        assertFalse(ArchivePaths.isSingleStreamExt(null));
    }

    // ══════════ tarInnerExt ══════════

    @Test
    public void tarInnerExt_tarReturnsNull() {
        // 纯 tar 没有内层扩展名
        assertNull(ArchivePaths.tarInnerExt("tar"));
    }

    @Test
    public void tarInnerExt_compoundReturnsInner() {
        assertEquals("gz", ArchivePaths.tarInnerExt("tar.gz"));
        assertEquals("bz2", ArchivePaths.tarInnerExt("tar.bz2"));
        assertEquals("xz", ArchivePaths.tarInnerExt("tar.xz"));
        assertEquals("zst", ArchivePaths.tarInnerExt("tar.zst"));
    }

    @Test
    public void tarInnerExt_noDot() {
        assertNull(ArchivePaths.tarInnerExt("gz"));
    }

    // ══════════ containsGarbled ══════════

    @Test
    public void containsGarbled_normalTextIsClean() {
        assertFalse(ArchivePaths.containsGarbled("正常的中文文件名"));
        assertFalse(ArchivePaths.containsGarbled("normal_ascii.txt"));
    }

    @Test
    public void containsGarbled_replacementCharDetected() {
        // U+FFFD 是 UTF-8 解 GBK 字节时的替换字符
        assertTrue("含 U+FFFD 应判为乱码", ArchivePaths.containsGarbled("abc\uFFFDdef"));
    }

    @Test
    public void containsGarbled_nullAndEmpty() {
        assertFalse(ArchivePaths.containsGarbled(null));
        assertFalse(ArchivePaths.containsGarbled(""));
    }

    // ══════════ splitZipPath ══════════

    @Test
    public void splitZipPath_zipWithInnerPath() {
        String[] r = ArchivePaths.splitZipPath("zip:///sdcard/test.zip/dir/file.txt");
        assertEquals("/sdcard/test.zip", r[0]);
        assertEquals("dir/file.txt", r[1]);
    }

    @Test
    public void splitZipPath_rootOfZip() {
        String[] r = ArchivePaths.splitZipPath("zip:///sdcard/test.zip/");
        assertEquals("/sdcard/test.zip", r[0]);
        assertEquals("", r[1]);
    }

    @Test
    public void splitZipPath_allSupportedExtensions() {
        // 这些后缀都曾漏过或差点漏掉，必须逐个覆盖。
        // ArchiveExtractor.NAME_SUFFIXES 是唯一来源，这里验证切分能找到它们。
        String[] exts = {".zip", ".jar", ".apk", ".7z", ".rar",
                ".tar", ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst",
                ".tgz", ".tbz", ".tbz2", ".txz", ".tzst"};
        for (String ext : exts) {
            String virt = "zip:///sdcard/a" + ext + "/inner";
            String[] r = ArchivePaths.splitZipPath(virt);
            assertEquals("切分失败: " + ext, "/sdcard/a" + ext, r[0]);
            assertEquals("包内路径错误: " + ext, "inner", r[1]);
        }
    }

    @Test
    public void splitZipPath_noTrailingSlashFallsBack() {
        // 防御分支：路径末尾无 '/'（正常构造点不会出现）
        String[] r = ArchivePaths.splitZipPath("zip:///sdcard/x.zip");
        assertEquals("/sdcard/x.zip", r[0]);
        assertEquals("", r[1]);
    }
}
