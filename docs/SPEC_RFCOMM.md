# RFCOMM/SPP 耳机控制器实现规格（从 OppoPods 逆推）

> 目标：让一个不了解原仓库的人，能凭本文从零写出等效控制器，并知道换成 FIIL 耳机要改哪里。
> 参考实现：`refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/pods/RfcommController.kt`（1330 行）+ `Packets.kt`（971 行）。
> 运行环境前提：代码跑在**被注入的 `com.android.bluetooth` 进程**里（Xposed 模块），不是用户 App 进程。这一点决定了后面几乎所有设计（见 §5.4）。

---

## 0. 一句话骨架

`object` 单例 → 由蓝牙进程的 A2DP 连接状态回调驱动 `connectPod()` → IO 协程里建 RFCOMM socket → 一个协程按 `read()` 返回块当整帧喂给解析链 → 状态变化通过**两次 `sendBroadcast`**（一次给自己 App，一次给系统包）广播出去；UI 反向用广播下命令。**没有写队列、没有写锁、没有分帧重组、没有超时、没有指数退避**——这些是原实现的缺口，移植时必须补。

---

## 1. 对象形状与状态字段

### 1.1 单例形态

- Kotlin `object RfcommController`，进程级单例，无构造参数，无 DI，无 `init`。
- 全类 `@SuppressLint("MissingPermission", "StaticFieldLeak")`：持有 `Context` 且不释放是刻意的（生命周期 = 蓝牙进程）。
- 对外只有 **4 个入口**：`connectPod()` / `disconnectedPod()` / `handleUIEvent()` / `currentStatusSnapshot()`；其余为 `private`。另有若干控制方法（`setANCMode` 等）被 UI 广播间接调用。

### 1.2 字段分组（照抄这四组，不要混）

| 组 | 字段 | 说明 |
|---|---|---|
| 连接物件 | `socket: BluetoothSocket?`、`mContext: Context?`、`lateinit mDevice: BluetoothDevice`、`lateinit mPrefs: SharedPreferences` | `mDevice` 用 `lateinit` + `::mDevice.isInitialized` 做空判，是全类最常见的防御写法 |
| 路由扫描 | `mediaRouter`、`scanToken`、`routes: List<MediaRoute2Info>`、`routeScanStarted` | 只为音频路由切换服务，与协议无关 |
| 会话状态 | `isConnected`、`reconnectPending`、`reconnectAttempts: AtomicInteger`、`connectionJob/reconnectJob/readerJob: Job?`、`mShowedConnectedToast` | 注意 `isConnected` 语义是「**期望有会话**」，不是「socket 已建立」 |
| 设备状态缓存 | 见 §7 | 全部靠 `handleOppoPacket` 回填 |

### 1.3 线程归属（关键，且原实现是"散的"）

- **没有专用线程**。socket 读写全部跑在 `Dispatchers.IO` 协程上：`connectionJob`（建连）、`readerJob`（读循环）、`reconnectJob`（定时重连），以及每个控制指令临时 `CoroutineScope(Dispatchers.IO).launch { ... }`。
- 后果：`sendPacketSafe()` 可能被 N 个协程同时调用 → **原实现没有互斥**。移植时用 `Mutex` 或单线程 `Channel` 消费者包住写路径（§4）。
- 主线程唯一职责是 `BroadcastReceiver.onReceive()` 分发，命中后立刻 `launch` 到 IO。

---

## 2. 连接生命周期

### 2.1 BluetoothDevice 从哪来

三条来源，互相独立：

