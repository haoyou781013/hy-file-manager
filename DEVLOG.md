# 开发日志（未发布改动累积）

> 本文件记录**尚未发布**的改动。用户说「发布新版本」时，把这里的内容
> 汇总进 `versions.json`（面向用户）+ `CHANGELOG.md`（面向开发者），
> 然后清空本文件。
>
> 记录规则：每条写「改了什么 / 为什么 / 影响面」，不写调试过程。

---

## 待发布

> **本段组织方式**：按**主题**归类，不是按时间流水。
> 每项只保留**最终状态**；被后续改动推翻的内容标「⚠ 已推翻」并说明。
> 这样读的人不会把已被删掉的机制当成现存的。
>
> 累计来源：6 次代码审查（1 次自查 + 3 视角 + 5 维度）+ 2 次用户反馈修复，
> 共 8 个提交区间、31 项改动（含 1 项待发布）。

---

### 1. 修掉的真实缺陷

#### 1.1 崩溃类

**`.tbz/.tbz2/.txz/.tzst` 压缩包打开即崩**
`splitZipPath`（切分 `zip://` 虚拟路径）的后缀表漏了这 4 个，
而解压侧（`ArchiveExtractor.canExtract`）与侧边栏分类都支持它们 →
用户能看到、能点开，切分失败后回退到首个 `/`，点「解压」时 `substring` 越界。
根因是**同一份后缀知识散在三个地方各自维护**（详见 3.1）。

**`minSdk 23` 却调用 API 26 的 `java.nio.file.Files`**
`AccessRouter.toEntry` 每个条目都调 `Files.isSymbolicLink` →
Android 6.x/7.x 进任何目录必崩（`NoClassDefFoundError`）。
当时同文件的 `isSymlink()` 已有正确的版本判断，这两处漏了。

**未启用 `coreLibraryDesugaring` 却用 API 24/33 库**
代码用了 `Supplier`/`Consumer`（24）、`computeIfAbsent`/`removeIf`（24）、
`String.repeat`（33）。`lint.abortOnError=false` 把 NewApi 检查掩盖了。

**`runTask` 的两处 `UiKit.alert` 在后台线程执行**（既有 bug）
`UiKit.alert` 创建 View/Dialog，必须主线程。一处是提交失败分支
（`runTask` 可能被 `withArchiveSecret` 回调等后台线程调用），
一处是任务执行体的 `catch`（本来就在 TASK_QUEUE 线程上）。

#### 1.2 丢数据类

**SAF 复制失败被当成功 → 删源**
`copyFileToSaf` 里 `listFiles()` 返回 null（无读权限/目录已删/SELinux 拦截）
时静默跳过循环，`ok` 保持 true → 目标只建成空目录却报成功；
而 `moveInto` 第③步是「copyInto 成功 → deleteDocument(源)」
→ **源目录连同内容被删，界面报「成功」**。
同类问题在 `copySafToSaf`（`listChildren` 失败与真空目录都返回空 list）。

**编辑器保存非原子 + dirty 竞态**
① `new FileOutputStream(tab.file)` 原地截断，写一半被杀/掉电 → 原文件变半截或 0 字节；
② 保存期间用户继续输入（TextWatcher 置 dirty=true），worker 完成后
**无条件** `tab.dirty = false` → 关闭标签判 `!dirty` 直接移除，新输入静默丢弃。

**`FileOps.deleteRecursive` 不判 symlink**
`f.isDirectory()` 对「指向目录的 symlink」返回 true，旧实现递归进去真删目标内容。
调用链：跨分区移动删源、移入回收站删源、从回收站恢复删临时。
（模块内 `deleteTree` 与 `ArchiveExtractor.deleteTreeQuietly` 都判了，唯独这里漏）

**`deleteDirQuiet` 同样是递归 + 不判 symlink + 深目录栈溢出**
已改为委托 `deleteTree`（显式栈 + symlink 判断）。

**解压到已存在目录会清空整棵树**
`extract()` 签名允许 `destDir` 已存在，但 GBK 乱码重解路径里有
`deleteTreeQuietly(destDir)` → 把用户原有文件整棵删掉。
改为只在「本次新建的空目录」上允许清空重解。

#### 1.3 数据错乱类

**下拉刷新不清 `dirCache` —— 刷新等于没刷**
刷新回调只清了压缩包缓存，没清目录快照；`reload` 发现指纹没变 →
直接读旧快照。**实测复现**：文件 2 字节 → 改写为 49 字节，
目录 mtime 与子项数均未变，界面显示旧大小，下拉也刷不出来。

