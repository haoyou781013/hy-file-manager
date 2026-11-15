# HY文件管理 · 三视角独立代码审查汇总

**日期**：2026-11-16
**方法**：3 个无上下文的独立子代理分别审查「性能与内存」「正确性与健壮性」「架构与可维护性」，主会话逐条验证关键结论。

**规模修正**：文件实际 **17162 行**（此前记录 17118 有偏差），全项目 28038 行，该文件占 61.2%。

---

## 结论摘要

| 视角 | 最严重发现 |
|---|---|
| 正确性 | **minSdk 23 上列目录必崩**（用了 API 26 的 `Files.isSymbolicLink`） |
| 正确性 | **SAF 复制失败当成功 → 删源丢数据** |
| 正确性 | **编辑器保存非原子 + dirty 无条件清零 → 丢代码** |
| 性能 | **高清模式位图上限 166MB 单张 × 3 页 → 确定性 OOM** |
| 性能 | **拖动框选每帧全表重绑**（`if (changed \|\| true)`） |
| 架构 | **同一解压逻辑写两遍且后缀表不一致**（`.xapk` 漏了） |

这些比我第一轮审查发现的问题严重得多。第一轮我侧重「代码卫生」，漏了**平台兼容性**和**数据安全**。

---

## 一、会导致崩溃

### C1. minSdk 23 却调用 API 26 的 `java.nio.file.Files`

**位置**：
- `access/AccessRouter.java:120` — `toEntry()` 里 `Files.isSymbolicLink(f.toPath())`
- `access/RootVirtualStrategy.java:48` — 同样无版本判断
- `FileManagerModule.java:6715` — `exportDiagnosticLog` 的 `Files.readAllBytes`

**触发**：Android 6.0 / 7.0 / 7.1 设备进入任意真实目录。
**后果**：`toEntry()` 对每个子项都调 → `NoClassDefFoundError`，**列目录必崩**。

**关键证据**：同项目 `FileManagerModule.java:16942` 的 `isSymlink()` 就是正确处理：

```java
private static boolean isSymlink(File f) {
    if (android.os.Build.VERSION.SDK_INT >= 26) {
        return java.nio.file.Files.isSymbolicLink(f.toPath());
    }
    // 低版本兜底：canonicalPath != absolutePath
}
```

**作者知道怎么做（`deleteTree` 用了正确版本），但 `AccessRouter` / `RootVirtualStrategy` 漏了。**

**修复**：把 `isSymlink()` 移到公共工具类（如 `FileOps`），两处改调用。

### C2. 未启用 coreLibraryDesugaring，却用了 API 24/33 类库

**位置**：
- `filemgr/FileOps.java:219` — `java.util.function.Supplier`（API 24）
- `FileManagerModule.java:6955/9802/15521` — `Supplier` / `Consumer`（API 24）
- `archive/ZipCache.java:171` — `ConcurrentHashMap.computeIfAbsent`（API 24）
- `archive/ZipCache.java:208` — `List.removeIf`（API 24）
- `FileManagerModule.java:12447` — `String.repeat`（**API 33**，每次回车自动缩进都走）

**证据**：`app/build.gradle.kts` 无 `coreLibraryDesugaring`，且 `lint.abortOnError=false` 把 NewApi 掩盖了。

**后果**：API 23 设备 `NoClassDefFoundError` / `NoSuchMethodError`。

**修复**：
```kotlin
compileOptions {
    isCoreLibraryDesugaringEnabled = true
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```
`String.repeat` 改用项目里已有的 `repeatSpaces(n)`（11640 行）。

---

## 二、会导致数据丢失

### D1. SAF 复制失败被当成功 → 删源丢数据

**位置**：`access/SafStrategy.java:406-411`（`copyInto` 内）+ `:598-603`（`moveInto` 第③步）

```java
boolean ok = true;
File[] children = src.listFiles();
if (children != null) {              // ← null（无读权限）被静默跳过
    for (File c : children) { ... }
}
// ok 仍为 true → 返回成功
```

