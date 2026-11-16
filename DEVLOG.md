# 开发日志（未发布改动累积）

> 本文件记录**尚未发布**的改动。用户说「发布新版本」时，把这里的内容
> 汇总进 `versions.json`（面向用户）+ `CHANGELOG.md`（面向开发者），
> 然后清空本文件。
>
> 记录规则：每条写「改了什么 / 为什么 / 影响面」，不写调试过程。

---

## 待发布

### 代码审查修复（2026-11-15，代码审查报告见 `docs/CODE-REVIEW-1.0.5.md`）

1. **四处缓存加上限**（`FileManagerModule`）
   `dirCache` / `scrollStateByDir` 原来是无界 `HashMap`，每浏览一个新目录就 +1。
   其中 `dirCache` 的 value 是**整目录条目快照**，万级目录单条就几万个对象，
   浏览上百目录后内存常驻几十万对象。
   改法：照同文件 `archiveItemsCache` 的写法换成 `LinkedHashMap(accessOrder=true)` LRU，
   `DIR_CACHE_MAX=24`、`SCROLL_STATE_MAX=20`。
   `backStack` / `fwdStack` 加 `pushHistory()` 辅助方法，超 `HISTORY_MAX=50` 丢最旧项
   （4 处 `push` 调用全部收口到该方法）。

2. **下拉刷新失败不再静默**（`FileManagerModule`）
   原 `catch (Throwable ignored) {}` 整个吞掉。`reload` 里含 `AccessRouter.list` /
   `listFiles` / SAF 查询 / 权限判定，任一失败（权限被撤、目录被删、路径失效），
   用户只看到转圈消失但列表没变，且零提示。
   改为 `Log.w("HyRefresh", ...)` + toast 显示异常类型与消息。

3. **移除 5 处调试 `Log.d` 并配置 R8 剥离**
   位置：`syncByFocus`、`setActive`、`HYOpenWith`、`loadZip path=`、`loadZip result`。
   其中 `loadZip path=` 在**每次进入压缩包**时拼接 `p.path`（用户真实路径）+
   `inner` + `items.size()`，既是热路径开销也是隐私泄露。
   `proguard-rules.pro` 追加 `-assumenosideeffects class android.util.Log { d; v; }`。
   **注意保留 `Log.w` / `Log.e`** —— 排障依赖它们。
   实测验证：反编译 dex，`syncByFocus`/`HYFM`/`loadZip path=` 全部消失，
   而 `HyReload`/`HyRefresh`/`HyZipCache`/`HyArchive` 仍在。

4. **`maybePurgeArchiveCache` 加日志**
   该方法整个被 `catch (Throwable ignored)` 包住，但它负责**释放压缩包解出的磁盘缓存**。
   失败时缓存白占空间，用户只会看到存储莫名变少。改为 `Log.w("HyZipCache", ...)`。

5. **清理 2 处未使用导入**
   `ZipCache.java` 的 `java.util.Enumeration`、`IcoDecoder.java` 的 `java.io.File`。

6. **下拉刷新不清 `dirCache` —— 刷新等于没刷**（后续发现，真 bug）
   `refresh.setOnRefreshListener` 里只清了压缩包的 `archiveItemsCache`，
   没清目录快照缓存。`reload()` 发现指纹（mtime + 子项数）没变 → 直接读旧快照。
   **实测复现**：文件从 2 字节改写为 49 字节，目录 mtime 与子项数均未变
   → 旧代码会显示旧的 2 字节，用户下拉刷新也刷不出来。
   修复：刷新回调开头加 `p.dirCache.clear()`（注释说明「下拉刷新=用户明确要求最新内容，
   必须绕过快照缓存」）。
   「更多 → 刷新」走 `reloadAll()`，那里本来就有 `dirCache.clear()`，无需改。

7. **Manifest 缩进统一**
   4 处 `<activity>` 误缩进 12 格（`<application>` 子级应为 8 格）。
   验证：去空白后逐行对比完全一致，纯缩进修正，零实质改动。

**未修（评估后决定不动）**：
- `reload()` 主线程同步 IO：架构级改动（需序号守卫防竞态、空窗期 UI、缓存写入时机、
  `reloadAll` 同步语义变更），风险大于收益。且已有目录指纹缓存覆盖「返回上一目录」最高频场景。
  仅在用户反馈「某目录打开很卡」时再动。
- `ImageViewerActivity` / `MediaPlayerActivity` 的 `configChanges` 不一致
  （前者无 `screenLayout`）：两者均未实现 `onConfigurationChanged`，也未做分屏处理，
  差异不会导致行为不一致，保持原样。

**验证**：`assembleRelease` 通过（1m13s），装机后主界面双窗格渲染正常，logcat 无崩溃。

### 三视角审查修复（2026-11-16，第 1 批）

按 `docs/REVIEW-3PERSPECTIVE.md` 修复崩溃/丢数据/性能共 11 项。

#### 崩溃类

