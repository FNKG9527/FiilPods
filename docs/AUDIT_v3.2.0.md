# FiilPods v3.2.0 代码审计报告

- 审计基线：`92e7594`，`versionCode=10`，`versionName=3.2.0`
- 对比版本：v3.1.0（`versionCode=9`）
- 审计日期：2026-11-07
- 审计方式：只读源码审计 + 四个作用域 APK 反编译产物交叉核对；**本次没有修改源码，也没有进行真机回归**
- 作用域材料：
  - AOSP 蓝牙：`_dev/bt_smali/`
  - 小米蓝牙扩展：`_dev/bt_ext_smali/`
  - 系统设置：`_dev/settings_smali/`
  - MiLink 18.2.0.12：`_dev/milink_18_smali/`

## 1. 总结

| 分类 | 数量 | 含义 |
|---|---:|---|
| 确定失效 / 死配置 | 9 | 可从当前源码直接证明功能未执行、状态未消费或数据无法到达使用方 |
| 高风险 / 条件失效 | 4 | 代码存在明显缺陷，但最终影响需要特定机型、系统状态或复现场景确认 |
| 已有实现但未添加到界面 | 7 | 协议或底层控制已经存在，主要缺少 UI、状态字段或原生卡片入口 |
| 协议完全未实现 | 4 | 官方 App 已有相关能力，而本项目找不到对应完整控制链 |

本次没有把“未做真机验证”一律写成“失效”。LDAC、ANC、焦点通知、超级岛、融合中心和原生设置页的 **主要 hook 签名仍能在所给系统包中找到**，不能仅凭静态代码宣布这些功能整体失效。

---

# 2. 确定失效、未被消费或存在数据断点的项目

## A1. BLE-only GAIA 三款机型的接收协议用错，状态与控制链实际上无法闭环

**影响机型：earType 39 / 40 / 42。**

### 证据

- 机型表把 39、40、42 定义为 BLE-only：[FiilModels.kt](app/src/main/java/moe/chenxy/fiilpods/pods/FiilModels.kt) 的 `BLE_ONLY`。
- GATT 收到通知后始终使用 Mate 帧解析：[FiilGatt.kt](app/src/main/java/moe/chenxy/fiilpods/pods/FiilGatt.kt) `handleRx()`，约 283–303 行。
- GATT 数据进入控制器后也始终调用 `dispatchMate(frame)`：[FiilRfcommController.kt](app/src/main/java/moe/chenxy/fiilpods/pods/FiilRfcommController.kt) 约 700–707 行。
- 同一控制器已经实现 GAIA 解析器 `FiilGaia.drain()`，但 GATT 路径没有按机型选择它。
- `send()` 在 `usingGatt` 时已经有 GATT 分支，约 1863–1903 行；也就是说发送路径支持 GATT，接收路径却没有对应的协议分流。

### 结论

这是当前最明确的协议级缺陷之一。对这三款耳机，不能把“扫描到 BLE、写入特征成功”当成“功能已接入”。即使命令能发出，Mate 解析器也无法正确解释 GAIA 回包，因此电量、ANC、佩戴状态、命令 ACK 等无法形成可靠闭环。

**影响：39/40/42 的核心状态读取和控制应视为失效，必须真机回归。**

---

## A2. 主界面按蓝牙显示名称判断能力，用户改名后大量入口会错误消失

### 证据

- [MainUI.kt](app/src/main/java/moe/chenxy/fiilpods/ui/MainUI.kt) 约 213–218 行把 `displayTitle` 和 `productId` 传给能力检测。
- [DeviceCapabilities.kt](app/src/main/java/moe/chenxy/fiilpods/pods/DeviceCapabilities.kt) 约 99–104 行的重载忽略 `productId`，只使用 `FiilModels.earTypeOfName(deviceName)`。
- `MainUI.kt` 约 418–421 行已经从广播的 `product_id` 得到真实 `connectedEarType`，但没有用于这组能力判断。
- LDAC 是一个例外：约 922 行已经使用 `FiilModels.supportsLdac(connectedEarType.intValue)`。

### 结论

