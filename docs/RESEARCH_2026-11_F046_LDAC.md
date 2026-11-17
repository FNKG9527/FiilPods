# F046（Key Max）LDAC 授权位研究全过程 · 2026-11-16/17

本文是一次**完整的、失败的、但有大量正向收获**的逆向研究的过程记录。

写作目的只有一个：**给 `fiilpods` 模块的后续开发 / 修复 / 新功能提供可直接复用的技术资产**。
所以它不按「时间流水账」组织，而按「你开发时会用到什么」组织：

- 第 2 章是**协议字典**——所有实测确认过的帧、命令号、回包形状。写代码时直接查这里。
- 第 3 章是**模块实现对照**——模块里已经做了什么、哪些地方因为不知道这些事实而写错过。
- 第 4 章是**踩坑清单**——每条坑都带原始帧证据，模块开发者可以避免重犯。
- 第 5 章是**未解问题**——142 为什么置不上位，以及下一步该往哪走。
- 第 6 章是**工具与证据索引**——所有探针脚本与证据文件的位置。

> **一句话结论**：LDAC 的**开关**（143 / `cid=1 cmd=13`）模块已经能完全控制，实测有效；
> LDAC 的**授权**（142）是固件里的一个独立布尔位，**不服从 Mate 协议的任何写入**，
> 累计 5000+ 次写入尝试全部失败。它与设备 UUID 无关、与 140/141 存储槽内容无关、
> 与重启时序无关。**目前判断它由固件内嵌密钥的密码学校验或未暴露的激活流程控制。**

---

## 0. 对象与最终结果

| 项 | 值 |
|---|---|
| 耳机 | FIIL Key Max（型号代号 F046） |
| 主控 | 中科蓝讯 BT8931H |
| 固件 | `3.0.1.1`（回包 `03 00 01 01`），校验和 `7C 05 EE E4` |
| PID | `00 2E 01`（`0x2E` = 46 = F046） |
| 设备 UUID（左=右） | `4250323632303512003b51450620bb78` |
| 蓝牙地址 | `B0:F1:A3:A8:7A:9D` |
| 私有 SPP UUID | `d52b47bc-2dea-42c4-b26d-20252fceeeb3` → RFCOMM **channel 6** |
| 目标 | 把 `142`（LDAC 授权位）从 `00` 置成 `01` |

**最终结果**：**未达成**。`142` 全程 `00`。
但 `143`（LDAC 开关）已可自由读写，`144`（codec 状态）可读，协议层已完全掌握。

---

## 1. 三条传输通道的真实拓扑（重要，模块必须先认清这个）

这是整次研究里**最容易搞错、且直接影响模块架构**的一条。

```
耳机 B0:F1:A3:A8:7A:9D
│
├── BLE GATT  service 0000fdb3-...
│      ff18 (notify, read)   耳机 → 手机
│      ff16 (write, read)    ★ 实际不支持写
│      ff17 (write, write-no-response)  ★ 真正的写入口
│      → 帧格式与 RFCOMM 完全相同，只是换了承载
│
├── RFCOMM channel 6   （UUID d52b47bc-2dea-42c4-b26d-20252fceeeb3）
│      → Mate 协议主通道。所有 cid=1 / cid=39 / cid=40 命令走这里。
│      → 连接后第一帧必须是握手，否则耳机对所有命令静默。
│
├── RFCOMM channel 5   （标准 SPP UUID 00001101-...）
│      → 能连上，但零字节回包。不是 Mate 通道。
│
├── RFCOMM channel 2   （HFP）
│      → 连上后耳机主动发 "AT+BRSF=671\r"，与协议无关。
│
└── RFCOMM channel 3 / 4 / 7..15
       → 全部 connect fail（WinError 10064 目标主机坏了）
```

### 1.1 ★ GAIA 协议在 F046 上**不存在**（这是实测否证，不要再花时间）

`com.fiil.sdk.gaia` 这套 GAIA 命令空间（`FF 01 00 len 02 34 cmdH cmdL payload`）在 APK 里完整存在，
一度让我以为有第二条读写通道。实测结论：**F046 固件完全不实现 GAIA**。

在 ch6（唯一真实可用的私有通道）上，发任何 GAIA 帧都**零回包**，而紧邻其后的 Mate 帧正常应答：

```
ch6  Mate 握手            -> 00 27 02 00 03 ff 01 ff     ★ 有回包
     读 142               -> 01 27 02 00 03 8e 01 00      ★ 有回包
     GAIA GET_STATUS(16394)     -> (空)                   ★ 零回包
     GAIA GetCodingAndDecodingType(1954) -> (空)          ★ 零回包
     再读 142             -> 02 27 02 00 03 8e 01 00      ★ 有回包（证明链路正常）
```

同一批 GAIA 帧也在 ch5 / ch3 上试过，同样零回包。

**由此作废的整条路线**（省得后来人再走一遍）：

