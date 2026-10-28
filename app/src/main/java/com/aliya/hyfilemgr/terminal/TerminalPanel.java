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
    private TextView output;
    private EditText input;      // 0 宽隐藏输入框，仅接管键盘
    private TextView inputDisplay; // 可见的输入回显
    private ScrollView scroll;
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
        top.addView(btn("中断", v -> {
            if (session != null) session.sendInterrupt();
        }));
        top.addView(btn("关闭", v -> {
            if (onClose != null) onClose.onClose();
        }));

        // ── 输出区 ──
        scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        output = new TextView(ctx);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextColor(0xFFC9D1D9);
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());
        output.setPadding(dp(12), dp(10), dp(12), dp(10));
        output.setText(banner());
        scroll.addView(output, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 输入行 ──
        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(10), dp(4), dp(8), dp(8));
        inputRow.setBackgroundColor(0xFF161B22);
        box.addView(inputRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView prompt = new TextView(ctx);
        prompt.setText("$ ");
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        prompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        prompt.setTextColor(0xFF7EE787);
        inputRow.addView(prompt);

        // ── Termux 同款输入方案 ──
        // 不用可见 EditText 承载输入（它一旦 setText 就会触发焦点/IME 重算，
        // 部分 ROM 上直接收起键盘）。改为：
        //   ① 一个 0 宽度的隐藏 EditText 只负责「接住」输入法事件
        //   ② 可见的是一行自绘 TextView（显示当前输入缓冲）
        //   ③ 隐藏 EditText 始终持有焦点，键盘自然常驻
        inputDisplay = new TextView(ctx);
        inputDisplay.setText("");
        inputDisplay.setHint("输入命令…");
        inputDisplay.setHintTextColor(0xFF484F58);
        inputDisplay.setTextColor(0xFFC9D1D9);
        inputDisplay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        inputDisplay.setTypeface(Typeface.MONOSPACE);
        inputDisplay.setSingleLine(true);
        inputDisplay.setPadding(0, dp(8), dp(6), dp(8));
        inputDisplay.setOnClickListener(v -> keepFocus());
        inputRow.addView(inputDisplay, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 0 宽隐藏输入框：只用来接管键盘
        input = new EditText(ctx);
        input.setBackgroundColor(0x00000000);
        input.setAlpha(0f);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s2, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e2) {
                inputDisplay.setText(e2.toString());
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            runCommand();
            return true;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, dp(1)));

        inputRow.addView(btn("执行", v -> runCommand()));

        // 启动后自动聚焦并弹起输入法
        input.postDelayed(() -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, 0);
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

    private void runCommand() {
        String cmd = input.getText().toString();
        // 只清空内容，不动焦点：隐藏输入框始终保持 focus，键盘不会收起
        input.setText("");
        inputDisplay.setText("");
        if (cmd.trim().isEmpty()) {
            appendOutput("$ \n");
            keepFocus();
            return;
        }
        appendOutput("$ " + cmd + "\n");
        if (session != null) session.sendLine(cmd);
        // cd 后同步顶栏 pwd 显示
        if (cmd.trim().startsWith("cd ")) {
            String arg = cmd.trim().substring(3).trim();
            if (!arg.isEmpty() && !arg.startsWith("-")) {
                workDir = arg.startsWith("/") ? arg
                        : (workDir.endsWith("/") ? workDir + arg : workDir + "/" + arg);
                pwdView.setText(workDir);
            }
        }
        keepFocus();
    }

    /** 保持输入框焦点并让输入法继续显示，避免执行后键盘收起 */
    private void keepFocus() {
        if (input == null) return;
        if (!input.hasFocus()) input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            // 已显示就不重复调用（重复调用在某些 ROM 上反而会闪收）
            if (!imm.isActive(input)) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private void appendOutput(String text) {
        buffer.append(text);
        if (buffer.length() > MAX_LINES * 80) {
            int cut = buffer.length() - MAX_LINES * 60;
            int nl = buffer.indexOf("\n", Math.max(0, cut));
            if (nl > 0) buffer.delete(0, nl + 1);
        }
        output.setText(buffer.toString());
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void clearOutput() {
        buffer.setLength(0);
        buffer.append(banner());
        output.setText(buffer.toString());
        keepFocus();
    }

    private void copyOutput() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(
                            Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "终端输出", buffer.toString()));
                android.widget.Toast.makeText(ctx, "已复制全部输出",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "复制失败：" + t,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 隐藏输入法（面板关闭前调用） */
    public void hideKeyboard() {
        if (input == null) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
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
