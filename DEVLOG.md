# 开发日志（未发布改动累积）

> 本文件记录**尚未发布**的改动。用户说「发布新版本」时，把这里的内容
> 汇总进 `versions.json`（面向用户）+ `CHANGELOG.md`（面向开发者），
> 然后清空本文件。
>
> 记录规则：每条写「改了什么 / 为什么 / 影响面」，不写调试过程。

---

## 1.0.1（2026-11-05）已发布

本批全部改动已汇总：
- 面向用户 → `versions.json` 的 1.0.1 条目
- 面向开发者 → `CHANGELOG.md` 的 1.0.1 章节

详见上述两处。

---

## 1.0.1 重发（2026-11-05）

用户实测发现已发布的 1.0.1 有安装权限链路缺陷，撤回重发。

### 修：安装权限弹窗点不动 + 授权后不续接

**缺陷 1：两个按钮都无反应**
```java
UiKit.dismissWithAnim((Dialog) ((View) v.getParent()).getTag())
```
`getTag()` 从未被 setTag 过 → 取到 null → dismissWithAnim(null) 什么也不做。
"取消"点了没反应；"去开启"虽跳去系统设置，但弹窗没关，
回来后它还盖在最上层，表现为「卡在申请页」。

**缺陷 2：授权回来后不继续**
没有 onResume 检查 → 用户必须自己再点一次「检查更新」。

修法：
- 弹窗持有自身引用 pkgInstallDialog，不再依赖 getTag
- 「取消」→ dismiss + 清 pendingInstallInfo
- 「去开启」→ **先 dismiss 再跳设置**，回来时不再被挡
- 新增 pendingInstallInfo 记住待下载的更新
- onResume → resumePendingInstall()，延迟 400ms（避开 ROM 权限刷新延迟）
  自动续上下载；未授权就返回则 toast 提示并清待办

---

## 未发布

### 主题设置：背景图 + 卡片透明度 + 浅边框

「个性化」改名为「主题设置」，并加入背景图能力。

**背景图**
- 全局背景层：ImageView 铺满 rootContainer 底层（index 0），
  上面所有页面半透明叠着。逐页设背景会在切页时跳一下，
  一层铺满则背景完全稳定。
- 点击穿透：背景层 setClickable(false)，不挡列表点击
- 存储：复制到 filesDir/wallpaper/，原图删了也不影响；
  换图时删旧文件
- 解码：先读边界算 inSampleSize 再真解码。4000×3000 的图
  全尺寸在堆里是 48MB，低端机直接 OOM

**两个选图入口**
1. 系统图片选择器：ACTION_OPEN_DOCUMENT + image/*，
   系统自带相册入口，一步到位
2. 自建文件列表（单列）：复用 AccessRouter，
   能进 SAF 授权目录 / root / 外部存储 —— 有些壁纸存在
   隐藏目录或相册扫不到的地方，必须能自己 browse
   仅显示目录 + 图片扩展名，避免在一堆 txt 里找图

**三个滑块**
- 背景不透明度 0-100%（直接改 ImageView alpha，无需重渲染）
- 背景模糊度 0-25（stack blur：先 1/4 缩小再做 box blur 再放大，
  速度提升一个数量级，视觉几乎一致）
- 卡片不透明度 0-100%

**浅边框**
- 新增 cardSurface()：运行时生成半透明底 + 1px colorOutlineVariant@0x22
  （约 13% 不透明）描边
- 不用静态 drawable：透明度是可调状态，写死 alpha 就跟不上滑块
- 应用于 addInfoRow / buildSimpleRow（设置项行、选图弹层行）
- 用户要求「不需要太明显」，所以描边压到很淡

**内容层透明化**
有背景图时，设置页容器 / 底部功能栏 / 多选栏 都要半透明，
否则整块不透明会把背景全挡住。syncContentTransparency() 统一处理；
删掉背景图时恢复不透明。

---

## 待发布

（待汇总）