1. **A2DP 状态回调（主路径）**：hook `com.android.bluetooth.a2dp.A2dpService.handleConnectionStateChanged`（3 参：device / fromState / currState），取 `instance.mHandler` 投递到蓝牙 HandlerThread，`STATE_CONNECTED` → `connectPod(context, device, prefs)`；`STATE_DISCONNECTING|DISCONNECTED` → `disconnectedPod()`。
2. **App 主动请求**：`ACTION_CONNECT_POD_REQUEST` 的 Intent 里带 `Parcelable BluetoothDevice`（`BluetoothDevice` 本身 Parcelable，跨进程可行），`appRequested = true`（额外刷新 UI 活跃窗口）。
3. 设备过滤：`device.name?.contains("oppo", ignoreCase = true)`。**按名字过滤是最脆的一环**，换品牌必须改这里。

### 2.2 socket 怎么建（原实现极简）

```kotlin
private const val OPPO_UUID = "0000079A-D102-11E1-9B23-00025B00A5A5"
device.createRfcommSocketToServiceRecord(OPPO_RFCOMM_UUID)
```

必须明确记录原实现**没有做**的事，避免移植时误以为需要：

- ❌ 不用 `createInsecureRfcommSocketToServiceRecord`
- ❌ 没有 channel-1 反射兜底（`createRfcommSocket(int)` via hidden API）
- ❌ 没有 `adapter.cancelDiscovery()`（连接前不取消发现，实践中会拖慢/失败）
- ❌ 没有 `socket.setSoTimeout()`（读永不超时）
- ❌ 不检查 `adapter.isEnabled`

反射确实存在，但用途完全不同（不是在建 socket 上）：
- `BluetoothProfile` 代理上 `getMethod("connect"/"disconnect", BluetoothDevice::class.java)` → 强制 HFP 连/断（`connectAudio` / `disconnectAudio`）。
- `callMethod(mDevice, "setBatteryLevel", level)` 把电量塞进系统蓝牙栈，失败后回退到 `mContext` 的 `mAdapterService` / `mService` 字段再调 `setBatteryLevel(device, level, false)`。

### 2.3 握手时序（连上之后，全部靠 `delay` 排序）

```
connect() 成功 → changeUIConnectionState("connecting") → startPacketReader(inputStream)
  delay(300) → 查询产品 ID（0x0103）
  delay(50)  → 查询通知能力（0x0200）
  delay(50)  → 状态组合查询：0x010D → 50ms → 0x0106 → 50ms → 0x010C → 50ms → 0x010F →（可选 50ms → 0x0122）
```

收到 `0x8200`（通知能力回复）后做**订阅握手第二步**：过滤掉 `id >= 0xF0` 的调试通道，把剩下的 ID 列表用 `0x0205` 回订（`[count, id...]`）。这一步的意义：不硬编码 ID 就能拿到"智能降噪当前档位"那条推送（`0x0204` type=03 key=04）。

### 2.4 重试 / 退避 / 保活

- 首次连接：`connectRfcomm(initialDelayMs = 500L)`，固定 500 ms。
- 失败（`IOException`）：广播 `"error"` → `scheduleReconnect("connect failed")` → **固定 120 000 ms 后重连**，无指数退避。`reconnectAttempts` 只自增和打日志，**不参与任何计算**（这是原实现的装饰性字段）。
- 立即重连：`reconnectNowForRequest()` 只在 `socket == null || reconnectPending` 时生效，由用户发起的包触发（`sendPacketSafe(packet, reason)` 里 reason 非 null 即视为用户发起）。已有 `connectionJob` 在跑则跳过，避免叠加。
- 去重：`reconnectJob?.isActive == true` 则直接 return。
- **保活不存在于协议层**。活性靠 UI 心跳：MainUI 每 30 s 广播一次 `ACTION_PODS_UI_INIT` + `ACTION_REFRESH_STATUS`，模块侧回 `ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE`，UI 用 75 s 窗口判定模块是否活着。移植时建议加真正的业务保活：每 20–30 s 发一次状态查询，连续 N 次无响应即判死重连。

---

## 3. 读循环

### 3.1 原实现（必须知道它有多朴素）