`moveInto` 随后：
```java
if (!copyInto(...)) return false;    // copyInto 返回 true
if (!deleteDocument(ctx, srcDoc)) {  // ← 删掉源目录
```

**后果**：目标目录建成空的 → 返回 `true` → **源目录连同全部内容被删，界面报「成功」**。

**对比**：`FileOps.copyRecursive:244` 已修同类问题（`listFiles()==null` 抛 IOException），**SAF 侧没跟上**。

**修复**：`children == null` 时抛 IOException 返回 false；`listChildren` 失败返回 `null` 而非空 list，与「真空目录」区分。

### D2. 编辑器保存非原子 + dirty 竞态

**位置**：`FileManagerModule.java:11223`、`:11239-11241`

**问题 A（非原子写）**：`new FileOutputStream(tab.file)` 原地截断。写一半被杀/掉电 → 原文件变半截或 0 字节。

**问题 B（dirty 竞态）**：保存大文件期间用户继续输入（TextWatcher 置 `dirty=true`），worker 完成后 handler **无条件** `tab.dirty=false` → 关闭标签时判 `!dirty` 直接移除，**新输入静默丢弃**。

**修复**：先写 `xxx.tmp` 再 `renameTo`；完成回调里比对保存前的 content hash，不一致则保持 dirty。

### D3. 解压到已存在目录会清空整棵树

**位置**：`archive/ArchiveExtractor.java:80-82` + `:148-150`

注释明确「不存在会自动创建」，即允许 `destDir` 已存在。但 GBK 重解路径里有 `deleteTreeQuietly(destDir)` —— **把用户原有文件整树删除**。

**修复**：只在本次新建的空目录上允许清空重解；已存在目录解到临时目录再合并。

---

## 三、会导致数据错乱

### X1. 加密条目未识别时用 java.util.zip 直读，写垃圾并报成功

**位置**：`FileManagerModule.java:16048-16080`、`archive/ArchiveExtractor.java:307-318`

`readZipEncryptionMap` 解析失败时 `catch (Throwable ignored)` 返回空 map → 判为「未加密」→ 走 `ZipFile.getInputStream`。**传统 ZipCrypto 在 JDK 里不校验密码，返回错位字节**，且 `err==null` → 提示「已解出」并按图片打开（乱码），还污染 `zipview` 缓存。

**修复**：解析失败时改用 `getMethod()!=STORED` + 本地头加密位探测，或用 zip4j 读一次判断 `isEncrypted()`。

### X2. 属性框全局 `.replace("计算中…")` 两个占位符互相顶掉

**位置**：`FileManagerModule.java:10616`

目录大小线程先于子项计数返回时 → 「项目数：计算中…」被替换成大小文本 → 显示「项目数：1.2 MB」。

**修复**：分两个 TextView 分别回填。

### X3. 粘贴时 `uniqueName()` 未回写 `existing` 集合

**位置**：`FileManagerModule.java:3730`

一批粘贴中两个源同名 → 都解析成 `name (1)` → 后写覆盖前写，**用户少一个文件**。

**修复**：`uniqueName` 返回后 `existing.add(name)`。

### X4. `ZipCache` 直接写最终路径，无锁无临时文件

**位置**：`archive/ZipCache.java:98-104`

缩略图线程与查看器同时请求同一条目 → 读到半截文件；半截文件满足 `length()>0` 被后续命中复用 → **错误内容长期驻留**。

**修复**：写 `out.tmp` 后原子 rename，同 key 加锁。

### X5. 缩略图黑帧重试的 Bitmap 泄漏

**位置**：`filemgr/ThumbLoader.java:259-263`、`:266`

`f2` 全黑时既不 `recycle` 也不使用，直接丢弃（native 堆）。同类：`ImageViewerActivity.java:424` 的 `fb`。

---

## 四、性能问题（按收益排序）

### P1. 拖动框选每帧全表重绑

**位置**：`FileManagerModule.java:1888-1891`、`FileListAdapter.java:217-220`、`:394`

