# v3.4.2 修复说明：状态栏弹层补上耳机产品图

> 现象：点通知 / 点状态栏弹出来的那个小卡片里没有耳机图标，只有三列电量。
> 用户描述：「点击状态栏弹出来的画面里面没有耳机图标了」。

## 一、结论先说：不是回归，是从来没有

弹层 = `PopupActivity`（主题 `Theme.FiilPods.Popup`，由通知点击或
`ACTION_SHOW_PODS_UI` 拉起）。改动前它的三张卡片分别是：

| 卡片 | 内容 |
| --- | --- |
| 第 1 张 | `PodStatus(batteryParams)` —— 左/右/盒三列电量小图标 + 佩戴图标 |
| 第 2 张 | `AncSwitch` 降噪切换 |
| 第 3 张 | 游戏模式开关 |

`PodStatus`（`ui/components/PodStatus.kt`）画的是 `common_1..10` /
`charge_1..10` 这些 24dp 电量图标，**没有任何耳机产品图**。

取证：

```
git log  -- app/src/main/java/moe/chenxy/fiilpods/PopupActivity.kt
  → db8e390 (v2.2.0)、2aa474f (v2.1.0 初始)，只有这两次

git log -S "PodImages" -- .../PopupActivity.kt
  → 无输出
```

也就是说弹层历史上从未引用过 `PodImages`。再查前身项目
`refs/OppoPods/.../PopupActivity.kt` 与 `refs/HuaweiPods/.../PopupActivity.kt`，
对 `Image(|painterResource|PodImages|drawable|BitmapPainter` 全部零命中 ——
**这个弹层从模仿 OppoPods 起就没有设备图**。

所以「没有了」这个体感，来自通知栏那条链路（`FocusNotification` 的岛/
大图、`PodImageLoader.loadModelBoxBitmap` 三级回落）一直有图，而弹层一直
没有，两者放在一起看就像是弹层「丢了图」。

## 二、改了什么

### 1. `ui/components/PodImagePainter.kt`（新增）

把 `ui/pages/PodDetailPage.kt:228-241` 里私有的 `rememberPodImagePainter`
提成共享 Composable：

```kotlin
@Composable
fun rememberPodImagePainter(path: String?, earType: Int): Painter
```

三级回落保持原语义：

1. `path` 非空且能 `BitmapFactory.decodeFile` → 用户自定义盒图；
2. `earType > 0` → `PodImages.drawableOf(earType)` 官方型号图；
3. 否则 `R.drawable.img_box` 通用盒图。

`earType <= 0` **故意**不落 `PodImages.FALLBACK`（`fiil_unknow`）：
那是一张带问号的占位图，摆在产品图位置会被误读成「模块识别不出型号」。

`PodDetailPage` 删掉私有实现，改为引用这个共享版本，行为不变。

### 2. `PopupActivity.kt`

新增两个状态与一个落值函数：

```kotlin
val earType = remember { mutableIntStateOf(-1) }
val boxImagePath = remember { mutableStateOf<String?>(null) }
val lastImageAddress = remember { mutableStateOf<String?>(null) }

fun applyDeviceImage(address: String?, type: Int)
```

型号来源按置信度回落（与 `MiLinkServiceHook.headsetTypeIcon()` 同一套）：

```
广播带的 earType → FiilConfig.readEarTypeForAddress(address) → FiilModels.earTypeOfName(deviceName) → -1
```

- `ACTION_PODS_BATTERY_CHANGED` 分支调用
  `applyDeviceImage(EXTRA_ADDRESS, EXTRA_EAR_TYPE)`；
- `ACTION_PODS_CONNECTED` 分支调用
  `applyDeviceImage(EXTRA_ADDRESS, productId.toIntOrNull() ?: -1)`
  —— FIIL 侧的 `product_id` 就是 `earType.toString()`（见
  `OppoCompatBridge.publishConnection`）；
- 换地址（`address != lastImageAddress`）时先把 `earType` 清成 -1，
  避免 A 的图挂到 B 上。

布局：

| 方向 | 位置 | 尺寸 |
| --- | --- | --- |
| 竖屏 | 电量卡内、`PodStatus` 上方，`Column(horizontalAlignment = CenterHorizontally)` | 定高 120dp + `ContentScale.Fit` |
| 横屏 | 电量卡内、`PodStatus` 左侧同一 `Row` | `weight(0.34f)`、`heightIn(max = 96dp)` + `Fit` |

为什么竖屏选「定高 + Fit」而不是「定宽 + FillWidth」：各型号产品图宽高比
不同（盒图接近 2:1，头戴图偏方），定宽会让弹层高度随型号跳变；定高既稳定
又不会裁掉图。横屏整个弹层固定 240dp 高，图铺在上方会把电量与 ANC 挤没，
所以改成左右分栏。

### 3. `OppoCompatBridge.kt`

```kotlin
// 去重 key 补 earType
val key = "${s.batteryLeft}/${s.batteryRight}/${s.batteryCase}/${s.charging}/$headphones/${s.earType}"

// 电量广播补型号
putExtra(FiilAction.EXTRA_EAR_TYPE, s.earType)
```

前者修的是「换型号不换图」：同为头戴的 46（Key Max）与 8（Diva）电量
可能完全一样，旧 key 会把第二条广播去重掉，产品图就停在上一款。后者是
弹层唯一能拿到型号的通道（弹层注册的是 OppoPods 那套广播，不监听
`FiilAction.ACTION_STATE`）。

## 三、验证

```
.\gradlew.bat :app:compileDebugKotlin --offline -q   → 通过
.\gradlew.bat :app:testDebugUnitTest --offline       → BUILD SUCCESSFUL in 15s
app/build/test-results/testDebugUnitTest/*.xml 汇总  → suites=22 tests=178 failures=0 errors=0 skipped=0
```

与 3.4.1 基线（22/178）一致，未新增也未破坏用例。

编译期踩到两个 Compose import 坑，记录备查：`Alignment`、`Modifier.width`
（`width` 与 `widthIn`、`height` 与 `heightIn` 是不同扩展函数，必须各自 import）。

## 四、真机回归步骤

1. 连上 Fiil 耳机（Key Max 走头戴、TWS 任一），确认通知栏图标是型号图。
2. 点通知 → 弹层出现，竖屏下电量卡上方应显示与型号一致的产品图。
3. 旋转到横屏（或宽屏折叠/平板）→ 图应在电量左侧，电量与 ANC 都完整可见。
4. 在模块「耳机」页给这台耳机设自定义盒图 → 重开弹层，应显示自定义图。
5. 断开换另一副（例如 Diva 头戴 ↔ 某 TWS）→ 弹层图与电量列形态都要跟着换。

## 五、没改的

- **状态栏最左侧小图标**仍然用系统蓝牙图标。原因见 v2.2.3
  （`2a308a3`）：Android 状态栏图标只取 alpha 通道单色着色，彩色型号图压成
  剪影后 24dp 认不出，比系统图标更难辨识。
- **融合中心图标**不改，v3.4.0 已按 `FiilModels.isHeadphones` 返回
  `circulate_device_headset_headphones`(7) / `circulate_headset_icon`(3)，
  并在 earType 未到时回落到地址绑定与设备名。
