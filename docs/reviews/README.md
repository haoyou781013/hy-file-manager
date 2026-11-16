# 五维度全量代码审查汇总（2026-11-17）

**方法**：5 个无上下文的独立子代理并行审查，各自用 grep 实测代码，主会话逐条验证关键结论。
**范围**：43 个 Java 文件 / 28,661 行，主文件 `FileManagerModule.java` 17,224 行（占 60%）。

| 报告 | 文件 |
|---|---|
| 设计系统一致性 | `2026-11-17-设计系统.md` |
| 性能与内存 | `2026-11-17-性能内存.md` |
| 正确性与安全 | `2026-11-17-正确性安全.md` |
| 架构与可维护性 | `2026-11-17-架构.md` |
| 无障碍与 UX | `2026-11-17-无障碍UX.md` |

---

## 一、必修（真 bug，已验证）

### 1. `.tbz/.tbz2/.txz/.tzst` 压缩包打开即崩

**位置**：`FileManagerModule.java:15534-15565` `splitZipPath`

后缀表有 `.zip/.jar/.apk/.xapk/.7z/.rar/.tar/.tar.gz/.tar.bz2/.tar.xz/.tar.zst/.tgz`，
**缺 `.tbz` / `.tbz2` / `.txz` / `.tzst`** —— 而这 4 个 `ArchiveExtractor.canExtract` 明确支持，
侧边栏「压缩包」分类也包含它们。

后果：切分失败 → `best<0` 回退到首个 `/` → 整个 `zip://…/x.tbz` 被当包路径 →
`loadZip` 返回「不存在」→ 点「解压」时 `rest.charAt` 越界崩溃。

**修复**：补 4 个后缀（一行）。

### 2. `FileOps.deleteRecursive` 不判 symlink → 可能删除链接目标

**位置**：`filemgr/FileOps.java:278-289`

```java
private static int deleteRecursive(File f) {
    if (f.isDirectory()) {          // ← symlink 指向目录时 isDirectory()=true
        File[] children = f.listFiles();
        for (File c : children) count += deleteRecursive(c);  // ← 递归进目标目录
    }
    return f.delete() ? count + 1 : count;
}
```

对照：模块 `deleteTree`（16954）与 `ArchiveExtractor.deleteTreeQuietly` 都判 `isSymlink`，
**唯独这里没有**。调用链：`moveAsync` 跨分区删源、`moveToTrash` 删源、`restoreFromTrash` 删临时。

**修复**：与 `deleteTree` 同构，加 `isSymlink` 判断。

### 3. 压缩后删源不验证结果

**位置**：`FileManagerModule.java:15146-15154`、`15409-15416`、`3893-3899`

`deleteTree` / `delete()` 返回值被忽略。磁盘满或文件被占用时，
压缩包生成成功、源删除失败 → 无提示 → 用户下次看到两份。

**修复**：删源失败给提示。

### 4. 回收站过期清理留孤儿 `.trashmeta`

**位置**：`FileManagerModule.java:16382-16400`

`purgeExpiredIn` 删条目但没删同名 `.trashmeta` → 残留占位；
下次同名条目复用旧 meta → 恢复到错误路径。

**修复**：删除时一并 `deleteTrashMeta`。

---

## 二、设计系统（最系统性的问题）

### 核心发现：常量定义了但 0 处使用

| 常量 | 外部引用次数 |
|---|---|
| `UiKit.TYPE_*`（7 档字号标尺） | **0** |
| `UiKit.RADIUS_*`（4 档圆角） | **0** |

213 处 `setTextSize` 全部硬写数值，圆角散写 12 种。**常量纯摆设。**

### 实测数据

