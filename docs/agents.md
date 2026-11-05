# HY文件管理 · 八个固定子代理

**规则**：这 8 类职责固定，以后复用，不再新建。派任务时按此表对号入座。

| # | 代号 | 职责 | 覆盖目录 / 文件 |
|---|---|---|---|
| 1 | **A1-UI** | 界面构建、布局、View 树、对话框、动画、主题、图标 | `module/FileManagerModule.java`（UI 部分）、`ui/`、`res/layout`、`res/drawable` |
| 2 | **A2-IO** | 文件操作、读写、复制/移动/删除、SAF、Root、策略路由 | `access/`、`filemgr/FileOps.java`、`module` 中文件操作 |
| 3 | **A3-ARCH** | 压缩/解压、归档格式、编码、加密 | `archive/`、`util/MarkdownUtils.java`、压缩相关 |
| 4 | ~~**A4-TERM**~~ | ~~终端、Shell 会话、PTY~~ | 已停用（terminal/ 已于 2026-11-04 移除） |
| 5 | **A5-EDIT** | 文本编辑器、语法、行号、查找替换、预览 | `module` 中 editor* 系列、`util/` 编辑器工具 |
| 6 | **A6-MEDIA** | 播放器、图片查看、字体预览、弹幕、缩略图 | `MediaPlayerActivity`、`ImageViewerActivity`、`font/`、`player/`、`filemgr/ThumbLoader`、`apkicon/` |
| 7 | **A7-ROBUST** | 崩溃处理、异常、线程、内存、性能、构建配置 | `crash/`、`HYApp`、`MainActivity`、`build.gradle.kts`、`AndroidManifest.xml` |
| 8 | **A8-REVIEW** | 代码审查、一致性核查、文档、版本、提交规范 | 全项目（只读为主）+ `git` 操作核查 |

## 派单原则

- **单文件多职责时**（如 `FileManagerModule.java` 11116 行）：
  按**功能段落**切片派发，不要让两个代理同时写同一个文件。
- **冲突规避**：A1-UI 与 A5-EDIT 都碰 module 文件 → 串行派发，或明确划分行段。
- **审查闭环**：任何实现类任务完成后，由 A8-REVIEW 做一次交叉核查。

## 调用约定

```
subagent(
  description="A3-ARCH 解压修复",
  prompt="你是 A3-ARCH（归档/压缩/编码）。职责范围：archive/、压缩解压相关。
          任务：...
          约束：不修改职责外文件；不改动 A1/A2 的代码。
          输出：改动文件清单 + 关键代码 + 验证方式。"
)
```