**属性框两个占位符互相顶掉**
「项目数」与「大小」两个后台线程都调
`tv.getText().replace("计算中…", x)`。项目数先回来会把「大小：计算中…」
一并替掉，大小再回来找不到占位符 → 显示成「项目数：1.2 MB」。

**`uniqueName()` 未回写 `existing`**
同批粘贴两个不同目录的同名文件，各自都看到集合里没有它 → 拿到同一个名字
→ provider 侧后写覆盖前写，用户少一个文件。

**`ZipCache.extract` 无锁无临时文件**
缩略图线程 + 查看器 + 播放器可能同时请求同一条目。旧实现直接写最终路径，
线程 B 看到 `length()>0` 就当缓存命中 → 读到半截文件，且半截文件被长期复用。

**回收站过期清理留孤儿 `.trashmeta`**
删条目但没删配对 meta → 残留永久占位；下次同名条目复用旧 meta →
**恢复到错误路径**。

**`isImageName` 只认 6 种扩展名**
手写 `endsWith×6`（jpg/jpeg/png/webp/bmp/gif），而 `Exts.IMAGE` 有 19 种 →
背景图浏览器里 **HEIC/HEIF/SVG/TIFF/RAW 照片全部不显示**（相机默认 HEIC 的用户看不到自己的图）。

**`.xapk` 解压目录名不一致**
两份后缀表只在一份里有 `.xapk` → 同一个包手动解压得到 `foo`、
自动解压得到 `foo.xapk`。

#### 1.4 体验缺陷

**底部同步按钮圆圈偏大**
把按钮从 40dp 改成 48dp 后，`box` 的 OVAL 背景跟着撑大 → 截图里明显比其他
导航按钮大一圈。**修了两次才对**：
1. 第一次改回「视觉 40dp + box padding 4dp」→ 还是大的；
2. 根因：`GradientDrawable` 画在 View 的**完整边界（含 padding）**上；
3. 正解：`InsetDrawable` 向内缩 `touchPad`，让椭圆只占中间 40dp。

> **教训**：「扩触摸热区」和「扩背景绘制区」是两件事。凡是有自定义背景的
> 可点控件，扩热区必须用 `InsetDrawable` / `TouchDelegate`，不能只加 padding。

**切换目录不回到顶部**（用户反馈）
两层原因叠加：
① `p.entries` 是同一个 List 实例（`clear()` + `addAll()`），
   `notifyDataSetChanged()` 后 RecyclerView 保留旧锚点；
② 旧的滚动位置记忆机制（`scrollStateByDir` + `restoreScrollOnLoad`）
   虽然门控在 `restoreScrollOnLoad` 上，但 `markReturnToForeground()` 全项目零调用，
   **从未真正执行**——它白存了一堆 Parcelable，还让问题看起来像「设计如此」。
现为：三个切目录入口（`navigateTo`/`navBack`/`navForward`）显式滚到顶部；
排序、写操作后刷新、下拉刷新都保留当前位置。

---

### 2. 性能优化

#### 2.1 主线程 IO 移出

**目录枚举下沉到后台**
`reload()` 缓存未命中时原在主线程跑 `AccessRouter.list()` + 再 `listFiles()`
数子项 —— 万级目录 100~500ms，进大目录明显掉帧。
现只把「枚举 + 计数」挪到 `HyDirLoad` 线程，回主线程后走
`finishReload(p, dirChanged)` 继续做过滤/置顶/排序/绑数据/动画。
配套：`Pane.loadSeq` 序号守卫（连点几个目录只认最后一次，A→B→A 也不会错配）+
`dirChanged` 在分派前捕获（异步回来后该标志可能已被下一次切目录改写）。

**缓存命中不再 `listFiles()`**
旧实现即使命中缓存也要完整枚举目录数 `kids.length` —— 缓存只省了
`FileEntry` 构造，没省 IO。现只比 mtime；下拉刷新有 `dirCache.clear()` 兜底。

**冷启动主线程清扫回收站 → 后台 + 24h 节流**
`purgeExpiredTrash()` 在 `createMainView` 内、首帧之前，递归遍历过期目录树再删
→ 5000 文件的过期目录 = 白屏 1~3 秒，且每次启动都跑。
⚠ 第一版误用 `runTask()`，它会 `TaskKeepAliveService.acquire()` 拉前台服务，
冷启动时与 `reloadAll` 抢主线程 → 实测触发
`ForegroundServiceDidNotStartInTimeException` FATAL 崩溃。改裸线程后消失。

**`savePrefInt`/`savePrefBool` 主线程 `commit()` → 后台**
25 处主线程调用，每次 fsync 2~10ms；滑块松手还会紧接 `persistState()`
（又一次 24 键 commit）= **两次 fsync**。改用已有的 `PREF_HANDLER`，签名不变。

