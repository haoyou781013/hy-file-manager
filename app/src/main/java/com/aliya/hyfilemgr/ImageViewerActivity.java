package com.aliya.hyfilemgr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.aliya.hyfilemgr.ui.UiKit;

import java.io.File;
import java.io.InputStream;

/**
 * 内置图片查看器（原生 BitmapFactory + Matrix + ViewPager2，无第三方图片库）。
 *
 * <p>配合文件管理的「打开方式」弹窗使用：图片类文件优先由本页打开。</p>
 *
 * <p><b>2026-11-12 重构：滑动改用 ViewPager2</b>。此前是单个 ImageView +
 * GestureDetector.onFling 切图，观感是「甩一下→换一张」，拖动过程中看不到
 * 下一张，与主流相册差距明显。现在：</p>
 * <ul>
 *   <li>跟手左右拖动、相邻图半露可见、松手吸附/翻页（{@link ViewPager2}）</li>
 *   <li>每页内部仍是独立 ImageView，自带双指缩放/拖动/双击还原</li>
 *   <li>图片放大后水平拖动优先拖图（不翻页）——通过
 *       {@code requestDisallowInterceptTouchEvent} 实现</li>
 *   <li>只缓存当前位置 ±1 页，翻页时不再重复解码相邻图</li>
 * </ul>
 *
 * <p>底部功能栏三个操作均<strong>回传主界面</strong>处理：删除走模块
 * confirmDelete（回收站+刷新），设为背景走 setBackgroundFromUri。
 * 回传协议见 {@code RESULT_ACTION_*}。</p>
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

    /** 切图淡入时长：仅在「新页首次显示」时用，翻页本身有 ViewPager 动效 */
    private static final long FADE_MS = 160;
    /** 背景色过渡时长（栏显隐时整页背景渐变） */
    private static final long BG_ANIM_MS = 220;

    private ViewPager2 pager;
    private GalleryAdapter adapter;
    private View topBar, bottomBar;
    private boolean barsVisible = true;

    /** 同目录图片列表与当前位置（EXTRA_LIST 未传时只含当前一张，不显示计数） */
    private String[] gallery;
    private int galleryIndex;
    /** 当前展示的路径（删除/设背景都以它为准） */
    private String currentPath;
    private boolean currentIsUri;
    private String currentUriStr;

    /** 高清模式（默认值从设置读） */
    private boolean hdMode = true;
    /** onCreate 首次落位不播背景动画 */
    private boolean firstApply = true;
    /** 当前背景色（ArgbEvaluator 动画起点） */
    private int currentBg = 0xFF000000;

    @Override protected void attachBaseContext(android.content.Context base) {
        // 应用级 DPI 覆盖：与主界面/播放器一致，否则密度设置对本页无效
        super.attachBaseContext(com.aliya.hyfilemgr.util.DpiUtils.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(com.aliya.hyfilemgr.R.layout.activity_image_viewer);

        pager = findViewById(com.aliya.hyfilemgr.R.id.iv_pager);
        topBar = findViewById(com.aliya.hyfilemgr.R.id.iv_top);
        bottomBar = findViewById(com.aliya.hyfilemgr.R.id.iv_bottom);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String uriStr = getIntent().getStringExtra(EXTRA_URI);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        boolean isUri = uriStr != null && !uriStr.isEmpty();
        currentPath = path;
        currentIsUri = isUri;
        currentUriStr = uriStr;

        // 高清模式默认值从设置读（与模块共享同一个 SharedPreferences）
        try {
            hdMode = getSharedPreferences("hyvq_filemgr", MODE_PRIVATE)
                    .getBoolean("viewer_hd_default", true);
        } catch (Throwable ignored) {
            hdMode = true;
        }

        // 同目录图集：模块传来的当前窗格列表。未传或只有一张 → 只有一项，不显示计数
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
        updateTitleAndCounter(title == null || title.isEmpty() ? name : title);

        findViewById(com.aliya.hyfilemgr.R.id.btn_iv_back).setOnClickListener(v -> finish());

        // ── 底部功能栏 ──
        // zip 虚拟路径（zip://…）既不能删除也不能设为背景（无真实文件、
        // 删除会破坏包结构），但可以分享（解出后分享临时文件）
        final boolean inZip = currentPath != null && currentPath.startsWith(ZIP_PREFIX);
        View del = findViewById(com.aliya.hyfilemgr.R.id.btn_iv_delete);
        View wp = findViewById(com.aliya.hyfilemgr.R.id.btn_iv_wallpaper);
        del.setVisibility(!isUri && !inZip ? View.VISIBLE : View.GONE);
        wp.setVisibility(!isUri && !inZip ? View.VISIBLE : View.GONE);
        // 用 currentPath 间接引用：翻页后按钮作用对象要跟着变
        del.setOnClickListener(v -> confirmDelete(currentPath));
        wp.setOnClickListener(v -> askWallpaperTarget(currentPath));
        findViewById(com.aliya.hyfilemgr.R.id.btn_iv_share).setOnClickListener(
                v -> shareImage(currentPath, currentUriStr, currentIsUri));

        setupPager();
        setupStatusBarAndInsets();
        setBarsVisible(true);
    }

    // ══════ ViewPager2 ══════

    private void setupPager() {
        adapter = new GalleryAdapter();
        pager.setAdapter(adapter);
        // 单页时禁止滑动，避免误以为能拖
        pager.setUserInputEnabled(gallery != null && gallery.length > 1);
        // 预加载相邻页：翻页时不用现解码（提升跟手感）
        pager.setOffscreenPageLimit(1);
        pager.setCurrentItem(galleryIndex, false);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                galleryIndex = position;
                String p = gallery[position];
                currentPath = p;
                currentIsUri = false;
                currentUriStr = null;
                updateTitleAndCounter(displayNameOf(p));
                // 翻页后重置新页缩放，避免继承上一页的放大状态
                View page = findPage(position);
                if (page instanceof ImagePage) ((ImagePage) page).resetZoom();
                // 按钮可用性跟随当前路径：zip 虚拟路径不能删/设背景
                refreshBottomButtons();
            }
        });
    }

    /** 底栏按钮可用性：zip 虚拟路径不能删除/设背景（无真实文件），但可分享 */
    private void refreshBottomButtons() {
        boolean inZip = currentPath != null && currentPath.startsWith(ZIP_PREFIX);
        findViewById(com.aliya.hyfilemgr.R.id.btn_iv_delete)
                .setVisibility(!currentIsUri && !inZip ? View.VISIBLE : View.GONE);
        findViewById(com.aliya.hyfilemgr.R.id.btn_iv_wallpaper)
                .setVisibility(!currentIsUri && !inZip ? View.VISIBLE : View.GONE);
    }

    /** 显示名称：zip://… 取包内条目名 */
    private String displayNameOf(String path) {
        if (path == null) return "图片";
        if (path.startsWith(ZIP_PREFIX)) {
            String rest = path.substring(ZIP_PREFIX.length());
            int cut = rest.lastIndexOf('/');
            // 去掉最后一段包内条目后的剩余，取条目名本身
            String inner = rest;
            String lower = rest.toLowerCase();
            for (int i = rest.length() - 1; i >= 0; i--) {
                if (rest.charAt(i) != '/') continue;
                String before = lower.substring(0, i);
                if (before.endsWith(".zip") || before.endsWith(".jar")
                        || before.endsWith(".apk") || before.endsWith(".epub")) {
                    inner = rest.substring(i + 1);
                    break;
                }
            }
            int slash = inner.lastIndexOf('/');
            return slash >= 0 ? inner.substring(slash + 1) : inner;
        }
        return new File(path).getName();
    }

    /** 取当前 ViewPager2 内部 RecyclerView 中承载指定 position 的页 View */
    private View findPage(int position) {
        try {
            RecyclerView rv = (RecyclerView) pager.getChildAt(0);
            if (rv == null) return null;
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                int pos = rv.getChildAdapterPosition(child);
                if (pos == position) return child;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void updateTitleAndCounter(String title) {
        TextView tvTitle = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_title);
        tvTitle.setText(title);
        TextView counter = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_counter);
        if (gallery != null && gallery.length > 1) {
            counter.setVisibility(View.VISIBLE);
            counter.setText(String.format("%02d/%02d", galleryIndex + 1, gallery.length));
        } else {
            counter.setVisibility(View.GONE);
        }
    }

    /** ViewPager2 适配器：每页一个 ImagePage（自带缩放/拖动/双击/长按） */
    private class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImagePage page = new ImagePage();
            page.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new VH(page);
        }

        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            ((ImagePage) holder.itemView).bind(gallery[position]);
        }

        @Override public int getItemCount() {
            return gallery == null ? 0 : gallery.length;
        }

        class VH extends RecyclerView.ViewHolder {
            VH(@NonNull View itemView) { super(itemView); }
        }
    }

    /**
     * 单张图片页：双击缩放/双指缩放/拖动/单击切栏/长按切高清。
     *
     * <p><b>与 ViewPager2 的手势冲突处理</b>（关键）：
     * <ul>
     *   <li>未放大：单指水平拖动交给 ViewPager2 翻页（不拦截）</li>
     *   <li>已放大：单指拖动是看边缘 → {@code requestDisallowInterceptTouchEvent(true)}
     *       阻止 ViewPager2 抢走事件</li>
     * </ul>
     * 否则放大后想拖看右侧，页面直接翻走了。</p>
     */
    private class ImagePage extends FrameLayout {
        final ImageView iv;
        final Matrix matrix = new Matrix();
        android.view.ScaleGestureDetector scaleDetector;
        GestureDetector gestureDetector;
        private String boundPath;
        private boolean decoding;
        /** 解码序号：连续翻页时只认最后一次，防旧图晚到覆盖新图 */
        private int seq;

        ImagePage() {
            super(ImageViewerActivity.this);
            iv = new ImageView(ImageViewerActivity.this);
            iv.setScaleType(ImageView.ScaleType.MATRIX);
            iv.setBackgroundColor(0x00000000);
            addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            scaleDetector = new android.view.ScaleGestureDetector(ImageViewerActivity.this,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                            float f = d.getScaleFactor();
                            matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                            iv.setImageMatrix(matrix);
                            return true;
                        }
                    });

            gestureDetector = new GestureDetector(ImageViewerActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDoubleTap(MotionEvent e) {
                            resetZoom();
                            return true;
                        }

                        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                                         float dx, float dy) {
                            if (e2.getPointerCount() > 1) return false;   // 双指交给缩放
                            if (!isZoomedIn()) {
                                // 未放大：不消费，让 ViewPager2 翻页
                                return false;
                            }
                            matrix.postTranslate(-dx, -dy);
                            iv.setImageMatrix(matrix);
                            return true;
                        }

                        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                            toggleBars();
                            return true;
                        }

                        @Override public void onLongPress(MotionEvent e) {
                            // 长按 = 切换高清模式（用当前页的路径）
                            toggleHd();
                        }
                    });

            setOnTouchListener((v, ev) -> {
                // 放大状态下，从 ACTION_DOWN 起就禁止 ViewPager2 抢事件；
                // 松开后再恢复，否则翻不动页
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(isZoomedIn());
                }
                scaleDetector.onTouchEvent(ev);
                gestureDetector.onTouchEvent(ev);
                return true;
            });
        }

        /** 绑定路径：解码并展示（同路径已解码则跳过） */
        void bind(String path) {
            if (path == null || path.equals(boundPath)) return;
            boundPath = path;
            decoding = true;
            final int mySeq = ++seq;
            iv.setImageDrawable(null);
            iv.setAlpha(0f);
            resetZoomMatrix();

            // GIF（API 28+）：AnimatedImageDrawable 直接播动图
            if (android.os.Build.VERSION.SDK_INT >= 28
                    && path.toLowerCase().endsWith(".gif")) {
                try {
                    android.graphics.ImageDecoder.Source src =
                            android.graphics.ImageDecoder.createSource(new File(path));
                    android.graphics.drawable.Drawable d =
                            android.graphics.ImageDecoder.decodeDrawable(src, (decoder, info, s) -> {
                                android.util.Size sz = info.getSize();
                                int reqW = getResources().getDisplayMetrics().widthPixels;
                                int reqH = getResources().getDisplayMetrics().heightPixels;
                                if (sz.getWidth() > reqW * 2 || sz.getHeight() > reqH * 2) {
                                    decoder.setTargetSampleSize(2);
                                }
                            });
                    if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
                        post(() -> {
                            if (mySeq != seq) return;
                            decoding = false;
                            iv.setImageDrawable(d);
                            iv.animate().alpha(1f).setDuration(FADE_MS).start();
                            resetZoomMatrix();
                            ((android.graphics.drawable.AnimatedImageDrawable) d).start();
                        });
                        return;
                    }
                } catch (Throwable ignored) {
                    // 落到静态解码
                }
            }

            new Thread(() -> {
                Bitmap bmp = null;
                try {
                    bmp = decodeSampled(path, false);
                } catch (Throwable ignored) { }
                final Bitmap fb = bmp;
                post(() -> {
                    if (mySeq != seq) return;
                    decoding = false;
                    if (fb == null) {
                        return;   // 解码失败：留空白，不弹 toast 打扰翻页
                    }
                    iv.setImageBitmap(fb);
                    iv.animate().alpha(1f).setDuration(FADE_MS).start();
                    resetZoomMatrix();
                });
            }, "HyImgDecode").start();
        }

        boolean isZoomedIn() {
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d == null) return false;
            int vw = iv.getWidth(), vh = iv.getHeight();
            int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
            if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return false;
            float fit = Math.min((float) vw / dw, (float) vh / dh);
            float[] v = new float[9];
            matrix.getValues(v);
            return v[android.graphics.Matrix.MSCALE_X] > fit * 1.1f;
        }

        void resetZoom() {
            resetZoomMatrix();
            iv.setImageMatrix(matrix);
        }

        /** 居中适配显示（初始与双击还原共用） */
        void resetZoomMatrix() {
            int vw = iv.getWidth(), vh = iv.getHeight();
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d == null || vw == 0 || vh == 0) {
                matrix.reset();
                iv.setImageMatrix(matrix);
                return;
            }
            float sx = (float) vw / d.getIntrinsicWidth();
            float sy = (float) vh / d.getIntrinsicHeight();
            float s = Math.min(sx, sy);
            float dx = (vw - d.getIntrinsicWidth() * s) / 2f;
            float dy = (vh - d.getIntrinsicHeight() * s) / 2f;
            matrix.reset();
            matrix.postScale(s, s);
            matrix.postTranslate(dx, dy);
            iv.setImageMatrix(matrix);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            resetZoomMatrix();
        }
    }

    // ══════ 状态栏与 insets ══════

    private void setupStatusBarAndInsets() {
        android.view.Window w = getWindow();
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x99000000);

        final LinearLayout top = (LinearLayout) topBar;
        final LinearLayout bot = (LinearLayout) bottomBar;
        final View root = findViewById(com.aliya.hyfilemgr.R.id.iv_root);
        View.OnClickListener applyInsets = v -> {
            androidx.core.view.WindowInsetsCompat ins =
                    androidx.core.view.ViewCompat.getRootWindowInsets(root);
            if (ins == null) return;
            androidx.core.graphics.Insets sys = ins.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            top.setPadding(top.getPaddingLeft(), sys.top + dp(8), top.getPaddingRight(), dp(8));
            bot.setPadding(bot.getPaddingLeft(), dp(10),
                    bot.getPaddingRight(), sys.bottom + dp(10));
        };
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { applyInsets.onClick(null); }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
        root.post(() -> applyInsets.onClick(null));
        androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView())
                .setAppearanceLightStatusBars(false);
    }

    // ══════ 高清模式 ══════

    /**
     * 高清模式开关（长按图片切换，只影响当前页重新解码）。
     *
     * <p>默认值来自设置页「图片查看器 → 高清模式」（持久化在
     * hyvq_filemgr 的 viewer_hd_default）。长按只改本次会话，不回写设置——
     * 临时行为不覆盖用户偏好。</p>
     */
    private void toggleHd() {
        hdMode = !hdMode;
        UiKit.toast(this, hdMode ? "高清模式：开" : "高清模式：关（省内存）");
        // 强制当前页重新解码
        if (adapter != null) {
            View page = findPage(galleryIndex);
            if (page instanceof ImagePage) {
                ImagePage ip = (ImagePage) page;
                ip.boundPath = null;
                ip.bind(gallery[galleryIndex]);
            }
        }
    }

    /** 高清采样：原始尺寸不超屏幕 4 倍则用 1，超出按 2/4 逐级降（上界 8） */
    private int calcHdSample(int w, int h) {
        int reqW = getResources().getDisplayMetrics().widthPixels;
        int reqH = getResources().getDisplayMetrics().heightPixels;
        int sample = 1;
        while ((w / (sample * 2) > reqW * 4 || h / (sample * 2) > reqH * 4) && sample < 8) {
            sample *= 2;
        }
        return sample;
    }

    /** 普通模式采样：按屏幕 2 倍，上界 16（超宽/超高图也能降下来） */
    private int calcSample(int w, int h) {
        int reqW = getResources().getDisplayMetrics().widthPixels;
        int reqH = getResources().getDisplayMetrics().heightPixels;
        int sample = 1;
        // 用 || 而非 &&：超宽/超高图在 && 下 sample 恒为 1，会全尺寸解码
        while ((w / sample > reqW * 2 || h / sample > reqH * 2) && sample < 16) sample *= 2;
        return Math.max(1, sample);
    }

    /** 按目标尺寸采样解码，避免大图 OOM。zip:// 虚拟路径会先解出到缓存再读。 */
    private Bitmap decodeSampled(String src, boolean isUri) throws Exception {
        // zip 虚拟路径：先解出到 cacheDir/zipview（查看器页每次只解当前页）
        if (src != null && src.startsWith(ZIP_PREFIX)) {
            src = extractZipEntry(src);
            if (src == null) return null;
            isUri = false;
        }
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
        o.inPreferredConfig = hdMode ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        if (isUri) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(src))) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        }
        return BitmapFactory.decodeFile(src, o);
    }

    /** ZIP 虚拟路径前缀（与模块 PREFIX_ZIP 一致） */
    private static final String ZIP_PREFIX = "zip://";

    /**
     * 解开 {@code zip://<zip绝对路径>/<包内条目>}，把条目写到 cacheDir/zipview 并返回本地路径。
     *
     * <p>缓存文件名带「zip 路径哈希 + 条目名」前缀，避免不同压缩包内同名条目互相覆盖。
     * 已解出过则直接复用（同一个包反复翻页不重复解）。</p>
     *
     * @return 本地文件路径；失败返回 null
     */
    private String extractZipEntry(String virtPath) {
        String[] parts = com.aliya.hyfilemgr.archive.ZipCache.splitVirtual(virtPath);
        if (parts == null) return null;
        // 统一走 ZipCache：它负责 SHA-256 命名、per-archive 追踪、
        // 总量上限淘汰，并且支持「退出压缩包时清理」（用户要求）。
        return com.aliya.hyfilemgr.archive.ZipCache.extractPath(
                this, com.aliya.hyfilemgr.archive.ZipCache.DIR_VIEW,
                parts[0], parts[1]);
    }

    // ══════ 顶/底栏显隐 ══════

    private void toggleBars() {
        setBarsVisible(!barsVisible);
    }

    private void setBarsVisible(boolean show) {
        barsVisible = show;
        int vis = show ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        bottomBar.setVisibility(vis);

        View root = findViewById(com.aliya.hyfilemgr.R.id.iv_root);
        ImageView backBtn = findViewById(com.aliya.hyfilemgr.R.id.btn_iv_back);
        TextView title = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_title);
        TextView info = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_info);
        TextView counter = findViewById(com.aliya.hyfilemgr.R.id.tv_iv_counter);
        int mainText = show ? 0xFF1A1A1A : 0xFFFFFFFF;
        int subText = show ? 0x991A1A1A : 0x99FFFFFF;
        int scrim = show ? 0x00000000 : 0x99000000;
        topBar.setBackgroundColor(scrim);
        bottomBar.setBackgroundColor(scrim);
        backBtn.setColorFilter(mainText);
        title.setTextColor(mainText);
        info.setTextColor(subText);
        counter.setTextColor(subText);
        tintBottomButtons(mainText, subText);

        android.animation.ValueAnimator bgAnim = android.animation.ValueAnimator.ofObject(
                new android.animation.ArgbEvaluator(), currentBg, show ? 0xFFFFFFFF : 0xFF000000);
        bgAnim.setDuration(firstApply ? 0 : BG_ANIM_MS);
        bgAnim.addUpdateListener(a -> {
            currentBg = (int) a.getAnimatedValue();
            root.setBackgroundColor(currentBg);
        });
        bgAnim.start();
        firstApply = false;

        android.view.Window w = getWindow();
        w.setStatusBarColor(show ? 0xFFFFFFFF : 0x00000000);
        androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView())
                .setAppearanceLightStatusBars(show);
    }


    /** 底部栏三按钮图标+文字换色 */
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

    // ══════ 底部功能栏动作 ══════

    /** 系统分享：content:// 用原 Uri，本地文件走 FileProvider，zip 条目先解出 */
    private void shareImage(String path, String uriStr, boolean isUri) {
        try {
            String realPath = path;
            if (path != null && path.startsWith(ZIP_PREFIX)) {
                realPath = extractZipEntry(path);
                if (realPath == null) {
                    UiKit.toast(this, "解出失败，无法分享");
                    return;
                }
            }
            Uri uri;
            String name;
            if (isUri) {
                uri = Uri.parse(uriStr);
                name = "image";
            } else {
                File f = new File(realPath);
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
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(0xCCFFFFFF);
        cancel.setPadding(dp(16), dp(8), dp(16), dp(8));
        cancel.setOnClickListener(v -> UiKit.dismissWithAnim(dialog));
        TextView ok = new TextView(this);
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

    /**
     * 设为背景：先选目标（全局 / 文件列表页），确认后回传主界面。
     *
     * <p>不用 AlertDialog：framework 主题下它是深底深字，与暗色沉浸不一致。</p>
     */
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

    /** SHA-256 前若干 hex（用于缓存文件名，防碰撞） */
    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