```java
if (changed || true) {          // ← || true 让 changed 判断完全失效
    p.adapter.setSelected(p.selected);   // → notifyDataSetChanged()
```

`applyRangeSelection` 在 `onInterceptTouchEvent(MOVE)` 里每次位置变化就调 → **一次拖动 = 数十次全表 rebind**。

`toggle()` 也用了 `notifyItemRangeChanged(0, getItemCount())`（全表）。

**修复**：维护 `lastFrom/lastTo`，只刷新差集区间。**一行改动，零风险。**

### P2. 高清模式位图上限 16× 屏幕 → 确定性 OOM

**位置**：`ImageViewerActivity.java:565-573`、`:602-605`

```java
while ((w / (sample * 2) > reqW * 4 || h / (sample * 2) > reqH * 4) && sample < 8)
//                        ↑ 4× 屏宽 × 4× 屏高 = 16× 屏幕像素
```

1080×2400 屏理论峰值 41.5M px × ARGB_8888 ≈ **166MB 单张**。ViewPager2 `offscreenPageLimit=1` 常驻 3 页 = 498MB，与 256MB heap 直接冲突。

`ImagePage` 从不 `recycle`（仅 `setImageDrawable(null)`）。

**修复**：上限改为 `≤2×屏幕像素`；`onViewRecycled` 清 drawable。

### P3. `formatDate` 每行构造 SDF ×1 + Calendar ×2

**位置**：`AccessUtil.java:28-37`（`FileListAdapter.java:367/372/376/378` 每行调用）

可见行 ~12，每帧 12 次 `new SimpleDateFormat`（5~15µs/次）≈ **60~180µs/帧**，吃掉 1~3 帧预算。

**修复**：`ThreadLocal<SimpleDateFormat>` 两个实例。

### P4. 缓存命中也全目录扫描

**位置**：`FileManagerModule.java:2941-2944`

```java
if (snap != null) {
    File[] kids = dirF.listFiles();   // ← 命中缓存也要完整枚举
    cc = kids == null ? 0 : kids.length;
}
```

缓存只省 `FileEntry` 分配，**不省 IO**。万项目录每次进目录仍几百 ms。

**修复**：指纹只用 `mtime`，子项数后台补齐。

### P4b. 缓存未命中时同一目录枚举两次

**位置**：`FileManagerModule.java:2957-2967`

```java
p.lastResult = AccessRouter.list(ctx, p.path);   // ← 内部 listFiles() 一次
...
if (cc >= 0) {
    childCount = cc;                    // 有快照时复用
} else {
    File[] kids2 = dirF.listFiles();    // ← 无快照时再列一次
    childCount = kids2 == null ? 0 : kids2.length;
}
```

作者已修掉「三元表达式两侧各调一次」的旧问题，但**「`AccessRouter.list` + 统计 `childCount`」仍是两次全目录枚举**。万级目录白扫一遍 ≈ 100~200ms。

**修复**：直接用 `p.lastResult.entries.size()` 作为 `childCount`（语义一致，就是刚列出的真实条目数）。

### P4c. 权限检查路径上仍有 `Files.isSymbolicLink`

**位置**：`AccessRouter.java:119-133` 的 `toEntry()`

每条目调 `Files.isSymbolicLink` + `isDirectory` + `length` + `lastModified` = **4 次 syscall**，且 `f.toPath()` 还多建一个 `UnixPath` 对象。1 万项目录 = 4 万次 syscall + 1 万个临时对象。

**修复**：用 `Os.lstat(path, stat)` 一次拿到 `st_mode`/`st_size`/`st_mtime`，由 `st_mode & S_IFMT` 判 DIR/LINK/FILE → **4 万次降到 1 万次**。顺带解决 C1（`lstat` 在 API 21+ 就有，无需版本判断）。

### P6b. 每个玻璃弹窗主线程阻塞 ≤80ms + 23MB 临时垃圾

**位置**：`ui/UiKit.java:771`（`latch.await(80ms)`）、`:842-910`（`boxBlur`）

**调用规模**：`glassDialog` 在 `FileManagerModule` 内 **41 处**，全项目 45 处。