| 维度 | 现状 | 问题 |
|---|---|---|
| **字号** | **14 种**：13(62) 11(41) 12(32) 10(21) 14(15) 15(8) 9(3) 20(3) 16(2) 28/22/18/17/11.5(各1) | 11/12/13 三档合计 **135 次（63%）只差 1sp**，人眼不可分；另有 8 个孤值 |
| **颜色** | 66 种 `0x` 字面量（约 16 个是 PE/ZIP 解析魔数，真实 UI 色约 50） | 近似重复：错误红 `FF6B6B` vs `FF6E6E`；灰三档；浅底 6 种；描边 2 种 |
| **间距** | 47 种 `dp()` | 4 倍数 392 次，散值约 408 次。实际规律是「偶数合法」，不是 4/8 栅格 |
| **圆角** | 12 种实际半径（999×8、14×9、16×6、10×5…） | `RADIUS_*` 未使用，16dp（最常用卡片角）反而无常量 |
| **触摸目标** | 约 15 处 < 48dp | 最小 **8dp**（trashArrow 14dp、音乐键 34dp、同步按钮 40dp） |

### 建议标尺

```
字号（6 档，档差 ≥2sp）：
  9,10,11,11.5 → 11
  12,13        → 13
  14,15        → 15
  16,17,18     → 17
  20,22        → 20
  28           → 26

圆角：XS=6  SMALL=10  MEDIUM=16  LARGE=24  PILL=999

间距：合法集 {2,4,6,8,10,12,14,16,20,24}（2dp 微栅格），
      收敛掉 1/3/5/7/9/11/13/18/22（约 60 处）
```

**影响面**：字号 213 处（`FileManagerModule` 占 188）、圆角约 30 处、间距约 60 处。
sed 批量替换可完成。

---

## 三、性能（P0 四项）

| 项 | 位置 | 量化影响 |
|---|---|---|
| **主线程 `listFiles`** | `1783`（刷新）、`2919`（`reloadAll`）、`1088`（`createMainView` 首帧前） | 万级目录 100~500ms，启动白屏同量级 |
| **`onBindViewHolder` 每行重分配** | `FileListAdapter:297-370` | 一屏 15~20 行 ≈ **800~1200 个临时对象/帧**；每行 4~8 次 `resolveAttribute` |
| **排序 comparator 内 `toLowerCase`** | `3234-3248` | 1 万文件 ≈ 13 万次比较 × 2 = **26 万字符串对象**、约 10MB 抖动 |
| **任务队列无界 + 裸线程** | `TASK_QUEUE:120`（无界）、`FileOps.runAsync:216`（7 处 new Thread） | 连点 20 个解压 → 20 个 Runnable 积压 + 20 次前台服务 acquire |

已在上一轮修好的：`dirCache` LRU24、`archiveItemsCache` LRU8、历史栈上限 50、
`ThumbLoader` 3 线程池 + 8MB LruCache + 200MB 磁盘 + 30s 节流、
`PREF_HANDLER` 后台落盘、`ZipCache` 300MB LRU、回收站清扫后台化。

---

## 四、无障碍（最容易验证的短板）

### 触摸目标 < 48dp（4 处阻断）

| 位置 | 实际尺寸 |
|---|---|
| `13815` 音乐控制 4 按钮 | **34dp** |
| `2206` 同步按钮 | **40dp** |
| `11203-11219` 编辑器标签页 | **30dp**（关闭「×」padding 仅 8dp） |
| `4374-4379` 反馈类型 chips | 约 **30dp** |

### 对比度不足（3 处阻断，已算实际比值）

| 位置 | 配色 | 实测比值 |
|---|---|---|
| `13338` 失败原因红字 | `0xFFFF6B6B` 配浅色玻璃底 | **2.78:1** |
| `11211` 编辑器标签激活态 | 白字配 `0xFF82AAFF` | **≈2.3:1** |
| `11712` 编辑器浅色强调 | `0xFF42A5F5` 配白底 | **2.65:1** |

标准是 4.5:1。

### contentDescription 覆盖 17%

```
ImageView/Button 创建：36 个
setContentDescription：6 个（+1 = 7 处）
```

无标签的包括：底栏 4 个 navButton、FAB、回收站箭头、拖拽手柄、画廊缩略图、侧边栏图标。
编辑器关闭按钮是 TextView「×」，读屏朗读「乘号」。

### 其他

- **只能点不能聚焦** 6 处（crumb、trashHeader、chip、add 按钮、tvFormat、gallery 缩略图）
- `bg_ripple_round` 无 `state_focused`，键盘用户看不到焦点
- **`reduce motion` 不生效**：`animScale` 只在三个 Global scale 全 0 时判 0，
  Android 14 的 `accessibility_force_reduce_motion` 开启时动画照跑（代码注释自认）
