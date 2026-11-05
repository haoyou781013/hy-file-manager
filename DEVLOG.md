# 开发日志（未发布改动累积）

> 本文件记录**尚未发布**的改动。用户说「发布新版本」时，把这里的内容
> 汇总进 `versions.json` + `CHANGELOG.md`，然后清空本文件。
>
> 记录规则：每条写「改了什么 / 为什么 / 影响面」，不写调试过程。

---

## 2026-11-04

### 全面审查后的第三批修复（进行中）

本批来源：8 路子代理并行审查 19,776 行后的剩余高危项。

#### 已完成

1. **`runningTasks` 静态 int 无同步**（`TaskKeepAliveService`）
   acquire/release 从 TASK_QUEUE 线程调，resetCount 从 onAttach 主线程调。
   普通 int 的 ++/-- 并发丢更新，计数可为负 → release 里 `> 0` 永远为假
   → 前台服务永不停止，通知栏常驻「正在处理文件」且用户划不掉。
   改 AtomicInteger；release 用 updateAndGet 钳到 0。

2. **`archiveItemsCache` 无上限**（`FileManagerModule`）
   浏览过的每个包都留一份完整条目列表，十万条目的 7z 单份就几 MB。
   改 LinkedHashMap accessOrder=true 做 LRU，上限 8 个包。
   代价：被淘汰的包再进来要重扫（tar 家族明显），但优于内存只涨不降。

3. **缩略图缓存 32 位 hash 碰撞**（`ThumbLoader.diskFile`）
   `Integer.toHexString(key.hashCode())` 只有 32 位，
   生日悖论下约 7.7 万个 key 就有 50% 碰撞。
   后果是**显示别人的缩略图**，或 writeDisk 覆盖另一文件的缓存。
   改 SHA-256 取前 128 位（32 hex 字符）。失败回退 hashCode 保证不崩。

4. **字体预览：任意文件当字体解析 + 临时文件永不删除**（`FontPreviewActivity`）
   - 加 looksLikeFont()：校验前 4 字节魔数（00 01 00 00 / OTTO / true /
     typ1 / ttcf / wOFF / wOF2）+ 大小上限 40MB。
     理由是 createFromFile 直接进 native Skia/FreeType，历史上有解析器漏洞。
   - SAF 分支的临时文件记入 tmpFontFile，新增 onDestroy 清理。
     旧实现每次预览都在 cacheDir 留一份几 MB 的副本，永不删除。

5. **旋转屏幕丢失全部导航状态**（`AndroidManifest.xml`）
   MainActivity 是 4 个 Activity 里唯一没有 configChanges 的。
   旋转 → recreate → Pane 的 path / backStack / fwdStack / dirCache / entries
   全在实例字段上，一次性全丢。
   加 `orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize`。

6. **完全没有音频焦点处理**（`PlayerCore.create`）
   三个日常场景全错：来电/其他 App 播放时不暂停（声音叠加）、
   拔耳机继续外放、切后台不释放 codec。
   media3 的 ExoPlayer.Builder 内建 AudioFocusManager，两行即可：
     .setAudioAttributes(attrs, /* handleAudioFocus= */ true)
     .setHandleAudioBecomingNoisy(true)
   attrs = USAGE_MEDIA + AUDIO_CONTENT_TYPE_MOVIE。
   无需手写 AudioFocusRequest，不用任何权限。

#### 决定不做
- **双窗格路径持久化**：用户明确要求「重启软件重置路径」，
  不保存 paneL/paneR 的 path。原 A3-ARCH 报的「注释声称有路径记忆
  但实际没有」因此不构成缺陷，改为把注释与实际行为对齐。

7. **切换目录过于突然，没有过渡**（`FileManagerModule`）
   navigateTo / navBack / navForward 三处都设 `staggerPending = false`，
   于是翻目录完全无动画 —— 内容瞬间替换。
   但按 Material 规范，翻目录不该用**逐项 stagger**（那是给「新表面」的），
   所以不能简单把 staggerPending 改回 true。
   新增 playDirTransition()：整体 alpha 0.35→1 的交叉淡化，
   出 90ms + 入 140ms，总感知约 200ms（符合 transition ≤300ms）。
   用 dirFadePending 标志在 reload 的数据 bind 完成后触发，
   避免淡入时看到空列表。animScale<=0 时跳过（尊重无障碍减动效）。

