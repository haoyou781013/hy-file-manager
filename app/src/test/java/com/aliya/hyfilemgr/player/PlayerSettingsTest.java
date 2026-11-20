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
    public void constants_matchDefaults() {
        // 常量与 defaults() 必须一致，否则「恢复默认」会写出非默认值
        assertEquals(PlayerSettings.DEFAULT_HIDE_DELAY_MS, 3000);
        assertEquals(PlayerSettings.DEFAULT_DOUBLE_TAP_SEEK_MS, 10_000);
        assertEquals(PlayerSettings.DEFAULT_SEEK_FULL_WIDTH_MS, 120_000);
        assertEquals(PlayerSettings.DEFAULT_LONG_PRESS_SPEED, 2.0f, 0.001f);
    }
}