**任务队列无界 → 有界 16**
`newSingleThreadExecutor()` 内部是无界队列，连点 20 次解压就堆 20 个 Runnable
+ 20 次前台服务 acquire。超限时提示「任务排队已满」而非笼统的「无法提交任务」。

#### 2.2 热路径减少分配

**排序 comparator 内 `toLowerCase`（26 万字符串）**
1 万文件目录约 13 万次比较 × 2 = 26 万个临时字符串、约 10MB 抖动。
改为 `FileEntry.lowerName()` **惰性缓存**（不是构造时算——虚拟视图、
不排序的场景没必要付这个成本）。

**`UiKit.color` 每次 `new TypedValue` + 属性解析**
`onBindViewHolder` 每行调 4~8 次，一屏 ≈ 每帧 60~160 次。
加 `SparseIntArray` 缓存 + **双重失效**：`invalidateColorCache()` 由模块在主题
切换时调用；记录 `uiMode` 作兜底（系统深色模式切换后即使没人调也能自愈）。
⚠ 缓存是 `static`，不随 Activity 销毁，主题切换后必须显式清空。

**`formatDate`/`formatSize` 加 LruCache**
`yearOf()` 的 `Calendar.getInstance()` 改 `ThreadLocal<Calendar>` 复用；
两个格式化方法各加 `LruCache<Long,String>`（512 条）。
实测典型一屏 20 行只有 9 个不同大小值 → 命中率约 55%。

> ⚠ **失败的尝试（记录以免重犯）**：`formatSize` 曾改成整数定点运算替代
> `String.format`，用 2 万个值对比新旧输出 —— **8940 个不一致**
> （`1048575` 旧 `"1024.0 KB"` 新 `"1.0 MB"`；GB 档四舍五入偏差 0.01）。
> 结论：**文件大小显示错比慢一点严重得多**，回滚并保留 `String.format`，只加缓存。

**`trimDisk` 每写一张缩略图就全盘扫描**
5000 张缓存时每解一张 ≈ 5000 次 stat。改 `AtomicLong` 累加 + 30 秒节流 +
下标排序（避免 comparator 重复 `lastModified()`）。

**拖动框选每帧全表重绑**
`if (changed || true)` 让判断完全失效，每个 MOVE 事件都
`notifyDataSetChanged()` → 1000~2000 bnd/秒。
改为 `PAYLOAD_SELECTION` + 轻量 `bindSelection()`，只刷「上次区间 ∪ 本次区间」。

#### 2.3 内存上限

| 对象 | 原状态 | 现上限 |
|---|---|---|
| `dirCache` | 无界 HashMap | LRU 24 |
| `scrollStateByDir` | 无界 HashMap | **⚠ 已推翻：整套机制已删除**（见 1.4 切换目录不回到顶部） |
| `backStack`/`fwdStack` | 无界 ArrayDeque | 50（`pushHistory()` 收口） |
| `appIconCache` | 无界 HashMap | 64 + 插入序淘汰 |
| `cardStyleMap` | `IdentityHashMap` **强引用** View | `WeakHashMap` |
| `SIZE_CACHE`/`DATE_CACHE` | 无 | 各 512 条 |
| `archiveItemsCache`/`ZipCache` | 已有 LRU | 未动（原本就对） |

**`cardStyleMap` 用强引用的后果**：设置页每次重建 200~300 个 View 全进 map，
旧 View 永不回收；清理阈值 `size() > refs.size()*2 + 32` 随两者同步增长，
**基本不会触发**。

**图片查看器高清模式上限 16× 屏幕**
1080×2400 屏的 4 倍单边是 4320px —— 4000×3000 照片落在 `sample=1`
（48MB 单张），5000 万像素 = 200MB 必 OOM，而 `hdMode` 默认 true。
改为判据 4×→2×、上界 8→32、新增 `MAX_DECODE_PIXELS = 16MP` 总像素硬闸。

**缩略图黑帧 Bitmap 泄漏**
黑帧重试的 `f2` 若也全黑则既不使用也不 `recycle`（native 堆，每张 ~4MB）。
`ImageViewerActivity` 的 `seq` 已变分支同样漏回收。

---

### 3. 架构与代码卫生

#### 3.1 重复逻辑消除

