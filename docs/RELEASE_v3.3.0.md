# FiilPods v3.3.0 修复报告

> 依据：`docs/AUDIT_v3.2.0.md` 的审计结论（A1–A9 / B1–B4）。
> 目标：修好**已有的**失效、假功能与状态断点；不新增 OTA、触控手势、多连接等全新大功能。
> 版本：`versionCode 11` / `versionName 3.3.0` / 应用名 `FiilPods 3.3`。

---

## 0. 一句话结论

审计列出的 9 个「确定失效」项、4 个「高风险项」除个别标注外全部落地修复；
新增 10 个纯逻辑类与 12 个测试文件，单测从 78 项涨到 **151 项**（19 个 suite，全绿）。
**协议层、hook 注入层、UI 层都有改动，装完必须重启手机或关开一次蓝牙**——
LSPosed 只在进程启动时注入，这跟装模块的旧版本一样。

---

## 1. 逐项修复

### A1 · BLE-only 机型接收协议用错，状态与控制链闭不上环

**根因**（两层，缺一不可）：

1. `FiilGatt` 在 `onConnectionStateChange` 里拿到"MATE/GAIA"后才建 parser，
   但 GAIA 的首帧 `FF 01 ... 02 34` 是**变长**的，一次通知可能只到 `FF 01 F1`。
   旧代码把这段半帧直接交给 Mate 的 `drain`：Mate 在非法头处逐字节重同步，
   于是 GAIA 的 SOF（`0xFF`）被当成噪声吃掉，**后续所有 GAIA 帧全部解不出来**。
2. 旧代码在收不到任何完整帧时，默认把协议族判成 Mate。

**修法**：

- 新增 [`pods/GattFrameParser.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/GattFrameParser.kt)：
  有状态的纯 JVM 拆包器（不依赖 `BluetoothGatt`，可直接单测）。
  方言未知时**只有收全并通过校验的 GAIA 帧才锁定 GAIA**；
  不能用"看到 `0xFF` 就当 GAIA"，因为 Mate 的 seq 合法范围**包含 `0xFF`**。
- `FiilGatt` 改为持有 `GattFrameParser`，`setDialect()` 与 `reset()` 原子换方言并丢弃上一轮半帧。
- `FiilRfcommController.setEarTypeOverride()` 在 GATT 已连接时同步 `FiilGatt.setDialect(FrameDialect.fromMate(isMate))`，
  避免"型号改了但 RX parser 还按旧方言跑"。

**反证**：同一条连接上，`enumerateA2dpConnected()` 用 hook 的 classLoader 反射
`com.android.bluetooth.a2dp.A2dpService`，实测可用——说明 BLE 通道本身没问题，
问题确实只在拆包。

### A2 · 能力判断读蓝牙显示名称，用户改名后入口消失

**根因**：`DeviceCapabilities` 允许"名称推断"覆盖"productId 事实"。
用户在系统蓝牙里把耳机改成"我的耳机"，`FiilModels.earTypeOfName()` 返回 -1，
于是 LDAC、ANC、EQ 等入口集体消失。

**修法**：`pods/DeviceCapabilities.kt` 改为 **productId 优先**——
只要拿到权威 `earType`，名称推断一律不参与；
名称只在 `earType <= 0`（还没连上/还没读到）时兜底。

### A3 · 「超级岛显示时机」是死配置

**根因**：`islandShowTimings` 有设置页 UI、有持久化，但**没有任何代码读它**；
岛什么时候弹完全由硬编码决定。

**修法**：

- 新增 [`pods/IslandTimingPolicy.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/IslandTimingPolicy.kt)：
  `CONNECTED / WEARING / REMOVED / IN_CASE` 四个触发器 → 是否放行。
  空集按"只在连接时显示"（保持旧默认），**不能**当成"全显示"。
- 佩戴触发器只认「已知的上次状态 → 本次状态」的**边沿**：心跳不重弹；
  必须先判 `true → false`（两侧同时摘下也是一次 REMOVED）。
- `IN_CASE` **故意永不触发**：`BatterySnapshot.fromExtras()` 只要任一 connected extra
  存在就会造出三侧对象，断开/静默探测 teardown 也会形成全 disconnected 快照，
  把这种快照猜成"入盒"会误弹。因此设置页把这个选项**移除**了，
  而不是留一个点了没反应的开关。
- `hook/MiBluetoothToastHook.kt` 的展示路径改为问 `IslandTimingPolicy.allows(...)`。

### A4 · 「融合卡片能力」设置不控制融合卡片

**根因**：`milinkCardFeatures` 只被持久化，两个面板的开关是硬编码的。

