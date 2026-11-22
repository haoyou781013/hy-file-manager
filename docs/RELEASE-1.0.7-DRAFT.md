# 1.0.7 更新日志草稿（未发布）

> **状态**：待发布。版本号 1.0.7 / versionCode 13。
> **用途**：发布时把「用户版」正文填入 `versions.json`，「开发者版」并入 `CHANGELOG.md`。
> **依据**：`git log 1ee6867..HEAD` 的 15 个提交 + DEVLOG 累积记录。

---

## 用户版正文（直接填 versions.json 的 body）

这个版本主要在**重做视频播放器**，另外修了一批「平时不显眼、但会在特定情况下咬你一口」的问题。

## 视频播放器：几乎整个重做了

### 手势现在跟主流播放器一致

以前双击屏幕任意位置都是暂停。现在改成：

- **双击左半边** → 后退 10 秒
- **双击正中间** → 暂停/继续
- **双击右半边** → 前进 10 秒

暂停留在中间，是为了不让用惯双击暂停的人找不到地方。每次操作都有屏幕中央的提示，不会再出现「我点了但不知道生效没有」。

从屏幕最左右边缘 16 像素内开始的横滑**不再响应快进**——那块区域留给系统的返回手势，以前从边上滑会同时触发两件事。

### 进度条重做到能真的定位

以前进度条只有 4 像素粗、拖动时只改左边那个时间数字。看两小时的视频时，一个像素约等于 10 秒，手指抖一下就跳过好几分钟。

现在：

- 进度条加粗到 6 像素，可触摸区域扩大到 40 像素（**手指不用瞄得那么准**）
- 拖动时手指上方会出现一个**跟着走的精确时间气泡**，想看第 42 分钟就真的能停在第 42 分钟
- 圆点在按下时会放大，让你知道「抓住了」
- 已缓冲的部分显示得更明显，网络视频能看出哪里已经下载好了

### 新增：播放器设置

**设置 → 工具 → 视频播放器**

之前所有参数都是写死的，你觉得「控制条 3 秒就消失，来不及点」也没地方改。现在可以调：

- **控制条自动隐藏**（可关掉，让它一直显示）
- 自动隐藏的等待时间（1.5 / 3 / 5 / 10 秒）
- 单击画面是「切换」还是只「叫出来」
- 长按加速的倍速（1.5× / 2× / 2.5× / 3×）
- 双击跳转的秒数（5 / 10 / 15 / 30 秒）
- 横向滑动的灵敏度（整屏宽 = 1 / 2 / 3 / 5 分钟）
- 一键恢复默认

### 修：视频播完后再点播放键没反应

以前播完之后点播放键毫无反应，只能退出去重进。这是播放器状态没被正确处理导致的，现在播完点一下就会从头再播。

### 修：长按加速会卡在 2 倍速

长按加速后如果手指滑出屏幕边缘、或直接切到后台，会一直停在 2 倍速退不出来。现在无论怎么打断都会恢复。

### 修：底部控制条按钮互相重叠

手机屏幕窄的时候，「上一个」被左下角的菜单按钮压住、「下一个」被倍速按钮压住。现在改成三段式布局，空间不够时挤压而不是覆盖。

### 其他

- 提示文字对比度提高（深色画面上不再灰蒙蒙）
- 弹幕加了描边，白字压在雪景、白墙这类亮画面上也能看清

---

## 修复：老系统上会闪退的地方

这个 App 最低支持 Android 6.0，但有 9 处调用只在高版本系统才有的接口，在 Android 6～11 上会崩溃。最严重的一处是**一进「设置 → 权限管理」就闪退**。

已全部加上版本判断，老系统上会优雅降级而不是崩溃。

## 修复：缩略图在老系统上会整个失效

缩略图加载用了一个 Android 7.0 才有的类，而项目的兼容库其实没有真正覆盖它。这会导致老系统上**缩略图功能整块崩掉**。已改成只依赖 Android 6.0 就有的实现。

## 修复：某些语言的系统下格式识别全错

土耳其语等语言环境下，程序把大写字母 `I` 转小写会得到 `ı`（不是 `i`）。这会让**所有文件后缀判断失效**——图片认不出、压缩包打不开。

已统一改为不受系统语言影响的转换方式。

## 修复：看大图时可能卡顿

动图（GIF）的解码原本在主线程做，一张多帧动图要几百毫秒，期间界面完全卡住。已挪到后台线程。

## 修复：设置页点一下整页闪一下

上一版加的播放器设置项，每点一个开关整个页面都会重建一次。现在只更新那一行，不再整页刷新。

## 一处能力说明

**DASH（.mpd）格式目前不能播**。之前文档里写成支持，实际缺必需的模块，会报「不支持的格式」。已从说明里更正，需要的话下个版本补上。

---

## 开发者版（并入 CHANGELOG.md）

### 视频播放器架构重做

分三阶段完成，方案文档见 `docs/PLAYER-REDESIGN-V2.md`。

