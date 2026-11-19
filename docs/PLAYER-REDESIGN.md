# 视频播放器重做 · 完整方案

**调研日期**：2026-11-19
**对象**：`MediaPlayerActivity.java`（1286 行，重构后）+ `PlayerCore.java`（212 行）+ `activity_media_player.xml`（约 400 行）
**当前状态**：第一/二阶段已完成（视觉 + HUD + 自动隐藏），见 `PLAYER-BASELINE.md`

---

## 一、调研结论（来源见文末）

### 1.1 业界共识：三条不可违反的原则

**① 控制条 3 秒规则（`fluxplays.live` / YouTube / B 站一致）**

> Controls should disappear automatically after **3 seconds** of inactivity.

这条我已经实现。但调研补充了两个关键细节：

- **左侧 1/3 后退、右侧 1/3 前进**（YouTube 推广的双击模式）—— 我们目前是「双击任意位置暂停」，与主流不一致
- **距屏幕边缘 16px 内不放控件**，因为手势导航会与边缘滑动冲突 —— 我们底部按钮距离边缘只有 12dp

**② 「功能对等 > 视觉刷新」（`eleken.co` 分析 YouTube/Netflix 改版反馈）**

> Users tolerate visual changes but react strongly when familiar functionality disappears.

这解释了为什么我第一轮重构你觉得只是"不那么丑了"但没本质提升——**功能没变**。

**③ auto-hide 已经不是可选项**

> When this interaction breaks, people immediately assume something is wrong.

### 1.2 主流播放器的具体做法（横向对比）

| 能力 | VLC | MX Player | mpv-android | Next Player | 我们当前 | 评定 |
|---|---|---|---|---|---|---|
| **长按倍速** | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ 已有 |
| **双击左/右 seeks** | ❌ | ✅ | ✅ | ✅ | ❌ 双击暂停 | ⚠️ 需对齐 |
| **横向滑动 seek** | ✅ 全屏 | ✅ 全屏 | ✅ | ✅ | ⚠️ 全屏无分区 | ⚠️ 需分区 |
| **纵向：左亮度/右音量** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ 已有 |
| **双指缩放画面** | ✅ | ✅ | ✅ | ✅ | ❌ | ⚠️ 缺 |
| **双指上下滑动调速** | ❌ | ✅ | ❌ | ❌ | ❌ | 可选 |
| **A-B 循环** | ✅ | ✅ | ✅ | ❌ | ❌ | ⚠️ 缺 |
| **外挂字幕加载** | ✅ | ✅ | ✅ | ✅ | ❌ | ⚠️ 缺 |
| **字幕位置/大小调节** | ✅ | ✅ | ✅ | — | ❌ | 可选 |
| **画中画** | ✅ | ✅ | ✅ | ✅ | ❌ | ⚠️ 缺 |
| **后台播放** | ✅ | ✅ | ✅ | ✅ | ❌ | ⚠️ 缺 |
| **手势灵敏度可调** | ❌ | ✅ | ✅ | ❌ | ❌ | 可选 |
| **播放器按钮布局自定义** | ❌ | ✅ | ✅ | ❌ | ❌ | 过度设计 |

**我们缺的、且主流都有的 5 项**：双击分区 seek、双指缩放、A-B 循环、外挂字幕、画中画 + 后台播放。

### 1.3 Media3 官方最佳实践（`developer.android.com/media/media3/ui/customization`）

调研发现我们**违反了 3 条官方建议**：

| 官方建议 | 我们的做法 | 影响 |
|---|---|---|
| **用 `Util.shouldShowPlayButton(player)` 决定播放/暂停图标** | 自己 `player.isPlaying()` 推断 | 播放结束/失败后按钮状态可能错（应显示「重播」而非「播放」） |
| **用 `Util.handlePlayPauseButtonAction(player)` 处理点击** | 自己 `togglePlay()` | 播完后点击不会重新开始 |
| **只监听 `onEvents()` 而非分散的 `onIsPlayingChanged` 等** | 用了 `onIsPlayingChanged` + `onPlaybackStateChanged` + `onPlayerError` 三个回调 | 一次状态变化触发多轮 UI 刷新 |
| **用 `player.isCommandAvailable(COMMAND_SEEK_TO_NEXT)` 判断按钮可用性** | 无条件启用 | 单文件播放时"下一个"按钮可点但无反应 |

**第 1、2 条是真 bug**：视频播完后再点播放键，ExoPlayer 处于 `STATE_ENDED`，`togglePlay()` 调 `play()` 无效——需要 `seekTo(0)` 再 `play()`。而官方 `Util.handlePlayPauseButtonAction` 已经处理了。

### 1.4 手势设计的前沿做法（百度 MEUX 论文）