**修法**：新增 [`pods/MiLinkCardFeatureGate.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/MiLinkCardFeatureGate.kt)，
`gameModePanelEnabled(earType, features)` 与 `spatialPanelEnabled(earType, features)`
同时要求「用户勾选」+「机型真的支持」（`supportsMateMaf` / `supportsCinema`）。
`hook/milink/MiLinkServiceHook.kt` 的 `gameModePanelEnabled()` / `spatialPanelEnabled()`
改为走这个 gate；配置变更广播会重建面板。

### A5 · 「自动游戏模式」没有自动触发逻辑

**根因**：只有开关与持久化，**没有任何 UsageStats 轮询、没有归属管理**。

**修法**（三层，纯逻辑与 Android 侧分离）：

- [`pods/AutoGameModePolicy.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/AutoGameModePolicy.kt)：
  `Ownership{NONE,AUTO,MANUAL}` + `evaluate(enabled, classification, confirmedMode)`。
  只有「已确认 MUSIC 基线 + 归属 NONE + 前台是游戏」才给 GAME；
  只有「归属 AUTO + 已确认 GAME + 前台不再是游戏」才还原 MUSIC。
- [`pods/AutoGameModeRuntime.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/AutoGameModeRuntime.kt)：
  会话状态机（`setEnabled` / `onForegroundClassification` / `onConfirmedLowLatency` /
  `markManualAction` / `onDisconnected` / `hasConfirmedBaseline`）。
  **没有确认的 MUSIC 基线之前一律不动**——否则"用户本来就开着游戏模式"会被误还原。
- `hook/BluetoothProcessHook.kt`：`UsageStatsManager.queryEvents(now-10min, now)`
  取最新一条 `MOVE_TO_FOREGROUND`/`ACTIVITY_RESUMED` 的包名，再用
  `ApplicationInfo.CATEGORY_GAME` / `FLAG_IS_GAME` 判游戏。
  **任何异常或读不到都 fail closed**（返回 UNKNOWN 并退避），绝不猜。
  连接建立时（`connectBlocking` / `onGattReady`）启动会话，
  断开 / 配置变化 / 手动操作都会正确让出归属。

### A6 · 操作失败广播没有任何 UI 接收者

**根因**：`FiilAction.ACTION_OP_FAILED` 有人发、没人收。

**修法**：`ui/MainUI.kt` 注册该 action，收到后按 `EXTRA_CMD` 查
`R.string.operation_failed`（"操作未生效：%1$s，请重试"）弹 Toast。
`FiilRfcommController.broadcastOpFailed()` 在 ANC 确认超时、命令回包未生效时发出。

### A7 · 主 UI 不接收完整 `FiilStatus`

**根因**：状态广播只发给模块自己一个包，四个已注入的系统宿主
（`com.android.bluetooth` / `com.android.settings` / `com.milink.service` / `com.xiaomi.bluetooth`）
拿不到同一份快照；UI 侧则只处理电量，`cinemaMode` / `multipoint` / `wearDetect` /
`noiseAdaptive` / `windAdaptive` 等已采集字段永远不显示。

**修法**：

- 新增 [`pods/StateBroadcastTargets.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/StateBroadcastTargets.kt)：
  用**显式包名列表**（模块 + `FiilAction.STATUS_TARGETS`）而不是隐式广播，
  避免把内部状态暴露给无关应用。
- `FiilRfcommController.publishState()` 把 `ACTION_STATE`（完整 JSON）逐个包发出。
- `ui/MainUI.kt` 解析完整 `FiilStatus`，新增字段全部接上界面。

### A8 · 跨进程日志桥在 Provider 返回空结果时静默丢日志

**根因**：`RfcommLog` 把一批日志交给 `PodImageProvider.call("appendLog")` 后，
不看返回值；Provider 因为参数不合法/进程刚起返回 null 时，这批日志**直接消失**，
而调用方以为自己记下了。

**修法**（`pods/RfcommLog.kt`）：

- 新增 `RemoteLogQueue` + `SerializedRemoteLogBridge`：单线程串行、有界待发队列。
- 每批带**代际号**（generation）；`clear()` 时 `clearThen { ... }`
  把新日志挡在屏障之后，屏障前的在途批次被拒绝后按代际判定**不回灌**，
  避免"清空后又冒出来几行"。
- Provider 返回空/拒绝 → 该批**退回写自己的 `filesDir`**，不丢行。
- `hex()` 的负长度上限改为按 0 处理（旧代码会抛 `IllegalArgumentException` 把日志路径打断）。

### A9 · 自定义 EQ 写入丢掉设备返回的 reserved 字节

