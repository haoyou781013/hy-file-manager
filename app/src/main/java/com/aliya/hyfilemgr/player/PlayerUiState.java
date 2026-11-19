package com.aliya.hyfilemgr.player;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;

/**
 * 播放器 UI 的全部状态（不可变）。
 *
 * <p><b>为什么需要这个类</b>：重构前 {@code MediaPlayerActivity} 有 30+ 个可变字段
 * （{@code controlsVisible} / {@code longPressing} / {@code horizontalMode} /
 * {@code dragging} / {@code prepared} / {@code switching} / {@code seekTargetMs} …），
 * 每加一个功能就要多维护几组「什么时候该改哪个字段」的规则。结果是：</p>
 *
 * <ul>
 *   <li>转屏后状态丢失，UI 与播放器实际状态漂移（典型的：「图标显示暂停但实际在播」）；</li>
 *   <li>从画中画 / 后台切回来，控件状态对不上；</li>
 *   <li>没法写单元测试——状态改在 View 里，必须起 Activity 才能验证。</li>
 * </ul>
 *
 * <p><b>规则</b>：UI 的任何差异都只能从这个对象派生。
 * 所有事件经 {@link PlayerReducer#reduce} 生成新实例，再由
 * {@code PlayerRenderer.render(state)} 一次性刷到 View。禁止在 Activity 里
 * 直接改 View 表达状态。</p>
 *
 * <p><b>可测性</b>：本类与 {@link PlayerReducer} 都不依赖 {@code Context} /
 * 真实 View，可以在 JVM 上直接单测。</p>
 */
public final class PlayerUiState {

    // ── 播放状态 ──

    /** 中央按钮该显示「播放」还是「暂停」。用 {@link Util#shouldShowPlayButton} 派生 */
    public final boolean showPlayButton;
    /** {@link Player#getPlaybackState()} 的原值（IDLE/BUFFERING/READY/ENDED） */
    public final int playbackState;
    /** 是否正在缓冲（STATE_BUFFERING） */
    public final boolean buffering;
    /** 当前位置（毫秒） */
    public final long positionMs;
    /** 总时长（毫秒）；未知时 {@link C#TIME_UNSET} */
    public final long durationMs;
    /** 已缓冲位置（毫秒）——进度条的 secondaryProgress */
    public final long bufferedMs;

    // ── 媒体信息 ──

    /** 标题栏主文案（文件名 / 「番剧 · 线路 · 第3集」） */
    public final String title;
    /** 计数（「3 / 12」）；单条目时为 null */
    public final String counter;
    /** 是否音频（无画面，显示占位而不是黑屏） */
    public final boolean audioOnly;

    // ── 控制条 ──

    /** 控制条是否可见。自动隐藏在渲染层按 showTimeoutMs 排 */
    public final boolean controlsVisible;
    /**
     * 是否锁定。锁定时屏蔽一切手势（除解锁外），防止误触。
     * 常态 false；用户主动点锁才 true。
     */
    public final boolean locked;

    // ── 播放参数 ──

    /** 当前倍速 */
    public final float speed;
    /** 当前画面比例模式（对应 {@code AspectRatioFrameLayout.RESIZE_MODE_*}） */
    public final int resizeMode;

    // ── 业务可用性 ──

    /** 是否可选集（动漫模式才显示入口） */
    public final boolean episodePickerAvailable;
    /** 是否可显示弹幕（有 seasonId 才显示入口） */
    public final boolean danmakuAvailable;

    // ── 错误 ──

    /** 错误文案；null 表示无错误 */
    public final String errorText;
    /** 错误的技术码（显示在文案下方，便于用户反馈） */
    public final String errorCode;

    private PlayerUiState(Builder b) {
        this.showPlayButton = b.showPlayButton;
        this.playbackState = b.playbackState;
        this.buffering = b.buffering;
        this.positionMs = b.positionMs;
        this.durationMs = b.durationMs;
        this.bufferedMs = b.bufferedMs;
        this.title = b.title;
        this.counter = b.counter;
        this.audioOnly = b.audioOnly;
        this.controlsVisible = b.controlsVisible;
        this.locked = b.locked;
        this.speed = b.speed;
        this.resizeMode = b.resizeMode;
        this.episodePickerAvailable = b.episodePickerAvailable;
        this.danmakuAvailable = b.danmakuAvailable;
        this.errorText = b.errorText;
        this.errorCode = b.errorCode;
    }

    /** 初始状态：控制条可见、无错误、1.0× 倍速 */
    public static PlayerUiState initial() {
        return new Builder().build();
    }

