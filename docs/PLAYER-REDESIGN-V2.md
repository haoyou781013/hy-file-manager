# 视频播放器重做 · 完整方案（v2 · 架构重做）

**日期**：2026-11-19
**取代**：`PLAYER-REDESIGN.md`（v1，增量修补思路，方向错误）

---

## 零、v1 方案错在哪

### 错误 1：把「架构问题」当成「功能清单问题」

v1 列了 7 个阶段、20 项功能，本质是**在现有 1286 行单类上继续加**。结果是：

```
A 修 3 个 bug → B 加手势 → C 加 PiP → D 加 Service → ...
```

每加一项，`MediaPlayerActivity` 就更臃肿。做到 G 阶段「拆分」时，要一次性拆一个 2000+ 行的类——**那时风险比现在大得多**。

**正确顺序：先定架构，再填功能。**

### 错误 2：绕开了 Media3 自带的控制器架构

调研发现 Media3 的 `PlayerView` 已经提供：

| 能力 | Media3 内置 | 我们 |
|---|---|---|
| 控制条自动隐藏 | `show_timeout` 属性（默认 5s） | 自己写 3 个方法（`toggleControls` / `applyControlsVisible` / `scheduleControlsAutoHide` / `cancelControlsAutoHide`） |
| 播放/暂停图标 | `exo_play`/`exo_pause` 标准 id 自动接线 | 自己写 `updatePlayIcon()` |
| 进度条 | `exo_progress` 自动绑定 | 自己写 `syncProgress()` + ticker |
| 按钮可用性 | `exo_progress` 等自动 disable | 自己判断 |
| 布局可换 | `controller_layout_id` 指向任意布局 | 从零写 345 行 XML |
| 缓冲/错误遮罩 | `show_buffering` / 内置 | 自己写 error overlay |

我们的 `use_controller="false"` 把这一切关掉了，然后**手写了一遍更差的实现**。

**但注意**：Media3 的 `PlayerControlView` 也不完全够用——它不支持：
- 选集/弹幕这类业务按钮
- 手势 HUD
- 长按倍速

**所以正确做法不是「回去用 PlayerControlView」，而是「保留自绘控制器，但把能借的架构借过来」**，并且明确定义哪些是 Media3 的职责、哪些是我们的职责。

### 错误 3：状态管理散乱，没有单一数据源

当前 `MediaPlayerActivity` 有 **30+ 个可变字段**：

```
controlsVisible / longPressing / horizontalMode / verticalMode
dragging / prepared / switching / seekTargetMs
downX / downY / speedIdx / epIndex / idx
controlsHideRunnable / hudHideRunnable / lastHudUpdateMs
...
```

每个都要手动维护一致性。调研中所有成熟项目（Next Player、IPTV-Killua、开源音频引擎案例）都用**单一 UiState + 单向数据流**：

```kotlin
// 调研中反复出现的模式
data class PlayerUiState(
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val controlsVisible: Boolean,
    val isLocked: Boolean,
    ...
)
```

**这才是「整体方案」该解决的问题。**

---

## 一、调研到的两条主流架构路线

### 路线 A：Activity 内直连 ExoPlayer（前台播放为主）

```
Activity ──直接持有──> ExoPlayer
    │                      ↑
    └── PlayerView ────────┘
```

**代表**：简单的本地播放器、YouTube 早期架构

- 优点：简单，零跨进程开销，UI 与 player 同生命周期
- 缺点：切后台不可控、无法被系统媒体控制发现
- **适用条件**：只看视频，不需要后台播放/画中画以外的系统集成

### 路线 B：Service 持有 Player + MediaSession（推荐）

```
┌─ Activity ─────────────┐      ┌─ PlaybackService ──────┐
│  UI（PlayerView）      │      │  ExoPlayer             │
│  MediaController ──────┼─────>│  MediaSession          │
└────────────────────────┘      └────────────────────────┘
            ↑                              ↓
            └────── 系统媒体控制/通知栏/蓝牙 ←┘
```

**代表**：Next Player、IPTV-Killua、VLC、所有音乐播放器

- 优点：后台播放、PiP、锁屏控制、车机/蓝牙全部可用；转动屏幕不重建播放管线
- 缺点：需要 `MediaController` 异步连接，`MediaItem` 构建要迁到 Service
- **调研明确结论**（`rakesh-lakshmipathy.github.io`）：

> For foreground-only video, an Activity/Fragment-scoped player may be enough.
> For music/podcast playback expected to continue with screen off or UI gone,
> **a session service gives the correct ownership and system integration.**

### 我们的选择：**路线 B**

理由：
1. 用户已明确要求后台播放 + 画中画
2. 当前架构下，转屏会重建 Activity → 播放器要重新 prepare（`medium.com` 提到部分设备会因此崩溃）
3. 现有 `PlayerCore` 已经是 ExoPlayer 的唯一出口，迁到 Service 改动可控