**根因**：Mate 自定义 EQ 的请求体是 `[reserved][type=15][band0..bandN]`，
旧代码把 `reserved` 写死成 0；而设备上一次回包里的那个字节是有意义的，
写 0 会导致部分机型 EQ 不生效。

**修法**：

- `pods/FiilStatus.kt` 新增 `eqReserved: Int`（进 JSON、可跨进程传递）。
- 新增 [`pods/EqWritePayload.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/EqWritePayload.kt)：
  `build(bands, type, reserved, expectedCount)` 做长度校验并拼包。
- `pods/FIILProtocol.kt` 的 `writeEQ` 走它，`reserved` 取最近一次真实回包的值。

### B1 · 手动 `refreshAll()` 不刷新提示音与自适应状态

**修法**：`FIILProtocol.refreshAll()` 的 Mate 分支补
`requestToneVolume()`、`requestAdaptiveSwitches()`、`requestLdacState()`，
与 `handshake` 的顺序保持一致（可重复调用、无副作用）。

### B2 · 导出日志前的配置快照可能退回默认值

**根因**：`ConfigManager.current()` 是进程内缓存。若从崩溃恢复后直接进设置页导出，
缓存还没被 `refreshFromPrefs` 填过，导出的配置快照就是**默认值**，反而误导排障。

**修法**：`utils/LogExporter.kt` 的 `appendConfig()` 先读一次磁盘 prefs 再取快照，
读失败才退回缓存。

### B4 · 日志/图片 Provider 的 `appendLog` 没有调用方鉴权

**修法**（`config/PodImageProvider.kt`）：

- `call()` 只接受 `METHOD_APPEND_LOG`，其余 method 一律返回 null。
- 调用方 UID 必须是「模块自己」或「`com.android.bluetooth`」，
  否则抛 `SecurityException`。
- 载荷改为 `Array<out String?>` 逐条校验：拒绝 null、拒绝超 20 行、
  拒绝累计 UTF-8 超 64 KiB，返回实际接收行数。
- **明确不做**：不提供 `clearLog`。清日志由模块进程自己删 canonical 文件即可，
  把"删除"这种破坏性操作暴露到 exported Provider 上是净风险。

### 附带修复 · ANC 语义按方言解释（本次最有价值的隐性 Bug）

审计的 6.2 节说"ANC / MAF 仍然成立"，但**只对 Mate 方言成立**：
Mate 与 GAIA 的原始线值 **2 与 3 是互换的**（Mate `2=通透 3=兼听`；
GAIA `2=监听 3=通透`）。旧代码在五处直接 `when (raw)` 写死 Mate 视角，
在 GAIA 机型上会把"监听"显示成"通透"、把"开放"显示成"关闭"。

新增 [`pods/AncWireCodec.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/AncWireCodec.kt)（语义 ↔ 线值 ↔ Oppo 状态码），
并在 `pods/NoiseControlMode.kt` 补三个纯函数：

- `ancDialectForEarType(earType)`：已知 GAIA 型号 → GAIA，未知型号保守按 Mate。
- `ancMiuiStateFromWire(dialect/earType, raw): Int?`：线值 → MIUI 三态；
  **未知线值返回 null，不许当成"关"**。
- `ancCycleWire(dialect, raw)`：循环按钮按**语义**推进（关→降噪→通透→关）。
  旧实现是 `(anc + 1) % 3`，在 GAIA 上从"监听"直接跳到"关闭"，
  用户永远切不到"通透"。

接线点：`ui/MainUI.kt`、`pods/OppoCompatBridge.kt`、`hook/SettingsHook.kt`、
`hook/milink/MiLinkServiceHook.kt`、`hook/BluetoothProcessHook.kt` 的 `ACTION_CYCLE_ANC`。

### 附带修复 · ANC 确认与回读

**根因**：`SET_MAF` 的回包**不携带切换后的档位**。旧代码在同一帧里就 ack，
UI 于是读到"尚未落定"的状态——这正是"点了没反应/要按两次"的观感来源。

**修法**：

- 新增 [`pods/AncConfirmationPolicy.kt`](../app/src/main/java/moe/chenxy/fiilpods/pods/AncConfirmationPolicy.kt)：
  3.5 秒窗口内、**目标匹配**的回包才算确认并 ack；
  目标不匹配的回包忽略且**不 ack**；窗口外的回包只更新设备真相，不算成功。
  用「目标 + 时刻」做身份判定，避免同键新请求被旧超时误清。
- `SET_MAF` 回包只触发 `requestAuthoritativeAncReadback()`（MAF_STATUS 主动回读），
  ack 留给真正的状态通知。
