# HY文件管理 性能优化清单

**版本**：v1.0.5 + 两轮审查修复后
**日期**：2026-11-15

按「收益/风险比」排序。每条给出问题定位、量化影响、修复方案、风险等级。

---

## P0 · 高收益低成本（建议优先做）

### 1. `onBindViewHolder` 每行重复解析主题属性

**定位**：`filemgr/FileListAdapter.java:228-240`

```java
int surface = checked
        ? UiKit.color(context, com.google.android.material.R.attr.colorPrimaryContainer)   // ← resolveAttribute
        : UiKit.color(context, com.google.android.material.R.attr.colorSurfaceContainerLow); // ← resolveAttribute
h.itemView.setBackground(makeBg(surface, checked));   // ← new GradientDrawable
```

`UiKit.color()` 的实现（`ui/UiKit.java:179`）：

```java
public static int color(Context context, int attr) {
    TypedValue tv = new TypedValue();                    // ← 每次 new
    context.getTheme().resolveAttribute(attr, tv, true); // ← 每次查表
    return tv.data;
}
```

`FileListAdapter` 里共 **11 处** `UiKit.color`，但要分清哪些在热路径：

| 行号 | 位置 | 执行频率 |
|---|---|---|
| 234, 235 | `onBindViewHolder` 算 surface | **每次绑定** |
| 271, 274, 277 | `onBindViewHolder` 里 `if (!useThumb)` 分支的图标色 | **每次绑定（非缩略图行）** |
| 359 | `fileColor(e)` | 未知扩展名时**每次绑定** |
| 456, 466, 468, 478 | `buildRow()` 构造 View | 只 `onCreateViewHolder` 时**一次** |
| 504 | `makeBg()` 里画边框 | **每次绑定**（仅选中态） |

所以热路径是 **234/235 + (271|274|277) + 可能的 359 + 可能的 504**。

**量化**：

| 场景 | 调用次数 |
|---|---|
| 绑定一行（目录） | 2 次 `resolveAttribute` + 1 次 `new GradientDrawable` |
| 绑定一行（文件，走 `fileColor`） | 3 次 `resolveAttribute` + 1 次 `new GradientDrawable` |
| 绑定一行（选中态） | 再加 1 次（`makeBg` 里画边框） |
| 滚动 10 屏（100 行重绑） | **约 300 次属性解析 + 100 个 Drawable 分配** |

**修复**：

```java
// 构造时缓存一次（主题在 Activity 生命周期内不变）
private int colorSurfaceContainerLow = 0;
private int colorPrimaryContainer = 0;
private int colorOutline = 0;
private int colorTertiary = 0;
private int colorPrimary = 0;

public FileListAdapter(Context context, ...) {
    this.context = context;
    this.colorSurfaceContainerLow = UiKit.color(context, R.attr.colorSurfaceContainerLow);
    this.colorPrimaryContainer   = UiKit.color(context, R.attr.colorPrimaryContainer);
    // ... 其余同理
}
```

`makeBg` 也可以缓存两个实例（选中态 / 未选中态），只在 `density` 或 `cardAlpha` 变化时重建。

**风险**：低。唯一注意点是**主题切换**（深/浅色模式）时需要重建缓存 —— 在 `setDensity` / `onConfigurationChanged` 里重取即可。

**预期收益**：滚动掉帧减少，长列表滑动更顺。

---

### 2. `formatDate` 每行调用都创建 Calendar×2 + SimpleDateFormat

**定位**：`access/AccessUtil.java:28-37`

```java
public static String formatDate(long millis) {
    if (millis <= 0) return "-";
    Calendar cal = Calendar.getInstance();        // ← 每行 2 次
    cal.setTimeInMillis(millis);
    Calendar now = Calendar.getInstance();        // ← 每行 2 次
    SimpleDateFormat sdf = new SimpleDateFormat(  // ← 每行 1 次
            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ? "MM-dd HH:mm" : "yyyy-MM-dd",
            Locale.getDefault());
    return sdf.format(new Date(millis));
}
```

