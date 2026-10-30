package com.aliya.hyfilemgr.crash;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃展示页。
 *
 * <p>由 {@link CrashHandler} 冷启动；展示完整崩溃报告，右下角提供三个操作：</p>
 * <ul>
 *   <li><b>复制</b>：整份报告写入剪贴板</li>
 *   <li><b>导出</b>：写到公共「下载/HY文件管理-崩溃日志/」，无需存储权限（Android 10+ 走 MediaStore）</li>
 *   <li><b>重启</b>：直接重新拉起 App</li>
 * </ul>
 */
public class CrashActivity extends Activity {

    public static final String EXTRA_REPORT = "crash_report";
    public static final String EXTRA_LOG_PATH = "crash_log_path";

    /** 导出子目录（下载目录下） */
    private static final String EXPORT_DIR = "HY文件管理-崩溃日志";

    private String report = "";
    private String logPath = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent in = getIntent();
        report = in == null ? "" : orEmpty(in.getStringExtra(EXTRA_REPORT));
        logPath = in == null ? "" : orEmpty(in.getStringExtra(EXTRA_LOG_PATH));
        if (report.isEmpty() && !logPath.isEmpty()) {
            report = CrashHandler.readReport(this, logPath);
        }
        if (report.isEmpty()) report = "（未获取到崩溃信息）";

        setContentView(buildUi());
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ── UI ──

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF12151A);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, pad);

        // 标题
        TextView title = new TextView(this);
        title.setText("应用崩溃了");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFF6B6B);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("以下信息可帮助定位问题。日志已自动保存在应用缓存目录。");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(0xFF8A94A6);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(10);
        root.addView(sub, subLp);

        // 报告正文（可滚动、可选中）
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF1B1F26);
        int sp = dp(12);
        scroll.setPadding(sp, sp, sp, sp);
        TextView body = new TextView(this);
        body.setText(report);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        body.setTextColor(0xFFD5DBE5);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        // 底部按钮行：左侧「关闭」 右侧「复制 / 导出 / 重启」
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(14);
        root.addView(btns, btnsLp);

        btns.addView(makeBtn("关闭", 0xFF3A4048, v -> finish()), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        btns.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        btns.addView(makeBtn("复制", 0xFF2A3340, v -> copyReport()));
        btns.addView(makeBtn("导出", 0xFF2A3340, v -> exportReport()));
        btns.addView(makeBtn("重启", 0xFF1E88E5, v -> restartApp()));

        return root;
    }

    /** 胶囊按钮 */
    private TextView makeBtn(String text, int bgColor, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(9), dp(16), dp(9));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(bgColor);
        tv.setBackground(g);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(130).start()).start();
            onClick.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ── 三个操作 ──

    /** 复制整份报告到剪贴板 */
    private void copyReport() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("剪贴板不可用");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("HY 崩溃日志", report));
            toast("已复制崩溃日志（" + report.length() + " 字符）");
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    /**
     * 导出到「下载/HY文件管理-崩溃日志/」。
     *
     * <p>Android 10+ 走 MediaStore（无需存储权限）；低版本用公共 Download 目录 + WRITE 权限。</p>
     */
    private void exportReport() {
        String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".log";
        try {
            String saved;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // ── MediaStore: Downloads ──
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_DIR);
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    toast("导出失败：无法创建文件");
                    return;
                }
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        toast("导出失败：无法打开输出流");
                        return;
                    }
                    out.write(report.getBytes("UTF-8"));
                    out.flush();
                }
                saved = Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_DIR + "/" + name;
            } else {
                // ── 低版本：直接写公共 Download ──
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), EXPORT_DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    toast("导出失败：无法创建目录");
                    return;
                }
                File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(report.getBytes("UTF-8"));
                    out.flush();
                }
                saved = f.getAbsolutePath();
            }
            toast("已导出到：\n" + saved);
        } catch (Throwable t) {
            toast("导出失败：" + t);
        }
    }

    /** 重启应用：结束当前进程后由系统/自身重新拉起 */
    private void restartApp() {
        try {
            Intent i = new Intent(this, com.aliya.hyfilemgr.MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            // 用 PendingIntent + AlarmManager 延迟拉起：
            // 原来 startActivity → finish → killProcess 连发，进程在
            // MainActivity 冷启动前就被杀掉，重启必然失败。
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 0, i,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            // 三段各自 try：setExact 在 API 31+ 无 SCHEDULE_EXACT_ALARM 时抛
            // SecurityException，若与 finishAffinity/kill 同块会被连带跳过，
            // 导致进程不退出、崩溃页留在栈上。
            // 改用 set()（非精确闹钟，免权限，400ms 精度对重启足够）。
            try {
                android.app.AlarmManager am =
                        (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null) {
                    am.set(android.app.AlarmManager.RTC,
                            System.currentTimeMillis() + 400, pi);
                }
            } catch (Throwable ignored) {
                // 闹钟设不上时退化为立即 startActivity
                try {
                    startActivity(i);
                } catch (Throwable ignored2) {
                }
            }

            try {
                finishAffinity();
            } catch (Throwable ignored) {
            }

            // 给 Alarm 留下调度时间再退出（不要立即 kill）
            try {
                new android.os.Handler(getMainLooper()).postDelayed(
                        () -> android.os.Process.killProcess(android.os.Process.myPid()),
                        200);
            } catch (Throwable ignored) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        } catch (Throwable t) {
            toast("重启失败：" + t);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
