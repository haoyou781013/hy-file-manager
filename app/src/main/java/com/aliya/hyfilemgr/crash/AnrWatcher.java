package com.aliya.hyfilemgr.crash;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ANR 记录采集。
 *
 * <p><b>为什么不自建看门狗</b>：常见做法是主线程 post 一个心跳，超时未响应
 * 就判定 ANR。但这条路有三个问题：</p>
 * <ol>
 *   <li>误报率高 —— 主线程被正常的长任务（如解压大包）占用时也会触发；</li>
 *   <li>拿不到系统判定依据 —— 自己判的 ANR 和系统判的 ANR 不是一回事；</li>
 *   <li>需要常驻线程，与省电策略冲突。</li>
 * </ol>
 *
 * <p>Android 11（API 30）起系统提供了
 * {@link ActivityManager#getHistoricalProcessExitReasons()}，
 * 可以直接读取**系统已经判定为 ANR 的退出原因**，准确且零运行时开销。
 * 本项目 minSdk 23，所以只在 API 30+ 启用，低版本跳过。</p>
 *
 * <p><b>去重</b>：系统会保留多条历史退出记录，每次启动都读会导致同一个 ANR
 * 被反复报告。用 SharedPreferences 记住上次处理过的 timestamp，只看更新的。</p>
 */
public final class AnrWatcher {

    private static final String TAG = "HYAnr";
    private static final String PREFS = "hyvq_filemgr";
    private static final String KEY_LAST_TS = "anr_last_captured_ts";

    /** 单次最多读取多少条历史退出记录 */
    private static final int MAX_RECORDS = 5;

    private AnrWatcher() {}

    /**
     * 检查并采集上次运行期间的 ANR。
     *
     * <p>应在 Application.onCreate 里调用（越早越好，但要放在崩溃处理器
     * 安装之后，以便写入失败时还能记录）。</p>
     *
     * @return 新采集到的 ANR 报告文件；没有则返回 null
     */
    public static File captureIfNeeded(Context ctx) {
        if (ctx == null) return null;
        if (Build.VERSION.SDK_INT < 30) {
            // API 30 以下没有 getHistoricalProcessExitReasons
            return null;
        }
        try {
            Context app = ctx.getApplicationContext();
            ActivityManager am = (ActivityManager)
                    app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;

            long lastTs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_TS, 0L);

            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(
                    app.getPackageName(), 0, MAX_RECORDS);
            if (list == null || list.isEmpty()) return null;

            long newestTs = lastTs;
            File saved = null;

            for (ApplicationExitInfo info : list) {
                long ts = info.getTimestamp();
                if (ts <= lastTs) continue;      // 已处理过

                int reason = info.getReason();
                // 只关心 ANR 与「初始化失败」，其余（正常退出、被用户划掉、
                // 内存不足被杀）都不算需要用户知道的异常
                boolean isAnr = reason == ApplicationExitInfo.REASON_ANR;
                boolean isCrash = reason == ApplicationExitInfo.REASON_CRASH
                        || reason == ApplicationExitInfo.REASON_CRASH_NATIVE;
                if (!isAnr && !isCrash) {
                    if (ts > newestTs) newestTs = ts;
                    continue;
                }

                String report = buildReport(app, info);
                File f = write(app, report, reason);
                if (f != null) saved = f;
                if (ts > newestTs) newestTs = ts;
            }

            if (newestTs > lastTs) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_TS, newestTs).apply();
            }
            return saved;
        } catch (Throwable t) {
            // 采集失败绝不能影响启动
            Log.w(TAG, "capture anr failed", t);
            return null;
        }
    }

    /**
     * @return 退出原因的中文说明
     *
     * <p>参数虽是 int，但常量来自 {@link ApplicationExitInfo}（API 30），
     * 故整个方法标注 {@code @RequiresApi(30)}。调用方必须先判断版本。</p>
     */
    @androidx.annotation.RequiresApi(30)
    static String reasonText(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR:
                return "应用无响应（ANR）";
            case ApplicationExitInfo.REASON_CRASH:
                return "Java 崩溃（系统记录）";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "Native 崩溃（系统记录）";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "内存不足被系统回收";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "被信号终止";
            default:
                return "退出原因 " + reason;
        }
    }

    /**
     * 组装报告。
     *
     * <p>只包含系统给的信息 —— 这里没有堆栈，因为进程已经死了。
     * 想看堆栈得靠系统 dropbox（需要 root）。报告里如实说明这点，
     * 避免用户以为是日志丢了。</p>
     */
    @androidx.annotation.RequiresApi(30)
    static String buildReport(Context ctx, ApplicationExitInfo info) {
        StringBuilder sb = new StringBuilder(1024);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(info.getTimestamp()));

        sb.append("===== HY 文件管理 异常退出记录 =====\n");
        sb.append("时间: ").append(time).append('\n');
        sb.append("类型: ").append(reasonText(info.getReason())).append('\n');
        sb.append("原因码: ").append(info.getReason()).append('\n');
        String desc = info.getDescription();
        if (desc != null && !desc.isEmpty()) {
            sb.append("系统描述: ").append(desc).append('\n');
        }
        sb.append("重要性: ").append(info.getImportance()).append('\n');
        sb.append("进程名: ").append(info.getProcessName()).append('\n');
        sb.append("PID: ").append(info.getPid()).append('\n');
        sb.append('\n');

        sb.append("--- 设备 ---\n");
        sb.append("型号: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append('\n');

        sb.append("--- 应用 ---\n");
        sb.append("包名: ").append(ctx.getPackageName()).append('\n');
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            sb.append("版本: ").append(pi.versionName)
                    .append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable ignored) {
        }
        sb.append('\n');

        sb.append("--- 说明 ---\n");
        sb.append("此记录来自系统 ApplicationExitInfo，不含 Java 堆栈。\n");
        sb.append("进程在异常发生时已被终止，堆栈需从系统 dropbox 获取（需要 root）。\n");
        sb.append("如需定位该问题，请同时提供触发前的操作步骤。\n");

        return sb.toString();
    }

    /** 写入崩溃目录（与 CrashHandler 共用同一个目录，便于统一查看） */
    private static File write(Context ctx, String content, int reason) {
        try {
            File dir = CrashHandler.logDir(ctx);
            String tag = reason == ApplicationExitInfo.REASON_ANR ? "anr" : "exit";
            String name = tag + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date()) + ".log";
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(content.getBytes("UTF-8"));
                out.flush();
                try {
                    out.getFD().sync();     // 与 CrashHandler 同理：必须刷盘
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "anr/exit record saved → " + f.getName());
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "write anr record failed", t);
            return null;
        }
    }
}