8. **进入目录时底部功能栏播放"退出多选"动画**（`FileManagerModule`）
   reload 里 `if (p == active) exitMultiMode()` 会播 160ms 的 opBar 退场动画
   + bottomNav 淡入 + fabNew 淡入。但 reload 紧接着就把新目录数据 bind 上去了 ——
   功能栏还没退完，新内容已经画出，两个动画窗口重叠，观感是底部栏莫名闪一下。

   新增 exitMultiMode(true) 变体：跳过所有动画，一次性把
   opBar 隐藏 / bottomNav 显示 / fabNew 显示 落到「已完成退出」的状态。
   reload 改用它。手动点「关闭」退出多选时仍走动画版，不受影响。

9. **未保存的编辑内容零持久化**（`FileManagerModule` 编辑器）
   旧 saveEditorTabs 只存路径列表，restoreEditorTabs 从磁盘重读，
   只要用户没点保存，内容就没了（杀进程/选「放弃修改」后重开必丢）。
   另外 root:// 前缀没剥离，恢复时 new File("root://...") 永远 isFile()=false，
   root 标签 100% 恢复失败。

   新增草稿机制：
   - saveEditorDrafts() 把每个 dirty 标签的 content 写成
     getFilesDir()/editor_drafts/draft_N.txt，
     元数据（序号/原路径/是否root/编码/行尾）存 SharedPreferences。
   - 不放 SharedPreferences：2MB 文本会让 prefs 全量重写变慢。
   - 文件名用序号而非路径 hash —— 这个项目已在缩略图缓存上踩过
     32 位 hash 碰撞的坑，不再重犯。
   - restoreEditorDrafts() 按 path 匹配已恢复标签，匹配不上就跳过
     （不新建标签，避免造幽灵条目）；恢复后标 dirty=true 并重置撤销栈
     起点为磁盘版内容，保证「撤销」能回到保存态。
   - 恢复成功时 toast 告知「已恢复 N 个未保存的编辑草稿」。
   - root:// 前缀已剥离。

10. **删除 FTP / HTTP 文件服务**（按用户要求，不是修而是移除）
    排查时发现这套功能**早已没有 UI 入口** —— 侧边栏只有「文件管理」
    一张卡片，show{Ftp,Http}RunningDialog 没有任何调用者，
    stop{Ftp,Http}Server 只被 dialo g内部引用。属于纯死代码，
    但 static ServerSocket 一旦启动就跨实例存活，
    还带着「局域网端口持续暴露」的安全面。
    整块删除 619 行（11855–12473），保留 checkPermission / humanSize
    —— 这两个在块外仍有使用者。
    README 中「局域网 FTP / HTTP 文件服务」一行同步移除。
    AndroidManifest 的 ACCESS_NETWORK_STATE 保留（INTERNET 仍被更新检查使用）。

11. **文件大小闸三处不一致**（`FileManagerModule`）
    旧有三套互相矛盾的判据：
      openTextEditorInternal  >2MB 硬拒
      tryOpenInEditor         >12MB 才提示「可能无法加载」
      openInEditorByName      完全不判
    从「打开方式」进来先看到「可能无法加载」，真进去又被 2MB 拒；
    换入口则可能直接 OOM。
    新增 MAX_EDIT_BYTES = 2MB 单一常量，三处全部改用它。
    tryOpenInEditor 的文案从「可能无法加载」改为明确拒绝 + 实际上限。


### 全量审查第四批：数据安全（10 项）

来源：8 路并行审查 HEAD a7165b1。以下是**会丢用户数据**的路径，
逐条修复，不按「影响面小」筛。

1. **回收站失败降级为物理删除**（最危险）
   `if (!useTrash || !moveToTrash(f)) { deleteTree(f); }`
   用户勾了「移入回收站」，copy 因权限/跨区失败 → 直接物理删除。
   改为：失败报错并保留原文件，绝不降级。

2. **回收站留半截文件且无 meta**
   FileOps.copy 中途抛异常 → dest 半截残留，writeTrashMeta 未执行
   → loadTrash 当正常条目展示，用户「恢复」得到残缺数据。
   改为：copy 异常时先 deleteTree(dest) 再抛出。

3. **deleteTree 跟随符号链接 → 删上层文件**
   `f.isDirectory()` 对 symlink 会跟随。若存在 /sdcard/a -> /sdcard 环：
   深度递归到 StackOverflow，溢出前已真的删掉上层文件。
   改为 isSymlink() 显式判断（API26+ 用 Files.isSymbolicLink，
   低版本用 canonicalPath 兜底），symlink 只删链接本身；
   并把栈递归改成显式栈，深目录不再溢出。

