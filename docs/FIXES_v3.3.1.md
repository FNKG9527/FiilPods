# FiilPods v3.3.1 修复说明

> 基线：v3.3.0（`d013e83`）  
> 范围：第二轮审计 `_reports/audit_v3.3.0/AUDIT_REPORT_v3.3.0.md` 的 P0 项，
> 外加 3 条不需真机即可判定的 P1（E15/E16/E17）。  
> 原则：只改「读回来的数据是错的 / 根本没读 / 根本没有接收端」的地方；
> 协议线值有争议的一律不动。

---

## 1. 修了什么

| ID | 症状（修复前） | 改动位置 | 修复后行为 |
|----|----------------|----------|------------|
| E1 | Mate 电量三路只解 `payload[1]`，左右耳同值，盒电恒 -1 | `FiilRfcommController.kt:1766` | 按官方 `buff[0]/[1]/[2]` 解左/右/盒；单字节短包退化同值；`255` = 未知 |
| E2 | `GET_BATTERY_CHARGE`(0x0306) 零引用，`isCharging` 五处硬编码 false | `FiilRfcommController.kt:1828`、`FIILProtocol.kt:648`、`FiilStatus.kt:22`、`OppoCompatBridge.kt:217/259`、`MainUI.kt:1236` | GAIA 链路查询并解析 `payload[1]==1`，写入状态 JSON、`PodParams.isCharging` 与 `EXTRA_BATTERY_CHARGING`；盒电仍为未知 |
| E3 | GAIA 多点关闭写 0（官方 1/2），且 `dispatch` 无 258/386 分支 → 必超时 | `FIILProtocol.kt:380`、`FiilRfcommController.kt:1844` | 线值 `1(开)/2(关)`；收到 258/386 时按 1/2 回填并结算 pending |
| E4 | GAIA EQ 回包只取 `payload[1]`，reserved/十段增益全丢；ACK 也当状态 | `FiilRfcommController.kt:1852` | 仅 `!isAck` 时按 `[reserved][type][b0..bn]` 解析；1/2/3+ 字节分别降级 |
| E5 | Mate 上先登记 `expectAck` 再 return → 2.5 s 后必弹失败 | `FiilRfcommController.kt:2153` | 方言判断前移；Mate 直接拒绝并记日志，不再假超时 |
| E6 | Mate 无 search 命令却无条件发 GAIA `SET_SEARCH` | `FiilRfcommController.kt:2222` | 返回 Boolean；Mate 记日志并返回 false，不再伪装成功 |
| E7 | 生产端写 Boolean、消费端 `getIntExtra` → 恒 -1，界面永远「未知」 | `OppoCompatBridge.kt:259`、`FiilStatus.kt:31`、`FiilRfcommController.kt:1841` | 发 `WearState` Int（0x07/0x05/-1）；新增 `wearKnown`，未回包时显示未知 |
| E8 | UI 发 `ACTION_AUTO_GAME_MODE_CHANGED`，蓝牙进程从未注册 → 开关要重启才生效 | `MainUI.kt:991`、`BluetoothProcessHook.kt:766` | 改发 `CONFIG_CHANGED`；同时兼容接收旧 action |
| E9 | 「删除自定义 EQ」只有发送端，没有接收端 | `BluetoothProcessHook.kt:943` | 接收后把曲线恢复平直（128），失败走 `OP_FAILED` |
| E15 | Settings 代理的 `getDeviceInfo`/`isSupportAudioSwitch` 按 `(BluetoothDevice)` 注册，宿主只有 `(String)` → Hook 从不触发 | `SettingsHook.kt:312` 起 | 两种签名都注册；新增 `isFiilPodAddress()` 用地址表 + FIIL OUI 兜底 |
| E16 | `updateAtUiInfo` 是 package-private，`callMethod` 只查 public → 静默不回填 | `HookContext.kt:195` | 先查 public，再沿继承链查 `declaredMethods` 并 `setAccessible(true)` |
| E17 | 日志兜底写进 `com.android.bluetooth` 私有目录，模块读不到、也导不出 | `RfcommLog.kt:688` | 不再写宿主文件；只记一条 warning 让丢弃可见 |

### 语义说明（避免误读）

- **E6 不是「修好了查找」**：Mate 协议族（含官方 3.5.2）没有 find/search 命令。
  修的是「点下去假装成功、2.5 秒后必失败」；要真正支持得等官方协议或换 GAIA 机型。
