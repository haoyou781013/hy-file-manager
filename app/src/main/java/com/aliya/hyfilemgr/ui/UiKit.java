package com.aliya.hyfilemgr.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.WindowManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * HY_VQ 模块 UI 适配工具包（Module UI Kit）。
 *
 * <p>目的：保证软件对模块 UI 的适配性。任何模块（内置或外置）都可以调用本工具包
 * 构建对话框、卡片行、标题等组件，自动获得与主框架一致的玻璃拟态 / Material3
 * 主题外观，并随深色模式、用户自定义主题（模块贡献的 HyVqTheme）自动变化。</p>
 *
 * <p>所有方法都是纯 Java 构建（基于主题 attr 解析），不依赖模块自带资源，
 * 因此外置模块无需打包任何 drawable/layout 也能获得统一视觉。</p>
 */
public final class UiKit {

    private UiKit() {}

    // ── 主题工具 ──

    /** 解析当前主题下某个 attr 的颜色值（模块 UI 与主框架配色保持一致的关键） */
    // ══════════════ 对比度感知的颜色助手（WCAG AA 合规）════════════
    //
    // 实测：本项目 10 套主题的 primary 全是浅色（#42A5F5 / #81C784 / #B39DDB …），
    // 配白色 onPrimary 文字的对比度仅 **1.16 ~ 2.65:1**，全部低于 WCAG AA 的 4.5:1。
    // 但直接改主题色会动到全 App 观感 —— 此处在**使用点**按需压暗到达标，
    // 主题仍是唯一色源，观感保持同色系。

    /** WCAG 对比度公式：(L1+0.05)/(L2+0.05) */
    public static double contrast(String fgHex, String bgHex) {
        double la = relativeLuminance(fgHex), lb = relativeLuminance(bgHex);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relativeLuminance(String hex) {
        String h = hex.replace("#", "");
        if (h.length() == 8) h = h.substring(2);
        double r = Integer.parseInt(h.substring(0, 2), 16) / 255.0;
        double g = Integer.parseInt(h.substring(2, 4), 16) / 255.0;
        double b = Integer.parseInt(h.substring(4, 6), 16) / 255.0;
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    }

    private static double f(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * 返回「配白字也能达到 WCAG AA(4.5:1)」的加深版本。
     *
     * <p>做法：按 HSL 把亮度逐级下调，直到与白色对比度达标；
     * 达标即停（**不改变色相**，观感仍是同一色系的深浅变化）。
     * 最深下探到 8% 亮度仍不达标时返回原色（避免变成纯黑）。</p>
     */
    public static int darkenForWhite(int color) {
        int a = (color >>> 24) & 0xFF;
        if (a == 0) a = 255;

        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);   // hsv = {hue, sat, val}
        float light = hsv[2];

        for (int i = 0; i < 40 && light > 0.08f; i++) {
            int cand = android.graphics.Color.HSVToColor(hsv);   // 每轮基于 hsv[2] 重算
            if (contrast(hex(cand), "#FFFFFF") >= 4.5) {
                return (a << 24) | (cand & 0xFFFFFF);
            }
            light -= 0.03f;
            hsv[2] = light;                                // ← 关键：更新亮度
        }
        hsv[2] = 0.08f;
        int cand = android.graphics.Color.HSVToColor(hsv);
        return (a << 24) | (cand & 0xFFFFFF);
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.US, "#%06X", color & 0xFFFFFF);
    }


    /** 同上，直接接收主题色 int */
    public static int onPrimarySafe(int color) {
        if (contrast(hex(color), "#FFFFFF") >= 4.5) {
            return color;
        }
        return darkenForWhite(color);
    }

    // ══════════════ 全局排版标尺（跨模块统一）════════════
    //
    // 全量审计发现：全项目存在 12 种字号（9/10/11/12/12.5/13/14/15/16/17/20/26/30），
    // 其中 9sp 过小（可读性问题）、12.5/17 离网格。此处定义标尺，
    // 新代码一律引用常量；旧值逐步向标尺靠拢。
    /** 微型标签 / 角标（原 9/10sp 过小，统一 11） */
    public static final float TYPE_MICRO = 11f;
    /** 次要说明文字 */
    public static final float TYPE_CAPTION = 12f;
    /** 正文 */
    public static final float TYPE_BODY = 13f;
    /** 强调正文 */
    public static final float TYPE_BODY_LARGE = 14f;
    /** 小标题 */
    public static final float TYPE_TITLE = 16f;
    /** 段落标题 */
    public static final float TYPE_HEADLINE = 20f;
    /** 页面大标题 */
    public static final float TYPE_DISPLAY = 26f;

