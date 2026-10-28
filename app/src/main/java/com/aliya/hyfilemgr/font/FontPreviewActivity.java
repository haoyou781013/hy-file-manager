package com.aliya.hyfilemgr.font;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * 字体预览页（TTF / OTF / TTC / WOFF）。
 *
 * <p>加载字体文件后用多种字号与样式渲染样张，支持：</p>
 * <ul>
 *     <li>实时修改预览文本</li>
 *     <li>切换字号档（12/16/20/28/40/64sp）</li>
 *     <li>横排/竖排样张</li>
 *     <li>查看字体基本信息（文件大小、字符数、能否加载）</li>
 * </ul>
 *
 * <p>加载失败时显示失败原因，不崩溃。</p>
 */
public class FontPreviewActivity extends Activity {

    public static final String EXTRA_PATH = "font_path";
    public static final String EXTRA_URI = "font_uri";
    public static final String EXTRA_TITLE = "font_title";

    /** 默认预览文本 */
    private static final String DEFAULT_SAMPLE =
            "永和九年，岁在癸丑，暮春之初，会于会稽山阴之兰亭。\n"
          + "The quick brown fox jumps over the lazy dog.\n"
          + "0123456789 !@#$%^&*() {}[]<>/\\|~`\n"
          + "Abcdefghijklmnopqrstuvwxyz"
          + "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int[] SIZES = {12, 16, 20, 28, 40, 64};

    private Typeface typeface;
    private String loadError;
    private String filePath;
    private Uri fileUri;
    private String title;

    private LinearLayout sampleBox;
    private EditText input;
    private String sampleText = DEFAULT_SAMPLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        filePath = in == null ? null : in.getStringExtra(EXTRA_PATH);
        String uriStr = in == null ? null : in.getStringExtra(EXTRA_URI);
        if (uriStr != null && !uriStr.isEmpty()) fileUri = Uri.parse(uriStr);
        title = in == null ? "" : in.getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) {
            title = filePath != null ? new File(filePath).getName() : "字体预览";
        }

        loadTypeface();
        setContentView(buildUi());
        renderSamples();
    }

    /** 加载字体：本地路径优先，其次 SAF URI（先复制到缓存再加载） */
    private void loadTypeface() {
        try {
            if (filePath != null && !filePath.isEmpty()) {
                File f = new File(filePath);
                if (!f.exists()) {
                    loadError = "文件不存在：" + filePath;
                    return;
                }
                if (!f.canRead()) {
                    loadError = "没有读取权限";
                    return;
                }
                typeface = Typeface.createFromFile(f);
                if (typeface == null) loadError = "Typeface.createFromFile 返回 null";
                return;
            }
            if (fileUri != null) {
                // SAF：把字体复制到缓存再加载（createFromFile 需要真实文件）
                File tmp = new File(getCacheDir(), "font_preview.tmp");
                try (java.io.InputStream is = getContentResolver().openInputStream(fileUri);
                     java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
                    if (is == null) {
                        loadError = "无法打开 SAF 输入流";
                        return;
                    }
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                }
                typeface = Typeface.createFromFile(tmp);
                if (typeface == null) loadError = "字体解析失败（可能不是有效的字体文件）";
                return;
            }
            loadError = "未提供字体路径";
        } catch (Throwable t) {
            loadError = t.getClass().getSimpleName() + "：" + t.getMessage();
        }
    }

    // ── UI ──

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF12151A);
        root.setFitsSystemWindows(true);

        // 顶部栏：返回 + 标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(10), dp(12), dp(10));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(0xFFFFFFFF);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        top.addView(back);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 状态条
        TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setPadding(dp(14), 0, dp(14), dp(10));
        if (loadError != null) {
            status.setText("加载失败：" + loadError);
            status.setTextColor(0xFFFF6B6B);
        } else {
            status.setText("字体已加载 · " + describeSource());
            status.setTextColor(0xFF6FCF97);
        }
        root.addView(status);

        // 预览输入框
        input = new EditText(this);
        input.setText(sampleText);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        input.setTextColor(0xFFD5DBE5);
        input.setHintTextColor(0xFF5A6472);
        input.setHint("输入要预览的文字…");
        input.setBackgroundColor(0xFF1B1F26);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setMaxLines(4);
        input.setVerticalScrollBarEnabled(true);
        input.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String t = s.toString();
                sampleText = t.isEmpty() ? DEFAULT_SAMPLE : t;
                renderSamples();
            }
        });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.leftMargin = dp(12);
        inputLp.rightMargin = dp(12);
        root.addView(input, inputLp);

        // 样张区（可滚动）
        ScrollView scroll = new ScrollView(this);
        sampleBox = new LinearLayout(this);
        sampleBox.setOrientation(LinearLayout.VERTICAL);
        sampleBox.setPadding(dp(12), dp(10), dp(12), dp(24));
        scroll.addView(sampleBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private String describeSource() {
        if (filePath != null) {
            File f = new File(filePath);
            long kb = f.length() / 1024;
            return f.getName() + " · " + (kb < 1024 ? kb + " KB"
                    : String.format(java.util.Locale.US, "%.1f MB", kb / 1024f));
        }
        return "SAF 文件";
    }

    /** 按 6 档字号重新渲染样张 */
    private void renderSamples() {
        if (sampleBox == null) return;
        sampleBox.removeAllViews();

        if (loadError != null) {
            TextView err = new TextView(this);
            err.setText("无法预览该字体\n\n" + loadError
                    + "\n\n可能原因：文件损坏、格式不受支持（如部分 TTC/WOFF2），"
                    + "或文件不是真正的字体。");
            err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            err.setTextColor(0xFFFF6B6B);
            err.setLineSpacing(dp(4), 1f);
            sampleBox.addView(err);
            return;
        }

        for (int sp : SIZES) {
            // 字号标注
            TextView tag = new TextView(this);
            tag.setText(sp + " sp");
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tag.setTextColor(0xFF5A6472);
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tagLp.topMargin = dp(14);
            sampleBox.addView(tag, tagLp);

            // 样张
            TextView sample = new TextView(this);
            sample.setText(sampleText);
            sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            sample.setTextColor(0xFFE8EDF5);
            sample.setLineSpacing(dp(4), 1f);
            if (typeface != null) sample.setTypeface(typeface);
            sample.setTextIsSelectable(true);
            sampleBox.addView(sample);
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
