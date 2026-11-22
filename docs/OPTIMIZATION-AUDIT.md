# 系统可优化项审查报告

> **2026-11-22 更新：P0/P3/P4 已修完。** lint 问题 236 → 71，Error 21 → 0。
> 详见文末「修复记录」。

**日期**：2026-11-20
**范围**：HY文件管理 全项目（50 个 Java 文件 / 31116 行）
**方法**：lint 全量扫描 + 代码结构度量 + 功能与文档一致性核对

---

## 概览

| 维度 | 数据 | 评价 |
|---|---|---|
| 代码量 | 31116 行 / 50 文件 | — |
| lint 问题 | **236**（21 Error + 215 Warning） | ⚠ 偏高，但多数是本地化类误报 |
| 编译警告 | **0** | ✅ 干净 |
| 单测 | 73 用例 / 4 类 | ✅ 稳步增长（此前 24） |
| TODO/FIXME | **0** | ✅ 无技术债堆积 |
| APK | 6.9 MB（含 zip/7z/rar/HLS） | ✅ 合理 |
| 混淆 | `isMinifyEnabled = true` + shrinkResources | ✅ 已开 |
| 硬编码密钥 | 无（反馈 token 已混淆且标注边界） | ✅ |
| 导出组件 | 仅 MainActivity，其余全 false | ✅ |

**结论**：代码卫生总体良好（零警告、零 TODO、零密钥泄漏），
真正的风险集中在 **9 处缺少版本守卫的高版本 API 调用**（API 23 设备会崩）和
**一个 17776 行的 God Class**。

---

## P0 · 会导致崩溃（9 处，minSdk 23 设备）

lint 报 13 个 `NewApi`，其中 4 个是 Java 库（`CompletableFuture` /
`getContentLengthLong`），已被 `coreLibraryDesugaring 2.1.5` 覆盖，
属误报。**剩余 9 个是 Android 平台 API，desugar 管不到，会真崩。**

脚本逐个检查往上 15 行有无 `SDK_INT` 判断，结果如下：

| 文件:行 | API | 调用 | 影响 |
|---|---|---|---|
| `FileManagerModule.java:7644` | **30** | `Environment.isExternalStorageManager()` | **最高危**：进「设置→权限管理」必崩 |
| `StorageStrategy.java:33` | 24 | `StorageManager.getStorageVolumes()` | 进存储列表崩 |
| `StorageStrategy.java:36` | 24 | `StorageVolume.getDescription()` | 同行 |
| `StorageStrategy.java:44` | 24 | `StorageVolume.isPrimary()` | 同行 |
| `FileManagerModule.java:10824` | 24 | `DocumentsContract.isTreeUri()` | SAF 授权判定崩 |
| `FileManagerModule.java:10848` | 24 | `DocumentsContract.isTreeUri()` | 同上 |
| `SafStrategy.java:113` | 24 | `DocumentsContract.isTreeUri()` | 同上 |
| `MediaPlayerActivity.java:1362` | 24 | `PixelCopy.request()` | 播放器截图（API 23）崩。**该处注释写着「本项目 minSdk 28」，但 `aapt2 dump badging` 实测 APK 是 `sdkVersion:'23'` —— 注释是错的，正是它掩盖了这个风险** |

另有 2 处**已有守卫**（`PackageManager.canRequestPackageInstalls` 26、
`StorageVolume` 的两处 30），lint 报警但实际安全。

**修法**（统一模式）：

```java
// 现状
boolean allFiles = Environment.isExternalStorageManager();

// 改为
boolean allFiles = Build.VERSION.SDK_INT >= 30
        && Environment.isExternalStorageManager();
```

对 `StorageStrategy` 三处，把整个 `getStorageVolumes()` 循环包在
`SDK_INT >= 24` 里，低于 24 时退回单卷（`Environment.getExternalStorageDirectory()`）。

对 `PixelCopy`（API 24），低版本退回 `SurfaceView` 的
`getDrawingCache()` 或直接提示「系统版本过低不支持截图」。

**影响面**：只在 API 23/24 设备触发。当前测试机是 Android 16（API 36），
所以从未暴露。但如果保留 minSdk 23 的承诺，就必须修。