| 作废的能力 | 曾经的期望 | 实际 |
|---|---|---|
| `FFCOMMAND_OPEN_FILE` (16385) / `READ_FILE` (16387) | dump 耳机侧文件系统，读 `flag=7` 的 OTA 固件包 | 零回包 |
| `FFCOMMAND_GET_AUTH_BITMAPS` (1408) | 直接读鉴权位图 | 零回包 |
| `FFCOMMAND_SetUnlockLADC` (1968) | GAIA 侧的 LDAC 解锁 | 零回包 |
| `FFCOMMAND_START/END_TRIAL_MODE` (12308/12309) | 试用模式 | 零回包 |
| `FFCOMMAND_GET_DISK_SPACE` (16399) | 耳机 flash 容量 | 零回包 |
| 全部 `FFCOMMAND_*` | — | 零回包 |

**根因也找到了**：`FiilManager.a()` 按 `earType` 分派命令类，`earType == 46` **没有任何分支命中**，
落到最内层 `default` → `new com.fiil.sdk.command.b()`（FiilCommandF002）。
而 `b.java` 只有 `getAnc` / `setAnc` / `update` 三个方法，`setUnlockLDAC` 继承基类 `FiilCommandUtil.java:1304` 的**空实现**。

> **给模块开发者的结论**：F046 只有 **Mate 一套协议**。凡是 `com.fiil.sdk.gaia` 里的东西，
> 对 F046 一律无效。`FiilGaia.kt` 里的实现只服务于 F039/F048 那批老机型。

### 1.2 握手：连接后第一帧

```
TX  00 27 01 00 04 FF 02 02 34
RX  00 27 02 00 03 FF 01 FF
```

- `cid = 39`（QUERY），`cmdId = 255`
- payload = `02 34`（就是 `VENDOR_FF = 564 = 0x0234` 的两个字节）

**不发这一帧，耳机对所有后续命令静默**（不是拒绝回包，是完全不响应）。
`setUnlock` 这个名字极具误导性——它**不是解锁命令**，是握手。

源码依据 `com\fengeek\main\i\d\c.java:537-550`：
```java
public void setUnlock(com.fengeek.main.i.d.i.b infoListener) {
    aVar.setCommandClassifyId(39);
    aVar.setCommandId(-1);              // 0xFF
    aVar.setBuffer(new byte[]{2, 52});  // 0x02 0x34
}
```

> **模块侧注意**：`FiilRfcommController` 已在握手流程里发了这一帧。若新增通道（如 BLE 直连），
> 必须**重新发一次**。多点连接切换会断链，重连后同样要做。

---

## 2. 协议字典（模块开发直接查这里）

### 2.1 Mate 帧结构

```
[seq][cid][type][idx][plen][cmdId?][len][payload...]
 0    1     2     3    4      5       6     7..
```

| 字段 | 含义 | 备注 |
|---|---|---|
| `seq` | 序号 | **不能用于请求-响应匹配**。耳机用自己的滚动计数器，与 TX 的 seq 无关。只能靠 `cid + cmdId` 语义分发。 |
| `cid` | classifyId | 见 2.2 |
| `type` | 1 = 请求，2 = 应答，3 = 通知 | |
| `idx` | 帧序号，回包原样带回 | |
| `plen` | 后面的总字节数 | |
| `cmdId` | 子命令 | **仅当 cid 需要时存在**。`cid=36`（恢复出厂）没有 cmdId。 |
| `len` | payload 长度 | |

**★ `plen` 的三态语义（最容易写错的地方）**：

| `plen` | 总帧长 | 含义 |
|---|---|---|
| `0` | 5 | 空帧 = 耳机不认识这条命令 |
| `1` | 6 | SET 的应答，第 6 字节 = 成功标志（`00` 成功 / `01` 拒绝） |
| `>= 2` | `5 + plen` | 所有 GET 应答与请求本身 |

按「`plen < 2` 就当 5 字节空帧」切帧，会把 SET 的成功字节整个吞掉，导致所有 SET 误判失败。

### 2.2 cid 全表（实测确认）

| cid | 名称 | 方向 | 用途 |
|---|---|---|---|
| `1` | `SET_PARAM` | 写 | 所有设置类命令，`cmdId` 区分 |
| `2` | — | 写 | 仅 F048 用 |
| `3` | — | 写 | 仅 F048 用 |
| `32` | `SET_EQ` | 写 | EQ 曲线 |
| `33` | `SET_MUSIC` | 写 | 播放控制 |
| `34` | `SET_KEY` | 写 | 按键自定义（F046 只用 key=3） |
| `36` | `SET_RESET` | 写 | **恢复出厂**，无 cmdId 无 payload |
| `37` | `SET_LOW_LATENCY` | 写 | 低延迟 |
| `38` | `SET_WEAR` | 写 | 佩戴检测 |
| `39` | `QUERY` | 读 | 所有查询 |
| `40` | `NOTIFY` | 耳机→手机 | 主动上报 |
| `41` | `SET_LANGUAGE` | 写 | 仅 F049/F051/F053 |
| `44` | `SET_MAF` | 写 | MAF 档位 |
| `50` | `SET_SPATIAL_AUDIO` | 写 | 影院模式（**只有 F046 支持**） |

