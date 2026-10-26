> ⚠️ **本文是 FiilPods 早期版本的 README（649 行技术长文），保留作为完整技术档案。**
>
> 仓库首页已改为更精简的 [README](../README.md)；实现原理请优先看 [IMPLEMENTATION.md](IMPLEMENTATION.md)。
> 本文的价值在于：它记录了 v1.0.0 ~ v2.1.0 期间**每一处细节的来龙去脉**（协议帧逐字节、源码结构、构建排错、交付状态），这些内容没有全部搬进新文档。

---
# FiilPods — FIIL 耳机 HyperOS 系统级控制模块

把 FIIL 耳机接入 HyperOS 的原生耳机体验：系统蓝牙设置页出现电池/降噪卡片、控制中心超级岛提示、独立控制台。**界面与设置项完全对齐 OppoPods 2.2.0**（三 Tab 底栏 / 模块页 / 耳机页 / 设置页），只是控制的耳机换成 FIIL。

- **包名**：`moe.chenxy.fiilpods`
- **版本**：v2.1.0（`versionCode 3`，2026-10-26）。v1.0.0 / v2.0.0 均归档在 `dist\`，包名与签名一致，可直接覆盖安装
- **产物**：`dist\FiilPods-v2.1.0-release.apk`（**3.88 MB**，日常使用装这个）／`dist\FiilPods-v2.1.0-debug.apk`（34.1 MB，排查问题装这个）
- **运行要求**：Android 15+（minSdk 35）、HyperOS 2/3、LSPosed（libxposed API 102）

---

## 一、它做了什么

| 能力 | 说明 |
|---|---|
| **界面（移植自 OppoPods）** | 三 Tab 底栏：**首页**（模块激活状态 / 蓝牙状态 / 配对数量 / 系统与环境信息卡）· **耳机**（设备列表 + 详情页）· **设置**（主题 / 语言 / 日志级别 / 隐藏图标 / 焦点通知 / 超级岛 / 互联卡片 / 伪装型号）。形态与 OppoPods 2.2.0 完全一致 |
| **设置页原生集成** | 系统「设置 → 蓝牙 → FIIL 耳机」页面出现与小米自家耳机一致的电池卡片、降噪三/五档切换；**并在下方额外挂一张自建控制卡片**，承载宿主没有的影院模式 / 低延迟三档 / 蓝牙畅连 / 自定义 EQ 入口；右上角「⋮」菜单多一项「FiilPods 高级设置」直达模块控制台 |
| **融合设备中心** | 下拉控制中心的耳机卡片：显示电量、切换降噪、空间音频/游戏模式开关 |
| **超级岛提示** | 电量变化与降噪切换时在状态栏顶部弹出 HyperOS 强提示，**并常驻超级岛**显示「当前 MAF 模式 · 电量」；断开时自动收起 |
| **型号自适应** | 按 `earType` 识别 50+ 款 FIIL 耳机，自动决定档位数量、EQ 预设、可用开关；识别不出或认错时可在控制台**手动指定型号** |
| **半透明弹层** | 从通知或快捷方式拉起紧凑版控制面板：电量卡 → 降噪卡 → 低延迟卡 →「更多 / 完成」。竖屏三卡纵排，横屏 0.6/0.4 分栏（对齐 OppoPods `PopupActivity`） |

### 控制项

- **降噪（MAF）**：关闭 / 降噪 / 开放 / 兼听 / 风中（五档；F051 CC3 只有前三档，F045/F047/F048 无 ANC。文案取自 FIIL+ 布局原文，**不是**「通透/监听」，也**没有**「自适应」档 —— 智能调噪的「智能」指算法本身）
- **自适应开关**：环境自适应降噪 / 自适应抗风噪 / 耳道自适应降噪 / 自适应 EQ / 云感调音（Mate cid=1，commandId 16/17/15/14/8，按机型显示）
- **EQ**：12 组预设（原声 / 怀旧 / 爵士 / 轻音乐 / 家庭影院 / 金属 / 流行 / R&B / 舞曲 / 摇滚 / 电子 / 轻低音 / 新时代）+ **10 段自定义图形 EQ**
- **影院模式**（空间音频）：仅 F046（Key Max）支持
- **蓝牙畅连**（多点连接）：所有 SPP 机型
- **佩戴检测**：摘下暂停 / 戴上播放，仅 F051（Key Pro2）、F053（CC Pro3）
- **低延迟**：音乐 / 游戏 / 视频（GAIA 机型三档，线值 0/1/2；Mate 机型只有音乐/游戏，选视频按游戏下发）
- **语音提示音量**（Mate 专有）：高 / 中 / 低 / 静音四档，cid=1 cmdId=3，线值 0=高 … 3=静音（与直觉相反，0 最响）。真机回包 01 27 02 00 03 82 01 02 里 01 是 payloadLen、02 才是音量 —— payload 只有 1 字节，别按「第 1 字节」取
- **对话框三档联动**：详情页的「低延迟」下拉与「游戏模式」开关互相同步（只有 GAME 档算「游戏模式开启」），两条广播独立不打架
- **手动选择型号**：控制台底部「机型信息」卡里的「手动选择型号」下拉，选完立即切换协议族与能力面（`-1` / 「恢复自动识别」回到自动推断）。用途：蓝牙名被改过、老机型没进型号表、或逆向里尚未验证的型号
- **型号识别与地址绑定**：显示名优先用系统蓝牙设置里的别名（用户改的名字），但型号识别只用**厂商原始名**（`getName()`）；同时把「MAC 地址 → earType」记在模块自己的偏好里（键 `ear_type_for_<MAC>`），手动指定或耳机回包识别出的型号都会落一份。这样即使用户把蓝牙名改成「我的耳机」、或覆盖项被清掉，同一只耳机下次连接仍能认回原型号
- **品牌判定三路兜底**：名字关键字 → MAC 前缀 `B0:F1:A3` / `FF:F1:A3` → 已知地址表。MAC 前缀来自 FIIL+ 自己的 OTA 页判定（`BaseFragment.java:28` 等 15 处 `startsWith("B0:F1:A3")`），用于蓝牙名被改得完全看不出品牌的情况
- **操作**：寻找耳机、音量加减、播放/暂停/上一首/下一首

> 双协议栈：老机型走 **GAIA**（16 位命令号，`FF 01/F1 …02 34`），F045~F053 走 **Mate**（小整数 classifyId，`00 <cid> 01 00 …`）。模块按首帧 `buf[0]==0x00` 自动识别，两条链路共用同一条 SPP。

---

## 二、安装

```bash
# 1. 构建（必须 Gradle ≥ 9.3.1）
set JAVA_HOME=C:\Program Files\Amazon Corretto\jdk21.0.11_10
D:\gradle-9.4.1\bin\gradle.bat :app:assembleDebug

