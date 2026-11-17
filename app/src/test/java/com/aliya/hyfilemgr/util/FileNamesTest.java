package com.aliya.hyfilemgr.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * {@link FileNames} 的单元测试。
 *
 * <p>这些用例的价值不在「证明代码对」，而在<b>锁住已经踩过的坑</b>：
 * 每条 assert 背后都有一次真实故障或一次审查发现。改动这些方法时
 * 测试挂了，说明踩过的坑可能又回来了。</p>
 */
public class FileNamesTest {

    // ══════════ trimSlash ══════════

    @Test
    public void trimSlash_removesTrailingSlashes() {
        assertEquals("/sdcard/DCIM", FileNames.trimSlash("/sdcard/DCIM/"));
        assertEquals("/sdcard/DCIM", FileNames.trimSlash("/sdcard/DCIM///"));
        assertEquals("/sdcard/DCIM", FileNames.trimSlash("/sdcard/DCIM"));
    }

    @Test
    public void trimSlash_keepsRoot() {
        // 根目录 "/" 不能被削成空串，否则所有路径比较都会崩
        assertEquals("/", FileNames.trimSlash("/"));
        assertEquals("/", FileNames.trimSlash("//"));
    }

    // ══════════ isValidFileName ══════════

    @Test
    public void isValidFileName_acceptsNormalNames() {
        assertTrue(FileNames.isValidFileName("photo.jpg"));
        assertTrue(FileNames.isValidFileName("中文文件名.txt"));
        assertTrue(FileNames.isValidFileName("a b c.md"));
        assertTrue(FileNames.isValidFileName("file(1).zip"));
    }

    @Test
    public void isValidFileName_rejectsEmptyAndDots() {
        assertFalse(FileNames.isValidFileName(null));
        assertFalse(FileNames.isValidFileName(""));
        assertFalse(FileNames.isValidFileName("   "));
        assertFalse(FileNames.isValidFileName("."));
        assertFalse(FileNames.isValidFileName(".."));
    }

    @Test
    public void isValidFileName_rejectsIllegalChars() {
        // Windows/常见文件系统非法字符，逐个验证
        for (char c : new char[]{'/', '\\', ':', '*', '?', '"', '<', '>', '|'}) {
            assertFalse("应拒绝字符: " + c,
                    FileNames.isValidFileName("a" + c + "b"));
        }
    }

    @Test
    public void isValidFileName_rejectsTrailingDot() {
        // 部分 SAF provider 会静默吞掉尾点，导致「创建成功但名字变了」。
        // 这条分支是**有效**的——trim() 不会删点。
        assertFalse(FileNames.isValidFileName("name."));
        assertFalse(FileNames.isValidFileName("name.."));
    }

    @Test
    public void isValidFileName_trailingSpaceIsRejectedAtFunctionLevel() {
        // 函数自身语义：拒绝尾空格。
        // 但注意：当前所有调用方都先 .trim() 再传（showNewFolderDialog:4189、
        // rename:10563），所以实际链路上走不到这条分支，只是补全函数契约。
        // 保留此测试是为了锁住「函数宁可严格」的语义，避免有人删掉那个判断。
        assertFalse(FileNames.isValidFileName("name "));
        // 而「首尾空白被容忍」是调用方 trim 的效果，不是本函数的职责：
        assertTrue(FileNames.isValidFileName("  name  ".trim()));
    }

    // ══════════ uniqueName ══════════

    @Test
    public void uniqueName_returnsSameNameWhenNoConflict() {
        Set<String> existing = new HashSet<>(Arrays.asList("other.txt"));
        assertEquals("new.txt", FileNames.uniqueName("/tmp", "new.txt", existing));
    }

    @Test
    public void uniqueName_appendsCounterKeepingExtension() {
        // 扩展名必须保留：photo.jpg → photo (1).jpg，不是 photo.jpg (1)
        Set<String> existing = new HashSet<>(Arrays.asList("photo.jpg"));
        assertEquals("photo (1).jpg", FileNames.uniqueName("/tmp", "photo.jpg", existing));

        existing.add("photo (1).jpg");
        assertEquals("photo (2).jpg", FileNames.uniqueName("/tmp", "photo.jpg", existing));
    }

    @Test
    public void uniqueName_handlesHiddenFilesWithoutExtension() {
        // .gitignore 的点是「隐藏标记」不是扩展名分隔符
        Set<String> existing = new HashSet<>(Arrays.asList(".gitignore"));
        assertEquals(".gitignore (1)", FileNames.uniqueName("/tmp", ".gitignore", existing));
    }