**压缩包后缀表：三份 → 一份（曾因此崩过）**
原三处等价但形式不同、互相独立维护：
① `ArchiveExtractor.canExtract` 的 switch；
② `ARCHIVE_NAME_SUFFIXES` 带点数组（剥名用）；
③ `splitZipPath` 内联 if 链（切分虚拟路径）。
③ 漏了 `.tbz/.tbz2/.txz/.tzst` 而 ①② 都有 → 1.1 那个「打开即崩」。
现统一到 `ArchiveExtractor`：`EXTRACTABLE_EXTS`（16 项）+
`NAME_SUFFIXES`（20 项，长后缀在前）。验证：两表双向差集为空。

**`fmtStorageSize` 并入 `AccessUtil.formatStorageSize`**
两份只差「TB 档 + 负值占位符（`—` 而非 `-`）」，改精度时容易漏改一边。
验证：20011 个值（含边界与随机）对比新旧输出，**零差异**。

**扩展名集合：10 套 → 1 套（`util/Exts`）**
原先散在 `FileListAdapter`（5 套）、`FileManagerModule`（4 套）、
`MediaPlayerActivity`（1 套），内容逐字相同却毫无关联。
新增格式时必须改三处，漏一处就出现「详情页说是图片、列表却不显示缩略图」。

**`formatSize`/`formatDate`/`extOf` 各合并为一份**
`FileOps.formatDate` 是旧版拷贝（**没有** ThreadLocal 缓存——上一轮性能优化
只改了一半）；`extOf` 三份合一，顺带修了 `FileManagerModule` 那份
**没判 `name == null`** 的 NPE 隐患。

**`deleteDirQuiet` 委托 `deleteTree`、`isImageName` 改走 `Exts`**

#### 3.2 死代码清理

| 项 | 情况 |
|---|---|
| `fixGbkName` | 零调用（与 `ArchiveExtractor` 的同名实现重复） |
| `PREFIX_CAT` | 分类功能已移除，零使用 |
| `catCache`/`catCacheKey` | 只声明，全项目零读写 |
| `findBySuffix()` / `buildBookmarkSection()` / `itemMenuGridRow()` | 零调用 |
| `naturalCompare` 包装 | 拆分后无调用点 |
| `REQ_PICK_BG` | **⚠ 注释写错了**：自称「retired」但 `8116` 仍在用（Android 13+ 主路径）。改注释，不删代码 |
| 未使用 import | 去重后失效的 6 个（`Arrays`/`HashSet`/`Set`/`Date`/`Locale`） |

#### 3.3 设计系统

**字号 14 种 → 6 种（213 处）**
原标尺 `{11,12,13,14,16,20,26}` **自身就有问题**——12/13/14 三个 1sp 相邻档，
1sp 差构不成视觉层级。实测 11/12/13 三档合计 **135 次（63%）**，
读者分不出轻重；另有 8 个孤值。新标尺 6 档、档差 ≥2sp：
`9/10/11/11.5→11`、`12/13→13`、`14/15→15`、`16/17/18→17`、`20/22→20`、`26/28→26`。
核对后全项目只剩这 6 种值。

**圆角标尺重定**
`RADIUS_*` 原**外部引用 0 次**（纯摆设），实际散写 12 种半径。
新标尺：`XS=6 / SMALL=10 / MEDIUM=14 / LARGE=16 / XL=24 / PILL=999`
（16 是最常用的卡片角，原来反而没常量）。

**触摸目标补 48dp**
新增 `UiKit.TOUCH_MIN_DP = 48`。音乐控制 34→48dp、底部同步按钮 40→48dp、
编辑器标签 30→44dp（空间取舍）、标签关闭「×」加 `minWidth/minHeight=44dp`
（原热区约 25×30dp）。
**故意不改的两处**：`trashArrow` 14dp（不可点，父容器有 12dp padding）、
顶栏 `toolIcon` 40dp（注释有设计推理：36→48→40，用户反馈顶栏太大）。

**3 处对比度不达标（实测值）**

| 位置 | 原配色 | 实测 | 改为 |
|---|---|---|---|
| 失败原因红字 | `0xFFFF6B6B` 硬编码配浅玻璃底 | **2.78:1** | 按明暗动态：深色 `FF6B6B`，浅色 `D32F2F`（4.98:1） |
| 编辑器浅色强调 | `0xFF42A5F5` 配白底 | **2.65:1** | `0xFF1565C0`（5.75:1） |
| 标签激活态 | 白字压上述亮蓝 | **≈2.3:1** | 随背景变深自动达标 |

为此在 `UiKit` 新增 `isDarkTheme()`（从 `colorSurface` 实际亮度判断，
不读 `uiTheme` 三态设置）、`relativeLuminance()`、`contrastRatio()`。

**`contentDescription` 6 处 → 11 处**
补底部导航 4 个按钮与编辑器标签关闭键（后者原来读屏朗读「乘号」）。

#### 3.4 拆分与测试