4. **Guard 压缩比防线是死的**
   compressedBytes 累加的是 in.read()（解压流）字节，与 total 同源，
   比值恒 ≈1，永不触发。删除该累加，新增 checkZipRatio4j()：
   用 zip4j FileHeader.getCompressedSize/getUncompressedSize 判，
   单条目 + 整包两道。这是唯一能判压缩比的格式
   （tar 不压缩、7z/rar 不暴露大小）。

5. **RAR 分支完全绕过 Guard**
   没调 countEntry()，extractFile 直写不经 copy(guard)。
   RAR 炸弹零防护。改为补 countEntry + 包一层计数 OutputStream
   过 guard.addBytes，超限即抛。

6. **「放弃修改」后草稿会复活**
   finishEditor 无条件 saveEditorTabs → saveEditorDrafts，
   用户放弃的内容被写回盘，下次又被恢复。
   改为 finishEditor(boolean discardDrafts)：
   放弃时 clearEditorDrafts() + 抹掉 dirty。

7. **0 字节草稿被跳过**
   draft.length()==0 当作"没有草稿"。但这正是「用户把内容全删了」
   的合法状态 → 清空文件的修改永久丢失。
   改为只判 !draft.isFile()。

8. **草稿写入原子性**
   旧：先删光旧草稿再写新，meta 最后 commit。
   删完写完第 1 个时崩溃 → meta 与文件双双错位。
   已改为：写 .new → 全部成功 → rename → 清理本批之外。
   renameTo 失败保留 .new（旧实现无视返回值，随后被清理逻辑删掉，
   等于刚写好的草稿被自己删）；写入失败弹 toast 告知用户。

9. **为「按 Home 后被杀」补 onPause**
   基类 HyVqModule.onPause 是空实现，MainActivity 调了没效果。
   草稿只在 finishEditor 写 → 编辑中按 Home 被杀，一次都不会写。
   新增 FileManagerModule.onPause()，IO 放后台线程。

10. **dirFadePending / staggerPending 双窗格串扰**
    两个标志是 Module 级单 boolean，却被两个窗格共用。
    左栏翻目录置位 → 右栏先 reload 就消费掉，左栏自己没动画。
    下沉到 Pane。

### 未修（记录在案，需后续处理）
- 撤销栈按条数限 200，2MB 文件约 800MB 堆
- replaceCurrent / replaceAll 无 ReDoS 前置检查
- onStop 只 pause 不释放 codec；KEEP_SCREEN_ON 不清
- 触摸目标多处 < 48dp
- ACCESS_NETWORK_STATE / REQUEST_INSTALL_PACKAGES 零使用
- docs/ 的终端 / 终末地残留 6 处


### 更新日志改为面向普通用户

两个问题：
1. **`versions.json` 的 body 全是技术名词**：ArchiveItem、SevenZFile、
   junrar、TaskKeepAliveService、RecyclerView…… 普通用户看不懂，
   而这份内容正是 App 里「软件更新」弹窗直接展示的。
2. **弹窗把 Markdown 当纯文本渲染**：用户看到的是 `## 1.0.0`、`### 新增`、
   `**软件更新**`，一堆井号和星号。

改法：
- `versions.json` 的 body 重写为普通用户视角：只说「能看见什么变化」
  和「以前会怎样、现在会怎样」，不出现任何类名/API 名。
  技术名词已用脚本核验清零。
- 新增 `stripMarkdown()`：弹窗渲染前去掉 `#`、`**`、反引号、
  `---`，列表符号统一为「·」。不做完整 Markdown 解析 ——
  格式是我们自己写的，只用这几种标记。
- 弹窗字号 12sp → 13sp，行距 2dp → 3dp，长文更好读。
- 「versionCode」那行去掉（用户不关心），日期改「发布于」。
- `CHANGELOG.md` 保留技术细节，但头部加说明区分两者的用途。



### 软件更新体验改进（5 项）

1. **新安装默认收起侧边栏分组**
   PREF_NAV_LOCAL_OPEN / PREF_NAV_TOOLS_OPEN 的默认值 true → false。
   新装的用户打开侧边栏看到的是两个分组标题，按需展开，不会一上来
   被一长串目录淹没。已有用户的选择（无论开还是收）照旧被沿用。

2. **检查更新卡片圆角 14dp → 10dp**
   14dp 在约 60dp 高的卡片上接近药丸形，与设置页其他卡片（圆角 10）
   不一致。统一到 10。

