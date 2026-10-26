# 修复记录：「模块装了但检测不到已连接的 FIIL 耳机」

> 用户反馈（2026-10-24）：模块已安装到手机，耳机也确实连着，但模块界面一直显示未连接、任何控制都不生效。

本文记录**七处根因**的定位过程、证据与修复方式。全部结论来自一手代码与真机探测，不含推测。

---

## 0. 真机探测环境

| 项 | 值 |
|---|---|
| 手机 | Redmi K90 Pro Max（`25102RKBEC`），Android 17 / SDK 37 / HyperOS V816 |
| 耳机 | FIIL Key Max，`B0:F1:AA:BB:CC:DD`，固件 3.0.1.1 |
| 控制链路 | 自定义 SPP UUID `d52b47bc-2dea-42c4-b26d-20252fceeeb3` |
| 探测工具 | `tools/probe/FiilProbe`（独立 C# 实现，不复用模块代码，避免自证） |

真机原始收发（节选，`00 27` 打头的是 Mate 帧）：

```
TX UNLOCK   00 27 01 00 04 ff 02 02 34
RX          00 27 02 00 03 ff 01 ff
TX BATTERY  00 27 01 00 02 01 00
RX          02 27 02 00 05 01 03 00 62 00      ← 0x62 = 98%
```

---

## 根因 1：缺 `setUnlock` 握手 → 耳机对所有查询保持静默

**证据**：真机探测中，不发 `setUnlock` 时 VERSION / BATTERY / EQ 全部无响应；发出后每一帧都有应答。

**逆向来源**：`com/fengeek/main/i/d/h.java:712-727` `setUnlock()`：

```
setCommandClassifyId(39)
setCommandId(-1)                  // 0xFF
setBuffer(new byte[]{2, 0x34})    // GAIA vendor id
```

触发时机在 `com/fengeek/main/f053/fragment/F053MainFragment.java:275-279` 的 `case 5:`（"message: 蓝牙已连接"）——**连接成功后的第一帧**。

**修复**：
- `pods/FiilMate.kt` 新增 `object Query { const val UNLOCK = 255 }`
- `pods/FIILProtocol.kt` 新增 `fun unlock()`，`handshake()` 第一行调用

---

## 根因 2：`unlock()` 的门控写成死锁

`isMateDialect` 只由蓝牙名推断。用户改过设备名、或型号不在 `NAME_RULES` 里时 `earType = -1` → `isMateDialect = false` → 旧代码 `if (!isMateDialect) return` 直接返回 → 耳机静默 → 永远收不到 `0x00` 打头的帧 → **永远判不出 Mate**。

**修复**（`FIILProtocol.kt:83-89`）：门控改为

```kotlin
if (!ctrl.isMateDialect && ctrl.earType != -1) return
```

即「已确认 Mate」或「型号未知」都发；已确认 GAIA 的机型（含 `earType=39` 的 CC Pro2——它用自定义 UUID 但走 GAIA 帧）不发。

同时 `handshake()` 在 `earType == -1` 时**两套协议各打一发**版本查询，谁先回包 `readLoop()` 就把 `isMateDialect` 定死。为此把 Mate 那一批查询抽成 `fun mateQueries()`，并在 `FiilRfcommController.readLoop()` 运行中判出 Mate 时补调一次。

---

## 根因 3：模块激活时耳机已连接 → A2DP 回调不会再触发

`A2dpService.handleConnectionStateChanged()` **只在状态变化时触发**。而模块被 LSPosed 加载的时刻，耳机往往早就连好了（先连耳机后启用模块 / 升级后进程重启 / 蓝牙进程重启），那个回调永远不会再来。

**修复**（`hook/BluetoothProcessHook.kt`）：
- 新增 `adoptAlreadyConnected(ctx)`，在 `onHook()` 与 `AdapterService.onCreate` hook 中各调一次；
- 调度 0ms / 1500ms / 5000ms 三次（进程刚起来时 A2DP profile 可能还没 ready）；
- `enumerateA2dpConnected(ctx)` 反射枚举「当前已连接的 A2DP 设备」，见根因 3b；
- 兜底：`A2dpService` 尚未构造时，退回 `findPairedFiilDevices()` + `getProfileConnectionState(A2DP) == STATE_CONNECTED`。

### 根因 3b：Android 17 删掉了 `A2dpService.sA2dpService`

| 版本 | 入口 |
|---|---|
| Android 15 及更早 | `A2dpService.sA2dpService`（`A2dpService.java:182`）→ `getConnectedDevices()`（`:748`） |
| Android 17 | 静态字段**已删除** → `AdapterService.sAdapterService`（`AdapterService.java:290`）→ `Optional getA2dpService()`（`:1396`）→ `Optional.get()` → `getConnectedDevices()`（`A2dpService.java:821`，返回裸 `List`） |

`a2dpServiceInstance()` 实现三级兜底，任一级失败退下一级。

---

## 根因 4：`createSocket()` 只看「创建成功」，把 Mate 机型永久挡在门外

`createRfcommSocketToServiceRecord()` **只创建 socket 对象，不做 SDP、也不报错**——真正的建链发生在 `connect()`。

旧实现「创建成功就 return」→ 标准 SPP 的 socket 永远先返回 → `sock.connect()` 对 Mate 机型必然失败 → 整体连接失败 → 自定义 UUID 那条路**根本执行不到**。

