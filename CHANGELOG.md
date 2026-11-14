## 1.0.5 — 2026-11-14

### 修复：音频文件点开即崩溃

**现象**：点任何音频 → 立即闪退。NPE 堆栈落在
`Player.addListener` 上，调用方是 `showAudioPlayerDialog`。

**根因**：`showAudioPlayerDialog` 里写着 `final ExoPlayer p = null;`
然后紧接着 `p.addListener(...)`。周边逻辑（状态监听、切歌、进度、
循环列表、错误处理、按钮绑定）全都写好了，唯独「创建播放器」这一步
留成桩代码没替换。

**改动**：
- 改用 `PlayerCore.create(ctx)` 真正创建（全项目唯一 ExoPlayer 出口，
  自带 audio focus 管理与耳机拔出自动暂停）
- 切歌前释放旧实例（`stop()` + `release()`），
  否则每切一首多占一个 codec，连听几十首会卡
- 弹窗入口补 `PlayerCore.init(ctx)`——音频弹窗不经过 MediaPlayerActivity，
  `zip://` 虚拟路径解出需要 cacheDir

**影响面**：`FileManagerModule.showAudioPlayerDialog`。

### 加固：reloadAll 双层异常隔离

`reloadAll` 是「点击 → 刷新」链路的公共出口，任一窗格 NPE 会崩掉整个 App。
改为外层 try-catch + 逐窗格独立 try-catch，单个窗格失败只记 `HyReload` 日志。

### 新增：文件列表下拉刷新

`buildPane` 返回值从 `RecyclerView` 改为外包的 `SwipeRefreshLayout`。

- 触发距离 **96dp**（默认 64dp 偏灵敏，轻微下拉就出转圈；
  96dp ≈ 1.5 个列表行高，需要明确的下拉意图）
- `setOnChildScrollUpCallback` 显式接管「只有滚到顶部才允许下拉」，
  否则往下滑列表会误触刷新
- 双窗格各有独立刷新容器，只刷新本窗格
- 压缩包内刷新先清 `archiveItemsCache`，否则读到旧目录树
- 刷新后 400ms 收起转圈（`reload` 是同步 `listFiles`，无完成回调）
- 新增 `Pane.swipeRefresh` 字段

**新依赖**：`androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`。

### 变更：压缩包内图片改为按需解出 + 退出即清

**① 点击哪张解哪张**

`ViewPager2` 的 `offscreenPageLimit` 最小为 1（设 0 也会当 1），
而 `onBindViewHolder` 在页面被预创建时就调 `bind()` 解码 ——
这是「看一张解三张」的根源。

改法：`onBindViewHolder` 只 `setPendingPath()` 记路径不解码；
真正解码由 `onPageSelected`（用户翻到该页）与首屏初始化触发 `ensureLoaded()`。

**② 退出销毁本次缓存（不管是否还在预览界面）**

此前只在 `maybePurgeArchiveCache`（离开 `zip://` 路径时）清。
用户「从压缩包看图 → 退出查看器 → 退出文件管理器」这条路径上，
缓存会一直留到「离开压缩包」那一刻。

新增 `ImageViewerActivity.onDestroy()`：从 `gallery` 的 `zip://` 路径
提取涉及的包逐个 `purgeArchive`。只清本次用到的包，不影响别的压缩包。

**③「解出中」toast 改弹窗**

新增 `showLoadingDialog(message)`：不确定进度条 + 文案，不可取消
（解出中取消会留半截缓存文件，支持中断超出本次范围）。

三处替换：解到对侧（`HyVqZipToOther`）、加密条目解密解出（`HyVqZipEnc`）、
打开压缩包内条目（`HyVqZipEntry`）。完成后统一 `dismissQuiet(loading)`
（未 show / 已 dismiss / Activity 已销毁都不抛）。

### 修复：状态栏看不清 + 顶栏上方白带

用户两个反馈，各一个根因：

**① 状态栏看不清**

`MainActivity` 在 `module.createMainView()` **之后**执行一段
`setAppearanceLightStatusBars(false)`，把模块刚按用户设置算好的图标明暗
覆盖回浅色。用户 `statusBarAlpha=0`（透明）时，模块判定「无背景图 →
不透明浅色底 + 深色图标」，紧接着被改成浅色图标 → 白图标压浅底。

修法：删掉那段覆盖，明暗全权交给模块。

**② 顶栏上方白带**