---

## 二、目标架构

### 2.1 分层

```
┌──────────────────────────────────────────────────────┐
│  MediaPlayerActivity（≤200 行，只做装配与生命周期）     │
│  ├─ PlayerView（video surface + 我们自绘的 controller）│
│  ├─ GestureController   手势 → 语义事件                │
│  └─ PlayerHud          手势反馈                        │
└───────────────────┬──────────────────────────────────┘
                    │ MediaController（异步连接）
┌───────────────────▼──────────────────────────────────┐
│  PlaybackService : MediaSessionService                │
│  ├─ ExoPlayer（PlayerCore.create 复用现有工厂）        │
│  ├─ MediaSession                                      │
│  └─ PlaylistManager   播放列表 / 选集 / 循环模式       │
└───────────────────┬──────────────────────────────────┘
                    │
┌───────────────────▼──────────────────────────────────┐
│  PlayerUiState（单一数据源，由 Player.Listener 驱动）  │
│  ├─ PlayerStateMapper  Player 事件 → UiState          │
│  └─ UiState 被 UI / PiP / 通知栏共同消费               │
└──────────────────────────────────────────────────────┘
```

### 2.2 单一数据源设计

```java
/** 播放器 UI 的全部状态。任何 UI 差异都只能从这个对象派生。 */
public final class PlayerUiState {
    // 播放
    public final boolean showPlayButton;   // Util.shouldShowPlayButton
    public final int playbackState;        // IDLE/BUFFERING/READY/ENDED
    public final boolean isBuffering;
    public final long positionMs;
    public final long durationMs;
    public final long bufferedMs;

    // 媒体
    public final String title;
    public final String subtitle;          // 「番剧 · 线路 · 第3集」
    public final int itemIndex;
    public final int itemCount;

    // 控制条
    public final boolean controlsVisible;
    public final boolean locked;           // 锁定后屏蔽手势
    public final float speed;
    public final int resizeMode;

    // 业务
    public final boolean danmakuAvailable;
    public final boolean episodePickerAvailable;
}
```

**规则**：所有 `onXxx` 回调不再直接改 View，而是 `reduce(state, event) → newState`，再由单一 `render(state)` 刷 UI。

**收益**：
- 转屏 / PiP 切换 / 从 Service 重连后，`render(state)` 一次就能恢复全部 UI
- 消除「图标显示暂停但实际在播」这类状态漂移
- 可对 `reduce()` 写纯函数单测（不需要 Android 环境）

### 2.3 手势的事件化

```java
// 手势不再直接调 player.xxx，而是产生语义事件
public interface GestureSink {
    void onSeekPreview(long targetMs);   // 滑动中预览
    void onSeekCommit(long targetMs);    // 抬手提交
    void onBrightnessDelta(float ratio);
    void onVolumeDelta(float ratio);
    void onTogglePlay();
    void onTapCenter();                  // 显示/隐藏控制条
    void onLongPressBoostStart();
    void onLongPressBoostEnd();
    void onLockToggle();
}
```

`GestureController` 只管「识别手势 → 发事件」，不知道 player 存在。这样：
- 手势逻辑可单测（mock GestureSink）
- 双击左/右 seek、双击中间暂停 这类分区逻辑改起来不动播放器代码

---

## 三、Media3 内置能力：借什么、不借什么

### 借用（减少自写代码）

| 能力 | 做法 |
|---|---|
| **播放/暂停语义** | `Util.shouldShowPlayButton(player)` / `Util.handlePlayPauseButtonAction(player)` |
| **按钮可用性** | `Player.isCommandAvailable(COMMAND_SEEK_TO_NEXT/PREVIOUS)` |
| **缓冲遮罩** | `PlayerView` 的 `show_buffering` 属性（`when_playing`） |
| **字幕渲染** | `PlayerView` 内置 `SubtitleView` |
| **画面比例** | `PlayerView` 的 `resize_mode`（fill/fit/zoom/fixed_width…） |
| **事件聚合** | `Player.Listener.onEvents(player, events)` 单出口 |

### 不借（自己实现）

| 能力 | 原因 |
|---|---|
| **整套 controller 布局** | 需要选集/弹幕业务按钮，`PlayerControlView` 塞不进去（可以 `controller_layout_id` 换布局，但 3 秒自动隐藏 + 手势 HUD + 长按倍速都要另写，不如自绘干净） |
| **手势系统** | Media3 完全没有手势概念 |
| **HUD** | 同上 |
| **PiP 逻辑** | 需要与手势锁定、控制条状态联动 |

**判断依据**：Media3 提供的是**播放器控件语义**，我们真正缺的是**手势 + 业务按钮 + 状态机**。这三块自研，其余尽量用官方。