- 超时 → 回退 `lastConfirmedAncMode` + `publishState()` + `broadcastOpFailed`。

### B3 · 崩溃页没有「导出日志」入口，用户必须先清掉崩溃记录才能进设置页

**根因**：`MainActivity.onCreate()` 一旦读到 `CrashHandler.readLatest()` 就直接
`setContentView(buildCrashView(text)); return`，绕开整个 Compose 设置页；
旧崩溃页只有「复制」与「清除并重试」两个按钮。而崩溃记录恰恰是最该带出去的东西，
用户只有两条路：复制十几行堆栈，或者清掉它再进设置页——后者把证据删了。

**为什么不能直接分享 `crash_last.txt`**：它在 `filesDir/crash_last.txt` 或
external-files 根下，而 `res/xml/log_export_paths.xml:10-19` 只暴露了 `logs/` 两个根。
`FileProvider.getUriForFile()` 对不在声明路径内的文件会抛 `IllegalArgumentException`。
放宽 provider 路径等于把任意 `filesDir` 暴露给外部应用，不可接受。

**修法**：

- 新增 [`CrashExportGate.kt`](../app/src/main/java/moe/chenxy/fiilpods/CrashExportGate.kt)：
  `AtomicBoolean` 的 `tryBegin()/finish()/isExporting()`，把 gate 放在
  `MainActivity` 的 **companion object**（进程级）。理由：Activity 重建时旧 worker
  可能仍在跑，实例级 gate 挡不住跨实例并发，而 `LogExporter` 里的
  `SimpleDateFormat` 是 object 级、非线程安全，文件名又只有秒级精度。
- `MainActivity.buildCrashView()` 增加「导出日志」按钮，**复用**正常路径的
  `LogExporter.export(applicationContext)`——崩溃记录本来就被写进导出 txt 的
  「崩溃记录」区段（`LogExporter.kt:176-192`），文件落在 provider 已暴露的 `logs/` 下。
- 导出在后台 `Thread` 里跑，所有 view 操作回到 `runOnUiThread`，并先判
  `isFinishing || isDestroyed`。崩溃记录**保留**到用户显式点「清除并重试」。
- 「导出成功」与「分享面板打不开」分开提示：前者先 Toast 文件行数与大小，
  后者再单独 Toast `export_log_share_failed`，不会把分享失败误报成导出失败。
- 崩溃页文案全部走资源：新增 `crash_page_title` / `crash_copy_stack` /
  `crash_clip_label` / `crash_copied` / `crash_clear_retry` /
  `export_log_share_failed`（中英各一套），不再写死中文。

---

## 2. 新增/修改文件

**新增（生产代码 11 个）**

| 文件 | 作用 |
| --- | --- |
| `pods/AncWireCodec.kt` | ANC 语义 ↔ 线值 ↔ Oppo 状态码 |
| `pods/AncConfirmationPolicy.kt` | ANC 回包确认/超时判定（纯函数） |
| `pods/AutoGameModePolicy.kt` | 自动游戏模式归属策略（纯函数） |
| `pods/AutoGameModeRuntime.kt` | 自动游戏模式会话状态机 |
| `pods/GattFrameParser.kt` | BLE 拆包 + 方言锁定（纯 JVM） |
| `pods/IslandTimingPolicy.kt` | 超级岛时机（纯函数） |
| `pods/MiLinkCardFeatureGate.kt` | 融合卡片能力门控 |
| `pods/EqWritePayload.kt` | 自定义 EQ 请求体 |
| `pods/StateBroadcastTargets.kt` | 完整状态广播的显式接收方 |
| `hook/CardCommandRoute.kt` | 宿主控制卡命令路由 |
| `CrashExportGate.kt` | 崩溃页导出的进程级互斥 |

**新增测试 13 个**：`AncWireCodecTest` / `AncConfirmationPolicyTest` /
`AncDialectMigrationTest` / `AutoGameModePolicyTest` / `AutoGameModeRuntimeTest` /
`MiLinkCardFeatureGateTest` / `StateBroadcastTargetsTest` / `RemoteLogBridgeTest` /
`PodImageProviderLogBridgeTest` / `ProtocolAuditRepairTest`（含 GATT 拆包与方言锁定） /
`IslandBatteryEventReducerTest` / `CardCommandRouteTest` / `CrashExportGateTest`。

**改动面**：19 个文件 `+2029 / −373`（不含 10 个新文件）。

---

## 3. 构建与产物

