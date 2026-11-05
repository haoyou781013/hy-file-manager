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

## 尚未发布

（空）