百度 MEUX 团队做了 AB 实验（小流量半个月），结论：

> 「长按+滑选」的组合手势，**用户 1~2 次尝试即可掌握**，且各播控功能使用率**大幅提升**。

他们的三条落地细节：

1. **扩展触发热区**：除顶/底 bar 与自带长按事件的按钮外，其余大面积区域都支持长按
2. **跟手触发**：浮层位置跟随手指纵坐标，减少手指位移
3. **实时提示与响应反馈**：滑入选择 / 松手触发分别给引导提示 + 震动反馈

**我们目前的长按只是「临时加速」**，百度把它升级成「长按 → 浮层快捷菜单 + 滑选」。这是可选的进阶方向。

---

## 二、现状问题清单

### P0 · 真 bug（Media3 最佳实践违背）

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| **P0-1** | 播完后再点播放键**无反应** | `togglePlay:587` | 视频播完后无法重播，必须退出重进 |
| **P0-2** | 播放/暂停图标用 `isPlaying()` 判断 | `updatePlayIcon:602` | 缓冲中、已播完时图标状态不准 |
| ~~P0-3~~ | ~~单文件播放时「下一个」按钮可点但无反应~~ | — | **⚠ 已推翻：这个判断是错的**。`step()` 是循环语义（`n >= playlist.length → 0`），单文件点「下一个」会重播自己，按钮本就该一直可点。用 `COMMAND_SEEK_TO_NEXT` 判断反而会误禁用。已移除该改动 |

### P1 · 与主流不一致

| # | 问题 | 主流做法 |
|---|---|---|
| P1-1 | 双击 = 暂停/继续 | 双击左 1/3 后退、右 1/3 前进、**双击中间才暂停** |
| P1-2 | 横向滑动全屏生效 | 全屏滑容易与纵向误判；主流会明确分区或提高阈值 |
| P1-3 | 底部按钮距屏幕边缘 12dp | 手势导航区宽 16dp，会冲突 |
| P1-4 | 单次点击切换控制条 | 主流点击任意处「显示」控制条，再点「隐藏」（当前是切换，符合主流 ✅） |

### P2 · 功能缺失（主流都有）

| # | 功能 | 价值 |
|---|---|---|
| P2-1 | **画中画** | 切出去继续看，日常高频 |
| P2-2 | **后台播放 + 通知栏控制** | 听视频/播客必需 |
| P2-3 | **外挂字幕**（srt/ass） | 看外语片/教程必需 |
| P2-4 | **A-B 循环** | 学外语、看教程重复片段 |
| P2-5 | 双指缩放画面 | 看细节 |
| P2-6 | 字幕位置/大小/延迟调节 | 高级需求 |

### P3 · 代码结构

| 问题 | 现状 |
|---|---|
| 单文件 1286 行 | 播放/UI/手势/弹幕/选集/截图/历史 全在一个类 |
| 状态字段 30+ 个 | `controlsVisible`/`longPressing`/`horizontalMode`/`dragging`/`prepared`/`switching`… 无单一数据源 |
| 无 MediaSession | 无法与系统媒体控制/蓝牙耳机/车机交互 |

---

## 三、重做方案

### 3.1 目标架构

```
MediaPlayerActivity（装配 + 生命周期，目标 ≤250 行）
├── PlayerController         播放引擎封装（含 MediaSession 桥接）
├── PlayerControlsView       控制条（XML inflate + 状态绑定）
├── GestureController        手势（双击分区 / 滑动 / 长按 / 双指）
├── PlayerHud                手势反馈浮层（已实现，抽出来）
├── DanmakuController        弹幕（现有 DanmakuView 的上层封装）
├── PlaylistController       播放列表 / 选集
└── PlaybackService          MediaSessionService（后台播放 + PiP 宿主）
```

**为什么不激进拆分**：当前项目无 DI 框架，纯 Java + 手写 View。按「职责边界清晰但不过度抽象」拆分，每块保持可直接 `new`（不引入注入）。

### 3.2 分阶段计划

| 阶段 | 内容 | 风险 | 可独立交付 |
|---|---|---|---|
| **A** | 修 P0 三个 bug（Media3 最佳实践对齐） | 极低 | ✅ |
| **B** | 手势对齐主流（双击分区 + 边缘避让） | 中 | ✅ |
| **C** | 画中画 | 低 | ✅ |
| **D** | 后台播放 + MediaSession（`PlaybackService`） | **高**（改架构） | ✅ |
| **E** | 外挂字幕 + A-B 循环 | 中 | ✅ |
| **F** | 双指缩放画面 | 低 | ✅ |
| **G** | 代码拆分（抽 Controller） | **最高** | ✅ |

