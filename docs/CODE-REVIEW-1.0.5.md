# HY文件管理 代码审查报告

**版本**：v1.0.5（vc10，HEAD `7c685b0`）
**审查日期**：2026-11-15
**规模**：42 个 Java 文件，27,995 行

---

## 总体评价

代码质量**优于同类个人项目**。几个值得肯定的点：

- **注释写「为什么」而非「做什么」**，且大量记录了已修复的 bug（如「旧写法在两侧各调一次 listFiles，第二次结果直接丢弃」）
- **`archiveItemsCache` 用了标准 LRU**（`LinkedHashMap` + `removeEldestEntry`，上限 8）—— 说明作者知道该怎么做
- **`reloadAll` 双层异常隔离**，注释明确说明崩溃堆栈来自此方法
- **`ThumbLoader` 后台线程池 + `MIN_PRIORITY`**，不抢 UI 线程
- **应用图标双层缓存**（`appIconCache` + `appIconQueried`），避免滚动时反复走 PackageManager IPC
- **try-with-resources 用得多**（64 处）多过裸流（41 处）
- **Manifest 组件除 LAUNCHER 外全部 `exported="false"`**

下面按严重程度列出问题。

---

## P1 · 应修复

### 1. 四处缓存/栈无容量上限，会随使用无限增长

**位置**：`FileManagerModule.java:544, 550, 553, 593`

| 字段 | 类型 | 增长来源 | 上限 |
|---|---|---|---|
| `scrollStateByDir` | `HashMap` | 每浏览一个新目录 +1 | ❌ 无 |
| `dirCache` | `HashMap` | 每浏览一个新目录 +1 | ❌ 无 |
| `backStack` | `ArrayDeque` | 每次进目录 push | ❌ 无 |
| `fwdStack` | `ArrayDeque` | 每次后退 push | ❌ 无 |

**为什么是问题**：

- `dirCache` 的 value 是 `DirSnapshot`，内含**整个目录的 `ArrayList<FileEntry>` 快照**。浏览一个 5000 文件的目录，这一条就是 5000 个对象。走 100 个目录 = 50 万对象常驻。
- `scrollStateByDir` 的 value 是 `Parcelable`（`RecyclerView` 布局状态），虽小但同样无界。
- `backStack` 用户反复前进后退 1000 次就存 1000 个路径字符串。

**对比证据**：同文件的 `archiveItemsCache`（15671 行）就是正确的 LRU 写法：

```java
private final Map<String, List<ArchiveItem>> archiveItemsCache =
        new LinkedHashMap<String, List<ArchiveItem>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<...> eldest) {
                return size() > ARCHIVE_CACHE_MAX;   // 8
            }
        };
```

**建议**：`dirCache` 改成同样的 LRU，上限 16~32（覆盖日常往返的目录够用）；`scrollStateByDir` 上限 ~15（对应 Android 的 `ACTIVITY_MAX_RESUMED` 量级）；`backStack`/`fwdStack` 在 push 后判 `size() > 50` 则 `removeLast()`。

`remove` 时注意：`dirCache` 被 `reloadAll` 整体 `clear()`，替换成 LRU 后 `clear()` 仍然可用。

---

### 2. 下拉刷新的 `reload(p)` 异常被完全吞掉

**位置**：`FileManagerModule.java:1656-1668`

```java
refresh.setOnRefreshListener(() -> {
    try {
        if (p.path != null && p.path.startsWith(PREFIX_ZIP)) {
            ...
            archiveItemsCache.remove(zp[0]);
        }
        reload(p);                          // ← 这里抛异常会静默失败
    } catch (Throwable ignored) {
    }
    // 后面照常收起转圈
```

**问题**：`reload` 里包含 `AccessRouter.list()`、`listFiles()`、SAF 查询、权限判定。任何一处抛异常（权限被撤销、目录被删、路径失效），用户看到的是**转圈消失但列表没变**，且无任何提示。

**对比**：同一文件的 `reloadAll`（2786 行）对 `reload(p)` 就用了 `Log.w("HyReload", ...)` 记录。这里却没有。

**建议**：改为 `catch (Throwable t) { Log.w("HyRefresh", "刷新失败", t); UiKit.toast(ctx, "刷新失败：" + t.getMessage()); }`

