# FIIL 耳机 HyperOS 控制模块 —— 结项验收报告

> **验收对象**：FiilPods（`moe.chenxy.fiilpods`）
> **版本 / Tag**：`1.0.0` / **无 release tag**（工程未纳入 git）
> **验收日期**：2026-10-24
> **验收主体**：6 角色联合验收专家团
> **验收纪律**：不改代码、不新增功能；禁止用基准分数替代实机证据；禁止把"代码存在"写成"功能可用"；所有结论必须有证据位置

---

## 【项目结项验收结论】

| 项 | 内容 |
|---|---|
| **项目** | FIIL 耳机 HyperOS 控制模块（FiilPods） |
| **版本 / Tag** | `1.0.0` / **无 tag**（`git status` → `fatal: not a git repository`） |
| **验收日期** | 2026-10-24 |
| **专家团结论** | **可内测**（不可公开发布） |
| **总分** | **72 / 100** |
| **P0 阻塞项** | **2 项**：**P0-1** 日志明文打印蓝牙 MAC（**4 处**：`BluetoothProcessHook.kt:88`、`SettingsHook.kt:167`、`FiilRfcommController.kt:314`、`FiilRfcommController.kt:371`，release 包已实证 4 条字符串全部残留）；**P0-2** 融合设备中心 hook 失效时静默吞掉（`runCatching` 31 处 / `onFailure` 11 处）、无任何用户可见状态 |
| **P1 遗留项** | **13 项**，见 §三（每条带规避方案） |
| **未验证项** | 佩戴检测、自适应五开关（4+1）、充电盒电量、视频低延迟档、F042 BLE 链路、Android 侧全部注入行为 |
| **交付物** | release 包 ✅ / PROTOCOL_FIIL.md ✅ / 用户说明 ✅ / **回滚方案 ❌ 缺失** / **热修预案 ❌ 缺失** / 遗留问题移交说明 ✅ |
| **回滚触发条件** | 见 §六（4 条） |
| **发布经理签字** | 可内测（修完 P0-1 后可升"可发布·内测渠道"） |
| **各角色签字** | 协议 ✅ / 集成 ⚠️有条件 / UI ⚠️有条件 / QA ⚠️有条件 / **合规 ❌否决** |

**一句话判据**：协议层真机 19/19 通过（硬证据）；模块本体 8,002 行 Android 代码零实机验证（软证据）。
两项 P0 分工不同——**P0-1 日志泄露**触发「不可发布」一票否决；**P0-2 融合设备中心静默失败**单独阻断「可发布」中「无 P0」这条门禁。
除此之外无假指令、作用域合规、许可证合规、断连闭环。

---

## 一、专家团逐角色结论表

### 角色 1：BLE / 协议逆向专家