1. **minSdk 23 却用 API 26 的 `java.nio.file.Files`**（最严重）
   `AccessRouter.toEntry()` 每条目调 `Files.isSymbolicLink` → Android 6.x/7.x
   **进任何目录必崩**（`NoClassDefFoundError`）。同项目 `FileManagerModule.isSymlink()`
   早有版本判断，这两处漏了。
   改法：`toEntry` 全面改用 `android.system.Os.lstat`（API 21+，无版本问题），
   一次拿到 `st_mode/st_size/st_mtime`，用 `st_mode & S_IFMT` 判 DIR/LINK/FILE。
   顺手解决 **P4c**：旧实现每条目 4 次 syscall（isSymbolicLink + isDirectory +
   length + lastModified）+ 1 个 `UnixPath` 对象，万项目录 = 4 万次 syscall；
   现在 1 次 `lstat` 搞定。失败时退回纯 `java.io.File` 的 `toEntryFallback`（零 nio 依赖）。
   `RootVirtualStrategy.listRoot()` 改为直接复用 `AccessRouter.toEntry`，删掉重复逻辑。

2. **未启用 coreLibraryDesugaring 却用 API 24/33 库**
   代码用了 `Supplier`/`Consumer`（API 24）、`computeIfAbsent`/`removeIf`（API 24）、
   `String.repeat`（API 33）。`lint.abortOnError=false` 把 NewApi 掩盖了。
   改法：`build.gradle.kts` 启用 `isCoreLibraryDesugaringEnabled = true` +
   `desugar_jdk_libs:2.1.5`；`String.repeat` 改用项目已有的 `repeatSpaces()`
   （脱糖不一定覆盖 String 的新实例方法）。
   `exportDiagnosticLog` 的 `Files.readAllBytes` 改为自写 `readFileBytes`。

#### 数据安全类

3. **SAF 复制失败当成功 → 删源丢数据**
   `copyFileToSaf` 里 `File[] children = src.listFiles(); if (children != null) {...}`，
   `listFiles()` 返回 null（无读权限/目录已删/SELinux 拦截）时静默跳过循环，
   `ok` 保持 true → 目标只建成空目录却报成功。而 `moveInto` 第③步是
   「copyInto 成功 → deleteDocument(源)」→ **源目录连同内容被删，界面报「成功」**。
   改法：`children == null` 时写错误、删已建目标目录、返回 false。
   注意真正的空目录 `listFiles()` 返回长度 0 的数组（非 null），不会误伤。
   同类问题在 `copySafToSaf`：`listChildren()` 无论失败还是空目录都返回空 list。
   新增 `listChildrenOrNull()`（读失败返回 null）来区分，递归复制改用它。

4. **编辑器保存非原子 + dirty 竞态**
   ① `new FileOutputStream(tab.file)` 原地截断，写一半被杀/掉电 → 原文件变半截或 0 字节。
   ② 保存大文件期间用户继续输入（TextWatcher 置 dirty=true），worker 完成后
   handler **无条件** `tab.dirty = false` → 关闭标签判 `!dirty` 直接移除，**新输入静默丢弃**。
   改法：写同目录 `xxx.hysave.tmp` → `getFD().sync()` → `renameTo`（原子）；
   rename 失败（跨分区/被占用）降级为「备份原文件 + 复制 + 失败回滚」。
   dirty 改为「保存前快照 vs 完成时 content」比对，不一致则保持 dirty，
   toast 也区分「已保存」与「已保存（保存期间又有修改未存）」。

#### OOM 类

5. **图片查看器高清模式上限 16× 屏幕**
   `calcHdSample` 判据是「原始边不超屏幕 4 倍就 sample=1」，1080×2400 屏的 4 倍
   单边是 4320px —— 手机原图（4000×3000）全落在 sample=1，即 48MB 单张；
   5000 万像素（8160×6120）仍不降级 = **200MB** 必 OOM，而 `hdMode` 默认 true。
   改法：判据 4× → 2×，上界 8 → 32，新增 `MAX_DECODE_PIXELS = 16_000_000`
   总像素硬闸（单边判据拦不住两边都大的方图）。`calcSample` 也加同一硬闸。

#### 性能类

6. **拖动框选每帧全表重绑**
   `applyRangeSelection` 里 `if (changed || true)` 让 changed 判断完全失效，
   每个 `onInterceptTouchEvent(MOVE)` 都 `setSelected()` → `notifyDataSetChanged()`
   → 16 行全部重绑，60~120 MOVE/秒 = **1000~2000 次 bind/秒**。
   改法：新增 `FileListAdapter.applySelection()`（只换引用不刷新）+
   `PAYLOAD_SELECTION` + `onBindViewHolder(VH, int, payloads)` 走轻量
   `bindSelection()`（只重算背景/缩放/勾选框，不碰名称/图标/缩略图），
   并只对「上次区间 ∪ 本次区间」`notifyItemRangeChanged`。

7. **`formatDate` 每行构造 SDF ×1 + Calendar ×2**
   `buildSub()` 每个文件行都调，绑定 100 行 = 200 次 Calendar + 100 次
   `new SimpleDateFormat`（构造要解析 pattern 并建 DateFormatSymbols）。
   改法：两个 pattern 各缓存一份 `ThreadLocal<SimpleDateFormat>`（非线程安全，
   相册扫描后台线程也会调）；「今年」只每年算一次（`volatile int cachedYear`）。

8. **`trimDisk` 每写一张缩略图就全盘扫描**
   `writeDisk()` 结尾无条件 `trimDisk()`：全目录 `listFiles()` + 每文件 `length()`
   + 超限时全量排序（comparator 内还再调 `lastModified()`，N log N 次 syscall）。
   缓存 5000 张时每解一张 ≈ 5000 次 stat，而 POOL 只有 3 线程
   —— 这就是「大目录滚动后段缩略图越出越慢」的成因。
   改法：`AtomicLong` 内存累加字节数 + 30 秒最小间隔，只在超限时才真扫盘；
   排序前把 `length`/`lastModified` 读进数组，改用下标排序避免 comparator 重复 syscall。