耳机改名、显示名与内置别名不完全一致、系统返回厂商别名时，主 UI 的以下能力可能被误判为不支持：

- 游戏 / 低延迟模式
- 空间音频 / 影院
- 自定义 EQ
- 双设备连接
- 自适应能力入口

这不是协议能力不足，而是 **UI 能力识别用了低可靠性的名字，忽略已经拿到的 earType**。

---

## A3. “超级岛显示时机”是死配置

### 完整链路

- UI：`MainUI.kt` 约 963–968 行 → `ConfigManager.updateIslandShowTimings()`。
- 保存：[ConfigManager.kt](app/src/main/java/moe/chenxy/fiilpods/config/ConfigManager.kt) 约 123–126、170–182 行。
- 使用方：[MiBluetoothToastHook.kt](app/src/main/java/moe/chenxy/fiilpods/hook/MiBluetoothToastHook.kt) 约 342–393 行只读取 `focusNotification` 和 `islandMode`，没有读取 `islandShowTimings`。

### 结论

用户切换“佩戴 / 摘下 / 入盒”等显示时机，配置会保存、也会出现在导出日志里，但超级岛展示逻辑完全不根据它分流。

**该设置项当前无效。**

---

## A4. “融合卡片能力”设置不控制融合卡片

### 证据

- `milinkCardFeatures` 在 `AppConfig`、设置页、SharedPreferences、日志导出中均存在。
- 在 `hook/` 下没有实际消费方；`SettingsControlCard.kt` 与 `MiLinkServiceHook.kt` 都不根据该集合隐藏或显示功能。

### 结论

`SettingsPage.kt` 中这组多选项当前只是持久化数据。无论选择哪些功能，原生控制卡和融合中心行为都不会因此改变。

**该设置项当前无效。**

---

## A5. “自动游戏模式”没有自动触发逻辑

### 证据

- `autoGameMode` 有 UI、配置保存和日志导出。
- 全项目没有监听应用切换、游戏包名、AudioManager 模式或前台应用并自动设置低延迟的实现。
- 现有 `setLowLatencyMode()` 只来自用户手动操作或原生卡片操作。

### 结论

打开“自动游戏模式”不会自动进入游戏 / 低延迟模式。

**该设置项当前无效。**

---

## A6. 操作失败广播没有任何 UI 接收者

### 证据

- [FiilAction.kt](app/src/main/java/moe/chenxy/fiilpods/pods/FiilAction.kt) 定义 `ACTION_OP_FAILED`。
- [FiilRfcommController.kt](app/src/main/java/moe/chenxy/fiilpods/pods/FiilRfcommController.kt) 约 2334–2350 行发送失败广播。
- `MainUI.kt` 没有注册或处理 `ACTION_OP_FAILED`；全项目没有 UI 消费该 action。

### 结论

ANC、EQ、LDAC 等命令超时或失败时，底层可能发出失败事件，但用户界面：

- 不显示失败原因；
- 不提示命令未生效；
- 不能依靠这条广播统一恢复开关状态。

这会造成“界面看起来切换了，但耳机实际没有切换”的假成功表现。

---

## A7. 主 UI 不接收完整 `FiilStatus`，已经采集的多个状态永远无法显示

### 证据

- 完整状态通过 `FiilAction.ACTION_STATE` + `EXTRA_JSON` 广播。
- [MainUI.kt](app/src/main/java/moe/chenxy/fiilpods/ui/MainUI.kt) 的广播过滤器只包含连接、断开、电量、ANC、编解码器及系统蓝牙状态，没有注册 `FiilAction.ACTION_STATE`。
- `FiilStatus` 已包含：
  - `wearLeft` / `wearRight`
  - `wearDetect`
  - `noiseAdaptive`
  - `windAdaptive`
  - `earAdaptive`
  - `eqAdaptive`
  - `tuning`
  - `earTypeOverridden`
  - 其他 Mate 状态

### 结论

协议层即使正确收到这些数据，主 UI 也没有完整状态入口。当前佩戴检测、自适应、提示音相关状态只能留在 JSON / 广播里，无法形成完整控制界面。

