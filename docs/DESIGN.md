# HY 文件管理 · 设计文档

## 一、定位

与 HY_VQ 的关系：
- **HY_VQ**：综合工具（文件管理 + 终端 + 插件化 UI）
- **HY 文件管理**：**专注**文件管理与归档，包含文本编辑器、播放器；不使用插件机制
  - 已推翻 → 内置终端已于 2026-11-04（7c4dd57）移除

## 二、技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Java 17 | 与 HY_VQ 一致，便于复用 |
| UI | Material 3 | 与 HY_VQ 一致 |
| 压缩 | `java.util.zip` + `zip4j` | zip4j 仅在解密时启用 |
| 网络 | 播放器 + 更新检查 | 媒体流走 OkHttp；更新检查拉 versions.json；其余全离线 |
  - 已推翻 → 终端已移除；新增软件更新检查（2026-11-04）

## 三、模块划分

```
app/src/main/java/com/aliya/hyfilemgr/
├── MainActivity.java            主界面（双栏）
├── module/
│   ├── FileManagerModule.java   核心浏览/操作
│   （压缩包浏览与搜索已并入 FileManagerModule）
└── ui/
    └── ...
```

## 四、与 HY_VQ 的代码复用

可直接搬运：
- `FileManagerModule.java`（基础版，不带终端/插件）
- `MainActivity.java` 的 `fmtSize`、`animatePress`、`toolIcon`
- `readZipEncryptionMap`（中央目录解析）
- `splitZipPath`、`loadZip`、`openZipEntry`

不搬：
- ~~终端相关（TermNative、SSH、PTY）~~ 已推翻 → 2026-11-04 全部移除
- 插件化 UI（ModuleRegistry、抽屉）
- 网络模块（更新、下载）

## 五、已定（原「待定」项）

- 7z / rar：**已支持**。7z 走 commons-compress SevenZOutputFile 写、SevenZFile 读；rar 只解压（专有算法，无开源写实现），走 junrar

## 六、其他待定

- 是否支持 root 浏览
- 主题 / 图标
