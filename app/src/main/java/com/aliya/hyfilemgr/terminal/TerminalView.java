package com.aliya.hyfilemgr.terminal;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.method.MetaKeyKeyListener;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 终端输出视图（Termux 同款输入方案）。
 *
 * <p><b>为什么这么写</b>：任何 <code>EditText</code> 中转输入的方案，
 * 只要调用 {@code setText()} 就会重建输入连接，导致输入法焦点丢失
 * （键盘还在但不接收按键）。Termux 的做法是让终端视图本身成为可编辑
 * 目标——重写 {@link #onCreateInputConnection(EditorInfo)} 返回
 * {@link BaseInputConnection}，由它直接把输入法产生的字符送进 shell，
 * 全程不涉及 EditText，因此焦点永不丢失。</p>
 *
 * <p>同时支持物理键盘（{@link #onKeyDown}）与软键盘（InputConnection）。</p>
 */
public class TerminalView extends ScrollView {

    /** 输入回调：把字符交给 shell；onEnter 表示回车 */
    public interface Sink {
        void onText(String text);
        void onEnter();
        void onBackspace();
    }

    private final TextView inner;
    private Sink sink;
    private BaseInputConnection ic;
    private final StringBuilder screen = new StringBuilder();
    private int maxLines = 3000;

    /** 提示符（如 "$ "）。null 表示不显示 */
    private String promptStr = "$ ";
    /** 光标字符（闪烁显示/隐藏） */
    private static final String CURSOR_ON = "▏";
    private static final String CURSOR_OFF = " ";
    /** 光标当前是否可见 */
    private boolean cursorVisible = true;
    /** 光标闪烁定时器（500ms 一次，贴近真实终端） */
    private final android.os.Handler cursorHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable cursorBlink = new Runnable() {
        @Override
        public void run() {
            cursorVisible = !cursorVisible;
            repaint();
            cursorHandler.postDelayed(this, 530);
        }
    };
    /** 当前行已输入的字符（用于重绘提示符 + 输入 + 光标） */
    private final StringBuilder lineBuf = new StringBuilder();
    /** 屏幕已刷出的内容（不含当前行） */
    private final StringBuilder flushed = new StringBuilder();

    public TerminalView(Context context) {
        super(context);
        setBackgroundColor(0xFF0D1117);
        setFillViewport(true);

        inner = new TextView(context);
        inner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        inner.setTypeface(Typeface.MONOSPACE);
        inner.setTextColor(0xFFC9D1D9);
        inner.setTextIsSelectable(false);
        int p = dp(12);
        inner.setPadding(p, p, p, p);
        addView(inner, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 关键：自身可获焦并可编辑，才能拿到 InputConnection
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                requestFocus();
                showIme();
            }
            return false;
        });
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    // ── 输入法连接（核心） ──

    @Override
    public boolean onCheckIsTextEditor() {
        return true;   // 让系统认为这是可编辑控件
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (ic == null) {
            // 用一个哑 Editable 承载 BaseInputConnection 的必需参数，
            // 但它的内容不会被读取，所有输入都直接转发给 sink。
            ic = new BaseInputConnection(this, false) {
                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    if (sink != null && text != null) {
                        String s2 = text.toString();
                        if (!s2.isEmpty()) {
                            if (s2.contains("\n")) {
                                String[] parts = s2.split("\n", -1);
                                for (int i = 0; i < parts.length; i++) {
                                    if (!parts[i].isEmpty()) sink.onText(parts[i]);
                                    if (i < parts.length - 1) sink.onEnter();
                                }
                            } else {
                                sink.onText(s2);
                            }
                        }
                    }
                    return true;
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    return handleKey(event);
                }

                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    // 软键盘退格走这里
                    for (int i = 0; i < beforeLength; i++) {
                        if (sink != null) sink.onBackspace();
                    }
                    return true;
                }

                @Override
                public Editable getEditable() {
                    return null;   // 不提供真实 Editable，避免焦点/文本状态耦合
                }
            };
        }
        return ic;
    }

    // ── 物理键盘 ──

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleKey(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleKey(KeyEvent event) {
        if (event == null || sink == null) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) sink.onEnter();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) sink.onBackspace();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int u = event.getUnicodeChar();
            if (u > 0) {
                sink.onText(new String(Character.toChars(u)));
                return true;
            }
        }
        return false;
    }

    // ── 显示 ──

    /** 设置提示符（如 "$ "、"> "） */
    public void setPrompt(String prompt) {
        this.promptStr = prompt;
        repaint();
    }

    /**
     * 重绘：已刷出内容 + 当前行（提示符 + 输入 + 光标）。
     *
     * <p>光标用下划线字符表示，与真正的终端一致；每次输入/退格都重画当前行。</p>
     */
    private void repaint() {
        StringBuilder all = new StringBuilder(flushed);
        if (promptStr != null) all.append(promptStr);
        all.append(lineBuf);
        all.append(cursorVisible ? CURSOR_ON : CURSOR_OFF);
        inner.setText(all.toString());
        post(() -> fullScroll(FOCUS_DOWN));
    }

    /** 本地回显一个字符（追加到当前行） */
    public void echo(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') continue;
            if (c == '\n') {
                flushed.append(promptStr == null ? "" : promptStr)
                        .append(lineBuf).append("\n");
                lineBuf.setLength(0);
            } else {
                lineBuf.append(c);
            }
        }
        repaint();
    }

    /** 退格：从当前行尾删一个字符 */
    public void backspaceEcho() {
        if (lineBuf.length() > 0) {
            lineBuf.deleteCharAt(lineBuf.length() - 1);
        }
        repaint();
    }

    /** 回车：提交当前行到已刷出区，重置输入缓冲 */
    public void newlineEcho() {
        flushed.append(promptStr == null ? "" : promptStr)
                .append(lineBuf).append("\n");
        lineBuf.setLength(0);
        repaint();
    }

    /** 追加输出并自动滚到底部 */
    public void append(String text) {
        // shell 输出直接进 flushed，末尾补提示符 + 光标
        flushed.append(text);
        repaint();
        return;
    }

    private void appendLegacy(String text) {
        screen.append(text);
        int lines = 0;
        for (int i = 0; i < screen.length(); i++) if (screen.charAt(i) == '\n') lines++;
        if (lines > maxLines) {
            int cut = screen.length() / 3;
            int nl = screen.indexOf("\n", cut);
            if (nl > 0) screen.delete(0, nl + 1);
        }
        inner.setText(screen.toString());
        post(() -> fullScroll(FOCUS_DOWN));
    }

    public void clear(String banner) {
        screen.setLength(0);
        flushed.setLength(0);
        lineBuf.setLength(0);
        if (banner != null) {
            flushed.append(banner);
            screen.append(banner);
        }
        repaint();
    }

    /** 当前行已输入的内容（不含提示符） */
    public String currentLine() {
        return lineBuf.toString();
    }

    public String dump() {
        StringBuilder all = new StringBuilder(flushed);
        if (promptStr != null) all.append(promptStr);
        all.append(lineBuf);
        return all.toString();
    }

    /** 开始光标闪烁（视图可见时调用） */
    public void startCursorBlink() {
        cursorHandler.removeCallbacks(cursorBlink);
        cursorVisible = true;
        cursorHandler.postDelayed(cursorBlink, 530);
    }

    /** 停止光标闪烁（视图不可见/销毁时调用） */
    public void stopCursorBlink() {
        cursorHandler.removeCallbacks(cursorBlink);
        cursorVisible = true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startCursorBlink();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopCursorBlink();
        super.onDetachedFromWindow();
    }

    public void showIme() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, 0);
    }

    public void hideIme() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
