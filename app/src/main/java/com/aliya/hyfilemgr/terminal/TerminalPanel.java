package com.aliya.hyfilemgr.terminal;

import android.content.Context;
import android.graphics.Typeface;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 内置终端面板（嵌在主界面内，不切换 Activity）。
 *
 * <p>顶栏：标题「终端 | 当前 pwd」+ 清屏/复制/中断/关闭；
 * 中部：等宽字体输出区；底部：$ 提示符 + 输入框。</p>
 *
 * <p>关键：执行命令后<b>不</b>让输入框失焦，输入法保持弹起，可连续输入。</p>
 */
public class TerminalPanel {

    /** 关闭回调（由宿主移除面板） */
    public interface OnClose {
        void onClose();
    }

    private static final int MAX_LINES = 3000;

    private final Context ctx;
    private final OnClose onClose;
    private final LinearLayout root;

    private TerminalSession session;
    private TerminalView termView;
    private TextView pwdView;
    private final StringBuilder buffer = new StringBuilder();

    private String workDir;
    private int dpCache = -1;

    public TerminalPanel(Context ctx, String workDir, OnClose onClose) {
        this.ctx = ctx;
        this.workDir = (workDir == null || workDir.isEmpty()) ? "/sdcard" : workDir;
        this.onClose = onClose;
        this.root = build();
        this.root.setTag(this);
    }

    public View getView() {
        return root;
    }

    // ── UI ──

    private LinearLayout build() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF0D1117);
        box.setClickable(true);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);

        // ── 顶栏：终端 | pwd ──
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(8), dp(6), dp(8));
        top.setBackgroundColor(0xFF161B22);
        box.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tag = new TextView(ctx);
        tag.setText("终端");
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tag.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tag.setTextColor(0xFF7EE787);
        top.addView(tag);

        TextView sep = new TextView(ctx);
        sep.setText(" | ");
        sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sep.setTypeface(Typeface.MONOSPACE);
        sep.setTextColor(0xFF484F58);
        top.addView(sep);

        pwdView = new TextView(ctx);
        pwdView.setText(workDir);
        pwdView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pwdView.setTypeface(Typeface.MONOSPACE);
        pwdView.setTextColor(0xFF8B949E);
        pwdView.setSingleLine(true);
        pwdView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        top.addView(pwdView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(btn("清屏", v -> clearOutput()));
        top.addView(btn("复制", v -> copyOutput()));
        top.addView(btn("Ctrl+C", v -> {
            if (session != null) session.sendInterrupt();
            termView.append("^C\n");
        }));
        top.addView(btn("关闭", v -> {
            if (onClose != null) onClose.onClose();
        }));

        // ── 终端视图（Termux 同款：直接在其上输入）──
        termView = new TerminalView(ctx);
        termView.setSink(new TerminalView.Sink() {
            @Override
            public void onText(String text) {
                // 本地回显（带提示符 + 光标） + 送给 shell
                termView.echo(text);
                if (session != null && session.isRunning()) session.send(text);
            }

            @Override
            public void onEnter() {
                termView.newlineEcho();
                if (session != null && session.isRunning()) session.send("\n");
                // cd 后同步顶栏 pwd
                String line = termView.currentLine();
                if (line.trim().startsWith("cd ")) {
                    String arg = line.trim().substring(3).trim();
                    if (!arg.isEmpty() && !arg.startsWith("-")) {
                        workDir = arg.startsWith("/") ? arg
                                : (workDir.endsWith("/") ? workDir + arg : workDir + "/" + arg);
                        pwdView.setText(workDir);
                    }
                }
            }

            @Override
            public void onBackspace() {
                termView.backspaceEcho();
                if (session != null && session.isRunning()) session.send("\b");
            }
        });
        termView.clear(banner());
        box.addView(termView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 打开即聚焦并弹键盘
        termView.postDelayed(() -> {
            termView.requestFocus();
            termView.showIme();
        }, 200);

        return box;
    }

    private TextView btn(String text, View.OnClickListener onClick) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(0xFF58A6FF);
        tv.setPadding(dp(8), dp(6), dp(8), dp(6));
        tv.setClickable(true);
        tv.setOnClickListener(v -> {
            v.animate().alpha(0.55f).setDuration(60)
                    .withEndAction(() -> v.animate().alpha(1f).setDuration(120).start())
                    .start();
            onClick.onClick(v);
        });
        return tv;
    }

    private String banner() {
        return "HY 终端 · 轻量版（系统 shell，无 proot）\n"
                + "目录：" + workDir + "\n"
                + "可用：ls / cat / cp / mv / rm / ps / grep / find / toybox ...\n"
                + "─────────────────────────────\n";
    }

    // ── 会话 ──

    public void start() {
        if (session != null && session.isRunning()) return;
        session = new TerminalSession(workDir, new TerminalSession.Callback() {
            @Override
            public void onOutput(String text) {
                appendOutput(text);
            }

            @Override
            public void onExit(int exitCode) {
                appendOutput("\n[会话已结束，退出码 " + exitCode + "]\n");
            }
        });
        session.start();
    }

    /** 输出追加（来自 shell） */
    private void appendOutput(String text) {
        if (termView == null) return;
        termView.append(text);
    }

    private void clearOutput() {
        if (termView != null) termView.clear(banner());
    }

    private void copyOutput() {
        try {
            String text = termView != null ? termView.dump() : "";
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(
                            Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("终端输出", text));
                android.widget.Toast.makeText(ctx, "已复制全部输出",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "复制失败：" + t,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 供宿主在面板显示后调用，聚焦终端并弹键盘 */
    public void requestInputFocus() {
        if (termView != null) {
            termView.requestFocus();
            termView.showIme();
        }
    }

    public void hideKeyboard() {
        if (termView != null) termView.hideIme();
    }

    public void close() {
        hideKeyboard();
        if (session != null) session.close();
        session = null;
    }

    private int dp(int v) {
        if (dpCache < 0) {
            dpCache = 1;
        }
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
}