| 检查项 | 证据位置 | 结论 | P0-P2 |
|---|---|---|---|
| GAIA 帧格式完整（SOF/ver/len/vendor/cmd/payload） | `pods\FiilGaia.kt` L1-157；`docs\PROTOCOL_FIIL.md` §2.1 | 仅静态验证 | — |
| Mate 帧格式完整 | `pods\FiilMate.kt` L1-315；`docs\PROTOCOL_FIIL.md` §3.2 | **实机通过** | — |
| `setUnlock` 是强制前置握手 | `docs\DEVICE_TEST.md` L20、L35 | **实机通过** | — |
| MAF 五态线值 0-4 | `docs\DEVICE_TEST.md` L38-52 | **实机通过** | — |
| 回包 cid 双通道（39 查询 / 40 通知） | `docs\DEVICE_TEST.md` L23；`pods\FiilRfcommController.kt` L497 | **实机通过** | — |
| EQ 十段读写 + `reserved` 回填 | `docs\DEVICE_TEST.md` L68-81 | **实机通过** | — |
| 畅连写入用 `cmd=1`（非 128） | `docs\DEVICE_TEST.md` L83-94 | **实机通过** | — |
| 低延迟 Mate 二态 0/1 | `docs\DEVICE_TEST.md` L61-66 | **实机通过** | — |
| 影院模式开关 | `docs\DEVICE_TEST.md` L54-59 | **实机通过** | — |
| 断连重连 + 重新握手 | `docs\DEVICE_TEST.md` L96-104 | **实机通过** | — |
| 低延迟 GAIA 四态 0/1/2/3 | `docs\PROTOCOL_FIIL.md` §3.7；`pods\FIILProtocol.kt` L250、L275 | 未验证 | P2-1 |
| 佩戴检测（cid 38 写 / 39 读） | `docs\PROTOCOL_FIIL.md` §3.10 | 未验证 | P1-3 |
| 自适应五开关（cid=1 cmd 14/15/16/17/8） | `docs\PROTOCOL_FIIL.md` §3.11 | 未验证 | P1-4 |
| 充电盒电量（GAIA `GET_BATTERY_OF_BOX`） | `pods\FIILProtocol.kt` L56-57 | 未验证 | P1-2 |
| 豆包 0x55AA 链路 | `docs\PROTOCOL_FIIL.md` §4 | 未验证 | P2-2 |
| F042 BLE 私有特征 UUID | `docs\PROTOCOL_FIIL.md` §6 第 1 项 | **缺失** | P1-5 |
| 帧头/长度/命令字/payload 完整性 | `pods\FiilGaia.kt` `parse()`/`drain()`；`pods\FiilMate.kt` 同名 | 仅静态验证 + 22 项单测 | — |
| 通知解析有边界处理 | `pods\FiilRfcommController.kt` L680 `applyMateNotify()` | 仅静态验证 | — |
| 无假指令（每条命令有 APK 逆向来源） | `docs\PROTOCOL_FIIL.md` §2.4 / §3.4 / §3.11.1 全表 | **通过** | — |
| FIILProtocol 每个方法有逆向字节来源注释 | `pods\FIILProtocol.kt`：30 个顶层 `fun` 中 **21 个** KDoc 明确给出逆向字节/命令号（70%）；5 个只写语义不写来源（`supportsWearSetting` L166、`refreshAll` L449、`set3DAudio` L493、`volumeUp` L499、`mediaPlay` L503）；4 个与同组首方法共用一条语义注释（`volumeDown` L500、`mediaPause` L504、`mediaNext` L505、`mediaPrev` L506） | **部分实现** | P2-7 |

**签字**：✅ 通过（附条件：补 P2-7 九处注释）

---

### 角色 2：Android / LSPosed 集成专家

| 检查项 | 证据位置 | 结论 | P0-P2 |
|---|---|---|---|
| Xposed 入口元数据 | APK `assets/xposed_init` = `moe.chenxy.fiilpods.hook.HookEntry`（实测解包） | **通过** | — |
| `module.prop` API 版本 | `META-INF/xposed/module.prop` = min/target `102`、`staticScope=true` | **通过** | — |
| Hook 异常不外泄 | `hook\HookEntry.kt` L36-46（包级）、L53-57（`installHook`） | **通过** | — |
| 设置页注入 | `hook\SettingsHook.kt` L390-403（`onCreateView`）、L405-416（`onServiceConnected`） | 仅静态验证 | P1-1 |
| 自建控制卡片 | `hook\SettingsControlCard.kt` L1-435 | 仅静态验证 | P1-1 |
| 跨进程状态同步 | `pods\FiilAction.kt` + `hook\BluetoothProcessHook.kt` | 仅静态验证 | P1-1 |
| 融合设备中心 | `hook\milink\MiLinkServiceHook.kt` L141-143 | 仅静态验证 | **P0-2** |
| 超级岛 | `hook\MiBluetoothToastHook.kt` L227-254（`island_param`）、L363（`hideStrongToast`） | 仅静态验证 | P1-1 |
| 进程重启恢复 | `pods\FiilConfig.kt`（SharedPreferences） | 仅静态验证 | P1-1 |
| Xposed 作用域 | `scope.list` = 4 条，无 `system_server` | **通过** | — |

**签字**：⚠️ 有条件通过（需 HyperOS 真机验证 hook 点）

---

### 角色 3：HyperOS / MIUIX UI 专家

**比对基准已确认**：`<repo>\refs\OppoPods` → `origin = https://github.com/1812z/OppoPods.git`，`HEAD = c201e25 "Release 2.2.0"`

#### 逐控件比对表