# 2. 产物
#    app\build\outputs\apk\debug\app-debug.apk
```

```bash
# 安装到手机
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

然后：

1. 打开 **LSPosed** → 模块 → 启用 **FiilPods**
2. 作用域勾选：`com.android.bluetooth`、`com.android.settings`、`com.xiaomi.bluetooth`、`com.milink.service`
3. 重启手机（或重启这四个作用域进程）
4. 连接 FIIL 耳机 → 打开系统蓝牙设置页，应看到新增的电池与降噪卡片
5. 该页右上角「⋮」菜单里会多一项 **「FiilPods 高级设置」**，点它进模块完整控制台
   （机型选择、自定义 EQ、自适应开关、融合设备中心相关开关都在那里）

---

## 三、技术架构

### 3.1 双传输层：SPP 与 BLE GATT

FIIL 主力机型用**经典蓝牙 SPP（RFCOMM）**承载私有 GAIA 协议，这是逆向 FIIL+ 3.5.2 APK 后最重要的结论：

```
UUID: 00001101-0000-1000-8000-00805F9B34FB   (标准 SPP)
      d52b47bc-2dea-42c4-b26d-20252fceeeb3   (CC Pro2 等新芯片专用)
```

**但 SPP 并非在所有机型上都可靠。** 2026-10-25 用 PC 端 Bleak 直连 FIIL Key Max（`B0:F1:AA:BB:CC:DD`，固件 3.0.1.1）实测：

| 通道 | 建链 | 数据 | 稳定性 |
|---|---|---|---|
| 标准 SPP `00001101-…` | 能连上 | **完全静默**，零回包 | — |
| 自定义 SPP `d52b47bc-…` | 能连上 | 有回包 | 被耳机反复踢掉（`dumpsys bluetooth_manager` 里 `scn:6 opened=21 closed=0 leaked=21`） |
| **BLE GATT `ff17`/`ff18`** | 能连上 | 有回包 | **稳定** |

所以模块做成**双传输层、同一套帧编码**：

```
                    +-----------------------------+
   上层 FIILProtocol |  GAIA 帧 / Mate 帧（与传输层无关）|
                    +-------------+---------------+
                                  | 同一份 ByteArray
              +-------------------+-------------------+
              v                                       v
    FiilRfcommController（SPP）              FiilGatt（BLE GATT）
    UUID_SPP / UUID_CC_PRO2 / ch1          ff17 写（No Response）/ ff18 收
              |                                       |
              +---- SPP 三条全失败 -----------------+
                    或连上后 3.5 s 零回包
                    -> 自动降级到 GATT
```

关键点：**BLE GATT 的帧格式与 SPP 逐字节相同**，不需要任何封装；`setUnlock` 握手在两个通道上都必须先发。

模块在 `com.android.bluetooth` 进程内持有这条链路，直接与耳机收发帧。

> 为什么不能像 OppoPods 那样只走一种通道？FIIL 不同芯片方案（GAIA / Mate / 豆包）的传输层选择不同，同一机型上 SPP 与 BLE 的表现还会随固件变化。做成「先 SPP、静默就换 GATT」是实测下来唯一能覆盖全系的策略。

### 3.2 GAIA 帧格式

```
┌────┬──────┬───────┬─────┬───────┬─────────┬─────────┐
│ FF │ ver  │ flags │ len │ 02 34 │ cmdH L  │ payload │
└────┴──────┴───────┴─────┴───────┴─────────┴─────────┘
  SOF  0x01  0x00   payload  vendor  16-bit cmd
       0xF1          长度     0x0234
```

- `ver = 0x01`：旧协议；`ver = 0xF1`：新协议（新机型用）
- `cmd & 0x8000`：ACK 标志；`cmd & 0x7FFF`：真实命令号

### 3.3 关键命令

| 命令 | 值 | 方向 |
|---|---|---|
| `GET_EARTYPE` | 777 | 查询型号 |
| `GET_ANC` / `SET_ANC` | 641 / 513 | 降噪 |
| `GET_EQ` / `SET_EQ` | 643 / 515 | 音效 |
| `GET_3D` / `SET_3D` | 644 / 516 | 3D 音效 |
| `GET_WEAR` | 391 | 佩戴状态 |
| `SET_WEAR` | 263 | 佩戴检测开关（**1=开、2=关**） |
| `GET_WEARING_SWITCH` | 1917 | 佩戴检测开关状态 |
| `GET_BATTERY_LEVEL_V2` | 1800 | 电量 |
| `GET_BATTERY_OF_BOX` | 1915 | 仓电量 |
| `SET_LOW_LATENCY` | 1826 | 低延迟 |
| `SET_VOLUME` | 262 | 音量 |

> 电量解析有个坑：`payload[2]` 是**十进制字符串**，要按十六进制解析后 **+1**。充电盒（1915）则不加 1。

**Mate 命令空间的 GET 与 SET 是两套语义**（这是最容易踩的坑，详见 `docs\PROTOCOL_FIIL.md` §3.12）：

```
GET：cid 恒为 39，cmdId 填查询码，两层长度字段
  00 27 01 00 02 0C 00        查 MAF
SET：cid = 命令族，cmdId 恒为 0，只有一层长度字段
  02 2C 01 00 01 02           设 MAF=2（cid=44）
```

SET 成功应答 `payload[0]==0`；MAF/低延迟/空间音频/多点/媒体键均已 PC 端实测通过。
**EQ 写入是例外**：`SET_EQ`（cid=32）返回成功但读回曲线不变，Key Max 上未生效，属未确认项。

### 3.4 设置页注入原理

设置页分两层：宿主的**原生 UI 复用**（伪造数据喂给它），加上我们**自建的一张控制卡片**
（补宿主根本没有的 FIIL 私有能力）。

#### 3.4.1 复用宿主原生 UI

Compose 无法跨 ClassLoader 在 `com.android.settings` 进程运行，所以不塞 Compose，而是复用宿主原生 UI：

1. 伪造设备 ID `01010607` + 能力串 `01010607,000000000000000010000000`
2. Hook `MiuiHeadsetActivity` / `MiuiHeadsetActivityPlugin` 的 Intent 参数
3. Hook `IMiuiHeadsetService$Stub$Proxy` 的 12 个方法，全部返回伪造数据
4. 通过反射回填 `updateAtUiInfo()` / `updateAncUi()` / `refreshStatus()`
5. 往详情页的溢出菜单（右上角「⋮」）加一项 **「FiilPods 高级设置」**，
   点它跳到模块自己的完整控制台 —— 目标第三阶段「设置集成」的入口

