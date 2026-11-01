# 第三方组件与借鉴来源许可清单

> 本项目基于 **GPL-3.0** 开源。下面列出所有**代码层面**依赖的第三方组件，
> 以及 UI / Hook 架构上**借鉴并移植**了代码的开源项目。
>
> 协议层的事实来源（`fiil-headphone-protocol` 等）不含代码，单列在文末「知识来源」一节。
>
> 最后核对：v3.0.0（2026-11-01）

---

## 一、代码依赖（Gradle 依赖，逐项列出）

| 组件 | 坐标 | 版本 | 许可 | 用途 |
|---|---|---|---|---|
| libxposed API | `io.github.libxposed:api` | 见 `gradle/libs.versions.toml` | **Apache-2.0** | Xposed 模块入口与 Hook API（**API 102**） |
| libxposed service | `io.github.libxposed:service` | 同上 | **Apache-2.0** | LSPosed remote prefs 注入 |
| Miuix | `top.yukonga.miuix.kmp:miuix` | 同上 | **Apache-2.0** | HyperOS 风格 Compose 基础组件 |
| Miuix Preference | `...:miuix-preference` | 同上 | **Apache-2.0** | 设置项组件（`SwitchPreference` 等） |
| Miuix Icons | `...:miuix-icons` | 同上 | **Apache-2.0** | 图标集 |
| Miuix Blur | `...:miuix-blur` | 同上 | **Apache-2.0** | 模糊背景 |
| Miuix Nav | `...:miuix-nav` | 同上 | **Apache-2.0** | 导航组件 |
| Kotlin Stdlib | `org.jetbrains.kotlin:kotlin-stdlib` | 同上 | **Apache-2.0** | 语言运行时 |
| Kotlinx Serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 同上 | **Apache-2.0** | `AppConfig` 的 JSON 持久化 |
| Kotlinx Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 同上 | **Apache-2.0** | 异步与线程调度 |
| AndroidX Core / Compose | `androidx.*` | 同上 | **Apache-2.0** | 基础框架与 UI |

> 精确版本号以 `gradle/libs.versions.toml` 为准（运行时读取，避免本文档与构建配置不同步）。

---

## 二、移植借鉴的开源项目（GPL-3.0 义务所在）

**这一节是重点。** 这些项目不是「看了看」，而是**有实质代码移植**，因此其 GPL-3.0 义务
沿用到本项目（本项目同样是 GPL-3.0，条款兼容）。

### 2.1 OppoPods —— 原型与主要借鉴对象

- 仓库：<https://github.com/1812z/OppoPods>（201★, Kotlin，无 SPDX 声明，按作者意愿为 GPL 系）
- 上游分支参考：<https://github.com/Leaf-lsgtky/OppoPods>

**移植范围：整个 UI 层与设置页骨架。**

| 移植内容 | 本仓库落点 |
|---|---|
| 三 Tab 底部导航、`MainTabsScaffold` 页面组织 | `ui/MainTabs.kt` |
| 详情页卡片布局、耳机控制项列表 | `ui/pages/PodDetailPage.kt` |
| 设置页分组结构 | `ui/pages/SettingsPage.kt` |
| `PopupActivity` 弹窗形态 | `PopupActivity.kt` |
| `ConfigManager` 的 `@Serializable AppConfig` + 扁平镜像键 | `config/ConfigManager.kt` |
| `OppoPodsAction` 广播契约 | `utils/miuiStrongToast/data/OppoPodsAction.kt`（**类名保留原名**） |
| `utils/miuiStrongToast` 超级岛 / 焦点通知封装 | `utils/miuiStrongToast/` |
| `PodImagePrefs` 自定义耳机图片体系 | `config/PodImagePrefs.kt`、`utils/PodImageLoader.kt` |
| `FocusIslandUtil`（超级岛 focus-api V3） | `utils/FocusIslandUtil.kt` |
| 崩溃兜底页的形态 | `MainActivity.kt`、`CrashHandler.kt` |

**包名说明**：本项目包名 `moe.chenxy.fiilpods` 与 OppoPods 的 `moe.chenxy.oppopods`
只差一个词，因为整个工程是从它 fork 后改造的。**这个事实不隐藏。**

