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

    // 不能用 final 就地初始化：ColorOS 的 OplusFontManager 会在
    // TextView 构造**中途**（applyTextAppearance）回调 setTypeface，
    // 那时这些字段还没赋上值，final 字段读出 null，下一行就 NPE。
    // 改成普通字段 + 在 setTypeface 里判空跳过。
    private Paint gutterText = null;
    private Paint gutterBg = null;

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
        if (gutterText == null) gutterText = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (gutterBg == null) gutterBg = new Paint();
        gutterText.setTextAlign(Paint.Align.RIGHT);
        gutterText.setTypeface(getTypeface());
        gutterText.setTextSize(getTextSize());
        gutterBg.setColor(0x00000000);
        gutterWidth = 0;
        gutterPad = 0;
    }

    /** 设置行号栏宽度（px）、左右内边距（px）与配色 */
    public void setGutter(int widthPx, int padPx, int bgColor, int textColor) {
        if (gutterText == null || gutterBg == null) init();
        this.gutterWidth = Math.max(0, widthPx);
        this.gutterPad = Math.max(0, padPx);
        this.gutterBgColor = bgColor;
        this.gutterTextColor = textColor;
        gutterBg.setColor(bgColor);
        gutterText.setColor(textColor);
        gutterText.setTypeface(getTypeface());
        gutterText.setTextSize(getTextSize());
        // 正文左内边距 = 行号栏宽 + 左 pad + 右 pad，
        // 让正文正好从行号栏右侧开始。
        setPadding(gutterWidth + gutterPad * 2, getPaddingTop(),
                getPaddingRight(), getPaddingBottom());
        invalidate();
    }

    @Override
    public void setTypeface(Typeface tf) {
        super.setTypeface(tf);
        // 构造中途系统就会调到这里（见字段注释），此时 Paint 还是 null。
        // 跳过即可：init() 里会用 getTypeface() 重新同步一次。
        if (gutterText == null) return;
        gutterText.setTypeface(tf);
        invalidate();
    }

    @Override
    public void setTextSize(float size) {
        super.setTextSize(size);
        // 与 setTypeface 同理：构造期可能被系统提前回调，Paint 还没建
        if (gutterText == null) return;
        gutterText.setTextSize(getTextSize());
        invalidate();
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        if (gutterText == null) return;
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

        // 坐标系说明：EditText 的绘制原点在**内容区左上角**（已扣除 padLeft）。
        // gutter 占用了 padLeft 的前 gutterWidth 像素，因此它在内容坐标里
        // 是 0..gutterWidth，而不是 -gutterWidth..0。
        // 横向滚动时内容整体左移 scrollX，gutter 要钉在左端 → translate(scrollX)。
        canvas.save();
        canvas.translate(scrollX, 0f);
        if (gutterBg == null) return;
        canvas.drawRect(0f, 0f, gutterWidth, height, gutterBg);
        canvas.restore();

        // 逻辑行计数：从第 0 行起累加，避免每行都从头数（旧实现 O(n²)）
        int logicalLine = 1;
        final int firstLine = layout.getLineForVertical(scrollY);
        for (int i = 0; i < firstLine && i < layout.getLineCount(); i++) {
            int st = layout.getLineStart(i);
            if (st > 0 && getText().charAt(st - 1) == '\n') logicalLine++;
        }

        canvas.save();
        canvas.translate(scrollX, 0f);
        final int lastLine = layout.getLineForVertical(scrollY + height);
        for (int line = firstLine; line <= lastLine && line < layout.getLineCount(); line++) {
            int lineStart = layout.getLineStart(line);
            boolean isLogicalStart = lineStart == 0
                    || (lineStart > 0 && getText().charAt(lineStart - 1) == '\n');
            if (isLogicalStart) {
                int baseline = layout.getLineBaseline(line);
                // baseline 是内容坐标，与 scrollY 同量纲，可直接比较。
                // 用行顶/行底判断是否与可视区相交，避免 getLineHeight()（Layout 无此方法）
                // lineTop/lineBottom/baseline 都是**内容坐标**（不含 scrollY），
                // 与 scrollY 比较即可判断是否落在可视区
                int lineTop = layout.getLineTop(line);
                int lineBottom = layout.getLineBottom(line);
                if (lineBottom >= scrollY && lineTop <= scrollY + height) {
                    canvas.drawText(String.valueOf(logicalLine),
                            gutterWidth - gutterPad, baseline, gutterText);
                }
                logicalLine++;
            }
        }
        canvas.restore();

        super.onDraw(canvas);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