**全库 `setCommandClassifyId(...)` 的实参只有** `1/2/3/32/33/34/36/37/38/39/41/44`，**从不出现 40**——
40 只出现在 `*MainFragment` 的 notify 分支判断里。

### 2.3 `cid=1` 的 SET 子命令（写参数）

**格式**：`[seq][01][01][00][plen=03][cmdId][len=01][value]`，回显 `[seq][01][02][00][01][cmdId][00/01]`

| cmdId | 名称 | 取值 | 源码 |
|---|---|---|---|
| `2` | `setTone` | 提示音音量 `0/1/2` | `c.java` |
| `3` | `setTipVolume` | 语音提示音量 `0..3` | `c.java` |
| `6` | `setMultiConnect` | 多点连接 `0/1` | `c.java` |
| `8` | `setLowDelay` | 低延迟 `0/1` | `f.java:738` |
| `10` | `setLanguage` | 语言（老机型） | `h.java:488` |
| `12` | `setActivationLdac` | LDAC 激活，**payload 是 17 字节 license** | `c.java:287` |
| `13` | `setLdacSwitch` | LDAC 开关 `0/1` | `c.java:371` |
| `14` | `setEQAdaptive` | 自适应 EQ（F049+） | `f.java:470` |
| `15` | `setEarAdaptive` | 耳道自适应（F049+） | `f.java:487` |
| `16` | `setNoiseAdaptive` | 环境自适应（F049+） | `f.java:622` |
| `17` | `setWindAdaptive` | 风噪自适应（F049/F051） | `f.java:772` |
| `18` | `setTuning` | 云感调音（F049+） | `f.java:704` |

> **★ `cmd=12` 与 `cmd=13` 是两个不同的命令，别混。**
> 历史上我曾用 `cmd=12` 发 `[13,1,1]` 想开 LDAC，全部 `ACK=1` 被拒，误判成「写入通道被锁」。
> 实测：`cmd=13 payload=[01]` → `ACK=0`，143 立刻变 1。

### 2.4 `cid=39` 的 QUERY 子命令（读）

**格式**：`[seq][27][01][00][02][cmdId][00]` → `[seq][27][02][00][plen][cmdId][len][value...]`

| cmdId | dec | 名称 | 实测值（F046 / 固件 3.0.1.1） |
|---|---|---|---|
| `01` | 1 | 电量 | `00 64 00`（左/右/盒，`0` 与 `255` 视为未知） |
| `02` | 2 | 固件版本 | `03 00 01 01` |
| `03` | 3 | 蓝牙名 | `"FIIL Key Max"` |
| `04` | 4 | EQ | `0a 00×11` |
| `05` | 5 | 按键配置 | 10 组 `[key][?][value]` |
| `06` | 6 | 音量 | `0c` |
| `07` | 7 | 播放状态 | `00` |
| `08` | 8 | 工作模式 | `00` |
| `0C` | 12 | ANC 模式 | `00` |
| `0D` | 13 | 是否 TWS | `00` |
| `10` | 16 | 固件校验和 | `7c 05 ee e4` |
| `11` | 17 | ANC 增益 | `00` |
| `12` | 18 | 通透增益 | `00` |
| `16` | 22 | 主耳侧 | `00` |
| `18` | 24 | 空间音效 | `00` |
| `80` | 128 | 未命名 | `00` |
| `81` | 129 | 未命名 | `01` |
| `82` | 130 | 未命名 | `01` |
| `83` | 131 | **手机 MAC** | `d8 80 83 8a f0 18`（随连接方变化） |
| `84` | 132 | **PID** | `00 2E 01`（`0x2E` = 46） |
| `86` | 134 | 未命名 | `03 0c` |
| `8A` | 138 | 左耳 UUID | `42 50 32 36 32 30 35 12 00 3b 51 45 06 20 bb 78` |
| `8B` | 139 | 右耳 UUID | 同上（Key Max 左右耳同 UUID，设备级） |
| `8C` | 140 | **license 槽 L** | 可写、可持久化、精确回显 |
| `8D` | 141 | **license 槽 R** | 与 140 恒为同一份内容的镜像 |
| `8E` | 142 | **LDAC 授权** | `00` = 未授权 |
| `8F` | 143 | **LDAC 开关** | `01` = 开 |
| `90` | 144 | codec 状态 | `01` / `02` 都见过；与授权无因果关系 |
| `FE` | 254 | 能力位 | `08 00` |
| `FF` | 255 | 最大包长 | `ff` |

**未回包（耳机不认识）**：`0x09..0x0B`、`0x0E`、`0x0F`、`0x15`、`0x17`、`0x19..0x7F`