    /**
     * 从 Player 快照当前状态。
     *
     * <p>用于「外部状态变了，我不关心具体是哪个事件」的场景：
     * {@code onEvents()} 回调、控制器重连、转屏后恢复。
     * 这类场景下逐字段推演事件不如直接快照——因为 Player 才是权威数据源。</p>
     */
    @NonNull
    public static PlayerUiState fromPlayer(@NonNull Player player, @NonNull PlayerUiState prev) {
        int state = player.getPlaybackState();
        return new Builder(prev)
                .showPlayButton(Util.shouldShowPlayButton(player))
                .playbackState(state)
                .buffering(state == Player.STATE_BUFFERING)
                .positionMs(Math.max(0, player.getCurrentPosition()))
                .durationMs(player.getDuration())
                .bufferedMs(Math.max(0, player.getBufferedPosition()))
                .speed(player.getPlaybackParameters().speed)
                .build();
    }

    /** 派生一个只改部分字段的副本 */
    @NonNull
    public Builder buildUpon() {
        return new Builder(this);
    }

    /** 状态是否等价（用于跳过无意义的重复渲染） */
    public boolean sameAs(PlayerUiState o) {
        if (o == null) return false;
        return showPlayButton == o.showPlayButton
                && playbackState == o.playbackState
                && buffering == o.buffering
                // position 每帧都在变，不参与等价判断（否则永远不等价）
                && durationMs == o.durationMs
                && eq(title, o.title)
                && eq(counter, o.counter)
                && audioOnly == o.audioOnly
                && controlsVisible == o.controlsVisible
                && locked == o.locked
                && Float.compare(speed, o.speed) == 0
                && resizeMode == o.resizeMode
                && episodePickerAvailable == o.episodePickerAvailable
                && danmakuAvailable == o.danmakuAvailable
                && eq(errorText, o.errorText)
                && eq(errorCode, o.errorCode);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    @NonNull
    @Override
    public String toString() {
        return "PlayerUiState{play=" + showPlayButton + ", state=" + playbackState
                + ", buf=" + buffering + ", pos=" + positionMs + "/" + durationMs
                + ", ctrl=" + controlsVisible + ", locked=" + locked
                + ", speed=" + speed + ", err=" + errorText + "}";
    }

    /** 可变构造器（内部使用） */
    public static final class Builder {
        private boolean showPlayButton = true;
        private int playbackState = Player.STATE_IDLE;
        private boolean buffering = false;
        private long positionMs = 0L;
        private long durationMs = C.TIME_UNSET;
        private long bufferedMs = 0L;
        private String title = "";
        private String counter = null;
        private boolean audioOnly = false;
        private boolean controlsVisible = true;
        private boolean locked = false;
        private float speed = 1f;
        private int resizeMode = 0;   // RESIZE_MODE_FIT
        private boolean episodePickerAvailable = false;
        private boolean danmakuAvailable = false;
        private String errorText = null;
        private String errorCode = null;

        public Builder() {}

        Builder(PlayerUiState s) {
            this.showPlayButton = s.showPlayButton;
            this.playbackState = s.playbackState;
            this.buffering = s.buffering;
            this.positionMs = s.positionMs;
            this.durationMs = s.durationMs;
            this.bufferedMs = s.bufferedMs;
            this.title = s.title;
            this.counter = s.counter;
            this.audioOnly = s.audioOnly;
            this.controlsVisible = s.controlsVisible;
            this.locked = s.locked;
            this.speed = s.speed;
            this.resizeMode = s.resizeMode;
            this.episodePickerAvailable = s.episodePickerAvailable;
            this.danmakuAvailable = s.danmakuAvailable;
            this.errorText = s.errorText;
            this.errorCode = s.errorCode;
        }

        public Builder showPlayButton(boolean v) { this.showPlayButton = v; return this; }
        public Builder playbackState(int v) { this.playbackState = v; return this; }
        public Builder buffering(boolean v) { this.buffering = v; return this; }
        public Builder positionMs(long v) { this.positionMs = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder bufferedMs(long v) { this.bufferedMs = v; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder counter(String v) { this.counter = v; return this; }
        public Builder audioOnly(boolean v) { this.audioOnly = v; return this; }
        public Builder controlsVisible(boolean v) { this.controlsVisible = v; return this; }
        public Builder locked(boolean v) { this.locked = v; return this; }
        public Builder speed(float v) { this.speed = v; return this; }
        public Builder resizeMode(int v) { this.resizeMode = v; return this; }
        public Builder episodePickerAvailable(boolean v) { this.episodePickerAvailable = v; return this; }
        public Builder danmakuAvailable(boolean v) { this.danmakuAvailable = v; return this; }
        public Builder errorText(String v) { this.errorText = v; return this; }
        public Builder errorCode(String v) { this.errorCode = v; return this; }

        public PlayerUiState build() { return new PlayerUiState(this); }
    }
}