与 ① 同源：模块原逻辑「`statusBarAlpha < 30` 就切浅色图标」，
但没考虑**没有背景图**时 alpha=0 意味着「不画底色、透出浅色主题背景」，
而非「透出照片」。

修法：
- 无背景图时按不透明处理（`effectiveAlpha = 100`）
- 图标明暗改由**最终底色的实际亮度**决定（WCAG 整数近似），
  不再靠「有没有背景图」猜 —— 背景图也可能是一张纯浅色图片
- 保留「底色确实透明时用浅色图标」的保守分支

### 修复：弹窗模糊背景白带

这条白带查了三轮，每轮根因不同，三个修复叠加才彻底解决：

**① 模糊图整体下移一个状态栏高**

只加 `FLAG_LAYOUT_IN_SCREEN` 不够——窗口矩形从 y=0 算，
但内容区仍被系统 inset（高度 = 屏高 - 状态栏 - 导航栏），
配 `setGravity(CENTER)` 会垂直居中。
必须 `setDecorFitsSystemWindows(window, false)` 让内容区铺满整个窗口矩形。

**② `BitmapDrawable + Gravity.FILL` 不等于拉伸**

FILL 在宽高比与窗口不一致时按「填满」规整（等价 `CENTER_CROP`），
横向先填满、纵向超出部分居中裁掉 —— 实测顶部被切约 100px，
表现为顶栏只露下半截。改为自定义 Drawable 顶部对齐等比填充。

**③ `decor.draw()` 拿不到状态栏像素**

`decor.draw()` 只画 View 树自己，状态栏是系统在另一层画的，
decor 那里透明。透明区模糊后仍透明，被 `liftBrightness` 提亮 →
成品白带（实测 224px）。

改用 `PixelCopy` 抓窗口真实合成像素。**关键坑**：PixelCopy 回调若绑
主线程 Handler，而调用方又在主线程 `await` 阻塞 —— 主线程永远空闲不下来
执行回调，必然超时。必须绑独立 `HandlerThread`。80ms 超时回退 `decor.draw`。

另：`liftBrightness` 支持负值（压暗）。浅色主题下截图近白，
再提亮会与弹窗面板糊成一片；改为按底色平均亮度自适应
（暗底 +0.18 提亮，亮底 -0.08 压暗）。

### 修复：共享顶栏降高

54dp → 46dp，路径点击区撑满、字号 12 → 13sp。

### 修复：ZIP 相关一批

- 压缩包内目录层级错误，并列显示「测试A」「测试A/测试B」
- 压缩包内图片可滑动切换 + 列表显示缩略图
- 压缩包内视频播放器显示「1/2」（窗格虚拟路径 + 当前临时文件两项）
- 视频缩略图黑帧：取 10% 时长 + `isAllBlack` 检测
- 缓存名碰撞
- 新增 `archive/ZipCache.java` 统一管理解出缓存，
  退出压缩包 / 退出应用即销毁

### 其它

- README 版本号此前停留在 1.0.3，本次补到 1.0.5

## 1.0.4 — 2026-11-12

### 新增：图片查看器底部功能栏

打开图片后，点击画面唤出顶栏与底部功能栏（再点隐藏，无超时）。底部栏三个操作：

- **分享**：调系统分享面板，任意应用可接收
- **删除**：确认后按主界面设置移入回收站（可恢复）或直接删除，列表自动刷新
- **设为背景**：一键把当前图片设为 App 全局背景或文件列表页背景

### 新增：左右滑动切换同目录图片

在文件列表里打开图片后，直接**左右滑动即可查看同目录的其它图片**，不用返回列表再点：

- 底栏显示「01/32」两位计数，一眼看出看到第几张、一共几张
- 滑动到底自动回绕
- 切换图片有淡入淡出过渡，不刺眼
- 删除、设为背景等操作跟随当前显示的图片
- 图片放大拖动时滑动仍是拖图，快速甩动才切换

### 新增：图片高清模式

查看大图时**长按图片**切换高清模式（再长按关闭）：

- 普通模式：按屏幕尺寸采样，加载快、省内存
- 高清模式：接近原图分辨率 + 真彩色，放大看细节、渐变、文字不发虚
- 信息位会标注「· 高清」

### 新增：相册浏览页

侧边栏 → 工具 → **图片查看器**：按扫描路径浏览相册图片。