**每次开销**：
- `shot` 全屏位图 1080×2400×4 = **10.4MB**
- `boxBlur` 6 趟 × 2 = **6 个 Bitmap + 12 个 int[]** ≈ 12MB
- `liftBrightness` + 裁剪再 1.3MB
- 合计 **≈23MB 临时垃圾/弹窗**
- `latch.await(80ms)` **阻塞主线程**

**修复**：模糊底图按「窗口尺寸+主题+300ms 内」缓存复用；`boxBlurPass` 的 `int[]` 用静态缓冲池；`captureByPixelCopy` 的 `HandlerThread` 复用而非每次 new。

### P7. `cardStyleMap` 强引用泄漏

**位置**：`FileManagerModule.java:301-302`

```java
private final java.util.Map<View, int[]> cardStyleMap = new java.util.IdentityHashMap<>();
```

**key 是强引用**，而配对的 `cardViewRefs`（297 行）是 `WeakReference`。设置页每次重建都产生 200~300 个新 View 全部进 map，旧 View 因被强引用永不回收。清理阈值 `size() > cardViewRefs.size()*2 + 32`（7936 行）随两者同步增长，**基本不会触发**。

**修复**：`cardStyleMap` 换 `WeakHashMap`，或把 `int[]` 并入 `cardViewRefs` 的配对结构。

### P8. 冷启动在主线程递归清扫回收站

**位置**：`FileManagerModule.java:1040` `purgeExpiredTrash()`（在 `createMainView` 内，首帧之前）

回收站有 1 个 5000 文件的过期目录时，`countTree` 遍历 5000 项再 `deleteTree` 删除，**全在主线程**，冷启动白屏 1~3 秒。且每次启动都跑（无「上次清理时间」记录）。

**修复**：移到 `TASK_QUEUE`；持久化 `last_purge_at`，24h 内不重复；`countTree` 与删除合并为单次遍历。

### P9. 「打开方式」弹窗约 2100 次 PackageManager IPC

**位置**：`FileManagerModule.java:13304-13440`

200 个应用时：5 次 `queryIntentActivities` + 1 次 `getInstalledApplications` + 200 次 `getLaunchIntentForPackage` + 200 次 `getApplicationLabel` + **排序 comparator 内约 1500 次 `getApplicationLabel`** + 主线程写诊断文件。

**修复**：整段搬后台；label 先算一次存 Map；诊断写文件那段（13333-13447）是遗留调试代码，直接删。

### P10. `appIconCache` 无上限

**位置**：`FileListAdapter.java:107-111`

`Android/data` 下 3000 个包名目录滚一遍 → 缓存 3000 个 `Drawable`（每个 30~100KB）= **90~300MB**。

**修复**：`LruCache<String, Drawable>` 上限 64。

### P11. `SharedPreferences.commit()` 主线程 25 处，滑块松手写两次

**位置**：`FileManagerModule.java:418-424`、`9970-9977`，滑块 12 处

每个滑块 `onStopTrackingTouch` = `savePrefInt(commit)` + `persistState(24 键 commit)` = **2 次 fsync**（2~10ms，低端机 20ms+）。

**修复**：`savePrefInt/savePrefBool` 内部改 `PREF_HANDLER.post(...)`（签名不变，25 个调用点零改动）；滑块释放合并为一次任务。

### P5. 缩略图每写一张就扫全盘

**位置**：`ThumbLoader.java:497-523`

`writeDisk()` 结尾无条件 `trimDisk()`：全量 `listFiles()` + 每文件 `length()`。5000 张缓存时每解一张 ≈ 5000 次 stat。

**修复**：内存累加字节数，或每 64 次写 / 每 30s 才 trim 一次。

### P6. 编辑器每次按键复制全文两遍

**位置**：`FileManagerModule.java:11106-11113`、`:11563-11572`

`pushUndo(s.toString())` 每次按键复制全文；`refreshEditorStat()` 又复制一遍并全扫。2MB 文件每字符 ≈ 8MB 复制。

### P7. 其他