**另一个选择**：把 minSdk 提到 24。`coreLibraryDesugaring` 注释里写着
「minSdk = 23」，而媒体播放器的 media3 1.11.1 本身要求更高。
提到 24 可一次性消掉 6 处（`DocumentsContract` ×3、`PixelCopy`、
`StorageVolume` ×3），只剩 `isExternalStorageManager`（30）需单独守卫。
代价：放弃 Android 6.0 用户（占比已很低）。

---

## P1 · 架构（God Class）

`FileManagerModule.java` = **17776 行 / 455 个方法**，占全项目 **57%**。

已有清晰的功能分区注释，说明**边界是存在的，只是没拆出来**：

| 区块 | 起始行 | 估算行数 |
|---|---|---|
| 主题/背景图 | 7891 | ~300 |
| 卡片透明度与边框 | 8200 | ~300 |
| 设置分组体系 | 8091 | ~300 |
| 问题反馈 | 4370 | ~180 |
| 相册浏览页 | 4549 | ~330 |
| 视频播放器设置 | 4880 | ~460 |
| 存储管理 | 5342 | ~740 |
| 软件更新 + 关于 | 6085 | ~1800 |
| 压缩包条目枚举 | 16178 | ~1600 |
| 内置打开方式 | 13654 | ~1400 |

**拆分建议**（按收益排序，非一次性做完）：

1. **SoftwareUpdatePage**（~1800 行）—— 与文件管理零耦合，独立成类最安全
2. **StorageManagerPage**（~740 行）—— 同上
3. **ArchivePreviewPage**（~1600 行）—— 压缩包条目枚举
4. **PlayerSettingsPage**（~460 行）—— 我刚写的，最容易抽

**为什么现在不做**：单类拆分是纯结构改动，零功能收益，但回归风险高
（455 个方法之间的隐式依赖）。应该在没有功能开发任务的窗口期做，
且每次只拆一个区块 + 装机验证。

**成本更低的替代方案**：不拆类，只把区块内的**纯逻辑**抽成静态工具类
（例如 `SettingsCardFactory`、`StorageStats`），降低文件行数但不改变结构。
风险小得多。

### 已执行：纯逻辑抽取（2026-11-22）

用脚本扫出模块里 58 个 `static` 方法，逐个检查方法体内是否引用实例状态
（`ctx` / `dp()` / `fmPrefs()` / `handler` / `UiKit` 等），结果 **57 个无实例依赖**。

按职责抽成三个类：

| 新类 | 行数 | 内容 |
|---|---|---|
| `SettingsFormat` | 141 | `fmtSeconds` / `fmtSpeed` / `nextIntOption` / `nextFloatOption` / `fmtPublishDate` / `formatDownloadProgress` / `stripMarkdown` / `displayNameOf` |
| `FileTypes` | 159 | `mimeOf`（104 行查表）/ `guessExt` / `extOf` / `isImageName` |
| `ArchivePaths` | 118 | `splitZipPath` / `normalizeEntryName` / `containsGarbled` / `isSingleStreamExt` / `tarInnerExt` / `PREFIX_ZIP` |

主类 **17776 → 17547 行（减 229，1.3%）**。

**诚实的结论：这条路的天花板很低。**

- 抽干净的纯逻辑已经抽完了（58 个 static 里可抽的都抽了）
- 剩下 17547 行里约 80% 是 UI 构建代码，紧贴 `ctx` / `dp()` / `fmPrefs()`
  等实例状态，无法整体移动
- 真要降体量必须改架构（引入 ViewModel / 拆成多个 Panel 类），
  那是数百处调用的连锁改动，需要专门窗口期与完整回归测试

**但抽取本身仍有价值**：这些函数从「必须起整个模块才能测」
变成「一行断言可测」，新增 **45 个用例**（26 + 19）。

---

## P2 · 功能与文档不一致（3 处）

### 外挂字幕已实现，但文档说「未开始」

**代码事实**（`MediaPlayerActivity.java:521`）：

