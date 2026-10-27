package com.aliya.hyfilemgr.util;

import android.content.Context;

/**
 * DPI 覆盖占位实现。
 *
 * <p>HY_VQ 原版用它让「设置→显示密度」只对本应用生效；HY文件管理 没有该设置项，
 * 这里直接原样返回，保持调用点不变。</p>
 */
public final class DpiUtils {
    private DpiUtils() { }

    public static Context wrap(Context base) {
        return base;
    }
}