| 项 | 位置 | 问题 |
|---|---|---|
| 排序器每次比较 2 次 `toLowerCase` | `FileManagerModule.java:3113` | 1 万项 ≈ 28 万临时字符串 |
| ZIP 加密表无缓存且在主线程 | `FileManagerModule.java:16228` | 每次进子目录重解析 |
| 缩略图线程池无界队列 | `ThumbLoader.java:60-66` | 快速滑动排队上千任务 |
| `savePrefInt` 用 `commit()` 在主线程 | `FileManagerModule.java:420` | 每次 1~5ms fsync |

---

## 五、架构问题

### A1. 重复逻辑（比拆分更划算，应先做）

| 重复项 | 位置 | 后果 |
|---|---|---|
| **解压写了两遍** | `extractZip` 15374 vs `tryUnzip` 13125 | **后缀表不一致：15384 有 `.xapk`，13139 没有** → 已存在的功能不一致 |
| 大小格式化三份 | `fmtStorageSize` 5654 / `FileOps.formatSize` 39 / `AccessUtil.formatSize` 16 | 口径可能不一 |
| 递归删除四份 | `deleteTree` 16898 / `deleteDirQuiet` 6890 / `deleteChildren` 5577 / `FileOps.deleteRecursive` 282 | 安全策略不一致风险 |
| 递归统计三份+1 | `dirSize` 16653 / `countFiles` 16665 / `countTree` 16373 / `FileOps.dirSize` 64 | 同一子树各走一遍，IO 翻倍 |
| 递归扫描六份 | scanDir / scanGallery / collectImages / collectPlayable / searchRecursive / collectTrash | 新增功能必漏一处 |
| 扩展名判断四套 | CATEGORIES 110 / IMAGE_EXTS 12724 / TEXT_EXTS 10891 | 新增格式必漏 |

### A2. 可拆分的低风险块

| 优先级 | 行号 | 职责 | 依赖 | 工作量 | 风险 |
|---|---|---|---|---|---|
| P0 | 3113–3230 等 | 纯函数（naturalCompare/isValidFileName/uniqueName/mimeOf） | **零依赖** | 0.5 天 | 极低 |
| P0 | 110–125、12724–12750 | 扩展名知识库 | 无 | 0.5 天 | 低（会暴露遗漏） |
| P1 | 5682、6055–6824 | 更新检查/下载/关于 | ctx、UiKit | 2 天 | 低 |
| P1 | 16288–16604 | 回收站 | FileOps | 1 天 | 低 |
| P2 | 10900–12710 + 146–200 | 文本编辑器（约 1830 行） | ctx、handler、UiKit | 5–7 天 | 中（onDetach 要逐个 close 弹窗） |
| P3 | 15474–16290 | 解压 + zip 浏览 | Pane 状态耦合 | 4 天 | 中高，最后做 |

### A3. 不该拆

- **920–4200**（`createMainView`/`buildPane`/`reload`/导航/菜单/多选）：模块本体，拆=重写
- 约 2500 行设置/主题/卡片透明度：与 `settingsGroup`、87 个 View 字段交织
- 157 处 `new TextView(ctx)` 手写 UI → 转 XML 是净损失

### A4. 死代码（约 250 行可删）

| 位置 | 内容 |
|---|---|
| `needRoot` 2612 | **恒 `return false`**（已验证） |
| `catCache`/`catCacheKey` 134-135 | 只声明，全项目无写入 |
| `PREFIX_CAT` 103、`fixGbkName` 15421、`findBySuffix` 15915 | 无调用 |
| `SHOW_SAF_MOUNTS=false` | 导致 5 处分支永久不走 |

### A5. 并发语义两套并存

**25 处裸 `new Thread`**（已验证），而文件里已有 `TASK_QUEUE` + `runTask`（**仅 9 处使用**）。

**好消息**：精确分析确认 **25 处全部正确把 UI 操作 post 回主线程**，无后台碰 UI 问题。

---

## 六、未发现问题（已核对，不必担心）

