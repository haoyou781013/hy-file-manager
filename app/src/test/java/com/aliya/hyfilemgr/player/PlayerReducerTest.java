package com.aliya.hyfilemgr.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import androidx.media3.common.Player;

/**
 * {@link PlayerReducer} / {@link PlayerUiState} 的单元测试。
 *
 * <p><b>为什么这些测试有价值</b>：重构前「点暂停后界面该显示什么」这类问题
 * 必须启动 Activity、点按钮、看截图才能验证。现在是一行断言。</p>
 *
 * <p>每条断言背后都有一次真实的困惑或 bug —— 尤其是「锁定后控制条该不该显示」
 * 「暂停时该不该自动隐藏」这两组，是调研中反复踩坑的点。</p>
 *
 * <p>跑法：{@code gradle testReleaseUnitTest}（纯 JVM，不需要设备）。</p>
 */
public class PlayerReducerTest {

    // ══════════ 控制条可见性 ══════════

    @Test
    public void surfaceTap_togglesControls() {
        PlayerUiState s = PlayerUiState.initial();   // 初始 controlsVisible = true
        assertTrue(s.controlsVisible);

        PlayerUiState s2 = PlayerReducer.reduce(s, new PlayerEvent.SurfaceTapped());
        assertFalse("点画面应隐藏控制条", s2.controlsVisible);

        PlayerUiState s3 = PlayerReducer.reduce(s2, new PlayerEvent.SurfaceTapped());
        assertTrue("再点应显示回来", s3.controlsVisible);
    }

    @Test
    public void surfaceTap_ignoredWhenLocked() {
        // 锁定状态下点画面不应该弹出控制条——这正是"锁定"存在的意义
        PlayerUiState locked = PlayerUiState.initial().buildUpon().locked(true).build();
        PlayerUiState after = PlayerReducer.reduce(locked, new PlayerEvent.SurfaceTapped());
        assertFalse("锁定时点画面不该显示控制条", after.controlsVisible);
    }

    @Test
    public void lockToggle_hidesControlsAndBlocksGestures() {
        PlayerUiState visible = PlayerUiState.initial();
        assertTrue(visible.controlsVisible);

        PlayerUiState locked = PlayerReducer.reduce(visible, new PlayerEvent.LockToggled());
        assertTrue(locked.locked);
        assertFalse("锁定后立即隐藏控制条", locked.controlsVisible);

        PlayerUiState unlocked = PlayerReducer.reduce(locked, new PlayerEvent.LockToggled());
        assertFalse(unlocked.locked);
    }

    @Test
    public void autoHideEvent_setsInvisible() {
        PlayerUiState visible = PlayerUiState.initial();
        PlayerUiState hidden = PlayerReducer.reduce(visible, new PlayerEvent.ControlsAutoHidden());
        assertFalse(hidden.controlsVisible);
    }

    @Test
    public void controlsShownEvent_makesVisible() {
        PlayerUiState hidden = PlayerUiState.initial()
                .buildUpon().controlsVisible(false).build();
        PlayerUiState shown = PlayerReducer.reduce(hidden, new PlayerEvent.ControlsShown());
        assertTrue(shown.controlsVisible);
    }

    // ══════════ 自动隐藏判定（调研得来的三条豁免）══════════

    @Test
    public void shouldAutoHide_whilePlayingAndVisible() {
        // 正在播放（showPlayButton=false）+ 控制条可见 → 应该自动隐藏
        PlayerUiState playing = PlayerUiState.initial()
                .buildUpon()
                .showPlayButton(false)      // 显示"暂停"图标 = 正在播
                .controlsVisible(true)
                .buffering(false)
                .build();
        assertTrue(PlayerReducer.shouldAutoHide(playing));
    }

