# HY 文件管理

> 一个轻量级 Android 文件管理器 · 纯 Java + XML

## 项目简介

HY 文件管理（HY_FileManager）是一个极简、高效的 Android 文件管理工具，专注于
快速浏览、搜索和操作本地文件，不引入第三方框架，保持最小依赖。

## 特性

- 📁 双栏文件浏览（左目录树 + 右文件列表）
- 🔍 文件搜索、排序、过滤
- 📦 压缩包内部浏览（虚拟路径 `zip://`，不解压）
- 📋 多选批量操作（复制 / 移动 / 删除 / 重命名 / 解压）
- 📊 文件属性查看（大小 / 修改时间 / 权限 / 校验）
- 🚀 性能优化：lazy 加载 + 异步 IO

## 技术栈

| 项 | 值 |
|---|---|
| 语言 | Java 17 |
| 最低 Android | 5.0（API 21） |
| 目标 Android | 14（API 34） |
| UI 框架 | AndroidX · Material 3 |
| 构建 | Gradle 8.x · AGP 8.x |
| 依赖 | 仅 androidx.core / appcompat / material |

## 项目状态

- **阶段**：初始化（v0.1.0-dev）
- **范围**：本地文件管理，不联网、不发布到 Play
- **分发**：自签名 APK（GitHub Releases + Gitee Releases）

## 开发与构建

```bash
# 构建（命令行）
sh toolchain/gradle/gradle-8.1.1/bin/gradle :app:assembleRelease --no-daemon

# 产物
app/build/outputs/apk/release/app-release.apk
```

## 发布

- 双平台：GitHub `haoyou781013/HY_FileManager` + Gitee `haoyou781/hy_filemanager`
- 测试版 `X.Y.Z-beta.N`，正式版 `X.Y.Z`
- 版本号唯一来源：`app/build.gradle.kts` 里的 `versionName/versionCode`

## 目录结构

```
HY文件管理/
├── app/                          Android 工程
├── docs/                         设计与变更文档
├── tools/                        构建辅助脚本
├── versions.json                 版本历史（发布记录）
└── README.md                     本文件
```

## 隐私

- **不联网**：纯本地文件管理，不上传任何数据
- **不索取多余权限**：只申请文件读写、安装包权限

## License

私有项目，不公开分发。