9. **`cardStyleMap` 强引用泄漏**
   用 `IdentityHashMap`（key 强引用）而配对的是 `WeakReference` 列表。
   设置页每次重建 200~300 个 View 全进 map，旧 View 永不回收；清理阈值
   `size() > refs.size()*2 + 32` 随两者同步增长，基本不触发。
   改法：换 `WeakHashMap`（`android.view.View` 未重写 equals/hashCode，语义等价），
   并删掉那段已无意义的阈值反向清理。

10. **`appIconCache` 无上限**
    `Android/data` 下 3000 个包名 → 缓存 3000 个 Drawable（30~100KB each）= 90~300MB。
    改法：加 `appIconOrder` 队列 + `APP_ICON_CACHE_MAX = 64`，
    超限时按插入顺序淘汰（`appIconQueried` 同步清理，保证能重新查询）。

11. **`savePrefInt`/`savePrefBool` 主线程 `commit()`**
    25 处主线程调用，每次 fsync 2~10ms（低端机 20ms+）；滑块松手还会紧接
    `persistState()`（又一次 24 键 commit）= **2 次 fsync**，表现为「拖完卡一下」。
    改法：方法体改为 `PREF_HANDLER.post(...)`（专用后台线程，`persistState` 已走这条），
    签名不变 → 25 个调用点零改动。

**验证**：`assembleRelease` 通过（1m37s），装机启动正常，
无崩溃、无 `NoSuchMethodError`/`NoClassDefFoundError`，双窗格渲染正常
（`Os.lstat` 改造后文件大小与 mtime 显示均正确）。

### 三视角审查修复（2026-11-16，第 2 批）

12. **X2 属性框两个占位符互相顶掉**
    目录属性框同时起两个后台线程（项目数 / 大小），回填都用
    `tv.getText().toString().replace("计算中…", x)`。项目数先回来时它的 replace
    会把「大小：计算中…」一并替掉，大小再回来已找不到占位符 → 显示成
    「项目数：1.2 MB」这种错乱。
    改法：两个值分别存 `String[]`，每次回填用 `propHeadLine` + 当前已知值
    重拼整段文本（`rerenderProp`）。

13. **X3 `uniqueName()` 未回写 `existing`**
    同一批粘贴里两个不同目录的同名文件，各自都看到 `existing` 里没有它 →
    拿到同一个名字 → provider 侧后写覆盖前写（或 provider 造出 `name (1) (1)`），
    用户少一个文件。改法：分配后立即 `existing.add(name)`。

14. **X4 `ZipCache.extract` 无锁无临时文件**
    缩略图线程 + 查看器 + 播放器可能同时请求同一条目。旧实现直接写最终路径，
    线程 A 写到一半，线程 B 看到 `length()>0` 就当缓存命中 → 读到半截文件；
    而半截文件满足 `length()>0` 会被后续调用长期复用，错误内容驻留缓存。
    改法：按绝对路径的细粒度锁（`ConcurrentHashMap` 取锁对象）+ 写 `.tmp`
    后原子 rename；`purgeArchive` 删文件时同步移除锁表条目防泄漏。

15. **P8 冷启动主线程清扫回收站**
    `purgeExpiredTrash()` 在 `createMainView` 内、`reloadAll()` 之前，对每个过期
    条目调 `countTree`（递归遍历整棵被删目录树）再 `deleteTree`。5000 文件的
    过期目录 = 冷启动白屏 1~3 秒，且每次启动都跑。
    改法：移到独立线程 `HyTrashPurge` + 24 小时节流（`PREF_LAST_TRASH_PURGE`）。
    **踩坑**：第一版用了 `runTask()`，而 `runTask` 会 `TaskKeepAliveService.acquire()`
    拉起前台服务 → 冷启动时前台服务与 `reloadAll()` 抢主线程，实测触发
    `ForegroundServiceDidNotStartInTimeException` 崩溃（FATAL，进程死）。
    回收站清扫是启动维护，用户没发起任何操作，不该弹「正在处理文件」通知，
    改用裸线程（不碰前台服务）后崩溃消失。

16. **P5 缩略图黑帧 Bitmap 泄漏**
    `decodeVideoThumb` 里黑帧重试的 `f2` 若也是全黑，既不使用也不 `recycle`
    直接丢弃。`getFrameAtTime` 的位图在 native 堆，不 recycle 要等 GC finalizer；
    批量加载黑场视频缩略图会累积多张 1080p 位图（每张 ~4MB）。
    同类：`ImageViewerActivity` 解码完成但 `seq` 已变时 `fb` 直接丢弃未回收；
    顺带补上「换图时回收旧 drawable 的位图」。

17. **D3 解压到已存在目录会清空整棵树**
    `extract()` 签名明确允许 `destDir` 已存在，但 GBK 乱码重解路径里有
    `deleteTreeQuietly(destDir)` —— 会把用户原有文件整棵删除。
    改法：`extract()` 记录 `destPreexisted` 并传给 `extractZip`；
    只有「本次新建的空目录」才允许清空重解，目标原本存在时跳过清空并打日志。

