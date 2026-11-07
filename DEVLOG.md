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
### 修：打开侧边栏崩溃（ClassCastException）

崩溃：
```
java.lang.ClassCastException: android.widget.ImageView
  cannot be cast to android.view.ViewGroup
  at showNavSidebar(FileManagerModule:3756)
  at 汉堡图标 onClick
```

**根因**：是上一版加背景层引入的连锁反应。

`showNavSidebar()` 要找 `rootContainer` 里除 `navOverlay` 外的第一个子节点，
当作毛玻璃模糊目标：

```java
for (int i = 0; i < rootContainer.getChildCount(); i++) {
    View c = rootContainer.getChildAt(i);
    if (c != navOverlay) {
        blurTarget = (ViewGroup) c;   // ← 崩
        break;
    }
}
```

原来 `rootContainer` 的子节点是 `[body, navOverlay]`，第一个非 navOverlay 的就是
`body`（LinearLayout，能转 ViewGroup）。

`attachBackgroundLayer()` 用 `addView(bgImageView, 0, lp)` 把背景图插到了 index 0，
于是变成 `[bgImageView, body, navOverlay]`，循环第一个命中的就是 `bgImageView`
（ImageView），强转直接崩。

**修法**：判断条件加上 `instanceof ViewGroup`，跳过非容器节点。

**同一处隐患还有第二处**（关闭侧边栏时清 renderEffect，原 `FileManagerModule:6620`）：
那里有 try-catch，`ClassCastException` 被吞掉不崩溃，但**模糊永远清不掉**。
症状是关掉侧边栏后，文件列表一直糊着。一并改成 `instanceof` 判断。

**核查**：全仓只有这两处遍历 `rootContainer`，没有其他按索引取子节点的代码；
`navOverlay` 靠添加顺序置顶，未用 `bringToFront`，背景层插 index 0 不影响其 z-order。

**验证**：assembleRelease 通过，装机启动无 FATAL。
侧边栏开关的实际毛玻璃效果**未实测**。

## 设备侧工作（2026-11-06，非本项目代码）

### 通过 OTG 在一加手机上建立 adb/fastboot 环境

无 PC 前提下，用一加（USB Host）直接调试 Redmi 13R。

**为什么这么绕**：Termux 运行在 `untrusted_app` SELinux 域，对 `usb_device` 判 deny，
`chmod 666` 节点与 `chmod 755` 父目录均无效。必须以 root 运行 adb。

**四道墙与破法**：
1. Termux 用户无 USB 权限 → 以 root 运行 adb
2. `/data/data` 在 root shell 内不可见 → 二进制与库先搬到 `/sdcard`，再搬到 `/data/local/tmp/usbh`
3. 换 HOME 就换 RSA 密钥 → 复制 Termux 已授权的 `adbkey` 过去，否则 `adb devices` 恒为空
4. 官方静态 aarch64 adb 必撞 bionic fdsan 崩溃 → 改用 Termux 版 android-tools 37.0.0p1

**建成环境**：`/data/local/tmp/usbh/`（adb + fastboot + 322 个 .so + adbkey）

**调用模板**：
```bash
export LD_LIBRARY_PATH=/data/local/tmp/usbh/lib HOME=/data/local/tmp/usbh
/data/local/tmp/usbh/adb devices
```

**验证结果**：
- `adb devices` → `CUX87P4T6XWWLZ5T device product:air model:23124RN87C`
- `fastboot devices` → `CUX87P4T6XWWLZ5T fastboot`
- 相册备份 1.6 GB / 2250 个文件 → `10-Redmi备份/`

### 解锁 BL：已实测排除，除官方通道外无技术路径

2026-11-08 真机跑完 12 条 fastboot 命令：

| 命令 | 返回 |
|---|---|
| `flashing unlock` / `unlock_critical` | `unknown command` |
| `oem unlock` / `unlock-go` / `unlock 0` / `unlock 1` | `Start unlock flow` → `Failed to unlock the device` |
| `oem device-info` / `get_unlock_ability` | `unknown command` |
| `oem lock` | `No support by lock control` |
| `flashing lock` / `oem relock` / `reboot-edl` | `unknown command` |

**决定性变量**：

```
tokenversion        = 2     ← 网上所有强解教程针对 v1，v2 协议已换
anti                = 1     ← 防回滚启用
get_unlock_ability  = 空     ← AOSP 规范要求为 1 才允许
sys.oem_unlock_allowed = 1  ← 仅 Android 侧开关，bootloader 不读
```

**不可行原因（物理层面，不是还没试）**：

| 方案 | 阻塞点 |
|---|---|
| MTK BROM / mtkclient | proot 内 `libusb_init = -99`（实测），且需 PC |
| 9008 / EDL | 联发科无此模式 |
| amonet 等 BROM 漏洞 | README 只支持 3 款 Amazon Echo Show |
| 伪造 token | v2 协议 + 服务端私钥签名 |
| 改 bootloader | 签名保护，改一字节即砖 |

**AOSP 规范依据**：`source.android.com` 明确 `get_unlock_ability` 未设置时
设备 should deny `flashing unlock`。小米在上层加了服务端 token 校验。

**唯一可行路径**：`gauthamnair2005/Redmi13C5G_Air_Resources` 的解锁请求脚本
（README 承认不绕过资格，只自动抢整点），前提是 30 天小米账号 + 手机卡 + 社区活跃。
拿到资格后等 168 小时 → `fastboot oem unlock` → 刷 TWRP/ROM
（`suchit-7x/twrp_device_xiaomi_air` + `mt6835-devs/android_device_xiaomi_air`）。

**附带教训**：`fastboot getvar all` 输出数百行，连续调用会让 bootloader
卡死、手机自行重启。查变量必须逐个查。

---

## 待发布

（待汇总）