    // ══════════════ 全局圆角标尺 ═════════════
    /** 小组件（图标内衬 / 输入框 / 胶囊内） */
    public static final int RADIUS_SMALL = 8;
    /** 按钮 / 中卡 */
    public static final int RADIUS_MEDIUM = 14;
    /** 内容卡片 / 弹窗容器 */
    public static final int RADIUS_LARGE = 24;
    /** 全圆角胶囊（标签 / 底栏 / 播放钮） */
    public static final int RADIUS_PILL = 999;

    /** 按标尺取最接近的字号（供旧代码迁移用） */
    public static float snapType(float sp) {
        float[] scale = {TYPE_MICRO, TYPE_CAPTION, TYPE_BODY, TYPE_BODY_LARGE,
                TYPE_TITLE, TYPE_HEADLINE, TYPE_DISPLAY};
        float best = scale[0];
        float diff = Math.abs(sp - best);
        for (float f : scale) {
            float d = Math.abs(sp - f);
            if (d < diff) { diff = d; best = f; }
        }
        return best;
    }

    public static int color(Context context, int attr) {
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    /** 按名称解析模块图标（如 "ic_chat"），找不到时用兜底图标 */
    public static int iconRes(Context context, String iconName, int fallbackRes) {
        if (iconName == null || iconName.isEmpty()) return fallbackRes;
        int id = context.getResources().getIdentifier(iconName, "drawable", context.getPackageName());
        return id == 0 ? fallbackRes : id;
    }

    public static void toast(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }


    /**
     * 失败 / 错误提示：可关闭的弹框，而不是几秒就消失的 Toast。
     *
     * <p>为什么失败不用 Toast：错误信息往往较长（含异常 message），
     * Toast 2 秒消失用户来不及读完；多个错误连续出现时还会互相覆盖。
     * 成功 / 进行中类反馈仍用 {@link #toast}，那类信息一句话就够。</p>
     *
     * <p>标题固定为「操作失败」，正文为具体原因；只有一个「知道了」按钮。</p>
     */
    public static void alert(Context context, String message) {
        if (context == null || message == null || message.isEmpty()) return;
        try {
            android.widget.LinearLayout box = new android.widget.LinearLayout(context);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);

            android.widget.TextView title = new android.widget.TextView(context);
            title.setText("操作失败");
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setTextColor(color(context,
                    com.google.android.material.R.attr.colorError));
            title.setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 6));
            box.addView(title);

