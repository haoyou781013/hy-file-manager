package com.aliya.hyfilemgr.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * UI 工具（从 HY_VQ ModuleUiKit 精简剥离）。
 *
 * <p>只保留文件管理真正用到的两个方法：主题属性取色 + 圆角背景。
 * 原 ModuleUiKit 558 行，这里只留 40 行。</p>
 */
public final class UiKit {

    private UiKit() { }

    /** 解析主题属性为颜色值（如 colorPrimary → 实际 ARGB） */
    public static int color(Context context, int attr) {
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    /** 圆角背景（radiusDp 圆角，fillColor 填充，strokeColor 描边 0=无） */
    public static GradientDrawable rounded(Context context, int radiusDp,
                                           int fillColor, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(context, radiusDp));
        if (fillColor != 0) gd.setColor(fillColor);
        if (strokeColor != 0) gd.setStroke(dp(context, 1), strokeColor);
        return gd;
    }

    public static int dp(Context context, float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
