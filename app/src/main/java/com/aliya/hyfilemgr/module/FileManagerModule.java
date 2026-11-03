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
import java.net.Socket;
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
    /** 编辑器设置持久化 key：字号 / 等宽 / Tab宽度 / 主题（0跟随系统 1浅色 2深色） */
    private static final String EDITOR_PREF_FONT_SIZE = "editor_font_size";
    private static final String EDITOR_PREF_MONO = "editor_mono";
    private static final String EDITOR_PREF_TAB_W = "editor_tab_w";
    private static final String EDITOR_PREF_THEME = "editor_theme";
    // ── 浏览状态持久化键（2026-09-06 补齐：排序/隐藏文件/双窗格路径原为纯内存，重启即回默认） ──
    private static final String PREF_SORT_MODE = "sort_mode";
    private static final String PREF_SHOW_HIDDEN = "show_hidden";
    private static final String PREF_DPI = "ui_dpi";
    private static final String PREF_DENSITY = "list_density";
    /** 界面主题：0=默认，1=工业风格 */
    private static final String PREF_UI_THEME = "ui_theme";
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
    private boolean thumbsEnabled = true;
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
    private boolean staggerPending = true;

    private static final class Pane {
        String path;
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
    }

    @Override
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
        listDensity = Math.max(1, Math.min(3, sp.getInt(PREF_DENSITY, 2)));
        uiTheme = Math.max(0, Math.min(1, sp.getInt(PREF_UI_THEME, 0)));
        uiMode = Math.max(0, Math.min(2, sp.getInt(PREF_UI_MODE, 0)));
        navLocalExpanded = sp.getBoolean(PREF_NAV_LOCAL_OPEN, true);
        navToolsExpanded = sp.getBoolean(PREF_NAV_TOOLS_OPEN, true);
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
                        .putBoolean(PREF_GRID_MODE, fGrid)
                        .putInt(PREF_SORT_MODE, fSort)
                        .putInt(PREF_DENSITY, fDensity)
                        .putInt(PREF_UI_THEME, fTheme)
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

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(8), dp(4), dp(8), dp(4)); // 紧凑化：原 dp(10)/dp(8)
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        navLp.bottomMargin = dp(8);
        navLp.leftMargin = dp(8);
        navLp.rightMargin = dp(8);
        contentWrap.addView(nav, navLp);

        rootContainer.addView(body);

        // ⭐10 FAB 悬浮新建（先于侧边栏叠加层加入：侧边栏打开时被遮罩覆盖，符合 Material 规范）
        buildFab();
        buildNavOverlay();

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
        int panelFill = UiKit.color(ctx,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        // 92% 不透明，保留一点磨砂感又不牺牲可读性
        panelBg.setColor((panelFill & 0x00FFFFFF) | 0xEB000000);
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
        int bg = UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainer);
        bar.setBackground(UiKit.rounded(ctx, 14, bg, 0));
        int pad = dp(3);
        bar.setPadding(pad, pad, pad, pad);

        // 左上角：文件模式显示汉堡（打开侧边栏），终端/设置模式显示返回（回文件列表）
        topBarLeftIcon = toolIcon(R.drawable.ic_menu, v -> {
            if (contentMode == ContentMode.FILES) {
                showNavSidebar();
            } else {
                // 设置模式：返回文件列表
                closeSettingsEmbed();
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
        rv.setPadding(0, dp(2), 0, 0);

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
        p.adapter.setDensity(listDensity);
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
    private ImageView syncLeftIv, syncRightIv;
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
        bottomNav.setPadding(pad, dp(1), pad, dp(1));
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
                dp(44), ViewGroup.LayoutParams.WRAP_CONTENT);
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
        iv.setPadding(dp(3), dp(2), dp(3), dp(2));
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
    private LinearLayout buildSyncButton() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);   // 上下堆叠
        box.setGravity(Gravity.CENTER);
        // 蓝色包裹：圆角 + 蓝色描边
        int stroke = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary);
        int fill = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimaryContainer);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(10));
        gd.setColor((fill & 0x00FFFFFF) | 0x33000000);
        gd.setStroke(Math.round(dp(1) * 1.5f), stroke);
        box.setBackground(gd);
        box.setPadding(0, 0, 0, 0);

        // 上：← 靠左（右栏焦点时亮，点击 右→左）
        syncLeftIv = new ImageView(ctx);
        syncLeftIv.setImageResource(R.drawable.ic_sync_arrow_back);
        syncLeftIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        syncLeftIv.setPadding(dp(3), dp(1), dp(3), dp(1));
        syncLeftIv.setClickable(true);
        syncLeftIv.setBackgroundResource(R.drawable.bg_ripple_round);
        syncLeftIv.setOnClickListener(v -> syncByFocus());
        syncLeftIv.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).start();
            else if (ev.getAction() == android.view.MotionEvent.ACTION_UP || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
            return false;
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(32), dp(16));
        llp.gravity = Gravity.START;          // 左对齐 → 形成交错
        box.addView(syncLeftIv, llp);

        // 下：→ 靠右（左栏焦点时亮，点击 左→右）
        syncRightIv = new ImageView(ctx);
        syncRightIv.setImageResource(R.drawable.ic_sync_arrow_fwd);
        syncRightIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        syncRightIv.setPadding(dp(3), dp(1), dp(3), dp(1));
        syncRightIv.setClickable(true);
        syncRightIv.setBackgroundResource(R.drawable.bg_ripple_round);
        syncRightIv.setOnClickListener(v -> syncByFocus());
        syncRightIv.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).start();
            else if (ev.getAction() == android.view.MotionEvent.ACTION_UP || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
            return false;
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(32), dp(16));
        rlp.gravity = Gravity.END;            // 右对齐 → 形成交错
        box.addView(syncRightIv, rlp);

        return box;
    }

    /** 刷新同步按钮的高亮：左焦点→右箭头亮；右焦点→左箭头亮 */
    private void refreshSyncButton() {
        if (syncLeftIv == null || syncRightIv == null) return;
        int on = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary);
        int off = UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant);
        boolean leftActive = (active == paneL);
        syncLeftIv.setColorFilter(leftActive ? off : on);    // 右栏焦点时 ← 亮
        syncRightIv.setColorFilter(leftActive ? on : off);   // 左栏焦点时 → 亮
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
                        File tf = new File(e.path);
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
        staggerPending = false;      // 翻目录不是「新建 surface」，不播入场动画
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
        staggerPending = false;
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
        staggerPending = false;
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
        File f = new File(e.path);
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
        // 4. 内嵌设置页 → 回文件列表
        if (contentMode == ContentMode.SETTINGS) {
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
        if (p == active) exitMultiMode();

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
            // ⭐ v2.8.0 root 路径禁用目录缓存：root 目录在 App 侧的 lastModified()/
            // listFiles() 恒为 0/null，指纹永远相同 → 目录内容变了仍会显示旧快照
            if (!p.path.startsWith("content://") && !needRoot(p.path)) {
                DirSnapshot snap = p.dirCache.get(p.path);
                File dirF = new File(p.path);
                long mt = dirF.lastModified();
                int cc = 0;
                File[] kids = dirF.listFiles();
                if (kids != null) cc = kids.length;
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
                    p.dirCache.put(p.path, new DirSnapshot(
                            new ArrayList<>(p.entries), dirF.lastModified(),
                            dirF.listFiles() == null ? 0 : dirF.listFiles().length));
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
        if (staggerPending && !p.entries.isEmpty()) {
            staggerPending = false;   // 全局只消费一次
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
                if (real && isTextFile(e.name)) openTextEditor(new File(e.path));
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
        multiMode = false;
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
        card.setBackgroundResource(R.drawable.bg_menu_card);
        if (!enabled) {
            card.setAlpha(0.35f);
        } else {
            card.setOnClickListener(v -> {
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(140)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                                .start())
                        .start();
                onClick.onClick(v);
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
            final File f = new File(path);
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
            // 模糊 body(索引0)而非 navOverlay 本身
            for (int i = 0; i < rootContainer.getChildCount(); i++) {
                View c = rootContainer.getChildAt(i);
                if (c != navOverlay) {
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
        box.addView(buildSettingsBackRow("返回文件列表"));
        box.addView(UiKit.sectionHeader(ctx, "设置"));


        // ── 权限管理 ──
        addSettingsRow(box, "权限管理",
                "所有文件访问 · 存储权限 · SAF 授权", R.drawable.ic_lock, v -> {
                    showInSettingsDialog(this::buildPermissionManager);
                });

        // ── 文件管理 ──
        addSettingsRow(box, "文件管理",
                "隐藏文件 · 缩略图 · 排序方式", R.drawable.ic_folder, v -> {
                    showInSettingsDialog(this::buildFileSettings);
                });

        // ── 个性化 ──
        addSettingsRow(box, "个性化",
                "显示密度(DPI) · 列表文件密度 · 界面主题", R.drawable.ic_sliders, v -> {
                    showInSettingsDialog(this::buildAppearanceSettings);
                });
        return box;
    }

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
        if (contentWrapRef == null) return;
        View v = contentSupplier.get();
        if (v == null) return;

        if (settingsEmbedView == null) {
            settingsEmbedView = new LinearLayout(ctx);
            settingsEmbedView.setOrientation(LinearLayout.VERTICAL);
            settingsEmbedView.setBackgroundColor(UiKit.color(ctx,
                    com.google.android.material.R.attr.colorSurface));
            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.setFillViewport(true);
            settingsEmbedContent = new LinearLayout(ctx);
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

    /** 设置页顶部的返回行（比底部的按钮更方便，符合常见设置页习惯） */
    private View buildSettingsBackRow(String label) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_ripple_round);
        row.setOnClickListener(v -> showSettingsPage());

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_back);
        icon.setColorFilter(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(6);
        row.addView(tv, lp);
        return row;
    }

    /** 打开设置主页（内嵌视图） */
    private void showSettingsPage() {
        showInSettingsDialog(this::buildSettingsRoot);
    }

    /** 薄包装：以独立弹窗打开（保持既有调用点兼容） */
    private void showPermissionManager() {
        showInSettingsDialog(this::buildPermissionManager);
    }

    private void showFileSettings() {
        showInSettingsDialog(this::buildFileSettings);
    }

    private void showAppearanceSettings() {
        showInSettingsDialog(this::buildAppearanceSettings);
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
        row.setPadding(dp(12), dp(14), dp(12), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_ripple_round);
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        parent.addView(row, rowLp);

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
        if (settingsTopTitle != null) settingsTopTitle.setText("权限管理");
        LinearLayout box = new LinearLayout(ctx);
        box.addView(buildSettingsBackRow("返回设置主页"));
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "权限管理"));

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
    private enum ContentMode { FILES, SETTINGS }

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

        if (fileContentView != null) {
            fileContentView.setVisibility(fileVisible ? View.VISIBLE : View.GONE);
        }
        if (settingsEmbedView != null) {
            settingsEmbedView.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);
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

        // 左上角图标：文件模式=汉堡，设置=返回
        if (topBarLeftIcon != null) {
            topBarLeftIcon.setImageResource(fileVisible
                    ? R.drawable.ic_menu : R.drawable.ic_back);
        }

        // 顶栏：文件 / 设置 二选一
        if (fileVisible) {
            removeSettingsTopTitle();
            if (topBarCrumbView != null) topBarCrumbView.setVisibility(View.VISIBLE);
            if (topBarFileActions != null) topBarFileActions.setVisibility(View.VISIBLE);
        } else {
            enterSettingsTopBar();
        }

        if (prev != target && fileVisible && multiMode) updateOpBar();
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
        if (settingsTopTitle != null) settingsTopTitle.setText("个性化");
        LinearLayout box = new LinearLayout(ctx);
        box.addView(buildSettingsBackRow("返回设置主页"));
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "个性化"));

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

        // ── 列表文件密度：SeekBar（3 档） ──
        final String[] densityNames = {"紧凑", "标准", "宽松"};
        final TextView densityValue = addSliderRow(box, "列表文件密度",
                densityNames[Math.max(0, Math.min(2, listDensity - 1))],
                listDensity - 1, 0, 2, 1,
                new String[]{"紧凑", "标准", "宽松"});

        final android.widget.SeekBar densityBar = lastSlider;
        densityBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                densityValue.setText(densityNames[Math.max(0, Math.min(2, progress))]);
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                applyDensity(sb.getProgress() + 1);
            }
        });
        return box;
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
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(4), dp(10), dp(4), dp(6));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = dp(4);
        parent.addView(wrap, wrapLp);

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
        if (settingsTopTitle != null) settingsTopTitle.setText("文件管理");
        LinearLayout box = new LinearLayout(ctx);
        box.addView(buildSettingsBackRow("返回设置主页"));
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "文件管理"));

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
                    UiKit.toast(ctx, thumbsEnabled ? "已开启缩略图" : "已关闭缩略图");
                });

        // ── 回收站（展开项）──
        final LinearLayout trashBox = new LinearLayout(ctx);
        trashBox.setOrientation(LinearLayout.VERTICAL);
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
        trashHeader.setPadding(dp(4), dp(10), dp(4), dp(6));
        trashHeader.setBackgroundResource(R.drawable.bg_ripple_round);
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
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_ripple_round);
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        parent.addView(row, rowLp);

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
                tvLeft.setText("已用 " + humanSize(used) + " / " + humanSize(ft));
                tvPct.setText(pct + "%");
                tvFree.setText("可用 " + humanSize(ff));

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
        toolsBody.addView(buildToolCard("文件管理",
                "隐藏文件 · 缩略图 · 排序 · 回收站",
                R.drawable.ic_folder, v -> {
                    closeNavSidebarThen(() -> {
                        showInSettingsDialog(this::buildFileSettings);
                    });
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
                            if (c != navOverlay) {
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
            active.navPrev = active.path;
            active.path = e.enterable ? e.target : e.id;
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
                FileOps.renameAsync(new File(e.path), newName, (ok, msg) -> {
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
            sb.append("项目数：").append(countChildren(e)).append('\n');
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
                long size = FileOps.dirSize(new File(e.path));
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
                for (FileEntry e : items) {
                    if (SafStrategy.isSafUri(e.path)) {
                        // SAF 授权条目：DocumentsContract.deleteDocument（无法移入回收站）
                        try {
                            if (SafStrategy.deleteDocument(ctx, e.path)) ok++;
                        } catch (Throwable ignored) {
                        }
                        continue;
                    }
                    File f = new File(e.path);
                    if (!f.exists()) continue;
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
                            if (!useTrash || !moveToTrash(f)) {
                                // 跨分区/权限失败回退物理删除（数据可能已部分移动）
                                deleteTree(f);
                            }
                        }
                        ok++;
                    } catch (Throwable ignored) {
                    }
                }
                final int fOk = ok;
                final boolean fTrash = !inTrash;
                handler.post(() -> {
                    UiKit.toast(ctx, fTrash
                            ? "已移入回收站 " + fOk + "/" + items.size() + " 项（可在侧边栏回收站恢复）"
                            : "已彻底删除 " + fOk + "/" + items.size() + " 项");
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
        if (f.length() > 2 * 1024 * 1024) {
            UiKit.toast(ctx, "文件过大（>2MB），请用其他应用打开");
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
                .setNegativeButton("放弃修改", (d, w) -> finishEditor())
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

    /** 结束编辑器会话：持久化标签路径（打开保留）并清空内存 */
    private void finishEditor() {
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
    private void saveEditorTabs() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (EditorTabState t : editorTabs) {
            arr.put(t.rootTarget != null ? "root://" + t.rootTarget : t.file.getAbsolutePath());
        }
        fmPrefs().edit().putString(EDITOR_PREF_TABS, arr.toString()).commit();
    }

    /** 打开保留：恢复上次保留的标签（跳过不存在/过大/读取失败文件） */
    private void restoreEditorTabs() {
        String json = fmPrefs().getString(EDITOR_PREF_TABS, "");
        if (json.isEmpty()) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.getString(i);
                File f = new File(p);
                if (!f.isFile() || f.length() > 2 * 1024 * 1024) continue;
                String[] rc = readTextContent(f);
                if (rc == null) continue;
                editorTabs.add(new EditorTabState(f, rc[0], rc[1]));
            }
        } catch (Throwable ignored) {
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
            if (st.size() > 200) st.remove(0); // 栈深上限
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
        while (currentEditTab.redoStack.size() > 200) {
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

    /** 正则查找：forward=true 找 region 内第一个；false 遍历取最后一个 */
    private int[] regexFind(String text, String query, int from, int to, boolean forward) {
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
                rep = java.util.regex.Pattern.compile(query)
                        .matcher(text.substring(hit[0], hit[1]))
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
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(query).matcher(text);
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
            File target = new File(path);
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
        if (f.length() <= 2 * 1024 * 1024 && isTextFile(f.getName())) {
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
            File f = new File(pathOrUri);
            if (!f.exists()) return "文件不存在或无法访问";
            if (!f.canRead()) return "没有读取该文件的权限";
        }
        openImageViewer(pathOrUri, name, isUri);
        return null;
    }

    /** 尝试用内置播放器打开。 */
    private String tryOpenWithBuiltInPlayer(String pathOrUri, String name, boolean isUri) {
        if (!isUri) {
            File f = new File(pathOrUri);
            if (!f.exists()) return "文件不存在或无法访问";
            if (!f.canRead()) return "没有读取该文件的权限";
        }
        openWithBuiltInPlayer(pathOrUri, name, isUri);
        return null;
    }

    /** 尝试用内置文本编辑器打开。 */
    private String tryOpenInEditor(String pathOrUri, String name, boolean isUri) {
        if (isUri) return "SAF 文件（content://）暂不支持内置编辑器";
        File f = new File(pathOrUri);
        if (!f.exists()) return "文件不存在或无法访问";
        if (!f.canRead()) return "没有读取该文件的权限";
        if (f.isDirectory()) return "这是一个文件夹，不能作为文本编辑";
        if (f.length() > 12L * 1024 * 1024) {
            return "文件过大（" + (f.length() / 1024 / 1024) + "MB），编辑器可能无法加载";
        }
        openInEditorByName(pathOrUri, name, false);
        return null;
    }

    /** 尝试解压（仅 zip 有效）。 */
    private String tryUnzip(String pathOrUri, String name, boolean isUri) {
        if (isUri) return "SAF 文件（content://）暂不支持直接解压，请先复制到本地";
        File f = new File(pathOrUri);
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
            File f = new File(pathOrUri);
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
            File f = new File(pathOrUri);
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
                    new File(pathOrUri));
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
                    openFile(new File(pathOrUri));
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

    /** 内置图片查看器 */
    private void openImageViewer(String pathOrUri, String name, boolean isUri) {
        try {
            // 内置图片查看器（双指缩放 / 拖动 / 双击还原 / 大图采样解码）
            Intent i = new Intent(ctx, com.aliya.hyfilemgr.ImageViewerActivity.class);
            if (isUri) {
                i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_URI, pathOrUri);
            } else {
                i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_PATH, pathOrUri);
            }
            i.putExtra(com.aliya.hyfilemgr.ImageViewerActivity.EXTRA_TITLE, name);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法打开图片查看器：" + t.getMessage());
        }
    }

    /** 用内置编辑器打开指定文件（先进入其所在目录再打开） */
    private void openInEditorByName(String pathOrUri, String name, boolean isUri) {
        try {
            File f = isUri ? null : new File(pathOrUri);
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
            File f = isUri ? null : new File(pathOrUri);
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
        try {
            Uri uri = isUri ? Uri.parse(pathOrUri)
                    : FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider",
                    new File(pathOrUri));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            UiKit.alert(ctx, "无法安装：" + t.getMessage());
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
        sb.append("大小　　").append(humanSize(apk.length())).append(System.lineSeparator());
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
        addMenuRow(panel, thumbsEnabled ? "缩略图：已开启" : "缩略图：已关闭",
                R.drawable.ic_thumbnail, v -> {
                    popup.dismiss();
                    thumbsEnabled = !thumbsEnabled;
                    savePrefBool(PREF_THUMBS, thumbsEnabled);
                    persistState();
                    for (Pane pp : panes) {
                        if (pp != null && pp.adapter != null) {
                            pp.adapter.setThumbsEnabled(thumbsEnabled);
                        }
                    }
                    UiKit.toast(ctx, thumbsEnabled ? "已开启缩略图" : "已关闭缩略图");
                });
        addMenuRow(panel, "书签列表", R.drawable.ic_bookmarks, v -> {
            popup.dismiss();
            showBookmarks();
        });
        addMenuRow(panel, "收藏当前目录", R.drawable.ic_star_add, v -> {
            popup.dismiss();
            addBookmark();
        });
        addMenuRow(panel, gridMode ? "切换到列表视图" : "切换到网格视图",
                gridMode ? R.drawable.ic_list : R.drawable.ic_grid, v -> {
                    popup.dismiss();
                    gridMode = !gridMode;
                    persistState();
                    reloadAll();
                    UiKit.toast(ctx, gridMode ? "已切换到网格视图" : "已切换到列表视图");
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
                openFile(new File(e.path));
            }
        });
        dialog.show();
    }

    // ── 压缩 / 解压（长按菜单入口；参考 Fossify ItemsAdapter；java.util.zip 零依赖） ──

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
        final File src = new File(e.path);
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
        final File archive = new File(e.path);
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

    /** 压缩包内条目列表缓存：包路径 → 条目（避免每次进子目录重扫 tar） */
    private final java.util.Map<String, java.util.List<ArchiveItem>> archiveItemsCache =
            new java.util.HashMap<>();

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
                String sub = (it.size >= 0 ? humanSize(it.size) : "—")
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
                // 跨分区：复制 + 删源。复制失败必须中止，绝不删源，
                // 否则「移入回收站」变成不可恢复的物理删除
                FileOps.copy(f, dest);
                if (!dest.exists()) return false;
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
        final File f = new File(e.path);
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
    //    单连接串行、PASV 数据通道，支持浏览/下载/上传/删除，
    //    登录：用户名 hyvq + 每次启动随机生成的一次性密码） ──

    private static java.net.ServerSocket ftpCtrlSocket = null;
    private static java.net.ServerSocket ftpDataSocket = null;
    private static volatile boolean ftpRunning = false;
    private static final File FTP_ROOT = Environment.getExternalStorageDirectory();
    private static final int FTP_PORT = 2121;
    /** FTP 登录凭据：用户名固定，密码每次启动随机生成（6 位数字），仅当前会话有效。
     *  ⭐ 安全修复：原实现 USER/PASS 无条件返回 230，同一局域网任何设备都能免密读写全盘。 */
    private static final String FTP_USER = "hyvq";
    private static volatile String ftpPass = "";

    private void stopFtpServer() {
        ftpRunning = false;
        ftpPass = "";   // 清空一次性密码，重新启动时会生成新的
        try {
            if (ftpCtrlSocket != null) ftpCtrlSocket.close();
        } catch (Throwable ignored) {
        }
        ftpCtrlSocket = null;
        UiKit.toast(ctx, "FTP 服务器已停止");
    }

    /**
     * FTP 运行信息弹窗 —— 持续可见，替代原来一闪而过的 toast。
     * <p>原实现用 Toast 展示「地址 + 用户名 + 密码」，长文本在 Toast 里几秒即消失，
     * 用户来不及抄写或看清（尤其密码），因此改为常驻弹窗：
     * 地址用等宽大字突出、可长按选中，并提供一键复制与「停止服务器」。</p>
     */
    private void showFtpRunningDialog(final java.util.List<String> ips) {
        final int onSurface = UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface);
        final int onVar = UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant);
        final int primary = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary);
        final int container = UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        final String mainUrl = "ftp://" + ips.get(0) + ":" + FTP_PORT;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "FTP 服务器运行中"));

        // ── 主地址：等宽大字，长按可选中 ──
        TextView tvUrl = new TextView(ctx);
        tvUrl.setText(mainUrl);
        tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvUrl.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tvUrl.setTextColor(primary);
        tvUrl.setTextIsSelectable(true);
        tvUrl.setPadding(dp(2), dp(10), dp(2), dp(2));
        box.addView(tvUrl);

        TextView tvHint = new TextView(ctx);
        tvHint.setText("电脑端：文件资源管理器地址栏粘贴（浏览器不支持 ftp://）");
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvHint.setTextColor(onVar);
        tvHint.setPadding(dp(2), 0, dp(2), dp(10));
        box.addView(tvHint);

        // ── 备用地址（多网卡时列出，便于挨个试）──
        if (ips.size() > 1) {
            StringBuilder alts = new StringBuilder("备用地址（主地址连不上时换这个）");
            for (int i = 1; i < ips.size(); i++) {
                alts.append("\n    ftp://").append(ips.get(i)).append(":").append(FTP_PORT);
            }
            TextView tvAlt = new TextView(ctx);
            tvAlt.setText(alts.toString());
            tvAlt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvAlt.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvAlt.setTextColor(onVar);
            tvAlt.setTextIsSelectable(true);
            tvAlt.setPadding(dp(2), 0, dp(2), dp(10));
            box.addView(tvAlt);
        }

        // ── 登录凭据卡 ──
        LinearLayout cred = new LinearLayout(ctx);
        cred.setOrientation(LinearLayout.VERTICAL);
        cred.setPadding(dp(12), dp(10), dp(12), dp(10));
        cred.setBackground(UiKit.rounded(ctx, 14, container, 0));
        TextView tvCred = new TextView(ctx);
        tvCred.setText("用户名：" + FTP_USER + "\n密　码：" + ftpPass);
        tvCred.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvCred.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvCred.setTextColor(onSurface);
        tvCred.setTextIsSelectable(true);
        cred.addView(tvCred);
        box.addView(cred);

        TextView tvNote = new TextView(ctx);
        tvNote.setText("· 密码为本次启动临时生成，停止服务器后失效\n"
                + "· 电脑端弹登录框时填入上面的用户名与密码\n"
                + "· 用完请点下方「停止服务器」，避免长期暴露在局域网");
        tvNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvNote.setTextColor(onVar);
        tvNote.setLineSpacing(dp(2), 1.2f);
        tvNote.setPadding(dp(2), dp(10), dp(2), dp(2));
        box.addView(tvNote);

        // ── 按钮 ──
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("复制地址", v -> {
            copyToClipboard("FTP 地址", mainUrl);
            UiKit.toast(ctx, "地址已复制");
        }));
        btns.addView(textButton("复制密码", v -> {
            copyToClipboard("FTP 密码", ftpPass);
            UiKit.toast(ctx, "密码已复制");
        }));
        btns.addView(textButton("停止服务器", v -> {
            UiKit.dismissWithAnim(dialog);
            stopFtpServer();
        }));
        btns.addView(textButton("关闭", v -> UiKit.dismissWithAnim(dialog)));
        dialog.show();
    }

    /** 写入系统剪贴板（供 FTP 地址/密码一键复制） */
    private void copyToClipboard(String label, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            }
        } catch (Throwable ignored) {
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HTTP 文件服务（浏览器原生支持，手机/电脑都不必安装客户端）
    //  ⭐ 为什么加它：Chrome/Firefox/Edge 及所有国产浏览器已在 2021 年前后
    //     彻底移除 FTP 支持，ftp:// 在浏览器里等于打不开；而 http:// 人人能用。
    //  实现：手写极简 HTTP/1.1（零第三方依赖，与 FTP 同样自给自足）
    //  安全：Basic 认证（一次性随机密码）+ 路径穿越防护 + 限定在存储根内
    // ══════════════════════════════════════════════════════════════

    private static java.net.ServerSocket httpSocket = null;
    private static volatile boolean httpRunning = false;
    private static final int HTTP_PORT = 8080;
    private static volatile String httpPass = "";

    private void stopHttpServer() {
        httpRunning = false;
        httpPass = "";
        try {
            if (httpSocket != null) httpSocket.close();
        } catch (Throwable ignored) {
        }
        httpSocket = null;
        UiKit.toast(ctx, "HTTP 服务已停止");
    }

    /** 极简 HTTP 处理：解析请求头 → Basic 认证 → 路由（目录列表 / 文件下载） */
    private void handleHttp(final Socket s) {
        java.io.OutputStream out = null;
        try {
            s.setSoTimeout(15000);
            java.io.InputStream rawIn = s.getInputStream();
            out = s.getOutputStream();

            // 只读到头部结束 \r\n\r\n（GET 无 body）
            java.io.ByteArrayOutputStream headBuf = new java.io.ByteArrayOutputStream();
            int state = 0, b, guard = 0;
            while ((b = rawIn.read()) != -1 && guard++ < 16384) {
                headBuf.write(b);
                if ((state == 0 || state == 2) && b == '\r') state++;
                else if ((state == 1 || state == 3) && b == '\n') state++;
                else state = (b == '\r') ? 1 : 0;
                if (state == 4) break;
            }
            String head = new String(headBuf.toByteArray(), "ISO-8859-1");
            String[] lines = head.split("\r\n");
            if (lines.length == 0 || lines[0].isEmpty()) {
                sendHttpError(out, 400, "Bad Request");
                return;
            }
            String[] req = lines[0].split(" ");
            if (req.length < 2) {
                sendHttpError(out, 400, "Bad Request");
                return;
            }
            String method = req[0], target = req[1];
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                sendHttpError(out, 405, "Method Not Allowed");
                return;
            }

            // ── Basic 认证 ──
            String auth = null;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].regionMatches(true, 0, "Authorization:", 0, 14)) {
                    auth = lines[i].substring(14).trim();
                    break;
                }
            }
            boolean ok = false;
            if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
                try {
                    String dec = new String(android.util.Base64.decode(
                            auth.substring(6).trim(), android.util.Base64.DEFAULT), "UTF-8");
                    String pw = dec.contains(":") ? dec.substring(dec.indexOf(':') + 1) : "";
                    ok = !httpPass.isEmpty() && pw.equals(httpPass);
                } catch (Throwable ignored) {
                }
            }
            if (!ok) {
                String resp = "HTTP/1.1 401 Unauthorized\r\n"
                        + "WWW-Authenticate: Basic realm=\"HY_VQ\", charset=\"UTF-8\"\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Connection: close\r\nContent-Length: 0\r\n\r\n";
                out.write(resp.getBytes("ISO-8859-1"));
                out.flush();
                return;
            }

            // ── 解析路径（去查询串 + URL 解码）──
            String path = target;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            try {
                path = java.net.URLDecoder.decode(path, "UTF-8");
            } catch (Throwable ignored) {
            }

            File f = resolveHttpPath(path);
            if (f == null) {
                sendHttpError(out, 403, "Forbidden");
                return;
            }

            if (f.isDirectory()) {
                byte[] body = renderDirHtml(f, path).getBytes("UTF-8");
                String resp = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n"
                        + "Content-Length: " + body.length + "\r\n\r\n";
                out.write(resp.getBytes("ISO-8859-1"));
                if (!"HEAD".equals(method)) out.write(body);
                out.flush();
            } else if (f.isFile()) {
                sendHttpFile(out, f, "HEAD".equals(method));
            } else {
                sendHttpError(out, 404, "Not Found");
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) out.flush();
            } catch (Throwable ignored) {
            }
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把请求路径解析成 File，并确保不越出存储根（防目录穿越） */
    private static File resolveHttpPath(String path) {
        try {
            String root = FTP_ROOT.getAbsolutePath();
            String rel = path == null ? "/" : path;
            if (!rel.startsWith("/")) rel = "/" + rel;
            StringBuilder sb = new StringBuilder();
            for (String seg : rel.split("/")) {
                if (seg.isEmpty() || ".".equals(seg)) continue;
                if ("..".equals(seg)) return null;   // 任何 ".." 直接拒绝
                sb.append('/').append(seg);
            }
            File f = new File(root + sb);
            return f.getAbsolutePath().startsWith(root) ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 下载/预览文件（流式，不整体读入内存） */
    private void sendHttpFile(java.io.OutputStream out, File f, boolean headOnly) {
        java.io.FileInputStream in = null;
        try {
            String resp = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + guessHttpMime(f.getName()) + "\r\n"
                    + "Content-Length: " + f.length() + "\r\n"
                    + "Content-Disposition: inline; filename*=UTF-8''"
                    + urlEnc(f.getName()) + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(resp.getBytes("ISO-8859-1"));
            if (!headOnly) {
                in = new java.io.FileInputStream(f);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendHttpError(java.io.OutputStream out, int code, String msg) {
        try {
            byte[] body = ("<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head>"
                    + "<body style=\"font-family:sans-serif;background:#FAFCFF;color:#1A1C1E;"
                    + "padding:40px\"><h2>" + code + " " + msg + "</h2></body></html>")
                    .getBytes("UTF-8");
            String resp = "HTTP/1.1 " + code + " " + msg + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Connection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
            out.write(resp.getBytes("ISO-8859-1"));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    /** 极简 MIME 猜测（浏览器预览图片/文本/媒体需要） */
    private static String guessHttpMime(String name) {
        String n = name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        String e = dot >= 0 ? n.substring(dot + 1) : "";
        switch (e) {
            case "html": case "htm": return "text/html; charset=utf-8";
            case "txt": case "log": case "md": case "json": case "xml": case "java":
            case "js": case "css": case "ini": case "conf": return "text/plain; charset=utf-8";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "wav": return "audio/wav";
            case "pdf": return "application/pdf";
            case "apk": return "application/vnd.android.package-archive";
            case "zip": return "application/zip";
            default: return "application/octet-stream";
        }
    }

    /** 目录列表页（浅蓝 Material 风格，与 App 配色一致） */
    private String renderDirHtml(File dir, String reqPath) {
        String base = reqPath == null || reqPath.isEmpty() ? "/" : reqPath;
        if (!base.endsWith("/")) base = base + "/";
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>HY_VQ · ").append(escHtml(dir.getName().isEmpty() ? "存储" : dir.getName()))
          .append("</title><style>")
          .append("body{margin:0;font-family:-apple-system,'Noto Sans CJK SC',sans-serif;")
          .append("background:#FAFCFF;color:#1A1C1E;padding:20px}")
          .append("h1{font-size:17px;margin:0 0 4px;color:#42A5F5;word-break:break-all}")
          .append(".sub{font-size:12px;color:#43525E;margin-bottom:16px}")
          .append("a{text-decoration:none;color:inherit}")
          .append(".row{display:flex;align-items:center;padding:11px 14px;margin-bottom:8px;")
          .append("background:#F1F8FD;border:1px solid #DCE9F5;border-radius:12px}")
          .append(".row:hover{background:#E8F2FB}")
          .append(".ic{width:30px;font-size:17px}")
          .append(".nm{flex:1;font-size:14px;word-break:break-all}")
          .append(".sz{font-size:12px;color:#43525E;margin-left:10px;white-space:nowrap}")
          .append(".foot{margin-top:20px;font-size:11px;color:#79747E;text-align:center}")
          .append("</style></head><body>");
        sb.append("<h1>").append(escHtml(dir.getAbsolutePath())).append("</h1>");
        sb.append("<div class=\"sub\">HY_VQ 文件服务 · 点文件夹进入，点文件下载或预览</div>");

        if (!dir.getAbsolutePath().equals(FTP_ROOT.getAbsolutePath())) {
            String parent = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            int slash = parent.lastIndexOf('/');
            parent = slash <= 0 ? "/" : parent.substring(0, slash + 1);
            sb.append("<a href=\"").append(escAttr(parent)).append("\"><div class=\"row\">")
              .append("<span class=\"ic\">⬆️</span><span class=\"nm\">返回上级</span></div></a>");
        }

        File[] fs = dir.listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File f : fs) {
                if (f.getName().startsWith(".")) continue;
                String name = f.getName();
                String href = base + urlEnc(name)
                        + (f.isDirectory() ? "/" : "");
                sb.append("<a href=\"").append(escAttr(href)).append("\"><div class=\"row\">")
                  .append("<span class=\"ic\">").append(f.isDirectory() ? "📁" : iconFor(name)).append("</span>")
                  .append("<span class=\"nm\">").append(escHtml(name)).append("</span>")
                  .append("<span class=\"sz\">").append(f.isDirectory() ? "" : humanSize(f.length()))
                  .append("</span></div></a>");
            }
        }
        sb.append("<div class=\"foot\">HY_VQ · 仅供局域网内使用</div></body></html>");
        return sb.toString();
    }

    private static String iconFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".apk")) return "📦";
        if (n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|svg)$")) return "🖼️";
        if (n.matches(".*\\.(mp4|mkv|avi|mov|webm)$")) return "🎬";
        if (n.matches(".*\\.(mp3|m4a|wav|flac|aac|ogg)$")) return "🎵";
        if (n.matches(".*\\.(zip|rar|7z|tar|gz)$")) return "🗜️";
        if (n.matches(".*\\.(txt|md|log|json|xml|java|js|css|html)$")) return "📄";
        return "📎";
    }

    private static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1073741824L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    /** URL 编码（把受检异常收敛掉，避免每个调用点都写 try-catch） */
    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s == null ? "" : s;
        }
    }

    private static String escHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escAttr(String s) {
        return escHtml(s).replace("'", "&#39;");
    }

    /** HTTP 运行信息弹窗（与 FTP 弹窗同风格；浏览器可直接打开，无需客户端） */
    private void showHttpRunningDialog(final java.util.List<String> ips) {
        final int onSurface = UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurface);
        final int onVar = UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant);
        final int primary = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimary);
        final int container = UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        final String mainUrl = "http://" + ips.get(0) + ":" + HTTP_PORT;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, "HTTP 文件服务运行中"));

        TextView tvUrl = new TextView(ctx);
        tvUrl.setText(mainUrl);
        tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvUrl.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tvUrl.setTextColor(primary);
        tvUrl.setTextIsSelectable(true);
        tvUrl.setPadding(dp(2), dp(10), dp(2), dp(2));
        box.addView(tvUrl);

        TextView tvHint = new TextView(ctx);
        tvHint.setText("手机或电脑的浏览器直接打开（无需安装任何客户端）");
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvHint.setTextColor(onVar);
        tvHint.setPadding(dp(2), 0, dp(2), dp(10));
        box.addView(tvHint);

        if (ips.size() > 1) {
            StringBuilder alts = new StringBuilder("备用地址（主地址连不上时换这个）");
            for (int i = 1; i < ips.size(); i++) {
                alts.append("\n    http://").append(ips.get(i)).append(":").append(HTTP_PORT);
            }
            TextView tvAlt = new TextView(ctx);
            tvAlt.setText(alts.toString());
            tvAlt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvAlt.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvAlt.setTextColor(onVar);
            tvAlt.setTextIsSelectable(true);
            tvAlt.setPadding(dp(2), 0, dp(2), dp(10));
            box.addView(tvAlt);
        }

        LinearLayout cred = new LinearLayout(ctx);
        cred.setOrientation(LinearLayout.VERTICAL);
        cred.setPadding(dp(12), dp(10), dp(12), dp(10));
        cred.setBackground(UiKit.rounded(ctx, 14, container, 0));
        TextView tvCred = new TextView(ctx);
        tvCred.setText("用户名：" + FTP_USER + "\n密　码：" + httpPass);
        tvCred.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvCred.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvCred.setTextColor(onSurface);
        tvCred.setTextIsSelectable(true);
        cred.addView(tvCred);
        box.addView(cred);

        TextView tvNote = new TextView(ctx);
        tvNote.setText("· 浏览器首次打开会弹出登录框，填入上面的用户名与密码\n"
                + "· 可浏览目录、下载文件；图片与文本会直接在浏览器里打开\n"
                + "· 密码为本次启动临时生成，停止服务后失效\n"
                + "· 用完请点「停止服务」，避免长期暴露在局域网");
        tvNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvNote.setTextColor(onVar);
        tvNote.setLineSpacing(dp(2), 1.2f);
        tvNote.setPadding(dp(2), dp(10), dp(2), dp(2));
        box.addView(tvNote);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        btns.addView(textButton("复制网址", v -> {
            copyToClipboard("HTTP 地址", mainUrl);
            UiKit.toast(ctx, "网址已复制");
        }));
        btns.addView(textButton("复制密码", v -> {
            copyToClipboard("HTTP 密码", httpPass);
            UiKit.toast(ctx, "密码已复制");
        }));
        btns.addView(textButton("停止服务", v -> {
            UiKit.dismissWithAnim(dialog);
            stopHttpServer();
        }));
        btns.addView(textButton("关闭", v -> UiKit.dismissWithAnim(dialog)));
        dialog.show();
    }


    /** 虚拟/隧道类接口名关键字：这些接口即使 isUp() 也不该作为对外地址，
     *  否则会给出电脑无法访问的 IP（如 ColorOS 的 vgate0 虚拟网关）。 */
    private static final String[] VIRTUAL_IF_KEYWORDS = {
            "vgate", "dummy", "ovnet", "tun", "tap", "ifb", "gre", "gretap",
            "sit", "ip6", "ip_vti", "erspan", "p2p", "rmnet_ipa", "wifi-aware", "lo"};

    private static boolean isVirtualIface(String name) {
        if (name == null) return true;
        String n = name.toLowerCase(Locale.getDefault());
        for (String k : VIRTUAL_IF_KEYWORDS) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    /** 收集本机可供局域网访问的 IPv4（按可达性排序）。
     *  ⭐ 修复：原实现「返回第一个非回环 IPv4」在多网卡设备上会选中虚拟网卡
     *  （实测 vgate0=172.30.220.67 排在 wlan0 之前且状态非 down），
     *  导致提示里给出电脑无法访问的地址 → 表现为「FTP 连不上」。
     *  现按 WiFi → 热点 → 移动数据 → 其它 的优先级返回，并排除虚拟接口。 */
    private static java.util.List<String> localIps() {
        java.util.LinkedHashMap<String, String> wifi = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> ap = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> cell = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> other = new java.util.LinkedHashMap<>();
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en =
                 java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                java.net.NetworkInterface ni = en.nextElement();
                String nm = ni.getName();
                if (nm == null || isVirtualIface(nm)) continue;
                if (!ni.isUp()) continue;
                for (java.util.Enumeration<java.net.InetAddress> ae = ni.getInetAddresses();
                     ae.hasMoreElements(); ) {
                    java.net.InetAddress ia = ae.nextElement();
                    if (!(ia instanceof java.net.Inet4Address) || ia.isLoopbackAddress()) continue;
                    String ip = ia.getHostAddress();
                    if (ip == null || ip.startsWith("127.") || ip.startsWith("169.254.")) continue;
                    String n = nm.toLowerCase(Locale.getDefault());
                    if (n.startsWith("wlan")) wifi.put(ip, nm);
                    else if (n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap")) ap.put(ip, nm);
                    else if (n.startsWith("rmnet") || n.startsWith("r_rmnet")) cell.put(ip, nm);
                    else other.put(ip, nm);
                }
            }
        } catch (Throwable ignored) {
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        out.addAll(wifi.values().isEmpty() ? wifi.keySet() : wifi.keySet());
        for (String k : ap.keySet()) if (!out.contains(k)) out.add(k);
        for (String k : cell.keySet()) if (!out.contains(k)) out.add(k);
        for (String k : other.keySet()) if (!out.contains(k)) out.add(k);
        return out;
    }

    private static java.net.Socket acceptData() {
        try {
            if (ftpDataSocket == null) return null;
            ftpDataSocket.setSoTimeout(15000);
            java.net.Socket ds = ftpDataSocket.accept();
            try {
                ftpDataSocket.close();
            } catch (Throwable ignored) {
            }
            ftpDataSocket = null;
            return ds;
        } catch (Throwable t) {
            return null;
        }
    }

    /** FTP 路径解析：限制在 FTP_ROOT（/storage/emulated/0）内，越界回退根 */
    private static File resolveFtp(File cwd, String arg) {
        try {
            File f = (arg == null || arg.isEmpty()) ? cwd
                    : (arg.startsWith("/") ? new File(arg) : new File(cwd, arg));
            String root = FTP_ROOT.getAbsolutePath();
            String fp = f.getAbsolutePath();
            if (fp.equals(root) || fp.startsWith(root + "/")) return f;
            return FTP_ROOT;
        } catch (Throwable t) {
            return FTP_ROOT;
        }
    }

    // ── 权限 ──

    private boolean checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    // ── 工具 ──

    private int countChildren(FileEntry e) {
        if (SafStrategy.isSafUri(e.path)) return SafStrategy.childCount(ctx, e.path);
        File[] list = new File(e.path).listFiles();
        return list == null ? 0 : list.length;
    }

    private void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) deleteTree(c);
            }
        }
        f.delete();
    }

    private ImageView toolIcon(int res, View.OnClickListener onClick) {
        ImageView iv = new ImageView(ctx);
        // 视觉：图标内容 22dp（原 30dp 偏大）；判定：容器仍 36dp（可点区域不缩）
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.setMarginStart(dp(1));
        lp.topMargin = dp(-3);
        lp.bottomMargin = dp(-3);
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

    private int dp(int v) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
}