用具名任务 + 单 worker + 进程内 Kotlin（16 GB 机器的稳定组合）：

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease `
  --console=plain --no-daemon --max-workers=1 `
  "-Pkotlin.compiler.execution.strategy=in-process" `
  "-Dorg.gradle.jvmargs=-Xmx1800m -XX:MaxMetaspaceSize=640m -XX:ReservedCodeCacheSize=256m"
```

`BUILD SUCCESSFUL in 6m 20s`。

| 项 | 值 |
| --- | --- |
| 单测 | **suites=20 / tests=152 / failures=0 / errors=0 / skipped=0** |
| release APK | `4,643,848 B` · SHA256 `29C9A7C2766DF6E50591154BC93C60E32B201BBB179CBD758B07D07B0895EBFA` |
| debug APK | `35,875,094 B` · SHA256 `8F3DA1547417E7C4650A61053743B6868D9B46146330EA6BBE1C97CB0431A0B5` |
| badging | `versionCode='11' versionName='3.3.0'` · `application-label:'FiilPods 3.3'` |

产物已放 `dist/FiilPods-v3.3.0-release.apk` 与 `dist/FiilPods-v3.3.0-debug.apk`。

> **教训（沿用）**：增量构建产物哈希每次都不同，交付必须用 `clean` 重建的那一份。

---

## 4. 真机验收清单

> 装 APK 后**必须重启手机或关开一次蓝牙**，否则 `com.android.bluetooth` 里还是旧代码。

**ANC 语义（GAIA 机型重点）**
1. 融合中心切"降噪 → 通透 → 关"，原生设置页三段式与实际听感一致（不再把"监听"显示成"通透"）。
2. 连点"循环切换降噪模式"，三个语义档位轮转，不会卡在两档之间。

**ANC 确认**
3. 切 ANC 后 3.5 秒内 UI 落到设备真实档位；设备不回包时弹"操作未生效：…"。
4. 连续快速切两次，最终显示的是**第二次**的目标，不是第一次的回包。

**能力识别**
5. 在系统蓝牙里把耳机改名成任意中文名，重连后 LDAC / ANC / EQ 入口仍然在。

**超级岛**
6. 设置页「超级岛显示时机」勾"佩戴/摘下"，戴上弹出、摘下弹出；心跳不重弹。
7. 设置页不再出现"入盒"选项（协议没有可靠入盒位）。

**融合卡片**
8. 关掉"融合卡片能力 → 游戏模式"，融合中心面板里的游戏模式入口消失；再打开恢复。

**自动游戏模式**
9. 打开开关 → 连耳机 → 打开一个游戏，低延迟自动切到 GAME；退出游戏回到 MUSIC。
10. 手动切一次低延迟后，本条连接内不再被自动改写。
11. 关掉开关，行为完全回到手动。

**日志**
12. 设置页「导出日志」→ 分享出的 txt 里"配置快照"与当前设置一致（不是默认值）。
13. 导出后立刻清空日志，再看导出文件，不会冒出清空前的残行。

**状态显示**
14. 主界面显示影院模式/多点连接/佩戴检测/自适应开关的真实状态，不再是固定值。

**崩溃页导出（B3）**
15. 造一次崩溃（或先把 `crash_last.txt` 塞进 `filesDir`）→ 重启 App 落在崩溃页 →
    点「导出日志」→ 分享出的 txt 里「崩溃记录」区段有本次堆栈，且崩溃页**没有**被自动清掉。
16. 崩溃页连点两次「导出日志」只产生一次导出（第二次点击被进程级 gate 挡掉）。

---

## 5. 明确**没做**的事（避免误解）

- **C1–C7**（佩戴检测开关、五个自适应开关、固件版本、找耳机、媒体控制、
  型号覆盖、原生页高级功能）：这些是"底层有、UI 没入口"的新增产品功能，
  本次范围内**未做**。
- **D1–D4**（OTA、完整触控手势、完整查找流程、EQ 预设管理）：明确不在范围内。
- 多连接 / 三设备并发：未做。
- `README.md:30` 那段"现在不要下载任何版本"的告示是用户自己写的，**未改动**。

---

## 6. 已知限制

- 日志桥依赖 `PodImageProvider`（`exported=true`）。已加 UID 鉴权与载荷上限，
  但同机其他应用在极端情况下仍可能伪造 `com.android.bluetooth` 的 UID 之外的身份——
  这需要 root，按低风险接受。
- 导出只覆盖模块自己记的日志，**不含系统蓝牙栈**（本机 `logd` 只有 64 KiB，读不到）。
- 自动游戏模式的"前台是游戏"依赖 `UsageStatsManager`；用户关掉"使用情况访问"权限时
  一律 fail closed（不自动切），不会误伤。