**调用链**：`buildSub()` → `formatDate()`，**每个文件行都走**（`FileListAdapter.java:363-379`）。

**量化**：绑定 100 行 = **200 次 `Calendar.getInstance()` + 100 次 `new SimpleDateFormat()` + 100 次 `new Date()`**。

`SimpleDateFormat` 的构造会解析 pattern 并建 `DateFormatSymbols`，是重活。

**修复**：

```java
// 两个共享实例（注意：SimpleDateFormat 非线程安全，但这里只在主线程用）
private static final ThreadLocal<SimpleDateFormat> SDF_SAME_YEAR =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()));
private static final ThreadLocal<SimpleDateFormat> SDF_OTHER_YEAR =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()));

public static String formatDate(long millis) {
    if (millis <= 0) return "-";
    int fileYear  = yearOf(millis);
    int nowYear   = CURRENT_YEAR;   // 每次列表刷新时更新一次
    SimpleDateFormat sdf = (fileYear == nowYear) ? SDF_SAME_YEAR.get() : SDF_OTHER_YEAR.get();
    return sdf.format(new Date(millis));
}
```

或者更彻底：在 `AccessUtil` 加一个 `formatDateCached(long)`，用 `LongSparseArray` 缓存最近 200 个结果（同一目录里很多文件的 mtime 相同）。

**风险**：低。`SimpleDateFormat` 非线程安全，用 `ThreadLocal` 或只主线程访问都安全。

**预期收益**：绑定 100 行省 ~200 次 Calendar 创建。

---

### 3. `naturalCompare` 每次比较都 `toLowerCase()` ×2

**定位**：`module/FileManagerModule.java:3113-3116`

```java
private static int naturalCompare(String a, String b) {
    String s1 = a == null ? "" : a.toLowerCase(Locale.getDefault());  // ← 每次比较都建新串
    String s2 = b == null ? "" : b.toLowerCase(Locale.getDefault());  // ← 每次比较都建新串
```

**量化**（`Collections.sort` 的比较次数 ≈ 2·n·log₂n）：

| 目录规模 | `toLowerCase` 调用 | 临时字符串 |
|---|---|---|
| 100 文件 | 1,328 次 | 1,328 个 |
| 1,000 文件 | **19,930 次** | **19,930 个** |
| 5,000 文件 | **122,876 次** | **122,876 个** |

**修复**（两种，按侵入性递增）：

**方案 A（推荐，改动小）**：预先算好小写名放进 `FileEntry`

```java
// FileEntry 加字段
public String lowerName;   // 构造时算一次

// 比较时直接用
private static int naturalCompare(FileEntry a, FileEntry b) {
    String s1 = a.lowerName, s2 = b.lowerName;
    // ... 原逻辑不变
}
```

**方案 B**：`toLowerCase` 只在字符不等时才调（先用原串比较，不等再退化成小写比较）—— 但会改变排序的大小写敏感性，需谨慎。

**风险**：方案 A 低（只是预计算），方案 B 中（影响排序行为）。

**预期收益**：1000 文件目录排序时省 ~2 万次字符串分配。

---

## P1 · 中收益 / 需评估

### 4. `notifyDataSetChanged` 全量刷新（8 处，零 DiffUtil）

**定位**：`filemgr/FileListAdapter.java:42, 89, 121, 199, 209, 214, 219`

```java
public void setSelected(Set<FileEntry> selected) {
    this.selected = selected;
    notifyDataSetChanged();      // ← 全量重绑，即使只勾选了一个
}
```

**问题分层**：

| 场景 | 现状 | 是否值得改 |
|---|---|---|
| 切换缩略图开关 / 密度 / 多选模式 | 全量刷新 | ❌ 不该改（低频，且大部分行确实要变） |
| **多选时勾选一个文件** | **全量刷新** | ✅ **值得改** |
| `setData` 换目录 | 全量刷新 | 视情况：换目录本来就是全新列表，全量合理 |

**修复（针对多选）**：

```java
// 在 FileManagerModule 里勾选回调中，改为精确通知
int pos = entries.indexOf(e);
if (pos >= 0) adapter.notifyItemChanged(pos);
```

