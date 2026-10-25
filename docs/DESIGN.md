# HY 文件管理 · 设计文档

## 一、定位

与 HY_VQ 的关系：
- **HY_VQ**：综合工具（文件管理 + 终端 + 插件化 UI）
- **HY 文件管理**：**专注**文件管理，剔除终端、插件、网络等，保持轻量

## 二、技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Java 17 | 与 HY_VQ 一致，便于复用 |
| UI | Material 3 | 与 HY_VQ 一致 |
| 压缩 | `java.util.zip` + `zip4j` | zip4j 仅在解密时启用 |
| 网络 | **不引入** | 纯本地，零联网 |

## 三、模块划分

```
app/src/main/java/com/aliya/hyfilemgr/
├── MainActivity.java            主界面（双栏）
├── module/
│   ├── FileManagerModule.java   核心浏览/操作
│   ├── ZipBrowserModule.java    压缩包浏览
│   └── SearchModule.java        搜索/过滤
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
- 终端相关（TermNative、SSH、PTY）
- 插件化 UI（ModuleRegistry、抽屉）
- 网络模块（更新、下载）

## 五、待定

- 是否支持 7z / rar（需第三方库）
- 是否支持 root 浏览
- 主题 / 图标