```kotlin
val buffer = ByteArray(1024)
while (isConnected) {
    val n = inputStream.read(buffer)
    if (n > 0) handleOppoPacket(buffer.copyOfRange(0, n))
    else if (n == -1) { scheduleReconnect("stream ended"); break }
}
```

- 一次 `read()` 的返回值被**当成恰好一个完整帧**。
- 没有累积缓冲、没有按长度字段切片、没有 `0xAA` 重同步扫描、没有最大帧长保护、没有读超时。
- 能工作纯粹因为：帧都很小（≤ 64 B），SPP 一般按 L2CAP 帧边界交付，且解析器全部做 `data.size < 9 + payLen → return null` 的自校验，宁可丢帧不会越界。

### 3.2 移植时应该写成什么样（把缺口补上）

帧头里有 `TotalLen`（`data[1]`，见 §4 帧格式），所以标准做法：

1. 维护一个 `ByteArrayOutputStream`/环形累积缓冲；每次 `read()` 追加。
2. 循环：找到魔数 `0xAA` 的位置，前面多余的字节丢弃（重同步）。
3. 若累计长度 < 2 → 等更多数据。
4. `total = 2 + buf[1]`（`TotalLen` 只计后续字节，实际整帧 = 2 + TotalLen）。
5. `total > MAX_FRAME`（建议 512）→ 判为**流已损坏**：丢弃首个 `0xAA`，回到第 2 步重同步。
6. 累计不足 `total` → 等更多数据；够了 → 切出整帧交给 `handleOppoPacket`，缓冲前移。
7. 异常：`read()` 抛 `IOException` 或返回 `-1` 才算断流；**必须判断"是不是主动断开"**（`if (isConnected)`）再决定重连，否则 `disconnectedPod()` 会被自己触发的重连救回来（原实现正是靠这个 `if` 避免的）。
8. 补 `setSoTimeout(30_000)`：超时抛 `SocketTimeoutException` → 主动发一次状态查询当作探活，连续 N 次超时才判死。

### 3.3 分发：一条 try-parse 链，顺序敏感

`handleOppoPacket(packet)` 从头到尾按固定顺序尝试解析器，先命中先 return。顺序不可随意调换，原因有两个：

- `Cmd 0x0204` 是**多路复用通道**，靠 `payload[0]` 的 report type 区分：`0x01`=电量主动上报、`0x02`=佩戴状态、`0x03`=带 key 的通知（key `0x04` = 智能降噪当前档位）。任何声称自己吃 `0x0204` 的解析器都必须先检查 type 并跳过别人的。
- 部分解析器命令集重叠。`TransparencyVocalEnhancementParser` 同时匹配 `0x0404` 与 `0x0204`，若放在 ANC 分支之前会把 ANC 包吞掉。原实现的做法是**在 ANC 分支内部**再调它，外层再调一次（作为纯 `0x0404` 回落）。

建议链序：产品 ID → 通知能力 → 智能档位 → 电量（查询响应）→ 电量（主动上报）→ 佩戴 → 空间音频（3 个）→ EQ（预设 / 详情）→ ANC（内含人声增强）→ 人声增强回落 → 开关特性查询响应 → 开关特性设置响应 → 未识别（打日志）。

---

## 4. 写路径

### 4.1 帧格式（`Packets.kt` 的唯一真源）

```kotlin
// AA | TotalLen(1B) | 00 00 | Cmd(2B LE) | Seq(1B) | PayLen(2B LE) | Payload
val totalLen = 7 + payload.size          // TotalLen 不含自身
val packet = ByteArray(2 + totalLen)     // 整帧 = 9 + payload.size
packet[0] = 0xAA; packet[1] = totalLen
packet[4] = cmd and 0xFF; packet[5] = (cmd shr 8) and 0xFF   // 小端
packet[6] = seq                            // 默认 0xF0
packet[7] = payLen and 0xFF; packet[8] = (payLen shr 8) and 0xFF
```

