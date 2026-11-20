package com.aliya.hyfilemgr.filemgr;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import com.aliya.hyfilemgr.R;
import com.aliya.hyfilemgr.access.AccessUtil;
import com.aliya.hyfilemgr.access.FileEntry;
import com.aliya.hyfilemgr.ui.UiKit;
import com.aliya.hyfilemgr.util.Exts;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件管理单列混排适配器（文件夹+文件同列表，对齐 MT 管理器单列模式）。
 *
 * <p>条目类型驱动图标与副文本：真实目录=紫文件夹+时间；真实文件=青文件+大小·时间；
 * 虚拟目录=紫文件夹（名称旁「虚」角标）+副文本；虚拟文件=灰文件；
 * 符号链接=带 →目标的链接标记。多选模式显示左侧勾选框。</p>
 */
public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.VH> {

    /** 缩略图开关（内存/CPU 敏感时可关闭；默认开启） */
    private boolean thumbsEnabled = true;

    /**
     * 是否网格模式。
     *
     * <p>影响行背景的圆角策略：列表模式下整个列表共用外圆角
     * （首行上圆、末行下圆、中间直角），网格模式下每格独立圆角。
     * 由 {@code buildPane()} 在切换视图时同步。</p>
     */
    private boolean gridMode = false;

    public void setGridMode(boolean on) {
        if (this.gridMode == on) return;
        this.gridMode = on;
        notifyDataSetChanged();   // 圆角策略变了，需要重绘所有行
    }

    public boolean isGridMode() {
        return gridMode;
    }

    /** 设置缩略图开关并刷新列表 */
    public void setThumbsEnabled(boolean on) {
        this.thumbsEnabled = on;
        notifyDataSetChanged();
    }

    public boolean isThumbsEnabled() {
        return thumbsEnabled;
    }

    // ── 格式支持：扩展名 → 图标分类（与 FileManagerModule 分类白名单同源扩充） ──
    // 扩展名集合已统一到 util/Exts（原先这里 5 套、FileManagerModule 4 套、
    // MediaPlayerActivity 1 套，内容逐字重复，新增格式必漏一处）。

    /** 行交互回调 */
    public interface Listener {
        void onClick(FileEntry e);
        void onLongClick(FileEntry e, View anchor);
        /** 多选状态变化（勾选/取消） */
        void onSelectionChanged();
    }

    private final Context context;
    private List<FileEntry> entries;
    private Set<FileEntry> selected;
    private boolean multiMode;
    /** 列表密度：1=紧凑 2=标准 3=宽松（影响行内边距与图标尺寸） */
    private int density = 2;

    /** 文件行卡片不透明度 0-100，与设置页「卡片不透明度」共用同一个值 */
    private int rowAlpha = 100;

    /** 设置文件行卡片不透明度（0-100），调用后需 notifyDataSetChanged 重绘 */
    public void setRowAlpha(int alpha) {
        int a = Math.max(0, Math.min(100, alpha));
        if (a == rowAlpha) return;
        rowAlpha = a;
        notifyDataSetChanged();
    }
    private final Listener listener;

    // ── 包名文件夹应用图标叠加 ──

    /** 是否在 Android/data、obb、media 的包名子目录上叠应用图标 */
    private boolean appBadgeEnabled = true;

    /**
     * 包名 → 应用图标 Drawable 的缓存。
     *
     * <p><b>为什么必须缓存</b>：{@code onBindViewHolder} 每帧都可能被调用
     * （滚动时整屏重绑）。包名解析要走 PackageManager IPC，几十行就是几十次
     * 跨进程调用，滚动直接掉帧。这里按包名表缓存，滚动时零开销。</p>
     *
     * <p>value 允许为 null（表示「查过但没有」），避免对未安装应用反复查询。</p>
     */
    private final java.util.Map<String, android.graphics.drawable.Drawable> appIconCache =
            new java.util.HashMap<>();

    /**
     * 应用图标缓存上限（按条目数）。
     *
     * <p><b>为什么必须有上限</b>：{@code Android/data} 下可能有几千个包名目录，
     * 不管有没有图标都会进 {@code appIconQueried}。旧实现两个集合都无界：
     * 3000 个包名 × 30~100KB 图标 = <b>90~300MB</b> 常驻。
     * 用户不会只待在一个目录，但会反复往返，故缓存必须存在（避免滚动时
     * 走 PackageManager IPC），只是要控量。64 条足够覆盖屏幕内的包名目录。</p>
     */
    private static final int APP_ICON_CACHE_MAX = 64;

    /** 记住哪些包名查过（含查出 null 的），避免重复查询；同样有上限 */
    private final java.util.Set<String> appIconQueried = new java.util.HashSet<>();

    /** 两集合超限时各淘汰最旧的一半（插入顺序：HashSet/HashMap 无顺序，故用队列记录） */
    private final java.util.ArrayDeque<String> appIconOrder = new java.util.ArrayDeque<>();

    private void trimAppIconCache() {
        while (appIconOrder.size() > APP_ICON_CACHE_MAX) {
            String old = appIconOrder.pollFirst();
            if (old == null) break;
            appIconCache.remove(old);
            appIconQueried.remove(old);
        }
    }

    /** 这些父目录下的直接子目录才当作包名文件夹 */
    private static final String[] PANGO_PARENT_SUFFIXES = {
            "/Android/data", "/Android/obb", "/Android/media",
    };

    public void setAppBadgeEnabled(boolean on) {
        if (this.appBadgeEnabled == on) return;
        this.appBadgeEnabled = on;
        notifyDataSetChanged();
    }

    public boolean isAppBadgeEnabled() {
        return appBadgeEnabled;
    }

    /**
     * 该目录是否「本应叠加应用图标」——路径形如
     * {@code /storage/emulated/0/Android/data/com.example.app}，
     * 且末段像包名（至少两个点，全小写字母数字下划线点）。
     */
    private static boolean isPackageDir(FileEntry e) {
        if (e == null || !e.isDir() || e.path == null) return false;
        String p = e.path;
        boolean underPkgParent = false;
        for (String suffix : PANGO_PARENT_SUFFIXES) {
            if (p.endsWith(suffix + "/" + e.name)) {
                underPkgParent = true;
                break;
            }
        }
        if (!underPkgParent) return false;
        return looksLikePackageName(e.name);
    }

    /** 粗判包名：含至少一个点、无空格、只含包名字符集 */
    private static boolean looksLikePackageName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        int dots = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                dots++;
            } else if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return dots >= 1;
    }

    /**
     * 取该包名对应的应用图标；未安装 / 不可见时返回 null。
     *
     * <p><b>包可见性</b>：Android 11+ 默认只能看到声明过的包。
     * 本应用 manifest 里已有 {@code <queries>} 的 VIEW intent 声明，
     * 能看到绝大多数应用，但**不是全部**。查不到就返回 null，
     * 调用方据此不叠图标，不报错、不重试。</p>
     */
    private android.graphics.drawable.Drawable appIconFor(String pkg) {
        if (pkg == null) return null;
        if (appIconQueried.contains(pkg)) return appIconCache.get(pkg);
        appIconQueried.add(pkg);
        appIconOrder.addLast(pkg);
        android.graphics.drawable.Drawable d = null;
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(
                    pkg, 0);
            if (ai != null) d = pm.getApplicationIcon(ai);
        } catch (Throwable ignored) {
            // 未安装 / 不可见：静默
        }
        appIconCache.put(pkg, d);
        trimAppIconCache();
        return d;
    }

    public FileListAdapter(Context context, List<FileEntry> entries,
                           Set<FileEntry> selected, Listener listener) {
        this.context = context;
        this.entries = entries;
        this.selected = selected;
        this.listener = listener;
    }

    /** 刷新数据源（主线程） */
    public void setData(List<FileEntry> entries) {
        this.entries = entries;
        notifyDataSetChanged();
    }

    /**
     * 设置列表密度。
     *
     * @param density 1=紧凑 2=标准 3=宽松
     */
    public void setDensity(int density) {
        this.density = Math.max(1, Math.min(3, density));
        notifyDataSetChanged();
    }

    public void setMultiMode(boolean multiMode) {
        this.multiMode = multiMode;
        notifyDataSetChanged();
    }

    /**
     * 仅更新选择集合的引用，<b>不触发任何刷新</b>。
     *
     * <p>给「拖动框选」这种高频路径用：调用方自己知道哪些行变了，
     * 会配合 {@code notifyItemRangeChanged(from, count, PAYLOAD_SELECTION)} 精确通知。</p>
     *
     * <p>对比 {@link #setSelected}：那个是 {@code notifyDataSetChanged()} 全表重绑，
     * 适合「全选/取消全选」这种确实全变了的场景，不适合拖动。</p>
     */
    public void applySelection(Set<FileEntry> selected) {
        this.selected = selected;
    }

    /**
     * 局部刷新的 payload：只重算选中态（背景/缩放/勾选框）。
     *
     * <p>命中此 payload 时 {@link #onBindViewHolder(VH, int, List)} 跳过大头的
     * 名称/副文本/图标/缩略图绑定，只重算与选中相关的几个属性。</p>
     */
    public static final Object PAYLOAD_SELECTION = new Object();

    public void setSelected(Set<FileEntry> selected) {
        this.selected = selected;
        notifyDataSetChanged();
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        return new VH(buildRow());
    }

    /**
     * 带 payload 的绑定：拖动框选时只重算选中态，不重做名称/图标/缩略图。
     *
     * <p>这是「拖动不卡」的关键——旧实现每个 MOVE 都全表 {@code notifyDataSetChanged}，
     * 每行都要重建 GradientDrawable、重算 formatDate、重新请求缩略图。</p>
     */
    @Override
    public void onBindViewHolder(VH h, int position, List<Object> payloads) {
        if (payloads != null && !payloads.isEmpty()) {
            boolean onlySelection = true;
            for (Object o : payloads) {
                if (o != PAYLOAD_SELECTION) { onlySelection = false; break; }
            }
            if (onlySelection) {
                bindSelection(h, position);
                return;
            }
        }
        onBindViewHolder(h, position);
    }

    /** 只重算选中相关的视觉（背景 / 缩放 / 勾选框），不动名称与图标 */
    private void bindSelection(VH h, int position) {
        FileEntry e = entries.get(position);
        boolean checked = selected != null && selected.contains(e);
        int surface = checked
                ? UiKit.color(context, com.google.android.material.R.attr.colorPrimaryContainer)
                : UiKit.color(context, com.google.android.material.R.attr.colorSurfaceContainerLow);
        h.itemView.setBackground(makeBg(surface, checked, position));
        h.itemView.setScaleX(checked ? 0.985f : 1f);
        h.itemView.setScaleY(checked ? 0.985f : 1f);
        if (h.check != null) h.check.setChecked(checked);
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        FileEntry e = entries.get(position);
        h.name.setText(e.name);
        h.sub.setText(buildSub(e));
        boolean checked = selected != null && selected.contains(e);
        int surface = checked
                ? UiKit.color(context, com.google.android.material.R.attr.colorPrimaryContainer)
                : UiKit.color(context, com.google.android.material.R.attr.colorSurfaceContainerLow);
        h.itemView.setBackground(makeBg(surface, checked, position));
        // 选中态轻微内缩 + 放大反馈，配合蓝色边框形成「被拿起」的观感
        h.itemView.setScaleX(checked ? 0.985f : 1f);
        h.itemView.setScaleY(checked ? 0.985f : 1f);
        // 缩略图优先（图片/视频首帧/APK 图标）；无缩略图或开关关闭 → 回退类型图标
        //
        // ⚠ zip 内部条目（zip://…）在模型上标记为 virtual，但它们是**真实存在的
        // 包内文件**，缩略图能解（ThumbLoader 会按需解出到缓存）。
        // 旧逻辑一律 !e.virtual 排除，导致压缩包内所有图片/视频都只显示通用图标。
        boolean isZipEntry = e.path != null && e.path.startsWith("zip://");
        boolean useThumb = thumbsEnabled && !e.parent && (!e.virtual || isZipEntry)
                && !e.isDir() && ThumbLoader.supports(e.name);
        if (useThumb) {
            // 先显示类型图标兜底，解码成功后覆盖；
            // 解码失败保持类型图标（ThumbLoader 内部会先清空，故这里要重设）
            h.icon.setColorFilter(null);
            h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            h.icon.setImageResource(iconRes(e));
            ThumbLoader.load(context, h.icon, e.path, e.name, e.lastModified,
                    iv -> {
                        // 回退：重新贴上类型图标
                        iv.setColorFilter(null);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        iv.setImageResource(iconRes(e));
                    });
        } else {
            h.icon.setColorFilter(null);
            h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            h.icon.setImageResource(iconRes(e));
        }
        if (!useThumb) {
            int iconColor;
            switch (e.type) {
                case VIRTUAL_DIR:
                case VIRTUAL_FILE:
                    iconColor = UiKit.color(context, com.google.android.material.R.attr.colorOutline);
                    break;
                case LINK:
                    iconColor = UiKit.color(context, com.google.android.material.R.attr.colorTertiary);
                    break;
                case DIR:
                    iconColor = UiKit.color(context, com.google.android.material.R.attr.colorPrimary);
                    break;
                default:
                    // 多彩扁平化（Material 流行趋势）：文件图标按类型主题色
                    iconColor = fileColor(e);
                    break;
            }
            h.icon.setColorFilter(iconColor);
        }
        // ── 包名文件夹右下角应用图标角标 ──
        // 只对 Android/data、obb、media 下的包名子目录生效，其余行一律 GONE。
        // 注意：仅视觉标记，不改变点击行为（点击仍是进入文件夹）。
        if (h.appBadge != null) {
            if (appBadgeEnabled && isPackageDir(e)) {
                android.graphics.drawable.Drawable d = appIconFor(e.name);
                if (d != null) {
                    h.appBadge.setImageDrawable(d);
                    h.appBadge.setVisibility(View.VISIBLE);
                } else {
                    // 未安装 / 不可见：不叠图标，保持纯文件夹观感
                    h.appBadge.setImageDrawable(null);
                    h.appBadge.setVisibility(View.GONE);
                }
            } else {
                h.appBadge.setImageDrawable(null);
                h.appBadge.setVisibility(View.GONE);
            }
        }

        // 虚拟条目标记
        h.badge.setVisibility(e.virtual ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> {
            if (multiMode) {
                toggle(e, h.check);
            } else if (listener != null) {
                listener.onClick(e);
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            // ".."强制条目不参与长按（多选模式也不勾选）
            if (e.parent) return true;
            if (listener != null) {
                if (!multiMode) listener.onLongClick(e, v);
                else toggle(e, h.check);
            }
            return true;
        });
        h.check.setOnClickListener(v -> toggle(e, h.check));
    }

    /** 图标按类型区分：文件夹=ic_folder；文件按扩展名归 6 类（图片/视频/音频/文档/APK/压缩包） */
    private int iconRes(FileEntry e) {
        if (e.type == FileEntry.Type.LINK) return R.drawable.ic_folder;
        if (e.isDir()) return R.drawable.ic_folder;
        String ext = extOf(e.name);
        if (Exts.isImage(ext)) return R.drawable.ic_img;
        if (Exts.isVideo(ext)) return R.drawable.ic_video;
        if (Exts.isAudio(ext)) return R.drawable.ic_audio;
        if (Exts.isDoc(ext)) return R.drawable.ic_doc;
        if (ext.equals("apk")) return R.drawable.ic_apk;
        if (Exts.isArchive(ext)) return R.drawable.ic_archive;
        return R.drawable.ic_file;
    }

    /** 取小写扩展名（无点）；无扩展名返回空串。实现统一在 util/Exts。 */
    private static String extOf(String name) {
        return Exts.extOf(name);
    }

    /** 多彩扁平化文件图标配色（对齐 Material 流行趋势）：
     *  图片=绿 / 视频=紫 / 音频=橙 / 文档=蓝 / APK=青 / 压缩包=琥珀；未知=主题次要色 */
    private int fileColor(FileEntry e) {
        String ext = extOf(e.name);
        if (Exts.isImage(ext)) return 0xFF66BB6A;
        if (Exts.isVideo(ext)) return 0xFFAB47BC;
        if (Exts.isAudio(ext)) return 0xFFFFA726;
        if (Exts.isDoc(ext)) return 0xFF42A5F5;
        if (ext.equals("apk")) return 0xFF26A69A;
        if (Exts.isArchive(ext)) return 0xFFFFCA28;
        return UiKit.color(context, com.google.android.material.R.attr.colorSecondary);
    }

    /** 副文本：目录=时间（虚拟=标记），文件=大小·时间，链接=→目标，".."=返回上级 */
    private String buildSub(FileEntry e) {
        if (e.parent) return "返回上级";
        if (e.type == FileEntry.Type.LINK) {
            String base = e.subText == null ? "链接" : e.subText;
            if (e.lastModified > 0) return base + " · " + AccessUtil.formatDate(e.lastModified);
            return base;
        }
        if (e.virtual) {
            String base = e.subText == null ? "虚拟条目" : e.subText;
            if (e.lastModified > 0) return base + " · " + AccessUtil.formatDate(e.lastModified);
            return base;
        }
        if (e.isDir()) {
            return AccessUtil.formatDate(e.lastModified);
        }
        return AccessUtil.formatSize(e.length) + " · " + AccessUtil.formatDate(e.lastModified);
    }

    @Override
    public int getItemCount() {
        return entries == null ? 0 : entries.size();
    }

    private void toggle(FileEntry e, CheckBox check) {
        if (selected == null) return;
        // ".."强制条目不参与多选
        if (e.parent) return;
        if (selected.contains(e)) selected.remove(e);
        else selected.add(e);
        check.setChecked(selected.contains(e));
        if (listener != null) listener.onSelectionChanged();
        notifyItemRangeChanged(0, getItemCount());
    }

    /** 构建行视图：勾选框 + 图标 + 名称/角标/副文本 */
    private View buildRow() {
        // 密度 → 行内边距 / 图标尺寸
        final int pad;
        final int iconSize;
        switch (density) {
            case 1:  pad = dp(6);  iconSize = dp(18); break;   // 紧凑
            case 3:  pad = dp(14); iconSize = dp(26); break;   // 宽松
            default: pad = dp(10); iconSize = dp(22); break;   // 标准
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 多选不再显示勾选框：选中态完全由「蓝底 + 蓝边框」表达。
        // 仍保留一个不可见占位以满足 ViewHolder 字段，宽度 0 不占空间。
        CheckBox check = new CheckBox(context);
        check.setVisibility(View.GONE);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(0, 0);
        row.addView(check, checkLp);

        // 图标区：FrameLayout 容纳「类型图标 + 右下角应用图标角标」。
        // 只有包名文件夹（Android/data 等）会显示角标，其余行把角标设为 GONE。
        android.widget.FrameLayout iconWrap = new android.widget.FrameLayout(context);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginStart(dp(4));
        row.addView(iconWrap, iconLp);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_folder);
        iconWrap.addView(icon, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右下角应用图标角标：尺寸取图标区的 55%，压在右下角
        ImageView appBadge = new ImageView(context);
        appBadge.setVisibility(View.GONE);
        appBadge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int badgeSize = Math.max(dp(10), Math.round(iconSize * 0.55f));
        android.widget.FrameLayout.LayoutParams appBadgeLp = new android.widget.FrameLayout.LayoutParams(
                badgeSize, badgeSize, Gravity.END | Gravity.BOTTOM);
        iconWrap.addView(appBadge, appBadgeLp);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(10));
        row.addView(textCol, textLp);

        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        textCol.addView(nameRow);

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(UiKit.color(context, com.google.android.material.R.attr.colorOnSurface));
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        nameRow.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(context);
        badge.setText("虚");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTextColor(UiKit.color(context, com.google.android.material.R.attr.colorOnSecondaryContainer));
        badge.setBackground(UiKit.rounded(context, dp(6),
                UiKit.color(context, com.google.android.material.R.attr.colorSecondaryContainer), 0));
        int bp = dp(4);
        badge.setPadding(bp, dp(1), bp, dp(1));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginStart(dp(6));
        nameRow.addView(badge, badgeLp);

        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(UiKit.color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        textCol.addView(sub, subLp);

        row.setTag(new Object[]{check, icon, name, sub, badge, appBadge});
        return row;
    }

    /**
     * 行背景。
     *
     * <p>选中态 = 主色淡填充 + 2dp 主色边框（替代原有的勾选框，
     * 用「整行被蓝色框住」表达选中）。未选中态只有圆角填充、无边框。</p>
     */
    /**
     * 行背景。
     *
     * <p><b>圆角规则改为「整组只在外围收圆角」</b>：此前每一行都
     * {@code setCornerRadius(dp(14))}，视觉上是一串独立小卡片。
     * 用户反馈希望「只有四周用圆角，中间拼接的文件不用每个都圆角」——
     * 也就是整个文件列表像一张大卡片，行与行之间是连续的分隔。</p>
     *
     * <p>做法（列表模式下）：</p>
     * <ul>
     *   <li>第一行：上圆角</li>
     *   <li>最后一行：下圆角</li>
     *   <li>中间行：直角</li>
     *   <li>只有一个条目时：四角全圆</li>
     * </ul>
     *
     * <p>网格模式不改：网格中每个格子本就是独立单元，共用圆角会连成一片，
     * 反而看不出边界。</p>
     *
     * @param position 条目位置，用于判断首尾
     */
    private GradientDrawable makeBg(int fill, boolean checked, int position) {
        GradientDrawable gd = new GradientDrawable();
        float r = dp(14);
        if (gridMode) {
            // 网格：保持每格独立圆角
            gd.setCornerRadius(r);
        } else {
            int count = entries.size();
            boolean isFirst = position <= 0;
            boolean isLast = position >= count - 1;
            boolean single = count <= 1;
            // 四个角分别控制：左上、右上、右下、左下
            gd.setCornerRadii(new float[]{
                    (isFirst || single) ? r : 0f, (isFirst || single) ? r : 0f,   // 左上
                    (isFirst || single) ? r : 0f, (isFirst || single) ? r : 0f,   // 右上
                    (isLast || single) ? r : 0f, (isLast || single) ? r : 0f,     // 右下
                    (isLast || single) ? r : 0f, (isLast || single) ? r : 0f      // 左下
            });
        }
        // 按 rowAlpha 压不透明度：fill 原本是实心色（alpha=FF），
        // 直接用它会把背景图完全挡住，文件行就成了一块块实心板。
        int a = Math.round(255f * rowAlpha / 100f);
        gd.setColor((fill & 0x00FFFFFF) | (a << 24));
        if (checked) {
            gd.setStroke(dp(2), UiKit.color(context,
                    com.google.android.material.R.attr.colorPrimary));
        }
        return gd;
    }

    private int dp(int v) {
        return Math.round(context.getResources().getDisplayMetrics().density * v);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name, sub, badge;
        final ImageView icon;
        /** 右下角应用图标角标（仅包名文件夹显示） */
        final ImageView appBadge;
        final CheckBox check;

        VH(View v) {
            super(v);
            Object[] tags = (Object[]) v.getTag();
            check = (CheckBox) tags[0];
            icon = (ImageView) tags[1];
            name = (TextView) tags[2];
            sub = (TextView) tags[3];
            badge = (TextView) tags[4];
            appBadge = (ImageView) tags[5];
        }
    }
}