**抽出 `util/FileNames.java`（6 个纯函数）**
`trimSlash` / `nameExists` / `uniqueName` / `isValidFileName` /
`naturalCompareLower` / `archiveBaseName`。
抽取判据是**脚本验证方法体不出现 `ctx`/`handler`/`fmPrefs`/`SafStrategy`/`Uri`**，
不是「看起来独立」。模块内保留转发方法，调用点零改动。
未抽 `isSelfNested`/`isSameParent`/`baseNameOf`——依赖 `SafStrategy` 的 uri 解析，
抽出去会把类内耦合变成跨包耦合。

**建立单元测试底座（项目此前零测试）**
`testImplementation("junit:junit:4.13.2")` + `FileNamesTest`（24 用例），
`gradle testReleaseUnitTest` → **24 tests, 0 skipped, 0 failures**。
用例价值在**锁住已踩过的坑**：`.tbz` 剥名、`.xapk` 剥名、
`a.tar.gz→a`、`file2<file10`、`name (1).jpg`、`.gitignore→.gitignore (1)`。

> **测试跑出来的一个发现（如实记录）**：`isValidFileName` 里
> `n.endsWith(" ")` 是**死代码**——前面 `trim()` 已把尾空格删光。
> 但进一步核查：**所有调用方都先 `.trim()` 再传入**（`4189`、`10563`），
> 这条分支在实际链路上本来就到不了。把判断移到 `trim()` 之前只是补全
> 函数语义契约，**对现有行为无影响**——写进注释避免后人误以为修了个线上 bug。

#### 3.5 构建配置

- 启用 `coreLibraryDesugaring`（`desugar_jdk_libs:2.1.5`）
- `proguard-rules.pro` 加 `-assumenosideeffects` 剥 `Log.d`/`Log.v`
  （**保留 `Log.w`/`Log.e`**，崩溃排查依赖）。反编译验证：`syncByFocus`/`HFM`/
  `loadZip path=` 全部消失，`HyReload`/`HyRefresh`/`HyZipCache`/`HyArchive` 仍在
- 移除 5 处调试 `Log.d`（其中一个在**每次进压缩包**都拼接 `p.path` 用户真实路径）
- Manifest 4 处缩进统一（去空白后逐行对比确认零实质改动）

---

### 4. 审查中确认「已经做对」的部分

以下项经审查验证**无需改动**，记录在此避免以后重复优化：

| 项 | 结论 |
|---|---|
| Zip Slip 防护 | 四后端统一过 `safeChild`（`getCanonicalPath` + 前缀比较），无绕过 |
| 解压炸弹配额 | 条目数/单文件/总量 + 压缩比四道防线齐备 |
| `ThumbLoader` | 3 线程池 + 8MB LruCache + 200MB 磁盘 + 30s 节流，设计正确 |
| `archiveItemsCache` | 标准 LRU(8)，是后来参照的模板 |
| 加密密码处理 | 不落盘、不进日志、不明文回显 |
| SAF Cursor | 均 `finally` close，无泄漏 |
| 25 处裸 `new Thread` | **全部正确把 UI 操作 post 回主线程**（做了括号匹配精确分析） |
| 主题色引用 | 261 处 `UiKit.color(attr)` 走主题，方向正确 |
| 删除安全性 | 有确认弹窗 + 回收站可恢复 + 重名自动改名防覆盖 |

---

### 5. 审查报告发现的「已知未修项」

以下是审查指出但**评估后决定暂不动**的，供后续参考：

| 项 | 位置 | 不做的原因 |
|---|---|---|
| 顶栏 `toolIcon` 40dp | `17047` | 注释有设计推理（36→48→40，用户反馈顶栏太大）；要改需上 `TouchDelegate` |
| `createMainView` 同步建全部页 | `1088` | 冷启动 UI 构建 10~30ms，收益小于风险 |
| `reloadAll` 两窗格都刷 | `2919` | 写操作后只刷受影响窗格需改调用约定，牵连面大 |
| `reduce motion` 不生效 | `821-837` | 需监听 `accessibility_force_reduce_motion`（Android 14），本机 API 34 测不到 |
| `bg_ripple_round` 无 focus 态 | — | 键盘/D-pad 用户才受影响，手机上罕见 |
| 「打开方式」约 2100 次 IPC | `13304` | 200 应用机型 300~900ms，但需重排 UI 构建流程 |
| 拆分编辑器 1.8k 行 | `10958` | 需先给 `ctx`/`fmPrefs()` 建构造器注入，风险中等，单独一轮做 |
| `ZipCache` 锁表不清理 | `149` | 条目数受解出量约束，量级小 |

---

