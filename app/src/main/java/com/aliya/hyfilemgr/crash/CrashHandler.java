package com.aliya.hyfilemgr.crash;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全局崩溃捕获框架。
 *
 * <p>安装为默认 UncaughtExceptionHandler；任意线程崩溃后：</p>
 * <ol>
 *   <li>收集崩溃信息（堆栈 / 设备 / 版本 / 线程 / 时间）</li>
 *   <li>写入 {@code cacheDir/crash/crash_<时间戳>.log}</li>
 *   <li>冷启动 {@link CrashActivity} 展示弹窗（可复制 / 导出 / 重启）</li>
 *   <li>结束当前进程</li>
 * </ol>
 *
 * <p>只保留最近 {@link #MAX_KEEP} 份日志，避免占满缓存。</p>
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "HYCrash";
    /** 最多保留的日志份数 */
    private static final int MAX_KEEP = 20;

    private static Context appCtx;
    private static Thread.UncaughtExceptionHandler previous;

    private CrashHandler() { }

    /** 在 Application.onCreate 中调用一次 */
    public static void install(Context ctx) {
        appCtx = ctx.getApplicationContext();
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());
        Log.i(TAG, "crash handler installed, logDir=" + logDir(ctx));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            String detail = buildReport(thread, ex);
            File f = save(detail);
            Log.e(TAG, "crash captured → " + (f == null ? "(save failed)" : f.getName()));

            // 冷启动崩溃页（独立进程任务栈，避免受当前崩溃 Activity 影响）
            if (appCtx != null) {
                Intent i = new Intent(appCtx, CrashActivity.class);
                i.putExtra(CrashActivity.EXTRA_REPORT, detail);
                i.putExtra(CrashActivity.EXTRA_LOG_PATH, f == null ? "" : f.getAbsolutePath());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                appCtx.startActivity(i);
            }
        } catch (Throwable self) {
            Log.e(TAG, "crash handler failed", self);
        } finally {
            // 交给原处理器（便于系统统计），随后结束进程
            if (previous != null && previous != this) {
                try {
                    previous.uncaughtException(thread, ex);
                } catch (Throwable ignored) {
                }
            }
            try {
                Thread.sleep(400);   // 给 CrashActivity 一点启动时间
            } catch (InterruptedException ignored) {
            }
            Process.killProcess(Process.myPid());
            System.exit(10);
        }
    }

    // ── 报告生成 ──

    /** 组装完整崩溃报告文本 */
    public static String buildReport(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder(2048);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());

        sb.append("===== HY 文件管理 崩溃报告 =====\n");
        sb.append("时间: ").append(time).append('\n');
        sb.append("线程: ").append(thread == null ? "?" : thread.getName())
                .append(" (id=").append(thread == null ? -1 : thread.getId()).append(")\n");
        sb.append("进程: ").append(Build.VERSION.SDK_INT >= 28
                ? android.app.Application.getProcessName() : "?").append('\n');
        sb.append('\n');

        // ── 设备信息 ──
        sb.append("--- 设备 ---\n");
        sb.append("型号: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("品牌: ").append(Build.BRAND).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("ABI : ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        sb.append('\n');

        // ── 应用信息 ──
        sb.append("--- 应用 ---\n");
        if (appCtx != null) {
            try {
                PackageInfo pi = appCtx.getPackageManager()
                        .getPackageInfo(appCtx.getPackageName(), 0);
                sb.append("包名: ").append(pi.packageName).append('\n');
                sb.append("版本: ").append(pi.versionName)
                        .append(" (").append(pi.versionCode).append(")\n");
            } catch (Throwable ignored) {
                sb.append("包名: ").append(appCtx.getPackageName()).append('\n');
            }
        }
        sb.append('\n');

        // ── 异常信息 ──
        sb.append("--- 异常 ---\n");
        sb.append(ex == null ? "(no throwable)" : ex.toString()).append("\n\n");

        // ── 完整堆栈（含 Caused by 链）──
        sb.append("--- 堆栈 ---\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (ex != null) ex.printStackTrace(pw);
        pw.flush();
        sb.append(sw.toString());

        return sb.toString();
    }

    // ── 落盘 ──

    /**
     * 日志目录。
     *
     * <p>优先 {@code getExternalFilesDir("crash")}（Android/data/包名/files/crash），
     * 便于用户用文件管理器直接查看；不可用时退回 {@code cacheDir/crash}。</p>
     */
    public static File logDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        File d = new File(base != null ? base : ctx.getFilesDir(), "crash");
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + d);
        }
        return d;
    }

    /** 保存报告，返回文件；失败返回 null */
    private static File save(String content) {
        if (appCtx == null) return null;
        File dir = logDir(appCtx);
        String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date()) + ".log";
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            Log.e(TAG, "save failed", t);
            return null;
        }
        trim(dir);
        return f;
    }

    /** 只保留最近 MAX_KEEP 份 */
    private static void trim(File dir) {
        File[] all = dir.listFiles((d, n) -> n.startsWith("crash_") && n.endsWith(".log"));
        if (all == null || all.length <= MAX_KEEP) return;
        Arrays.sort(all, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < all.length - MAX_KEEP; i++) {
            //noinspection ResultOfMethodCallIgnored
            all[i].delete();
        }
    }

    /** 全部历史日志（新→旧） */
    public static List<File> history(Context ctx) {
        File[] all = logDir(ctx).listFiles((d, n) -> n.startsWith("crash_") && n.endsWith(".log"));
        if (all == null) return new ArrayList<>();
        Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return new ArrayList<>(Arrays.asList(all));
    }

    /** 读取崩溃报告全文（含堆栈） */
    public static String readReport(Context ctx, String path) {
        if (path == null || path.isEmpty()) return "";
        try (BufferedReader r = new BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(path), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败：" + t;
        }
    }
}