    @Test
    public void shouldAutoHide_notWhenPaused() {
        // 暂停时不该自动隐藏：用户暂停就是想操作
        PlayerUiState paused = PlayerUiState.initial()
                .buildUpon()
                .showPlayButton(true)       // 显示"播放"图标 = 已暂停/结束
                .controlsVisible(true)
                .build();
        assertFalse("暂停时不该自动隐藏控制条", PlayerReducer.shouldAutoHide(paused));
    }

    @Test
    public void shouldAutoHide_notWhenBuffering() {
        // 缓冲中不该隐藏：用户需要看到进度
        PlayerUiState buffering = PlayerUiState.initial()
                .buildUpon()
                .showPlayButton(false)
                .controlsVisible(true)
                .buffering(true)
                .build();
        assertFalse("缓冲时不该自动隐藏", PlayerReducer.shouldAutoHide(buffering));
    }

    @Test
    public void shouldAutoHide_whenLocked() {
        PlayerUiState locked = PlayerUiState.initial().buildUpon().locked(true).build();
        assertTrue("锁定状态视为已隐藏", PlayerReducer.shouldAutoHide(locked));
    }

    // ══════════ 进度 ══════════

    @Test
    public void seekPreview_updatesPositionWithoutClampingAboveDuration() {
        PlayerUiState s = PlayerUiState.initial()
                .buildUpon().durationMs(100_000).positionMs(10_000).build();
        PlayerUiState after = PlayerReducer.reduce(s,
                new PlayerEvent.SeekPreview(50_000));
        assertEquals(50_000, after.positionMs);
    }

    @Test
    public void seekPreview_clampsBeyondDuration() {
        PlayerUiState s = PlayerUiState.initial()
                .buildUpon().durationMs(100_000).build();
        PlayerUiState after = PlayerReducer.reduce(s,
                new PlayerEvent.SeekPreview(999_999));
        assertEquals("超出时长应夹到末尾", 100_000, after.positionMs);
    }

    @Test
    public void seekPreview_clampsNegative() {
        PlayerUiState s = PlayerUiState.initial().buildUpon().durationMs(100_000).build();
        PlayerUiState after = PlayerReducer.reduce(s,
                new PlayerEvent.SeekPreview(-5000));
        assertEquals("负数应夹到 0", 0, after.positionMs);
    }

    @Test
    public void seekPreview_handlesUnknownDuration() {
        // 直播流 / 时长未知时 durationMs = TIME_UNSET，不应把 position 夹成 0
        PlayerUiState s = PlayerUiState.initial();   // duration 默认 TIME_UNSET
        PlayerUiState after = PlayerReducer.reduce(s,
                new PlayerEvent.SeekPreview(30_000));
        assertEquals("时长未知时不该夹上界", 30_000, after.positionMs);
    }

    // ══════════ 倍速 ══════════

    @Test
    public void longPressBoostStart_setsSpeedTo2x() {
        PlayerUiState s = PlayerUiState.initial();   // speed = 1.0
        PlayerUiState boosted = PlayerReducer.reduce(s,
                new PlayerEvent.LongPressBoostStart());
        assertEquals(2.0f, boosted.speed, 0.001f);
    }

    @Test
    public void speedChanged_setsExactSpeed() {
        PlayerUiState s = PlayerUiState.initial();
        PlayerUiState after = PlayerReducer.reduce(s,
                new PlayerEvent.SpeedChanged(1.5f));
        assertEquals(1.5f, after.speed, 0.001f);
    }

    // ══════════ 错误 ══════════

    @Test
    public void errorRaised_setsTextAndCode() {
        PlayerUiState s = PlayerUiState.initial();
        PlayerUiState err = PlayerReducer.reduce(s,
                new PlayerEvent.ErrorRaised("播放列表格式无法解析", "PARSING_MANIFEST_UNSUPPORTED"));
        assertEquals("播放列表格式无法解析", err.errorText);
        assertEquals("PARSING_MANIFEST_UNSUPPORTED", err.errorCode);
    }

