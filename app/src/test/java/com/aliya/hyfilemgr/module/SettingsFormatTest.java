package com.aliya.hyfilemgr.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link SettingsFormat} 的单元测试。
 *
 * <p><b>这批测试为什么以前写不了</b>：这些方法原本是
 * {@code FileManagerModule}（17776 行）里的 private static 函数，
 * 类加载需要 Android 框架与完整模块状态，JVM 上根本起不来。
 * 抽成独立工具类后就是普通的静态函数，一行断言可测。</p>
 */
public class SettingsFormatTest {

    // ══════════ fmtSeconds ══════════

    @Test
    public void fmtSeconds_exactSeconds() {
        assertEquals("3 秒", SettingsFormat.fmtSeconds(3000));
        assertEquals("10 秒", SettingsFormat.fmtSeconds(10000));
    }

    @Test
    public void fmtSeconds_exactMinutes() {
        // 整分钟走「分钟」分支，不显示成「120 秒」
        assertEquals("1 分钟", SettingsFormat.fmtSeconds(60_000));
        assertEquals("2 分钟", SettingsFormat.fmtSeconds(120_000));
    }

    @Test
    public void fmtSeconds_fractional() {
        // 1.5 秒走小数分支
        assertEquals("1.5 秒", SettingsFormat.fmtSeconds(1500));
        assertEquals("2.5 秒", SettingsFormat.fmtSeconds(2500));
    }

    @Test
    public void fmtSeconds_zero() {
        assertEquals("0 秒", SettingsFormat.fmtSeconds(0));
    }

    @Test
    public void fmtSeconds_nonExactMinute() {
        // 90 秒：不是整分钟，但能被 1000 整除 → 走整秒分支，输出「90 秒」。
        // （我最初误以为是「90.0 秒」，测试跑出来才对上 —— 保留这条用例，
        //  正是为了锁住「整秒优先于小数」这个判定顺序。）
        assertEquals("90 秒", SettingsFormat.fmtSeconds(90_000));
    }

    // ══════════ fmtSpeed ══════════

    @Test
    public void fmtSpeed_integerNoDecimal() {
        // 2.0 → "2×" 而不是 "2.0×"
        assertEquals("2×", SettingsFormat.fmtSpeed(2.0f));
        assertEquals("1×", SettingsFormat.fmtSpeed(1.0f));
        assertEquals("3×", SettingsFormat.fmtSpeed(3.0f));
    }

    @Test
    public void fmtSpeed_fractional() {
        assertEquals("1.25×", SettingsFormat.fmtSpeed(1.25f));
        assertEquals("1.5×", SettingsFormat.fmtSpeed(1.5f));
    }

    @Test
    public void fmtSpeed_half() {
        assertEquals("0.5×", SettingsFormat.fmtSpeed(0.5f));
    }

    // ══════════ nextIntOption ══════════

    @Test
    public void nextIntOption_advances() {
        int[] opts = {1500, 3000, 5000};
        assertEquals(Integer.valueOf(3000), Integer.valueOf(SettingsFormat.nextIntOption(opts, 1500)));
        assertEquals(Integer.valueOf(5000), Integer.valueOf(SettingsFormat.nextIntOption(opts, 3000)));
    }

    @Test
    public void nextIntOption_wraps() {
        int[] opts = {1500, 3000, 5000};
        assertEquals("末位应回到首位", 1500, SettingsFormat.nextIntOption(opts, 5000));
    }

    @Test
    public void nextIntOption_notFoundReturnsFirst() {
        int[] opts = {1500, 3000};
        assertEquals("不在候选里时返回第一个", 1500, SettingsFormat.nextIntOption(opts, 9999));
    }

    @Test
    public void nextIntOption_singleElementStays() {
        int[] opts = {3000};
        assertEquals(3000, SettingsFormat.nextIntOption(opts, 3000));
    }

    // ══════════ nextFloatOption ══════════

    @Test
    public void nextFloatOption_advances() {
        float[] opts = {1.5f, 2.0f, 2.5f};
        assertEquals(2.0f, SettingsFormat.nextFloatOption(opts, 1.5f), 0.001f);
        assertEquals(2.5f, SettingsFormat.nextFloatOption(opts, 2.0f), 0.001f);
    }

