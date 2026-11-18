# 视频播放器重构 · 行为基线

> 重构前必须先把现有行为记下来，否则"顺手改"会丢掉用户已在用的特性。
> 每条都对应代码位置，重构后逐条核对。

**文件**：`MediaPlayerActivity.java`（1151 行）+ `player/PlayerCore.java`（212 行）
\+ `player/DanmakuView.java`（239 行）+ `res/layout/activity_media_player.xml`（345 行）

---

## A. 必须保留的行为（丢失即为回归）

| # | 行为 | 位置 | 说明 |
|---|---|---|---|
| A1 | 单击显示/隐藏控制条 | `toggleControls:577` | 目前**没有自动隐藏**，纯手动 |
| A2 | 双击暂停/继续 | `onDoubleTap:604` | **无任何视觉反馈**（要改） |
| A3 | 长按 2× 加速，松手恢复 | `onLongPress:610` + `ACTION_UP:655` | 恢复的是 `SPEEDS[speedIdx]`，不是 1.0× |
| A4 | 水平滑动预览 seek，抬手才生效 | `ACTION_MOVE:634` | 全屏宽 = 2 分钟；`tvPos` 显示 `"00:30 ⟶"` |
| A5 | 垂直滑动：**左半亮度 / 右半音量** | `ACTION_MOVE:643` | 阈值 `dp(12)` 才判定方向 |
| A6 | 拖动进度条时不跟播放进度打架 | `syncProgress:583` 的 `dragging` 判断 | |
| A7 | 音频文件显示占位图标 + 文件名 | `audio_placeholder` | 不是黑屏 |
| A8 | 错误内嵌浮层 + 重试按钮 | `player_error_overlay` | 用户明确要求「不要弹窗」 |
| A9 | 错误码翻译成中文 | `friendlyError:810` | 不直接抛 ExoPlayer 原始码 |
| A10 | 倍速 8 档 | `SPEEDS` / `SPEED_LABELS` | 循环切换 |
| A11 | 画面比例循环（适应/填充/拉伸…） | `cycleRatio:972` | |
| A12 | 截图到相册 | `captureFrame:1042` | |
| A13 | 选集（动漫模式） | `showEpisodePicker:882` | 仅 `epTitles != null` 时显示入口 |
| A14 | 弹幕（B 站 season） | `initDanmaku:715` | `danmakuSeasonId <= 0` 时整个功能关闭 |
| A15 | 观看历史续播 + 回写 | `historyKey` / `saveHistoryProgress:783` | `historyHandler` 每 5 秒 |
| A16 | 播放列表选文件 | `showPlaylistPicker:488` | 左下角菜单图标 |
| A17 | 上一个/下一个 | `step:528` | 走 `playAt` |
| A18 | 标题栏显示「番剧名 · 线路 · 第N集」 | `animeMode` 分支 | 不是显示 `index.m3u8` |
| A19 | 屏幕常亮 | `FLAG_KEEP_SCREEN_ON` | |
| A20 | 播放网络源时带请求头 | `EXTRA_HEADERS` → `PlayerCore` | HLS 分片也要带 |

---

## B. 当前缺失的能力

| # | 缺什么 | 影响 |
|---|---|---|
| B1 | **控制条自动隐藏** | 用户看着控制条挡画面，得手动点掉 |
| B2 | **双击/长按无视觉反馈** | 不知道操作生效了没有 |
| B3 | 画中画（PiP） | 切出去就暂停 |
| B4 | 后台播放 + 通知栏控制 | 只能前台播 |
| B5 | 字幕选择 | 有多字幕轨的视频只能看默认 |
| B6 | A-B 循环 | 学外语/看教程需要 |
| B7 | 手势区域未分区 | 横向滑动全屏生效，容易误触 |
| B8 | 亮度/音量无屏幕指示条 | 只能靠感觉 |

---

## C. 当前视觉问题（用户反馈"丑"）

| # | 问题 | 位置 |
|---|---|---|
| C1 | 中央播放按钮是**纯白实心圆**（`#FFFFFFFF`），在黑底上过于刺眼 | `bg_player_btn_primary.xml` |
| C2 | 进度条只有 **3dp**，缓冲进度层缺失（只有 background + progress） | `progress_player.xml` |
| C3 | chip 按钮（选集/弹幕/倍速/比例）**高矮不一**（靠 `paddingVertical=7dp` 撑，实际约 30dp） | 布局 |
| C4 | 顶部栏「计数器 + 标题」都靠左，右侧大片空白 | `player_top` |
| C5 | 左下角「选择文件」是 38dp 图标，与 44dp 的 prev/next 不齐 | `btn_player_list` |
| C6 | 无缓冲/spinner 状态（seek 到未缓冲区只显示旧帧） | — |

---

## D. 重构约束

1. **不许减少功能**：A 表 20 条逐条保留
2. **不许改交互语义**：A3 长按恢复的是「当前选中倍速」不是 1.0×；A4 横向滑动是**预览式**（抬手才 seek）
3. 视觉可以大改，但**保持纯黑底 + 沉浸式**
4. 分阶段改，每阶段 `assembleRelease` + 装机验证
5. 代码拆分时 **`findViewById` 的 id 不许改**（除非同步改全部引用点）