要点：命令字**小端**（`0x0103` → `03 01`）；设备回复命令 = 请求 `| 0x8000`；`Seq` 默认 `0xF0`，个别指令用特定值（如人声增强用 `0x57`）；有两条帧是**硬编码字节数组**（`QUERY_BATTERY` 9 字节、`QUERY_STATUS` 21 字节），因为它们带"唤醒权重"、不能按模板拼。

### 4.2 原实现的写

```kotlin
val s = socket ?: { log; scheduleReconnect(...); return }
s.outputStream.write(packet); s.outputStream.flush()
```

- 无队列、无锁、无分片。帧都很小，`write` 一次写完，不会出现半帧。
- 失败处理：`IOException` → 记日志 → `scheduleReconnect("send error", immediate = userInitiated)`。
- 发送前调用 `reconnectNowForRequest(reason)`：**用户发起的包顺便当探活**。

### 4.3 移植时必须改成的样子

1. `Channel<ByteArray>` 或 `Mutex` + 单消费者协程，保证同一时刻只有一个 `write`/`flush` 序列。
2. 帧长上限断言（> MAX_FRAME 直接拒绝，别把设备搞挂）。
3. 用 `withTimeout` 包 `connect()`（原实现裸调 `connect()`，最坏会卡到系统超时）。
4. 指令之间的间隔继续用 `delay` 也够用，但要集中到一个 `sendSequence(vararg packets, gapMs = 50)`，避免散落各处的魔数。

---

## 5. 回调与分发

### 5.1 两条广播通道（同名 action，两个受众）

```kotlin
sendAppStatusBroadcast(action)          // setPackage(BuildConfig.APPLICATION_ID)
sendExternalPodsStatusBroadcast(action) // 依次 setPackage("com.milink.service",
                                        //              "com.xiaomi.bluetooth",
                                        //              "com.android.settings")
```

两者都加 `FLAG_RECEIVER_FOREGROUND`（后台进程发广播必须，否则被延迟）。外部那条是给 MIUI 系统组件"装作原厂耳机"的，硬编码包名，移植时要么保留要么整段删掉。

App 侧通道还多一道闸：`isAppUiActive()` —— `appUiActive && elapsedRealtime() <= appUiActiveUntilMs`，窗口 75 s，由 `ACTION_PODS_UI_INIT` 刷新。**UI 不在前台就不发**，省电也省日志。

### 5.2 action 命名约定

`chen.action.oppopods.<snake_case>`，语义后缀区分方向：

- 命令（UI → 控制器）：`..._select`、`..._set`、`..._request`、`..._cycle_anc`
- 状态（控制器 → UI）：`pods_<thing>_changed`（`pods_battery_changed` / `pods_anc_select` / `pods_wear_status_changed` / `pods_connection_state_changed` / `pods_game_mode_changed` …）
- 生命周期：`pods_ui_init`、`pods_ui_closed`、`module_bluetooth_service_alive`
- 调试：`rfcomm_log`、`rfcomm_debug_send`

**已知的命名不一致（别照抄）**：`ACTION_PODS_ANC_CHANGED = "chen.action.oppopods.pods_anc_select"` —— "changed" 常量指向了 "select" 字符串，和命令 `ACTION_ANC_SELECT` 同串；`ACTION_PODS_MAC_RECEIVED` 与 `ACTION_GET_PODS_MAC` 也同串。生效原因是两者本来就被同一个 receiver 当命令收。

### 5.3 UI → 控制器

`connectPod()` 里首次注册 `BroadcastReceiver`（`receiverRegistered` 守门），列出全部命令 action，`Context.RECEIVER_EXPORTED`。**API 33 以下没有这个常量**，原实现无条件使用；要么改用两参 `registerReceiver` 并做 `SDK_INT` 判断（`PopupActivity.kt:143` 里有正确写法可抄），要么把 `minSdk` 提到 33。

### 5.4 为什么单例能跨进程活下来