**验证（第 2 批）**：`assembleRelease` 通过，装机启动正常，
崩溃 0 / FATAL 0 / 前台服务异常 0，进程存活，双窗格渲染正常。
（第 1 次构建曾引入 `ForegroundServiceDidNotStartInTimeException`，已修复并复测通过。）

### 切换目录回到顶部（2026-11-16，用户反馈修复）

**用户反馈**：「切换目录从头滑动没有了（切换目录不要保存上个目录的滑动位置）」

**根因（两层叠加）**：

1. 切换目录走 `adapter.setData(p.entries)` → `notifyDataSetChanged()`。
   而 `p.entries` 是**同一个 List 实例**（`reload` 里一直 `clear()` + `addAll()`），
   RecyclerView 在 `notifyDataSetChanged` 后会保留原来的 `LinearLayoutManager` 锚点
   → 从第 N 行进去仍然停在第 N 行。
2. 旧的「滚动位置记忆」机制（`scrollStateByDir` + `restoreScrollOnLoad`）
   虽然门控在 `restoreScrollOnLoad` 上，但 `markReturnToForeground()` 全项目
   **零调用**，等于白存了一堆 `Parcelable`；同时它让代码看起来「有恢复逻辑」，
   掩盖了真正的问题。

**修复**：

1. 新增 `resetListToTop(Pane)`：`scrollToPosition(0)` →
   `post()` 到下一帧再 `scrollToPositionWithOffset(0, 0)`。
   分两拍是因为 `notifyDataSetChanged` 的 layout 在下一帧才完成，
   只调一次会出现「偶尔停在老位置」。
2. 在 `reload()` 里用 `dirChanged` 标志控制：
   ```java
   final boolean dirChanged = p.dirFadePending;   // 必须在消费前拷贝
   if (p.dirFadePending) { p.dirFadePending = false; playDirTransition(p.list); }
   ...
   if (dirChanged) resetListToTop(p);
   ```
   **踩坑**：第一版直接在后面判 `p.dirFadePending`，但它在上面淡入动画块里
   已被置 false，导致逻辑永不执行。改为先拷一份。
3. **彻底删除** `scrollStateByDir` / `restoreScrollOnLoad` / `lastLoadedPath` /
   `markReturnToForeground()` / `SCROLL_STATE_MAX` 整套机制（用户明确不要恢复，
   留着只是死代码 + 内存占用）。
4. 删除 `reload()` 里 `onSaveInstanceState` / `onRestoreInstanceState` 调用。

**边界正确性**（已用脚本逐一核对）：

| 场景 | 入口 | 是否重置滚动 |
|---|---|---|
| 进入目录 | `navigateTo` | ✅ 重置 |
| 后退 | `navBack` | ✅ 重置 |
| 前进 | `navForward` | ✅ 重置 |
| 排序 | `reloadAll` | ❌ 保留（否则列表突然跳顶） |
| 写操作后刷新 | `reloadAll` | ❌ 保留 |
| 下拉刷新 | 直接调 `reload` | ❌ 保留（不清 dirFadePending） |

**网格模式**：`GridLayoutManager` 是 `LinearLayoutManager` 的子类，
`instanceof` 判断覆盖两种布局，`scrollToPositionWithOffset` 同样适用。

**验证**：`assembleRelease` 通过（1m16s），装机启动正常，崩溃 0 / FATAL 0 / 进程存活。

### 下一步待办

- 文本编辑器重构（预览/编辑双模式、语法高亮、快捷输入栏、编码切换、
  查找替换增强、Markdown/HTML/JSON·XML 预览、HTML 可切工作目录）
- 视频播放器 UI 重构
- 图片隐藏式压缩包（合体制作 + 检测提取）

---

## 1.0.4（2026-11-12 已发布）

本批全部改动已汇总：面向用户 → `versions.json` 的 1.0.4 条目；面向开发者 → `CHANGELOG.md` 的 1.0.4 章节。以下保留实现细节存档。

### 图片查看器重构（bbd78c1）

**改动面**：`ImageViewerActivity.java`（179→378 行）、`activity_image_viewer.xml`、
新增 `drawable/ic_share.xml`、`FileManagerModule.java`（openImageViewer + onViewerResult）、
`MainActivity.java`（onActivityResult 转发一行）。

**架构决策：查看器不持有业务，全部回传主界面**
- 删除：查看器内仅弹确认（glassDialog 自建，UiKit.alert 是固定失败样式不能用于确认框）
  → `setResult` 携带 RESULT_ACTION_DELETE → finish →
  模块 `onViewerResult` 构造 FileEntry（final 字段模型，用完整构造器，
  Type.FILE + length/mtime）→ `confirmDelete(singletonList)` 复用回收站机制 + reloadAll。
  不复制 moveToTrash 逻辑（它依赖实例状态，静态化得不偿失）。
- 设为背景：选择框（全局/文件列表页）→ RESULT_ACTION_WALLPAPER + EXTRA_FOR_FM →
  模块置 `pickForFmTheme` → `setBackgroundFromUri(Uri.fromFile(path))`——
  ContentResolver.openInputStream 支持 file:// scheme（FileUriExposedException
  只限跨进程 Intent，本应用内部可用），finally 里复位 pickForFmTheme。
