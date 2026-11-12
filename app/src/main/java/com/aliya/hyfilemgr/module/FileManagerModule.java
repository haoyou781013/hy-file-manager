package com.aliya.hyfilemgr.module;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.widget.SeekBar;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aliya.hyfilemgr.R;
import com.aliya.hyfilemgr.access.AccessResult;
import com.aliya.hyfilemgr.access.AccessRouter;
import com.aliya.hyfilemgr.access.AccessUtil;
import com.aliya.hyfilemgr.access.FileEntry;
import com.aliya.hyfilemgr.access.SafStrategy;
import com.aliya.hyfilemgr.filemgr.FileListAdapter;
import com.aliya.hyfilemgr.filemgr.FileOps;
import com.aliya.hyfilemgr.module.HyVqModule;
import com.aliya.hyfilemgr.module.ModuleServices;
import com.aliya.hyfilemgr.ui.UiKit;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件管理模块（内置）——HY_VQ 版真双独立窗口文件管理器（对齐 MT 管理器实测模型）。
 *
 * <p><b>双窗口模型（MT 实测 2026-08-16）</b>：左右两个完全独立的列表窗格，每个窗格
 * 拥有独立路径/条目/多选集合/滚动位置；顶部单一路径栏 + 状态栏 = 跟随「最近交互的
 * 活跃窗口」（滑动或点击都会切换焦点）。数据源走 {@link AccessRouter} 四源路由
 * （真实 listFiles → PackageManager 虚拟枚举 → StorageManager 卷枚举 → 静默空），
 * 未授予 MANAGE_EXTERNAL_STORAGE 也能以虚拟视图浏览全盘。</p>
 *
 * <p><b>未来扩展点（用户规划）</b>：底部小栏 + 右下角左右方向键切换布局
 * （单列/双列/三列）。当前窗格抽象 {@link Pane} 已支持任意列数，接入底部栏时
 * 只需动态增删 Pane 并维护 {@link #panes} 列表。</p>
 *
 * <p>顶部路径栏（返回/根目录/路径/新建/刷新/排序），长按基础菜单，多选批量操作，
 * 剪贴板跨窗格复制/剪切/粘贴（虚拟条目不入板）。</p>
 */
public class FileManagerModule extends HyVqModule {

    // ── 排序模式 ──
    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_TIME = 2;
    private static final String[] SORT_LABELS = {"按名称", "按大小", "按时间"};

    // ── 临时开关：侧边栏暂时隐藏 SAF 授权目录（用户要求 2026-08-16；改回 true 即恢复显示）。
    // 仅影响侧边栏列表显示，授权数据（persisted uri）不受影响，底部「＋授权目录」按钮保留。 ──
    private static final boolean SHOW_SAF_MOUNTS = false;

    // ── 全量功能基础设施（2026-08-16 用户规划落地） ──
    /** 隐藏文件（.开头）显示开关：⋮菜单实时切换，reload 时过滤 */
    private static boolean showHidden = false;
    /** 网格视图开关（默认列表） */
    private boolean gridMode = false;
    /** 最近打开文件（路径，最新在前，最多 20 条） */
    private final java.util.List<String> recentFiles = new java.util.ArrayList<>();
    /** 回收站目录：保留原路径层级（.HyVqTrash/storage/emulated/0/DCIM/a.jpg），
     *  恢复 = 移回 TRASH_DIR 相对路径对应的原绝对路径；隐藏目录天然被分类扫描跳过 */
    private static final String TRASH_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/.HyVqTrash";
    /** 分类视图虚拟路径前缀（分类功能已移除，仅作历史路径识别兜底，不再产生新路径） */
    private static final String PREFIX_CAT = "cat://";
    /** 回收站虚拟路径：Pane.path = "trash://"（reload 特殊路由） */
    private static final String PREFIX_TRASH = "trash://";
    /** ZIP 内部浏览虚拟路径：zip://<zip绝对路径>/<包内相对路径>。
     *  只读 ZIP 中央目录，不解压任何数据到磁盘。 */
    private static final String PREFIX_ZIP = "zip://";
    /** 分类定义：id / 标题 / 扩展名白名单（空格分隔，小写）——2026-08-16 全面扩充格式支持 */
    private static final String[][] CATEGORIES = {
            {"images", "图片", ".jpg .jpeg .png .gif .webp .bmp .heic .heif .svg .ico .tif .tiff .raw .cr2 .nef .arw .dng .psd .ai .avif .jfif .jxl .wdp .jxr"},
            {"video", "视频", ".mp4 .mkv .avi .mov .wmv .flv .3gp .webm .ts .m4v .mpg .mpeg .rm .rmvb .vob .f4v .m2ts .mts .ogv .mxf .asf .divx .dv .wtv .yuv .amv .nsv .roq"},
            {"audio", "音频", ".mp3 .flac .wav .m4a .aac .ogg .opus .wma .amr .aiff .ape .wv .mid .midi .ac3 .dts .mka .ra .au .caf .mp2 .mpa .spx .tak .tta .dsf .dff .voc .8svx"},
            {"docs", "文档", ".pdf .doc .docx .xls .xlsx .ppt .pptx .txt .md .epub .csv .rtf .odt .ods .odp .pages .numbers .key .html .htm .xml .json .log .chm .mobi .azw3 .tex .java .kt .c .cpp .h .hpp .cs .py .js .ts .css .scss .less .php .rb .go .rs .swift .m .properties .ini .cfg .conf .yaml .yml .gradle .sh .bat .ps1 .sql .ipynb .lua .pl .r .dart .groovy .vue .jsx .tsx .svgz .fb2 .djvu .cbr .cbz .mht .wps .et .dps .pot .pps .xlsm .xlsb .docm .dotx .dotm .xltx .xltm .pptxm"},
            {"apk", "APK安装包", ".apk .aab .xapk .apks .zipx"},
            {"archives", "压缩包", ".zip .rar .7z .tar .gz .bz2 .xz .tgz .iso .jar .zst .lz4 .z .lzh .cab .ace .arj .uue .bz .tbz .tbz2 .txz .war .ear .deb .rpm .pkg .dmg .apk"},
    };
    /** 轻量任务队列：耗时操作（粘贴/压缩/解压/搜索/分类扫描/回收站）统一排队，
     *  避免多线程并发写同一目录；⋮菜单可查排队数 */
    private static final java.util.concurrent.ExecutorService TASK_QUEUE =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 偏好落盘的专用线程：commit() 是同步 fsync，不能放 UI 线程 */
    private static final android.os.HandlerThread PREF_THREAD =
            new android.os.HandlerThread("hy-prefs");
    private static final android.os.Handler PREF_HANDLER;
    static {
        PREF_THREAD.start();
        PREF_HANDLER = new android.os.Handler(PREF_THREAD.getLooper());
    }

    private static final java.util.concurrent.atomic.AtomicInteger queuedTasks =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 分类扫描结果缓存（key=分类id，避免重复全盘扫描） */
    private static String catCacheKey = null;
    private static List<FileEntry> catCache = null;

    // ── 剪贴板（模块级静态：切页/重建视图不丢；存路径字符串，虚拟条目不入板） ──
    private static final List<String> clipboard = new ArrayList<>();
    private static boolean clipCut = false;

    // ── 内置文本编辑器：多标签 + 打开保留（2026-08-16 参考 ZeroTermux EditTextActivity）
    //  布局参考：顶部标签栏(HorizontalScrollView) + 编辑区 + 状态行 + 底部符号栏
    //  打开保留参考：标签列表持久化到 SharedPreferences，下次打开编辑器自动恢复 ──
    /** 编辑器标签页状态：文件/内容/已存内容/脏标记/编码/行尾符/撤销重做栈（对齐 ZeroTermux EditorTab）
     *  2026-08-17 升级：encoding 可变（编码选择保存）、lineEnding（LF/CRLF）、undo/redo 快照栈 */
    private static final class EditorTabState {
        final File file;
        /** ⭐ v2.8.0 Root 编辑：非空表示这是 root 文件的缓存副本，保存后需写回该真实路径 */
        final String rootTarget;
        String content;
        String savedContent;
        boolean dirty;
        String encoding;
        String lineEnding; // "LF" | "CRLF"
        final java.util.ArrayList<String> undoStack = new java.util.ArrayList<>();
        final java.util.ArrayList<String> redoStack = new java.util.ArrayList<>();

        EditorTabState(File file, String content, String encoding) {
            this(file, content, encoding, null);
        }

        EditorTabState(File file, String content, String encoding, String rootTarget) {
            this.file = file;
            this.rootTarget = rootTarget;
            this.content = content;
            this.savedContent = content;
            this.dirty = false;
            this.encoding = encoding;
            this.lineEnding = content.contains("\r\n") ? "CRLF" : "LF";
        }
    }

    /** 编辑器标签列表（打开保留：关闭编辑器时持久化路径，下次打开恢复） */
    private final List<EditorTabState> editorTabs = new ArrayList<>();
    /** 当前激活标签 */
    private EditorTabState currentEditTab = null;
    /** 编辑器对话框与控件（ensureEditorDialog 构建，会话内复用） */
    private android.app.Dialog editorDialog = null;
    private com.aliya.hyfilemgr.widget.NumberedEditText editorEdit = null;


    /** 编辑器：回车自动缩进（可在「编辑器设置」中关闭） */
    private boolean editorAutoIndent = true;
    /** 编辑器：Tab / 一级缩进宽度（空格数） */
    private int editorTabWidth = 4;
    private TextView editorStat = null;
    private TextView editorHeader = null;
    private LinearLayout editorTabBar = null;
    private android.text.TextWatcher editorWatcher = null;
    /** 预览对话框（HTML/Markdown WebView，独立于编辑会话） */
    private android.app.Dialog webDialog = null;
    /** 预览用 WebView（关闭时必须 destroy，否则每次预览叠加一个实例） */
    private android.webkit.WebView previewWebView = null;
    /** 编辑器侧边栏（文件树抽屉：☰ 弹出；浏览目录/打开文件/原功能菜单） */
    private android.app.Dialog editorDrawer = null;
    private File editorDrawerDir = null;
    private LinearLayout editorDrawerList = null;
    private TextView editorDrawerPath = null;
    /** 标签路径列表持久化 key（打开保留） */
    private static final String EDITOR_PREF_TABS = "editor_tabs";
    /** 编辑器草稿的元数据（内容本身存在 files/editor_drafts/ 下） */
    private static final String EDITOR_PREF_DRAFTS = "editor_drafts_meta";
    /**
     * 内置编辑器能打开的单文件大小上限。
     *
     * <p><b>必须只有这一处定义</b>。此前存在三套互相矛盾的判据：
     *   openTextEditorInternal  —— >2MB 硬拒
     *   tryOpenInEditor         —— >12MB 才提示「可能无法加载」
     *   openInEditorByName      —— 完全不判
     * 结果是从「打开方式」菜单点进来会先看到「可能无法加载」，
     * 真进去又被硬拒；换一个入口则可能直接 OOM。
     *
     * <p>取 2MB 的理由：编辑器是全文读进内存 + 每次撤销存整份快照，
     * 再大就不可靠。要读大文件请用外部编辑器。</p>
     */
    private static final long MAX_EDIT_BYTES = 2L * 1024 * 1024;
    /** 编辑器设置持久化 key：字号 / 等宽 / Tab宽度 / 主题（0跟随系统 1浅色 2深色） */
    private static final String EDITOR_PREF_FONT_SIZE = "editor_font_size";
    private static final String EDITOR_PREF_MONO = "editor_mono";
    private static final String EDITOR_PREF_TAB_W = "editor_tab_w";
    private static final String EDITOR_PREF_THEME = "editor_theme";
    // ── 浏览状态持久化键（2026-09-06 补齐：排序/隐藏文件/双窗格路径原为纯内存，重启即回默认） ──
    /** 启动时自动检查更新（默认开启） */
    private static final String PREF_AUTO_CHECK_UPDATE = "auto_check_update";
    /** 自动提示过的版本号：同一版本只弹一次，避免每次启动都打扰 */
    private static final String PREF_UPDATE_LAST_ASKED = "update_last_asked";
    private static final String PREF_SORT_MODE = "sort_mode";
    private static final String PREF_SHOW_HIDDEN = "show_hidden";
    private static final String PREF_DPI = "ui_dpi";
    private static final String PREF_DENSITY = "list_density";
    /** 界面主题：0=默认，1=工业风格 */
    private static final String PREF_UI_THEME = "ui_theme";
    // ── 背景图 ──
    /** 背景图在私有目录里的文件名；空 = 无背景图 */
    private static final String PREF_BG_FILE = "bg_file";
    /** 背景图不透明度 0-100（100 = 完全不透明，0 = 完全隐形） */
    private static final String PREF_BG_ALPHA = "bg_alpha";
    /** 背景图模糊度 0-25（0 = 不模糊） */
    private static final String PREF_BG_BLUR = "bg_blur";
    // ── 文件管理主题（独立于全局主题）──
    /** 文件列表页是否启用独立主题 */
    private static final String PREF_FM_THEME_ON = "fm_theme_on";
    /** 文件列表页专用背景图文件名 */
    private static final String PREF_FM_BG_FILE = "fm_bg_file";
    /** 文件列表页背景不透明度 0-100 */
    private static final String PREF_FM_BG_ALPHA = "fm_bg_alpha";
    /** 文件列表页背景模糊度 0-25 */
    private static final String PREF_FM_BG_BLUR = "fm_bg_blur";
    /** 文件列表行卡片不透明度 0-100 */
    private static final String PREF_FM_ROW_ALPHA = "fm_row_alpha";
    /** 侧边栏面板不透明度 0-100 */
    private static final String PREF_NAV_PANEL_ALPHA = "nav_panel_alpha";
    /** 列表密度 0标准 1紧凑 3宽松（沿用 listDensity 语义） */
    private static final String PREF_FM_DENSITY = "fm_density";
    /** 卡片不透明度 0-100（透出背景的程度） */
    private static final String PREF_CARD_ALPHA = "card_alpha";
    /** 状态栏底色的不透明度：0 = 完全透明（露出背景图），100 = 实心主题色 */
    private static final String PREF_STATUS_BAR_ALPHA = "status_bar_alpha";
    /** 明暗模式：0=跟随系统，1=亮色，2=暗色（工业风格下隐藏） */
    private static final String PREF_UI_MODE = "ui_mode";
    /** 侧边栏「本地」分组是否展开 */
    private static final String PREF_NAV_LOCAL_OPEN = "nav_local_open";
    /** 侧边栏「工具」分组是否展开 */
    private static final String PREF_NAV_TOOLS_OPEN = "nav_tools_open";
    /** 回收站启用开关 */
    private static final String PREF_TRASH_ENABLED = "trash_enabled";
    /** 是否默认移入回收站（关闭则直接物理删除） */
    private static final String PREF_TRASH_DEFAULT = "trash_default";
    /** 回收站自动清理天数（0 = 不自动清理） */
    private static final String PREF_TRASH_KEEP_DAYS = "trash_keep_days";

    /** 界面主题：0=默认，1=工业风格 */
    private static int uiTheme = 0;

    // ── 背景图状态（全局背景）──
    /** 背景层 ImageView（铺在 rootContainer 最底） */
    private android.widget.ImageView bgImageView;
    /** 共享顶栏，卡片透明度变化时需要重刷它的背景 */
    private LinearLayout topBar;
    /** 标记「这个 View 已经是卡片」，供 applyCardAlphaRecursive 识别不可点击的卡片（如滑块行） */
    private static final int CARD_TAG_KEY = 0x7C4D0001;

    /**
     * 所有「用 cardSurface 做背景」的 View 登记表。
     *
     * <p><b>为什么需要登记而不能靠遍历识别</b>：旧实现靠
     * {@code isClickable() || CARD_TAG_KEY} 在整棵 View 树里猜哪些是卡片。
     * 猜漏一处（漏打 tag、或行本身不可点击）那块就不跟透明度走，
     * 表现就是「拖滑块只有一部分变色、重启才全对」——
     * 因为重启后所有 View 都是按新 cardAlpha 新建的。
     *
     * <p>改成登记后，<b>只要背景出自 {@link #applyCardSurface} 就一定会被刷新</b>，
     * 不再依赖「记得打 tag」这种人工约定。新增卡片样式时用统一入口即可。</p>
     *
     * <p>弱引用：View 被回收后自动出队，不会因为这张表钉住整棵旧 View 树。</p>
     */
    private final java.util.List<java.lang.ref.WeakReference<View>> cardViewRefs =
            new java.util.ArrayList<>();

    /** 卡片圆角 / 是否标题卡的登记信息（重建背景时要原样还原） */
    private final java.util.Map<View, int[]> cardStyleMap =
            new java.util.IdentityHashMap<>();

    /**
     * 「设置卡片背景」的唯一入口。
     *
     * <p>所有需要跟随「卡片不透明度」的 View 都必须走这里，
     * 不要直接 {@code v.setBackground(cardSurface(...))}——
     * 那样不会进登记表，拖滑块时不会被刷新。</p>
     *
     * @param cornerDp 圆角 dp，-1 = 行默认（10dp）
     * @param isHeader true = 分区标题卡（更亮一级底色 + 8dp 圆角）
     */
    private void applyCardSurface(View v, int cornerDp, boolean isHeader) {
        if (v == null) return;
        v.setBackground(cardSurface(cornerDp, isHeader));
        // 相同 View 重复设置时不要重复登记（IdentityHashMap 天然去重）
        cardStyleMap.put(v, new int[]{cornerDp, isHeader ? 1 : 0});
        boolean registered = false;
        for (java.util.Iterator<java.lang.ref.WeakReference<View>> it = cardViewRefs.iterator();
             it.hasNext(); ) {
            View x = it.next().get();
            if (x == null) {
                it.remove();
            } else if (x == v) {
                registered = true;
            }
        }
        if (!registered) cardViewRefs.add(new java.lang.ref.WeakReference<>(v));
    }

    private void applyCardSurface(View v) {
        applyCardSurface(v, -1, false);
    }
    /**
     * 当前正在填充的「设置分组」卡片。
     *
     * <p>设置页从「每个标题一张卡片、每行再套一张卡片」改成
     * 「每个标题一张卡片，标题下面直接列选项，选项之间用分割线隔开」。
     * 这样一屏能少一半的圆角与描边，视觉更安静，也更省一次 draw。</p>
     *
     * <p>用法：{@code sectionHeaderInCard()} 建立新分组并把它记到
     * {@link #settingsGroup}；紧接着的 {@code addInfoRow()} /
     * {@code addSliderRow()} 判断「父容器是否就是这个分组」，
     * 是的话走组内行（无背景、无间距、带分割线），否则走老的独立卡片。</p>
     */
    private LinearLayout settingsGroup;

    /**
     * 在组内加一条分割线。
     *
     * <p>组里第一个子 View 是标题，所以「每行之前都加线」正好形成
     * 「标题 ├─ 选项1 ├─ 选项2」的层级：标题和第一项之间也有一条线，
     * 组内所有行之间也都有线，不需要额外判断。</p>
     */
    private void addGroupDivider(LinearLayout group) {
        View line = new View(ctx);
        line.setBackgroundColor((UiKit.color(ctx,
                com.google.android.material.R.attr.colorOutlineVariant) & 0x00FFFFFF)
                | 0x33000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(14);
        lp.rightMargin = dp(14);
        group.addView(line, lp);
    }

    /** 标记「这是分区标题卡片」，重刷时保留它的高亮底色 */
    private static final int CARD_TAG_HDR_KEY = 0x7C4D0002;
    /** 选图目标：true = 设为文件管理主题背景，false = 设为全局背景 */
    private boolean pickForFmTheme = false;
    /** 背景图渲染序号：拖滑块连发时只让最后一次的结果落地 */
    private int bgLoadSeq = 0;
    /** 背景图来源文件（私有目录内），null = 未设置 */
    private File bgImageFile;
    /** 背景不透明度 0-100 */
    private static int bgAlpha = 100;
    /** 背景模糊度 0-25 */
    private static int bgBlur = 0;
    /** 卡片不透明度 0-100（越低背景越透出来） */
    private static int cardAlpha = 100;
    /** 状态栏底色不透明度，默认 0（透明，露出背景图） */
    private static int statusBarAlpha = 0;

    // ── 文件管理主题（独立开关，不启用时全部回落到全局值）──
    /** 文件列表页是否启用独立主题 */
    private static boolean fmThemeOn = false;
    /** 文件列表页专用背景图，null = 用全局背景图 */
    private File fmBgFile;
    /** 文件列表页背景不透明度 */
    private static int fmBgAlpha = 100;
    /** 文件列表页背景模糊度 */
    private static int fmBgBlur = 0;
    /** 文件列表行卡片不透明度 */
    private static int fmRowAlpha = 100;
    /** 文件列表页密度：0标准 1紧凑 3宽松 */
    private static int fmDensity = 0;
    /** 侧边栏面板不透明度 0-100，默认 92（保留一点磨砂感又不牺牲可读性） */
    private static int navPanelAlpha = 92;
    /** 侧边栏面板的填充色，供 navPanelSurface 复用 */
    private int navPanelFill = 0;
    /** 明暗模式：0=跟随系统，1=亮色，2=暗色 */
    private static int uiMode = 0;

    /** 回收站是否启用（关闭后侧边栏不显示回收站入口） */
    private static boolean trashEnabled = true;
    /** 删除时是否默认移入回收站 */
    private static boolean trashDefault = true;
    /** 回收站保留天数，0 表示不自动清理 */
    private static int trashKeepDays = 30;
    /** 设置页「回收站」分组是否展开（仅界面状态） */
    private boolean trashExpanded = true;

    /** 列表文件密度：1=紧凑 2=标准 3=宽松 */
    private static int listDensity = 2;

    /** 同步写入整型偏好（commit 同步落盘，失败打日志） */
    private void savePrefInt(String key, int value) {
        try {
            boolean ok = fmPrefs().edit().putInt(key, value).commit();
            android.util.Log.i("HYPrefs", "save " + key + "=" + value + " ok=" + ok);
        } catch (Throwable t) {
            android.util.Log.e("HYPrefs", "save " + key + " failed", t);
        }
    }
    private static final String PREF_GRID_MODE = "grid_mode";
    private static final String PREF_THUMBS = "thumbs_enabled";
    /** 包名文件夹（Android/data 等）右下角显示应用图标 */
    private static final String PREF_APP_BADGE = "app_badge_enabled";
    private boolean thumbsEnabled = true;
    /** 包名文件夹右下角应用图标开关 */
    private boolean appBadgeEnabled = true;
    private static final String PREF_RECENT_FILES = "recent_files";
    // 注：历史上有 last_path_l / last_path_r / active_pane 三项（用于恢复上次目录），
    // 按用户要求「重启回到存储根」已停用，偏好文件中的旧键值忽略即可。
    /** 撤销快照合并控制：连续输入（<600ms 且位置连续）合并为一次快照 */
    private long lastUndoPush = 0;
    private int lastUndoStart = -1;

    // ── 窗格抽象：每个窗格 = 独立路径/条目/多选/滚动（对齐 MT 真双独立窗口） ──
    // ══════════════════════════════════════════════════════════
    // 动效常量（Material 3 duration token）
    //   short1-4 = 50/100/150/200，medium1-4 = 250/300/350/400
    //   官方阈值：入场 225ms、离场 195ms、>400ms 被感知为慢
    // ══════════════════════════════════════════════════════════
    /** 列表项 add（M3 short3） */
    private static final int ANIM_ITEM_ADD = 150;
    /** 列表项 remove（M3 short3） */
    private static final int ANIM_ITEM_REMOVE = 150;
    /** 列表项 move（M3 short3） */
    private static final int ANIM_ITEM_MOVE = 150;
    /** 列表项 change（M3 short2） */
    private static final int ANIM_ITEM_CHANGE = 100;

    /** stagger 单项快照时长（M3 short2 的一半，保持轻盈） */
    private static final int ANIM_STAGGER_ITEM = 90;
    /** stagger 相邻间隔：官方上限 20ms，取 12ms 留余量 */
    private static final int ANIM_STAGGER_STEP = 12;
    /** stagger 总延迟上限：官方 >400ms 被感知为慢，收敛到 150ms */
    private static final int ANIM_STAGGER_MAX_DELAY = 150;
    /**
     * 切换目录时的整体交叉淡化时长。
     *
     * <p>与 stagger（逐项错峰入场）是<b>两件事</b>：
     * stagger 用于「首次进入/空目录变出内容」这类新表面；
     * 而翻目录只是同一块列表换了数据，官方不建议逐项错峰，
     * 但也绝不等于「硬切」。用整体淡出→淡入把硬切变得有过渡。</p>
     *
     * <p>取 short4 = 200ms 的一半各占（出 90 / 入 140），
     * 总感知约 200ms，符合 Material「transition ≤300ms」的约束。</p>
     */
    private static final int ANIM_DIR_FADE_OUT = 90;
    /** 文件 ↔ 设置 内容区切换时长 */
    private static final int ANIM_MODE_SWITCH = 180;
    /**
     * 底部功能栏高度。
     *
     * <p>原来用 WRAP_CONTENT：内部 navButton 是 20dp 图标 + 上下 2dp padding，
     * 外面还有 wrap 的默认 padding 和 bottomNav 自己的 1dp，实测能顶到 56~64dp，
     * 加上底边距 8dp 就是列表最后 70dp 被盖住。统一固定成 48dp。</p>
     */
    private static final int BOTTOM_NAV_HEIGHT = 48;
    /** 列表底部额外留白 = 功能栏高度 + 底边距 + 一点余量，保证最后一项能完整露出来 */
    private static final int BOTTOM_NAV_RESERVE = 62;
    private static final int ANIM_DIR_FADE_IN = 140;

    /**
     * 系统动画缩放倍率（设置 → 无障碍/开发者选项 的「动画时长缩放」）。
     *
     * <p>系统对 {@code ViewPropertyAnimator}/{@code ObjectAnimator} 会自动应用该值，
     * 但**手写 postDelayed 延时不会**被缩放；且无障碍「移除动画」
     * （Android 14+ {@code accessibility_force_reduce_motion}）不改这个值。
     * 因此本字段用于显式判零：为 0 时跳过所有自定义动画。</p>
     */
    private static float animScale = 1f;

    /**
     * 本窗格是否还没播过入场动画（每次冷启动只播一次）。
     *
     * <p>官方规范：stagger 仅限「新建 surface」——首次进入列表 / 空态→有内容。
     * 翻目录、排序、删除后刷新都是「同一 surface 的重载」，重播会让用户
     * 连续翻目录时反复看到入场动画（被点名的过度动效）。</p>
     *
     * <p>置 true：冷启动后首次加载。置 false：导航到新目录、或已播过一次。</p>
     */
    // staggerPending / dirFadePending 已下沉到 Pane —— 见 Pane 类上的注释。
    // Module 级单 boolean 会被两个窗格互相消费。

    private static final class Pane {
        String path;
        /**
         * 下一次 reload 结束后是否播整体交叉淡化。
         *
         * <p><b>必须在 Pane 上，不能在 Module 上</b>。旧实现是 Module 的
         * 单个 boolean，而 Module 同时管两个窗格 —— 左栏翻目录置位后，
         * 如果右栏先触发 reload（reloadAll / 粘贴完成 / SAF 授权回调），
         * 标志被右栏消费，左栏自己反而没动画；反之亦然。
         * 双窗格快速交替操作时可复现「动画跑到错的那一栏」。</p>
         */
        boolean dirFadePending;
        /** 同 dirFadePending，逐项 stagger 的消费标志，同样必须在 Pane 上 */
        boolean staggerPending = true;
        /** 进入 SAF 授权目录（content://）前的路径：授权根的".."返回这里 */
        String navPrev;
        final List<FileEntry> entries = new ArrayList<>();
        final Set<FileEntry> selected = new LinkedHashSet<>();
        /** 区间框选锚点（按下位置），-1 表示无进行中的框选 */
        int dragRangeStart = -1;
        AccessResult lastResult = AccessResult.empty("None");
        RecyclerView list;
        FileListAdapter adapter;
        /** ⭐8 目录滚动位置记忆（参考 MaterialFiles FileListFragment）：按路径缓存
         *  RecyclerView 布局状态（含像素偏移），切换目录保存上一个、返回时恢复 */
        final Map<String, android.os.Parcelable> scrollStateByDir = new HashMap<>();
        /** 最近一次加载的路径：reload 离开前据此把旧目录状态存入 scrollStateByDir */
        String lastLoadedPath;

        /** ⭐ 目录缓存（仿 ZhuFiler DirCache）：路径 → 条目快照 + 目录指纹（mtime/子项数）。
         *  切换回未变化目录直接复用快照，避免重复 listFiles（大目录性能提升明显）。 */
        final Map<String, DirSnapshot> dirCache = new HashMap<>();

        /** ⭐ 底部栏：后退历史栈（点击目录/输入路径时压栈，后退时弹出） */
        final java.util.Deque<String> backStack = new java.util.ArrayDeque<>();
        /** ⭐ 底部栏：前进历史栈（后退时把当前路径压到这里，前进时弹回） */
        final java.util.Deque<String> fwdStack = new java.util.ArrayDeque<>();

        Pane(String path) {
            this.path = path;
        }
    }

    /** 目录快照：条目列表 + 目录指纹（上次 listFiles 的 mtime 与子项数，用于失效判断） */
    private static final class DirSnapshot {
        final List<FileEntry> entries;
        final long mtime;
        final int childCount;
        DirSnapshot(List<FileEntry> e, long m, int c) {
            entries = e;
            mtime = m;
            childCount = c;
        }
    }

    private Context ctx;
    /** 宿主 Activity（SAF 系统文件选择器需 Activity 启动） */
    private Activity hostActivity;
    /** 当前活动实例（供 MainActivity 转发 SAF 授权结果后刷新侧边栏） */
    private static FileManagerModule activeInstance;
    /** 授权列表加载诊断（错误可见性：列表空且异常时 toast 真实原因） */
    private String mountLoadError;

    /** 下次 reload 是否恢复滚动位置：仅「切到其他页面/后台后返回」时置 true。
     *  目录之间的普通切换不恢复（避免用户莫名其妙的滚动跳动）。 */
    private boolean restoreScrollOnLoad = false;
    /** SAF 系统文件选择器授权请求码 */
    private static final int REQ_SAF_MOUNT = 1005;
    private int sortMode = SORT_NAME;
    /** 双窗格共用的 ViewHolder 回收池（两栏 item 布局一致，可安全共享） */
    private androidx.recyclerview.widget.RecyclerView.RecycledViewPool sharedViewPool;

    private final Pane paneL = new Pane("/storage/emulated/0");
    private final Pane paneR = new Pane("/storage/emulated/0");
    private final List<Pane> panes = new ArrayList<>(); // 未来三列扩展：动态增删
    private Pane active;
    private boolean multiMode = false;

    private TextView pathText;
    /** 顶栏中部插槽（文件=面包屑 / 终端=标题） */
    private ImageView topBarLeftIcon;
    private FrameLayout topBarCenterSlot;
    private View topBarCrumbView;
    private View topBarFileActions;
    /** 终端模式下顶栏的标题 View 与 pwd TextView */
    /** ⭐ v2.8.0 ROOT 顶栏按钮（状态着色：已授权=主色） */
    /** 上一次已知的 root 状态，用于状态变化时只刷新一次 UI */
    /** 曾经授权成功过的标记：决定启动时是"静默探测"还是"什么都不做"（避免每次冷启动弹授权窗） */
    private static final String PREF_ROOT_EVER = "root_ever_granted";
    private LinearLayout clipBar, opBar;
    private TextView clipInfo;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public FileManagerModule() {
        this.id = "file_manager";
        this.name = "文件管理";
        this.version = "1.0.0";
        this.icon = "ic_folder";
        this.showInDrawer = true;
    }

    @Override
    public void onAttach(Context context, ModuleServices services) {
        this.ctx = context.getApplicationContext();
        this.detached = false;
        com.aliya.hyfilemgr.filemgr.ThumbLoader.init(this.ctx);
        refreshAnimScale();
        if (context instanceof Activity) hostActivity = (Activity) context;
        activeInstance = this;
        restoreState();
        // 进程被系统回收后静态计数会停留在旧值，这里归零避免泄漏
        com.aliya.hyfilemgr.service.TaskKeepAliveService.resetCount();
        installBackCallback();
        maybeAutoCheckUpdate();
    }

    /**
     * 启动时自动检查更新（可在「设置 → 软件更新」里关掉）。
     *
     * <p>时机选择：延后 3 秒。启动瞬间要做的事很多（恢复状态、
     * 首次列表、缩略图），此时发网络请求会抢带宽和线程，
     * 而且用户还没看到界面就弹窗很突兀。3 秒后界面已稳定。</p>
     *
     * <p>失败静默：自动检查是「顺便看一下」，连不上更新服务器
     * 不该在启动时弹错误框打断用户。只有真发现新版本才提示。</p>
     */
    private void maybeAutoCheckUpdate() {
        if (!fmPrefs().getBoolean(PREF_AUTO_CHECK_UPDATE, true)) return;
        handler.postDelayed(() -> {
            if (detached) return;
            new Thread(() -> {
                UpdateInfo info = fetchLatestUpdate();
                if (info == null) return;                 // 失败静默
                if (info.code <= appVersionCode()) return; // 已最新，不打扰
                handler.post(() -> {
                    if (detached) return;
                    // 同一版本只自动提示一次，避免每次启动都弹
                    String seen = fmPrefs().getString(PREF_UPDATE_LAST_ASKED, "");
                    if (!info.ver.equals(seen)) {
                        fmPrefs().edit().putString(PREF_UPDATE_LAST_ASKED, info.ver).apply();
                        showUpdateDetailDialog(info);
                    }
                });
            }, "HyUpdateAuto").start();
        }, 3000);
    }

    /**
     * Activity 进后台时把草稿落盘。
     *
     * <p><b>为什么必须有这一处</b>：草稿只在 finishEditor / 关标签时写，
     * 而用户最常见的丢数据方式是「编辑中按 Home → 系统 OOM 杀掉进程」——
     * 这条路一次 finishEditor 都不会走。基类 HyVqModule.onPause 是空实现，
     * MainActivity:162 调了也没效果。覆写它才算真正兜住「杀进程不丢」。</p>
     *
     * <p>IO 跑在后台线程：onPause 里同步写几十 MB 草稿会阻塞主线程，
     * 系统可能因此认为 ANR。</p>
     */
    @Override
    public void onPause() {
        super.onPause();
        if (editorTabs.isEmpty()) return;
        final java.util.List<EditorTabState> snapshot = new ArrayList<>(editorTabs);
        new Thread(() -> {
            try {
                File dir = draftDir();
                if (dir == null) return;
                org.json.JSONArray meta = new org.json.JSONArray();
                int idx = 0;
                for (EditorTabState t : snapshot) {
                    if (!t.dirty || t.content == null) continue;
                    File f = new File(dir, "draft_" + idx + ".new");
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                        fos.write(t.content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("i", idx);
                    o.put("path", t.rootTarget != null ? t.rootTarget : t.file.getAbsolutePath());
                    o.put("root", t.rootTarget != null);
                    o.put("enc", t.encoding == null ? "UTF-8" : t.encoding);
                    o.put("le", t.lineEnding == null ? "LF" : t.lineEnding);
                    meta.put(o);
                    idx++;
                }
                for (File f : dir.listFiles()) {
                    if (f.getName().endsWith(".new")) {
                        String nm = f.getName();
                        //noinspection ResultOfMethodCallIgnored
                        f.renameTo(new File(dir, nm.substring(0, nm.length() - 4)));
                    }
                }
                fmPrefs().edit().putString(EDITOR_PREF_DRAFTS, meta.toString()).commit();
            } catch (Throwable t) {
                android.util.Log.w("HyEditor", "onPause draft save failed", t);
            }
        }, "HyDraftOnPause").start();
    }

    public void onDetach() {
        detached = true;
        uninstallBackCallback();
        dismissAllPopups();
        persistState();
        if (activeInstance == this) activeInstance = null;
        // 释放 Activity 强引用：否则旋转/重建后旧 Activity 被钉住，
        // 整个 View 树泄漏，且延时回调会对已销毁 Activity 调 recreate
        hostActivity = null;
        // 不清 handler：它是本模块唯一 UI 回调通道，25 处 post 都在用，
        // 清空会丢弃排队中的解压/粘贴/搜索完成回调
        if (dpiHandler != null) dpiHandler.removeCallbacksAndMessages(null);
        // 释放背景图位图。全屏 ARGB_8888 约 10MB，模糊时还有一份同尺寸中间图；
        // Android 8+ 像素在 native 堆，不主动解引用就要等 GC finalizer，
        // 旋转几次屏幕能累积几十 MB。这里主动断掉引用。
        if (bgImageView != null) {
            bgImageView.setImageDrawable(null);
            bgImageView = null;
        }
    }

    /**
     * 读取系统「动画时长缩放」（开发者选项 / 无障碍）。
     *
     * <p>系统对 {@code ViewPropertyAnimator}/{@code ObjectAnimator} 会自动应用该值，
     * 但本项目有 6 处手写 {@code postDelayed} 延时不受缩放影响，另有一些
     * 自定义入场动画需要显式判零。为 0 表示用户关闭动画（或无动画无障碍已开），
     * 此时应跳过所有自定义动效。</p>
     */
    private void refreshAnimScale() {
        try {
            animScale = android.provider.Settings.Global.getFloat(
                    ctx.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable t) {
            animScale = 1f;   // 读不到就当正常值，不要因设置读取失败而瘫痪动效
        }
        // 注意：android.jar 里**没有** AccessibilityManager.isReduceMotionEnabled()
        // （那是 Compose 的 LocalMotionDurationScale，不是平台 API）。
        // 平台侧只有下面三个 Global 缩放值，任一为 0 即视为「用户要求无动画」。
        try {
            float win = android.provider.Settings.Global.getFloat(
                    ctx.getContentResolver(),
                    android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
            float trans = android.provider.Settings.Global.getFloat(
                    ctx.getContentResolver(),
                    android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
            if (win <= 0f && trans <= 0f) animScale = 0f;
        } catch (Throwable ignored) {
        }
    }

    /** 恢复持久化的浏览状态（排序模式 / 是否显示隐藏文件 / 双窗格上次路径）。
     *  只在路径仍可用时套用，否则保留默认 /storage/emulated/0，避免启动进空目录。 */
    /** 恢复**用户偏好**（排序 / 是否显示隐藏文件）。
     *
     *  ⚠️ 刻意**不恢复上次浏览的目录**（用户要求）：
     *  - 重启软件 → FileManagerModule 重新构造 → 两窗格回到内部存储根 /storage/emulated/0
     *  - 挂后台再回来 → 同一实例，路径原样保留，不会跳回根目录
     *  这两种行为靠"实例是否重建"天然区分，无需额外标志。 */
    private void restoreState() {
        if (ctx == null) return;
        android.content.SharedPreferences sp = fmPrefs();
        sortMode = Math.max(SORT_NAME, Math.min(SORT_TIME, sp.getInt(PREF_SORT_MODE, SORT_NAME)));
        showHidden = sp.getBoolean(PREF_SHOW_HIDDEN, false);
        gridMode = sp.getBoolean(PREF_GRID_MODE, false);
        thumbsEnabled = sp.getBoolean(PREF_THUMBS, true);
        appBadgeEnabled = sp.getBoolean(PREF_APP_BADGE, true);
        listDensity = Math.max(1, Math.min(3, sp.getInt(PREF_DENSITY, 2)));
        uiTheme = Math.max(0, Math.min(1, sp.getInt(PREF_UI_THEME, 0)));
        bgAlpha = Math.max(0, Math.min(100, sp.getInt(PREF_BG_ALPHA, 100)));
        bgBlur = Math.max(0, Math.min(25, sp.getInt(PREF_BG_BLUR, 0)));
        cardAlpha = Math.max(0, Math.min(100, sp.getInt(PREF_CARD_ALPHA, 100)));
        statusBarAlpha = Math.max(0, Math.min(100, sp.getInt(PREF_STATUS_BAR_ALPHA, 0)));
        fmThemeOn = sp.getBoolean(PREF_FM_THEME_ON, false);
        fmBgAlpha = Math.max(0, Math.min(100, sp.getInt(PREF_FM_BG_ALPHA, 100)));
        fmBgBlur = Math.max(0, Math.min(25, sp.getInt(PREF_FM_BG_BLUR, 0)));
        fmRowAlpha = Math.max(0, Math.min(100, sp.getInt(PREF_FM_ROW_ALPHA, 100)));
        fmDensity = sp.getInt(PREF_FM_DENSITY, 0);
        navPanelAlpha = Math.max(0, Math.min(100, sp.getInt(PREF_NAV_PANEL_ALPHA, 92)));
        String fmName = sp.getString(PREF_FM_BG_FILE, "");
        File bgDirNow = bgDir();
        fmBgFile = fmName.isEmpty() ? null : new File(bgDirNow, fmName);
        String bgName = sp.getString(PREF_BG_FILE, "");
        bgImageFile = bgName.isEmpty() ? null : new File(bgDirNow, bgName);
        uiMode = Math.max(0, Math.min(2, sp.getInt(PREF_UI_MODE, 0)));
        // 本地：默认展开（这是主入口，一进来就要能点目录）
        // 工具：默认收起（低频功能，收起来减少视觉噪声）
        // 已有用户的选择不论开收都照旧沿用，这里只是首次默认值。
        navLocalExpanded = sp.getBoolean(PREF_NAV_LOCAL_OPEN, true);
        navToolsExpanded = sp.getBoolean(PREF_NAV_TOOLS_OPEN, false);
        trashEnabled = sp.getBoolean(PREF_TRASH_ENABLED, true);
        trashDefault = sp.getBoolean(PREF_TRASH_DEFAULT, true);
        trashKeepDays = Math.max(0, sp.getInt(PREF_TRASH_KEEP_DAYS, 30));
        com.aliya.hyfilemgr.util.DpiUtils.setOverrideDpi(sp.getInt(PREF_DPI, 0));
        recentFiles.clear();
        String rf = sp.getString(PREF_RECENT_FILES, "");
        if (!rf.isEmpty()) {
            for (String path : rf.split("\n")) {
                if (!path.isEmpty()) recentFiles.add(path);
            }
        }
    }

    /** 持久化浏览状态（排序 / 隐藏文件 / 双窗格路径 / 活动窗格）：切换目录与 detach 时调用 */
    /**
     * 持久化用户偏好。
     *
     * <p>官方文档（SharedPreferences.Editor）：
     * "pending apply() calls can block the main thread during component
     * lifecycle transitions, which is a common source of ANR errors."</p>
     *
     * <p>我们不把 commit 改回 apply（apply 的落盘时机不可控），
     * 而是把 commit 放到专用 HandlerThread：
     * 既保证同步落盘，又不在 UI 线程阻塞。</p>
     */
    private void persistState() {
        if (ctx == null) return;
        // 快照：异步执行时字段可能已被主线程改动
        final boolean fHidden = showHidden;
        final boolean fThumbs = thumbsEnabled;
        final boolean fGrid = gridMode;
        final int fSort = sortMode;
        final int fDensity = listDensity;
        final int fTheme = uiTheme;
        final int fMode = uiMode;
        final boolean fTrashOn = trashEnabled;
        final boolean fTrashDefault = trashDefault;
        final int fTrashDays = trashKeepDays;
        final int fDpi = com.aliya.hyfilemgr.util.DpiUtils.getOverrideDpi();
        final java.util.ArrayList<String> fRecent = new java.util.ArrayList<>(recentFiles);
        PREF_HANDLER.post(() -> {
            try {
                boolean ok = fmPrefs().edit()
                        .putBoolean(PREF_SHOW_HIDDEN, fHidden)
                        .putBoolean(PREF_THUMBS, fThumbs)
                        .putBoolean(PREF_APP_BADGE, appBadgeEnabled)
                        .putBoolean(PREF_GRID_MODE, fGrid)
                        .putInt(PREF_SORT_MODE, fSort)
                        .putInt(PREF_DENSITY, fDensity)
                        .putInt(PREF_UI_THEME, fTheme)
                        .putInt(PREF_BG_ALPHA, bgAlpha)
                        .putInt(PREF_BG_BLUR, bgBlur)
                        .putInt(PREF_CARD_ALPHA, cardAlpha)
                        .putInt(PREF_STATUS_BAR_ALPHA, statusBarAlpha)
                        .putBoolean(PREF_FM_THEME_ON, fmThemeOn)
                        .putInt(PREF_FM_BG_ALPHA, fmBgAlpha)
                        .putInt(PREF_FM_BG_BLUR, fmBgBlur)
                        .putInt(PREF_FM_ROW_ALPHA, fmRowAlpha)
                        .putInt(PREF_FM_DENSITY, fmDensity)
                        .putInt(PREF_NAV_PANEL_ALPHA, navPanelAlpha)
                        .putString(PREF_FM_BG_FILE,
                                fmBgFile == null ? "" : fmBgFile.getName())
                        .putString(PREF_BG_FILE,
                                bgImageFile == null ? "" : bgImageFile.getName())
                        .putInt(PREF_UI_MODE, fMode)
                        .putBoolean(PREF_TRASH_ENABLED, fTrashOn)
                        .putBoolean(PREF_TRASH_DEFAULT, fTrashDefault)
                        .putInt(PREF_TRASH_KEEP_DAYS, fTrashDays)
                        .putInt(PREF_DPI, fDpi)
                        .putString(PREF_RECENT_FILES, android.text.TextUtils.join("\n", fRecent))
                        .commit();
                if (!ok) android.util.Log.e("HYPrefs", "persistState commit failed");
            } catch (Throwable t) {
                android.util.Log.e("HYPrefs", "persistState error", t);
            }
        });
    }

    // ── 视图 ──

    @Override
    public View createMainView(LayoutInflater inflater, ViewGroup parent) {
        ctx = inflater.getContext();
        panes.clear();
        panes.add(paneL);
        panes.add(paneR);
        // 固定为左窗格：不恢复上次的活动窗格（与「重启回到存储根」策略一致）
        active = paneL;
        // 根容器 = FrameLayout：body（模块主体）+ 侧边栏叠加层（内部展开，不遮全局导航栏）
        rootContainer = new FrameLayout(ctx);
        rootContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 背景层先挂上（index 0 = 最底），后面所有页面都在它上面半透明叠着
        attachBackgroundLayer();

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        // 状态栏透明 + edge-to-edge 后，body 从屏幕最顶端开始布局，
        // 必须自己让出状态栏高度，否则顶栏会被状态栏盖住。
        // ⚠️ inset 只能加在 body 上，绝不能加在 rootContainer 上——
        // 那会把 bgImageView 一起推下去，状态栏区域反而露白。
        final int sbHeight = statusBarHeight();
        body.setPadding(dp(8), dp(4) + sbHeight, dp(8), dp(4)); // 紧凑化：原 dp(10)/dp(8)
        body.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        body.addView(buildTopBar());
        bodyRoot = body;
        // 未授权"所有文件访问"时显示非拦截提示条：虚拟视图仍可浏览，读写受限
        permissionBar = buildPermissionBar();
        if (!checkPermission()) {
            body.addView(permissionBar, 1);
        } else {
            permissionBar.setVisibility(View.GONE);
        }
        // ── 内容区 + 悬浮层 ──
        // opBar 必须「浮」在列表之上而不是插在 LinearLayout 里：
        // 插进流式布局会占据高度，退场下移时让出的空间变成空白（看不到文件），
        // 同时布局重排还会与位移动画打架产生回弹。
        FrameLayout contentWrap = new FrameLayout(ctx);
        // 关闭裁剪 + 固定软件层：opBar 用 elevation 浮在列表上时会提升为独立
        // GPU 合成层，升降动画期间与列表层反复合并/分离，表现为覆盖区域
        // 「突然出现/消失」。禁用子 View 裁剪并让 contentWrap 保持单一图层可消除。
        contentWrap.setClipChildren(false);
        contentWrap.setClipToPadding(false);
        contentWrapRef = contentWrap;
        fileContentView = buildContent();
        contentWrap.addView(fileContentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(contentWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // opBar 叠在内容区底部，不占空间
        View op = buildOpBar();
        FrameLayout.LayoutParams opLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        opLp.bottomMargin = dp(8);
        opLp.leftMargin = dp(8);
        opLp.rightMargin = dp(8);
        contentWrap.addView(op, opLp);

        body.addView(buildClipBar());

        // ── 底部导航栏：同样叠加在 contentWrap 内、与 opBar 同位 ──
        // ⚠️ 绝不能再作为 body(LinearLayout) 的直接子 View：它会占用
        // 「topMargin 6dp + 自身高度」的布局空间。退出多选时它一显示，
        // contentWrap 立即被压矮，列表底部被截掉一块露出白底 ——
        // 这就是「白色遮罩闪一下」的真正来源，与动画时序无关。
        // 放进 contentWrap 后两者互斥显示、位置完全一致，切换零布局变化。
        View nav = buildBottomNav();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(BOTTOM_NAV_HEIGHT),
                Gravity.BOTTOM);
        navLp.bottomMargin = dp(8);
        navLp.leftMargin = dp(8);
        navLp.rightMargin = dp(8);
        contentWrap.addView(nav, navLp);

        rootContainer.addView(body);

        // ⭐10 FAB 悬浮新建（先于侧边栏叠加层加入：侧边栏打开时被遮罩覆盖，符合 Material 规范）
        buildFab();
        buildNavOverlay();

        // 背景透明度补一次同步。
        // attachBackgroundLayer() 在 body 之前调用，那时 opBar / bottomNav 还是 null，
        // syncContentTransparency 里对它们的半透明处理全部落空，
        // 表现为冷启动后底栏与工具栏不透明、把背景图盖住。
        // 这里等两者建好后再跑一遍。
        // 恒按有背景图处理（见 syncContentTransparency 说明）
        syncContentTransparency(true);

        // 首帧后按底部导航栏实际高度设一次列表底部预留
        if (bottomNav != null) bottomNav.post(this::updateListBottomReserve);

        // 启动时按保存的 statusBarAlpha 应用一次状态栏底色
        // （MainActivity 那边只设了 TRANSPARENT 作为初值，真正值在这里覆盖）
        applyStatusBarAlpha();

        // 状态栏 inset 修正：createMainView 阶段 DecorView 还没 attach，
        // getRootWindowInsets() 拿不到，只能退回 ColorOS 上偏小的资源值 24dp。
        // attach 之后再算一次，把真实高度（约 47dp）补上。
        bodyRef = body;
        body.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                // 立即一次
                applyStatusBarInset();
                // 再补一次：部分 ROM 的 insets 在 attach 的下一帧才稳定
                v.post(FileManagerModule.this::applyStatusBarInset);
            }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
        if (body.isAttachedToWindow()) applyStatusBarInset();

        // 启动时清理过期回收站文件（对比记录时间）
        try {
            purgeExpiredTrash();
        } catch (Throwable ignored) {
        }

        reloadAll();
        return rootContainer;
    }

    // ── 内部侧边栏叠加层：覆盖模块区域（全局导航栏正下方），不遮全局标题栏 ──

    /** 模块根容器（FrameLayout：body + 侧边栏叠加层） */
    private FrameLayout rootContainer;
    /** body 的引用：attach 后要重算状态栏 inset（见 applyStatusBarInset） */
    private LinearLayout bodyRef;
    /** 侧边栏叠加层（GONE 初始，点击三条杠后显示） */
    private FrameLayout navOverlay;
    /** 侧边栏遮罩（点击关闭） */
    private View navScrim;
    /** 侧边栏面板容器（左侧 55% 宽，内容动态重建） */
    private LinearLayout navPanel;
    /** 侧边栏面板宽度（0.55 屏宽，动画初始位移用） */
    private int navPanelWidth;

    private void buildNavOverlay() {
        navOverlay = new FrameLayout(ctx);
        // 与 body 同样的状态栏 inset：侧边栏是 rootContainer 的兄弟，
        // 不单独加会被状态栏盖住顶部（前几轮「侧边栏冲到状态栏上」就是这么来的）
        navOverlay.setPadding(0, statusBarHeight(), 0, 0);
        navOverlay.setVisibility(View.GONE);
        // ⭐ 防点击穿透：侧边栏展开时它必须是**唯一的触摸目标**，
        // 否则点在遮罩/面板空白处的事件会落到底层文件列表上（表现为"误操作列表"）。
        navOverlay.setClickable(true);
        navOverlay.setFocusable(true);
        // 遮罩：浅色半透明黑（同外层 DrawerLayout scrimColor 0x33000000），点击关闭
        navScrim = new View(ctx);
        navScrim.setBackgroundColor(0x33000000);
        navScrim.setClickable(true);
        navScrim.setOnClickListener(v -> closeNavSidebar());
        navOverlay.addView(navScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 左侧面板：磨砂玻璃背景（本体不透明 glass_light_100；右侧圆角 20dp + 半透明白描边）
        navPanel = new LinearLayout(ctx);
        navPanel.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable();
        panelBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        // 面板背景必须用**主题色**，不能固定 glass_light_75（那是 75% 白）。
        // 固定白底 + colorOnSurface 文字 = 浅色主题下深字浅底尚可、
        // 工业风格下浅字浅底直接看不清；再叠加卡片的半透明，对比度严重不足。
        navPanelFill = UiKit.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        // 不透明度由「侧边栏不透明度」控制，默认 92：
        // 保留一点磨砂感又不牺牲可读性。调到 0 就等于完全透出背景图。
        int pa = Math.round(255f * navPanelAlpha / 100f);
        panelBg.setColor((navPanelFill & 0x00FFFFFF) | (pa << 24));
        panelBg.setCornerRadii(new float[]{0, 0, dp(20), dp(20), 0, 0, dp(20), dp(20)});
        panelBg.setStroke(dp(1), UiKit.color(ctx,
                com.google.android.material.R.attr.colorOutlineVariant));
        navPanel.setBackground(panelBg);
        int pad = dp(14);
        navPanel.setPadding(pad, pad, pad, pad);
        navPanel.setClickable(true);   // 面板空白处拦住事件：既不穿透，也不误触关闭
        navPanelWidth = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.55); // 更窄：55%
        navOverlay.addView(navPanel, new FrameLayout.LayoutParams(
                navPanelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        rootContainer.addView(navOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 顶部路径栏（⭐10 重构为 Material AppBar 风格，参考 MaterialFiles）：
     *  ☰ 侧边栏 / 面包屑路径（点击弹层级选择，任意跳转）/ 🔍搜索直达 / 排序直达 / ⋮更多。
     *  首页/新建/刷新/FTP 移出顶栏：首页→路径面包屑「内部存储」级，新建→FAB，刷新→⋮菜单，FTP→底部导航。 */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        topBar = bar;
        bar.setBackground(topBarSurface());
        int pad = dp(3);
        bar.setPadding(pad, pad, pad, pad);

        // 左上角：文件模式显示汉堡（打开侧边栏），终端/设置模式显示返回（回文件列表）
        topBarLeftIcon = toolIcon(R.drawable.ic_menu, v -> {
            if (contentMode == ContentMode.FILES) {
                showNavSidebar();                 // 文件页：开侧边栏
            } else if (currentSettingsPage.isEmpty()) {
                showNavSidebar();                 // 设置主页：也是开侧边栏
            } else {
                showSettingsPage();               // 设置子页：回设置主页
            }
        });
        bar.addView(topBarLeftIcon);

        // 中部区域：文件模式显示面包屑，终端模式显示「终端 | pwd」。
        // 用一个 FrameLayout 承载，两种内容互斥显示，共享同一个顶栏。
        FrameLayout centerSlot = new FrameLayout(ctx);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slotLp.setMarginStart(dp(6));
        bar.addView(centerSlot, slotLp);
        topBarCenterSlot = centerSlot;

        // 面包屑路径（MaterialFiles 风格：点击弹出层级列表，可跳任意上级目录）
        LinearLayout crumb = new LinearLayout(ctx);
        crumb.setOrientation(LinearLayout.HORIZONTAL);
        crumb.setGravity(Gravity.CENTER_VERTICAL);
        crumb.setClickable(true);
        crumb.setOnClickListener(v -> showPathCrumbs());
        centerSlot.addView(crumb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        topBarCrumbView = crumb;

        pathText = new TextView(ctx);
        pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pathText.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        crumb.addView(pathText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 路径右侧下拉箭头（提示可点击展开层级）
        TextView caret = new TextView(ctx);
        caret.setText("▾");
        caret.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        caret.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        caretLp.setMarginStart(dp(3));
        caretLp.setMarginEnd(dp(4));
        crumb.addView(caret, caretLp);

        // 右侧操作区：文件模式显示 搜索/排序/更多，终端模式显示 清屏/复制/中断
        LinearLayout fileActions = new LinearLayout(ctx);
        fileActions.setOrientation(LinearLayout.HORIZONTAL);
        fileActions.setGravity(Gravity.CENTER_VERTICAL);
        fileActions.addView(toolIcon(R.drawable.ic_search, v -> showSearchDialog()));
        fileActions.addView(toolIcon(R.drawable.ic_sort, v -> showSortMenu(v)));
        fileActions.addView(toolIcon(R.drawable.ic_more_vert, v -> showMoreMenu(v)));
        bar.addView(fileActions);
        topBarFileActions = fileActions;

        return bar;
    }

    // ════════════════════════════════════════════════════════════════
    // ⭐ v2.8.0 ROOT 权限与功能
    //
    // 本机实测基线（Android 16 / Magisk + susfs4ksu，2026-09）：
    //  · su = /system/bin/su；su -c 单次约 19ms（授权已记住）→ 一命令一进程足够快
    //  · su 会话域 u:r:magisk:s0，可读 /data/data 等 App 私有目录
    //  · root 建在 App 私有目录的文件缺 SELinux categories，必须 restorecon（见 RootFs.relayToCache）
    //  · /system 是 system-as-root（非独立挂载点）+ erofs 只读 → remount 如实报错，不假装成功
    // ════════════════════════════════════════════════════════════════

    /** ROOT 快捷目录（标签 + 路径 + 说明） */



    /** 面板内小标题（比 sectionHeader 更轻） */
    private TextView subHeader(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(2);
        lp.leftMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }






    /** ⭐10 面包屑路径（MaterialFiles 风格）：点击顶栏路径弹出层级列表，任选一级直接跳转。
     *  真实路径按 / 拆分；分类/回收站/SAF 虚拟视图提示不可用。 */

    /**
     * 构造纯文本 ListView 适配器。
     *
     * <p>不用 {@link android.widget.ArrayAdapter}：它在 {@code getView} 里会校验
     * 布局根节点必须是 TextView，否则抛
     * {@code IllegalStateException: ArrayAdapter requires the resource ID to be a TextView}。
     * 这里直接用 BaseAdapter 自己建行，完全不依赖布局资源，杜绝该类崩溃。</p>
     *
     * @param twoLine true = 显示 sub[i] 作为第二行（sub 可为 null）
     */
    private android.widget.BaseAdapter textListAdapter(final List<String> items,
                                                      final List<String> sub) {
        return new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return items == null ? 0 : items.size();
            }

            @Override
            public Object getItem(int position) {
                return items == null ? null : items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public android.view.View getView(int position, android.view.View convertView,
                                             android.view.ViewGroup parent) {
                LinearLayout row;
                TextView tv1;
                TextView tv2 = null;
                if (convertView instanceof LinearLayout
                        && convertView.getTag() instanceof Object[]) {
                    row = (LinearLayout) convertView;
                    Object[] tag = (Object[]) convertView.getTag();
                    tv1 = (TextView) tag[0];
                    tv2 = (TextView) tag[1];
                } else {
                    row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(14), dp(10), dp(14), dp(10));
                    row.setBackgroundResource(R.drawable.bg_ripple);

                    tv1 = new TextView(ctx);
                    tv1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    tv1.setTextColor(UiKit.color(ctx,
                            com.google.android.material.R.attr.colorOnSurface));
                    tv1.setSingleLine(true);
                    tv1.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    row.addView(tv1);

                    if (sub != null) {
                        tv2 = new TextView(ctx);
                        tv2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                        tv2.setTextColor(UiKit.color(ctx,
                                com.google.android.material.R.attr.colorOnSurfaceVariant));
                        tv2.setSingleLine(true);
                        tv2.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        lp2.topMargin = dp(2);
                        row.addView(tv2, lp2);
                    }
                    row.setTag(new Object[]{tv1, tv2});
                }
                tv1.setText(items.get(position));
                if (sub != null && tv2 != null && position < sub.size()) {
                    tv2.setText(sub.get(position));
                }
                return row;
            }
        };
    }

    /**
     * 地址栏点击：路径输入弹窗。
     *
     * <p>布局：顶部 EditText（默认填入当前路径）→ 底部按钮行
     * [取消] ... [复制] [粘贴] [确认]</p>
     * <ul>
     *   <li>取消：关闭，不做任何事</li>
     *   <li>复制：把当前路径写入系统剪贴板</li>
     *   <li>粘贴：把剪贴板内容填入输入框（不直接跳转，用户可编辑后再确认）</li>
     *   <li>确认：跳转到输入框里的路径</li>
     * </ul>
     */
    private void showPathCrumbs() {
        if (active == null || active.path == null) return;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "跳转路径"));

        // ── 输入框（默认填入当前路径）──
        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setText(displayPath(active.path));
        et.setSingleLine(true);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setSelectAllOnFocus(false);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = dp(2);
        box.addView(et, etLp);

        // 光标移到末尾，方便直接追加/修改
        try {
            et.setSelection(et.getText().length());
        } catch (Throwable ignored) {
        }

        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);

        // ── 按钮行：左 [取消] + 右 [复制][粘贴][确认] ──
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(10);
        box.addView(btns, btnsLp);

        // 左侧：取消
        TextView btnCancel = textButton("取消", v -> dialog.dismiss());
        btns.addView(btnCancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 中间弹性占位，把右侧三个按钮推到右边
        View spacer = new View(ctx);
        btns.addView(spacer, new LinearLayout.LayoutParams(
                0, 1, 1f));

        // 右侧：复制
        btns.addView(textButton("复制", v -> {
            String path = et.getText().toString().trim();
            if (path.isEmpty()) {
                UiKit.toast(ctx, "路径为空");
                return;
            }
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) ctx.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
                    UiKit.toast(ctx, "已复制当前路径");
                }
            } catch (Throwable t) {
                UiKit.alert(ctx, "复制失败：" + t.getMessage());
            }
        }));

        // 右侧：粘贴（把剪贴板内容填进输入框，不直接跳转）
        btns.addView(textButton("粘贴", v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) ctx.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                        || cm.getPrimaryClip().getItemCount() == 0) {
                    UiKit.toast(ctx, "剪贴板为空");
                    return;
                }
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                if (text == null || text.length() == 0) {
                    UiKit.toast(ctx, "剪贴板内容为空");
                    return;
                }
                et.setText(text.toString().trim());
                et.setSelection(et.getText().length());
            } catch (Throwable t) {
                UiKit.alert(ctx, "粘贴失败：" + t.getMessage());
            }
        }));

        // 右侧：确认（跳转）
        btns.addView(textButton("确认", v -> {
            String raw = et.getText().toString().trim();
            if (raw.isEmpty()) {
                UiKit.toast(ctx, "请输入路径");
                return;
            }
            dialog.dismiss();
            navigateTo(active, raw);
        }));

        dialog.show();
    }

    /** ⭐10 排序直达菜单（顶栏排序图标）：名称/大小/时间（Popover 弹出，当前模式高亮） */
    private void showSortMenu(View anchor) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int surface = UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int stroke = UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant);
        android.graphics.drawable.GradientDrawable gd = UiKit.rounded(ctx, 14,
                (surface & 0x00FFFFFF) | 0xF2000000, stroke);
        panel.setBackground(gd);
        int p4 = dp(4);
        panel.setPadding(p4, p4, p4, p4);

        final PopupWindow popup = trackPopup(new PopupWindow(panel,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true));
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(dp(8));
        // 入场/退场动画：从右下角轻微放大淡入，避免「啪」地出现
        popup.setAnimationStyle(R.style.AnimPopup);

        for (int i = 0; i < SORT_LABELS.length; i++) {
            final int mode = i;
            addMenuRow(panel, SORT_LABELS[i], R.drawable.ic_sort, v -> {
                sortMode = mode;
                fmPrefs().edit().putInt(PREF_SORT_MODE, mode).commit();
                reload(active);
                popup.dismiss();
            });
            LinearLayout row = (LinearLayout) panel.getChildAt(panel.getChildCount() - 1);
            for (int j = 0; j < row.getChildCount(); j++) {
                View child = row.getChildAt(j);
                if (child instanceof ImageView) {
                    ((ImageView) child).setColorFilter(mode == sortMode
                            ? UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary)
                            : UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
                } else if (child instanceof TextView) {
                    ((TextView) child).setTextColor(mode == sortMode
                            ? UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary)
                            : UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
                    ((TextView) child).setTypeface(null, mode == sortMode
                            ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                }
            }
        }
        popup.showAsDropDown(anchor, -dp(80), dp(2));
    }

    /** 权限提示条引用（授权返回后动态刷新，避免提示条残留） */
    private View permissionBar;
    /** 权限条父容器（用于动态添加/移除） */
    private LinearLayout bodyRoot;

    /** 权限提示条（非拦截）：点击跳转系统授权页 */
    private View buildPermissionBar() {
        TextView bar = new TextView(ctx);
        bar.setText("⚠ 未开启「所有文件访问」：虚拟视图可浏览，读写受限，点此授权");
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bar.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnErrorContainer));
        bar.setBackground(UiKit.rounded(ctx, 14,
                UiKit.color(ctx, com.google.android.material.R.attr.colorErrorContainer), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        bar.setLayoutParams(lp);
        int pad = dp(8);
        bar.setPadding(pad, dp(6), pad, dp(6));
        bar.setOnClickListener(v -> requestAllFilesAccess());
        return bar;
    }

    /**
     * 刷新权限提示条：授权后从系统设置返回时调用。
     *
     * <p>修复：原先只在 createMainView 时判断一次，用户去系统设置授权后返回，
     * 提示条仍在（功能已可用但提示不消失）。</p>
     */
    private void refreshPermissionBar() {
        if (permissionBar == null || bodyRoot == null) return;
        boolean granted = checkPermission();
        if (granted) {
            if (permissionBar.getParent() != null) {
                bodyRoot.removeView(permissionBar);
            }
            permissionBar.setVisibility(View.GONE);
        } else {
            if (permissionBar.getParent() == null) {
                // 重新插到顶栏下（index 1）
                bodyRoot.addView(permissionBar, Math.min(1, bodyRoot.getChildCount()));
            }
            permissionBar.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onResume() {
        refreshPermissionBar();
        resumePendingInstall();
    }

    /**
     * 从「安装未知应用」系统设置回来后，自动继续之前被权限挡下的下载。
     *
     * <p>需一次 {@code post}：onResume 时 {@code canRequestPackageInstalls()}
     * 在部分 ROM 上还有几十毫秒的延迟刷新，直接查可能仍返回 false。</p>
     */
    private void resumePendingInstall() {
        final UpdateInfo pending = pendingInstallInfo;
        if (pending == null) return;
        handler.postDelayed(() -> {
            if (detached || pendingInstallInfo == null) return;
            if (!canRequestInstall()) {
                // 用户没给权限就回来了：清掉待办，不要再打扰
                pendingInstallInfo = null;
                UiKit.toast(ctx, "未开启安装权限，已取消更新");
                return;
            }
            pendingInstallInfo = null;
            downloadAndInstall(pending);
        }, 400);
    }

    private void requestAllFilesAccess() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            ctx.startActivity(intent);
        } catch (Throwable t) {
            ctx.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /** 中部双列：左窗格 + 分隔线 + 右窗格（各自独立 ListView，对齐 MT） */
    private View buildContent() {
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(buildPane(paneL), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        View divider = new View(ctx);
        divider.setBackgroundColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant));
        content.addView(divider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(buildPane(paneR), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return content;
    }

    /** 构建单窗格：独立 RecyclerView + 适配器 + 滚动监听（滚动即激活本窗格，对齐 MT） */
    private View buildPane(final Pane p) {
        RecyclerView rv = new RecyclerView(ctx);
        if (gridMode) {
            rv.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(ctx, 3));
        } else {
            rv.setLayoutManager(new LinearLayoutManager(ctx));
        }
        // ── 底部避让 ──
        // 底部功能栏是叠在 contentWrap 上的浮层（不占布局空间），
        // 列表原来底部 padding=0 → 滚到末尾时最后几项被浮层盖住，点不到。
        // 给列表加一段底部留白，高度 = 功能栏高度 + 底边距 + 余量。
        //
        // clipToPadding=false 是关键：否则留白区不参与滚动，
        // 内容会在留白上方被截断，仍然有一截空白看起来像 bug。
        // 关掉之后，留白只在「滚到最底」时出现，正好把最后一项让出来。
        rv.setClipToPadding(false);
        rv.setPadding(0, dp(2), 0, dp(BOTTOM_NAV_RESERVE));

        // ── 性能优化（官方 RecyclerView 指南建议项，此前一项都没做）──

        // 1) 固定尺寸：item 高度不随内容变化（行高由 listDensity 决定、
        //    网格行高也固定），声明后 RecyclerView 省掉每次数据集变化的
        //    整表重新测量，滚动与刷新都更稳。
        rv.setHasFixedSize(true);

        // 2) item 视图缓存：默认 2 个。文件列表每屏十几个可见项，
        //    缓存 10 个可让「滚动回来」免去重新 bind，弱机型收益明显。
        rv.setItemViewCacheSize(10);

        // 3) 双窗格共享 RecycledViewPool：两栏 item 布局完全一致
        //    （FileListAdapter 只有一种 VH、onCreateViewHolder 不分支），
        //    共享后一栏滚出去的 ViewHolder 可直接被另一栏复用，
        //    省掉一半的 inflate 与 findViewById。
        //    注意：网格/列表切换不重建 RecyclerView（只 reloadAll + LayoutManager 不变），
        //    所以池子不会跨布局类型混用。
        if (sharedViewPool != null) {
            rv.setRecycledViewPool(sharedViewPool);
        } else {
            sharedViewPool = new androidx.recyclerview.widget.RecyclerView.RecycledViewPool();
            // 每栏一屏可见 ~12 项，默认每类型 5 个偏少，提到 20
            sharedViewPool.setMaxRecycledViews(0, 20);
            rv.setRecycledViewPool(sharedViewPool);
        }

        p.list = rv;
        p.adapter = new FileListAdapter(ctx, p.entries, p.selected, makeListener(p));
        p.adapter.setThumbsEnabled(thumbsEnabled);
        p.adapter.setAppBadgeEnabled(appBadgeEnabled);
        p.adapter.setDensity(fmActiveDensity());
        p.adapter.setRowAlpha(fmActiveRowAlpha());
        rv.setAdapter(p.adapter);
        // 列表项动画：淡入淡出 + 位移，比默认更柔和
        // 时长取自 Material 3 duration token（不用 180/160 这类散值）：
        //   add/remove/move = short3(150)，change = short2(100)
        androidx.recyclerview.widget.DefaultItemAnimator ia =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        ia.setAddDuration(ANIM_ITEM_ADD);
        ia.setRemoveDuration(ANIM_ITEM_REMOVE);
        ia.setMoveDuration(ANIM_ITEM_MOVE);
        ia.setChangeDuration(ANIM_ITEM_CHANGE);
        rv.setItemAnimator(ia);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int state) {
                // 用户拖动滚动 = 交互本窗格 → 切换活跃焦点（MT 路径栏联动实测）
                if (state == RecyclerView.SCROLL_STATE_DRAGGING && active != p) {
                    setActive(p);
                }
            }
        });
        // 轻点窗格任意位置（含空白）即切焦点 —— 同步键高亮依赖 active
        attachSwipeToSelect(rv, p);
        return rv;
    }

    /** 横滑进入多选的触发阈值（dp） */
    private static final int SWIPE_SELECT_DP = 72;

    /**
     * 横向滑动进入多选。
     *
     * <p>手指在列表上横向移动超过 {@link #SWIPE_SELECT_DP} 后松手，
     * 进入多选并选中滑动起点所在的行。手势过程中被滑动的条目会跟随
     * 手指轻微位移（最大 28dp）并逐级加深蓝色边框，形成「正在拾起」的
     * 实时反馈；未达阈值则回弹归位。</p>
     */
    private void attachSwipeToSelect(final RecyclerView rv, final Pane p) {
        final float enterThreshold = dp(SWIPE_SELECT_DP);   // 进入多选的阈值
        final float rangeThreshold = dp(48);                // 多选中触发框选的阈值
        final float maxShift = dp(28);

        final float[] downX = {0f};
        final float[] downY = {0f};
        final int[] downPos = {-1};        // 按下时的条目位置（框选起点）
        final View[] shifting = {null};
        final boolean[] abort = {false};
        final boolean[] rangeMode = {false}; // 本次手势处于「框选」模式
        final int[] lastRangeEnd = {-1};     // 上次框选终点，用于避免重复刷新

        rv.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView r, android.view.MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN: {
                        if (active != p) setActive(p);
                        downX[0] = ev.getX();
                        downY[0] = ev.getY();
                        abort[0] = false;
                        rangeMode[0] = false;
                        lastRangeEnd[0] = -1;
                        shifting[0] = null;
                        downPos[0] = positionAt(r, ev);
                        return false;
                    }
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (abort[0]) return false;
                        float dx = ev.getX() - downX[0];
                        float dy = ev.getY() - downY[0];

                        // 纵向意图更强 → 交给列表纵向滚动（多选中同样允许）
                        if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > dp(10)) {
                            abort[0] = true;
                            releaseShift();
                            return false;
                        }
                        if (Math.abs(dx) < dp(6)) return false;

                        // 横向确认
                        r.getParent().requestDisallowInterceptTouchEvent(true);

                        if (multiMode) {
                            // ── 多选态：横向拖动 = 区间框选 ──
                            if (Math.abs(dx) < rangeThreshold) {
                                // 未达阈值时只有轻微跟随，避免误触
                                if (shifting[0] != null) {
                                    float shift = Math.max(-maxShift,
                                            Math.min(maxShift, dx * 0.3f));
                                    shifting[0].setTranslationX(shift);
                                }
                                return true;
                            }
                            rangeMode[0] = true;
                            applyRangeSelection(r, ev, downPos[0], lastRangeEnd);
                            return true;   // 吞掉，避免触发条目点击
                        }

                        // ── 非多选态：横向拖动 = 单条卡片跟手 ──
                        if (shifting[0] == null && downPos[0] >= 0) {
                            RecyclerView.ViewHolder vh =
                                    r.findViewHolderForAdapterPosition(downPos[0]);
                            if (vh != null) shifting[0] = vh.itemView;
                        }
                        if (shifting[0] != null) {
                            float shift = Math.max(-maxShift, Math.min(maxShift, dx));
                            shifting[0].setTranslationX(shift);
                            float progress = Math.min(1f, Math.abs(dx) / enterThreshold);
                            shifting[0].setAlpha(1f - 0.25f * progress);
                        }
                        return false;
                    }
                    case android.view.MotionEvent.ACTION_UP: {
                        float dx = ev.getX() - downX[0];
                        View target = shifting[0];
                        shifting[0] = null;
                        r.getParent().requestDisallowInterceptTouchEvent(false);

                        // ── 框选结束 ──
                        if (rangeMode[0]) {
                            rangeMode[0] = false;
                            return true;
                        }

                        if (target == null || abort[0] || downPos[0] < 0) {
                            return false;
                        }

                        // ── 达到阈值：进入多选 ──
                        if (Math.abs(dx) >= enterThreshold) {
                            FileEntry sel = entryAt(p, downPos[0]);
                            // 关键：卡片先无动画归位，再统一做一次入场，
                            // 避免「卡片回弹」与「工具栏上冲」两个动画叠加产生弹跳
                            target.setTranslationX(0f);
                            target.setAlpha(1f);
                            if (sel != null) {
                                p.selected.add(sel);
                                p.adapter.setSelected(p.selected);
                                enterMultiMode();
                                updateOpBar();
                                return true;
                            }
                        }

                        // ── 未达阈值：弹性回位 ──
                        target.animate().translationX(0f).alpha(1f)
                                .setDuration(180)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                                .start();
                        return false;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        r.getParent().requestDisallowInterceptTouchEvent(false);
                        rangeMode[0] = false;
                        releaseShift();
                        return false;
                    }
                    default:
                        return false;
                }
            }

            /**
             * 区间框选：把 [起点, 当前点] 之间的所有条目加入选择。
             *
             * <p>一次拖动即可框选中间任意多条，不需要滑两次。区间按当前位置重算，
             * 往回拖时会把超出区间的条目取消选中。</p>
             */
            private void applyRangeSelection(RecyclerView rv, android.view.MotionEvent ev,
                                             int anchorPos, int[] lastEnd) {
                int curPos = positionAt(rv, ev);
                if (curPos < 0) return;
                if (curPos == lastEnd[0]) return;
                lastEnd[0] = curPos;

                int from = Math.min(anchorPos, curPos);
                int to = Math.max(anchorPos, curPos);
                boolean changed = false;
                for (int i = from; i <= to; i++) {
                    FileEntry e = entryAt(p, i);
                    if (e == null) continue;
                    if (p.selected.add(e)) changed = true;
                }
                // 回收本次区间之外、但由本次拖动加入的条目
                if (p.dragRangeStart >= 0) {
                    int pf = Math.min(p.dragRangeStart, lastRangePrevEnd);
                    int pt = Math.max(p.dragRangeStart, lastRangePrevEnd);
                    for (int i = pf; i <= pt; i++) {
                        if (i >= from && i <= to) continue;   // 仍在区间内，保留
                        FileEntry e = entryAt(p, i);
                        if (e != null) p.selected.remove(e);
                    }
                }
                p.dragRangeStart = anchorPos;
                lastRangePrevEnd = curPos;

                if (changed || true) {
                    p.adapter.setSelected(p.selected);
                    updateOpBar();
                }
            }

            /** 上一次框选的终点（用于回收区间） */
            private int lastRangePrevEnd = -1;

            private void releaseShift() {
                if (shifting[0] != null) {
                    shifting[0].animate().translationX(0f).alpha(1f)
                            .setDuration(160).start();
                    shifting[0] = null;
                }
            }

            @Override
            public void onTouchEvent(RecyclerView r, android.view.MotionEvent ev) { }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) { }
        });
    }

    /** 屏幕坐标 → RecyclerView 适配器位置；越界返回 -1 */
    private int positionAt(RecyclerView rv, android.view.MotionEvent ev) {
        View child = rv.findChildViewUnder(ev.getX(), ev.getY());
        if (child == null) return -1;
        return rv.getChildAdapterPosition(child);
    }

    /** 取窗格内指定位置的条目；越界返回 null */
    private FileEntry entryAt(Pane p, int pos) {
        if (p == null || p.entries == null) return null;
        if (pos < 0 || pos >= p.entries.size()) return null;
        FileEntry e = p.entries.get(pos);
        return (e == null || e.parent) ? null : e;
    }

    /** 剪贴板栏：复制/剪切后出现，支持粘贴/清空 */
    private View buildClipBar() {
        clipBar = new LinearLayout(ctx);
        clipBar.setOrientation(LinearLayout.HORIZONTAL);
        clipBar.setGravity(Gravity.CENTER_VERTICAL);
        int bg = UiKit.color(ctx, com.google.android.material.R.attr.colorSecondaryContainer);
        clipBar.setBackground(UiKit.rounded(ctx, 14, bg, 0));
        int pad = dp(8);
        clipBar.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        clipBar.setLayoutParams(lp);
        clipBar.setVisibility(View.GONE);

        clipInfo = new TextView(ctx);
        clipInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clipInfo.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer));
        clipBar.addView(clipInfo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clipBar.addView(textButton("粘贴", v -> pasteClipboard()));
        clipBar.addView(textButton("清空", v -> clearClipboard()));
        return clipBar;
    }

    /**
     * 多选底部功能栏（重构版）。
     *
     * <p>结构：左侧「已选 N 项」计数 + 右侧 6 个图标动作
     * （全选 / 复制 / 剪切 / 删除 / 更多 / 关闭）。图标卡片式、等宽分布，
     * 进出场带位移 + 透明度 + 轻微回弹动画。</p>
     */
    private View buildOpBar() {
        opBar = new LinearLayout(ctx);
        opBar.setOrientation(LinearLayout.VERTICAL);
        int surface = UiKit.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int stroke = UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary);
        opBar.setBackground(UiKit.rounded(ctx, 16, surface, stroke));
        int pad = dp(10);
        opBar.setPadding(pad, dp(8), pad, dp(8));
        // LayoutParams 由外层 FrameLayout 指定（底部对齐叠加）
        opBar.setVisibility(View.GONE);
        // 不用 elevation：elevation 会让 opBar 提升为独立渲染层，
        // 动画期间与下方列表层的合成顺序变化会造成闪烁。
        // 改用不透明底色 + 描边保证可辨识度。

        // ── 第一行：已选计数 ──
        opCount = new TextView(ctx);
        opCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        opCount.setTypeface(null, android.graphics.Typeface.BOLD);
        opCount.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        opCount.setText("已选 0 项");
        opBar.addView(opCount);

        // ── 第二行：动作网格 ──
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        opBar.addView(row, rowLp);

        row.addView(opAction(R.drawable.ic_checklist, "全选", v -> selectAll()));
        row.addView(opAction(R.drawable.ic_copy, "复制", v -> clipboardCopy(false)));
        row.addView(opAction(R.drawable.ic_cut, "剪切", v -> clipboardCopy(true)));
        row.addView(opAction(R.drawable.ic_trash, "删除", v -> deleteSelected()));
        row.addView(opAction(R.drawable.ic_more_vert, "更多", v -> showMoreMenu(opBar)));
        row.addView(opAction(R.drawable.ic_close, "关闭", v -> exitMultiMode()));

        return opBar;
    }

    /**
     * 多选栏内的单个图标动作：图标在上、文字在下，等宽 weight=1。
     */
    private View opAction(int iconRes, String label, View.OnClickListener onClick) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(2), dp(6), dp(2), dp(4));
        cell.setBackgroundResource(R.drawable.bg_ripple_round);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setOnClickListener(v -> {
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(140)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f))
                            .start())
                    .start();
            onClick.onClick(v);
        });

        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconRes);
        ic.setColorFilter(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        cell.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(4);
        cell.addView(tv, tvLp);

        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return cell;
    }

    /** 多选栏左侧计数文本 */
    private TextView opCount;

    /** 底部状态栏：活跃窗格的文件夹数 · 文件数 · 存储 */


    // ════════════════════════════════════════════════════════════════
    // ⭐10 底部导航栏 + FAB（参考 Amaze/MaterialFiles 现代布局）：
    // 底部导航 = 文件 / 分类 / 回收站 / 更多 全局视图切换；
    // FAB = 右下角悬浮新建（新建文件/文件夹，Material 规范）。
    // ════════════════════════════════════════════════════════════════

    /** 底部导航容器（5 Tab 横排） */
    private LinearLayout bottomNav;
    /** 底部栏按钮引用（用于前进/后退灰显刷新） */
    private ImageView navBackBtn, navFwdBtn;
    /** 同步路径按钮容器 + 左右箭头 */
    private LinearLayout syncBtn;
    /** 同步按钮的交换图标（重绘后只保留一个） */
    private ImageView syncIcon;
    /** 底部导航 Tab 视图列表（更新选中高亮用） */
    private final List<View> navTabs = new ArrayList<>();
    /** FAB 悬浮新建按钮 */
    private ImageView fabNew;

    /** 构建底部导航栏：文件（内部存储根）/分类（分类选择）/回收站/更多（⋮菜单） */
    private View buildBottomNav() {
        bottomNav = new LinearLayout(ctx);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        int bg = UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow);
        // 加主色描边：与多选栏视觉统一，浮在列表上时也有清晰边界
        int navStroke = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary);
        bottomNav.setBackground(UiKit.rounded(ctx, 16, bg, navStroke));
        int pad = dp(4);
        bottomNav.setPadding(pad, 0, pad, 0);
        // 固定高度：WRAP_CONTENT 会随内部按钮 padding 飘，导致"太高"
        bottomNav.setMinimumHeight(dp(BOTTOM_NAV_HEIGHT));
        // LayoutParams 由外层 FrameLayout 指定（底部叠加，与 opBar 同位）

        // ① 后退 <
        navBackBtn = navButton(dp(20), v -> navBack(active));
        navBackBtn.setImageResource(R.drawable.ic_chevron_left);
        addNavBtnWrap(navBackBtn);

        // ② 前进 >
        navFwdBtn = navButton(dp(20), v -> navForward(active));
        navFwdBtn.setImageResource(R.drawable.ic_chevron_right);
        addNavBtnWrap(navFwdBtn);

        // ③ 上级 ↑
        ImageView upBtn = navButton(dp(20), v -> navUp(active));
        upBtn.setImageResource(R.drawable.ic_arrow_upward);
        addNavBtnWrap(upBtn);

        // ④ 同步路径：蓝色包裹的 ← → 组合
        syncBtn = buildSyncButton();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                dp(40), ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomNav.addView(syncBtn, slp);

        // ⑤ 新建 +
        ImageView addBtn = navButton(dp(20), v -> showNewMenu(bottomNav));
        addBtn.setImageResource(R.drawable.ic_add);
        addNavBtnWrap(addBtn);

        bottomNav.setAlpha(0f);
        bottomNav.setTranslationY(dp(10));
        bottomNav.animate().alpha(1f).translationY(0f).setDuration(150).start();
        refreshNavButtons();
        return bottomNav;
    }

    /** 底部栏图标按钮（统一样式） */
    private ImageView navButton(int size, View.OnClickListener onClick) {
        ImageView iv = new ImageView(ctx);
        iv.setColorFilter(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(3), 0, dp(3), 0);
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setOnClickListener(onClick);
        // 按压回弹（Material 触感）
        iv.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start();
            } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                    || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
            }
            return false;
        });
        return iv;
    }

    /** 把图标按钮包进等分布局（weight=1） */
    private void addNavBtnWrap(ImageView iv) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setGravity(Gravity.CENTER);
        wrap.setClickable(true);
        wrap.setFocusable(true);
        wrap.setBackgroundResource(R.drawable.bg_ripple_round);
        wrap.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setOnClickListener(v -> iv.performClick());
        bottomNav.addView(wrap, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /**
     * 同步路径按钮：蓝色圆角包裹的「← →」组合。
     *
     * <p>语义：焦点在左窗格时 → 高亮（点击把左目录同步到右）；焦点在右窗格时 ← 高亮
     * （点击把右目录同步到左）。整块用蓝色描边包裹以示「这是一组联动操作」。</p>
     */
    /**
     * 双窗格「同步路径」按钮。
     *
     * <p><b>重绘原因</b>：旧实现是两个 40×32 的直线箭头上下堆叠，
     * 总高 64dp —— 比功能栏本身还高，直接把栏撑破。而且两个箭头
     * 都调用同一个 syncByFocus()，点哪个效果一样，「左右」没有方向含义。</p>
     *
     * <p>现在改为<b>单个 40×40 的环形交换图标</b>：
     * 两条方向相反的弧形箭头首尾相接，是 swap / exchange 的通用符号。
     * 一个按钮一个动作，不会再让人以为上下箭头有区别。</p>
     *
     * <p>高亮改为「描边 + 底色」而不是箭头颜色 —— 单图标没法再用
     * 「哪个箭头亮」表达当前焦点，改用整体强调色表示「可切换」。</p>
     */
    private LinearLayout buildSyncButton() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(true);

        int stroke = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary);
        int fill = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimaryContainer);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor((fill & 0x00FFFFFF) | 0x33000000);
        gd.setStroke(Math.round(dp(1) * 1.5f), stroke);
        box.setBackground(gd);

        syncIcon = new ImageView(ctx);
        syncIcon.setImageResource(R.drawable.ic_swap_paths);
        syncIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        syncIcon.setColorFilter(stroke);
        syncIcon.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(syncIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        box.setOnClickListener(v -> syncByFocus());
        // 与同栏其它按钮一致的按压回弹
        box.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start();
            } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                    || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(3f))
                        .start();
            }
            return false;
        });
        return box;
    }

    /** 刷新同步按钮的高亮：左焦点→右箭头亮；右焦点→左箭头亮 */
    /**
     * 刷新同步按钮的可用状态。
     *
     * <p>同步方向由<b>焦点栏</b>决定（syncByFocus：焦点在左则左→右）。
     * 所以「按钮是否该亮」要同时满足三个条件，缺一都点不出效果：</p>
     * <ol>
     *   <li>两栏都存在且路径非空</li>
     *   <li>两栏路径不同 —— 相同时 syncPath 会直接 toast「两边已经是同一目录」</li>
     *   <li><b>焦点栏</b>（同步的源）路径非空 —— 旧实现只看前两条，
     *       焦点栏为空指针时按钮仍然亮，点了静默无反应</li>
     * </ol>
     *
     * <p>用一个可读的 disableReason 直接把「为什么不能点」写到按钮的
     * contentDescription 上，长按/读屏能说出原因，不靠用户猜。</p>
     */
    private void refreshSyncButton() {
        if (syncIcon == null) return;

        String disableReason = null;
        if (paneL == null || paneR == null) {
            disableReason = "单窗格模式不支持同步";
        } else if (paneL.path == null || paneR.path == null) {
            disableReason = "窗格路径未就绪";
        } else if (paneL.path.equals(paneR.path)) {
            disableReason = "两边已经是同一目录";
        } else {
            // 焦点栏 = 同步的源。它必须有有效路径，否则 syncByFocus 无源可移
            Pane src = (active == paneL) ? paneL : paneR;
            if (src.path == null || src.path.isEmpty()) {
                disableReason = "当前焦点栏没有有效路径";
            }
        }

        boolean enabled = (disableReason == null);
        int on = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary);
        int off = UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant);
        syncIcon.setColorFilter(enabled ? on : off);
        if (syncBtn != null) {
            syncBtn.setAlpha(enabled ? 1f : 0.45f);
            syncBtn.setContentDescription(enabled
                    ? "同步路径：" + ((active == paneL) ? "左 → 右" : "右 → 左")
                    : disableReason);
        }
    }


    /**
     * 同步路径。
     *
     * @param leftToRight true = 左目录 → 右栏；false = 右目录 → 左栏
     */
    /**
     * 按当前焦点同步：焦点在左栏 → 左到右；焦点在右栏 → 右到左。
     *
     * <p>不再依赖点的是哪个箭头 —— 上下两个箭头都触发同一逻辑，
     * 由 active 决定方向。箭头高亮只做视觉提示。</p>
     */
    private void syncByFocus() {
        boolean leftToRight = (active == paneL);
        android.util.Log.d("HYFM", "syncByFocus: active=" + (leftToRight ? "LEFT" : "RIGHT")
                + " → " + (leftToRight ? "左→右" : "右→左"));
        syncPath(leftToRight);
    }

    private void syncPath(boolean leftToRight) {
        Pane src = leftToRight ? paneL : paneR;
        Pane dst = leftToRight ? paneR : paneL;
        if (src == null || dst == null) return;
        if (src.path == null || src.path.equals(dst.path)) {
            UiKit.toast(ctx, "两边已经是同一目录");
            return;
        }
        dst.backStack.push(dst.path);
        dst.fwdStack.clear();
        dst.path = src.path;
        reload(dst);
        refreshNavButtons();
        UiKit.toast(ctx, (leftToRight ? "左 → 右" : "右 → 左") + " 已同步");
    }

    /** 更新底部导航高亮：文件=真实路径，分类=cat://，回收站=trash://，更多不常驻高亮 */
    /** 底部栏已改为导航按钮组，不再有标签高亮；保留方法供旧调用点使用 */
    private void updateBottomNav() {
        refreshNavButtons();
        refreshSyncButton();
    }


    /** 构建 FAB：右下角悬浮圆形新建按钮（Material 规范，Elevation 阴影，点击弹新建菜单） */
    private void buildFab() {
        fabNew = new ImageView(ctx);
        fabNew.setImageResource(R.drawable.ic_add);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(UiKit.color(ctx, com.google.android.material.R.attr.colorPrimaryContainer));
        fabNew.setBackground(gd);
        int s = dp(52);
        fabNew.setPadding(dp(14), dp(14), dp(14), dp(14));
        fabNew.setColorFilter(UiKit.color(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer));
        fabNew.setElevation(dp(6));
        fabNew.setOnClickListener(v -> {
            animatePress(fabNew, () -> showNewMenu(fabNew));
        });
        // ⭐ 用户反馈：右下角悬浮加号会挡住剪贴板操作栏。
        // 改为**内置到底部导航栏**（见 buildBottomNav 的 addNewButton），此处不再挂到 rootContainer。
        fabNew.setVisibility(View.GONE);
    }

    /** 点击缩放反馈动画（按下缩小 → 回弹后执行动作），用于按钮的平滑过渡 */
    private void animatePress(final View v, final Runnable then) {
        if (v == null) {
            if (then != null) then.run();
            return;
        }
        // 回弹从 140ms 放宽到 280ms，并加减速插值：此前回弹过快，
        // 手指还没离开屏幕卡片就归位了。
        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                        .withEndAction(then).start())
                .start();
    }

    // ── 活跃窗格与交互 ──

    /** 切换活跃窗格：顶部路径栏/状态栏/底部导航跟随（MT 实测联动行为） */
    private void setActive(Pane p) {
        if (p == null) return;
        if (active == p) { refreshSyncButton(); return; }   // 已是焦点也要刷新高亮
        active = p;
        android.util.Log.d("HYFM", "setActive: " + (p == paneL ? "LEFT" : "RIGHT")
                + "  path=" + p.path);
        if (pathText != null) {
            pathText.setText(displayPath(p.path));
        }
        updateBottomNav();      // 内部会调 refreshNavButtons → refreshSyncButton
    }

    /** 每个窗格独立 listener（闭包捕获所属窗格，点击/长按先激活再处理） */
    private FileListAdapter.Listener makeListener(final Pane p) {
        return new FileListAdapter.Listener() {
            @Override
            public void onClick(FileEntry e) {
                if (active != p) setActive(p);
                // ".."强制条目：返回上级（不算在列出文件中）
                if (e.parent) {
                    // 条目自带目标路径（压缩包根目录的 .. 指向 zip 所在文件夹）
                    if (e.path != null && !e.path.isEmpty() && !e.path.equals(p.path)) {
                        navigateTo(p, e.path);
                    } else {
                        goParent();
                    }
                    return;
                }
                switch (e.type) {
                    case DIR:
                    case VIRTUAL_DIR:
                        enterDir(p, e);
                        break;
                    case LINK:
                        openLink(p, e);
                        break;
                    case VIRTUAL_FILE:
                        if (e.path.startsWith("content://")) {
                            openSafFile(e); // SAF 授权文件：uri + provider mime 直发系统
                        } else if (e.path.startsWith(PREFIX_ZIP)) {
                            // 嵌套压缩包：先把内层 zip 解出到缓存，再进入其内部浏览
                            if (e.name != null
                                    && e.name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                                enterNestedZip(e);
                            } else {
                                // 普通包内条目：只解出命中的这一条，其余字节不碰
                                openZipEntry(e);
                            }
                        } else {
                            UiKit.toast(ctx, "虚拟条目，仅可查看信息");
                        }
                        break;
                    default:
                        File tf = AccessRouter.fileOf(e);
                        // ⚠️ 安装包必须排在压缩包之前判断。
                        // .apk 本质是 zip，isZip() 对它返回 true；
                        // 先判压缩包会直接进内部浏览，用户点安装包看到的却是目录树。
                        // 安装包默认走「打开方式 → 安装」，想看内部结构可以长按或
                        // 在打开方式弹窗里选「解压」。
                        if (isInstallerExt(e.name)) {
                            // apk / xapk 交给内置安装流程（含 APK 信息预检）
                            String low = e.name.toLowerCase(java.util.Locale.ROOT);
                            if (low.endsWith(".apk") || low.endsWith(".xapk")) {
                                installApkByName(e.path, e.name, false);
                            } else {
                                // aab / apks / apkm / zipx：系统安装器不认，
                                // 用打开方式弹窗让用户自行选择
                                showOpenWithDialog(e.path, e.name, false);
                            }
                            break;
                        }
                        // 压缩包：直接进入内部浏览（beta.8），不再走解压或外部打开
                        if (isZip(e)) {
                            // 现在所有归档格式都能内部浏览（listArchiveItems 统一枚举）：
                            // zip/7z/rar/tar 家族 → 进虚拟路径
                            // 单流格式（gz/bz2/xz/zst）没有条目 → 直接解压
                            if (isArchiveBrowsable(e)) enterZip(e.path);
                            else extractZip(e);
                        } else if (isTextFile(tf.getName())) {
                            openTextEditor(tf);   // 文本/代码文件 → 内置编辑器
                        } else {
                            openFile(tf);         // 其余外部打开
                        }
                        break;
                }
            }

            @Override
            public void onLongClick(FileEntry e, View anchor) {
                // ".."强制条目不参与长按菜单
                if (e.parent) return;
                if (active != p) setActive(p);
                showItemMenu(p, e, anchor);
            }

            @Override
            public void onSelectionChanged() {
                updateOpBar();
            }
        };
    }

    /** 路径栏显示：zip://<绝对路径> 变换为「包名.zip/包内路径/」，与 MT 的 zip 内浏览观感一致 */
    private static String displayPath(String path) {
        if (path == null || !path.startsWith(PREFIX_ZIP)) return path;
        String[] parts = splitZipPath(path);
        String zipName = parts[0];
        int i = zipName.lastIndexOf('/');
        if (i >= 0) zipName = zipName.substring(i + 1);
        return zipName + "/" + parts[1];
    }

    // ══════ 底部栏导航：历史栈 ══════

    /** 跳转到新路径并记录历史（进入目录 / 跳转路径都走这里） */
    private void navigateTo(Pane p, String newPath) {
        if (p == null || newPath == null || newPath.equals(p.path)) return;
        p.backStack.push(p.path);
        p.fwdStack.clear();          // 新分支，清空前进栈
        p.path = newPath;
        // 翻目录不用 stagger（那是给「新表面」的），改用整体交叉淡化
        p.staggerPending = false;
        p.dirFadePending = true;
        reload(p);
        refreshNavButtons();
    }

    /** 后退：上一级历史 */
    private void navBack(Pane p) {
        if (p == null || p.backStack.isEmpty()) {
            UiKit.toast(ctx, "没有上一步了");
            return;
        }
        p.fwdStack.push(p.path);
        p.path = p.backStack.pop();
        p.staggerPending = false;
        p.dirFadePending = true;
        reload(p);
        refreshNavButtons();
    }

    /** 前进：下一步历史 */
    private void navForward(Pane p) {
        if (p == null || p.fwdStack.isEmpty()) {
            UiKit.toast(ctx, "没有下一步了");
            return;
        }
        p.backStack.push(p.path);
        p.path = p.fwdStack.pop();
        p.staggerPending = false;
        p.dirFadePending = true;
        reload(p);
        refreshNavButtons();
    }

    /** 上一级目录 */
    private void navUp(Pane p) {
        if (p == null) return;
        String cur = p.path;
        if (cur == null) return;
        // 虚拟路径：退回收站/分类的上一层就是根目录
        if (cur.startsWith(PREFIX_TRASH)) {
            navigateTo(p, Environment.getExternalStorageDirectory().getAbsolutePath());
            return;
        }
        // zip 内：退到包内上一级，或退出 zip
        if (cur.startsWith(PREFIX_ZIP)) {
            String[] parts = splitZipPath(cur);
            String inner = parts[1];
            if (inner == null || inner.isEmpty()) {
                String zipPath = parts[0];
                int i = zipPath.lastIndexOf('/');
                navigateTo(p, i > 0 ? zipPath.substring(0, i)
                        : Environment.getExternalStorageDirectory().getAbsolutePath());
            } else {
                String trimmed = inner.endsWith("/")
                        ? inner.substring(0, inner.length() - 1) : inner;
                int i = trimmed.lastIndexOf('/');
                String parentInner = i >= 0 ? trimmed.substring(0, i + 1) : "";
                navigateTo(p, PREFIX_ZIP + parts[0] + "/" + parentInner);
            }
            return;
        }
        int i = cur.lastIndexOf('/');
        if (i <= 0) {
            UiKit.toast(ctx, "已在根目录");
            return;
        }
        navigateTo(p, cur.substring(0, i));
    }

    /** 底部栏按钮可用性刷新（前进/后退灰显） */
    private void refreshNavButtons() {
        if (navBackBtn != null) {
            boolean can = active != null && !active.backStack.isEmpty();
            navBackBtn.setAlpha(can ? 1f : 0.3f);
        }
        if (navFwdBtn != null) {
            boolean can = active != null && !active.fwdStack.isEmpty();
            navFwdBtn.setAlpha(can ? 1f : 0.3f);
        }
        if (syncBtn != null) refreshSyncButton();
    }

    /** 同步复制/移动（root 移除后替代原 RootFs.copy/move，走 App 自身权限） */
    private static boolean copyOrMoveSync(String src, String dst, boolean cut) {
        try {
            java.io.File s = new java.io.File(src);
            java.io.File d = new java.io.File(dst);
            if (cut) {
                if (d.exists()) return false;
                return s.renameTo(d);
            }
            if (s.isDirectory()) {
                return copyDirRecursive(s, d);
            }
            try (java.io.InputStream in = new java.io.FileInputStream(s);
                 java.io.OutputStream out = new java.io.FileOutputStream(d)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 递归复制目录 */
    private static boolean copyDirRecursive(java.io.File src, java.io.File dst) {
        if (!dst.exists() && !dst.mkdirs()) return false;
        java.io.File[] kids = src.listFiles();
        if (kids == null) return false;
        for (java.io.File k : kids) {
            java.io.File t = new java.io.File(dst, k.getName());
            if (k.isDirectory()) {
                if (!copyDirRecursive(k, t)) return false;
            } else {
                try (java.io.InputStream in = new java.io.FileInputStream(k);
                     java.io.OutputStream out = new java.io.FileOutputStream(t)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } catch (Throwable ex) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * root 已移除：恒返回 false。
     *
     * <p>保留此方法只为让原有调用点（复制/移动/删除/新建的判断分支）无需全部重写：
     * 返回 false 即走 App 自身权限路径，root 分支成为死代码。</p>
     */
    private static boolean needRoot(String path) {
        return false;
    }

    /** 进入目录（虚拟目录可能为空列表，对齐 MT 静默空行为） */
    private void enterDir(Pane p, FileEntry e) {
        if (e == null || !e.isDir()) return;
        navigateTo(p, e.path);
    }

    /** 符号链接：跟随进入（目录）或打开（文件） */
    /** 记录最近打开的文件 */
    private void recordRecent(String path) {
        if (path == null || path.isEmpty()) return;
        recentFiles.remove(path);
        recentFiles.add(0, path);
        while (recentFiles.size() > 20) recentFiles.remove(recentFiles.size() - 1);
        // 只改内存，不落盘：openLink 后续必定调 reload()，若各自 commit
        // 一次打开文件就是两次 fsync。recentFiles 属"丢了也无所谓"的数据，
        // 统一在 onDetach 落盘一次即可。
    }

    private void openLink(Pane p, FileEntry e) {
        recordRecent(e.path);
        File f = AccessRouter.fileOf(e);
        if (f.isDirectory()) {
            p.path = e.path;
            reload(p);
        } else if (f.exists()) {
            // 文本/代码文件 → 内置编辑器；其余外部打开
            if (isTextFile(f.getName())) openTextEditor(f);
            else openFile(f);
        } else {
            UiKit.alert(ctx, "链接目标不存在");
        }
    }

    /** 返回上级（活跃窗格）；根目录提示 */
    private void goParent() {
        if (active == null || active.path == null || active.path.equals("/")) {
            UiKit.toast(ctx, "已到达根目录");
            return;
        }
        // 压缩包内：退到包内上一级；已在包根 → 退出预览回到 zip 所在文件夹
        if (active.path.startsWith(PREFIX_ZIP)) {
            navUp(active);
            return;
        }
        // 分类/回收站虚拟视图：直接返回内部存储根
        if (active.path.startsWith(PREFIX_TRASH)) {
            active.path = Environment.getExternalStorageDirectory().getAbsolutePath();
            reload(active);
            return;
        }
        // SAF uri 路径：父 uri 来自上次列表结果（lastResult.parentUri）；
        // 授权根（parentUri=null）→ 返回进入前的路径（navPrev）
        if (active.path.startsWith("content://")) {
            String up = active.lastResult == null ? null : active.lastResult.parentUri;
            if (up == null) up = active.navPrev;
            if (up == null) {
                UiKit.toast(ctx, "已到达根目录");
                return;
            }
            active.path = up;
            reload(active);
            return;
        }
        String parent = new File(active.path).getParent();
        if (parent == null) {
            UiKit.toast(ctx, "已到达根目录");
            return;
        }
        active.path = parent;
        reload(active);
    }


    // ══════════ 返回键接管 ══════════

    /**
     * 系统返回键回调。
     *
     * <p>此前项目里<b>完全没有</b>任何返回处理（无 onBackPressed、
     * 无 OnBackPressedCallback、无 OnBackInvokedCallback），
     * 按返回直接退出 Activity，双栏导航/侧边栏/多选全部被丢弃。</p>
     *
     * <p>官方文档明确：{@code OnBackPressedCallback} 始终会被调用，
     * 与 {@code android:enableOnBackInvokedCallback} 的取值无关，
     * 因此这是最稳的接入方式——不需要现在就做完整的 Predictive Back 迁移，
     * 将来要接系统预测动画时再补 OnBackInvokedCallback 即可。</p>
     *
     * <p>处理顺序（从「最临时」到「最持久」）：</p>
     * <ol>
     *   <li>多选模式 → 退出多选</li>
     *   <li>侧边栏展开 → 收起</li>
     *   <li>设置弹窗 → 关闭</li>
     *   <li>设置页（内嵌） → 回文件列表</li>
     *   <li>当前窗格在虚拟路径（压缩包内/回收站） → 逐级返回</li>
     *   <li>有历史栈 → 后退一步</li>
     *   <li>都不是 → 交给系统（退出 App）</li>
     * </ol>
     */
    private androidx.activity.OnBackPressedCallback backCallback;

    /** 挂到宿主 Activity；重复调用幂等 */
    private void installBackCallback() {
        if (!(hostActivity instanceof androidx.activity.ComponentActivity)) return;
        if (backCallback != null) return;   // 已挂过
        backCallback = new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (handleBackPressed()) return;
                // 本模块不处理 → 交还系统（Activity 退出）
                setEnabled(false);
                if (hostActivity != null) hostActivity.onBackPressed();
                setEnabled(true);
            }
        };
        ((androidx.activity.ComponentActivity) hostActivity)
                .getOnBackPressedDispatcher().addCallback(backCallback);
    }

    /** 卸掉回调（detach 时） */
    private void uninstallBackCallback() {
        if (backCallback != null) {
            backCallback.remove();
            backCallback = null;
        }
    }

    /**
     * 消费一次返回。
     *
     * @return true 表示本模块已处理，不应退出 Activity
     */
    public boolean handleBackPressed() {
        // 1. 多选
        if (multiMode) {
            exitMultiMode();
            return true;
        }
        // 2. 侧边栏
        if (navOverlay != null && navOverlay.getVisibility() == View.VISIBLE) {
            closeNavSidebar();
            return true;
        }
        // 3. 设置弹窗（showSettingsDialog 系列的 Dialog）
        //    没有单独字段，靠 Activity 的 Window 判断太脆；
        //    这里靠 settingsEmbedView 与 contentMode 区分「内嵌设置页」
        // 4. 内嵌设置页：逐层退回，不要一步跳回文件列表。
        //    子页 → 设置主页 → 文件列表。
        //    直接 setContentMode(FILES) 会让用户在任意子页按返回都被弹到
        //    文件列表，丢掉「我在设置里」的层级感。
        if (contentMode == ContentMode.SETTINGS) {
            if (!currentSettingsPage.isEmpty()) {
                showSettingsPage();      // 子页 → 设置主页
            } else {
                setContentMode(ContentMode.FILES);   // 设置主页 → 文件列表
            }
            return true;
        }
        // 4b. 相册页 → 文件列表
        if (contentMode == ContentMode.GALLERY) {
            setContentMode(ContentMode.FILES);
            return true;
        }
        // 5. 虚拟路径（压缩包内 / 回收站）→ 逐级返回
        if (active != null && active.path != null) {
            String cur = active.path;
            if (cur.startsWith(PREFIX_ZIP)) {
                String[] parts = splitZipPath(cur);
                String inner = parts[1];
                if (inner != null && !inner.isEmpty()) {
                    // 包内子目录 → 上一级
                    String trimmed = inner;
                    while (trimmed.endsWith("/")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1);
                    }
                    int cut = trimmed.lastIndexOf('/');
                    String up = cut < 0 ? "" : trimmed.substring(0, cut + 1);
                    active.path = PREFIX_ZIP + parts[0] + "/" + up;
                    reload(active);
                    return true;
                }
                // 包根 → 退出预览，回到压缩包所在目录
                File zf = new File(parts[0]);
                String dir = zf.getParent();
                active.path = dir == null
                        ? Environment.getExternalStorageDirectory().getAbsolutePath()
                        : dir;
                reload(active);
                return true;
            }
            // 回收站只显示顶层条目、不存在子目录浏览，
            // 所以进到这里说明已在回收站根（PREFIX_TRASH），
            // 直接交给下一步（历史栈）回退到进入前的目录

        }
        // 6. 历史栈
        if (active != null && !active.backStack.isEmpty()) {
            navBack(active);
            return true;
        }
        // 7. 交给系统
        return false;
    }

    /** 全部窗格重新加载（首次进入视图） */
    private void reloadAll() {
        // 后台线程调用（如 restoreFromTrash 里的 runTask）会直接触碰
        // RecyclerView 适配器 → CalledFromWrongThreadException。
        // 统一转回主线程。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            handler.post(this::reloadAll);
            return;
        }
        // 写操作/刷新后目录内容可能变化：清空全部窗格目录缓存（强制重新 listFiles）
        for (Pane p : panes) {
            if (p.dirCache != null) p.dirCache.clear();
        }
        for (Pane p : panes) reload(p);
    }

    /** 重新加载单个窗格：AccessRouter 四源路由 → 排序 → 刷新（恢复该目录上次滚动位置） */
    private void reload(Pane p) {
        // 记录最近的真实目录，供终端作工作目录（虚拟路径时回退用它）
        // 只记录**当前激活窗格**的真实路径：reloadAll 会按 L→R 连续调 reload，
        // 若无条件赋值，最终留下的是右窗格路径，而用户操作的是左窗格。
        if (p != null && p == active && p.path != null
                && !p.path.startsWith("content://")
                && !p.path.startsWith(PREFIX_TRASH)
                && !p.path.startsWith(PREFIX_ZIP)
                && !p.path.startsWith("cat://")) {
            lastRealPath = p.path;
        }
        if (p == null || p.path == null) return;
        // 不在此 persistState()：persistState 的注释已明确「路径不再保存」，
        // 而 reload 是每次切目录/刷新/操作的公共入口 →
        // 每翻一层目录一次同步 commit（13 键 fsync，UI 线程）。
        // 真正需要落盘的是设置类改动，那些 handler 已各自调用。
        // 滚动位置记忆（参考 MaterialFiles）：离开目录前缓存布局状态，**仅在特定场景恢复**。
        // ⭐ 用户反馈：目录之间随便点一下也恢复旧位置，会让人莫名"上滑"。
        //   故改为：只有「切到别处（如设置）再返回」才回到原位置；
        //   同一次浏览中的目录切换一律**从顶部开始**。
        if (p.list != null && p.list.getLayoutManager() != null && p.lastLoadedPath != null) {
            try {
                p.scrollStateByDir.put(p.lastLoadedPath, p.list.getLayoutManager().onSaveInstanceState());
            } catch (Throwable ignored) {
            }
        }
        final android.os.Parcelable restoreState = restoreScrollOnLoad
                ? p.scrollStateByDir.get(p.path) : null;
        restoreScrollOnLoad = false;   // 用后即清，避免影响后续普通目录切换
        p.lastLoadedPath = p.path;
        p.selected.clear();
        // 用 instant：翻目录时底部栏直接切到位，不播 160ms 退场动画。
        // 旧写法播动画，而下面紧接着就 bind 新数据，两个窗口重叠。
        if (p == active && multiMode) exitMultiMode(true);

        // 虚拟视图路由：回收站（不经过 AccessRouter 四源路由，路径不落盘）
        if (p.path.startsWith(PREFIX_TRASH)) {
            loadTrash(p);
        } else if (p.path.startsWith(PREFIX_ZIP)) {
            loadZip(p);
        } else {
            // ⭐ 目录缓存（仿 ZhuFiler DirCache）：真实路径 + 非 SAF → 目录指纹未变则复用
            // 原始（未过滤）条目快照，跳过 AccessRouter.list；指纹 = mtime + 子项数。
            // 过滤/置顶/排序仍在下方统一执行（缓存与显示解耦，隐藏开关切换永远正确）。
            boolean fromCache = false;
            // 子项数：-1 表示「还没扫过」。下面按需扫描，避免无快照时白扫一遍。
            int cc = -1;
            // ⭐ v2.8.0 root 路径禁用目录缓存：root 目录在 App 侧的 lastModified()/
            // listFiles() 恒为 0/null，指纹永远相同 → 目录内容变了仍会显示旧快照
            if (!p.path.startsWith("content://") && !needRoot(p.path)) {
                DirSnapshot snap = p.dirCache.get(p.path);
                File dirF = new File(p.path);
                long mt = dirF.lastModified();
                // ⚠️ 只有「已有快照」时才需要算子项数去比对指纹。
                // 旧实现在无快照时也扫一遍，且扫完直接丢弃 —— 白花一次主线程 IO。
                if (snap != null) {
                    File[] kids = dirF.listFiles();
                    cc = kids == null ? 0 : kids.length;
                }
                if (snap != null && snap.mtime == mt && snap.childCount == cc) {
                    p.entries.clear();
                    p.entries.addAll(snap.entries);
                    p.lastResult = AccessResult.ok(new ArrayList<>(snap.entries), "Cache");
                    fromCache = true;
                }
            }
            if (!fromCache) {
                p.lastResult = AccessRouter.list(ctx, p.path);
                p.entries.clear();
                p.entries.addAll(p.lastResult.entries);
                if (!p.path.startsWith("content://") && !needRoot(p.path)) {
                    File dirF = new File(p.path);
                    // ⚠️ 旧写法 dirF.listFiles() 在三元表达式两侧各调一次，
                    // 第二次结果直接丢弃 —— 万级目录等于白扫一遍全目录。
                    int childCount;
                    if (cc >= 0) {
                        childCount = cc;   // 上面已扫过，直接复用
                    } else {
                        File[] kids2 = dirF.listFiles();
                        childCount = kids2 == null ? 0 : kids2.length;
                    }
                    p.dirCache.put(p.path, new DirSnapshot(
                            new ArrayList<>(p.entries), dirF.lastModified(), childCount));
                }
            }
            // 隐藏文件开关：默认过滤 .开头条目（".."parent条目不参与过滤）
            if (!showHidden) filterHidden(p);
            // 强制置顶".."返回上级条目（不算在列出文件/统计中，根目录不显示；MT 管理器样式）
            // SAF uri 路径：父 uri 由 SafStrategy 计算（parentUri）；授权根（parentUri=null）
            // 显示".."返回进入前的路径（navPrev），对齐 MT 挂载目录可返回行为
            if (!"/".equals(p.path)) {
                String parentPath;
                if (p.path.startsWith("content://")) {
                    parentPath = p.lastResult.parentUri;
                    if (parentPath == null) parentPath = p.navPrev;
                } else {
                    parentPath = new File(p.path).getParent();
                }
                if (parentPath != null) {
                    p.entries.add(0, FileEntry.parent(parentPath));
                }
            }
        }
        // 虚拟视图（分类/回收站）的".."固定返回内部存储根
        if (p.path.startsWith(PREFIX_TRASH)) {
            p.entries.add(0, FileEntry.parent(
                    Environment.getExternalStorageDirectory().getAbsolutePath()));
        }
        sortMode = Math.max(SORT_NAME, Math.min(SORT_TIME, sortMode));
        sortEntries(p);
        p.adapter.setData(p.entries);

        // 翻目录/前进后退后的整体交叉淡化。
        // 数据已经 bind 完再做动画，避免淡入时看到空列表。
        if (p.dirFadePending) {
            p.dirFadePending = false;
            playDirTransition(p.list);
        }
        // 数据就绪后恢复该目录上次的布局状态（滚动位置/偏移；目录内容变化时
        // RecyclerView 内部会钳制越界位置，安全）
        if (restoreState != null && p.list != null && p.list.getLayoutManager() != null) {
            try {
                p.list.getLayoutManager().onRestoreInstanceState(restoreState);
            } catch (Throwable ignored) {
            }
        }
        // SAF/虚拟层查询失败时提示真实错误（不再静默空列表，便于定位权限/uri格式问题）
        if (p.entries.isEmpty() && p.lastResult.error != null && !p.lastResult.error.isEmpty()) {
            UiKit.toast(ctx, p.lastResult.error);
        }
        // 入场动画只在「新建 surface」时播：首次进入该窗格、或空目录→有内容。
        // 翻目录/排序/删除后刷新都不播（官方 stagger 适用边界）。
        // 入场动画只在「新建 surface」时播：冷启动后的首次加载。
        // 翻目录/排序/删除后刷新都不播（官方 stagger 适用边界）。
        // 目录为空时不播（没有可入场的子项），保持 staggerPending 等下次。
        if (p.staggerPending && !p.entries.isEmpty()) {
            p.staggerPending = false;
            playStaggerAnimation(p.list);
        }
        if (p == active && pathText != null) {
            pathText.setText(displayPath(p.path));
            updateBottomNav();
        }
        updateClipBar();
    }

    /**
     * MT 风格入场动画：条目从上到下依次淡入（纯透明度 0.5→1，无位移不抖动）。
     *
     * <p><b>只在「新建 surface」时调用</b>——Material 官方规范：
     * stagger 仅适用于首次进入列表 / 空态→有内容；
     * 翻目录、排序、删除后刷新都属于「同一 surface 的重载」，重播会让用户
     * 连续翻目录时反复看到入场动画（被点名的过度动效）。</p>
     *
     * <p>三条硬约束（来自官方规范）：</p>
     * <ul>
     *   <li>相邻项间隔 ≤ 20ms（这里取 {@link #ANIM_STAGGER_STEP} = 12ms）</li>
     *   <li>总延迟收敛到 {@link #ANIM_STAGGER_MAX_DELAY} = 150ms，
     *       避免列表变长后延迟线性膨胀（100 项 × 12ms = 1188ms）</li>
     *   <li>系统动画缩放为 0（无障碍「移除动画」）时直接跳过</li>
     * </ul>
     */
    private void playStaggerAnimation(final RecyclerView rv) {
        if (rv == null) return;
        // 用户关闭动画 / 无障碍「移除动画」时不动
        if (animScale <= 0f) return;
        rv.post(() -> {
            int n = rv.getChildCount();
            for (int i = 0; i < n; i++) {
                View child = rv.getChildAt(i);
                if (child == null) continue;
                // 延迟上限：超出后不再累加，全部同时开始
                long delay = Math.min((long) i * ANIM_STAGGER_STEP, ANIM_STAGGER_MAX_DELAY);
                child.setAlpha(0.5f);
                child.animate().alpha(1f)
                        .setDuration(ANIM_STAGGER_ITEM)
                        .setStartDelay(delay).start();
            }
        });
    }

    /**
     * 切换目录时的整体交叉淡化。
     *
     * <p>此前 {@code navigateTo/navBack/navForward} 都设 staggerPending=false，
     * 于是翻目录完全没有过渡 —— 内容瞬间替换，观感「过于突然」。
     * 但按官方规范，翻目录不该用逐项 stagger（那是给「新表面」的），
     * 所以这里用<b>整体</b>淡出→淡入，既保留方向感又不违反 stagger 的适用条件。</p>
     *
     * <p>实现要点：淡出期间先把新数据 bind 好（reload 已经做完），
     * 再淡入，避免中间闪一帧空白。<b>不改变列表高度</b>，
     * 因此不会引起布局跳动。</p>
     */
    private void playDirTransition(final RecyclerView rv) {
        if (rv == null) return;
        if (animScale <= 0f) return;          // 用户关闭动画 / 无障碍减动效
        rv.animate().cancel();
        // 先快速淡出到 0.35（不是全透明，保留轮廓，避免"黑一下"的错觉）
        rv.animate().alpha(0.35f).setDuration(ANIM_DIR_FADE_OUT)
                .withEndAction(() ->
                        rv.animate().alpha(1f).setDuration(ANIM_DIR_FADE_IN).start())
                .start();
    }

    /** 排序：文件夹优先，再按名称/大小/时间（名称走自然排序，file2 < file10） */
    private void sortEntries(Pane p) {
        Comparator<FileEntry> cmp;
        if (sortMode == SORT_TIME) {
            cmp = (a, b) -> Long.compare(b.lastModified, a.lastModified);
        } else if (sortMode == SORT_SIZE) {
            cmp = (a, b) -> Long.compare(b.length, a.length);
        } else {
            cmp = (a, b) -> naturalCompare(a.name, b.name);
        }
        Collections.sort(p.entries, (a, b) -> {
            // ".."返回上级条目永远置顶（不参与排序）
            if (a.parent) return -1;
            if (b.parent) return 1;
            if (a.isDir() != b.isDir()) return a.isDir() ? -1 : 1;
            return cmp.compare(a, b);
        });
    }

    // ── 借鉴工具方法（开源参考调研 2026-08-16 合入） ──

    /** 自然排序比较（移植自 Prism naturalCompare，无 regex 纯扫描）：数字块按数值比较，
     *  file2 < file10、chapter1 < chapter10；非数字按字符序。名称排序体验对齐主流管理器。 */
    private static int naturalCompare(String a, String b) {
        String s1 = a == null ? "" : a.toLowerCase(Locale.getDefault());
        String s2 = b == null ? "" : b.toLowerCase(Locale.getDefault());
        int i1 = 0, i2 = 0;
        while (i1 < s1.length() && i2 < s2.length()) {
            char c1 = s1.charAt(i1);
            char c2 = s2.charAt(i2);
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                long n1 = 0, n2 = 0;
                while (i1 < s1.length() && Character.isDigit(s1.charAt(i1))) {
                    n1 = n1 * 10 + (s1.charAt(i1) - '0');
                    i1++;
                }
                while (i2 < s2.length() && Character.isDigit(s2.charAt(i2))) {
                    n2 = n2 * 10 + (s2.charAt(i2) - '0');
                    i2++;
                }
                if (n1 != n2) return Long.compare(n1, n2);
            } else {
                if (c1 != c2) {
                    // 数字块与非数字相邻：数字排前（对齐 Prism 行为）
                    if (Character.isDigit(c1) && !Character.isDigit(c2)) return -1;
                    if (!Character.isDigit(c1) && Character.isDigit(c2)) return 1;
                    return Character.compare(c1, c2);
                }
                i1++;
                i2++;
            }
        }
        return Integer.compare(s1.length(), s2.length());
    }

    /** 文件名合法性校验（参考 Fossify isAValidFilename）：空名/非法字符/尾点/保留名拒绝。
     *  与 SafStrategy.sanitizeName（净化）互补：这里在 UI 层直接拦截，提示用户改输入。 */
    private static boolean isValidFileName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String n = name.trim();
        if (n.equals(".") || n.equals("..")) return false;
        // Windows/常见文件系统非法字符：/ \ : * ? " < > |
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        // 尾点/尾空格：部分文件系统/SAF provider 会吞掉或报错（Windows 语义）
        if (n.endsWith(".") || n.endsWith(" ")) return false;
        return true;
    }

    /** 防自嵌套（参考 MiCode canMove）：源目录不能移动到自身或其子目录；文件不能移动到自己/父目录。
     *  支持真实路径与 SAF uri 两种形态（SAF 用解码 docId 路径前缀比较）。 */
    private static boolean isSelfNested(String src, String dest) {
        if (src == null || dest == null) return false;
        boolean srcSaf = SafStrategy.isSafUri(src);
        boolean dstSaf = SafStrategy.isSafUri(dest);
        if (srcSaf != dstSaf) return false; // 跨类型（真实↔SAF）不存在自嵌套
        String s1, s2;
        if (srcSaf) {
            s1 = SafStrategy.docPathOf(src);
            if (s1 == null) return false;
            // dest 可能是授权根 tree uri（无 /document/ 段）→ 转根 document uri 再取 docId
            String ds = dest;
            if (ds.indexOf("/document/") < 0) {
                String w = SafStrategy.writableParentUri(ds);
                if (w == null) return false;
                ds = w;
            }
            s2 = SafStrategy.docPathOf(ds);
            if (s2 == null) return false;
        } else {
            s1 = trimSlash(src);
            s2 = trimSlash(dest);
        }
        if (s2.equals(s1)) return true;
        return s2.startsWith(s1 + "/");
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 目标是否就是源所在目录（剪切到原地无意义，拦截防误操作；参考 MT 同目录粘贴行为） */
    private static boolean isSameParent(String src, String dest) {
        if (src == null || dest == null) return false;
        boolean srcSaf = SafStrategy.isSafUri(src);
        boolean dstSaf = SafStrategy.isSafUri(dest);
        if (srcSaf != dstSaf) return false;
        if (srcSaf) {
            String dp = SafStrategy.parentUriOf(src);
            if (dp == null) return false;
            if (dp.equals(dest)) return true;
            // dest 可能是授权根 tree uri（≠ 根 document uri 字符串）→ 转 document uri 再比
            String dw = SafStrategy.writableParentUri(dest);
            return dw != null && dw.equals(dp);
        }
        String parent = new File(src).getParent();
        return parent != null && trimSlash(parent).equals(trimSlash(dest));
    }

    /** 目标重名自动改名："name (1)"、"name (2)"…（参考 AnExplorer buildUniqueFile，防覆盖丢数据）。
     *  existing 为 null 时逐个 File.exists 检查（真实路径）；否则查名字集合（SAF 预列子项）。 */
    private static String uniqueName(String dest, String name, Set<String> existing) {
        if (!nameExists(dest, name, existing)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!nameExists(dest, cand, existing)) return cand;
        }
        return name; // 理论不可达（1000 个重名）
    }

    private static boolean nameExists(String dest, String name, Set<String> existing) {
        if (existing != null) return existing.contains(name);
        return new File(dest, name).exists();
    }

    private void updateClipBar() {
        if (clipInfo == null || clipBar == null) return;
        boolean has = !clipboard.isEmpty();
        clipBar.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) {
            clipInfo.setText("剪贴板：" + clipboard.size() + " 项（" + (clipCut ? "剪切" : "复制") + "）");
        }
    }

    private void updateOpBar() {
        // 只有文件模式才显示多选栏；终端/设置下即使 multiMode 也不能弹出来
        if (contentMode != ContentMode.FILES) {
            if (opBar != null) opBar.setVisibility(View.GONE);
            return;
        }
        if (opCount != null) {
            int n = active == null || active.selected == null ? 0 : active.selected.size();
            opCount.setText("已选 " + n + " 项");
        }

        if (opBar == null) return;
        opBar.setVisibility(multiMode ? View.VISIBLE : View.GONE);
        // 两栏高度差很大（opBar 约 100dp、bottomNav 56dp），
        // 切换后要按新可见栏的实际高度重算列表底部预留，
        // 否则多选时最后几行被 opBar 盖住。post 一帧等布局完成。
        if (opBar != null) opBar.post(this::updateListBottomReserve);
        if (bottomNav != null) bottomNav.post(this::updateListBottomReserve);
    }

    // ── 长按菜单（MT 风格固定菜单：所有功能可见，当前场景不可用的置灰禁用） ──

    private void showItemMenu(final Pane p, final FileEntry e, View anchor) {
        boolean virt = e.virtual;
        // SAF 授权条目（content://）可写（新���/重命名/删除/复制/剪切），与真实条目同菜单
        boolean saf = e.path != null && e.path.startsWith("content://");
        boolean real = !virt && !saf;
        boolean inTrash = p.path.startsWith(PREFIX_TRASH);
        boolean inCat = false;   // 分类功能已移除，恒 false
        boolean parent = e.parent;
        boolean zip = real && isZip(e);
        boolean writable = !parent && (real || saf) && !inTrash && !inCat;
        boolean realOnly = !parent && real && !inTrash && !inCat;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, e.name));

        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);

        // ── 按键定义：{文字, 图标, 是否可用, 点击动作} ──
        // 按需追加，行数由数量自动决定（每行 4 个）
        final List<Object[]> actions = new ArrayList<>();

        // 文件夹没有「打开方式」这个概念：目录不是可被外部应用打开的文件。
        // 此前只判 !parent，文件夹长按也会显示这个按钮，
        // 点下去走 case 里的 e.isDir() 分支变成「进入文件夹」——与按钮语义不符。
        actions.add(new Object[]{"打开方式", R.drawable.ic_file,
                !parent && !e.isDir(), (Runnable) () -> {
            dialog.dismiss();
            showOpenWithDialog(e.path, e.name,
                    e.path != null && e.path.startsWith("content://"));
        }});
        actions.add(new Object[]{"解到对侧", R.drawable.ic_archive,
                zip && isZipReadable(e), (Runnable) () -> {
            dialog.dismiss();
            extractZipEntryToOtherPane(e);
        }});
        actions.add(new Object[]{"编辑", R.drawable.ic_doc,
                realOnly && isTextFile(e.name), (Runnable) () -> {
                    dialog.dismiss();
                    openInEditorByName(e.path, e.name, false);
                }});
        actions.add(new Object[]{"复制", R.drawable.ic_copy, writable, (Runnable) () -> {
            dialog.dismiss();
            putClipboard(Collections.singletonList(e), false);
        }});
        actions.add(new Object[]{"剪切", R.drawable.ic_cut, writable, (Runnable) () -> {
            dialog.dismiss();
            putClipboard(Collections.singletonList(e), true);
        }});
        actions.add(new Object[]{"删除", R.drawable.ic_trash, writable, (Runnable) () -> {
            dialog.dismiss();
            confirmDelete(Collections.singletonList(e));
        }});
        actions.add(new Object[]{"重命名", R.drawable.ic_rename, writable, (Runnable) () -> {
            dialog.dismiss();
            showRenameDialog(e);
        }});
        actions.add(new Object[]{zip ? "解压" : "压缩",
                zip ? R.drawable.ic_archive : R.drawable.ic_compress,
                realOnly, (Runnable) () -> {
                    dialog.dismiss();
                    if (zip) extractZip(e); else compressToZip(e);
                }});
        actions.add(new Object[]{"属性", R.drawable.ic_info, !parent, (Runnable) () -> {
            dialog.dismiss();
            showPropertyDialog(e);
        }});
        actions.add(new Object[]{"加书签", R.drawable.ic_star_add, realOnly, (Runnable) () -> {
            dialog.dismiss();
            addBookmarkPath(e.path);
        }});
        actions.add(new Object[]{"多选", R.drawable.ic_checklist,
                !parent && !inCat, (Runnable) () -> {
                    dialog.dismiss();
                    p.selected.add(e);
                    enterMultiMode();
                }});
        if (inTrash) {
            actions.add(new Object[]{"恢复", R.drawable.ic_restore, !parent, (Runnable) () -> {
                dialog.dismiss();
                onItemMenu(p, e, "恢复");
            }});
            actions.add(new Object[]{"彻底删", R.drawable.ic_trash, !parent, (Runnable) () -> {
                dialog.dismiss();
                onItemMenu(p, e, "彻底删除");
            }});
        }

        // ── 按每行 4 个自动分行；行数随按键数量自适应 ──
        final int PER_ROW = 4;
        LinearLayout row = null;
        for (int i = 0; i < actions.size(); i++) {
            if (i % PER_ROW == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = dp(1);
                box.addView(row, rowLp);
            }
            Object[] a = actions.get(i);
            addGridItem(row, (String) a[0], (Integer) a[1],
                    (Boolean) a[2], v -> ((Runnable) a[3]).run());
            // 本行结束（下一个是新行或已是最后一个）→ 在**末尾**补空位，保证卡片等宽
            boolean rowEnd = (i % PER_ROW == PER_ROW - 1) || (i == actions.size() - 1);
            if (rowEnd) {
                int filled = i % PER_ROW + 1;
                for (int k = filled; k < PER_ROW; k++) {
                    View sp = new View(ctx);
                    LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                    int gap = dp(5) / 2;
                    spLp.setMargins(gap, gap, gap, gap);
                    row.addView(sp, spLp);
                }
            }
        }

        // 底部：取消
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(12);
        box.addView(btns, btnsLp);
        btns.addView(textButton("取消", v -> dialog.dismiss()));

        dialog.show();
    }

    /** 长按菜单动作分发（居中对话框版） */
    private void onItemMenu(final Pane p, final FileEntry e, String label) {
        // SAF 授权条目（content://）支持写操作（与真实条目同能力）
        boolean saf = e.path != null && e.path.startsWith("content://");
        boolean real = !e.virtual && !saf;
        switch (label) {
            case "打开方式":
                if (e.isDir()) {
                    enterDir(p, e);
                } else if (e.type == FileEntry.Type.LINK) {
                    openLink(p, e);
                } else if (e.type == FileEntry.Type.VIRTUAL_FILE && !saf) {
                    UiKit.toast(ctx, "虚拟条目，仅可查看信息");
                } else {
                    // ⭐ 长按菜单入口：**始终弹「打开方式」弹窗**，让用户自选内置或系统方式。
                    // 不能走 openFile/openSafFile —— 那两条是「单击」用的直连内置逻辑，
                    // 会在格式明确时直接打开，导致此入口失去「选择其他方式」的作用。
                    showOpenWithDialog(e.path, e.name, saf);
                }
                break;
            case "编辑":
                if (real && isTextFile(e.name)) openTextEditor(AccessRouter.fileOf(e));
                break;
            case "复制":
                if (!e.virtual || saf) putClipboard(Collections.singletonList(e), false);
                break;
            case "剪切":
                if (!e.virtual || saf) putClipboard(Collections.singletonList(e), true);
                break;
            case "删除":
                if (!e.virtual || saf) confirmDelete(Collections.singletonList(e));
                break;
            case "重命名":
                if (!e.virtual || saf) showRenameDialog(e);
                break;
            case "属性":
                showPropertyDialog(e);
                break;
            case "添加书签":
                addBookmarkPath(e.path);
                break;
            case "多选模式":
                p.selected.add(e);
                enterMultiMode();
                break;
            case "压缩":
                if (!e.virtual && !saf) compressToZip(e);
                break;
            case "解压":
                if (!e.virtual && !saf) extractZip(e);
                break;
            case "恢复":
                restoreFromTrash(e);
                break;
            case "彻底删除":
                // 回收站模式下删除 = 物理删除（不再二次进回收站）
                confirmDelete(Collections.singletonList(e));
                break;
        }
    }

    // ── 多选（作用于活跃窗格） ──

    private void enterMultiMode() {
        boolean wasMulti = multiMode;
        multiMode = true;
        for (Pane p : panes) p.adapter.setMultiMode(true);
        updateOpBar();
        // ⚠️ bottomNav 必须**立即**隐藏（同一帧），不能做淡出动画：
        // bottomNav 是 body(LinearLayout) 的子 View，只要它还在（哪怕正在淡出），
        // 就仍占着一行高度，锚在 contentWrap 底部的 opBar 被顶在上方；
        // 等它淡出结束腾出空间，opBar 再「落下来」→ 看到的就是
        // 「多选栏先弹到上面，再跟着下沉」。
        // 因此这里先硬切隐藏（多选栏随后从底部滑入，视觉上仍然连贯）。
        if (bottomNav != null) {
            bottomNav.animate().cancel();
            bottomNav.setAlpha(1f);
            bottomNav.setTranslationY(0f);
            bottomNav.setVisibility(View.GONE);
        }
        // 首次进入才播进场动画（后续勾选不重播）
        if (!wasMulti && opBar != null) {
            opBar.animate().cancel();
            opBar.setAlpha(0f);
            opBar.setTranslationY(dp(24));
            opBar.setVisibility(View.VISIBLE);
            // 同 bottomNav：用 OnPreDrawListener 保证首帧就是 alpha≈0
            final android.view.View opBarRef = opBar;
            opBar.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    android.view.ViewTreeObserver cur = opBarRef.getViewTreeObserver();
                    if (!cur.isAlive()) return true;
                    cur.removeOnPreDrawListener(this);
                    opBarRef.setAlpha(0f);
                    opBarRef.setTranslationY(dp(24));
                    opBarRef.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(220)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                    return true;
                }
            });
        }
        // ⭐10 Material 规范：多选模式下隐藏 FAB（底部操作栏接管操作）
        if (fabNew != null) fabNew.setVisibility(View.GONE);
    }

    private void exitMultiMode() {
        exitMultiMode(false);
    }

    /**
     * 退出多选。
     *
     * @param instant true = 跳过退场动画，当场完成切换。
     *
     * <p><b>为什么要 instant</b>：{@code reload()} 里翻目录时会退出多选，
     * 旧实现播放 160ms 的 opBar 退场动画 + fab 淡入，而 reload 紧接着就
     * 把新目录数据 bind 上去了 —— 结果是「功能栏还没退完，新内容已经画出」，
     * 两个动画窗口重叠，观感是底部栏莫名闪一下。
     *
     * <p>翻目录属于「同一 surface 换数据」，底部栏的进出不该和内容过渡
     * 抢时间窗口。直接切换完，剩下的过渡交给 {@link #playDirTransition}。</p>
     */
    private void exitMultiMode(boolean instant) {
        multiMode = false;
        if (instant) {
            // 一次性把布局落到「已完成退出」的状态，不播任何动画
            if (opBar != null) {
                opBar.animate().cancel();
                opBar.setVisibility(View.GONE);
                opBar.setTranslationY(0f);
                opBar.setAlpha(1f);
            }
            if (bottomNav != null) {
                bottomNav.animate().cancel();
                bottomNav.setAlpha(1f);
                bottomNav.setTranslationY(0f);
                if (contentMode == ContentMode.FILES) {
                    bottomNav.setVisibility(View.VISIBLE);
                }
            }
            if (fabNew != null) {
                fabNew.animate().cancel();
                fabNew.setAlpha(1f);
                if (contentMode == ContentMode.FILES) {
                    fabNew.setVisibility(View.VISIBLE);
                }
            }
            // 选择态清理由 reload 自己负责（它已经 p.selected.clear()）
            for (Pane px : panes) {
                if (px != null && px.adapter != null) px.adapter.setMultiMode(false);
            }
            return;
        }
        final boolean animating = opBar != null && opBar.getVisibility() == View.VISIBLE;
        if (animating) {
            // ⚠️ 关键：bottomNav 不能在动画前恢复。
            // bottomNav 是 body(LinearLayout) 的子 View，设为 VISIBLE 会立刻
            // 占用一行高度，把 contentWrap(weight=1) 压扁，锚在底部的 opBar
            // 随之被顶到上方，形成「先弹上去再向下退场」的跳变。
            // 因此：先播放位移动画（此时布局完全不变），
            // 在动画结束的同一帧里再一次性完成所有布局变更。
            opBar.animate().translationY(dp(24)).alpha(0f).setDuration(160)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        opBar.setVisibility(View.GONE);
                        opBar.setTranslationY(0f);
                        opBar.setAlpha(1f);
                        clearSelectionAndRefresh();
                        // bottomNav 改「瞬间出现」为「淡入 + 上移过渡」，
                        // 与 opBar 退场交叉衔接，避免生硬的跳变
                        showBottomNavAnimated();
                        if (fabNew != null) {
                            fabNew.setAlpha(0f);
                            fabNew.setVisibility(View.VISIBLE);
                            fabNew.animate().alpha(1f).setDuration(180).start();
                        }
                    }).start();
        } else {
            showBottomNavAnimated();
            if (fabNew != null) fabNew.setVisibility(View.VISIBLE);
            clearSelectionAndRefresh();
        }
    }

    /** bottomNav 以「淡入 + 上移」出现（退出多选后的过渡） */
    private void showBottomNavAnimated() {
        if (bottomNav == null) return;
        bottomNav.animate().cancel();
        bottomNav.setAlpha(0f);
        bottomNav.setTranslationY(dp(16));
        bottomNav.setVisibility(View.VISIBLE);

        // ⚠️ 用 OnPreDrawListener 而非 post()：
        // setVisibility(VISIBLE) 会触发一次完整布局，布局会把 alpha/translationY
        // 复位；而 post() 的回调可能在「布局完成后、绘制前」之外执行，
        // 导致至少有一帧以 alpha=1 全白绘制出来（用户看到的「白色遮罩闪一下」）。
        // OnPreDrawListener 在**绘制前**最后时刻执行：先补设初始值，再启动动画，
        // 保证第一帧就是 alpha≈0，不会露出不透明背景。
        final android.view.ViewTreeObserver vto = bottomNav.getViewTreeObserver();
        final android.view.ViewTreeObserver.OnPreDrawListener[] holder =
                new android.view.ViewTreeObserver.OnPreDrawListener[1];
        holder[0] = new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                android.view.ViewTreeObserver cur = bottomNav.getViewTreeObserver();
                if (!cur.isAlive()) return true;
                cur.removeOnPreDrawListener(this);
                bottomNav.setAlpha(0f);
                bottomNav.setTranslationY(dp(16));
                bottomNav.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(240)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                return true;   // 不取消这一帧绘制
            }
        };
        vto.addOnPreDrawListener(holder[0]);
    }

    /** 清空选中并让列表回到非多选外观（退出多选的收尾动作） */
    private void clearSelectionAndRefresh() {
        for (Pane p : panes) {
            p.selected.clear();
            p.adapter.setMultiMode(false);
        }
        if (opCount != null) opCount.setText("已选 0 项");
    }

    private void selectAll() {
        active.selected.clear();
        for (FileEntry e : active.entries) {
            // ".."强制条目不参与多选
            if (!e.parent) active.selected.add(e);
        }
        active.adapter.setSelected(active.selected);
        updateOpBar();
    }

    private void deleteSelected() {
        if (active.selected.isEmpty()) {
            UiKit.toast(ctx, "未选择文件");
            return;
        }
        confirmDelete(new ArrayList<>(active.selected));
    }

    private void clipboardCopy(boolean cut) {
        if (active.selected.isEmpty()) {
            UiKit.toast(ctx, "未选择文件");
            return;
        }
        putClipboard(new ArrayList<>(active.selected), cut);
        exitMultiMode();
    }

    // ── 剪贴板（路径字符串；虚拟条目过滤不入板） ──

    private void putClipboard(List<FileEntry> list, boolean cut) {
        List<FileEntry> real = new ArrayList<>();
        int skipped = 0;
        for (FileEntry e : list) {
            // SAF 授权条目（content://）可复制/剪切；仅纯虚拟条目过滤
            boolean saf = e.path != null && e.path.startsWith("content://");
            if (e.virtual && !saf) skipped++;
            else real.add(e);
        }
        if (real.isEmpty()) {
            UiKit.toast(ctx, "虚拟条目不支持复制/剪切");
            return;
        }
        clipboard.clear();
        for (FileEntry e : real) clipboard.add(e.path);
        clipCut = cut;
        updateClipBar();
        UiKit.toast(ctx, "已" + (cut ? "剪切" : "复制") + " " + real.size() + " 项"
                + (skipped > 0 ? "（跳过 " + skipped + " 个虚拟条目）" : "") + "，请到目标窗格粘贴");
    }

    private void clearClipboard() {
        clipboard.clear();
        clipCut = false;
        updateClipBar();
    }


    /** 异常转可读文案：getMessage() 常为 null，直接拼接会显示「失败：null」 */
    private static String msgOf(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m != null && !m.trim().isEmpty()) return m;
        return t.getClass().getSimpleName();
    }

    private void pasteClipboard() {
        if (clipboard.isEmpty()) {
            updateClipBar();
            return;
        }
        final List<String> items = new ArrayList<>(clipboard);
        final boolean cut = clipCut;
        final String dest = active.path; // 目标：真实路径或 content:// uri
        new Thread(() -> {
            int ok = 0;
            StringBuilder err = new StringBuilder();
            // 剪切：仅复制成功的 SAF 源才删除（避免失败丢数据）
            final List<String> toDelete = new ArrayList<>();
            // ⭐6 重名自动改名（参考 AnExplorer buildUniqueFile）：SAF 目标先预列子项名
            // 集合（避免逐项查询），粘贴重名时自动 "name (1)"，防覆盖丢数据
            Set<String> existing = null;
            if (SafStrategy.isSafUri(dest)) {
                existing = new HashSet<>();
                try {
                    for (String[] c : SafStrategy.listChildren(ctx, dest)) existing.add(c[1]);
                } catch (Throwable ignored) {
                }
            }
            for (String p : items) {
                try {
                    // ⭐7 防自嵌套（参考 MiCode canMove）：剪切时源不能是目标的祖先/自身
                    //（目录移入自己子目录 → 无限递归；文件移到自己父目录 → 原地粘贴）
                    if (cut && (isSelfNested(p, dest) || isSameParent(p, dest))) {
                        err.append(baseNameOf(p)).append("（不能移动到自身/子目录） ");
                        continue;
                    }
                    boolean srcSaf = SafStrategy.isSafUri(p);
                    boolean dstSaf = SafStrategy.isSafUri(dest);
                    if (srcSaf || dstSaf) {
                        // SAF 参与的组合（SAF→真实 / 真实→SAF / SAF→SAF）
                        String name = uniqueName(dest, baseNameOf(p), existing);
                        boolean okOp;
                        if (cut && srcSaf && dstSaf) {
                            // SAF→SAF 剪切：走 moveInto 三策略（同父 rename / 同 provider moveDocument /
                            // 复制+删源），内部处理删源，不再进 toDelete
                            okOp = SafStrategy.moveInto(ctx, p, dest, name, err);
                        } else {
                            okOp = SafStrategy.copyInto(ctx, p, dest, name, err);
                            if (okOp && cut && srcSaf) toDelete.add(p);
                        }
                        if (okOp) {
                            ok++;
                        } else {
                            err.append(baseNameOf(p)).append(' ');
                        }
                    } else if (needRoot(p) || needRoot(dest)) {
                        String dst = new File(dest, baseNameOf(p)).getAbsolutePath();
                        if (copyOrMoveSync(p, dst, cut)) {
                            ok++;
                        } else {
                            err.append(baseNameOf(p)).append(' ');
                        }
                    } else {
                        File f = new File(p);
                        if (f.exists()) {
                            File destFile = new File(dest, uniqueName(dest, f.getName(), null));
                            if (cut) {
                                if (!f.renameTo(destFile)) {
                                    FileOps.copy(f, destFile);
                                    deleteTree(f);
                                }
                            } else {
                                FileOps.copy(f, destFile);
                            }
                            ok++;
                        }
                    }
                } catch (Throwable t) {
                    err.append(baseNameOf(p)).append(' ');
                }
            }
            // 剪切：复制成功后删除 SAF 源（DocumentsContract.deleteDocument）
            for (String p : toDelete) {
                try {
                    SafStrategy.deleteDocument(ctx, p);
                } catch (Throwable ignored) {
                }
            }
            final int fOk = ok;
            final String fErr = err.toString();
            handler.post(() -> {
                // 剪切粘贴成功后清空剪贴板；复制保留（可继续粘贴到多个目录）
                if (cut) clearClipboard();
                updateClipBar();
                // 刷新全部窗格：移动时源窗格（非活跃）也必须同步移除已移走的条目
                reloadAll();
                UiKit.toast(ctx, "粘贴完成：" + fOk + "/" + items.size()
                        + (fErr.isEmpty() ? "" : "，失败：" + fErr.trim()));
            });
        }, "HyVqFilePaste").start();
    }

    /** 剪贴板条目显示名：真实路径取末段；SAF document uri 解码 docId 取末段 */
    private static String baseNameOf(String p) {
        if (p == null || p.isEmpty()) return "";
        String s = p;
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        int i = s.lastIndexOf('/');
        String name = i < 0 ? s : s.substring(i + 1);
        // SAF document uri 末段是编码 docId（%2F...），解码后取文件名
        if (name.indexOf('%') >= 0) {
            String dec = Uri.decode(name);
            if (dec != null) {
                int sl = dec.lastIndexOf('/');
                name = sl >= 0 ? dec.substring(sl + 1) : dec;
            }
        }
        return name;
    }

    // ── 对话框 ──

    // ── 新建菜单：FAB 向上展示两个选项（新建文件 / 新建文件夹） ──

    /** ⭐10 FAB 点击：向上弹出「新建文件 / 新建文件夹」两选项（PopupWindow 锚定 FAB 上方） */
    private void showNewMenu(View anchor) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int surface = UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int stroke = UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant);
        android.graphics.drawable.GradientDrawable gd = UiKit.rounded(ctx, 14,
                (surface & 0x00FFFFFF) | 0xF2000000, stroke);
        panel.setBackground(gd);
        int p4 = dp(4);
        panel.setPadding(p4, p4, p4, p4);

        final PopupWindow popup = trackPopup(new PopupWindow(panel,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true));
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(dp(8));

        addMenuRow(panel, "新建文件", R.drawable.ic_file, v -> {
            popup.dismiss();
            showNewFileDialog();
        });
        addMenuRow(panel, "新建文件夹", R.drawable.ic_folder, v -> {
            popup.dismiss();
            showNewFolderDialog();
        });
        // ⭐10 向上展示：FAB 在右下角，向下弹会出屏——showAtLocation 锚定屏幕右下角上方
        //（面板高约 2 行 × 40dp + padding，偏移 dp(12) 右缘留边、dp(96) 抬升到 FAB 上方）
        popup.showAtLocation(anchor, Gravity.BOTTOM | Gravity.END, dp(12), dp(96));
    }

    /** 新建菜单行：图标 + 文字（圆角行）；enabled=false 时整行置灰禁用（MT 风格：不可用项保持可见但灰色） */
    /**
     * 长按菜单的网格项（图标上、文字下）。
     *
     * <p>自适应列数：容器为横向 LinearLayout，每格 weight=1，满 4 格自动换行。
     * 调用方用 {@link #itemMenuGridRow} 创建行容器。</p>
     *
     * @param grid  当前行容器
     * @param text  显示文字
     * @param icon  图标资源
     * @param enabled false 时整格置灰（保留可见，提示功能存在但当前不可用）
     */
    /**
     * 长按菜单卡片式按键：独立圆角卡片（背景 + 描边），图标在上、文字在下。
     *
     * <p>外层包一层 margin 容器，卡片之间留 5dp 间隙，形成明显的卡片分离感。</p>
     */
    private void addGridItem(LinearLayout grid, String text, int iconRes,
                             boolean enabled, View.OnClickListener onClick) {
        // ── 外层：负责间距 ──
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int gap = dp(5) / 2;
        wrapLp.setMargins(gap, gap, gap, gap);

        // ── 卡片本体 ──
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(4), dp(14), dp(4), dp(12));
        card.setMinimumHeight(dp(78));
        card.setClickable(enabled);
        card.setFocusable(enabled);
        if (enabled) {
            // 启用态：ripple 提供按下泛光
            card.setBackgroundResource(R.drawable.bg_menu_card);
        } else {
            // ⚠️ 禁用态**不能用** bg_menu_card —— 它是 <ripple>，
            // 而 ripple 的按下反馈由 View 的 Pressed 状态驱动，
            // 与 setClickable(false) 无关。用了就会出现「禁用项反而有动效」。
            // 换成静态圆角底 + 降不透明度，明确传达「不可用」。
            card.setAlpha(0.35f);
            card.setBackground(UiKit.rounded(ctx, dp(14), 0x14FFFFFF, 0x26FFFFFF));
        }
        if (enabled) {
            // ⚠️ 反馈要「按下就走」，不能等 onClick：
            // 旧实现把缩放放在 onClickListener 里，而 onClick 的第一句
            // 往往是 dialog.dismiss() —— 弹窗一关 View 就没了，
            // 80ms 的缩小 + 140ms 回弹用户根本看不到。
            // 改为 onTouch 的 ACTION_DOWN 立即缩小，ACTION_UP 回弹后
            // 再延迟极短时间执行动作，让回弹有个起手。
            card.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate().scaleX(0.94f).scaleY(0.94f)
                                .setDuration(80).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(140)
                                .setInterpolator(
                                        new android.view.animation.OvershootInterpolator(2f))
                                .start();
                        break;
                    default:
                        break;
                }
                return false;   // 不消费，交给 OnClickListener 正常走点击判定
            });
            card.setOnClickListener(v -> {
                // 回弹已由 ACTION_UP 启动；这里只负责触发动作。
                // 用 postDelayed 让回弹先有形（60ms 约 4 帧），
                // 再 dismiss，避免「点了但什么都没看到」。
                v.postDelayed(() -> onClick.onClick(v), 60);
            });
        }
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(UiKit.color(ctx, enabled
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOutline));
        card.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setLineSpacing(0, 0.95f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(7);
        card.addView(label, labelLp);

        grid.addView(wrap, wrapLp);
    }

    /**
     * 创建长按菜单的一行（横向，最多 4 格）。
     *
     * @param box   菜单根容器（Vertical LinearLayout）
     * @param items 本行项数，不足 4 时右侧补空占位保持等宽
     */
    private LinearLayout itemMenuGridRow(LinearLayout box, int items) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(2);
        box.addView(row, rowLp);
        // 按屏幕方向补空占位：横屏 4 列 / 竖屏 3 列（与「打开方式」同一规则）
        int cols = openGridColumns();
        for (int i = items; i < cols; i++) {
            View sp = new View(ctx);
            row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        }
        return row;
    }

    private void addMenuRow(LinearLayout panel, String text, int iconRes, boolean enabled, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(10);
        row.setPadding(p, p, p, p);
        // 禁用态：不可点击 + 整行半透明灰（保留可见性，提示"功能存在但当前场景不可用"）
        row.setClickable(enabled);
        if (enabled) {
            row.setOnClickListener(onClick);
            // 按压回弹
            row.setOnTouchListener((v, ev) -> {
                if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                        || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }
                return false;
            });
        }
        row.setAlpha(enabled ? 1f : 0.38f);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        int s = dp(20);
        row.addView(icon, new LinearLayout.LayoutParams(s, s));

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.setMarginStart(dp(8));
        row.addView(tv, tvLp);
        panel.addView(row);
    }

    /** 新建菜单行（默认可用）：图标 + 文字（圆角行） */
    private void addMenuRow(LinearLayout panel, String text, int iconRes, View.OnClickListener onClick) {
        addMenuRow(panel, text, iconRes, true, onClick);
    }

    /** 新建文件对话框：单一输入框直接输完整文件名（含后缀，用户需求 2026-08-16） */
    private void showNewFileDialog() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "新建文件"));

        final EditText nameInput = new EditText(ctx);
        nameInput.setHint("文件名（含后缀，如 test.txt）");
        nameInput.setSingleLine(true);
        box.addView(nameInput);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("取消", v -> dialog.dismiss()));
        btns.addView(textButton("创建", v -> {
            String full = nameInput.getText().toString().trim();
            // ⭐5 文件名合法性校验（参考 Fossify isAValidFilename）：非法字符直接拦截，
            // 不再静默净化后创建（避免 SAF provider 吞字符/路径穿越的隐性问题）
            if (!isValidFileName(full)) {
                UiKit.alert(ctx, "文件名不合法（含 / \\ : * ? \" < > | 或以点结尾）");
                return;
            }
            dialog.dismiss();
            if (SafStrategy.isSafUri(active.path)) {
                // SAF 授权目录：DocumentsContract.createDocument（对齐 MT 可新建）
                new Thread(() -> {
                    String uri = SafStrategy.createDocument(ctx, active.path, full, false);
                    final String msg = uri != null ? "已创建文件：" + full : "创建失败（无写入权限？）";
                    handler.post(() -> {
                        UiKit.toast(ctx, msg);
                        reload(active);
                    });
                }, "HyVqSafCreate").start();
            } else {
                FileOps.createFileAsync(new File(active.path), full, (ok, msg) -> {
                    UiKit.toast(ctx, msg);
                    reload(active);
                });
            }
        }));
        dialog.show();
    }

    // ── 内部小侧边栏：层级导航 + SAF 挂载目录（对齐 MT 管理器三条杠导航） ──


    /** 侧边栏书签区（仿 ZhuFiler NavigationView 书签组）：分组标题 + 书签行。
     *  点击进入目录（record navPrev 供返回）、长按删除；空态提示用 ⋮ 菜单收藏。 */
    private void buildBookmarkSection(LinearLayout content) {
        final java.util.Set<String> set = new java.util.LinkedHashSet<>(
                fmPrefs().getStringSet("bookmarks", new java.util.HashSet<String>()));
        // 分组标题（与挂载区风格一致）
        TextView head = new TextView(ctx);
        head.setText("书签（" + set.size() + "）");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = dp(8);
        headLp.bottomMargin = dp(2);
        headLp.leftMargin = dp(4);
        content.addView(head, headLp);

        if (set.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无书签（⋮菜单可收藏当前目录）");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
            empty.setAlpha(0.6f);
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyLp.leftMargin = dp(4);
            emptyLp.bottomMargin = dp(4);
            content.addView(empty, emptyLp);
            return;
        }

        for (final String path : set) {
            final File f = AccessRouter.fileOf(path);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(6), dp(6));
            row.setClickable(true);
            // 书签卡片：圆角 10dp + 主题色 + 轻描边(参考 Her 卡片风格)
            {
                int rowBg = path.equals(active.path)
                        ? UiKit.color(ctx, com.google.android.material.R.attr.colorPrimaryContainer)
                        : UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh);
                android.graphics.drawable.GradientDrawable g = UiKit.rounded(ctx, 0, rowBg, 0);
                g.setCornerRadii(new float[]{dp(14), dp(14), dp(14), dp(14), dp(14), dp(14), dp(14), dp(14)});
                g.setStroke(dp(1), UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant));
                row.setBackground(g);
            }
            row.setOnClickListener(v -> {
                closeNavSidebar();
                active.navPrev = active.path;
                active.path = path;
                reload(active);
            });
            // 长按删除书签（弹确认，防误删）
            row.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(ctx)
                        .setTitle("删除书签")
                        .setMessage("删除书签：" + path + "？")
                        .setPositiveButton("删除", (d, w) -> {
                            set.remove(path);
                            fmPrefs().edit().putStringSet("bookmarks", set).commit();
                            UiKit.toast(ctx, "已删除书签");
                            rebuildNavContent();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(f.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file);
            icon.setColorFilter(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            int s = dp(16);
            row.addView(icon, new LinearLayout.LayoutParams(s, s));
            TextView tv = new TextView(ctx);
            String name = f.getName();
            tv.setText(name == null || name.isEmpty() ? path : name);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tvLp.leftMargin = dp(6);
            row.addView(tv, tvLp);
            content.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 书签卡片间距
            View spacer = new View(ctx);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
            content.addView(spacer, sp);
        }
    }

    /** 左上角三条杠（汉堡）点击：弹出内部小侧边栏（覆盖模块区域，不遮全局导航栏） */
    private void showNavSidebar() {
        if (navOverlay == null || navPanel == null || rootContainer == null) return;
        rebuildNavContent();

        // 真毛玻璃：Android 12+ 对 body(文件列表)施加模糊，透过磨砂面板看到虚化内容(同外部侧边栏)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ViewGroup blurTarget = rootContainer;
            // 模糊 body 而非 navOverlay 本身。
            // 必须同时判 instanceof：背景图 ImageView 也被插在 rootContainer 里，
            // 只判 c != navOverlay 会第一个命中它，强转 ViewGroup 直接崩。
            for (int i = 0; i < rootContainer.getChildCount(); i++) {
                View c = rootContainer.getChildAt(i);
                if (c != navOverlay && c instanceof ViewGroup) {
                    blurTarget = (ViewGroup) c;
                    break;
                }
            }
            try {
                blurTarget.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {}
        }

        // 显示叠加层：同外层 DrawerLayout 逻辑——先置初始状态（无闪烁帧）再显示，
        // 面板左滑入 + 遮罩淡入同步（约 250ms，对齐外层侧边栏系统动画时长）
        navPanel.setTranslationX(-navPanelWidth - dp(20));
        navScrim.setAlpha(0f);
        navOverlay.setVisibility(View.VISIBLE);
        navPanel.animate().translationX(0f).setDuration(250).start();
        navScrim.animate().alpha(1f).setDuration(250).start();
    }

    /** 动态重建侧边栏内容：路径+挂载合并为同一列表（根目录/内部存储/SAF授权目录，
     *  可长按三条杠拖动排序、SAF 授权项可左滑删除；SAF 授权完成后也会调用刷新） */
    // ══════════════════════════════════════════════════════════════
    //  侧边栏头部（HY_VQ 同款）：头像 + 标题/副标题，下方接存储空间条
    // ══════════════════════════════════════════════════════════════

    /**
     * 侧边栏底部固定的「设置」入口。
     *
     * <p>与上方滚动区分离，始终可见。图标 + 文字 + 右箭头，点击打开设置页。</p>
     */
    private void buildSidebarSettingsEntry() {
        // 顶部细分隔线
        View divider = new View(ctx);
        divider.setBackgroundColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        navPanel.addView(divider, dvLp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(12), dp(6), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_ripple_round);
        row.setOnClickListener(v -> {
            // 必须等侧边栏真正隐藏后再弹设置页：
            // 旧代码 closeNavSidebar() 只启动 200ms 动画就立刻 showSettingsPage()，
            // 设置对话框的模糊遮罩会把仍在收起的侧边栏一起模糊掉，
            // 表现为「透过模糊能看到侧边栏在退出，但它其实还没退干净」。
            closeNavSidebarThen(this::showSettingsPage);
        });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        navPanel.addView(row, rowLp);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_settings);
        icon.setColorFilter(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView label = new TextView(ctx);
        label.setText("设置");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lbLp.leftMargin = dp(12);
        row.addView(label, lbLp);
    }

    /**
     * 设置页（底部弹出面板）。
     *
     * <p>当前两项：权限管理、文件管理。后续新设置项在此追加即可。</p>
     */
    private View buildSettingsRoot() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        // 不在这里再放一行「返回文件列表」：
        // 共享顶栏的返回键已经能回文件列表，两者功能完全相同。
        // 保留共享顶栏那个（所有设置子页都靠它），删掉这个页内重复的。
        // ── 基础设置 ──
        box.addView(sectionHeaderInCard("基础设置"));
        addSettingsRow(box, "主题设置",
                "背景图 · 卡片透明度 · 界面主题 · 显示密度",
                R.drawable.ic_sliders, v -> {
                    showInSettingsDialog("主题设置", this::buildAppearanceSettings);
                });
        addSettingsRow(box, "存储管理",
                "存储占用明细 · 缓存与回收站清理",
                R.drawable.ic_pie_chart, v -> {
                    showInSettingsDialog("存储管理", this::buildStorageManager);
                });

        // ── 工具 ──
        box.addView(sectionHeaderInCard("工具"));
        addSettingsRow(box, "文件管理",
                "隐藏文件 · 缩略图 · 排序方式", R.drawable.ic_folder, v -> {
                    showInSettingsDialog("文件管理", this::buildFileSettings);
                });
        addSettingsRow(box, "图片查看器",
                "相册扫描路径管理", R.drawable.ic_img, v -> {
                    showGallerySettings();
                });

        // ── 软件 ──
        box.addView(sectionHeaderInCard("软件"));
        addSettingsRow(box, "权限管理",
                "所有文件访问 · 存储权限 · SAF 授权", R.drawable.ic_lock, v -> {
                    showInSettingsDialog("权限管理", this::buildPermissionManager);
                });
        addSettingsRow(box, "软件更新",
                "当前 " + appVersionName() + " · 检查是否有新版本",
                R.drawable.ic_refresh, v -> {
                    showInSettingsDialog("软件更新", this::showUpdatePageBody);
                });
        addSettingsRow(box, "问题反馈",
                "崩溃 · 建议 · 其他（无需注册账号）", R.drawable.ic_info, v -> {
                    showFeedbackDialog();
                });
        addSettingsRow(box, "关于",
                "版本信息 · 开源仓库 · 开发说明", R.drawable.ic_info, v -> {
                    showInSettingsDialog("关于", this::showAboutPageBody);
                });
        return box;
    }

    // ══════════ 问题反馈 ══════════

    /**
     * 问题反馈弹窗：类型选择 + 内容输入 + 提交。
     *
     * <p>不需要用户注册账号——内置一枚<b>权限受限</b>的 GitHub token 代提交
     * （fine-grained PAT，只授权本仓库 issues 写，90 天过期）。
     * 详见 {@link com.aliya.hyfilemgr.feedback.FeedbackSender} 的安全说明。</p>
     *
     * <p>提交失败必须有退路：弹窗展示完整内容 + 「复制」+ 「网页提交」，
     * 否则弱网/限流时用户白写一遂。</p>
     */
    private void showFeedbackDialog() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("问题反馈"));

        TextView hint = new TextView(ctx);
        hint.setText("不需要注册账号，直接提交到开发者的 GitHub Issues。\n"
                + "会自动附带应用版本、机型与系统版本（不含任何文件内容）。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        hint.setLineSpacing(dp(2), 1f);
        box.addView(hint);

        // 类型选择：三个可点的胶囊
        final int[] sel = {com.aliya.hyfilemgr.feedback.FeedbackSender.TYPE_OTHER};
        String[] names = {"崩溃", "功能建议", "其他"};
        int[] types = {com.aliya.hyfilemgr.feedback.FeedbackSender.TYPE_CRASH,
                com.aliya.hyfilemgr.feedback.FeedbackSender.TYPE_SUGGESTION,
                com.aliya.hyfilemgr.feedback.FeedbackSender.TYPE_OTHER};
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(2), 0, dp(2), dp(10));
        box.addView(chips);

        final TextView[] chipViews = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView chip = new TextView(ctx);
            chip.setText(names[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setClickable(true);
            chipViews[i] = chip;
            chip.setOnClickListener(v -> {
                sel[0] = types[idx];
                for (int j = 0; j < chipViews.length; j++) styleFeedbackChip(chipViews[j], j == idx);
            });
            chips.addView(chip);
        }
        // 默认选「其他」
        for (int j = 0; j < chipViews.length; j++) styleFeedbackChip(chipViews[j], j == 2);

        // 内容输入
        final android.widget.EditText input = new android.widget.EditText(ctx);
        input.setHint("请描述遇到的问题或建议…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        input.setHintTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        input.setBackground(UiKit.rounded(ctx, 10,
                UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow),
                UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant)));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.bottomMargin = dp(10);
        box.addView(input, inLp);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        TextView cancel = textButton("取消", v -> UiKit.dismissWithAnim(d));
        final TextView submit = textButton("提交", null);
        submit.setOnClickListener(v -> {
            final String text = input.getText() == null ? "" : input.getText().toString().trim();
            if (text.isEmpty()) {
                UiKit.toast(ctx, "请先填写内容");
                return;
            }
            submit.setEnabled(false);
            submit.setAlpha(0.5f);
            submit.setText("提交中…");
            new Thread(() -> {
                final com.aliya.hyfilemgr.feedback.FeedbackSender.Result r =
                        com.aliya.hyfilemgr.feedback.FeedbackSender.submit(ctx, sel[0], text);
                handler.post(() -> {
                    if (detached) return;
                    submit.setEnabled(true);
                    submit.setAlpha(1f);
                    submit.setText("提交");
                    if (r.ok) {
                        UiKit.dismissWithAnim(d);
                        UiKit.toast(ctx, "已提交，编号 #" + r.issueNumber + "，感谢反馈！");
                    } else {
                        showFeedbackFailDialog(text, r.message);
                    }
                });
            }, "HyFeedback").start();
        });
        btns.addView(cancel);
        btns.addView(submit);
        box.addView(btns);
        d.show();
    }

    /** 反馈类型胶囊样式（选中=主题色填充，未选=卡片底+描边） */
    private void styleFeedbackChip(TextView chip, boolean active) {
        chip.setTextColor(UiKit.color(ctx, active
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        chip.setBackground(UiKit.rounded(ctx, 999,
                UiKit.color(ctx, active
                        ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorSurfaceContainerHigh),
                active ? 0 : UiKit.color(ctx,
                        com.google.android.material.R.attr.colorOutlineVariant)));
    }

    /** 提交失败的退路：展示内容 + 复制 + 网页提交 */
    private void showFeedbackFailDialog(final String text, String reason) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("⚠ 提交失败"));

        TextView why = new TextView(ctx);
        why.setText(reason + "\n\n以下是你的内容，可以复制后到网页提交：");
        why.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        why.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorError));
        why.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(why);

        TextView content = new TextView(ctx);
        content.setText(text);
        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        content.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        content.setPadding(dp(10), dp(8), dp(10), dp(8));
        content.setBackground(UiKit.rounded(ctx, 10,
                UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow),
                UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant)));
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.addView(content);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.bottomMargin = dp(10);
        box.addView(sv, svLp);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        btns.addView(textButton("复制", v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("反馈内容", text));
            UiKit.toast(ctx, "已复制到剪贴板");
        }));
        btns.addView(textButton("网页提交", v -> {
            com.aliya.hyfilemgr.feedback.FeedbackSender.openWebFallback(ctx, text);
        }));
        btns.addView(textButton("关闭", v -> UiKit.dismissWithAnim(d)));
        box.addView(btns);
        d.show();
    }

    // ══════════ 相册浏览页 ══════════

    /** 相册扫描路径持久化键（换行分隔） */
    private static final String PREF_GALLERY_PATHS = "gallery_scan_paths";
    /** 相册页视图（与 settingsEmbedView 同级，动态创建） */
    private LinearLayout galleryEmbedView;
    /** 网格内容容器（切扫描路径/重新扫描时重建） */
    private LinearLayout galleryContent;
    /** 扫描路径列表（内存态，改后落盘） */
    private java.util.ArrayList<String> galleryScanPaths;
    /** 网格当前展示的目录（点图进查看器时传图集用） */
    private String galleryCurDir;

    /** 默认扫描路径（存在才进列表；首次启动初始化用） */
    private static String[] defaultGalleryPaths() {
        String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        return new String[]{
                root + "/DCIM/Camera", root + "/DCIM",
                root + "/Pictures", root + "/Pictures/Screenshots",
                root + "/Download"};
    }

    /** 读扫描路径（无持久化数据时初始化默认值并落盘） */
    private java.util.ArrayList<String> loadGalleryPaths() {
        if (galleryScanPaths != null) return galleryScanPaths;
        galleryScanPaths = new java.util.ArrayList<>();
        String saved = fmPrefs().getString(PREF_GALLERY_PATHS, "");
        if (saved.isEmpty()) {
            for (String p : defaultGalleryPaths()) {
                if (new File(p).isDirectory()) galleryScanPaths.add(p);
            }
            saveGalleryPaths();
        } else {
            for (String p : saved.split("\n")) {
                if (!p.trim().isEmpty()) galleryScanPaths.add(p.trim());
            }
        }
        return galleryScanPaths;
    }

    private void saveGalleryPaths() {
        if (galleryScanPaths == null) return;
        fmPrefs().edit().putString(PREF_GALLERY_PATHS,
                android.text.TextUtils.join("\n", galleryScanPaths)).apply();
    }

    /** 侧边栏/设置页入口：打开相册页（首个扫描路径） */
    private void openGallery() {
        java.util.ArrayList<String> paths = loadGalleryPaths();
        String initial = paths.isEmpty()
                ? android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
                : paths.get(0);
        if (galleryEmbedView == null) {
            buildGalleryView();
            contentWrapRef.addView(galleryEmbedView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        scanGallery(initial);
        setContentMode(ContentMode.GALLERY);
    }

    /** 相册页骨架：路径 chips 行 + 网格区（滚动容器） */
    private void buildGalleryView() {
        galleryEmbedView = new LinearLayout(ctx);
        galleryEmbedView.setOrientation(LinearLayout.VERTICAL);
        galleryEmbedView.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        // 路径 chips：横向滚动，点击切换扫描目录
        android.widget.HorizontalScrollView chipsScroll = new android.widget.HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(10), dp(8), dp(10), dp(2));
        chipsScroll.addView(chips, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        galleryEmbedView.addView(chipsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        galleryChipsRow = chips;

        // 网格滚动容器
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.setFillViewport(true);
        galleryContent = new LinearLayout(ctx);
        galleryContent.setOrientation(LinearLayout.VERTICAL);
        galleryContent.setPadding(dp(8), dp(4), dp(8), dp(12));
        sv.addView(galleryContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        galleryEmbedView.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private LinearLayout galleryChipsRow;

    /** 刷新路径 chips 行 */
    private void refreshGalleryChips(final String current) {
        galleryChipsRow.removeAllViews();
        for (final String p : loadGalleryPaths()) {
            TextView chip = new TextView(ctx);
            chip.setText(new File(p).getName());
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((LinearLayout.LayoutParams) chip.getLayoutParams()).setMargins(0, 0, dp(6), 0);
            boolean active = p.equals(current);
            chip.setTextColor(UiKit.color(ctx,
                    active ? com.google.android.material.R.attr.colorOnPrimary
                           : com.google.android.material.R.attr.colorOnSurfaceVariant));
            chip.setBackground(UiKit.rounded(ctx, 999,
                    UiKit.color(ctx, active ? com.google.android.material.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorSurfaceContainerHigh),
                    active ? 0 : UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant)));
            chip.setClickable(true);
            chip.setOnClickListener(v -> scanGallery(p));
            galleryChipsRow.addView(chip);
        }
        // 末尾「+」chip：添加扫描路径（跳设置弹窗）
        TextView add = new TextView(ctx);
        add.setText("＋");
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        add.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary));
        add.setPadding(dp(10), dp(6), dp(10), dp(6));
        add.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) add.getLayoutParams()).setMargins(dp(2), 0, dp(6), 0);
        add.setBackground(UiKit.rounded(ctx, 999,
                UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh),
                UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant)));
        add.setClickable(true);
        add.setOnClickListener(v -> showGallerySettings());
        galleryChipsRow.addView(add);
    }

    /** 扫描指定目录（一层）并重建网格：目录分组 → 组内缩略图网格 */
    private void scanGallery(String rootPath) {
        galleryCurDir = rootPath;
        refreshGalleryChips(rootPath);
        galleryContent.removeAllViews();

        final TextView loading = new TextView(ctx);
        loading.setText("扫描中…");
        loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        loading.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(24), 0, dp(24));
        galleryContent.addView(loading);

        new Thread(() -> {
            File root = new File(rootPath);
            // 目录分组：本层图片一组 + 每个子目录一组（一层为限，不做全盘递归）
            java.util.LinkedHashMap<String, java.util.ArrayList<File>> groups = new java.util.LinkedHashMap<>();
            java.util.ArrayList<File> own = new java.util.ArrayList<>();
            File[] kids = root.listFiles();
            if (kids != null) {
                java.util.Arrays.sort(kids, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : kids) {
                    if (f.isDirectory()) {
                        // 子目录：只收直接子层图片
                        File[] sub = f.listFiles();
                        java.util.ArrayList<File> subImgs = new java.util.ArrayList<>();
                        if (sub != null) {
                            java.util.Arrays.sort(sub, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                            for (File s : sub) {
                                if (s.isFile() && com.aliya.hyfilemgr.filemgr.ThumbLoader.isImage(s.getName().toLowerCase())) {
                                    subImgs.add(s);
                                }
                            }
                        }
                        if (!subImgs.isEmpty()) groups.put(f.getName(), subImgs);
                    } else if (com.aliya.hyfilemgr.filemgr.ThumbLoader.isImage(f.getName().toLowerCase())) {
                        own.add(f);
                    }
                }
            }
            if (!own.isEmpty()) groups.put("", own);
            final java.util.LinkedHashMap<String, java.util.ArrayList<File>> fGroups = groups;
            handler.post(() -> {
                if (contentMode != ContentMode.GALLERY || !rootPath.equals(galleryCurDir)) return;
                galleryContent.removeAllViews();
                if (fGroups.isEmpty()) {
                    TextView empty = new TextView(ctx);
                    empty.setText("此路径下没有图片\n可在下方 ＋ 或 设置 中添加其它扫描路径");
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    empty.setTextColor(UiKit.color(ctx,
                            com.google.android.material.R.attr.colorOnSurfaceVariant));
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, dp(32), 0, dp(32));
                    galleryContent.addView(empty);
                    return;
                }
                for (java.util.Map.Entry<String, java.util.ArrayList<File>> g : fGroups.entrySet()) {
                    String dir = g.getKey().isEmpty() ? rootPath
                            : new File(rootPath, g.getKey()).getAbsolutePath();
                    addGalleryGroup(g.getKey(), dir, g.getValue());
                }
            });
        }, "HyGalleryScan").start();
    }

    /** 一组图片：组标题（子目录名）+ 缩略图网格 */
    private void addGalleryGroup(String label, final String dir, java.util.ArrayList<File> imgs) {
        if (label != null && !label.isEmpty()) {
            TextView head = new TextView(ctx);
            head.setText(label + "（" + imgs.size() + "）");
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            head.setTypeface(null, android.graphics.Typeface.BOLD);
            head.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            head.setPadding(dp(6), dp(10), dp(6), dp(4));
            galleryContent.addView(head);
        }

        // 网格：行式 LinearLayout（每行 3 格等分），格子恒为正方形。
        //
        // 不用 GridLayout：它靠 rowSpec/columnSpec 计算行列，
        // 逐格 setLayoutParams 时高度约束会被行规格覆盖，
        // 实测表现为「一长条一张图」（格子高度塌成图片原始比例）。
        // 行式布局下 weight=1 保证等宽、height=固定 dp 保证正方形，行为确定。
        final int col = 3;
        final int gap = dp(2);
        int contentW = ctx.getResources().getDisplayMetrics().widthPixels - dp(16 + 12);
        final int side = (contentW - gap * (col - 1)) / col;

        final java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        for (File f : imgs) paths.add(f.getAbsolutePath());

        LinearLayout rowBox = null;
        for (int i = 0; i < imgs.size(); i++) {
            final File f = imgs.get(i);
            final int pos = i;
            if (i % col == 0) {
                rowBox = new LinearLayout(ctx);
                rowBox.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = i == 0 ? 0 : gap;
                galleryContent.addView(rowBox, rowLp);
            }

            android.widget.ImageView iv = new android.widget.ImageView(ctx);
            // 居中裁切：不管原图什么比例，格子内都是填满的正方形
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh));
            iv.setImageResource(R.drawable.ic_img);   // 占位，ThumbLoader 成功后替换
            com.aliya.hyfilemgr.filemgr.ThumbLoader.load(ctx, iv,
                    f.getAbsolutePath(), f.getName(), f.lastModified());
            iv.setClickable(true);
            iv.setOnClickListener(v -> openImageViewerFromGallery(dir, paths, pos));

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(side, side);
            int leftMargin = (i % col == 0) ? 0 : gap;
            cellLp.setMargins(leftMargin, 0, 0, 0);

            // GIF 角标：网格只播首帧，用角标告知是可动图
            if (f.getName().toLowerCase().endsWith(".gif")) {
                android.widget.FrameLayout cell = new android.widget.FrameLayout(ctx);
                cell.addView(iv, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                TextView badge = new TextView(ctx);
                badge.setText("GIF");
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
                badge.setTypeface(null, android.graphics.Typeface.BOLD);
                badge.setTextColor(0xFFFFFFFF);
                badge.setBackgroundColor(0x99000000);
                badge.setPadding(dp(4), dp(1), dp(4), dp(1));
                android.widget.FrameLayout.LayoutParams bLp =
                        new android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.END | Gravity.BOTTOM);
                bLp.setMargins(0, 0, dp(4), dp(4));
                cell.addView(badge, bLp);
                cell.setOnClickListener(v -> openImageViewerFromGallery(dir, paths, pos));
                rowBox.addView(cell, cellLp);
            } else {
                iv.setLayoutParams(cellLp);
                rowBox.addView(iv);
            }
        }
        // 末行补齐空位：保证最后一行的格子不会被 weight 拉宽
        if (rowBox != null && imgs.size() % col != 0) {
            int need = col - (imgs.size() % col);
            for (int k = 0; k < need; k++) {
                View spacer = new View(ctx);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(side, side);
                spLp.setMargins(gap, 0, 0, 0);
                rowBox.addView(spacer, spLp);
            }
        }
    }

    /** 从相册页点图进查看器：目录图片列表作图集（左右滑只在当前目录内） */
    private void openImageViewerFromGallery(final String dir, java.util.ArrayList<String> paths, int pos) {
        try {
            Intent i = new Intent(ctx, com.aliya.hyfilemgr.ImageViewerActivity.class);
            i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_PATH, paths.get(pos));
            i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_TITLE,
                    new File(paths.get(pos)).getName());
            if (paths.size() > 1) {
                i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_LIST,
                        paths.toArray(new String[0]));
                i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_INDEX, pos);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (hostActivity != null) {
                viewerPendingPath = paths.get(pos);
                hostActivity.startActivityForResult(i, REQ_VIEWER);
            } else {
                ctx.startActivity(i);
            }
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开图片查看器：" + t.getMessage());
        }
    }

    /** 相册扫描路径设置弹窗（侧边栏 ＋ 与设置页共用）：列表增删 + 常用路径快捷加 */
    private void showGallerySettings() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("图片查看器 · 扫描路径"));

        TextView hint = new TextView(ctx);
        hint.setText("相册页按这些路径扫描图片（含一层子目录）。\n点路径删除，点下方快捷项添加。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(hint);

        // 路径行容器：重建式刷新
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);

        android.app.Dialog d = UiKit.glassDialog(ctx, box);

        // lambda 内递归引用自身 → 用数组容器绕开 final 限制
        final Runnable[] rebuildHolder = new Runnable[1];
        rebuildHolder[0] = () -> {
            list.removeAllViews();
            for (final String p : loadGalleryPaths()) {
                TextView row = new TextView(ctx);
                row.setText(p.replace(
                        android.os.Environment.getExternalStorageDirectory().getAbsolutePath(), "内部存储"));
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                row.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
                row.setPadding(dp(8), dp(10), dp(8), dp(10));
                row.setBackgroundResource(com.aliya.hyfilemgr.R.drawable.bg_ripple);
                row.setClickable(true);
                row.setOnClickListener(v -> {
                    loadGalleryPaths().remove(p);
                    saveGalleryPaths();
                    UiKit.toast(ctx, "已删除");
                    rebuildHolder[0].run();
                });
                list.addView(row);
            }
            if (loadGalleryPaths().isEmpty()) {
                TextView empty = new TextView(ctx);
                empty.setText("（无扫描路径）");
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                empty.setTextColor(UiKit.color(ctx,
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
                empty.setPadding(dp(8), dp(8), dp(8), dp(8));
                list.addView(empty);
            }
        };
        rebuildHolder[0].run();

        // 常用路径快捷添加：存在且未在列表中才显示
        LinearLayout quick = new LinearLayout(ctx);
        quick.setOrientation(LinearLayout.VERTICAL);
        String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        String[][] quickPaths = {
                {"DCIM（相机）", root + "/DCIM"},
                {"DCIM/Camera", root + "/DCIM/Camera"},
                {"Pictures", root + "/Pictures"},
                {"Screenshots（截图）", root + "/Pictures/Screenshots"},
                {"Download（下载）", root + "/Download"}};
        for (final String[] qp : quickPaths) {
            if (!new File(qp[1]).isDirectory()) continue;
            if (loadGalleryPaths().contains(qp[1])) continue;
            TextView row = new TextView(ctx);
            row.setText("＋ " + qp[0]);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary));
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.setBackgroundResource(com.aliya.hyfilemgr.R.drawable.bg_ripple);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                loadGalleryPaths().add(qp[1]);
                saveGalleryPaths();
                UiKit.toast(ctx, "已添加 " + qp[0]);
                rebuildHolder[0].run();
            });
            quick.addView(row);
        }
        box.addView(quick);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        TextView done = new TextView(ctx);
        done.setText("完成");
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        done.setTypeface(null, android.graphics.Typeface.BOLD);
        done.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary));
        done.setPadding(dp(16), dp(8), dp(8), dp(8));
        done.setOnClickListener(v -> {
            UiKit.dismissWithAnim(d);
            // 扫描路径变化后刷新相册页（若已打开）
            if (contentMode == ContentMode.GALLERY && galleryCurDir != null) {
                scanGallery(galleryCurDir);
            }
        });
        btns.addView(done);
        box.addView(btns);
        d.show();
    }

    // ══════════ 存储管理 ══════════

    /**
     * 存储管理页：容量总览 + 应用各项占用明细 + 一键清理。
     *
     * <p>先给总量条，再逐项列出可清的目录并各自带清理入口，最后给一个
     * 「全部清理」。用户既能看到全局占满程度，也能只清自己在意的那一项。</p>
     *
     * <p><b>为什么统计必须异步</b>：{@link FileOps#dirSize} 是递归遍历，
     * 回收站可能有上万文件，草稿目录也可能很大。主线程做会卡。</p>
     *
     * <p><b>为什么先建行再回填</b>：统计要几秒，先 show 再刷新，
     * 用户不会盯着一个白屏等。每项先显示「统计中…」，回调里 setText。</p>
     */
    private View buildStorageManager() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("存储管理"));

        final String storePath = Environment.getExternalStorageDirectory().getAbsolutePath();

        // ── 一、内部存储总量 ──
        //
        // ⚠ 容量数字、百分比、进度条必须全部躺在分组卡片里面。
        // 之前它们直接 addView 到 box，落在卡片外面——一旦设了背景图，
        // 这行字就是白字压白墙，整段「内部存储」只剩标题能看见。
        // 现在整段包进 sectionHeaderInCard 返回的卡片，与「应用占用」
        // 同一种结构，背景图和卡片透明度都能正确作用于它。
        box.addView(sectionHeaderInCard("内部存储"));

        final TextView tvTotal = new TextView(ctx);
        tvTotal.setText("统计中…");
        tvTotal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvTotal.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTotal.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        tvTotal.setPadding(dp(14), dp(12), dp(14), dp(4));
        addToSettingsGroup(box, tvTotal);

        final TextView tvDetail = new TextView(ctx);
        tvDetail.setText("");
        tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvDetail.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvDetail.setPadding(dp(14), 0, dp(14), dp(10));
        addToSettingsGroup(box, tvDetail);

        final android.widget.ProgressBar bar = new android.widget.ProgressBar(
                ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        barLp.leftMargin = dp(14);
        barLp.rightMargin = dp(14);
        barLp.bottomMargin = dp(14);
        addToSettingsGroup(box, bar, barLp);

        // ── 二、应用占用明细 ──
        box.addView(sectionHeaderInCard("应用占用"));

        final TextView tvThumb = addStorageItem(box, "缩略图缓存", "统计中…", true,
                () -> {
                    clearCacheSub("thumbs");
                    UiKit.toast(ctx, "已清理缩略图缓存");
                    refreshStoragePage();
                });
        final TextView tvZip = addStorageItem(box, "ZIP 浏览缓存", "统计中…", true,
                () -> {
                    clearCacheSub("zipview");
                    clearCacheSub("zipnested");
                    UiKit.toast(ctx, "已清理 ZIP 缓存");
                    refreshStoragePage();
                });
        final TextView tvUpd = addStorageItem(box, "更新包缓存", "统计中…", true,
                () -> {
                    clearUpdateApks();
                    UiKit.toast(ctx, "已清理更新包");
                    refreshStoragePage();
                });
        final TextView tvDraft = addStorageItem(box, "编辑器草稿", "统计中…", true,
                () -> confirmThen("清理编辑器草稿", "未保存的编辑内容会丢失，确定继续？", () -> {
                    File d = draftDir();
                    if (d != null) deleteChildren(d);
                    UiKit.toast(ctx, "已清理编辑器草稿");
                    refreshStoragePage();
                }));
        final TextView tvBg = addStorageItem(box, "背景图", "统计中…", true,
                () -> confirmThen("清理旧背景图", "只删除当前未使用的旧背景图，正在用的那张会保留。", () -> {
                    int n = purgeOrphanWallpapers();
                    UiKit.toast(ctx, n > 0 ? "已清理 " + n + " 张旧背景图" : "没有可清理的旧背景图");
                    refreshStoragePage();
                }));
        final TextView tvTrash = addStorageItem(box, "回收站", "统计中…", true,
                () -> confirmThen("清空回收站", "回收站内所有文件将被彻底删除，无法恢复。", () -> {
                    runTask(() -> {
                        int cnt = deleteChildren(new File(TRASH_DIR));
                        handler.post(() -> {
                            UiKit.toast(ctx, cnt > 0 ? "已清空回收站" : "回收站已经是空的");
                            refreshStoragePage();
                        });
                    });
                }));
        final TextView tvApp = addStorageItem(box, "应用私有数据合计", "统计中…", false, null);

        // ── 三、一键清理 ──
        box.addView(sectionHeaderInCard("一键清理"));
        final TextView tvAllClearable = new TextView(ctx);
        tvAllClearable.setText("统计中…");
        tvAllClearable.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvAllClearable.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvAllClearable.setPadding(dp(14), dp(12), dp(14), dp(4));
        box.addView(tvAllClearable);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(dp(8), 0, dp(8), dp(8));
        box.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btns.addView(textButton("全部清理", v -> confirmThen(
                "清理全部缓存",
                "缩略图、ZIP 缓存、更新包会被清除。草稿、背景图、回收站不受影响。", () -> {
                    clearCacheSub("thumbs");
                    clearCacheSub("zipview");
                    clearCacheSub("zipnested");
                    clearUpdateApks();
                    UiKit.toast(ctx, "缓存已全部清理");
                    refreshStoragePage();
                })));
        btns.addView(textButton("刷新", v -> refreshStoragePage()));

        loadStorageStats(storePath, tvTotal, tvDetail, bar,
                tvThumb, tvZip, tvUpd, tvDraft, tvBg, tvTrash, tvApp, tvAllClearable);

        return box;
    }

    /**
     * 把任意视图塞进当前设置分组卡片（没有分组时退回 parent）。
     *
     * <p><b>为什么需要这个</b>：分组机制只有 addSettingsRow / addInfoRow /
     * addSliderRow 三个工厂在遵守，而存储管理页要放「大字容量数字 + 进度条」
     * 这种现有工厂造不出来的东西。直接 box.addView 会落到卡片外面——
     * 设了背景图后白字压白墙，整段看不见。</p>
     */
    private void addToSettingsGroup(LinearLayout parent, View v,
                                    LinearLayout.LayoutParams lp) {
        final LinearLayout host = settingsGroup != null ? settingsGroup : parent;
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        host.addView(v, lp);
    }

    private void addToSettingsGroup(LinearLayout parent, View v) {
        addToSettingsGroup(parent, v, null);
    }

    /** 存储管理页里的一行：标题 + 大小 + 可选「清理」按钮 */
    private TextView addStorageItem(LinearLayout parent, String title, String initSize,
                                    boolean clearable, Runnable onClear) {
        final LinearLayout host = settingsGroup != null ? settingsGroup : parent;
        final boolean inGroup = host == settingsGroup;
        if (inGroup) addGroupDivider(host);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        texts.addView(tvTitle);

        TextView tvSize = new TextView(ctx);
        tvSize.setText(initSize);
        tvSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvSize.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvSize.setAlpha(0.8f);
        texts.addView(tvSize);

        if (clearable && onClear != null) {
            TextView btn = textButton("清理", v -> onClear.run());
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btn.setPadding(dp(10), dp(6), dp(10), dp(6));
            row.addView(btn);
        }

        host.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tvSize;
    }

    /** 二次确认后执行（清理类操作统一走这里，避免误触） */
    private void confirmThen(String title, String message, Runnable onConfirm) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "⚠ " + title));

        TextView msg = new TextView(ctx);
        msg.setText(message);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        msg.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        msg.setLineSpacing(0, 1.3f);
        msg.setPadding(dp(6), dp(8), dp(6), dp(12));
        box.addView(msg);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(dp(4), 0, dp(4), dp(4));
        box.addView(btns);

        btns.addView(textButton("取消", v -> UiKit.dismissWithAnim(d)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(textButton("确认清理", v -> {
            UiKit.dismissWithAnim(d);
            onConfirm.run();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        d.show();
    }

    /** 删掉 cacheDir 下的某个子目录内容（不删目录本身，避免后续 mkdir 失败） */
    private void clearCacheSub(String name) {
        try {
            File d = new File(ctx.getCacheDir(), name);
            if (d.exists()) deleteChildren(d);
        } catch (Throwable t) {
            android.util.Log.w("HYStorage", "clearCacheSub " + name + " failed", t);
        }
    }

    /** 删掉 cacheDir 里的更新包（update_*.apk） */
    private void clearUpdateApks() {
        try {
            File[] list = ctx.getCacheDir().listFiles();
            if (list == null) return;
            for (File f : list) {
                if (f.isFile() && f.getName().startsWith("update_")
                        && f.getName().endsWith(".apk")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("HYStorage", "clearUpdateApks failed", t);
        }
    }

    /** 删除目录下所有子项，返回删掉的子项个数；目录本身保留 */
    private int deleteChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        int n = 0;
        for (File c : list) {
            deleteTree(c);
            n++;
        }
        return n;
    }

    /**
     * 清理「孤儿」背景图：删掉 wallpaper 目录里除当前在用之外的所有图。
     *
     * <p>目录里积压旧图是历史遗留（早期「设完图不立即落盘」导致
     * {@code old.delete()} 拿不到 old 而跳过），三张 17MB 就是 51MB。
     * 这里只删不在用的，绝不动 {@link #bgImageFile} / {@link #fmBgFile}
     * 指向的那两个文件。</p>
     *
     * @return 删掉的文件数
     */
    private int purgeOrphanWallpapers() {
        File dir = bgDir();
        if (dir == null) return 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        int n = 0;
        for (File f : list) {
            if (!f.isFile()) continue;
            boolean inUse = (bgImageFile != null && f.getName().equals(bgImageFile.getName()))
                    || (fmBgFile != null && f.getName().equals(fmBgFile.getName()));
            if (inUse) continue;
            if (!f.getName().startsWith("bg_") && !f.getName().startsWith("fmbg_")) continue;
            //noinspection ResultOfMethodCallIgnored
            if (f.delete()) n++;
        }
        return n;
    }

    /** 重建存储管理页（清理完刷新用） */
    private void refreshStoragePage() {
        hideSettingsDialog();
        showInSettingsDialog("存储管理", this::buildStorageManager);
    }

    /** 异步统计并回填所有大小。全部求和跑在一个后台线程里依次做。 */
    private void loadStorageStats(String storePath,
                                  TextView tvTotal, TextView tvDetail,
                                  android.widget.ProgressBar bar,
                                  TextView tvThumb, TextView tvZip, TextView tvUpd,
                                  TextView tvDraft, TextView tvBg, TextView tvTrash,
                                  TextView tvApp, TextView tvAllClearable) {
        new Thread(() -> {
            long total = 0, avail = 0;
            try {
                android.os.StatFs sf = new android.os.StatFs(storePath);
                total = sf.getTotalBytes();
                avail = sf.getAvailableBytes();
            } catch (Throwable t) {
                try {
                    android.os.StatFs sf2 = new android.os.StatFs("/data");
                    total = sf2.getTotalBytes();
                    avail = sf2.getAvailableBytes();
                } catch (Throwable ignored) {
                }
            }
            final long fTotal = total, fAvail = avail;

            final long thumb = FileOps.dirSize(new File(ctx.getCacheDir(), "thumbs"));
            final long zip = FileOps.dirSize(new File(ctx.getCacheDir(), "zipview"))
                    + FileOps.dirSize(new File(ctx.getCacheDir(), "zipnested"));
            final long upd = updateApkSize();
            final long draft = FileOps.dirSize(draftDir());
            final long bg = FileOps.dirSize(bgDir());
            final long trash = FileOps.dirSize(new File(TRASH_DIR));
            final long appAll = FileOps.dirSize(ctx.getFilesDir())
                    + FileOps.dirSize(ctx.getCacheDir());

            handler.post(() -> {
                if (detached) return;
                if (fTotal > 0) {
                    long used = Math.max(0, fTotal - fAvail);
                    int pct = (int) Math.round(used * 100.0 / fTotal);
                    tvTotal.setText("已用 " + fmtStorageSize(used)
                            + " / " + fmtStorageSize(fTotal));
                    tvDetail.setText(pct + "% 已用 · 可用 " + fmtStorageSize(fAvail));
                    bar.setProgress(Math.max(0, Math.min(1000, pct * 10)));
                } else {
                    tvTotal.setText("存储空间不可用");
                    tvDetail.setText("无法读取容量信息（可能权限不足）");
                }
                tvThumb.setText(fmtStorageSize(thumb));
                tvZip.setText(fmtStorageSize(zip));
                tvUpd.setText(fmtStorageSize(upd));
                tvDraft.setText(fmtStorageSize(draft));
                tvBg.setText(fmtStorageSize(bg));
                tvTrash.setText(fmtStorageSize(trash));
                tvApp.setText(fmtStorageSize(appAll));
                tvAllClearable.setText("可一键清理的缓存合计 "
                        + fmtStorageSize(thumb + zip + upd));
            });
        }, "HyStorageStats").start();
    }

    /** cacheDir 里 update_*.apk 的总大小 */
    private long updateApkSize() {
        long n = 0;
        try {
            File[] list = ctx.getCacheDir().listFiles();
            if (list == null) return 0;
            for (File f : list) {
                if (f.isFile() && f.getName().startsWith("update_")
                        && f.getName().endsWith(".apk")) {
                    n += f.length();
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /**
     * 存储页专用的大小格式化。
     *
     * <p>与 {@link AccessUtil#formatSize} 的差别只有两点，都是这个页面
     * 特有的需求：</p>
     * <ul>
     *   <li>统计失败（负值）显示「—」而不是「-」——一列数据里一个横杠
     *       看着像排版错误，「—」才像「没有数据」。</li>
     *   <li>补 TB 档。现在 1TB 存储很常见，{@code 1234.56 GB}
     *       既不直观也撑破行宽。</li>
     * </ul>
     * <p>其余刻度与 formatSize 保持一致，避免同一屏里两种单位口径。</p>
     */
    private static String fmtStorageSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format(Locale.US, "%.2f GB", gb);
        return String.format(Locale.US, "%.2f TB", gb / 1024.0);
    }



    // ══════════ 软件更新 + 关于 ══════════

    /**
     * 更新清单的端点。
     *
     * <p><b>为什么只读 versions.json 而不读 Releases API</b>：
     * 你说「不管多少个版本都能正常接收最新版」。Releases API 需要
     * 分页遍历（默认 30 条/页），且「最新 Release」不一定是「最新版本」
     * （可能有人补发了旧版 Release）。versions.json 里带 versionCode，
     * 直接取 <b>versionCode 最大</b>的那项，与发布顺序、条数解耦。</p>
     *
     * <p>双源：GitHub 优先，失败回落 Gitee（国内网络更稳）。
     * 两个地址指向同一份 versions.json 内容。</p>
     */
    private static final String[] UPDATE_MANIFEST_URLS = {
            // ① Gitee 优先：国内直连，最稳
            "https://gitee.com/haoyou781/hy-file-manager/raw/master/versions.json",
            // ② GitHub 镜像（CDN 加速，走境外但无需翻墙）
            "https://cdn.jsdelivr.net/gh/haoyou781013/hy-file-manager@main/versions.json",
            // ③ GitHub 镜像（另一家代理）
            "https://gh-proxy.com/https://raw.githubusercontent.com"
                    + "/haoyou781013/hy-file-manager/main/versions.json",
            // ④ GitHub 原始地址（兜底，需要能直连）
            "https://raw.githubusercontent.com/haoyou781013/hy-file-manager/main/versions.json",
    };

    private static final String REPO_GITHUB = "https://github.com/haoyou781013/hy-file-manager";
    private static final String REPO_GITEE = "https://gitee.com/haoyou781/hy-file-manager";

    /** 设置页「软件更新」内容（由 showInSettingsDialog 承载） */
    /**
     * 「启动时检查更新」开关卡片。
     *
     * <p>做成独立卡片而不是一行 addInfoRow：后者是「标题+副标题+整行跳转」，
     * 适合导航项；这里是布尔开关，需要真实可见的 Switch。
     * 圆角 10dp，与上面那张版本卡一致。</p>
     */
    private View buildAutoCheckCard() {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(10), dp(12));
        // 走统一入口：这块原本是硬编码 rounded，卡片不透明度滑块对它是无效的
        applyCardSurface(card, 6, false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        card.setLayoutParams(clp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(ctx);
        title.setText("启动时检查更新");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        textCol.addView(title);

        final TextView sub = new TextView(ctx);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setPadding(0, dp(3), 0, 0);
        textCol.addView(sub);
        card.addView(textCol);

        final android.widget.Switch sw = new android.widget.Switch(ctx);
        boolean on = fmPrefs().getBoolean(PREF_AUTO_CHECK_UPDATE, true);
        sw.setChecked(on);
        card.addView(sw);

        final Runnable refreshSub = () -> sub.setText(sw.isChecked()
                ? "打开 App 时自动查一次，有新版会提示"
                : "只在手动点「检查更新」时查");
        sw.setOnCheckedChangeListener((b, checked) -> {
            fmPrefs().edit().putBoolean(PREF_AUTO_CHECK_UPDATE, checked).apply();
            refreshSub.run();
        });
        // 整行可点：Switch 本身命中区偏小
        card.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        refreshSub.run();
        return card;
    }

    private View showUpdatePageBody() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        String cur = appVersionName();
        int curCode = appVersionCode();

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        // 走 cardSurface，跟其它卡片同一个不透明度旋钮。
        // 原来这里硬编码 colorSurfaceContainerLow + 6dp 圆角，
        // 调卡片透明度时它不会跟着变，会是一块实心板。
        applyCardSurface(card, 10, false);
        card.setTag(CARD_TAG_KEY, Boolean.TRUE);

        TextView tvCur = new TextView(ctx);
        tvCur.setText("当前版本\n" + cur + "  （versionCode " + curCode + "）");
        tvCur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvCur.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        tvCur.setLineSpacing(dp(2), 1f);
        card.addView(tvCur);

        TextView tvState = new TextView(ctx);
        tvState.setText("点下方按钮检查更新");
        tvState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvState.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvState.setPadding(0, dp(10), 0, 0);
        card.addView(tvState);
        box.addView(card);

        box.addView(buildAutoCheckCard());

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(14), 0, 0);

        final boolean[] checking = {false};
        var checkBtnHolder = new Object() { TextView v; };

        checkBtnHolder.v = textButton("检查更新", v -> {
            if (checking[0]) return;
            checking[0] = true;
            tvState.setText("正在检查…");
            new Thread(() -> {
                UpdateInfo info = fetchLatestUpdate();
                handler.post(() -> {
                    if (detached) return;
                    checking[0] = false;
                    if (info == null) {
                        tvState.setText("检查失败：无法连接更新服务器\n"
                                + "国内网络可优先访问 Gitee 仓库手动下载：\n"
                                + REPO_GITEE);
                        return;
                    }
                    if (info.code <= curCode) {
                        tvState.setText("已是最新版本（最新 " + info.ver
                                + " / versionCode " + info.code + "）\n"
                                + "发布日期：" + fmtPublishDate(info.date));
                    } else {
                        tvState.setText("发现新版本 " + info.ver
                                + "（versionCode " + info.code + "）\n"
                                + "发布日期：" + fmtPublishDate(info.date));
                        showUpdateDetailDialog(info);
                    }
                });
            }, "HyUpdateCheck").start();
        });
        btns.addView(checkBtnHolder.v);
        box.addView(btns);

        return box;
    }

    /** 解析出的更新信息 */
    private static final class UpdateInfo {
        String ver;
        int code;
        String date;
        String body;
        String apkUrl;
    }

    private String appVersionName() {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int appVersionCode() {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 拉取 versions.json，返回 versionCode **最大**的那一项。
     *
     * <p>遍历整个 versions 数组取最大值，而不是取第一个 ——
     * 数组顺序可能因手工编辑而乱，取 max 才能保证「不管多少个版本
     * 都能拿到真正最新的那个」。</p>
     */
    /** 发布日期显示用的空值兜底：清单缺 publishedAt（旧版手工编辑遗漏）时不再出现裸冒号 */
    private static String fmtPublishDate(String date) {
        return (date == null || date.isEmpty()) ? "未知" : date;
    }

    private UpdateInfo fetchLatestUpdate() {
        for (String url : UPDATE_MANIFEST_URLS) {
            java.io.InputStream in = null;
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL u = new java.net.URL(url);
                conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "HYFileManager/" + appVersionName());
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code != 200) continue;
                in = conn.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                String json = new String(bos.toByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8);

                org.json.JSONObject root = new org.json.JSONObject(json);
                org.json.JSONArray arr = root.optJSONArray("versions");
                if (arr == null || arr.length() == 0) continue;

                UpdateInfo best = null;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    UpdateInfo it = new UpdateInfo();
                    it.ver = o.optString("ver", "");
                    it.code = o.optInt("versionCode", 0);
                    it.date = o.optString("publishedAt", "");
                    it.body = o.optString("body", "");
                    it.apkUrl = o.optString("apkUrl", "");
                    if (best == null || it.code > best.code) best = it;
                }
                if (best != null && best.code > 0) return best;
            } catch (Throwable t) {
                android.util.Log.w("HyUpdate", "拉取失败 " + url, t);
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /**
     * 下载新版本 APK 并调起安装。
     *
     * <p>先要「安装未知应用」权限再下载 —— 反过来会让用户白等
     * 几十 MB 才被拦。</p>
     *
     * <p>下载到 cacheDir：不需要存储权限，装完能干净删掉。</p>
     */
    private void downloadAndInstall(final UpdateInfo info) {
        final String url = (info.apkUrl != null && !info.apkUrl.isEmpty())
                ? info.apkUrl
                : REPO_GITHUB + "/releases/download/v" + info.ver
                  + "/HYFileManager-" + info.ver + ".apk";

        if (!canRequestInstall()) {
            // 记下来：用户在系统设置里授权完回来，onResume 会自动续上
            pendingInstallInfo = info;
            askInstallPermission();
            return;
        }
        pendingInstallInfo = null;

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(ctx);
        pd.setMessage("正在下载 " + info.ver + "…");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            final File out = new File(ctx.getCacheDir(), "update_" + info.ver + ".apk");
            java.net.HttpURLConnection conn = null;
            java.io.InputStream in = null;
            try {
                java.net.URL u = new java.net.URL(url);
                conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "HYFileManager/" + appVersionName());
                int code = conn.getResponseCode();
                if (code != 200) throw new java.io.IOException("服务器返回 " + code);
                final long total = conn.getContentLengthLong();
                in = conn.getInputStream();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    long got = 0;
                    int r;
                    int lastPct = -1;
                    while ((r = in.read(buf)) > 0) {
                        fos.write(buf, 0, r);
                        got += r;
                        if (total > 0) {
                            final int pct = (int) (got * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                handler.post(() -> pd.setMessage(
                                        "正在下载 " + info.ver + "… " + pct + "%"));
                            }
                        }
                    }
                }
                final long size = out.length();
                handler.post(() -> {
                    pd.dismiss();
                    if (detached) return;
                    if (size <= 0) {
                        UiKit.alert(ctx, "下载失败：文件为空");
                        return;
                    }
                    doInstall(out.getAbsolutePath(), false);
                });
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                final String why = msgOf(t);
                handler.post(() -> {
                    pd.dismiss();
                    if (detached) return;
                    UiKit.alert(ctx, "下载失败：" + why
                            + "\n\n可到仓库页面手动下载：\n" + REPO_GITEE);
                });
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) { }
                try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { }
            }
        }, "HyUpdateDownload").start();
    }

    /** 有新版本时的详情弹窗 */
    /**
     * 把 Markdown 标记去掉，只留可读文本。
     *
     * <p>更新说明是给普通用户看的，弹窗用 TextView 纯文本渲染 ——
     * 直接把 ## / ### / ** 显示出来会像一堆符号噪声。
     * 这里做最小转换：标题行去掉井号、去掉粗体星号、
     * 列表符号统一成「·  」，去掉包装用的反引号。</p>
     *
     * <p>不做完整 Markdown 解析：更新说明的格式是我们自己写的，
     * 只用到这几种标记，完整解析器是过度设计。</p>
     */
    private static String stripMarkdown(String md) {
        if (md == null || md.isEmpty()) return "";
        StringBuilder out = new StringBuilder(md.length());
        for (String raw : md.split("\n", -1)) {
            String line = raw;
            if (line.trim().matches("^-{3,}$")) {
                out.append('\n');
                continue;
            }
            int hash = 0;
            while (hash < line.length() && line.charAt(hash) == '#') hash++;
            if (hash > 0 && hash < line.length() && line.charAt(hash) == ' ') {
                out.append('\n').append(line.substring(hash + 1).trim()).append('\n');
                continue;
            }
            String t = line.trim();
            if (t.startsWith("- ") || t.startsWith("* ")) {
                line = "·  " + t.substring(2);
            }
            line = line.replace("**", "").replace("__", "").replace("`", "");
            out.append(line).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private void showUpdateDetailDialog(UpdateInfo info) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "发现新版本 " + info.ver));

        TextView tv = new TextView(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("版本：").append(info.ver).append('\n');
        sb.append("发布于：").append(fmtPublishDate(info.date)).append('\n');
        if (!info.body.isEmpty()) {
            sb.append('\n').append(stripMarkdown(info.body));
        }
        tv.setText(sb.toString());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextIsSelectable(true);
        tv.setPadding(0, dp(8), 0, dp(4));
        box.addView(tv);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("稍后", v -> UiKit.dismissWithAnim(d)));
        btns.addView(textButton("下载并安装", v -> {
            UiKit.dismissWithAnim(d);
            downloadAndInstall(info);
        }));
        box.addView(btns);
        d.show();
    }

    /** 关于页内容（由 showInSettingsDialog 承载） */
    /**
     * 关于页（2026-11-12 重构）。
     *
     * <p>结构：头部大图标 + 版本徽标 → 快捷操作（检查更新/问题反馈）→
     * 应用信息 → 开源仓库 → 第三方库（含许可证）→ 诊断 → 致谢与许可。</p>
     *
     * <p><b>为什么拆成清单</b>：旧版是一大段文本，用户看不清也没耐心读。
     * 拆开后每项都能扫读，且报 issue 时可直接报版本号/库版本。</p>
     */
    private View showAboutPageBody() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        // ══════ 头部：图标 + 应用名 + 版本徽标 + 一句话定位 ══════
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(dp(16), dp(20), dp(16), dp(18));
        applyCardSurface(header, 12, true);
        header.setTag(CARD_TAG_KEY, Boolean.TRUE);
        header.setTag(CARD_TAG_HDR_KEY, Boolean.TRUE);

        android.widget.ImageView logo = new android.widget.ImageView(ctx);
        logo.setImageResource(R.mipmap.ic_launcher);
        header.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView appName = new TextView(ctx);
        appName.setText("HY文件管理");
        appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        appName.setTypeface(null, android.graphics.Typeface.BOLD);
        appName.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        appName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(10);
        header.addView(appName, nameLp);

        TextView verBadge = new TextView(ctx);
        verBadge.setText("v" + appVersionName() + "  ·  " + appVersionCode());
        verBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        verBadge.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnPrimary));
        verBadge.setPadding(dp(12), dp(4), dp(12), dp(4));
        verBadge.setBackground(UiKit.rounded(ctx, 999,
                UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary), 0));
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = dp(8);
        header.addView(verBadge, vLp);

        TextView tagline = new TextView(ctx);
        tagline.setText("双窗格文件管理器 · 压缩包直读 · 内置编辑器");
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tagline.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = dp(6);
        header.addView(tagline, tagLp);
        box.addView(header);

        // ══════ 快捷操作：检查更新 / 问题反馈（横向等分）══════
        LinearLayout quick = new LinearLayout(ctx);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams quickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        quickLp.topMargin = dp(8);

        LinearLayout btnUpdate = aboutQuickButton("检查更新", R.drawable.ic_refresh, v -> {
            showInSettingsDialog("软件更新", this::showUpdatePageBody);
        });
        LinearLayout btnFeedback = aboutQuickButton("问题反馈", R.drawable.ic_info,
                v -> showFeedbackDialog());
        LinearLayout.LayoutParams half1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        quick.addView(btnUpdate, half1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(8);
        quick.addView(btnFeedback, half2);
        box.addView(quick, quickLp);

        // ══════ 应用信息 ══════
        box.addView(sectionHeaderInCard("应用信息"));
        addInfoRow(box, "包名", ctx.getPackageName(), true, null);
        addInfoRow(box, "最低系统", "Android 6.0（API 23）", true, null);
        addInfoRow(box, "编译 SDK", "Android 16（API 36）", true, null);

        // ══════ 开源仓库 ══════
        box.addView(sectionHeaderInCard("开源仓库"));
        addInfoRow(box, "GitHub", REPO_GITHUB, true, v -> openUrl(REPO_GITHUB));
        addInfoRow(box, "Gitee", REPO_GITEE, true, v -> openUrl(REPO_GITEE));
        addInfoRow(box, "问题反馈", "无需注册，应用内直接提交", true,
                v -> showFeedbackDialog());

        // ══════ 第三方开源库（许可证合规 + 排查用）══════
        box.addView(sectionHeaderInCard("第三方开源库"));
        addInfoRow(box, "AndroidX · Material Components", "Apache-2.0", false, null);
        addInfoRow(box, "Media3 / ExoPlayer", "Apache-2.0", false, null);
        addInfoRow(box, "OkHttp", "Apache-2.0", false, null);
        addInfoRow(box, "zip4j", "Apache-2.0", false, null);
        addInfoRow(box, "Apache Commons Compress / Codec", "Apache-2.0", false, null);
        addInfoRow(box, "XZ for Java", "Public Domain", false, null);
        addInfoRow(box, "zstd-jni", "BSD-2-Clause", false, null);
        addInfoRow(box, "junrar", "UnRAR License（仅解压）", false, null);

        // ══════ 诊断 ══════
        box.addView(sectionHeaderInCard("诊断"));
        addInfoRow(box, "导出诊断日志", "崩溃记录 + 设备与应用信息", true,
                v -> exportDiagnosticLog());
        addInfoRow(box, "清除应用数据", "设置与缓存（不动你的文件）", true,
                v -> confirmClearAllData());

        // ══════ 致谢与许可 ══════
        box.addView(sectionHeaderInCard("致谢与许可"));
        TextView desc = new TextView(ctx);
        desc.setText(
                "本应用以 MIT 许可证开源，源码见上方仓库。\n\n"
                + "开发说明：开发过程中使用了 AI 辅助工具进行代码编写、审查与"
                + "文档整理，所有产出均经人工核对与真机验证。\n\n"
                + "解压能力基于上述第三方库。RAR 为专有格式，"
                + "本应用仅用 junrar 解压，不提供 RAR 压缩。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        desc.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        desc.setLineSpacing(dp(3), 1f);
        desc.setTextIsSelectable(true);
        LinearLayout descCard = new LinearLayout(ctx);
        descCard.setOrientation(LinearLayout.VERTICAL);
        descCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        applyCardSurface(descCard, -1, false);
        descCard.setTag(CARD_TAG_KEY, Boolean.TRUE);
        descCard.addView(desc);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(6);
        box.addView(descCard, dlp);

        return box;
    }

    /** 关于页快捷按钮：图标 + 文字横排，卡片底，由调用方决定宽度 */
    private LinearLayout aboutQuickButton(String text, int iconRes, View.OnClickListener onClick) {
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(13), dp(10), dp(13));
        applyCardSurface(btn, 10, false);
        btn.setTag(CARD_TAG_KEY, Boolean.TRUE);
        btn.setClickable(true);
        btn.setFocusable(true);

        android.widget.ImageView icon = new android.widget.ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary));
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iLp.rightMargin = dp(6);
        btn.addView(icon, iLp);

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        btn.addView(tv);

        btn.setOnClickListener(onClick);
        return btn;
    }

    /** 导出诊断日志（应用/设备信息 + 最近 5 条崩溃记录） */
    private void exportDiagnosticLog() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("=== HY文件管理 诊断日志 ===\n");
            sb.append("时间：").append(new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date())).append('\n');
            sb.append("版本：").append(appVersionName()).append("（")
                    .append(appVersionCode()).append("）\n");
            sb.append("包名：").append(ctx.getPackageName()).append('\n');
            sb.append("设备：").append(android.os.Build.MANUFACTURER).append(' ')
                    .append(android.os.Build.MODEL).append('\n');
            sb.append("系统：Android ").append(android.os.Build.VERSION.RELEASE)
                    .append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n");
            sb.append("ABI：").append(android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "?").append('\n');
            sb.append("\n=== 崩溃日志 ===\n");
            java.io.File crashDir = new java.io.File(ctx.getFilesDir(), "crash");
            java.io.File[] logs = crashDir.listFiles();
            if (logs == null || logs.length == 0) {
                sb.append("（无崩溃记录）\n");
            } else {
                java.util.Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                int n = Math.min(5, logs.length);
                for (int i = 0; i < n; i++) {
                    sb.append("\n--- ").append(logs[i].getName()).append(" ---\n");
                    try {
                        byte[] d = java.nio.file.Files.readAllBytes(logs[i].toPath());
                        sb.append(new String(d, java.nio.charset.StandardCharsets.UTF_8));
                    } catch (Throwable t) {
                        sb.append("（读取失败：").append(t).append("）\n");
                    }
                }
                if (logs.length > 5) {
                    sb.append("\n…还有 ").append(logs.length - 5).append(" 个更早的崩溃日志未包含\n");
                }
            }
            final String content = sb.toString();
            handler.post(() -> {
                if (detached) return;
                showSaveLogDialog(content);
            });
        }, "HyDiag").start();
    }

    /** 日志保存弹窗：预览 + 复制 + 保存到下载 */
    private void showSaveLogDialog(final String content) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("导出诊断日志"));

        TextView tip = new TextView(ctx);
        String preview = content.length() > 400 ? content.substring(0, 400) + "\n…" : content;
        tip.setText("共 " + content.length() + " 字符：\n\n" + preview);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tip.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        tip.setTypeface(android.graphics.Typeface.MONOSPACE);
        tip.setPadding(dp(8), dp(8), dp(8), dp(8));
        tip.setBackground(UiKit.rounded(ctx, 8,
                UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow),
                UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant)));
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.addView(tip);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svLp.bottomMargin = dp(10);
        box.addView(sv, svLp);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        btns.addView(textButton("复制", v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("HY诊断日志", content));
            UiKit.toast(ctx, "已复制");
        }));
        btns.addView(textButton("保存到下载", v -> {
            UiKit.dismissWithAnim(d);
            saveLogToDownloads(content);
        }));
        btns.addView(textButton("关闭", v -> UiKit.dismissWithAnim(d)));
        box.addView(btns);
        d.show();
    }

    /** 写日志到下载目录（Android 10+ 走 MediaStore，无需存储权限） */
    private void saveLogToDownloads(final String content) {
        String name = "hy_diag_" + new java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".log";
        try {
            String saved;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/HY文件管理-日志");
                android.net.Uri uri = ctx.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    UiKit.alert(ctx, "无法创建文件");
                    return;
                }
                try (java.io.OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        UiKit.alert(ctx, "无法打开输出流");
                        return;
                    }
                    out.write(content.getBytes("UTF-8"));
                    out.flush();
                }
                saved = "下载/HY文件管理-日志/" + name;
            } else {
                java.io.File dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS), "HY文件管理-日志");
                if (!dir.exists() && !dir.mkdirs()) {
                    UiKit.alert(ctx, "无法创建目录");
                    return;
                }
                java.io.File f = new java.io.File(dir, name);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(content.getBytes("UTF-8"));
                    out.flush();
                }
                saved = f.getAbsolutePath();
            }
            UiKit.toast(ctx, "已保存到：\n" + saved);
        } catch (Throwable t) {
            UiKit.alert(ctx, "保存失败：" + msgOf(t));
        }
    }

    /** 清除应用数据：二次确认 */
    private void confirmClearAllData() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("⚠ 清除应用数据"));

        TextView tv = new TextView(ctx);
        tv.setText("将清除：\n· 全部设置（主题、背景图、排序、扫描路径等）\n"
                + "· 缩略图缓存、ZIP 浏览缓存、更新包缓存\n\n"
                + "不会动：\n· 你的文件与回收站内容\n\n"
                + "清除后应用恢复到初始状态，不可撤销。");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(tv);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        btns.addView(textButton("取消", v -> UiKit.dismissWithAnim(d)));
        TextView ok = textButton("确认清除", null);
        ok.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorError));
        ok.setOnClickListener(v -> {
            UiKit.dismissWithAnim(d);
            doClearAllData();
        });
        btns.addView(ok);
        box.addView(btns);
        d.show();
    }

    /** 实际清除：SharedPreferences + 缓存子目录（不碰用户文件与回收站） */
    private void doClearAllData() {
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            try {
                fmPrefs().edit().clear().commit();
                report.append("· 设置已清除\n");
            } catch (Throwable t) {
                report.append("· 设置清除失败：").append(t).append('\n');
            }
            String[] caches = {"thumbs", "zipview", "zipnested"};
            for (String c : caches) {
                java.io.File dir = new java.io.File(ctx.getCacheDir(), c);
                if (deleteDirQuiet(dir)) report.append("· 缓存已清：").append(c).append('\n');
            }
            java.io.File[] ups = ctx.getCacheDir().listFiles((d, n) -> n.startsWith("update_"));
            if (ups != null) {
                for (java.io.File f : ups) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                report.append("· 更新包已清\n");
            }
            final String msg = report.toString().trim();
            handler.post(() -> {
                if (detached) return;
                UiKit.alert(ctx, "清除完成：\n\n" + msg + "\n\n建议重启应用以完全生效。");
            });
        }, "HyClearData").start();
    }

    /** 静默删目录（不抛异常，返回是否删掉了） */
    private boolean deleteDirQuiet(java.io.File dir) {
        if (dir == null || !dir.exists()) return false;
        java.io.File[] kids = dir.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) {
                if (k.isDirectory()) deleteDirQuiet(k);
                else {
                    //noinspection ResultOfMethodCallIgnored
                    k.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        return dir.delete();
    }

    /** 用系统浏览器打开链接 */
    private void openUrl(String url) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开链接：" + msgOf(t) + "\n" + url);
        }
    }

    /**
     * 当前停在设置的哪一层："" = 设置主页，非空 = 某个子页。
     *
     * <p>顶栏左上角图标的语义完全取决于它：
     * 主页 → 面包键（开侧边栏，和文件页一致）
     * 子页 → 返回键（回设置主页）
     * 以前不管在哪一层都显示返回键，用户会把顶栏返回和页内返回行
     * 当成两个「不同的返回」来回犹豫。</p>
     */
    private String currentSettingsPage = "";

    /** 设置页对话框引用（供子页返回时复用） */

    /**
     * 在原地打开一个新设置页：复用同一个 settingsDialog 实例，
     * 只替换 contentView。
     *
     * <p>与「dismiss 旧弹窗 + glassDialog 新弹窗」的区别：
     * 不重新创建窗口、不重新截图模糊（applyBlur 不再执行），
     * 因此没有整屏闪一下。窗口背景与模糊图沿用首次创建时的结果。</p>
     */
    /** 设置容器（内嵌在 contentWrap，与文件列表/终端同级） */
    private LinearLayout settingsEmbedView;
    /** 设置容器内滚动的 ScrollView（各设置页内容放这里） */
    private LinearLayout settingsEmbedContent;
    /** 嵌在共享顶栏里的设置标题 */
    private View topBarSettingsTitleView;
    private TextView settingsTopTitle;

    /**
     * 打开/切换设置页（**内嵌视图**，不是弹窗）。
     *
     * <p>原先是 Dialog：遮罩 + 模糊截图 + 窗口创建，与文件列表是两套界面。
     * 改为与终端相同的做法——在 contentWrap 内换视图，顶栏切换成「设置 | 页名」。
     * 这样没有任何窗口/遮罩开销，切换只是换子 View。</p>
     */
    private void showInSettingsDialog(java.util.function.Supplier<View> contentSupplier) {
        showInSettingsDialog("", contentSupplier);
    }

    /**
     * 在原地切到某个设置子页，并把子页名同步到共享顶栏。
     *
     * <p><b>为什么标题必须在这里设</b>：顶栏是共享的，只有
     * {@code settingsTopTitle} 这一段会随子页变化。旧实现让各子页
     * 自己在内容体开头写 {@code settingsTopTitle.setText(...)} ——
     * 结果 5 个子页里只有 3 个写了（权限管理/个性化/文件管理），
     * 软件更新、关于、设置主页全漏，顶栏显示成「设置 | 」后面空着，
     * 用户不知道自己在哪一页。</p>
     *
     * <p>改成由入口统一传标题：新增子页时不可能再忘。</p>
     */
    private void showInSettingsDialog(String title,
                                      java.util.function.Supplier<View> contentSupplier) {
        if (contentWrapRef == null) return;
        // 每次进设置页先清空分组引用：settingsGroup 是实例字段，
        // 上一页留下的值会让「先加行、后加标题」的页面（如关于页）
        // 把行误塞进上一个页面的卡片里。
        settingsGroup = null;
        View v = contentSupplier.get();
        if (v == null) return;
        settingsGroup = null;   // 建完也清掉，避免跨页残留
        // 进任何设置页都必须处于 SETTINGS 模式。
        // 从文件页 / 侧边栏直接调进来时 contentMode 还是 FILES，
        // 之前只切了内容 View，顶栏却停在文件态（面包屑 + 文件操作按钮），
        // 于是出现「顶栏看着像文件页、内容是设置页」的错位。
        if (contentMode != ContentMode.SETTINGS) {
            setContentMode(ContentMode.SETTINGS);
        }
        currentSettingsPage = (title == null) ? "" : title;
        if (settingsTopTitle != null) settingsTopTitle.setText(title == null ? "" : title);
        refreshSettingsLeftIcon();

        if (settingsEmbedView == null) {
            settingsEmbedView = new LinearLayout(ctx);
            settingsEmbedView.setOrientation(LinearLayout.VERTICAL);
            // 容器恒透明：上面的卡片自己带 cardSurface 底色，
            // 容器再铺一层不透明色会把卡片的层级盖平（见 syncContentTransparency 的说明）
            settingsEmbedView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.setFillViewport(true);
            settingsEmbedContent = new LinearLayout(ctx);
            // 卡片外侧留白：设置行自身是 cardSurface 卡片，
            // 容器不给内边距的话卡片会顶到屏幕边缘，看不出「卡片」这个层次。
            settingsEmbedContent.setOrientation(LinearLayout.VERTICAL);
            settingsEmbedContent.setPadding(dp(8), dp(4), dp(8), dp(12));
            settingsEmbedContent.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(14);
            settingsEmbedContent.setPadding(pad, dp(8), pad, pad);
            sv.addView(settingsEmbedContent, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            settingsEmbedView.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            contentWrapRef.addView(settingsEmbedView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 换内容（淡入）
        settingsEmbedContent.removeAllViews();
        settingsEmbedContent.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsEmbedContent.setAlpha(0f);
        settingsEmbedContent.animate().alpha(1f).setDuration(160).start();

        // 统一互斥切换（会隐藏终端、切顶栏、管底栏与 FAB）
        setContentMode(ContentMode.SETTINGS);
    }

    /** 顶栏切到设置模式：「设置 | 页名」+ 返回 */
    private void enterSettingsTopBar() {
        if (topBarCenterSlot == null || topBarSettingsTitleView != null) return;
        if (topBarCrumbView != null) topBarCrumbView.setVisibility(View.GONE);

        LinearLayout title = new LinearLayout(ctx);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);

        TextView tag = new TextView(ctx);
        tag.setText("设置");
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tag.setTypeface(null, android.graphics.Typeface.BOLD);
        tag.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        title.addView(tag);

        TextView sep = new TextView(ctx);
        sep.setText("  |  ");
        sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sep.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        title.addView(sep);

        settingsTopTitle = new TextView(ctx);
        settingsTopTitle.setText("");
        settingsTopTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        settingsTopTitle.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        settingsTopTitle.setSingleLine(true);
        title.addView(settingsTopTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topBarCenterSlot.addView(title, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        topBarSettingsTitleView = title;

        if (topBarFileActions != null) topBarFileActions.setVisibility(View.GONE);
    }

    /** 关闭设置页，恢复文件视图与顶栏 */
    private void closeSettingsEmbed() {
        if (settingsEmbedView == null) return;
        setContentMode(ContentMode.FILES);
    }

    /** 打开设置主页（内嵌视图） */
    private void showSettingsPage() {
        // 标题传 ""：
        //   ① 顶栏不会显示成「设置 | 设置」这种重复；
        //   ② currentSettingsPage 为空 → 顶栏图标回到面包键（主页层级），
        //      而不是返回键。之前传 "设置" 导致从子页回来仍判为非空，
        //      图标停在返回键，点它又调用 showSettingsPage —— 表现为「返回到了设置页」。
        showInSettingsDialog("", this::buildSettingsRoot);
    }

    /** 薄包装：以独立弹窗打开（保持既有调用点兼容） */
    private void showPermissionManager() {
        showInSettingsDialog("权限管理", this::buildPermissionManager);
    }

    private void showFileSettings() {
        showInSettingsDialog("文件管理", this::buildFileSettings);
    }

    private void showAppearanceSettings() {
        showInSettingsDialog("主题设置", this::buildAppearanceSettings);
    }

    private void hideSettingsDialog() {
        closeSettingsEmbed();
    }


    /** 设置行：标题 + 副标题 + 图标 + 右箭头 */
    private void addSettingsRow(LinearLayout parent, String title, String subtitle,
                                int iconRes, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 有分组时改投到分组卡片里，而不是调用方传进来的 box。
        // 调用点统一写 addSettingsRow(box, ...)，但视觉上这些行应该
        // 躺在 sectionHeaderInCard 返回的那张卡片内部；不去比 parent == settingsGroup
        // （那样永远为 false，因为 parent 一直是 box）。
        final LinearLayout host = settingsGroup != null ? settingsGroup : parent;
        final boolean inGroup = host == settingsGroup;
        if (inGroup) addGroupDivider(host);

        row.setPadding(dp(12), dp(14), dp(12), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        if (!inGroup) applyCardSurface(row, -1, false);
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!inGroup) rowLp.topMargin = dp(6);
        host.addView(row, rowLp);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(14);
        row.addView(texts, tLp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        texts.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText(subtitle);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvSub.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvSub.setAlpha(0.75f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        texts.addView(tvSub, subLp);
    }

    /** 权限管理页：所有文件访问 / 存储权限 / SAF 授权目录 */
    private View buildPermissionManager() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("权限管理"));

        // ① 所有文件访问
        boolean allFiles = Environment.isExternalStorageManager();
        addInfoRow(box, "所有文件访问",
                allFiles ? "已授权 · 可读写整个存储" : "未授权 · 读写受限，点此授权",
                allFiles, v -> {
                    requestAllFilesAccess();
                });

        // ② 存储读写权限（Android 12 及以下）
        boolean storageOk = checkPermission();
        addInfoRow(box, "存储读写权限",
                storageOk ? "已授权" : "未授权",
                storageOk, v -> {
                    if (Build.VERSION.SDK_INT >= 23) {
                        java.util.ArrayList<String> perms = new java.util.ArrayList<>();
                        perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                        if (Build.VERSION.SDK_INT <= 28) {
                            perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                        }
                        if (ctx instanceof android.app.Activity) {
                            ((android.app.Activity) ctx).requestPermissions(
                                    perms.toArray(new String[0]), 0x2001);
                        } else {
                            UiKit.toast(ctx, "请在应用内授予存储权限");
                        }
                    }
                });

        // ③ 安装未知应用（安装 APK 必需）
        boolean canInstall = false;
        try {
            canInstall = ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable ignored) {
        }
        final boolean installOk = canInstall;
        addInfoRow(box, "安装未知应用",
                installOk ? "已允许 · 可直接安装 APK" : "未允许 · 安装 APK 会被系统拦截",
                installOk, v -> {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        try {
                            Intent i = new Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + ctx.getPackageName()));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(i);
                        } catch (Throwable t) {
                            openAppSettings();
                        }
                    } else {
                        UiKit.toast(ctx, "当前系统无需单独授权");
                    }
                });

        // ④ 通知权限（Android 13+ 崩溃通知/任务提示需要）
        boolean notifOk = true;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifOk = ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        addInfoRow(box, "通知权限",
                notifOk ? "已授权" : "未授权（Android 13+ 需要）",
                notifOk, v -> {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        if (ctx instanceof android.app.Activity) {
                            ((android.app.Activity) ctx).requestPermissions(
                                    new String[]{"android.permission.POST_NOTIFICATIONS"}, 0x2002);
                        } else {
                            UiKit.toast(ctx, "请在应用内授予通知权限");
                        }
                    } else {
                        UiKit.toast(ctx, "当前系统无需通知权限");
                    }
                });

        // ⑤ SAF 授权目录
        addInfoRow(box, "SAF 授权目录",
                SHOW_SAF_MOUNTS ? "已在侧边栏显示" : "侧边栏暂不显示（授权数据保留）",
                SHOW_SAF_MOUNTS, v -> {
                    openSafAuthorize();
                });
        return box;
    }

    /** 内容区容器引用（文件视图 / 终端面板共存于此，靠 visibility 切换） */
    private FrameLayout contentWrapRef;

    /** 文件列表主视图（切到终端时隐藏） */
    private View fileContentView;

    /** 最近一次浏览过的真实目录（虚拟路径时终端用它作工作目录） */
    private volatile String lastRealPath = null;

    /**
     * 模块是否已 detach。PopupWindow 不属于任何 ViewGroup，
     * Activity 重建（切主题 / recreate）后旧实例的 PopupWindow 仍挂在 window 上，
     * 表现为「弹窗残留在屏幕上」。detach 时统一 dismiss。
     */
    private boolean detached = false;

    /** 当前显示中的 PopupWindow（最多两个：上下文菜单 + 格式下拉） */
    private final java.util.List<android.widget.PopupWindow> livePopups =
            new java.util.ArrayList<>();

    /** 关掉所有还活着的 PopupWindow（detach / 重建前调用） */
    private void dismissAllPopups() {
        for (android.widget.PopupWindow p : new java.util.ArrayList<>(livePopups)) {
            try {
                if (p != null && p.isShowing()) p.dismiss();
            } catch (Throwable ignored) {
            }
        }
        livePopups.clear();
    }

    /**
     * 登记一个 PopupWindow，让它在 detach 时被自动关闭。
     *
     * <p>PopupWindow 不挂在 View 树上，Activity recreate 后旧实例的窗口
     * 不会被系统回收，会以「幽灵弹窗」形式留在屏幕上——之前 4 个
     * PopupWindow 全是局部变量、无人持有，正是这个泄漏。</p>
     */
    private android.widget.PopupWindow trackPopup(android.widget.PopupWindow p) {
        if (p != null) {
            livePopups.add(p);
            p.setOnDismissListener(() -> livePopups.remove(p));
        }
        return p;
    }


    /** 当前内容视图：三者互斥 */
    private enum ContentMode { FILES, SETTINGS, GALLERY }

    private ContentMode contentMode = ContentMode.FILES;

    /**
     * 统一的内容视图切换：先把其余视图全部隐藏，再显示目标。
     *
     * <p>旧代码各入口各自 setVisibility，缺少互斥约束 →
     * 侧边栏「工具→文件管理」卡片在终端打开时直接叠上来，
     * 关设置后终端又从 contentWrap 移除但未重新加入，出现
     * 「两个视图同显」或「屏幕全空」。集中到一个方法避免再漏。</p>
     */
    private void setContentMode(ContentMode target) {
        if (target == null) return;
        ContentMode prev = contentMode;
        contentMode = target;

        boolean fileVisible = target == ContentMode.FILES;
        boolean settingsVisible = target == ContentMode.SETTINGS;
        boolean galleryVisible = target == ContentMode.GALLERY;

        // ── 内容区切换：加过渡 ──
        // 旧实现直接 VISIBLE/GONE，切设置页和返回文件列表都是"啪"一下换掉。
        // 现在：进来的一层从「淡+轻微位移」到正常，出去的一层直接隐藏。
        // 不用同时双向动画 —— 两层叠加时如果都做 alpha，中间会出现
        // 两页半透明重叠的糊状帧，比硬切还难看。
        final View inView = fileVisible ? fileContentView
                : (settingsVisible ? settingsEmbedView : galleryEmbedView);
        final View outView;
        if (fileVisible) {
            outView = settingsVisible != false && prev == ContentMode.SETTINGS
                    ? settingsEmbedView : (prev == ContentMode.GALLERY ? galleryEmbedView : null);
        } else if (settingsVisible) {
            outView = prev == ContentMode.GALLERY ? galleryEmbedView : fileContentView;
        } else {
            outView = prev == ContentMode.SETTINGS ? settingsEmbedView : fileContentView;
        }
        if (outView != null) {
            outView.animate().cancel();
            outView.setVisibility(View.GONE);
            outView.setAlpha(1f);
            outView.setTranslationY(0f);
        }
        if (inView != null) {
            inView.animate().cancel();
            inView.setVisibility(View.VISIBLE);
            if (animScale > 0f && prev != target) {
                // 设置/相册页从右侧轻滑入，文件列表从左侧轻滑入 —— 有方向感
                final boolean toSub = settingsVisible || galleryVisible;
                inView.setAlpha(0f);
                inView.setTranslationX(dp(toSub ? 24 : -24));
                inView.animate().alpha(1f).translationX(0f)
                        .setDuration(ANIM_MODE_SWITCH)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            } else {
                inView.setAlpha(1f);
                inView.setTranslationX(0f);
            }
        }

        // 底部两栏与 FAB 只在文件模式显示
        if (bottomNav != null) {
            if (fileVisible) {
                bottomNav.setAlpha(1f);
                bottomNav.setTranslationY(0f);
                bottomNav.setVisibility(View.VISIBLE);
            } else {
                bottomNav.setVisibility(View.GONE);
            }
        }
        if (fabNew != null) {
            fabNew.setVisibility(fileVisible ? View.VISIBLE : View.GONE);
        }
        if (opBar != null) {
            if (fileVisible && multiMode) {
                updateOpBar();
            } else {
                opBar.setVisibility(View.GONE);
            }
        }

        // 左上角图标：文件页 → 菜单；设置主页/相册 → 菜单；设置子页 → 返回。
        // 相册页左上角也用菜单（与侧边栏入口一致），返回靠系统返回键逐层退。
        if (topBarLeftIcon != null) {
            topBarLeftIcon.setImageResource(
                    fileVisible || currentSettingsPage.isEmpty()
                            ? R.drawable.ic_menu : R.drawable.ic_back);
        }

        // 顶栏：文件 / 设置 / 相册 三选一
        if (fileVisible) {
            removeSettingsTopTitle();
            if (topBarCrumbView != null) topBarCrumbView.setVisibility(View.VISIBLE);
            if (topBarFileActions != null) topBarFileActions.setVisibility(View.VISIBLE);
        } else if (galleryVisible) {
            enterSettingsTopBar();
            if (settingsTopTitle != null) settingsTopTitle.setText("图片查看器");
        } else {
            enterSettingsTopBar();
        }

        if (prev != target && fileVisible && multiMode) updateOpBar();
    }

    /** 按「文件页 / 设置主页 / 设置子页」刷新左上角图标。 */
    private void refreshSettingsLeftIcon() {
        if (topBarLeftIcon == null) return;
        boolean needBack = (contentMode == ContentMode.SETTINGS)
                && !currentSettingsPage.isEmpty();
        topBarLeftIcon.setImageResource(needBack
                ? R.drawable.ic_back : R.drawable.ic_menu);
        topBarLeftIcon.setContentDescription(needBack ? "返回设置主页" : "打开侧边栏");
    }


    // ══════════ 背景图 ══════════

    /** 背景图私有目录：filesDir/wallpaper */
    private File bgDir() {
        File d = new File(ctx.getFilesDir(), "wallpaper");
        if (!d.exists() && !d.mkdirs()) return null;
        return d;
    }

    /**
     * 创建背景层，插到 rootContainer 最底。
     *
     * <p><b>为什么用 ImageView 铺满 + alpha 而不是把背景设给每个页面</b>：
     * 逐页设背景会出现「切页时背景跳一下」，而且每个页面还要各自的模糊版本。
     * 一层铺满整个 rootContainer，上面所有页面都半透明透出它，
     * 切换页面时背景是完全稳定的。</p>
     *
     * <p>点击穿透：background 层 setClickable(false) + 不消费触摸，
     * 否则它会挡掉下面所有列表的点击。</p>
     */
    private void attachBackgroundLayer() {
        if (rootContainer == null) return;
        bgImageView = new android.widget.ImageView(ctx);
        bgImageView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        bgImageView.setClickable(false);
        bgImageView.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rootContainer.addView(bgImageView, 0, lp);   // index 0 = 最底
        applyBackground();
    }

    /** 按当前 bgImageFile / bgAlpha / bgBlur 重新渲染背景层。 */
    private void applyBackground() {
        if (bgImageView == null) return;
        // 背景层是文件列表页的底，取文件管理主题的值（关闭时自动回落到全局）
        final File bgFile = fmActiveBgFile();
        final int bgA = fmActiveBgAlpha();
        final int bgB = fmActiveBgBlur();
        if (bgFile == null || !bgFile.isFile()) {
            bgImageView.setImageDrawable(null);
            bgImageView.setVisibility(View.GONE);
            syncContentTransparency(false);
            return;
        }
        bgImageView.setVisibility(View.VISIBLE);
        bgImageView.setAlpha(bgA / 100f);
        syncContentTransparency(true);

        // 解码与模糊都放后台：模糊是纯 Java 双重循环，全屏尺寸下是秒级，
        // 放在主线程会让「设背景图」和「拖模糊滑块」都卡住。
        // 用序号丢弃过期结果——拖滑块会连发多次，只认最后一次。
        final int seq = ++bgLoadSeq;
        final File src = bgFile;
        final int blur = bgB;
        new Thread(() -> {
            android.graphics.Bitmap bmp = decodeScaled(src);
            if (bmp != null && blur > 0) bmp = blurBitmap(bmp, blur);
            final android.graphics.Bitmap out = bmp;
            handler.post(() -> {
                if (detached || seq != bgLoadSeq) {
                    // 过期或已销毁：直接丢掉，避免旧图盖掉新图
                    if (out != null && !out.isRecycled()) out.recycle();
                    return;
                }
                if (bgImageView == null) {
                    if (out != null && !out.isRecycled()) out.recycle();
                    return;
                }
                if (out == null) {
                    bgImageView.setImageDrawable(null);
                    bgImageView.setVisibility(View.GONE);
                    return;
                }
                bgImageView.setImageBitmap(out);
            });
        }, "HyBgDecode").start();
    }

    /**
     * 有背景图时让内容层变透明，背景才透得出来。
     *
     * <p>列表（RecyclerView）自身本来就没有不透明底色，所以主要是
     * 设置页容器、顶栏和底部两栏需要处理。这里统一刷新一次，
     * 设/删背景图时调用。</p>
     */
    private void syncContentTransparency(boolean hasBg) {
        try {
            // ⚠ 参数 hasBg 已不再参与任何判断：现在**恒按「有背景图」处理**。
            //
            // 原先按 hasBg 二分会造成两种模式下完全不同的观感：
            // 没设背景图时设置页容器不透明、顶栏底栏实心，
            // 设了之后全部变透明——用户设/删一张图，整个界面的层次就变了。
            // 改成恒透明后，界面层次不随「有没有背景图」漂移，
            // 可读性统一由卡片自己的 cardAlpha 负责。
            //
            // 参数保留是为了不动 20 多个调用点，语义上已是「刷新内容层」。
            if (settingsEmbedView != null) {
                settingsEmbedView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
            // 顶部栏 / 底部功能栏 / 多选栏：半透明化，保留一点底色保证可读
            int navBg = UiKit.color(ctx,
                    com.google.android.material.R.attr.colorSurfaceContainerLow);
            int translucent = (navBg & 0x00FFFFFF) | 0xD0000000;
            if (bottomNav != null) {
                int stroke = UiKit.color(ctx,
                        com.google.android.material.R.attr.colorPrimary);
                bottomNav.setBackground(UiKit.rounded(ctx, 16, translucent, stroke));
            }
            if (topBar != null) topBar.setBackground(topBarSurface());
            if (opBar != null) {
                opBar.setBackground(UiKit.rounded(ctx, 16, translucent,
                        UiKit.color(ctx,
                                com.google.android.material.R.attr.colorOutlineVariant)));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按屏幕尺寸采样解码。
     *
     * <p>先只读边界拿原始尺寸，算出 inSampleSize 再真解码。
     * 不同步直接 decode 全尺寸 —— 一张 4000×3000 的图在堆里是 48MB，
     * 低端机直接 OOM。</p>
     */
    private android.graphics.Bitmap decodeScaled(File f) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int reqW = Math.max(1, dm.widthPixels);
            int reqH = Math.max(1, dm.heightPixels);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            android.util.Log.w("HYBg", "decode failed", t);
            return null;
        }
    }

    /**
     * 高斯模糊（RenderScript 自 API 31 移除，这里用 stack blur 的简化实现）。
     *
     * @param radius 1-25，实际按 /2 收缩以控制耗时
     */
    private android.graphics.Bitmap blurBitmap(android.graphics.Bitmap src, int radius) {
        if (src == null || radius <= 0) return src;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            // 先缩小再模糊，速度提升一个数量级，放大后视觉效果几乎一致
            int scale = 4;
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(
                    src, Math.max(1, w / scale), Math.max(1, h / scale), true);
            // 不能在这里回收 src：中途抛异常时 catch 分支会把 src 原样返回，
            // 已回收的 Bitmap 交给 setImageBitmap 绘制时会崩。
            // 统一放到所有步骤成功之后、且确认不返回 src 时再回收。

            int sw = small.getWidth();
            int sh = small.getHeight();
            int[] pix = new int[sw * sh];
            small.getPixels(pix, 0, sw, 0, 0, sw, sh);

            int r = Math.max(1, radius / 2);
            int[] tmp = new int[pix.length];
            // 横向
            boxBlurPass(pix, tmp, sw, sh, r, true);
            // 纵向
            boxBlurPass(tmp, pix, sw, sh, r, false);

            android.graphics.Bitmap after = android.graphics.Bitmap.createBitmap(
                    sw, sh, android.graphics.Bitmap.Config.ARGB_8888);
            after.setPixels(pix, 0, sw, 0, 0, sw, sh);
            small.recycle();

            // 放大回原尺寸
            android.graphics.Bitmap out = android.graphics.Bitmap.createScaledBitmap(after, w, h, true);
            after.recycle();
            // 到这里 out 已成型，src 确定不会再被返回，可以安全回收
            if (out != src && !src.isRecycled()) src.recycle();
            return out;
        } catch (Throwable t) {
            android.util.Log.w("HYBg", "blur failed", t);
            return src;
        }
    }

    /** 单方向 box blur 一趟 */
    private static void boxBlurPass(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int len = horiz ? w : h;
        int lines = horiz ? h : w;
        for (int line = 0; line < lines; line++) {
            int idx = horiz ? line * w : line;
            int step = horiz ? 1 : w;
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, gg = 0, bb = 0, cnt = 0;
                int from = Math.max(0, i - r);
                int to = Math.min(len - 1, i + r);
                for (int j = from; j <= to; j++) {
                    int c = in[idx + j * step];
                    a += (c >>> 24) & 0xFF;
                    rr += (c >> 16) & 0xFF;
                    gg += (c >> 8) & 0xFF;
                    bb += c & 0xFF;
                    cnt++;
                }
                out[idx + i * step] = ((a / cnt) << 24) | ((rr / cnt) << 16)
                        | ((gg / cnt) << 8) | (bb / cnt);
            }
        }
    }

    /** 把选中的图片复制进私有目录，替换旧背景。 */
    private void setBackgroundFromUri(final android.net.Uri uri) {
        new Thread(() -> {
            java.io.InputStream in = null;
            File tmp = null;
            try {
                File dir = bgDir();
                if (dir == null) throw new java.io.IOException("无法创建壁纸目录");
                tmp = new File(dir, "bg.tmp");
                in = ctx.getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("无法打开图片");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                // 校验是不是能解码的图片
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
                if (o.outWidth <= 0) throw new java.io.IOException("不是有效的图片");

                final File dst = new File(dir,
                        (pickForFmTheme ? "fmbg_" : "bg_") + System.currentTimeMillis()
                        + (guessExt(o) ));
                if (!tmp.renameTo(dst)) throw new java.io.IOException("保存失败");

                final boolean forFm = pickForFmTheme;
                final File old = forFm ? fmBgFile : bgImageFile;
                handler.post(() -> {
                    if (detached) return;
                    if (forFm) fmBgFile = dst; else bgImageFile = dst;
                    // 立刻落盘：不等 onDetach。
                    // 设完图马上被后台杀掉时 onDetach 不会执行，
                    // 之前只有滑块回调才 persistState，边界场景会丢。
                    persistState();
                    applyBackground();
                    if (old != null && !old.equals(dst)) {
                        //noinspection ResultOfMethodCallIgnored
                        old.delete();
                    }
                    applyFmThemeToAdapters();
                    UiKit.toast(ctx, forFm ? "文件管理背景图已更换" : "背景图已更换");
                    // 设置页里的预览也要刷新
                    refreshSettingsPage();
                });
            } catch (Throwable t) {
                if (tmp != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                final String why = msgOf(t);
                handler.post(() -> {
                    if (detached) return;
                    UiKit.alert(ctx, "设置背景失败：" + why);
                });
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) { }
            }
        }, "HyBgSet").start();
    }

    private static String guessExt(android.graphics.BitmapFactory.Options o) {
        String m = o.outMimeType;
        if (m == null) return ".img";
        if (m.contains("jpeg")) return ".jpg";
        if (m.contains("png")) return ".png";
        if (m.contains("webp")) return ".webp";
        if (m.contains("gif")) return ".gif";
        return ".img";
    }

    /** 清除背景图 */
    private void clearBackground() {
        final File old = bgImageFile;
        bgImageFile = null;
        applyBackground();
        if (old != null) {
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
        UiKit.toast(ctx, "背景图已移除");
        refreshSettingsPage();
    }



    // ══════════ 卡片透明度与边框 ══════════

    /**
     * 卡片/设置行的统一背景：半透明底 + 一圈极浅描边。
     *
     * <p><b>为什么要运行时生成而不能用静态 drawable</b>：
     * 透明度是可调状态，静态资源写死 alpha 就没法跟着滑块变。
     * 这里每次调用按当前 cardAlpha 算色值。</p>
     *
     * <p><b>描边为什么这么浅</b>：用户要求「不需要太明显，只是浅浅一层」。
     * 用 colorOutlineVariant 再压到 alpha 0x22（约 13%），
     * 目的是让卡片在背景图上有边界，而不是变成硬框。</p>
     *
     * @param cornerDp 圆角，-1 表示跟随 ripple（行用 10，卡片用 14）
     */
    private android.graphics.drawable.Drawable cardSurface(int cornerDp) {
        return cardSurface(cornerDp, false);
    }

    /**
     * 卡片底色。
     *
     * @param isHeader true = 分区标题卡片
     *
     * <p><b>为什么标题要和内容区分</b>：两者原本都用 colorSurfaceContainerLow，
     * 摆在一起就是一整片同色块，看不出「这个标题管下面哪些行」。
     * 标题改用更亮一级的 colorSurfaceContainerHigh，
     * 在暗色下更亮、亮色下更白，天然比内容卡片「浮」起来一层。
     * 圆角也收小：标题是小卡片，10dp 显得过于圆润。</p>
     */
    private android.graphics.drawable.Drawable cardSurface(int cornerDp, boolean isHeader) {
        int base = UiKit.color(ctx,
                isHeader
                        ? com.google.android.material.R.attr.colorSurfaceContainerHigh
                        : com.google.android.material.R.attr.colorSurfaceContainerLow);
        // cardAlpha 100 → 完全不透明；0 → 只剩描边
        int a = Math.round(255f * cardAlpha / 100f);
        int fill = (base & 0x00FFFFFF) | (a << 24);
        int stroke = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOutlineVariant);
        // 描边本身也留一点存在感，不随 cardAlpha 降到 0（否则边框就没了）
        int strokeColor = (stroke & 0x00FFFFFF) | 0x22000000;
        // UiKit.rounded 的参数语义是 dp（内部自己做 dp() 换算），
        // 这里不能再传 dp(10)——那是 px，会被二次换算，3x 屏上圆角偏大 3 倍。
        return UiKit.rounded(ctx, cornerDp < 0 ? 10 : cornerDp, fill, strokeColor);
    }

    /**
     * 共享顶栏的底色：与卡片共用同一个不透明度。
     *
     * <p>顶栏不是 cardSurface（它没有描边，圆角也更大），
     * 但透明度必须跟着「卡片不透明度」走，否则调低卡片透明度后
     * 顶栏会是一块实心色块，把背景图挡出一条。</p>
     */
    // ══════ 文件管理主题取值器 ══════
    // 开关关闭时全部回落到全局值，调用点不需要再判 fmThemeOn。

    /** 文件列表页当前应使用的背景图 */
    private File fmActiveBgFile() {
        return (fmThemeOn && fmBgFile != null && fmBgFile.isFile()) ? fmBgFile : bgImageFile;
    }

    /** 文件列表页当前应使用的背景不透明度 */
    private int fmActiveBgAlpha() {
        return fmThemeOn ? fmBgAlpha : bgAlpha;
    }

    /** 文件列表页当前应使用的背景模糊度 */
    private int fmActiveBgBlur() {
        return fmThemeOn ? fmBgBlur : bgBlur;
    }

    /** 文件列表行当前应使用的卡片不透明度 */
    private int fmActiveRowAlpha() {
        return fmThemeOn ? fmRowAlpha : cardAlpha;
    }

    /** 文件列表页当前应使用的密度 */
    private int fmActiveDensity() {
        return fmThemeOn ? fmDensity : listDensity;
    }

    /** 把文件管理主题的当前取值推给所有窗格的 adapter */
    private void applyFmThemeToAdapters() {
        int rowA = fmActiveRowAlpha();
        int dens = fmActiveDensity();
        for (Pane pp : panes) {
            if (pp == null || pp.adapter == null) continue;
            pp.adapter.setRowAlpha(rowA);
            pp.adapter.setDensity(dens);
        }
    }

    private android.graphics.drawable.Drawable topBarSurface() {
        int base = UiKit.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainer);
        int a = Math.round(255f * cardAlpha / 100f);
        int fill = (base & 0x00FFFFFF) | (a << 24);
        return UiKit.rounded(ctx, 14, fill, 0);
    }

    /**
     * 分区标题，外面套一层卡片。
     *
     * <p>裸 {@link UiKit#sectionHeader} 是直接压在背景图上的文字，
     * 背景图偏亮或偏暗时会看不清。把标题收进 cardSurface 卡片后，
     * 可读性统一由「卡片不透明度」这一个旋钮控制，
     * 不需要再额外维护一个遮罩强度。</p>
     *
     * <p>没有背景图时仍返回裸标题：纯色背景下套卡片只会多出一圈无意义的描边。</p>
     */
    /**
     * 分区标题。返回的卡片同时作为**该分组的容器**：
     * 标题在最上面，后续 {@code addInfoRow}/{@code addSliderRow}
     * 直接往这张卡片里加行，行与行之间由 {@link #addGroupDivider} 加分割线。
     *
     * <p>这样一屏只有一个大卡片，而不是「标题一张卡 + 每个选项一张卡」，
     * 圆角和描边数量直接减半，设置页看着安静很多。</p>
     */
    private View sectionHeaderInCard(String text) {
        // 标题始终是「分组头」，不再区分有没有背景图。
        // 之前这里在无背景图时返回裸 TextView（settingsGroup=null），
        // 结果分组逻辑整个失效，设置页还是老的「每行一张卡」。
        // 视觉上：分组头用更亮的一级底色、圆角收窄，和内部的白底行区分开。
        TextView tv = UiKit.sectionHeader(ctx, text);
        tv.setPadding(dp(14), dp(12), dp(14), dp(10));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 0, 0, 0);
        applyCardSurface(card, 12, true);
        card.setTag(CARD_TAG_KEY, Boolean.TRUE);
        card.setTag(CARD_TAG_HDR_KEY, Boolean.TRUE);
        card.addView(tv);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        settingsGroup = card;   // 后续行加到这里
        return card;
    }

    /** 把当前 cardAlpha 应用到所有已存在的设置行/卡片并重绘。 */
    private void applyCardAlpha() {
        // ⚠ 这里必须刷**整个窗口**，不能只刷 settingsEmbedContent。
        //
        // 旧实现只递归 settingsEmbedContent 一棵子树，于是：
        //   · 被设置页遮住的文件列表 / 分类 / 回收站——不在那棵树里，不刷
        //   · 所有 Dialog——独立 Window，不在任何 View 树里，不刷
        //   · bottomNav / opBar——只由 syncContentTransparency 更新，而那个
        //     只在设/删背景图时调，拖滑块完全不碰
        // 结果就是「拖滑块只有设置页那几行变色，其它地方要重启才生效」——
        // 因为重启后所有 View 都是按新 cardAlpha 新建的。
        //
        // ⚠ 现在改为刷**登记表**，不再遍历 View 树。
        //
        // 遍历的问题是「猜」：靠 isClickable() || CARD_TAG_KEY 识别卡片，
        // 漏打 tag 或行本身不可点击就会漏刷，表现是「拖滑块只有一部分变色」。
        // 登记表是「确定性」的：只要背景出自 applyCardSurface 就必然在里面。
        //
        // 副作用：如果将来有人绕过 applyCardSurface 直接 setBackground，
        // 那块就不跟随——这是有意的，入口唯一才不会漏。
        refreshRegisteredCards();
        // 兜底：某些 View 可能在登记表清理后仍有 tag 标记（老路径遗留）
        View windowRoot = activityRootView();
        if (windowRoot != null) {
            applyCardAlphaRecursive(windowRoot, 0);
        }

        // 顶栏 / 底栏 / 多选栏：它们是 rounded() 现算的，标签识别不到，
        // 必须显式重设。三者都跟 cardAlpha 走。
        if (topBar != null) topBar.setBackground(topBarSurface());
        refreshBarSurfaces();

        // 文件列表行是 RecyclerView 子项（由 adapter 自己 makeBg），不在递归里
        applyFmThemeToAdapters();

        // 侧边栏**不动**：它由独立的「侧边栏不透明度」旋钮（navPanelAlpha）
        // 控制，不跟 cardAlpha 走。这里刻意不调 applyNavPanelAlpha()。

        // 当前开着的弹窗也要刷（Dialog 是独立 Window，上面那棵 DecorView 覆盖不到）
        applyCardAlphaToOpenDialogs();

        UiKit.toast(ctx, "卡片不透明度 " + cardAlpha + "%");
    }

    /**
     * 按登记表重刷所有卡片背景。
     *
     * <p>同时清理两类失效项：View 已被回收（弱引用空）、View 已脱离窗口
     * （{@code isAttachedToWindow()} 为 false 且已 destroy）。后者能被重新
     * attach，所以只在明显失效时才剔除；否则宁可留着多刷一次。</p>
     */
    private void refreshRegisteredCards() {
        int refreshed = 0;
        java.util.Iterator<java.lang.ref.WeakReference<View>> it = cardViewRefs.iterator();
        while (it.hasNext()) {
            View v = it.next().get();
            if (v == null) {
                it.remove();
                continue;
            }
            int[] style = cardStyleMap.get(v);
            if (style == null) {
                it.remove();
                continue;
            }
            v.setBackground(cardSurface(style[0], style[1] == 1));
            refreshed++;
        }
        // 反向清理：cardStyleMap 里已没有强引用的 Key 会随 View 回收消失，
        // 这里只清掉本次遍历中已确认失效的项，避免 map 无限增长
        if (cardStyleMap.size() > cardViewRefs.size() * 2 + 32) {
            java.util.Map<View, int[]> alive = new java.util.IdentityHashMap<>();
            for (java.util.Iterator<java.lang.ref.WeakReference<View>> it2 = cardViewRefs.iterator();
                 it2.hasNext(); ) {
                View v = it2.next().get();
                if (v != null && cardStyleMap.containsKey(v)) {
                    alive.put(v, cardStyleMap.get(v));
                }
            }
            cardStyleMap.clear();
            cardStyleMap.putAll(alive);
        }
        android.util.Log.i("HYCardAlpha", "refreshed " + refreshed + " cards @ " + cardAlpha + "%");
    }

    /** 取 Activity 的窗口根 View（DecorView），拿不到返回 null */
    private View activityRootView() {
        try {
            if (hostActivity == null) return null;
            android.view.Window w = hostActivity.getWindow();
            if (w == null) return null;
            return w.getDecorView();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 重设底栏 / 多选栏的底色，让它们跟 cardAlpha 同步。
     *
     * <p>这两处的背景是 {@code UiKit.rounded(...)} 现算的渐变，
     * 没有 CARD_TAG_KEY 标记，也不是「可点击的卡片容器」，
     * 递归识别不到，只能显式重算一遍。</p>
     */
    private void refreshBarSurfaces() {
        try {
            int baseColor = UiKit.color(ctx,
                    com.google.android.material.R.attr.colorSurfaceContainerLow);
            int a = Math.round(255f * cardAlpha / 100f);
            int fill = (baseColor & 0x00FFFFFF) | (a << 24);
            if (bottomNav != null) {
                bottomNav.setBackground(UiKit.rounded(ctx, 16, fill,
                        UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary)));
            }
            if (opBar != null) {
                opBar.setBackground(UiKit.rounded(ctx, 16, fill,
                        UiKit.color(ctx,
                                com.google.android.material.R.attr.colorOutlineVariant)));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 给当前所有开着的弹窗重刷卡片透明度。
     *
     * <p>Dialog 的 View 树挂在 WindowManager 上，和 Activity 的 DecorView 是
     * 两棵互不相干的树。模块里没有集中管理弹窗引用的注册表，所以这里
     * 通过 {@link UiKit} 登记过的活动弹窗列表遍历。</p>
     */
    private void applyCardAlphaToOpenDialogs() {
        try {
            java.util.List<android.app.Dialog> ds = UiKit.liveDialogs();
            for (android.app.Dialog d : ds) {
                if (d == null || !d.isShowing()) continue;
                android.view.Window dw = d.getWindow();
                if (dw == null) continue;
                View root = dw.getDecorView();
                if (root != null) applyCardAlphaRecursive(root, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 递归给设置页里的「行容器」换背景。
     *
     * <p>只替换「可点击的垂直/水平行」和「带圆角的卡片」——
     * 纯文字 TextView 不动，否则会变成一堆色块。</p>
     */
    private void applyCardAlphaRecursive(View v, int depth) {
        if (v == null || depth > 12) return;
        // 认两类容器：
        //   ① 可点击的（设置行、选图行）—— 它们本来就是卡片
        //   ② 已带 cardSurface 卡片的（滑块行，不可点击但也是卡片）
        // 只判 isClickable 会漏掉滑块行，拖卡片透明度时它们不跟着刷新。
        boolean isCard = v instanceof ViewGroup
                && !(v instanceof android.widget.AdapterView)
                && (v.isClickable() || v.getTag(CARD_TAG_KEY) != null);
        if (isCard) {
            // 保留标题卡片的高亮底色，否则拖卡片透明度时标题会被刷成内容色
            boolean isHeader = Boolean.TRUE.equals(v.getTag(CARD_TAG_HDR_KEY));
            v.setBackground(cardSurface(isHeader ? 8 : -1, isHeader));
        }
        // 递归走所有 ViewGroup，而不是只认 LinearLayout / ScrollView：
        // 设置页里有 FrameLayout 包裹的分组，只认两类会在那里断掉，
        // 导致整棵子树不刷新。
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) applyCardAlphaRecursive(vg.getChildAt(i), depth + 1);
        }
    }

    // ── 背景图选择入口 ──

    private static final int REQ_PICK_BG = 0x4B47;   // 老式 startActivityForResult 通道（retired）
    /** 图片查看器回传通道：删除已确认 / 设为背景已选择 */
    private static final int REQ_VIEWER = 0x4B49;
    /** 系统照片选择器的启动器，由 MainActivity 注册后注入 */
    private Runnable bgPickerLauncher;

    /** MainActivity 注册好 PickVisualMedia 后注入 */
    public void setBgPickerLauncher(Runnable r) {
        this.bgPickerLauncher = r;
    }

    /**
     * 选背景图：弹两个入口给用户选。
     *
     * <p>两个入口的分工：
     * ① <b>图片选择器</b>（默认拉相册）—— 大多数时候用户要的图就在相册里，
     *    一步到位；用 ACTION_OPEN_DOCUMENT + image/*，系统会自带相册入口。
     * ② <b>文件列表</b>（单列）—— 有些壁纸存在隐藏目录、U 盘、SAF 授权目录里，
     *    相册扫不到，必须能自己 browse 到。</p>
     */
    private void pickBackground() {
        pickForFmTheme = false;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("更换背景图"));

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);

        box.addView(buildSimpleRow("从相册选择", "打开系统图片选择器", v -> {
            UiKit.dismissWithAnim(d);
            launchImagePicker();
        }));
        box.addView(buildSimpleRow("从文件选择", "浏览所有目录挑一张图", v -> {
            UiKit.dismissWithAnim(d);
            showBgFileBrowser();
        }));

        if (bgImageFile != null) {
            box.addView(buildSimpleRow("移除背景图", "恢复纯色背景", v -> {
                UiKit.dismissWithAnim(d);
                clearBackground();
            }));
        }
        d.show();
    }

    /** 系统图片选择器（默认落到相册） */
    private void launchImagePicker() {
        // 优先直连系统 Photo Picker（Android 13+ / API 33+）。
        //
        // 不能只依赖 AndroidX 的 PickVisualMedia：它在部分 ROM 上会判定
        // 「没有系统选择器」并回退到 ACTION_OPEN_DOCUMENT，而后者能被多个
        // 应用处理，于是弹出 ResolverActivity（「打开方式」）。
        // 实测本机 com.android.photopicker 存在，直接走系统 Action 最稳。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                Intent pickImages = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
                pickImages.setType("image/*");
                // 限定包名，杜绝任何「打开方式」弹窗
                pickImages.setPackage("com.android.photopicker");
                if (hostActivity != null
                        && pickImages.resolveActivity(ctx.getPackageManager()) != null) {
                    hostActivity.startActivityForResult(pickImages, REQ_PICK_BG);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // 没有系统 Photo Picker（或包名不同）：用 AndroidX PickVisualMedia，
        // 它会自动回退到 ACTION_OPEN_DOCUMENT
        if (bgPickerLauncher != null) {
            try {
                bgPickerLauncher.run();
                return;
            } catch (Throwable ignored) {
            }
        }
        UiKit.toast(ctx, "图片选择器不可用，改用文件列表");
        showBgFileBrowser();
    }

    /**
     * 按当前 navPanelAlpha 重刷侧边栏面板背景。
     *
     * <p>只换颜色，不重建视图——滑块拖动时每帧都调，
     * 重建会把面板里的滚动位置和列表状态一起丢掉。</p>
     */
    private void applyNavPanelAlpha() {
        if (navPanel == null || navPanelFill == 0) return;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        int pa = Math.round(255f * navPanelAlpha / 100f);
        g.setColor((navPanelFill & 0x00FFFFFF) | (pa << 24));
        g.setCornerRadii(new float[]{0, 0, dp(20), dp(20), 0, 0, dp(20), dp(20)});
        g.setStroke(dp(1), UiKit.color(ctx,
                com.google.android.material.R.attr.colorOutlineVariant));
        navPanel.setBackground(g);
    }

    /**
     * 选文件管理主题的独立背景图。
     *
     * <p>与全局背景图共用一套选图入口（相册 / 文件列表），
     * 只是落盘时用不同前缀区分，避免互相覆盖。</p>
     */
    private void pickFmBackground() {
        pickForFmTheme = true;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("更换文件管理背景图"));

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);

        box.addView(buildSimpleRow("从相册选择", "打开系统图片选择器", v -> {
            UiKit.dismissWithAnim(d);
            launchImagePicker();
        }));
        box.addView(buildSimpleRow("从文件选择", "浏览所有目录挑一张图", v -> {
            UiKit.dismissWithAnim(d);
            showBgFileBrowser();
        }));
        if (fmBgFile != null) {
            box.addView(buildSimpleRow("移除背景图", "改回使用全局背景图", v -> {
                UiKit.dismissWithAnim(d);
                File old = fmBgFile;
                fmBgFile = null;
                applyBackground();
                if (old != null) {
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
                hideSettingsDialog();
                showInSettingsDialog("文件管理主题", this::buildFileManagerTheme);
                UiKit.toast(ctx, "已移除，改用全局背景图");
            }));
        }
        d.show();
    }

    /** 接收图片选择器结果（由 MainActivity 转发） */
    /** 接收系统照片选择器返回的图片 Uri */
    public void onPickBackgroundResult(android.net.Uri uri) {
        if (uri == null) return;
        setBackgroundFromUri(uri);
    }

    /** 老式 startActivityForResult 通道，保留兼容（当前未使用） */
    public void onPickBackgroundResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_BG) return;
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return;
        onPickBackgroundResult(data.getData());
    }

    /**
     * 自建文件浏览器：单列列表，只显示目录和图片。
     *
     * <p>复用现有的 AccessRouter 列举能力，所以 SAF 授权目录、root 路径、
     * 外部存储都能进去。仅显示图片扩展名 + 目录，避免用户在一堆 txt 里找图。</p>
     */
    private void showBgFileBrowser() {
        final String[] curPath = { active != null && active.path != null
                ? active.path : Environment.getExternalStorageDirectory().getAbsolutePath() };

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("选择图片"));

        final TextView pathLabel = new TextView(ctx);
        pathLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pathLabel.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(android.text.TextUtils.TruncateAt.START);
        pathLabel.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(pathLabel);

        final LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.addView(listBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340));
        box.addView(sv, slp);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);

        final Runnable[] reload = new Runnable[1];
        reload[0] = () -> {
            pathLabel.setText(curPath[0]);
            listBox.removeAllViews();
            new Thread(() -> {
                java.util.List<String[]> items = new java.util.ArrayList<>();
                try {
                    AccessResult r = com.aliya.hyfilemgr.access.AccessRouter.list(ctx, curPath[0]);
                    if (r != null && r.entries != null) {
                        for (FileEntry e : r.entries) {
                            if (e.name.startsWith(".")) continue;
                            if (e.isDir()) {
                                items.add(new String[]{ e.name, e.path, "d" });
                            } else if (isImageName(e.name)) {
                                items.add(new String[]{ e.name, e.path, "f" });
                            }
                        }
                    }
                } catch (Throwable t) {
                    android.util.Log.w("HYBg", "browse failed", t);
                }
                handler.post(() -> {
                    if (detached) return;
                    // 返回上级
                    File up = new File(curPath[0]).getParentFile();
                    if (up != null) {
                        listBox.addView(buildSimpleRow("⬆ 上级目录", up.getAbsolutePath(), v -> {
                            curPath[0] = up.getAbsolutePath();
                            reload[0].run();
                        }));
                    }
                    if (items.isEmpty()) {
                        TextView empty = new TextView(ctx);
                        empty.setText("（这里没有图片）");
                        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                        empty.setTextColor(UiKit.color(ctx,
                                com.google.android.material.R.attr.colorOnSurfaceVariant));
                        empty.setPadding(dp(8), dp(10), dp(8), dp(10));
                        listBox.addView(empty);
                    }
                    for (String[] it : items) {
                        final boolean isDir = "d".equals(it[2]);
                        listBox.addView(buildSimpleRow(
                                (isDir ? "📁 " : "🖼 ") + it[0],
                                isDir ? null : it[1], v -> {
                                    if (isDir) {
                                        curPath[0] = it[1];
                                        reload[0].run();
                                    } else {
                                        UiKit.dismissWithAnim(d);
                                        setBackgroundFromUri(
                                                android.net.Uri.fromFile(new File(it[1])));
                                    }
                                }));
                    }
                });
            }, "HyBgBrowse").start();
        };
        reload[0].run();
        d.show();
    }

    private static boolean isImageName(String name) {
        String l = name.toLowerCase(Locale.US);
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".webp") || l.endsWith(".bmp") || l.endsWith(".gif");
    }

    /** 一行「标题 + 可选副标题」，可点 */
    private View buildSimpleRow(String title, String subtitle, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        applyCardSurface(row, -1, false);
        row.setClickable(true);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        row.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s2 = new TextView(ctx);
            s2.setText(subtitle);
            s2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            s2.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            s2.setSingleLine(true);
            s2.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            s2.setPadding(0, dp(2), 0, 0);
            row.addView(s2);
        }
        row.setOnClickListener(onClick);
        return row;
    }

    /** 让当前设置页重建内容（透明度变化后预览要跟上） */
    private void refreshSettingsPage() {
        if (contentMode == ContentMode.SETTINGS) {
            showSettingsPage();
        }
    }

    /** 从顶栏移除设置标题（幂等） */
    private void removeSettingsTopTitle() {
        if (topBarSettingsTitleView != null && topBarCenterSlot != null) {
            topBarCenterSlot.removeView(topBarSettingsTitleView);
        }
        topBarSettingsTitleView = null;
        settingsTopTitle = null;
    }

    /** 终端面板（内置，同一 Activity 内叠加显示） */
    /** 回收站保留天数选择 */
    private void showTrashKeepDaysPicker() {
        final int[] opts = {0, 1, 3, 7, 15, 30, 60, 90, 180};
        String[] labels = {"不自动清理", "1 天", "3 天", "7 天", "15 天",
                "30 天", "60 天", "90 天", "180 天"};
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "自动清理周期"));

        TextView hint = new TextView(ctx);
        hint.setText("启动时对比回收站文件的记录时间，超过周期自动清除。\n"
                + "记录时间 = 文件移入回收站的时刻。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(hint);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        for (int i = 0; i < opts.length; i++) {
            final int days = opts[i];
            boolean active = (trashKeepDays == days);
            addInfoRow(box, labels[i],
                    active ? "当前设置" : "点按选择", active, v -> {
                        trashKeepDays = days;
                        savePrefInt(PREF_TRASH_KEEP_DAYS, days);
                        persistState();
                        UiKit.dismissWithAnim(d);
                        hideSettingsDialog();
                        showFileSettings();
                        UiKit.toast(ctx, days <= 0 ? "已关闭自动清理"
                                : "回收站保留 " + days + " 天");
                    });
        }
        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        box.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btns.addView(textButton("取消", v -> UiKit.dismissWithAnim(d)));
        d.show();
    }

    /** 明暗模式选择（跟随系统 / 亮色 / 暗色），切换后重建界面 */
    private void showModePicker() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "明暗模式"));

        TextView hint = new TextView(ctx);
        hint.setText("工业风格内置深色，不受此项影响。\n"
                + "切换后界面会自动重建。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(hint);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        String[] names = {"跟随系统", "亮色", "暗色"};
        String[] descs = {"按系统深色设置自动切换", "始终使用浅色界面", "始终使用深色界面"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            addInfoRow(box, names[i], descs[i], uiMode == i, v -> {
                if (uiMode == idx) {
                    UiKit.dismissWithAnim(d);
                    return;
                }
                uiMode = idx;
                savePrefInt(PREF_UI_MODE, idx);
                persistState();
                UiKit.dismissWithAnim(d);
                final android.app.Activity act = hostActivity;
                if (act != null) {
                    try {
                        act.recreate();
                    } catch (Throwable t) {
                        UiKit.toast(ctx, "已保存，重启应用后生效");
                    }
                }
            });
        }
        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        box.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btns.addView(textButton("取消", v -> UiKit.dismissWithAnim(d)));
        d.show();
    }

    /** 主题选择（默认 / 工业风格），切换后重建界面 */
    private void showThemePicker() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "界面主题"));

        TextView hint = new TextView(ctx);
        hint.setText("工业风格：深色底 + 琥珀色强调，适合暗光环境。\n"
                + "切换后界面会自动重建。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(hint);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        String[] names = {"默认", "工业风格"};
        String[] descs = {"Material3 跟随系统深浅色", "深色 + 琥珀强调，控制面板风格"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            addInfoRow(box, names[i], descs[i], uiTheme == i, v -> {
                if (uiTheme == idx) {
                    UiKit.dismissWithAnim(d);
                    return;
                }
                uiTheme = idx;
                savePrefInt(PREF_UI_THEME, idx);
                persistState();
                UiKit.dismissWithAnim(d);
                // 主题只能在 onCreate 前 setTheme，必须重建 Activity
                final android.app.Activity act = hostActivity;
                if (act != null) {
                    try {
                        act.recreate();
                    } catch (Throwable t) {
                        UiKit.toast(ctx, "主题已保存，重启应用后生效");
                    }
                } else {
                    UiKit.toast(ctx, "主题已保存，重启应用后生效");
                }
            });
        }

        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        box.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btns.addView(textButton("取消", v -> UiKit.dismissWithAnim(d)));
        d.show();
    }

    /** 个性化设置页：显示密度(DPI) + 列表文件密度（均为进度条式） */
    private View buildAppearanceSettings() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("主题设置"));

        // ── 背景图 ──
        addInfoRow(box, "背景图",
                bgImageFile != null ? "已设置 · 点击更换" : "未设置",
                bgImageFile != null, v -> pickBackground());

        // 背景图相关三项：没设背景图时隐藏（滑块调了也看不出效果，避免困惑）
        if (bgImageFile != null) {
            final TextView bgAlphaVal = addSliderRow(box, "背景不透明度",
                    bgAlpha + "%", bgAlpha, 0, 100, 10, null);
            lastSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                    bgAlphaVal.setText(p + "%");
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                    bgAlpha = sb.getProgress();
                    savePrefInt(PREF_BG_ALPHA, bgAlpha);
                    persistState();
                    if (bgImageView != null) bgImageView.setAlpha(bgAlpha / 100f);
                }
            });

            final TextView bgBlurVal = addSliderRow(box, "背景模糊度",
                    bgBlur == 0 ? "不模糊" : String.valueOf(bgBlur),
                    bgBlur, 0, 25, 1, null);
            lastSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                    bgBlurVal.setText(p == 0 ? "不模糊" : String.valueOf(p));
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                    bgBlur = sb.getProgress();
                    savePrefInt(PREF_BG_BLUR, bgBlur);
                    persistState();
                    applyBackground();   // 模糊要重渲染位图
                }
            });
        }

        // ── 卡片不透明度：全局影响所有卡片的底色透明度 ──
        final TextView cardVal = addSliderRow(box, "卡片不透明度",
                cardAlpha + "%", cardAlpha, 0, 100, 10, null);
        lastSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                cardVal.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                cardAlpha = sb.getProgress();
                savePrefInt(PREF_CARD_ALPHA, cardAlpha);
                persistState();
                // 立刻应用：把所有设置行/卡片的底色重刷一遍
                applyCardAlpha();
            }
        });

        // ── 状态栏不透明度：0 = 透明露出背景图，100 = 实心主题色 ──
        final TextView sbVal = addSliderRow(box, "状态栏不透明度",
                statusBarAlpha + "%", statusBarAlpha, 0, 100, 10, null);
        lastSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                sbVal.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                statusBarAlpha = sb.getProgress();
                savePrefInt(PREF_STATUS_BAR_ALPHA, statusBarAlpha);
                persistState();
                applyStatusBarAlpha();
            }
        });

        // ── 界面主题 ──
        final String[] THEME_NAMES = {"默认", "工业风格"};
        addInfoRow(box, "界面主题",
                THEME_NAMES[Math.max(0, Math.min(1, uiTheme))],
                uiTheme == 1, v -> {
                    showThemePicker();
                });

        // ── 明暗模式（工业风格内置深色，隐藏此项）──
        if (uiTheme != 1) {
            final String[] MODE_NAMES = {"跟随系统", "亮色", "暗色"};
            addInfoRow(box, "明暗模式",
                    MODE_NAMES[Math.max(0, Math.min(2, uiMode))],
                    uiMode != 0, v -> showModePicker());
        }

        // ── MD3 兼容模式 ──
        // 默认关闭（用 Material3）。部分旧 ROM 的 AppCompat+Material3
        // 主题解析冲突会白屏/闪退，那些机型需开启此项退回 AppCompat 主题。
        final boolean md3Off = fmPrefs().getBoolean("md3_off", false);
        addInfoRow(box, "关闭 Material3 主题",
                md3Off ? "已开启（使用 AppCompat 主题）" : "已关闭（使用 Material3 主题）",
                md3Off, v -> {
                    boolean next = !fmPrefs().getBoolean("md3_off", false);
                    fmPrefs().edit().putBoolean("md3_off", next).apply();
                    UiKit.toast(ctx, next
                            ? "已切到 AppCompat 主题，重启生效"
                            : "已切回 Material3 主题，重启生效");
                    showAppearanceSettings();
                });

        // 启动保护：若 MD3 在此机型起不来，上面会自动降级一次；
        // 这里额外给出手动恢复入口
        if (md3Off) {
            TextView hint = new TextView(ctx);
            hint.setText("若开启后仍无法启动：adb shell am start 前先执行\n"
                    + "adb shell run-as com.aliya.hyfilemgr …\n"
                    + "或直接卸载重装（设置会保留在带外备份）");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            hint.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            hint.setAlpha(0.7f);
            hint.setPadding(dp(4), dp(2), dp(4), dp(8));
            box.addView(hint);
        }

        // ── 显示密度（DPI）：SeekBar ──
        final int[] dpiPresets = com.aliya.hyfilemgr.util.DpiUtils.PRESETS;
        int curDpi = com.aliya.hyfilemgr.util.DpiUtils.getOverrideDpi();
        int dpiIdxTmp = 0;
        for (int i = 0; i < dpiPresets.length; i++) {
            if (dpiPresets[i] == curDpi) { dpiIdxTmp = i; break; }
        }
        final int dpiIdx = dpiIdxTmp;

        final TextView dpiValue = addSliderRow(box, "显示密度（DPI）",
                dpiLabel(dpiPresets[dpiIdx]),
                dpiIdx, 0, dpiPresets.length - 1, 0, null);

        final android.widget.SeekBar dpiBar = lastSlider;
        dpiBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            private int committed = dpiIdx;   // dpiIdx 已是 final

            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                dpiValue.setText(dpiLabel(dpiPresets[progress]));
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                int idx = sb.getProgress();
                if (idx == committed) return;
                committed = idx;
                applyDpi(dpiPresets[idx]);
            }
        });

        // ── 列表文件密度：左右布局，右侧显示当前值，点开在弹层里选 ──
        //
        // 旧实现是 SeekBar + 下方三段刻度文字，两个问题：
        //   ① 三段文字按 seekbar 宽度均分，左边缘和标题行对不齐；
        //   ② 三档是离散选项，用连续滑块表达不当 —— 手指停在两档之间时，
        //      视觉上没有"已选中"的确定感。
        // 改成「左标题 + 右当前值 ▾」，点整行弹出列表选择，
        // 与压缩格式选择弹窗同一套交互。
        addDensityPickerRow(box);

        // ── 侧边栏不透明度 ──
        final TextView navVal = addSliderRow(box, "侧边栏不透明度",
                navPanelAlpha + "%", navPanelAlpha, 0, 100, 10, null);
        lastSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                navVal.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                navPanelAlpha = sb.getProgress();
                savePrefInt(PREF_NAV_PANEL_ALPHA, navPanelAlpha);
                persistState();
                applyNavPanelAlpha();
            }
        });

        // ── 文件管理主题：独立定制文件列表页 ──
        addInfoRow(box, "文件管理主题",
                fmThemeOn ? "已启用 · 独立背景与卡片透明度" : "未启用 · 跟随全局主题",
                fmThemeOn, v ->
                        showInSettingsDialog("文件管理主题", this::buildFileManagerTheme));

        return box;
    }

    /**
     * 「文件管理主题」页：只影响文件列表页，不动设置页。
     *
     * <p>关闭时所有取值回落到全局主题（见 fmActiveXxx 取值器），
     * 所以这里的滑块即使调过也不会泄漏到别处。</p>
     */
    private View buildFileManagerTheme() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("文件管理主题"));

        // ── 总开关 ──
        addInfoRow(box, "启用独立主题",
                fmThemeOn ? "文件列表页使用下面的设置" : "文件列表页跟随全局主题",
                fmThemeOn, v -> {
                    fmThemeOn = !fmThemeOn;
                    savePrefBool(PREF_FM_THEME_ON, fmThemeOn);
                    persistState();
                    applyBackground();
                    applyFmThemeToAdapters();
                    hideSettingsDialog();
                    showInSettingsDialog("文件管理主题", this::buildFileManagerTheme);
                    UiKit.toast(ctx, fmThemeOn ? "文件管理主题已启用" : "已恢复跟随全局");
                });

        if (!fmThemeOn) {
            addInfoRow(box, "背景图", "启用独立主题后可单独设置", false, v ->
                    UiKit.toast(ctx, "请先启用独立主题"));
            return box;
        }

        // ── 独立背景图 ──
        addInfoRow(box, "背景图",
                fmBgFile != null ? "已设置 · 点击更换" : "未设置 · 使用全局背景图",
                fmBgFile != null, v -> pickFmBackground());

        final TextView bgAlphaVal = addSliderRow(box, "背景不透明度",
                fmBgAlpha + "%", fmBgAlpha, 0, 100, 10, null);
        lastSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                bgAlphaVal.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                fmBgAlpha = sb.getProgress();
                savePrefInt(PREF_FM_BG_ALPHA, fmBgAlpha);
                persistState();
                applyBackground();
            }
        });

        final TextView bgBlurVal = addSliderRow(box, "背景模糊度",
                fmBgBlur == 0 ? "不模糊" : String.valueOf(fmBgBlur),
                fmBgBlur, 0, 25, 1, null);
        lastSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                bgBlurVal.setText(p == 0 ? "不模糊" : String.valueOf(p));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                fmBgBlur = sb.getProgress();
                savePrefInt(PREF_FM_BG_BLUR, fmBgBlur);
                persistState();
                applyBackground();
            }
        });

        // ── 文件行卡片透明度 ──
        final TextView rowVal = addSliderRow(box, "文件行卡片不透明度",
                fmRowAlpha + "%", fmRowAlpha, 0, 100, 10, null);
        lastSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                rowVal.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                fmRowAlpha = sb.getProgress();
                savePrefInt(PREF_FM_ROW_ALPHA, fmRowAlpha);
                persistState();
                applyFmThemeToAdapters();
            }
        });

        // ── 列表密度 ──
        final String[] densNames = {"标准", "紧凑", "宽松"};
        final int[] densVals = {0, 1, 3};
        // 用 final 存「当前是第几档」，lambda 里要用；在循环里赋值的话不是 effectively final
        final int curDens;
        {
            int found = 0;
            for (int i = 0; i < densVals.length; i++) {
                if (densVals[i] == fmDensity) { found = i; break; }
            }
            curDens = found;
        }
        addInfoRow(box, "列表密度", densNames[curDens], fmDensity != 0, v -> {
            LinearLayout pbox = new LinearLayout(ctx);
            pbox.setOrientation(LinearLayout.VERTICAL);
            pbox.addView(UiKit.sectionHeader(ctx, "列表密度"));
            // 先建 dialog 再建行：行回调里要用 d 关窗，声明必须在 lambda 之前
            final android.app.Dialog d = UiKit.glassDialog(ctx, pbox);
            for (int i = 0; i < densNames.length; i++) {
                final int idx = i;
                pbox.addView(buildSimpleRow(densNames[i],
                        idx == curDens ? "当前" : null, vv -> {
                            fmDensity = densVals[idx];
                            savePrefInt(PREF_FM_DENSITY, fmDensity);
                            persistState();
                            applyFmThemeToAdapters();
                            UiKit.dismissWithAnim(d);
                            hideSettingsDialog();
                            showInSettingsDialog("文件管理主题", this::buildFileManagerTheme);
                        }));
            }
            d.show();
        });

        return box;
    }

    /**
     * 列表文件密度的选择行：左标题 + 右当前值（带 ▾），点整行弹层选择。
     *
     * <p>比 SeekBar 好的地方：三档是<b>离散</b>选项，列表里带勾选标记，
     * 「当前是哪个」一目了然；标题与右侧值在同一基线，左边缘也和
     * 上面的「界面主题」「明暗模式」完全对齐。</p>
     */
    private void addDensityPickerRow(LinearLayout box) {
        final String[] names = {"紧凑", "标准", "宽松"};
        final TextView value = new TextView(ctx);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        value.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        value.setText(names[Math.max(0, Math.min(2, listDensity - 1))] + "  ▾");
        final View row = addPickerRow(box, "列表文件密度", value, null);
        row.setOnClickListener(v -> showDensityPopup(row, names, value));
    }

    /**
     * 密度选择：锚定在当前选项行正下方的小弹窗。
     *
     * <p>比整块居中弹窗好在：选择项紧贴触发它的那行，用户视线不用
     * 跳到屏幕中央再跳回来；也不会盖住整个设置页。
     * 带 1px 描边 + 阴影，与页面背景分离。</p>
     */
    private void showDensityPopup(final View anchor, final String[] names,
                                  final TextView valueLabel) {
        final android.widget.PopupWindow[] pw = new android.widget.PopupWindow[1];

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        box.setPadding(pad, pad, pad, pad);

        int surface = UiKit.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int stroke = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOutline);
        android.graphics.drawable.GradientDrawable gd = UiKit.rounded(
                ctx, dp(8), surface, stroke);
        gd.setStroke(dp(1), stroke);   // 明确边框
        box.setBackground(gd);

        for (int i = 0; i < names.length; i++) {
            final int level = i + 1;
            final boolean cur = (listDensity == level);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundResource(cur
                    ? R.drawable.bg_ripple_round : R.drawable.bg_ripple_round);
            row.setClickable(true);

            TextView tv = new TextView(ctx);
            tv.setText(names[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTextColor(UiKit.color(ctx, cur
                    ? com.google.android.material.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOnSurface));
            row.addView(tv, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (cur) {
                TextView tick = new TextView(ctx);
                tick.setText("✓");
                tick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tick.setTextColor(UiKit.color(ctx,
                        com.google.android.material.R.attr.colorPrimary));
                row.addView(tick);
            }

            row.setOnClickListener(v -> {
                applyDensity(level);
                if (valueLabel != null) valueLabel.setText(names[level - 1] + "  ▾");
                if (pw[0] != null) pw[0].dismiss();
            });
            box.addView(row);
        }

        android.widget.PopupWindow p = new android.widget.PopupWindow(box,
                dp(150), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        p.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        p.setElevation(dp(8));
        p.setOutsideTouchable(true);
        p.setFocusable(true);
        pw[0] = p;
        // showAsDropDown 默认以锚点行的**左边缘**为基准，弹窗会甩到最左边。
        // 这里等布局完成后再按「右对齐锚点行右边缘」重新定位：
        // xoff = 锚点宽 - 弹窗宽，让弹窗右边贴住行的右边（也就是值标签那一侧）。
        anchor.post(() -> {
            if (anchor.getWindowToken() == null) return;
            int anchorW = anchor.getWidth();
            int pwW = dp(150);
            int xoff = anchorW - pwW;
            if (xoff < 0) xoff = 0;   // 锚点比弹窗窄时退回左对齐
            p.showAsDropDown(anchor, xoff, dp(2));
        });
    }

    /**
     * 通用「左标题 + 右值」行。
     *
     * @param valueView 右侧承载当前值的 View（调用方负责文字与样式）
     */
    private View addPickerRow(LinearLayout parent, String title, View valueView,
                              View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(12));
        applyCardSurface(row, -1, false);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(valueView);
        parent.addView(row);
        return row;
    }

    /** addSliderRow 最近创建的 SeekBar（供调用方挂监听） */
    private android.widget.SeekBar lastSlider;

    /**
     * 添加一行「标题 + 当前值 + 进度条」设置项。
     *
     * @param tickLabels 进度条下方的档位文字；null 表示不显示刻度
     * @return 右侧显示当前值的 TextView
     */
    private TextView addSliderRow(LinearLayout parent, String title, String initValue,
                                  int progress, int min, int max, int step,
                                  String[] tickLabels) {
        // 有分组时改投到分组卡片（见 addSettingsRow 的说明）
        final LinearLayout host = settingsGroup != null ? settingsGroup : parent;
        final boolean inGroup = host == settingsGroup;
        if (inGroup) addGroupDivider(host);

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        // 滑块标题、数值、刻度都直接写在 wrap 里，
        // 分组外时 wrap 必须自己给背景，否则裸露在背景图上。
        wrap.setPadding(dp(12), dp(10), dp(12), dp(8));
        if (!inGroup) {
            applyCardSurface(wrap, -1, false);
            wrap.setTag(CARD_TAG_KEY, Boolean.TRUE);
        }
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!inGroup) wrapLp.topMargin = dp(6);
        host.addView(wrap, wrapLp);

        // 标题行：左标题，右当前值
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTitle.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        titleRow.addView(tvTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvValue = new TextView(ctx);
        tvValue.setText(initValue);
        tvValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvValue.setTypeface(null, android.graphics.Typeface.BOLD);
        tvValue.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        titleRow.addView(tvValue);

        // 进度条
        android.widget.SeekBar bar = new android.widget.SeekBar(ctx);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, progress - min)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(6);
        wrap.addView(bar, barLp);
        lastSlider = bar;

        // 刻度文字：与 SeekBar 轨道对齐
        // SeekBar 的圆点在 [thumbRadius, width - thumbRadius] 区间移动，
        // 而一个 LinearLayout 等分会把文字按 [0, width] 排布 —— 两者差一个
        // thumb 半径，所以必须给刻度容器加左右同样的内边距，圆点才正好落在
        // 对应文字的中央。
        if (tickLabels != null && tickLabels.length > 0) {
            int thumbR = dp(8);   // SeekBar 默认 thumb 半径约 8dp（随主题略有差异）
            LinearLayout ticks = new LinearLayout(ctx);
            ticks.setOrientation(LinearLayout.HORIZONTAL);
            ticks.setPadding(thumbR, 0, thumbR, 0);
            for (int i = 0; i < tickLabels.length; i++) {
                TextView tv = new TextView(ctx);
                tv.setText(tickLabels[i]);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
                tv.setTextColor(UiKit.color(ctx,
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
                tv.setAlpha(0.7f);
                tv.setGravity(Gravity.CENTER);
                // 首尾两项各占一档宽度，居中到端点；中间项等分
                ticks.addView(tv, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = dp(2);
            wrap.addView(ticks, tLp);
        }

        return tvValue;
    }

    /** DPI 重建专用 handler（onDetach 单独取消，不影响主 UI 回调通道） */
    private android.os.Handler dpiHandler;

    /** 应用 DPI：落盘 + 重建界面 */
    private void applyDpi(int dpi) {
        com.aliya.hyfilemgr.util.DpiUtils.setOverrideDpi(dpi);
        savePrefInt(PREF_DPI, dpi);
        UiKit.toast(ctx, "已切换到 " + dpiLabel(dpi) + "，界面即将重建");
        // WeakReference：局部 final 强引用会让 onDetach 置空失效，
        // 400ms 后仍对已销毁 Activity 调 recreate 抛 IllegalStateException
        final java.lang.ref.WeakReference<android.app.Activity> ref =
                new java.lang.ref.WeakReference<>(hostActivity);
        if (hostActivity != null) {
            if (dpiHandler == null) {
                dpiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
            dpiHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    android.app.Activity a = ref.get();
                    if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                        try { a.recreate(); } catch (Throwable ignored) { }
                    }
                }
            }, 400);
        } else {
            UiKit.toast(ctx, "重启应用后生效");
        }
    }

    /** 应用列表密度（立即生效，无需重启） */
    private void applyDensity(int density) {
        listDensity = Math.max(1, Math.min(3, density));
        savePrefInt(PREF_DENSITY, listDensity);
        for (Pane p : panes) {
            if (p != null && p.adapter != null) p.adapter.setDensity(listDensity);
        }
        String[] names = {"紧凑", "标准", "宽松"};
        UiKit.toast(ctx, "列表密度：" + names[listDensity - 1]);
    }

    /** DPI 档位显示文字 */
    private String dpiLabel(int dpi) {
        return dpi <= 0 ? "跟随系统" : (dpi + " dpi");
    }

    /** 文件管理设置页：隐藏文件 / 缩略图 / 排序 */
    private View buildFileSettings() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeaderInCard("文件管理"));

        addInfoRow(box, "隐藏 . 开头文件",
                showHidden ? "正在显示" : "已隐藏",
                showHidden, v -> {
                    showHidden = !showHidden;
                    // 落盘优先：先写偏好再刷新列表，避免 reloadAll 异常导致设置丢失
                    savePrefBool(PREF_SHOW_HIDDEN, showHidden);
                    persistState();
                    try {
                        reloadAll();
                    } catch (Throwable ignored) {
                    }
                    // 必须重建本页：addInfoRow 的状态文字与圆点是构建时写死的，
                    // 不重建的话点了以后界面还显示旧状态，看着像没生效。
                    hideSettingsDialog();
                    showFileSettings();
                    UiKit.toast(ctx, showHidden ? "已显示隐藏文件" : "已隐藏 . 开头文件");
                });

        addInfoRow(box, "缩略图",
                thumbsEnabled ? "已开启（图片/视频/APK 预览）" : "已关闭（仅显示类型图标）",
                thumbsEnabled, v -> {
                    thumbsEnabled = !thumbsEnabled;
                    savePrefBool(PREF_THUMBS, thumbsEnabled);
                    persistState();
                    for (Pane pp : panes) {
                        if (pp != null && pp.adapter != null) {
                            pp.adapter.setThumbsEnabled(thumbsEnabled);
                        }
                    }
                    hideSettingsDialog();
                    showFileSettings();
                    UiKit.toast(ctx, thumbsEnabled ? "已开启缩略图" : "已关闭缩略图");
                });

        addInfoRow(box, "应用图标角标",
                appBadgeEnabled
                        ? "Android/data 等目录下显示应用图标"
                        : "已关闭（只显示文件夹图标）",
                appBadgeEnabled, v -> {
                    appBadgeEnabled = !appBadgeEnabled;
                    savePrefBool(PREF_APP_BADGE, appBadgeEnabled);
                    persistState();
                    for (Pane pp : panes) {
                        if (pp != null && pp.adapter != null) {
                            pp.adapter.setAppBadgeEnabled(appBadgeEnabled);
                        }
                    }
                    hideSettingsDialog();
                    showFileSettings();
                    UiKit.toast(ctx, appBadgeEnabled ? "已显示应用图标" : "已关闭应用图标");
                });

        // ── 回收站（展开项）──
        final LinearLayout trashBox = new LinearLayout(ctx);
        trashBox.setOrientation(LinearLayout.VERTICAL);
        // 展开区里的卡片本身已有底色，这里只做视觉缩进：
        // 左侧内缩 12dp，让「标题行 → 展开内容」的从属关系看得出来。
        // 不给 trashBox 自己加底色，否则会变成卡片套卡片的双层描边。
        trashBox.setPadding(dp(12), dp(4), 0, 0);
        trashBox.setVisibility(trashExpanded ? View.VISIBLE : View.GONE);

        addInfoRow(trashBox, "启用回收站",
                trashEnabled ? "开启 · 侧边栏显示回收站入口" : "关闭 · 删除直接物理删除",
                trashEnabled, v -> {
                    trashEnabled = !trashEnabled;
                    savePrefBool(PREF_TRASH_ENABLED, trashEnabled);
                    persistState();
                    hideSettingsDialog();
                    showFileSettings();
                    UiKit.toast(ctx, trashEnabled ? "回收站已启用" : "回收站已关闭");
                });

        addInfoRow(trashBox, "默认移入回收站",
                trashDefault ? "开启 · 删除时移到回收站，可恢复" : "关闭 · 删除时直接物理删除",
                trashDefault, v -> {
                    if (!trashEnabled) {
                        UiKit.toast(ctx, "请先启用回收站");
                        return;
                    }
                    trashDefault = !trashDefault;
                    savePrefBool(PREF_TRASH_DEFAULT, trashDefault);
                    persistState();
                    hideSettingsDialog();
                    showFileSettings();
                    UiKit.toast(ctx, trashDefault ? "删除将移入回收站" : "删除将直接清除");
                });

        addInfoRow(trashBox, "自动清理周期",
                trashKeepDays <= 0 ? "不自动清理"
                        : ("保留 " + trashKeepDays + " 天 · 启动时检查"),
                trashKeepDays > 0, v -> {
                    showTrashKeepDaysPicker();
                });

        final LinearLayout trashHeader = new LinearLayout(ctx);
        trashHeader.setOrientation(LinearLayout.HORIZONTAL);
        trashHeader.setGravity(Gravity.CENTER_VERTICAL);
        trashHeader.setPadding(dp(12), dp(12), dp(12), dp(12));
        // 与其它卡片共用同一套底色与描边；原来用静态 bg_ripple_round，
        // 既跟不上卡片透明度，也没有那层浅描边，看着像裸露的一行
        applyCardSurface(trashHeader, -1, false);
        trashHeader.setTag(CARD_TAG_KEY, Boolean.TRUE);
        trashHeader.setClickable(true);
        ImageView trashArrow = new ImageView(ctx);
        trashArrow.setImageResource(R.drawable.ic_chevron_right);
        trashArrow.setColorFilter(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        trashArrow.setLayoutParams(new LinearLayout.LayoutParams(dp(14), dp(14)));
        trashArrow.setRotation(trashExpanded ? 90f : 0f);
        trashHeader.addView(trashArrow);
        TextView trashTitle = new TextView(ctx);
        trashTitle.setText("回收站");
        trashTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        trashTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        trashTitle.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ttLp.leftMargin = dp(8);
        trashHeader.addView(trashTitle, ttLp);
        trashHeader.setOnClickListener(v -> {
            trashExpanded = !trashExpanded;
            trashArrow.animate().rotation(trashExpanded ? 90f : 0f).setDuration(180).start();
            if (trashExpanded) {
                trashBox.setVisibility(View.VISIBLE);
                trashBox.setAlpha(0f);
                trashBox.animate().alpha(1f).setDuration(180).start();
            } else {
                trashBox.animate().alpha(0f).setDuration(150)
                        .withEndAction(() -> trashBox.setVisibility(View.GONE)).start();
            }
        });
        box.addView(trashHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(trashBox);
        return box;
    }

    /** 状态行：图标 + 标题 + 状态描述 + 勾选标记 */
    private void addInfoRow(LinearLayout parent, String title, String status,
                            boolean ok, View.OnClickListener onClick) {
        // 有分组时改投到分组卡片（见 addSettingsRow 的说明）
        final LinearLayout host = settingsGroup != null ? settingsGroup : parent;
        final boolean inGroup = host == settingsGroup;
        if (inGroup) addGroupDivider(host);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        if (!inGroup) {
            applyCardSurface(row, -1, false);
        }
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!inGroup) rowLp.topMargin = dp(6);   // 卡片之间留呼吸
        host.addView(row, rowLp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, tLp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTitle.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        texts.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText(status);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvSub.setTextColor(UiKit.color(ctx, ok
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvSub.setAlpha(ok ? 1f : 0.75f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        texts.addView(tvSub, subLp);

        // 状态点
        View dot = new View(ctx);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(UiKit.color(ctx, ok
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOutline));
        dot.setBackground(g);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.leftMargin = dp(8);
        row.addView(dot, dotLp);
    }

    /**
     * 侧边栏头部：48dp 圆形头像 + 右侧「文件名 + 版本/路径摘要」纵列，
     * 与 HY_VQ nav_header.xml 的横向布局一致（头像左置、文字右侧纵列）。
     */
    private void buildSidebarHeader(LinearLayout content) {
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(10), dp(4), dp(8));
        content.addView(head);

        // ── 圆形头像（48dp，MaterialCardView 自动裁剪成圆）──
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(ctx);
        card.setRadius(dp(25));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimaryContainer));

        ImageView avatar = new ImageView(ctx);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageResource(R.drawable.ic_folder);
        avatar.setColorFilter(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        avatar.setPadding(dp(11), dp(11), dp(11), dp(11));
        card.addView(avatar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(50), dp(50));
        head.addView(card, cardLp);

        // ── 右侧：标题 + 副标题 ──
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.leftMargin = dp(12);
        head.addView(texts, textsLp);

        TextView title = new TextView(ctx);
        title.setText("HY 文件管理");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("v" + appVersion() + "  ·  本地文件");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setAlpha(0.75f);
        sub.setSingleLine(true);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        texts.addView(sub, subLp);

        // ── 存储空间进度条（紧跟头像下方）──
        buildStorageBar(content);
    }

    /** 应用版本名（取 PackageManager，失败返回 "0.1.0"） */
    private String appVersion() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0.1.0";
        }
    }

    /**
     * 存储空间条：已用/总容量 + 百分比进度条（仿 Material LinearProgressIndicator）。
     *
     * <p>取内部存储（Environment.getDataDirectory 所在分区）的 total/free，
     * 异步计算避免主线程 IO。用量超 90% 时进度条转警示色。</p>
     */
    private void buildStorageBar(LinearLayout content) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = dp(2);
        boxLp.bottomMargin = dp(8);
        boxLp.leftMargin = dp(4);
        boxLp.rightMargin = dp(4);
        content.addView(box, boxLp);

        // 顶部一行：左「已用 x / y」 右「xx%」
        LinearLayout labelRow = new LinearLayout(ctx);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(labelRow);

        final TextView tvLeft = new TextView(ctx);
        tvLeft.setText("存储空间");
        tvLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvLeft.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvLeft.setSingleLine(true);
        labelRow.addView(tvLeft, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvPct = new TextView(ctx);
        tvPct.setText("—");
        tvPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvPct.setTypeface(null, android.graphics.Typeface.BOLD);
        tvPct.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary));
        tvPct.setSingleLine(true);
        labelRow.addView(tvPct);

        // 进度条容器（自定义 drawable，圆角 + 分段色）
        final android.widget.FrameLayout track = new android.widget.FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable trackBg = UiKit.rounded(ctx, 3,
                UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHighest), 0);
        track.setBackground(trackBg);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        trackLp.topMargin = dp(5);
        box.addView(track, trackLp);

        final View fill = new View(ctx);
        android.graphics.drawable.GradientDrawable fillBg = UiKit.rounded(ctx, 3, 0
                | UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary), 0);
        fill.setBackground(fillBg);
        track.addView(fill, new android.widget.FrameLayout.LayoutParams(0, dp(5)));

        // 底部：可用空间
        final TextView tvFree = new TextView(ctx);
        tvFree.setText("可用 —");
        tvFree.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tvFree.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvFree.setAlpha(0.7f);
        tvFree.setSingleLine(true);
        LinearLayout.LayoutParams freeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        freeLp.topMargin = dp(3);
        box.addView(tvFree, freeLp);

        // 异步取容量（StatFs 在某些目录上可能耗时）
        new Thread(() -> {
            long total = 0, free = 0;
            try {
                android.os.StatFs st = new android.os.StatFs(
                        Environment.getExternalStorageDirectory().getAbsolutePath());
                total = st.getTotalBytes();
                free = st.getAvailableBytes();
            } catch (Throwable t) {
                try {
                    android.os.StatFs st2 = new android.os.StatFs("/data");
                    total = st2.getTotalBytes();
                    free = st2.getAvailableBytes();
                } catch (Throwable ignored) {
                }
            }
            final long ft = total, ff = free;
            handler.post(() -> {
                if (ft <= 0) {
                    tvLeft.setText("存储空间不可用");
                    tvPct.setText("—");
                    return;
                }
                long used = Math.max(0, ft - ff);
                int pct = (int) Math.round(used * 100.0 / ft);
                tvLeft.setText("已用 " + fmtStorageSize(used) + " / " + fmtStorageSize(ft));
                tvPct.setText(pct + "%");
                tvFree.setText("可用 " + fmtStorageSize(ff));

                // 进度条宽度（用 post 等布局完成拿到 track 宽度）
                track.post(() -> {
                    int w = track.getWidth();
                    if (w <= 0) return;
                    float ratio = (float) used / ft;
                    if (ratio < 0.005f) ratio = 0.005f;   // 极小值也显示一点，避免"空条"
                    int fw = Math.max(dp(2), Math.round(w * Math.min(1f, ratio)));
                    android.view.ViewGroup.LayoutParams lp = fill.getLayoutParams();
                    lp.width = fw;
                    fill.setLayoutParams(lp);
                    // 高占用改警示色
                    boolean warn = pct >= 90;
                    int color = UiKit.color(ctx, warn
                            ? com.google.android.material.R.attr.colorError
                            : com.google.android.material.R.attr.colorPrimary);
                    android.graphics.drawable.GradientDrawable g = UiKit.rounded(ctx, 3, color, 0);
                    fill.setBackground(g);
                    tvPct.setTextColor(color);
                });
            });
        }, "storage-stat").start();
    }

    private void rebuildNavContent() {
        if (navPanel == null) return;
        navPanel.removeAllViews();

        // 内容区滚动（合并列表可能超出屏高）
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        navPanel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 底部固定：设置入口（不随内容滚动）──
        buildSidebarSettingsEntry();

        // ══════ 头部：头像 + 用户名（HY_VQ 同款） + 存储空间进度条 ══════
        buildSidebarHeader(content);
        // 当前路径（次要信息，弱化显示）
        TextView curPath = new TextView(ctx);
        curPath.setText(displayPath(active.path));
        curPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        curPath.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        curPath.setAlpha(0.6f);
        curPath.setSingleLine(true);
        curPath.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams curLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        curLp.topMargin = dp(2);
        curLp.bottomMargin = dp(4);
        curLp.leftMargin = dp(4);
        content.addView(curPath, curLp);

        // ══════ 第一组：本地（可折叠）══════
        final LinearLayout localBody = new LinearLayout(ctx);
        localBody.setOrientation(LinearLayout.VERTICAL);
        // 顺序必须「先标题、后内容」，否则标题会出现在内容下方
        // 可见性由 buildCollapsibleHeader 依 navLocalExpanded 初始化
        content.addView(buildCollapsibleHeader("本地", localBody, navLocalExpanded, on -> {
            navLocalExpanded = on;
            savePrefBool(PREF_NAV_LOCAL_OPEN, on);
        }));
        content.addView(localBody);

        // ── 合并列表：根目录 + 内部存储 + SAF 授权目录（FCL 式"类挂载"，统一排序） ──
        List<MountEntry> entries = buildMountList();
        // 诊断（错误可见性）：一个授权项都没有且加载有异常 → toast 真实原因
        //（仅 SAF 挂载显示开启时提示；隐藏期间不误报）
        if (SHOW_SAF_MOUNTS) {
            int safCount = 0;
            for (MountEntry me : entries) if (me.deletable) safCount++;
            if (safCount == 0 && mountLoadError != null && !mountLoadError.isEmpty()) {
                UiKit.alert(ctx, "授权列表加载失败：" + mountLoadError);
            }
        }
        for (final MountEntry e : entries) {
            localBody.addView(buildMountRow(localBody, e));
        }

        // ── 分隔线 + 授权按钮（列表底部，触发系统文件选择器）；与授权项同开关隐藏 ──
        if (SHOW_SAF_MOUNTS) {
            View divider = new View(ctx);
            divider.setBackgroundColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant));
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            divLp.setMargins(dp(16), dp(8), dp(16), dp(8));
            localBody.addView(divider, divLp);

            TextView authBtn = new TextView(ctx);
            authBtn.setText("＋授权目录");
            authBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            authBtn.setGravity(Gravity.CENTER);
            authBtn.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary));
            authBtn.setBackground(UiKit.rounded(ctx, 0,
                    UiKit.color(ctx, com.google.android.material.R.attr.colorPrimaryContainer), 0));
            authBtn.setClickable(true);
            authBtn.setOnClickListener(v -> {
                closeNavSidebar(); // SAF 选择器会压住所有悬浮窗，先收起侧边栏
                openSafAuthorize();
            });
            LinearLayout.LayoutParams authLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            authLp.bottomMargin = dp(6);
            localBody.addView(authBtn, authLp);
        }

        // ══════ 第二组：工具（可折叠）══════
        final LinearLayout toolsBody = new LinearLayout(ctx);
        toolsBody.setOrientation(LinearLayout.VERTICAL);
        toolsBody.setVisibility(navToolsExpanded ? View.VISIBLE : View.GONE);

        // 工具项：卡片式（与「本地」组的挂载行视觉一致，但更紧凑）
        // 「文件管理」= 回到文件列表本身，不是进设置里的显示选项页。
        //
        // 两个理由：
        //   ① 文案叫「文件管理」，用户预期是回到浏览界面；
        //   ② 从这里跳设置子页会让顶栏变成返回键，和"回文件列表"的直觉打架。
        // 且**不动任何窗格的路径与焦点** —— 用户只是想关掉设置看看文件，
        // 不希望顺手被换目录。
        toolsBody.addView(buildToolCard("文件管理",
                "回到文件列表（不改变当前目录）",
                R.drawable.ic_folder, v -> {
                    closeNavSidebarThen(() -> setContentMode(ContentMode.FILES));
                }));

        // 图片查看器：相册网格页（扫描路径可在设置/网格页 + chip 中管理）
        toolsBody.addView(buildToolCard("图片查看器",
                "按扫描路径浏览相册图片",
                R.drawable.ic_img, v -> {
                    closeNavSidebarThen(this::openGallery);
                }));


        // toolsBody 此前从未设初始可见性 → navToolsExpanded=false 时首次仍展开，
        // 表现为「要点两次才能收起/展开」
        content.addView(buildCollapsibleHeader("工具", toolsBody, navToolsExpanded, on -> {
            navToolsExpanded = on;
            savePrefBool(PREF_NAV_TOOLS_OPEN, on);
        }));
        content.addView(toolsBody);
    }

    /**
     * 侧边栏工具项卡片：图标 + 标题 + 副标题，圆角卡片 + 1dp 描边。
     *
     * <p>与「本地」组的挂载行保持同一视觉语言（圆角 + 描边 + 左侧图标），
     * 但没有拖拽手柄与删除按钮，更紧凑。</p>
     */
    private View buildToolCard(String title, String subtitle, int iconRes,
                               View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(12);
        int padV = dp(10);
        card.setPadding(padH, padV, padH, padV);
        card.setClickable(true);
        card.setFocusable(true);

        // 背景：圆角 12dp + 主题卡片色 + 1dp 描边（与挂载行一致）
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12));
        bg.setColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        bg.setStroke(dp(1), UiKit.color(ctx,
                com.google.android.material.R.attr.colorOutlineVariant));
        card.setBackground(bg);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        int is = dp(20);
        card.addView(icon, new LinearLayout.LayoutParams(is, is));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(12);
        card.addView(texts, tLp);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        texts.addView(tv);

        TextView sub = new TextView(ctx);
        sub.setText(subtitle);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        sub.setAlpha(0.72f);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub);

        card.setOnClickListener(v -> {
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(130).start()).start();
            onClick.onClick(v);
        });

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.bottomMargin = dp(6);
        wrap.setLayoutParams(wrapLp);
        return wrap;
    }

    /** 侧边栏分组展开状态（重建时保持） */
    /** 侧边栏「本地」分组展开状态（持久化：nav_local_open） */
    private boolean navLocalExpanded = true;
    /** 侧边栏「工具」分组展开状态（持久化：nav_tools_open） */
    private boolean navToolsExpanded = true;

    /**
     * 可折叠分组标题行：箭头 + 标题 + 右侧计数/说明。
     *
     * <p>点击切换可见性并播放旋转箭头 + 淡入淡出动画；状态写回
     * {@code expandedFlag} 对应的字段（由调用方以 lambda 提供 getter）。</p>
     *
     * @param body     被折叠的内容容器
     * @param expanded 当前是否展开的 getter（调用方字段）
     */
    /**
     * 可折叠分组标题（侧边栏「本地」「工具」用）。
     *
     * <p><b>为什么要传 setter 而不是标题字符串判断</b>：旧实现写回状态时用
     * {@code "本地".equals(title)} 判字段，标题一改文案就静默失效。
     * 现在由调用方传入 {@code setExpanded} 明确写哪个字段。</p>
     *
     * <p><b>为什么要传 initial</b>：旧实现只有 {@code localBody} 设了初始可见性，
     * {@code toolsBody} 从未设置（默认 VISIBLE）——于是
     * navToolsExpanded=false 时首次进入仍展开，点一次变成「收起」，
     * 看起来像「要点两次才能展开」。现在两边都由同一份状态初始化。</p>
     *
     * @param title       分组标题（仅显示用，不再参与状态判定）
     * @param body        内容容器
     * @param expanded    当前是否展开（首次构建时的初始值，也用于初始化箭头）
     * @param setExpanded 状态写回（同时负责持久化）
     */
    private View buildCollapsibleHeader(String title, final LinearLayout body,
                                        boolean expanded,
                                        final java.util.function.Consumer<Boolean> setExpanded) {
        // 初始可见性与箭头方向都来自同一份状态——这是「点两次」的根因修复
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        body.setAlpha(1f);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(10), dp(8), dp(8));
        head.setBackgroundResource(R.drawable.bg_ripple_round);
        head.setClickable(true);

        // 折叠箭头
        final ImageView arrow = new ImageView(ctx);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        int as = dp(14);
        arrow.setColorFilter(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(as, as);
        arrow.setLayoutParams(aLp);
        arrow.setRotation(expanded ? 90f : 0f);
        head.addView(arrow);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(8);
        head.addView(tv, tLp);

        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = dp(6);
        head.setOnClickListener(v -> {
            boolean nowVisible = body.getVisibility() != View.VISIBLE;
            // 箭头旋转
            arrow.animate().rotation(nowVisible ? 90f : 0f).setDuration(180).start();
            // 内容淡入淡出
            if (nowVisible) {
                body.setVisibility(View.VISIBLE);
                body.setAlpha(0f);
                body.animate().alpha(1f).setDuration(180).start();
            } else {
                body.animate().alpha(0f).setDuration(150)
                        .withEndAction(() -> {
                            body.setVisibility(View.GONE);
                            body.setAlpha(1f);
                        }).start();
            }
            // 写回状态：由调用方提供的 setter 决定落到哪个字段
            setExpanded.accept(nowVisible);
        });
        return head;
    }

    /** SAF 授权完成回调（MainActivity.onActivityResult 转发）：
     *  无论侧边栏当前是否可见，都重建挂载列表；不可见时自动展开展示（对齐 MT：授权后立即看到挂载项） */
    public void onSafGranted() {
        if (navOverlay == null || navPanel == null) return;
        if (navOverlay.getVisibility() == View.VISIBLE) {
            navPanel.post(this::rebuildNavContent);
        } else {
            // 授权按钮点击时先 closeNavSidebar() 收起 → 返回后自动展开，避免"授权后无变化"
            navPanel.post(() -> showNavSidebar());
        }
        UiKit.toast(ctx, "授权成功，目录已挂载");
    }

    /** 静态入口：MainActivity 转发 SAF 授权结果 */
    public static void onSafGrantedStatic() {
        if (activeInstance != null) activeInstance.onSafGranted();
    }

    /** 打开系统文件选择器（SAF）授权目录：选择后由 MainActivity 持久化（persisted URI permission），即 MT 的"挂载" */
    private void openSafAuthorize() {
        if (hostActivity == null) {
            UiKit.alert(ctx, "宿主 Activity 不可用，无法打开系统文件选择器");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            hostActivity.startActivityForResult(intent, REQ_SAF_MOUNT);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开系统文件选择器：" + t.getMessage());
        }
    }

    /** 关闭内部侧边栏：面板左滑 + 遮罩淡出同步（修复透明度突变闪烁），结束后隐藏复位 */
    /**
     * 关闭侧边栏，并在**动画真正结束、导航层已 GONE** 后执行后续动作。
     *
     * <p>直接 closeNavSidebar() 只启动动画，随后立刻弹对话框会让
     * 对话框的模糊遮罩把仍在收起的侧边栏一起拍进去。</p>
     */
    private void closeNavSidebarThen(Runnable then) {
        if (navOverlay == null || navOverlay.getVisibility() != View.VISIBLE) {
            if (then != null) then.run();
            return;
        }
        closeNavSidebar();
        // closeNavSidebar 的动画时长 200ms，这里多留 40ms 余量，
        // 保证 navOverlay 已经 setVisibility(GONE)
        if (navPanel != null) {
            navPanel.postDelayed(() -> {
                if (then != null) then.run();
            }, 240);
        } else if (then != null) {
            then.run();
        }
    }

    private void closeNavSidebar() {
        if (navOverlay == null || navPanel == null) return;
        navPanel.animate().translationX(-navPanelWidth - dp(20)).setDuration(200).start();
        navScrim.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    navOverlay.setVisibility(View.GONE);
                    navPanel.setTranslationX(0f); // 复位，下次打开动画正常
                    // 清除毛玻璃模糊
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && rootContainer != null) {
                        for (int i = 0; i < rootContainer.getChildCount(); i++) {
                            View c = rootContainer.getChildAt(i);
                            // 同 showNavSidebar：背景图 ImageView 也在这个容器里，
                            // 只判 c != navOverlay 会命中它，强转失败被 catch 吞掉，
                            // 表现为模糊永远清不掉。必须判 instanceof。
                            if (c != navOverlay && c instanceof ViewGroup) {
                                try { ((ViewGroup) c).setRenderEffect(null); } catch (Throwable ignored) {}
                                break;
                            }
                        }
                    }
                }).start();
    }

    /** 侧边栏合并列表条目：根目录/内部存储/SAF授权目录统一建模（FCL 式"类挂载"） */
    private static final class MountEntry {
        final String id;        // "root" / "storage" / SAF uri 字符串
        final String title;
        final String subtitle;  // 存储占用或 uri
        final int iconRes;
        final boolean deletable; // 仅 SAF 授权项可左滑删除
        final boolean enterable; // 可映射真实路径直接进入
        final String target;    // 真实路径（displayPath）；不可进入时打开走 id(uri)
        MountEntry(String id, String title, String subtitle, int iconRes,
                   boolean deletable, boolean enterable, String target) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.iconRes = iconRes;
            this.deletable = deletable;
            this.enterable = enterable;
            this.target = target;
        }
    }

    /**
     * 同步写入单个布尔偏好（commit 而非 apply）。
     *
     * <p>写入失败会把异常打到 logcat，避免静默丢失设置。</p>
     */
    private void savePrefBool(String key, boolean value) {
        try {
            boolean ok = fmPrefs().edit().putBoolean(key, value).commit();
            android.util.Log.i("HYPrefs", "save " + key + "=" + value + " ok=" + ok);
        } catch (Throwable t) {
            android.util.Log.e("HYPrefs", "save " + key + " failed", t);
        }
    }

    private android.content.SharedPreferences fmPrefs() {
        return ctx.getSharedPreferences("hyvq_filemgr", Context.MODE_PRIVATE);
    }

    /** 构建合并列表：根目录 + 内部存储 + SAF 授权目录，按持久化顺序重排（未记录追加末尾） */
    private List<MountEntry> buildMountList() {
        final String rootPath = "/";
        final String storePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        List<MountEntry> list = new ArrayList<>();
        list.add(new MountEntry("root", "根目录", storageInfo(rootPath),
                R.drawable.ic_home, false, true, rootPath));
        list.add(new MountEntry("storage", "内部存储", storageInfo(storePath),
                R.drawable.ic_folder, false, true, storePath));
        // 回收站（用户规划 2026-08-16：回收站 → 左侧边栏）；虚拟路径 trash://
        if (trashEnabled) {
        list.add(new MountEntry(PREFIX_TRASH, "回收站", "已删除文件，可恢复",
                android.R.drawable.ic_menu_delete, false, false, PREFIX_TRASH));
        }
        // SAF 授权目录（临时开关 SHOW_SAF_MOUNTS=false 时隐藏，授权数据保留，改回 true 恢复）
        if (SHOW_SAF_MOUNTS) {
            for (String[] m : listGrantedMounts()) {
                boolean enterable = "1".equals(m[2]);
                // id = content:// uri（删除授权需 Uri.parse(id)；排序持久化也更稳定）；
                // target = displayPath 真实路径（可进入时直接切窗格路径）
                list.add(new MountEntry(m[3], m[0], m[1], R.drawable.ic_folder, true, enterable, m[1]));
            }
        }
        String order = fmPrefs().getString("nav_mount_order", "");
        if (!order.isEmpty()) {
            final List<String> ids = new ArrayList<>();
            for (String s : order.split("\n")) if (!s.isEmpty()) ids.add(s);
            final int n = ids.size();
            list.sort((a, b) -> {
                int ia = ids.indexOf(a.id), ib = ids.indexOf(b.id);
                if (ia < 0) ia = n;
                if (ib < 0) ib = n;
                return Integer.compare(ia, ib);
            });
        }
        return list;
    }

    /** 保存合并列表顺序（按 content 子视图 tag=id 收集） */
    private void saveMountOrder(LinearLayout content) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.getChildCount(); i++) {
            Object tag = content.getChildAt(i).getTag();
            if (tag instanceof String) sb.append(tag).append('\n');
        }
        fmPrefs().edit().putString("nav_mount_order", sb.toString()).commit();
    }

    /** 构建合并列表行：两行结构（图标+名称+三条杠 / 副标题），左滑露出删除（仅授权项）、长按手柄拖排序 */
    private View buildMountRow(final LinearLayout content, final MountEntry e) {
        final FrameLayout host = new FrameLayout(ctx);
        host.setTag(e.id);
        // 选中高亮：可进入项比对真实路径，不可进入（SAF uri）项比对 uri
        final boolean selected = e.enterable
                ? e.target.equals(active.path)
                : e.id.equals(active.path);

        // 删除按钮（右对齐，左滑露出；仅 SAF 授权项显示）
        TextView delBtn = new TextView(ctx);
        delBtn.setText("删除");
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setTextColor(android.graphics.Color.WHITE);
        delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        delBtn.setBackground(UiKit.rounded(ctx, 0, 0xFFE53935, 0)); // 方角删除按钮
        FrameLayout.LayoutParams delLp = new FrameLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT);
        delLp.gravity = Gravity.END;
        if (e.deletable) host.addView(delBtn, delLp);

        // 内容行（不透明背景，覆盖删除按钮；点击=切换焦点窗格到对应目录）
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        // 卡片式背景：圆角 12dp + 主题色 + 轻描边（参考 Her ai_card 风格）
        {
            int rowBg = selected
                    ? UiKit.color(ctx, com.google.android.material.R.attr.colorPrimaryContainer)
                    : UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow);
            android.graphics.drawable.GradientDrawable g = UiKit.rounded(ctx, 0, rowBg, 0);
            g.setCornerRadii(new float[]{dp(14), dp(14), dp(14), dp(14), dp(14), dp(14), dp(14), dp(14)});
            g.setStroke(dp(1), UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant));
            row.setBackground(g);
        }
        row.setClickable(true);
        row.setOnClickListener(v -> {
            closeNavSidebar();
            // SAF 授权目录一律 app 内浏览（同 MT）：可进入用真实路径（可读写），
            // 不可进入（如 Termux 数据目录 SELinux 不可读）用 content:// uri 走 SafStrategy；
            // navPrev 记录进入前路径，供授权根的".."返回
            // 点侧边栏目录 = 「回到文件管理，并把当前焦点窗格跳到这个目录」。
            // 旧实现只改 active 的 path 并 reload：如果当时正停在设置页，
            // 界面还留在设置，用户看到的是一次「消失的跳转」。
            // 所以先切回文件模式，再改路径。
            if (contentMode != ContentMode.FILES) {
                setContentMode(ContentMode.FILES);
            }
            active.navPrev = active.path;
            active.path = e.enterable ? e.target : e.id;
            closeNavSidebar();
            reload(active);
        });

        // 第一行：图标 + 名称 + 三条杠手柄
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(6), 0);
        top.setMinimumHeight(dp(44)); // 参考外部侧边栏 48dp 条目高度(内部略紧凑取 44)
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(e.iconRes);
        icon.setColorFilter(UiKit.color(ctx, selected
                ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnSurface));
        int s = dp(18);
        top.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView tv = new TextView(ctx);
        tv.setText(e.title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        // 未选中行原先用 colorOnSurface 但背景是 colorSurfaceContainerLow，
        // 在浅色主题下两者亮度接近、对比不足。改为加粗 + 全不透明正文色。
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(UiKit.color(ctx, selected
                ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnSurface));
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginStart(dp(10));
        top.addView(tv, tvLp);
        ImageView handle = new ImageView(ctx);
        handle.setImageResource(R.drawable.ic_drag);
        handle.setColorFilter(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        int hs = dp(20);
        top.addView(handle, new LinearLayout.LayoutParams(hs, hs));
        row.addView(top);

        // 第二行：副标题（存储占用 / uri）
        if (e.subtitle != null && !e.subtitle.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(e.subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            // 副标题原用 colorOnSurfaceVariant（灰），与卡片背景对比偏弱。
            // 改用正文色 + 75% 不透明度：仍明显弱于标题，但可读性更好。
            sub.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurface));
            sub.setAlpha(0.75f);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.setMarginStart(dp(36)); // 对齐名称文字（8padding+18图标+10间距）
            subLp.bottomMargin = dp(4);
            row.addView(sub, subLp);
        }

        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hostLp.bottomMargin = dp(6); // 卡片间距(参考 Her 卡片留白)
        host.setLayoutParams(hostLp);
        host.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 左滑删除（仅授权项）+ 长按三条杠拖动排序
        if (e.deletable) enableSwipeDelete(host, row, delBtn, e);
        enableDragSort(content, host, handle, e);
        return host;
    }

    /** 左滑露出删除按钮：超过一半吸附展开，点击取消授权（releasePersistableUriPermission）并重建列表 */
    private void enableSwipeDelete(final FrameLayout host, final View row, final View delBtn, final MountEntry e) {
        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final float[] startTx = new float[1];
        final boolean[] swiping = new boolean[1];
        row.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = ev.getRawX();
                    downY[0] = ev.getRawY();
                    startTx[0] = row.getTranslationX();
                    swiping[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX[0];
                    float dy = ev.getRawY() - downY[0];
                    if (!swiping[0] && Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy)) swiping[0] = true;
                    if (swiping[0]) {
                        row.setTranslationX(Math.max(-dp(64), Math.min(0f, startTx[0] + dx)));
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (swiping[0]) {
                        float tx = row.getTranslationX();
                        row.animate().translationX(tx < -dp(32) ? -dp(64) : 0f).setDuration(120).start();
                        swiping[0] = false;
                        return true;
                    }
                    return false;
            }
            return false;
        });
        delBtn.setOnClickListener(v -> {
            try {
                Uri uri = Uri.parse(e.id);
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                ctx.getContentResolver().releasePersistableUriPermission(uri, flags);
            } catch (Throwable ignored) {
            }
            rebuildNavContent();
        });
    }

    /** 长按三条杠手柄上下拖动排序：拖动时被跨越条目实时让位（用户需求 2026-08-16），
     *  松手按中心位置插入并持久化顺序，所有条目平滑归位 */
    private void enableDragSort(final LinearLayout content, final FrameLayout host,
                                final View handle, final MountEntry e) {
        final float[] downY = new float[1];
        final boolean[] dragging = new boolean[1];
        // 重入保护（真机崩溃 2026-08-16）：content.removeView(host) 会向被移除视图派发
        // ACTION_CANCEL → 再次进入本 onTouch 的 CANCEL 分支 → 又 removeView → 无限递归
        // StackOverflowError。标志置位期间直接放行，打断递归链。
        final boolean[] reordering = new boolean[1];
        // 实时让位：记录每个条目当前平移偏移（被跨越条目累计让位距离），
        // 计算插入位置时用实际位置（layout top + translationY）
        final float[] hostH = new float[1];
        final int[] lastTarget = new int[1];
        final java.util.Map<View, Float> offsets = new java.util.HashMap<>();
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = ev.getRawY();
                    hostH[0] = host.getHeight();
                    lastTarget[0] = content.indexOfChild(host);
                    offsets.clear();
                    for (int i = 0; i < content.getChildCount(); i++) {
                        offsets.put(content.getChildAt(i), 0f);
                    }
                    return false; // 放行给长按检测
                case MotionEvent.ACTION_MOVE:
                    if (dragging[0]) {
                        host.setTranslationY(ev.getRawY() - downY[0]);
                        // 实时计算应插入位置（实际位置 = layout top + translationY）
                        int target = computeTarget(content, host, hostH[0], offsets);
                        if (target != lastTarget[0]) {
                            int hostIdx = content.indexOfChild(host);
                            if (target > lastTarget[0]) {
                                // 向下拖：被跨越（host 下方）条目上移一个身位
                                for (int i = lastTarget[0] + 1; i <= target; i++) {
                                    if (i == hostIdx) continue;
                                    shiftRow(content.getChildAt(i), -hostH[0], offsets);
                                }
                            } else {
                                // 向上拖：被跨越（host 上方）条目下移一个身位
                                for (int i = target; i < lastTarget[0]; i++) {
                                    if (i == hostIdx) continue;
                                    shiftRow(content.getChildAt(i), +hostH[0], offsets);
                                }
                            }
                            lastTarget[0] = target;
                        }
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging[0] && !reordering[0]) {
                        reordering[0] = true;
                        try {
                            int target = computeTarget(content, host, hostH[0], offsets);
                            content.removeView(host);
                            content.addView(host, target);
                            host.setTranslationY(0f);
                            host.setElevation(0f);
                            host.setAlpha(1f);
                            // 所有条目从让位偏移动画归位（平滑结束）
                            for (int i = 0; i < content.getChildCount(); i++) {
                                View c = content.getChildAt(i);
                                if (c == host) continue;
                                c.animate().translationY(0f).setDuration(150).start();
                            }
                            offsets.clear();
                            saveMountOrder(content);
                        } finally {
                            dragging[0] = false;
                            reordering[0] = false;
                        }
                        return true;
                    }
                    return false;
            }
            return false;
        });
        handle.setOnLongClickListener(v -> {
            dragging[0] = true;
            host.setElevation(dp(10));
            host.setAlpha(0.92f);
            return true;
        });
    }

    /** 计算被拖动条目当前应插入的位置（rowCenter 之上的非 host 条目数；实际位置含偏移） */
    private int computeTarget(LinearLayout content, View host, float hostH,
                              java.util.Map<View, Float> offsets) {
        float rowCenter = host.getTop() + hostH / 2f + host.getTranslationY();
        int target = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c == host) continue;
            Float off = offsets.get(c);
            float cCenter = c.getTop() + c.getHeight() / 2f + (off == null ? 0f : off);
            if (rowCenter > cCenter) target++;
        }
        return target;
    }

    /** 让位：条目平移 delta 并记录偏移（100ms 平滑动画） */
    private void shiftRow(View c, float delta, java.util.Map<View, Float> offsets) {
        Float cur = offsets.get(c);
        float off = (cur == null ? 0f : cur) + delta;
        offsets.put(c, off);
        c.animate().translationY(off).setDuration(100).start();
    }

    /** 存储统计："X已用, Y可用"（MT 管理器侧边栏"根目录/内部存储"风格） */
    private String storageInfo(String path) {
        try {
            android.os.StatFs sf = new android.os.StatFs(path);
            long total = sf.getTotalBytes();
            long avail = sf.getAvailableBytes();
            long used = Math.max(0, total - avail);
            return AccessUtil.formatSize(used) + "已用, " + AccessUtil.formatSize(avail) + "可用";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 挂载目录 = 系统文件选择器授权（允许访问）的目录（persisted URI permissions）。
     * 与 MT 管理器"挂载"一致：它不是 DocumentsProvider roots，而是用户通过系统
     * 文件选择器对本软件"允许访问"过的目录（SAF per-app 授权）。
     * 返回 {显示名, 真实路径或uri, 是否可进入(1/0)}。
     */
    private List<String[]> listGrantedMounts() {
        List<String[]> out = new ArrayList<>();
        mountLoadError = null; // 诊断：授权列表加载异常（错误可见性）
        try {
            List<android.content.UriPermission> perms = ctx.getContentResolver().getPersistedUriPermissions();
            if (perms == null) return out;
            for (android.content.UriPermission up : perms) {
                try { // 单项异常不中断整体（避免一个坏 uri 导致整个授权列表消失）
                if (up == null || !up.isReadPermission()) continue;
                Uri uri = up.getUri();
                if (uri == null) continue;
                String docId = null;
                if (DocumentsContract.isTreeUri(uri)) {
                    docId = DocumentsContract.getTreeDocumentId(uri);
                } else if (DocumentsContract.isDocumentUri(ctx, uri)) {
                    docId = DocumentsContract.getDocumentId(uri);
                }
                if (docId == null) continue;
                // Android 外部存储授权（primary:xxx → /storage/emulated/0/xxx）
                String path = null;
                if (docId.startsWith("primary")) {
                    String seg = docId.substring("primary".length());
                    while (seg.startsWith(":")) seg = seg.substring(1);
                    String base = Environment.getExternalStorageDirectory().getAbsolutePath();
                    path = seg.isEmpty() ? base : base + "/" + Uri.decode(seg);
                }
                String name = null;
                // 优先查 provider 的 DISPLAY_NAME（友好名，同 MT：TermuxHome / cmoera theme）
                try {
                    // 注意1：buildDocumentUriUsingTree 内部 appendPath 会对 docId 二次编码
                    // （%2F → %252F），Termux 这类 docId 含 %2F 编码路径的 provider 会匹配失败。
                    // 注意2（真机实测 2026-08-16）：getTreeDocumentId 返回的是**解码后**明文
                    // （%2F → /），直接拼接会让 uri path 分段错乱（/tree/home:/data/... 被拆段）
                    // 导致 provider 解析失败（Termux 显示名一直回退 "home" 的根因）。
                    // 正解：从原始 uri 字符串提取**编码态** treeId（%2F 原样）再拼接。
                    Uri docUri;
                    if (DocumentsContract.isTreeUri(uri)) {
                        String auth = uri.getAuthority();
                        String us = uri.toString();
                        int ti = us.indexOf("/tree/");
                        String encTree = ti >= 0 ? us.substring(ti + "/tree/".length()) : null;
                        docUri = encTree != null && !encTree.isEmpty()
                                ? Uri.parse("content://" + auth + "/tree/" + encTree
                                        + "/document/" + encTree)
                                : uri;
                    } else {
                        docUri = uri;
                    }
                    android.database.Cursor c = ctx.getContentResolver().query(docUri,
                            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                String dn = c.getString(0);
                                if (dn != null && !dn.isEmpty()) name = dn;
                            }
                        } finally {
                            c.close();
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (name == null || name.isEmpty()) {
                    // 回退：解码 docId 取末段（避免 %2F 编码乱码）
                    String decoded = Uri.decode(docId);
                    if (decoded != null) {
                        int slash = decoded.lastIndexOf('/');
                        name = slash >= 0 ? decoded.substring(slash + 1) : decoded;
                    }
                    if (name == null || name.isEmpty()) name = docId;
                }
                if (path != null && path.equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
                    name = "存储根目录"; // primary 根
                }
                // 副标题：具体目录路径（对齐 MT 灰色小字），非 primary 也映射可读路径而非 content:// uri
                String displayPath = path;
                boolean enterable = path != null;
                if (displayPath == null) {
                    String dec = Uri.decode(docId);
                    if (dec != null && !dec.isEmpty()) {
                        int ci = dec.indexOf(':');
                        if (ci > 0) {
                            // 卷号:路径（如 BA73-022B:DCIM）→ /storage/卷号/路径
                            String vol = dec.substring(0, ci);
                            String seg = dec.substring(ci + 1);
                            displayPath = "/storage/" + vol + (seg.isEmpty() ? "" : "/" + seg);
                        } else {
                            displayPath = dec; // 绝对路径（如 /data/data/com.termux/files/home 或 /storage/BA73-022B）
                        }
                    }
                    if (displayPath == null) displayPath = uri.toString();
                    // MT 行为：真实路径可读则直接进入（如 ZeroTermux 数据在外部卷 /storage/BA73-022B）
                    try {
                        File f = new File(displayPath);
                        enterable = f.isDirectory() && f.canRead();
                    } catch (Throwable t) {
                        enterable = false;
                    }
                }
                // 第4元素 = 原始 content:// uri（删除授权/系统文件管理器打开必须用 uri，不能用路径）
                out.add(new String[]{name, displayPath, enterable ? "1" : "0", uri.toString()});
                } catch (Throwable t) { // 单项异常记录诊断，不中断整体
                    if (mountLoadError == null) mountLoadError = String.valueOf(t);
                }
            }
        } catch (Throwable t) {
            mountLoadError = String.valueOf(t);
        }
        return out;
    }

    private void showNewFolderDialog() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.sectionHeader(ctx, "新建文件夹");
        box.addView(title);

        final EditText input = new EditText(ctx);
        input.setHint("文件夹名称");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(input);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("取消", v -> dialog.dismiss()));
        btns.addView(textButton("创建", v -> {
            String name = input.getText().toString().trim();
            // ⭐5 文件名合法性校验（参考 Fossify isAValidFilename）：非法字符直接拦截
            if (!isValidFileName(name)) {
                UiKit.alert(ctx, "名称不合法（含 / \\ : * ? \" < > | 或以点结尾）");
                return;
            }
            dialog.dismiss();
            if (SafStrategy.isSafUri(active.path)) {
                // SAF 授权目录：DocumentsContract.createDocument（对齐 MT 可新建）
                new Thread(() -> {
                    String uri = SafStrategy.createDocument(ctx, active.path, name, true);
                    final String msg = uri != null ? "已创建文件夹：" + name : "创建失败（无写入权限？）";
                    handler.post(() -> {
                        UiKit.toast(ctx, msg);
                        reload(active);
                    });
                }, "HyVqSafMkdir").start();
            } else {
                FileOps.mkdirAsync(new File(active.path), name, (ok, msg) -> {
                    UiKit.toast(ctx, msg);
                    reload(active);
                });
            }
        }));
        dialog.show();
    }

    private void showRenameDialog(final FileEntry e) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "重命名"));

        final EditText input = new EditText(ctx);
        input.setText(e.name);
        input.setSingleLine(true);
        box.addView(input);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("取消", v -> dialog.dismiss()));
        btns.addView(textButton("确定", v -> {
            // ⭐5 文件名合法性校验（参考 Fossify isAValidFilename）：空名/非法字符直接拦截
            //（此前只做了 isEmpty 净化，重命名为空会落到 FileOps.renameAsync 产生脏名）
            final String newName = input.getText().toString().trim();
            if (!isValidFileName(newName)) {
                UiKit.alert(ctx, "名称不合法（含 / \\ : * ? \" < > | 或以点结尾）");
                return;
            }
            dialog.dismiss();
            if (SafStrategy.isSafUri(e.path)) {
                // SAF 授权条目：DocumentsContract.renameDocument
                new Thread(() -> {
                    String uri = SafStrategy.renameDocument(ctx, e.path, newName);
                    final String msg = uri != null ? "已重命名为：" + newName : "重命名失败";
                    handler.post(() -> {
                        UiKit.toast(ctx, msg);
                        reload(active);
                    });
                }, "HyVqSafRename").start();
            } else {
                FileOps.renameAsync(AccessRouter.fileOf(e), newName, (ok, msg) -> {
                    UiKit.toast(ctx, msg);
                    reload(active);
                });
            }
        }));
        dialog.show();
    }

    private void showPropertyDialog(final FileEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(e.name).append('\n');
        sb.append("路径：").append(e.path).append('\n');
        String typeStr;
        switch (e.type) {
            case DIR:
                typeStr = "文件夹";
                break;
            case FILE:
                typeStr = "文件";
                break;
            case VIRTUAL_DIR:
                typeStr = "虚拟文件夹";
                break;
            case VIRTUAL_FILE:
                typeStr = "虚拟文件";
                break;
            default:
                typeStr = "符号链接";
                break;
        }
        sb.append("类型：").append(typeStr).append('\n');
        if (e.isDir()) {
            // ⚠️ 不能在主线程算：SafStrategy.childCount 会 query 整个目录的游标，
            // 真实路径的 listFiles() 也要枚举全目录。十万项目录直接卡住。
            // 先占位，子项数放到后台算好后回填。
            sb.append("项目数：计算中…\n");
            sb.append("大小：").append(SafStrategy.isSafUri(e.path) ? "-" : "计算中…");
        } else {
            sb.append("大小：").append(FileOps.formatSize(e.length)).append('\n');
        }
        sb.append("修改时间：").append(FileOps.formatDate(e.lastModified));

        TextView tv = new TextView(ctx);
        tv.setText(sb.toString());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        tv.setLineSpacing(0, 1.25f);
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));

        // 目录子项数交给后台算，算完回填（上面写的是「计算中…」占位）
        if (e.isDir()) {
            final TextView tvRef = tv;
            final String baseText = sb.toString();
            new Thread(() -> {
                int cnt = countChildren(e);
                handler.post(() -> {
                    if (detached) return;
                    // 只替换占位那一行，其余内容（类型/修改时间）保持原样
                    tvRef.setText(baseText.replace("项目数：计算中…",
                            "项目数：" + cnt));
                });
            }, "HyPropCount").start();
        }

        // ⭐ v2.8.0 root 属性：权限 / 属主 / SELinux 上下文（App 侧拿不到，需 root stat）
        final TextView rootTv = new TextView(ctx);
        rootTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        rootTv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        rootTv.setLineSpacing(0, 1.3f);
        rootTv.setPadding(dp(4), dp(6), dp(4), dp(4));
        rootTv.setTextIsSelectable(true);
        rootTv.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "属性"));
        box.addView(tv);
        box.addView(rootTv);

        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);
        btns.addView(textButton("关闭", v -> dialog.dismiss()));
        dialog.show();

        // 目录大小后台计算后回填（仅真实目录；虚拟目录无真实大小，SAF 目录显示 "-"）
        if (e.isDir() && !e.virtual && !SafStrategy.isSafUri(e.path)) {
            new Thread(() -> {
                long size = FileOps.dirSize(AccessRouter.fileOf(e));
                final String sizeStr = size < 0 ? "计算失败" : FileOps.formatSize(size);
                handler.post(() -> {
                    tv.setText(tv.getText().toString().replace("计算中…", sizeStr));
                });
            }, "HyVqFileSize").start();
        }
    }

    private void confirmDelete(final List<FileEntry> items) {
        // 回收站模式（虚拟视图或条目本身在回收站内）→ 物理删除；否则移入回收站可恢复
        final boolean inTrash = active != null && active.path != null
                && (active.path.startsWith(PREFIX_TRASH)
                || (items.size() > 0 && items.get(0).path != null
                && items.get(0).path.startsWith(TRASH_DIR)));
        StringBuilder sb = new StringBuilder((inTrash ? "确定彻底删除以下 " : "确定删除以下 ")
                + items.size() + " 项？\n");
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            sb.append("· ").append(items.get(i).name).append('\n');
        }
        if (items.size() > 5) sb.append("…等").append(items.size()).append("项");
        if (!inTrash) {
            boolean hasRoot = false;
            for (FileEntry e : items) {
                if (needRoot(e.path)) {
                    hasRoot = true;
                    break;
                }
            }
            sb.append(hasRoot ? "（其中 root 条目将直接删除，不可恢复）" : "（将移入回收站，可恢复）");
        }

        TextView tv = new TextView(ctx);
        tv.setText(sb.toString().trim());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        tv.setLineSpacing(0, 1.3f);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, inTrash ? "⚠ 彻底删除确认" : "⚠ 删除确认"));
        box.addView(tv);

        // ── 单次生效的「移入回收站」选项 ──
        // 默认值跟随设置页的「默认移入回收站」；改动只对本次删除生效，
        // 不改写 Pref（避免一次临时选择被永久记住）。
        final boolean[] thisTimeTrash = {trashEnabled && trashDefault};
        if (!inTrash && trashEnabled) {
            final android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
            cb.setText("移入回收站（可恢复）");
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            cb.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurface));
            cb.setChecked(thisTimeTrash[0]);
            cb.setPadding(dp(4), dp(6), dp(4), dp(2));
            cb.setOnCheckedChangeListener((b, checked) -> thisTimeTrash[0] = checked);
            LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cbLp.topMargin = dp(6);
            box.addView(cb, cbLp);

            TextView tip = new TextView(ctx);
            tip.setText("仅本次生效，默认值在 设置 → 文件管理 → 回收站 中调整");
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            tip.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            tip.setAlpha(0.7f);
            tip.setPadding(dp(4), 0, dp(4), dp(4));
            box.addView(tip);
        }

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("取消", v -> dialog.dismiss()));
        btns.addView(textButton("删除", v -> {
            dialog.dismiss();
            exitMultiMode();
            runTask(() -> {
                int ok = 0;
                // ⚠️ 收集每一项的失败原因。
                // 旧实现全 catch(ignored)，最后只报「已移入回收站 7/10 项」——
                // 用户知道 3 个失败了，但不知道是哪个、为什么（权限？占用？只读？），
                // 完全无法补救。这是文件管理器最核心的操作，必须说清楚。
                final List<String> failed = new ArrayList<>();
                for (FileEntry e : items) {
                    String nm = e.name == null ? e.path : e.name;
                    if (SafStrategy.isSafUri(e.path)) {
                        // SAF 授权条目：DocumentsContract.deleteDocument（无法移入回收站）
                        try {
                            if (SafStrategy.deleteDocument(ctx, e.path)) {
                                ok++;
                            } else {
                                failed.add(nm + "：授权目录删除被拒绝");
                            }
                        } catch (Throwable t) {
                            failed.add(nm + "：" + msgOf(t));
                        }
                        continue;
                    }
                    File f = AccessRouter.fileOf(e);
                    if (!f.exists()) {
                        failed.add(nm + "：文件已不存在");
                        continue;
                    }
                    try {
                        if (inTrash) {
                            // 回收站：物理删除，连同 .trashmeta 一起清掉
                            // （否则 meta 会成为孤儿文件，且下次同名条目会复用旧路径）
                            deleteTrashMeta(f);
                            deleteTree(f);
                        } else {
                            // 移入回收站（保留原路径层级，恢复=移回原位置）
                            // 采用本次确认页上的勾选（默认跟随设置，改动仅本次生效）
                            boolean useTrash = trashEnabled && thisTimeTrash[0];
                            if (useTrash) {
                                // ⚠️ 绝不能降级为物理删除。
                                // 旧写法 `if (!useTrash || !moveToTrash(f)) { deleteTree(f); }`
                                // 在用户勾了「移入回收站」、而 copy 因权限/跨区失败时，
                                // 直接物理删除 —— 用户以为只是丢进回收站，实际永久没了。
                                // 失败就如实报错并保留原文件，让用户自己决定下一步。
                                if (!moveToTrash(f)) {
                                    failed.add(nm + "：无法移入回收站（原文件已保留）");
                                    continue;
                                }
                            } else {
                                deleteTree(f);
                            }
                        }
                        ok++;
                    } catch (Throwable t) {
                        failed.add(nm + "：" + msgOf(t));
                    }
                }
                final int fOk = ok;
                final boolean fTrash = !inTrash;
                final List<String> fFailed = failed;
                handler.post(() -> {
                    if (fFailed.isEmpty()) {
                        // 成功不弹 toast：删除是高频操作，底部闪一条只会打断浏览。
                        // 列表里条目立即消失本身就是反馈。
                        // 失败仍然弹（下面是 alert），那种情况必须让用户知道。
                    } else {
                        StringBuilder failMsg = new StringBuilder();
                        failMsg.append("成功 ").append(fOk).append(" 项，失败 ")
                               .append(fFailed.size()).append(" 项：\n");
                        for (int i = 0; i < Math.min(10, fFailed.size()); i++) {
                            failMsg.append("· ").append(fFailed.get(i)).append('\n');
                        }
                        if (fFailed.size() > 10) {
                            failMsg.append("…等 ").append(fFailed.size()).append(" 项");
                        }
                        UiKit.alert(ctx, failMsg.toString().trim());
                    }
                    reloadAll();
                });
            });
        }));
        dialog.show();
    }

    // ── 打开文件 / 排序 ──

    /** 扩展名 → MIME 自定义映射（2026-08-16 全面格式支持）：
     *  优先查自定义表补齐系统 MimeTypeMap 缺失的常见格式（md/epub/heic/mkv/flac/7z 等），
     *  未命中回落系统表；都查不到返回 null（调用方回落星号斜杠通配） */
    private static String mimeOf(String ext) {
        if (ext == null || ext.isEmpty()) return null;
        switch (ext) {
            case "md": return "text/markdown";
            case "epub": return "application/epub+zip";
            case "mobi": return "application/x-mobipocket-ebook";
            case "azw3": return "application/vnd.amazon.ebook";
            case "chm": return "application/vnd.ms-htmlhelp";
            case "tex": return "application/x-tex";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "psd": return "image/vnd.adobe.photoshop";
            case "ico": return "image/x-icon";
            case "mkv": return "video/x-matroska";
            case "m2ts": return "video/mp2t";
            case "mts": return "video/mp2t";
            case "ts": return "video/mp2t";
            case "m4v": return "video/x-m4v";
            case "wmv": return "video/x-ms-wmv";
            case "flv": return "video/x-flv";
            case "3gp": return "video/3gpp";
            case "ogv": return "video/ogg";
            case "flac": return "audio/flac";
            case "opus": return "audio/opus";
            case "amr": return "audio/amr";
            case "wma": return "audio/x-ms-wma";
            case "ape": return "audio/ape";
            case "aiff": return "audio/aiff";
            case "mid": return "audio/midi";
            case "midi": return "audio/midi";
            case "ac3": return "audio/ac3";
            case "7z": return "application/x-7z-compressed";
            case "rar": return "application/x-rar-compressed";
            case "tar": return "application/x-tar";
            case "gz": return "application/gzip";
            case "tgz": return "application/gzip";
            case "bz2": return "application/x-bzip2";
            case "xz": return "application/x-xz";
            case "iso": return "application/x-iso9660-image";
            case "apk": return "application/vnd.android.package-archive";
            case "jar": return "application/java-archive";
            case "xml": return "text/xml";
            case "json": return "application/json";
            case "yaml": return "text/yaml";
            case "yml": return "text/yaml";
            case "csv": return "text/csv";
            case "log": return "text/plain";
            case "sql": return "application/sql";
            case "torrent": return "application/x-bittorrent";
            case "sh": return "application/x-sh";
            case "bash": return "application/x-sh";
            case "bat": return "application/x-msdos-program";
            case "ps1": return "application/x-powershell";
            case "java": return "text/x-java-source";
            case "kt": return "text/x-kotlin";
            case "c": return "text/x-c";
            case "h": return "text/x-c";
            case "cpp": return "text/x-c++";
            case "hpp": return "text/x-c++";
            case "cs": return "text/x-csharp";
            case "py": return "text/x-python";
            case "js": return "application/javascript";
            case "mjs": return "application/javascript";
            case "jsx": return "text/jsx";
            case "tsx": return "text/tsx";
            case "vue": return "text/vue";
            case "css": return "text/css";
            case "scss": return "text/x-scss";
            case "less": return "text/x-less";
            case "php": return "text/x-php";
            case "rb": return "text/x-ruby";
            case "go": return "text/x-go";
            case "rs": return "text/x-rust";
            case "swift": return "text/x-swift";
            case "lua": return "text/x-lua";
            case "pl": return "text/x-perl";
            case "r": return "text/x-r";
            case "dart": return "text/x-dart";
            case "groovy": return "text/x-groovy";
            case "gradle": return "text/x-gradle";
            case "properties": return "text/x-java-properties";
            case "ini": return "text/plain";
            case "cfg": return "text/plain";
            case "conf": return "text/plain";
            case "ipynb": return "application/x-ipynb+json";
            case "fb2": return "application/x-fictionbook+xml";
            case "djvu": return "image/vnd.djvu";
            case "cbr": return "application/vnd.comicbook-rar";
            case "cbz": return "application/vnd.comicbook+zip";
            case "mht": return "message/rfc822";
            case "zst": return "application/zstd";
            case "lz4": return "application/x-lz4";
            case "cab": return "application/vnd.ms-cab-compressed";
            case "deb": return "application/vnd.debian.binary-package";
            case "rpm": return "application/x-rpm";
            case "dmg": return "application/x-apple-diskimage";
            case "aab": return "application/x-authorware-bin";
            case "xapk": return "application/vnd.android.package-archive";
            case "svgz": return "image/svg+xml";
            default: return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
    }

    // ── 内置文本编辑器（2026-08-16 参考 ZeroTermux 文本编辑页：等宽字体 + 大编辑区 + 保存） ──

    /** 文本/代码文件白名单：点击直接进内置编辑器（与 docs 分类扩充同源） */
    private static final Set<String> TEXT_EXTS = new HashSet<>(Arrays.asList(
            "txt", "md", "log", "ini", "cfg", "conf", "properties", "yaml", "yml", "json", "xml",
            "html", "htm", "css", "scss", "less", "csv", "sql", "sh", "bash", "zsh", "bat", "ps1",
            "java", "kt", "c", "cpp", "h", "hpp", "cs", "py", "js", "mjs", "jsx", "tsx",
            "vue", "php", "rb", "go", "rs", "swift", "m", "lua", "pl", "r", "dart", "groovy",
            "gradle", "ipynb", "tex", "svg", "svgz", "gitignore", "editorconfig", "toml", "lock",
            "env", "srt", "vtt", "ass", "nfo", "torrent", "plist", "xsh", "mk", "cmake"));

    /** 文本文件判定：白名单扩展名 + 无扩展名常见脚本/说明文件（Makefile/Dockerfile/README…） */
    private static boolean isTextFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0) {
            return n.equals("makefile") || n.equals("dockerfile") || n.equals("readme")
                    || n.equals("license") || n.equals("changelog") || n.equals("gradle");
        }
        return TEXT_EXTS.contains(n.substring(dot + 1));
    }

    /** 打开文本文件内置编辑（仅真实路径；>2MB 拒绝防卡死；UTF-8 优先、GBK 兜底、BOM 剥离）。
     *  多标签 + 打开保留（2026-08-16 参考 ZeroTermux EditTextActivity）：同文件复用标签，
     *  标签列表持久化到 SharedPreferences，下次打开编辑器自动恢复。 */
    private void openTextEditor(final File f) {
        openTextEditorInternal(f, null);
    }

    /**
     * 打开编辑器。
     *
     * @param rootTarget 非空 = 这是 root 文件的缓存副本，保存后需写回该真实路径
     */
    private void openTextEditorInternal(final File f, final String rootTarget) {
        if (!f.exists() || !f.isFile()) {
            UiKit.alert(ctx, "文件不存在");
            return;
        }
        if (f.length() > MAX_EDIT_BYTES) {
            UiKit.alert(ctx, "文件过大（" + (f.length() / 1024 / 1024)
                    + "MB，上限 " + (MAX_EDIT_BYTES / 1024 / 1024)
                    + "MB），请用其他应用打开");
            return;
        }
        // 已在标签列表 → 直接切换激活
        for (EditorTabState t : editorTabs) {
            if (t.file.equals(f)) {
                ensureEditorDialog();
                switchEditorTab(t);
                if (editorDialog != null) editorDialog.show();
                return;
            }
        }
        // 首次打开编辑器：恢复上次保留的标签（打开保留）
        if (editorTabs.isEmpty() && editorDialog == null) restoreEditorTabs();
        // 恢复后若已含该文件（上次关闭时保留在列表）→ 直接切换，避免重复标签
        for (EditorTabState t : editorTabs) {
            if (t.file.equals(f)) {
                ensureEditorDialog();
                switchEditorTab(t);
                if (editorDialog != null) editorDialog.show();
                return;
            }
        }
        String[] rc = readTextContent(f);
        if (rc == null) {
            UiKit.alert(ctx, "读取失败");
            return;
        }
        EditorTabState tab = new EditorTabState(f, rc[0], rc[1], rootTarget);
        editorTabs.add(tab);
        saveEditorTabs();
        ensureEditorDialog();
        switchEditorTab(tab);
        if (editorDialog != null) editorDialog.show();
    }

    /** 读取文本内容：UTF-8 优先、GBK 兜底、剥离 BOM；返回 {内容, 编码} 或 null */
    private String[] readTextContent(File f) {
        try {
            byte[] bytes = new byte[(int) f.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                int off = 0, r;
                while (off < bytes.length && (r = fis.read(bytes, off, bytes.length - off)) > 0) off += r;
            }
            int start = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                start = 3; // 剥离 UTF-8 BOM
            }
            String content = new String(bytes, start, bytes.length - start, "UTF-8");
            if (containsGarbled(content)) { // 乱码 → GBK 重读
                content = new String(bytes, start, bytes.length - start, "GBK");
                return new String[]{content, "GBK"};
            }
            return new String[]{content, "UTF-8"};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 构建全屏编辑器（覆盖整个屏幕；参考 ZeroTermux 顶部工具栏 + 标签栏布局；会话内复用） */
    private void ensureEditorDialog() {
        if (editorDialog != null && editorDialog.isShowing()) return;
        final int[] pal = editorPalette();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int surface = pal[0];
        box.setBackgroundColor(surface);

        // 顶部工具行：☰ 三条杠菜单 + 标题(weight=1) + 撤销/重做/查找 + 保存 + 关闭（功能导向：高频操作一键直达）
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(2), dp(4), dp(2));
        topBar.addView(toolIcon(R.drawable.ic_menu, v -> showEditorDrawer()));
        editorHeader = new TextView(ctx);
        editorHeader.setText("编辑器");
        editorHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        editorHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        editorHeader.setSingleLine(true);
        editorHeader.setEllipsize(android.text.TextUtils.TruncateAt.END);
        editorHeader.setPadding(dp(8), 0, dp(8), 0);
        editorHeader.setTextColor(pal[1]);
        topBar.addView(editorHeader, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(toolIcon(R.drawable.ic_undo, v -> undoEdit()));
        topBar.addView(toolIcon(R.drawable.ic_redo, v -> redoEdit()));
        topBar.addView(toolIcon(R.drawable.ic_search, v -> showFindReplaceDialog()));
        topBar.addView(textButton("保存", v -> {
            if (currentEditTab != null) saveEditorTab(currentEditTab, null);
        }));
        topBar.addView(textButton("关闭", v -> confirmEditorExit()));
        box.addView(topBar);

        // 标签栏（参考 ZeroTermux editor_tab_bar：HorizontalScrollView 横向滚动）
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        editorTabBar = new LinearLayout(ctx);
        editorTabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(editorTabBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(tabScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 编辑区：等宽/字号可设置（设置对话框持久化），weight=1 填满剩余屏幕
        editorEdit = new com.aliya.hyfilemgr.widget.NumberedEditText(ctx);
        editorEdit.setTypeface(editorMonoPref() ? android.graphics.Typeface.MONOSPACE
                : android.graphics.Typeface.DEFAULT);
        editorEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorFontSizePref());
        editorEdit.setGravity(Gravity.TOP | Gravity.START);
        editorEdit.setSingleLine(false);
        editorEdit.setHorizontallyScrolling(true);
        editorEdit.setTextColor(pal[1]);
        editorEdit.setHintTextColor(pal[2]);
        editorEdit.setBackground(UiKit.rounded(ctx, dp(8), pal[3], 0));
        int ep = dp(8);
        editorEdit.setPadding(ep, ep, ep, ep);

        // ── 行号栏 + 编辑区 ──
        // 结构：[行号 TextView (固定宽)] [EditText (weight=1)]
        // 行号在每次文本变化时重算，行数与内容一致；与编辑区共用同一字号/行距，
        // 才能保证纵向对齐（行高必须严格相等）。
        LinearLayout editorArea = new LinearLayout(ctx);
        editorArea.setOrientation(LinearLayout.HORIZONTAL);

        editorEdit.setIncludeFontPadding(false);
        editorEdit.setLineSpacing(0f, 1f);
        editorEdit.setGutter(dp(34), dp(6), pal[5] & 0x22FFFFFF, pal[2]);
        editorArea.addView(editorEdit, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        box.addView(editorArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 状态行：编码 · 行尾 · 行/列 · 字符实时统计
        editorStat = new TextView(ctx);
        editorStat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        editorStat.setTextColor(pal[2]);
        box.addView(editorStat);

        // 符号栏（Tab 键插入 tabWidth 个空格；点击插入光标处）
        HorizontalScrollView symScroll = new HorizontalScrollView(ctx);
        symScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout symBar = new LinearLayout(ctx);
        symBar.setOrientation(LinearLayout.HORIZONTAL);
        final int tabW = editorTabWidthPref();
        String[][] syms = {{"Tab", "\t"}, {"(", "("}, {")", ")"}, {"[", "["}, {"]", "]"},
                {"{", "{"}, {"}", "}"}, {"\"", "\""}, {"'", "'"}, {";", ";"}, {":", ":"},
                {"/", "/"}, {"=", "="}, {"+", "+"}, {"-", "-"}, {"*", "*"}, {"#", "#"}};
        for (String[] s : syms) {
            if ("Tab".equals(s[0])) {
                symBar.addView(symbolKey(s[0], repeatSpaces(tabW)));
            } else {
                symBar.addView(symbolKey(s[0], s[1]));
            }
        }
        symScroll.addView(symBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(symScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 全屏 Dialog：覆盖整个屏幕（隐藏状态栏）
        editorDialog = new android.app.Dialog(ctx);
        editorDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        editorDialog.setContentView(box);
        android.view.Window win = editorDialog.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surface));
        }
        // 返回键 → 未保存确认（打开保留：关闭时持久化标签列表）
        editorDialog.setOnCancelListener(d -> confirmEditorExit());

        // 编辑监听：统计 + 脏标记 + 撤销快照（切换标签时摘除/挂回，避免 setText 误置脏）
        editorWatcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                pushUndo(s.toString(), a); // s = 变化前完整文本（撤销快照）
            }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                refreshEditorStat();
                if (currentEditTab != null && !currentEditTab.dirty) {
                    currentEditTab.dirty = true;
                    renderEditorTabs();
                }
            }
        };
        attachAutoIndent(editorEdit);
        editorEdit.addTextChangedListener(editorWatcher);
        renderEditorTabs();
    }

    /** 切换激活标签：先保存当前编辑内容回内存，再加载目标标签 */
    private void switchEditorTab(EditorTabState tab) {
        if (editorEdit == null) return;
        if (currentEditTab != null && currentEditTab != tab) {
            currentEditTab.content = editorEdit.getText().toString();
        }
        currentEditTab = tab;
        editorEdit.removeTextChangedListener(editorWatcher);
        editorEdit.setText(tab.content);
        editorEdit.setSelection(0);
        editorEdit.addTextChangedListener(editorWatcher);
        if (editorHeader != null) {
            editorHeader.setText(tab.file.getName() + " · " + tab.encoding + " · "
                    + FileOps.formatSize(tab.file.length()));
        }
        refreshEditorStat();
        renderEditorTabs();
    }

    /** 渲染标签栏（参考 ZeroTermux renderEditorTabs：激活高亮 + 脏标记 • + ×关闭；颜色统一走主题调色板） */
    private void renderEditorTabs() {
        if (editorTabBar == null) return;
        editorTabBar.removeAllViews();
        final int[] pal = editorPalette();
        int activeColor = pal[4];
        int idleColor = pal[3];
        for (final EditorTabState t : editorTabs) {
            final boolean active = t == currentEditTab;
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(8), 0, 0, 0);
            item.setBackground(UiKit.rounded(ctx, dp(6), active ? activeColor : idleColor, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.setMargins(0, dp(4), dp(4), dp(4));
            item.setLayoutParams(lp);
            TextView name = new TextView(ctx);
            name.setText(t.file.getName() + (t.dirty ? " •" : ""));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setSingleLine(true);
            name.setMaxWidth(dp(170));
            name.setGravity(Gravity.CENTER_VERTICAL);
            name.setTextColor(active ? 0xFFFFFFFF : 0xFF9E9E9E);
            name.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            item.addView(name);
            TextView close = new TextView(ctx);
            close.setText("×");
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            close.setTextColor(active ? 0xFFD7D7D7 : 0xFF8B8B8B);
            close.setGravity(Gravity.CENTER);
            close.setPadding(dp(8), 0, dp(8), 0);
            close.setOnClickListener(v -> closeEditorTab(t));
            item.addView(close);
            item.setOnClickListener(v -> switchEditorTab(t));
            editorTabBar.addView(item);
        }
    }

    /** 关闭标签：有未保存修改 → 保存/放弃/取消 三选（参考 ZeroTermux closeEditorTab） */
    private void closeEditorTab(final EditorTabState tab) {
        if (!tab.dirty) {
            removeEditorTab(tab);
            return;
        }
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("未保存的修改")
                .setMessage("「" + tab.file.getName() + "」有未保存的修改")
                .setPositiveButton("保存", (d, w) -> saveEditorTab(tab, () -> removeEditorTab(tab)))
                .setNegativeButton("放弃", (d, w) -> removeEditorTab(tab))
                .setNeutralButton("取消", null)
                .show();
    }

    /** 移除标签；关空则关闭编辑器；关的是激活标签则切到相邻标签 */
    private void removeEditorTab(EditorTabState tab) {
        int idx = editorTabs.indexOf(tab);
        boolean active = tab == currentEditTab;
        editorTabs.remove(idx);
        saveEditorTabs();
        if (editorTabs.isEmpty()) {
            finishEditor();
            return;
        }
        if (active) {
            switchEditorTab(editorTabs.get(Math.min(idx, editorTabs.size() - 1)));
        } else {
            renderEditorTabs();
        }
    }

    /** 保存标签（队列化写回，按所选编码 UTF-8/GBK + 行尾符 LF/CRLF；成功后刷新列表；可选回调继续） */
    private void saveEditorTab(final EditorTabState tab, final Runnable onDone) {
        if (tab == currentEditTab && editorEdit != null) tab.content = editorEdit.getText().toString();
        // 行尾符归一：先统一 LF，再按需转 CRLF
        final String body = tab.content.replace("\r\n", "\n");
        final String out = "CRLF".equals(tab.lineEnding) ? body.replace("\n", "\r\n") : body;
        final String enc = tab.encoding;
        runTask(() -> {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tab.file)) {
                byte[] bytes;
                if ("GBK".equals(enc)) {
                    java.nio.charset.CharsetEncoder gbkEnc = java.nio.charset.Charset.forName("GBK").newEncoder();
                    if (!gbkEnc.canEncode(out)) {
                        bytes = out.getBytes("UTF-8");
                        tab.encoding = "UTF-8"; // 含 GBK 无法编码字符 → 回退 UTF-8
                        handler.post(() -> UiKit.toast(ctx, "含 GBK 无法编码的字符，已回退 UTF-8 保存"));
                    } else {
                        bytes = out.getBytes("GBK");
                    }
                } else {
                    bytes = out.getBytes("UTF-8");
                }
                fos.write(bytes);
                final String rootErr = null;
                handler.post(() -> {
                    tab.savedContent = tab.content;
                    tab.dirty = false;
                    if (editorHeader != null && tab == currentEditTab) {
                        editorHeader.setText(tab.file.getName() + " · " + tab.encoding + " · "
                                + FileOps.formatSize(tab.file.length()));
                    }
                    UiKit.toast(ctx, rootErr != null ? rootErr : "已保存："
                            + (tab.rootTarget != null ? tab.rootTarget : tab.file.getName()));
                    renderEditorTabs();
                    reloadAll();
                    if (onDone != null) onDone.run();
                });
            } catch (Throwable t) {
                handler.post(() -> UiKit.alert(ctx, "保存失败：" + t.getMessage()));
            }
        });
    }

    /** 关闭编辑器：有未保存修改 → 全部保存/放弃/取消（打开保留：关闭时持久化标签列表） */
    private void confirmEditorExit() {
        if (editorDialog == null) return;
        if (currentEditTab != null && editorEdit != null) currentEditTab.content = editorEdit.getText().toString();
        final java.util.List<EditorTabState> dirtyTabs = new ArrayList<>();
        for (EditorTabState t : editorTabs) if (t.dirty) dirtyTabs.add(t);
        if (dirtyTabs.isEmpty()) {
            finishEditor();
            return;
        }
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("未保存的修改")
                .setMessage(dirtyTabs.size() + " 个文件有未保存的修改")
                .setPositiveButton("全部保存", (d, w) -> {
                    final int[] done = {0};
                    for (EditorTabState t : dirtyTabs) {
                        saveEditorTab(t, () -> {
                            done[0]++;
                            if (done[0] >= dirtyTabs.size()) finishEditor();
                        });
                    }
                })
                .setNegativeButton("放弃修改", (d, w) -> finishEditor(true))
                .setNeutralButton("取消", (d, w) -> {
                    // 返回键路径：cancel 流程结束后再 show，避免窗口状态冲突
                    handler.post(() -> {
                        if (editorDialog != null) {
                            editorDialog.show();
                            renderEditorTabs();
                        }
                    });
                })
                .show();
    }

    private void finishEditor() {
        finishEditor(false);
    }

    /**
     * 结束编辑器会话：持久化标签路径（打开保留）并清空内存。
     *
     * @param discardDrafts true = 用户明确选了「放弃修改」。
     *
     * <p><b>为什么必须区分</b>：旧实现 finishEditor 无条件 saveEditorTabs()
     * → saveEditorDrafts()，于是「放弃修改」把 dirty 标签又写回草稿盘，
     * 下次打开编辑器 restoreEditorDrafts 又把它们恢复出来 ——
     * 用户永远丢不掉一次放弃。这是草稿机制最容易踩的坑：
     * 「防丢」的写入路径不能覆盖「用户主动丢弃」的语义。</p>
     */
    private void finishEditor(boolean discardDrafts) {
        if (discardDrafts) {
            // 用户主动放弃：清空草稿（含 meta），不留任何可恢复痕迹
            clearEditorDrafts();
            // 标签仍在列表里，但把 dirty 抹掉，让 saveEditorTabs 不写它们
            for (EditorTabState t : editorTabs) {
                t.dirty = false;
                t.content = t.savedContent;
            }
        }
        saveEditorTabs();
        if (editorDialog != null) editorDialog.dismiss();
        editorDialog = null;
        if (webDialog != null) {
            webDialog.dismiss();
            webDialog = null;
        }
        destroyPreviewWebView();
        if (editorDrawer != null) {
            editorDrawer.dismiss();
            editorDrawer = null;
            editorDrawerDir = null;
            editorDrawerList = null;
            editorDrawerPath = null;
        }
        currentEditTab = null;
        editorTabs.clear();
        editorEdit = null;
        editorStat = null;
        editorHeader = null;
        editorTabBar = null;
        editorWatcher = null;
    }

    /** 打开保留：保存当前标签路径列表（root 文件存真实路径，前缀 root:// 标记） */

    // ── 编辑器草稿：把「未保存的修改」落盘，防止杀进程丢失 ──

    /** 草稿目录：getFilesDir()/editor_drafts，每个标签一个文件 */
    /** 清空所有草稿（文件 + meta）。用户选「放弃修改」时调用。 */
    private void clearEditorDrafts() {
        try {
            File dir = draftDir();
            if (dir != null) {
                File[] fs = dir.listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            fmPrefs().edit().remove(EDITOR_PREF_DRAFTS).commit();
        } catch (Throwable t) {
            android.util.Log.w("HyEditor", "clearEditorDrafts failed", t);
        }
    }

    private File draftDir() {
        File d = new File(ctx.getFilesDir(), "editor_drafts");
        if (!d.exists() && !d.mkdirs()) return null;
        return d;
    }

    /**
     * 把有未保存修改的标签写成草稿。
     *
     * <p><b>为什么不写 SharedPreferences</b>：单个 editor_tabs 的 JSON
     * 已经在存路径列表，再塞完整文本会让一个 2MB 文件撑爆 prefs
     * （Android 对 SharedPreferences 没有硬限制，但全量重写 xml 的代价
     * 随大小线性上升，几百 KB 就开始明显卡）。草稿走文件系统。</p>
     *
     * <p>文件名用「序号」而不是路径 hash：恢复时靠 meta 里的 order 对上，
     * 避免路径 hash 碰撞（这个项目已经在缩略图缓存上踩过 32 位碰撞的坑）。</p>
     */
    private void saveEditorDrafts() {
        File dir = draftDir();
        if (dir == null) return;
        try {
            // ⚠️ 顺序很重要：**先写新草稿，全部写成功后再删旧的**。
            //
            // 旧实现先删光旧草稿再写新的 —— 若删完、写完第 1 个文件后进程被杀，
            // 草稿就只剩 1 个，其余标签的内容全没了。等于「为了防丢反而制造丢失」。
            //
            // 现在每个草稿写到 draft_<i>.new，写完整批后才把 .new 改名为正式名，
            // 最后才删不认识的残留文件。任一步崩溃，上一轮的草稿仍然完整。
            org.json.JSONArray meta = new org.json.JSONArray();
            int idx = 0;
            java.util.List<File> written = new java.util.ArrayList<>();
            for (EditorTabState t : editorTabs) {
                if (!t.dirty || t.content == null) continue;
                File f = new File(dir, "draft_" + idx + ".new");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    fos.write(t.content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                written.add(f);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("i", idx);
                // 原文件绝对路径（root 标签记其 rootTarget）
                o.put("path", t.rootTarget != null ? t.rootTarget : t.file.getAbsolutePath());
                o.put("root", t.rootTarget != null);
                o.put("enc", t.encoding == null ? "UTF-8" : t.encoding);
                o.put("le", t.lineEnding == null ? "LF" : t.lineEnding);
                meta.put(o);
                idx++;
            }
            // 本批全部写完 → 改名为正式文件（同目录 rename，原子）
            for (File f : written) {
                String nm = f.getName();
                File dst = new File(dir, nm.substring(0, nm.length() - 4));   // 去 ".new"
                if (!f.renameTo(dst)) {
                    // rename 失败（罕见：ENOSPC / 权限）。保留 .new 不删 ——
                    // 旧实现无视返回值，后面的清理会把 .new 一并删掉，
                    // 等于「刚写好的草稿被自己删了」。恢复端会读 .new 兜底。
                    android.util.Log.w("HyEditor", "rename failed: " + nm);
                }
            }
            // 删掉不属于本批的残留（上一轮多出来的 draft_N）
            File[] left = dir.listFiles();
            if (left != null) {
                for (File f : left) {
                    String nm = f.getName();
                    if (nm.endsWith(".new")) {
                        // 保留：可能是 rename 失败的兜底文件，恢复端会读它。
                        // 只清理「本批没写过」的 .new（序号 >= idx）
                        String stem = nm.substring(0, nm.length() - 4);
                        boolean inBatch = false;
                        for (int k = 0; k < idx; k++) {
                            if (stem.equals("draft_" + k)) { inBatch = true; break; }
                        }
                        if (!inBatch) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                        continue;
                    }
                    boolean keep = false;
                    for (int k = 0; k < idx; k++) {
                        if (nm.equals("draft_" + k + ".txt")) { keep = true; break; }
                    }
                    if (!keep) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            fmPrefs().edit().putString(EDITOR_PREF_DRAFTS, meta.toString()).commit();
            // ⚠️ renameTo 失败必须保留 .new，不能让后面的清理把它删掉
        } catch (Throwable t) {
            android.util.Log.w("HyEditor", "saveEditorDrafts failed", t);
            // 静默失败在这里代价很大：用户以为「关掉再打开还在」，
            // 实际内存里的内容随 editorTabs 一起没了。必须让用户知道。
            final String why = msgOf(t);
            handler.post(() -> UiKit.toast(ctx, "未保存的修改备份失败：" + why));
        }
    }

    /**
     * 恢复草稿：把草稿内容套回对应标签。
     *
     * <p>只在 {@code restoreEditorTabs} 之后调用，按 path 匹配已恢复的标签；
     * 匹配不上（文件被删/改名）就跳过 —— 不新建标签，避免造出幽灵条目。</p>
     */
    private void restoreEditorDrafts() {
        String json = fmPrefs().getString(EDITOR_PREF_DRAFTS, "");
        if (json.isEmpty()) return;
        File dir = draftDir();
        if (dir == null) return;
        int restored = 0;
        try {
            org.json.JSONArray meta = new org.json.JSONArray(json);
            for (int i = 0; i < meta.length(); i++) {
                org.json.JSONObject o = meta.getJSONObject(i);
                String path = o.optString("path", "");
                if (path.isEmpty()) continue;
                File draft = new File(dir, "draft_" + o.optInt("i", -1) + ".txt");
                // ⚠️ 不能因 length()==0 跳过：0 字节草稿 = 「用户把内容全删了」，
                // 是合法状态。跳过等于把「清空文件」这个修改当成没发生过，
                // 用户重开会看到完整原文，以为自己的删除没生效。
                if (!draft.isFile()) continue;
                String text;
                try (java.io.FileInputStream fis = new java.io.FileInputStream(draft)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                    text = new String(bos.toByteArray(),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                // 找到对应的已恢复标签并套上草稿
                for (EditorTabState t : editorTabs) {
                    String tp = t.rootTarget != null ? t.rootTarget
                            : t.file.getAbsolutePath();
                    if (!tp.equals(path)) continue;
                    t.content = text;
                    t.dirty = true;           // 仍是未保存状态，让用户自己决定
                    t.encoding = o.optString("enc", t.encoding);
                    t.lineEnding = o.optString("le", t.lineEnding);
                    // 撤销栈起点对准磁盘版内容，保证「撤销」能回到保存态
                    t.undoStack.clear();
                    t.redoStack.clear();
                    t.undoStack.add(t.savedContent == null ? "" : t.savedContent);
                    restored++;
                    break;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("HyEditor", "restoreEditorDrafts failed", t);
        }
        if (restored > 0) {
            final int n = restored;
            handler.post(() -> UiKit.toast(ctx,
                    "已恢复 " + n + " 个未保存的编辑草稿"));
        }
    }

    private void saveEditorTabs() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (EditorTabState t : editorTabs) {
            arr.put(t.rootTarget != null ? "root://" + t.rootTarget : t.file.getAbsolutePath());
        }
        fmPrefs().edit().putString(EDITOR_PREF_TABS, arr.toString()).commit();
        // 同时把「未保存的修改」落成草稿文件。旧的 editor_tabs 只存路径，
        // 恢复时从磁盘重读 —— 只要用户没点保存，内容就没了。
        saveEditorDrafts();
    }

    /** 打开保留：恢复上次保留的标签（跳过不存在/过大/读取失败文件） */
    private void restoreEditorTabs() {
        String json = fmPrefs().getString(EDITOR_PREF_TABS, "");
        if (json.isEmpty()) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.getString(i);
                // root:// 前缀要先剥掉再建 File，否则永远 isFile()=false
                boolean isRoot = p.startsWith("root://");
                String real = isRoot ? p.substring("root://".length()) : p;
                File f = new File(real);
                if (!f.isFile() || f.length() > MAX_EDIT_BYTES) continue;
                String[] rc = readTextContent(f);
                if (rc == null) continue;
                // root 标签的 file 指向缓存副本，rootTarget 记真实路径；
                // 这里没有副本上下文，统一按普通标签恢复，
                // 由上层（打开 root 文件的路径）在下次保存时重建 rootTarget
                editorTabs.add(new EditorTabState(f, rc[0], rc[1]));
            }
            // 草稿套回：把未保存的修改恢复上去
            restoreEditorDrafts();
        } catch (Throwable t) {
            android.util.Log.w("HyEditor", "restoreEditorTabs failed", t);
        }
    }

    /** 状态行刷新：编码 · 行尾 · 行/列 · 字符 */
    private void refreshEditorStat() {
        if (editorStat == null || editorEdit == null) return;
        String t = editorEdit.getText().toString();
        int lines = countLines(t);
        int sel = Math.max(0, editorEdit.getSelectionStart());
        int lineStart = t.lastIndexOf('\n', Math.max(0, sel - 1)) + 1;
        int col = sel - lineStart + 1;
        editorStat.setText((currentEditTab == null ? "" : currentEditTab.encoding + " · "
                + currentEditTab.lineEnding + " · ")
                + "行 " + lines + " · 列 " + col + " · 字符 " + t.length());
    }

    /** 符号栏按键：点击在光标处插入文本（参考 ZeroTermux symbol input；颜色统一走主题调色板） */
    private TextView symbolKey(String label, final String insert) {
        final int[] pal = editorPalette();
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(pal[2]);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        tv.setBackground(UiKit.rounded(ctx, dp(5), pal[3], 0));
        tv.setOnClickListener(v -> {
            if (editorEdit == null) return;
            int pos = editorEdit.getSelectionStart();
            editorEdit.getText().insert(Math.max(0, pos), insert);
        });
        return tv;
    }

    /** 全部保存：逐标签排队写回（任务队列串行，安全） */
    private void saveAllEditorTabs() {
        if (editorTabs.isEmpty()) return;
        for (EditorTabState t : editorTabs) saveEditorTab(t, null);
    }

    // ════════════════════════════════════════════════════════════
    // 编辑器升级（2026-08-17 用户规划落地）：查找替换 / 跳转行 / 撤销重做 /
    // 编码行尾符 / 主题字体设置 / HTML·Markdown 预览 + UI 重构主题统一
    // ════════════════════════════════════════════════════════════

    /** 编辑器主题调色板（UI 重构核心：编辑器所有颜色统一从这里取）
     *  {0 背景, 1 前景, 2 次要(提示/状态), 3 控件底, 4 强调, 5 分隔} */
    private int[] editorPalette() {
        int theme = editorThemePref();
        if (theme == 2) { // 深色（仿代码编辑器暗色主题）
            return new int[]{0xFF121212, 0xFFE0E0E0, 0xFF9E9E9E, 0xFF1E1E1E, 0xFF82AAFF, 0xFF2C2C2C};
        }
        if (theme == 1) { // 浅色
            return new int[]{0xFFFFFFFF, 0xFF212121, 0xFF757575, 0xFFF0F0F0, 0xFF42A5F5, 0xFFDDDDDD}; // 强调色改浅蓝
        }
        // 跟随系统：Material 动态属性
        return new int[]{
                UiKit.color(ctx, com.google.android.material.R.attr.colorSurface),
                UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface),
                UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant),
                UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh),
                UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary),
                UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant)};
    }

    private float editorFontSizePref() {
        return fmPrefs().getFloat(EDITOR_PREF_FONT_SIZE, 13f);
    }

    private boolean editorMonoPref() {
        return fmPrefs().getBoolean(EDITOR_PREF_MONO, true);
    }

    private int editorTabWidthPref() {
        return fmPrefs().getInt(EDITOR_PREF_TAB_W, 4);
    }

    private int editorThemePref() {
        return fmPrefs().getInt(EDITOR_PREF_THEME, 0);
    }

    private String repeatSpaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    /** 行数统计（空文本 = 0 行；末尾无换行补 1） */
    private int countLines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') lines++;
        return lines;
    }

    private String hexColor(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    // ── 撤销 / 重做（EditText 无内置 API，手动快照栈；快照存于标签内，切换标签各自独立） ──
    /** 撤销栈条数上限 */
    private static final int UNDO_MAX_ENTRIES = 200;
    /** 撤销栈总字符上限（约 64MB 堆），防止大文件快照撑爆内存 */
    private static final long UNDO_MAX_CHARS = 32L * 1024 * 1024;

    /** 撤销栈当前总字符数 */
    private static long undoChars(java.util.List<String> st) {
        long t = 0;
        for (int i = 0; i < st.size(); i++) {
            String v = st.get(i);
            if (v != null) t += v.length();
        }
        return t;
    }

    private void pushUndo(String text, int start) {
        if (currentEditTab == null) return;
        java.util.ArrayList<String> st = currentEditTab.undoStack;
        if (!st.isEmpty() && st.get(st.size() - 1).equals(text)) return; // 去重
        long now = System.currentTimeMillis();
        boolean merged = !st.isEmpty() && (now - lastUndoPush < 600)
                && (start == lastUndoStart || start == lastUndoStart + 1); // 连续输入合并
        if (merged) {
            st.set(st.size() - 1, text);
        } else {
            st.add(text);
            // 双重上限：条数 + 总字符数。
            //
            // 只按条数限 200 是不够的：每个快照是完整 String，
            // Java 里 char 占 2 字节，2MB 文件的单份快照约 4MB 堆，
            // 200 份就是 800MB —— 远超进程 heap（通常 256~512MB），
            // 编辑大文件几十次就会 OOM。
            //
            // 按字符预算裁：保留「最近编辑」的若干份，砍掉最老的。
            // 32M 字符 ≈ 64MB 堆，是常见 heap 的 1/4，留足余量。
            while (st.size() > UNDO_MAX_ENTRIES || undoChars(st) > UNDO_MAX_CHARS) {
                if (st.size() <= 1) break;   // 至少留一份，保证能撤销一步
                st.remove(0);
            }
        }
        lastUndoPush = now;
        lastUndoStart = start;
    }

    private void undoEdit() {
        if (editorEdit == null || currentEditTab == null) return;
        java.util.ArrayList<String> st = currentEditTab.undoStack;
        if (st.isEmpty()) {
            UiKit.toast(ctx, "没有可撤销的操作");
            return;
        }
        String prev = st.remove(st.size() - 1);
        String cur = editorEdit.getText().toString();
        currentEditTab.redoStack.add(cur);
        // 与 undoStack 同一套预算：条数 + 总字符，防止大文件快照撑爆内存
        while (currentEditTab.redoStack.size() > UNDO_MAX_ENTRIES
                || undoChars(currentEditTab.redoStack) > UNDO_MAX_CHARS) {
            if (currentEditTab.redoStack.size() <= 1) break;
            currentEditTab.redoStack.remove(0);
        }
        editorEdit.removeTextChangedListener(editorWatcher);
        editorEdit.setText(prev);
        editorEdit.setSelection(Math.min(prev.length(), editorEdit.getSelectionStart()));
        editorEdit.addTextChangedListener(editorWatcher);
        refreshEditorStat();
        renderEditorTabs();
    }

    private void redoEdit() {
        if (editorEdit == null || currentEditTab == null) return;
        java.util.ArrayList<String> st = currentEditTab.redoStack;
        if (st.isEmpty()) {
            UiKit.toast(ctx, "没有可重做的操作");
            return;
        }
        String next = st.remove(st.size() - 1);
        String cur = editorEdit.getText().toString();
        pushUndo(cur, -1); // start=-1 不与连续输入合并，保证重做可回退
        editorEdit.removeTextChangedListener(editorWatcher);
        editorEdit.setText(next);
        editorEdit.setSelection(Math.min(next.length(), editorEdit.getSelectionStart()));
        editorEdit.addTextChangedListener(editorWatcher);
        refreshEditorStat();
        renderEditorTabs();
    }

    // ── 查找 / 替换（正则开关；下一个 / 上一个 / 替换当前 / 全部替换） ──
    private void showFindReplaceDialog() {
        if (currentEditTab == null) return;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, 0);
        final EditText findEt = new EditText(ctx);
        findEt.setHint("查找内容");
        findEt.setSingleLine(true);
        box.addView(findEt);
        final EditText repEt = new EditText(ctx);
        repEt.setHint("替换为（可选）");
        repEt.setSingleLine(true);
        box.addView(repEt);
        final androidx.appcompat.widget.SwitchCompat regexSw = new androidx.appcompat.widget.SwitchCompat(ctx);
        regexSw.setText("正则表达式");
        regexSw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(regexSw);
        final androidx.appcompat.widget.SwitchCompat wrapSw = new androidx.appcompat.widget.SwitchCompat(ctx);
        wrapSw.setText("循环查找");
        wrapSw.setChecked(true);
        wrapSw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(wrapSw);
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx)
                .setTitle("查找 / 替换")
                .setView(box)
                .setPositiveButton("下一个", null)   // null：不自动关闭，方便连续查找
                .setNegativeButton("替换", null)
                .setNeutralButton("全部替换", null)
                .create();
        dlg.setOnShowListener(d -> {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!findNext(findEt.getText().toString(), regexSw.isChecked(), wrapSw.isChecked(), true)) {
                    UiKit.alert(ctx, "未找到匹配");
                }
            });
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (!replaceCurrent(findEt.getText().toString(), repEt.getText().toString(),
                        regexSw.isChecked(), wrapSw.isChecked())) {
                    UiKit.alert(ctx, "未找到匹配");
                }
            });
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                int n = replaceAll(findEt.getText().toString(), repEt.getText().toString(), regexSw.isChecked());
                UiKit.toast(ctx, n > 0 ? "已替换 " + n + " 处" : "无匹配");
            });
        });
        dlg.show();
    }

    /** 查找下一个/上一个：命中则选中并返回 true（wrap 循环查找） */
    private boolean findNext(String query, boolean regex, boolean wrap, boolean forward) {
        if (editorEdit == null || currentEditTab == null || query.isEmpty()) return false;
        String text = editorEdit.getText().toString();
        int sel = forward ? editorEdit.getSelectionEnd() : editorEdit.getSelectionStart();
        if (sel < 0) sel = 0;
        int[] hit = null;
        if (forward) {
            hit = regex ? regexFind(text, query, sel, text.length(), true)
                    : plainFind(text, query, sel, true);
            if (hit == null && wrap) {
                hit = regex ? regexFind(text, query, 0, text.length(), true)
                        : plainFind(text, query, 0, true);
            }
        } else {
            hit = regex ? regexFind(text, query, 0, Math.max(0, sel - 1), false)
                    : plainFind(text, query, Math.max(0, sel - 1), false);
            if (hit == null && wrap) {
                hit = regex ? regexFind(text, query, 0, text.length(), false)
                        : plainFind(text, query, text.length(), false);
            }
        }
        if (hit == null) return false;
        editorEdit.setSelection(hit[0], hit[1]);
        return true;
    }

    /** 普通文本查找：返回 {start,end} 或 null */
    private int[] plainFind(String text, String query, int from, boolean forward) {
        if (forward) {
            int i = text.indexOf(query, Math.max(0, from));
            return i < 0 ? null : new int[]{i, i + query.length()};
        }
        int i = text.lastIndexOf(query, Math.min(text.length(), from));
        return i < 0 ? null : new int[]{i, i + query.length()};
    }


    /**
     * 检测用户正则是否有**灾难性回溯**风险，危险时拒绝执行。
     *
     * <p>实测（JDK 17）：{@code (.*a){20}$} 在 28 字符输入上耗时 25 秒，
     * 而这三处正则全在主线程跑 → 直接 ANR。</p>
     *
     * <p>注意：Java 的 Pattern 对 {@code (a+)+$}、{@code (a|a)*$} 这类
     * 经典 ReDoS **实测不爆**（0-1ms），真正危险的是
     * <b>带量词的分组外层再套量词</b>，即 {@code (.*x){n}} / {@code (.+x)+}
     * 这种「多量词嵌套」。</p>
     *
     * <p>只是启发式拦截，不追求完备 —— 目的是拦住最典型的手滑写法，
     * 而不是做正则静态分析。</p>
     */

    /**
     * 安全编译正则：先过 ReDoS 启发式，可疑就返回 null。
     *
     * <p><b>为什么必须有它</b>：搜索/替换有三处 Pattern.compile，
     * 其中「全部替换」最危险 —— 它一次扫全文，且跑在主线程。
     * 实测 {@code (.*a){20}$} 在 28 个字符上耗时 25 秒，
     * 在真实文档上就是永久卡死 + ANR。</p>
     *
     * <p>只有 regexFind 原来做过检查，两处替换裸奔。统一走这里，
     * 以后新增正则入口照抄即可。</p>
     */
    private static java.util.regex.Pattern safeCompile(String query) {
        if (query == null) return null;
        try {
            if (isReDoSRisky(query)) return null;
            return java.util.regex.Pattern.compile(query);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isReDoSRisky(String pattern) {
        if (pattern == null || pattern.length() > 200) return false;
        // 形如 (  ... 量词  ...  )  紧跟另一个量词
        // 例：(.*a){20}  (.+x)+  (\d*)+  ([a-z]*)*
        java.util.regex.Pattern risky = java.util.regex.Pattern.compile(
                "\\([^()]*[*+][^()]*\\)[*+{]", java.util.regex.Pattern.CASE_INSENSITIVE);
        if (risky.matcher(pattern).find()) return true;
        return false;
    }

    /** 正则查找：forward=true 找 region 内第一个；false 遍历取最后一个 */
    private int[] regexFind(String text, String query, int from, int to, boolean forward) {
        if (isReDoSRisky(query)) {
            UiKit.alert(ctx, "该正则可能导致长时间卡顿（多量词嵌套，如 (.*x){20}），"
                    + "请改用更简单的写法");
            return null;
        }
        try {
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(query);
            java.util.regex.Matcher m = pat.matcher(text);
            if (forward) {
                m.region(Math.max(0, from), Math.max(Math.max(0, from), to));
                return m.find() ? new int[]{m.start(), m.end()} : null;
            }
            int[] last = null;
            m.region(0, Math.min(text.length(), to));
            while (m.find()) last = new int[]{m.start(), m.end()};
            return last;
        } catch (Throwable t) {
            UiKit.alert(ctx, "正则错误：" + t.getMessage());
            return null;
        }
    }

    /** 替换当前匹配（无匹配则先查找定位） */
    private boolean replaceCurrent(String query, String replacement, boolean regex, boolean wrap) {
        if (editorEdit == null || currentEditTab == null || query.isEmpty()) return false;
        String text = editorEdit.getText().toString();
        int sel = editorEdit.getSelectionStart();
        if (sel < 0) sel = 0;
        int[] hit = regex ? regexFind(text, query, sel, text.length(), true)
                : plainFind(text, query, sel, true);
        if (hit == null && wrap) {
            hit = regex ? regexFind(text, query, 0, text.length(), true)
                    : plainFind(text, query, 0, true);
        }
        if (hit == null) return false;
        String rep = replacement;
        if (regex) {
            try {
                java.util.regex.Pattern cp = safeCompile(query);
                if (cp == null) {
                    UiKit.alert(ctx, "这个正则在长文本上可能卡死，已阻止。\n"
                            + "请简化嵌套量词，例如避免 (a*)*、(.*a){20} 这类写法。");
                    return false;
                }
                rep = cp.matcher(text.substring(hit[0], hit[1]))
                        .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
            } catch (Throwable t) {
                UiKit.alert(ctx, "正则错误：" + t.getMessage());
                return false;
            }
        }
        editorEdit.getText().replace(hit[0], hit[1], rep);
        editorEdit.setSelection(hit[0] + rep.length());
        return true;
    }

    /** 全部替换：返回替换次数（先入撤销快照，可一步撤销） */
    private int replaceAll(String query, String replacement, boolean regex) {
        if (editorEdit == null || currentEditTab == null || query.isEmpty()) return 0;
        String text = editorEdit.getText().toString();
        String out;
        int count;
        if (regex) {
            try {
                java.util.regex.Pattern cpAll = safeCompile(query);
                if (cpAll == null) {
                    UiKit.alert(ctx, "这个正则在长文本上可能卡死，已阻止。\n"
                            + "请简化嵌套量词，例如避免 (a*)*、(.*a){20} 这类写法。");
                    return 0;
                }
                java.util.regex.Matcher m = cpAll.matcher(text);
                count = 0;
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    count++;
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
                }
                m.appendTail(sb);
                out = sb.toString();
            } catch (Throwable t) {
                UiKit.alert(ctx, "正则错误：" + t.getMessage());
                return 0;
            }
        } else {
            count = 0;
            StringBuilder sb = new StringBuilder();
            int i = 0, prev = 0;
            while ((i = text.indexOf(query, i)) >= 0) {
                sb.append(text, prev, i).append(replacement);
                i += query.length();
                prev = i;
                count++;
            }
            sb.append(text, prev, text.length());
            out = sb.toString();
        }
        if (count == 0) return 0;
        pushUndo(text, -1); // 替换前快照（-1 不与连续输入合并）
        editorEdit.removeTextChangedListener(editorWatcher);
        editorEdit.setText(out);
        editorEdit.addTextChangedListener(editorWatcher);
        currentEditTab.dirty = true;
        renderEditorTabs();
        refreshEditorStat();
        return count;
    }

    // ── 跳转行（输入行号 → setSelection 定位到行首） ──
    private void showGotoLineDialog() {
        if (currentEditTab == null || editorEdit == null) return;
        final EditText input = new EditText(ctx);
        input.setHint("输入行号（1 ~ " + countLines(editorEdit.getText().toString()) + "）");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int p = dp(16);
        input.setPadding(p, p, p, p);
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("跳转行")
                .setView(input)
                .setPositiveButton("跳转", (d, w) -> {
                    try {
                        gotoLine(Integer.parseInt(input.getText().toString().trim()));
                    } catch (Throwable ignored) {
                        UiKit.toast(ctx, "请输入有效行号");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 跳转到指定行（行号越界自动钳制） */
    private void gotoLine(int line) {
        if (editorEdit == null) return;
        String text = editorEdit.getText().toString();
        int total = countLines(text);
        if (line < 1) line = 1;
        if (line > total) line = total;
        int pos = 0;
        for (int i = 1; i < line; i++) pos = text.indexOf('\n', pos) + 1;
        editorEdit.setSelection(Math.min(pos, text.length()));
        refreshEditorStat();
    }

    // ── 编码选择保存（UTF-8/GBK）+ 行尾符切换（LF/CRLF） ──
    private void showEncodeDialog() {
        if (currentEditTab == null) return;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, 0);
        TextView encTitle = new TextView(ctx);
        encTitle.setText("编码（保存时按此写回）");
        encTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        encTitle.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        box.addView(encTitle);
        final android.widget.RadioButton[] enc = new android.widget.RadioButton[2];
        enc[0] = new android.widget.RadioButton(ctx);
        enc[0].setText("UTF-8（推荐）");
        enc[0].setChecked("UTF-8".equals(currentEditTab.encoding));
        enc[1] = new android.widget.RadioButton(ctx);
        enc[1].setText("GBK（中文 Windows 兼容）");
        enc[1].setChecked("GBK".equals(currentEditTab.encoding));
        android.widget.RadioGroup encGroup = new android.widget.RadioGroup(ctx);
        encGroup.addView(enc[0]);
        encGroup.addView(enc[1]);
        box.addView(encGroup);
        TextView eolTitle = new TextView(ctx);
        eolTitle.setText("行尾符");
        eolTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        eolTitle.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        box.addView(eolTitle);
        final android.widget.RadioButton[] eol = new android.widget.RadioButton[2];
        eol[0] = new android.widget.RadioButton(ctx);
        eol[0].setText("LF（Unix/Linux/Android）");
        eol[0].setChecked("LF".equals(currentEditTab.lineEnding));
        eol[1] = new android.widget.RadioButton(ctx);
        eol[1].setText("CRLF（Windows）");
        eol[1].setChecked("CRLF".equals(currentEditTab.lineEnding));
        android.widget.RadioGroup eolGroup = new android.widget.RadioGroup(ctx);
        eolGroup.addView(eol[0]);
        eolGroup.addView(eol[1]);
        box.addView(eolGroup);
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("编码与行尾符")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    currentEditTab.encoding = enc[0].isChecked() ? "UTF-8" : "GBK";
                    currentEditTab.lineEnding = eol[0].isChecked() ? "LF" : "CRLF";
                    if (editorHeader != null) {
                        editorHeader.setText(currentEditTab.file.getName() + " · "
                                + currentEditTab.encoding + " · "
                                + FileOps.formatSize(currentEditTab.file.length()));
                    }
                    refreshEditorStat();
                    UiKit.toast(ctx, "已切换 " + currentEditTab.encoding
                            + " / " + currentEditTab.lineEnding + "，保存时生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 编辑器设置：字号 / 等宽 / Tab宽度 / 主题（持久化，即时生效） ──
    private void showEditorSettings() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, 0);
        final TextView sizeTv = new TextView(ctx);
        sizeTv.setText("字号：" + (int) editorFontSizePref() + " sp");
        sizeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(sizeTv);
        final SeekBar sizeSb = new SeekBar(ctx);
        sizeSb.setMax(14);
        sizeSb.setProgress((int) editorFontSizePref() - 10);
        sizeSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                sizeTv.setText("字号：" + (progress + 10) + " sp");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(sizeSb);
        final androidx.appcompat.widget.SwitchCompat monoSw = new androidx.appcompat.widget.SwitchCompat(ctx);
        monoSw.setText("等宽字体");
        monoSw.setChecked(editorMonoPref());
        box.addView(monoSw);
        final TextView tabTv = new TextView(ctx);
        tabTv.setText("Tab 宽度：" + editorTabWidthPref() + " 空格");
        tabTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(tabTv);
        final SeekBar tabSb = new SeekBar(ctx);
        tabSb.setMax(6);
        tabSb.setProgress(editorTabWidthPref() - 2);
        tabSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tabTv.setText("Tab 宽度：" + (progress + 2) + " 空格");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(tabSb);
        TextView themeTitle = new TextView(ctx);
        themeTitle.setText("编辑器主题");
        themeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(themeTitle);
        final android.widget.RadioButton[] theme = new android.widget.RadioButton[3];
        theme[0] = new android.widget.RadioButton(ctx);
        theme[0].setText("跟随系统");
        theme[0].setChecked(editorThemePref() == 0);
        theme[1] = new android.widget.RadioButton(ctx);
        theme[1].setText("浅色");
        theme[1].setChecked(editorThemePref() == 1);
        theme[2] = new android.widget.RadioButton(ctx);
        theme[2].setText("深色");
        theme[2].setChecked(editorThemePref() == 2);
        android.widget.RadioGroup themeGroup = new android.widget.RadioGroup(ctx);
        themeGroup.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        for (android.widget.RadioButton rb : theme) themeGroup.addView(rb);
        box.addView(themeGroup);
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("编辑器设置")
                .setView(box)
                .setPositiveButton("应用", (d, w) -> {
                    fmPrefs().edit()
                            .putFloat(EDITOR_PREF_FONT_SIZE, sizeSb.getProgress() + 10f)
                            .putBoolean(EDITOR_PREF_MONO, monoSw.isChecked())
                            .putInt(EDITOR_PREF_TAB_W, tabSb.getProgress() + 2)
                            .putInt(EDITOR_PREF_THEME, theme[0].isChecked() ? 0 : theme[1].isChecked() ? 1 : 2)
                            .apply();
                    applyEditorSettings();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 应用编辑器设置：重建会话 UI（标签/内容/撤销栈保留在内存）即时生效 */
    private void applyEditorSettings() {
        if (editorDialog == null) return;
        editorDialog.dismiss();
        editorDialog = null;
        editorEdit = null;
        editorStat = null;
        editorHeader = null;
        editorTabBar = null;
        editorWatcher = null;
        ensureEditorDialog();
        if (currentEditTab != null) switchEditorTab(currentEditTab);
        if (editorDialog != null) editorDialog.show();
    }

    // ── HTML / Markdown 预览（WebView 全屏；Markdown 极简转 HTML，无第三方库） ──
    /** 销毁预览 WebView（loadUrl/stopLoading/destroy 三步缺一不可） */
    private void destroyPreviewWebView() {
        if (previewWebView == null) return;
        try {
            previewWebView.stopLoading();
            previewWebView.loadUrl("about:blank");
            previewWebView.clearHistory();
            previewWebView.removeAllViews();
            android.view.ViewParent parent = previewWebView.getParent();
            if (parent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) parent).removeView(previewWebView);
            }
            previewWebView.destroy();
        } catch (Throwable ignored) {
        }
        previewWebView = null;
    }

    private void showPreviewDialog() {
        if (currentEditTab == null) return;
        final String ext = currentEditTab.file.getName().toLowerCase();
        final boolean isHtml = ext.endsWith(".html") || ext.endsWith(".htm")
                || ext.endsWith(".xhtml") || ext.endsWith(".mht");
        final boolean isMd = ext.endsWith(".md") || ext.endsWith(".markdown")
                || ext.endsWith(".mdown") || ext.endsWith(".mkd")
                || ext.endsWith(".txt");
        if (!isHtml && !isMd) {
            UiKit.toast(ctx, "仅支持 HTML / Markdown / TXT 预览");
            return;
        }
        final String content = editorEdit.getText().toString();
        final int[] pal = editorPalette();
        final String css = "<style>body{font-family:sans-serif;padding:14px;line-height:1.6;color:"
                + hexColor(pal[1]) + ";background:" + hexColor(pal[0]) + ";}pre{background:"
                + hexColor(pal[3]) + ";padding:10px;border-radius:6px;overflow-x:auto;}code{background:"
                + hexColor(pal[3]) + ";padding:1px 4px;border-radius:3px;}pre code{background:transparent;padding:0;}"
                + "table{border-collapse:collapse;}td,th{border:1px solid " + hexColor(pal[5])
                + ";padding:6px 10px;}blockquote{border-left:3px solid " + hexColor(pal[4])
                + ";margin:8px 0;padding:4px 12px;color:" + hexColor(pal[2]) + ";}</style>";
        final String html;
        if (isHtml) {
            html = content;
        } else if (isMd) {
            // 真正的 Markdown 渲染（零依赖自实现，见 util/MarkdownUtils）
            String body = com.aliya.hyfilemgr.util.MarkdownUtils.toHtml(content);
            html = "<html><head><meta charset=\"utf-8\">" + css + "</head><body>"
                    + body + "</body></html>";
        } else {
            html = "<html><head><meta charset=\"utf-8\">" + css + "</head><body><pre>"
                    + android.text.TextUtils.htmlEncode(content) + "</pre></body></html>";
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(pal[0]);
        TextView title = new TextView(ctx);
        title.setText("预览 · " + currentEditTab.file.getName());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), dp(8), 0, dp(8));
        title.setTextColor(pal[1]);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(textButton("关闭", v -> {
            if (webDialog != null) {
                webDialog.dismiss();
                webDialog = null;
            }
            destroyPreviewWebView();
        }));
        box.addView(top);
          // 统一走 MarkdownWebView；⚠️ 不再开启 JavaScript —— 预览的是用户
          // 任意 .md/.html 文件，开 JS 等于给每个文件一个脚本执行入口
          android.webkit.WebView wv = new android.webkit.WebView(ctx);
          previewWebView = wv;
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        box.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        webDialog = new android.app.Dialog(ctx);
        webDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        webDialog.setContentView(box);
        android.view.Window win = webDialog.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(pal[0]));
        }
        webDialog.show();
    }

    // ════════════════════════════════════════════════════════════
    // 编辑器侧边栏（文件树抽屉）：☰ 弹出；浏览目录/打开文件/原功能菜单
    // （2026-08-17 用户需求：三条杠改为侧边栏形式，内部有文件树）
    // ════════════════════════════════════════════════════════════

    /** 打开编辑器侧边栏：左侧抽屉（78% 屏宽）+ 遮罩，面板左滑入 + 遮罩淡入同步
     *  （动画对齐主界面侧边栏 250ms） */
    /** 编辑器：另存为（复制到新路径，原文件不变） */
    private void showEditorSaveAsDialog() {
        if (currentEditTab == null) {
            UiKit.toast(ctx, "没有打开的编辑标签");
            return;
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "另存为"));

        TextView hint = new TextView(ctx);
        hint.setText("输入目标路径（支持绝对路径，或相对当前文件所在目录）");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(hint);

        final EditText et = new EditText(ctx);
        File cur = currentEditTab.file;
        String dir = (cur != null && cur.getParent() != null)
                ? cur.getParent() : "/sdcard";
        et.setText(dir + "/" + (cur != null ? cur.getName() : "untitled.txt"));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setSingleLine(true);
        box.addView(et);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        box.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btns.addView(textButton("取消", v -> d.dismiss()));
        btns.addView(textButton("保存", v -> {
            String path = et.getText().toString().trim();
            if (path.isEmpty()) {
                UiKit.alert(ctx, "路径不能为空");
                return;
            }
            File target = AccessRouter.fileOf(path);
            if (!target.isAbsolute()) target = new File(dir, path);
            d.dismiss();
            doSaveAs(target);
        }));
        d.show();
        et.postDelayed(() -> {
            et.requestFocus();
            et.setSelection(et.getText().length());
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, 0);
        }, 150);
    }

    /** 执行另存为：写文件，成功后提示（不切换当前标签） */
    private void doSaveAs(File target) {
        if (currentEditTab == null) return;
        try {
            if (target.isDirectory()) {
                UiKit.toast(ctx, "目标是目录，请输入文件名");
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                UiKit.alert(ctx, "无法创建目录：" + parent);
                return;
            }
            String text = editorEdit != null ? editorEdit.getText().toString()
                    : String.valueOf(currentEditTab.content);
            // 与 saveEditorTab 一致：按用户选的编码 + 行尾符写回
            String enc = currentEditTab.encoding == null
                    ? "UTF-8" : currentEditTab.encoding;
            String le = currentEditTab.lineEnding;
            if ("CRLF".equalsIgnoreCase(le)) {
                text = text.replace("\r\n", "\n").replace("\n", "\r\n");
            } else if ("CR".equalsIgnoreCase(le)) {
                text = text.replace("\r\n", "\n").replace("\n", "\r");
            }
            java.nio.charset.Charset cs;
            try {
                cs = java.nio.charset.Charset.forName(enc);
            } catch (Throwable t) {
                cs = java.nio.charset.StandardCharsets.UTF_8;
            }
            try (java.io.OutputStream os = new java.io.FileOutputStream(target)) {
                os.write(text.getBytes(cs));
                os.flush();
            }
            UiKit.toast(ctx, "已另存为：" + target.getName());
            reloadAll();
        } catch (Throwable t) {
            UiKit.alert(ctx, "另存为失败：" + t.getMessage());
        }
    }

    /** 编辑器：复制全部文本到剪贴板 */
    private void copyEditorAllText() {
        try {
            String text = editorEdit != null
                    ? editorEdit.getText().toString()
                    : (currentEditTab != null
                        ? String.valueOf(currentEditTab.content) : "");
            if (text.isEmpty()) {
                UiKit.toast(ctx, "编辑器内容为空");
                return;
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "编辑器内容", text));
                UiKit.toast(ctx, "已复制 " + text.length() + " 字符");
            }
        } catch (Throwable t) {
            UiKit.alert(ctx, "复制失败：" + t.getMessage());
        }
    }

    /**
     * 编辑器自动缩进。
     *
     * <p>回车时：
     * ① 继承上一行的前导空白（空格 / Tab 原样复制）
     * ② 若上一行以 <code>{ [ ( :</code> 结尾，再追加一级缩进（Tab 宽度的空格）</p>
     *
     * <p>用 TextWatcher 而非 OnKeyListener 实现：软键盘回车不一定产生
     * KEYCODE_ENTER，但一定会产生文本变化（换行符）。</p>
     */
    private void attachAutoIndent(final EditText et) {
        if (et == null) return;
        et.addTextChangedListener(new android.text.TextWatcher() {
            /** 上一次的文本，用于判断「刚插入了一个换行」 */
            private String prev = "";
            /** 自增标记，本轮插入是我们自己做的不再重复处理 */
            private boolean selfEdit = false;

            @Override
            public void beforeTextChanged(CharSequence s2, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s2, int start, int before, int count) { }

            @Override
            public void afterTextChanged(android.text.Editable e2) {
                if (selfEdit) {
                    prev = e2.toString();
                    return;
                }
                String now = e2.toString();
                String old = prev;
                prev = now;
                if (!editorAutoIndent) return;
                if (now.length() != old.length() + 1) return;   // 只处理插入 1 字符

                // 找到插入位置并确认是换行
                int cur = et.getSelectionStart();
                if (cur <= 0 || cur > now.length()) return;
                char inserted = now.charAt(cur - 1);
                if (inserted != '\n') return;

                // 上一行（不含刚插入的换行）
                int lineStart = now.lastIndexOf('\n', cur - 2);
                String prevLine = now.substring(lineStart + 1, cur - 1);

                // ① 前导空白
                StringBuilder indent = new StringBuilder();
                for (int i = 0; i < prevLine.length(); i++) {
                    char ch = prevLine.charAt(i);
                    if (ch == ' ' || ch == '\t') indent.append(ch);
                    else break;
                }
                // ② 结构缩进
                String trimmed = prevLine.trim();
                if (!trimmed.isEmpty()) {
                    char last = trimmed.charAt(trimmed.length() - 1);
                    if (last == '{' || last == '[' || last == '(' || last == ':') {
                        indent.append(" ".repeat(Math.max(1, editorTabWidthPref())));
                    }
                }
                if (indent.length() == 0) return;

                selfEdit = true;
                try {
                    e2.insert(cur, indent.toString());
                    et.setSelection(cur + indent.length());
                } catch (Throwable ignored) {
                }
                selfEdit = false;
                prev = e2.toString();
            }
        });
    }

    private void showEditorDrawer() {
        if (currentEditTab == null) return;
        if (editorDrawer != null && editorDrawer.isShowing()) {
            closeEditorDrawer();
            return;
        }
        final int[] pal = editorPalette();
        if (editorDrawerDir == null) {
            File pf = currentEditTab.file.getParentFile();
            editorDrawerDir = (pf != null && pf.canRead()) ? pf : new File("/");
        }
        int panelW = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.78f);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(pal[0]);
        // 顶部：当前路径 + 关闭×
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(10), dp(4), dp(10));
        editorDrawerPath = new TextView(ctx);
        editorDrawerPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        editorDrawerPath.setTextColor(pal[2]);
        editorDrawerPath.setSingleLine(true);
        editorDrawerPath.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(editorDrawerPath, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView closeX = new TextView(ctx);
        closeX.setText("×");
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        closeX.setTextColor(pal[1]);
        closeX.setPadding(dp(12), 0, dp(12), 0);
        closeX.setOnClickListener(v -> closeEditorDrawer());
        head.addView(closeX);
        panel.addView(head);
        View div = new View(ctx);
        div.setBackgroundColor(pal[5]);
        panel.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        // 文件树滚动区（weight=1 占满）
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        editorDrawerList = new LinearLayout(ctx);
        editorDrawerList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(editorDrawerList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // 底部：原功能菜单（紧凑行）
        View div2 = new View(ctx);
        div2.setBackgroundColor(pal[5]);
        panel.addView(div2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout menu = new LinearLayout(ctx);
        menu.setOrientation(LinearLayout.VERTICAL);
        addDrawerMenuRow(menu, "跳转行", R.drawable.ic_goto, v -> {
            closeEditorDrawer();
            showGotoLineDialog();
        });
        addDrawerMenuRow(menu, "编码与行尾符", R.drawable.ic_encode, v -> {
            closeEditorDrawer();
            showEncodeDialog();
        });
        addDrawerMenuRow(menu, "编辑器设置", R.drawable.ic_settings, v -> {
            closeEditorDrawer();
            showEditorSettings();
        });
        addDrawerMenuRow(menu, "HTML/Markdown 预览", R.drawable.ic_preview, v -> {
            closeEditorDrawer();
            showPreviewDialog();
        });
        addDrawerMenuRow(menu, "另存为…", R.drawable.ic_save, v -> {
            closeEditorDrawer();
            showEditorSaveAsDialog();
        });
        addDrawerMenuRow(menu, "复制全部文本", R.drawable.ic_copy, v -> {
            closeEditorDrawer();
            copyEditorAllText();
        });
        addDrawerMenuRow(menu, "保存当前文件", R.drawable.ic_save, v -> {
            closeEditorDrawer();
            if (currentEditTab != null) saveEditorTab(currentEditTab, null);
        });
        addDrawerMenuRow(menu, "全部保存", R.drawable.ic_save, v -> {
            closeEditorDrawer();
            saveAllEditorTabs();
        });
        addDrawerMenuRow(menu, "关闭编辑器", R.drawable.ic_more_vert, v -> {
            closeEditorDrawer();
            confirmEditorExit();
        });
        panel.addView(menu);
        // 遮罩：点击关闭
        View mask = new View(ctx);
        mask.setBackgroundColor(0x66000000);
        mask.setOnClickListener(v -> closeEditorDrawer());
        root.addView(panel, new LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(mask, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        editorDrawer = new android.app.Dialog(ctx);
        editorDrawer.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        editorDrawer.setContentView(root);
        android.view.Window win = editorDrawer.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        renderEditorDrawer();
        // 动画：先置初始状态再显示（无闪烁帧），面板左滑入 + 遮罩淡入同步
        panel.setTranslationX(-panelW - dp(20));
        mask.setAlpha(0f);
        editorDrawer.show();
        panel.animate().translationX(0f).setDuration(250).start();
        mask.animate().alpha(1f).setDuration(250).start();
    }

    /** 关闭编辑器侧边栏：整体淡出后 dismiss */
    private void closeEditorDrawer() {
        if (editorDrawer == null || !editorDrawer.isShowing()) return;
        editorDrawer.getWindow().getDecorView().animate().alpha(0f).setDuration(150)
                .withEndAction(() -> {
                    if (editorDrawer != null) editorDrawer.dismiss();
                }).start();
    }

    /** 渲染文件树：↑上级 + 目录（进入）+ 文件（打开）；目录优先、名称排序、隐藏文件跳过 */
    private void renderEditorDrawer() {
        if (editorDrawerList == null) return;
        editorDrawerList.removeAllViews();
        if (editorDrawerPath != null) {
            editorDrawerPath.setText(editorDrawerDir == null ? "" : editorDrawerDir.getAbsolutePath());
        }
        final File dir = editorDrawerDir;
        if (dir == null) return;
        File[] files = dir.listFiles();
        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) {
            addDrawerRow(R.drawable.ic_folder, "↑ 上级目录", parent.getName(), v -> {
                editorDrawerDir = parent;
                renderEditorDrawer();
            });
        }
        if (files == null) {
            addDrawerRow(R.drawable.ic_file, "（无法读取该目录）", "", null);
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File f : files) {
            String nm = f.getName();
            if (nm.startsWith(".")) continue; // 隐藏文件跳过
            if (f.isDirectory()) {
                addDrawerRow(R.drawable.ic_folder, nm, "目录", v -> {
                    editorDrawerDir = f;
                    renderEditorDrawer();
                });
            } else {
                addDrawerRow(iconForDrawerFile(nm), nm, FileOps.formatSize(f.length()),
                        v -> drawerOpenFile(f));
            }
        }
    }

    /** 抽屉文件行：图标 + 标题(weight=1) + 次级信息 */
    private void addDrawerRow(int iconRes, String title, String sub, View.OnClickListener onClick) {
        if (editorDrawerList == null) return;
        final int[] pal = editorPalette();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(14), dp(9));
        if (onClick != null) row.setOnClickListener(onClick);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(pal[2]);
        int s = dp(20);
        row.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(pal[1]);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginStart(dp(10));
        row.addView(tv, tvLp);
        if (sub != null && !sub.isEmpty()) {
            TextView subTv = new TextView(ctx);
            subTv.setText(sub);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            subTv.setTextColor(pal[2]);
            row.addView(subTv);
        }
        editorDrawerList.addView(row);
    }

    /** 抽屉底部菜单行（紧凑版，复用原三条杠菜单功能） */
    private void addDrawerMenuRow(LinearLayout panel, String text, int iconRes, View.OnClickListener onClick) {
        final int[] pal = editorPalette();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.setOnClickListener(onClick);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(pal[2]);
        int s = dp(18);
        row.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(pal[1]);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.setMarginStart(dp(10));
        row.addView(tv, tvLp);
        panel.addView(row);
    }

    /** 按扩展名分类映射格式图标（复用 CATEGORIES 分类清单） */
    private int iconForDrawerFile(String name) {
        String n = name.toLowerCase();
        for (String[] c : CATEGORIES) {
            String[] exts = c[2].trim().split("\\s+");
            for (String ext : exts) {
                if (!ext.isEmpty() && n.endsWith(ext)) {
                    switch (c[0]) {
                        case "images": return R.drawable.ic_img;
                        case "video": return R.drawable.ic_video;
                        case "audio": return R.drawable.ic_audio;
                        case "apk": return R.drawable.ic_apk;
                        case "archives": return R.drawable.ic_archive;
                        case "docs": return R.drawable.ic_doc;
                    }
                }
            }
        }
        return R.drawable.ic_file;
    }

    /** 抽屉点击文件：文本（≤2MB）→ 编辑器开新标签；其他 → 系统打开 */
    private void drawerOpenFile(File f) {
        if (f.isDirectory()) {
            editorDrawerDir = f;
            renderEditorDrawer();
            return;
        }
        closeEditorDrawer();
        if (f.length() <= MAX_EDIT_BYTES && isTextFile(f.getName())) {
            openTextEditor(f);
        } else {
            openFile(f);
        }
    }


    /** 纯视频扩展名：用于播放器左右切换时筛选同目录文件 */
    private static final java.util.Set<String> VIDEO_ONLY_EXTS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
                    "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf"));

    /** 纯音频扩展名：用弹窗播放器（原生 MediaPlayer），与视频分开处理 */
    private static final java.util.Set<String> AUDIO_ONLY_EXTS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "amr",
                    "aiff", "ape", "wv", "mid", "midi", "ac3", "dts", "mka", "ra", "au", "caf"));

    /** 图片扩展名：命中时内置「图片查看器」可用 */
    private static final java.util.Set<String> IMAGE_EXTS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg",
                    "ico", "tif", "tiff", "raw", "cr2", "nef", "arw", "dng", "psd", "ai"));

    /** 音视频扩展名：命中则直接调用**内置播放器**，不再走系统外部应用 */
    private static final java.util.Set<String> MEDIA_EXTS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    // 视频
                    "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
                    "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf",
                    // 音频
                    "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "amr",
                    "aiff", "ape", "wv", "mid", "midi", "ac3", "dts", "mka", "ra", "au", "caf"));

    /** 由宿主在「从其他页面（如设置）或后台返回」时调用：下次加载恢复原滚动位置 */
    public void markReturnToForeground() {
        restoreScrollOnLoad = true;
    }

    /** 收集当前活动目录下同类型的可播放文件（供播放器左右切换）。
     *  找不到时返回仅含自身的单元素列表，保证播放器始终有可用列表。 */
    private java.util.ArrayList<String> collectPlayable(String curPath, boolean audioOnly) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        Pane p = active;
        if (p != null && p.entries != null) {
            for (FileEntry e : p.entries) {
                if (e == null || e.isDir() || e.name == null) continue;
                String ex = extOf(e.name);
                boolean ok = audioOnly ? AUDIO_ONLY_EXTS.contains(ex) : VIDEO_ONLY_EXTS.contains(ex);
                if (ok && e.path != null && !e.path.isEmpty()) out.add(e.path);
            }
        }
        if (out.isEmpty() && curPath != null) out.add(curPath);
        return out;
    }

    /** 用内置播放器打开（视频：全屏页 + 同目录左右切换） */
    /** 用内置播放器（MediaPlayerActivity，Media3 内核）打开音视频，带同目录播放列表 */
    private void openWithBuiltInPlayer(String pathOrUri, String name, boolean isUri) {
        try {
            java.util.ArrayList<String> list = collectPlayable(pathOrUri, false);
            int idx = list.indexOf(pathOrUri);
            if (idx < 0) {
                list.add(0, pathOrUri);
                idx = 0;
            }
            Intent i = new Intent(ctx, com.aliya.hyfilemgr.MediaPlayerActivity.class);
            i.putExtra(com.aliya.hyfilemgr.MediaPlayerActivity.EXTRA_LIST,
                    list.toArray(new String[0]));
            i.putExtra(com.aliya.hyfilemgr.MediaPlayerActivity.EXTRA_INDEX, idx);
            i.putExtra(com.aliya.hyfilemgr.MediaPlayerActivity.EXTRA_IS_URI, isUri);
            i.putExtra(com.aliya.hyfilemgr.MediaPlayerActivity.EXTRA_TITLE, name);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开播放器：" + t.getMessage());
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private void openFile(File f) {
        final String ext = extOf(f.getName());
        final String path = f.getAbsolutePath();
        final String name = f.getName();
        // ⭐ v2.8.0 root 文件：App 无权读 → 先中转（root → App 缓存，含 restorecon）再打开
        if (!f.canRead() && needRoot(path)) {
            return;
        }
        // 格式明确且内置功能存在 → 直接调用内置功能，不弹窗打扰
        if (IMAGE_EXTS.contains(ext)) {
            openImageViewer(path, name, false);
            return;
        }
        if (AUDIO_ONLY_EXTS.contains(ext)) {
            // 音频：弹窗式播放（原生 MediaPlayer，无需 Surface）+ 同目录左右切换
            java.util.ArrayList<String> list = collectPlayable(path, true);
            int idx = list.indexOf(path);
            if (idx < 0) {
                list.add(0, path);
                idx = 0;
            }
            showAudioPlayerDialog(list, idx, false);
            return;
        }
        if (MEDIA_EXTS.contains(ext)) {
            openWithBuiltInPlayer(path, name, false);   // 视频：全屏播放（Media3 内核）
            return;
        }
        if (isTextFile(name)) {
            openTextEditor(f);
            return;
        }
        // 其余（APK / 压缩包 / 未知类型）：内置行为带副作用或不确定 → 弹「打开方式」由用户选择
        showOpenWithDialog(path, name, false);
    }

    // ==================== 打开方式弹窗 ====================

    /** 打开方式弹窗：默认展示**内置**打开方式；左下角可切换到**系统**打开方式。
     *  系统页列出系统已注册的应用（点击直接由该应用打开），**不拉起系统选择器**。 */
    private void showOpenWithDialog(final String pathOrUri, final String name, final boolean isUri) {
        final String ext = extOf(name);
        final boolean isImg = IMAGE_EXTS.contains(ext);
        final boolean isMedia = MEDIA_EXTS.contains(ext);
        final boolean isText = isTextFile(name);
        final boolean isZipFile = "zip".equals(ext) || "jar".equals(ext);
        final boolean isApk = "apk".equals(ext);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "打开方式"));

        TextView tvName = new TextView(ctx);
        tvName.setText(name);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvName.setMaxLines(2);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tvName.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        int p4 = dp(4);
        tvName.setPadding(p4, 0, p4, dp(8));
        box.addView(tvName);

        final TextView tvPage = new TextView(ctx);
        tvPage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvPage.setTypeface(null, android.graphics.Typeface.BOLD);
        tvPage.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        tvPage.setPadding(p4, 0, p4, dp(6));
        box.addView(tvPage);

        final LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sc = new android.widget.ScrollView(ctx);
        sc.addView(listBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)));

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView btnSwitch = new TextView(ctx);
        btnSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnSwitch.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSwitch.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        btnSwitch.setPadding(p4, dp(10), p4, dp(10));
        TextView btnCancel = new TextView(ctx);
        btnCancel.setText("取消");
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnCancel.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        btnCancel.setPadding(dp(12), dp(10), p4, dp(10));
        btns.addView(btnSwitch, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(btnCancel);
        box.addView(btns);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        final boolean[] sysMode = {false};

        final Runnable render = () -> {
            listBox.removeAllViews();
            tvPage.setText(sysMode[0] ? "系统打开方式" : "内置打开方式");
            btnSwitch.setText(sysMode[0] ? "内置打开方式" : "系统打开方式");
            if (!sysMode[0]) {
                // 全部解锁：不再按扩展名限制可用性，任何文件都可尝试任一内置方式；
                // 打开失败时由 showOpenFailed() 弹统一提示弹窗。
                String[][] items = {
                        {"img", "图片查看", "双指缩放 · 拖动 · 双击还原", "true"},
                        {"video", "播放器", "视频与音频 · 进度控制", "true"},
                        {"doc", "文本编辑", "查看与编辑文本、代码", "true"},
                        {"archive", "解压", "ZIP 压缩包", "true"},
                        {"apk", "安装", "Android 安装包", "true"},
                        {"font", "字体预览", "TTF · OTF · TTC 样张", "true"},
                };
                // 与长按菜单同一套卡片式按键：每行 4 个，行数按数量自适应
                final int PER_ROW = 4;
                LinearLayout row = null;
                for (int i = 0; i < items.length; i++) {
                    if (i % PER_ROW == 0) {
                        row = new LinearLayout(ctx);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        rowLp.topMargin = dp(1);
                        listBox.addView(row, rowLp);
                    }
                    final String[] it = items[i];
                    int res = "img".equals(it[0]) ? R.drawable.ic_img
                            : "video".equals(it[0]) ? R.drawable.ic_video
                            : "doc".equals(it[0]) ? R.drawable.ic_doc
                            : "archive".equals(it[0]) ? R.drawable.ic_archive
                            : "font".equals(it[0]) ? R.drawable.ic_font
                            : R.drawable.ic_apk;
                    final boolean en = Boolean.parseBoolean(it[3]);
                    addGridItem(row, it[1], res, en, v -> {
                        d.dismiss();
                        String err = null;
                        try {
                            switch (it[0]) {
                                case "img":
                                    err = tryOpenImageViewer(pathOrUri, name, isUri); break;
                                case "video":
                                    err = tryOpenWithBuiltInPlayer(pathOrUri, name, isUri); break;
                                case "doc":
                                    err = tryOpenInEditor(pathOrUri, name, isUri); break;
                                case "archive":
                                    err = tryUnzip(pathOrUri, name, isUri); break;
                                case "font":
                                    err = tryOpenFontPreview(pathOrUri, name, isUri); break;
                                default:
                                    err = tryInstallApk(pathOrUri, name, isUri); break;
                            }
                        } catch (Throwable t) {
                            err = t.getClass().getSimpleName() + ": " + t.getMessage();
                        }
                        if (err != null && !err.isEmpty()) {
                            showOpenFailed(it[1], name, err, pathOrUri, isUri);
                        }
                    });
                    // 行结束 → 末尾补空位，卡片保持左对齐等宽
                    boolean rowEnd = (i % PER_ROW == PER_ROW - 1) || (i == items.length - 1);
                    if (rowEnd) {
                        int filled = i % PER_ROW + 1;
                        for (int k = filled; k < PER_ROW; k++) {
                            View sp = new View(ctx);
                            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 1, 1f);
                            int gap = dp(5) / 2;
                            spLp.setMargins(gap, gap, gap, gap);
                            row.addView(sp, spLp);
                        }
                    }
                }
            } else {
                renderSystemOpeners(listBox, d, pathOrUri, name, isUri);
            }
        };

        btnSwitch.setOnClickListener(v -> {
            sysMode[0] = !sysMode[0];
            render.run();
        });
        btnCancel.setOnClickListener(v -> d.dismiss());
        render.run();
        d.show();
    }


    /** 判断网格列数：竖屏 3 列 / 横屏 4 列 */
    /**
     * 网格列数：固定 4 列（HY_VQ beta.10 同款）。
     *
     * <p>不随屏幕方向变化，也不按可用宽度降列。竖屏下每格约 1/4 弹窗宽度，
     * 文案按此宽度裁短（见 showItemMenu / showOpenWithDialog 的 items）。</p>
     */
    private int openGridColumns() {
        return 4;
    }

    private void addOpenRow(LinearLayout parent, final android.app.Dialog dialog,
                            int iconRes, String title, String desc, boolean enabled,
                            final Runnable action) {
        int onSurface = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface);
        int variant = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        int primary = UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (enabled) {
            row.setBackground(UiKit.rounded(ctx, 14,
                    UiKit.color(ctx,
                            com.google.android.material.R.attr.colorSurfaceContainerLow),
                    UiKit.color(ctx,
                            com.google.android.material.R.attr.colorOutlineVariant)));
        }

        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconRes);
        int isz = dp(24);
        ic.setLayoutParams(new LinearLayout.LayoutParams(isz, isz));
        ic.setColorFilter(enabled ? primary : variant);
        ic.setAlpha(enabled ? 1f : 0.3f);
        row.addView(ic);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t1 = new TextView(ctx);
        t1.setText(title);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t1.setTextColor(enabled ? onSurface : variant);
        t1.setAlpha(enabled ? 1f : 0.45f);
        TextView t2 = new TextView(ctx);
        t2.setText(enabled ? desc : desc + " · 不适用于此文件");
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t2.setTextColor(variant);
        t2.setAlpha(enabled ? 1f : 0.45f);
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        parent.addView(row, lp);

        if (enabled) {
            row.setOnClickListener(v -> {
                dialog.dismiss();
                action.run();
            });
        }
    }

    // ══════════ 内置打开方式：解锁版（不加格式限制，失败返回错误信息）══════════

    /**
     * 尝试用内置图片查看器打开。
     * @return null 表示已成功启动；非 null 为失败原因
     */
    private String tryOpenImageViewer(String pathOrUri, String name, boolean isUri) {
        if (!isUri) {
            File f = AccessRouter.fileOf(pathOrUri);
            if (!f.exists()) return "文件不存在或无法访问";
            if (!f.canRead()) return "没有读取该文件的权限";
        }
        openImageViewer(pathOrUri, name, isUri);
        return null;
    }

    /** 尝试用内置播放器打开。 */
    private String tryOpenWithBuiltInPlayer(String pathOrUri, String name, boolean isUri) {
        if (!isUri) {
            File f = AccessRouter.fileOf(pathOrUri);
            if (!f.exists()) return "文件不存在或无法访问";
            if (!f.canRead()) return "没有读取该文件的权限";
        }
        openWithBuiltInPlayer(pathOrUri, name, isUri);
        return null;
    }

    /** 尝试用内置文本编辑器打开。 */
    private String tryOpenInEditor(String pathOrUri, String name, boolean isUri) {
        if (isUri) return "SAF 文件（content://）暂不支持内置编辑器";
        File f = AccessRouter.fileOf(pathOrUri);
        if (!f.exists()) return "文件不存在或无法访问";
        if (!f.canRead()) return "没有读取该文件的权限";
        if (f.isDirectory()) return "这是一个文件夹，不能作为文本编辑";
        // 与 openTextEditorInternal 用同一个上限。旧值 12MB 只给「可能无法加载」的
        // 提示，真进去还是被 2MB 硬拒 —— 用户被误导一次。
        if (f.length() > MAX_EDIT_BYTES) {
            return "文件过大（" + (f.length() / 1024 / 1024) + "MB，上限 "
                    + (MAX_EDIT_BYTES / 1024 / 1024) + "MB），请用其他应用打开";
        }
        openInEditorByName(pathOrUri, name, false);
        return null;
    }

    /** 尝试解压（仅 zip 有效）。 */
    private String tryUnzip(String pathOrUri, String name, boolean isUri) {
        if (isUri) return "SAF 文件（content://）暂不支持直接解压，请先复制到本地";
        File f = AccessRouter.fileOf(pathOrUri);
        if (!f.exists()) return "文件不存在或无法访问";
        if (!f.canRead()) return "没有读取该文件的权限";
        String ext = extOf(name);
        if (!com.aliya.hyfilemgr.archive.ArchiveExtractor.canExtract(name)) {
            return "不支持的压缩格式：." + ext;
        }
        // 目标目录：与压缩包同级的同名文件夹（去掉压缩扩展名）
        File parentDir = f.getParentFile();
        if (parentDir == null || !parentDir.canWrite()) return "目录不可写，无法解压";
        String base = name;
        String lower = name.toLowerCase();
        for (String suffix : new String[]{".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst",
                ".tgz", ".tbz2", ".txz", ".tzst", ".zip", ".jar", ".apk", ".7z",
                ".rar", ".tar", ".gz", ".bz2", ".xz", ".zst"}) {
            if (lower.endsWith(suffix)) {
                base = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        if (base.isEmpty()) base = "extracted";
        final File destDir = new File(parentDir,
                uniqueName(parentDir.getAbsolutePath(), base, null));
        if (!destDir.mkdirs() && !destDir.isDirectory()) return "无法创建解压目录";

        // 后台解压，避免大包阻塞 UI
        final File srcFile = f;
        withArchiveSecret(srcFile, secret -> runTask(() -> {
            com.aliya.hyfilemgr.archive.ArchiveExtractor.Result res =
                    com.aliya.hyfilemgr.archive.ArchiveExtractor.extract(
                            srcFile, destDir, secret, null);
            handler.post(() -> {
                if (res.ok) {
                    UiKit.toast(ctx, "已解压 " + res.files + " 个文件到 " + destDir.getName()
                            + (res.skipped.isEmpty() ? ""
                               : "（跳过 " + res.skipped.size() + " 个非法路径条目）"));
                    reloadAll();
                } else {
                    UiKit.alert(ctx, "解压失败：" + res.error);
                    // 失败清理空目录
                    try {
                        String[] left = destDir.list();
                        if (left != null && left.length == 0) {
                            //noinspection ResultOfMethodCallIgnored
                            destDir.delete();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }));
        return null;   // 已转后台，无即时错误
    }

    /** 尝试安装 APK。 */
    private String tryInstallApk(String pathOrUri, String name, boolean isUri) {
        if (!"apk".equals(extOf(name))) {
            return "这不是 APK 安装包（扩展名 ." + extOf(name) + "）";
        }
        if (!isUri) {
            File f = AccessRouter.fileOf(pathOrUri);
            if (!f.exists()) return "文件不存在或无法访问";
            if (!f.canRead()) return "没有读取该文件的权限";
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                    return "未授予「安装未知应用」权限，请到 设置 → 权限管理 中开启";
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            installApkByName(pathOrUri, name, isUri);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 尝试用字体预览打开。支持 ttf / otf / ttc / woff。 */
    private String tryOpenFontPreview(String pathOrUri, String name, boolean isUri) {
        String ext = extOf(name);
        boolean isFont = "ttf".equals(ext) || "otf".equals(ext)
                || "ttc".equals(ext) || "woff".equals(ext);
        if (!isFont) {
            return "这不是字体文件（扩展名 ." + ext
                    + "），字体预览支持 ttf / otf / ttc / woff";
        }
        if (!isUri) {
            File f = AccessRouter.fileOf(pathOrUri);
            if (!f.exists()) return "文件不存在或无法访问";
            if (!f.canRead()) return "没有读取该文件的权限";
            if (f.length() == 0) return "文件为空（0 字节），可能未下载完整";
        }
        openFontPreview(pathOrUri, name, isUri);
        return null;
    }

    /** 启动字体预览页 */
    private void openFontPreview(String pathOrUri, String name, boolean isUri) {
        try {
            Intent i = new Intent(ctx,
                    com.aliya.hyfilemgr.font.FontPreviewActivity.class);
            if (isUri) {
                i.putExtra(com.aliya.hyfilemgr.font.FontPreviewActivity.EXTRA_URI, pathOrUri);
            } else {
                i.putExtra(com.aliya.hyfilemgr.font.FontPreviewActivity.EXTRA_PATH, pathOrUri);
            }
            i.putExtra(com.aliya.hyfilemgr.font.FontPreviewActivity.EXTRA_TITLE, name);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开字体预览：" + t.getMessage());
        }
    }

    /**
     * 打开失败提示弹窗。
     *
     * <p>说明哪个方式失败、失败原因，并提供「换内置方式重试」「用系统应用打开」「复制错误信息」。</p>
     */
    private void showOpenFailed(String modeName, String fileName, String reason,
                                String pathOrUri, boolean isUri) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "打开失败"));

        TextView tvWhat = new TextView(ctx);
        tvWhat.setText("方式：" + modeName + "\n文件：" + fileName);
        tvWhat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvWhat.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvWhat.setPadding(dp(4), 0, dp(4), dp(6));
        tvWhat.setMaxLines(3);
        tvWhat.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(tvWhat);

        TextView tvReason = new TextView(ctx);
        tvReason.setText(reason);
        tvReason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvReason.setTextColor(0xFFFF6B6B);
        tvReason.setPadding(dp(4), 0, dp(4), dp(10));
        tvReason.setTextIsSelectable(true);
        box.addView(tvReason);

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(8);
        box.addView(btns, btnsLp);
        btns.addView(textButton("复制原因", v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) ctx.getSystemService(
                                Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "打开失败", modeName + " / " + fileName + "\n" + reason));
                    UiKit.toast(ctx, "已复制");
                }
            } catch (Throwable ignored) {
            }
        }));
        btns.addView(textButton("系统应用打开", v -> {
            d.dismiss();
            showOpenWithDialog(pathOrUri, fileName, isUri);
        }));
        btns.addView(textButton("好的", v -> d.dismiss()));
        d.show();
    }

    /** 系统打开方式：列出系统已注册应用，点击直接由该应用打开（不弹系统选择器） */
    private void renderSystemOpeners(LinearLayout parent, final android.app.Dialog dialog,
                                     final String pathOrUri, final String name, final boolean isUri) {
        int onSurface = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface);
        int variant = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        try {
            final Uri uri = isUri ? Uri.parse(pathOrUri)
                    : FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider",
                    AccessRouter.fileOf(pathOrUri));
            final String mime = mimeOf(extOf(name));
            // ── 列出设备上**所有已安装应用**（不限系统/第三方、不限 MIME）──
            // 每个应用标注是否能处理此文件类型：
            //   ① 先统计声明了本 MIME（或 */*）的应用 → 可打开，排在前面
            //   ② 其余全部应用也列出（点击时尝试用 VIEW intent 打开）
            // 排除本应用自身。
            java.util.Set<String> canHandle = new java.util.HashSet<>();
            java.util.List<Intent> probes = new java.util.ArrayList<>();
            // ① 精确 MIME（如 application/x-font-ttf）
            if (mime != null && !mime.isEmpty()) {
                Intent exact = new Intent(Intent.ACTION_VIEW);
                exact.setDataAndType(uri, mime);
                probes.add(exact);
                // ①b 类型通配（如 application/*）
                int slash = mime.indexOf('/');
                if (slash > 0) {
                    Intent wild = new Intent(Intent.ACTION_VIEW);
                    wild.setDataAndType(uri, mime.substring(0, slash) + "/*");
                    probes.add(wild);
                }
            }
            // ② 任意 MIME
            Intent anyProbe = new Intent(Intent.ACTION_VIEW);
            anyProbe.setDataAndType(uri, "*/*");
            probes.add(anyProbe);
            // ③ 仅 scheme（MT 管理器等文件管理器只声明 file/content scheme，
            //    不带 type，用 setDataAndType 匹配不上，必须用 setData）
            Intent schemeProbe = new Intent(Intent.ACTION_VIEW);
            schemeProbe.setData(uri);
            probes.add(schemeProbe);
            // ④ 无 data 的 VIEW（部分应用只声明 action+category）
            probes.add(new Intent(Intent.ACTION_VIEW));

            for (Intent probe : probes) {
                try {
                    for (android.content.pm.ResolveInfo ri
                            : ctx.getPackageManager().queryIntentActivities(
                                    probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
                        if (ri.activityInfo != null) canHandle.add(ri.activityInfo.packageName);
                    }
                } catch (Throwable ignored) {
                }
            }

            // 取所有已安装应用，并过滤掉「无启动界面」的包
            // （系统服务 / 字体包 / 共享库 / 资源包等，表现为空名称 + 默认图标）
            java.util.List<android.content.pm.ApplicationInfo> allApps;
            try {
                allApps = ctx.getPackageManager().getInstalledApplications(
                        android.content.pm.PackageManager.GET_META_DATA);
            } catch (Throwable t) {
                allApps = new java.util.ArrayList<>();
            }
            final java.util.List<android.content.pm.ApplicationInfo> ordered =
                    new java.util.ArrayList<>();
            final java.util.Map<String, Boolean> handleMap = new java.util.HashMap<>();
            final android.content.pm.PackageManager pm = ctx.getPackageManager();
            for (android.content.pm.ApplicationInfo ai : allApps) {
                if (ctx.getPackageName().equals(ai.packageName)) continue;   // 排除自身
                // ① 必须有可启动界面（过滤系统组件包）
                Intent launch = null;
                try {
                    launch = pm.getLaunchIntentForPackage(ai.packageName);
                } catch (Throwable ignored) {
                }
                boolean can = canHandle.contains(ai.packageName);
                // ② 没有启动界面且也不声明能处理本类型 → 跳过
                if (launch == null && !can) continue;
                // ③ 名称必须非空（再兜一层，过滤无标签的内部包）
                String label = null;
                try {
                    CharSequence cs = pm.getApplicationLabel(ai);
                    if (cs != null) label = cs.toString();
                } catch (Throwable ignored) {
                }
                if (label == null || label.trim().isEmpty()) continue;
                ordered.add(ai);
                handleMap.put(ai.packageName, can);
            }
            // 能处理的排前面
            ordered.sort((a, b) -> {
                boolean ha = Boolean.TRUE.equals(handleMap.get(a.packageName));
                boolean hb = Boolean.TRUE.equals(handleMap.get(b.packageName));
                if (ha != hb) return ha ? -1 : 1;
                try {
                    String la = ctx.getPackageManager().getApplicationLabel(a).toString();
                    String lb = ctx.getPackageManager().getApplicationLabel(b).toString();
                    return la.compareToIgnoreCase(lb);
                } catch (Throwable t) {
                    return 0;
                }
            });

            // ── 诊断：结果同时写 logcat 与文件（logcat 缓冲区易被冲掉）──
            try {
                StringBuilder dbg = new StringBuilder();
                dbg.append("=== 打开方式诊断 ").append(new java.util.Date()).append(" ===\n");
                dbg.append("mime=").append(mime).append('\n');
                dbg.append("canHandle.size=").append(canHandle.size()).append('\n');
                dbg.append("canHandle=").append(canHandle).append('\n');
                dbg.append("allApps.size=").append(allApps.size()).append('\n');
                dbg.append("ordered.size=").append(ordered.size()).append('\n');
                dbg.append("mt.canHandle=").append(canHandle.contains("bin.mt.plus")).append('\n');
                dbg.append("mt.inOrdered=").append(handleMap.containsKey("bin.mt.plus")).append('\n');
                // 单独探测 MT
                try {
                    android.content.pm.ApplicationInfo mt = pm.getApplicationInfo("bin.mt.plus", 0);
                    dbg.append("mt.getApplicationInfo=OK label=")
                            .append(pm.getApplicationLabel(mt)).append('\n');
                    dbg.append("mt.launchIntent=")
                            .append(pm.getLaunchIntentForPackage("bin.mt.plus")).append('\n');
                    dbg.append("mt.flags=").append(mt.flags)
                            .append(" enabled=").append(mt.enabled).append('\n');
                } catch (Throwable t) {
                    dbg.append("mt.getApplicationInfo=FAIL ").append(t).append('\n');
                }
                // 用 queries 方式探测
                try {
                    Intent q = new Intent(Intent.ACTION_VIEW);
                    q.setDataAndType(uri, "*/*");
                    dbg.append("queryIntentActivities(*/*).size=")
                            .append(pm.queryIntentActivities(q, 0).size()).append('\n');
                } catch (Throwable t) {
                    dbg.append("query failed ").append(t).append('\n');
                }
                android.util.Log.d("HYOpenWith", dbg.toString());
                // 落盘便于 adb 读取
                java.io.File df = new java.io.File(
                        ctx.getExternalFilesDir(null), "openwith_diag.txt");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(df, false);
                fo.write(dbg.toString().getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) {
            }

            if (ordered.isEmpty()) {
                parent.addView(hintText("设备上没有找到可用的应用", variant));
                return;
            }
            // 提示：能处理的有几个
            int canCount = 0;
            for (android.content.pm.ApplicationInfo ai : ordered) {
                if (Boolean.TRUE.equals(handleMap.get(ai.packageName))) canCount++;
            }
            parent.addView(hintText("共 " + ordered.size() + " 个应用，其中 "
                    + canCount + " 个声明支持此类型（已置顶）", variant));

            // 把 ApplicationInfo 包装成与下面循环兼容的 ResolveInfo
            final java.util.List<android.content.pm.ResolveInfo> apps =
                    new java.util.ArrayList<>();
            for (android.content.pm.ApplicationInfo ai : ordered) {
                android.content.pm.ResolveInfo ri = new android.content.pm.ResolveInfo();
                ri.activityInfo = new android.content.pm.ActivityInfo();
                ri.activityInfo.packageName = ai.packageName;
                ri.activityInfo.name = "";
                ri.activityInfo.applicationInfo = ai;
                ri.activityInfo.exported = true;
                apps.add(ri);
            }
            if (apps.isEmpty()) return;
            for (final android.content.pm.ResolveInfo ri : apps) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                row.setBackground(UiKit.rounded(ctx, 14,
                        UiKit.color(ctx,
                                com.google.android.material.R.attr.colorSurfaceContainerLow),
                        UiKit.color(ctx,
                                com.google.android.material.R.attr.colorOutlineVariant)));

                ImageView ic = new ImageView(ctx);
                int isz = dp(26);
                ic.setLayoutParams(new LinearLayout.LayoutParams(isz, isz));
                try {
                    android.graphics.drawable.Drawable icon = null;
                    if (ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                        icon = ri.activityInfo.applicationInfo.loadIcon(ctx.getPackageManager());
                    }
                    if (icon == null) icon = ri.loadIcon(ctx.getPackageManager());
                    ic.setImageDrawable(icon);
                } catch (Throwable ignored) {
                }
                row.addView(ic);

                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(12), 0, 0, 0);
                TextView t1 = new TextView(ctx);
                String label = "";
                try {
                    // 优先用 applicationInfo 的 label（我们自己构造的 ActivityInfo
                    // 是空壳，ri.loadLabel() 会返回空串）
                    if (ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                        CharSequence cs = ctx.getPackageManager()
                                .getApplicationLabel(ri.activityInfo.applicationInfo);
                        if (cs != null) label = cs.toString();
                    }
                    if (label.isEmpty() && ri.activityInfo != null) {
                        CharSequence cs = ri.loadLabel(ctx.getPackageManager());
                        if (cs != null) label = cs.toString();
                    }
                } catch (Throwable ignored) {
                }
                if (label.isEmpty()) label = ri.activityInfo.packageName;
                t1.setText(label);
                t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                t1.setTextColor(onSurface);
                TextView t2 = new TextView(ctx);
                t2.setText(ri.activityInfo.packageName);
                t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                t2.setTextColor(variant);
                col.addView(t1);
                col.addView(t2);
                row.addView(col, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(6);
                parent.addView(row, lp);

                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    String pkg = ri.activityInfo.packageName;
                    String act = ri.activityInfo.name;
                    boolean canOpenThis = Boolean.TRUE.equals(handleMap.get(pkg));
                    // 1) 该应用能处理 → 直接指定 Component 打开
                    //    依次尝试：精确 MIME → */* → 仅 scheme（文件管理器类）
                    if (canOpenThis) {
                        java.util.List<Intent> tries = new java.util.ArrayList<>();
                        Intent i1 = new Intent(Intent.ACTION_VIEW);
                        if (act != null && !act.isEmpty()) {
                            i1.setComponent(new android.content.ComponentName(pkg, act));
                        } else {
                            i1.setPackage(pkg);
                        }
                        if (mime != null && !mime.isEmpty()) {
                            i1.setDataAndType(uri, mime);
                            tries.add(i1);
                        }
                        Intent i2 = new Intent(Intent.ACTION_VIEW);
                        if (act != null && !act.isEmpty()) {
                            i2.setComponent(new android.content.ComponentName(pkg, act));
                        } else {
                            i2.setPackage(pkg);
                        }
                        i2.setDataAndType(uri, "*/*");
                        tries.add(i2);
                        Intent i3 = new Intent(Intent.ACTION_VIEW);
                        if (act != null && !act.isEmpty()) {
                            i3.setComponent(new android.content.ComponentName(pkg, act));
                        } else {
                            i3.setPackage(pkg);
                        }
                        i3.setData(uri);
                        tries.add(i3);

                        for (Intent i : tries) {
                            try {
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                                ctx.startActivity(i);
                                return;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    // 2) 未声明处理 → 用包名拉起该应用主界面（用户自行在其内选择文件）
                    try {
                        Intent i = ctx.getPackageManager()
                                .getLaunchIntentForPackage(pkg);
                        if (i == null) {
                            UiKit.toast(ctx, "该应用没有可启动的界面");
                            return;
                        }
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                        UiKit.toast(ctx, "已启动应用，请在其中选择打开该文件");
                    } catch (Throwable t) {
                        UiKit.alert(ctx, "启动失败：" + t.getMessage());
                    }
                });
            }
        } catch (Throwable t) {
            // 读不到文件本身（权限不足 / 路径不可访问）→ 明确告知 + 给申请入口
            String msg = String.valueOf(t.getMessage());
            boolean permIssue = msg.contains("Permission") || msg.contains("permission")
                    || msg.contains("EACCES") || msg.contains("Failed to find configured root");
            if (permIssue) {
                parent.addView(hintText("无法读取该文件。系统打开方式需要先获得文件访问权限。", variant));
                LinearLayout act = new LinearLayout(ctx);
                act.setOrientation(LinearLayout.HORIZONTAL);
                act.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                actLp.topMargin = dp(8);
                parent.addView(act, actLp);
                act.addView(textButton("申请所有文件访问", v -> {
                    dialog.dismiss();
                    requestAllFilesAccess();
                }));
                act.addView(textButton("去设置页授权", v -> {
                    dialog.dismiss();
                    openAppSettings();
                }));
            } else {
                parent.addView(hintText("读取系统应用失败：" + msg, variant));
                LinearLayout act = new LinearLayout(ctx);
                act.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                actLp.topMargin = dp(8);
                parent.addView(act, actLp);
                act.addView(textButton("重试", v -> {
                    dialog.dismiss();
                    showOpenWithDialog(pathOrUri, name, isUri);
                }));
                act.addView(textButton("用内置方式打开", v -> {
                    dialog.dismiss();
                    openFile(AccessRouter.fileOf(pathOrUri));
                }));
            }
        }
    }

    /** 打开本应用的系统设置页（权限管理入口） */
    private void openAppSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开系统设置：" + t.getMessage());
        }
    }

    private TextView hintText(String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(color);
        tv.setPadding(dp(4), dp(10), dp(4), dp(10));
        return tv;
    }

    /** 当前音频播放弹窗的播放器（同一时刻只允许一个；关窗即释放）。
     *  已统一到 Media3 内核（见 {@code com.aliya.hyfilemgr.player.PlayerCore}）——
     *  ExoPlayer 不依赖 Surface，音频可直接出声，因此音视频共用同一条播放链路。 */
    private androidx.media3.exoplayer.ExoPlayer audioPlayer;
    private android.os.Handler audioTicker;

    /** 音频播放弹窗：原生 MediaPlayer（不依赖 Surface）+ **同目录左右切换**。
     *  UI 要点：控制键为**图标**（非中文）且**居中**排列（上一首 / 播放暂停 / 下一首）。 */
    private void showAudioPlayerDialog(final java.util.ArrayList<String> playlist,
                                       int startIndex, final boolean isUri) {
        releaseAudioPlayer();
        if (playlist == null || playlist.isEmpty()) {
            UiKit.toast(ctx, "无可用音频");
            return;
        }
        final int[] idx = {Math.max(0, Math.min(startIndex, playlist.size() - 1))};
        final boolean[] switching = {false};

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "🎵 音频播放"));

        int p4 = dp(4);

        final TextView tvCounter = new TextView(ctx);
        tvCounter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvCounter.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvCounter.setPadding(p4, 0, p4, dp(4));
        box.addView(tvCounter);

        final TextView tvName = new TextView(ctx);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvName.setMaxLines(2);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tvName.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        tvName.setPadding(p4, 0, p4, dp(4));
        box.addView(tvName);

        final TextView tvState = new TextView(ctx);
        tvState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvState.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvState.setPadding(p4, 0, p4, dp(10));
        box.addView(tvState);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView tvPos = new TextView(ctx);
        tvPos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvPos.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        final SeekBar seek = new SeekBar(ctx);
        final TextView tvDur = new TextView(ctx);
        tvDur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvDur.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        row.addView(tvPos);
        row.addView(seek, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvDur);
        box.addView(row);

        // ── 控制区：三键**整体居中**；左下角另放「选择文件」按钮 ──
        android.widget.FrameLayout ctrlWrap = new android.widget.FrameLayout(ctx);
        ctrlWrap.setPadding(0, dp(10), 0, dp(4));
        LinearLayout ctrl = new LinearLayout(ctx);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(android.view.Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams cwlp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        cwlp.gravity = android.view.Gravity.CENTER;
        ctrlWrap.addView(ctrl, cwlp);

        int iconSize = dp(34);
        int iconPad = dp(7);
        int tint = UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary);
        int tintDim = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant);

        final ImageView btnPrev = new ImageView(ctx);
        btnPrev.setImageResource(R.drawable.ic_skip_previous);
        btnPrev.setColorFilter(tintDim);
        btnPrev.setPadding(iconPad, iconPad, iconPad, iconPad);
        btnPrev.setContentDescription("上一首");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSize, iconSize);
        ilp.rightMargin = dp(18);
        ctrl.addView(btnPrev, ilp);

        final ImageView btnPlay = new ImageView(ctx);
        btnPlay.setImageResource(R.drawable.ic_pause);
        btnPlay.setColorFilter(tint);
        btnPlay.setPadding(iconPad, iconPad, iconPad, iconPad);
        btnPlay.setContentDescription("播放/暂停");
        ctrl.addView(btnPlay, new LinearLayout.LayoutParams(iconSize, iconSize));

        final ImageView btnNext = new ImageView(ctx);
        btnNext.setImageResource(R.drawable.ic_skip_next);
        btnNext.setColorFilter(tintDim);
        btnNext.setPadding(iconPad, iconPad, iconPad, iconPad);
        btnNext.setContentDescription("下一首");
        LinearLayout.LayoutParams ilp2 = new LinearLayout.LayoutParams(iconSize, iconSize);
        ilp2.leftMargin = dp(18);
        ctrl.addView(btnNext, ilp2);

        final ImageView btnList = new ImageView(ctx);
        btnList.setImageResource(R.drawable.ic_menu);
        btnList.setColorFilter(tintDim);
        btnList.setPadding(iconPad, iconPad, iconPad, iconPad);
        btnList.setContentDescription("选择文件");
        android.widget.FrameLayout.LayoutParams llp = new android.widget.FrameLayout.LayoutParams(
                iconSize, iconSize);
        llp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
        ctrlWrap.addView(btnList, llp);
        box.addView(ctrlWrap);   // 监听在 playCurrent 定义之后再绑定（Java 需先声明后使用）

        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(android.view.Gravity.END);
        TextView btnClose = new TextView(ctx);
        btnClose.setText("关闭");
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnClose.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        btnClose.setPadding(dp(12), dp(8), p4, dp(8));
        foot.addView(btnClose);
        box.addView(foot);

        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        dialog.setOnDismissListener(d -> releaseAudioPlayer());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) tvPos.setText(fmtMs(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (audioPlayer != null) {
                    try {
                        audioPlayer.seekTo(sb.getProgress());
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        // ── 播放指定索引（供首次与左右切换复用）──
        // 用单元素数组打破「lambda 自引用」限制（Java 不允许 lambda 引用正在初始化的自身变量）
        final Runnable[] playRef = new Runnable[1];
        playRef[0] = () -> {
            if (switching[0]) return;
            switching[0] = true;
            releaseAudioPlayer();
            final String path = playlist.get(idx[0]);
            String nm = path;
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) nm = path.substring(slash + 1);
            final String fileName = nm;
            tvCounter.setText((idx[0] + 1) + " / " + playlist.size());
            tvName.setText(fileName);
            tvState.setText("正在准备…");
            tvPos.setText("00:00");
            tvDur.setText("00:00");
            seek.setProgress(0);
            seek.setMax(0);
            btnPlay.setImageResource(R.drawable.ic_pause);
            btnPrev.setColorFilter(idx[0] > 0 ? tintDim : tintDim);
            btnNext.setColorFilter(idx[0] < playlist.size() - 1 ? tintDim : tintDim);

            // Media3 是异步准备，不再需要后台线程；也不会阻塞主线程
            final androidx.media3.exoplayer.ExoPlayer p =
                    null;
            audioPlayer = p;
            p.addListener(new androidx.media3.common.Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (audioPlayer != p) return;      // 期间又切了 → 忽略旧实例回调
                    if (state == androidx.media3.common.Player.STATE_READY) {
                        final int dur = (int) Math.max(0, p.getDuration());
                        runOnUi(() -> {
                            tvState.setText(isUri ? "播放中（SAF）" : "播放中");
                            tvDur.setText(fmtMs(dur));
                            seek.setMax(dur > 0 ? dur : 0);
                            startAudioTicker(seek, tvPos, btnPlay);
                            switching[0] = false;
                        });
                    } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                        runOnUi(() -> {
                            // 循环列表：播完自动下一个，最后一个回到第一个
                            idx[0] = (idx[0] < playlist.size() - 1) ? idx[0] + 1 : 0;
                            playRef[0].run();
                        });
                    }
                }

                @Override public void onPlayerError(androidx.media3.common.PlaybackException e) {
                    if (audioPlayer != p) return;
                    final String code = e.getErrorCodeName();
                    runOnUi(() -> {
                        tvState.setText("无法播放：" + (code == null ? "未知错误" : code));
                        switching[0] = false;
                    });
                }
            });
            try {
                androidx.media3.common.MediaItem mi =
                        isUri ? androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(path))
                              : androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(new java.io.File(path)));
                if (mi == null) {
                    tvState.setText("无效的播放地址");
                    switching[0] = false;
                    return;
                }
                p.setMediaItem(mi);
                p.setPlayWhenReady(true);
                p.prepare();
            } catch (Throwable t) {
                tvState.setText("无法播放：" + t.getMessage());
                switching[0] = false;
            }
        };
        final Runnable playCurrent = playRef[0];

        // 列表按钮需在 playCurrent 定义后绑定
        btnList.setOnClickListener(v -> showAudioPlaylistPicker(playlist, idx, playCurrent));
        btnPlay.setOnClickListener(v -> {
            if (audioPlayer == null) {
                playCurrent.run();
                return;
            }
            try {
                if (audioPlayer.isPlaying()) {
                    audioPlayer.pause();
                    btnPlay.setImageResource(R.drawable.ic_play);
                } else {
                    audioPlayer.play();
                    btnPlay.setImageResource(R.drawable.ic_pause);
                }
            } catch (Throwable ignored) {
            }
        });
        btnPrev.setOnClickListener(v -> {
            if (idx[0] <= 0) {
                UiKit.toast(ctx, "已经是第一个");
                return;
            }
            idx[0]--;
            playCurrent.run();
        });
        btnNext.setOnClickListener(v -> {
            if (idx[0] >= playlist.size() - 1) {
                UiKit.toast(ctx, "已经是最后一个");
                return;
            }
            idx[0]++;
            playCurrent.run();
        });
        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        playCurrent.run();
    }

    private void runOnUi(Runnable r) {
        if (hostActivity != null) hostActivity.runOnUiThread(r);
        else handler.post(r);
    }

    /** 音频弹窗左下角「选择文件」：列出当前播放列表（当前目录的音频），点选即切换 */
    private void showAudioPlaylistPicker(final java.util.ArrayList<String> list,
                                         final int[] idx, final Runnable playCurrent) {
        if (list == null || list.isEmpty()) return;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "播放列表（" + list.size() + "）"));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sc = new android.widget.ScrollView(ctx);
        sc.addView(col);
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)));

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        int primary = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary);
        int normal = UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface);
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            String p = list.get(i);
            int slash = p.lastIndexOf('/');
            String nm = (slash >= 0 && slash < p.length() - 1) ? p.substring(slash + 1) : p;
            TextView tv = new TextView(ctx);
            tv.setText((i + 1) + ".  " + nm);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tv.setPadding(dp(10), dp(10), dp(10), dp(10));
            tv.setTextColor(i == idx[0] ? primary : normal);
            if (i == idx[0]) tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setOnClickListener(v -> {
                d.dismiss();
                idx[0] = index;
                playCurrent.run();
            });
            col.addView(tv);
        }
        d.show();
    }

    /** 每 500ms 刷新进度与按钮图标（弹窗关闭后自动停止） */
    private void startAudioTicker(final SeekBar seek, final TextView tvPos,
                                  final ImageView btnPlay) {
        if (audioTicker == null) {
            audioTicker = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        audioTicker.removeCallbacksAndMessages(null);
        audioTicker.post(new Runnable() {
            @Override public void run() {
                if (audioPlayer == null) return;
                try {
                    int pos = (int) Math.max(0, audioPlayer.getCurrentPosition());   // ExoPlayer 返回 long
                    seek.setProgress(pos);
                    tvPos.setText(fmtMs(pos));
                    // 图标随播放状态切换（不使用中文文字）
                    btnPlay.setImageResource(audioPlayer.isPlaying()
                            ? R.drawable.ic_pause : R.drawable.ic_play);
                } catch (Throwable ignored) {
                }
                audioTicker.postDelayed(this, 500);
            }
        });
    }


    private void releaseAudioPlayer() {
        if (audioTicker != null) audioTicker.removeCallbacksAndMessages(null);
        if (audioPlayer != null) {
            try {
                audioPlayer.stop();
            } catch (Throwable ignored) {
            }
            try {
                audioPlayer.release();
            } catch (Throwable ignored) {
            }
            audioPlayer = null;
        }
    }

    private String fmtMs(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", s / 60, s % 60);
    }

    /** 用内置图片查看器打开（同目录左右切换：从当前窗格条目收集图片列表） */
    private void openImageViewer(String pathOrUri, String name, boolean isUri) {
        try {
            // 内置图片查看器（双指缩放 / 拖动 / 双击还原 / 左右滑动切图 /
            // 分享·删除·设为背景）
            Intent i = new Intent(ctx, com.aliya.hyfilemgr.ImageViewerActivity.class);
            if (isUri) {
                i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_URI, pathOrUri);
            } else {
                i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_PATH, pathOrUri);
                // 同目录图集：当前窗格里同扩展名的其它图片。
                // 与播放器 collectPlayable 同思路；窗格列表不可用时查看器自动退化为单图。
                java.util.ArrayList<String> imgs = collectImages(pathOrUri);
                if (imgs.size() > 1) {
                    i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_LIST,
                            imgs.toArray(new String[0]));
                    i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_INDEX,
                            Math.max(0, imgs.indexOf(pathOrUri)));
                }
            }
            i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_TITLE, name);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // 用 startActivityForResult：底部功能栏的「删除/设为背景」在查看器内
            // 只做确认，真正的回收站删除与壁纸落盘都回传这里走既有链路
            // （复用 REQ_SAF_MOUNT 同款 hostActivity 转发模式）。
            // hostActivity 为 null（理论上不可能，模块就挂在 MainActivity 里）时退化为普通启动。
            if (hostActivity != null) {
                viewerPendingPath = pathOrUri;
                hostActivity.startActivityForResult(i, REQ_VIEWER);
            } else {
                ctx.startActivity(i);
            }
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开图片查看器：" + t.getMessage());
        }
    }

    /** 收集当前窗格里的同目录图片（自然序外的文件系统序），至少包含当前图 */
    private java.util.ArrayList<String> collectImages(String curPath) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        Pane p = active;
        if (p != null && p.entries != null) {
            for (FileEntry e : p.entries) {
                if (e == null || e.isDir() || e.name == null) continue;
                if (IMAGE_EXTS.contains(extOf(e.name))
                        && e.path != null && !e.path.isEmpty()) out.add(e.path);
            }
        }
        if (!out.contains(curPath)) out.add(0, curPath);
        return out;
    }

    /** 查看器回传处理：onActivityResult 由 MainActivity 转发过来 */
    public void onViewerResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != REQ_VIEWER || resultCode != android.app.Activity.RESULT_OK
                || data == null || viewerPendingPath == null) return;
        String path = viewerPendingPath;
        viewerPendingPath = null;
        if (data.hasExtra(com.aliya.hyfilemgr.ImageViewerActivity.RESULT_ACTION_DELETE)) {
            // 复用 confirmDelete：回收站机制、失败明细、reloadAll 全部现成。
            // FileEntry 是 final 字段模型，用完整构造器；
            // path 不在 /storage 下时 moveToTrash 会自动只保留文件名。
            java.io.File f = new java.io.File(path);
            FileEntry e = new com.aliya.hyfilemgr.access.FileEntry(
                    path, f.getName(), com.aliya.hyfilemgr.access.FileEntry.Type.FILE,
                    f.length(), f.lastModified(), false, null);
            confirmDelete(java.util.Collections.singletonList(e));
        } else if (data.hasExtra(com.aliya.hyfilemgr.ImageViewerActivity.RESULT_ACTION_WALLPAPER)) {
            // 复用 setBackgroundFromUri：壁纸落盘/渲染/孤儿清理全部现成。
            // pickForFmTheme 决定落到全局还是文件列表页（参数由查看器带上）。
            pickForFmTheme = data.getBooleanExtra(
                    com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_FOR_FM, false);
            try {
                setBackgroundFromUri(android.net.Uri.fromFile(new java.io.File(path)));
            } finally {
                pickForFmTheme = false;   // 用完复位，不影响设置页后续选图
            }
        }
    }

    /** 查看器回传时记住的源路径（onActivityResult 只带动作不带路径以外的上下文） */
    private String viewerPendingPath;

    /** 用内置编辑器打开指定文件（先进入其所在目录再打开） */
    private void openInEditorByName(String pathOrUri, String name, boolean isUri) {
        try {
            File f = isUri ? null : AccessRouter.fileOf(pathOrUri);
            if (f == null || !f.exists()) {
                UiKit.toast(ctx, "SAF 文件暂不支持内置编辑器，请改用系统打开方式");
                return;
            }
            openTextEditor(f);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开编辑器：" + t.getMessage());
        }
    }

    /** 解压指定压缩包（复用既有解压流程） */
    private void unzipByName(String pathOrUri, String name, boolean isUri) {
        try {
            File f = isUri ? null : AccessRouter.fileOf(pathOrUri);
            if (f == null || !f.exists()) {
                UiKit.toast(ctx, "SAF 文件暂不支持直接解压，请先复制到本地");
                return;
            }
            extractZip(FileEntry.file(f.getAbsolutePath(), f.length(), f.lastModified()));
        } catch (Throwable t) {
            UiKit.alert(ctx, "解压失败：" + t.getMessage());
        }
    }

    /** 安装 APK（复用系统安装器） */
    private void installApkByName(String pathOrUri, String name, boolean isUri) {
        // 安装前先让用户看清要装的是什么：包名、版本、目标 SDK、签名摘要。
        // 只对真实文件（非 content://）解析，SAF 来源直接走系统安装器。
        if (!isUri) {
            try {
                showApkInfoThenInstall(new File(pathOrUri), name);
                return;
            } catch (Throwable ignored) {
                // 解析失败则退回直接安装，不阻断流程
            }
        }
        doInstall(pathOrUri, isUri);
    }

    private void doInstall(String pathOrUri, boolean isUri) {
        // 「安装未知应用」是受限权限：没开时 startActivity 会直接抛
        // ActivityNotFoundException / 或系统弹一个措辞含糊的拒绝，
        // 用户只看到「无法安装：null」。先查再引导，比事后报错有用。
        if (!canRequestInstall()) {
            askInstallPermission();
            return;
        }
        try {
            Uri uri = isUri ? Uri.parse(pathOrUri)
                    : FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider",
                    AccessRouter.fileOf(pathOrUri));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法安装：" + msgOf(t));
        }
    }

    /**
     * 是否已允许「安装未知应用」。
     *
     * <p>API 26 起改为按来源应用授权（{@code canRequestPackageInstalls}），
     * 之前是系统级「未知来源」开关，无法查询，一律返回 true 交给系统处理。</p>
     */
    private boolean canRequestInstall() {
        if (android.os.Build.VERSION.SDK_INT < 26) return true;
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return true;   // 查询失败不拦路，交给系统决定
        }
    }

    /** 跳系统设置让用户开启「安装未知应用」，回来后重试。 */
    /** 待下载的更新：授权回来后自动继续。null = 没有待办 */
    private UpdateInfo pendingInstallInfo;
    /** 授权弹窗引用：跳到系统设置前先关掉，避免回来后它还在最上层 */
    private android.app.Dialog pkgInstallDialog;

    /**
     * 申请「安装未知应用」权限。
     *
     * <p><b>旧实现两个缺陷</b>：
     * ① 按钮里用 {@code ((View) v.getParent()).getTag()} 取 Dialog，
     *    但那段代码从来没 setTag 过 —— 取到 null，
     *    {@code dismissWithAnim(null)} 什么也不做，「取消」点了没反应。
     * ② 跳去系统设置授权后回来，没有任何续接逻辑，
     *    用户必须自己去菜单再点一次「检查更新」。
     *
     * <p>现在：弹窗持有自身引用；「去开启」先 dismiss 再去设置页；
     * 记录 pendingInstallInfo，在 onResume 检测到权限已给时自动继续下载。</p>
     */
    private void askInstallPermission() {
        if (pkgInstallDialog != null && pkgInstallDialog.isShowing()) return;
        final android.app.Dialog d = UiKit.glassDialog(ctx, buildAskInstallBody());
        pkgInstallDialog = d;
        d.setOnDismissListener(x -> {
            if (pkgInstallDialog == d) pkgInstallDialog = null;
        });
        d.show();
    }

    private View buildAskInstallBody() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "需要安装权限"));

        TextView tv = new TextView(ctx);
        tv.setText("安装更新需要你允许 HY文件管理「安装未知应用」。\n\n"
                + "点「去开启」会跳到系统设置，打开开关后返回，"
                + "会**自动继续下载**，不用再点一次「检查更新」。\n\n"
                + "这是 Android 8.0 起的强制限制，任何应用安装安装包都要这一步。");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        tv.setLineSpacing(dp(3), 1f);
        box.addView(tv);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(16), 0, 0);

        btns.addView(textButton("取消", v -> {
            // 直接关掉这个弹窗（不走 tag，tag 从来没设过）
            if (pkgInstallDialog != null) {
                UiKit.dismissWithAnim(pkgInstallDialog);
            }
            // 取消就清掉待办，免得 onResume 又自动开始下载
            pendingInstallInfo = null;
        }));

        btns.addView(textButton("去开启", v -> {
            // 先关自己的弹窗，再去系统设置 —— 否则回来时它还盖在最上层
            if (pkgInstallDialog != null) {
                UiKit.dismissWithAnim(pkgInstallDialog);
            }
            openInstallPermissionSetting();
        }));
        box.addView(btns);
        return box;
    }

    /** 打开本应用的「安装未知应用」设置页。 */
    private void openInstallPermissionSetting() {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        try {
            Intent i = new Intent(android.provider.Settings
                    .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            // 部分 ROM 没有这个 action，退到应用详情页
            try {
                Intent i = new Intent(android.provider.Settings
                        .ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable t2) {
                UiKit.alert(ctx, "无法打开设置页，请手动到「设置 → 应用 → 特殊权限 → "
                        + "安装未知应用」里允许本应用");
            }
        }
    }

    /** 安装包信息弹窗：解析失败时直接安装，不拦路 */
    private void showApkInfoThenInstall(final File apk, String displayName) {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(
                apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        if (pi == null) {
            doInstall(apk.getAbsolutePath(), false);
            return;
        }
        String appName = apk.getName();
        int targetSdk = pi.applicationInfo != null ? pi.applicationInfo.targetSdkVersion : 0;
        String version = pi.versionName != null ? pi.versionName : String.valueOf(pi.versionCode);
        boolean installed = false;
        int installedVc = 0;
        try {
            android.content.pm.PackageInfo cur = pm.getPackageInfo(pi.packageName, 0);
            installed = true;
            installedVc = cur.versionCode;
        } catch (Throwable ignored) {
        }

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "📦 " + appName));

        StringBuilder sb = new StringBuilder();
        sb.append("包名　　").append(pi.packageName).append(System.lineSeparator());
        sb.append("版本　　").append(version).append("（").append(pi.versionCode).append("）")
                .append(System.lineSeparator());
        sb.append("目标 SDK　").append(targetSdk == 0 ? "未知" : String.valueOf(targetSdk))
                .append(System.lineSeparator());
        sb.append("大小　　").append(fmtStorageSize(apk.length())).append(System.lineSeparator());
        if (installed) {
            sb.append("已安装　").append(installedVc)
                    .append(pi.versionCode >= installedVc ? "（覆盖升级）" : "（降级安装）");
        } else {
            sb.append("已安装　否（全新安装）");
        }
        TextView tv = new TextView(ctx);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.4f);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        int pad = dp(4);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText(sb.toString());
        box.addView(tv);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("取消", v -> dialog.dismiss()));
        btns.addView(textButton("安装", v -> {
            dialog.dismiss();
            doInstall(apk.getAbsolutePath(), false);
        }));
        dialog.show();
    }

    /** 打开 SAF（content://）文件：uri + provider mime 直发系统（无需 FileProvider 中转） */
    private void openSafFile(FileEntry e) {
        try {
            Uri uri = Uri.parse(e.path);
            String mime = null;
            try {
                android.database.Cursor c = ctx.getContentResolver().query(uri,
                        new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) mime = c.getString(0);
                    } finally {
                        c.close();
                    }
                }
            } catch (Throwable ignored) {
            }
            if (mime == null || mime.isEmpty()) {
                mime = mimeOf(extOf(e.name));
            }
            // 格式明确且内置功能支持 → 直接调用（SAF 文本编辑暂不支持，故文本走弹窗）
            String safExt = extOf(e.name);
            if (IMAGE_EXTS.contains(safExt)) {
                openImageViewer(e.path, e.name, true);
                return;
            }
            if (AUDIO_ONLY_EXTS.contains(safExt)) {
                java.util.ArrayList<String> safl = collectPlayable(e.path, true);
                int safIdx = safl.indexOf(e.path);
                if (safIdx < 0) {
                    safl.add(0, e.path);
                    safIdx = 0;
                }
                showAudioPlayerDialog(safl, safIdx, true);   // 音频：弹窗 + 左右切换
                return;
            }
            if (MEDIA_EXTS.contains(safExt)) {
                openWithBuiltInPlayer(e.path, e.name, true);   // 视频：全屏页
                return;
            }
            showOpenWithDialog(e.path, e.name, true);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开：" + t.getMessage());
        }
    }

    private void showMoreMenu(View anchor) {
        // 右上角三点菜单（⭐10 精简：排序/搜索已上顶栏直达）：刷新/隐藏文件/书签/任务队列，
        // PopupWindow 同款新建菜单样式（玻璃圆角面板 + 图标行 + 锚定按钮）
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int surface = UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int stroke = UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant);
        android.graphics.drawable.GradientDrawable gd = UiKit.rounded(ctx, 14,
                (surface & 0x00FFFFFF) | 0xF2000000, stroke);
        panel.setBackground(gd);
        int p4 = dp(4);
        panel.setPadding(p4, p4, p4, p4);

        final PopupWindow popup = trackPopup(new PopupWindow(panel,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true));
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(dp(8));

        // ── 功能入口（刷新/隐藏文件切换/书签列表/收藏当前目录/任务队列） ──
        addMenuRow(panel, "刷新", R.drawable.ic_refresh, v -> {
            popup.dismiss();
            reloadAll();
        });
        addMenuRow(panel, showHidden ? "隐藏文件：显示中" : "隐藏文件：已隐藏",
                R.drawable.ic_visibility, v -> {
                    popup.dismiss();
                    showHidden = !showHidden;
                    savePrefBool(PREF_SHOW_HIDDEN, showHidden);
                    persistState();
                    try {
                        reloadAll();
                    } catch (Throwable ignored) {
                    }
                    UiKit.toast(ctx, showHidden ? "已显示隐藏文件" : "已隐藏 . 开头文件");
                });
        addMenuRow(panel, "书签列表", R.drawable.ic_bookmarks, v -> {
            popup.dismiss();
            showBookmarks();
        });
        addMenuRow(panel, "收藏当前目录", R.drawable.ic_star_add, v -> {
            popup.dismiss();
            addBookmark();
        });
        addMenuRow(panel, "最近文件", R.drawable.ic_history, v -> {
            popup.dismiss();
            showRecentFiles();
        });
        addMenuRow(panel, "存储分析", R.drawable.ic_pie_chart, v -> {
            popup.dismiss();
            showStorageAnalysis();
        });
        addMenuRow(panel, queuedTasks.get() > 0 ? "任务队列（" + queuedTasks.get() + "）" : "任务队列",
                R.drawable.ic_layers, v -> {
                    popup.dismiss();
                    showTaskQueue();
                });
        // ⭐10 方向自适应：顶栏 ⋮ 向下弹；底部导航「更多」Tab 触发时向上弹（避免出屏）
        if (anchorNearBottom(anchor)) {
            popup.showAtLocation(anchor, Gravity.BOTTOM | Gravity.END, dp(12), dp(96));
        } else {
            popup.showAsDropDown(anchor, -dp(104), dp(2));
        }
    }

    /** ⭐10 判断锚点是否位于屏幕下半区（底部导航/FAB 触发时弹窗向上，避免出屏） */
    private boolean anchorNearBottom(View anchor) {
        if (anchor == null) return false;
        int[] loc = new int[2];
        try {
            anchor.getLocationOnScreen(loc);
        } catch (Throwable t) {
            return false;
        }
        int h = ctx.getResources().getDisplayMetrics().heightPixels;
        return loc[1] > h / 2;
    }

    // ════════════════════════════════════════════════════════════════
    // 全量功能落地（2026-08-16 用户规划）：搜索/压缩解压/隐藏文件/分类/书签/
    // 回收站/任务队列/FTP —— 参考 MaterialFiles、Fossify、Amaze、Ghost Commander
    // ════════════════════════════════════════════════════════════════

    // ── 轻量任务队列：耗时操作排队串行（避免并发写冲突），⋮菜单可查 ──

    private void runTask(final Runnable task) {
        if (task == null) return;
        // 先提交再计数：旧代码先 ++ 再 execute，若 execute 抛
        // RejectedExecutionException（策略变更或 shutdown 后），
        // 计数永远停在 +1，全局「队列非空」判断失效。
        try {
            TASK_QUEUE.execute(() -> {
                // 任务真正开始执行时才 acquire：若排队很久才轮到，
                // 提前拉前台服务会让通知栏长时间挂一个「正在处理」。
                // 首次主动申请通知权限，否则前台服务通知不显示
                handler.post(FileManagerModule.this::ensureNotificationPermission);
                com.aliya.hyfilemgr.service.TaskKeepAliveService.acquire(ctx, "正在处理文件");
                try {
                    task.run();
                } catch (Throwable t) {
                    UiKit.alert(ctx, "任务失败：" + t.getMessage());
                } finally {
                    queuedTasks.decrementAndGet();
                    com.aliya.hyfilemgr.service.TaskKeepAliveService.release(ctx);
                }
            });
            queuedTasks.incrementAndGet();
        } catch (Throwable submitFail) {
            UiKit.alert(ctx, "无法提交任务：" + submitFail.getMessage());
        }
    }


    /** 是否已经问过通知权限（避免每次长任务都弹） */
    private boolean notifAsked = false;

    /**
     * 首次执行长任务时补问通知权限。
     *
     * <p>此前只在「设置 → 权限管理」里申请，用户不主动进去就永远是
     * granted=false。前台服务虽然照样能跑（FOREGROUND_SERVICE 不依赖
     * 通知权限），但通知栏不显示任何提示，用户切后台后完全看不到
     * 「正在压缩」的状态，体验断档。</p>
     *
     * <p>Android 13+ 才有该权限；13 以下直接跳过。
     * 只问一次，用户拒绝后不再骚扰（设置页里还能手动开）。</p>
     */
    private void ensureNotificationPermission() {
        if (notifAsked) return;
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (!(hostActivity instanceof android.app.Activity)) return;
        notifAsked = true;
        try {
            if (ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            ((android.app.Activity) hostActivity).requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, 0x2003);
        } catch (Throwable ignored) {
        }
    }

    /** 任务队列状态对话框（⋮菜单入口） */
    private void showTaskQueue() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "任务队列"));
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface));
        tv.setLineSpacing(0, 1.3f);
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        tv.setText(queuedTasks.get() > 0
                ? "当前排队/执行中任务：" + queuedTasks.get() + "\n粘贴/压缩/解压/搜索/分类扫描/回收站操作按顺序串行执行，完成后 toast 提示。"
                : "无进行中任务。\n耗时操作（粘贴/压缩/解压/搜索/分类扫描/回收站）统一排队串行执行，避免并发写同一目录造成数据损坏。");
        box.addView(tv);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);
        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("关闭", v -> dialog.dismiss()));
        dialog.show();
    }

    // ── 隐藏文件过滤 ──

    /** 隐藏文件过滤：.开头条目移除（".."parent 条目不参与）；showHidden=true 时不过滤 */
    private void filterHidden(Pane p) {
        List<FileEntry> kept = new ArrayList<>(p.entries.size());
        for (FileEntry fe : p.entries) {
            if (fe.parent) {
                kept.add(fe);
                continue;
            }
            if (fe.name.startsWith(".")) continue;
            kept.add(fe);
        }
        p.entries.clear();
        p.entries.addAll(kept);
    }

    // ── 搜索（⋮菜单入口；参考 MaterialFiles WalkFileTreeSearchable 异步递归范式） ──

    private void showSearchDialog() {
        if (active == null || active.path == null) return;
        if (active.path.startsWith(PREFIX_TRASH)) {
            UiKit.toast(ctx, "分类/回收站视图不支持搜索");
            return;
        }
        if (active.path.startsWith("content://")) {
            UiKit.toast(ctx, "SAF 授权目录暂不支持搜索（后续版本支持）");
            return;
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "搜索（当前目录树）"));
        final EditText input = new EditText(ctx);
        input.setHint("输入文件名关键词");
        input.setSingleLine(true);
        box.addView(input);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);
        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("取消", v -> dialog.dismiss()));
        btns.addView(textButton("搜索", v -> {
            final String kw = input.getText().toString().trim();
            if (kw.isEmpty()) {
                UiKit.toast(ctx, "请输入关键词");
                return;
            }
            dialog.dismiss();
            final String root = active.path;
            UiKit.toast(ctx, "正在搜索…");
            runTask(() -> {
                final List<FileEntry> results = new ArrayList<>();
                searchRecursive(new File(root), kw, results);
                handler.post(() -> showSearchResults(kw, results));
            });
        }));
        dialog.show();
    }

    /** 递归搜索：跳过隐藏目录（含回收站）、Android 无权限大目录；结果上限 500 */
    private void searchRecursive(File dir, String kw, List<FileEntry> out) {
        if (out.size() >= 500) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        String lowerKw = kw.toLowerCase(Locale.getDefault());
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (n.equals("Android")) continue;
                searchRecursive(f, kw, out);
            } else if (n.toLowerCase(Locale.getDefault()).contains(lowerKw)) {
                out.add(FileEntry.file(f.getAbsolutePath(), f.length(), f.lastModified()));
                if (out.size() >= 500) return;
            }
        }
    }

    /** 搜索结果对话框：点击目录跳转进入、点击文件系统打开 */
    private void showSearchResults(final String kw, final List<FileEntry> results) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx,
                "“" + kw + "” 结果：" + results.size() + " 项"));
        final ListView lv = new ListView(ctx);
        final java.util.List<String> labels = new ArrayList<>();
        for (FileEntry e : results) {
            labels.add((e.isDir() ? "📁 " : "📄 ") + e.name + "\n" + e.path);
        }
        lv.setAdapter(textListAdapter(labels, null));
        box.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);
        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("关闭", v -> dialog.dismiss()));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            FileEntry e = results.get(pos);
            dialog.dismiss();
            if (e.isDir()) {
                active.navPrev = active.path;
                active.path = e.path;
                reload(active);
            } else {
                openFile(AccessRouter.fileOf(e));
            }
        });
        dialog.show();
    }

    // ── 压缩 / 解压（长按菜单入口；参考 Fossify ItemsAdapter；java.util.zip 零依赖） ──

    /**
     * 是否为「安装包」类文件。
     *
     * <p><b>为什么需要单独判断</b>：{@code .apk} 同时出现在「压缩包」扩展名
     * 列表里（它本质是 zip），所以 {@link #isZip} 对它是 true。
     * 旧逻辑先判 isZip 就进入内部浏览，用户点 APK 看到的是 zip 目录树，
     * 而不是安装界面。安装包必须在压缩包分支之前拦截。</p>
     *
     * <p>覆盖安装包家族：apk / aab / xapk / apks / apkm / zipx。
     * 其中只有 apk / xapk 是系统安装器认得的，其余交给 openWith 让用户选。</p>
     */
    private static boolean isInstallerExt(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".apk") || n.endsWith(".aab") || n.endsWith(".xapk")
                || n.endsWith(".apks") || n.endsWith(".apkm") || n.endsWith(".zipx");
    }

    /**
     * 该压缩包能否被**内部浏览**（java.util.zip 只认得 zip/jar/apk）。
     * isZip 已扩为 canExtract（16 种格式），两者语义必须分开。
     */
    private static boolean isZipReadable(FileEntry e) {
        if (e == null || e.name == null) return false;
        String lower = e.name.toLowerCase(Locale.getDefault());
        return lower.endsWith(".zip") || lower.endsWith(".jar")
                || lower.endsWith(".apk");
    }

    /**
     * 该压缩包能否**内部浏览**（列出条目）。
     *
     * <p>与 isZipReadable（只认 zip/jar/apk，用于 java.util.zip 读取）
     * 的区别：这里判断的是 listArchiveItems 是否支持，
     * 已覆盖 zip / 7z / rar / tar 家族。
     * 单流格式（gz/bz2/xz/zst）是「一个文件压成一个流」，没有条目结构，
     * 只能直接解压，不能浏览。</p>
     */
    private static boolean isArchiveBrowsable(FileEntry e) {
        if (e == null || e.name == null) return false;
        String n = e.name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk")
                || n.endsWith(".xapk") || n.endsWith(".7z") || n.endsWith(".rar")) {
            return true;
        }
        return n.endsWith(".tar") || n.contains(".tar.") || n.endsWith(".tgz");
    }

    private static boolean isZip(FileEntry e) {
        return e.name != null
                && com.aliya.hyfilemgr.archive.ArchiveExtractor.canExtract(e.name);
    }

    /** 单条目压缩为 zip：同目录 <原名>.zip（重名自动 (1)）；目录递归打包 */
    /**
     * 压缩入口：弹出「创建压缩文件」对话框。
     *
     * <p>可配置：文件名（默认 x.zip，可改）、格式（zip/7z/tar/…）、
     * 压缩等级（存储/快速/标准/极限）、密码（仅 zip/7z 支持）、
     * 是否单独压缩、压缩后删除源文件、输出到另一窗格。</p>
     */
    private void compressToZip(final FileEntry e) {
        if (e == null || e.path == null) return;
        final File src = AccessRouter.fileOf(e);
        if (!src.exists()) {
            UiKit.alert(ctx, "文件不存在");
            return;
        }
        showCompressDialog(e, src);
    }

    /** 压缩格式定义：扩展名 / 显示名 / 是否支持密码 / 是否支持等级 */
    private static final String[][] COMPRESS_FORMATS = {
            // 扩展名 / 显示名 / 支持密码 / 支持等级 / 可否创建
            {"zip", "ZIP", "1", "1", "1"},
            // 7z：密码支持（commons-compress 1.26 的 SevenZOutputFile 可写 AES256）
            //     等级不支持——LZMA2Options 有 preset，但 SevenZOutputFile 不暴露入口
            {"7z",  "7Z",  "1", "0", "1"},
            {"tar", "TAR", "0", "0", "1"},
            {"tar.gz",  "TAR.GZ",  "0", "0", "1"},
            {"tar.bz2", "TAR.BZ2", "0", "0", "1"},
            {"tar.xz",  "TAR.XZ",  "0", "0", "1"},
            {"tar.zst", "TAR.ZST", "0", "0", "1"},
            {"gz",  "GZ",  "0", "0", "1"},
            {"bz2", "BZ2", "0", "0", "1"},
            {"xz",  "XZ",  "0", "0", "1"},
            {"zst", "ZST", "0", "0", "1"},
            // RAR 不在此列：UnRAR 许可证禁止用其代码开发 RAR 压缩器，
            // 官方 rar 为共享软件且无 aarch64 Android 分发。只支持解压。
    };

    /** 创建压缩文件对话框 */
    private void showCompressDialog(final FileEntry e, final File src) {
        final File parentDir = src.getParentFile();
        final String baseName = src.isDirectory()
                ? src.getName()
                : (src.getName().contains(".")
                    ? src.getName().substring(0, src.getName().lastIndexOf('.'))
                    : src.getName());
        // 默认命名：单文件 = 原名（去扩展名）；多文件 = 目录名
        final String defaultName = baseName.isEmpty() ? "archive" : baseName;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "创建压缩文件"));

        // ── 文件名 ──
        box.addView(labelText("文件名"));
        final EditText etName = new EditText(ctx);
        etName.setText(defaultName);
        etName.setSingleLine(true);
        etName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(etName);

        // ── 格式（点击展开列表）──
        box.addView(labelText("格式"));
        final int[] fmtIdx = {0};
        final TextView tvFormat = new TextView(ctx);
        tvFormat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvFormat.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvFormat.setBackgroundResource(R.drawable.bg_ripple_round);
        tvFormat.setClickable(true);
        tvFormat.setText(COMPRESS_FORMATS[0][1] + "  ▾");
        tvFormat.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        box.addView(tvFormat);
        // 格式下拉：PopupWindow 独立窗口，不参与弹窗布局，
        // 展开/收起弹窗高度完全不变，也不受父容器裁剪影响。
        final PopupWindow fmtPopup = trackPopup(new PopupWindow(ctx));
        final ScrollView fmtScroll = new ScrollView(ctx);
        fmtScroll.setVerticalScrollBarEnabled(true);
        fmtScroll.setBackground(UiKit.rounded(ctx, dp(10),
                UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh),
                UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant)));
        final LinearLayout fmtList = new LinearLayout(ctx);
        fmtList.setOrientation(LinearLayout.VERTICAL);
        fmtList.setPadding(dp(4), dp(4), dp(4), dp(4));
        fmtScroll.addView(fmtList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fmtPopup.setContentView(fmtScroll);
        fmtPopup.setFocusable(true);
        fmtPopup.setOutsideTouchable(true);
        fmtPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));

        // ── 压缩等级 ──
        final TextView tvLevel = labelText("压缩等级");
        box.addView(tvLevel);
        final int[] level = {net.lingala.zip4j.model.enums.CompressionLevel.NORMAL.getLevel()};
        final android.widget.SeekBar sbLevel = new android.widget.SeekBar(ctx);
        sbLevel.setMax(3);
        sbLevel.setProgress(2);   // 默认「标准」
        box.addView(sbLevel);
        final TextView tvLevelHint = new TextView(ctx);
        tvLevelHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvLevelHint.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvLevelHint.setAlpha(0.75f);
        box.addView(tvLevelHint);

        final String[] LEVEL_NAMES = {"存储（最快）", "快速", "标准", "极限（最慢）"};
        final int[] LEVEL_VALUES = {0, 1, 5, 9};
        sbLevel.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                level[0] = LEVEL_VALUES[Math.max(0, Math.min(3, progress))];
                tvLevelHint.setText("当前：" + LEVEL_NAMES[Math.max(0, Math.min(3, progress))]);
                tvLevel.setText("压缩等级 · " + LEVEL_NAMES[Math.max(0, Math.min(3, progress))]);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        tvLevelHint.setText("当前：" + LEVEL_NAMES[2]);
        tvLevel.setText("压缩等级 · " + LEVEL_NAMES[2]);

        // ── 密码（仅支持的格式显示）──
        final TextView tvPwdLabel = labelText("设置密码");
        final EditText etPwd = new EditText(ctx);
        etPwd.setHint("留空则不加密");
        etPwd.setSingleLine(true);
        etPwd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(tvPwdLabel);
        box.addView(etPwd);

        // ── 三个复选项 ──
        final android.widget.CheckBox cbSingle = optionCheck("单独压缩", false);
        final android.widget.CheckBox cbDelSrc = optionCheck("压缩后删除源文件", false);
        final android.widget.CheckBox cbOtherPane = optionCheck("压缩到另一个窗口路径", false);
        box.addView(cbSingle);
        box.addView(cbDelSrc);
        box.addView(cbOtherPane);

        // ── 格式切换：更新可用性 ──
        final Runnable applyFormat = () -> {
            String[] fmt = COMPRESS_FORMATS[fmtIdx[0]];
            boolean supportPwd = "1".equals(fmt[2]);
            boolean supportLevel = "1".equals(fmt[3]);
            tvFormat.setText(fmt[1] + "  ▾");
            tvPwdLabel.setVisibility(supportPwd ? View.VISIBLE : View.GONE);
            etPwd.setVisibility(supportPwd ? View.VISIBLE : View.GONE);
            tvLevel.setVisibility(supportLevel ? View.VISIBLE : View.GONE);
            sbLevel.setVisibility(supportLevel ? View.VISIBLE : View.GONE);
            tvLevelHint.setVisibility(supportLevel ? View.VISIBLE : View.GONE);
        };
        tvFormat.setOnClickListener(v -> {
            if (fmtPopup.isShowing()) {
                fmtPopup.dismiss();
                return;
            }
            if (fmtList.getChildCount() == 0) {
                for (int i = 0; i < COMPRESS_FORMATS.length; i++) {
                    final int idx = i;
                    TextView row = new TextView(ctx);
                    StringBuilder rowText = new StringBuilder(COMPRESS_FORMATS[i][1]);
                    if ("1".equals(COMPRESS_FORMATS[i][2])) rowText.append("   · 支持密码");
                    row.setText(rowText);
                    row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    row.setPadding(dp(14), dp(11), dp(14), dp(11));
                    row.setBackgroundResource(R.drawable.bg_ripple_round);
                    row.setClickable(true);
                    row.setTextColor(UiKit.color(ctx,
                            com.google.android.material.R.attr.colorOnSurface));
                    row.setOnClickListener(v2 -> {
                        fmtIdx[0] = idx;
                        fmtPopup.dismiss();
                        applyFormat.run();
                    });
                    fmtList.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
            fmtPopup.setWidth(Math.max(dp(180), tvFormat.getWidth()));
            // 高度自适应，最多 5 项（超出滚动）
            fmtPopup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            fmtScroll.setMinimumHeight(0);
            int maxH = dp(44 * 5);
            fmtList.measure(
                    View.MeasureSpec.makeMeasureSpec(Math.max(dp(180), tvFormat.getWidth()),
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            fmtPopup.setHeight(Math.min(fmtList.getMeasuredHeight() + dp(8), maxH));
            fmtPopup.showAsDropDown(tvFormat, 0, dp(2));
        });
        applyFormat.run();

        // ── 按钮 ──
        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(12);
        box.addView(btns, btnsLp);
        btns.addView(textButton("取消", v -> UiKit.dismissWithAnim(d)));
        btns.addView(textButton("确认", v -> {
            String name = etName.getText().toString().trim();
            String ext = COMPRESS_FORMATS[fmtIdx[0]][0];
            if (name.isEmpty()) {
                UiKit.alert(ctx, "文件名不能为空");
                return;
            }
            // 若用户自己写了扩展名就不重复添加。
            // 注意 tar.gz 这种多段扩展名：用户可能只写了 .tar 或 .gz，
            // 都视为「没写全」，统一补成完整后缀。
            String lower = name.toLowerCase();
            String suffix = "." + ext;
            if (!lower.endsWith(suffix)) {
                // 先剥掉用户误写的单段后缀，避免 foo.tar.tar.gz / foo.gz.tar.gz
                int dot = ext.indexOf('.');
                if (dot > 0) {
                    String lastSeg = "." + ext.substring(dot + 1);       // ".gz"
                    String firstSeg = "." + ext.substring(0, dot);        // ".tar"
                    if (lower.endsWith(lastSeg)) {
                        name = name.substring(0, name.length() - lastSeg.length());
                    } else if (lower.endsWith(firstSeg)) {
                        name = name.substring(0, name.length() - firstSeg.length());
                    }
                }
                name = name + suffix;
            }
            String pwd = "1".equals(COMPRESS_FORMATS[fmtIdx[0]][2])
                    ? etPwd.getText().toString() : "";
            UiKit.dismissWithAnim(d);
            doCompress(src, name, ext, level[0], pwd,
                    cbSingle.isChecked(), cbDelSrc.isChecked(), cbOtherPane.isChecked());
        }));
        // 格式下拉必须随对话框关闭：否则展开列表时点「取消」，
        // 弹窗关了但列表仍浮在屏幕上
        d.setOnDismissListener(x -> {
            if (fmtPopup != null && fmtPopup.isShowing()) fmtPopup.dismiss();
        });
        d.show();
        etName.postDelayed(() -> {
            etName.requestFocus();
            etName.setSelection(etName.getText().length());
        }, 150);
    }

    /** 表单小标题 */
    private TextView labelText(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(dp(4), dp(10), dp(4), dp(4));
        return tv;
    }

    /** 表单复选框 */
    private android.widget.CheckBox optionCheck(String text, boolean checked) {
        android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
        cb.setText(text);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cb.setChecked(checked);
        cb.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        cb.setPadding(dp(4), dp(8), dp(4), dp(4));
        return cb;
    }

    /** 执行压缩（zip4j，支持格式/等级/密码） */
    private void doCompress(final File src, final String outName, final String ext,
                            final int level, final String password,
                            final boolean single, final boolean delSource,
                            final boolean toOtherPane) {
        // 密码兜底：compressWithCommons 的调用方若传 null 会 NPE
        final String pwd = password == null ? "" : password;
        // 目标目录
        File outDir;
        if (toOtherPane) {
            Pane other = (active == paneL) ? paneR : paneL;
            File od = new File(other.path);
            outDir = od.isDirectory() ? od : src.getParentFile();
        } else {
            outDir = src.getParentFile();
        }
        if (outDir == null || !outDir.canWrite()) {
            UiKit.toast(ctx, "目标目录不可写");
            return;
        }
        UiKit.toast(ctx, "压缩中…");
        runTask(() -> {
            // 名字必须在本线程生成：uniqueName 是「检查再创建」，
            // 在主线程提前算好会在用户快速点两次时产生竞态——
            // 两次都拿到同一个不存在的名字，后一个 FileOutputStream 覆盖前一个。
            final File out = new File(outDir,
                    uniqueName(outDir.getAbsolutePath(), outName, null));
            boolean ok = false;
            String err = null;
            try {
                // TAR 家族 / 单流压缩 / 7Z 走 commons-compress
                // （zip4j 只能写 ZIP；RAR 因许可证限制不支持创建）
                if ("tar".equals(ext) || ext.startsWith("tar.")
                        || isSingleStreamExt(ext) || "7z".equals(ext)) {
                    final boolean tarOk;
                    try {
                        tarOk = compressWithCommons(src, out, ext, single, password);
                    } catch (Throwable tt) {
                        android.util.Log.e("HyCompress", "压缩失败 ext=" + ext
                                + " src=" + src + " dir=" + src.isDirectory(), tt);
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        // 把根因也拼进提示：NoClassDefFoundError 的 message
                        // 只有类名，看不出是谁缺，补上 cause 链第一层
                        Throwable root = tt;
                        while (root.getCause() != null && root.getCause() != root) {
                            root = root.getCause();
                        }
                        String terr = tt.getClass().getSimpleName() + ": " + tt.getMessage();
                        if (root != tt) {
                            terr += " ← " + root.getClass().getSimpleName()
                                    + ": " + root.getMessage();
                        }
                        final String fterr = terr;
                        handler.post(() -> UiKit.alert(ctx, "压缩失败：" + fterr));
                        return;
                    }
                    handler.post(() -> {
                        if (tarOk) {
                            UiKit.toast(ctx, "已压缩：" + out.getName());
                            if (delSource) deleteSourceQuietly(src);
                            reloadAll();
                        } else {
                            UiKit.alert(ctx, "压缩失败");
                        }
                    });
                    return;
                }

                net.lingala.zip4j.model.ZipParameters params =
                        new net.lingala.zip4j.model.ZipParameters();
                params.setCompressionLevel(level <= 0
                        ? net.lingala.zip4j.model.enums.CompressionLevel.NO_COMPRESSION
                        : (level <= 1
                            ? net.lingala.zip4j.model.enums.CompressionLevel.FASTEST
                            : (level >= 9
                                ? net.lingala.zip4j.model.enums.CompressionLevel.ULTRA
                                : net.lingala.zip4j.model.enums.CompressionLevel.NORMAL)));
                if (!password.isEmpty()) {
                    params.setEncryptFiles(true);
                    params.setEncryptionMethod(
                            net.lingala.zip4j.model.enums.EncryptionMethod.AES);
                }
                // 仅 zip/7z 支持；其他格式降级为 zip 内容（.tar/.gz 由 zip4j 不支持）
                net.lingala.zip4j.ZipFile zf =
                        password.isEmpty()
                                ? new net.lingala.zip4j.ZipFile(out)
                                : new net.lingala.zip4j.ZipFile(out, password.toCharArray());

                if (single && src.isDirectory()) {
                    // 单独压缩：把目录自身作为顶层条目加进去
                    params.setIncludeRootFolder(true);
                    zf.addFile(src, params);
                } else if (src.isDirectory()) {
                    // 普通压缩：只压内容（不含顶层目录）
                    params.setIncludeRootFolder(false);
                    zf.addFolder(src, params);
                } else {
                    zf.addFile(src, params);
                }
                ok = true;
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            final boolean fOk = ok;
            final String fErr = err;
            handler.post(() -> {
                if (fOk) {
                    UiKit.toast(ctx, "已压缩：" + out.getName());
                    if (delSource) {
                        try {
                            if (src.isDirectory()) deleteTree(src);
                            else //noinspection ResultOfMethodCallIgnored
                                src.delete();
                        } catch (Throwable ignored) {
                        }
                    }
                    reloadAll();
                } else {
                    UiKit.alert(ctx, "压缩失败：" + fErr);
                }
            });
        });
    }

    /**
     * TAR / TAR.GZ / GZ 压缩（commons-compress）。
     *
     * <p>tar 与 tar.gz 把整个目录树写入归档；
     * gz 只压缩单个文件（对目录会先打成 tar 再 gz）。</p>
     *
     * @param single 目录场景下是否把目录自身作为顶层条目
     */
    /**
     * 用 commons-compress 创建压缩包。
     *
     * <p>支持两种形态：</p>
     * <ul>
     *   <li><b>单流压缩</b>（gz/bz2/xz/zst，且源是单个文件）——无归档结构，
     *       解压后就是一个文件</li>
     *   <li><b>tar 家族</b>（tar / tar.gz / tar.bz2 / tar.xz / tar.zst）——
     *       先打 tar 再用对应压缩器包一层</li>
     * </ul>
     *
     * <p>7z 走 {@link #compress7z}（那条路要处理密码与条目头）。</p>
     */
    private boolean compressWithCommons(File src, File out, String ext, boolean single,
                                        String password) throws java.io.IOException {
        if ("7z".equals(ext)) return compress7z(src, out, single, password);

        // ── 单文件流压缩：gz / bz2 / xz / zst ──
        // 源是目录时不能裸压单流（会生成无压缩器的裸 tar 却叫 .gz），
        // 但也不能报错——调用方已按 ext 拼好文件名，这里降级为 tar.xxx 保持一致。
        if (src.isDirectory() && isSingleStreamExt(ext)) {
            // 走到下面的 tar 分支，但需要真实压缩器：对 tarInnerExt 特判
            // （见下方 inner 的取值处）
        } else if (!src.isDirectory() && isSingleStreamExt(ext)) {
            try (java.io.InputStream in = new java.io.BufferedInputStream(
                        new java.io.FileInputStream(src));
                 java.io.OutputStream os = new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(out));
                 java.io.OutputStream comp = wrapCompressor(os, ext)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) comp.write(buf, 0, n);
            }
            return true;
        }

        // ── tar 家族 ──
        java.io.OutputStream base = new java.io.BufferedOutputStream(
                new java.io.FileOutputStream(out));
        try {
            // "tar.gz" → "gz"；"tar" → null（不套压缩器）
            // 单流格式（gz/bz2/xz/zst）直接当压缩器用——这是「源是目录」时的降级路径，
            // 否则 tarInnerExt("gz") 会返回 null，生成裸 tar 却挂 .gz 后缀
            String inner = isSingleStreamExt(ext) ? ext : tarInnerExt(ext);
            java.io.OutputStream chain = inner == null ? base : wrapCompressor(base, inner);
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar =
                    new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(chain);
            try {
                String rootName = single ? src.getName() : "";
                if (src.isDirectory()) {
                    addTarDir(tar, src, rootName);
                } else {
                    addTarFile(tar, src, single ? src.getName() : src.getName());
                }
                tar.finish();
            } finally {
                tar.close();   // 会连带关闭 chain
            }
            return true;
        } finally {
            try { base.close(); } catch (Throwable ignored) { }
        }
    }

    /** 单文件流压缩格式（解压后是一个文件，不是归档） */
    private static boolean isSingleStreamExt(String ext) {
        return "gz".equals(ext) || "bz2".equals(ext) || "xz".equals(ext) || "zst".equals(ext);
    }

    /** "tar.gz" → "gz"；"tar" → null（不套压缩器） */
    private static String tarInnerExt(String ext) {
        if ("tar".equals(ext)) return null;
        int dot = ext.indexOf('.');
        return dot < 0 ? null : ext.substring(dot + 1);
    }

    /**
     * 按扩展名创建压缩流。xz / zst 的等级走预设值。
     *
     * <p>注意 xz 与 zstd 的依赖来源：xz 是 org.tukaani:xz（纯 Java），
     * zstd-jni 是 com.github.luben:zstd-jni（JNI 封装）。两者都已在
     * build.gradle.kts 里，且许可可闭源（public domain / BSD-2）。</p>
     */
    private java.io.OutputStream wrapCompressor(java.io.OutputStream os, String kind)
            throws java.io.IOException {
        switch (kind == null ? "" : kind) {
            case "gz":
                return new org.apache.commons.compress.compressors.gzip
                        .GzipCompressorOutputStream(os);
            case "bz2":
                return new org.apache.commons.compress.compressors.bzip2
                        .BZip2CompressorOutputStream(os);
            case "xz":
                // preset 0-9。库默认是 6，但 6 的 LZMA2 字典 8MiB、
                // 编码器峰值内存约 94MB，低内存 Android 设备容易 OOM。
                // 取 4（字典 4MiB，约 12MB）——手机上压缩率差异很小。
                return new org.apache.commons.compress.compressors.xz
                        .XZCompressorOutputStream(os, 4);
            case "zst":
                // zstd-jni 需要原生库；版本被钉在 1.5.5-11（新版要 compileSdk 37）。
                // ABI 不匹配时抛 UnsatisfiedLinkError / NoClassDefFoundError，
                // 那是 Error 不是 Exception，必须显式转成 IOException，
                // 否则上层只看到「压缩失败」而没有可读原因。
                try {
                    return new com.github.luben.zstd.ZstdOutputStream(os);
                } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                    throw new java.io.IOException("此设备不支持 ZSTD（原生库加载失败）", e);
                }
            default:
                throw new java.io.IOException("不支持的压缩格式：" + kind);
        }
    }

    /** 7z 压缩（commons-compress SevenZOutputFile；zip4j 不支持写 7z） */
    private boolean compress7z(File src, File out, boolean single, String password)
            throws java.io.IOException {
        // commons-compress 1.23.0+ 的 SevenZOutputFile 支持写加密 7z。
        // 旧版（我们曾用的 1.20）只有解码器，所以当初误判为「不支持写加密」。
        // 1.26.0 起 AES256 加密可用。
        char[] pwd = (password == null || password.isEmpty()) ? null : password.toCharArray();
        try (org.apache.commons.compress.archivers.sevenz.SevenZOutputFile sz =
                     new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(out, pwd)) {
            if (src.isDirectory()) {
                add7zDir(sz, src, single ? src.getName() : "");
            } else {
                add7zFile(sz, src, src.getName());
            }
            sz.finish();
        }
        return true;
    }

    private void add7zDir(org.apache.commons.compress.archivers.sevenz.SevenZOutputFile sz,
                          File dir, String prefix) throws java.io.IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String entry = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) add7zDir(sz, f, entry);
            else add7zFile(sz, f, entry);
        }
    }

    private void add7zFile(org.apache.commons.compress.archivers.sevenz.SevenZOutputFile sz,
                           File f, String entryName) throws java.io.IOException {
        org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e =
                sz.createArchiveEntry(f, entryName);
        sz.putArchiveEntry(e);
        try (java.io.InputStream in = new java.io.BufferedInputStream(
                new java.io.FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sz.write(buf, 0, n);
        }
        sz.closeArchiveEntry();
    }

    /** 递归写入 TAR：prefix 为空时直接写目录内容 */
    private void addTarDir(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar,
                           File dir, String prefix) throws java.io.IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String entry = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                addTarDir(tar, f, entry);
            } else {
                addTarFile(tar, f, entry);
            }
        }
    }

    /** 写入单个文件到 TAR */
    private void addTarFile(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar,
                            File f, String entryName) throws java.io.IOException {
        org.apache.commons.compress.archivers.tar.TarArchiveEntry entry =
                new org.apache.commons.compress.archivers.tar.TarArchiveEntry(f, entryName);
        tar.putArchiveEntry(entry);
        try (java.io.InputStream in = new java.io.BufferedInputStream(
                new java.io.FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) tar.write(buf, 0, n);
        }
        tar.closeArchiveEntry();
    }

    /** 删除源文件/目录（失败静默，不阻断压缩结果提示） */
    private void deleteSourceQuietly(File src) {
        try {
            if (src.isDirectory()) deleteTree(src);
            else //noinspection ResultOfMethodCallIgnored
                src.delete();
        } catch (Throwable ignored) {
        }
    }

    private void zipDir(File dir, String entryBase, java.util.zip.ZipOutputStream zos)
            throws java.io.IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String entry = entryBase + "/" + f.getName();
            if (f.isDirectory()) zipDir(f, entry, zos);
            else zipFileEntry(f, entry, zos);
        }
    }

    private void zipFileEntry(File f, String entry, java.util.zip.ZipOutputStream zos)
            throws java.io.IOException {
        java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(entry);
        ze.setTime(f.lastModified());
        zos.putNextEntry(ze);
        try (java.io.BufferedInputStream in = new java.io.BufferedInputStream(
                new java.io.FileInputStream(f))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    /** 解压 zip 到同目录 <zip名>/：处理 Windows GBK 文件名乱码、路径穿越防护 */
    private void extractZip(final FileEntry e) {
        final File archive = AccessRouter.fileOf(e);
        final File parent = archive.getParentFile();
        if (parent == null || !parent.canWrite()) {
            UiKit.alert(ctx, "目录不可写，无法解压");
            return;
        }
        // 去掉压缩扩展名作为输出目录名（含复合扩展名）
        String base = e.name;
        String lower = base.toLowerCase(Locale.getDefault());
        for (String suffix : new String[]{".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst",
                ".tgz", ".tbz2", ".tbz", ".txz", ".tzst", ".zip", ".jar", ".apk", ".xapk",
                ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz", ".zst"}) {
            if (lower.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        if (base.isEmpty()) base = "extracted";
        final File outDir = new File(parent, uniqueName(parent.getAbsolutePath(), base, null));

        UiKit.toast(ctx, "解压中…");
        withArchiveSecret(archive, secret -> runTask(() -> {
            com.aliya.hyfilemgr.archive.ArchiveExtractor.Result res =
                    com.aliya.hyfilemgr.archive.ArchiveExtractor.extract(
                            archive, outDir, secret, null);
            handler.post(() -> {
                if (res.ok) {
                    UiKit.toast(ctx, "已解压 " + res.files + " 个文件到 " + outDir.getName()
                            + (res.skipped.isEmpty() ? ""
                               : "（跳过 " + res.skipped.size() + " 个非法路径条目）"));
                    reloadAll();
                } else {
                    UiKit.alert(ctx, "解压失败：" + res.error);
                    try {
                        String[] left = outDir.list();
                        if (left != null && left.length == 0) {
                            //noinspection ResultOfMethodCallIgnored
                            outDir.delete();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }));
    }

    private static String fixGbkName(String name) {
        try {
            return new String(name.getBytes("ISO-8859-1"), "GBK");
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsGarbled(String s) {
        if (s == null || s.isEmpty()) return false;
        // UTF-8 解 GBK 字节时产生 U+FFFD。旧判据只看 c < 0x20 且跳过 \0，
        // 真实乱码是 U+FFFD 与高位字符 → 全部漏检，GBK 中文被当 UTF-8
        // 打开成一片替换符。
        int bad = 0;
        int total = Math.min(s.length(), 4096);
        for (int i = 0; i < total; i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') bad++;
            else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') bad++;
        }
        return total > 0 && bad * 200 > total;   // 超过 0.5%
    }

    /** 递归扫描：深度≤10、结果≤3000（防卡死）；跳过隐藏目录（含回收站）与 Android 大目录 */
    private void scanDir(File dir, Set<String> extSet, List<FileEntry> out, int depth) {
        if (depth > 10 || out.size() >= 3000) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (n.equals("Android")) continue;
                scanDir(f, extSet, out, depth + 1);
            } else {
                int dot = n.lastIndexOf('.');
                String ext = dot >= 0 ? n.substring(dot).toLowerCase(Locale.getDefault()) : "";
                if (extSet.contains(ext)) {
                    out.add(FileEntry.file(f.getAbsolutePath(), f.length(), f.lastModified()));
                }
            }
        }
    }

    // ── 回收站（侧边栏入口；参考 Amaze TRASH_BIN：删文件移入隐藏目录，可恢复/彻底删） ──

    /** 回收站视图：列出 .HyVqTrash 下所有文件（保留层级、平铺显示） */
    // ══════════════════════════════════════════════════════════════
    //  ZIP 内部浏览（beta.8）：只读中央目录，不解压任何数据
    //  虚拟路径 zip://<zip绝对路径>/<包内相对路径>
    // ══════════════════════════════════════════════════════════════

    /** 把 FileEntry 的 zip 路径拆成 [zip 文件绝对路径, 包内相对路径] */
    private static String[] splitZipPath(String virt) {
        String rest = virt.substring(PREFIX_ZIP.length());
        // zip 路径自身可能含 "/"，逐个尝试已知的 .zip/.jar/.apk 结尾作为切分点
        int best = -1;
        for (int i = rest.length(); i > 0; i--) {
            char c = rest.charAt(i - 1);
            if (c != '/' && c != '\\') continue;
            String head = rest.substring(0, i - 1);
            String low = head.toLowerCase(java.util.Locale.ROOT);
            // 支持所有可浏览格式的切分：
            // 此前只认 zip/jar/apk，7z/tar/rar 会切分失败 →
            // zipPath 变成整个 "zip:///sdcard/x/test.7z/" → isFile()=false →
            // loadZip 提前 return "压缩包不存在"，表现为「打开后空白、连 .. 都没有」
            if (low.endsWith(".zip") || low.endsWith(".jar") || low.endsWith(".apk")
                    || low.endsWith(".xapk") || low.endsWith(".7z")
                    || low.endsWith(".rar") || low.endsWith(".tar")
                    || low.endsWith(".tar.gz") || low.endsWith(".tar.bz2")
                    || low.endsWith(".tar.xz") || low.endsWith(".tar.zst")
                    || low.endsWith(".tgz")) {
                best = i - 1;
                break;
            }
        }
        if (best < 0) {
            int sl = rest.indexOf('/');
            best = sl < 0 ? rest.length() : sl;
        }
        String zip = rest.substring(0, best);
        String inner = rest.substring(Math.min(best + 1, rest.length()));
        while (inner.startsWith("/")) inner = inner.substring(1);
        return new String[]{zip, inner};
    }

    /** 进入某个 ZIP 的根：切换活跃窗格到 zip:// 虚拟路径 */

    /**
     * 若压缩包是加密的，先弹输入框收集凭据，再执行 {@code action}；
     * 否则直接执行（action 收到 null）。
     *
     * <p>此前两个解压入口都硬编码传 null，导致「本 App 加密压出的包本 App 打不开」。
     * 检测能力已有（{@link #readZipEncryptionMap} 解析 zip 中央目录），
     * 7z 无法预读加密标志，索性一律问一次——用户点取消则中止。</p>
     *
     * @param archive 待解压的压缩包
     * @param action  收到凭据后执行（可为空串）
     */
    private void withArchiveSecret(final File archive,
                                   final java.util.function.Consumer<String> action) {
        String name = archive.getName().toLowerCase(java.util.Locale.ROOT);
        boolean encrypted;
        if (name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".apk")
                || name.endsWith(".xapk")) {
            java.util.Map<String, Boolean> m = readZipEncryptionMap(archive);
            encrypted = false;
            for (Boolean v : m.values()) {
                if (Boolean.TRUE.equals(v)) { encrypted = true; break; }
            }
        } else if (name.endsWith(".7z")) {
            // 7z 的加密标志在文件头里，预读代价高；直接问一次，
            // 未加密的包用户留空即可
            encrypted = true;
        } else {
            encrypted = false;
        }
        if (!encrypted) { action.accept(""); return; }

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "压缩包已加密"));
        TextView hint = new TextView(ctx);
        hint.setText("输入解压凭据（留空则按未加密处理）");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(hint);
        final EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setHint("留空表示无");
        box.addView(et);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setGravity(Gravity.END);
        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("取消", v -> UiKit.dismissWithAnim(d)));
        btns.addView(textButton("解压", v -> {
            String s2 = et.getText().toString();
            UiKit.dismissWithAnim(d);
            action.accept(s2);
        }));
        box.addView(btns);
        d.show();
    }

    private void enterZip(String zipPath) {
        active.path = PREFIX_ZIP + zipPath + "/";
        reload(active);
    }


    // ══════════ 统一压缩包条目枚举（预览用） ══════════

    /** 一条归档内条目的最小信息，与具体格式无关 */
    private static final class ArchiveItem {
        final String name;      // 包内路径，用 / 分隔，已归一化（去掉 ./ 前缀、多余斜杠、尾部 /）
        final long size;        // 未压缩大小，-1 表示未知
        final long time;        // mtime，0 表示未知
        final boolean isDir;    // 是否是目录条目（tar/7z 会显式记录空目录）
        ArchiveItem(String name, long size, long time) {
            this(name, size, time, false);
        }
        ArchiveItem(String name, long size, long time, boolean isDir) {
            this.name = normalizeEntryName(name);
            this.size = size; this.time = time; this.isDir = isDir;
        }
    }

    /**
     * 归一化归档条目名。
     *
     * <p>tar 常带 {@code ./} 前缀（{@code tar czf x.tar.gz -C dir .} 就会这样），
     * 不处理的话 loadZip 里 {@code rel.indexOf('/')} 会落在位置 1，
     * {@code dirName} 取成空串，整包条目全部丢失。</p>
     *
     * <p>同时去掉前导 / 与重复斜杠，防止拼出 {@code //} 或绝对路径。</p>
     */
    private static String normalizeEntryName(String raw) {
        if (raw == null) return "";
        String n = raw.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        while (n.contains("//")) n = n.replace("//", "/");
        // 目录条目常以 / 结尾（tar 的 "./sub/"）：去掉尾部斜杠，
        // 否则 "sub/" 在按 / 切分时 dirName 会取成 "sub" 但 rel 为空导致条目丢失
        while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        return n;
    }

    /**
     * 列出压缩包内所有条目，与格式无关。
     *
     * <p>此前只有 zip/jar/apk 能内部浏览（java.util.zip.ZipFile），
     * 7z / rar / tar 家族单击会直接走解压。这里把「取条目列表」抽出来：
     * zip 走 ZipFile、7z 走 SevenZFile、tar 家族走 ArchiveInputStream、
     * rar 走 junrar Archive。</p>
     *
     * <p>大包代价：tar 家族必须单遍顺序扫（无中央目录），7z/zip/rar 可随机访问。
     * 因此结果由调用方按「压缩包绝对路径」缓存，滚动/进子目录不重复扫。</p>
     *
     * @return 条目列表；失败抛异常由调用方转成提示
     */
    private static java.util.List<ArchiveItem> listArchiveItems(File archive)
            throws Exception {
        java.util.List<ArchiveItem> out = new java.util.ArrayList<>();
        String lower = archive.getName().toLowerCase(java.util.Locale.ROOT);

        if (lower.endsWith(".zip") || lower.endsWith(".jar")
                || lower.endsWith(".apk") || lower.endsWith(".xapk")) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(archive)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry ze = en.nextElement();
                    out.add(new ArchiveItem(ze.getName(), ze.getSize(), ze.getTime(),
                            ze.isDirectory()));
                }
            }
            return out;
        }

        if (lower.endsWith(".7z")) {
            try (org.apache.commons.compress.archivers.sevenz.SevenZFile sz =
                         new org.apache.commons.compress.archivers.sevenz.SevenZFile(archive)) {
                for (org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e
                        : sz.getEntries()) {
                    // getLastModifiedDate() 在条目没有该字段时**抛
                    // UnsupportedOperationException**，不是返回 null。
                    // 必须先 hasLastModifiedDate() 判断，否则整个枚举直接失败。
                    long t = 0;
                    if (e.getHasLastModifiedDate()) {
                        java.util.Date d = e.getLastModifiedDate();
                        if (d != null) t = d.getTime();
                    }
                    out.add(new ArchiveItem(e.getName(), e.getSize(), t, e.isDirectory()));
                }
            }
            return out;
        }

        if (lower.endsWith(".rar")) {
            try (com.github.junrar.Archive a = new com.github.junrar.Archive(archive)) {
                for (com.github.junrar.rarfile.FileHeader h : a.getFileHeaders()) {
                    out.add(new ArchiveItem(h.getFileName(), h.getFullUnpackSize(),
                            h.getMTime() == null ? 0 : h.getMTime().getTime(),
                            h.isDirectory()));
                }
            }
            return out;
        }

        // tar 家族：tar / tar.gz / tar.bz2 / tar.xz / tar.zst
        // 单流格式（gz/bz2/xz/zst）不是归档，没有条目可列 → 返回空
        if (!lower.endsWith(".tar") && !lower.contains(".tar.")) return out;

        try (java.io.InputStream fin = new java.io.BufferedInputStream(
                     new java.io.FileInputStream(archive));
             org.apache.commons.compress.archivers.ArchiveInputStream<?> ain =
                     wrapArchiveStream(fin, archive.getName())) {
            if (ain == null) return out;
            org.apache.commons.compress.archivers.ArchiveEntry ae;
            while ((ae = ain.getNextEntry()) != null) {
                out.add(new ArchiveItem(ae.getName(), ae.getSize(),
                        ae.getLastModifiedDate() == null ? 0
                                : ae.getLastModifiedDate().getTime(),
                        ae.isDirectory()));
            }
        }
        return out;
    }

    /** 按扩展名把输入流包成 tar / tar.gz / tar.bz2 / tar.xz / tar.zst 的读流 */
    private static org.apache.commons.compress.archivers.ArchiveInputStream<?>
            wrapArchiveStream(java.io.InputStream in, String fileName) throws Exception {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        java.io.InputStream dec = in;
        if (lower.contains(".tar.gz") || lower.endsWith(".tgz")) {
            dec = new org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(in);
        } else if (lower.contains(".tar.bz2")) {
            dec = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(in);
        } else if (lower.contains(".tar.xz")) {
            dec = new org.apache.commons.compress.compressors.xz.XZCompressorInputStream(in);
        } else if (lower.contains(".tar.zst")) {
            dec = new com.github.luben.zstd.ZstdInputStream(in);
        }
        return new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(dec);
    }

    /**
     * 压缩包内条目列表缓存：包路径 → 条目（避免每次进子目录重扫 tar）。
     *
     * <p><b>必须有上限</b>：用户浏览过的每个包都会留一份完整条目列表，
     * 十万条目的 7z 单份就占几 MB。旧实现用无界 HashMap，
     * 浏览几十个包后内存只涨不降。</p>
     *
     * <p>用 LinkedHashMap 的 accessOrder 做 LRU，超过 ARCHIVE_CACHE_MAX
     * 个包就淘汰最久未用的。代价是被淘汰的包再进来要重扫一次
     * （tar 家族尤其明显），但优于内存持续增长。</p>
     */
    private static final int ARCHIVE_CACHE_MAX = 8;
    private final java.util.Map<String, java.util.List<ArchiveItem>> archiveItemsCache =
            new java.util.LinkedHashMap<String, java.util.List<ArchiveItem>>(
                    16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, java.util.List<ArchiveItem>> eldest) {
                    return size() > ARCHIVE_CACHE_MAX;
                }
            };

    private void loadZip(Pane p) {
        p.entries.clear();
        String[] parts = splitZipPath(p.path);
        String zipPath = parts[0];
        String inner = parts[1];
        File zip = new File(zipPath);
        if (!zip.isFile()) {
            android.util.Log.w("HyArchive", "loadZip: 不是文件 path=" + p.path
                    + " → zipPath=" + zipPath + " inner=" + inner);
            p.lastResult = AccessResult.empty("压缩包不存在：" + zipPath);
            return;
        }

        // 统一枚举：zip / 7z / rar / tar 家族都走同一条路径
        java.util.List<ArchiveItem> items = archiveItemsCache.get(zipPath);
        if (items == null) {
            try {
                items = listArchiveItems(zip);
                archiveItemsCache.put(zipPath, items);
            } catch (Throwable t) {
                android.util.Log.e("HyArchive", "listArchiveItems 失败 " + zipPath, t);
                p.lastResult = AccessResult.empty("无法读取压缩包：" + t.getMessage());
                return;
            }
        }

        android.util.Log.d("HyArchive", "loadZip path=" + p.path
                + " zipPath=" + zipPath + " inner=[" + inner + "]"
                + " items=" + items.size());

        java.util.TreeMap<String, FileEntry> dirs = new java.util.TreeMap<>();
        java.util.TreeMap<String, FileEntry> files = new java.util.TreeMap<>();
        // 加密标记只对 zip 有意义（能读到中央目录标志位）
        java.util.Map<String, Boolean> encMap =
                zip.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                        ? readZipEncryptionMap(zip)
                        : java.util.Collections.emptyMap();

        // inner 来自 splitZipPath，形如 "sub/"（带尾部斜杠）。
        // 直接 inner + "/" 会拼成 "sub//"，与条目名 "sub/b.txt" 永远匹配不上 ——
        // 这就是「根目录能列出、点进子目录一片空白」的根因。
        String innerNorm = inner;
        while (innerNorm.endsWith("/")) innerNorm = innerNorm.substring(0, innerNorm.length() - 1);
        String prefix = innerNorm.isEmpty() ? "" : innerNorm + "/";
        for (ArchiveItem it : items) {
            String name = it.name;
            if (name == null || name.isEmpty()) continue;   // "./" 归一化后为空
            if (!name.startsWith(prefix)) continue;
            String rel = name.substring(prefix.length());
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.isEmpty()) continue;

            int slash = rel.indexOf('/');
            // it.isDir：tar/7z 会显式给出目录条目（内容是空的），
            // 不能只靠名称里有没有 / 来判断
            if (it.isDir || slash >= 0) {
                String dirName = it.isDir ? rel : rel.substring(0, slash);
                if (!dirs.containsKey(dirName)) {
                    String childVirt = p.path + dirName + "/";
                    String dl = dirName.toLowerCase(java.util.Locale.ROOT);
                    boolean nested = dl.endsWith(".zip") || dl.endsWith(".7z")
                            || dl.endsWith(".rar") || dl.endsWith(".tar")
                            || dl.contains(".tar.") || dl.endsWith(".gz")
                            || dl.endsWith(".bz2") || dl.endsWith(".xz")
                            || dl.endsWith(".zst");
                    dirs.put(dirName, new FileEntry(childVirt, dirName,
                            FileEntry.Type.VIRTUAL_DIR, 0, 0, true,
                            nested ? "嵌套压缩包" : "文件夹", false));
                }
            } else {
                String childVirt = p.path + rel;
                boolean encrypted = Boolean.TRUE.equals(encMap.get(name));
                String sub = fmtStorageSize(it.size)
                        + (encrypted ? " · 加密" : "");
                files.put(rel, new FileEntry(childVirt, rel,
                        FileEntry.Type.VIRTUAL_FILE, it.size, it.time, true,
                        sub, false));
            }
        }

        // 置顶 ".." 返回条目：
        //  · 包内子目录 → 回到包内上一级
        //  · 压缩包根目录 → 退出预览，回到压缩包所在文件夹
        if (!inner.isEmpty()) {
            int cut = inner.lastIndexOf('/');
            String up = cut < 0 ? "" : inner.substring(0, cut + 1);
            p.entries.add(FileEntry.parent(PREFIX_ZIP + zipPath + "/" + up));
        } else {
            String zipDir = new File(zipPath).getParent();
            p.entries.add(FileEntry.parent(zipDir == null
                    ? Environment.getExternalStorageDirectory().getAbsolutePath()
                    : zipDir));
        }
        p.entries.addAll(dirs.values());
        p.entries.addAll(files.values());
        android.util.Log.d("HyArchive", "loadZip result dirs=" + dirs.size()
                + " files=" + files.size() + " entries=" + p.entries.size());
        if (items.isEmpty()) {
            p.lastResult = AccessResult.empty("压缩包 · " + zip.getName()
                    + "（单文件流格式，无内部条目）");
        } else {
            p.lastResult = AccessResult.empty("压缩包 · " + zip.getName());
        }
    }

    /** 把包内条目解出到另一窗格的当前目录 */
    /**
     * 把包内条目解出到另一窗格。
     *
     * <p>旧实现只认 zip（java.util.zip.ZipFile + splitZipPath 白名单），
     * 但顶部菜单已放开非 zip 格式 → 7z/rar/tar 走到这里必然失败。
     * 改为整包交给 ArchiveExtractor，再在目标目录里定位该条目。</p>
     */
    private void extractZipEntryToOtherPane(final FileEntry e) {
        if (panes.size() < 2) {
            UiKit.alert(ctx, "当前为单窗格，无法解压到对侧");
            return;
        }
        final Pane other = (panes.get(0) == active) ? panes.get(1) : panes.get(0);
        if (other == null || other.path == null
                || other.path.startsWith(PREFIX_ZIP)
                || other.path.startsWith(PREFIX_TRASH)) {
            UiKit.toast(ctx, "另一窗格不在可写入的目录");
            return;
        }
        final String destDir = other.path;
        String[] parts = splitZipPath(e.path);
        final String archivePath = parts[0];
        final String inner = parts[1];
        if (inner == null || inner.isEmpty()) {
            UiKit.toast(ctx, "请选择包内文件");
            return;
        }

        UiKit.toast(ctx, "解出中…");
        new Thread(() -> {
            String err = null;
            String outName = null;
            try {
                // 定点提取单个条目：不再把整包解到缓存再挑。
                // 旧做法 100MB 的包解 1KB 文件也要全解，还会先撞 Guard 上限。
                String leaf = inner;
                int cut = leaf.lastIndexOf('/');
                if (cut >= 0) leaf = leaf.substring(cut + 1);
                if (leaf.isEmpty()) leaf = "entry";
                File dest = new File(destDir, uniqueName(destDir, leaf, null));
                String e2 = com.aliya.hyfilemgr.archive.ArchiveExtractor
                        .extractEntry(new File(archivePath), inner, dest);
                if (e2 != null) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    throw new java.io.IOException(e2);
                }
                outName = dest.getName();
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String fErr = err, fOut = outName;
            handler.post(() -> {
                UiKit.toast(ctx, fErr != null ? "解出失败：" + fErr : "已解出：" + fOut);
                if (fErr == null) reload(other);
            });
        }, "HyVqZipToOther").start();
    }

    /** 在临时解压目录里按相对路径后缀查找条目 */
    private static File findBySuffix(File root, String inner) {
        java.util.ArrayDeque<File> q = new java.util.ArrayDeque<>();
        q.add(root);
        String norm = inner.replace('\\', '/');
        while (!q.isEmpty()) {
            File cur = q.poll();
            File[] items = cur.listFiles();
            if (items == null) continue;
            for (File f : items) {
                String p = f.getAbsolutePath().replace('\\', '/');
                if (p.endsWith("/" + norm)) return f;
                if (f.isDirectory()) q.add(f);
            }
        }
        return null;
    }

    /** 进入嵌套压缩包：先把内层 zip 解到缓存，再以它为根进入内部浏览 */
    private void enterNestedZip(final FileEntry e) {
        String[] parts = splitZipPath(e.path);
        final String outerZip = parts[0];
        final String inner = parts[1];
        UiKit.toast(ctx, "展开嵌套压缩包…");
        new Thread(() -> {
            String err = null;
            String nestedPath = null;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(new File(outerZip))) {
                java.util.zip.ZipEntry ze = zf.getEntry(inner);
                if (ze == null) {
                    err = "条目不存在";
                } else {
                    File dir = new File(ctx.getCacheDir(), "zipnested");
                    if (!dir.exists()) dir.mkdirs();
                    // 用条目名 hash 作文件名，避免不同包内同名互相覆盖
                    String base = inner.substring(inner.lastIndexOf('/') + 1);
                    File out = new File(dir, Math.abs(inner.hashCode()) + "_" + base);
                    try (java.io.InputStream in = zf.getInputStream(ze);
                         java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    nestedPath = out.getAbsolutePath();
                }
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String fErr = err, fPath = nestedPath;
            handler.post(() -> {
                if (fErr != null) {
                    UiKit.alert(ctx, "展开失败：" + fErr);
                } else {
                    active.path = PREFIX_ZIP + fPath + "/";
                    reload(active);
                }
            });
        }, "HyVqZipNested").start();
    }

    /** 打开压缩包内的单个条目：只解出命中这一条到缓存，其余条目不读 */
    /** 加密压缩包：密码输入框 → zip4j 解密解出单条 → 交给打开器 */
    private void showZipPasswordDialog(final String zipPath, final String inner,
                                       final String displayName) {
        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setHint("请输入压缩包密码");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "🔐 加密压缩包"));
        TextView desc = new TextView(ctx);
        desc.setText("条目：" + displayName + "\n该条目已加密，输入密码后解出");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        desc.setPadding(dp(4), dp(4), dp(4), dp(8));
        box.addView(desc);
        box.addView(et);
        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.END);
        box.addView(btns);
        btns.addView(textButton("取消", v -> d.dismiss()));
        btns.addView(textButton("解出", v -> {
            final String pwd = et.getText().toString();
            d.dismiss();
            if (pwd.isEmpty()) {
                UiKit.alert(ctx, "密码不能为空");
                return;
            }
            UiKit.toast(ctx, "解出中…");
            new Thread(() -> {
                File out = null;
                String err = null;
                try {
                    out = extractZipEntryWithPassword(zipPath, inner, pwd);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
                final File fOut = out;
                final String fErr = err;
                handler.post(() -> {
                    if (fErr != null) {
                        UiKit.alert(ctx, "解出失败：" + fErr);
                    } else if (fOut == null) {
                        UiKit.toast(ctx, "该条目不是文件");
                    } else if (isTextFile(fOut.getName())) {
                        openTextEditor(fOut);
                    } else {
                        openFile(fOut);
                    }
                });
            }, "HyVqZipEnc").start();
        }));
        d.show();
    }

    /** 用 zip4j 解密解出单条；失败抛异常由调用方处理 */
    private File extractZipEntryWithPassword(String zipPath, String inner, String password)
            throws Exception {
        net.lingala.zip4j.ZipFile zf = new net.lingala.zip4j.ZipFile(new File(zipPath), password.toCharArray());
        net.lingala.zip4j.model.FileHeader fh = zf.getFileHeader(inner);
        if (fh == null) throw new Exception("条目不存在");
        File dir = new File(ctx.getCacheDir(), "zipview");
        if (!dir.exists()) dir.mkdirs();
        String base = inner.substring(inner.lastIndexOf('/') + 1);
        File out = new File(dir, base);
        zf.extractFile(fh, dir.getAbsolutePath(), base);
        return out;
    }

    private void openZipEntry(final FileEntry e) {
        String[] parts = splitZipPath(e.path);
        final String zipPath = parts[0];
        final String inner = parts[1];
        if (inner.isEmpty()) return;
        // 加密条目：弹密码框，用 zip4j 解密
        java.util.Map<String, Boolean> encCheck = readZipEncryptionMap(new File(zipPath));
        if (Boolean.TRUE.equals(encCheck.get(inner))) {
            showZipPasswordDialog(zipPath, inner, e.name);
            return;
        }
        UiKit.toast(ctx, "解出中…");
        new Thread(() -> {
            File out = null;
            String err = null;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(new File(zipPath))) {
                java.util.zip.ZipEntry ze = zf.getEntry(inner);
                if (ze == null) {
                    err = "条目不存在";
                } else if (ze.isDirectory()) {
                    err = null;
                } else {
                    File dir = new File(ctx.getCacheDir(), "zipview");
                    if (!dir.exists()) dir.mkdirs();
                    String base = inner.substring(inner.lastIndexOf('/') + 1);
                    out = new File(dir, base);
                    try (java.io.InputStream in = zf.getInputStream(ze);
                         java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final File fOut = out;
            final String fErr = err;
            handler.post(() -> {
                if (fErr != null) {
                    UiKit.alert(ctx, "解出失败：" + fErr);
                } else if (fOut == null) {
                    UiKit.toast(ctx, "该条目不是文件");
                } else if (isTextFile(fOut.getName())) {
                    openTextEditor(fOut);
                } else {
                    openFile(fOut);
                }
            });
        }, "HyVqZipEntry").start();
    }

    /** 检测 ZIP 条目是否加密。
     *  ZipEntry 无公开 API；GPBF bit0 = 加密。旧实现用反射读 flag 字段，
     *  JDK9+ 模块化后抛 InaccessibleObjectException 被静默吞掉，永远返回 false。
     *  这里先试反射，失败则用 unverifiable 兜底不可靠，故直接按"无法判定=未加密"处理但记日志。*/
    /**
     * 解析 ZIP 中央目录，返回「条目名 → 是否加密」。
     * <p>不用反射读 {@code ZipEntry.flag}：JDK9+ 模块化下 {@code setAccessible} 抛
     * {@code InaccessibleObjectException}，异常被吞掉后永远返回 false —— 这正是旧版本
     * 「加密包永远被判为不加密」的根因。改为直接读中央目录记录的通用用途标志位(bit0)。</p>
     * <p>格式：中央目录项签名 0x02014b50，偏移 +8 是 general purpose bit flag(2字节,LE)。</p>
     */
    private static java.util.Map<String, Boolean> readZipEncryptionMap(File zip) {
        java.util.Map<String, Boolean> out = new java.util.HashMap<>();
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(zip, "r");
            long len = raf.length();
            // 找 EOCD（0x06054b50），从尾部最多回退 64KB + 22
            int maxBack = (int) Math.min(len, 65557);
            byte[] tail = new byte[maxBack];
            raf.seek(len - maxBack);
            raf.readFully(tail);
            int eocd = -1;
            for (int i = maxBack - 22; i >= 0; i--) {
                if ((tail[i] & 0xFF) == 0x50 && (tail[i + 1] & 0xFF) == 0x4B
                        && (tail[i + 2] & 0xFF) == 0x05 && (tail[i + 3] & 0xFF) == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return out;
            long cdOffset = readU32(tail, eocd + 16);
            long cdSize = readU32(tail, eocd + 12);
            long entries = readU16(tail, eocd + 10);
            raf.seek(cdOffset);
            byte[] hdr = new byte[46];
            for (long k = 0; k < entries; k++) {
                if (raf.getFilePointer() + 46 > cdOffset + cdSize) break;
                raf.readFully(hdr);
                if (readU32(hdr, 0) != 0x02014b50L) break;
                int flags = readU16(hdr, 8);
                int nameLen = readU16(hdr, 28);
                int extraLen = readU16(hdr, 30);
                int commentLen = readU16(hdr, 32);
                byte[] nameBuf = new byte[nameLen];
                raf.readFully(nameBuf);
                String name = new String(nameBuf, "UTF-8");
                out.put(name, (flags & 1) != 0);
                raf.seek(raf.getFilePointer() + extraLen + commentLen);
            }
        } catch (Throwable ignored) {
            // 解析失败则返回已解析部分；调用方按「未加密」兜底
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }
        return out;
    }

    private static long readU32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF))
                | ((long) (b[off + 1] & 0xFF) << 8)
                | ((long) (b[off + 2] & 0xFF) << 16)
                | ((long) (b[off + 3] & 0xFF) << 24);
    }

    private static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private void loadTrash(Pane p) {
        p.entries.clear();
        File trash = new File(TRASH_DIR);
        if (!trash.exists()) {
            p.lastResult = AccessResult.empty("回收站为空");
            return;
        }
        collectTrash(trash, p.entries);
        p.lastResult = AccessResult.empty("回收站");
    }

    /**
     * 收集回收站条目。
     *
     * <p>旧实现递归到叶子，把目录结构拍平——用户看到一堆散文件，
     * 不知道它们原本属于哪个文件夹，空目录更是完全消失。
     * 现在<b>只取顶层条目</b>，目录原样保留（点进去再展开）。</p>
     */
    private void collectTrash(File dir, List<FileEntry> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (isTrashMeta(f)) continue;   // 元数据不是用户可恢复的条目
            if (f.isDirectory()) {
                out.add(FileEntry.dir(f.getAbsolutePath(), f.lastModified()));
            } else {
                out.add(FileEntry.file(f.getAbsolutePath(), f.length(), f.lastModified()));
            }
        }
    }

    /**
     * 回收站自动清理。
     *
     * <p>启动时对比回收站内每个文件的「记录时间」（lastModified，即移入时刻）
     * 与保留天数，超过则物理删除。trashKeepDays == 0 表示不清理。</p>
     *
     * @return 本次清理的文件数
     */
    private int purgeExpiredTrash() {
        if (!trashEnabled || trashKeepDays <= 0) return 0;
        File trash = new File(TRASH_DIR);
        if (!trash.exists()) return 0;
        long deadline = System.currentTimeMillis()
                - trashKeepDays * 24L * 60L * 60L * 1000L;
        int removed = purgeExpiredIn(trash, deadline);
        if (removed > 0) {
            android.util.Log.i("HYTrash", "auto purged " + removed
                    + " items older than " + trashKeepDays + " days");
        }
        return removed;
    }

    /** 递归清理过期文件；顺带清掉变空的目录 */
    /**
     * 清理回收站里的过期条目。
     *
     * <p><b>只按顶层条目判过期，不递归进目录内部。</b></p>
     *
     * <p>旧实现递归遍历所有层级，对**每个文件**按它自己的 mtime 判过期。
     * 但 moveToTrash 只对顶层条目 setLastModified(now)，目录内部的子文件
     * 仍是原始 mtime → 删一个 2024 年的目录（内含旧照片）进回收站后，
     * 顶层目录不会过期，**内部旧照片却全部满足条件，下次启动被逐个静默删除**，
     * 留下空壳目录。用户以为还能恢复，实际内容已丢。</p>
     *
     * <p>改为：只看 dir 的直接子项，命中就整棵树删。</p>
     */
    private int purgeExpiredIn(File dir, long deadline) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            // 顶层条目才代表一次「删除操作」，其 mtime 已在 moveToTrash 里设为移入时间
            if (f.lastModified() >= deadline) continue;
            n += (int) countTree(f);
            if (f.isDirectory()) {
                deleteTree(f);
            } else if (f.delete()) {
                // 单个文件已删
            }
        }
        return n;
    }

    /** 统计一棵树的条目数（含目录自身） */
    private static long countTree(File f) {
        if (f == null || !f.exists()) return 0;
        if (!f.isDirectory()) return 1;
        File[] items = f.listFiles();
        if (items == null) return 1;
        long n = 1;
        for (File c : items) n += countTree(c);
        return n;
    }


    // ── 回收站元数据：记录原始绝对路径，避免重名加后缀后反推失准 ──

    private static final String TRASH_META_SUFFIX = ".trashmeta";

    /** 写 meta：同级文件 `<条目名>.trashmeta`，内容为原始绝对路径 */
    private static void writeTrashMeta(File entry, String originalPath) {
        try {
            File meta = new File(entry.getParentFile(),
                    entry.getName() + TRASH_META_SUFFIX);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(meta)) {
                fos.write(originalPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 删除某条目对应的 .trashmeta（不存在则静默） */
    private static void deleteTrashMeta(File entry) {
        try {
            File meta = new File(entry.getParentFile(),
                    entry.getName() + TRASH_META_SUFFIX);
            if (meta.exists()) //noinspection ResultOfMethodCallIgnored
                meta.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 读 meta；无 meta 时返回 null（旧格式条目，走相对路径反推） */
    private static String readTrashMeta(File entry) {
        try {
            File meta = new File(entry.getParentFile(),
                    entry.getName() + TRASH_META_SUFFIX);
            if (!meta.isFile() || meta.length() > 8192) return null;
            byte[] b = new byte[(int) meta.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(meta)) {
                int off = 0;
                while (off < b.length) {
                    int r = fis.read(b, off, b.length - off);
                    if (r <= 0) break;
                    off += r;
                }
                String p = new String(b, 0, off, java.nio.charset.StandardCharsets.UTF_8).trim();
                return p.isEmpty() ? null : p;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** meta 文件本身不该出现在回收站列表里 */
    private static boolean isTrashMeta(File f) {
        return f.getName().endsWith(TRASH_META_SUFFIX);
    }

    /** 移入回收站。
     *
     * <p><b>路径设计</b>：回收站内布局为 {@code .HyVqTrash/<相对外部存储的路径>}，
     * 例如 {@code /sdcard/DCIM/a.jpg} → {@code .HyVqTrash/DCIM/a.jpg}。
     * 旧实现用 {@code TRASH_DIR + 绝对路径}，会在中间多插一层
     * {@code /storage/emulated/0}，结构混乱且与 {@code /sdcard} 软链不同源。</p>
     *
     * <p><b>同名冲突</b>：追加 {@code ~<时间戳>} 后缀，并在同级写
     * {@code <名>.trashmeta}（内容为原始绝对路径），
     * 这样恢复时不必再靠路径反推——反推在加过后缀后必然错。</p>
     */
    private boolean moveToTrash(File f) {
        try {
            // 相对外部存储根：/sdcard/DCIM/a.jpg → DCIM/a.jpg
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            String abs = f.getAbsolutePath();
            String rel = abs.startsWith(root + "/")
                    ? abs.substring(root.length() + 1)
                    : f.getName();   // 不在外部存储下（/data 等）→ 只存文件名
            String target = TRASH_DIR + "/" + rel;
            File dest = new File(target);
            File pd = dest.getParentFile();
            if (pd != null) //noinspection ResultOfMethodCallIgnored
                pd.mkdirs();
            if (dest.exists()) {
                dest = new File(target + "~" + System.currentTimeMillis());
            }
            boolean moved;
            if (f.renameTo(dest)) {
                moved = true;
            } else {
                // 跨分区：复制 + 删源。
                //
                // 两个必须：
                // a) copy 不完整就抛异常（FileOps.copyRecursive 对 listFiles()==null
                //    已改为抛 IOException），异常时**绝不执行 deleteTree** ——
                //    旧判据 dest.exists() 只证明路径有东西，半截文件也满足。
                // b) 异常时**必须清掉半截目标**。否则回收站里留下一个残缺的
                //    目录/文件，且因为 writeTrashMeta 在下面才执行，
                //    它没有 .trashmeta → loadTrash 会把它当正常条目展示，
                //    用户「恢复」得到的是残缺数据，比直接失败更糟。
                try {
                    FileOps.copy(f, dest);
                } catch (Throwable copyErr) {
                    try {
                        deleteTree(dest);
                    } catch (Throwable ignored) {
                    }
                    throw copyErr;
                }
                deleteTree(f);
                moved = true;
            }
            if (moved) {
                // renameTo 与 copy 都保留**原文件** mtime，purgeExpiredTrash
                // 按 mtime 判过期 → 删一张旧照片会立刻被清理。
                //noinspection ResultOfMethodCallIgnored
                dest.setLastModified(System.currentTimeMillis());
                // 记录原始绝对路径：恢复时直接读，不再靠相对路径反推
                writeTrashMeta(dest, abs);
            }
            return moved;
        } catch (Throwable t) {
            android.util.Log.w("HYTrash", "moveToTrash failed: " + f, t);
            return false;
        }
    }


    /**
     * 旧格式回收站路径反推（无 .trashmeta 的条目）。
     *
     * <p>旧实现把 {@code TRASH_DIR + 绝对路径} 作为存放位置，所以条目形如
     * {@code .HyVqTrash/storage/emulated/0/DCIM/a.jpg}。
     * 新实现是 {@code .HyVqTrash/DCIM/a.jpg}。这里两者都要认。</p>
     */
    private static String legacyOrigPath(String trashAbsPath) {
        String rel = trashAbsPath.substring(TRASH_DIR.length());
        if (rel.startsWith("/")) rel = rel.substring(1);
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        // 旧格式：相对路径以 "storage/emulated/0/" 开头 → 前面补 /
        if (rel.startsWith("storage/") || rel.startsWith("sdcard/")) {
            return "/" + rel;
        }
        // 新格式或 /data 下的条目
        return root + "/" + rel;
    }

    /** 从回收站恢复：trash 路径去掉 TRASH_DIR 前缀即原绝对路径；父目录重建；重名自动 (1) */
    private void restoreFromTrash(final FileEntry e) {
        final File f = AccessRouter.fileOf(e);
        if (!e.path.startsWith(TRASH_DIR)) {
            UiKit.toast(ctx, "仅回收站内条目可恢复");
            return;
        }
        // 优先读 .trashmeta 里记的原始绝对路径；
        // 旧格式条目（无 meta）才回退到「相对路径反推」
        String fromMeta = readTrashMeta(f);
        final String orig = fromMeta != null
                ? fromMeta
                : legacyOrigPath(e.path);
        UiKit.toast(ctx, "恢复中…");
        runTask(() -> {
            boolean ok = false;
            String destPath = orig;
            try {
                File origFile = new File(orig);
                File pd = origFile.getParentFile();
                if (pd != null) pd.mkdirs();
                File dest = origFile;
                if (dest.exists()) {
                    dest = new File(uniqueName(pd == null ? "/" : pd.getAbsolutePath(),
                            origFile.getName(), null));
                }
                destPath = dest.getAbsolutePath();
                if (!f.renameTo(dest)) {
                    FileOps.copy(f, dest);
                    deleteTree(f);
                }
                ok = true;
            } catch (Throwable ignored) {
            }
            final boolean fOk = ok;
            final String fDest = destPath;
            handler.post(() -> {
                UiKit.toast(ctx, fOk ? "已恢复到：" + fDest : "恢复失败");
                reloadAll();
            });
        });
    }

    // ── 书签（⋮菜单入口；SharedPreferences 持久化路径列表，参考 Amaze database） ──

    private void addBookmark() {
        addBookmarkPath(active.path);
    }

    /** 收藏指定条目路径：目录收藏自身，文件收藏父目录；仅真实路径支持 */
    private void addBookmarkPath(String path) {
        if (path == null || path.startsWith(PREFIX_TRASH)
                || path.startsWith("content://")) {
            UiKit.alert(ctx, "该条目不能收藏");
            return;
        }
        File f = new File(path);
        String target = f.isDirectory() ? f.getAbsolutePath() : f.getParent();
        if (target == null) {
            UiKit.alert(ctx, "无法收藏");
            return;
        }
        android.content.SharedPreferences sp = fmPrefs();
        java.util.Set<String> set = new java.util.LinkedHashSet<>(
                sp.getStringSet("bookmarks", new java.util.HashSet<String>()));
        if (set.contains(target)) {
            UiKit.toast(ctx, "已在书签中");
            return;
        }
        set.add(target);
        sp.edit().putStringSet("bookmarks", set).commit();
        UiKit.toast(ctx, "已收藏：" + target);
    }

    /** 书签列表对话框：点击进入、长按删除 */
    /**
     * 存储分析：扫描当前目录树，按一级子目录统计占用与文件数。
     * 后台线程扫描，UI 线程刷新。
     */
    private void showStorageAnalysis() {
        final Pane p = active != null ? active : paneL;
        final File rootDir = new File(p.path);
        if (!rootDir.isDirectory()) {
            UiKit.toast(ctx, "当前路径不可用");
            return;
        }
        final android.app.Dialog progress = UiKit.glassDialog(ctx, buildProgressView("正在分析存储…"));
        progress.show();

        new Thread(() -> {
            final java.util.List<String[]> results = new java.util.ArrayList<>(); // [name, sizeStr, countStr]
            long[] total = {0};
            try {
                File[] children = rootDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        long size = dirSize(child);
                        int count = countFiles(child);
                        total[0] += size;
                        results.add(new String[]{child.getName(), FileOps.formatSize(size), String.valueOf(count),
                                child.isDirectory() ? "📁" : "📄"});
                    }
                }
            } catch (Throwable ignored) {
            }
            // 按大小降序
            results.sort((a, b) -> Long.compare(parseSize(b[1]), parseSize(a[1])));

            handler.post(() -> {
                progress.dismiss();
                showStorageResult(rootDir.getAbsolutePath(), total[0], results);
            });
        }, "storage-analysis").start();
    }

    private View buildProgressView(String text) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(tv);
        return box;
    }

    /** 递归统计目录大小 */
    private long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) total += dirSize(c);
        }
        return total;
    }

    /** 递归统计文件数 */
    private int countFiles(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return 1;
        int count = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) count += countFiles(c);
        }
        return count;
    }

    /** 解析 "1.5 GB" 格式为字节数（排序用） */
    private long parseSize(String s) {
        try {
            String[] parts = s.split(" ");
            double v = Double.parseDouble(parts[0]);
            String unit = parts.length > 1 ? parts[1].toUpperCase() : "B";
            switch (unit) {
                case "KB": return (long)(v * 1024);
                case "MB": return (long)(v * 1024 * 1024);
                case "GB": return (long)(v * 1024 * 1024 * 1024);
                case "TB": return (long)(v * 1024L * 1024 * 1024 * 1024);
                default: return (long) v;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 存储分析结果弹窗 */
    private void showStorageResult(String path, long totalSize, java.util.List<String[]> items) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "存储分析"));

        TextView meta = new TextView(ctx);
        meta.setText(path + "\n合计 " + FileOps.formatSize(totalSize) + " · " + items.size() + " 项");
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        meta.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(meta);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        box.addView(sv, new LinearLayout.LayoutParams(-1, dp(400)));

        for (String[] item : items) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));

            TextView icon = new TextView(ctx);
            icon.setText(item[3]);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.addView(icon);

            TextView name = new TextView(ctx);
            name.setText(item[0]);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(dp(8), 0, 0, 0);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            TextView size = new TextView(ctx);
            size.setText(item[1]);
            size.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            size.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorPrimary));
            size.setPadding(dp(4), 0, 0, 0);
            row.addView(size);

            TextView count = new TextView(ctx);
            count.setText(item[2] + " 个");
            count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            count.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            count.setPadding(dp(8), 0, 0, 0);
            row.addView(count);

            col.addView(row);
        }

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        d.show();
    }

    /** 最近打开的文件列表（最多 20 条） */
    private void showRecentFiles() {
        if (recentFiles.isEmpty()) {
            UiKit.toast(ctx, "暂无最近打开的文件");
            return;
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "最近打开的文件"));
        ScrollView sv = new ScrollView(ctx);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        box.addView(sv, new LinearLayout.LayoutParams(-1, dp(360)));

        for (final String path : recentFiles) {
            File f = new File(path);
            boolean exists = f.exists();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView icon = new TextView(ctx);
            icon.setText(exists ? "📄" : "❌");
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding(dp(10), 0, 0, 0);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(ctx);
            name.setText(f.getName());
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(name);

            TextView pathView = new TextView(ctx);
            pathView.setText(path);
            pathView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pathView.setMaxLines(1);
            pathView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            pathView.setTextColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            textCol.addView(pathView);

            row.addView(textCol);
            row.setOnClickListener(v -> {
                if (exists) {
                    // 跳转到文件所在目录
                    File parent = f.getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        Pane target = active != null ? active : paneL;
                        target.path = parent.getAbsolutePath();
                        reload(target);
                    }
                }
            });
            col.addView(row);
        }

        final android.app.Dialog d = UiKit.glassDialog(ctx, box);
        d.show();
    }

    private void showBookmarks() {
        final android.content.SharedPreferences sp = fmPrefs();
        final java.util.Set<String> set = new java.util.LinkedHashSet<>(
                sp.getStringSet("bookmarks", new java.util.HashSet<String>()));
        if (set.isEmpty()) {
            UiKit.toast(ctx, "暂无书签（⋮菜单「收藏当前目录」可添加）");
            return;
        }
        final List<String> paths = new ArrayList<>(set);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "书签（" + paths.size() + "）"));
        final ListView lv = new ListView(ctx);
        lv.setAdapter(textListAdapter(paths, null));
        box.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);
        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("关闭", v -> dialog.dismiss()));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            dialog.dismiss();
            active.navPrev = active.path;
            active.path = paths.get(pos);
            reload(active);
        });
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            set.remove(paths.get(pos));
            sp.edit().putStringSet("bookmarks", set).commit();
            UiKit.toast(ctx, "已删除书签");
            dialog.dismiss();
            showBookmarks();
            return true;
        });
        dialog.show();
    }

    // ── 网络模式：极简 FTP 服务器（左上角入口；参考 Ghost Commander 网络能力方向；
    /** 人类可读的字节数（B/KB/MB/GB）。原在 FTP 块内，删网络功能后仍被占用率条使用 */
    private boolean checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    // ── 工具 ──

    private int countChildren(FileEntry e) {
        if (SafStrategy.isSafUri(e.path)) return SafStrategy.childCount(ctx, e.path);
        File[] list = AccessRouter.fileOf(e).listFiles();
        return list == null ? 0 : list.length;
    }

    /**
     * 递归删除。
     *
     * <p><b>必须不跟随符号链接</b>。旧实现用 {@code f.isDirectory()}，
     * 它对符号链接会跟随目标判断 —— 若存在 {@code /sdcard/a -> /sdcard}
     * 这种环（Termux / root 操作后很常见），递归会进入自身：
     *   ① 深度递归直到 StackOverflowError；
     *   ② 更严重的是，溢出前它已经**真的删掉了上层的文件**。
     *
     * <p>现在显式判断：符号链接只删链接本身（delete 一个 symlink 不会碰目标），
     * 不递归进去。用 {@code Files.isSymbolicLink} 而不是 canRead/isFile，
     * 因为后者同样会跟随。</p>
     *
     * <p>另外把栈递归改成显式栈：深目录（几千层）不会 StackOverflow。</p>
     */
    private void deleteTree(File f) {
        if (f == null) return;
        // 非目录（含符号链接）直接删。delete() 对 symlink 只移除链接，不动目标。
        if (!f.isDirectory() || isSymlink(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        // 显式栈，避免深目录栈溢出
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        java.util.ArrayList<File> dirs = new java.util.ArrayList<>();
        stack.push(f);
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            if (isSymlink(cur)) {
                //noinspection ResultOfMethodCallIgnored
                cur.delete();
                continue;
            }
            if (cur.isDirectory()) {
                dirs.add(cur);           // 目录最后删（子项先删）
                File[] ch = cur.listFiles();
                if (ch != null) {
                    for (File c : ch) stack.push(c);
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                cur.delete();
            }
        }
        // 逆序删目录：先深后浅
        for (int i = dirs.size() - 1; i >= 0; i--) {
            //noinspection ResultOfMethodCallIgnored
            dirs.get(i).delete();
        }
    }

    /**
     * 是否是符号链接本身（不跟随）。
     *
     * <p>Android 上 {@code Files.isSymbolicLink} 需要 API 26；低版本用
     * {@code getCanonicalPath != getAbsolutePath} 兜底 —— 不完全等价
     * （路径中任意一段是链接都会判为 true），但方向是安全的一侧（少删不多删）。</p>
     */
    private static boolean isSymlink(File f) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                return java.nio.file.Files.isSymbolicLink(f.toPath());
            }
            File p = f.getParentFile();
            File canon = p == null ? f : new File(p.getCanonicalFile(), f.getName());
            return !canon.getCanonicalPath().equals(canon.getAbsolutePath());
        } catch (Throwable t) {
            // 判定失败时保守：当作普通目录/文件处理（保持旧行为）
            return false;
        }
    }

    private ImageView toolIcon(int res, View.OnClickListener onClick) {
        ImageView iv = new ImageView(ctx);
        // 视觉：图标内容 22dp；判定：容器 48dp —— Material 建议最小触摸目标 48dp。
        // 原来是 36dp 且上下各 -3dp 负边距，实际可点区约 30dp，
        // 顶栏一排图标挤在一起时很容易点错。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMarginStart(dp(1));
        iv.setLayoutParams(lp);
        iv.setImageResource(res);
        // 上下左右各 7dp 内边距 → 可见图标 36-14 = 22dp
        iv.setPadding(dp(7), dp(7), dp(7), dp(7));
        iv.setColorFilter(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        iv.setOnClickListener(onClick);
        return iv;
    }

    private TextView textButton(String text, View.OnClickListener onClick) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary));
        tv.setGravity(Gravity.CENTER);
        int pad = dp(12);
        tv.setPadding(pad, pad, pad, pad);
        tv.setOnClickListener(onClick);
        return tv;
    }

    /**
     * 按当前 statusBarAlpha 重设系统状态栏底色。
     *
     * <p>0% = 完全透明，背景图直接铺到状态栏后面；
     * 100% = 实心 colorSurfaceContainer。</p>
     *
     * <p><b>为什么用带 alpha 的主题色而不是直接改 View</b>：
     * 状态栏是系统窗口的一部分，应用只能通过 {@code setStatusBarColor}
     * 提交一个 ARGB 值。alpha 分量由系统负责与底下的内容混合。</p>
     *
     * <p>图标明暗也跟着底色亮度走：底色越不透明越接近主题色，
     * 此时按主题亮度决定；底色透明时底下的背景图通常偏暗，
     * 用浅色图标（不设 LIGHT_STATUS_BAR）更稳。</p>
     */
    private void applyStatusBarAlpha() {
        try {
            if (hostActivity == null) return;
            android.view.Window win = hostActivity.getWindow();
            if (win == null) return;

            int base = UiKit.color(ctx,
                    com.google.android.material.R.attr.colorSurfaceContainer);
            int a = Math.round(255f * statusBarAlpha / 100f);
            win.setStatusBarColor((base & 0x00FFFFFF) | (a << 24));

            androidx.core.view.WindowInsetsControllerCompat ctl =
                    androidx.core.view.WindowCompat.getInsetsController(
                            win, win.getDecorView());
            if (ctl != null) {
                boolean nightMode = (ctx.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                boolean lightBars = !nightMode;
                // 底色几乎透明时，底图多为照片，浅色图标（lightBars=false）对比度更好
                if (statusBarAlpha < 30) lightBars = false;
                ctl.setAppearanceLightStatusBars(lightBars);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 系统状态栏高度（像素）。
     *
     * <p>用 {@code WindowInsets} 拿不到时回退到资源尺寸。两条路都要试：
     * 部分 ROM 的 {@code status_bar_height} 在深色/刘海屏下与实际不符，
     * 而 insets 又不总能从非 attach 状态的 View 取到。</p>
     */
    private int statusBarHeight() {
        // ① 首选 WindowInsets（最准，含刘海/挖孔的真实高度）
        try {
            if (hostActivity != null) {
                androidx.core.view.WindowInsetsCompat ins =
                        androidx.core.view.ViewCompat.getRootWindowInsets(
                                hostActivity.getWindow().getDecorView());
                if (ins != null) {
                    int h = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                    if (h > 0) {
                        android.util.Log.i("HYInset", "statusBar from insets = " + h);
                        return h;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // ② 回退资源值。⚠️ 这个值在 ColorOS / 挖孔屏上经常偏小
        //    （Android 框架默认 24dp，而实际状态栏可能是 47dp），
        //    所以它不是「正确答案」，只是「没有 insets 时的最接近猜测」。
        //    真正的高度会在 onAttachedToWindow 后由 applyStatusBarInset() 修正。
        try {
            int id = ctx.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (id > 0) {
                int h = ctx.getResources().getDimensionPixelSize(id);
                android.util.Log.w("HYInset", "statusBar from resource = " + h
                        + " (unreliable on ColorOS)");
                return h;
            }
        } catch (Throwable ignored) {
        }
        int h = dp(24);
        android.util.Log.w("HYInset", "statusBar fallback dp(24) = " + h);
        return h;
    }

    /**
     * 重算并应用状态栏 inset（body + navOverlay）。
     *
     * <p><b>为什么需要二次调用</b>：{@link #createMainView} 里取
     * {@link #statusBarHeight()} 时，DecorView 往往还没 attach 到窗口，
     * {@code getRootWindowInsets()} 返回 null → 回退到资源值。
     * ColorOS 的 {@code status_bar_height} 是框架默认的 24dp，
     * 而实际状态栏约 47dp —— 差这 23dp 就是「顶栏与状态栏有一点重叠」。</p>
     *
     * <p>所以在 body attach 到窗口后再调一次，此时 insets 一定可用。</p>
     */
    private void applyStatusBarInset() {
        try {
            if (bodyRef == null || !bodyRef.isAttachedToWindow()) return;
            int sb = statusBarHeight();
            int baseTop = dp(4);
            if (bodyRef.getPaddingTop() != baseTop + sb) {
                bodyRef.setPadding(bodyRef.getPaddingLeft(), baseTop + sb,
                        bodyRef.getPaddingRight(), bodyRef.getPaddingBottom());
                android.util.Log.i("HYInset", "body paddingTop -> " + (baseTop + sb));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按**当前可见的**底部栏实际高度，更新列表底部预留。
     *
     * <p><b>为什么不能写死</b>：底部有两种栏，高度差很大——
     * 底部导航栏固定 48dp（+8dp 边距 = 56dp），
     * 多选栏 opBar 是内容撑的：「已选 N 项」一行 + 动作按钮格
     * （图标 22 + 文字 ≈ 44dp）+ 上下 padding 16dp，约 100dp。
     * 原先两者共用 BOTTOM_NAV_RESERVE = 62dp，多选时必然遮住最后几行。</p>
     *
     * <p>这里直接量可见栏的 {@code getHeight()}（布局完成后才有值），
     * 没布局完就退回常量估算。两栏互斥显示，所以取「可见的那个」即可。</p>
     */
    private void updateListBottomReserve() {
        int reserve = dp(BOTTOM_NAV_RESERVE);   // 兜底
        try {
            View visible = null;
            if (opBar != null && opBar.getVisibility() == View.VISIBLE) {
                visible = opBar;
            } else if (bottomNav != null && bottomNav.getVisibility() == View.VISIBLE) {
                visible = bottomNav;
            }
            if (visible != null && visible.getHeight() > 0) {
                // 栏本体高度 + 底部边距 8dp + 呼吸 8dp
                reserve = visible.getHeight() + dp(8) + dp(8);
            } else if (visible == opBar) {
                reserve = dp(110);   // opBar 还没布局：按典型高度估
            }
        } catch (Throwable ignored) {
        }
        final int r = reserve;
        for (Pane pp : panes) {
            if (pp == null || pp.list == null) continue;
            if (pp.list.getPaddingBottom() != r) {
                pp.list.setPadding(pp.list.getPaddingLeft(), pp.list.getPaddingTop(),
                        pp.list.getPaddingRight(), r);
            }
        }
    }

    private int dp(int v) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
}