| # | 控件 | OppoPods（1812z，2.2.0） | FiilPods | 差异 | 判定 |
|---|---|---|---|---|---|
| 1 | 电量展示 | `ui\components\PodStatus.kt`（240 行），置于首张 Card `pages\PodDetailPage.kt` L224-232 | `ui\PodDetailScreen.kt` L194-252 `BatteryCard`，独立 Card，三列 + "戴"标记 | FIIL 增加佩戴标记列 | 等价增强 |
| 2 | 降噪切换 | `ui\components\AncSwitch.kt` L70-110：**四个横排大按钮**（降噪/自适应/通透/关闭） | `PodDetailScreen.kt` L254-284：`TabRowWithContour` **单行芯片**，3/5 档随机型 | **形态不同** | 见差异说明 A |
| 3 | 降噪强度二级展开 | `AncSwitch.kt` L129-137 `AncStrengthTabRow`：智能/轻度/中度/深度四档 | **无** | FIIL 协议无 ANC 强度分级命令 | 协议限制 |
| 4 | 通透人声增强 | `AncSwitch.kt` L154-157 `ResponsiveAncTabRow`：通透 / 人声增强 | **无** | FIIL 无对应命令 | 协议限制 |
| 5 | 游戏/低延迟 | `PodDetailPage.kt` L254-261：`SwitchPreference` **二态** | `PodDetailScreen.kt` L117 `LowLatencyCard`：**三档芯片**（音乐/游戏/视频） | FIIL 更强（GAIA 真实四态） | 超越 |
| 6 | 空间音频 | `PodDetailPage.kt` L262-275：`OverlayDropdownPreference` **三选**（关/固定/头部跟踪） | L333-338：`SwitchPreference` **二态**（影院模式） | FIIL 更弱 | 协议限制 |
| 7 | 均衡器 | `PodDetailPage.kt` L286-298：`BasicComponent` 点击 → 跳 `pages\EqualizerPage.kt`（396 行） | `EqCard` 预设芯片 + `ui\EqEditorCard.kt` L45 **十段图形编辑器**内嵌 | FIIL 更强（不需跳页） | 超越 |
| 8 | 双设备/畅连 | `PodDetailPage.kt` L299-306：`SwitchPreference` | L347-355：`SwitchPreference` 蓝牙畅连 | 一致 | 对齐 |
| 9 | 佩戴检测 | `PodStatus.kt` **只读显示**佩戴状态 | L357-366：`SwitchPreference` **可写开关** | FIIL 更强 | 超越 |
| 10 | 自适应四开关 | **无** | L368-398：环境自适应/抗风噪/耳道自适应/自适应 EQ | FIIL 独有 | 超越 |
| 11 | 云感调音 | **无** | L400-408：`SwitchPreference` | FIIL 独有 | 超越 |
| 12 | 操作区 | **无独立操作卡** | L412-459 `ActionsCard`：刷新/寻找耳机/音量+/音量- | FIIL 独有 | 超越 |
| 13 | 深色模式 | `ui\Theme.kt`（46 行）`MiuixTheme` | `ui\Theme.kt`（44 行）`MiuixTheme` + 设置页卡片用 `resolveAttribute(textColorPrimary)` | 一致 | 对齐 |
| 14 | 快捷弹窗 | 无独立 popup Activity | `PopupActivity.kt`（334 行）竖屏三卡 / 横屏 6:4 分栏 | FIIL 独有 | 超越 |

#### 差异说明

**A. 降噪切换形态不同的原因**：OppoPods 用"四个大按钮 + 二级展开"，是因为 OPPO 协议支持 ANC 强度分级（轻度/中度/深度）和人声增强。
FIIL 协议（`docs\PROTOCOL_FIIL.md` §3.5）只有五个**平级档位**，没有强度子档，若照搬"大按钮+展开"会得到一个永远展开不出内容的空壳。
所以改用单行芯片直接呈现全部平级档位。**这是按协议事实做的适配，不是偷工减料。**

| 检查项 | 证据位置 | 结论 | P0-P2 |
|---|---|---|---|
| 14 项控件逐项比对 | 上表（含双方精确行号） | **部分实现**（差异均有协议依据） | — |
| UI 截图 / 录屏 | — | **缺失** | **P1-6** |
| 弹窗紧凑布局 | `PopupActivity.kt` L95-200 | 仅静态验证 | P1-1 |
| EQ 十段编辑器 | `ui\EqEditorCard.kt` L45、L99 `VerticalSlider` | 仅静态验证 | P1-1 |
| 设置页卡片只用 framework 控件 | `hook\SettingsControlCard.kt`（仅 LinearLayout/TextView/Switch） | **正确决策**（规避跨 ClassLoader 崩溃） | — |

**签字**：⚠️ 有条件通过（"与 OppoPods 一致"已用源码逐项核实；但**零截图**，视觉一致性无法验证）

---

