# FiilPods v3.3.2 · 头戴式电量修复

## 问题

FIIL Key Max（earType=46，头戴式）连接后，模块界面显示：

```
左 0%    右 85%    盒 0%
```

真机日志里的电量回包为：

```
02 27 02 00 05 01 03 00 55 00
```

按 Mate 帧结构拆开：`seq=02`、`cid=0x27(QUERY)`、`type=02`、`reserved=00`、
`len=05`、`cmdId=01(BATTERY)`、`payload=[00, 0x55, 00]`。`0x55 = 85`。

头戴式只有一档整体电量，官方 `F046HomeFragment.java:623-625`：

```java
public void setElectricity(byte[] buff) {
    m(buff[1]);   // 只读 payload[1]
}
```

旧实现按双耳的 `[左, 右, 盒]` 解析，于是把 `[0, 85, 0]` 读成
「左 0 / 右 85 / 盒 0」。这不是显示层的问题，是解析层就错了；只改 UI
会把错误数字藏起来，通知栏、设置页、超级岛仍然会读到同样的错值。

## 改动

| 层 | 文件 | 行为 |
|---|---|---|
| 协议解析 | `pods/BatteryPayload.kt`（新增） | `parseMate(payload, headphones)` 纯函数：头戴读 `[1]` → `(整体, -1, -1)`；双耳读 `[0]/[1]/[2]`；255/缺字节 → -1；单字节固件左右同值 |
| 控制器 | `pods/FiilRfcommController.kt` | `applyMateBattery` 改用 `BatteryPayload`；GAIA 电量分支头戴写 left、清空 right/case |
| 状态 | `pods/FiilStatus.kt` | 新增 `headphones`（按 `FiilModels.isHeadphones` 计算）与 `displayBattery`（头戴取整体、双耳取较低） |
| 快照 | `pods/BatterySnapshot.kt` | 新增 `headphones`；头戴时 `right/case = null`；系统三路编码仍是 `[85,255,255]` |
| 广播 | `pods/OppoCompatBridge.kt` | 新增 `battery_headphones` / `EXTRA_BATTERY_HEADPHONES`；头戴时 right/case 传 -1、`BatteryParams` 只带 left |
| 主界面 | `ui/MainUI.kt`、`ui/components/PodStatus.kt` | 头戴单列「头戴 85%」，不画三列；`batteryParams.headphones` 决定布局 |
| 设置卡 | `hook/SettingsControlCard.kt` | 头戴显示「电量 85%」，不再拼「左/右/盒」 |
| 通知/岛 | `hook/MiBluetoothToastHook.kt`、`utils/FocusIslandUtil.kt` | 通知正文、AOD、焦点岛头戴显示「电量 85%」，不写 L/R |

## 判定依据

`FiilModels.isHeadphones(earType)` 的集合是
`{2, 5, 8, 46, 247, 250}`（Wireless、Diva Pro、Diva、Key Max、Diva2、Diva2 Pro），
已有 `HeadphoneFormFactorTest` 锁表。本次没有改这张表，只是第一次真正把它
用在电量解析上。

## 验证

- `:app:compileDebugKotlin` 通过。
- `:app:testDebugUnitTest`：21 suite / 165 tests / 0 failures / 0 errors。
- 新增 `BatteryFormFactorTest` 13 条：
  - `[0,85,0]` 头戴 → `(85,-1,-1)`；同一段字节双耳 → `(0,85,0)`；
  - 单字节、255、空包降级；
  - `displayBattery` 头戴取整体、双耳取较低、单耳回落；
  - `BatterySnapshot` 头戴 right/case 为 null、`values() = [85,255,255]`。

## 真机回归

1. 连 Key Max，主界面应只出现一列「头戴 XX%」，数值与日志 `payload[1]` 一致。
2. 下拉通知栏：正文「电量：XX%」，不再出现「左耳/右耳/充电盒」。
3. 原生蓝牙设置里的耳机卡：一行「电量 XX%」。
4. 连一台真无线（如 CC nano）：仍是左右耳 + 盒三列，未受影响。

## 未改

- MiLink 卡片列表接口固定三元素，头戴传 `[-1, 85, -1]`，总电量取 left。
- 强提示右槽仍是官方右耳动画（文案为空）。
- 头戴的盒电/分耳充电位不存在，保持未知，不伪造 0。
