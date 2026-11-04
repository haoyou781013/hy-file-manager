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

#### 待办
- 未保存的编辑内容零持久化
- 文件大小闸三处不一致
- FTP/HTTP 服务器不在生命周期关闭

