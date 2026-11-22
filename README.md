# HY文件管理

Android 双窗格文件管理器。支持 11 种压缩格式的创建与解压、内置文本编辑器、
媒体播放器（手势控制 · 弹幕 · HLS）、图片查看与 APK 解析。

- **仓库**：[GitHub](https://github.com/haoyou781013/hy-file-manager) · [Gitee](https://gitee.com/haoyou781/hy-file-manager)
- **当前版本**：1.0.7（versionCode 13）
- **包名**：`com.aliya.hyfilemgr`
- **最低系统**：Android 6.0（API 23）

---

## 功能

### 文件管理
- 双窗格浏览，独立历史栈（前进/后退/上级）
- 列表 / 网格两种视图，三档显示密度
- 排序（名称自然序 / 时间 / 大小）、隐藏文件开关
- 多选、复制、剪切、粘贴、重命名、删除
- 回收站：删除可恢复，`.trashmeta` 记录原始路径，按天自动清理
- 书签、最近文件
- **`Android/data` / `obb` / `media` 可读可写**（见下方「Android/data 访问」）
- 包名文件夹右下角叠加对应应用图标（可在设置里关闭）

### 主题与外观
- 背景图（全局 + 文件列表页独立两套），支持不透明度、模糊度
- 卡片不透明度、侧边栏不透明度、状态栏不透明度，均为实时生效
- 状态栏透明，背景图可一直铺到状态栏后面
- 界面主题（默认 / 工业风格）、明暗模式（跟随系统 / 亮 / 暗）
- 存储管理页：内部存储总量 + 应用占用明细，各项可单独清理

### 压缩
| 操作 | 支持的格式 |
|---|---|
| 创建 | zip · 7z · tar · tar.gz · tar.bz2 · tar.xz · tar.zst · gz · bz2 · xz · zst |
| 解压 | 上述全部 + rar / rar5 |
| 内部浏览 | zip · 7z · rar · tar 家族（可直接查看包内结构，无需解压） |

- 加密压缩（zip AES / 7z AES256），解压时自动识别并询问口令
- 解压炸弹防护：条目数上限、单文件 1GB、总量 4GB、压缩比检测
- 定点提取单个条目（「解到对侧」不整包解压）

### 媒体播放器
基于 media3（ExoPlayer 1.11.1），视频 / 音频 / 网络流。

**播放能力**
- 本地文件与 HTTP(S) 网络流；HLS（m3u8）分片播放
- 可随 Intent 传入请求头（Referer / Cookie 等），适配需要防盗链的源
- 播放列表与选集（动漫模式下显示「番剧 · 线路 · 第N集」）
- **外挂字幕**：自动扫同目录同名前缀的 `.srt` / `.ass` / `.ssa` / `.vtt`
- 倍速 6 档（0.5× / 0.75× / 1.0× / 1.25× / 1.5× / 2.0×）、画面比例三档（适应 / 填充 / 裁剪）、当前帧截图（长按倍速按钮）
- 观看历史：退出记录进度，下次进入自动续播

**手势**
| 手势 | 行为 |
|---|---|
| 双击左 1/3 | 后退 10 秒 |
| 双击中间 | 播放 / 暂停 |
| 双击右 1/3 | 前进 10 秒 |
| 横向滑动 | 拖动进度（整屏宽 = 2 分钟，松手生效） |
| 左侧上下滑 | 亮度 |
| 右侧上下滑 | 音量 |
| 长按 | 临时加速，松手恢复 |

- 屏幕边缘 16dp 内不识别横向滑动，让位给系统返回手势
- 所有手势都有中央 HUD 反馈（含目标时间），不会「操作了却没反应」

**进度条**
- 三层结构：已缓冲 / 已播放 / 未播放，粗细差异化
- 拖动时跟随手指显示精确时间气泡（长视频定位必需）
- 40dp 触摸区 + 按下放大圆点

**弹幕**
- 从 B 站接口拉取，滚动 / 顶部 / 底部三种模式
- 描边渲染，亮画面上也清晰
- 播放器内可一键开关（状态记忆到下次）

**播放器设置**（设置 → 工具 → 视频播放器）
- 控制条自动隐藏开关 / 隐藏延迟（1.5 / 3 / 5 / 10 秒）/ 单击切换
- 长按加速倍速、双击跳转时长、横向滑动灵敏度
- 一键恢复默认

### 其他
- 文本编辑器：多标签、撤销/重做、正则查找替换、GBK/UTF-8 编码处理
- 图片查看、字体预览、APK 图标解析
- SAF 授权目录挂载、root 路径浏览（需 root）

---

## 构建

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

环境要求：
- JDK 17
- AGP 8.1.4 / Gradle 8.1.1
- compileSdk 36 / minSdk 23 / targetSdk 34
- **aarch64 设备需自建 aapt2**（官方 build-tools 的 aapt2 只有 x86-64）

---

## 开发说明

**本项目在开发过程中使用了 AI 辅助工具**，具体包括：

| 用途 | 工具 |
|---|---|
| 代码生成与重构 | DeepSeek 系列模型 |
| 代码审查（8 角色并行，两轮共 16 路） | DeepSeek 系列模型 |
| 文档整理与一致性核对 | DeepSeek 系列模型 |
| 构建工具链 | Gradle 8.1.1 / AGP 8.1.4 / JDK 17 |
| aapt2 | 自行编译的 aarch64 版本 2.20-android-16.0.0_r4 |

所有 AI 产出的代码均经过：
1. 单元测试（JVM，无需设备）
2. 编译验证（Gradle 全量构建）
3. 真机安装验证（OnePlus PLQ110 / Android 16）
4. 关键路径实测（压缩、解压、预览、回收站、返回键、长任务保活）

代码审查采用「生成 → 独立审查 → 交叉验证」流程，审查角色按职责固定（UI / IO / 架构 / 健壮性 / 媒体 / 一致性等），
每个角色独立阅读代码并输出带行号的问题清单，再由主流程核对后修复。

### 测试

`gradle testReleaseUnitTest`，71 个用例：

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `FileNamesTest` | 24 | 文件名解析与自然排序 |
| `PlayerReducerTest` | 24 | 播放器状态机 |
| `GestureControllerTest` | 19 | 手势判定纯函数 |
| `PlayerSettingsTest` | 4 | 播放器设置默认值 |

播放器重构时把核心逻辑抽成**纯函数**（不依赖 `Context` / View / `Player`），
所以能在 JVM 上直接测——例如「双击落在 2/3 边界算哪个分区」
「暂停时该不该自动隐藏控制条」这类以前必须装机点屏幕才能验证的问题，
现在是几行断言。

### 播放器架构

```
MediaPlayerActivity          装配 + 生命周期
├── GestureController        手势识别（只发事件，不执行）
├── GestureSink              手势语义出口
├── PlayerUiState            不可变状态（17 字段）
├── PlayerEvent              15 种语义事件
├── PlayerReducer            纯函数 reduce(state, event)
├── PlayerSettings           可配置参数（SharedPreferences）
├── DanmakuView              弹幕渲染（自绘，支持滚动/顶部/底部）
└── PlayerCore               ExoPlayer 工厂（HLS / 请求头 / zip 内媒体）
```

关键点：
- 手势层不知道 `player` 存在，通过 `GestureSink` 发语义事件
- 所有状态变化走 `reduce()`，UI 由状态派生，避免「图标显示暂停但实际在播」
- 参数不硬编码，用户可在设置里调

设计文档见 [`docs/PLAYER-REDESIGN-V2.md`](docs/PLAYER-REDESIGN-V2.md)
与 [`docs/PLAYER-BASELINE.md`](docs/PLAYER-BASELINE.md)。

---

## Android/data 访问

Android 11 起，`/sdcard/Android/data`、`obb`、`media` 由 FUSE 层做**路径字符串**
拦截。即使应用拿到 `MANAGE_EXTERNAL_STORAGE`，`listFiles()` 对这些目录仍返回 null，
官方文档也明确写着「仍不能访问其他应用的 app-specific 目录」。

本项目的做法是：路径里 `Android` 与 `data` 之间插入一个零宽空格（U+200B）。
FUSE 的拦截规则按字符串匹配认不出这是 `Android/data`，而 Linux VFS 在解析
目录项时会忽略这个字符，最终落到同一个 inode —— 该 inode 的权限是
`drwxrws--x`，从未收紧，于是读、写、删全部通行。

- 实现见 `app/src/main/java/com/aliya/hyfilemgr/access/PathAlias.java`
- 零宽字符只存在于内部 IO 路径，`FileEntry.path` 始终是真实路径，
  **不会出现在界面、复制出来的文本或任何持久化数据里**
- 非受限路径（`/sdcard/DCIM` 之类）不经过该逻辑，零额外开销

**这是利用一个未修复的系统行为，不是官方支持的能力。** Google 修复字符处理后
该功能即失效，届时会回退到原来的「无权限」空列表。本项目同时实现了官方的
`getManageSpaceActivityIntent`（Android 12+）作为替代路径。

## 已知限制

- **RAR 不能创建**：UnRAR 许可证禁止开发 RAR 兼容的压缩器，只能解压
- **zstd 压缩依赖 JNI 原生库**，需真机运行（x86 模拟器/容器可能加载失败）
- 大 `tar.gz` 首次列目录需完整扫一遍，几十 MB 的包会有可感知等待
- 加密 rar 未接口令输入
- **DASH（`.mpd`）实际不可用**：`PlayerCore` 会给 `.mpd` 设置 MPD mimeType，
  但 `build.gradle.kts` 未引入 `media3-exoplayer-dash` 依赖，
  会走到 ExoPlayer 的「未支持的格式」错误。要么补依赖，要么去掉该分支——
  当前状态是静默失效（不崩溃，但播不了）
- **`Android/data` 访问依赖系统路径校验缺陷**：见上方专节，Google 修复后失效
- `dirCache` 指纹基于父目录 mtime + 直接子项数，**外部 App 在子目录内增删文件**后
  父目录缓存不会失效（应用内操作会正确刷新）

---

## 隐私

所有文件操作均在本地完成，不上传任何数据。

**唯一的网络请求**是「设置 → 软件更新 → 检查更新」，按以下顺序依次尝试
拉取 `versions.json`，任一成功即停止：

1. `gitee.com`（国内直连，首选）
2. `cdn.jsdelivr.net`（GitHub 镜像）
3. `gh-proxy.com`（GitHub 镜像）
4. `raw.githubusercontent.com`（GitHub 原始地址，兜底）

下载新版本 APK 时同样只访问上述域名（GitHub Release 走 1、3、4）。
请求中只带 User-Agent（含版本号），不发送设备标识或任何文件内容。

---

## 版本记录

- **[CHANGELOG.md](CHANGELOG.md)** —— 面向开发者的技术记录
- **[versions.json](versions.json)** —— 面向用户的更新说明（App 内「软件更新」展示的就是这里）
- **[DEVLOG.md](DEVLOG.md)** —— 尚未发布的改动在此累积，发版时汇总进上面两个文件

发布规则：
- 版本号唯一来源是 `app/build.gradle.kts` 的 `versionName` / `versionCode`
- `versions.json` 与之一致，更新检查读 `versionCode` **最大**项
- 正式版 `X.Y.Z`，测试版 `X.Y.Z-beta.N`

---

## 许可

见 [LICENSE](LICENSE)。