> **★ cmdId 是 signed byte**：源码里写的是 `NetError` 负数常量
> （`-128`→128、`-126`→130、`-118`→138、`-117`→139、`-114`→142、`-113`→143、`-112`→144）。
> 组帧时务必 `and 0xFF`。

### 2.5 `cid=36` 恢复出厂

```
TX  00 24 01 00 00
RX  0A 24 02 00 01 00        ← payload[0] = 00，被接受
```

无 cmdId、无 payload，5 字节裸帧。

**实测影响（2026-11-16 21:15 首次真实执行）**：

| 项 | 恢复前 | 恢复后 | 结论 |
|---|---|---|---|
| `142` 授权 | `00` | `00` | **不动** |
| `143` 开关 | `01` | `00` | **被重置** |
| `144` codec | `01` | `02` | **变化** |
| 固件版本 | `03 00 01 01` | 不变 | |
| 校验和 | `7C 05 EE E4` | 不变 | |
| 138/139 UUID | 不变 | 不变 | 不在恢复范围内 |
| 254 能力位 | `08 00` | 不变 | |

**★ 恢复出厂会临时关闭整个写入通道**：紧接着的 17 次 SET（含 `setTone`/`setTipVolume`/
`multiConnect`/`earReaction`/`setLDAC`）全部 `ACK=01` 被拒。
**断电重启后立即恢复**（`setLDAC(1)` 回 `ACK=0`）。
期间试过 5 种自救（握手后立刻写 / 连写 3 次 / 读完设备信息再写 / 重新握手再写 / 探测 cmd 0-255）全失败。

> **模块侧注意**：若给用户提供「恢复出厂」入口，必须提示**重启耳机**，
> 否则后续所有设置操作都会静默失败（`ACK=01` 但 UI 无感）。

### 2.6 `ACK` 的真实语义（重要，别当成内容校验）

`cid=1` 的 SET 回显末字节，**只由两个条件决定**：

```
ACK = 0  当且仅当  len == 17  且  payload[0] ∈ {0x00, 0x01}
ACK = 1  其它一切情况
```

18 个候选的实测矩阵：

| 候选 | ACK |
|---|---|
| 17 字节全零 | 0 |
| 真 UUID license（side=0） | 0 |
| 真 UUID license（side=1） | 0 |
| 全 `FF` ×17 | 1 |
| 全 `AA` ×17 | 1 |
| 全 `01` ×17 | 0 |
| 空 payload（len=0） | 1 |
| 单字节 `00` / `01` | 1 / 1 |
| 2 字节 `00 00` | 1 |
| 16 字节裸 UUID（无 side） | 1 |
| 18 字节 `side0+UUID+00` | 1 |
| 17 字节 `side=2` | 1 |
| 17 字节 `side=FF` | 1 |
| 17 字节 `side0 + 全零UUID` | 0 |
| 17 字节 `side1 + 全零UUID` | 0 |
| 反转 UUID + side0 | 0 |
| 17 字节随机（`00 + 11..20`） | 0 |

**内容完全不参与。** 所以 `ACK` 不能当 oracle，**唯一判据永远是回读 `142`**。

> **★ 对 `setLDAC`（cmd=13）不适用**：它 1 字节 payload 就能拿到 `ACK=0`。
> 上面这套规则只描述 `cmd=12`（activateLdac）。

### 2.7 `cid=1 cmd=12` 的真实帧形状（★ 曾经在这里错了 4 小时）

```java
// 官方 F046 路径（c.java）：buffer = 17 字节裸 license，直接进 setBuffer
//   → 帧 = 00 01 01 00 13 0C 11 <side><16B>        plen=19, len=17   ★ 正确
//
// 我一度用的（错）：把 F39AppSettingRequest 的 19 字节形状套到 F046 上
//   → 帧 = 00 01 01 00 15 0C 13 0C 11 <side><16B>                   ✗ ACK=1
```

`F39AppSettingRequest.setActivateLdac()` 会自己加 `[12, 17]` 前缀：
```java
bArr[0] = 12;
bArr[1] = 17;      // ★ 硬编码魔法数，不是 values.length
System.arraycopy(values, 0, bArr, 2, values.length);
```
**那是 F048 / CC Pro2 的形状，不是 F046 的。** 混用会全部 `ACK=1`。

实测验证：

| 测试 | 帧 | ACK | 槽位是否被改写 |
|---|---|---|---|
| W1 | 17B 裸帧 `[00]+A0..AF` | **0** | ✅ `140=141=a0..af` |
| W2 | 19B 前缀 `[12,17,00]+A0..AF` | 1 | ❌ 未变 |
| W3 | 17B 裸帧 `[01]+B0..BF` | **0** | ✅ `140=141=b0..bf` |
| W4 | 19B 前缀 `[12,17,01]+B0..BF` | 1 | ❌ 未变 |

### 2.8 140 / 141：license 的真实存储槽

