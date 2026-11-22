# 当前状态：更新内容与待办

**日期**：2026-11-22
**版本**：**1.0.7（vc13）已发布**（2026-11-22）
**工作区**：干净，无未提交改动

---

## 一、待发布的更新内容

自 1.0.6（`1ee6867`）以来 **17 个提交**，代码变更 **3637 行新增 / 664 行删除**，
涉及 36 个文件（9 个源码 / 5 个测试 / 1 个布局 / 4 个文档 / 其余资源与配置）。

### 1.1 视频播放器重构（本轮主线）

| 改动 | 提交 | 用户可感知 |
|---|---|---|
| Media3 最佳实践对齐（播完无法重播） | `b75c84e` | ✅ |
| 阶段 1：状态机骨架（`PlayerUiState` / `PlayerEvent` / `PlayerReducer`） | `b080b27` | — |
| 阶段 2：抽出 `GestureController` + `GestureSink` | `c80401a` | — |
| 阶段 2 接入：双击分区、边缘保护区 | `814e446` | ✅ |
| 底部控制条三段式布局 + 提示对比度 + 弹幕描边 | `fda8e32` | ✅ |
| 进度条重做 + 播放器设置页 | `e38a03c` | ✅ |
| 设置项点击不再整页重建 + 说明文字进卡片 | `a72ddeb` | ✅ |
| 设置项展开动画 + 文件列表圆角 | `0d2e471` | ✅ |
| 开关快照缺陷（点两次同一结果） | `c335e22` | ✅ |

**新增能力**：
- 双击分区（左 1/3 后退 / 中 1/3 暂停 / 右 1/3 前进 10 秒）
- 边缘 16dp 让位给系统返回手势
- 进度条：6dp 轨道 / 40dp 触摸区 / 拖动时间气泡 / 按下放大圆点
- 播放器设置页：6 项可配（自动隐藏开关与延迟、单击行为、长按倍速、
  双击跳转、横滑灵敏度、恢复默认）

### 1.2 稳定性修复

| 问题 | 影响 | 提交 |
|---|---|---|
| 9 处高版本 API 无守卫 | **Android 6～11 崩溃**，权限管理页必崩 | `db0a23f` |
| `CompletableFuture` 实际未脱糖 | 低版本缩略图**整块失效** | `db0a23f` |
| `DefaultLocale` 14 处 | 土耳其语等环境下**后缀识别全错** | `db0a23f` |
| GIF 解码在 UI 线程 | 看多帧动图时界面无响应 | `db0a23f` |
| `DrawAllocation` 3 处 | 容量条滚动掉帧 | `69802f9` |
| `InlinedApi` 4 处常量 | 低版本 APK 信息弹窗静默空掉 | `69802f9` |
| 平台 `Switch` 不跟随主题 | ColorOS/MIUI 上颜色不一致 | `69802f9` |

### 1.3 质量基建

| 项 | 变化 |
|---|---|
| lint 问题 | 236 → **57** |
| lint Error | 21 → **0**（`abortOnError` 已改为 `true`） |
| 单测 | 24 → **118** |
| 主类行数 | 17776 → **17547** |
| 新增纯逻辑类 | `SettingsFormat` / `FileTypes` / `ArchivePaths` |

### 1.4 文档

- `docs/PLAYER-REDESIGN-V2.md`：播放器重构方案 + 进度快照
- `docs/OPTIMIZATION-AUDIT.md`：全项目审查报告 + 两轮修复记录
- `docs/RELEASE-1.0.7-DRAFT.md`：**更新日志草稿（用户版已规范化）**
- `README.md` / `DEVLOG.md`：功能与记录同步

---

## 二、待办清单

### 2.1 高价值 · 用户可感知

