package com.aliya.hyfilemgr.player;

/**
 * 播放器事件——用户在 UI / 手势 / 系统控制上做出的所有动作。
 *
 * <p><b>为什么要事件化</b>：重构前这些动作散落在 30+ 处 {@code setOnClickListener}
 * 与手势回调里，每个地方都直接改若干字段 + 直接改 View。想回答「点了暂停之后
 * 界面应该变成什么」必须把所有分支读一遍。</p>
 *
 * <p>事件化之后，所有状态变化都是 {@code reduce(state, event)} 的纯函数运算，
 * 可以逐条写测试用例断言「给了这个事件，新状态应该是什么」。</p>
 */
public class PlayerEvent {

    private PlayerEvent() {}

    /** 点了中央播放/暂停按钮 */
    public static final class PlayPauseTapped extends PlayerEvent {}

    /** 点了上一个 */
    public static final class PrevTapped extends PlayerEvent {}

    /** 点了下一个 */
    public static final class NextTapped extends PlayerEvent {}

    /** 点空白处（显示/隐藏控制条） */
    public static final class SurfaceTapped extends PlayerEvent {}

    /** 拖动进度条中（预览，尚未提交） */
    public static final class SeekPreview {
        public final long targetMs;
        public SeekPreview(long targetMs) { this.targetMs = targetMs; }
    }

    /** 拖动进度条松手（提交 seek） */
    public static final class SeekCommitted {
        public final long targetMs;
        public SeekCommitted(long targetMs) { this.targetMs = targetMs; }
    }

    /** 双击左/右区域快退/快进（增量） */
    public static final class SeekBy {
        public final long deltaMs;
        public SeekBy(long deltaMs) { this.deltaMs = deltaMs; }
    }

    /** 长按加速开始（sticky） */
    public static final class LongPressBoostStart extends PlayerEvent {}

    /** 长按加速结束（恢复原倍速） */
    public static final class LongPressBoostEnd extends PlayerEvent {}

    /** 倍速被用户选择 */
    public static final class SpeedChanged {
        public final float speed;
        public SpeedChanged(float speed) { this.speed = speed; }
    }

    /** 画面比例切换 */
    public static final class ResizeModeChanged {
        public final int resizeMode;
        public ResizeModeChanged(int resizeMode) { this.resizeMode = resizeMode; }
    }

    /** 锁定/解锁控制条（锁定时屏蔽手势） */
    public static final class LockToggled extends PlayerEvent {}

    /** 播放器报告错误 */
    public static final class ErrorRaised {
        public final String text;
        public final String code;
        public ErrorRaised(String text, String code) { this.text = text; this.code = code; }
    }

    /** 错误被清除（重试成功 / 换源成功） */
    public static final class ErrorCleared extends PlayerEvent {}

    /** 控制条被自动隐藏（由渲染层的计时器触发） */
    public static final class ControlsAutoHidden extends PlayerEvent {}

    /** 控制条被主动显示（切集 / 恢复播放后重新计时） */
    public static final class ControlsShown extends PlayerEvent {}

    /** 媒体信息变化（标题 / 计数 / 音频还是视频） */
    public static final class MediaInfoChanged {
        public final String title;
        public final String counter;
        public final boolean audioOnly;
        public MediaInfoChanged(String title, String counter, boolean audioOnly) {
            this.title = title;
            this.counter = counter;
            this.audioOnly = audioOnly;
        }
    }

    /** 业务可用性变化（选集 / 弹幕入口是否要显示） */
    public static final class AvailabilityChanged {
        public final boolean episodePicker;
        public final boolean danmaku;
        public AvailabilityChanged(boolean episodePicker, boolean danmaku) {
            this.episodePicker = episodePicker;
            this.danmaku = danmaku;
        }
    }
}
