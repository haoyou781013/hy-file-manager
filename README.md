# HY文件管理

Android 双窗格文件管理器。支持 11 种压缩格式的创建与解压、内置文本编辑器、媒体播放器。

- **仓库**：[GitHub](https://github.com/haoyou781013/hy-file-manager) · [Gitee](https://gitee.com/haoyou781/hy-file-manager)
- **当前版本**：1.0.0（versionCode 5）
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

### 压缩
| 操作 | 支持的格式 |
|---|---|
| 创建 | zip · 7z · tar · tar.gz · tar.bz2 · tar.xz · tar.zst · gz · bz2 · xz · zst |
| 解压 | 上述全部 + rar / rar5 |
| 内部浏览 | zip · 7z · rar · tar 家族（可直接查看包内结构，无需解压） |

- 加密压缩（zip AES / 7z AES256），解压时自动识别并询问口令
- 解压炸弹防护：条目数上限、单文件 1GB、总量 4GB、压缩比检测
- 定点提取单个条目（「解到对侧」不整包解压）

### 其他
- 文本编辑器：多标签、撤销/重做、正则查找替换、GBK/UTF-8 编码处理
- 媒体播放器：基于 media3（视频 / 音频）
- 图片查看、字体预览、APK 图标解析
- SAF 授权目录挂载、root 路径浏览（需 root）
- 局域网 FTP / HTTP 文件服务

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
1. 编译验证（Gradle 全量构建）
2. 真机安装验证（OnePlus PLQ110 / Android 16）
3. 关键路径实测（压缩、解压、预览、回收站、返回键、长任务保活）

代码审查采用「生成 → 独立审查 → 交叉验证」流程，审查角色按职责固定（UI / IO / 架构 / 健壮性 / 媒体 / 一致性等），
每个角色独立阅读代码并输出带行号的问题清单，再由主流程核对后修复。

---

## 已知限制

- **RAR 不能创建**：UnRAR 许可证禁止开发 RAR 兼容的压缩器，只能解压
- **zstd 压缩依赖 JNI 原生库**，需真机运行（x86 模拟器/容器可能加载失败）
- 大 `tar.gz` 首次列目录需完整扫一遍，几十 MB 的包会有可感知等待
- 加密 rar 未接口令输入
- `dirCache` 指纹基于父目录 mtime + 直接子项数，**外部 App 在子目录内增删文件**后
  父目录缓存不会失效（应用内操作会正确刷新）

---

## 隐私

所有文件操作均在本地完成，不上传任何数据。

**唯一的网络请求**是「设置 → 软件更新 → 检查更新」，向
`raw.githubusercontent.com` 或 `gitee.com` 拉取 `versions.json`，
请求中只带 User-Agent（含版本号），不发送设备标识或任何文件内容。

---

## 版本记录

见 [CHANGELOG.md](CHANGELOG.md) 与 [versions.json](versions.json)。

发布规则：
- 版本号唯一来源是 `app/build.gradle.kts` 的 `versionName` / `versionCode`
- `versions.json` 与之一致，更新检查读 `versionCode` **最大**项
- 正式版 `X.Y.Z`，测试版 `X.Y.Z-beta.N`

---

## 许可

见 [LICENSE](LICENSE)。