```java
List<MediaItem.SubtitleConfiguration> subs = findSubtitles(path, isUri);
if (!subs.isEmpty()) {
    mi = base.buildUpon().setSubtitleConfigurations(subs).build();
    UiKit.toast(this, "已加载 " + subs.size() + " 条字幕");
}
```

`findSubtitles()` 扫描同目录同名前缀的 `.srt` / `.ass` / `.ssa` / `.vtt`，
已完整接上。

**但**：
- `README.md` 完全没提字幕
- `docs/PLAYER-REDESIGN-V2.md:15` 把「阶段 5 字幕」标为 `⏳ 未开始`

**修法**：README 补一节；V2 文档把字幕从阶段 5 移出，标注「已完成（计划外）」。

### 其余功能状态核对

| 功能 | 代码 | 文档 | 一致性 |
|---|---|---|---|
| 画中画 | 0 处引用 | 标未开始 | ✅ |
| 后台播放 / MediaSession | 0 处引用 | 标未开始 | ✅ |
| A-B 循环 | 0 处引用 | 标未开始 | ✅ |
| 外挂字幕 | **已实现** | 标未开始 | ❌ |

### 注释声称 minSdk 28，实际是 23（危险）

`MediaPlayerActivity.java:1345`：

```java
/**
 * 用 PixelCopy 从 Surface 取帧（API 24+，本项目 minSdk 28）；
 */
```

但实测：

```
$ aapt2 dump badging app-release.apk | grep sdkVersion
sdkVersion:'23'
```

`app/build.gradle.kts:11` 也明确写着 `minSdk = 23`。

**这条错误注释的危害**：它会让所有读代码的人（包括我）以为
`PixelCopy` 这类 API 24+ 的调用是安全的，从而不去加版本守卫。
**P0 里的 9 处 NewApi 很可能就是这样积累下来的。**

**修法**：注释改为「API 24+，本项目 minSdk 23，需版本守卫」，
并按 P0 加守卫。

---

## P3 · 代码质量（可批量修）

| 类型 | 数量 | 说明 | 建议 |
|---|---|---|---|
| `SetTextI18n` | 108 | `setText("共" + n + "项")` 拼接 | 项目只做中文，**可不修**。若将来做 i18n 必须清 |
| `HardcodedText` | 22 | 布局里写死中文 | 同上 |
| `DefaultLocale` | 14 | `String.format` 未指定 Locale | **值得修**：阿拉伯语/土耳其语环境会出乱码。统一加 `Locale.US` |
| `ClickableViewAccessibility` | 12 | 自定义触摸未实现 `performClick` | 影响 TalkBack。修法简单但要改 12 处 |
| `ApplySharedPref` | 9 | 用 `commit()` 而非 `apply()` | **多数是有意的**（关键设置要立刻落盘防丢），但可逐个确认；`PREF_HANDLER` 已包了后台线程，实际不卡 UI |
| `NotifyDataSetChanged` | 8 | 全量刷新 | 文件列表刷全量是合理的（排序/过滤会整表变），不改 |
| `UnusedResources` | 5 | 未引用资源 | 可清理 |
| `DrawAllocation` | 3 | onDraw 里 new 对象 | 值得修（每帧分配 → GC 压力） |

**最高性价比**：`DefaultLocale` 14 处（真 bug 风险）+ `DrawAllocation` 3 处（真性能问题）。

---

## P4 · 健壮性

### `ImageViewerActivity.java:393` 在 UI 线程解码

```java
android.graphics.drawable.Drawable d =
        android.graphics.ImageDecoder.decodeDrawable(src, ...);   // UI 线程
```

lint 标 `WrongThread`。大图解码耗时数百毫秒，会造成可见卡顿（ANR 风险）。
**修法**：挪到已有的后台线程，解码完 `runOnUiThread` 设置。

### 三处 `StaticFieldLeak` 是误报

- `PlayerCore:129` → 存的是 `getApplicationContext()`，不泄漏
- `CrashHandler:42` → 同上
- `ThumbLoader:581` → 需确认，但缓存工具类存 Application context 是常见做法

**不改**，但可以在代码里加 `@SuppressLint` + 注释说明，让 lint 报告干净。

