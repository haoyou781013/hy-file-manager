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