写 `cid=1 cmd=12` 之后，**全 info 空间 0..255 逐项 diff，唯一变化就是 140 与 141**：

```
写前：140 = 141 = 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f 20
写后：140 = 141 = 42 50 32 36 32 30 35 12 00 3b 51 45 06 20 bb 78
```

性质（全部实测）：

| 性质 | 结论 |
|---|---|
| 可写 | ✅ `cid=1 cmd=12`，17 字节裸帧 |
| 可持久化 | ✅ 断链重连后内容仍在（真落盘，非会话缓存） |
| 精确回显 | ✅ 写什么读回什么，不加工 |
| `side` 的作用 | **只做路由，不改变存储内容**：写 side=0 或 side=1 都同时改 140 和 141 |
| 与 138/139 的关系 | **无关**。138/139 是只读设备 UUID，任何写入都不影响它们 |
| `cid=39` 侧能否写 | ❌ 发 `cid=39 cmd=0x8C` 带 16B 数据，回包原值，槽未变 → 只读镜像 |

**→ 140/141 是「可写的 license 缓存」，但 142 不是它的函数。**

### 2.9 142 与 143 是两条完全独立的路径

实测组合矩阵：

| `142` | `143` | `144` | 实际 LDAC 工作？ |
|---|---|---|---|
| `00` | `01` | `02` | ✅ 工作 |
| `00` | `00` | `01` | ❌ 不工作 |
| `01` | `00` | `02` | ✅ 工作（2026-10-25 历史记录） |

**2026-10-25 的原始日志**（`D:\DeepSeek\fiil_pc\out\unverified_cmdid_20261025_014438.txt`）
证明历史上有过 `142=01 / 143=00 / 144=02` 的组合，且当时 UUID 与现在逐字节相同：

```
[TX] getLdacSwitchStatus    06 27 01 00 02 8F 00
     [RX] 06 27 02 00 03 8F 01 00      ← 143 = 00
[TX] getLdacUnlockStatus    07 27 01 00 02 8E 00
     [RX] 07 27 02 00 03 8E 01 01      ← 142 = 01  ★
[TX] getLeftHeadsetUUID     08 27 01 00 02 8A 00
     [RX] 08 27 02 00 12 8A 10 42 50 32 36 32 30 35 12 00 3B 51 45 06 20 BB 78
                                                                    ★ 与现在完全相同
```

> **对模块的直接含义**：`ldacUnlocked` 只应作为**开关是否可用的门控**，
> **不能**用来推断「LDAC 当前是否真的在跑」。真正的运行时状态看 `143` 与手机侧 A2DP 协商结果。
> 模块 `SettingsControlCard.kt:184` 的 `isEnabled = s.connected && ldacSupported && s.ldacUnlocked` 是正确用法。

---

## 3. 模块实现对照（写代码时对照这张表）

### 3.1 已经做对的部分

| 模块位置 | 做的事 | 状态 |
|---|---|---|
| `FiilMate.kt:345` | `Query.LDAC_UNLOCK = 142` | ✅ |
| `FiilMate.kt:346` | `Query.LDAC_SWITCH = 143` | ✅ |
| `FiilMate.kt:304` | `Param.LDAC_SWITCH = 13` | ✅ |
| `FiilMate.kt:311` | `Param.LDAC_ACTIVATE = 12` | ✅ |
| `FiilModels.kt` | `LDAC_EAR_TYPES = setOf(39, 46, 48, 49, 51, 53)`（跟官方 `PROTOCOL_FIIL.md:239` 一致） | ✅ |
| `FIILProtocol.kt:559` | `setLdacSwitch()` 用 `cid=1 cmd=13`，**开前先关低延迟**（照官方顺序） | ✅ |
| `FIILProtocol.kt:554` | `requestLdacState()` 同时查 142 与 143 | ✅ |
| `FiilRfcommController.kt:1505` | `ldacUnlocked = frame.byteAt(0, 0) == 1` | ✅ |
| `PodDetailPage.kt:404` | `!ldacUnlocked -> "需先在 FIIL+ 官方 App 里解锁高清音频"` | ✅ 语义正确 |
| `BluetoothProcessHook.kt:1049` | 注释明确写了「耳机侧 + 手机侧两层」 | ✅ |

### 3.2 值得复核的地方

| 模块位置 | 现象 | 建议 |
|---|---|---|
| `FiilRfcommController.kt:1505` | `ldacUnlocked = frame.byteAt(0,0) == 1` | 若回包 `len == 0`（耳机不认识），`byteAt(0,0)` 会取到默认值。建议显式判 `len >= 1`。 |
| `OppoCompatBridge.kt:441` | 用 `"${ldacKnown}/${ldacEnabled}/${ldacUnlocked}"` 做去重 key | ✅ 合理，但若将来加 `codec`（144），记得一起进 key。 |
| `FIILProtocol.kt:554` | `requestLdacState()` 有 `supportsLdac()` 门控 | ✅ 正确。**注意**：`earType` 未识别时（`0`）会直接跳过，此时 UI 显示「读取中」而非「不支持」——这是对的行为。 |