| 检查项 | 结论 |
|---|---|
| Zip Slip 防护 | ✅ 四后端统一过 `safeChild`（`getCanonicalPath` + 前缀比较），未发现绕过 |
| 解压炸弹配额 | ✅ 条目数/单文件/总量 + 压缩比检查成立 |
| `FileOps.copyRecursive` 自嵌套拦截 | ✅ 正确 |
| `deleteTree` 不跟随 symlink | ✅ 显式栈实现正确 |
| SAF Cursor 关闭 | ✅ `finally` 中 close，无泄漏 |
| `ThumbLoader` in-flight 去重 | ✅ 正确 |
| 密码是否落盘/进日志 | ✅ 未发现泄漏面 |
| 25 处 `new Thread` 的线程安全 | ✅ 全部正确 post 回主线程 |

---

## 七、修复优先级建议

### 立即修（会导致崩溃/丢数据）

| 顺序 | 项 | 工作量 | 说明 |
|---|---|---|---|
| 1 | **C1 `Files` API 版本判断** | 30 分钟 | 一行封装，Android 6.x 用户必崩 |
| 2 | **D1 SAF 空目录假成功** | 1 小时 | 真丢数据且报成功 |
| 3 | **D2 编辑器原子写 + dirty** | 1 小时 | 丢代码 |
| 4 | **C2 启用 desugaring** | 20 分钟 | 顺带修 `String.repeat` |
| 5 | **P2 高清采样上限** | 30 分钟 | 唯一确定性 OOM |

### 随后修（性能，用户可感知）

| 顺序 | 项 | 工作量 | 收益 |
|---|---|---|---|
| 6 | P1 框选精确通知（含去掉 `\|\| true`） | 20 分钟 | 拖选从 1000~2000 bind/秒降到只刷差异 |
| 7 | P3 `formatDate` 复用 SDF | 20 分钟 | 每帧省 60~180µs |
| 8 | P4b 用 `entries.size()` 替掉第二次 `listFiles` | 10 分钟 | 万项目录省 100~200ms |
| 9 | P5 `trimDisk` 节流 | 20 分钟 | 缩略图加载不再越滚越慢 |
| 10 | P7 `cardStyleMap` 换 WeakHashMap | 15 分钟 | 长会话内存不再单调上涨 |
| 11 | P10 `appIconCache` 加 LRU | 15 分钟 | 防 90~300MB 峰值 |
| 12 | P11 `savePrefInt` 走后台 | 20 分钟 | 滑块操作不再卡 10~30ms |
| 13 | P6b 玻璃弹窗模糊缓存 | 1 小时 | 弹窗首帧不再延迟 20~100ms |
| 14 | X2/X3/X4 三处小修 | 1 小时 | 数据错乱 |
| 15 | P8 回收站清扫移后台 | 30 分钟 | 冷启动白屏 |
| 16 | P9 打开方式搬后台 | 1 小时 | 长按卡 300~900ms |
| — | P4c 用 `Os.lstat` 合 syscall | 2 小时 | 4 万次 syscall → 1 万次（与 C1 一起做） |

### 再后（架构，需谨慎）

11. A1 解压逻辑去重（顺带修 `.xapk` 不一致）
12. A4 删死代码 250 行
13. A2 的 P0/P1 拆分

---

## 八、对第一轮审查的反思

第一轮（主会话自查）发现的是：
- 缓存无上限 ✅ 真实
- 刷新失败静默 ✅ 真实
- `Log.d` 残留 ✅ 真实
- 空 catch 过多 ⚠️ 多数是合理防御

**漏掉的**：
- 平台兼容性（minSdk vs API 级别）—— 这是最严重的，我完全没查
- 数据安全（失败当成功、非原子写）—— 我只查了 Zip Slip，没查「SAF 复制失败」
- 确定性 OOM 路径（高清模式 166MB）

**教训**：代码审查不能只看「代码写法」，必须检查**构建配置与运行时的契约**（minSdk 承诺 vs 实际 API 使用），以及**每条失败路径的实际后果**（是静默、报错，还是丢数据）。