    @Test
    public void errorCleared_removesBothFields() {
        PlayerUiState err = PlayerUiState.initial()
                .buildUpon().errorText("boom").errorCode("CODE").build();
        PlayerUiState ok = PlayerReducer.reduce(err, new PlayerEvent.ErrorCleared());
        assertNull(ok.errorText);
        assertNull(ok.errorCode);
    }

    // ══════════ 媒体信息 ══════════

    @Test
    public void mediaInfoChanged_updatesTitleCounterAudioFlag() {
        PlayerUiState s = PlayerUiState.initial();
        PlayerUiState after = PlayerReducer.reduce(s,
                new PlayerEvent.MediaInfoChanged("某番剧 · 线路A · 第3集", "3 / 12", false));
        assertEquals("某番剧 · 线路A · 第3集", after.title);
        assertEquals("3 / 12", after.counter);
        assertFalse(after.audioOnly);
    }

    @Test
    public void availabilityChanged_togglesBusinessEntries() {
        PlayerUiState s = PlayerUiState.initial();
        assertFalse(s.episodePickerAvailable);
        assertFalse(s.danmakuAvailable);

        PlayerUiState after = PlayerReducer.reduce(s,
                new PlayerEvent.AvailabilityChanged(true, true));
        assertTrue(after.episodePickerAvailable);
        assertTrue(after.danmakuAvailable);
    }

    // ══════════ 未知事件 ══════════

    @Test
    public void unknownEvent_returnsSameState() {
        PlayerUiState s = PlayerUiState.initial().buildUpon().title("x").build();
        PlayerUiState after = PlayerReducer.reduce(s, new Object());
        assertEquals("未知事件应原样返回", "x", after.title);
    }

    // ══════════ 不可变性 ══════════

    @Test
    public void reduce_returnsNewInstanceNotMutate() {
        PlayerUiState s = PlayerUiState.initial();
        PlayerUiState s2 = PlayerReducer.reduce(s, new PlayerEvent.ControlsAutoHidden());
        assertNotNull(s2);
        // 原状态不能被改
        assertTrue("原状态应保持不变", s.controlsVisible);
        assertFalse(s2.controlsVisible);
    }

    // ══════════ sameAs 等价判断 ══════════

    @Test
    public void sameAs_ignoresPositionChanges() {
        // position 每帧都在变，不该触发重渲染
        PlayerUiState a = PlayerUiState.initial().buildUpon().positionMs(1000).build();
        PlayerUiState b = PlayerUiState.initial().buildUpon().positionMs(2000).build();
        assertTrue("position 不应参与等价判断", a.sameAs(b));
    }

    @Test
    public void sameAs_detectsMeaningfulChanges() {
        PlayerUiState a = PlayerUiState.initial();
        PlayerUiState b = PlayerUiState.initial().buildUpon().controlsVisible(false).build();
        assertFalse("控制条可见性变化应被检出", a.sameAs(b));
    }

    // ══════════ 状态流转完整性 ══════════

    @Test
    public void fullFlow_playPauseLockUnlock() {
        // 模拟真实使用：播放中 → 点画面出控制条 → 锁定 → 解锁
        PlayerUiState s = PlayerUiState.initial()
                .buildUpon().showPlayButton(false).controlsVisible(false).build();

        s = PlayerReducer.reduce(s, new PlayerEvent.SurfaceTapped());
        assertTrue("点画面应显示控制条", s.controlsVisible);

        s = PlayerReducer.reduce(s, new PlayerEvent.LockToggled());
        assertTrue(s.locked);
        assertFalse("锁定后隐藏", s.controlsVisible);

        s = PlayerReducer.reduce(s, new PlayerEvent.SurfaceTapped());
        assertFalse("锁定时点画面无效", s.controlsVisible);

        s = PlayerReducer.reduce(s, new PlayerEvent.LockToggled());
        assertFalse("解锁成功", s.locked);
    }
}