- 路径标签行可切换目录，也可点「＋」添加
- 目录分组网格：缩略图 3 列正方形，本层图片一组、子目录一组
- 点任意图进入查看器，左右滑切换同目录图片
- GIF 图片右下角带「GIF」标记，点开后在查看器里直接播放动图
- 设置 → 工具 → 图片查看器：管理扫描路径（点路径删除、快捷添加常用路径）
- 默认扫描：DCIM、Camera、Pictures、Screenshots、Download（存在才显示）

### 新增：EXE / DLL / ICO 真实图标显示

以前这些文件在列表里统一显示一个通用图标，现在能直接看到程序自己的图标：

- **.exe / .dll**：读出 Windows 程序内嵌的图标并显示（兼容 32/64 位、多尺寸、PNG 压缩图标）
- **.ico**：直接解析图标文件（之前虽然归为图片但实际无法显示）
- 加壳或损坏的文件自动回退到通用图标，不会报错

### 界面变化

- 进入时白底 + 功能栏展开，点击画面切换黑底沉浸（带渐变过渡）
- 功能栏常驻，不再自动隐藏

### 修复

- **打开图片闪退**：部分机型上点开图片会直接崩溃（主题样式不兼容）
- **更新页发布日期显示空白**：部分版本的说明缺少发布日期时，「检查更新」会显示"发布日期："（冒号后面空），现在显示"未知"
- **弹窗文字看不清**：删除确认、设为背景选择框在深色背景下黑字黑底，现在改为浅色文字
- **查看器进入时的显示错位**：打开图片时背景与功能栏状态不一致，现在进入即为白底 + 功能栏展开

---

## 1.0.3 — 2026-11-10


### 新增：存储管理页

设置 → 基础设置 → 存储管理。

- **存储总览**：已用 / 总量 / 百分比 / 可用，配一条进度条
- **应用占用明细**，每项独立显示大小并可单独清理：

| 项 | 清理策略 |
|---|---|
| 缩略图缓存 | 直接清 |
| ZIP 浏览缓存 | 直接清 |
| 更新包缓存 | 直接清 |
| 编辑器草稿 | 二次确认 |
| 背景图 | 二次确认，只清当前未使用的旧图 |
| 回收站 | 二次确认 |
| 应用私有数据合计 | 只读 |

- **一键清理**：只清三项缓存，草稿 / 背景图 / 回收站不受影响
- 统计跑后台线程（`FileOps.dirSize` 递归遍历，回收站上万文件时主线程会卡）

新增 `fmtStorageSize()`：统计失败显示 `—` 而非 `-`，并补 TB 档。
其余刻度与 `formatSize` 一致，避免同一屏两种单位口径。

### 新增：Android/data 访问（零宽字符路径别名）

Android 11+ 把 `/sdcard/Android/{data,obb,media}` 交给 FUSE 做**路径字符串**拦截，
即使有 `MANAGE_EXTERNAL_STORAGE`，`listFiles()` 对这些目录仍返回 null。
但目录真实权限是 `drwxrws--x`（有 x 位），inode 权限从未收紧 ——
在 `Android` 与 `data` 之间插入 U+200B 后，FUSE 匹配不上而 VFS 能解析到同一 inode。

本机 Android 16 实测：列目录 / 读 / 写 / 删 **全部通过**。

- 新增 `access/PathAlias.java`：`io()` / `real()` / `isGuarded()`，幂等
- `AccessRouter.list()` 失败后换别名重试；条目 path 用**真实父路径**拼回
- `AccessRouter.fileOf(FileEntry)` / `fileOf(String)` 作为唯一 IO 入口
- `FileOps` 的 copy / move / delete / mkdir / createFile / rename 全部先转别名
- `FileManagerModule` 里 24 处 `new File(e.path)` / `new File(pathOrUri)` 改走 `fileOf`

**零宽字符绝不泄漏到 UI**：`FileEntry.path` 始终是真实路径。

**风险**：这是利用未修复的系统行为，不是官方支持能力。
`PathAlias.java` 顶部有完整注释说明。Google 一旦修复即失效，回退到「无权限」空列表。

### 新增：状态栏透明 + 可调不透明度

- `setDecorFitsSystemWindows(false)` + `setStatusBarColor(TRANSPARENT)`，背景图铺到状态栏后面
- `body` 与 `navOverlay` **各自**加状态栏 inset（`root` / `rootContainer` / `bgImageView` 不加，
  否则背景图会被一起推下去，状态栏区域反而露白）