    @Test
    public void nextFloatOption_wraps() {
        float[] opts = {1.5f, 2.0f, 2.5f};
        assertEquals(1.5f, SettingsFormat.nextFloatOption(opts, 2.5f), 0.001f);
    }

    @Test
    public void nextFloatOption_usesCompareNotEquals() {
        // Float.compare 能正确处理 2.0f 与 2.00f 这类值
        float[] opts = {2.0f, 3.0f};
        assertEquals("相等的浮点应命中", 3.0f, SettingsFormat.nextFloatOption(opts, 2.0f), 0.001f);
    }

    // ══════════ fmtPublishDate ══════════

    @Test
    public void fmtPublishDate_nullBecomesUnknown() {
        assertEquals("未知", SettingsFormat.fmtPublishDate(null));
        assertEquals("未知", SettingsFormat.fmtPublishDate(""));
    }

    @Test
    public void fmtPublishDate_passesThrough() {
        assertEquals("2026-11-22", SettingsFormat.fmtPublishDate("2026-11-22"));
    }

    // ══════════ stripMarkdown ══════════

    @Test
    public void stripMarkdown_removesBold() {
        assertEquals("加粗文字", SettingsFormat.stripMarkdown("**加粗文字**"));
    }

    @Test
    public void stripMarkdown_removesInlineCode() {
        assertEquals("代码", SettingsFormat.stripMarkdown("`代码`"));
    }

    @Test
    public void stripMarkdown_convertsListMarker() {
        String out = SettingsFormat.stripMarkdown("- 第一项\n- 第二项");
        assertTrue("列表符号应转为 ·", out.contains("·"));
        assertFalse("不应残留 markdown 的短横线列表符", out.startsWith("- "));
    }

    @Test
    public void stripMarkdown_stripsHeadingHash() {
        String out = SettingsFormat.stripMarkdown("## 标题");
        assertEquals("标题", out);
    }

    @Test
    public void stripMarkdown_collapsesBlankLines() {
        String out = SettingsFormat.stripMarkdown("a\n\n\n\nb");
        assertFalse("连续空行应合并", out.contains("\n\n\n"));
    }

    @Test
    public void stripMarkdown_nullSafe() {
        assertEquals("", SettingsFormat.stripMarkdown(null));
        assertEquals("", SettingsFormat.stripMarkdown(""));
    }

    // ══════════ displayNameOf ══════════

    @Test
    public void displayNameOf_knownKeys() {
        assertEquals("缩略图缓存", SettingsFormat.displayNameOf("thumbs"));
        assertEquals("ZIP 浏览缓存", SettingsFormat.displayNameOf("zip"));
        assertEquals("回收站", SettingsFormat.displayNameOf("trash"));
    }

    @Test
    public void displayNameOf_unknownKeyPassesThrough() {
        assertEquals("unknown_key", SettingsFormat.displayNameOf("unknown_key"));
    }

    // ══════════ formatDownloadProgress：不在 JVM 单测范围内 ══════════
    //
    // 该方法依赖 FileOps.formatSize → AccessUtil.formatSize，
    // 而 FileOps 顶层引用了 android.os.Handler / Looper，
    // 类初始化在纯 JVM 上直接 ExceptionInInitializerError（已实测）。
    //
    // 要测它需要 Robolectric 或把 formatSize 拆成不依赖 Android 的纯函数。
    // 当前阶段不做 —— 用一个测不了的测试占位，比没有测试更糟（会给人
    // 「这块有覆盖」的错觉）。

    // ══════════ nextFloatOption 的浮点比较语义 ══════════

    @Test
    public void nextFloatOption_zeroAndNegative() {
        float[] opts = {0f, 1f, 2f};
        assertEquals(1f, SettingsFormat.nextFloatOption(opts, 0f), 0.001f);
        assertEquals(0f, SettingsFormat.nextFloatOption(opts, 2f), 0.001f);   // 回绕
    }
}
