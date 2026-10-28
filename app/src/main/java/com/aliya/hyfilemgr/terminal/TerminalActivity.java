package com.aliya.hyfilemgr.terminal;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
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
import android.widget.Toast;

/**
 * 轻量终端界面。
 *
 * <p>单 Activity：上方输出区（可滚动、等宽字体）+ 下方命令输入框。
 * 命令逐行执行，输出实时追加。支持 Ctrl+C 中断、清屏、复制全部输出。</p>
 */
public class TerminalActivity extends Activity {

    public static final String EXTRA_WORKDIR = "term_workdir";
    public static final String EXTRA_TITLE = "term_title";

    /** 输出区保留的最大行数（超出截断，避免长会话吃内存） */
    private static final int MAX_LINES = 3000;

    private TerminalSession session;
    private TextView output;
    private EditText input;
    private ScrollView scroll;
    private final StringBuilder buffer = new StringBuilder();
    private String workDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        workDir = in == null ? null : in.getStringExtra(EXTRA_WORKDIR);
        String title = in == null ? null : in.getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) title = "终端";

        setContentView(buildUi(title));
        startSession();
    }

    // ── UI ──

    private View buildUi(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);   // 深色终端底

        // 顶部栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(10), dp(8), dp(8));
        top.setBackgroundColor(0xFF161B22);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(0xFFFFFFFF);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        top.addView(back);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvTitle.setTextColor(0xFF7EE787);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(topBtn("清屏", v -> clearOutput()));
        top.addView(topBtn("复制", v -> copyOutput()));
        top.addView(topBtn("中断", v -> {
            if (session != null) session.sendInterrupt();
        }));

        // 输出区
        scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextColor(0xFFC9D1D9);
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());
        int p = dp(12);
        output.setPadding(p, p, p, p);
        output.setText(banner());
        scroll.addView(output, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 输入行
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(10), dp(6), dp(10), dp(10));
        inputRow.setBackgroundColor(0xFF161B22);
        root.addView(inputRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView prompt = new TextView(this);
        prompt.setText("$ ");
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        prompt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        prompt.setTextColor(0xFF7EE787);
        inputRow.addView(prompt);

        input = new EditText(this);
        input.setHint("输入命令…");
        input.setHintTextColor(0xFF484F58);
        input.setTextColor(0xFFC9D1D9);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackgroundColor(0x00000000);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(0, dp(6), 0, dp(6));
        input.setOnEditorActionListener((v, actionId, event) -> {
            runCommand();
            return true;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        inputRow.addView(topBtn("执行", v -> runCommand()));
        return root;
    }

    private TextView topBtn(String text, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(0xFF58A6FF);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setClickable(true);
        tv.setOnClickListener(v -> {
            v.animate().alpha(0.6f).setDuration(60)
                    .withEndAction(() -> v.animate().alpha(1f).setDuration(120).start())
                    .start();
            onClick.onClick(v);
        });
        return tv;
    }

    private String banner() {
        return "HY 终端 · 轻量版（系统 shell，无 proot）\n"
                + "工作目录：" + (workDir != null ? workDir : "/sdcard") + "\n"
                + "可用：ls / cat / cp / mv / rm / ps / grep / find / toybox ...\n"
                + "─────────────────────────────────\n";
    }

    // ── 会话 ──

    private void startSession() {
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
        // 清空但不失焦：setText 会让部分输入法收起，用 setSelection 保持焦点
        input.setText("");
        input.setSelection(0);
        input.requestFocus();
        // 重新弹起输入法（执行后能继续输入）
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, 0);

        if (cmd.trim().isEmpty()) {
            appendOutput("$ \n");
            return;
        }
        appendOutput("$ " + cmd + "\n");
        if (session != null) session.sendLine(cmd);
    }

    /** 追加输出并滚动到底部 */
    private void appendOutput(String text) {
        buffer.append(text);
        // 超长截断：保留最后 MAX_LINES 行
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
    }

    private void copyOutput() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "终端输出", buffer.toString()));
                Toast.makeText(this, "已复制全部输出", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.close();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
