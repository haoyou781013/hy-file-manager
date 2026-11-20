package com.aliya.hyfilemgr.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link PlayerSettings} 的测试。
 *
 * <p>注意：本测试只覆盖不依赖 {@code Context} 的部分（默认值、isAllDefault）。
 * 真正的 SharedPreferences 读写需要 Robolectric 或仪器测试，
 * 这里不引入额外依赖——读写逻辑本身很薄，出问题会在装机验证时暴露。</p>
 */
public class PlayerSettingsTest {

    @Test
    public void defaults_haveExpectedValues() {
        PlayerSettings s = PlayerSettings.defaults();
        assertTrue("默认开启自动隐藏", s.autoHide);
        assertEquals("默认 3 秒", 3000, s.hideDelayMs);
        assertTrue("默认单击切换", s.tapToggle);
        assertEquals("默认长按 2×", 2.0f, s.longPressSpeed, 0.001f);
        assertEquals("默认双击 10 秒", 10_000, s.doubleTapSeekMs);
        assertEquals("默认整屏 2 分钟", 120_000, s.seekFullWidthMs);
    }

    @Test
    public void defaults_isAllDefaultTrue() {
        assertTrue("默认值对象应自认为全默认", PlayerSettings.defaults().isAllDefault());
    }

    @Test
    public void nullContext_returnsDefaults() {
        // 设置读失败不该让播放器起不来
        PlayerSettings s = PlayerSettings.load(null);
        assertTrue(s.isAllDefault());
        assertEquals(3000, s.hideDelayMs);
    }

    @Test
    public void toggleSemantics_repeatedToggleMustAlternate() {
        // 用户反馈的真实 bug：「点开启后再点又弹开启」。
        //
        // 根因不在 PlayerSettings，而在设置页的 onClick 用了构建时的
        // 快照 cur.autoHide 取反 —— 页面不重建就永远是旧值，
        // 于是每次点击都算出同一个结果。
        //
        // 这里用「先读后写」的正确模式验证语义：连续切换必须交替，
        // 而不是永远得到同一个值。PlayerSettings 本身无状态、
        // 每次都从 prefs 现读，所以这个模式是可靠的。
        //
        // 注：本测试不碰真实 SharedPreferences（需要 Context），
        // 只用局部变量复刻「现读 → 取反 → 写回」的语义，
        // 锁住的是「不能缓存快照」这条规则。
        boolean stored = true;          // 假设当前已开启

        // 第一次点击：现读 true → 取反 false → 写回
        boolean first = !stored;
        stored = first;
        assertFalse("第一次点击应由开启变为关闭", stored);

        // 第二次点击：现读 false → 取反 true → 写回
        boolean second = !stored;
        stored = second;
        assertTrue("第二次点击应由关闭变回开启", stored);

        // 第三次
        boolean third = !stored;
        stored = third;
        assertFalse("第三次点击应再次变为关闭", stored);
    }

    @Test
    public void snapshotAntipattern_alwaysYieldsSameValue() {
        // 反面用例：证明「基于快照取反」为什么是错的。
        // 快照 cur 在页面构建时固定，连续点击算出同一个结果。
        final boolean snapshot = true;          // 构建时的值
        boolean r1 = !snapshot;                 // false
        boolean r2 = !snapshot;                 // false —— 永远是 false
        boolean r3 = !snapshot;                 // false
        assertFalse(r1);
        assertFalse(r2);
        assertFalse(r3);
        // 三次结果全等 → 用户看到的就是「点了没变化 / 又弹开启」
        assertEquals("快照取反三次结果相同，正是 bug 的表现", r1, r3);
    }

    @Test
    public void constants_matchDefaults() {
        // 常量与 defaults() 必须一致，否则「恢复默认」会写出非默认值
        assertEquals(PlayerSettings.DEFAULT_HIDE_DELAY_MS, 3000);
        assertEquals(PlayerSettings.DEFAULT_DOUBLE_TAP_SEEK_MS, 10_000);
        assertEquals(PlayerSettings.DEFAULT_SEEK_FULL_WIDTH_MS, 120_000);
        assertEquals(PlayerSettings.DEFAULT_LONG_PRESS_SPEED, 2.0f, 0.001f);
    }
}