- 43 处 `setDuration` 中只有 7 处参考 `animScale`

---

## 五、架构

### 重复逻辑（已验证）

| 功能 | 位置 1 | 位置 2 | 状态 |
|---|---|---|---|
| 扩展名判断 | `isImageName:8401`（手写 endsWith×6） | `Exts.isImage` | **漏网旧实现**，12826 行注释说「已统一到 Exts」却还在用 |
| GBK 修复 | `fixGbkName:15481` | `ArchiveExtractor:786` | 本文件这份 **零调用**，删 |
| 递归删除 | `deleteTree:16954`（有 symlink 防） | `deleteDirQuiet:6912`（无） | 两套 |
| 递归统计 | `dirSize:16695` + `countFiles:16707` | `FileOps.dirSize:60` | 逐字相同 |
| 大小格式化 | `AccessUtil.formatSize` | `fmtStorageSize:5673` | 后者多 TB 档 |
| 压缩后缀表 | `ARCHIVE_NAME_SUFFIXES:629` | `ArchiveExtractor.canExtract` | 各自维护 |

### 注释与代码不符

- `REQ_PICK_BG:8054` 自称 retired，但 `8116/8202` 仍在用
- `isImageName:8401` 在「已统一到 Exts」注释（12826）之后仍存在

### 拆分建议

**先拆（零依赖，机械搬移，各约 2h）**：
压缩实现 `14822-15443`、回收站 `16330-16612`、
共享工具池（~35 个纯静态方法 → `util/FileUtils.java`）

**后拆（有 View/State 耦合）**：
文本编辑器 `10958-12790`（1800 行，需先给 `ctx`/`fmPrefs()` 建立构造器注入）

**不拆**：主题/卡片渲染链（`321-530`、`7759-8060`）、`onSafGrantedStatic` 桥

**不值得做**：引入 DI 框架、把 60 个字段改 ViewModel、为 435 个方法建接口。
17k → 8k 行就够了。

### 可测试性

无 `test`/`androidTest` 目录。435 个方法里约 35 个是纯函数
（`naturalCompare`/`isValidFileName`/`uniqueName`/`parseSize`/`stripMarkdown` 等）——
这是唯一能低门槛建测试底座的部分。

---

## 六、验证结果：1 条误报

| 子代理声称 | 实测 |
|---|---|
| `resumePendingInstall` 延时回调未检查 `detached`（会崩） | ❌ **误报**。`1629-1631` 已有 `if (detached \|\| pendingInstallInfo == null) return;` |

其余关键结论（#1 后缀表、#4 deleteRecursive、`TYPE_*`/`RADIUS_*` 零引用、
`0xFFFF6B6B` 位置、触摸目标尺寸）**全部验证为真**。

---

## 七、优先级建议

### 立即修（真 bug，30 分钟）

| # | 项 | 工作量 |
|---|---|---|
| 1 | `splitZipPath` 补 `.tbz/.tbz2/.txz/.tzst` | 1 行 |
| 2 | `FileOps.deleteRecursive` 加 symlink 判 | 10 行 |
| 3 | 回收站清理时删 `.trashmeta` | 10 行 |
| 4 | 删 `fixGbkName`（零调用）、`isImageName` 改走 `Exts` | 20 行 |

### 然后（用户体验可见，2~3 小时）

| # | 项 | 收益 |
|---|---|---|
| 5 | 字号收敛到 6 档（sed 批量） | 信息层级恢复 |
| 6 | 触摸目标 <48dp 的 4 处补 padding | 无障碍 |
| 7 | 3 处对比度不足换色 | 无障碍 |
| 8 | 关键图标按钮补 `contentDescription` | 无障碍 |

### 再后（性能，改动大）

9. `onBindViewHolder` 属性预计算（滚动手感）
10. 排序 key 预计算（大目录卡顿，10 行）
11. `listFiles` 移出主线程（启动白屏，工作量最大）

### 最后（架构，谨慎）

12. 拆分 4 块无状态代码
13. 统一 6 处重复逻辑
14. 纯函数补 JUnit
