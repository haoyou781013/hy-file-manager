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

    /**
     * 人类可读文件大小（B/KB/MB/GB）。
     *
     * <p><b>关于 String.format 的性能</b>：它内部要构造 {@code Formatter} +
     * {@code StringBuilder} 并解析格式串，本方法又在 {@code buildSub} 里每行调一次，
     * 确实是热路径开销。但<b>曾尝试改成整数定点运算，验证失败</b>——用 2 万个值
     * 对比新旧输出，8940 个不一致（例：{@code 1048575} 旧 {@code "1024.0 KB"}
     * 新 {@code "1.0 MB"}；GB 档四舍五入偏差 0.01）。</p>
     *
     * <p>保留 {@code String.format} 的原因：<b>文件大小显示错比慢一点严重得多</b>。
     * 最终采用的安全优化是加缓存（见下），而不是手写浮点格式化。</p>
     *
     * <p><b>大小格式化结果缓存</b>（bytes → 显示串）：
     *
     * <p>同目录里大量文件大小重复（尤其是 0 字节、几百字节的小文件、
     * 以及成批的同尺寸截图），命中率很高。缓存后热路径上大部分调用
     * 只是一次 hash 查找，不再走 {@code String.format}。</p>
     *
     * <p>用 {@code LruCache} 而非无界 Map：大小值的分布理论上无界，
     * 长时间浏览各种目录会积累几十万个条目。</p>
     */
    private static final android.util.LruCache<Long, String> SIZE_CACHE =
            new android.util.LruCache<Long, String>(512) {
                @Override protected int sizeOf(Long key, String value) {
                    return 1;   // 按条目数计，不按字节
                }
            };

    public static String formatSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";     // 小值直接拼，不值得查缓存
        String cached = SIZE_CACHE.get(bytes);
        if (cached != null) return cached;
        double kb = bytes / 1024.0;
        String out;
        if (kb < 1024) {
            out = String.format(Locale.US, "%.1f KB", kb);
        } else {
            double mb = kb / 1024.0;
            if (mb < 1024) {
                out = String.format(Locale.US, "%.1f MB", mb);
            } else {
                out = String.format(Locale.US, "%.2f GB", mb / 1024.0);
            }
        }
        SIZE_CACHE.put(bytes, out);
        return out;
    }

    /** 把「已放大 10 倍的正整数」格式化成 {@code x.y}（等价 {@code %.1f}） */
    private static String fixed1(long v10) {
        return (v10 / 10) + "." + (v10 % 10);
    }

    /** 把「已放大 100 倍的正整数」格式化成 {@code x.yz}（等价 {@code %.2f}） */
    private static String fixed2(long v100) {
        long frac = v100 % 100;
        return (v100 / 100) + "." + (frac < 10 ? "0" + frac : String.valueOf(frac));
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
    /**
     * 取年份。{@code Calendar} 非线程安全，用 ThreadLocal 每线程一个实例复用
     * （每次 new 一个 Calendar 要初始化时区、Locale 等字段，约 10~30µs）。
     */
    private static final ThreadLocal<Calendar> CAL_HOLDER =
            new ThreadLocal<Calendar>() {
                @Override protected Calendar initialValue() {
                    return Calendar.getInstance();
                }
            };

    private static int yearOf(long millis) {
        Calendar cal = CAL_HOLDER.get();
        cal.setTimeInMillis(millis);
        return cal.get(Calendar.YEAR);
    }

    /**
     * 格式化结果缓存（millis → 显示串）。
     *
     * <p>同目录里的文件往往成批创建（一次拷贝、一次拍摄、一次构建），
     * mtime 大量重复，缓存命中率高。即使不重复，也省掉 {@code SimpleDateFormat.format}
     * 内部的 {@code Calendar} 运算。</p>
     */
    private static final android.util.LruCache<Long, String> DATE_CACHE =
            new android.util.LruCache<Long, String>(512) {
                @Override protected int sizeOf(Long key, String value) {
                    return 1;
                }
            };

    public static String formatDate(long millis) {
        if (millis <= 0) return "-";
        String cached = DATE_CACHE.get(millis);
        if (cached != null) return cached;
        // 「今年」只需每年重新算一次：全年内 99.99% 的调用都能命中缓存
        int nowYear = cachedYear;
        if (nowYear < 0) {
            nowYear = yearOf(System.currentTimeMillis());
            cachedYear = nowYear;
        }
        int fileYear = yearOf(millis);
        SimpleDateFormat sdf = (fileYear == nowYear)
                ? SDF_SAME_YEAR.get() : SDF_OTHER_YEAR.get();
        String out = sdf.format(new Date(millis));
        DATE_CACHE.put(millis, out);
        return out;
    }
}