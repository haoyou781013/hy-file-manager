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
