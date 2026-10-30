package com.aliya.hyfilemgr.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

/**
 * 带行号栏的 EditText。
 *
 * <p><b>为什么这样做</b>：把行号画在独立的 TextView 里，无论怎么对齐参数，
 * 滚动后必然错位——两个 View 各自维护自己的绘制偏移。唯一能结构上保证
 * 逐行对齐的做法是让行号与正文取自**同一个 Layout**：在本 View 的
 * {@link #onDraw} 里用 {@code getLayout().getLineBaseline(line)} 取基线。</p>
 *
 * <p>折行时只在逻辑行首标号，续行留空（与主流编辑器一致）。</p>
 */
public class NumberedEditText extends AppCompatEditText {

    private int gutterWidth;
    private int gutterPad;
    private int gutterBgColor;
    private int gutterTextColor;

    private final Paint gutterText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterBg = new Paint();

    public NumberedEditText(@NonNull Context context) {
        super(context);
        init();
    }

    public NumberedEditText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NumberedEditText(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        gutterText.setTextAlign(Paint.Align.RIGHT);
        gutterText.setTypeface(getTypeface());
        gutterText.setTextSize(getTextSize());
        gutterBg.setColor(0x00000000);
        gutterWidth = 0;
        gutterPad = 0;
    }

    /** 设置行号栏宽度（px）、左右内边距（px）与配色 */
    public void setGutter(int widthPx, int padPx, int bgColor, int textColor) {
        this.gutterWidth = Math.max(0, widthPx);
        this.gutterPad = Math.max(0, padPx);
        this.gutterBgColor = bgColor;
        this.gutterTextColor = textColor;
        gutterBg.setColor(bgColor);
        gutterText.setColor(textColor);
        gutterText.setTypeface(getTypeface());
        gutterText.setTextSize(getTextSize());
        // 正文左侧留出行号栏宽度，否则文字会被行号压住
        setPadding(gutterWidth + dp(8), getPaddingTop(),
                getPaddingRight(), getPaddingBottom());
        invalidate();
    }

    @Override
    public void setTypeface(Typeface tf) {
        super.setTypeface(tf);
        gutterText.setTypeface(tf);
        invalidate();
    }

    @Override
    public void setTextSize(float size) {
        super.setTextSize(size);
        gutterText.setTextSize(getTextSize());
        invalidate();
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        gutterText.setTextSize(getTextSize());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final Layout layout = getLayout();
        if (layout == null || gutterWidth <= 0) {
            super.onDraw(canvas);
            return;
        }

        final int scrollY = getScrollY();
        final int scrollX = getScrollX();
        final int height = getHeight();
        final int padTop = getExtendedPaddingTop();
        final int padBottom = getExtendedPaddingBottom();

        // 行号栏背景：固定在可视区左侧，不随横向滚动移动
        canvas.save();
        canvas.translate(scrollX, 0f);
        canvas.drawRect(-gutterWidth - dp(8), 0f,
                dp(8), height + scrollY, gutterBg);
        canvas.restore();

        final int firstLine = layout.getLineForVertical(scrollY);
        final int lastLine = layout.getLineForVertical(scrollY + height);
        final int topClip = scrollY + padTop;
        final int bottomClip = scrollY + height - padBottom;

        canvas.save();
        canvas.translate(scrollX, 0f);
        for (int line = firstLine; line <= lastLine && line < layout.getLineCount(); line++) {
            // 只在逻辑行首（lineStart 处的字符前是 \n 或文件开头）标号
            int lineStart = layout.getLineStart(line);
            boolean isLogicalStart = lineStart == 0
                    || (lineStart > 0 && getText().charAt(lineStart - 1) == '\n');
            if (!isLogicalStart) continue;

            int baseline = layout.getLineBaseline(line);
            if (baseline < topClip || baseline > bottomClip) continue;

            canvas.drawText(String.valueOf(logicalLineOf(layout, line)),
                    -gutterPad, baseline, gutterText);
        }
        canvas.restore();

        super.onDraw(canvas);
    }

    /** 该显示行所属的逻辑行号（从 1 开始） */
    private int logicalLineOf(Layout layout, int shownLine) {
        int n = 1;
        for (int i = 1; i <= shownLine && i < layout.getLineCount(); i++) {
            int start = layout.getLineStart(i);
            if (start > 0 && getText().charAt(start - 1) == '\n') n++;
        }
        return n;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