3. **新增「启动时检查更新」开关**（默认开启）
   独立卡片 + 真实 Switch，整行可点（Switch 本身命中区偏小）。
   打开时延后 3 秒检查：启动瞬间要恢复状态、首次列表、缩略图，
   此时抢线程和带宽都不合适，用户也还没看到界面。
   失败静默（自动检查是"顺便看一下"，连不上不该弹错误框打断启动）；
   同一版本只自动提示一次（PREF_UPDATE_LAST_ASKED），避免每次启动都弹。

4. **自动检查的弹窗适配 Markdown**
   沿用上一轮新增的 stripMarkdown()，自动弹出的详情窗与手动检查一致，
   不会显示 ## 和 ** 符号。

5. **安装更新需要安装权限**
   原先 doInstall 直接 startActivity，没查「安装未知应用」是否开启。
   未开启时系统给的是措辞含糊的拒绝，用户只看到「无法安装：null」。
   现在：
   - canRequestInstall()：API 26+ 查 canRequestPackageInstalls，
     26 以下一律 true（系统级开关无法查询）
   - 未授权时弹说明窗 + 跳 ACTION_MANAGE_UNKNOWN_APP_SOURCES
     （部分 ROM 无此 action，退到应用详情页，再退到文字指引）
   - 新增 downloadAndInstall()：直接下载 APK 到 cacheDir 并调起安装器，
     带进度百分比。下载前先要权限，避免白等几十 MB 才被拦。
     「去下载」按钮改为「下载并安装」。



### 设置页与侧边栏三项

1. **去掉设置主页多余的返回行**
   设置主页顶部原本有一行「← 返回文件列表」，而共享顶栏左侧的返回键
   做的是同一件事。两个完全同功能的按钮并排，用户会犹豫。
   删掉页内那一行，只留共享顶栏的（所有设置子页都靠它退回）。
   子页内的「← 设置 / ← 返回设置主页」保留 —— 那是有层级意义的
   （回上一层，不是回文件列表）。

2. **文件 ↔ 设置 切换加过渡**
   setContentMode 原本直接 VISIBLE/GONE，进出设置都是硬切。
   现在：进来的一层从「alpha 0 + 水平位移 24dp」到正常，
   180ms DecelerateInterpolator；出去的一层直接隐藏
   （不做双向 alpha —— 两层同时半透明会叠出糊状中间帧）。
   方向感：去设置从右侧滑入，回文件列表从左侧滑入。

3. **侧边栏默认值调整**
   本地分组：默认展开（主入口，一进来就要能点目录）
   工具分组：默认收起（低频功能，收起来减少噪声）
   上一轮把两个都改成收起了，对本地过于激进。



4. **设置子页标题没同步到共享顶栏**
   顶栏结构是「设置 | 子页名」，但子页名那段（settingsTopTitle）
   只被 3 个子页写过：权限管理、个性化、文件管理。
   软件更新、关于、设置主页全没写 → 顶栏显示成「设置 | 」后面空着，
   用户不知道自己在哪一页。

   修法：给 showInSettingsDialog 增加标题参数，由入口统一设置。
   10 处调用点全部补上，并删掉子页内重复的 setText。
   这样新增子页时不可能再忘 —— 标题不再依赖子页自觉。


### 底部功能栏高度与遮挡

两个问题一起修：

1. **功能栏太高**
   bottomNav 用 WRAP_CONTENT，高度随内部内容飘：
   navButton 是 20dp 图标 + 上下各 2dp padding，外面 wrap 有默认 padding，
   bottomNav 自己还有 1dp，实测能顶到 56~64dp。
   改：固定 48dp（BOTTOM_NAV_HEIGHT），navButton 去掉纵向 padding，
   bottomNav 的 padding 改为上下 0。

2. **滚到底时最后几项被浮层盖住，点不到**
   功能栏是叠在 contentWrap 上的浮层，本来就不占布局空间；
   而列表底部 padding = 0，于是最后 60 多 dp 永远在浮层下面。
   改：列表底部 padding = BOTTOM_NAV_RESERVE（62dp = 48 + 8底边距 + 6余量），
   并 setClipToPadding(false) —— 否则留白区不参与滚动，
   内容在留白上方被截断，仍然看着像 bug。



### 重绘功能栏同步键

**问题**：旧实现是两个 40×32 的直线箭头上下堆叠，总高 64dp ——
比功能栏本身（48dp）还高，直接把栏撑破。而两个箭头都调
同一个 syncByFocus()，点哪个效果一样，「左右」没有方向含义。