**顺序理由**：A 是 bug 必须最先；B/C 低风险高收益；D 动架构必须单独一轮；E/F 是纯增量；G 放最后——功能都稳定了再动骨架。

---

## 四、阶段 A 详细设计（先做这个）

### A-1 修「播完无法重播」

```java
// 现状
private void togglePlay() {
    if (player == null) return;
    if (player.isPlaying()) player.pause();
    else player.play();          // ← STATE_ENDED 时无效
}

// 改为官方推荐
private void togglePlay() {
    if (player == null) return;
    Util.handlePlayPauseButtonAction(player);   // 内部处理 ENDED → seekTo(0) + play
}

private void updatePlayIcon(boolean playing) {
    boolean showPlay = Util.shouldShowPlayButton(player);  // 处理 ENDED/BUFFERING/ERROR
    btnPlay.setImageResource(showPlay ? R.drawable.ic_play : R.drawable.ic_pause);
}
```

**Media3 已提供的 `Util` 方法**：
- `Util.shouldShowPlayButton(player)` —— 综合 `playWhenReady`、`playbackState`、`playbackSuppressionReason` 判断
- `Util.handlePlayPauseButtonAction(player)` —— 正确处理 `ENDED`（先 `seekToDefaultPosition()` 再 `play()`）

### A-2 合并事件监听

```java
// 现状：3 个独立回调，一次状态变化触发多轮 UI 刷新
@Override public void onIsPlayingChanged(boolean v) { updatePlayIcon(v); }
@Override public void onPlaybackStateChanged(int s) { ... }
@Override public void onPlayerError(PlaybackException e) { ... }

// 改为单一 onEvents（官方建议）
@Override public void onEvents(Player p, Player.Events events) {
    if (events.containsAny(EVENT_PLAY_WHEN_READY_CHANGED,
                           EVENT_PLAYBACK_STATE_CHANGED,
                           EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)) {
        updatePlayPauseButton();
        if (controlsVisible) scheduleControlsAutoHide();
    }
    if (events.contains(EVENT_PLAYBACK_STATE_CHANGED)) { handleStateChange(p.getPlaybackState()); }
}
```

### A-3 按钮可用性 ~~（已推翻）~~

**原计划**用 `player.isCommandAvailable(COMMAND_SEEK_TO_NEXT)` 禁用单文件时的「下一个」按钮。

**实施时发现这个判断是错的**：`step()` 是循环语义——

```java
private void step(int delta) {
    int n = idx + delta;
    if (n < 0) n = playlist.length - 1;
    if (n >= playlist.length) n = 0;    // ← 越界回绕
    playAt(n);
}
```

单文件点「下一个」会重播自己，这是**合理且有反馈**的行为。用 `COMMAND_SEEK_TO_NEXT`
判断反而会在某些情况下误禁用（ExoPlayer 对单文件也常报告该命令可用，
语义是「跳到末尾」而非「切下一个媒体项」）。

**结论**：不改。已实施后移除。

> 教训：调研时把「主流播放器单文件时禁用上下曲」当成通用规律，
> 但没核对本项目 `step()` 的实际语义。**先看代码再采纳外部经验**。

---

## 五、阶段 B 详细设计（手势对齐）

### B-1 双击分区（YouTube 模式）

调研结论：**左侧 1/3 后退、右侧 1/3 前进**是 YouTube 推广的标准，B 站也是这个逻辑。

```java
@Override public boolean onDoubleTap(MotionEvent e) {
    float x = e.getX();
    float w = rootView.getWidth();
    if (x < w / 3f) {
        seekBy(-10_000);        // 后退 10 秒
        showGestureHud(R.drawable.ic_replay_10, "后退 10 秒", false);
    } else if (x > w * 2 / 3f) {
        seekBy(+10_000);        // 前进 10 秒
        showGestureHud(R.drawable.ic_forward_10, "前进 10 秒", false);
    } else {
        togglePlay();           // 中间才暂停
        showGestureHud(...);
    }
    return true;
}
```

**为什么保留双击暂停在中间**：用户已在用「双击暂停」，直接改掉会破坏肌肉记忆。分区后仍有中间区域可暂停，是渐进式改进而非推翻。

### B-2 横向滑动加分区约束

现状：`ratio = dx / v.getWidth()`，全屏宽 = 2 分钟。问题是从屏幕边缘起始的滑动会被系统手势拦截。

改进：
- 滑动起点距屏幕边缘 < 16dp 时**不识别**为 seek（让给系统手势）
- 滑动时显示方向箭头 + 目标时间（已实现）

### B-3 底部控件避让手势区

布局里底部按钮 `paddingBottom` 从 12dp 提到 20dp，让出系统手势导航区。

---