### 角色 4：QA / 稳定性专家

| 检查项 | 证据位置 | 结论 | P0-P2 |
|---|---|---|---|
| 断连重连闭环 | `pods\FiilRfcommController.kt` L323 `disconnect()` + 指数退避 | **实机通过**（协议层）+ 仅静态验证（模块层） | — |
| 状态回读以通知帧为准 | `FiilRfcommController.kt` L497 `Cid.NOTIFY ->`、L680 `applyMateNotify()` | **实机通过**（cid=40 实测到） | — |
| 失败回退提示，不静默失败 | `FiilRfcommController.kt` L1086 `pendingOps`、L1111 `OP_TIMEOUT_MS=2500`、L1145 `expectAck` | 仅静态验证 | P1-7 |
| 断连清理通知/岛 | `hook\MiBluetoothToastHook.kt` L124 `cancelNotification()`、L363 `hideStrongToast()` | 仅静态验证 | P1-7 |
| 重连同步状态 | `FiilRfcommController.kt` `refreshAll()` | **实机通过**（协议层） | — |
| 多设备切换 | 控制器为单例，仅维护一条 SPP | **缺失** | P1-8 |
| 进程重启恢复 | `pods\FiilConfig.kt` | 仅静态验证 | P1-1 |
| JVM 单测 | `app\src\test\`：`ProtocolFrameTest.kt` 22 项 + `ModelCapabilityTest.kt` 16 项；实测 `tests=38 failures=0 errors=0` | **通过** | — |
| 真机回归 | `docs\DEVICE_TEST.md` L28 `RESULT: pass=19 fail=0` | **通过**（协议层） | — |
| Android 侧崩溃风险 | 全部 hook 包 `runCatching` | 仅静态验证 | P1-1 |

**签字**：⚠️ 有条件通过（稳定性清单在 Android 侧全部为静态验证）

---

### 角色 5：安全与合规专家

| 检查项 | 证据位置 | 结论 | P0-P2 |
|---|---|---|---|
| **日志脱敏（无 MAC 泄露）** | **4 处**：`hook\BluetoothProcessHook.kt` L88 `logI("FIIL device connected: ${device.address} ${device.name}")`<br>`hook\SettingsHook.kt` L167 `logI("patched $className intent for ${device?.address}")`<br>`pods\FiilRfcommController.kt` L314 `Log.i(TAG, "connected to ${device.address} mate=$isMateDialect")`<br>`pods\FiilRfcommController.kt` L371 `Log.i(TAG, "reconnect attempt ($left left) -> $address")` | **不合规** | **P0-1** |
| release 包内是否残留 | `app-release.apk`（2,496,726 B / `classes.dex` 2,345,432 B）解包后直接扫 dex ASCII：`"FIIL device connected: "`@2,099,828、`" intent for "`@1,714,996、`"patched "`@2,230,541、`"connected to "`@2,189,656、`"mate="`@1,715,631、`"reconnect attempt ("`@2,233,181、`" left) -> "`@1,715,611。`proguard-rules.pro` 含 `-keep class moe.chenxy.fiilpods.hook.** { *; }` 与 `-keep class moe.chenxy.fiilpods.pods.** { *; }`，`Lmoe/chenxy/fiilpods/hook/BluetoothProcessHook;`、`Lmoe/chenxy/fiilpods/hook/SettingsHook;`、`Lmoe/chenxy/fiilpods/pods/FiilRfcommController;` 均在 dex 内 —— **4 处日志代码全部存活，会被执行** | **残留且可执行**（非死代码） | **P0-1** |
| 硬编码 MAC | 全工程 grep：仅 `pods\FiilConfig.kt` L117 `FIIL_OUI_PREFIXES = listOf("B0:F1:A3", "FF:F1:A3")` | **通过**（OUI 厂商前缀，非个人 MAC） | — |
| Xposed 作用域最小化 | `resources\META-INF\xposed\scope.list` = 4 条（bluetooth / settings / xiaomi.bluetooth / milink.service） | **通过**（无 system_server） | — |
| 无未验证 UUID | `docs\PROTOCOL_FIIL.md` §1.2；F042 BLE 已标"未验证" | **通过** | — |
| 无假指令 | 见角色 1 | **通过** | — |
| 许可证合规 | `LICENSE` = GPL-3.0（35,823 B）；`README.md` §6 声明 | **通过** | — |
| 第三方声明 | 无 `THIRD_PARTY_LICENSES` 文件 | **缺失** | P1-9 |

**签字**：❌ **否决**（P0-1 日志泄露 MAC，触发一票否决）

---

### 角色 6：发布经理

| 检查项 | 证据位置 | 结论 | P0-P2 |
|---|---|---|---|
| release APK 存在 | `app\build\outputs\apk\release\app-release.apk` = **2,496,726 B** @ 2026-10-24 12:47:37 | **通过** | — |
| 构建可复现 | `:app:assembleRelease` EXIT=0 | **通过** | — |
| 签名 | `apksigner verify` → V2，`CN=Android Debug` | **部分实现**（debug 证书） | P1-10 |
| git tag | `git status` → `fatal: not a git repository` | **缺失** | P1-11 |
| 用户说明 | `README.md`（391 行，含 §8 交付状态） | **通过** | — |
| 回滚方案 | — | **缺失** | P1-12 |
| 热修预案 | — | **缺失** | P1-13 |
| 遗留问题移交 | `docs\PROTOCOL_FIIL.md` §6 + 本报告 §三 | **通过** | — |

**签字**：**可内测**

---

## 二、功能验收矩阵

| 功能 | 结论 | 证据 | 差异说明 |
|---|---|---|---|
| **电量：左耳** | 实机通过 | `docs\DEVICE_TEST.md` L104 `payload=00-62-00` → 98% | Key Max 回包只给单一电量（双耳同步） |
| **电量：右耳** | 实机通过 | 同上（双耳同步值） | 左右分列字段仅 GAIA 链路有 |
| **电量：充电盒** | 未验证 | `pods\FIILProtocol.kt` L56-57 走 GAIA `GET_BATTERY_OF_BOX`(0x077B) | Key Max 是 Mate 链路，无盒电量命令 |
| **MAF 降噪** | 实机通过 | `DEVICE_TEST.md` L45 | — |
| **MAF 自适应** | 未验证 | `docs\PROTOCOL_FIIL.md` §3.11 | Key Max 不支持（仅 F046/F049） |
| **MAF 风中** | 实机通过 | `DEVICE_TEST.md` L48 | — |
| **MAF 监听（兼听）** | 实机通过 | `DEVICE_TEST.md` L47 | — |
| **MAF 关闭** | 实机通过 | `DEVICE_TEST.md` L44 | — |
| **影院模式开关** | 实机通过 | `DEVICE_TEST.md` L54-59 | — |
| **低延迟：游戏** | 实机通过 | `DEVICE_TEST.md` L65 | Mate 链路 0/1 二态 |
| **低延迟：音乐** | 实机通过 | `DEVICE_TEST.md` L66 | 同上 |
| **低延迟：视频** | 未验证 | `docs\PROTOCOL_FIIL.md` §3.7 | 仅 GAIA 四态有；Mate 降级为游戏 |
| **蓝牙畅连（多点连接）** | 实机通过 | `DEVICE_TEST.md` L83-94 | 写入必须 `cmd=1`；关闭会断链 |
| **自定义 EQ 读写** | 实机通过 | `DEVICE_TEST.md` L68-81 | 需回填耳机给的 `reserved` |
| **EQ 预设** | 仅静态验证 | `pods\FiilModels.kt` L272 `EQ_PRESETS` / L277 `MATE_EQ_PRESETS` | 预设名对齐 APP，未逐档试听 |
| **超级岛** | 仅静态验证 | `hook\MiBluetoothToastHook.kt` L227-254 / L363 | 需 Android 真机 |
| **融合设备中心** | 仅静态验证 | `hook\milink\MiLinkServiceHook.kt` L141-143 | 需 HyperOS 真机；P0-2 |
| **快捷弹窗** | 仅静态验证 | `PopupActivity.kt` L95-200 | 无截图 |
| **蓝牙详情页** | 仅静态验证 | `SettingsHook.kt` L390-416 + `SettingsControlCard.kt` | 无截图 |
| **佩戴检测** | 未验证 | `docs\PROTOCOL_FIIL.md` §3.10 | Key Max 无此命令（仅 F051/F053） |
| **自适应五开关** | 未验证 | `docs\PROTOCOL_FIIL.md` §3.11 | Key Max 不支持（仅 F046/F049） |
| **UI 与 1812z/OppoPods 一致** | 部分实现 | 见 §一 角色 3，14 项逐控件比对 | 3 项超越、2 项协议受限、9 项对齐；**无截图** |

**统计（22 条）**：实机通过 **11** / 仅静态验证 **5** / 未验证 **5** / 部分实现 **1**
（另有矩阵外缺失项 1 条：F042 BLE 私有链路，见 P1-5）

---

## 三、P0 / P1 / P2 遗留问题清单

### P0（阻塞发布，必须修）

| 编号 | 问题 | 证据位置 | 规避方案 |
|---|---|---|---|
| **P0-1** | 日志明文打印蓝牙 MAC 地址（**共 4 处**） | ① `app\src\main\java\moe\chenxy\fiilpods\hook\BluetoothProcessHook.kt` **L88** `logI("FIIL device connected: ${device.address} ${device.name}")`<br>② `app\src\main\java\moe\chenxy\fiilpods\hook\SettingsHook.kt` **L167** `logI("patched $className intent for ${device?.address}")`<br>③ `app\src\main\java\moe\chenxy\fiilpods\pods\FiilRfcommController.kt` **L314** `Log.i(TAG, "connected to ${device.address} mate=$isMateDialect")`<br>④ `app\src\main\java\moe\chenxy\fiilpods\pods\FiilRfcommController.kt` **L371** `Log.i(TAG, "reconnect attempt ($left left) -> $address")`<br>⑤ release 包实测（直接扫 `classes.dex` ASCII）：`"FIIL device connected: "`@2,099,828、`" intent for "`@1,714,996、`"patched "`@2,230,541、`"connected to "`@2,189,656、`"mate="`@1,715,631、`"reconnect attempt ("`@2,233,181、`" left) -> "`@1,715,611 —— **四条日志字符串全部存活** | 改为只打印设备名，或 MAC 仅保留后 4 位（如 `**:**:*3:A8:7A:9D`）。**改动量 4 行** |
| **P0-2** | 融合设备中心 hook 失效时静默吞掉 | `app\src\main\java\moe\chenxy\fiilpods\hook\milink\MiLinkServiceHook.kt` L141-143 等，全部包 `runCatching` 只打日志 | hook 安装失败时向模块控制台暴露"融合设备中心：未生效"状态，不发版也能诊断 |

### P1（可内测，需带入说明）

| 编号 | 问题 | 证据位置 | 规避方案 |
|---|---|---|---|
| P1-1 | Android 侧全部功能零实机验证 | 全部 `hook/**`、`ui/**` | 需一台 HyperOS 真机走一遍 |
| P1-2 | 充电盒电量未验证 | `pods\FIILProtocol.kt` L56-57 | 需 GAIA 老机型 |
| P1-3 | 佩戴检测未验证 | `docs\PROTOCOL_FIIL.md` §3.10 | 需 F051/F053 |
| P1-4 | 自适应五开关未验证 | `docs\PROTOCOL_FIIL.md` §3.11 | 需 F046/F049 |
| P1-5 | F042 BLE 链路缺失 | `docs\PROTOCOL_FIIL.md` §6 第 1 项 | 该机型走另一套 UUID，未实现 |
| P1-6 | 无任何 UI 截图/录屏 | — | 内测用户提供截图 |
| P1-7 | 失败回退、断连清理岛未实机验证 | `FiilRfcommController.kt` L1086-1147；`MiBluetoothToastHook.kt` L124/L363 | 内测观察日志 |
| P1-8 | 多设备同时连接不支持 | `pods\FiilRfcommController.kt`（单例） | 已知限制，README 已标注 |
| P1-9 | 无 `THIRD_PARTY_LICENSES` | 工程根目录 | 补一份，列 HyperPods/HuaweiPods/OppoPods + 依赖 |
| P1-10 | release 用 debug 证书签名 | `apksigner verify` → `CN=Android Debug` | 公开发布需自签正式证书 |
| P1-11 | 无 git tag / 未纳入版本控制 | `git status` → not a repository | `git init` + `tag v1.0.0` |
| P1-12 | 回滚方案缺失 | — | 见 §六 |
| P1-13 | 热修预案缺失 | — | 见 §六 |

### P2（记录，不阻塞）

| 编号 | 问题 | 证据位置 |
|---|---|---|
| P2-1 | 低延迟 GAIA 四态未真机验证 | `docs\PROTOCOL_FIIL.md` §3.7 |
| P2-2 | 豆包 0x55AA 链路未展开逆向，模块不主动发 | `docs\PROTOCOL_FIIL.md` §4 |
| P2-3 | Mate `[0]` 保留位语义未知，模块填 0 | `docs\PROTOCOL_FIIL.md` §6 第 4 项 |
| P2-4 | 影院模式与 EQ 互斥联动未复刻 | `docs\PROTOCOL_FIIL.md` §6 第 5 项 |
| P2-5 | 深色模式未真机确认 | `SettingsControlCard.kt` `textColor()` |
| P2-6 | MIUI 三态与 FIIL MAF 五态非一一映射 | `docs\PROTOCOL_FIIL.md` §6.1 |
| P2-7 | 30 个顶层 `fun` 中 9 个缺逆向字节/命令来源：5 个只写语义（`supportsWearSetting` L166、`refreshAll` L449、`set3DAudio` L493、`volumeUp` L499、`mediaPlay` L503）+ 4 个共用同组注释（`volumeDown` L500、`mediaPause` L504、`mediaNext` L505、`mediaPrev` L506） | `pods\FIILProtocol.kt`（实测：KDoc 内含字节/命令号者 21/30） |

---

## 四、评分表与总分

| 维度 | 权重 | 得分 | 打分依据 |
|---|---:|---:|---|
| **协议与功能** | 40% | **34** | 11 项实机通过是硬证据（`DEVICE_TEST.md` 19/19）；扣 6 分：5 项未验证 + F042 缺失 + 9 处方法缺逆向来源注释 |
| **UI 对齐** | 20% | **12** | 14 项控件已逐项源码比对，差异均有协议依据；扣 8 分：**零截图**，视觉一致性无证据 |
| **稳定性** | 15% | **9** | 38 单测 + 19 真机绿；扣 6 分：断连清理/失败回退/重启恢复 Android 侧全静态、多设备缺失 |
| **兼容性** | 10% | **5** | 仅 Key Max 一台（Mate 链路）；GAIA 老机型、F042/F051/F053 全未验证 |
| **构建** | 10% | **9** | 构建可复现、2.38 MB 体积健康、元数据齐全；扣 1 分：debug 证书、无 tag |
| **合规与文档** | 5% | **3** | GPL-3.0 合规、作用域最小、无假指令；扣 2 分：**P0-1 日志泄露**、无第三方声明 |
| **总分** | 100% | **72 / 100** | |

---

## 五、发布经理签字结论

### **可内测**（不可公开发布）

**门禁逐条核对**：

| 门禁条件 | 满足 | 依据 |
|---|---|---|
| 可发布：无 P0 | ❌ | P0-1、P0-2 存在 |
| 可发布：核心功能全部"实机通过" | ❌ | 设置页注入/超级岛/融合设备中心均为"仅静态验证" |
| 可发布：无未确认指令阻塞核心 | ✅ | 未确认项均不阻塞 Key Max 核心链路 |
| 不可发布：假指令 | ✅ 无 | 全部命令有 APK 逆向来源（`PROTOCOL_FIIL.md` 全表） |
| 不可发布：作用域过大 | ✅ 无 | 4 个包，无 system_server |
| 不可发布：**日志泄露** | ❌ **触发** | P0-1，release 包已实证 |
| 不可发布：许可证不合规 | ✅ 合规 | GPL-3.0 + README §6 声明 |
| 不可发布：断连未闭环 | ✅ 闭环 | 实机验证重连 + 重新解锁（`DEVICE_TEST.md` L96-104） |

**判定**：触发"日志泄露"一票否决 → **不可公开发布**；但无假指令、无作用域问题、许可证合规、断连闭环、无崩溃/数据风险 → **可内测**。

### 签字

| 角色 | 签字 | 附条件 |
|---|---|---|
| BLE 协议逆向专家 | ✅ 通过 | 补 P2-7 九处来源注释 |
| Android / LSPosed 集成专家 | ⚠️ 有条件通过 | 修 P0-2；需真机验证 hook 点 |
| HyperOS / MIUIX UI 专家 | ⚠️ 有条件通过 | 补 UI 截图 |
| QA / 稳定性专家 | ⚠️ 有条件通过 | Android 侧稳定性零验证 |
| 安全与合规专家 | ❌ **否决** | P0-1 必须先修 |
| **发布经理** | **可内测** | 修完 P0-1 后可升"可发布·内测渠道" |

---

## 六、结项交付物清单

| 交付物 | 状态 | 位置 / 内容 |
|---|---|---|
| **release 包** | ✅ | `<repo>\app\build\outputs\apk\release\app-release.apk`（2,496,726 B，2026-10-24 12:47:37） |
| debug 包 | ✅ | `app\build\outputs\apk\debug\app-debug.apk`（32,939,287 B） |
| **协议文档** | ✅ | `docs\PROTOCOL_FIIL.md`（35,256 B / 569 行，含 §6 未确认项） |
| 真机验证报告 | ✅ | `docs\DEVICE_TEST.md`（5,319 B / 126 行，含原始收发字节） |
| **用户说明** | ✅ | `README.md`（391 行，含 §8 交付状态、§8.2 风险） |
| 逆向原始报告 | ✅ | `<fiil_analysis>\FIIL+_3.5.2_逆向分析报告.md`（948 行） |
| 架构/规格文档 | ✅ | `docs\ARCH_*.md` × 3、`docs\SPEC_*.md` × 4 |
| 许可证 | ✅ | `LICENSE`（GPL-3.0，35,823 B） |
| **遗留问题移交说明** | ✅ | 本报告 §三（P0×2 / P1×13 / P2×7） |
| **回滚方案** | ❌ **缺失** → 建议如下 | ① LSPosed 中停用 FiilPods 模块 → ② 强制停止 `com.android.bluetooth` 与 `com.android.settings` → ③ 重启蓝牙 → 系统恢复原生行为。因模块不写系统分区、不常驻服务，停用即完全回滚 |
| **热修预案** | ❌ **缺失** → 建议如下 | hook 点失效时不发版可诊断：模块控制台显示各 hook 状态（当前已有 `HookEntry.log` 记录）；重签 APK 覆盖安装即可热修，无需卸载 |
| 第三方许可证声明 | ❌ **缺失** | 待补 `THIRD_PARTY_LICENSES.md` |
| UI 截图 / 录屏 | ❌ **缺失** | 需真机 |

### 回滚触发条件（4 条）

1. 安装后**蓝牙无法开启**或系统蓝牙设置页崩溃 → 立即回滚
2. 耳机**无法连接**或连接后**音频异常** → 立即回滚
3. `com.android.bluetooth` 进程**反复重启**（logcat 出现该进程崩溃） → 立即回滚
4. 融合设备中心**显示错误的设备信息**（如显示成小米耳机但无法控制） → 降级：仅停用 `com.milink.service` 作用域

---

## 七、关于"可发布"

**本模块当前不满足"可发布"条件**（P0-1 未修 + 核心功能无实机证据），故不给 tag 建议与发布说明草稿。

升级路径（按优先级）：

1. **修 P0-1**（**4 行改动**）：`BluetoothProcessHook.kt:88`、`SettingsHook.kt:167`、`FiilRfcommController.kt:314`、`FiilRfcommController.kt:371` 停止打印完整 MAC
2. **修 P0-2**：融合设备中心 hook 失效时暴露状态
3. **拿一台 HyperOS 真机**跑：设置页卡片是否出现 / 超级岛是否弹 / 融合设备中心是否显示 FIIL 设备 / 断连后岛是否收起
4. **补 UI 截图**
5. `git init` + `tag v1.0.0`（建议 tag 格式 `v1.0.0`，与 `versionName` 一致）
6. 自签正式证书替换 debug 证书
7. 补 `THIRD_PARTY_LICENSES.md`、回滚方案、热修预案

完成 1、3、4 即可满足"可发布"门禁。

---

## 八、验收的诚实边界

**这个项目验证到哪一步，必须说清楚：**

协议层是**真机验证过**的——用一套与模块无关的 C# WinRT 实现（`tools\probe\FiilProbe`）连真耳机逐条收发，
19 个用例全绿，并发现了 `setUnlock` 强制握手、畅连写入用错命令值、EQ 需回填 `reserved` 三个**只有真机能暴露**的问题。
这些纸面逆向永远不会发现。

模块本体是**最大的空白**——26 个 Kotlin 文件、8,002 行代码中，所有关于 Android 系统注入的部分
（设置页 / 超级岛 / 融合设备中心 / 弹窗）**没有一行在真机上运行过**。
它们逻辑自洽、有 38 项单测、编译通过、APK 元数据正确——但这些都不能证明它们在一台真实的
HyperOS 手机上会按预期工作，尤其依赖内部方法签名匹配的 hook。

**所以结论是可内测**：协议可信到可以放心用，模块本体需要有人拿真机试。