- 回传通道：`REQ_VIEWER=0x4B49`，`hostActivity.startActivityForResult`
  （照抄 REQ_SAF_MOUNT=1005 模式），`openImageViewer` 三处调用方零改动。
  hostActivity 为 null 时退化为普通启动（此时底部栏仍工作但结果无人接，
  理论不可达，模块必挂在 MainActivity 上）。
- `viewerPendingPath`：onActivityResult 的 data 只带动作，源路径存在模块字段里。

**UI 实现**
- 暗色沉浸：纯黑背景不变，scrim `#99000000`（用户选择：不接主题卡片色）
- 状态栏：`setDecorFitsSystemWindows(false)` + TRANSPARENT + insets 双次重算
  （addOnAttachStateChangeListener + root.post，照抄主界面模式，
  ColorOS status_bar_height 资源值偏小 22px）+ 浅色图标（黑底）
- 底部栏：三按钮图标+文字竖排，横向均分，`selectableItemBackground` ripple
- 唤出式显隐：toggleBars 同时切顶/底栏，3s 自动隐藏（Handler+removeCallbacks，
  onDestroy 清理）；content:// 来源隐藏删除/设背景按钮（SAF 删除与壁纸流不走本链路）
- `attachBaseContext` 补 `DpiUtils.wrap`（原缺失，显示密度对本页无效）

**顺带**：更新页发布日期空值兜底 `fmtPublishDate()`（3 处消费点 4680/4684/4922），
清单缺 publishedAt 时显示"未知"。

**图集切换（8d70f49）**：模块 `collectImages()` 从当前窗格 entries 收集
同目录图片（IMAGE_EXTS 过滤，与播放器 collectPlayable 同思路），
EXTRA_LIST/EXTRA_INDEX 传入；content:// 或窗格不可用退化为单图。
查看器 onFling 切图（≥60dp、水平 1.5× 垂直、vx≥800，循环）；
切图重置矩阵+清旧图+标题/计数更新；删除/设背景跟随 currentPath
（底部栏按钮改间接引用，不用闭包旧 path）。移除超时隐藏（用户指示）。

**切图过渡 + 计数（bc73be2 + 6bae8bb）**：
- 切图淡出→解码→淡入（150ms 连续渐变），loadSeq 序号防旧图晚到覆盖
- 计数「01/32」两位格式，**放底栏上方居中**（第一版放顶栏右侧
  用户看不到以为没加——UI 新元素要放显眼位置），随双模式换色
- 顶栏信息位只显示尺寸；单图时计数行隐藏

**验证**：assembleRelease 通过；已静默装机（pm install -r）。
**未实测**：滑动切图、计数、快滑与拖图的手势边界。

## 相册浏览页（5da0627）

**功能**：侧边栏「工具→图片查看器」入口 → 相册页（路径 chips + 目录分组网格）→
点图进查看器（本目录图集左右滑）；设置→工具→图片查看器管理扫描路径。

**架构**：
- ContentMode 加 GALLERY 三态互斥；galleryEmbedView 动态创建挂 contentWrapRef
  （与 settingsEmbedView 同级），过渡动画/顶栏/底栏 FAB 显隐全部沿用 setContentMode
- 顶栏：GALLERY 模式走 enterSettingsTopBar，页名显示「图片查看器」；
  左上角保持菜单键（不制造第二个返回，系统返回键退）
- handleBackPressed 加 GALLERY → FILES 分支（逐层退原则）
- 扫描：一层为限（本层图片 + 每个子目录一层），不做全盘递归——
  相册图片集中在 DCIM/Pictures 子目录，一层够用且性能可控；按 mtime 倒序
- 缩略图复用 ThumbLoader（内存/磁盘缓存/in-flight 去重/防错位全现成），
  预设 ic_img 占位图标防加载中空白
- 网格：GridLayout 3 列方形（side = 屏宽-28dp）/3），spec FILL+weight
- 扫描路径持久化：PREF_GALLERY_PATHS 换行分隔；默认值（存在才收）：
  DCIM/Camera、DCIM、Pictures、Screenshots、Download
- 设置弹窗 rebuild 递归引用自身 → final Runnable[] 数组容器绕开

**坑**：View 没有 setMargins（必须 LayoutParams 上设）；View 没有 getDrawingColor
（改用字段记录当前色）；模块非 Activity，getResources 要走 ctx.getResources()。

**网格布局返工（62758c3，用户实测「一长条一张图」）**：GridLayout 的
rowSpec/columnSpec 会覆盖逐格 LayoutParams 的高度约束，格子高度塌成原图比例。
改用行式 LinearLayout：每行 3 格，cell = (内容宽 - 2gap)/3 固定方形，
CENTER_CROP 填满裁切，末行补空 View 防拉宽。**教训：Android 上做等分网格
优先行式 LinearLayout 或 RecyclerView+GridLayoutManager，不要手拼 GridLayout 规格。**

**GIF 支持**：网格 gif 格子叠「GIF」角标（仍用首帧缩略图，网格内不解码动图）；
查看器（API 28+）ImageDecoder.createSource + decodeDrawable 出
AnimatedImageDrawable 播放，>屏幕 2 倍时 setTargetSampleSize(2) 降采样；
信息位显示「宽 × 高 · GIF」；低版本/失败回退 BitmapFactory 静态首帧。

**验证**：assembleRelease 通过；已静默装机。
**未实测**：网格滚动性能（大目录）、chips 切换、路径增删后的刷新、点图进查看器。