---

## A8. v3.2.0 跨进程日志桥在 Provider 返回空结果时会静默丢日志

### 证据

- [RfcommLog.kt](app/src/main/java/moe/chenxy/fiilpods/pods/RfcommLog.kt) `flushRemote()` 约 191–213 行。
- 只有 `ContentResolver.call()` **抛异常**时才退回蓝牙进程自己的 `filesDir`。
- 当模块进程未启动、Provider 不可达或 `call()` 返回 `null` 时，不一定抛异常。
- 待发送批次已经清理，失败分支没有把整批重新放回队列。

### 结论

v3.2.0 的“导出日志”界面和导出文件生成已经实现，但**日志采集完整性仍有缺口**。模块进程未运行时，蓝牙进程产生的部分日志可能既没有进入模块日志文件，也没有回退到蓝牙进程文件。

这直接影响后续排障，特别是用户最需要导出日志的异常启动场景。

---

## A9. 自定义 EQ 写入没有保留设备返回的 reserved 字节

### 证据

- [FIILProtocol.kt](app/src/main/java/moe/chenxy/fiilpods/pods/FIILProtocol.kt) `writeEQ()` 约 416–444 行把 `payload[0]` 固定写成 `0`。
- [DEVICE_TEST.md](docs/DEVICE_TEST.md) 约 68–81 行记录设备回包的 reserved 字节应回填，实测值包括 `0x0A`。
- `FiilRfcommController.kt` 约 1346–1355 行读取 EQ 时只保存频段和增益，没有保存 reserved。

### 结论

源码与项目已有的实机协议记录不一致。自定义 EQ 的读取、预设切换和写入路径都存在，但自定义写入可能因首字节不符合设备要求而被忽略或拒绝。

**这是确定的实现偏差；最终哪些固件拒绝写入，需要真机确认。**

---

# 3. 高风险、条件失效或需要补强的项目

## B1. 手动 `refreshAll()` 没刷新提示音和自适应状态

`FIILProtocol.kt` 的 `refreshAll()` 包含版本、电量、MAF、低延迟、影院、多点、佩戴、EQ、LDAC，但没有：

- `requestTone()`
- `requestAdaptiveSwitches()`

首次连接的 `mateQueries()` 包含这些查询，所以首连可能正常；之后手动刷新或状态恢复时，提示音和自适应状态可能保持旧值。

---

## B2. 导出日志前的配置快照可能退回默认值

`LogExporter` 调用 `ConfigManager.current()`。若模块进程冷启动、配置尚未完成加载，导出文件中的配置快照可能是默认值，而不是用户当前配置。

这不影响日志 txt 的生成，但会误导排障。

---

## B3. 崩溃页挡住设置页，无法从现有入口导出导致崩溃的那份记录

当前崩溃记录已经写入并纳入导出内容，但崩溃后 UI 直接进入崩溃页。用户必须先清除崩溃堆栈，才能重新进入设置页导出。

因此“导出崩溃日志”功能存在，**崩溃当次的使用路径却不完整**。

---

## B4. 日志和图片 Provider 的 `call("appendLog")` 没有调用方鉴权

[PodImageProvider.kt](app/src/main/java/moe/chenxy/fiilpods/config/PodImageProvider.kt) 约 38–45 行处理 `appendLog`，Provider 为 exported，未限定只接受本模块或蓝牙进程调用。

影响主要是：

- 同机其他应用可写入伪造日志；
- 可通过频繁写日志干扰排障；
- 增加不必要的 IPC 暴露面。

现有文件大小限制降低了磁盘滥用风险，因此不把它夸大为严重远程漏洞，但应收紧。

---

# 4. 已有底层实现、但未添加到产品界面的功能

## C1. 佩戴检测开关

- `FiilModels.supportsWearDetect()` 已定义支持机型。
- `FiilStatus.wearDetect` 已有字段。
- 蓝牙进程已有 `wear` 命令处理。
- 主 UI、原生控制卡均无开关。
- 主 UI 还不接收完整 `ACTION_STATE`。