第 5 条只挂 `MiuiHeadsetFragment#onCreateOptionsMenu` 与
`MiuiHeadsetActivity(Plugin)#onCreateOptionsMenu`，框架级
`Activity#onCreateOptionsMenu` 的兜底还必须过 `isHeadsetDetailActivity()` 判定
（类名或 Intent 里的伪造能力串），否则整个设置 App 每一页都会长出这个菜单项。
菜单标题写死字面量，不取 `R.string` —— 设置进程的 ClassLoader 加载不到模块资源。

#### 3.4.2 自建控制卡片（`SettingsControlCard.kt`）

宿主页面由 MIUI 的 MMA 协议驱动，只认识**电量**与**降噪档位**。FIIL 的影院模式、
低延迟三档、蓝牙畅连、自定义 EQ 它一概没有 —— 这些能力只能由我们自己补一张卡片。

| 项 | 实现要点 |
|---|---|
| 控件类型 | 只用 framework 类（`LinearLayout` / `TextView` / `Switch` / `GradientDrawable`），**绝不加载 Compose / MIUIX** —— 跨 ClassLoader 拿不到模块资源表，必然崩 |
| 挂载位置 | BFS 找页面里第一个「有子 View 的纵向 `LinearLayout`」。必须钻过 `ScrollView`，直接 `addView` 会抛 `ScrollView can host only one direct child` |
| 幂等 | 卡片用 `View.generateViewId()` 生成 id，判重用 `findViewById`。**不能用 `findViewWithTag`** —— 它只比对普通 tag，keyed tag 永远查不到，会导致页面重建时卡片重复叠加 |
| 挂载时机 | `onCreateView` 与 `onServiceConnected` 都试。前者刚 inflate 完，容器常常还是空的（此时 `attach` 返回 null）；后者 `getView()` 才是完整树 |
| 内容 | 电量行 → 调噪模式芯片 → 影院模式开关 → 低延迟三档芯片（音乐/游戏/视频）→ 蓝牙畅连开关 → 「打开均衡器」 |
| 档位门控 | 档位数量按 `earType` 动态重建：Mate 五档 / Mate 四档 / GAIA 五档 / 默认三档；影院与畅连各自按 `FiilModels` 白名单显隐 |
| 回声防护 | `Switch.setOnCheckedChangeListener` 在代码里赋值 `isChecked` 时**同样回调**。回填状态期间必须置 `BindingFlag.active`，否则「回填 → 下发 → 回包 → 再回填」会死循环 |
| 下发通道 | 复用 `FiilAction.ACTION_COMMAND` 广播到蓝牙进程，与模块 UI 完全同一条链路，协议细节只实现一份 |

### 3.5 跨进程通信

四个进程（bluetooth / settings / xiaomi.bluetooth / milink.service）通过广播通信：

```
蓝牙进程 ──ACTION_PODS_BATTERY_CHANGED──▶ 设置进程 / 融合中心
         ──ACTION_PODS_ANC_CHANGED────▶
         ──ACTION_PODS_CONNECTED──────▶
设置进程 ──ACTION_ANC_SELECT─────────▶ 蓝牙进程
融合中心 ──ACTION_COMMAND / ANC_SELECT─▶ 蓝牙进程
```

广播都用 `setPackage()` 逐个点名投递（`FiilAction.STATUS_TARGETS`），
不依赖隐式广播的注册者是否存在。

### 3.6 超级岛：`param` 与 `island_param` 是两个通道

HyperOS 的 `StatusBarManager#setStatus(1, "strong_toast_action", bundle)` 里，
`param` 是**一次性强提示气泡**，`island_param` 才是**超级岛**的渲染载荷 ——
两个字段互相独立，只设 `param` 会弹一下气泡但不驻留到岛上。模块两路都填：

| 字段 | 内容 | 说明 |
|---|---|---|
| `param` | `{"left":…,"right":…}` | 气泡：左=`模式 · 左耳电量`，右=右耳电量（无右耳时退回盒电量） |
| `island_param` | 同上，但 `iconType=0`、`turnAnim=true` | 岛上用文字型图标 + 数字翻转动效（对齐 `refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/utils/miuiStrongToast/MiuiStrongToastUtil.kt:137-149`） |
| `notifyId` | `fiil_pods_battery` | 去重：同一 id 重复下发只更新，不叠加多条 |
| 断开时 | `status_bar_strong_toast=hide_strong_toast` | 主动收起，避免岛上残留旧电量 |

模式文案按协议族分流（`FiilModels.ancLabel` / `mateMafLabel`）——Mate 的 2 是「开放」、
GAIA 的 2 是「监听」，共用一套文案会写错档位名。

### 3.7 操作确认与失败回退

**目标要求「降噪/模式切换失败时有回退提示，不静默失败」，这里是怎么做到的：**

FIIL 的 GAIA / Mate 协议并不对每条 `SET` 命令都回 ACK。控制器为此维护一张
「待确认操作」表（`pendingOps`），登记时记下操作前读到的状态值：

```
用户操作 → 登记 pendingOps[key]（记 beforeValue）
        → 发出命令
        → 2.5s 内收到回包？ ── 是 → ackOp(key) 撤销，UI 正常刷新
                            └ 否 → 重读状态
                                   ├ 值已变 → 视为成功（耳机回了但没走标准回包）
                                   └ 值未变 → 发 ACTION_OP_FAILED → UI Toast 提示
```

三条实现上的取舍，都是踩过之后才定的：

| 情形 | 处理 | 原因 |
|---|---|---|
| 用户点的就是**当前档位** | 直接跳过等待（`expectAckIfChanged`） | 状态不会变，若仍等待就会误报失败 |
| **EQ 写入** | 严格等回显（`expectAckStrict`） | 写自定义档时 `eqIndex` 恒为 -1，按「值有没有变」判断会误报 |
| **连接断开** | `cleanupSocket()` 清空 `pendingOps` 与 `pendingTargets` | 否则断线后还会叠一条「操作未生效」，用户看到的是断线，不该再被误导 |
| **Mate 的 SET 回显** | 发送时把目标值记进 `pendingTargets`，回显到达时取出采用 | cid=1/32/37/38/50 的回显**不带新状态**（空或 `[0]`）；按 `payload` 解释会把「刚打开」错记成关闭，按取反推定在连点两次时又会与真实状态错位 |

最后一条是本轮修掉的真实缺陷：`cid=1` 下挂着多点与五个自适应开关，回显只能靠
`commandId` 分辨，且回显值恒为 `0`（成功），因此状态必须来自「我们发出去的目标值」，
不能来自回包。

超时值取 **2.5s**，与 FIIL+ 自己 `CommandQueue` 的 3s 超时同量级。

### 3.7.1 断线自动重连

A2DP 的连接状态**不能**代表控制链路还活着：耳机放回仓、走出范围、手机切飞行模式时，
`A2dpService` 往往仍报 `STATE_CONNECTED`，而 SPP socket 已经断了。只靠 A2DP 回调
驱动连接，会出现「设置页显示连着、但点什么都不生效」的假死。