**阶段 1 · 状态机骨架**（`b080b27`）

新增 `PlayerUiState`（17 字段不可变状态）/ `PlayerEvent`（15 种语义事件）/
`PlayerReducer`（纯函数 `reduce`）。替代原先散落在 Activity 里的 30+ 可变字段。

Reducer 刻意不接 `Context` / `Player` / View，因此可在 JVM 上直接单测。
测试抓到一处不变式缺陷：`locked ⇒ controlsVisible=false` 只在经过
`LockToggled` 事件时成立，外部直接置 `locked=true` 时控制条仍可见。

**阶段 2 · 手势控制器**（`c80401a` + `814e446`）

抽出 `GestureSink`（语义出口）+ `GestureController`（223 行，只识别不执行）。
删除 113 行内联手势与 4 个散落字段。

行为变更：双击分区对齐 YouTube / B站（左 1/3 后退、中 1/3 暂停、右 1/3 前进）；
新增边缘 16dp 保护区让位给系统返回手势。

回调参数传「比例」而非绝对值（`onSeekPreview(deltaRatio)`），
手势层不感知「2 分钟」这类业务参数。

测试抓到浮点边界 bug：
```
600/900 = 0.6666667f,  1f - 1f/3f = 0.6666666f
→ 0.6666667 > 0.6666666 成立 → 2/3 边界点被误判为 RIGHT
```
改用整数比较（`x*3 < width` / `x*3 > width*2`）。

**Media3 最佳实践对齐**（`b75c84e`）

- 播完无法重播：旧实现 `if (isPlaying()) pause(); else play()`，
  而 `STATE_ENDED` 下 `play()` 是 no-op。改用
  `Util.handlePlayPauseButtonAction(player)`。
- 图标状态不准：改用 `Util.shouldShowPlayButton(player)`
  （综合 `playWhenReady` + `playbackState` + `suppressionReason`）。

**一条判断被推翻**：原计划用 `isCommandAvailable(COMMAND_SEEK_TO_NEXT)`
禁用单文件的「下一个」按钮。实施时发现 `step()` 是循环语义
（`n >= length → 0`），单文件点「下一个」会重播自己，按钮本就该可点。
已移除并记录教训：调研时不该把外部经验当通用规律，须先核对本项目语义。

### 进度条重做（`e38a03c`）

量化依据：143 分钟视频 / 900px 轨道 ≈ **每像素 9.5 秒**。

- 三层差异高度：底 3dp / 缓冲 4dp / 已播 6dp
- `paddingHorizontal=0dp`（默认会预留半个 thumb，轨道变短精度再降）
- `layout_height` 固定 40dp（触摸区 = 控件高度；且圆点放大不触发重测量）
- 圆点 `selector`：常态 16dp / 按下 24dp（放大而非变色——变色会被指腹遮住）
- 新增 `seek_bubble` 时间气泡，位置经 `clampBubbleX()` 计算防边缘裁切

### 播放器设置页（`e38a03c` + `a72ddeb` + `c335e22`）

`PlayerSettings`（165 行）+ 设置页 6 项。三处用户反馈的修复：

1. **点击整页重建**：原写法走 `hideSettingsDialog()` + `showInSettingsDialog()`
   （完整建容器 + 淡入）。改为 `rebuildSettingsContent()`，
   只换内容 View 并恢复滚动位置。
2. **说明文字未进卡片**：设置页分组靠 `settingsGroup` 引用，
   `addInfoRow` 会塞进卡片，而 `box.addView(tip)` 绕过了它。
   新增 `tipText()` + `addTipToGroup()`。
3. **开关反复点同一个结果**（快照缺陷）：`boolean now = !cur.autoHide`
   里的 `cur` 是构建时快照，页面不重建就永远是旧值。
   改为 `PlayerSettings.load(ctx)` 现读。同一缺陷共 6 处
   （2 个布尔开关 + 4 个档位循环），全部修正。

### 全部修掉的 lint 问题（236 → 57，Error 21 → 0）

| 类别 | 数量 | 处理 |
|---|---|---|
| `NewApi` | 9（真崩） | 加版本守卫：`isExternalStorageManager`(30)、`DocumentsContract.isTreeUri`(24)×3、`StorageManager.getStorageVolumes`(24)、`PixelCopy`(24)、`canRequestPackageInstalls`(26) |
| `DefaultLocale` | 14 | `toLowerCase` / `format` 统一加 `Locale.ROOT` / `Locale.US` |
| `WrongThread` | 1 | GIF 解码挪出 UI 线程 |
| `DrawAllocation` | 3 | `onDraw` 里 `new Paint` / `new RectF` 改为字段预分配 |
| `InlinedApi` | 4 | `GET_SIGNING_CERTIFICATES`(28)、`ACTION_MANAGE_ALL_FILES_ACCESS`(30)、`MATCH_UNINSTALLED_PACKAGES`(24) 加守卫 |
| `UnusedResources` | 5 | 删除零引用 drawable |
| `UseSwitchCompatOrMaterialCode` | 1 | 平台 `Switch` → `SwitchMaterial` |

