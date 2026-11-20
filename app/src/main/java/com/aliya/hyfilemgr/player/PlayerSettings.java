package com.aliya.hyfilemgr.player;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 播放器设置项。
 *
 * <p><b>为什么需要这个类</b>：播放器的行为参数（是否自动隐藏控制条、
 * 隐藏延迟多久、长按倍速多少、双击跳多少秒）此前<b>全部硬编码在
 * MediaPlayerActivity 里</b>。用户反馈「控制条 3 秒就没了我来不及点」，
 * 但没有任何地方能改。</p>
 *
 * <p>这里把参数集中成一个对象，Activity 启动时读一次快照，
 * 设置页改完写回 SharedPreferences 并让 Activity 下次进入时生效。</p>
 *
 * <p><b>为什么用 SharedPreferences 而不是 DataStore</b>：项目已有
 * 一套 SharedPreferences 体系（文件管理器模块的 fmPrefs），
 * 引入 DataStore 要加依赖 + 协程，收益不抵成本。</p>
 *
 * <p><b>键名前缀 {@code player_} 避免与文件管理器的设置冲突</b>——
 * 两者共用一个 prefs 文件（{@code hyvq_filemgr}）。</p>
 */
public final class PlayerSettings {

    /** 与文件管理器模块共用的 prefs 文件名 */
    private static final String PREFS = "hyvq_filemgr";

    // ── 键名 ──
    private static final String K_AUTO_HIDE = "player_auto_hide";
    private static final String K_HIDE_DELAY = "player_hide_delay_ms";
    private static final String K_TAP_TOGGLE = "player_tap_toggle";
    private static final String K_LONG_PRESS_SPEED = "player_long_press_speed";
    private static final String K_DOUBLE_TAP_SEEK = "player_double_tap_seek_ms";
    private static final String K_SEEK_FULL_WIDTH = "player_seek_full_width_ms";

    // ── 默认值 ──
    /** 默认开启自动隐藏（主流做法，调研确认 auto-hide 已非可选项） */
    public static final boolean DEFAULT_AUTO_HIDE = true;
    /** 默认 3 秒（YouTube / B 站 / fluxplays 调研一致） */
    public static final int DEFAULT_HIDE_DELAY_MS = 3000;
    /** 默认单击切换控制条 */
    public static final boolean DEFAULT_TAP_TOGGLE = true;
    /** 默认长按 2× 加速 */
    public static final float DEFAULT_LONG_PRESS_SPEED = 2.0f;
    /** 默认双击跳 10 秒（YouTube 推广的标准） */
    public static final int DEFAULT_DOUBLE_TAP_SEEK_MS = 10_000;
    /** 默认横向滑动整屏宽 = 2 分钟 */
    public static final int DEFAULT_SEEK_FULL_WIDTH_MS = 120_000;

    // ── 字段 ──
    public final boolean autoHide;
    public final int hideDelayMs;
    public final boolean tapToggle;
    public final float longPressSpeed;
    public final int doubleTapSeekMs;
    public final int seekFullWidthMs;

    private PlayerSettings(boolean autoHide, int hideDelayMs, boolean tapToggle,
                           float longPressSpeed, int doubleTapSeekMs, int seekFullWidthMs) {
        this.autoHide = autoHide;
        this.hideDelayMs = hideDelayMs;
        this.tapToggle = tapToggle;
        this.longPressSpeed = longPressSpeed;
        this.doubleTapSeekMs = doubleTapSeekMs;
        this.seekFullWidthMs = seekFullWidthMs;
    }

    /** 默认设置 */
    public static PlayerSettings defaults() {
        return new PlayerSettings(DEFAULT_AUTO_HIDE, DEFAULT_HIDE_DELAY_MS,
                DEFAULT_TAP_TOGGLE, DEFAULT_LONG_PRESS_SPEED,
                DEFAULT_DOUBLE_TAP_SEEK_MS, DEFAULT_SEEK_FULL_WIDTH_MS);
    }