`FiilRfcommController.disconnect()` 因此把断开分成两类：

| 原因字符串 | 来源 | 是否自动补连 |
|---|---|---|
| `read-loop-exit` / `input-failed` | socket 自己断了（意外） | 是，每 5s 一次共 3 次 |
| `user-request` / `a2dp-disconnected` / `stop` / `reconnect` | 用户或系统主动 | 否，并打断排队中的补连 |

补连跑在独立线程（`connect()` 会阻塞在 `socket.connect()`，不能占用蓝牙进程的 handler 线程）。
连上后照常走 `FIILProtocol.handshake()`，所以重连即恢复状态同步 —— 这是目标第二阶段
「断线重连时恢复状态同步」的实现方式，而不是被动等 A2DP 再报一次连接。

### 3.8 融合设备中心（`com.milink.service`）

下拉控制中心里的耳机卡片由 `com.xiaomi.mxbluetoothsdk` 驱动，只认白名单里的
米系/大厂私有协议，FIIL 不在其中。模块**不实现小米协议**，而是在 SDK 的取值点上拦截：

| SDK 取值点 | 伪造方式 |
|---|---|
| `MxBluetoothService/Manager.checkIsMiTWS` | 返回 `1` |
| `getDeviceId` | 返回伪 ID `01010607`（与设置页注入同一套） |
| `getBatteryLevel` / `getPowers` | 取左右耳较低者；三路明细走 `[盒, 左, 右, 充1, 充2, 充3]` |
| `getAncState` / `getMode` | FIIL MAF 五态 → MIUI 三态（监听/风中归到通透） |
| `getSpatialMode` / `getFindRingState` | 影院 → `2`（头动跟踪位）、游戏模式 → `100` |

反向控制：卡片上点降噪 / 空间音频 / 游戏模式，SDK 调 `openAnc` /
`setMiAudioEffect` / `setFindRing`，我们 `hookBefore` 直接短路，转成
`FiilAction.ACTION_ANC_SELECT` 或 `ACTION_COMMAND` 广播发给蓝牙进程，由真正的
SPP 链路下发。状态侧由 `headsetPropertyChangeListener.invoke(device, updateType)`
反向通知刷新（4 电量 / 8 降噪 / 9 空间音频 / 10 游戏模式）。

因为 milink 与蓝牙是两个进程，状态同时落盘到 `SharedPreferences("fiilpods_milink_state")`，
每个 getter 进入前先 `loadState()`。

「寻找耳机」卡片被改写成「游戏模式 已开启/已关闭」，点击即切低延迟——这与设置页里
那张卡片的行为一致，避免控制中心出现一个点了没反应的入口。

---

## 四、源码结构

```
app/src/main/java/moe/chenxy/fiilpods/
├── FiilPodsApp.kt               Application（持有 xposedService 引用）
├── MainActivity.kt              模块设置入口（Xposed 模块页）
├── PopupActivity.kt             从系统蓝牙页拉起的耳机控制弹窗
├── config/
│   ├── ConfigManager.kt         配置持久化（主题/语言/日志级别/隐藏图标…）
│   ├── LeAudioConfig.kt         LE Audio 配置项
│   ├── PodImagePrefs.kt         自定义耳机图片偏好
│   └── PodImageProvider.kt      图片 ContentProvider
├── hook/
│   ├── HookEntry.kt            Xposed 入口，按包名分发
│   ├── HookContext.kt          Hook 基类（hookBefore/After/ConstructorAfter）
│   ├── Log.kt                  统一日志（脱敏/级别过滤）
│   ├── BluetoothProcessHook.kt 蓝牙进程：启动控制器 + 广播接收 + 启动补偿枚举
│   ├── SettingsHook.kt         设置页注入（核心）
│   ├── SettingsControlCard.kt  设置页自建控制卡片（framework 控件）
│   ├── MiBluetoothToastHook.kt 超级岛强提示
│   └── milink/
│       ├── MiLinkServiceHook.kt      融合设备中心：MxBluetoothSDK 取值伪造
│       └── MiLinkSpatialAudioHook.kt 融合设备中心：空间音频/游戏模式子模块
├── pods/
│   ├── FiilGaia.kt             GAIA 帧组包/解包（老机型）
│   ├── FiilMate.kt             Mate 帧组包/解包（F045~F053）
│   ├── FiilGatt.kt             ★ BLE GATT 传输层（ff17 写 / ff18 收）
│   ├── FiilCommands.kt         GAIA 命令常量表
│   ├── FIILProtocol.kt         ★ 语义化协议层：双栈分派 + 全部控制/查询命令
│   ├── FiilRfcommController.kt ★ 传输调度：SPP 建链、双协议分帧、GATT 降级、待确认操作表
│   ├── OppoCompatBridge.kt     把 FIIL 状态翻译成 OppoPods 广播契约（供移植来的 UI 使用）
│   ├── FiilModels.kt           earType→型号映射、按机型的能力表
│   ├── DeviceCapabilities.kt   由 earType 推导的能力集
│   ├── NoiseControlMode.kt     降噪模式枚举
│   ├── FiilStatus.kt           状态数据类（含影院/多点/佩戴检测/自适应开关/EQ 10 段）
│   ├── FiilAction.kt           广播契约
│   ├── FiilConfig.kt           配置常量
│   ├── BatterySnapshot.kt      电量快照（含 MIUI 编码）
│   └── StringToastBundle.kt    强提示 Bundle 构造
├── ui/                         ★ 整体移植自 OppoPods 2.2.0，形态完全一致
│   ├── App.kt / AppLocale.kt / Theme.kt        应用外壳、多语言、主题
│   ├── MainUI.kt               ★ 1059 行：三 Tab 导航 + 全部页面路由
│   ├── MainTabs.kt / MainTab.kt / MainBottomNavigation.kt   底栏与分页
│   ├── components/             AncSwitch / PodStatus / AppIcons
│   ├── dialogs/                LE Audio / 耳机图片 / 重启作用域
│   └── pages/                  HomePage / EarphonesTabPage / PodDetailPage /
│                               SettingsPage / ThemeSettingsPage / EqualizerPage /
│                               DevicePickerPage / LeAudioSettingsPage / AboutPage /
│                               RfcommDebugPage
└── utils/
    ├── FocusIslandUtil.kt      焦点通知通道
    ├── MediaControl.kt         媒体键
    ├── miuiStrongToast/        HyperOS 强提示（MiuiStrongToastUtil + data/*）
    ├── PodImageLoader.kt       耳机图片加载
    ├── RootManager.kt          root 工具（重启作用域用）
    └── SystemApisUtils.kt      系统 API 反射封装
```

