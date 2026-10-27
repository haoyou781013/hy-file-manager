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
    private static int queuedTasks = 0;
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
    private EditText editorEdit = null;
    private TextView editorStat = null;
    private TextView editorHeader = null;
    private LinearLayout editorTabBar = null;
    private android.text.TextWatcher editorWatcher = null;
    /** 预览对话框（HTML/Markdown WebView，独立于编辑会话） */
    private android.app.Dialog webDialog = null;
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
    private static final class Pane {
        String path;
        /** 进入 SAF 授权目录（content://）前的路径：授权根的".."返回这里 */
        String navPrev;
        final List<FileEntry> entries = new ArrayList<>();
        final Set<FileEntry> selected = new LinkedHashSet<>();
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
    private final Pane paneL = new Pane("/storage/emulated/0");
    private final Pane paneR = new Pane("/storage/emulated/0");
    private final List<Pane> panes = new ArrayList<>(); // 未来三列扩展：动态增删
    private Pane active;
    private boolean multiMode = false;

    private TextView pathText;
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
        com.aliya.hyfilemgr.filemgr.ThumbLoader.init(this.ctx);
        if (context instanceof Activity) hostActivity = (Activity) context;
        activeInstance = this;
        restoreState();
    }

    @Override
    public void onDetach() {
        persistState();
        if (activeInstance == this) activeInstance = null;
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
        recentFiles.clear();
        String rf = sp.getString(PREF_RECENT_FILES, "");
        if (!rf.isEmpty()) {
            for (String path : rf.split("\n")) {
                if (!path.isEmpty()) recentFiles.add(path);
            }
        }
    }

    /** 持久化浏览状态（排序 / 隐藏文件 / 双窗格路径 / 活动窗格）：切换目录与 detach 时调用 */
    private void persistState() {
        if (ctx == null || paneL == null || paneR == null) return;
        // 只持久化用户偏好；路径不再保存（重启固定回存储根，保存无意义且徒增写盘）
        StringBuilder rfb = new StringBuilder();
        for (String path : recentFiles) rfb.append(path).append('\n');
        fmPrefs().edit()
                .putBoolean(PREF_GRID_MODE, gridMode)
                .putString(PREF_RECENT_FILES, rfb.toString())
                .putInt(PREF_SORT_MODE, sortMode)
                .putBoolean(PREF_SHOW_HIDDEN, showHidden)
                .putBoolean(PREF_THUMBS, thumbsEnabled)
                .apply();
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
        body.addView(buildContent(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        body.addView(buildClipBar());
        body.addView(buildOpBar());
        // ⭐10 底部导航栏（Amaze 风格：文件/分类/回收站/更多，全局视图切换）
        body.addView(buildBottomNav());
        rootContainer.addView(body);

        // ⭐10 FAB 悬浮新建（先于侧边栏叠加层加入：侧边栏打开时被遮罩覆盖，符合 Material 规范）
        buildFab();
        buildNavOverlay();

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
        // 参考外部侧边栏 bg_glass_rounded：右下圆角 20dp + 半透明磨砂(glass_light_75)
        int glassFill = ctx.getResources().getColor(R.color.glass_light_75, null);
        panelBg.setColor(glassFill);
        panelBg.setCornerRadii(new float[]{0, 0, dp(20), dp(20), 0, 0, dp(20), dp(20)}); // 仅右下圆角(同外部)
        panelBg.setStroke(dp(1), ctx.getResources().getColor(R.color.glass_border_light, null));
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

        // 左上角三条杠（汉堡）导航：点击弹出内部小侧边栏（层级导航 + SAF 挂载目录）
        bar.addView(toolIcon(R.drawable.ic_menu, v -> showNavSidebar()));

        // 面包屑路径（MaterialFiles 风格：点击弹出层级列表，可跳任意上级目录）
        LinearLayout crumb = new LinearLayout(ctx);
        crumb.setOrientation(LinearLayout.HORIZONTAL);
        crumb.setGravity(Gravity.CENTER_VERTICAL);
        crumb.setClickable(true);
        crumb.setOnClickListener(v -> showPathCrumbs());
        LinearLayout.LayoutParams crumbLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        crumbLp.setMarginStart(dp(6));
        bar.addView(crumb, crumbLp);

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

        // 🔍 搜索直达（原 ⋮ 菜单内入口，MaterialFiles/Amaze 顶栏搜索位）
        bar.addView(toolIcon(R.drawable.ic_search, v -> showSearchDialog()));
        // 排序直达（名称/大小/时间，Popover 弹出）
        bar.addView(toolIcon(R.drawable.ic_sort, v -> showSortMenu(v)));
        // ⋮ 更多（隐藏文件/书签/任务队列/刷新）
        bar.addView(toolIcon(R.drawable.ic_more_vert, v -> showMoreMenu(v)));
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
                UiKit.toast(ctx, "复制失败：" + t.getMessage());
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
                UiKit.toast(ctx, "粘贴失败：" + t.getMessage());
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

        final PopupWindow popup = new PopupWindow(panel,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(dp(8));
        // 入场/退场动画：从右下角轻微放大淡入，避免「啪」地出现
        popup.setAnimationStyle(R.style.AnimPopup);

        for (int i = 0; i < SORT_LABELS.length; i++) {
            final int mode = i;
            addMenuRow(panel, SORT_LABELS[i], R.drawable.ic_sort, v -> {
                sortMode = mode;
                fmPrefs().edit().putInt(PREF_SORT_MODE, mode).apply();
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
        p.list = rv;
        p.adapter = new FileListAdapter(ctx, p.entries, p.selected, makeListener(p));
        p.adapter.setThumbsEnabled(thumbsEnabled);
        rv.setAdapter(p.adapter);
        // 列表项动画：淡入淡出 + 位移，比默认更柔和
        androidx.recyclerview.widget.DefaultItemAnimator ia =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        ia.setAddDuration(180);
        ia.setRemoveDuration(160);
        ia.setMoveDuration(180);
        ia.setChangeDuration(160);
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
        rv.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN && active != p) {
                setActive(p);
            }
            return false;   // 不消费，正常传递给条目点击/滚动
        });
        return rv;
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

    /** 多选操作栏：全选/复制/剪切/删除/取消（作用于活跃窗格） */
    private View buildOpBar() {
        opBar = new LinearLayout(ctx);
        opBar.setOrientation(LinearLayout.HORIZONTAL);
        opBar.setGravity(Gravity.CENTER);
        int bg = UiKit.color(ctx, com.google.android.material.R.attr.colorPrimaryContainer);
        opBar.setBackground(UiKit.rounded(ctx, 14, bg, 0));
        int pad = dp(6);
        opBar.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        opBar.setLayoutParams(lp);
        opBar.setVisibility(View.GONE);

        opBar.addView(textButton("全选", v -> selectAll()));
        opBar.addView(textButton("复制", v -> clipboardCopy(false)));
        opBar.addView(textButton("剪切", v -> clipboardCopy(true)));
        opBar.addView(textButton("删除", v -> deleteSelected()));
        opBar.addView(textButton("取消", v -> exitMultiMode()));
        return opBar;
    }

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
        bottomNav.setBackground(UiKit.rounded(ctx, 14, bg, 0));
        int pad = dp(4);
        bottomNav.setPadding(pad, dp(1), pad, dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        bottomNav.setLayoutParams(lp);

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
                            enterZip(e.path);
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
        persistState();
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
            UiKit.toast(ctx, "链接目标不存在");
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

    /** 全部窗格重新加载（首次进入视图） */
    private void reloadAll() {
        // 写操作/刷新后目录内容可能变化：清空全部窗格目录缓存（强制重新 listFiles）
        for (Pane p : panes) {
            if (p.dirCache != null) p.dirCache.clear();
        }
        for (Pane p : panes) reload(p);
    }

    /** 重新加载单个窗格：AccessRouter 四源路由 → 排序 → 刷新（恢复该目录上次滚动位置） */
    private void reload(Pane p) {
        if (p == null || p.path == null) return;
        persistState(); // 目录切换即落盘（下次启动回到这里）
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
        // MT 风格刷新动画：条目从上到下依次快速出现
        playStaggerAnimation(p.list);
        if (p == active && pathText != null) {
            pathText.setText(displayPath(p.path));
            updateBottomNav();
        }
        updateClipBar();
    }

    /** MT 风格刷新动画：条目从上到下依次淡入（纯透明度 0.5→1，无上下位移，柔和无抖动） */
    private void playStaggerAnimation(final RecyclerView rv) {
        if (rv == null) return;
        rv.post(() -> {
            int n = rv.getChildCount();
            for (int i = 0; i < n; i++) {
                View child = rv.getChildAt(i);
                if (child == null) continue;
                // 只做透明度动画（0.5→1，无位移不抖动）：从上到下依次淡入
                child.setAlpha(0.5f);
                child.animate().alpha(1f)
                        .setDuration(90).setStartDelay(i * 12L).start();
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
        if (opBar == null) return;
        opBar.setVisibility(multiMode ? View.VISIBLE : View.GONE);
    }

    // ── 长按菜单（MT 风格固定菜单：所有功能可见，当前场景不可用的置灰禁用） ──

    private void showItemMenu(final Pane p, final FileEntry e, View anchor) {
        boolean virt = e.virtual;
        // SAF 授权条目（content://）可写（新建/重命名/删除/复制/剪切），与真实条目同菜单
        boolean saf = e.path != null && e.path.startsWith("content://");
        boolean real = !virt && !saf;
        boolean inTrash = p.path.startsWith(PREFIX_TRASH);
        boolean inCat = false;   // 分类功能已移除，恒 false
        boolean parent = e.parent;
        boolean zip = real && isZip(e);
        // 写操作可用条件：非".."、真实或SAF条目、不在回收站/分类只读视图
        boolean writable = !parent && (real || saf) && !inTrash && !inCat;
        // 压缩/解压/书签可用条件：仅真实条目（java.io 直操作），且不在只读虚拟视图
        boolean realOnly = !parent && real && !inTrash && !inCat;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(ctx, e.name));

        // 居中玻璃对话框 + 边框描边（用户要求：长按弹窗居中显示，与背景可分辨）
        // 固定菜单（对齐 MT 管理器长按菜单）：不可用项置灰但保留可见，点击无响应
        final android.app.Dialog dialog = UiKit.glassDialog(ctx, box);
        addMenuRow(box, "打开方式", android.R.drawable.ic_menu_share, !parent, v -> {
            dialog.dismiss();
            onItemMenu(p, e, "打开方式");
        });
        // 包内条目专用：把这一条解出到「另一窗格」的当前目录（双列布局的价值所在）
        if (e.path != null && e.path.startsWith(PREFIX_ZIP) && !e.isDir()) {
            addMenuRow(box, "解压到对侧", R.drawable.ic_archive, true, v -> {
                dialog.dismiss();
                extractZipEntryToOtherPane(e);
            });
        }
        // 编辑：仅真实路径文本/代码文件可用（点击同款内置编辑器）
        addMenuRow(box, "编辑", R.drawable.ic_doc, realOnly && isTextFile(e.name), v -> {
            dialog.dismiss();
            onItemMenu(p, e, "编辑");
        });
        addMenuRow(box, "复制", R.drawable.ic_copy, writable, v -> {
            dialog.dismiss();
            onItemMenu(p, e, "复制");
        });
        addMenuRow(box, "剪切", R.drawable.ic_cut, writable, v -> {
            dialog.dismiss();
            onItemMenu(p, e, "剪切");
        });
        addMenuRow(box, "删除", android.R.drawable.ic_menu_delete, writable, v -> {
            dialog.dismiss();
            onItemMenu(p, e, "删除");
        });
        addMenuRow(box, "重命名", android.R.drawable.ic_menu_edit, writable, v -> {
            dialog.dismiss();
            onItemMenu(p, e, "重命名");
        });
        // zip → 解压；其余真实条目（文件/文件夹）→ 压缩
        addMenuRow(box, zip ? "解压" : "压缩",
                zip ? R.drawable.ic_download : android.R.drawable.ic_menu_upload,
                realOnly, v -> {
                    dialog.dismiss();
                    onItemMenu(p, e, zip ? "解压" : "压缩");
                });
        addMenuRow(box, "属性", android.R.drawable.ic_menu_info_details, !parent, v -> {
            dialog.dismiss();
            onItemMenu(p, e, "属性");
        });
        addMenuRow(box, "添加书签", R.drawable.ic_star, realOnly, v -> {
            dialog.dismiss();
            onItemMenu(p, e, "添加书签");
        });
        addMenuRow(box, "多选模式", android.R.drawable.ic_menu_agenda, !parent && !inCat, v -> {
            dialog.dismiss();
            onItemMenu(p, e, "多选模式");
        });
        // 回收站场景追加：恢复 / 彻底删除（其余写操作已置灰）
        if (inTrash) {
            addMenuRow(box, "恢复", android.R.drawable.ic_menu_revert, !parent, v -> {
                dialog.dismiss();
                onItemMenu(p, e, "恢复");
            });
            addMenuRow(box, "彻底删除", android.R.drawable.ic_menu_delete, !parent, v -> {
                dialog.dismiss();
                onItemMenu(p, e, "彻底删除");
            });
        }
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
        multiMode = true;
        for (Pane p : panes) p.adapter.setMultiMode(true);
        updateOpBar();
        // ⭐10 Material 规范：多选模式下隐藏 FAB（底部操作栏接管操作）
        if (fabNew != null) fabNew.setVisibility(View.GONE);
        // 多选底栏「替换」而非「叠加」底部导航栏：
        // 两者同为 body 的直接子 View，同时可见会看到两层底栏叠在一起。
        if (bottomNav != null) bottomNav.setVisibility(View.GONE);
    }

    private void exitMultiMode() {
        multiMode = false;
        for (Pane p : panes) {
            p.selected.clear();
            p.adapter.setMultiMode(false);
        }
        if (opBar != null) opBar.setVisibility(View.GONE);
        // 退出多选恢复 FAB 与活跃窗格状态栏
        if (fabNew != null) fabNew.setVisibility(View.VISIBLE);
        // 同步恢复底部导航栏（与 enterMultiMode 的隐藏成对）
        if (bottomNav != null) bottomNav.setVisibility(View.VISIBLE);
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

        final PopupWindow popup = new PopupWindow(panel,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
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
                UiKit.toast(ctx, "文件名不合法（含 / \\ : * ? \" < > | 或以点结尾）");
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
                        : UiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow);
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
                            fmPrefs().edit().putStringSet("bookmarks", set).apply();
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

        // ── 书签区（仿 ZhuFiler NavigationView 书签组：常驻可见，点击进入、长按删除） ──
        buildBookmarkSection(content);

        // ── ⭐ v2.8.0 ROOT 分组（已授权时列出系统路径；未授权时一行申请入口） ──


        // ── 存储位置分组标题（挂载列表） ──
        {
            TextView mHead = new TextView(ctx);
            mHead.setText("存储位置");
            mHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            mHead.setTypeface(null, android.graphics.Typeface.BOLD);
            mHead.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
            LinearLayout.LayoutParams mHeadLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mHeadLp.topMargin = dp(10);
            mHeadLp.bottomMargin = dp(4);
            mHeadLp.leftMargin = dp(4);
            content.addView(mHead, mHeadLp);
        }

        // ── 合并列表：根目录 + 内部存储 + SAF 授权目录（FCL 式"类挂载"，统一排序） ──
        List<MountEntry> entries = buildMountList();
        // 诊断（错误可见性）：一个授权项都没有且加载有异常 → toast 真实原因
        //（仅 SAF 挂载显示开启时提示；隐藏期间不误报）
        if (SHOW_SAF_MOUNTS) {
            int safCount = 0;
            for (MountEntry me : entries) if (me.deletable) safCount++;
            if (safCount == 0 && mountLoadError != null && !mountLoadError.isEmpty()) {
                UiKit.toast(ctx, "授权列表加载失败：" + mountLoadError);
            }
        }
        for (final MountEntry e : entries) {
            content.addView(buildMountRow(content, e));
        }

        // ── 分隔线 + 授权按钮（列表底部，触发系统文件选择器）；与授权项同开关隐藏 ──
        if (SHOW_SAF_MOUNTS) {
            View divider = new View(ctx);
            divider.setBackgroundColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant));
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            divLp.setMargins(dp(16), dp(8), dp(16), dp(8));
            content.addView(divider, divLp);

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
            content.addView(authBtn, authLp);
        }
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
            UiKit.toast(ctx, "宿主 Activity 不可用，无法打开系统文件选择器");
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
            UiKit.toast(ctx, "无法打开系统文件选择器：" + t.getMessage());
        }
    }

    /** 关闭内部侧边栏：面板左滑 + 遮罩淡出同步（修复透明度突变闪烁），结束后隐藏复位 */
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
        list.add(new MountEntry(PREFIX_TRASH, "回收站", "已删除文件，可恢复",
                android.R.drawable.ic_menu_delete, false, false, PREFIX_TRASH));
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
        fmPrefs().edit().putString("nav_mount_order", sb.toString()).apply();
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
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
        int s = dp(18);
        top.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView tv = new TextView(ctx);
        tv.setText(e.title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
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
            sub.setTextColor(UiKit.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
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
                UiKit.toast(ctx, "名称不合法（含 / \\ : * ? \" < > | 或以点结尾）");
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
                UiKit.toast(ctx, "名称不合法（含 / \\ : * ? \" < > | 或以点结尾）");
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
                            deleteTree(f); // 回收站：物理删除
                        } else {
                            // 移入回收站（保留原路径层级，恢复=移回原位置）
                            if (!moveToTrash(f)) {
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
            UiKit.toast(ctx, "文件不存在");
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
            UiKit.toast(ctx, "读取失败");
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
        editorEdit = new EditText(ctx);
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
        box.addView(editorEdit, new LinearLayout.LayoutParams(
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
                handler.post(() -> UiKit.toast(ctx, "保存失败：" + t.getMessage()));
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
        fmPrefs().edit().putString(EDITOR_PREF_TABS, arr.toString()).apply();
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
                    UiKit.toast(ctx, "未找到匹配");
                }
            });
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (!replaceCurrent(findEt.getText().toString(), repEt.getText().toString(),
                        regexSw.isChecked(), wrapSw.isChecked())) {
                    UiKit.toast(ctx, "未找到匹配");
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
            UiKit.toast(ctx, "正则错误：" + t.getMessage());
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
                UiKit.toast(ctx, "正则错误：" + t.getMessage());
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
                UiKit.toast(ctx, "正则错误：" + t.getMessage());
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
    private void showPreviewDialog() {
        if (currentEditTab == null) return;
        final String ext = currentEditTab.file.getName().toLowerCase();
        final boolean isHtml = ext.endsWith(".html") || ext.endsWith(".htm")
                || ext.endsWith(".xhtml") || ext.endsWith(".mht");
        final boolean isMd = ext.endsWith(".md") || ext.endsWith(".markdown") || ext.endsWith(".txt");
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
        } else if (isMd && !ext.endsWith(".txt")) {
            // 用共享 MarkdownUtils（与更新详情页一致）；CSS 沿用编辑器调色板
            String body = android.text.TextUtils.htmlEncode(content).replace("\n", "<br>");
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
        }));
        box.addView(top);
          // 统一走 MarkdownWebView；⚠️ 不再开启 JavaScript —— 预览的是用户
          // 任意 .md/.html 文件，开 JS 等于给每个文件一个脚本执行入口
          android.webkit.WebView wv = new android.webkit.WebView(ctx);
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
            UiKit.toast(ctx, "无法打开播放器：" + t.getMessage());
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
                // 自适应卡片网格：竖屏 3 列 / 横屏 4 列
                int cols = openGridColumns();
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(-2), dp(-2), dp(-2), dp(-2));
                int added = 0;
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                cardLp.setMargins(dp(2), dp(2), dp(2), dp(2));

                String[][] items = {
                        {"img", "内置图片查看器", "双指缩放 · 拖动 · 双击还原", String.valueOf(isImg)},
                        {"video", "内置播放器", "视频与音频 · 进度控制", String.valueOf(isMedia)},
                        {"doc", "内置文本编辑器", "查看与编辑文本、代码", String.valueOf(isText)},
                        {"archive", "解压到当前目录", "ZIP 压缩包", String.valueOf(isZipFile)},
                        {"apk", "安装应用", "Android 安装包", String.valueOf(isApk)},
                };
                for (String[] it : items) {
                    if (added == cols) {
                        listBox.addView(row);
                        row = new LinearLayout(ctx);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setPadding(dp(-2), dp(-2), dp(-2), dp(-2));
                        added = 0;
                    }
                    int res = "img".equals(it[0]) ? R.drawable.ic_img
                            : "video".equals(it[0]) ? R.drawable.ic_video
                            : "doc".equals(it[0]) ? R.drawable.ic_doc
                            : "archive".equals(it[0]) ? R.drawable.ic_archive : R.drawable.ic_apk;
                    boolean en = Boolean.parseBoolean(it[3]);
                    addOpenCard(row, d, res, it[1], it[2], en, () -> {
                        switch (it[0]) {
                            case "img": openImageViewer(pathOrUri, name, isUri); break;
                            case "video": openWithBuiltInPlayer(pathOrUri, name, isUri); break;
                            case "doc": openInEditorByName(pathOrUri, name, isUri); break;
                            case "archive": unzipByName(pathOrUri, name, isUri); break;
                            default: installApkByName(pathOrUri, name, isUri); break;
                        }
                    });
                    added++;
                }
                if (added > 0) listBox.addView(row);
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

    /** 内置打开方式条目：可用则高亮可点，不适用则灰显并标注 */
    /** 内置打开方式：自适应卡片网格（竖屏 3 列 / 横屏 4 列），与长按菜单同一套观感 */
    private void addOpenCard(LinearLayout parent, final android.app.Dialog dialog,
                            int iconRes, String title, String desc, boolean enabled,
                            final Runnable action) {
        int onSurface = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface);
        int variant = UiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        int primary = UiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(10), dp(12), dp(10), dp(12));
        card.setBackground(UiKit.rounded(ctx, 14,
                enabled ? UiKit.color(ctx,
                        com.google.android.material.R.attr.colorSurfaceContainerLow)
                : UiKit.color(ctx,
                        com.google.android.material.R.attr.colorSurface),
                enabled ? UiKit.color(ctx,
                        com.google.android.material.R.attr.colorOutlineVariant) : 0));

        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconRes);
        int isz = dp(28);
        ic.setLayoutParams(new LinearLayout.LayoutParams(isz, isz));
        ic.setColorFilter(enabled ? primary : variant);
        ic.setAlpha(enabled ? 1f : 0.35f);
        card.addView(ic, new LinearLayout.LayoutParams(isz, isz));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(enabled ? onSurface : variant);
        t.setGravity(android.view.Gravity.CENTER);
        t.setAlpha(enabled ? 1f : 0.5f);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView dsc = new TextView(ctx);
        dsc.setText(enabled ? desc : "不适用");
        dsc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        dsc.setTextColor(variant);
        dsc.setGravity(android.view.Gravity.CENTER);
        dsc.setAlpha(enabled ? 1f : 0.5f);
        dsc.setMaxLines(2);
        card.addView(dsc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (enabled) {
            card.setOnClickListener(v -> {
                dialog.dismiss();
                action.run();
            });
        }
        parent.addView(card);
    }

    /** 判断网格列数：竖屏 3 列 / 横屏 4 列 */
    private int openGridColumns() {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels ? 4 : 3;
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
            Intent probe = new Intent(Intent.ACTION_VIEW);
            probe.setDataAndType(uri, mime == null ? "*/*" : mime);
            java.util.List<android.content.pm.ResolveInfo> apps =
                    ctx.getPackageManager().queryIntentActivities(probe, 0);
            if (apps.isEmpty()) {
                parent.addView(hintText("系统未找到可打开该文件的应用", variant));
                return;
            }
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
                    ic.setImageDrawable(ri.loadIcon(ctx.getPackageManager()));
                } catch (Throwable ignored) {
                }
                row.addView(ic);

                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(12), 0, 0, 0);
                TextView t1 = new TextView(ctx);
                String label = "";
                try {
                    label = ri.loadLabel(ctx.getPackageManager()).toString();
                } catch (Throwable ignored) {
                }
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
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setComponent(new android.content.ComponentName(
                                ri.activityInfo.packageName, ri.activityInfo.name));
                        i.setDataAndType(uri, mime == null ? "*/*" : mime);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    } catch (Throwable t) {
                        UiKit.toast(ctx, "打开失败：" + t.getMessage());
                    }
                });
            }
        } catch (Throwable t) {
            parent.addView(hintText("读取系统应用失败：" + t.getMessage(), variant));
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
            UiKit.toast(ctx, "无法打开图片查看器：" + t.getMessage());
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
            UiKit.toast(ctx, "无法打开编辑器：" + t.getMessage());
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
            UiKit.toast(ctx, "解压失败：" + t.getMessage());
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
            UiKit.toast(ctx, "无法安装：" + t.getMessage());
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
            UiKit.toast(ctx, "无法打开：" + t.getMessage());
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

        final PopupWindow popup = new PopupWindow(panel,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(dp(8));

        // ── 功能入口（刷新/隐藏文件切换/书签列表/收藏当前目录/任务队列） ──
        addMenuRow(panel, "刷新", R.drawable.ic_refresh, v -> {
            popup.dismiss();
            reloadAll();
        });
        addMenuRow(panel, showHidden ? "隐藏文件：显示中" : "隐藏文件：已隐藏",
                android.R.drawable.ic_menu_view, v -> {
                    popup.dismiss();
                    showHidden = !showHidden;
                    fmPrefs().edit().putBoolean(PREF_SHOW_HIDDEN, showHidden).apply();
                    UiKit.toast(ctx, showHidden ? "已显示隐藏文件" : "已隐藏 . 开头文件");
                    reloadAll();
                });
        addMenuRow(panel, thumbsEnabled ? "缩略图：已开启" : "缩略图：已关闭",
                R.drawable.ic_img, v -> {
                    popup.dismiss();
                    thumbsEnabled = !thumbsEnabled;
                    fmPrefs().edit().putBoolean(PREF_THUMBS, thumbsEnabled).apply();
                    for (Pane pp : panes) {
                        if (pp != null && pp.adapter != null) {
                            pp.adapter.setThumbsEnabled(thumbsEnabled);
                        }
                    }
                    UiKit.toast(ctx, thumbsEnabled ? "已开启缩略图" : "已关闭缩略图");
                });
        addMenuRow(panel, "书签列表", R.drawable.ic_star, v -> {
            popup.dismiss();
            showBookmarks();
        });
        addMenuRow(panel, "收藏当前目录", android.R.drawable.ic_menu_save, v -> {
            popup.dismiss();
            addBookmark();
        });
        addMenuRow(panel, gridMode ? "切换到列表视图" : "切换到网格视图",
                R.drawable.ic_preview, v -> {
                    popup.dismiss();
                    gridMode = !gridMode;
                    persistState();
                    reloadAll();
                    UiKit.toast(ctx, gridMode ? "已切换到网格视图" : "已切换到列表视图");
                });
        addMenuRow(panel, "最近文件", R.drawable.ic_doc, v -> {
            popup.dismiss();
            showRecentFiles();
        });
        addMenuRow(panel, "存储分析", R.drawable.ic_info, v -> {
            popup.dismiss();
            showStorageAnalysis();
        });
        addMenuRow(panel, queuedTasks > 0 ? "任务队列（" + queuedTasks + "）" : "任务队列",
                android.R.drawable.ic_menu_manage, v -> {
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
        queuedTasks++;
        TASK_QUEUE.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                UiKit.toast(ctx, "任务失败：" + t.getMessage());
            } finally {
                queuedTasks--;
            }
        });
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
        tv.setText(queuedTasks > 0
                ? "当前排队/执行中任务：" + queuedTasks + "\n粘贴/压缩/解压/搜索/分类扫描/回收站操作按顺序串行执行，完成后 toast 提示。"
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

    private static boolean isZip(FileEntry e) {
        return e.name != null && e.name.toLowerCase(Locale.getDefault()).endsWith(".zip");
    }

    /** 单条目压缩为 zip：同目录 <原名>.zip（重名自动 (1)）；目录递归打包 */
    private void compressToZip(final FileEntry e) {
        final File src = new File(e.path);
        final File parent = src.getParentFile();
        if (parent == null || !parent.canWrite()) {
            UiKit.toast(ctx, "目录不可写，无法压缩");
            return;
        }
        final String zipName = uniqueName(parent.getAbsolutePath(), e.name + ".zip", null);
        final File zipFile = new File(parent, zipName);
        UiKit.toast(ctx, "压缩中…");
        runTask(() -> {
            boolean ok = false;
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(zipFile)))) {
                if (src.isDirectory()) {
                    zipDir(src, src.getName(), zos);
                } else {
                    zipFileEntry(src, src.getName(), zos);
                }
                ok = true;
            } catch (Throwable t) {
                UiKit.toast(ctx, "压缩失败：" + t.getMessage());
                zipFile.delete();
            }
            final boolean fOk = ok;
            handler.post(() -> {
                UiKit.toast(ctx, fOk ? "已压缩：" + zipName : "压缩失败");
                reload(active);
            });
        });
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
        final File zipFile = new File(e.path);
        final File parent = zipFile.getParentFile();
        if (parent == null) return;
        String base = e.name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        final File outDir = new File(parent, uniqueName(parent.getAbsolutePath(), base, null));
        UiKit.toast(ctx, "解压中…");
        runTask(() -> {
            int count = 0;
            try {
                outDir.mkdirs();
                try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                        new java.io.BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
                    java.util.zip.ZipEntry ze;
                    while ((ze = zis.getNextEntry()) != null) {
                        String name = ze.getName();
                        // 中文乱码修复：Windows zip 常见 GBK 编码文件名，被按 UTF-8 解码
                        // 会出现 U+FFFD 替换符/控制字符 → 用 ISO-8859-1→GBK 还原
                        if (name.indexOf('\uFFFD') >= 0 || containsGarbled(name)) {
                            String fixed = fixGbkName(name);
                            if (fixed != null && !fixed.equals(name)) name = fixed;
                        }
                        // 防 zip 炸弹路径穿越：拒绝绝对路径与 ../
                        if (name.startsWith("/") || name.contains("..")) continue;
                        File target = new File(outDir, name);
                        if (ze.isDirectory()) {
                            target.mkdirs();
                        } else {
                            File td = target.getParentFile();
                            if (td != null) td.mkdirs();
                            try (java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(
                                    new java.io.FileOutputStream(target))) {
                                byte[] buf = new byte[65536];
                                int n;
                                while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                            }
                            target.setLastModified(ze.getTime());
                            count++;
                        }
                        zis.closeEntry();
                    }
                }
            } catch (Throwable t) {
                UiKit.toast(ctx, "解压失败：" + t.getMessage());
            }
            final int fCount = count;
            handler.post(() -> {
                UiKit.toast(ctx, "解压完成：" + fCount + " 个文件 → " + outDir.getName());
                reload(active);
            });
        });
    }

    private static String fixGbkName(String name) {
        try {
            return new String(name.getBytes("ISO-8859-1"), "GBK");
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsGarbled(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // 只跳过常见的合法控制字符：Tab(0x09) / 换行(0x0A) / 回车(0x0D)
            // 以及 NUL(0x00)，其余 <0x20 视为乱码
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\0') return true;
        }
        return false;
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
            if (low.endsWith(".zip") || low.endsWith(".jar") || low.endsWith(".apk")) {
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
    private void enterZip(String zipPath) {
        active.path = PREFIX_ZIP + zipPath + "/";
        reload(active);
    }

    private void loadZip(Pane p) {
        p.entries.clear();
        String[] parts = splitZipPath(p.path);
        String zipPath = parts[0];
        String inner = parts[1];
        File zip = new File(zipPath);
        if (!zip.isFile()) {
            p.lastResult = AccessResult.empty("压缩包不存在");
            return;
        }
        java.util.TreeMap<String, FileEntry> dirs = new java.util.TreeMap<>();
        java.util.TreeMap<String, FileEntry> files = new java.util.TreeMap<>();
        // 先解析中央目录拿到加密表（不依赖反射）
        java.util.Map<String, Boolean> encMap = readZipEncryptionMap(zip);
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            String prefix = inner.isEmpty() ? "" : inner + "/";
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry ze = en.nextElement();
                String name = ze.getName();
                if (!name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (rel.isEmpty()) continue;
                boolean encrypted = Boolean.TRUE.equals(encMap.get(name));
                String encMark = encrypted ? " · 加密" : "";
                int slash = rel.indexOf('/');
                if (slash >= 0) {
                    String dirName = rel.substring(0, slash);
                    if (!dirs.containsKey(dirName)) {
                        String childVirt = p.path + dirName + "/";
                        boolean nested = dirName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
                        dirs.put(dirName, new FileEntry(childVirt, dirName,
                                FileEntry.Type.VIRTUAL_DIR, 0, 0, true,
                                nested ? "嵌套压缩包" : "文件夹", false));
                    }
                } else {
                    String childVirt = p.path + rel;
                    files.put(rel, new FileEntry(childVirt, rel,
                            FileEntry.Type.VIRTUAL_FILE, ze.getSize(), ze.getTime(), true,
                            humanSize(ze.getSize()) + encMark, false));
                }
            }
        } catch (Throwable t) {
            p.lastResult = AccessResult.empty("无法读取压缩包：" + t.getMessage());
            return;
        }
        // 置顶 ".." 返回条目：
        //  · 包内子目录 → 回到包内上一级
        //  · 压缩包根目录 → 退出预览，回到压缩包所在文件夹
        if (!inner.isEmpty()) {
            int cut = inner.lastIndexOf('/');
            String up = cut < 0 ? "" : inner.substring(0, cut + 1);
            p.entries.add(FileEntry.parent(PREFIX_ZIP + zipPath + "/" + up));
        } else {
            // 根目录：.. 指向 zip 文件所在的普通目录
            String zipDir = new File(zipPath).getParent();
            p.entries.add(FileEntry.parent(zipDir == null
                    ? Environment.getExternalStorageDirectory().getAbsolutePath()
                    : zipDir));
        }
        p.entries.addAll(dirs.values());
        p.entries.addAll(files.values());
        p.lastResult = AccessResult.empty("压缩包 · " + zip.getName());
    }

    /** 把包内条目解出到另一窗格的当前目录 */
    private void extractZipEntryToOtherPane(final FileEntry e) {
        if (panes.size() < 2) {
            UiKit.toast(ctx, "当前为单窗格，无法解压到对侧");
            return;
        }
        final Pane other = (panes.get(0) == active) ? panes.get(1) : panes.get(0);
        if (other == null || other.path == null
                || other.path.startsWith(PREFIX_ZIP)
                || other.path.startsWith(PREFIX_TRASH)
                ) {
            UiKit.toast(ctx, "另一窗格不在可写入的目录");
            return;
        }
        final String destDir = other.path;
        String[] parts = splitZipPath(e.path);
        final String zipPath = parts[0];
        final String inner = parts[1];
        java.util.Map<String, Boolean> encCheck2 = readZipEncryptionMap(new File(zipPath));
        if (Boolean.TRUE.equals(encCheck2.get(inner))) {
            UiKit.toast(ctx, "该条目已加密，当前版本暂不支持解密");
            return;
        }
        UiKit.toast(ctx, "解出中…");
        new Thread(() -> {
            String err = null;
            String outName = null;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(new File(zipPath))) {
                java.util.zip.ZipEntry ze = zf.getEntry(inner);
                if (ze == null) {
                    err = "条目不存在";
                } else {
                    File dest = new File(destDir, uniqueName(destDir,
                            inner.substring(inner.lastIndexOf('/') + 1), null));
                    try (java.io.InputStream in = zf.getInputStream(ze);
                         java.io.OutputStream os = new java.io.FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    outName = dest.getName();
                }
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
                    UiKit.toast(ctx, "展开失败：" + fErr);
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
                UiKit.toast(ctx, "密码不能为空");
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
                        UiKit.toast(ctx, "解出失败：" + fErr);
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
                    UiKit.toast(ctx, "解出失败：" + fErr);
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

    private void collectTrash(File dir, List<FileEntry> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectTrash(f, out);
            else out.add(FileEntry.file(f.getAbsolutePath(), f.length(), f.lastModified()));
        }
    }

    /** 移入回收站：目标 = TRASH_DIR + 原绝对路径（保留层级，恢复=移回原位置）；重名加时间戳 */
    private boolean moveToTrash(File f) {
        try {
            String target = TRASH_DIR + f.getAbsolutePath();
            File dest = new File(target);
            File pd = dest.getParentFile();
            if (pd != null) pd.mkdirs();
            if (dest.exists()) dest = new File(target + "." + System.currentTimeMillis());
            if (f.renameTo(dest)) return true;
            // renameTo 失败（跨分区等）→ 复制 + 删源
            FileOps.copy(f, dest);
            deleteTree(f);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从回收站恢复：trash 路径去掉 TRASH_DIR 前缀即原绝对路径；父目录重建；重名自动 (1) */
    private void restoreFromTrash(final FileEntry e) {
        final File f = new File(e.path);
        if (!e.path.startsWith(TRASH_DIR)) {
            UiKit.toast(ctx, "仅回收站内条目可恢复");
            return;
        }
        final String orig = e.path.substring(TRASH_DIR.length());
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
            UiKit.toast(ctx, "该条目不能收藏");
            return;
        }
        File f = new File(path);
        String target = f.isDirectory() ? f.getAbsolutePath() : f.getParent();
        if (target == null) {
            UiKit.toast(ctx, "无法收藏");
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
        sp.edit().putStringSet("bookmarks", set).apply();
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
            sp.edit().putStringSet("bookmarks", set).apply();
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

    /** 单连接 FTP 命令循环：USER/PASS 认证（一次性随机密码）、PASV 被动模式、
     *  LIST/RETR/STOR/DELE/MKD/RMD。
     *  ⭐ 安全修复：未认证的连接只能发 USER/PASS/QUIT，任何文件操作一律 530 拒绝。 */
    private void handleFtp(final Socket s) {
        try {
            s.setSoTimeout(60000);
            final java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            final java.io.PrintWriter out = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(s.getOutputStream(), "ISO-8859-1"), true);
            File cwd = FTP_ROOT;
            boolean authed = false;
            out.println("220 HY_VQ FTP Server ready");
            String line;
            while (ftpRunning && (line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                String cmd = line.toUpperCase(Locale.getDefault());
                String arg = line.length() > cmd.length() ? line.substring(cmd.length()).trim() : "";
                if (cmd.startsWith("USER")) {
                    // 用户名固定，但不在此时校验，统一在 PASS 阶段判定
                    out.println("331 Password required");
                } else if (cmd.startsWith("PASS")) {
                    String pw = line.length() > 4 ? line.substring(4).trim() : "";
                    if (!ftpPass.isEmpty() && pw.equals(ftpPass)) {
                        authed = true;
                        out.println("230 Login successful");
                    } else {
                        authed = false;
                        out.println("530 Login incorrect");
                    }
                } else if (cmd.startsWith("QUIT")) {
                    out.println("221 Bye");
                    break;
                } else if (!authed) {
                    // ⭐ 未登录：除 USER/PASS/QUIT 外一律拒绝，杜绝免密访问
                    out.println("530 Please login with USER and PASS");
                } else if (cmd.startsWith("TYPE") || cmd.startsWith("NOOP")) {
                    out.println("200 OK");
                } else if (cmd.startsWith("SYST")) {
                    out.println("215 UNIX Type: L8");
                } else if (cmd.startsWith("PWD") || cmd.startsWith("XPWD")) {
                    out.println("257 \"" + cwd.getAbsolutePath() + "\"");
                } else if (cmd.startsWith("CWD")) {
                    File d = resolveFtp(cwd, arg);
                    if (d != null && d.isDirectory()) {
                        cwd = d;
                        out.println("250 OK");
                    } else out.println("550 No such directory");
                } else if (cmd.startsWith("CDUP")) {
                    File p = cwd.getParentFile();
                    if (p != null && p.getAbsolutePath().startsWith(FTP_ROOT.getAbsolutePath())) {
                        cwd = p;
                        out.println("250 OK");
                    } else out.println("550 Already at root");
                } else if (cmd.startsWith("PASV")) {
                    try {
                        if (ftpDataSocket != null) {
                            try {
                                ftpDataSocket.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        ftpDataSocket = new java.net.ServerSocket(0);
                        String h = s.getLocalAddress().getHostAddress().replace('.', ',');
                        int p1 = ftpDataSocket.getLocalPort() / 256;
                        int p2 = ftpDataSocket.getLocalPort() % 256;
                        out.println("227 Entering Passive Mode (" + h + "," + p1 + "," + p2 + ")");
                    } catch (Throwable t) {
                        out.println("425 Can't open data connection");
                    }
                } else if (cmd.startsWith("LIST") || cmd.startsWith("NLST")) {
                    java.net.Socket ds = acceptData();
                    if (ds == null) {
                        out.println("425 No data connection");
                        continue;
                    }
                    File dir = resolveFtp(cwd, arg);
                    out.println("150 Opening data connection");
                    try (java.io.PrintWriter dw = new java.io.PrintWriter(
                            new java.io.OutputStreamWriter(ds.getOutputStream(), "ISO-8859-1"), true)) {
                        if (dir != null && dir.isDirectory()) {
                            File[] fs = dir.listFiles();
                            if (fs != null) {
                                for (File f : fs) {
                                    String perms = f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--";
                                    long sz = f.isDirectory() ? 4096 : f.length();
                                    String date = java.text.DateFormat.getDateInstance(
                                            java.text.DateFormat.MEDIUM, Locale.ENGLISH)
                                            .format(new java.util.Date(f.lastModified()));
                                    dw.println(perms + " 1 owner group " + sz + " " + date
                                            + " " + f.getName());
                                }
                            }
                        }
                    }
                    ds.close();
                    out.println("226 Transfer complete");
                } else if (cmd.startsWith("RETR")) {
                    File f = resolveFtp(cwd, arg);
                    if (f == null || !f.isFile()) {
                        out.println("550 No such file");
                        continue;
                    }
                    java.net.Socket ds = acceptData();
                    if (ds == null) {
                        out.println("425 No data connection");
                        continue;
                    }
                    out.println("150 Opening binary data");
                    try (java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(
                            ds.getOutputStream());
                         java.io.BufferedInputStream bis = new java.io.BufferedInputStream(
                                 new java.io.FileInputStream(f))) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = bis.read(buf)) > 0) bos.write(buf, 0, n);
                    }
                    ds.close();
                    out.println("226 Transfer complete");
                } else if (cmd.startsWith("STOR")) {
                    File f = resolveFtp(cwd, arg);
                    if (f == null) {
                        out.println("550 Invalid path");
                        continue;
                    }
                    java.net.Socket ds = acceptData();
                    if (ds == null) {
                        out.println("425 No data connection");
                        continue;
                    }
                    out.println("150 Opening binary data");
                    try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(
                            ds.getInputStream());
                         java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(
                                 new java.io.FileOutputStream(f))) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = bis.read(buf)) > 0) bos.write(buf, 0, n);
                    }
                    ds.close();
                    out.println("226 Transfer complete");
                } else if (cmd.startsWith("DELE")) {
                    File f = resolveFtp(cwd, arg);
                    out.println(f != null && f.isFile() && f.delete() ? "250 Deleted" : "550 Can't delete");
                } else if (cmd.startsWith("MKD")) {
                    File d = resolveFtp(cwd, arg);
                    out.println(d != null && d.mkdirs() ? "257 Created" : "550 Can't create");
                } else if (cmd.startsWith("RMD")) {
                    File d = resolveFtp(cwd, arg);
                    out.println(d != null && d.isDirectory() && d.delete() ? "250 Removed" : "550 Can't remove");
                } else if (cmd.startsWith("SIZE")) {
                    File f = resolveFtp(cwd, arg);
                    out.println(f != null && f.isFile() ? "213 " + f.length() : "550 Not found");
                } else if (cmd.startsWith("FEAT")) {
                    out.println("211-Features:");
                    out.println(" SIZE");
                    out.println("211 End");
                } else {
                    out.println("502 Command not implemented");
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
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
        // 按钮盒 22 → 30 dp，容器 34 → 40 dp，间距收紧 4 dp：
        // 此前只在原方框里调内边距，尺寸没变，看起来像没动过。这次改的是结构。
        int size = dp(30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMarginStart(dp(1));
        lp.topMargin = dp(-4);
        lp.bottomMargin = dp(-4);
        iv.setLayoutParams(lp);
        iv.setImageResource(res);
        iv.setPadding(dp(5), dp(5), dp(5), dp(5));
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