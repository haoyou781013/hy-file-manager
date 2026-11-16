package com.aliya.hyfilemgr.access;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 访问引擎工具：格式化（与 FileOps 对齐的展示逻辑，避免引擎层依赖操作层）。
 */
public final class AccessUtil {

    private AccessUtil() {}

    /** 人类可读文件大小（B/KB/MB/GB） */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    /** 格式化修改时间：今年 MM-dd HH:mm，跨年 yyyy-MM-dd */
    /**
     * 格式化修改时间：今年 MM-dd HH:mm，跨年 yyyy-MM-dd。
     *
     * <p><b>旧实现的性能问题</b>：每次调用都 {@code Calendar.getInstance()} 两次
     * （一个给目标时间、一个给当前时间）+ {@code new SimpleDateFormat(...)} +
     * {@code new Date()}。而本方法在 {@code FileListAdapter.buildSub} 里
     * <b>每个文件行都调一次</b>，绑定 100 行 = 200 次 Calendar + 100 次 SDF 构造。
     * SimpleDateFormat 的构造要解析 pattern 并建 DateFormatSymbols，是重活。</p>
     *
     * <p><b>改法</b>：两个 pattern 各自缓存一份 {@code ThreadLocal<SimpleDateFormat>}；
     * 年份判断改为「一次算年 + 对比缓存的本年」。{@code SimpleDateFormat} 非线程安全，
     * 用 ThreadLocal 保证安全（相册扫描等后台线程也会调本方法）。</p>
     */
    private static final ThreadLocal<SimpleDateFormat> SDF_SAME_YEAR =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                }
            };
    private static final ThreadLocal<SimpleDateFormat> SDF_OTHER_YEAR =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                }
            };

    /**
     * 缓存「今年」的年份值。每年重新取一次，避免每次调用都建 Calendar 去查年份。
     * 用 volatile 保证多线程可见性（后台扫描线程也会读）。
     */
    private static volatile int cachedYear = -1;

    /** 取时间戳对应的年份（用 Calendar 的轻量字段，不建完整对象不可行，故做本年缓存） */
    private static int yearOf(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        return cal.get(Calendar.YEAR);
    }

    public static String formatDate(long millis) {
        if (millis <= 0) return "-";
        // 「今年」只需每年重新算一次：全年内 99.99% 的调用都能命中缓存
        int nowYear = cachedYear;
        if (nowYear < 0) {
            nowYear = yearOf(System.currentTimeMillis());
            cachedYear = nowYear;
        }
        int fileYear = yearOf(millis);
        SimpleDateFormat sdf = (fileYear == nowYear)
                ? SDF_SAME_YEAR.get() : SDF_OTHER_YEAR.get();
        return sdf.format(new Date(millis));
    }
}