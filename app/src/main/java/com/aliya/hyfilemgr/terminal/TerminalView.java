package com.aliya.hyfilemgr.terminal;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
    /**
     * InputConnection 的可编辑载体。
     *
     * <p>父类 BaseInputConnection 的 beginBatchEdit / setComposingText /
     * getTextBeforeCursor 等**全部**会访问 getEditable()。返回 null 只是
     * 把 NPE 从 commitText 推迟到 beginBatchEdit（IME 发字符前必调），
     * 所以中文输入仍会崩。正确做法是返回一个真实但**不与视图耦合**的
     * Editable：内容只用于满足 IME 协议，不参与显示。</p>
     */
    private final android.text.SpannableStringBuilder icBuffer =
            new android.text.SpannableStringBuilder();
    /** 屏幕缓冲字符上限（约 3000 行 × 80 字符） */
    private static final int MAX_SCREEN_CHARS = 240_000;

    /** 提示符（如 "$ "）。null 表示不显示 */
    private String promptStr = "$ ";
    /** 视图当前是否停在底部（用户上滑后为 false） */
    private boolean atBottom = true;
    private float touchDownX, touchDownY;
    private boolean touchMoved;
    /** 正在长按选择文本 */
    private boolean selecting;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!touchMoved) enterSelectionMode();
        }
    };
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
        setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            // 距底部 24dp 内视为「在底部」
            android.view.View child = getChildAt(0);
            if (child == null) return;
            atBottom = (child.getBottom() - (sy + getHeight())) <= dp(24);
        });
        // 长按选择：长按 400ms 且未移动 → 进入文本选择模式
        setOnLongClickListener(v -> {
            enterSelectionMode();
            return true;
        });
        setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = ev.getX();
                    touchDownY = ev.getY();
                    touchMoved = false;
                    // 手动实现长按：ScrollView 的 onLongClick 会被自身滚动拦截
                    postDelayed(longPressRunnable, android.view.ViewConfiguration
                            .getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    // 超过触摸斜率视为滚动意图，不再当作点击
                    if (Math.abs(ev.getY() - touchDownY) > dp(8)
                            || Math.abs(ev.getX() - touchDownX) > dp(8)) {
                        touchMoved = true;
                        removeCallbacks(longPressRunnable);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPressRunnable);
                    // 只有「没滑动过」的轻点才拉起输入法。
                    // 此前无条件 showIme() 导致上滑看历史被立刻弹键盘。
                    if (!touchMoved && !selecting) {
                        requestFocus();
                        showIme();
                    }
                    selecting = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPressRunnable);
                    break;
                default:
                    break;
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

    /** 是否正处于 IME 回调栈内（commitText 等） */
    private boolean inImeCallback;
    /** 是否已投递过延后的重绘 */
    private boolean repaintQueued;
    /** IME 批处理深度：>0 时暂停重绘，避免 setText 打断 IME 的提交序列 */

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
            // ── 与 Termux 完全一致的模型 ──
            // 参考 terminal-view/.../TerminalView.java（termux-app）
            //   ic = new BaseInputConnection(this, true)
            //   getEditable() 不覆盖，走父类内部 SpannableStringBuilder
            //   commitText: super.commitText → 读取整个 getEditable() → 发送 → clear
            //
            // 为什么这样能修掉 cldcd/lls/lscd：
            // 之前我们「自建 icBuffer + 用 commitText 入参送 sink」，
            // 而且 sink 内部会同步 echo → repaint → inner.setText()。
            // TextView 内容一变，系统对焦点 View 触发 restartInput，
            // InputConnection 被断开重建，IME 把缓存中的字符再发一遍 →
            // 字符重复且错序。batchDepth / suppressNextEnter 只是掩盖症状。
            //
            // Termux 的做法把「提交」与「显示」解耦：同一 batch 内多次 commit
            // 先合并进父类 buffer，再由一次 sendTextToTerminal 整体送出，
            // 顺序由 buffer 本身保证，天然不会错序。
            ic = new BaseInputConnection(this, true) {
                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    // 先让父类写进它自己的 buffer（保留 IME 协议状态）
                    super.commitText(text, newCursorPosition);
                    Editable e = getEditable();
                    if (e != null && e.length() > 0) {
                        inImeCallback = true;
                        try {
                            flushBuffer(e);
                        } finally {
                            inImeCallback = false;
                        }
                        e.clear();
                    }
                    return true;
                }

                @Override
                public boolean finishComposingText() {
                    super.finishComposingText();
                    // 部分 IME（手写/日文）只 setComposingText + finishComposingText，
                    // 不发 commitText，这里把残留内容补发出去。
                    Editable e = getEditable();
                    if (e != null && e.length() > 0) {
                        inImeCallback = true;
                        try {
                            flushBuffer(e);
                        } finally {
                            inImeCallback = false;
                        }
                        e.clear();
                    }
                    return true;
                }

                @Override
                public boolean setComposingText(CharSequence text, int newCursorPosition) {
                    // 组词阶段只维护父类 buffer，不发送
                    return super.setComposingText(text, newCursorPosition);
                }

                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    // Termux 的做法：转成 KEYCODE_DEL 逐个发送，而不是直接调 sink。
                    // 原因：三星等键盘开拼写检查时会一次发 leftLength>1，
                    // 直连 sink 会绕过 IME 的状态机。
                    for (int i = 0; i < beforeLength; i++) {
                        sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_DEL));
                        sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_DEL));
                    }
                    if (afterLength > 0) {
                        return true;
                    }
                    Editable e = getEditable();
                    return e != null;
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    return handleKey(event);
                }
            };
        }
        return ic;
    }

    /**
     * 把 IME buffer 的内容整体送给 sink（Termux 的 sendTextToTerminal）。
     *
     * <p>按内容拆行：普通字符走 onText，换行走 onEnter。
     * 关键点：**重绘延后到下一个消息循环**，不在 InputConnection
     * 回调栈内调 setText —— 否则会触发系统 restartInput，
     * InputConnection 被重建，IME 重发缓存字符（cldcd 的根因）。</p>
     */
    private void flushBuffer(CharSequence buf) {
        if (buf == null || buf.length() == 0 || sink == null) return;
        String s2 = buf.toString().replace("\r", "");
        int start = 0;
        for (int i = 0; i < s2.length(); i++) {
            if (s2.charAt(i) == '\n') {
                if (i > start) sink.onText(s2.substring(start, i));
                sink.onEnter();
                start = i + 1;
            }
        }
        if (start < s2.length()) sink.onText(s2.substring(start));
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

    /**
     * 进入文本选择模式：把内部 TextView 临时设为可选，弹出系统选择菜单。
     *
     * <p>为什么必须临时切换：平时 {@code inner} 必须 {@code setTextIsSelectable(false)},
     * 否则它会自己抢焦点，导致 InputConnection 被它拿走、软键盘输入进不了
     * TerminalView 的 sink。只有长按时才短暂开启选择能力。</p>
     */
    private void enterSelectionMode() {
        if (inner == null) return;
        selecting = true;
        // 先停光标闪烁并回显真实文本（去掉光标字符），否则选中的文本会带 ▏
        cursorHandler.removeCallbacks(cursorBlink);
        cursorVisible = false;
        repaintNoCursor();

        inner.setTextIsSelectable(true);
        inner.setFocusable(true);
        inner.setFocusableInTouchMode(true);
        inner.requestFocus();

        // TextView 的 getText() 返回的是普通 String（不可变），
        // getEditableText() 对它返回 null → Selection.setSelection(null) 必崩。
        // 必须先塞一个 SpannableString 进去，才能设置选区。
        CharSequence cur = inner.getText();
        String plain = cur == null ? "" : cur.toString();
        if (!plain.isEmpty()) {
            android.text.SpannableString ss = new android.text.SpannableString(plain);
            inner.setText(ss, android.widget.TextView.BufferType.SPANNABLE);
            try {
                android.text.Selection.setSelection(ss, 0, ss.length());
            } catch (Throwable ignored) {
            }
        }
        CharSequence txt = plain;
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && txt != null && txt.length() > 0) {
            // 同时直写剪贴板：很多 ROM 的 CAB 在自绘 View 上不弹
            try {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("终端输出", txt));
                android.widget.Toast.makeText(getContext(),
                        "终端输出已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }

        // 3 秒后自动退出选择模式，恢复输入能力
        postDelayed(() -> {
            if (inner == null) return;
            // 清掉选区，避免退出后仍高亮
            try {
                CharSequence c = inner.getText();
                if (c instanceof android.text.Spannable) {
                    android.text.Selection.removeSelection((android.text.Spannable) c);
                }
            } catch (Throwable ignored) {
            }
            inner.setTextIsSelectable(false);
            inner.setFocusable(false);
            inner.setFocusableInTouchMode(false);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            requestFocus();
            cursorVisible = true;
            cursorHandler.removeCallbacks(cursorBlink);
            cursorHandler.postDelayed(cursorBlink, 530);
        }, 3000);
    }

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
    /** 重绘但不画光标（选择模式用，避免选中的文本带 ▏ 字符） */
    private void repaintNoCursor() {
        if (inner == null) return;
        StringBuilder all = new StringBuilder(flushed);
        if (promptStr != null) all.append(promptStr);
        all.append(lineBuf);
        inner.setText(all.toString());
    }


    private void repaint() {
        // 若正处于 IME 回调栈内（commitText/deleteSurroundingText 等），
        // 直接 setText 会触发 restartInput → IME 重发缓存字符。
        // 统一改为投递到队列尾，让回调栈先返回。
        if (inImeCallback) {
            if (!repaintQueued) {
                repaintQueued = true;
                post(() -> {
                    repaintQueued = false;
                    repaint();
                });
            }
            return;
        }
        StringBuilder all = new StringBuilder(flushed);
        if (promptStr != null) all.append(promptStr);
        all.append(lineBuf);
        all.append(cursorVisible ? CURSOR_ON : CURSOR_OFF);
        inner.setText(all.toString());
        // 只有原本就在底部时才跟随滚动。用户向上翻看历史输出时
        // 任何新输出（含光标闪烁触发 repaint）都不应把他拉回底部。
        if (atBottom) post(() -> fullScroll(FOCUS_DOWN));
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
        if (text != null && !text.isEmpty()) {
            flushed.append(text);
            // 输出末尾若没有换行，提示符会和最后一行输出粘连
            // （ls 之后行缓冲看似带了 ls 前缀，实为上一行的残留视觉）
            if (flushed.length() > 0 && flushed.charAt(flushed.length() - 1) != '\n') {
                flushed.append('\n');
            }
        }
        // 输出上限：超长会话（logcat / find /）会造成内存线性增长
        // 并让每次 repaint 的 O(n) 复制越来越慢，最终卡死
        if (flushed.length() > MAX_SCREEN_CHARS) {
            int cut = flushed.length() - MAX_SCREEN_CHARS / 2;
            int nl = flushed.indexOf("\n", cut);
            if (nl > 0) flushed.delete(0, nl + 1);
        }
        repaint();
    }

    

    public void clear(String banner) {
        flushed.setLength(0);
        lineBuf.setLength(0);
        if (banner != null) flushed.append(banner);
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