控制器**不跑在 App 进程**，而是被注入到常驻的 `com.android.bluetooth` 进程。所以：

- App UI 进程被杀 / 旋转 / 重进，`object` 状态（电量缓存、ANC 状态、socket）**全部还在**，UI 重连后靠 `ACTION_PODS_UI_INIT` 一次性把快照刷回去（这是 `handleUIEvent` 里 UI_INIT 分支存在的全部理由）。
- App 侧**不能**直接调用控制器的方法，只能发广播——所有跨进程交互都退化成 `Intent` + extras。
- 反过来，控制器需要 `mContext` 是蓝牙进程的 Context，才有 `BLUETOOTH_PRIVILEGED` 等特权，`createRfcommSocketToServiceRecord` 才不会抛 `SecurityException`。
- 迁移到自己的 App 内进程时，这条前提失效：要么改成前台 Service + `StateFlow`，要么保留广播但用 `LocalBroadcastManager`/显式 ComponentName。

UI_INIT 的完整回放序列（可作为"快照应有内容"的清单）：连接态 → 电量 → 佩戴 → ANC → 智能档位 → 游戏模式 → 人声增强 → 空间音频 → EQ 预设 → 双设备连接 → （若已连）再发一次 connected。

---

## 6. 重连与清理

### 6.1 断连（`disconnectedPod`）

按序执行，顺序本身有意义：

1. `isConnected = false`（**先置位**，让读循环自然退出、让 `scheduleReconnect` 的守卫静默返回）
2. 取消 4 个 Job（connection / reconnect / reader / autoGameMode），`reconnectAttempts.set(0)`
3. `closeSocketOnly()`：先 cancel readerJob，再 `socket.close()`（吞 `IOException`），置 null
4. 停路由扫描（`cancelScanRequest` + `unregisterRouteCallback`）
5. 撤通知、广播 `ACTION_PODS_DISCONNECTED`
6. 反注册 receiver，`receiverRegistered = false`
7. 清空全部缓存（见 §6.3），`mContext = null`

### 6.2 蓝牙关闭

控制器**自己不监听** `BluetoothAdapter.ACTION_STATE_CHANGED`，靠的是 A2DP 的 `STATE_DISCONNECTED` 兜底——所以蓝牙关掉时也会走 §6.1。这样做的代价是：若 A2DP 回调丢失（hook 失效），socket 会变成僵尸。移植时建议显式监听 `ACTION_STATE_CHANGED`，`STATE_TURNING_OFF` 即主动 `closeSocketOnly()`，`STATE_ON` 时若 `mDevice` 还在则重连。

`socket.close()` 在蓝牙刚关时几乎必然抛 `IOException`，必须吞掉（原实现是 `catch (_: IOException) {}`）。

### 6.3 断连必须清什么

| 必清 | 原实现 |
|---|---|
| `mShowedConnectedToast`（下次连上要重新弹） | ✅ |
| `currentWearStatus`（回默认 `WearStatus()`） | ✅ |
| `currentAnc` → 1（关） | ✅ |
| `currentSmartAncLevel` → -1（未知） | ✅ |
| `currentGameMode` → false | ✅ |
| `currentTransparencyVocalEnhancement` → false | ✅ |
| `currentSpatialAudioMode` → OFF | ✅ |
| `currentEqPreset` → -1；`currentDeviceEqPresets` → empty | ✅ |
| `currentDualDeviceConnection` → false | ✅ |
| `currentProductId` → null | ✅ |
| `lastKnownCaseBattery` / `lastKnownCaseCharging` → 0 / false | ✅ |
| `cachedDeviceName` → "" | ✅ |
| **`currentBatteryParams`（电量缓存）** | ❌ **漏了** |

`currentBatteryParams` 是 `lateinit`，断连后不清 → 下次连上前若 UI 发 `UI_INIT`，会把上一副耳机的电量当成当前值播出去。移植时务必置为 null 并把所有读取点改成可空。

---