---

## P5 · 小项

| 项 | 内容 |
|---|---|
| `lint.abortOnError = false` | 建议改为 `true`，否则新增的 Error 永远不会阻断构建。可先用 `lintOptions { baseline }` 冻结现有 21 个，再打开 |
| `checkReleaseBuilds = false` | 同上 |
| `ScopedStorage` | Play 商店对 `MANAGE_EXTERNAL_STORAGE` 有政策限制（仅文件管理器类目可申请）。若不上架可忽略 |
| `GradleDependency` 3 处 | appcompat 1.6.1 / material 1.12.0 有更新版本，非安全相关，不急 |
| `UseSwitchCompatOrMaterialCode` 1 处 | `FileManagerModule:6155` 用了平台 `Switch`，建议换 `SwitchMaterial`（主题一致 + 修复已知的平台 Switch 主题 bug） |

---

## 建议执行顺序

| 优先级 | 项 | 工作量 | 收益 |
|---|---|---|---|
| **1** | P0 · 9 处 NewApi 加版本守卫 | 小（1-2 小时） | 消除 API 23/24 崩溃 |
| **2** | P2 · 文档补字幕 | 极小（10 分钟） | 消除不一致 |
| **3** | P4 · ImageViewer 解码挪后台 | 小 | 消除 ANR 风险 |
| **4** | P3 · DefaultLocale 14 处 + DrawAllocation 3 处 | 小 | 真 bug + 真性能 |
| **5** | P5 · lint baseline + abortOnError=true | 小 | 防未来回归 |
| **6** | P1 · 拆 God Class | **大** | 纯结构，无功能收益，需专门窗口期 |

**不建议现在做的**：
- P3 里的 `SetTextI18n` / `HardcodedText`（130 处，项目只做中文，收益为零）
- P1 的完整拆类（风险高、无功能收益，应等窗口期）

---

## 附：完整的 lint 分布

```
Error 21:
  13  NewApi              ← 4 误报（Java 库）+ 9 真问题
   7  UnsafeOptInUsageError  ← 需逐个确认
   1  WrongThread           ← 真问题

Warning 215:
 108  SetTextI18n           ← 中文项目可忽略
  22  HardcodedText         ← 同上
  14  DefaultLocale         ← 值得修
  12  ClickableViewAccessibility ← TalkBack
   9  ApplySharedPref       ← 多数有意
   8  NotifyDataSetChanged  ← 合理
   5  UnusedResources
   5  IconLauncherShape
   4  InlinedApi
   3  GradleDependency
   3  DiscouragedApi
   3  DrawAllocation        ← 值得修
   3  StaticFieldLeak       ← 误报
   2  InternalInsetResource
   2  Overdraw
   其余各 1（ScopedStorage / OldTargetApi / SdCardPath /
            UseSwitchCompatOrMaterialCode / UnspecifiedRegisterReceiverFlag /
            ObsoleteSdkInt / VectorPath / DisableBaselineAlignment /
            UsableSpace / IconDipSize / IconDuplicatesConfig / RtlEnabled）
```

### 已核对：`UnsafeOptInUsageError` 7 处

全部是 media3 的 `@UnstableApi` 标注缺失，**不是运行时问题**：

| 文件:行 | 内容 |
|---|---|
| `MediaPlayerActivity.java:186-188` | `AspectRatioFrameLayout.RESIZE_MODE_*` 三个常量 |
| `MediaPlayerActivity.java:1281` | `PlayerView.setResizeMode()` |
| `MediaPlayerActivity.java:1351` | `PlayerView` 相关 |
| `PlayerCore.java:91` | media3 数据源配置 |
| `PlayerCore.java:117` | `MediaItem` 构建 |

**性质**：media3 把部分 API 标为 `@UnstableApi` 表示「升级小版本时可能改名」，
不是不能用。不加标注只是**编译期提醒**，运行完全正常。

**修法**：在类上加 `@androidx.annotation.OptIn(markerClass =
androidx.media3.common.util.UnstableApi.class)`，或者在 `lint { disable "UnsafeOptInUsageError" }`
里关掉（因为整个播放器本来就在用 media3 的 unstable API，逐个加标注没意义）。

