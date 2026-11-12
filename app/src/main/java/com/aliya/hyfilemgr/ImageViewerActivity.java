package com.aliya.hyfilemgr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aliya.hyfilemgr.ui.UiKit;

import java.io.File;
import java.io.InputStream;

/**
 * 内置图片查看器（原生 BitmapFactory + Matrix，无第三方依赖）。
 *
 * <p>配合文件管理的「打开方式」弹窗使用：图片类文件优先由本页打开。
 * 支持：双指缩放、拖动查看、双击还原、单击画面切换顶栏/底部功能栏显隐
 * （3 秒无操作自动隐藏）、大图按目标尺寸采样解码（避免 OOM），
 * 兼容本地路径与 SAF(content://)。</p>
 *
 * <p>底部功能栏三个操作均<strong>回传主界面</strong>处理，本页不实现业务：
 * 删除走模块 confirmDelete（回收站+刷新），设为背景走 setBackgroundFromUri
 * （壁纸落盘与渲染都在模块）。回传协议见 {@link #RESULT_ACTION_*}。</p>
 */
public class ImageViewerActivity extends Activity {
    public static final String EXTRA_PATH = "image_path";
    public static final String EXTRA_URI = "image_uri";
    public static final String EXTRA_TITLE = "image_title";

    /** 结果动作：删除已确认。data = 要删的路径（本地路径才有删除按钮） */
    public static final String RESULT_ACTION_DELETE = "image_action_delete";
    /** 结果动作：设为背景已选择。data = 图片路径；extra = 是否文件列表页背景 */
    public static final String RESULT_ACTION_WALLPAPER = "image_action_wallpaper";
    public static final String EXTRA_FOR_FM = "image_for_fm";
    /** 同目录图片列表（String[]）与起始索引；不传则不启用左右切换 */
    public static final String EXTRA_LIST = "image_list";
    public static final String EXTRA_INDEX = "image_index";

    private ImageView imageView;
    private View topBar, bottomBar;
    private final Matrix matrix = new Matrix();
    private android.view.ScaleGestureDetector scaleDetector;
    private android.view.GestureDetector gestureDetector;
    private boolean barsVisible = true;

    /** 同目录图片列表与当前位置（EXTRA_LIST 未传时只含当前一张，不显示计数） */
    private String[] gallery;
    private int galleryIndex;
    /** 当前展示的路径（删除/设背景都以它为准） */
    private String currentPath;
    private boolean currentIsUri;
    private String currentUriStr;

    @Override protected void attachBaseContext(android.content.Context base) {
        // 应用级 DPI 覆盖：与主界面/播放器一致，否则密度设置对本页无效
        super.attachBaseContext(com.aliya.hyfilemgr.util.DpiUtils.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(com.aliya.hyfilemgr.R.layout.activity_image_viewer);

        imageView = findViewById(com.aliya.hyfilemgr.R.id.iv_image);
        topBar = findViewById(com.aliya.hyfilemgr.R.id.iv_top);
        bottomBar = findViewById(com.aliya.hyfilemgr.R.id.iv_bottom);
        TextView tvTitle = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_title);
        TextView tvInfo = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_info);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String uriStr = getIntent().getStringExtra(EXTRA_URI);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        boolean isUri = uriStr != null && !uriStr.isEmpty();
        currentPath = path;
        currentIsUri = isUri;
        currentUriStr = uriStr;

        // 同目录图集：模块传来的当前窗格列表（与播放器 collectPlayable 同思路）。
        // 未传或只有一张 → 不启用切换；local path 才能扫，content:// 无目录概念。
        String[] list = getIntent().getStringArrayExtra(EXTRA_LIST);
        int idx = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (!isUri && path != null && list != null && list.length > 1) {
            gallery = list;
            galleryIndex = Math.max(0, Math.min(idx, list.length - 1));
        } else {
            gallery = new String[]{ isUri ? uriStr : path };
            galleryIndex = 0;
        }

        String name = isUri ? "图片" : new File(path == null ? "" : path).getName();
        if (title == null || title.isEmpty()) title = name;
        tvTitle.setText(title);
        updateCounter();