### 3.3 可以直接新增的能力（协议已支持，模块还没做）

| 能力 | 帧 | 说明 |
|---|---|---|
| **读 codec 状态** | `cid=39 cmd=144` | 回 `01`/`02`。可与 A2DP 侧交叉验证「耳机是否真在跑 LDAC」。**但**官方 App 的 codec 判据其实是 GAIA 1954（F046 不支持），所以 144 的准确语义仍待定。 |
| **读 license 槽** | `cid=39 cmd=0x8C` / `0x8D` | 只读镜像，能看到耳机当前存的 license 是什么。可用于**诊断**：展示「耳机里存的授权数据」。 |
| **写 license 槽** | `cid=1 cmd=12`，17 字节裸帧 | **技术上可行**（`ACK=0`、槽位精确回显、可持久化）。但不改变 142，**不建议对用户暴露**。 |
| **读手机 MAC** | `cid=39 cmd=0x83` | 回 6 字节。可用于「当前连接方」展示。 |
| **读能力位** | `cid=39 cmd=254` | `08 00`。位含义未知，值得继续挖（可能包含 LDAC 支持位）。 |
| **读主耳侧** | `cid=39 cmd=0x16` | `00`。多设备场景有用。 |

---

## 4. 踩坑清单（每条都带原始证据）

### 坑 1 · 把 `setUnlock` 当成解锁命令

**现象**：以为 `cid=39 cmd=255 payload={02,34}` 是「解锁 LDAC」的隐藏后门，围绕它设计了一整套攻击。
**实际**：它是**握手帧**。不发它，后续命令全静默；发了它，也不会解锁任何东西。
**依据**：`c.java:537-550`，方法体里只有 `setCommandClassifyId(39)` / `setCommandId(-1)` / `setBuffer({2,52})`。

### 坑 2 · 用 `cmd=12` 发 `setLDAC`

**现象**：`cid=1 cmd=12 payload=[13,1,1]` 全部 `ACK=1`，误判为「写入通道被锁死」，花了大量时间做「救援」。
**实际**：`setLDAC` 是 `cid=1 cmd=13 payload=[v]`（1 字节）。用 `cmd=12` 发当然 `ACK=1`。
**验证**：`cmd=13 payload=[01]` → `ACK=0`；`cmd=13 payload=[00]` → `ACK=0` 且 143 变 0。

### 坑 3 · 套错 license 帧形状

**现象**：用 19 字节 `[12,17,side,UUID]` 写 license，全部 `ACK=1`、槽位不动。
**实际**：那是 `F39AppSettingRequest`（F048/CC Pro2）的形状。F046 要 **17 字节裸 license**。
**验证**：见 2.7 的 W1~W4 对照表。

### 坑 4 · 把 `ACK` 当 oracle

**现象**：试图用 `ACK` 的 0/1 来暴力搜索正确的 license 内容。
**实际**：`ACK` 只判 `len == 17 && side ∈ {0,1}`，**内容完全不参与**。
**后果**：18 个候选（含全 FF、全 AA、错误 side、错误长度）的 ACK 分布是 `{0: 8, 1: 10}`，与内容毫无关系。

### 坑 5 · 认为 138/139 UUID 可以改写

**现象**：早期以为读到的是「被脱敏的占位值」，试图找回「真正的 UUID」。
**实际**：138/139 是**只读设备 UUID**，2026-10-25（`142=01` 时期）与 2026-11-16（`142=00` 时期）
读到的**逐字节相同**。142 从来不是 UUID 的函数。
**推论作废**：三件事同时被否证——① UUID 不是授权派生输入；② 144 与授权无因果；③ `142=01 / 143=00` 是合法组合。

### 坑 6 · 认为存在「写 → 重启 → 重算」的时序窗口

**现象**：历史上 5000+ 次写入的复核**全部在同一次连接内**（或断链重连），从未做过真重启后回读。
怀疑固件把 142 缓存在 RAM、boot 时按槽位内容重算。
**实际**（`boot_recompute.json`）：写 license → `cid=36` 重启 → 重连立刻回读，**142 仍 `00`**。
对照组写全零 → 重启 → 回读也是 `00`。
**→ 142 不是 140/141 槽位内容的函数。**

### 坑 7 · 在 GAIA 协议上浪费一整轮

见 1.1。GAIA 帧在 F046 上零回包，整条文件系统 / 鉴权位图 / 试用模式路线全部作废。
**教训**：先用**最小成本**验证协议是否存在（发一帧看回不回），再投入实现。

### 坑 8 · 认为恢复出厂会重置授权

**现象**：`research/README.md:281` 写「隐藏重置命令 | 不存在。发了恢复默认值后 142 仍为 0」。
**实际**：那句话建立在**已自我否证的前提**上——同文件 `:196-197` 自己更正过「`cid=36` 一次都没真正发出过」。
**我这次首次真实执行**（`0A 24 02 00 01 00` 被接受）：恢复出厂只重置 **143、144**，`142` 完全不动。