## 7. 电量 / 状态缓存字段与更新点

### 7.1 电量

数据结构：`BatteryParams(left/right/case: PodParams(battery, isCharging, isConnected, ...))`。

原始字节编码（**每分量 1 字节**）：`level = raw and 0x7F`，`isCharging = (raw and 0x80) != 0`。分量索引 `1=左 2=右 3=仓`。

两个入口，格式不同但都能喂给同一个 `handleBatteryChanged`：

- **查询响应** `0x8106`：payload 是纯 `[idx, raw]*` 平铺对。
- **主动上报** `0x0204` + `payload[0]=0x01`：`[0x01, count, (idx, raw)*count]`。

缓存策略：仓（case）经常不回报 → **黏住上次值**（`lastKnownCaseBattery/lastKnownCaseCharging`），但 `isConnected` 置 false，UI 才能区分"仓不在"和"仓 0%"。

聚合值：`lastTempBatt` = 左右都在时取较小值，只有一边就用那一边，都没有 = `BATTERY_LEVEL_UNKNOWN`；再通过反射 `setBatteryLevel()` 写进系统栈（状态栏电量）。

守卫：首次（`!mShowedConnectedToast`）要求**至少一只耳有有效电量（>0）**才继续，防止连上瞬间的 0% 假数据把系统电量打成 0。

### 7.2 其余状态

| 字段 | 更新点 | 取值域 |
|---|---|---|
| `currentAnc` | `handleOppoPacket` 的 ANC 分支 / `setANCMode` 乐观更新 | 1=关 2=降噪 3=通透 4=自适应 5=智能 6=轻度 7=中度 8=深度 |
| `currentSmartAncLevel` | `0x0204` type=03 key=04 位图（bit4=深度 bit5=中度 bit6=轻度） | `NoiseControlMode.ordinal` / -1 |
| `currentWearStatus` | 佩戴解析结果，**逐分量 merge**（只覆盖非 null 分量） | `0x00 断开 0x04 在仓 0x05 取出 0x07 佩戴中` |
| `currentProductId` | `0x8103` 响应，6 位十六进制 | 驱动 `detectDeviceCapabilities()` |
| `currentGameMode` | `0x810D` 响应 / `0x8403` 响应 / 乐观更新 | Bool；同时刷新 `lastGameModeStatusUpdateMs` 用于"是否真的生效"判定 |
| `gameModeFeatureId` | 产品 ID 到达后按机型能力重算；若只报了一个特性则反推 | `0x28` 主开关 / `0x06` 低延迟 |
| `currentEqPreset` / `currentDeviceEqPresets` | `0x0504` 推送 / `0x810F` 响应 / `0x0122` 响应 | 预设 ID + 自定义预设列表 |
| `currentSpatialAudioMode` | `0x0510` 推送 / `0x8422` 响应 / 开关响应 | 0=关 1=固定 2=头动 |
| `currentDualDeviceConnection` | `0x810D` / `0x8403` | Bool |

派生方法 `currentConnectionState()` 的四态：`connected`（socket 在**且**电量已初始化）/ `connecting`（有 connectionJob 或 reconnectPending，或 socket 在但电量未到）/ `disconnected`。**"connected" 是由电量驱动的**——这是原实现的隐含约定，UI 别处还有一处硬编码 `"connected"`（`handleBatteryChanged` 首次弹窗分支），移植时统一成一个 `enum`。

---

## 8. 作者踩过 / 绕过的坑