        findViewById(com.aliya.hyfilemgr.R.id.btn_iv_back).setOnClickListener(v -> finish());

        // ── 底部功能栏：全部回传主界面处理 ──
        // 删除/设为背景只对本地路径可用（content:// 的删除要 SAF 流程、
        // 壁纸复制需要可重开的流——两者都走模块的既有链路，Uri 来源另议）。
        // 切图后按钮行为跟随 currentPath，所以用间接引用而不是直接闭包 path。
        View del = findViewById(com.aliya.hyfilemgr.R.id.btn_iv_delete);
        View wp = findViewById(com.aliya.hyfilemgr.R.id.btn_iv_wallpaper);
        del.setVisibility(!isUri ? View.VISIBLE : View.GONE);
        wp.setVisibility(!isUri ? View.VISIBLE : View.GONE);
        del.setOnClickListener(v -> confirmDelete(currentPath));
        wp.setOnClickListener(v -> askWallpaperTarget(currentPath));
        findViewById(com.aliya.hyfilemgr.R.id.btn_iv_share).setOnClickListener(
                v -> shareImage(currentPath, currentUriStr, currentIsUri));
        // 高清模式：长按图片切换（默认关，按屏幕 2 倍采样 + RGB_565，快且省内存）。
        // 注意：不能在这里用 setOnLongClickListener（会被 OnTouchListener 吃掉），
        // 实际回调在 GestureDetector.onLongPress 里。