**重绘**：
- 新建 ic_swap_paths.xml：两条方向相反的直线箭头上下排列 + 箭头尖端，
  是 swap / exchange 的通用符号
- 单个 40×40 圆形按钮（OVAL 描边 + primaryContainer 填充）
- 一个按钮一个动作，不再让人误以为上下箭头有区别
- 同步宽度 44dp → 40dp

**高亮逻辑改了**：单图标没法再用「左右哪个箭头亮」表达焦点，
改为按「两栏路径是否相同」着色 —— 可同步用主色，
路径相同则灰掉 + alpha 0.45，表示点了也没变化。



### 同步键加焦点方向判断

上一版只判断「两栏路径是否不同」，漏了**焦点栏**这个维度。
同步方向由 syncByFocus 决定（焦点在左则左→右），所以按钮能否点得动
必须同时满足三条：

1. 两栏都存在且路径非空
2. 两栏路径不同（相同时 syncPath 直接 toast「两边已经是同一目录」）
3. **焦点栏**（同步的源）路径有效

旧实现只看 1、2。焦点栏路径异常时按钮仍然亮，点了静默无反应。

顺带把 disableReason 写进 contentDescription：
- 可用时：「同步路径：左 → 右」/「右 → 左」
- 不可用时：具体原因（如「两边已经是同一目录」）

读屏能说出为什么不能点，不靠用户猜。



### 软件更新卡片圆角 + 列表密度改左右布局

1. **软件更新页卡片圆角 10dp → 6dp**
   10dp 在这个高度上仍偏圆润。设置页整体是偏方的工具型观感，
   6dp 与输入框、分段控件更接近。版本卡和自动检查卡都改了。

2. **列表文件密度：SeekBar → 左右布局 + 弹层选择**
   旧实现是 SeekBar + 下方三段刻度文字，两个问题：
   - 三段文字按 seekbar 宽度均分，左边缘和标题行对不齐
   - 三档是**离散**选项，用连续滑块表达不当：手指停在两档之间时，
     视觉上没有「已选中」的确定感
   改为「左标题 + 右当前值 ▾」，点整行弹出列表，当前项带 ✓，
   交互与压缩格式选择弹窗一致。
   新增 addPickerRow（通用左标题右值行）+ showDensityPicker（弹层）。
   设置页里从此不存在第二个滑块。



### 顶栏图标按层级区分 + 密度改锚定小弹窗

1. **顶栏左上角图标三态**
   以前不管在设置的哪一层都显示返回键，用户会把「顶栏返回」和
   「页内返回行」当成两个不同的返回来回犹豫（用户原话：下意识点错）。
   改为：
   - 文件页     → 面包键，开侧边栏
   - 设置主页   → 面包键，开侧边栏（与文件页一致，不制造第二个"返回"）
   - 设置子页   → 返回键，回设置主页
   新增 currentSettingsPage（""=主页）记录层级，showInSettingsDialog
   统一写入，refreshSettingsLeftIcon 同步图标与 contentDescription。

2. **列表密度选择改为锚定小弹窗 + 边框**
   上一版是居中大弹窗，选择项离触发它的那行很远，视线要来回跳。
   改为 PopupWindow 锚定在选项行正下方（showAsDropDown + 2dp 间距）：
   - 宽 150dp，圆角 8dp
   - **1px colorOutline 描边**（明确边框）
   - elevation 8dp 阴影
   - 点外部关闭
   当前项主色 + ✓。



### 修设置主页返回死循环 + 密度弹窗对齐

1. **设置主页卡死（返回键点了又回设置页）**
   根因：showSettingsPage() 传的标题是 "设置"（非空），于是
   currentSettingsPage = "设置" → 被判为「仍在子页」→ 顶栏继续显示返回键
   → 点它又调 showSettingsPage() → 永远停在"设置页返回设置页"。

   改：设置主页标题传 ""。这样
   - currentSettingsPage 为空 → 图标回到面包键
   - 顶栏也不会显示成「设置 | 设置」的重复

2. **密度弹窗贴在行最左边**
   showAsDropDown(anchor, 0, 2dp) 的 xoff=0 是**锚点行左边缘**，
   而用户看的是右侧的值标签（"标准 ▾"），所以感觉弹窗跑到了左边。

   改：anchor.post 里按 xoff = 锚点宽 - 弹窗宽 重新定位，
   让弹窗**右边缘贴住行的右边缘**；锚点比弹窗窄时退回左对齐。


#### 待办
（见上方「未修」）