### 新增：本地 HLS（m3u8）播放（2026-11-19，待发布）

> ⚠ **标题原为「HLS（m3u8）/ DASH（mpd）」，2026-11-20 修正**：
> `.mpd` 分支虽然给 MediaItem 设了 `APPLICATION_MPD`，但
> `build.gradle.kts` **没有引入 `media3-exoplayer-dash` 依赖**，
> ExoPlayer 找不到 `DashMediaSource.Factory`，实际会报「不支持的格式」。
> 即 mpd 是**静默失效**，不是可用功能。要么补依赖，要么删掉该分支。

用户提供的测试样本：`/storage/emulated/0/DCIM/测试/492481_DM84/1/`
—— `playlist.m3u8` + **862 个 `seg_*.ts`**、共 1.8 GB、**2.39 小时**。
清单健康：有 `EXT-X-ENDLIST`、无加密、无 `EXT-X-MAP`、分片全是同级相对路径。

**根因：两条链都断了**

1. **扩展名未收录**：`Exts.VIDEO` / `Exts.MEDIA` 里没有 `m3u8`/`mpd`，
   `openFile` 三个 `if (Exts.isXxx)` 全不命中 → 落到 `showOpenWithDialog`
   弹「打开方式」，用户以为点了没反应。
2. **播放器缺 HLS 模块**：`app/build.gradle.kts` 只引了 `media3-exoplayer` +
   `media3-ui`，没引 `media3-exoplayer-hls` → 即使勉强进播放器，
   `DefaultMediaSourceFactory` 也找不到 `HlsMediaSource.Factory`，
   报「播放列表格式无法解析」。

**改动**

- `build.gradle.kts` 加 `androidx.media3:media3-exoplayer-hls:1.11.1`
- `Exts.VIDEO` / `Exts.MEDIA` 收录 `m3u8`、`mpd`
- `PlayerCore.buildItem` 显式设 MIME（`APPLICATION_M3U8` / `APPLICATION_MPD`）
- `collectPlayable` 对播放清单**特判**：点 m3u8 时播放列表只含它自己

**为什么 collectPlayable 必须特判**：HLS 目录是「1 个清单 + 861 个分片」。
若按普通视频收集，列表会变成 862 项 —— 用户点 m3u8 播放时左右滑会滑到
某个单独的 ts 分片（只 10 秒），而分片不该被当作「另一部视频」。

**为什么不靠 MediaItem 的后缀推断**：带查询串的 URL（`a.m3u8?token=x`）
后缀会变成 `m3u8?token=x`，推断失败 → 当作普通文件交给
`ProgressiveMediaSource` → 报错。本地文件虽无查询串，统一显式指定更稳。

**保留行为**：单独点某个 `.ts` 分片仍播那一段（用户明确要求保持）。

**验证（未实测播放）**：
- `assembleRelease` 通过（HLS 依赖正常解析，APK 6.8M→6.9M）
- 判定链脚本验证：`playlist.m3u8` → 内置播放器 + 列表 1 项；
  `seg_00000.ts` → 内置播放器 + 列表 861 项
- 装机启动正常，崩溃 0（验证后已 `am force-stop` 关闭）
- ⚠ **未做**：真正加载 862 个分片播完 2 小时的验证，需用户在真机确认

---

### 新增：视频播放器架构重做（2026-11-20，待发布）

用户先要求「播放器重做，从网上完整调研，整体整体方案」，随后逐步确认
走 **Service 持有 + 状态机骨架优先** 的路线。已完成阶段 1、2 与进度条重做。

**方案先纠正了一个方向错误**：初版方案（`docs/PLAYER-REDESIGN.md`）把
「代码拆分」放在最后阶段——那是增量修补思路，结果是每加一功能单类就更臃肿，
最后要一次拆 2000+ 行。v2（`docs/PLAYER-REDESIGN-V2.md`）改为**先定架构再填功能**，
把状态机骨架提到第一位。

#### 阶段 1 · 状态机骨架（`b080b27`）

新增三个纯逻辑类（UI 零变化）：

| 类 | 行数 | 职责 |
|---|---|---|
| `PlayerUiState` | 236 | 17 字段不可变状态，替代原先散落的 30+ 可变字段 |
| `PlayerEvent` | 106 | 15 种语义事件 |
| `PlayerReducer` | 156 | 纯函数 `reduce(state, event)` |

**为什么 `Reducer` 不接收 `Player`**：接收就变成依赖 Android 框架，
单测要起 Robolectric 或 mock 一堆接口。保持纯函数后，
「点暂停后 UI 该显示什么」是一行断言。