## ICO / PE 图标提取 + 查看器高清模式（44052ba）

**新增文件**：`icon/IcoDecoder.java`（293 行）、`icon/PeIconExtractor.java`（282 行），
均纯 Java 零依赖，只读文件头与 .rsrc 节，不加载/执行 PE 内容。

**ThumbLoader 接入**：supports() 加 ico 与 exe/dll/mun/cpl；
decode() 里 ico/PE 分支必须在 isImage 之前（.ico 同时在 isImage 列表）。
失败静默回退静态类型图标。

**PE 解析两个致命坑（实测 notepad++.exe 逐层验证）**：
1. `RandomAccessFile.readInt()/readUnsignedShort()` 是**大端**，PE 是小端。
   直接用会把 'MZ'(0x5A4D) 读成 0x4D5A，第一步就判非 PE → 全量静默失败。
   必须自写 le16/le32。
2. **两套偏移语义不同**：
   - 目录项 `OffsetToDirectory` → 相对 **.rsrc 节起点**
   - 数据项 `OffsetToData` → **真 RVA**，需减 rsrcRva
   混用会得到负偏移（seek 抛 Negative seek offset）或读到文件尾外。
3. 无 .rsrc 节的 PE（如 PyInstaller bootloader）正确返回 null。

**实测结果**：notepad++.exe → 128×128 图标，9999/16384 不透明像素、7250 种颜色
（确认是真实彩色图标而非全黑/空白）；边界（不存在/垃圾/只有 MZ 头/无 rsrc）均 null。

**查看器高清模式**：长按切换。默认屏幕 2 倍采样 + RGB_565（快、省内存）；
高清模式屏幕 4 倍采样 + ARGB_8888，信息位标「· 高清」。
**坑**：长按不能写在 `imageView.setOnLongClickListener` ——
`OnTouchListener` 恒返回 true 会吃掉 View 级长按事件，必须写在
`GestureDetector.onLongPress`。GIF 动图走 ImageDecoder 另一条路径，不受影响。
**实测崩溃 1 次（bbd78c1）**：`?attr/selectableItemBackground` 在查看器的
framework 主题（Theme.Black.NoTitleBar.Fullscreen）下无法解析 →
InflateException 打开图片即崩。修复 `4afc2b9`：换 `@drawable/bg_ripple`
（纯 drawable 引用）。同型风险清理 `2f96584`：播放器音频占位
`?attr/colorPrimary` 换静态色 + 删除两个含 ？attr 的零引用死布局。
**教训**：查看器/播放器/Crash 页用的是 framework 主题，布局里
禁用一切 `?attr/`（AppCompat attrs 由 AppCompat 主题提供，framework 主题没有）。
全仓已 grep 清零（仅剩两个已删布局）。
**实测问题 2（3491731）**：弹窗黑底黑字 —— 删除确认框文字按浅色面板写的深色
（0xFF222222 等），但 glassDialog 面板在本 App 暗色配置下是深底 →
不可见。全部改浅色系（白/半透明白 + 亮红删除钮）。设为背景原用
AlertDialog（framework 主题下同样深底深字）→ 改自建 glassDialog。
**综合教训**：查看器内自建 UI 一律用浅色字（暗色沉浸风格），
不要用 AlertDialog（framework 主题下深底深字），不要假设面板底色是浅色。
**未实测**：修复后的唤出/自动隐藏/分享/回收站/壁纸生效。

---
## 1.0.1（2026-11-05）已发布

本批全部改动已汇总：
- 面向用户 → `versions.json` 的 1.0.1 条目
- 面向开发者 → `CHANGELOG.md` 的 1.0.1 章节

详见上述两处。

---

## 1.0.1 重发（2026-11-05）

用户实测发现已发布的 1.0.1 有安装权限链路缺陷，撤回重发。

### 修：安装权限弹窗点不动 + 授权后不续接

**缺陷 1：两个按钮都无反应**
```java
UiKit.dismissWithAnim((Dialog) ((View) v.getParent()).getTag())
```
`getTag()` 从未被 setTag 过 → 取到 null → dismissWithAnim(null) 什么也不做。
"取消"点了没反应；"去开启"虽跳去系统设置，但弹窗没关，
回来后它还盖在最上层，表现为「卡在申请页」。

**缺陷 2：授权回来后不继续**
没有 onResume 检查 → 用户必须自己再点一次「检查更新」。

修法：
- 弹窗持有自身引用 pkgInstallDialog，不再依赖 getTag
- 「取消」→ dismiss + 清 pendingInstallInfo
- 「去开启」→ **先 dismiss 再跳设置**，回来时不再被挡
- 新增 pendingInstallInfo 记住待下载的更新
- onResume → resumePendingInstall()，延迟 400ms（避开 ROM 权限刷新延迟）
  自动续上下载；未授权就返回则 toast 提示并清待办

---

## 1.0.3 实现细节存档（2026-11-04 已发布）

> ⚠️ 原段落标题是「未发布」，实际早已随 1.0.3 / 1.0.4 发布，
> 仅作实现细节存档。不要当成待发布内容汇总进新版本。

### 新增：设置页改为分组卡片布局

设置主页从「每个标题一张卡 + 每行再一张卡」改成
**每个标题一张卡，标题下直接列选项，选项之间用分割线隔开**。
一屏少一半圆角与描边，视觉更安静。