    @Test
    public void uniqueName_handlesNameWithMultipleDots() {
        Set<String> existing = new HashSet<>(Arrays.asList("a.tar.gz"));
        assertEquals("a.tar (1).gz", FileNames.uniqueName("/tmp", "a.tar.gz", existing));
    }

    @Test
    public void uniqueName_handlesNoExtension() {
        Set<String> existing = new HashSet<>(Arrays.asList("README"));
        assertEquals("README (1)", FileNames.uniqueName("/tmp", "README", existing));
    }

    // ══════════ nameExists ══════════

    @Test
    public void nameExists_usesProvidedSetWhenAvailable() {
        Set<String> existing = new HashSet<>(Arrays.asList("a.txt"));
        // 传了集合就只查集合，不碰文件系统（SAF 目录必须走这条）
        assertTrue(FileNames.nameExists("/nonexistent-path-xyz", "a.txt", existing));
        assertFalse(FileNames.nameExists("/nonexistent-path-xyz", "b.txt", existing));
    }

    // ══════════ naturalCompareLower ══════════

    @Test
    public void naturalCompare_numbersSortNumerically() {
        // 这是「自然排序」的核心价值：file2 要排在 file10 前面
        assertTrue(FileNames.naturalCompareLower("file2", "file10") < 0);
        assertTrue(FileNames.naturalCompareLower("file10", "file2") > 0);
        assertTrue(FileNames.naturalCompareLower("chapter1", "chapter10") < 0);
    }

    @Test
    public void naturalCompare_equalForSameString() {
        assertEquals(0, FileNames.naturalCompareLower("abc", "abc"));
        assertEquals(0, FileNames.naturalCompareLower("", ""));
    }

    @Test
    public void naturalCompare_digitsBeforeLetters() {
        // 数字块 vs 非数字：数字排前（对齐 Prism naturalCompare 行为）
        assertTrue(FileNames.naturalCompareLower("1abc", "a1bc") < 0);
    }

    @Test
    public void naturalCompare_prefixShorterFirst() {
        assertTrue(FileNames.naturalCompareLower("abc", "abcd") < 0);
    }

    @Test
    public void naturalCompare_handlesLargeNumbers() {
        // 用 long 累加而不是 int，避免超长数字字符串溢出
        assertTrue(FileNames.naturalCompareLower(
                "file9999999999", "file10000000000") < 0);
    }

    // ══════════ archiveBaseName ══════════

    @Test
    public void archiveBaseName_stripsCommonSuffixes() {
        assertEquals("backup", FileNames.archiveBaseName("backup.zip"));
        assertEquals("backup", FileNames.archiveBaseName("backup.7z"));
        assertEquals("backup", FileNames.archiveBaseName("backup.tar"));
    }

    @Test
    public void archiveBaseName_stripsCompoundSuffixes() {
        // 复合扩展名必须整体剥掉：a.tar.gz → a（不能是 a.tar）
        assertEquals("a", FileNames.archiveBaseName("a.tar.gz"));
        assertEquals("a", FileNames.archiveBaseName("a.tar.bz2"));
        assertEquals("a", FileNames.archiveBaseName("a.tar.xz"));
        assertEquals("a", FileNames.archiveBaseName("a.tar.zst"));
    }

    @Test
    public void archiveBaseName_stripsTbzFamily() {
        // 回归：这 4 个后缀曾只在解压表里有、切分表里漏了，导致打开即崩
        assertEquals("a", FileNames.archiveBaseName("a.tbz"));
        assertEquals("a", FileNames.archiveBaseName("a.tbz2"));
        assertEquals("a", FileNames.archiveBaseName("a.txz"));
        assertEquals("a", FileNames.archiveBaseName("a.tzst"));
    }

    @Test
    public void archiveBaseName_stripsXapk() {
        // 回归：.xapk 曾只在一份后缀表里，另一份缺 → 手动/自动解压目录名不一致
        assertEquals("game", FileNames.archiveBaseName("game.xapk"));
    }

    @Test
    public void archiveBaseName_caseInsensitive() {
        assertEquals("BACKUP", FileNames.archiveBaseName("BACKUP.ZIP"));
    }

    @Test
    public void archiveBaseName_fallsBackWhenAllStripped() {
        // 文件名整个就是 ".zip" 时，剥完是空串 → 用兜底名
        assertEquals("extracted", FileNames.archiveBaseName(".zip"));
        assertEquals("extracted", FileNames.archiveBaseName(null));
        assertEquals("extracted", FileNames.archiveBaseName(""));
    }
}