1. **`SecurityException`**：全部 Bluetooth API 调用靠 `@SuppressLint("MissingPermission")` 压掉，真正的保障是"跑在蓝牙进程里因此持有特权权限"。在普通 App 进程照抄会直接崩 —— 必须申请 `BLUETOOTH_CONNECT` 并做 `SDK_INT >= 31` 分支。
2. **`Context.RECEIVER_EXPORTED` 的 API 33 硬门槛**：控制器里无条件用了它，但同仓 `PopupActivity.kt:143` 有正确的 `SDK_INT >= TIRAMISU` 判断——说明作者知道，只是控制器没改。
3. **socket 关闭竞态**：`connectionJob.cancel()` **不会**中断阻塞中的 `socket.connect()`，只有 `close()` 能。所以顺序必须是"先 close，再 cancel/重连"；`closeSocketOnly()` 里先 cancel reader 再 close，避免 reader 拿着即将失效的流抛异常后误触发重连。
4. **主动断连不能被自己救回来**：读循环的 `catch (IOException)` 里加了 `if (isConnected)` 守卫，否则 `disconnectedPod()` → close → IOException → `scheduleReconnect()` → 又连上了。
5. **"连上了但不健康"**：`connect()` 成功只代表链路通，设备可能不应答。原实现因此不发 `"connected"`，而是发 `"connecting"`，等电量上报到了才认。这个"以业务数据为连接判据"的做法值得保留。
6. **`sendPacketSafe` 的双重身份**：既是发送，又是探活（末尾 `reconnectNowForRequest`）。用户点一下 ANC 顺带触发一次立即重连，是设计不是巧合。
7. **协议解析全部"自校验 + 返回 null"**：每个 parser 都做 `size >= 9`、`magic == 0xAA`、`cmd 匹配`、`size >= 9 + payLen` 四道关，任一条不满足返回 null，由分发链继续试下一个。这是没有分帧重组还能不崩的根本原因。移植时保留这套防御（即使加了重组）。
8. **`0x0204` 多路复用**：同一命令字承载三种上报，`payload[0]` 是 type 判别位；解析器必须"不是我的 type 就 return null"，否则互相抢包。
9. **`0x810D` 两套格式都要认**：新版是结构化 `[status, count, (featureId, enabled)*count]`，老版是扁平平铺对。先试结构化，失败再降级扫描。
10. **蓝牙关机的 `close()` 必抛**：`catch (_: IOException) {}` 空吞是标准做法。
11. **反射调 `setBatteryLevel` 要两套签名**：`(int)` 和 `(BluetoothDevice, int, boolean)`，前者在 Android 13+ 的 `BluetoothDevice` 上往往不存在，必须回退到 `mAdapterService` / `mService` 字段再调用。整段包在 `runCatching` 里，失败只打 warn 不影响主流程。
12. **`Intent.FLAG_RECEIVER_FOREGROUND`**：从蓝牙进程发广播，不加这个标志会被当成后台广播排队，表现为"状态更新慢十几秒"。
13. **SELinux**：原实现**没有**处理。若目标 ROM 的 sepolicy 禁止蓝牙进程对外发自定义广播，`sendBroadcast` 会静默失败——排查时先看 `logcat | grep avc`。

---

## 9. 移植到 FIIL 耳机（SPP `00001101-0000-1000-8000-00805F9B34FB`，帧 `FF 01 00 LEN 02 34 CMDPAYLOAD`）改动清单

> 逐条可勾，前 4 条不通过则整条链路不通。

- [ ] **1. UUID**：`OPPO_RFCOMM_UUID` → `00001101-0000-1000-8000-00805F9B34FB`（标准 SPP）。删除 `createRfcommSocketToServiceRecord` 的注释性"OPPO"字样。
- [ ] **2. 补建连健壮性**（原实现没有，FIIL 大概率需要）：
  - `adapter.cancelDiscovery()` 后再 `connect()`
  - `withTimeout(8_000)` 包 `connect()`
  - SDP 失败 → 反射 `createRfcommSocket(int channel=1)` 兜底 → 再失败 → 不加密 `createInsecureRfcommSocketToServiceRecord` 兜底