---

### 3. 发布包残留 5 处 `Log.d`，且 proguard 未配置移除

**位置**：
- `FileManagerModule.java:2240` `syncByFocus: active=...`
- `FileManagerModule.java:2311` `setActive: ...`
- `FileManagerModule.java:13387` `HYOpenWith: ...`
- `FileManagerModule.java:15707` `loadZip path=...`
- `FileManagerModule.java:15798` `loadZip result dirs=...`

**为什么是问题**：

1. **性能**：`15707` 那行在**每次进入压缩包**时拼接字符串（`p.path`、`inner`、`items.size()`），字符串拼接在热路径上是实打实的开销
2. **隐私**：`p.path` 含用户真实文件路径，logcat 任何有日志权限的应用都能读
3. `proguard-rules.pro` 里**没有** `assumenosideeffects` 配置，R8 不会自动剥掉

**建议**：在 `proguard-rules.pro` 加

```proguard
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
```

或者把这 5 处改成条件守卫 `if (BuildConfig.DEBUG)`。

---

## P2 · 建议改进

### 4. 155 处空 catch 中，多数合理但有几处值得加日志

**分布**：

| 文件 | 数量 |
|---|---|
| `FileManagerModule.java` | 76 |
| `MediaPlayerActivity.java` | 19 |
| `access/SafStrategy.java` | 11 |
| `ui/UiKit.java` | 10 |
| `filemgr/ThumbLoader.java` | 9 |
| 其余 7 个文件 | 30 |

**抽样判断**：绝大多数是**合理的防御性保护** —— UI 状态恢复（`scrollStateByDir.put`）、缓存清理（`dirCache.clear`）、`setRefreshing(false)`、`setSelection`。这些失败确实无害，吞掉是对的。

**例外**：`maybePurgeArchiveCache`（2826 行）整个方法体被 `catch (Throwable ignored)` 包住，而这个方法负责**释放磁盘缓存**。失败时用户不会知道缓存没清掉（磁盘白占空间）。建议至少 `Log.w`。

---

### 5. `reload()` 在主线程做文件 IO

**位置**：`FileManagerModule.java:2832` 起的 `reload(Pane p)`

**现状**：`AccessRouter.list(ctx, p.path)`、`dirF.listFiles()`、`dirF.lastModified()` 全部在主线程同步执行。

**缓解措施（作者已做）**：
- 目录指纹缓存（`mtime` + 子项数），命中则跳过 `listFiles`
- 避免重复扫描（注释明确记录了「旧写法两侧各调一次」的修复）

**残留风险**：首次进入大目录（如万级文件的 `Download`）或指纹失效时，仍是**主线程同步扫描**。实测 Android 上 `listFiles()` 万级目录约 200~500ms，会掉帧。

**建议**：这是架构级改动，代价大。若当前体验可接受，**不改**；若用户反馈「进大目录卡顿」，再考虑异步化（先显示缓存快照 → 后台扫描 → 有变化再更新）。

---

### 6. `Directory` 缓存指纹用 `lastModified()`，在某些文件系统上不可靠

**位置**：`FileManagerModule.java:2889-2896`

```java
long mt = dirF.lastModified();
File[] kids = dirF.listFiles();
cc = kids == null ? 0 : kids.length;
if (snap != null && snap.mtime == mt && snap.childCount == cc) {  // 复用缓存
```

**风险**：这是「mtime + 子项数」双重判据，比单用 mtime 好得多（注释也说明 root 路径已禁用缓存）。但仍有一个漏洞：**修改文件内容不改变目录 mtime 和子项数**（除非文件大小变化被界面显示）。对本应用（显示名称/大小/时间）来说，文件被外部改写但目录项数不变时，界面会显示旧的大小/时间。

**建议**：可以接受（这是主流文件管理器的共同取舍），但如果用户反映「文件大小没更新」，根因在这里。可在下拉刷新时强制清 `dirCache`（当前 `reloadAll` 已 clear，但 `refresh` 回调没有）。

---

## P3 · 小问题

### 7. Manifest 缩进错乱

`AndroidManifest.xml:91` 起，`ImageViewerActivity` 等几个 activity 的缩进比 `<application>` 的子级多出 4 格：