架构拆解见 `docs/ARCH_OPPOPODS.md`（逐节规格 + 行号）。

### 2.2 HuaweiPods —— Hook 基础设施主模板

- 仓库：<https://github.com/Nshpiter/HuaweiPods>（36★, Kotlin, **GPL-3.0**）

| 移植内容 | 本仓库落点 |
|---|---|
| `HookContext` 极简版（去掉热重载 / 关闭代际 / 稳定 HookId） | `hook/HookContext.kt` |
| `META-INF/xposed/java_init.list` 入口声明方式 | `app/src/main/resources/META-INF/xposed/` |
| **LSPosed remote prefs 注入时机**（跨进程读模块设置） | `hook/HookEntry.kt` 的 `loadHook` |
| 「一个 APK 两种身份」整体结构 | 全局 |
| `Application.attach` 作为通知注册主通道的思路 | `hook/MiBluetoothToastHook.kt` |

架构规格见 `docs/ARCH_HUAWEIPODS.md`。

### 2.3 HyperPods —— 组织方式参考

- 仓库：<https://github.com/Art-Chen/HyperPods>（103★, Kotlin, **GPL-3.0**）

借鉴超级岛多形态切换与通知渠道命名策略。**无成段代码移植**，属设计参考。

### 2.4 OPPOLeaConnect —— 判定依据（无移植）

- 仓库：<https://github.com/Leaf-lsgtky/OPPOLeaConnect>（Java, **GPL-3.0**）

v2.2.1 **删除**了从 OppoPods 连带抄来的「OPPO LE Audio 设置」页。判定依据之一是该项目
只对 OPPO 私有 AT 族有效，与 FIIL 无关。**本项目不包含它的任何代码。**

---

## 三、知识来源（不含代码）

| 来源 | 许可 | 用途 |
|---|---|---|
| [fiil-headphone-protocol](https://github.com/FNKG9527/fiil-headphone-protocol)（同一作者） | **CC-BY-SA-4.0** | 协议帧格式、命令表、握手流程的事实来源 |
| FIIL+ 3.5.2 官方 App 静态逆向 | 不适用（分析成果，非代码分发） | 190 条 GAIA 命令表、Mate cid/cmdId、`setUnlock` 时机 |
| MiLink（`com.milink.service`）18.2.0.12 | 不适用（仅分析） | 融合中心 / 超级岛系统侧实现 |
| FIIL+ 官方产品图（30 张 `fiil_earphones_*.png`） | 版权归 FIIL | 转为 webp 后随模块分发，仅用于识别自家设备 |

> **仓库不含任何 APK 或反编译产物。** 逆向结论以「文件:行号 + 脱敏日志」的形式记录。

---

## 四、合规声明

1. 本项目整体以 **GPL-3.0** 分发（见 `LICENSE`），与上述 GPL 系上游兼容。
2. 所有从 GPL-3.0 项目移植的代码，其版权仍归原作者；本项目不主张这些部分的著作权。
3. Apache-2.0 组件（libxposed / Miuix / AndroidX / Kotlin）按 Apache-2.0 条款使用，
   其 NOTICE 与许可文本可到各自仓库获取。
4. 若任何归属有误或遗漏，请提 Issue，会立即更正。

---

## 五、相关项目（同一作者，独立仓库）

这套工具链不只有本模块：

| 仓库 | 是什么 | 许可 |
|---|---|---|
| [FiilPods](https://github.com/FNKG9527/FiilPods) | **本项目**：HyperOS 系统级 FIIL 耳机控制模块 | GPL-3.0 |
| [fiil-headphone-protocol](https://github.com/FNKG9527/fiil-headphone-protocol) | 协议逆向笔记（GAIA / Mate / 豆包三套命令空间） | CC-BY-SA-4.0 |
| [fiil-pods-pc](https://github.com/FNKG9527/fiil-pods-pc) | Windows PC 控制台（BLE GATT + tkinter） | GPL-3.0 |
| [fiil-ldac-unlock](https://github.com/FNKG9527/fiil-ldac-unlock) | LDAC 离线激活工具集 | GPL-3.0 |