- 新增 `PREF_STATUS_BAR_ALPHA` + 「状态栏不透明度」滑块（0 = 透明，100 = 实色）
- 图标明暗按 alpha 决定：alpha < 30 强制浅色图标（透明底是照片，偏暗）
- 图标控制权从 `MainActivity` 移交给模块，避免两处打架

**踩坑记录**：`createMainView` 阶段 DecorView 还没 attach，
`getRootWindowInsets()` 返回 null → 回退 `status_bar_height` 资源值。
ColorOS 上该值是 119px 而真实值 141px，差 22px 就是「顶栏与状态栏有一点重叠」。
修法：`body.addOnAttachStateChangeListener` 里 attach 后重算一次，再 `post` 一帧确认。

### 新增：包名文件夹应用图标角标

`Android/data`、`obb`、`media` 的直接子目录若名字像包名，
文件夹图标右下角叠该应用图标。仅视觉标记，不改变点击行为。
设置 → 文件管理 可关闭，默认开。

- 图标**必须缓存**：`onBindViewHolder` 滚动时整屏重绑，包名解析走
  `PackageManager` IPC，几十行就是几十次跨进程调用，直接掉帧
- 查不到（未安装 / 不可见）静默跳过，不报错不重试
- manifest 加 `QUERY_ALL_PACKAGES`，否则系统应用图标拿不到

### 修复：底部功能栏遮挡

`BOTTOM_NAV_RESERVE` 固定 62dp 不够：`opBar`（多选栏，内容撑高约 100dp）
与 `bottomNav`（固定 48dp + 8 边距）共用同一个值，多选时必然遮住最后几行。

改为 `updateListBottomReserve()` 量当前可见栏的 `getHeight()`，
三处触发（启动 / 多选切换 / 栏可见性变化），`post` 一帧等布局完成。

### 修复：.apk 点击被当成压缩包

`.apk` 本质是 zip，`isZip()` 对它返回 true，点击先命中压缩包分支直接进内部浏览，
用户看到的 是 zip 目录树而不是安装界面。

- 新增 `isInstallerExt()`：apk / aab / xapk / apks / apkm / zipx
- 点击分发里排在压缩包分支**之前**
- apk / xapk → `installApkByName`（含 APK 信息预检）
- aab / apks / apkm / zipx → 打开方式弹窗（系统安装器不认）

### 新增：设置页分组卡片布局

设置主页改为三张分组卡：**基础设置** / **工具** / **软件**。
每个标题下面是选项列表，行间用 1px 分割线隔开，
不再是「标题一张卡 + 每行一张卡」。

实现要点（踩过两个坑）：

- `settingsGroup` 记录当前分组卡，`sectionHeaderInCard()` **无条件**建卡。
  原先加了「有背景图才分组」的条件，而本机没有背景图，逻辑整个静默失效
- `addSettingsRow` / `addInfoRow` / `addSliderRow` **三处必须同步改**，
  只改其中一两个会出现「分组卡有了但行还在卡外」
- `addGroupDivider()` 不能因 `childCount <= 1` 提前 return ——
  标题是第 0 个孩子，提前 return 会让标题与首行之间少一条线
- `showInSettingsDialog()` 前后置空 `settingsGroup`，
  否则关于页这类「先加行后加标题」的页面会继承上一页的分组

### 修复

- **存储管理页「内部存储」只剩标题**：容量数字 / 百分比 / 进度条
  直接 `box.addView` 落在分组卡外面，设了背景图后白字压白墙。
  改用 `addToSettingsGroup()` 统一塞进卡片
- **关于页正文「有背景图才套卡」**：无背景图时裸放，
  卡片不透明度滑块对它有作用 / 没作用，行为漂移。
  改为无条件套卡
- **设完背景图不立即落盘**：只在 `onDetach` 落盘，
  设完马上被后台杀掉时文件名就丢。补 `persistState()`

### 移除

- 删除成功的 toast（「已移入回收站 N 项」/「已彻底删除 N 项」）。
  删除是高频操作，列表条目立即消失本身就是反馈。**失败仍然弹**

---

## 1.0.2 — 2026-11-07

主题系统重构 + 一批状态/持久性缺陷修复。

### 新增：主题设置（原「个性化」）
- **全局背景层**：`ImageView` 铺满 `rootContainer` index 0，上层页面半透明叠着。
  逐页设背景会在切页时跳一下，一层铺满则完全稳定。