    /**
     * 从 SharedPreferences 读取。
     *
     * <p>任何读取异常都退回默认值——设置读失败不该让播放器起不来。</p>
     */
    public static PlayerSettings load(Context context) {
        if (context == null) return defaults();
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new PlayerSettings(
                    sp.getBoolean(K_AUTO_HIDE, DEFAULT_AUTO_HIDE),
                    sp.getInt(K_HIDE_DELAY, DEFAULT_HIDE_DELAY_MS),
                    sp.getBoolean(K_TAP_TOGGLE, DEFAULT_TAP_TOGGLE),
                    sp.getFloat(K_LONG_PRESS_SPEED, DEFAULT_LONG_PRESS_SPEED),
                    sp.getInt(K_DOUBLE_TAP_SEEK, DEFAULT_DOUBLE_TAP_SEEK_MS),
                    sp.getInt(K_SEEK_FULL_WIDTH, DEFAULT_SEEK_FULL_WIDTH_MS));
        } catch (Throwable t) {
            android.util.Log.e("HYVQ_PLAYER", "读取播放器设置失败，用默认值", t);
            return defaults();
        }
    }

    /** 写回一项。设置页每个开关/选择器改完就调一次。 */
    public static void saveAutoHide(Context c, boolean v) {
        put(c, K_AUTO_HIDE, e -> e.putBoolean(K_AUTO_HIDE, v));
    }

    public static void saveHideDelay(Context c, int ms) {
        put(c, K_HIDE_DELAY, e -> e.putInt(K_HIDE_DELAY, ms));
    }

    public static void saveTapToggle(Context c, boolean v) {
        put(c, K_TAP_TOGGLE, e -> e.putBoolean(K_TAP_TOGGLE, v));
    }

    public static void saveLongPressSpeed(Context c, float v) {
        put(c, K_LONG_PRESS_SPEED, e -> e.putFloat(K_LONG_PRESS_SPEED, v));
    }

    public static void saveDoubleTapSeek(Context c, int ms) {
        put(c, K_DOUBLE_TAP_SEEK, e -> e.putInt(K_DOUBLE_TAP_SEEK, ms));
    }

    public static void saveSeekFullWidth(Context c, int ms) {
        put(c, K_SEEK_FULL_WIDTH, e -> e.putInt(K_SEEK_FULL_WIDTH, ms));
    }

    /** 恢复全部默认值 */
    public static void resetAll(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(K_AUTO_HIDE)
                    .remove(K_HIDE_DELAY)
                    .remove(K_TAP_TOGGLE)
                    .remove(K_LONG_PRESS_SPEED)
                    .remove(K_DOUBLE_TAP_SEEK)
                    .remove(K_SEEK_FULL_WIDTH)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    // ── 内部 ──

    private interface EditorAction {
        SharedPreferences.Editor apply(SharedPreferences.Editor e);
    }

    private static void put(Context c, String key, EditorAction action) {
        if (c == null) return;
        try {
            SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            action.apply(e).apply();
        } catch (Throwable t) {
            android.util.Log.e("HYVQ_PLAYER", "保存播放器设置 " + key + " 失败", t);
        }
    }

    /** 默认值是否已全部生效（用于设置页显示「恢复默认」是否可用） */
    public boolean isAllDefault() {
        return autoHide == DEFAULT_AUTO_HIDE
                && hideDelayMs == DEFAULT_HIDE_DELAY_MS
                && tapToggle == DEFAULT_TAP_TOGGLE
                && Float.compare(longPressSpeed, DEFAULT_LONG_PRESS_SPEED) == 0
                && doubleTapSeekMs == DEFAULT_DOUBLE_TAP_SEEK_MS
                && seekFullWidthMs == DEFAULT_SEEK_FULL_WIDTH_MS;
    }
}