| 项 | 说明 | 预估 |
|---|---|---|
| **播放器阶段 3** | `PlaybackService` + `MediaSession`：后台播放、锁屏控制、车机 | 大（改架构） |
| **播放器阶段 4** | 画中画（依赖阶段 3） | 中 |
| 播放器阶段 5 | A-B 循环、双指缩放 | 小 |
| 文本编辑器重构 | 预览/编辑双模式、语法高亮、快捷输入栏、编码切换、查找替换增强、Markdown/HTML 预览 | 大 |
| 图片隐藏式压缩包 | 合体制作 + 检测提取 | 中 |

### 2.2 技术债

| 项 | 说明 | 预估 |
|---|---|---|
| DASH 支持 | 补 `media3-exoplayer-dash` 依赖，或移除 `PlayerCore` 的 mpd 分支（当前静默失效） | 小 |
| God Class 继续瘦身 | 已从 17776 降到 17547（1.3%）；再降必须改架构（ViewModel / 拆 Panel） | 大 |
| `ClickableViewAccessibility` 12 处 | 已确认误报（`setOnTouchListener` 均 `return false`），可不修 | — |
| `ApplySharedPref` 9 处 | 多数有意（关键设置要立刻落盘），逐个确认即可 | 小 |

### 2.3 已完成但曾误列为待办（需更正记录）

**外挂字幕已实现**（`MediaPlayerActivity.findSubtitles()`）：
扫描同目录同名前缀的 `.srt` / `.ass` / `.ssa` / `.vtt`，
经 `MediaItem.SubtitleConfiguration` 挂给 ExoPlayer，加载后 toast 提示条数。

但 DEVLOG 的待办里仍写着「阶段 5：外挂字幕」，属记录滞后。应从待办移除。

---

## 三、动手前的建议

**优先做播放器阶段 3（后台播放）**，理由：

1. 它是**唯一剩下的用户能明确感知的功能缺口**
   —— 看视频切出去就停，这在实际使用中很频繁
2. 它是阶段 4（画中画）的前置，做完能连带解锁
3. 风险虽高但**边界清晰**：改动集中在 `PlayerCore` → `PlaybackService` 的迁移，
   `PlayerSettings` / `PlayerUiState` / `GestureController` 都不受影响

**不建议优先做的**：

- God Class 继续拆 —— 纯结构，无功能收益，风险与收益不成比例
- 文本编辑器重构 —— 范围太大，应单独立项

---

## 四、发布状态：✅ 已完成（2026-11-22）

| 项 | 状态 |
|---|---|
| 版本号 | ✅ `versionCode 13` / `versionName 1.0.7` |
| tag | ✅ `v1.0.7`（annotated）已推 Gitee + GitHub |
| `versions.json` | ✅ count 11 → 12，updatedAt 2026-11-22 |
| `CHANGELOG.md` | ✅ 已插入 1.0.7 章节（开发者版） |
| `README.md` | ✅ 版本行同步，补「外挂字幕」说明 |
| APK（Gitee） | ✅ https://gitee.com/haoyou781/hy-file-manager/releases/download/v1.0.7/HYFileManager-1.0.7.apk |
| APK（GitHub） | ✅ https://github.com/haoyou781013/hy-file-manager/releases/download/v1.0.7/HYFileManager-1.0.7.apk |

**Release**：
- Gitee id=1354426
- GitHub id=454500391

**校验记录**：

| 项 | 结果 |
|---|---|
| `tools/preflight.sh` | ✅ 全部通过（版本号三处一致 / 无调试残留 / APK 存在已签名） |
| `aapt2 dump badging` | ✅ `versionCode='13' versionName='1.0.7' sdkVersion:'23' targetSdk:'34'` |
| 单测 | ✅ 118/118 |
| APK 大小 | ✅ 7,153,296 bytes（两端一致） |
| 更新检查 4 个源 | ✅ 全部返回 `1.0.7 (vc13)` |
| Gitee APK 下载 | ✅ HTTP 200，Content-Length 一致 |
| GitHub APK 状态 | ✅ `state=uploaded` |

**未做**：装机冒烟验证（Shizuku 服务未运行）。
构建、测试、preflight 均已通过，装机验证非发布前置条件。