**结论：协议与控制底层已存在，产品入口未添加。**

---

## C2. 五个自适应功能开关

已有状态：

- 降噪自适应 `noiseAdaptive`
- 抗风噪自适应 `windAdaptive`
- 耳道自适应 `earAdaptive`
- EQ 自适应 `eqAdaptive`
- 调音自适应 `tuning`

蓝牙进程已有 `adaptive` 命令入口，但 UI 没有调用方，原生卡片也没有这些项目。

**结论：底层较完整，缺少界面与状态接入。**

---

## C3. 固件版本显示

`queryVersion()` 已存在，Mate 和 GAIA 的版本回包也已收到，但：

- 回包只写日志；
- `FiilStatus` 没有固件版本字段；
- 没有版本广播；
- 设置页、详情页、原生卡片都不显示版本。

**结论：查询能力已有，版本信息产品化未添加。**

---

## C4. 找耳机 / 发声定位

`FiilRfcommController.findDevice()` 已存在，但主要是 GAIA 分支，Mate 没有完整实现；UI 也没有调用。

**结论：GAIA 有底层入口，Mate 与 UI 均未完成。**

---

## C5. 媒体播放、暂停、上一首、下一首和音量控制

`FiilRfcommController` 约 2053–2097 行已有 Mate 音量与播放控制实现，但全项目 UI 没有这些调用。

**结论：协议函数存在，控制界面未添加。**

---

## C6. 手动指定 / 覆盖耳机型号

`setEarTypeOverride()` 约 2112–2130 行已存在，会改变协议方言并重新发布状态，但 UI 没有型号选择入口。

这项能力与 A2 直接相关：当显示名识别失败时，用户目前无法手动纠正主 UI 能力判断。

---

## C7. 原生小米耳机页与融合卡片的高级功能不完整

[SettingsControlCard.kt](app/src/main/java/moe/chenxy/fiilpods/hook/SettingsControlCard.kt) 已有：

- 影院
- 低延迟
- 多点
- EQ

但没有：

- 佩戴检测
- LDAC
- 自适应开关
- 音量 / 媒体控制
- 找耳机
- 固件版本

此外，这些卡片完全不读取 `milinkCardFeatures`，所以 A4 的配置失效与这里的功能缺失是两个不同问题。

---

# 5. 官方 App 有能力、本项目未形成完整实现的功能

以下项目在当前源码中没有找到完整“状态读取 + 控制命令 + UI + 系统接入”链路，应单独立项，不应被描述成“已支持但偶尔失效”。

## D1. OTA 固件升级

未找到完整的固件包选择、分块传输、升级进度、校验、失败恢复和升级界面。

## D2. 完整触控手势自定义

当前项目可以控制部分耳机功能，但没有官方 App 那种单击、双击、长按、左右耳分别配置及动作映射的完整体系。

## D3. 查找耳机的完整产品流程

除底层 `findDevice()` 缺口外，还缺少：

- 左右耳分别发声；
- 音量与安全提示；
- 状态反馈；
- Mate 分支；
- 界面入口。

## D4. 完整 EQ 预设保存管理能力

当前已有 EQ 预设、频段、增益和保存命令，但仍缺：

- reserved 字节正确保留；
- 自定义预设的完整列表与持久化 UI；
- 与设备回包的一致性验证；
- 失败反馈。

因此 EQ 不能简单归类为“完全未添加”，而应归类为 **已有主体实现，但自定义写入和失败闭环不完整**。

---

# 6. 经核对仍然成立、不要误判为整体失效的功能

## 6.1 LDAC

- `FiilModels.supportsLdac()` 已按 earType 判断。
- 主 UI 的 LDAC 显示已使用真实 earType，不再仅依赖 A2DP 是否声明支持。
- 耳机侧 LDAC 开关与手机侧 A2DP 设置已分开。
- `FiilCodec` 的反射签名在蓝牙包中仍能找到。

**结论：主要自锁问题已修复。是否真的切换 990 kbps，仍需耳机型号、解锁状态和系统编解码器共同验证。**

## 6.2 ANC / MAF

