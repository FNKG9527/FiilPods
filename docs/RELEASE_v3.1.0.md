# FiilPods v3.1.0 发布说明 —— 上架 LSPosed 官方模块仓库

> 上一篇：[`RELEASE_v3.0.0.md`](RELEASE_v3.0.0.md)（原生态接入收口，功能面的完整说明在那里）
>
> 版本：`versionCode 9` / `versionName 3.1.0` / 应用名 **FiilPods 3.1**
> 产物：`FiilPods-v3.1.0-release.apk`（4,543,608 B，SHA-256 `E9437CC3682F8152F080B46643174CDEF57278B211752CFABEC1ACCADD4E4FE0`）

---

## 1. 这一版只做了一件事：换包名

3.1.0 的**功能面与 3.0.0 完全一致**。没有新增 hook，没有改协议命令，没有动界面。
唯一的实质变化是：

```
applicationId   moe.chenxy.fiilpods  →  io.github.fnkg9527.fiilpods
```

之所以要到"换包名"这一步，是因为不换就进不了 LSPosed 的官方模块仓库。

## 2. 为什么必须换

LSPosed 的模块仓库（[Xposed-Modules-Repo](https://github.com/Xposed-Modules-Repo)）在
创建模块仓库时会校验 `applicationId`：

> `applicationId` 反推出来的域名必须归提交者所有。

校验方式是两种之一：

1. 在该域名的 DNS 上放一条 TXT 记录：`lsposed-modules-repo-verification=<GitHub 用户名>`；
2. 直接使用 `io.github.<GitHub 用户名>` 前缀。

原包名 `moe.chenxy.fiilpods` 反推的域名是 `chenxy.moe`。

- 该域名确实存在（Cloudflare 托管，NS 为 `duke.ns.cloudflare.com` / `desi.ns.cloudflare.com`）；
- 但它属于**上游 OppoPods 的作者**，本项目只是从那里 fork 改造而来；
- 实测该域 **没有任何 TXT 记录**，我们也没有该域的控制权，第 1 条路走不通。

于是有了本项目的一次真实拒绝：

| 工单 | 包名 | 结果 |
|---|---|---|
| [#2392](https://github.com/Xposed-Modules-Repo/submission/issues/2392) | `moe.chenxy.fiilpods` | 8 秒后被机器人关闭，打 `invalid` 标签：「It seems like your request has an invalid package name, please consider another package name (e.g. `io.github.fnkg9527.[appname]`).」 |
| [#2394](https://github.com/Xposed-Modules-Repo/submission/issues/2394) | `io.github.fnkg9527.fiilpods` | 约 30 秒后建库成功 |

值得记录的一点：`moe.chenxy.huaweipods`（#1465，2026-07）当时走的是"等待人工审核"，
一小时后通过；而 2026-10-30 的 #2361 已经改用 `io.github.mo_setian.hyperpods`。
**策略已经收紧，同前缀不再自动放行**，所以这一关绕不过去。

## 3. 哪些地方跟着改了

包名不是一个字段，是散在七八处的身份字符串。这一版逐处收口：

| 位置 | 原来 | 现在 |
|---|---|---|
| `app/build.gradle.kts` | `applicationId = "moe.chenxy.fiilpods"` | `io.github.fnkg9527.fiilpods` |
| `pods/FiilConfig.kt` `MODULE_PACKAGE` | 同上 | 同上 |
| `utils/PodImageLoader.kt` 同名常量 | 同上 | 同上 |
| `config/PodImagePrefs.kt` `AUTHORITY` | `…fiilpods.podimages` | `io.github.fnkg9527.fiilpods.podimages` |
| `AndroidManifest.xml` provider `authorities` | 同上 | 同上 |
| `AndroidManifest.xml` 的 `SHOW_PODS_UI` action | `moe.chenxy.fiilpods.action.…` | `io.github.fnkg9527.fiilpods.action.…` |
| `pods/FiilAction.kt` 14 个 action | 旧前缀 | 新前缀 |
| `pods/FiilRfcommController.kt` 三个同名 action | 旧前缀 | 新前缀 |
| `pods/OppoCompatBridge.kt` `setPackage(...)` | 硬编码字面量 | 改读 `FiilConfig.MODULE_PACKAGE` |
| 两个 `strings.xml` 的 `app_name` | `FiilPods 2.2 Fix` → `FiilPods 3.0` | **`FiilPods 3.1`** |

### 故意**没有**改的

`namespace` 与所有 Kotlin 源码包名仍然是 `moe.chenxy.fiilpods`。
`applicationId` 与 `namespace` 在 AGP 里是两个独立概念：前者是设备上的 App 身份，
后者是 R 类与源码的包路径。只改前者即可满足 LSPosed 的校验，改后者会牵动 73 个源文件的
`import` 与 `R` 引用，风险与收益完全不成比例。

因此下面这些"看起来像包名"的字符串**保持原样**，它们都是**类全名**而不是包名：

- `hook/SettingsHook.kt` `CLS_MODULE_MAIN = "moe.chenxy.fiilpods.MainActivity"`
- `hook/MiBluetoothToastHook.kt` `POPUP_ACTIVITY = "moe.chenxy.fiilpods.PopupActivity"`
- `ui/MainUI.kt` `launcherComponent()` 里的 `LauncherActivity`

它们与包名的配合方式是 `setClassName(FiilConfig.MODULE_PACKAGE, CLS)` /
`ComponentName(context.packageName, CLS)`——包名由调用方单独给出，类名只写类路径。

## 4. 为什么 `AUTHORITY` 必须一起换

`ContentProvider` 的 authority 在**全设备范围内唯一**。如果只改 `applicationId` 而 authority
仍为 `moe.chenxy.fiilpods.podimages`，那么 3.0.0 与 3.1.0 无法共存：

```
INSTALL_FAILED_CONFLICTING_PROVIDER
```

换掉之后两版可以并存，用户可以先装 3.1.0 验证，确认没问题再卸载旧版。

## 5. 新增的回归测试

`app/src/test/java/moe/chenxy/fiilpods/pods/ModulePackageTest.kt`（4 个用例）：

1. `MODULE_PACKAGE == BuildConfig.APPLICATION_ID`
2. `AUTHORITY == "${APPLICATION_ID}.podimages"`
3. `FiilAction` 里 14 个 action 全部以 `"$MODULE_PACKAGE.action."` 开头
4. `FiilRfcommController` 的三个 action 常量与 `FiilAction` 中对应常量相等

这四条是包名迁移的"防漏网"：以后再动身份字符串，只要漏改一处，测试立刻红。

跑通结果：**suites=6 / tests=68 / failures=0**（3.0.0 时是 64 项，本次 +4）。

## 6. 升级路径（与以往版本不同）

**3.1.0 无法覆盖安装 3.0.0 及更早版本**——`applicationId` 不同，系统视为两个 App。

| | 能不能覆盖安装 |
|---|---|
| 1.0.0 → 3.0.0 之间任意两版 | 可以（包名相同、签名相同） |
| 3.0.0 → 3.1.0 | **不可以**，需先卸载 |

干净升级步骤：

1. 记下旧版的设置（伪装型号、自定义耳机图等），卸载旧版会一并清掉；
2. 安装 `FiilPods-v3.1.0-release.apk`；
3. 在 LSPosed 里**启用**模块，作用域勾选四项：
   `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.milink.service`、`com.android.settings`；
4. **关开一次蓝牙**（或重启手机）——LSPosed 只在进程启动时注入；
5. 打开模块 App，把伪装型号等设置重新填一遍。

## 7. 模块仓库侧的内容

仓库：<https://github.com/Xposed-Modules-Repo/io.github.fnkg9527.fiilpods>

| 文件 | 内容 |
|---|---|
| `SUMMARY` | 一句话简介（仓库列表里显示的那句） |
| `SCOPE` | `["com.android.bluetooth","com.android.settings","com.milink.service","com.xiaomi.bluetooth"]` |
| `SOURCE_URL` | `https://github.com/FNKG9527/FiilPods` |
| `VERSION` | `9-3.1.0`（= release tag，格式 `<versionCode>-<versionName>`） |
| `README.md` / `README_EN.md` | 中英文说明 |
| `LICENSE` | GPL-3.0 |

release：tag `9-3.1.0`，name `3.1.0`，asset `FiilPods-3.1.0-release.apk`（4,543,608 B）。

> 仓库的 **description** 就是模块在 LSPosed 管理器里显示的名字，且**无法通过 API 设置**
> （维护者权限不够，GitHub 要求 admin）。必须在浏览器里手动填：
> <https://github.com/Xposed-Modules-Repo/io.github.fnkg9527.fiilpods/settings> → 填 `FiilPods`。
> 描述为空的仓库不会出现在模块列表里。

## 8. 回归验证清单

| # | 检查项 | 期望 |
|---|---|---|
| 1 | `aapt2 dump badging` | `package name='io.github.fnkg9527.fiilpods' versionCode='9' versionName='3.1.0'` |
| 2 | 应用名 | `FiilPods 3.1` |
| 3 | 合并后 manifest 的 provider authority | `io.github.fnkg9527.fiilpods.podimages` |
| 4 | `META-INF/xposed/java_init.list` | `moe.chenxy.fiilpods.hook.HookEntry`（类全名，不变） |
| 5 | 与 3.0.0 并装 | 可以同时安装，互不覆盖 |
| 6 | 4 个作用域全部生效 | 融合中心 / 详情页 / 通知栏 / 设置页均有响应 |
| 7 | 关开蓝牙后 | hook 生效，不出现「装了没反应」 |
| 8 | 模块自有广播 | 模块 App 与蓝牙进程之间状态同步正常（action 前缀已换） |
| 9 | 自定义耳机图 | 选择与显示正常（authority 已换，走新 provider） |
| 10 | 单元测试 | 68 项全绿 |