- **三档滑块**：
  - 背景不透明度 0-100%（改 `ImageView.alpha`，不重渲染）
  - 背景模糊度 0-25（先 1/4 缩小 → box blur → 放大，快一个数量级）
  - 卡片不透明度 0-100%
- **选图双入口**：系统照片选择器 + 自建文件列表（单列，只显示目录与图片）
- **解码采样**：先读边界算 `inSampleSize` 再解码。4000×3000 全尺寸在堆里 48MB。
- **解码与模糊移出主线程**：`HyBgDecode` 线程 + `bgLoadSeq` 序号丢弃过期结果。

### 新增：文件管理主题
文件列表页独立于全局主题：
- 独立背景图（`fmbg_` 前缀，不覆盖全局 `bg_`）
- 独立背景不透明度/模糊度、文件行卡片不透明度、列表密度
- 取值器 `fmActiveXxx()`：开关关闭时自动回落全局值，调用点不判开关
- 选图分流：`pickForFmTheme` 标志决定落点

### 卡片化
| 元素 | 改动 |
|---|---|
| 共享顶栏 | `topBarSurface()`，alpha 跟 `cardAlpha` 同步 |
| 文件列表行 | `FileListAdapter.rowAlpha`，`makeBg` 按它压 alpha |
| 滑块行 | `wrap` 原先无背景，改 `cardSurface` + `CARD_TAG_KEY` |
| `buildSimpleRow` / `addPickerRow` | 静态 `bg_ripple_round` → `cardSurface` |
| `sectionHeader` | 套 `cardSurface`；无背景图时仍返回裸标题 |
| 关于页标题/正文 | 套 `cardSurface` |
| 更新页版本卡 | 硬编码色 → `cardSurface(10)` |

**标题与内容分层**：标题用 `colorSurfaceContainerHigh` + 圆角 8dp，
内容用 `Low` + 10dp。递归重刷读 `CARD_TAG_HDR_KEY` 保留标题色。

**套卡片后压平 padding**：`UiKit.sectionHeader` 自带 `上 12 / 下 6`，
套进卡片后变上 14 / 下 8，文字偏下。卡片内归零，由卡片给对称 10dp。

### 修复
- **侧边栏崩溃**：`ClassCastException: ImageView cannot be cast to ViewGroup`。
  背景层插 index 0 后，「第一个非 navOverlay 的子 View」成了 ImageView。
  两处（打开 + 关闭路径）都加 `instanceof ViewGroup`。
  关闭路径原来被 `try-catch` 吞掉，症状是「模糊清不掉」。
- **六个滑块不落盘**：只改内存字段，没调 `savePrefInt`，重启丢失。
- **文件管理页状态不同步**：「隐藏文件」「缩略图」只改变量 + toast，
  不重建 UI，状态文字与圆点是构建时写死的。
- **抽屉展开项无容器**：`trashHeader` 用静态 `bg_ripple_round`，
  展开区无视觉归属。标题行卡片化 + 展开区左侧缩进 12dp。
- **相册选图打开文件树**：`ACTION_OPEN_DOCUMENT` 是 DocumentsUI。
  改用 `ACTION_PICK_IMAGES` + `setPackage("com.android.photopicker")`，
  回退 `PickVisualMedia`，再回退自建列表。
- **设置返回层级**：`handleBackPressed` 一刀切 `setContentMode(FILES)`，
  任意子页按返回都跳回文件列表。改为子页 → 主页 → 文件列表。

### 移除
- 文件列表三点菜单的「缩略图」（已移至设置 → 文件管理）
- 文件列表三点菜单的「切换到网格视图」

### 已知限制
- 网格视图入口已移除，`gridMode` 的持久化与渲染逻辑仍保留（旧设置不会崩，
  但无法再切换）。
- 卡片透明度与侧边栏不透明度只作用于设置页/文件列表，弹窗需重进才生效。

---

# 更新日志（开发者版）

> 本文件是**技术记录**，给开发和排查用，保留实现细节。
> 用户在 App「设置 → 软件更新」里看到的是 `versions.json` 的 `body` 字段 ——
> 那份按**普通用户视角**写：只讲能感知到的变化，不出现类名、API 名和技术术语。
>
> 发版时两处都要更新：
> - `versions.json` → 面向用户的通俗说明
> - `CHANGELOG.md` → 面向开发者的完整技术记录

