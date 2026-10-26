# Changelog · 更新日志

本项目的所有重要变更都会记录在此文件。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 SemVer](https://semver.org/lang/zh-CN/)。

---

## [2.1.0] - 2026-10-26

修复 v2.0.0 上机后用户反馈的三条问题。

### Fixed

- **融合设备中心切换降噪模式后图标要再点一次才更新**
  `MiLinkServiceHook.hookAncCommand()` / `hookAncStateBlock()` 只改了成员变量并通知控制中心刷新，
  没有回写 `AncBatteryModel` 的内存字段，控制中心重绘时读到的仍是旧值。
  新增 `syncAncModel()`，按 ROM 字段名漂移依次回写 `ancState` / `bluetoothAncState` /
  `miLinkAncState`，再二次推送 `UPDATE_ANC`。三处接入（含耳机实体键切换走的
  `ACTION_PODS_ANC_CHANGED` 分支）。

- **模块页误报「模块服务未响应」**
  `announceAlive()` 只在 `AdapterService.onCreate` 时发一次，App 后开就一条都收不到，
  75 秒窗口校验必然超时。改为 `UI_INIT` / `REFRESH_STATUS` 分支每次回发 ALIVE 心跳。

- **设置页开启焦点通知后通知栏无通知**（四个缺陷叠加）
  - `HookEntry` 从未注入 LSPosed 远端 prefs，四个被注入进程里的 `ConfigManager`
    永远是默认值，用户改设置对 Hook 侧无效。新增 `HookContext.attach()` 与
    `HookEntry.prepareRemotePrefs()`。
  - `FocusIslandUtil.showBatteryIsland()` 全工程零调用点，「超级岛 = 模块」这条分支从未接线。
    新增 `MiBluetoothToastHook.showModuleFocusIsland()` 按 `islandMode` 分流。
  - 通知渠道建成 `IMPORTANCE_MIN`，HyperOS 会折叠进「静默通知」；且渠道重要性创建后不可修改。
    换新渠道 id `fiilpods_battery_v2` + `IMPORTANCE_LOW`。
  - `onHook()` 里 `if (!focusNotification()) return` 导致进程在「关」状态下启动后
    再也收不到。改为总是挂接收器，开关判断下沉到显示时刻。

### Changed

- `versionCode` 2 → 3，`versionName` 2.0.0 → 2.1.0
- 补齐 `PodImageProvider` 的 `<provider>` 声明（移植时遗漏，用户设置自定义耳机图片会崩）
- 空断言降级：`getParcelableExtra("status")!!` 等 6 处改为安全调用
- `PodStatus.themedResources.getDrawable() as BitmapDrawable` 硬转改为 `runCatching`
- `values-zh-rCN/strings.xml` 补齐 `fiil_maf_listen`（兼听）、`fiil_maf_wind`（风中）

### Added

- `docs/FIX_V2_1.md`：本轮三条问题的根因、修法与验证记录（302 行）

---

## [2.0.0] - 2026-10-26

修复「电量能显示但模式切不了」，并补齐模块内日志抓取能力。**v1.0.0 的源码与产物完整保留。**

### Fixed

- **Mate 机型（Key Max 等）无法切换降噪模式**（六个真缺陷）
  1. `send()` 缺 GATT 分支：走 BLE GATT 时命令根本不发出去
  2. `setAnc()` 无条件发 GAIA 帧：Mate 机型只认 `cid=44` 的 Mate 帧，GAIA 帧被静默丢弃
  3. `setEq()` / `set3D()` 同样未做协议族分流
  4. `FiilMate.parse()` 把帧首字节当固定 `0x00`，实际是 **seq 滚动计数器** ——
     34 个回包里有 31 个被这一条丢掉
  5. `TONE` 回包解析偏移写错（真机 `01 27 02 00 03 82 01 02`，payload 从第 6 字节起）
  6. 调试页发的 `RFCOMM_LOG_CONNECT` / `DISCONNECT` / `CLEAR` 三个 action
     蓝牙进程根本没注册，日志通道从未接通

- **新手机点开 App 闪退**（`java.lang.RuntimeException: Error receiving broadcast`）
  根因是跨进程 Parcelable：`BatteryParams` 在蓝牙进程与 UI 进程里类名不一致
  （R8 混淆），反序列化抛 `ClassNotFoundException: zd`。
  三层修复：① 业务层彻底不读 Parcelable，改用纯 int extra 通道
  ② 接收器整体 `runCatching` 兜底 ③ proguard 加 keep 规则

### Added

- `pods/FiilMate.kt` 新增 `Query.UNLOCK = 255`（`setUnlock` 握手，帧 `00 27 01 00 04 FF 02 02 34`）
- `pods/RfcommLog.kt`：模块内环形日志缓冲 + 调试页实时查看 + 一键复制
- `RfcommDebugPage`：设置页新增入口，可看 TX/RX 原始字节、过滤、清空、导出
- `docs/FIX_V2_MODES.md`：v2.0.0 六个真缺陷的完整记录（294 行）
- `docs/FIX_CRASH_PARCELABLE.md`：闪退根因与三层修复记录

### Changed

- `versionCode` 1 → 2，`versionName` 1.0.0 → 2.0.0

---

## [1.0.0] - 2026-10-26

首个可用版本。把 FIIL 耳机接入 HyperOS 原生体验。

### Added

- **UI 完全对齐 OppoPods 2.2.0**（三 Tab 底栏：模块 / 耳机 / 设置）
  - 模块页：激活状态、蓝牙状态、配对设备数、系统/应用/Android/LSPosed 版本、构建时间、设备型号
  - 耳机页：多设备列表、电量卡片、降噪模式（MAF 五态）、音效预设、影院模式、蓝牙畅连
  - 设置页：主题、语言、日志级别、隐藏桌面图标、焦点通知、超级岛来源、互联卡片、伪装耳机型号
- **系统蓝牙详情页控制卡片**（`SettingsControlCard.kt`，纯 framework 控件，跨 ClassLoader 安全）
- **超级岛 / 强提示通知**（`MiBluetoothToastHook.kt` + `FocusIslandUtil`）
- **融合设备中心卡片联动**（`hook/milink/`，注入 `com.milink.service`）
- 双传输层：经典蓝牙 SPP（RFCOMM）+ BLE GATT（`ff17` 写 / `ff18` 通知）
- 支持 4 套协议栈：GAIA（老机型）/ Mate（Key Max 等）/ Airoha（F042/CC Pro2）/ 豆包 0x55AA
- 机型识别三级兜底：名称匹配 → MAC OUI 前缀（`B0:F1:A3` / `FF:F1:A3`）→ 地址绑定
- 49 项单元测试（`ModelCapabilityTest` 16 + `ProtocolFrameTest` 27 + `AddressMaskTest` 6）

### Known Issues

- `MiLinkServiceHook` 中 31 处 `runCatching` 静默吞异常，失败无用户可见反馈（记为 P0-2）
- Mate 机型自定义 EQ 写入不生效
- GAIA 老机型、自适应开关仅静态验证，无对应硬件

---

## 更早的历史

- `docs/ACCEPTANCE_REPORT.md` —— 6 角色联合结项验收报告（总分 72/100，结论「可内测、不可公开发布」）

---

## 版本对照

| 版本 | versionCode | 日期 | 关键变化 |
|---|---|---|---|
| 2.1.0 | 3 | 2026-10-26 | 三条上机反馈修复（设备中心图标 / 服务心跳 / 焦点通知） |
| 2.0.0 | 2 | 2026-10-26 | Mate 模式切换 6 缺陷 + 跨进程 Parcelable 闪退 + 日志抓取 |
| 1.0.0 | 1 | 2026-10-26 | 首个可用版本，UI 对齐 OppoPods 2.2.0 |

三个版本的签名一致（V2 SHA-256 `605996dc…38d34`），可直接覆盖安装。

[2.1.0]: https://github.com/FNKG9527/FiilPods/releases/tag/v2.1.0
[2.0.0]: https://github.com/FNKG9527/FiilPods/releases/tag/v2.0.0
[1.0.0]: https://github.com/FNKG9527/FiilPods/releases/tag/v1.0.0