- `settingsGroup` 记住当前正在填的分组卡，
  `sectionHeaderInCard()` 无条件建卡并赋值（不再区分有没有背景图——
  本机 `bgImageFile` 为 null，加条件会静默失效）
- `addSettingsRow` / `addInfoRow` / `addSliderRow` **三处同步改**：
  `host = settingsGroup != null ? settingsGroup : parent`，
  行 `addView` 到 host 而不是调用方传进来的 box。
  只改其中一两个会出现「分组卡有了但行还在卡外」
- `addGroupDivider()` 在每行之前画 1px `colorOutlineVariant@0x33`，
  左右 margin 14dp。**不能因 `childCount<=1` 提前 return**——
  标题是第 0 个孩子，提前 return 会让标题与首行之间少一条线
- `showInSettingsDialog()` 前后置空 `settingsGroup`，
  否则「先加行、后加标题」的页面（关于页）会继承上一页的分组
- 设置主页重组为三组：**基础设置**（主题设置、存储管理）/
  **工具**（文件管理）/ **软件**（权限管理、软件更新、关于），
  权限管理从原先的最前移到软件组首位

### 新增：存储管理页

设置 → 基础设置 → 存储管理。参考 NeriPlayer 的分区式存储展示。

- **内部存储**：`StatFs` 取 total/available，大字显示「已用 X / 总量 Y」，
  下附百分比与可用量，配一条自绘进度条
- **应用占用明细**，逐项显示大小 + 独立「清理」按钮：

| 项 | 路径 | 清理策略 |
|---|---|---|
| 缩略图缓存 | `cacheDir/thumbs` | 直接清 |
| ZIP 浏览缓存 | `cacheDir/zipview` + `zipnested` | 直接清 |
| 更新包缓存 | `cacheDir/update_*.apk` | 直接清 |
| 编辑器草稿 | `filesDir/editor_drafts` | 二次确认 |
| 背景图 | `filesDir/wallpaper` | 二次确认，**只清孤儿旧图** |
| 回收站 | `/sdcard/.HyVqTrash` | 二次确认 |
| 应用私有数据合计 | `filesDir` + `cacheDir` | 只读 |

- **一键清理**：只清三项缓存，不碰草稿/背景图/回收站
- 统计全部跑在 `HyStorageStats` 后台线程，`FileOps.dirSize` 是递归遍历，
  回收站上万文件时主线程做会卡；统计完一次性 post 回填

**新方法**

| 方法 | 作用 |
|---|---|
| `addToSettingsGroup(parent, v, lp)` | 把任意 View 塞进当前分组卡片。分组机制只有三个行工厂在遵守，容量大字和进度条造不出来，直接 `box.addView` 会落到卡外 |
| `addStorageItem(...)` | 存储页专用行：标题 + 大小 + 可选清理按钮 |
| `confirmThen(title, msg, runnable)` | 清理类操作统一二次确认 |
| `deleteChildren(dir)` | 删目录下所有子项，目录本身保留 |
| `purgeOrphanWallpapers()` | 删掉 wallpaper 里除当前在用外的 `bg_*` / `fmbg_*` |
| `fmtStorageSize(bytes)` | 存储页专用格式化（见下） |
| `updateApkSize()` | `update_*.apk` 总大小 |

### 单位：新增 `fmtStorageSize()`

与 `AccessUtil.formatSize` / `FileOps.formatSize` 的差别只有两点，
其余刻度完全一致，避免同一屏出现两种单位口径：

- **统计失败（负值）显示 `—`** 而不是 `-`。一列数据里一个横杠
  看着像排版错误，「—」才像「没有数据」
- **补 TB 档**。`1234.56 GB` 既不直观也撑破行宽

### 修：内部存储段只剩标题（背景图下看不见）

**症状**：设了背景图后，存储管理页的「内部存储」只显示标题，
下面的容量数字、百分比、进度条全不见。

**根因**：这三者直接 `box.addView`，`box` 是外层容器而非分组卡片。
`sectionHeaderInCard("内部存储")` 已经把 `settingsGroup` 指向卡片，
但裸 View 加到了 box 上，视觉上落在卡片**外面、直接贴背景图**。
白字压白墙。

**修法**：全部改走 `addToSettingsGroup()`。

### 修：关于页正文「有背景图才套卡」

`showAboutPageBody()` 里原本：

```java
if (bgImageFile != null && bgImageFile.isFile()) {
    // 套卡片
} else {
    box.addView(desc);   // ← 无背景图时裸放
}
```

两种模式下**卡片不透明度滑块一个有作用一个没有**，属于行为漂移。
改为无条件套卡 + 打 `CARD_TAG_KEY`，无论有没有背景图，
页面上的字都在卡片里，都由 `cardAlpha` 一个旋钮控制。

### 修：设完背景图不立即落盘

`setBackgroundFromUri()` 成功分支补 `persistState()`。
原先只有 `onDetach` 和滑块回调会落盘，设完图马上被后台杀掉时
`onDetach` 不执行，文件名就丢了。

### 移除：删除成功的 toast

`confirmDelete` 里成功分支的「已移入回收站 N 项」/「已彻底删除 N 项」删掉。
删除是高频操作，底部闪一条只会打断浏览，列表条目立即消失本身就是反馈。
**失败仍然弹**（那种情况必须让用户知道）。

### 诊断代码（临时，待撤）