## 六、后续阶段要点（预留设计）

### C · 画中画

```xml
<!-- Manifest -->
<activity android:name=".MediaPlayerActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation" />
```

```java
// 进入 PiP（Android 8+）
if (Build.VERSION.SDK_INT >= 26) {
    enterPictureInPictureMode(new PictureInPictureParams.Builder()
        .setAspectRatio(new Rational(16, 9))
        .build());
}
// PiP 中隐藏控制条
@Override public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
    controlsVisible = !inPip;
    applyControlsVisible();
}
```

**注意**：`onUserLeaveHint()` 里自动进 PiP 是主流做法（点击 Home 键就缩小），但要给用户开关，不是所有人都喜欢。

### D · 后台播放（架构改动）

依赖：`androidx.media3:media3-session:1.11.1`

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<service android:name=".PlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

```java
public class PlaybackService extends MediaSessionService {
    private MediaSession session;
    private ExoPlayer player;

    @Override public void onCreate() {
        player = PlayerCore.create(this);      // 复用现有出口
        session = new MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntentToPlayer())
            .build();
    }
    @Override public MediaSession onGetSession(ControllerInfo info) { return session; }
    @Override public void onDestroy() { session.release(); player.release(); }
}
```

**Activity 侧改为 `MediaController`**：

```java
// 不再自己创建 ExoPlayer，而是连到 Service
MediaController.Builder(this, SessionToken(this, new ComponentName(this, PlaybackService.class)))
    .buildAsync()
    .addListener(controller -> { playerView.setPlayer(controller); }, MoreExecutors.directExecutor());
```

**风险点**：
- `PlayerCore` 的 HLS/请求头配置要迁到 Service
- 观看历史的回写要在 Service 里做（Activity 可能已销毁）
- 弹幕同步依赖 `player.getCurrentPosition()`，MediaController 跨进程调用有延迟（本地 Service 其实同进程，问题不大）

### E · 外挂字幕

```java
MediaItem.SubtitleConfiguration sub = new MediaItem.SubtitleConfiguration.Builder(subUri)
    .setMimeType(MimeTypes.APPLICATION_SUBRIP)   // 或 TEXT_SSA
    .setLanguage("zh")
    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    .build();
```

自动扫描同目录同名字幕：`video.mp4` → `video.srt` / `video.zh.srt` / `video.ass`。

### F · 双指缩放

```java
// ScaleGestureDetector + 已有的 zoom 矩阵
playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
// 或用 TextureView 自己画变换矩阵
```

---

## 七、验证方法（每一阶段）

| 层级 | 方法 |
|---|---|
| 编译 | `gradle assembleRelease` |
| 单测 | 现有 `FileNamesTest`（24）+ 新增播放器纯逻辑测试 |
| 静态 | `preflight.sh`（版本一致 / 签名 / 无调试残留） |
| 运行时 | `am start` 拉起播放器 + `logcat` 检查 `HYVQ_PLAYER` |
| 视觉 | 截图核验（按约定，验证后 `am force-stop` 关闭） |
| **手势** | ⚠️ 需 `input swipe` 模拟（当前禁用），或由用户实测 |

---

## 八、参考来源

| 来源 | 用于 |
|---|---|
| `developer.android.com/media/media3/ui/customization` | Media3 官方最佳实践（`Util.shouldShowPlayButton` / `onEvents` / `isCommandAvailable`） |
| `developer.android.com/media/media3/session/background-playback` | `MediaSessionService` 规范 |
| `developer.android.com/media/media3/ui/playerview` | `PlayerView` 属性与 `keepScreenOn` |
| `fluxplays.live/blog/streaming-ui-ux-best-practices` | 3 秒规则、移动端左/右 1/3 分区、16px 边缘避让 |
| `eleken.co/blog-posts/video-player-ui` | 「功能对等 > 视觉刷新」、auto-hide 已非可选项 |
| `百度 MEUX / 优设网`（2022-12） | 组合手势 AB 实验：长按+滑选，用户 1~2 次掌握 |
| `MX Player / VLC / mpv-android 官方文档与用户反馈` | 手势能力横向对比表 |
| `mpvRx 中文资源`（GitHub） | 播放器设置项清单（手势灵敏度、A-B、字幕调节等） |

---

## 九、我的建议

**先做 A 阶段**——它修的是真 bug（播完无法重播），代价 20 行代码，收益立刻可见。

**然后 B 阶段**——双击分区是用户最可能察觉的改进，改动小。

**D（后台播放）单独一轮**——它要改架构，混在其他改动里出问题难定位。

**G（代码拆分）最后**——功能都稳定后再动骨架，否则功能改动 + 结构改动同时进行，出问题无法判断是哪边引起的。