### 坑 9 · 恢复出厂后不做断电重启

**现象**：恢复出厂后所有 SET 都 `ACK=01`，以为耳机坏了。
**实际**：这是**临时状态**，断电重启后立即恢复。
**模块侧**：若提供「恢复出厂」入口，必须提示用户重启耳机。

### 坑 10 · 认为左右耳 UUID 不同

**实际**：Key Max 左右耳 UUID **相同**，是设备级的。
`getLeftHeadsetUUID`（138）与 `getRightHeadsetUUID`（139）返回同一串。

---

## 5. 未解问题与下一步

### 5.1 已经彻底排除的可能（不用再试）

| 假设 | 排除方式 | 规模 |
|---|---|---|
| license = 设备 UUID | 直接写，`142` 不变 | — |
| license = UUID 单字节变体 | 17 位置 × 256 值全扫 | **4335 次** |
| license = UUID 的哈希/编码派生 | md5/sha1/sha256/blake2b/blake2s × raw/hex × 多偏移 | 410 候选 |
| license = 时间戳/有效期 | 4/8 字节时间戳 × 4 位置 × 大小端、日期串、FILETIME、纪元天数 | 173 候选 |
| license = 带 App 内密钥的派生 | XOR / HMAC × 12 个 App 密钥 | 88 候选 |
| license = 常量（全零/全 FF/ASCII 关键词等） | 256 单字节 + 18 关键词 × 4 补齐 | 410 候选 |
| 写入通道被锁 | 帧形状修正后 `ACK=0` | — |
| 需要先开 143 | A/B 对照：143=1 与 143=0 两组 `ACK` 都是 01 | — |
| 需要恢复出厂重置 | 首次真实执行，142 不动 | — |
| 写→重启→重算 | 真重启后回读，142 仍 00 | — |
| 延迟生效 | 保持连接 53 秒反复回读 + 2 轮断链重连 | — |
| GAIA 通道 | ch6 上零回包 | — |
| 中科蓝讯 bootloader 令牌 | `SYNC A5 96 87 5A` 等 6 种令牌零回包 | — |
| 帧结构变体 | cid 0..255 / commandType 0..3 / indexTotal 8 种 / seq 4 种 | — |
| USB 裸访问 | 非管理员 + HID `OutputReportByteLength=0` | — |
| OTA 固件 dump | 真机回单字节 01，无镜像来源 | — |

### 5.2 剩下的两种可能

**① 142 存在 Mate 协议未暴露的另一存储位置**

官方激活流程（`F046UnlockLDACActivity`）走的是「服务器发 `activateCode` → App 写进去」。
如果 `activateCode` 不是写 140/141，而是写另一个从未被读过的地址，那么：
- 需要找到那个地址（可能是 `cid=39` 未回包区间里的某个 cmdId，或另一条 cid）
- **注意**：11-11 的全扫里 `cmd=140/141` 落在 `25..127` 段全部 no-reply，
  而 11-12 的扫描里 140/141 回 16 字节 UUID——**这个矛盾尚未查明**，可能是破口。

**② 置位需要固件内嵌密钥的密码学校验**

28 个 `.so` 已全部扫过 AES S-box / AES Te0 / UUID 常量，只有 FFmpeg 与 libcoap 命中。
若密钥在固件里（不在 APK），则**必须拿到固件二进制**。

### 5.3 拿到固件的三条路

| 路径 | 前置条件 | 状态 |
|---|---|---|
| **中科蓝讯 XLink 下载器** | 3.3V 桥 + TX/RX 各串 200Ω 短接 + 接 `PB3`（常与 USB D+ 同脚）或 `PA7` | 需拆机。工具 `bluetrum-tools` 已 clone，但 `BT8931H` 不在已知芯片代号表（`SMAT`/`QUEN`/`PRAO`）里，`fwmake1.py` 的 header 附加内容未复现，`scsiio` 依赖缺失 |
| **官方 OTA 服务器** | — | `shop.fiil.com` / `www.fiil.com` / `source-img.fiil.com` 全部 **NXDOMAIN**，已关闭 |
| **中科蓝讯官方工具站** | — | `bluetrum.com/product/showproduct.php?id=679` 有 DataSheet；`sunsili.com` 有 Xlink 工具线索。**未尝试下载** |

### 5.4 如果继续研究，建议的顺序

1. **先查 5.2 ① 的扫描矛盾**（成本最低）：重跑一次 `cid=39` 的 140/141 查询，
   确认它们到底回不回包、回什么。这直接决定「是否有未发现的存储位置」。
2. **扫 `cid=39` 的 0x19..0x7F 未回包区间**，但这次要用**带 payload 的写**而不是纯查询
   （历史上全是纯查询，从未在这个区间试过写入）。
3. 拿到固件（5.3）。