**修复**（`FiilRfcommController.kt:467-492`）：`createSocket()` 换成 `openSocket()`——三条候选（标准 SPP `00001101-0000-1000-8000-00805F9B34FB`、自定义 `d52b47bc-...`、反射 `createRfcommSocket(1)`）全部先建好，再**逐个 `connect()`**，成功即返回，失败就 `close()` 继续下一条。

---

## 根因 5：`FiilMate.parse()` 把 type 字节写死成 `0x01`，丢弃全部耳机回包

偏移 2 是 **type**，不是版本号：

| 值 | 含义 |
|---|---|
| `0x01` | REQUEST（模块发出） |
| `0x02` | RESPONSE（耳机应答——真机全部回包都是这个） |
| `0x03` | NOTIFY（耳机主动上报） |

旧代码 `if (buffer[offset+2] != 0x01) return null` 把所有 RESPONSE 当杂散字节丢弃 → **状态永远收不到**。`drain()` 里同样错。

**修复**：`parse()` 与 `drain()` 均改为接受 `0x01 / 0x02 / 0x03`。

> 连带修了一个测试：`ProtocolFrameTest.mateParseRejectsWrongMagic` 原本断言 `byte[2]=0x02` 必须返回 null —— **把 bug 固化成了"正确行为"**。已改写为：`0x02/0x03` 必须可解析、`0x09` 必须拒、`reserved != 0` 必须拒。

---

## 根因 6：`connect()` 是同步阻塞，被主线程调用会把蓝牙进程拖到 ANR

`BluetoothProcessHook` 里 4 处 `connect()` 调用**全在蓝牙进程主线程**（A2DP 回调、`onReceive`、adopted 定时任务、`handleCommand`）。而 `openSocket()` 逐条 `sock.connect()`，标准 SPP 失败常需 1~3 秒，三路累计 10 秒以上。

后果：**UI 每次刷新状态 → `onReceive` → 同步 `connect()` → 主线程卡死 → 蓝牙进程 ANR 被系统重启 → socket 与状态全丢**。

**修复**（`FiilRfcommController.kt:308-334`）：
- `connect(device)` 改为**异步、无返回值**：`connectInFlight` 去重 + 起 `FiilPods-Connect` 守护线程；
- 原同步实现改名 `connectBlocking(device): Boolean`，函数体包在 `synchronized(connectLock)` 里，逻辑一字未改；
- 补连线程本来就跑在后台，直接调 `connectBlocking()`。

---

## 根因 7：状态广播发给了蓝牙进程自己，模块 UI 一条也收不到

`publishState()` 与 `broadcastOpFailed()` 原写：

```kotlin
ctx.sendBroadcast(Intent(...).setPackage(ctx.packageName))
```

但 `ctx` 是 **`com.android.bluetooth` 进程的 Context**，`ctx.packageName == "com.android.bluetooth"`。于是完整状态 JSON 全部**绕回蓝牙进程自身**，模块 UI 进程 `moe.chenxy.fiilpods` 一条都收不到 → `StatusRepo.status` 永远是 `FiilStatus.EMPTY` → 界面一直显示「未连接」。

这一条与「模块已注入、耳机已连、UI 仍检测不到」的症状完全吻合，是**最直接**的一处。

**修复**：两处改为 `.setPackage(FiilConfig.MODULE_PACKAGE)`（= `moe.chenxy.fiilpods`），并加注释说明为什么不能用 `ctx.packageName`。

> 全模块 14 处 `sendBroadcast` 已逐条扫描：仅这两处原本错，其余（发往 `com.android.settings` / `com.android.bluetooth` / `com.android.milink.service`）全部正确。

---

## 验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | EXIT=0 |
| `:app:testDebugUnitTest` | EXIT=0（38 用例全过） |
| `:app:assembleDebug` | EXIT=0 → `app-debug.apk` = 33,160,695 B |
| `:app:assembleRelease` | EXIT=0 → `app-release.apk` = 2,496,726 B |
| release dex 标记校验 | `adopt: a2dp-connected` / `getA2dpService` / `sAdapterService` / `FiilPods-Connect` / `connect skipped: another attempt in flight` 全部存在 |
| 签名 | 两个包均 Android Debug 证书，SHA-256 `605996dc…138d34`，与手机上已装那版**同一证书** → 可直接覆盖安装 |

---

## 装机步骤

手机上那版是 `2026-10-24 16:56:28` 装的，**不含以上任何一处修复**（dex 字符串差分已证实：`adopt: a2dp-connected`、`getA2dpService`、`sAdapterService`、`FiilPods-Connect` 这四个标记在旧包里全都不存在）。

因为签名相同，直接覆盖安装即可，**不需要先卸载**：

```
adb install -r "<repo>\app\build\outputs\apk\release\app-release.apk"
```

或在手机上直接点开 `app-release.apk` 安装。

装完后：

1. 在 LSPosed 里确认模块对 `com.android.bluetooth`、`com.android.settings`、
   `com.xiaomi.bluetooth`、`com.milink.service` 四个作用域都启用；
2. 强制停止一次「蓝牙」（设置 → 应用 → 显示系统应用 → 蓝牙 → 强制停止），让新代码重新注入；
   或直接开关一次飞行模式；
3. 打开 FiilPods，应能看到电量 / MAF / EQ 等状态。

---

## 仍未解决

- **本轮无法真机验证**：装机需要改动手机，用户明确要求只读操作，因此由用户自行安装后确认。
- logcat 在这台机器上不可用（`ro.logd.size = 64K`，缓冲区 `0 B readable`），后续排查只能靠模块自己写文件或 UI 反馈。