---

## 1.0.1 — 2026-11-05

22 个提交。以「修数据丢失」和「重做导航手感」两条线为主。

### 数据安全（12 项）

| # | 问题 | 后果 |
|---|---|---|
| 1 | `copyRecursive` 遇 `listFiles()==null` 静默跳过 | 剪切时源被删、目标缺块、报成功 |
| 2 | 粘贴前 SAF 列举失败 | 静默覆盖目标同名文件 |
| 3 | **回收站失败降级为物理删除** | 勾了「移入回收站」→ 实际永久删除 |
| 4 | 回收站半截文件无 `.trashmeta` | 恢复得到残缺数据 |
| 5 | `deleteTree` 跟随符号链接 | `ln -s . loop` → StackOverflow，溢出前已删上层文件 |
| 6 | Guard 压缩比防线是死代码 | `compressedBytes` 与 `total` 同源，比值恒 ≈1 |
| 7 | RAR 分支完全绕过 Guard | 条目数/单文件/总量三条全失效 |
| 8 | 「放弃修改」后草稿复活 | 用户永远丢不掉一次放弃 |
| 9 | 0 字节草稿被跳过 | 「把文件清空」这个修改永久丢失 |
| 10 | 草稿写入非原子 | 崩溃时 meta 与文件错位，旧草稿套到错标签 |
| 11 | 无 `onPause` 落盘 | 编辑中按 Home 被 OOM 杀掉，草稿一次都不写 |
| 12 | 多选删除失败不报具体原因 | 用户不知道哪个文件失败、为什么 |

修法要点：
- `copyRecursive` 改为抛 IOException；三个调用方全部核实安全
- 回收站失败**绝不降级**，报错并保留原文件；copy 异常时先 `deleteTree(dest)`
- `isSymlink()` 显式判断（API26+ 用 `Files.isSymbolicLink`），栈递归改显式栈
- `checkZipRatio4j()` 用 `zip4j` 的 `FileHeader.getCompressedSize/getUncompressedSize`
- RAR 包一层计数 `OutputStream` 过 `guard.addBytes`
- `finishEditor(boolean discardDrafts)` + `clearEditorDrafts()`
- 草稿写 `.new` → 全部成功 → rename → 清本批外；`renameTo` 失败保留 `.new`
- 新增 `FileManagerModule.onPause()`，IO 在后台线程

### 崩溃 / 卡顿（5 项）

- `runningTasks` 静态 int 无同步 → 计数可负 → 前台服务永不停止。改 `AtomicInteger`
- `archiveItemsCache` 无上限 → 改 `LinkedHashMap(accessOrder=true)` LRU，上限 8
- 缩略图缓存 32 位 `hashCode()` → 出生悖论下串图。改 SHA-256 前 128 位
- ReDoS：`replaceCurrent`/`replaceAll` 无检查。新增 `safeCompile()`
- 撤销栈只限条数 200 → 2MB 文件约 800MB 堆。改条数 + 32M 字符双上限

### 软件更新（5 项）

- 新增「软件更新」「关于」两页
- 四源优先级：Gitee → jsdelivr → gh-proxy → GitHub 原始
- 取 `versionCode` **最大**项（不取数组首个），与版本条数解耦
- 启动时自动检查（延迟 3s、失败静默、同版本只提示一次）
- 下载 APK 到 cacheDir 并调起安装；先查 `canRequestPackageInstalls()`
- `stripMarkdown()`：弹窗不再显示 `##` / `**`

**1.0.1 重发补丁**（首版发布后用户实测发现）：
- 权限弹窗按钮用 `((View)v.getParent()).getTag()` 取 Dialog，但从未 setTag
  → 取到 null → `dismissWithAnim(null)` 无效 →「取消」点不动，
    「去开启」跳设置后弹窗不关，回来仍盖在最上层
- 改为弹窗持有自身引用 `pkgInstallDialog`；「去开启」先 dismiss 再跳
- 新增 `pendingInstallInfo` + `onResume → resumePendingInstall()`：
  授权返回后延迟 400ms 自动续上下载；未授权则清待办并提示

### 导航（8 项）

