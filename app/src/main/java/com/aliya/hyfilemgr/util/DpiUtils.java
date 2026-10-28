package com.aliya.hyfilemgr.util;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * 应用级 DPI（显示密度）覆盖。
 *
 * <p>通过给 Context 套一层 {@link android.content.ContextWrapper} 并
 * {@link Resources#updateConfiguration} 实现，只影响本应用，不改系统设置、不需要权限。</p>
 *
 * <p>注意：{@code updateConfiguration} 对已创建的 View 不会重算尺寸，
 * 因此修改后必须重建 Activity 才能生效（调用方负责）。</p>
 */
public final class DpiUtils {

    /** 用户设定的 DPI；0 表示跟随系统 */
    public static final int DPI_SYSTEM = 0;
    /** 可选 DPI 档位 */
    public static final int[] PRESETS = {0, 320, 360, 400, 440, 480, 520, 560, 600, 640};

    private static int overrideDpi = DPI_SYSTEM;

    private DpiUtils() { }

    /** 设置全局覆盖值（调用方负责重建界面） */
    public static void setOverrideDpi(int dpi) {
        overrideDpi = Math.max(0, dpi);
    }

    public static int getOverrideDpi() {
        return overrideDpi;
    }

    /**
     * 按当前覆盖值包装 Context。
     *
     * <p>未设置覆盖时原样返回，避免无谓包装。</p>
     */
    public static Context wrap(Context base) {
        if (base == null || overrideDpi <= 0) return base;
        try {
            Resources res = base.getResources();
            Configuration cfg = new Configuration(res.getConfiguration());
            cfg.densityDpi = overrideDpi;
            // Configuration.density 在部分 API 上不是公开可写字段，
            // 用 setTo 之外的方式可能编译失败；densityDpi 已足够驱动资源缩放，
            // 这里只保留 densityDpi 赋值。
            Context wrapped = base.createConfigurationContext(cfg);
            return wrapped != null ? wrapped : base;
        } catch (Throwable t) {
            return base;
        }
    }
}