            android.widget.TextView body = new android.widget.TextView(context);
            body.setText(message);
            body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            body.setTextColor(color(context,
                    com.google.android.material.R.attr.colorOnSurface));
            body.setLineSpacing(dp(context, 2), 1f);
            body.setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 4));
            // 长错误信息可滚动，避免把按钮挤出屏幕
            android.widget.ScrollView sv = new android.widget.ScrollView(context);
            sv.setVerticalScrollBarEnabled(true);
            sv.addView(body);
            android.widget.LinearLayout.LayoutParams svLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            box.addView(sv, svLp);

            android.widget.LinearLayout btns = new android.widget.LinearLayout(context);
            btns.setGravity(android.view.Gravity.END);
            android.widget.TextView ok = new android.widget.TextView(context);
            ok.setText("知道了");
            ok.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            ok.setTypeface(null, android.graphics.Typeface.BOLD);
            ok.setTextColor(color(context,
                    com.google.android.material.R.attr.colorPrimary));
            ok.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
            ok.setClickable(true);
            ok.setBackgroundResource(
                    com.aliya.hyfilemgr.R.drawable.bg_ripple_round);
            box.addView(ok);
            box.addView(btns);

            android.app.Dialog d = glassDialog(context, box);
            ok.setOnClickListener(v -> dismissWithAnim(d));
            d.show();
        } catch (Throwable t) {
            // 弹框本身失败时退回 toast，不要连错误都报不出去
            toast(context, message);
        }
    }

    // ── 玻璃对话框 ──

    /**
     * 创建玻璃拟态对话框骨架：透明窗口 + 圆角玻璃背景 + 92% 屏宽。
     * 模块把自己的内容 View 传入即可。
     */
    public static Dialog glassDialog(Context context, View content) {
        return glassDialog(context, content, true);
    }

    /** @param dismissOnOutside 点击面板外的模糊区域是否关闭弹窗（进度类弹窗传 false） */
    public static Dialog glassDialog(Context context, View content, boolean dismissOnOutside) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = dp(context, 20);
        int surface = color(context, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int stroke = color(context, com.google.android.material.R.attr.colorOutlineVariant);

        // ── 玻璃质感面板（88% 不透明度，让窗口背景模糊透出来）──
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable gd = rounded(context, 24, (surface & 0x00FFFFFF) | 0xE0000000, stroke);
        gd.setStroke(dp(context, 1), stroke);
        panel.setBackground(gd);
        panel.setClickable(true);   // 点面板内部不应关闭
        if (content != null) {
            panel.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ⭐ 「点击面板外的阴影区域关闭」改用**真实遮罩层**实现（2026-09-26 重做）。
        // 旧做法只在 decor 上 setClickable+setOnClickListener 兜底：而 applyBlur 为了让模糊
        // 背景铺满会把窗口设成 MATCH_PARENT，此时「弹窗外的区域」仍在窗口内、且面板之外
        // **没有任何真实子 View**，触摸能否冒泡到 decor 完全依赖系统分发细节 —— 实测不可靠。
        // 现在在面板**下方**铺一层占满窗口的透明遮罩：面板外任意位置点击必然命中它 →
        // 物理上不可能失效；面板在上层且自身 clickable，点面板内部不会误关。
        int panelW = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92);
        FrameLayout shell = new FrameLayout(context);
        if (dismissOnOutside) {
            View scrim = new View(context);
            scrim.setClickable(true);
            scrim.setFocusable(true);
            scrim.setOnClickListener(v -> dialog.dismiss());
            shell.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // ⚠️ 高度上限：面板原本是 WRAP_CONTENT，内容一多（压缩对话框的
        // 文件名/格式/等级/密码/多个复选/按钮）就会超出屏幕，
        // 底部按钮被裁掉且**无法滚动** —— 横屏或大字体下必然踩到。
        // 这里给面板一个屏幕 80% 的硬上限，并把内容区包进 ScrollView。
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        int maxPanelH = (int) (screenH * 0.8f);

        FrameLayout.LayoutParams panelLp =
                new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
        panel.setLayoutParams(panelLp);
        panel.setMinimumHeight(0);
        // 用「最大高度」约束：View 本身不支持 maxHeight，
        // 改为把 panel 包一层 ScrollView，由 ScrollView 撑满上限并在内部滚动。
        android.widget.ScrollView panelScroll = new android.widget.ScrollView(context);
        panelScroll.setVerticalScrollBarEnabled(true);
        panelScroll.setOverScrollMode(android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        panelScroll.setClipToPadding(false);
        // 面板自身的圆角背景移到 ScrollView 之外的容器，
        // 否则滚动时背景会跟着内容走、露出未圆角的空白
        FrameLayout panelHolder = new FrameLayout(context);
        panelHolder.setBackground(panel.getBackground());
        panel.setBackground(null);
        panelScroll.addView(panel, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panelHolder.addView(panelScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panelLp.height = maxPanelH;
        shell.addView(panelHolder, panelLp);

        dialog.setContentView(shell);
        if (dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            // 用**不透明主题色**兜底，不要透明：
            // 从 Surface 创建到首帧绘制之间显示的是窗口背景，
            // 透明会让这一帧露出黑色 Surface → 观感就是「弹窗切换闪一下黑」。
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    color(context, com.google.android.material.R.attr.colorSurfaceContainer)));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
            // centerContent=false：面板已在 shell 中居中，这里只管铺模糊背景
            applyBlur(window, context, false);
            // 注意：applyBlur 不修改窗口背景（模糊图由它自己设为 BitmapDrawable），
            // 这里的底色只是 applyBlur 内部渲染前的兜底，无需重复设置。
        }
        dialog.setCancelable(true);

        // ── 过渡动画（统一入口，所有玻璃弹窗共享）──
        // 模糊背景是「截图当前界面」得到的静态图，若面板直接从 0 淡入会有半秒空窗感；
        // 因此面板用「轻微缩放 + 淡入 + 上浮」的 Material 式进入，遮罩层同步淡入。
        final View scrimView = dismissOnOutside ? shell.getChildAt(0) : null;
        // 起始 alpha 不取 0：留 0.35 的可见度，配合提亮后的模糊底，
        // 避免「整屏先暗一下再亮起来」
        panel.setAlpha(0.35f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.setTranslationY(dp(context, 10));
        if (scrimView != null) scrimView.setAlpha(0f);

        dialog.setOnShowListener(d -> {
            android.view.animation.Interpolator ease =
                    new android.view.animation.DecelerateInterpolator(1.6f);
            panel.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(180).setInterpolator(ease).start();
            if (scrimView != null) {
                scrimView.animate().alpha(1f).setDuration(200).start();
            }
        });
        // 把面板挂到 decorView 的 tag 上，供 dismissWithAnim(dialog) 取回
        if (dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().setTag(panel);
        }

        // 弹窗关闭后恢复状态栏颜色（applyBlur 曾把它设为透明）
        dialog.setOnDismissListener(d -> restoreStatusBarColor(dialog.getWindow(), context));
        return dialog;
    }

    /** 弹窗退场动画：先播放过渡再真正 dismiss（用于需要「关得也好看」的场景）。
     *  用法：{@code ModuleUiKit.dismissWithAnim(dialog)} */
    public static void dismissWithAnim(final Dialog dialog) {
        if (dialog == null || !dialog.isShowing()) return;
        View panel = null;
        try {
            if (dialog.getWindow() != null) {
                Object t = dialog.getWindow().getDecorView().getTag();
                if (t instanceof View) panel = (View) t;
            }
        } catch (Throwable ignored) {
        }
        if (panel == null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
            return;
        }
        panel.animate()
                .alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .setDuration(160)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {
                    }
                })
                .start();
    }

    /**
     * 浮窗背景模糊（规范：所有浮窗/对话框统一走本方法）。
     * <p>采用网络通用方案（Blurry/BlurView 同原理，全设备可靠）：
     * <ul>
     *   <li>主方案：截取当前界面 → 高斯模糊（box blur 三趟近似）→ 设为窗口背景。
     *       窗口铺满屏幕、原弹窗内容居中悬浮在模糊背景上，任何 Android 版本均有效，
     *       不依赖系统 {@code FLAG_BLUR_BEHIND}（部分 OEM 设备声明支持但实际无效）。</li>
     *   <li>兜底（截图失败，如 context 非 Activity）：API 31+ 系统窗口模糊；
     *       API 28~30 暗化遮罩 {@code FLAG_DIM_BEHIND}。</li>
     * </ul>
     * 注意：面板自身需半透明（如 {@link #glassDialog} 的 88% 不透明度），模糊效果才可见。
     */
    public static void applyBlur(Window window, Context context) {
        applyBlur(window, context, true);
    }

    /**
     * @param centerContent true = 把内容面板收窄并垂直居中（**既有自定义弹窗的旧行为，保持兼容**）；
     *                      false = 只铺模糊背景，子 View 布局交给调用方
     *                      （{@link #glassDialog} 用，因为面板已在 shell 里居中，若再收窄会连遮罩一起缩掉）
     */
    /** 恢复状态栏颜色为标题栏同色（弹窗 applyBlur 时曾设为透明） */
    private static void restoreStatusBarColor(Window window, Context context) {
        if (window == null || context == null) return;
        try {
            window.setStatusBarColor(color(context,
                    com.google.android.material.R.attr.colorSurfaceContainer));
        } catch (Throwable ignored) {
        }
    }

    public static void applyBlur(Window window, Context context, boolean centerContent) {
        if (window == null) return;
        // 主方案：截图模糊背景（弹窗内容悬浮其上，全版本可靠）
        Drawable bg = captureBlurredBackground(context);
        if (bg != null) {
            // 记录原窗口宽度，保持各弹窗既定宽度不变（如 92%/78% 屏宽）
            int prevWidth = window.getAttributes().width;

            // 不在此设置窗口背景：保留调用方（glassDialog）设定的不透明
            // 主题色作为首帧兜底。模糊图由下方 ImageView 承载。
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
            // ⚠️ 不用 FLAG_LAYOUT_NO_LIMITS：它让窗口超出屏幕边界，
            // 部分 ROM 会用黑色 Surface 补齐超出部分，绘制背景前就露出黑 →
            // 这就是「弹窗切换闪一下黑」的真正来源。
            // 只用 LAYOUT_IN_SCREEN（允许覆盖状态栏区域，但不越界）。
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            // 状态栏透明：模糊图要铺到状态栏下方
            try {
                window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                // 同时把状态栏绘制旗标清掉，避免系统在状态栏区域再画一层
                if (window.getDecorView() != null) {
                    int vis = window.getDecorView().getSystemUiVisibility();
                    vis |= android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                    window.getDecorView().setSystemUiVisibility(vis);
                }
            } catch (Throwable ignored) {
            }

            // 窗口背景直接用模糊图（BitmapDrawable），并让它按 CENTER_CROP 铺满。
            // 之前用 ImageView 承载会有首帧空白（等布局+绘制才有内容）→ 闪黑。
            // 设为窗口背景则第一帧就由系统绘制，不会出现空白帧。
            // 模糊图叠加一层提亮：直接截图模糊的结果比弹窗面板暗，
            // 面板 alpha 从 0 淡入期间整屏只有这层暗图 → 观感「先变黑再变亮」。
            // 用白色半透明叠加把基底提亮，面板淡入时就不会有明暗跳变。
            // 注意：base 已经是 boxBlur 降采样后的 1/4 尺寸图，
            // liftBrightness 在它上面 copy 的成本比全屏小 16 倍。
            // 仍保留 copy 是为了不改动 captureBlurredBackground 的返回对象
            // （它可能被同一帧内多处复用）。
            android.graphics.Bitmap base = blurredOf(bg);
            android.graphics.Bitmap lifted = liftBrightness(base, 0.18f);
            android.graphics.drawable.BitmapDrawable windowBg =
                    new android.graphics.drawable.BitmapDrawable(
                            window.getContext().getResources(),
                            lifted != null ? lifted : base);
            windowBg.setGravity(Gravity.FILL);
            window.setBackgroundDrawable(windowBg);

            if (!centerContent) return;
            View decor = window.getDecorView();
            View content = decor.findViewById(android.R.id.content);
            if (content instanceof FrameLayout) {
                FrameLayout frame = (FrameLayout) content;
                if (frame.getChildCount() > 0) {
                    View child = frame.getChildAt(0);
                    child.setLayoutParams(new FrameLayout.LayoutParams(
                            prevWidth > 0 ? prevWidth : FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER));
                }
            }
            return;
        }
        // 兜底：系统窗口模糊 / 暗化遮罩
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            float radiusPx = context.getResources().getDisplayMetrics().density * 15f;
            window.setBackgroundBlurRadius((int) radiusPx);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.45f);
        }
    }

    /**
     * 提亮位图：叠加一层白色半透明（alpha = ratio）。
     *
     * @param ratio 0~1，0.18 约等于把整体亮度抬高 18%
     * @return 新位图；失败返回 null（调用方回退原图）
     */
    private static android.graphics.Bitmap liftBrightness(
            android.graphics.Bitmap src, float ratio) {
        if (src == null || src.isRecycled()) return null;
        try {
            android.graphics.Bitmap out = src.copy(
                    android.graphics.Bitmap.Config.ARGB_8888, true);
            if (out == null) return null;
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            int a = Math.max(0, Math.min(255, (int) (ratio * 255)));
            c.drawColor(android.graphics.Color.argb(a, 255, 255, 255));
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 BitmapDrawable 取出底层 Bitmap；其他类型返回 null */
    private static android.graphics.Bitmap blurredOf(Drawable d) {
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        }
        return null;
    }

    /**
     * 截取当前界面内容并高斯模糊，作为弹窗背景（主线程调用）。
     * 截图失败（非 Activity 上下文/尺寸无效）返回 null，由调用方走兜底方案。
     */
    private static Drawable captureBlurredBackground(Context context) {
        if (!(context instanceof android.app.Activity)) return null;
        try {
            android.app.Activity activity = (android.app.Activity) context;
            View decor = activity.getWindow().getDecorView();
            if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return null;
            View content = decor.findViewById(android.R.id.content);
            if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) return null;

            // 截整个 decorView（含状态栏区域），弹窗窗口铺满全屏时背景才能覆盖状态栏。
            // 旧实现只截 android.R.id.content（不含状态栏），弹窗窗口又是 MATCH_PARENT，
            // 于是状态栏那一条没有模糊背景 → 表现为「模糊不包括状态栏」。
            int dw = decor.getWidth();
            int dh = decor.getHeight();

            // 直接按 decor 自身尺寸截图，不做任何坐标补偿。
            //
            // 旧实现用 getLocationInWindow 取 decorLoc 并在顶部补 extraTop 像素，
            // 试图「补上状态栏那一截」——但 decor 本身就是整屏（含状态栏）的，
            // getLocationInWindow 相对窗口而非屏幕，返回值随实现漂移，
            // 补出来的 extraTop 恰好是一个状态栏高度，于是状态栏下方多出
            // 一条空白模糊带，再往下才是真实顶栏内容。
            //
            // 正确做法：decor 多大就截多大，BitmapDrawable 铺满弹窗即可。
            Bitmap shot = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(shot);
            decor.draw(canvas);
            // 模糊半径 3：三趟 box blur（水平+垂直）已足够柔和；过高的半径（如 6）
            // 会让背景糊成一团，文字/内容观感发虚（实测反馈"模糊度过高"）。
            Bitmap blurred = boxBlur(shot, 3);
            if (blurred != shot) shot.recycle();
            return new BitmapDrawable(context.getResources(), blurred);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 高斯模糊近似：降采样 1/4 + 三趟 box blur（水平+垂直），弹窗场景性能足够 */
    private static Bitmap boxBlur(Bitmap src, int radius) {
        float scale = 0.25f;
        int w = Math.max(1, (int) (src.getWidth() * scale));
        int h = Math.max(1, (int) (src.getHeight() * scale));
        Bitmap bmp = Bitmap.createScaledBitmap(src, w, h, true);
        int r = Math.max(1, radius);
        for (int pass = 0; pass < 3; pass++) {
            bmp = boxBlurPass(bmp, r, true);
            bmp = boxBlurPass(bmp, r, false);
        }
        return bmp;
    }

    /** 单方向 box blur 一趟（滑动窗口，O(n)）；返回新位图并回收原图 */
    private static Bitmap boxBlurPass(Bitmap src, int radius, boolean horizontal) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] in = new int[w * h];
        src.getPixels(in, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        int span = radius * 2 + 1;
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                int ra = 0, ga = 0, ba = 0;
                int row = y * w;
                for (int i = -radius; i <= radius; i++) {
                    int c = in[row + clamp(i, 0, w - 1)];
                    ra += (c >> 16) & 0xFF;
                    ga += (c >> 8) & 0xFF;
                    ba += c & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    out[row + x] = 0xFF000000 | ((ra / span) << 16) | ((ga / span) << 8) | (ba / span);
                    int add = clamp(x + radius + 1, 0, w - 1);
                    int rem = clamp(x - radius, 0, w - 1);
                    int ca = in[row + add];
                    int cr = in[row + rem];
                    ra += ((ca >> 16) & 0xFF) - ((cr >> 16) & 0xFF);
                    ga += ((ca >> 8) & 0xFF) - ((cr >> 8) & 0xFF);
                    ba += (ca & 0xFF) - (cr & 0xFF);
                }
            }
        } else {
            for (int x = 0; x < w; x++) {
                int ra = 0, ga = 0, ba = 0;
                for (int i = -radius; i <= radius; i++) {
                    int c = in[clamp(i, 0, h - 1) * w + x];
                    ra += (c >> 16) & 0xFF;
                    ga += (c >> 8) & 0xFF;
                    ba += c & 0xFF;
                }
                for (int y = 0; y < h; y++) {
                    out[y * w + x] = 0xFF000000 | ((ra / span) << 16) | ((ga / span) << 8) | (ba / span);
                    int add = clamp(y + radius + 1, 0, h - 1);
                    int rem = clamp(y - radius, 0, h - 1);
                    int ca = in[add * w + x];
                    int cr = in[rem * w + x];
                    ra += ((ca >> 16) & 0xFF) - ((cr >> 16) & 0xFF);
                    ga += ((ca >> 8) & 0xFF) - ((cr >> 8) & 0xFF);
                    ba += (ca & 0xFF) - (cr & 0xFF);
                }
            }
        }
        Bitmap res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        res.setPixels(out, 0, w, 0, 0, w, h);
        src.recycle();
        return res;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ── 卡片行（图标 + 标题 + 描述 + 点击） ──

    /**
     * 构建与主框架侧边栏/对话框一致的"卡片行"列表项：
     * 圆角图标容器 + 主标题 + 描述文字，整行可点击。
     * 模块的列表、菜单、设置项应统一走本方法，保证交互视觉一致。
     */
    public static LinearLayout cardRow(Context context, int iconRes,
                                       String title, String desc,
                                       View.OnClickListener onClick) {
        int surface = color(context, com.google.android.material.R.attr.colorSurfaceContainerLow);
        int onSurface = color(context, com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = color(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int primaryContainer = color(context, com.google.android.material.R.attr.colorPrimaryContainer);
        int onPrimaryContainer = color(context, com.google.android.material.R.attr.colorOnPrimaryContainer);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, 14);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(rippleBg(context, rounded(context, 16, surface, 0)));
        row.setClickable(true);
        row.setFocusable(true);
        if (onClick != null) row.setOnClickListener(onClick);

        // 图标容器
        LinearLayout iconBox = new LinearLayout(context);
        int boxSize = dp(context, 40);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(boxSize, boxSize);
        iconBox.setLayoutParams(boxLp);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setBackground(rounded(context, 12, primaryContainer, 0));
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(onPrimaryContainer);
        icon.setPadding(dp(context, 9), dp(context, 9), dp(context, 9), dp(context, 9));
        iconBox.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(iconBox);

        // 文案列
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(context, 12));
        row.addView(textCol, textLp);

        TextView titleView = new TextView(context);
        titleView.setText(title == null ? "" : title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTextColor(onSurface);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(titleView);

        if (desc != null && !desc.isEmpty()) {
            TextView descView = new TextView(context);
            descView.setText(desc);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            descView.setTextColor(onSurfaceVariant);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descLp.topMargin = dp(context, 2);
            textCol.addView(descView, descLp);
        }
        return row;
    }

    // ── 小节标题 ──

    /** 构建模块设置页/内容页统一的"小节标题" */
    public static TextView sectionHeader(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 6));
        return tv;
    }

    // ── 内部工具 ──

    public static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    /** 圆角填充背景（公开：模块构建行/栏背景时直接调用，保证圆角风格统一） */
    public static GradientDrawable rounded(Context context, int radiusDp, int fillColor, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(context, radiusDp));
        if (fillColor != 0) gd.setColor(fillColor);
        if (strokeColor != 0) gd.setStroke(dp(context, 1), strokeColor);
        return gd;
    }

    /**
     * 圆角背景 + 点击水波纹。
     *
     * <p><b>为什么必须这样组合</b>：{@code setBackground()} 只能有一个背景。
     * 若先设 {@link GradientDrawable} 再调 {@code setBackgroundResource(水波纹)}，
     * 前者会被覆盖。正确做法是把 GradientDrawable 作为 RippleDrawable 的
     * content 一并设置。</p>
     */
    public static android.graphics.drawable.RippleDrawable rippleBg(Context context, GradientDrawable base) {
        int ripple = color(context, com.google.android.material.R.attr.colorOnSurface);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ripple & 0xFFFFFF | 0x1A000000),
                base, null);
    }
}