**一条错误注释掩盖了 P0**：`MediaPlayerActivity.java:1345` 写着
「本项目 minSdk 28」，但 `aapt2 dump badging` 实测 `sdkVersion:'23'`。
这条错误注释让所有读代码的人以为 API 24+ 调用是安全的，
9 处 `NewApi` 很可能就是这样积累下来的。已改正。

**`CompletableFuture` 其实没被脱糖**：`desugar_jdk_libs-2.1.5.jar` 里
带着该类，但实际产物 `desugar_lib_dex/release/classes1000.dex` 中只有
`ConcurrentHashMap` / `ThreadLocalRandom` / `atomic` 系列，没有
`CompletableFuture`。官方 CHANGELOG 的覆盖清单也未提及它。
已在 `ThumbLoader` 改用 `ConcurrentHashMap<String, List<Waiter>>` 自行实现去重
（双参 `remove` 只摘自己那份；`decode + writeDisk` 完成后才 remove，
避免与下一个 `putIfAbsent` 之间的并发写窗口产生半写文件）。

**lint 配置收紧**：`abortOnError` 从 `false` 改为 `true`、
`checkReleaseBuilds` 改 `true`（Error 已归零）。关闭三类已确认噪音：
`UnsafeOptInUsageError`（media3 `@UnstableApi`，非运行时问题）、
`SetTextI18n` / `HardcodedText`（项目不做 i18n）。

### God Class 局部拆解（`0d186a4`）

`FileManagerModule` 17776 行 / 455 方法。脚本扫出 58 个 `static` 方法，
逐个检查方法体是否引用实例状态，**57 个无依赖**，抽成三个类：

| 新类 | 行数 | 内容 |
|---|---|---|
| `SettingsFormat` | 141 | 时长/倍速/档位/进度/Markdown 格式化 |
| `FileTypes` | 159 | `mimeOf`（104 行查表）/ `guessExt` / `extOf` / `isImageName` |
| `ArchivePaths` | 118 | `splitZipPath` / `normalizeEntryName` / `containsGarbled` 等 |

主类 17776 → 17547（**减 1.3%**）。诚实结论：能抽的纯逻辑已抽完，
剩余约 80% 是 UI 构建代码，紧贴实例状态，真要降体量必须改架构。

价值在于**可测试性**：这些函数以前必须起整个模块，现在一行断言。
新增 45 个用例（`SettingsFormatTest` 26 + `ArchivePathsTest` 19），
其中 `splitZipPath` 的 15 种后缀逐个验证——该函数曾因后缀表漏项
导致点解压时 `StringIndexOutOfBounds` 崩溃。

### 测试

| 测试类 | 用例 |
|---|---|
| `FileNamesTest` | 24 |
| `PlayerReducerTest` | 24 |
| `GestureControllerTest` | 19 |
| `ArchivePathsTest` | 19 |
| `SettingsFormatTest` | 26 |
| `PlayerSettingsTest` | 6 |
| **合计** | **118**（1.0.6 时 24） |

### 其他修复

- 底部控制条重叠：`FrameLayout` 三组 `layout_gravity` 叠放改为
  `LinearLayout` 三段式 + `weight`（空间不足时挤压而非覆盖）
- HUD 对比度：背景 72% → 88% 黑 + 文字加阴影
- 弹幕可读性：先描黑边（`STROKE`，宽度字号 8%）再填本色
- 设置项展开动画：`if (cur.autoHide)` 条件创建改为始终创建 + 高度动画
  （条件创建时切换是瞬间出现，无过渡）
- 文件列表圆角：每行 `setCornerRadius` 改为按位置决定四角
  （首行上圆 / 末行下圆 / 中间直角），网格模式保持每格独立
- 删除死代码 `UiKit.iconRes()`

---

## 已知问题（不修）

| 项 | 说明 |
|---|---|
| DASH（.mpd）不可用 | `PlayerCore` 设了 `APPLICATION_MPD` 但缺 `media3-exoplayer-dash` 依赖，静默失效 |
| 画中画 / 后台播放 / A-B 循环 | 未实现，见 `docs/PLAYER-REDESIGN-V2.md` 阶段 3-5 |
| God Class 17747 行 | 需改架构，非本轮范围 |
| 12 处 `ClickableViewAccessibility` | 误报：这些 `setOnTouchListener` 均 `return false` 或不消费点击，真正的点击由 `OnClickListener` / `GestureDetector` 处理 |

---

## 待办清单（下个版本候选）

- 播放器阶段 3：`PlaybackService` + `MediaSession`（后台播放 / 锁屏 / 车机）
- 播放器阶段 4：画中画
- 播放器阶段 5：A-B 循环 / 双指缩放
- DASH 支持：补依赖或移除 mpd 分支
- 文本编辑器重构（长期挂起项）