共 **73 个 Kotlin 文件 / 约 16,292 行**（其中 `ui/` 层约 7,000 行整体移植自 OppoPods 2.2.0）。

---

## 五、构建排错

| 现象 | 原因 | 解决 |
|---|---|---|
| `Minimum supported Gradle version is 9.3.1` | Gradle 太旧 | 用 `D:\gradle-9.4.1`，不要用 9.2.1 |
| `Unresolved reference: MiuixIcons` | MIUIX 版本不匹配 | 确认 `miuix-ui` / `miuix-icons` 都已引入 |
| 模块启用后无效果 | 作用域没勾全 | 四个包都要勾，且需重启进程 |
| 设置页没出现卡片 | 设备名不匹配 | 检查 `FiilConfig.isFiilName()`，可临时放宽 |
| 电量显示错误 | 解析公式 | 检查是否按 hex 解析并 +1 |
| `Could not resolve org.jetbrains.kotlin:compose-group-mapping` | 用了 `--offline` | release 构建会跑 `produceReleaseComposeMapping`，**必须联网**，不要加 `--offline` |

### 5.1 构建产物体积

| 产物 | 大小 | 说明 |
|---|---|---|
| `FiilPods-v2.1.0-debug.apk` | **34.1 MB**（32.6 MiB，35,713,691 B） | 不混淆，Compose/MIUIX 全量打入；**排查问题装这个** |
| `FiilPods-v2.1.0-release.apk` | **3.88 MB**（3.70 MiB，4,063,403 B） | R8 混淆 + 资源裁剪，**日常使用装这个** |

> **v2.1.0 实测（2026-10-26）**：debug 35,713,691 B / release 4,063,403 B。
> 相对 v2.0.0 几乎持平（+0.1 MB），新增的是 `HookEntry` 远端 prefs 注入、
> `MiLinkServiceHook.syncAncModel` 与焦点通知渠道。所有历史版本产物都归档在 `dist\`：
> `FiilPods-v1.0.0-{debug,release}.apk`、`FiilPods-v2.0.0-{debug,release}.apk`、
> `FiilPods-v2.1.0-{debug,release}.apk`。

体积优化过程（69.5 MB → 2.38 MB，降幅 96.6%）：

| 措施 | 效果 | 依据 |
|---|---|---|
| 删除 `androidx.compose.material:material-icons-extended`、`androidx.compose.material3`、`compose.ui.tooling(.preview)` 四个未使用依赖 | dex 68.65 MB → 32.94 MB（−35.7 MB） | 三者全工程 import 计数均为 **0**；其中 `material-icons-extended` 贡献最大——它的符号在旧 `classes.dex`（40.00 MB）里出现 56,970 次。实际图标全部来自 `top.yukonga.miuix.kmp.icon.MiuixIcons` |
| release 开启 `isMinifyEnabled` + `isShrinkResources` | 31.4 MB → 2.38 MB（−93%） | 未引用类被 R8 裁掉 |

> 剩下的 34.0 MB（debug）是**真正在用**的 Compose 运行时（`androidx/compose/ui` 13,332 处、`foundation` 10,999 处、`runtime` 5,458 处）+ MIUIX（5,718 处）+ kotlinx + 移植来的 7,000 行 UI。这部分删不掉——模块的界面就是 Compose 写的。
>
> 另有一个坑：移除依赖后 `assembleDebug` 产出的 APK **可能仍是旧体积**——AGP 增量打包沿用了旧的文件偏移，多出来的是没有任何 entry 的死区。`gradle :app:clean` 后重建才是真实体积。判断体积一定要先 clean。
>
> **2026-10-26 v2 更新**：v2.0.0 实测 debug **35,697,303 B** / release **3,965,095 B**。
> 相对 v1 的 48.4 MB / 3.91 MB，debug 明显变小——因为 2026-10-26 那次构建前先 `clean`
> 过，旧 APK 里 AGP 增量打包留下的死区被清掉了（这也再次印证下面那条「判断体积一定要先 clean」）。
> release 反而略增 0.04 MB，是 `RfcommLog` 日志通道 + 调试图 + `CrashHandler` 的净增。

> 对照 `OppoPods 2.2.0`（3.84 MB）：它的 UI 全部用 framework View + XML，**根本没有打包 Compose 运行时与 MIUIX**（实测其 dex 内 `androidx/compose` 已定义类仅 1 个）；本模块的 Compose/MIUIX UI 是体积的主要来源，R8 后仍保留约 3.9 MB 属于合理开销。

---

## 六、许可与声明

本项目仅用于**学习与研究**：理解 Android 蓝牙协议栈、Xposed Hook 技术、HyperOS 系统集成方式。

- 不含任何 FIIL 官方代码或资源
- 协议知识来自对公开 APK 的逆向分析
- 请勿用于商业用途或绕过官方功能限制
- 使用风险自负（Hook 系统进程有导致蓝牙异常的可能）

---

## 七、相关文档

| 文件 | 内容 |
|---|---|
| `<fiil_analysis>\FIIL+_3.5.2_逆向分析报告.md` | FIIL+ APP 完整逆向报告（948 行） |
| `docs/ACCEPTANCE_REPORT.md` | ★ 结项验收报告：6 角色逐项核查，总分 72/100，结论**可内测、不可公开发布**（P0 日志泄露 MAC 待修） |
| `docs/DEVICE_TEST.md` | ★ 真机协议验证报告：FIIL Key Max 上 19/19 用例通过，含原始收发字节 |
| `docs/PROTOCOL_FIIL.md` | ★ 协议参考：GAIA + Mate 双栈帧格式、命令表、机型能力矩阵 |
| `docs/FIX_NOT_CONNECTED.md` | 「检测不到已连接耳机」九处根因的定位与修复记录 |
| `docs/FIX_CRASH_PARCELABLE.md` | 新手机「点开闪退」根因（跨进程 Parcelable）与三层修复 |
| `docs/FIX_V2_MODES.md` | ★ **v2.0.0 修复记录**：电量能显示但模式切不了的 6 个真缺陷 + 日志抓取三条路 |
| `docs/FIX_V2_1.md` | ★ **v2.1.0 修复记录**：设备中心图标滞后 / 模块服务误报 / 焦点通知不显示 三条问题的根因与修法（302 行） |
| `docs/SPEC_RFCOMM.md` | SPP 连接层设计规格 |
| `docs/SPEC_SETTINGS_HOOK.md` | 设置页注入规格 |
| `docs/SPEC_UI.md` / `SPEC_UI_OPPOPODS.md` | UI 设计参考 |
| `docs/SPEC_ARCH_HUAWEIPODS.md` | 参考仓库架构规格（2544 行） |

---

## 八、交付状态

| 项 | 状态 |
|---|---|
| 五阶段功能面 | 全部落地（协议逆向 / 模块架构 / 五个 UI 位置 / 功能清单 / 健壮性） |
| 构建 | **v2.1.0（versionCode 3）** `:app:assembleDebug :app:assembleRelease` 通过 → `FiilPods-v2.1.0-debug.apk` = **35,713,691 B（34.1 MB）**、`FiilPods-v2.1.0-release.apk` = **4,063,403 B（3.88 MB）**，两个产物都归档在 `dist\` |
| APK 元数据 | `assets/xposed_init` = `moe.chenxy.fiilpods.hook.HookEntry`；`module.prop` min/target API = 102；`scope.list` 四条包名齐全 |
| **协议真机验证** | **已通过** —— FIIL Key Max（固件 3.0.1.1）上 **19/19 用例全绿**，详见 [`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md) |
| **模块真机验证** | **部分进行** —— 2026-10-24 在 Redmi K90 Pro Max（Android 17 / HyperOS V816）上确认模块已成功注入 `com.android.bluetooth`（`dumpsys activity broadcasts` 可查到本模块注册的 6 个 action），并据此定位修复了 9 处「检测不到已连接耳机」的缺陷，详见 [`docs/FIX_NOT_CONNECTED.md`](docs/FIX_NOT_CONNECTED.md)。设置页卡片 / 超级岛 / 融合设备中心 / 通知的**视觉表现**仍未逐项截图验证 |
| JVM 单测 | **49 项全绿**（`:app:testDebugUnitTest`：ModelCapabilityTest 16 + ProtocolFrameTest 27 + AddressMaskTest 6），覆盖帧编解码、命令路由、状态机、机型能力矩阵、日志脱敏 |
| **结项验收（v1）** | **可内测（不可公开发布）**，总分 **72/100** —— 详见 [`docs/ACCEPTANCE_REPORT.md`](docs/ACCEPTANCE_REPORT.md)。阻塞项 2 个：**P0-1** 日志明文打印蓝牙 MAC；**P0-2** 融合设备中心 hook 失效时静默吞掉、无用户可见状态。**P0-1 已在 v2.0.0 修复**（7 处全改走 `FiilConfig.maskAddress()`，release dex 实测明文 MAC 已消失），**P0-2 仍未修** |