- **E9 的「删除」= 恢复平直**：设备端只保存一条实时自定义曲线，没有预设列表可删。
  这是唯一能落地的语义，已在注释和 CHANGELOG 写明。
- **E7 的盒内佩戴位**仍为 -1：协议没有可靠入盒位，不猜。

---

## 2. 验证

```
.\gradlew.bat :app:compileDebugKotlin --offline     # 通过
.\gradlew.bat :app:testDebugUnitTest  --offline     # 20 suite / 152 tests / 0 failures
```

单测只覆盖纯逻辑（帧解析、策略、编码）；本次改动的状态机大多在 Android 进程内，
**必须真机回归**。建议顺序：

1. Mate 机型：看左右耳电量是否不同、盒电是否出现；点 3D 不再弹失败；点「查找」应安静失败（日志有 `findDevice ignored`）。
2. GAIA 机型：开关「蓝牙畅连」各一次，确认不再超时、状态回弹正确；切自定义 EQ 后重进页面，十段增益与 reserved 应保持；插电看是否出现充电态。
3. 任一台：戴/摘一只，通知栏或融合卡片应显示「佩戴/摘下」，而不是一直「未知」。
4. 设置 → 蓝牙 → 耳机详情：状态回填（ANC 文案、电量）应正常；日志里不应再有
   `getDeviceInfo skipped` / `isSupportAudioSwitch skipped` / `inject fragment status failed`。

---

## 3. 未修清单（及原因）

| 项 | 原因 | 下一步 |
|----|------|--------|
| EQ 增益 `/12` 换算 | 官方 App 把回包原始 signed byte 直接当滑杆值回写，与模块「128=0 dB、±12 满量程」冲突；静态无法裁决 | 真机抓一次写 EQ 的 RFCOMM 帧，对比官方 App 写入值 |
| GAIA 帧版本位 `0xF1` vs 官方 `0x01`（0x070B/0x070C/0x0719/0x071A） | 改错会让现有可用机型全哑 | 真机 A/B：同一条命令两种版本位各发一次 |
| E10 通透人声增强 | 全仓无生产端，也没有协议映射 | 先在官方 App 抓包确认命令，再补 |
| E11 smartAnc 档位 | 只有消费端；宿主是否上报、上报字段都未证实 | 抓 `HeadsetInfo`/MiLink 状态回调 |
| E12 GAIA 自适应/LDAC/语音音量 | 协议族没有对应命令，不是漏查 | 改 UI 门控（Mate-only 能力不在 GAIA 机型显示） |
| E13 入盒状态 | 无可靠协议位 | 保持 -1，不猜 |
| E14 固件版本 | `GET_VERSION` 回包格式未取证 | 抓包确认后加字段 + 关于页展示 |
| E18 SettingsControlCard 不读 `milinkCardFeatures` | 复核后判定**不是缺陷**：该配置只控制 MiLink 融合卡片（游戏/空间音频两个面板），原生设置卡的「影院模式」是机型能力，不属同一开关 | 无需改；若要扩语义需产品决策 |
| E19 Mate MAF 5/6 归一化 | 线值与 UI 语义映射未证实 | 真机逐个模式对照 |
| E20 型号覆盖无 UI | 底层已有 `setEarTypeOverride`，缺入口 | 设置页加下拉（低风险，可下版做） |
| MiLink「找耳机」卡被游戏模式占用 | 涉及宿主卡片布局与点击路由，改动面大 | 单独任务：读 `MiLinkServiceHook.kt:921-969` 的卡片构建 |
| 自动游戏模式的 `UsageStats` 权限 | 运行在蓝牙进程 UID，模块 manifest 声明无效；未授权时策略 fail closed | 设置页加「未授权」提示 + 跳转授权页 |

---

## 4. 变更文件

```
app/build.gradle.kts                        versionCode 12 / 3.3.1
app/src/main/java/.../pods/FiilStatus.kt            charging / wearKnown
app/src/main/java/.../pods/FiilRfcommController.kt  E1/E2/E3/E4/E5/E6/E7
app/src/main/java/.../pods/FIILProtocol.kt          E2/E3 + GAIA refresh
app/src/main/java/.../pods/OppoCompatBridge.kt      E2/E7
app/src/main/java/.../ui/MainUI.kt                  E2/E8
app/src/main/java/.../hook/BluetoothProcessHook.kt  E8/E9
app/src/main/java/.../hook/HookContext.kt           E16
app/src/main/java/.../hook/SettingsHook.kt          E15
app/src/main/java/.../pods/RfcommLog.kt             E17
CHANGELOG.md                                        3.3.1 节
```
