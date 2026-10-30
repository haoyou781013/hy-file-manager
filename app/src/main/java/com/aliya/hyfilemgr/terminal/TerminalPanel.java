package com.aliya.hyfilemgr.terminal;

import com.aliya.hyfilemgr.R;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
    /** Ctrl 锁定状态 */
    private boolean ctrlLocked = false;
    /** Ctrl 键视图（用于高亮） */
    private TextView ctrlKey;
    private TextView pwdView;

    private String workDir;

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

    /** 顶栏/终端区底色：跟随当前主题（文件列表用同一个 attr） */
    private int surfaceHigh() {
        try {
            return com.aliya.hyfilemgr.ui.UiKit.color(ctx,
                    com.google.android.material.R.attr.colorSurfaceContainer);
        } catch (Throwable t) {
            return 0xFF161B22;
        }
    }

    /** 正文色 */
    private int onSurface() {
        try {
            return com.aliya.hyfilemgr.ui.UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurface);
        } catch (Throwable t) {
            return 0xFFE0E0E0;
        }
    }

    private int primary() {
        try {
            return com.aliya.hyfilemgr.ui.UiKit.color(ctx,
                    com.google.android.material.R.attr.colorPrimary);
        } catch (Throwable t) {
            return 0xFF58A6FF;
        }
    }

    private int onSurfaceVariant() {
        try {
            return com.aliya.hyfilemgr.ui.UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant);
        } catch (Throwable t) {
            return 0xFF8B949E;
        }
    }

    private LinearLayout build() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(surfaceHigh());
        box.setClickable(true);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);

        // ── 融入式标题行：终端 | pwd ──
        // 不再是「浮层自己的顶栏」，而是与文件列表共享同一顶栏高度与配色，
        // 视觉上就像文件列表换了一屏内容，而不是弹出窗口。
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(10), dp(8), dp(10));
        top.setBackgroundColor(surfaceHigh());
        box.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tag = new TextView(ctx);
        tag.setText("终端");
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextColor(primary());
        top.addView(tag);

        TextView sep = new TextView(ctx);
        sep.setText("  |  ");
        sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sep.setTextColor(onSurfaceVariant());
        top.addView(sep);

        pwdView = new TextView(ctx);
        pwdView.setText(workDir);
        pwdView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pwdView.setTypeface(Typeface.MONOSPACE);
        pwdView.setTextColor(onSurfaceVariant());
        pwdView.setSingleLine(true);
        pwdView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        top.addView(pwdView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 操作入口与文件列表的 ⋮ 一致：三个文字按钮 + 关闭
        top.addView(btn("清屏", v -> clearOutput()));
        top.addView(btn("复制", v -> copyOutput()));
        top.addView(btn("中断", v -> {
            if (session != null) session.sendInterrupt();
        }));
        top.addView(btn("关闭", v -> {
            if (onClose != null) onClose.onClose();
        }));

        // 与文件列表一致的细分割线
        View headerDivider = new View(ctx);
        headerDivider.setBackgroundColor(onSurfaceVariant() & 0x33FFFFFF);
        box.addView(headerDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        // ── 终端视图（Termux 同款：直接在其上输入）──
        termView = new TerminalView(ctx);
        termView.setSink(new TerminalView.Sink() {
            @Override
            public void onText(String text) {
                // 本地回显：ProcessBuilder 起的是**非交互式** sh
                // （无 tty），它不回显输入也不输出提示符。
                // 上一版改成「只交 shell」后终端打字完全看不到内容。
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
                // 发 DEL(0x7F) 而非 BS(0x08)：交互式 toybox sh 的行编辑
                // 认 0x7F，0x08 会在 shell 缓冲里留一个多余控制字符，
                // 导致输入与显示不一致、执行时参数被污染。
                if (session != null && session.isRunning()) session.send("\u007f");
            }
        });
        // 非交互 sh 不输出 PS1，本地给一个提示符
        termView.setPrompt("$ ");
        termView.clear(banner());
        box.addView(termView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // ── 底部快捷键工具栏（带顶部分隔边框）──
        // Ctrl 为「点击保持」锁定键：点亮后再按字母即发送 Ctrl+字母，
        // 与真实键盘的 Ctrl 行为一致，而不是每个组合键一个按钮。
        LinearLayout toolBarWrap = new LinearLayout(ctx);
        toolBarWrap.setOrientation(LinearLayout.VERTICAL);
        // 分隔线：与上方终端输出区明确区分
        View toolDivider = new View(ctx);
        toolDivider.setBackgroundColor(0xFF30363D);
        toolBarWrap.addView(toolDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        // ── 工具栏（参考 ZeroTermux 的 extra keys 布局）──
        // 三行分组，每组用分隔线区隔：
        //   行1 功能键：Ctrl / Alt / Tab / Esc / Home / End
        //   行2 常用符号：| ~ / - _ $ * 空格
        //   行3 方向键：← ↑ ↓ →
        // 不放入 a~z（软键盘已有字母）。
        LinearLayout tools = new LinearLayout(ctx);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(6), dp(4), dp(6), dp(4));

        // 行1：功能键
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        ctrlKey = termKey("Ctrl", "", null);
        ctrlKey.setOnClickListener(v -> {
            ctrlLocked = !ctrlLocked;
            updateCtrlKeyState();
            termView.requestFocus();
        });
        row1.addView(ctrlKey);
        row1.addView(termKey("Tab", "\t", null));
        row1.addView(termKey("Esc", "\u001b", null));
        row1.addView(termKey("Home", "\u0001", null));
        row1.addView(termKey("End", "\u0005", null));
        row1.addView(termKey("PgUp", "\u001b[5~", null));
        row1.addView(termKey("PgDn", "\u001b[6~", null));
        tools.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 行2：常用符号
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(2);
        row2.addView(termKey("|", "|", null));
        row2.addView(termKey("~", "~", null));
        row2.addView(termKey("/", "/", null));
        row2.addView(termKey("-", "-", null));
        row2.addView(termKey("_", "_", null));
        row2.addView(termKey("$", "$", null));
        row2.addView(termKey("*", "*", null));
        row2.addView(termKey("\\", "\\", null));
        row2.addView(termKey("'", "'", null));
        row2.addView(termKey("\"", "\"", null));
        tools.addView(row2, row2Lp);

        // 行3：方向键（四个并排，不依赖横向滑动）
        LinearLayout row3 = new LinearLayout(ctx);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row3Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row3Lp.topMargin = dp(2);
        row3.addView(termKey("←", "\u001b[D", null));
        row3.addView(termKey("↑", "\u001b[A", null));
        row3.addView(termKey("↓", "\u001b[B", null));
        row3.addView(termKey("→", "\u001b[C", null));
        tools.addView(row3, row3Lp);

        toolBarWrap.addView(tools, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(toolBarWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateCtrlKeyState();


        // 打开即聚焦并弹键盘
        termView.postDelayed(() -> {
            termView.requestFocus();
            termView.showIme();
        }, 200);

        return box;
    }

    /**
     * 工具栏按键。
     *
     * <p>若 Ctrl 处于锁定状态且按下的是单个字母，则发送对应控制码
     * （k = letter &amp; 0x1F），与真实键盘的 Ctrl+字母 等价；
     * 同时解除 Ctrl 锁定（跟真实键盘一样，组合完成后释放）。</p>
     *
     * @param seq 固定要发送的转义序列（如方向键 ESC[A）；null 表示按标签处理
     */
    private TextView termKey(String label, final String seq,
                             View.OnClickListener extra) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFFC9D1D9);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackgroundResource(R.drawable.bg_ripple_round);
        tv.setClickable(true);
        tv.setOnClickListener(v -> {
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(120).start()).start();
            if (extra != null) {
                extra.onClick(v);
                return;
            }
            if (session == null || !session.isRunning()) return;

            String out = seq;
            boolean ctrlConsumed = false;
            // Ctrl 锁定 + 单字母 → 发送控制码
            if (ctrlLocked && label.length() == 1) {
                char c = Character.toLowerCase(label.charAt(0));
                if (c >= 'a' && c <= 'z') {
                    out = String.valueOf((char) (c & 0x1f));
                    ctrlConsumed = true;
                }
            }
            if (out == null || out.isEmpty()) return;
            session.send(out);
            // 可打印字符本地回显（控制字符不回显）
            if (!ctrlConsumed && out.length() == 1 && out.charAt(0) >= ' ') {
                termView.echo(out);
            }
            if (ctrlConsumed) {
                ctrlLocked = false;
                updateCtrlKeyState();
            }
            termView.requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 刷新 Ctrl 键高亮（锁定=主色底，未锁定=普通） */
    private void updateCtrlKeyState() {
        if (ctrlKey == null) return;
        if (ctrlLocked) {
            ctrlKey.setTextColor(0xFF0D1117);
            ctrlKey.setBackground(com.aliya.hyfilemgr.ui.UiKit.rounded(
                    ctx, dp(6), 0xFF58A6FF, 0));
            ctrlKey.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        } else {
            ctrlKey.setTextColor(0xFFC9D1D9);
            ctrlKey.setBackgroundResource(R.drawable.bg_ripple_round);
            ctrlKey.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        }
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
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
}