### 8.1 已验证到哪一步

分两套独立的第三方实现验证，均与模块自身代码无关：

**（A）`tools/probe/FiilProbe`（C# + Windows 原生 RFCOMM 栈）—— SPP 通道**

| 验证项 | 结果 |
|---|---|
| `setUnlock` 握手是强制前置 | 确认：不发则耳机对所有命令静默 |
| 版本 / 电量 / 编解码器查询 | 通过，固件 `3.0.1.1` |
| MAF 五态（关闭/降噪/开放/兼听/风中） | 五档逐档写入并回读，全部一致 |
| 影院模式开关 | 通过 |
| 低延迟开关（Mate 二态） | 通过 |
| EQ 十段读写 | 通过，含 `reserved` 字节回填要求 |
| 蓝牙畅连开关 | 通过（写入必须用 `cmd=1`，`cmd=128` 只读） |
| 断开 → 重连 → 重新握手 | 通过 |

**（B）`<fiil_pc>\*.py`（Python + Bleak）—— BLE GATT 通道**（2026-10-25，FIIL Key Max `B0:F1:AA:BB:CC:DD`）

| 验证项 | 结果 |
|---|---|
| GATT 建链 `ff17` 写 / `ff18` 收 | 通过（**ff16 是错的**，写入返回 false） |
| `setUnlock` 在 GATT 上同样必需 | 通过，回 `00 27 02 00 03 FF 01 FF` |
| 13 条 GET 逐条 | **11 条有数据**；`GET_CINEMA(0x0B)` 与 `GET_WEAR(0x20)` 回空（Key Max 不支持） |
| `GET_ADDRESS(0x03)` | 返回字符串 `"FIIL Key Max"`，不是 MAC |
| `GET_EARTYPE(0x05)` | 返回多段参数表，非单字节 earType |
| SET：MAF / 低延迟 / 空间音频 / 多点 / 媒体键 | **全部通过**（写后 GET 读回一致；多点切换会短暂掉链属正常） |
| SET：EQ 12 字节曲线 | **返回成功但读回不变** → 未生效，列为未确认项 |
| `cid=40` NOTIFY 主动上报 | 收到 1 条：`0B 28 03 00 0E 83 0C D8 80 83 8A F0 18 00 C3 0A B3 7F 60`（payload 里疑似含另一台设备 MAC） |
| **模块自身的 Android 侧行为** | 见下方 8.2，仅部分验证 |

### 8.2 仍存在的风险

1. **Android 侧验证不完整**。Xposed 注入点（`com.android.settings` / `com.android.bluetooth`
   / `com.xiaomi.bluetooth` / `com.milink.service`）全部依赖方法签名匹配，HyperOS 版本一旦
   变化就可能 hook 失败。所有 hook 都包了 `runCatching`，失败会静默降级而不是崩溃。
   已在 Redmi K90 Pro Max（Android 17 / LSPosed 2.1.1）上确认**模块确实注入**蓝牙进程，
   但设置页卡片 / 超级岛 / 融合设备中心 / 通知的**视觉表现**未逐项截图验证。
2. **机型覆盖不全**。真机只测了 Key Max（Mate 链路）。GAIA 链路（老机型）、F042/F051/F053
   的 BLE 链路、自适应档位、佩戴检测均只有逆向推导，没有对应硬件。
3. **手机侧 UI 未适配深色模式**。设置页卡片用 `resolveAttribute(android.R.attr.textColorPrimary)`
   取色，理论上自适应，但未经真机确认。
4. **Mate 自定义 EQ 写入在 Key Max 上不生效**（`SET_EQ` 返回成功但读回不变）。可能是该机型只接受
   预设，或必须先切「自定义档」（`setEQAdaptive`）。GAIA 机型不受影响。
5. **SPP 与 BLE GATT 的并发行为未测**。模块当前让两条通道互斥，同一时刻只走一条。

### 8.3 新手机「点开闪退」的根因与修复（2026-10-26）

#### 现象与根因

Redmi K90 Pro Max（Android 17 / HyperOS V816）上点开 App 立即闪退。抓到堆栈后根因明确：

```
java.lang.RuntimeException: Error receiving broadcast
    Intent { act=chen.action.oppopods.pods_battery_changed ... (has extras) }
    in moe.chenxy.fiilpods.ui.MainUIKt$MainUI$broadcastReceiver$1$1
Caused by: android.os.BadParcelableException: ClassNotFoundException when unmarshalling: zd
    at android.os.Parcel.readParcelableCreatorInternal(Parcel.java:5445)
    at android.os.Bundle.getParcelable(Bundle.java:1121)
    at android.content.Intent.getParcelableExtra(Intent.java:9859)
    at moe.chenxy.fiilpods.ui.MainUIKt$MainUI$broadcastReceiver$1$1.onReceive(MainUI.kt:261)
```