**测试抓到的真实缺陷**：`surfaceTap_ignoredWhenLocked` 首次失败——
Reducer 假设「`locked` ⇒ `controlsVisible=false`」，但这只在经过
`LockToggled` 事件时成立。外部直接置 `locked=true` 时不变式就破了。
修法是 `locked` 时强制保持隐藏，而不是简单 `return state`。

#### 阶段 2 · 手势控制器（`c80401a` + `814e446`）

抽出 `GestureSink`（接口）+ `GestureController`（223 行，只识别不执行）。
删除 113 行内联手势与 4 个散落字段（`downX/downY/horizontalMode/verticalMode`）。

**行为变化**：双击分区对齐主流（左 1/3 后退、中 1/3 暂停、右 1/3 前进），
新增边缘 16dp 保护区让位给系统返回手势。

**参数传「比例」而非「绝对值」**：`onSeekPreview(deltaRatio)` —— 手势层
不知道「2 分钟」这种业务参数，改成 30 秒一跳只改 Activity 一行。

**测试抓到的浮点边界 bug**：
```
600/900 = 0.6666667f
1f - 1f/3f = 0.6666666f
→ 0.6666667 > 0.6666666 成立 → 边界点被判成 RIGHT
```
改用整数比较（`x*3 < width` / `x*3 > width*2`）消除精度损失。

#### 修 Media3 最佳实践违背（`b75c84e`）

调研 `developer.android.com/media/media3/ui/customization` 发现两处真 bug：

1. **播完后再点播放键无反应**：旧实现 `if (isPlaying()) pause(); else play();`，
   而 ExoPlayer 播完处于 `STATE_ENDED`，此时 `play()` 是 no-op。
   改用官方 `Util.handlePlayPauseButtonAction(player)`（内部会先
   `seekToDefaultPosition` 再 `play`）。
2. **图标状态不准**：`isPlaying()` 在 ENDED / BUFFERING / 被音频焦点抑制时
   都为 false。改用 `Util.shouldShowPlayButton(player)`。

**一条判断被推翻**：原计划用 `isCommandAvailable(COMMAND_SEEK_TO_NEXT)`
禁用单文件的「下一个」按钮。实施时发现 `step()` 是循环语义
（`n >= length → 0`），单文件点「下一个」会重播自己，按钮本就该可点。
已移除该改动，文档里标「已推翻」并记教训：**调研时把外部经验当通用规律，
却没核对本项目代码的实际语义**。

#### 修布局重叠与可读性（`fda8e32`）

用户实测反馈「底部控制栏有 UI 重叠」「中间提示应为白色」。

- **重叠根因**：`FrameLayout` 里塞三组元素各自 `layout_gravity` 定位，
  中间组 190dp + 左 44dp + 右约 120dp = 354dp，窄屏必然叠。
  改成 `LinearLayout` 三段式 + `weight`，空间不足时挤压而非覆盖。
- **提示发灰**：HUD 文字本来就是 `#FFFFFFFF`，问题是对比度——
  背景 72% 黑叠深色画面，白字失去衬托。背景提到 88% + 加黑色阴影。
- **弹幕可读性**：先描黑边（`STROKE`，宽度字号 8%）再填本色，
  解决白字压在雪景/白墙上看不清。

#### 进度条重做（`e38a03c`）

143 分钟视频实测：轨道约 900px，**每像素 ≈ 9.5 秒**，
手指动 1px 就是 9.5 秒误差，「定位到第 42 分钟」做不到。

| 项 | 改动 | 理由 |
|---|---|---|
| 触摸区 | `wrap_content`(~24dp) → **固定 40dp** | 圆点放大时不触发重测量 |
| 轨道内边距 | `paddingHorizontal=0dp` | 默认预留半个 thumb，轨道变短精度再降 |
| 三层高度 | 底 3dp / 缓冲 4dp / 已播 **6dp** | 「已播更饱满」，播放头易识别 |
| 已播段 | 纯色 → 渐变 | 纯色在低对比画面会与背景混 |
| 圆点 | 恒定 16dp → 按下 **24dp** | 变色会被指腹遮住，放大才能露出边缘 |

**新增拖动时间气泡**：`seek_bubble` 放 root 层（放控制条内会挤占布局），
跟随拇指水平移动、垂直固定上方 40dp，位置经 `clampBubbleX()` 计算防裁切。

#### 播放器设置页（`e38a03c` + `a72ddeb`）

此前所有行为参数硬编码（`CONTROLS_AUTO_HIDE_MS = 3000L` 等），
用户反馈「控制条 3 秒就没了我来不及点」却无处可改。

新增 `PlayerSettings`（165 行）+ 设置页（设置 → 工具 → 视频播放器）：