```xml
        </activity>
            <activity            ← 多缩进了
            android:name=".ImageViewerActivity"
```

不影响功能，但说明这段是后来插入时没对齐。

---

### 8. 可能的未使用导入 2 处

静态扫描发现 2 个导入的类名在文件正文中不再出现。数量很少，可以忽略或顺手清掉。

---

### 9. 硬编码路径 13 处

`/sdcard`、`/storage/emulated`、`/data/adb` 等直接写死在代码里。对文件管理器来说这很难完全避免（要识别这些特殊路径），但建议集中到一个常量类，便于将来适配多用户/工作资料。

---

## 未发现问题的项（已验证）

| 检查项 | 结果 |
|---|---|
| 静态 Context 泄漏 | ✅ 无（全部是方法参数传递，无 `static Context` 字段） |
| 资源泄漏 | ✅ try-with-resources 64 处 > 裸流 41 处 |
| 组件导出安全 | ✅ 仅 LAUNCHER 导出，其余 `exported="false"` |
| 线程安全（`runTask`） | ✅ 单线程队列 + 前台服务保活 + 异常提示，设计合理 |
| 图片解码线程 | ✅ `ThumbLoader` 用 3 线程池且 `MIN_PRIORITY` |
| 图标查询缓存 | ✅ 双层缓存避免滚动时 PackageManager IPC |
| `archiveItemsCache` | ✅ 标准 LRU，上限 8 |
| 空 catch 合理性 | ✅ 抽样 8 处，多数是有意的防御性保护 |

---

## 修复状态（2026-11-15）

| 项 | 状态 |
|---|---|
| P1-1 四处缓存加上限 | ✅ 已修（LRU + `pushHistory`） |
| P1-2 刷新失败提示 | ✅ 已修（Log.w + toast） |
| P1-3 移除 5 处 `Log.d` + R8 剥离 | ✅ 已修（反编译验证字符串已消失） |
| P2-4 `maybePurgeArchiveCache` 加日志 | ✅ 已修 |
| P3-8 未使用导入 | ✅ 已修（2 处） |
| P3-7 Manifest 缩进 | ✅ 已修（4 处，去空白后逐行对比确认零实质改动） |
| **新发现：下拉刷新不清 `dirCache`** | ✅ 已修（真 bug，见下） |
| P2-5 主线程 IO 异步化 | ⏭ 评估后不动（架构级，风险大于收益） |
| P2-6 缓存指纹刷新取舍 | ⏭ 保持取舍，但**刷新入口必须清缓存**（已修） |

### 补充发现：下拉刷新等于没刷（真 bug）

审查 P2-6 时发现的连带问题。`refresh.setOnRefreshListener` 只清了压缩包的
`archiveItemsCache`，没清目录快照 `dirCache` —— 于是 `reload()` 发现
指纹（mtime + 子项数）没变，直接读旧快照。

**实测复现**：

```
文件 f1.txt: 2 字节 → 改写为 49 字节
目录 mtime:  1794756650（未变）
子项数:      3（未变）
→ 旧代码命中缓存，显示旧的 2 字节，下拉刷新也刷不出来
```

这恰好是 P2-6 描述的「文件被外部改写时界面不更新」场景的**唯一用户可自救途径**，
而它被别一个 bug 堵死了。修复：刷新回调开头加 `p.dirCache.clear()`。

验证：`assembleRelease` 通过，装机后主界面渲染正常，logcat 无崩溃。

## 建议的修复顺序

| 优先级 | 问题 | 工作量 | 收益 |
|---|---|---|---|
| 1 | 4 处缓存加上限 | 小（改 3 个字段类型 + 2 处 push 判断） | 长时使用内存不再增长 |
| 2 | 刷新失败提示 | 极小（改 1 个 catch） | 故障可见 |
| 3 | 移除 5 处 `Log.d` | 小 | 性能 + 隐私 |
| 4 | `maybePurgeArchiveCache` 加日志 | 极小 | 磁盘缓存问题可诊断 |
| 5 | Manifest 缩进 | 极小 | 整洁 |
| — | 主线程 IO 异步化 | **大**（架构级） | 仅在实测有卡顿时才值得 |

前 5 项合计约 30 分钟，都属于「低风险高收益」。第 6 项建议先不做。
