package com.aliya.hyfilemgr.ui;

import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * 统一动效工具（从零建立，替代「弹窗瞬间出现」的僵硬观感）。
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>入场：透明度 0→1 + 缩放 0.88→1，220ms，DecelerateInterpolator</li>
 *   <li>退场：透明度 1→0 + 缩放 1→0.94，140ms，比入场快（离场应干脆）</li>
 *   <li>按压：缩放 0.96，120ms，释放用 OvershootInterpolator(2.5f) 回弹</li>
 * </ul>
 */
public final class Anim {

    private Anim() { }

    /** 入场：淡入 + 轻微放大（弹窗/面板/列表项） */
    public static void in(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /** 入场（带轻微上浮，适合从底部弹出的面板） */
    public static void inFromBottom(View v, float startY) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(startY);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /** 退场：淡出 + 轻微缩小，快于入场 */
    public static void out(View v) {
        if (v == null) return;
        v.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(140)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

    /** 按压回弹：缩放 0.96 → 1，Overshoot 回弹 */
    public static void press(View v) {
        if (v == null) return;
        v.animate()
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(110)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(new OvershootInterpolator(2.5f))
                        .start())
                .start();
    }

    /** 列表项入场：按索引错峰淡入上浮 */
    public static void staggerIn(View v, int index) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(UiKit.dp(v.getContext(), 8));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(Math.min(index, 8) * 22L)   // 最多错峰 176ms
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }
}
