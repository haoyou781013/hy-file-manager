# 开发日志（未发布改动累积）

> 本文件记录**尚未发布**的改动。用户说「发布新版本」时，把这里的内容
> 汇总进 `versions.json`（面向用户）+ `CHANGELOG.md`（面向开发者），
> 然后清空本文件。
>
> 记录规则：每条写「改了什么 / 为什么 / 影响面」，不写调试过程。

---

## 待发布

（暂无。1.0.5 的内容已汇总进 `versions.json` + `CHANGELOG.md`，见其 1.0.5 章节。）

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