- 蓝牙命令、状态回包、超时处理和 MiLink 映射均存在。
- MiLink 18.2 的 `AncBatteryController`、`ProfileContext`、`DiscoveryKt` 等关键类和方法仍存在。
- 当前 `component8=switchState`、`component11=findRingState` 与所审计的 18.2 字段布局一致。

**结论：不能判定 ANC 整体失效。主要问题是失败反馈、UI 状态完整性和真机系统版本差异。**

## 6.3 通知、焦点通知和超级岛

- 通知注册、电池广播、ANC 操作、FocusNotification、通知图标按型号加载均已接线。
- 小图标仍固定系统蓝牙图标；型号图用于 FocusNotification 大图，这是设计选择，不等于所有图标都失效。

**结论：通知主体链路存在；确定的问题是显示时机配置无效，以及失败场景需真机验证。**

## 6.4 原生设置页

- `HeadsetIDConstants.checkSupport`
- `isBleMmaConnect`
- `IMiuiHeadsetService.Stub.Proxy.changeAncMode`
- `changeAncLevel`
- `setCommonCommand`
- `MiuiHeadsetBattery.onBatteryChanged(III)`

上述签名在所给 Settings 反编译包中仍能找到。

**结论：静态签名未显示全面失效；但只验证了当前保存的 APK 版本，系统 OTA 后仍需重新核验。**

---

# 7. v3.1.0 → v3.2.0 的实际变化

v3.2.0 主要是日志功能版本，没有修复本报告列出的协议与 UI 缺口。

新增或改变：

- 设置页“导出日志”；
- txt 导出；
- 最近 60 分钟窗口；
- 最大 2 MiB；
- 跨进程日志桥；
- 配置快照与崩溃记录；
- 文件分享；
- `LogWindowTest`，总测试数增至 78 项；
- `versionCode 9 → 10`，`versionName 3.1.0 → 3.2.0`。

新增风险：

- A8：Provider 返回空结果时日志静默丢失；
- B2：冷启动配置快照可能不准；
- B3：崩溃页阻挡导出入口；
- B4：日志写入 IPC 无鉴权。

因此，**78 项单元测试通过不等于 78 项耳机功能通过真机验证**。现有测试主要覆盖纯函数、映射、帧构建和配置契约，不能覆盖 Android 系统进程注入、真实 RFCOMM/GATT 和厂商固件行为。

---

# 8. 建议修复顺序

## P0：先恢复正确性与可诊断性

1. 修复 GATT 按 GAIA / Mate 分流，优先覆盖 39/40/42。
2. 修复 EQ reserved 字节保存与回填。
3. 主 UI 能力判断改为优先使用 `connectedEarType`，显示名只做兜底。
4. 主 UI 接收 `ACTION_OP_FAILED`，失败时提示并恢复状态。
5. 修复跨进程日志桥的空结果回退与批次重试。

## P1：清理假功能和状态断点

6. 实现或暂时移除三个死配置：`islandShowTimings`、`milinkCardFeatures`、`autoGameMode`。
7. 主 UI 接入完整 `ACTION_STATE`。
8. `refreshAll()` 补齐提示音与自适应查询。
9. 日志导出增加“保留最近窗口 / 导出全部”的明确选项，避免用户误以为 2 MiB 就是全部历史。

## P2：补产品功能

10. 佩戴检测、自适应、固件版本、型号覆盖入口。
11. 媒体控制与找耳机。
12. 原生控制卡功能补齐并接入 `milinkCardFeatures`。
13. 触控自定义和 OTA 作为独立大功能设计，不建议混入小修版本。

---

# 9. 审计边界

本报告没有声称：

- 所有真机问题均已复现；
- 所有反编译 hook 在每个 HyperOS 版本均有效；
- 单元测试通过等于功能验收通过；
- 官方 App 的每项能力都已被完整枚举。

下一步如果要进入修复，应先把 **P0 五项** 做成独立小提交，并在 Key Max（earType 46）之外至少补一台 GAIA 或 BLE-only 设备验证；只用 Key Max 无法证明 GAIA 与 BLE-only 路径已经修好。