**建议**：后者。这不是缺陷，是噪音。


---

# 修复记录（2026-11-22）

## 结果

| 指标 | 修前 | 修后 |
|---|---|---|
| lint 总问题 | 236 | **71** |
| lint Error | 21 | **0** |
| `NewApi`（真崩） | 9 | **0** |
| `DefaultLocale` | 14 | **0** |
| `WrongThread` | 1 | **0** |
| 单测 | 73 | 73（全通过） |

## P0 · 9 处版本守卫（全部修复）

| 文件:行 | API | 修法 |
|---|---|---|
| `FileManagerModule.java:7644` | 30 | `SDK_INT >= 30 && Environment.isExternalStorageManager()` |
| `FileManagerModule.java:10824` | 24 | `SDK_INT >= 24 && DocumentsContract.isTreeUri()` |
| `FileManagerModule.java:10848` | 24 | 同上 |
| `SafStrategy.java:113` | 24 | 同上（低版本 API 23 的 SAF 只有 document 形式，无 tree） |
| `StorageStrategy.java:33` | 24 | 整个 `getStorageVolumes()` 循环包进 `SDK_INT >= 24`，低版本靠兜底补主卷 |
| `MediaPlayerActivity.java:1362` | 24 | `PixelCopy` 前加判断，低版本提示「需 Android 7.0+」，并**修正了那条错误注释** |
| `FileManagerModule.java:7682` | 26 | `canRequestPackageInstalls` 加显式判断（原做法只靠 try-catch，语义不清且 lint 无法识别） |

## 更深的问题：`CompletableFuture` 其实没被脱糖

审查时发现 `ThumbLoader` 用 `CompletableFuture`（API 24）做缩略图去重。

**先误判为「desugar 已覆盖」**，因为 `desugar_jdk_libs-2.1.5.jar` 里确实
带着 `java/util/concurrent/CompletableFuture.class`。

但检查实际构建产物 `desugar_lib_dex/release/classes1000.dex`：

```
j$/util/concurrent/ConcurrentHashMap     ← 有
j$/util/concurrent/ThreadLocalRandom     ← 有
j$/util/concurrent/atomic/DesugarAtomicInteger ← 有
CompletableFuture                        ← 没有
```

而官方 CHANGELOG 的覆盖清单也只提到 `ConcurrentHashMap` / `Flow` /
`java.time` / `java.util.function`，**没提 `CompletableFuture`**。

**结论：R8 的脱糖配置没有实际启用该类。API 23 上会 `NoClassDefFoundError`，
整个缩略图功能崩掉。**

**修法**：不依赖 desugar，改用 `ConcurrentHashMap<String, List<Waiter>>`
自行实现「同 key 只解码一次、多请求共享结果」的语义：

```java
// 抢占占位：抢到就自己解码，没抢到就登记为等待者
List<Waiter> myList = new ArrayList<>();
myList.add(waiter);
List<Waiter> existing = IN_FLIGHT.putIfAbsent(key, myList);
if (existing != null) {
    synchronized (existing) { existing.add(waiter); }
    return;
}
POOL.execute(() -> { /* decode + writeDisk */ ... });
```

**两个细节**：
1. 摘除用双参 `remove(key, myList)`，保证「只摘自己那一份」，
   避免误删别人的等待列表导致那些请求收不到回调
2. `decode + writeDisk` 全部完成**之后**才 remove，否则与下一个
   `putIfAbsent` 之间存在窗口，两个线程会同时写同一个 `.jpg`
   → 半写文件 → 之后 `readDisk` 永久返回损坏图

## P3 · DefaultLocale 14 处

土耳其语环境下 `"IMAGE.JPG".toLowerCase()` 会得到 `"ımage.jpg"`
（大写 I → 无点 ı），导致所有后缀判断失效、缩略图与格式识别全错。

批量改为 `toLowerCase(Locale.ROOT)` / `String.format(Locale.US, ...)`：
`ArchiveExtractor` 1、`FileManagerModule` 7、`ImageViewerActivity` 4、
`MediaPlayerActivity` 1、`SafStrategy` 1。