        // ── 状态栏沉浸：透明状态栏，顶栏从状态栏后面开始。
        // insets 必须在 attach 后取（createMainView 阶段 DecorView 未 attach
        // 时 getRootWindowInsets() 返回 null），照抄主界面「attach 后重算
        // + post 一帧确认」的双次模式（ColorOS 的 status_bar_height 资源值偏小）。
        android.view.Window w = getWindow();
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x99000000);
        LinearLayout top = (LinearLayout) topBar;
        LinearLayout bot = (LinearLayout) bottomBar;
        View root = findViewById(com.aliya.hyfilemgr.R.id.iv_root);
        View.OnClickListener applyInsets = v -> {
            androidx.core.view.WindowInsetsCompat ins =
                    androidx.core.view.ViewCompat.getRootWindowInsets(root);
            if (ins == null) return;
            androidx.core.graphics.Insets sys = ins.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            top.setPadding(top.getPaddingLeft(),
                    sys.top + dp(8), top.getPaddingRight(), dp(8));
            bot.setPadding(bot.getPaddingLeft(), dp(10),
                    bot.getPaddingRight(), sys.bottom + dp(10));
        };
        root.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { applyInsets.onClick(null); }
                    @Override public void onViewDetachedFromWindow(View v) { }
                });
        root.post(() -> applyInsets.onClick(null));   // attach 后再补一次（insets 那时才可用）
        androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView())
                .setAppearanceLightStatusBars(false);   // 黑底浅色图标

        // 手势：ScaleGestureDetector 管双指缩放，GestureDetector 管拖动/双击/单击
        scaleDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                        float f = d.getScaleFactor();
                        matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                        imageView.setImageMatrix(matrix);
                        return true;
                    }
                });

        gestureDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        resetMatrix();
                        return true;
                    }

                    @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                        if (e2.getPointerCount() > 1) return false;   // 双指时交给缩放
                        matrix.postTranslate(-dx, -dy);
                        imageView.setImageMatrix(matrix);
                        return true;
                    }

                    @Override public boolean onFling(MotionEvent e1, MotionEvent e2,
                                                     float vx, float vy) {
                        // 左右快滑切图：仅单指、水平分量明显大于垂直、无缩放中。
                        // 图片被放大拖动时水平滑动是拖图（dx 已被 onScroll 消费），
                        // fling 只在图片未放大（可判定 matrix 平移在边界内）或快速甩动时触发。
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) < dp(60) || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                        if (Math.abs(vx) < 800f) return false;
                        switchImage(dx < 0 ? 1 : -1);
                        return true;
                    }

                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        toggleBars();
                        return true;
                    }

                    @Override public void onLongPress(MotionEvent e) {
                        // 长按 = 切换高清模式。
                        // 必须写在 GestureDetector 里：imageView 的 OnTouchListener
                        // 恒返回 true（为了接管缩放/拖动），会吃掉 View 级长按事件，
                        // setOnLongClickListener 根本不会触发。
                        toggleHd();
                    }
                });

        imageView.setOnTouchListener((v, ev) -> {
            scaleDetector.onTouchEvent(ev);
            gestureDetector.onTouchEvent(ev);
            return true;
        });

        loadImage(path, isUri);

        // 初始态：白底+栏展开。之前漏调导致 XML 默认黑底+栏可见的混合态
        //（用户实测：进入是黑底显示功能栏，点击后才恢复）。
        setBarsVisible(true);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
    }

    // ── 高清模式 ──

    /**
     * 高清模式开关（长按图片切换）。
     *
     * <p><b>默认为什么不是高清</b>：默认走「屏幕 2 倍采样 + RGB_565」，
     * 首屏快、内存占用低（大图动辄 20~60MB）。但用户放大看细节、或图片带渐变/文字时，
     * RGB_565 的色阶压缩与屏幕采样会看出模糊与色带。
     * 高清模式改用原分辨率兜底采样（仍防 OOM，见 {@link #calcHdSample}）+ ARGB_8888。</p>
     *
     * <p>只对静态位图生效；GIF 动图不受影响（它走另一条解码路径）。</p>
     */
    private void toggleHd() {
        hdMode = !hdMode;
        UiKit.toast(this, hdMode ? "高清模式：开（重新加载中）" : "高清模式：关");
        loadImage(currentPath, currentIsUri);
    }

    /** 高清模式标记 */
    private boolean hdMode;

    /**
     * 高清采样倍率：取「原始尺寸不超过屏幕 4 倍」时用 1（不采样），
     * 超出则按 2/4 逐级降，既保留细节又防超大图 OOM。
     */
    private int calcHdSample(int w, int h) {
        int reqW = getResources().getDisplayMetrics().widthPixels;
        int reqH = getResources().getDisplayMetrics().heightPixels;
        int sample = 1;
        while ((w / (sample * 2) > reqW * 4 || h / (sample * 2) > reqH * 4) && sample < 8) {
            sample *= 2;
        }
        return sample;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── 图集加载与切换 ──

    /** 切图过渡时长（毫秒）：淡出旧图→换新图→淡入 */
    private static final long FADE_MS = 150;
    /** 解码任务序号：连续切换时只认最后一次的结果，避免慢图晚到盖住新图 */
    private int loadSeq;

    /** 加载指定路径的图片（后台解码 + 淡入过渡；序号防旧图晚到覆盖新图） */
    private void loadImage(String path, boolean isUri) {
        final String src = isUri ? currentUriStr : path;
        final int seq = ++loadSeq;

        // GIF（API 28+）：用 ImageDecoder 出 AnimatedImageDrawable，直接播放动图。
        // 低版本/解码失败 → 回退下面的 BitmapFactory 静态首帧。
        if (!isUri && android.os.Build.VERSION.SDK_INT >= 28
                && path != null && path.toLowerCase().endsWith(".gif")) {
            try {
                android.graphics.ImageDecoder.Source gifSrc =
                        android.graphics.ImageDecoder.createSource(new File(path));
                android.graphics.drawable.Drawable anim =
                        android.graphics.ImageDecoder.decodeDrawable(gifSrc, (decoder, info, s) -> {
                            // 按屏幕尺寸降采样：动图内存占用比静态图高得多
                            android.util.Size size = info.getSize();
                            int w = size.getWidth(), h = size.getHeight();
                            int reqW = getResources().getDisplayMetrics().widthPixels;
                            int reqH = getResources().getDisplayMetrics().heightPixels;
                            if (w > reqW * 2 || h > reqH * 2) {
                                decoder.setTargetSampleSize(2);
                            }
                        });
                if (anim instanceof android.graphics.drawable.AnimatedImageDrawable) {
                    final android.graphics.drawable.AnimatedImageDrawable aid =
                            (android.graphics.drawable.AnimatedImageDrawable) anim;
                    runOnUiThread(() -> {
                        if (seq != loadSeq) return;
                        imageView.setImageDrawable(aid);
                        imageView.setAlpha(0f);
                        imageView.animate().alpha(1f).setDuration(FADE_MS).start();
                        TextView info = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_info);
                        info.setText(aid.getIntrinsicWidth() + " × " + aid.getIntrinsicHeight() + " · GIF");
                        updateCounter();
                        resetMatrix();
                        aid.start();
                    });
                    return;
                }
            } catch (Throwable ignored) {
                // 落到静态解码分支
            }
        }

        new Thread(() -> {
            final Bitmap bmp;
            try {
                bmp = decodeSampled(src, isUri);
            } catch (Throwable t) {
                runOnUiThread(() ->
                        UiKit.toast(this, "图片解码失败：" + t.getMessage()));
                return;
            }
            runOnUiThread(() -> {
                if (seq != loadSeq) return;   // 已切到别的图：丢弃
                if (bmp == null) {
                    UiKit.toast(this, "无法解码该图片");
                    return;
                }
                imageView.setImageBitmap(bmp);
                imageView.setAlpha(0f);
                imageView.animate().alpha(1f).setDuration(FADE_MS).start();
                TextView info = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_info);
                info.setText(bmp.getWidth() + " × " + bmp.getHeight()
                        + (hdMode ? " · 高清" : ""));
                updateCounter();
                resetMatrix();
            });
        }).start();
    }

    /** 左右切换同目录图片：dir=1 下一张 / -1 上一张，循环 */
    private void switchImage(int dir) {
        if (gallery == null || gallery.length <= 1) return;
        galleryIndex = (galleryIndex + dir + gallery.length) % gallery.length;
        String p = gallery[galleryIndex];
        currentPath = p;
        currentIsUri = false;
        matrix.reset();
        imageView.setImageMatrix(matrix);
        // 旧图淡出后清掉，等新图淡入（手感上是一段连续的渐变）
        imageView.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            imageView.setImageDrawable(null);
        }).start();
        TextView tvTitle = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_title);
        tvTitle.setText(new File(p).getName());
        updateCounter();
        loadImage(p, false);
    }

    /** 底栏计数行：有图集时显示「01/32」两位格式，单图隐藏（顶栏尺寸照常） */
    private void updateCounter() {
        TextView counter = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_counter);
        if (gallery != null && gallery.length > 1) {
            counter.setVisibility(View.VISIBLE);
            counter.setText(String.format("%02d/%02d", galleryIndex + 1, gallery.length));
        } else {
            counter.setVisibility(View.GONE);
        }
    }

    // ── 顶/底栏显隐 ──

    /** 单击画面：唤出或隐藏顶栏+底部功能栏；唤出时排 3 秒自动隐藏 */
    private void toggleBars() {
        setBarsVisible(!barsVisible);
    }

    /** 背景色过渡时长（毫秒）：栏显隐同步的整页背景渐变 */
    private static final long BG_ANIM_MS = 220;

    private void setBarsVisible(boolean show) {
        barsVisible = show;
        int vis = show ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        bottomBar.setVisibility(vis);
        // 栏出=白底（浏览模式）、栏藏=黑底（沉浸看图）。
        // 进入时默认栏展开+白底，点击画面后切黑沉浸。
        // 白底时栏背景透明（白透出来）、图标文字深色；黑底时栏用半透明黑 scrim、文字浅色。
        View root = findViewById(com.aliya.hyfilemgr.R.id.iv_root);
        ImageView backBtn = findViewById(com.aliya.hyfilemgr.R.id.btn_iv_back);
        TextView title = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_title);
        TextView info = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_info);
        int mainText = show ? 0xFF1A1A1A : 0xFFFFFFFF;
        int subText = show ? 0x991A1A1A : 0x99FFFFFF;
        int scrim = show ? 0x00000000 : 0x99000000;
        topBar.setBackgroundColor(scrim);
        bottomBar.setBackgroundColor(scrim);
        backBtn.setColorFilter(mainText);
        title.setTextColor(mainText);
        info.setTextColor(subText);
        findViewById(com.aliya.hyfilemgr.R.id.tv_iv_counter).setBackgroundColor(0x00000000);
        ((TextView) findViewById(com.aliya.hyfilemgr.R.id.tv_iv_counter)).setTextColor(subText);
        tintBottomButtons(mainText, subText);
        // 背景渐变过渡：硬切会闪一下，ArgbEvaluator 平滑变白/变黑。
        // 首次调用（onCreate 尾部）不播动画，直接落位。
        android.animation.ValueAnimator bgAnim = android.animation.ValueAnimator.ofObject(
                new android.animation.ArgbEvaluator(), currentBg, show ? 0xFFFFFFFF : 0xFF000000);
        bgAnim.setDuration(firstApply ? 0 : BG_ANIM_MS);
        bgAnim.addUpdateListener(a -> {
            currentBg = (int) a.getAnimatedValue();
            root.setBackgroundColor(currentBg);
        });
        bgAnim.start();
        firstApply = false;
        // 状态栏底色跟随：白底时状态栏也白、图标深色；黑底时透明、图标浅色。
        android.view.Window w = getWindow();
        w.setStatusBarColor(show ? 0xFFFFFFFF : 0x00000000);
        androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView())
                .setAppearanceLightStatusBars(show);
        // 不自动隐藏（用户指示）：栏常驻直到用户再点画面。
    }

    /** onCreate 首次落位不播动画 */
    private boolean firstApply = true;
    /** 当前背景色（ArgbEvaluator 动画的起点） */
    private int currentBg = 0xFF000000;

    /** 底部栏三按钮图标+文字换色（分享/删除/设为背景） */
    private void tintBottomButtons(int iconColor, int textColor) {
        int[][] ids = {
                {com.aliya.hyfilemgr.R.id.btn_iv_share, com.aliya.hyfilemgr.R.id.iv_share_icon},
                {com.aliya.hyfilemgr.R.id.btn_iv_delete, com.aliya.hyfilemgr.R.id.iv_delete_icon},
                {com.aliya.hyfilemgr.R.id.btn_iv_wallpaper, com.aliya.hyfilemgr.R.id.iv_wallpaper_icon}};
        for (int[] pair : ids) {
            LinearLayout row = findViewById(pair[0]);
            ImageView icon = row.findViewById(pair[1]);
            TextView label = (TextView) row.getChildAt(1);
            icon.setColorFilter(iconColor);
            label.setTextColor(textColor);
        }
    }

    // ── 底部功能栏动作 ──

    /** 系统分享：content:// 用原 Uri，本地文件走 FileProvider */
    private void shareImage(String path, String uriStr, boolean isUri) {
        try {
            Uri uri;
            String name;
            if (isUri) {
                uri = Uri.parse(uriStr);
                name = "image";
            } else {
                File f = new File(path);
                uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", f);
                name = f.getName();
            }
            String mime = getMimeType(name);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime != null ? mime : "image/*");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享图片"));
        } catch (Throwable t) {
            UiKit.toast(this, "分享失败：" + t.getMessage());
        }
    }

    /** 删除确认：确认后回传主界面，由模块走回收站+列表刷新 */
    private void confirmDelete(String path) {
        String name = new File(path).getName();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("⚠ 删除确认");
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(title);

        TextView tv = new TextView(this);
        tv.setText("确定删除「" + name + "」吗？\n（将按主界面设置移入回收站或直接删除）");
        tv.setTextSize(13);
        tv.setTextColor(0xCCFFFFFF);
        tv.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(tv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.END);

        final android.app.Dialog dialog = UiKit.glassDialog(this, box);
        android.widget.TextView cancel = new android.widget.TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(0xCCFFFFFF);
        cancel.setPadding(dp(16), dp(8), dp(16), dp(8));
        cancel.setOnClickListener(v -> UiKit.dismissWithAnim(dialog));
        android.widget.TextView ok = new android.widget.TextView(this);
        ok.setText("删除");
        ok.setTextSize(14);
        ok.setTypeface(null, android.graphics.Typeface.BOLD);
        ok.setTextColor(0xFFFF6E6E);
        ok.setPadding(dp(16), dp(8), dp(16), dp(8));
        ok.setOnClickListener(v -> {
            UiKit.dismissWithAnim(dialog);
            Intent data = new Intent();
            data.putExtra(RESULT_ACTION_DELETE, path);
            setResult(RESULT_OK, data);
            finish();
        });
        btns.addView(cancel);
        btns.addView(ok);
        box.addView(btns);

        dialog.show();
    }

    /** 设为背景：先选目标（全局 / 文件列表页），确认后回传主界面。
     *
     * <p>不用 AlertDialog：framework 主题下它是深底深字，且与玻璃弹窗风格不一致。
     * 自建 glassDialog + 浅色文字，与暗色沉浸一致。</p> */
    private void askWallpaperTarget(String path) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("设为背景图");
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(title);

        final android.app.Dialog dialog = UiKit.glassDialog(this, box);

        String[] targets = {"全局背景", "文件列表页背景"};
        for (int i = 0; i < targets.length; i++) {
            final int which = i;
            TextView row = new TextView(this);
            row.setText(targets[i]);
            row.setTextSize(14);
            row.setTextColor(0xE6FFFFFF);
            row.setPadding(dp(4), dp(12), dp(4), dp(12));
            row.setBackgroundResource(com.aliya.hyfilemgr.R.drawable.bg_ripple);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                UiKit.dismissWithAnim(dialog);
                Intent data = new Intent();
                data.putExtra(RESULT_ACTION_WALLPAPER, path);
                data.putExtra(EXTRA_FOR_FM, which == 1);
                setResult(RESULT_OK, data);
                finish();
            });
            box.addView(row);
        }

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(13);
        cancel.setTextColor(0x99FFFFFF);
        cancel.setPadding(dp(4), dp(10), dp(4), dp(2));
        cancel.setOnClickListener(v -> UiKit.dismissWithAnim(dialog));
        box.addView(cancel);

        dialog.show();
    }

    private String getMimeType(String name) {
        String ext = "";
        int i = name.lastIndexOf('.');
        if (i >= 0) ext = name.substring(i + 1).toLowerCase();
        switch (ext) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            default: return null;
        }
    }

    /** 中央居中显示（初始与双击还原共用） */
    private void resetMatrix() {
        int vw = imageView.getWidth(), vh = imageView.getHeight();
        android.graphics.drawable.Drawable d = imageView.getDrawable();
        if (d == null || vw == 0 || vh == 0) {
            matrix.reset();
            imageView.setImageMatrix(matrix);
            return;
        }
        float sx = (float) vw / d.getIntrinsicWidth();
        float sy = (float) vh / d.getIntrinsicHeight();
        float s = Math.min(sx, sy);           // 完整显示，不裁切
        float dx = (vw - d.getIntrinsicWidth() * s) / 2f;
        float dy = (vh - d.getIntrinsicHeight() * s) / 2f;
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate(dx, dy);
        imageView.setImageMatrix(matrix);
    }

    /** 按屏幕尺寸采样解码，避免大图 OOM（高清模式下改为保细节采样 + ARGB_8888） */
    private Bitmap decodeSampled(String src, boolean isUri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (isUri) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(src))) {
                BitmapFactory.decodeStream(in, null, o);
            }
        } else {
            BitmapFactory.decodeFile(src, o);
        }
        o.inSampleSize = hdMode ? calcHdSample(o.outWidth, o.outHeight)
                                : calcSample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        // RGB_565 省一半内存但会丢色阶（渐变、文字边缘发虚）；
        // 高清模式用 ARGB_8888 保原色
        o.inPreferredConfig = hdMode ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        if (isUri) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(src))) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        }
        return BitmapFactory.decodeFile(src, o);
    }

    private int calcSample(int w, int h) {
        int reqW = getResources().getDisplayMetrics().widthPixels;
        int reqH = getResources().getDisplayMetrics().heightPixels;
        int sample = 1;
        // 用 || 而非 &&：超宽/超高图（如 12000×3000）在 && 下 sample 恒为 1，
        // 会全尺寸解码（3000 万像素 ≈ 60MB）。同时加上界防死循环。
        while ((w / sample > reqW * 2 || h / sample > reqH * 2) && sample < 16) sample *= 2;
        return Math.max(1, sample);
    }
}