`FileManagerModule` 里有 10 处 `Log.i("HYBgDiag", ...)`：
`restoreState` / `applyBackground` / 解码回调 / `setBackgroundFromUri`。
用于定位「背景图重启后变纯色」，**定位完成后删除**。
同一轮修好的 `persistState()` 提前落盘是正式改动，保留。

### 验证

- `compileReleaseJavaWithJavac` / `assembleRelease` 通过
- 装机启动无 FATAL EXCEPTION
- 设置页三张分组卡显示正常（用户确认）
- 背景图重启后正常显示：`HYBgDiag` 日志确认
  `bgExists=true` → `APPLY ok` → `DECODE 2160x4367` → `SHOW set`
- **未实测**：存储管理页各清理项的实际效果（清单已构建并安装，
  用户尚未逐项试用）

## 设备侧工作（2026-11-06，非本项目代码）

### 通过 OTG 在一加手机上建立 adb/fastboot 环境

无 PC 前提下，用一加（USB Host）直接调试 Redmi 13R。

**为什么这么绕**：Termux 运行在 `untrusted_app` SELinux 域，对 `usb_device` 判 deny，
`chmod 666` 节点与 `chmod 755` 父目录均无效。必须以 root 运行 adb。

**四道墙与破法**：
1. Termux 用户无 USB 权限 → 以 root 运行 adb
2. `/data/data` 在 root shell 内不可见 → 二进制与库先搬到 `/sdcard`，再搬到 `/data/local/tmp/usbh`
3. 换 HOME 就换 RSA 密钥 → 复制 Termux 已授权的 `adbkey` 过去，否则 `adb devices` 恒为空
4. 官方静态 aarch64 adb 必撞 bionic fdsan 崩溃 → 改用 Termux 版 android-tools 37.0.0p1

**建成环境**：`/data/local/tmp/usbh/`（adb + fastboot + 322 个 .so + adbkey）

**调用模板**：
```bash
export LD_LIBRARY_PATH=/data/local/tmp/usbh/lib HOME=/data/local/tmp/usbh
/data/local/tmp/usbh/adb devices
```

**验证结果**：
- `adb devices` → `CUX87P4T6XWWLZ5T device product:air model:23124RN87C`
- `fastboot devices` → `CUX87P4T6XWWLZ5T fastboot`
- 相册备份 1.6 GB / 2250 个文件 → `10-Redmi备份/`

### 解锁 BL：已实测排除，除官方通道外无技术路径

2026-11-08 真机跑完 12 条 fastboot 命令：

| 命令 | 返回 |
|---|---|
| `flashing unlock` / `unlock_critical` | `unknown command` |
| `oem unlock` / `unlock-go` / `unlock 0` / `unlock 1` | `Start unlock flow` → `Failed to unlock the device` |
| `oem device-info` / `get_unlock_ability` | `unknown command` |
| `oem lock` | `No support by lock control` |
| `flashing lock` / `oem relock` / `reboot-edl` | `unknown command` |

**决定性变量**：

```
tokenversion        = 2     ← 网上所有强解教程针对 v1，v2 协议已换
anti                = 1     ← 防回滚启用
get_unlock_ability  = 空     ← AOSP 规范要求为 1 才允许
sys.oem_unlock_allowed = 1  ← 仅 Android 侧开关，bootloader 不读
```

**不可行原因（物理层面，不是还没试）**：

| 方案 | 阻塞点 |
|---|---|
| MTK BROM / mtkclient | proot 内 `libusb_init = -99`（实测），且需 PC |
| 9008 / EDL | 联发科无此模式 |
| amonet 等 BROM 漏洞 | README 只支持 3 款 Amazon Echo Show |
| 伪造 token | v2 协议 + 服务端私钥签名 |
| 改 bootloader | 签名保护，改一字节即砖 |

**AOSP 规范依据**：`source.android.com` 明确 `get_unlock_ability` 未设置时
设备 should deny `flashing unlock`。小米在上层加了服务端 token 校验。

**唯一可行路径**：`gauthamnair2005/Redmi13C5G_Air_Resources` 的解锁请求脚本
（README 承认不绕过资格，只自动抢整点），前提是 30 天小米账号 + 手机卡 + 社区活跃。
拿到资格后等 168 小时 → `fastboot oem unlock` → 刷 TWRP/ROM
（`suchit-7x/twrp_device_xiaomi_air` + `mt6835-devs/android_device_xiaomi_air`）。

**附带教训**：`fastboot getvar all` 输出数百行，连续调用会让 bootloader
卡死、手机自行重启。查变量必须逐个查。

---

## 1.0.4 版本计划（已过期，2026-11-12 已发布）

### Android/data 空目录提示

**现象**：`/sdcard/Android/data` 下某些子目录在部分设备/Android 版本上
零宽别名也读不到时，列表只剩 `..`，用户不知道是「真的空」还是「被系统拦了」。

**要做的**：区分两种情况，给明确提示。

- 真为空 → 正常显示空目录
- 读不到（`listFiles()` 两次都返回 null）→ 列表里插入一行说明：
  「Android 11+ 限制：此目录需要 root 或由该应用自身提供访问」

**实现线索**：`AccessRouter.list()` 现在的回退链是
`真实路径 → 零宽别名 → denied()`，在 denied 分支返回的 `AccessResult`
里带一个 `reason` 字段，UI 层据此渲染提示行。

---