## P4 · WrongThread（ImageViewer GIF 解码）

`ImageDecoder.decodeDrawable` 原在 UI 线程，解码多帧动图可达数百毫秒 → ANR 风险。

改为 GIF 线程内决策两条分支：
- 解出 `AnimatedImageDrawable` → post 到主线程显示
- 解不出 → post 回主线程走既有静态解码

**中途踩的坑**：第一版用了 `gifThread.join(3000)` 等结果再判断，
这会让 UI 线程阻塞最多 3 秒 —— 比原问题更糟。已改为完全异步。

## P5 · lint 配置

关闭三类确认为噪音的规则，并写明原因：

```kotlin
disable += "UnsafeOptInUsageError"   // media3 @UnstableApi，非运行时问题
disable += "SetTextI18n"             // 只做中文，不做 i18n
disable += "HardcodedText"           // 同上
```

问题数 236 → 85（修完 DefaultLocale 后 71），剩下的都是有意义的告警。

## 第二轮修复（2026-11-22 续）

| 项 | 数量 | 处理 |
|---|---|---|
| `DrawAllocation` | 3 | `SegmentedBar.onDraw` 里的 `new Paint` / `new RectF` 改为字段预分配复用 |
| `UnusedResources` | 5 | 删除 `ic_grid` / `ic_list` / `ic_sync_arrow_back` / `ic_sync_arrow_fwd` / `ic_thumbnail`（全项目零引用，含 `getIdentifier` 动态路径核查） |
| `InlinedApi` | 4 | `GET_SIGNING_CERTIFICATES`(28) 低版本退 `GET_SIGNATURES`；`ACTION_MANAGE_*_ALL_FILES_ACCESS`(30) 加版本判断；`MATCH_UNINSTALLED_PACKAGES`(24) 低版本退 `GET_UNINSTALLED_PACKAGES` |
| `UseSwitchCompatOrMaterialCode` | 1 | 平台 `Switch` → `SwitchMaterial`（ROM 主题一致性） |
| 死代码 | 1 | 删除 `UiKit.iconRes()`（零调用） |

lint 进一步 62 → **57**，Error 保持 0。

### `ClickableViewAccessibility` 12 处：判定为误报，不改

逐处核对后确认：这 8 个 `setOnTouchListener` 全部**返回 false 或不消费点击**，
真正的点击由 `OnClickListener` / `GestureDetector` 处理，
TalkBack 能正常识别控件语义。lint 的规则假设「设了 touch 监听就该处理点击」，
但本项目这些监听只做按压缩放动画与手势识别，不吞事件。

试图加 `performClick()` 转发时发现自己判断错了（`return false` 时系统仍会
自行派发 onClick，补调用反而可能重复触发），已回滚。

### 剩余 57 项的性质

| 类型 | 数量 | 判断 |
|---|---|---|
| `ClickableViewAccessibility` | 12 | 误报，见上 |
| `ApplySharedPref` | 9 | 多数有意（关键设置要立刻落盘防丢） |
| `NotifyDataSetChanged` | 8 | 文件列表整表刷新合理 |
| `IconLauncherShape` / `IconDipSize` / `IconDuplicatesConfig` | 7 | 图标资源规范，非功能问题 |
| `StaticFieldLeak` | 3 | 存 Application context，误报 |
| `InternalInsetResource` / `DiscouragedApi` / `Overdraw` | 6 | 低优先 |
| 其余各 1 | 12 | 单点问题，收益低 |

## 剩余可做项

| 项 | 数量 | 说明 |
|---|---|---|
| `ClickableViewAccessibility` | 12 | TalkBack 支持，需逐个实现 `performClick` |
| `ApplySharedPref` | 9 | 多数是有意的（关键设置要立刻落盘） |
| `NotifyDataSetChanged` | 8 | 文件列表整表刷新合理 |
| `DrawAllocation` | 3 | onDraw 里分配对象，值得修 |
| God Class 拆分 | 1 个 17776 行文件 | 需专门窗口期 |
| `abortOnError = true` | — | 现在 Error 已归零，可以打开了 |