- [ ] **3. 帧构造器重写**：`AA | TotalLen | 00 00 | Cmd(2B LE) | Seq | PayLen(2B LE)` → `FF 01 00 LEN 02 34 ...`。
  - 去掉 `TotalLen`/`PayLen` 双长度字段，统一成一个 `LEN`
  - **`LEN` 到底数 payload 还是整帧，必须先用一次真机抓包确认**（原 OPPO 的 `TotalLen` 是"不含自身及魔数"，很容易抄错）
  - 魔数从 `0xAA` 改为 `0xFF`，**且 `FF` 在 payload 中出现的概率远高于 `AA`** → 重同步扫描需要额外校验 `buf[i+1]==0x01 && buf[i+2]==0x00 && buf[i+3]==LEN`
- [ ] **4. 分帧重组**（§3.2 全流程）：`0xFF` 重同步 + `LEN` 驱动切片 + `MAX_FRAME=512` 保护 + `setSoTimeout(30_000)`。原实现那套"一次 read 当一帧"在 FIIL 上不可用。
- [ ] **5. 解析链清空重写**：`Cmd` 常量表（`0x0103/0x8103/0x8106/0x0204/0x810C/0x810D/0x0404/0x0403/0x0122/0x0422/0x8200/0x0205` …）全部作废，按 FIIL 协议文档重建。`0x0204` 的 report-type 多路复用机制大概率不存在，别硬套。
- [ ] **6. 握手序列**：删掉"查通知能力 `0x0200` → 回订 `0x0205`"这套 OPPO 特有机制，替换为 FIIL 的初始化序列（无文档时先只保留"发一次状态查询"）。三段 `delay` 时序需按 FIIL 实测重定。
- [ ] **7. 设备过滤**：`device.name?.contains("oppo")` → FIIL 的识别规则（建议用 `device.uuids` 里是否含 SPP UUID + 名字白名单双条件）。
- [ ] **8. 电量解码**：`raw and 0x7F` / `raw and 0x80` 这套 1 字节编码是 OPPO 的，必须按 FIIL 重解；`lastKnownCaseBattery` 的"仓黏住"策略可保留。
- [ ] **9. ANC 模式码表**：`0x01/0x02/0x04/0x80/0x40/0x20/0x10` 与 UI 码 `1..8` 的映射全部重做；`AncImplementation.COMPATIBLE`（老机型 01/02 互换）若无对应机型，直接删掉这个分支减少复杂度。
- [ ] **10. 广播命名空间**：`chen.action.oppopods.*` → 自己的前缀；顺手修掉 `pods_anc_select` 同时表示命令与状态的撞名，以及 MAC 两个常量同串的问题。
- [ ] **11. 外部广播扇出**：`com.milink.service` / `com.xiaomi.bluetooth` / `com.android.settings` 这套是 MIUI 专用。若目标是 HyperOS 保留（小米妙享/负一屏要靠它），否则整段删除。
- [ ] **12. `miuiRefreshPayload()`**：16 字段 CSV + `255` 表示未连接 + 电量高位置 128 表示充电 + ANC 四字符码（`0103`智能/`0101`轻度/`0100`中度/`0102`深度/`0200`通透/`0201`通透人声增强）—— 这套是**小米蓝牙栈的接口约定，与耳机品牌无关**，值要保留，只有"当前 ANC 值从哪来"要改。
- [ ] **13. 反射 `setBatteryLevel`**：品牌无关（是 MIUI 蓝牙栈 API），原样保留；仍要保留两套签名回退。
- [ ] **14. 断连清理**：补上 `currentBatteryParams = null`（原实现的漏项），并把读取点改可空。
- [ ] **15. 蓝牙关闭**：新增 `ACTION_STATE_CHANGED` 监听（`STATE_TURNING_OFF` 主动 close），别只依赖 A2DP 回调兜底。
- [ ] **16. hook 挂载点**：`A2dpService.handleConnectionStateChanged` 可沿用（品牌无关），但若目标 ROM 无此方法需换 `BluetoothHeadsetService` 的连接回调；`isOppoPod()` 必须改。
