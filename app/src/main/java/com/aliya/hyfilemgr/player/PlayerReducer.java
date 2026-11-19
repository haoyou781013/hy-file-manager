package com.aliya.hyfilemgr.player;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;

/**
 * 状态归约器：{@code (旧状态, 事件) → 新状态}。
 *
 * <p><b>纯函数</b>：不碰 View、不碰 {@code Context}、不调任何 {@code Player} 方法
 * （需要读播放器真实状态的场景由调用方先转成事件，或直接走
 * {@link PlayerUiState#fromPlayer}）。所以可以在 JVM 上直接单测。</p>
 *
 * <p><b>为什么不把 Player 传进来</b>：传进来就变成了「Reducers 依赖 Android 框架」，
 * 单测要么起 Robolectric 要么 mock 一堆接口。保持纯函数意味着
 * 「点暂停后 UI 应该显示什么」这类问题可以用一行断言验证。</p>
 */
public final class PlayerReducer {

    private PlayerReducer() {}

    /**
     * 归约一个事件。
     *
     * @param state 旧状态
     * @param event 事件
     * @return 新状态；不认识的组合返回原状态（而非 null）
     */
    @NonNull
    public static PlayerUiState reduce(@NonNull PlayerUiState state, @NonNull Object event) {

        // ── 播放控制 ──
        if (event instanceof PlayerEvent.PlayPauseTapped) {
            // 注意：这里**不改 isPlaying**——真实播放状态由 Player 决定，
            // 点击后 Player 状态变化会触发 onEvents → fromPlayer 快照。
            // 这里只做「点了按钮，控制条应重新计时」这种 UI 侧的事。
            return state.buildUpon().controlsVisible(true).build();
        }

        if (event instanceof PlayerEvent.SurfaceTapped) {
            // 点画面：切换控制条。
            //
            // 锁定状态下**强制保持隐藏**——不能只 return state，因为
            // 状态可能处于「locked=true 但 controlsVisible=true」（比如外部
            // 直接把 locked 置 true 而不经过 LockToggled 事件）。
            // 测试 surfaceTap_ignoredWhenLocked 就是抓这个问题的。
            if (state.locked) {
                return state.controlsVisible ? state.buildUpon().controlsVisible(false).build()
                                             : state;
            }
            return state.buildUpon().controlsVisible(!state.controlsVisible).build();
        }

        if (event instanceof PlayerEvent.ControlsAutoHidden) {
            // 自动隐藏：不改变其它状态
            if (!state.controlsVisible) return state;
            return state.buildUpon().controlsVisible(false).build();
        }

        if (event instanceof PlayerEvent.ControlsShown) {
            return state.buildUpon().controlsVisible(true).build();
        }

        // ── 进度 ──
        if (event instanceof PlayerEvent.SeekPreview) {
            long target = ((PlayerEvent.SeekPreview) event).targetMs;
            // 预览：只更新显示用的 position，不改 Player
            return state.buildUpon()
                    .positionMs(clamp(target, 0, state.durationMs))
                    .build();
        }

        if (event instanceof PlayerEvent.SeekCommitted) {
            long target = ((PlayerEvent.SeekCommitted) event).targetMs;
            // 提交：同样只反映到 state，真正的 seek 由调用方对 Player 发起后
            // 通过 onEvents → fromPlayer 快照回来（避免这里猜测最终位置）
            return state.buildUpon()
                    .positionMs(clamp(target, 0, state.durationMs))
                    .build();
        }

        // ── 倍速 ──
        if (event instanceof PlayerEvent.LongPressBoostStart) {
            // 长按加速：记下当前倍速用于恢复（由调用方保存，这里只改显示）
            return state.buildUpon().speed(2f).build();
        }

        if (event instanceof PlayerEvent.SpeedChanged) {
            float s = ((PlayerEvent.SpeedChanged) event).speed;
            return state.buildUpon().speed(s).build();
        }

        // ── 画面比例 ──
        if (event instanceof PlayerEvent.ResizeModeChanged) {
            int m = ((PlayerEvent.ResizeModeChanged) event).resizeMode;
            return state.buildUpon().resizeMode(m).build();
        }

        // ── 锁定 ──
        if (event instanceof PlayerEvent.LockToggled) {
            boolean nowLocked = !state.locked;
            // 锁定后强制隐藏控制条：锁定的目的就是「别让误触弹出控件」
            return state.buildUpon()
                    .locked(nowLocked)
                    .controlsVisible(nowLocked ? false : state.controlsVisible)
                    .build();
        }

        // ── 错误 ──
        if (event instanceof PlayerEvent.ErrorRaised) {
            PlayerEvent.ErrorRaised e = (PlayerEvent.ErrorRaised) event;
            return state.buildUpon().errorText(e.text).errorCode(e.code).build();
        }

        if (event instanceof PlayerEvent.ErrorCleared) {
            if (state.errorText == null && state.errorCode == null) return state;
            return state.buildUpon().errorText(null).errorCode(null).build();
        }

        // ── 媒体信息 ──
        if (event instanceof PlayerEvent.MediaInfoChanged) {
            PlayerEvent.MediaInfoChanged e = (PlayerEvent.MediaInfoChanged) event;
            return state.buildUpon()
                    .title(e.title)
                    .counter(e.counter)
                    .audioOnly(e.audioOnly)
                    .build();
        }

        // ── 业务可用性 ──
        if (event instanceof PlayerEvent.AvailabilityChanged) {
            PlayerEvent.AvailabilityChanged e = (PlayerEvent.AvailabilityChanged) event;
            return state.buildUpon()
                    .episodePickerAvailable(e.episodePicker)
                    .danmakuAvailable(e.danmaku)
                    .build();
        }

        return state;
    }

    /**
     * 控制条是否该自动隐藏。
     *
     * <p>三条豁免（来源：YouTube / B 站 / mpvRx 的实际行为）：</p>
     * <ol>
     *   <li><b>暂停时不隐藏</b>——用户暂停就是想操作，藏了控件等于逼他再点一次；</li>
     *   <li><b>缓冲中不隐藏</b>——缓冲时用户需要看到进度；</li>
     *   <li><b>锁定状态下永不显示</b>（锁定本身就是隐藏）。</li>
     * </ol>
     */
    public static boolean shouldAutoHide(@NonNull PlayerUiState s) {
        if (s.locked) return true;
        if (!s.controlsVisible) return true;   // 已经隐藏，无需再隐藏
        if (s.buffering) return false;
        // showPlayButton == true 表示「点了会开始播」→ 当前是暂停/结束状态
        return !s.showPlayButton;
    }

    private static long clamp(long v, long min, long max) {
        if (max <= 0) return Math.max(min, v);   // 时长未知时不夹上界
        return Math.max(min, Math.min(max, v));
    }
}