---

## 四、实施阶段（按依赖顺序，非按功能罗列）

### 阶段 0 · 建立基线（已完成）

`PLAYER-BASELINE.md`：20 条必须保留行为 + 8 项缺失 + 6 个视觉问题。

### 阶段 1 · 状态机骨架（**先做，不碰 UI**）

**目标**：引入 `PlayerUiState` + `reduce` + `render`，但**保持现有 UI 完全不变**。

```
新增：
  player/PlayerUiState.java        （不可变状态对象）
  player/PlayerEvent.java          （事件：PlayPauseTapped / SeekCommitted / …）
  player/PlayerReducer.java        （纯函数：reduce(state, event, player) → state）
  player/PlayerRenderer.java       （唯一允许碰 View 的地方）

改造：
  MediaPlayerActivity: 所有 onXxx 回调 → dispatch(event) → render(newState)
  删除：controlsVisible / longPressing / prepared 等散落字段，收进 state
```

**验收**：
- 现有 20 条行为逐条不变
- `PlayerReducer` 有单测（纯 Java，不需要 Android）
- 转屏后 UI 状态不丢

**风险**：中（改动面广但逻辑等价，可逐步替换）

**为什么先做这个**：后面每个功能（PiP / 后台播放 / 锁定 / 字幕）都要往状态里加字段，先有骨架再加字段，比反过来便宜得多。

### 阶段 2 · 拆手势控制器

**目标**：`GestureController` 从 Activity 抽出，通过 `GestureSink` 发事件。

**同时对齐主流**：
- 双击左 1/3 → 后退 10s
- 双击右 1/3 → 前进 10s
- 双击中间 → 暂停（保留 |
- 边缘 16dp 内不识别横向 seek（让给系统手势）
- 长按加速改走 `LongPressBoostStart/End` 事件（顺带根治之前那个「加速卡住」bug 的架构性成因）

**验收**：手势可单测（`GestureController` + mock `GestureSink`）。

### 阶段 3 · PlaybackService + MediaSession

**目标**：把 Player 从 Activity 搬到 Service。

```
新增：
  player/PlaybackService.java      extends MediaSessionService
  player/PlayerConnection.java     MediaController 异步连接封装

改造：
  PlayerCore.create() 移到 Service（保留现有 HLS/headers/OkHttp 配置）
  MediaPlayerActivity 改用 MediaController
  MediaItem 构建（含 zip:// 虚拟路径解出）移到 Service
```

**验收**：
- 切后台继续播
- 通知栏有控制（`MediaSession` 自动生成）
- 锁屏可控
- 耳机按键可控
- 转屏不重建播放管线

**风险**：**高** —— 这是唯一动架构的阶段，必须单独一轮，不与功能改动混做。

**已知坑**（调研得到）：
- `onTaskRemoved` 要处理：播完则 `stopSelf()`，否则保留
- 观看历史回写要在 Service 做（Activity 可能已销毁）
- 需要 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限（targetSdk 34+ 必需）

### 阶段 4 · 画中画

依赖阶段 3（Service 持有 Player 后 PiP 才能平滑）。

```xml
android:supportsPictureInPicture="true"
android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"
```

**交互设计**（调研结论：华为官方也建议两种入口）：
- 点 PiP 按钮 → 进入
- 点 Home / 返回 → 自动进入（可配置关闭）

**风险**：低

### 阶段 5 · 字幕 + A-B 循环 + 双指缩放

纯增量，互不依赖：

| 功能 | 关键点 |
|---|---|
| 外挂字幕 | `MediaItem.SubtitleConfiguration`，自动扫同目录同名 `.srt/.ass` |
| A-B 循环 | Media3 原生无此能力，用 `player.addListener` 检测位置到达 B 点后 `seekTo(A)` |
| 双指缩放 | 手势层加 `ScaleGestureDetector`，变换 `PlayerView` 的 surface 矩阵 |

### 阶段 6 · 视觉与皮肤

最后做。因为阶段 1-5 都可能调整 UI 结构，先做视觉等于白做。

---

## 五、依赖图（为什么必须这个顺序）

```
   阶段0 基线
      │
      ▼
   阶段1 状态机骨架 ◄──── 所有后续阶段的共同基础
      │
      ├──────────┬──────────┐
      ▼          ▼          ▼
   阶段2 手势   阶段3 Service   （可并行，但 Service 优先）
                    │
                    ▼
                阶段4 PiP
                    │
      ┌─────────────┼─────────────┐
      ▼             ▼             ▼
   阶段5 字幕    阶段5 A-B     阶段5 缩放
                    │
                    ▼
                阶段6 视觉
```