**风险**：中。需要确认所有 `setSelected` 的调用点，避免漏刷。多选模式下「全选/取消全选」仍应走全量。

**预期收益**：多选操作从 O(n) 变 O(1)。

---

### 5. `reload()` 主线程同步文件 IO

**定位**：`module/FileManagerModule.java:2876` 起

已在 `docs/CODE-REVIEW-1.0.5.md` 详述。**不建议现在动** —— 需要序号守卫、空窗期 UI、缓存写入时机、`reloadAll` 同步语义变更，风险大于收益。已有目录指纹缓存覆盖「返回上一目录」最高频场景。

**触发条件**：用户明确反馈某目录打开卡顿。

---

### 6. `String.format` 21 处（内部创建 `Formatter`）

**定位**：全项目 21 处，主要在 `AccessUtil.formatSize`（每行调用）。

```java
return String.format(Locale.US, "%.1f KB", kb);   // ← 内部 new Formatter + StringBuilder
```

**修复**：热门路径上的（`formatSize`）可换成手写格式化：

```java
// 只保留 1 位小数，避免 String.format 开销
long v10 = (long)(kb * 10);           // 放大 10 倍取整
return (v10 / 10) + "." + (v10 % 10) + " KB";
```

**风险**：低，但要注意边界（四舍五入 vs 截断、负数、0）。

**预期收益**：绑定 100 行省 100 次 `Formatter` 创建。**收益不如 P0-2 明显**，因为 `formatSize` 只在文件行调用，目录行不调。

---

## P2 · 低收益 / 不必做

### 7. `MarkdownUtils` 逐行 `matches()` 运行时编译正则

**定位**：`util/MarkdownUtils.java:73, 93`

**为什么不急**：只用于「更新说明」弹窗和 Markdown 预览，**低频、非热路径**。一次渲染几百行，多编译几百个 `Pattern` 对用户无感知。

### 8. `ThumbLoader` 缓存

已正确实现：8MB `LruCache` + 200MB 磁盘 + `MIN_PRIORITY` 线程池 + 后台线程。**无需改**。

### 9. 应用图标缓存

已有 `appIconCache` + `appIconQueried` 双层缓存，避免滚动时 PackageManager IPC。**无需改**。

---

## 建议执行顺序

| 顺序 | 项目 | 工作量 | 收益 | 风险 |
|---|---|---|---|---|
| 1 | P0-1 缓存主题色 + Drawable | 30 分钟 | **高**（滚动流畅度） | 低 |
| 2 | P0-2 `formatDate` 复用 SDF | 20 分钟 | **高**（每行都调） | 低 |
| 3 | P0-3 `naturalCompare` 预计算小写 | 30 分钟 | 中（大目录排序） | 低 |
| 4 | P1-4 多选精确通知 | 20 分钟 | 中 | 中 |
| 5 | P1-6 `formatSize` 手写格式化 | 15 分钟 | 低-中 | 低 |
| — | P1-5 主线程 IO | 数小时 | 视场景 | **高**，暂不做 |
| — | P2 其余 | — | 低 | — |

前三项合计约 80 分钟，风险都低，且都是**每次滚动/刷新都受益**的热路径优化。

---

## 验证方法（改完怎么确认有效）

`onBindViewHolder` 的优化效果不能靠感觉，建议这样测：

```bash
# 1. 构建前后各装一次，用同一目录做基准
# 2. 清空应用数据保证冷启动
# 3. 用 gfxinfo 抓帧耗时
adb shell dumpsys gfxinfo com.aliya.hyfilemgr reset
# 滚动列表 10 屏
adb shell dumpsys gfxinfo com.aliya.hyfilemgr | grep -A 3 "Draw\|Prepare"
```

关注 `Janky frames`（掉帧率）与 `90th percentile`。

也可以用 `systrace` / `perfetto` 抓 `onBindViewHolder` 的实际耗时。但**在容器里没法跑 adb**，需要你在手机上用开发者工具的「GPU 渲染模式分析」看直方图。