---

## 6. 工具与证据索引

### 6.1 探针脚本（`D:\DeepSeek\fiil_work\src\`）

| 脚本 | 作用 | 是否跑过 |
|---|---|---|
| `probe_chan_matrix.py` | 信道 × 协议矩阵（15 信道 × Mate/GAIA） | ✅ 证伪 GAIA |
| `probe_gaia_dump.py` | GAIA 文件系统 dump（open/read/close/sync） | ✅ 零回包 |
| `probe_gaia_fs.py` | GAIA 文件系统 + LDAC 家族 + 试用模式 | ✅ 零回包 |
| `probe_gaia_auth.py` | GAIA 鉴权族（AUTH_BITMAPS / PS_KEY / FEATURE） | ✅ 零回包 |
| `probe_license_shape.py` | 17B 裸帧 vs 19B 前缀对照 | ✅ 决定性 |
| `probe_boot_recompute.py` | 写 → `cid=36` 重启 → 回读 | ✅ 否证 |
| `sweep_single_byte.py` | UUID 单字节全扫 4335 | ✅ 全灭 |
| `sweep_correct_shape.py` | 正确形状下 410 候选 | ✅ 全灭 |
| `sweep_keyed.py` | 带 App 密钥的派生 88 候选 | ✅ 全灭 |
| `probe_time_license.py` | 时间型 license 173 候选 | ✅ 全灭 |
| `probe_ack_semantics.py` | ACK 语义标定 + 143 A/B 对照 | ✅ |
| `probe_rescue.py` | 恢复出厂后 5 种自救 | ✅ 全失败 |
| `probe_reset_then_license.py` | 恢复出厂 → 重启窗口写 license | ✅ 首次执行 cid=36 |
| `probe_last_dims.py` | 帧结构维度 + bootloader 令牌 | ✅ |
| `probe_value_space.py` | `cid=1` 值空间全扫 8192 帧 | 已写好，未完成 |
| `usb_full_desc.py` | Hub IOCTL 读 USB 完整描述符 | ✅ 决定性 |
| `usb_hid_report_desc.py` | 读 HID 报告描述符 | ✅ 零 Output 项 |
| `dis_libbluetoothdata.py` | capstone 反汇编 `libBluetoothData.so` | ✅ 解出全部 11 个 JNI |
| `gaia_cmds.py` | 提取 GAIA 全部命令字节参数 | ✅ 132 条 |
| `hunt_original_uuid.py` | 全工作区搜原始 UUID（53512 文件） | ✅ 无残留 |

### 6.2 证据文件（`D:\DeepSeek\fiil_work\docs\evidence\`）

| 文件 | 内容 |
|---|---|
| `usb_full_descriptor_20261116.md` | USB 完整描述符（4 接口 + OUT 端点 EP 0x02 + HID 报告描述符全文） |
| `factory_reset_20261116_2115.md` | 恢复出厂首次执行全过程（9 项对比 + 17 次 SET raw 帧 + 5 种自救） |
| `libBluetoothData.asm.txt` | 11 个 JNI 符号的 ARM64 反汇编（1042 行） |
| `info_diff_after_license.json` | 全 info 差分，定位 140/141 |
| `license_shape.json` | W1~W5 帧形状对照 |
| `boot_recompute.json` | 写→重启→回读 |
| `correct_shape_sweep.json` | 410 候选完整记录（59690B） |
| `keyed_license.json` | 88 候选派生 |
| `time_license.json` | 173 时间型候选 |
| `ack_oracle2.json` | 18 候选 ACK 矩阵 |
| `single_byte_sweep.json` | 4335 单字节全扫 |
| `last_dims.json` | 帧结构维度 + bootloader 令牌 |
| `license_delayed.json` | 53 秒延迟 + 2 轮重连 |
| `reset_then_license.json` | 恢复出厂 + 重启窗口写 |

### 6.3 APK 反编译产物

```
D:\DeepSeek\fiil_analysis\jadx_out\sources\
├── com\fengeek\main\i\d\c.java        ← F046 命令类（最重要）
├── com\fengeek\main\f046\             ← F046 全部 UI 与 Activity
├── com\bluetrum\devicemanager\f39\request\  ← F39AppSettingRequest 等
├── com\fiil\sdk\gaia\father\Gaia.java ← GAIA 常量全表（对 F046 无效）
├── com\fiil\sdk\command\              ← 21 个 FiilCommandXxx 类
└── com\fiil\sdk\manager\FiilManager.java  ← earType → 命令类分派（★ 46 落 default）
```

---

## 7. 一句话给未来的自己

**LDAC 的「开关」是协议问题，已经解决了；LDAC 的「授权」是固件问题，协议层解决不了。**

模块能做的、且已经做对的：读 142/143、写 143、按机型门控、正确提示用户去官方 App 解锁。
模块不该做的：尝试写 142。**5000+ 次失败已经证明这条路在协议层是封死的。**