关键点：`getParcelableExtra` 的反序列化按**类名字符串**去 ClassLoader 找类，找不到就抛
`BadParcelableException` —— 这是**异常**，不是返回 null，所以 `?.let` 和空判断都挡不住。
抛在 `BroadcastReceiver.onReceive` 里会被 AMS 包装成 `RuntimeException`，直接杀掉进程。

`zd` 是 R8 混淆后的短类名。写入端（蓝牙进程里注入的代码）与读取端（UI 进程）不是同一个
构建时，类名就对不上：覆盖安装、模块与 UI 版本不一致、蓝牙进程里残留旧注入，都会触发。

#### 修复（两层）

| 层 | 做法 |
|---|---|
| 业务层：**不传不读 Parcelable** | `OppoCompatBridge.publishBattery()` 在发 `status` 的同时带上 `EXTRA_BATTERY_LEFT/RIGHT/CASE` 三个纯 int（基础类型没有类名问题）；`MainUI.kt` 与 `PopupActivity.kt` 的接收分支**只读这三个 int**，用 `rebuildBatteryFromInts()` / `OppoCompatBridge.batteryFromIntent()` 重建电量，彻底不再调用 `getParcelableExtra("status", ...)` |
| 框架层：接收器最外层兜底 | `MainUI.kt`、`PopupActivity.kt`、`BluetoothProcessHook.kt` 的 `onReceive` 整个 `when` 包进 `runCatching{}.onFailure{ Log.w(...) }`。将来任何分支若再读 Parcelable，也只是丢一条广播，不会杀进程 |

#### 治本：让混淆不再改名

`app/proguard-rules.pro` 新增：

```
-keep class moe.chenxy.fiilpods.utils.miuistrongtoast.data.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
    public static **[] newArray(int);
}
```

release 包实测已能查到完整类名 `Lmoe/chenxy/fiilpods/utils/miuistrongtoast/data/BatteryParams;`
与 `PodParams;`（修复前被改写成短名）。这样即便还有别处传 Parcelable，两端也能对上。

#### 同批修掉的其它真缺陷

| 项 | 处理 |
|---|---|
| 崩溃自捕获 | 新增 `CrashHandler.kt`，`FiilPodsApp.onCreate()` 第一行装进程级 handler，堆栈写 `filesDir/crash_last.txt` 与 `/sdcard/Android/data/moe.chenxy.fiilpods/files/crash_last.txt` |
| 崩溃页 | `MainActivity.onCreate()` 读到上次崩溃时**绕过 Compose/MIUIX**，用纯 framework 控件显示堆栈，带「复制堆栈」「清除并重试」 |
| ContentProvider | `AndroidManifest.xml` 补 `.config.PodImageProvider`（`authorities=moe.chenxy.fiilpods.podimages`）——移植时漏声明，用户设自定义耳机图片时必然 `ActivityNotFoundException` |
| 空断言 | `FocusIslandUtil.kt` 的 `left!!/right!!` 改为 `?.let`；`getParcelableExtra(...)!!` 全部去掉 |
| Drawable 硬转 | `PodStatus.kt` 的 `as BitmapDrawable` 改为 `as?` + 退回 `painterResource` |
| 中文语种 | `values-zh-rCN/strings.xml` 补齐 `fiil_maf_listen`（兼听）、`fiil_maf_wind`（风中），两套各 197 条 |

#### 教训

**跨进程传 Parcelable 是架构级风险，不是编码细节。** 本项目的 UI 层是整份移植自
OppoPods，而 OppoPods 的两端永远同版本同构建，所以它这么写没出过事；但 FiilPods 的注入
代码跑在 `com.android.bluetooth` 进程里，生命周期与 UI 进程完全独立，覆盖安装时必然出现
「一端新一端旧」。正确做法就是从协议层开始只传基础类型。

---

### 8.4 v2.0.0 修了什么（2026-10-26）

v1.0.0 归档保留在 `dist\FiilPods-v1.0.0-{debug,release}.apk`，v2 是**增量修复版**，
包名与签名都不变，可直接覆盖安装。

#### 真缺陷清单（全部有源码位置）

| # | 缺陷 | 后果 | 位置 | 修法 |
|---|---|---|---|---|
| 1 | `sendMate()` 只有 SPP 分支，没有 GATT 分支 | **Mate 机型走 BLE GATT 时能连上但一条命令都发不出去** —— 这正是「电量能显示、模式切不了」的根因 | `pods\FiilRfcommController.kt:1480` | 加 `if (usingGatt) FiilGatt.send(frame) else enqueue(frame)` |
| 2 | `setAnc()` / `setEq()` / `set3D()` 无条件按 GAIA 命令号组帧 | Mate 机型（cid 语义完全不同）收到 GAIA 帧后静默丢弃 → MAF 五档全部无效 | `pods\FIILProtocol.kt` | 按 `isMateDialect` 分流到 `sendMate(cid, cmdId, payload)` |
| 3 | `ancStatusForUi()` 把「兼听」映射成 4、「风中」映射成 8 | UI 侧 4=ADAPTIVE、8=DEEP，导致**「兼听」高亮成「自适应」、「风中」高亮成「深度降噪」** | `pods\OppoCompatBridge.kt:101` | 改用 9 / 10 两个 FIIL 专有码，`ui\MainUI.kt:261-262` 加对应分支 |
| 4 | `onGattReady()` 无条件 `isMateDialect = true` | F042 / CC Pro2 / CC nano（earType 39/40/42）只支持 BLE 但走 GAIA 帧，会被发 Mate 帧 → 「连上了、电量有、别的全无响应」 | `pods\FiilRfcommController.kt:579` | 改为 `if (earType > 0) FiilModels.isMateDialect(earType) else true` |
| 5 | `RFCOMM_LOG_CONNECT/DISCONNECT/CLEAR` 三个 action 蓝牙进程没注册 | **调试页日志通道整条是死的**，点「开始」没有任何日志 | `hook\BluetoothProcessHook.kt` | 补注册三个 action 并转发到 `RfcommLog` |
| 6 | 调试页缺「发送原始 Hex」入口 | 无法在不改代码的前提下验证某条指令耳机认不认 | `ui\pages\RfcommDebugPage.kt` | 新增输入框 + 发送按钮，走 `FiilRfcommController.sendRawHex()` |

#### 日志怎么取（对应「怎么通过软件抓日志」）

三条路，任选其一：

1. **模块内调试页（推荐）**：设置页 → 齿轮 Tab → 最下方「RFCOMM 调试」→ 点「开始」→ 复现问题
   → 点「复制全部」。导出的日志含 TX/RX 每一帧的十六进制，以及连接 / 握手 / 断连时间点。