**反向做会怎样**（v1 的错误）：
先做视觉 → 阶段 3 迁 Service 时 Activity 大改，视觉工作白费
先做 PiP → 没有 Service，PiP 进来后播放器状态与 Activity 脱节

---

## 六、验证策略

| 层级 | 手段 | 覆盖 |
|---|---|---|
| 纯逻辑单测 | JUnit（JVM） | `PlayerReducer` / `GestureController` / `PlayerUiState 派生` |
| 构建 | `gradle assembleRelease` | 编译 |
| 静态 | `preflight.sh` | 版本一致性 / 签名 / 无调试残留 |
| 运行时 | `am start` + `logcat HYVQ_PLAYER` | 启动 / 播放 / 错误 |
| 视觉 | 截图 | UI 状态 |
| **交互** | ⚠️ 需 `input swipe`（当前禁用）或用户实测 | 手势 |

**新增的测试底座**：阶段 1 引入的 `PlayerReducer` 是纯函数，可以直接补单测——这是当前项目最缺的（只有 `FileNamesTest` 24 个用例）。

---

## 七、与 v1 方案的对照

| 维度 | v1 | v2 |
|---|---|---|
| 出发点 | 修 bug + 加功能 | 先定架构 |
| 顺序 | A 修 bug → B 手势 → C PiP → D Service → E 字幕 → F 缩放 → G 拆分 | 0 基线 → **1 状态机** → 2 手势 / 3 Service → 4 PiP → 5 功能 → 6 视觉 |
| 状态管理 | 不提，继续散落 30+ 字段 | **单一 UiState + reduce/render** |
| Media3 利用 | 只用 `Util` 两个方法 | 借控件语义 + 缓冲 + 字幕渲染 + 事件聚合，**不借 controller 布局** |
| 代码拆分 | 放最后（G） | **放最前（1）**——因为它是其他改动的地基 |
| 风险集中点 | 分散在 7 个阶段 | 集中在阶段 3（Service），单独一轮 |
| 可测性 | 只说「补单测」 | `Reducer` / `GestureController` 天然可测 |

---

## 八、参考来源

| 来源 | 取用的结论 |
|---|---|
| `developer.android.com/reference/androidx/media3/ui/PlayerView` | `use_controller` / `show_buffering` / `controller_layout_id` / `show_timeout` 属性清单 |
| `developer.android.com/reference/kotlin/androidx/media3/ui/PlayerControlView` | `show_timeout` / `show_rewind_button` / `exo_*` 标准 id 清单 |
| `developer.android.com/media/media3/ui/customization` | `Util.shouldShowPlayButton` / `handlePlayPauseButtonAction` / `onEvents` / `isCommandAvailable` |
| `developer.android.com/media/media3/session/background-playback` | `MediaSessionService` 规范、`onTaskRemoved`、权限要求 |
| `google.github.io/horologist` PlayerScreen | UI 状态外置（`PlayerUiState`）+ 槽位化组合的官方范例 |
| `rakesh-lakshmipathy.github.io/.../media3-playback` | 「前台播放 vs Service 持有」的判定标准；「UI 不该自己维护 isPlaying」 |
| `shrey524.medium.com/beyond-exoplayer` | `PlayerManager` + `PlayerUiState` + `StateFlow` 的生产级音频引擎案例 |
| `github.com/MyNameIsKillua/IPTV-by-Killua/docs/PLAYER.md` | PlaybackService / PlayerConnection / PlayerPresentationState 的分层命名与职责 |
| `github.com/anilbeesetti/nextplayer` | 功能清单（音轨/字幕轨选择、字幕延迟、缩放、PiP、后台播放、网络存储）与模块化结构（core/feature） |
| `github.com/google/ExoPlayer/issues/7190` | `controller_layout_id` 的正确用法与常见错误 |
| `medium.com/@janand1991/...media3-part-ii` | 自定义 controller 布局的实操（隐藏底栏、时间放进度条两端） |
| `eleken.co/blog-posts/video-player-ui` | 「功能对等 > 视觉刷新」；auto-hide 已非可选项 |
| `fluxplays.live/blog/streaming-ui-ux-best-practices` | 3 秒规则、移动端左右 1/3 分区、16px 边缘避让 |
| 百度 MEUX（优设网转载） | 「长按+滑选」组合手势 AB 实验结果 |

---

## 九、下一步

**建议从阶段 1 开始**：引入 `PlayerUiState` + `reduce` / `render`，UI 完全不变。

理由：
- 它不改任何用户可见行为 → 零回归风险
- 它是阶段 2~5 的共同地基
- 它能把「30+ 散落字段」这个问题一次性解决
- 引入后可立刻补 `PlayerReducer` 单测（当前项目只有 24 个用例，全是文件名工具）

**不做阶段 1 直接做功能，就是重走 v1 的老路。**