- 顶栏图标三态：文件页/设置主页=☰，设置子页=←
- `currentSettingsPage` 作为层级真相源，`showInSettingsDialog(title, ...)` 统一写入
- **删掉 5 处页内返回行 + `buildSettingsBackRow` 方法**（净减 1240 行含格式化）
- 侧边栏「本地」目录项：先 `setContentMode(FILES)` 再跳
- 侧边栏「文件管理」：直接回文件列表，**不动路径与焦点**
- 文件 ↔ 设置切换加 180ms 位移+淡入
- 翻目录整体交叉淡化（与逐项 stagger 分离）
- 删除 FTP/HTTP（619 行死代码）

### UI

- 底部功能栏固定 48dp；列表底部 padding 62dp + `clipToPadding=false`
- 同步键重做成单个 40×40 圆形交换图标，带焦点方向判断
- 列表密度 SeekBar → 左右布局 + 锚定小弹窗（右对齐 + 1px 边框）
- 卡片圆角 14 → 10 → 6dp
- `toolIcon` 36 → 48dp，同步箭头 32×16 → 40×32

### 权限

- 补 `INTERNET`（1.0.0 的更新检查因此全部失败）
- 删 `ACCESS_NETWORK_STATE`（FTP 删后零使用）
- `REQUEST_INSTALL_PACKAGES` 保留（现在真的需要）

### 文档

- `versions.json` 面向用户；`CHANGELOG.md` 面向开发者；`DEVLOG.md` 累积未发布
- `docs/` 终端与终末地残留按准则 5 标注失效而非删除

### 未实测

- 回收站跨分区失败路径
- RAR 炸弹拦截（无样本）
- 符号链接环删除
- 按 Home 后强杀恢复草稿
- 下载并安装完整链路
- 启动自动检查（当前已最新，不会弹）

---

## 1.0.0 — 2026-11-04

首个正式版。

### 新增
- 软件更新（设置页）：读取 `versions.json` 取 `versionCode` 最大项，与发布顺序/版本条数解耦；GitHub 优先、Gitee 回落
- 关于页：版本信息、仓库地址、开发说明
- 压缩包内部浏览扩展到 7z / rar / tar 家族
- 长任务前台服务（dataSync），切后台不再被杀
- 系统返回键接管：多选 → 侧边栏 → 设置页 → 压缩包内逐级 → 历史栈 → 退出
- RecyclerView 三项优化：固定尺寸、视图缓存 10、双窗格共享回收池
- 错误提示改可关闭弹框（47 处失败类 Toast）

### 数据安全
- 修复 `copyRecursive` 遇无权限子目录静默跳过 → 剪切时源被删、目标缺块、报成功
- 修复粘贴前 SAF 列举失败 → 静默覆盖目标同名文件
- 修复多选删除失败不报告具体原因
- 修复剪切时 SAF 源删除失败被静默（源留两处）
- 修复回收站路径多插一层、目录结构被拍平、恢复路径靠反推

### 功能修复
- 修复加密包解密链路完全断裂（加密压的 7z 本 App 打不开）
- 修复压缩炸弹防护三处失效（7z 绕过 guard / 单流无 guard / 压缩比死代码）
- 修复归档预览四处缺陷（扩展名白名单 / 7z 时间戳 / tar 的 `./` 前缀 / 子目录双斜杠）
- 修复「解到对侧」整包解压 → 定点提取
- 「打开方式」对文件夹禁用

### 崩溃/卡顿
- 修复正则 ReDoS（`(.*a){20}$` 28 字符 25 秒，主线程）
- 修复属性面板主线程枚举目录
- 修复 `childCount` 全量读内存（十万项目录 OOM）
- 修复 `reload` 一次扫三遍 `listFiles`
- 修复对话框高度无上限导致按钮不可达
- 修复 PopupWindow 泄漏（切主题残留）
- 修复折叠面板需要点两次
- 修复菜单禁用项反而有动效（ripple 与 setClickable 无关）
- 修复 `commons-codec` 未声明导致所有 tar 家族压缩运行期 NoClassDefFoundError

### 移除
- 终端功能
- 终末地主题

---

## 0.2.0-beta.3 — 2026-10-31

第三轮八代理全量审查后的修复（26 项）。详见 `versions.json`。

## 0.2.0-beta.2 — 2026-10-30

第二轮八代理全量审查后的修复，含 8 条回归修复。详见 `versions.json`。

## 0.2.0-beta.1 — 2026-10-30

第一轮八代理全量审查后的修复。详见 `versions.json`。

## 0.1.0-dev — 2026-10-25

初始开发版。详见 `versions.json`。
