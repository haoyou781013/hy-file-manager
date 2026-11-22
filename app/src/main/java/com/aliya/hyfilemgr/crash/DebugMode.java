package com.aliya.hyfilemgr.crash;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 调试模式开关。
 *
 * <p><b>为什么需要它</b>：崩溃日志、ANR 记录、安全模式这些诊断能力，
 * 对普通用户是噪音（设置页多出几个看不懂的入口），对开发者是刚需。
 * 常见做法是用 `FLAG_DEBUGGABLE`（debug 包）区分，但发布版是 release 包，
 * 用户报的问题恰恰只在 release 包复现 —— 所以需要一条「在 release 包里
 * 临时打开诊断入口」的通道。</p>
 *
 * <p><b>交互设计</b>：关于页点击版本号 7 次开启（对齐 Android 系统
 * 「点版本号进开发者选项」的既有心智）。关闭同样是 7 次，
 * 不会因为误触而关掉。</p>
 *
 * <p><b>为什么用 SharedPreferences 而不是 BuildConfig</b>：需要在运行时
 * 切换且重启后保持。写死在 build 里就得重新发包。</p>
 */
public final class DebugMode {

    private static final String PREFS = "hyvq_filemgr";
    private static final String KEY_ENABLED = "debug_mode_enabled";

    /** 需要连点多少次 */
    public static final int REQUIRED_TAPS = 7;

    /**
     * 两次点击的最大间隔（毫秒）。
     *
     * <p>超过这个间隔就重新计数 —— 否则用户一天里零散点 7 次
     * 也会误触发，那不是「刻意操作」。</p>
     */
    public static final long TAP_TIMEOUT_MS = 1500L;

    private DebugMode() {}

    /** 是否已开启 */
    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            return prefs(ctx).getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 直接设置 */
    public static void setEnabled(Context ctx, boolean on) {
        if (ctx == null) return;
        try {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 记录一次点击，返回仍需点击的次数。
     *
     * <p>纯逻辑放在这里而不是塞进 UI，因为它有明确的可测规则：
     * 连续点、超时重置、达成后归零。</p>
     *
     * @param currentCount 当前已连续点击数（调用方持有）
     * @param lastTapMs    上次点击的时间戳；0 表示首次
     * @param nowMs        这次的点击时间
     * @return 本次点击后的状态
     */
    public static TapResult onTap(int currentCount, long lastTapMs, long nowMs) {
        // 超时或首次 → 重新从 1 开始
        boolean restart = lastTapMs <= 0 || (nowMs - lastTapMs) > TAP_TIMEOUT_MS;
        int count = restart ? 1 : currentCount + 1;

        if (count >= REQUIRED_TAPS) {
            // 达成：计数归零，避免下一次点击立刻又触发
            return new TapResult(0, 0L, true, 0);
        }
        return new TapResult(count, nowMs, false, REQUIRED_TAPS - count);
    }

    /** 一次点击的结果 */
    public static final class TapResult {
        /** 更新后的连续计数（达成为 0） */
        public final int count;
        /** 本次点击时间戳（达成为 0，下次重新计时） */
        public final long lastTapMs;
        /** 本次是否达成 7 次 */
        public final boolean completed;
        /** 还差几次（达成为 0） */
        public final int remaining;

        TapResult(int count, long lastTapMs, boolean completed, int remaining) {
            this.count = count;
            this.lastTapMs = lastTapMs;
            this.completed = completed;
            this.remaining = remaining;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