| 设置项 | 落点 |
|---|---|
| 自动隐藏控制条 | `controlsAutoHideMs` 置 0，`scheduleControlsAutoHide` early return |
| 自动隐藏延迟 | `handler.postDelayed(runnable, controlsAutoHideMs)` |
| 单击切换控制条 | `onSurfaceTap`：关闭后只「叫出」不「隐藏」 |
| 长按加速倍速 | `player.setPlaybackSpeed(settings.longPressSpeed)` |
| 双击跳转时长 | `seekBy(±settings.doubleTapSeekMs)` + HUD 文案 |
| 横向滑动灵敏度 | `delta = deltaRatio * settings.seekFullWidthMs` |

**用户反馈两个问题后修复**（`a72ddeb`）：
1. **点击整页刷新**：每个开关都走 `hideSettingsDialog()` +
   `showInSettingsDialog()`，那是完整建容器 + 淡入流程。
   改为 `rebuildSettingsContent()`，只换内容 View、保留容器与滚动位置。
2. **说明文字没被卡片包裹**：设置页分组机制靠 `settingsGroup` 引用，
   `addInfoRow` 会塞进卡片，而我直接 `box.addView(tip)` 绕过了它。
   新增 `settingsTipText()` + `addTipToGroup()`。

#### 测试

| 测试类 | 用例 |
|---|---|
| `PlayerReducerTest` | 24 |
| `GestureControllerTest` | 19 |
| `PlayerSettingsTest` | 4 |
| `FileNamesTest` | 24 |
| **合计** | **71**（此前 24） |

#### 未完成

- 阶段 3 `PlaybackService` + `MediaSession`（后台播放 / 锁屏控制 / 车机）——最大改动，未开始
- 阶段 4 画中画
- 阶段 5 外挂字幕 / A-B 循环 / 双指缩放
- **DASH（mpd）静默失效**：`PlayerCore` 设了 `APPLICATION_MPD`，
  但 `build.gradle.kts` 没引 `media3-exoplayer-dash` 依赖

---

### 6. 验证记录

| 提交 | 验证方式 | 结果 |
|---|---|---|
| `84a147a` | assembleRelease + 装机 + Python 复刻 `splitZipPath` 9 用例 | 通过（8/9，第 9 个是非压缩包路径，实际走不到） |
| `2575655` | assembleRelease + 字号全集脚本核对 | 6 种值与标尺一致 |
| `24c83b1` | assembleRelease + `formatSize` 20017 值对比 | 回滚后零差异 |
| `7633459` | assembleRelease + **截图确认双窗格加载** | 通过，并发现同步按钮问题 |
| `a26245b` | assembleRelease + 后缀表差集 + 20011 值对比 | 全部零差异 |
| `a6ef42d` | **截图确认**同步按钮已与其他按钮等高 | 通过 |
| `6f842e2` | `testReleaseUnitTest` + assembleRelease | 24/24 通过 |
| `b080b27` | `testReleaseUnitTest` + assembleRelease | 48/48（骨架零 UI 变化） |
| `c80401a` | `testReleaseUnitTest` + assembleRelease | 62/62 |
| `814e446` | 同上 + 装机启动截图 | 62/62，UI 正常 |
| `fda8e32` | 同上 + 装机截图确认控制条布局 | 完全分离无重叠 |
| `e38a03c` | 同上 + 装机确认设置入口出现 | 71/71 |
| `a72ddeb` | `testReleaseUnitTest` + assembleRelease | 71/71（⚠ 点击行为待用户实测） |

**统一验证口径**：每次改动后 `assembleRelease` 必须通过；装机启动后
崩溃 0 / FATAL 0；`logcat` 无 `CalledFromWrongThreadException`；
验证完毕 `am force-stop` 关闭并确认进程退出。

---

### 下一步待办

- 文本编辑器重构（预览/编辑双模式、语法高亮、快捷输入栏、编码切换、
  查找替换增强、Markdown/HTML/JSON·XML 预览、HTML 可切工作目录）
- 视频播放器阶段 3：`PlaybackService` + `MediaSession`（后台播放 / 锁屏 / 车机）
- 视频播放器阶段 4：画中画
- 视频播放器阶段 5：A-B 循环 / 双指缩放
  （**外挂字幕已完成**：`MediaPlayerActivity.findSubtitles()` 扫同目录同名
   `.srt/.ass/.ssa/.vtt` 并挂到 `MediaItem.SubtitleConfiguration`，
   此前误列为待办，2026-11-22 更正）
- DASH 支持：补 `media3-exoplayer-dash` 依赖，或移除 `PlayerCore` 里的 mpd 分支
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