2. **崩溃堆栈文件**：`/sdcard/Android/data/moe.chenxy.fiilpods/files/crash_last.txt`。
3. **App 内崩溃页**：一旦崩溃过，下次打开 App 会直接显示上一次的完整堆栈（绕过 Compose 渲染），
   带「复制堆栈」按钮。

> ⚠️ 不要依赖 `adb logcat`：实测机（HyperOS V816）`ro.logd.size = 64K` 且
> `persist.debug.dpis.global_log_enabled = 0`，logcat 缓冲区常年 `0 B readable`。

#### 顺带修掉的验收 P0-1：日志不再打印明文 MAC

结项验收报告的 P0-1 是「日志明文打印蓝牙 MAC」。v2 新增 `FiilConfig.maskAddress()`：

```
B0:F1:AA:BB:CC:DD  ->  B0:F1:**:**:**
```

保留前两段（OUI 高两字节，排障要用来判断芯片厂商），后四段全码。
改了 **7 处**打日志的地方（`BluetoothProcessHook` ×2、`SettingsHook` ×1、
`FiilRfcommController` ×3、`FiilGatt` ×1），并新增 `AddressMaskTest`（6 个用例）
把规则钉死。release dex 实测：明文 `B0:F1:A3:A8:7A` **已不在包内**。

> v1 的 `docs/ACCEPTANCE_REPORT.md` 保持原样（那是 v1 的历史验收记录，不回写）。

#### v2 仍未完成

| 项 | 状态 |
|---|---|
| v2 的 MAF 切换真机验证 | **未验证**（等用户装机反馈） |
| GAIA 老机型的 `setAnc` 分流 | 仅静态推导，无对应硬件 |
| Mate 自定义 EQ 写入 | Key Max 上仍不生效（v1 已记录，v2 未改） |
| 自适应四个开关 | 已实现 UI + 协议，仅静态验证 |
| 融合设备中心静默吞异常（验收 P0-2） | **未修** |

> 完整的技术细节（每个缺陷的判定依据、日志三条路、构建踩坑）见
> [`docs/FIX_V2_MODES.md`](docs/FIX_V2_MODES.md)（294 行）。

---

### 8.5 v2.1.0 修了什么（2026-10-26）

v2.0.0 上机后用户报三条问题（原文）：
> 「1：在设备中心里面切换模式时候 虽然能切换 但是图标还是会显示在原来的功能图标里面 等在进行点击一次才能切换到对于的图标上面
> 2：模块页面 模块服务未响应 但功能能够正常使用
> 3：在设置页面开启焦点通知无法在通知栏里面显示通知」

| # | 问题 | 根因 | 位置 | 修法 |
|---|---|---|---|---|
| 1 | 设备中心切模式后图标滞后一拍 | `hookAncCommand` / `hookAncStateBlock` 只发广播 + 调 `notifyPropertyChanged(device, UPDATE_ANC)`，**没有回写 `AncBatteryModel` 的内存字段**；控制中心重绘时读到的仍是旧值，要再点一次（上一次的广播路径已把模型刷新）才对 | `hook\milink\MiLinkServiceHook.kt` | 新增 `syncAncModel(owner, device, maf)`：取 `ancBatteryModel` 后按 ROM 字段名漂移依次 `setObjectField(model,"ancState"/"bluetoothAncState"/"miLinkAncState", state)` 再二次推送 `UPDATE_ANC`；`ACTION_PODS_ANC_CHANGED` 分支也同步回写（覆盖耳机实体键切换） |
| 2 | 模块页误报「模块服务未响应」 | `announceAlive()` 只在 `AdapterService.onCreate` 时发一次；App 后开就一条都收不到，`bluetoothServiceResponsive` 永远 false（UI 侧 75 秒窗口校验） | `hook\BluetoothProcessHook.kt:332` | `ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS` 分支开头回发 `announceAlive(...)`；UI 每 30s 发一次心跳，状态自然维持 |
| 3a | 设置页开焦点通知后无任何通知 | **`HookEntry` 从未注入 LSPosed 远端 prefs**，三个系统进程里的 `ConfigManager` 永远是默认值，用户改设置对 Hook 侧完全无效 | `hook\HookEntry.kt:51-78`、`hook\HookContext.kt:60-80` | 新增 `HookContext.attach(prefs, packageName)`（`ConfigManager.init` + 三个 key 的变更监听）；`HookEntry.prepareRemotePrefs()` 用 `getRemotePreferences(ConfigManager.PREFS_NAME)` 并注入四个 Hook |
| 3b | 同上 | `FocusIslandUtil.showBatteryIsland()` **全工程零调用点** —— 「超级岛 = 模块」这条分支从未接线 | `hook\MiBluetoothToastHook.kt:172-226` | 新增 `showModuleFocusIsland()`：`focusNotification && islandMode == ISLAND_MODE_MODULE` 时调 `FocusIslandUtil.showBatteryIsland(...)`；`BatterySnapshot` → `BatteryParams` 转换 |
| 3c | 同上（次要但真实） | 常驻通知渠道建成 `IMPORTANCE_MIN`，HyperOS 会塞进「静默通知」并默认折叠；**渠道重要性创建后不可修改**，改代码重装也无效 | `hook\MiBluetoothToastHook.kt` | 换新渠道 id `fiilpods_battery_v2` + `IMPORTANCE_LOW` |
| 3d | 同上（时序陷阱） | `onHook()` 里照抄 OppoPods 写了 `if (!focusNotification()) return`，若进程在「关闭」状态下启动，再打开开关也不会生效 | `hook\MiBluetoothToastHook.kt:73` | 改为**总是挂接收器**，开关判断下沉到 `onBatteryChanged()`（关掉时顺带 `cancelNotification`） |

#### 验证

- `:app:compileDebugKotlin` → EXIT=0
- `:app:testDebugUnitTest` → EXIT=0，**49 项全绿**
- `:app:assembleDebug :app:assembleRelease` → EXIT=0
- APK 元数据：`versionCode 3` / `versionName 2.1.0`；V2 签名 SHA-256 与 v1/v2 完全一致（`605996dc…`），可直接覆盖安装
- release dex 标记实测全部命中：`syncAncModel` / `announceAlive` / `prepareRemotePrefs` / `showModuleFocusIsland` / `fiilpods_battery_v2` / `oppopods_focus_island` / `receiver still mounted`

#### v2.1.0 仍未完成

| 项 | 状态 |
|---|---|
| 三条修复的真机回归 | **未验证**（等用户装机反馈） |
| 融合设备中心静默吞异常（验收 P0-2） | **未修** |
| Mate 自定义 EQ 写入 | Key Max 上仍不生效 |
| GAIA 老机型 / 自适应开关 | 仅静态验证 |
