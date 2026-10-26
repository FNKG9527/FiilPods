# SPEC_UI — OppoPods UI/UX 复刻规格（供 FIIL 换壳实现）

> 只读源 `refs\OppoPods`（GPL-3.0，仅参考不复制代码），行号对应当前 clone。
> 代码根 `refs\OppoPods\app\src\main\java\moe\chenxy\oppopods\`。
> 基线（`gradle\libs.versions.toml`）：Kotlin `2.4.20`、Compose BOM `2026.09.00`、`top.yukonga.miuix.kmp`
> 的 `miuix-ui`/`miuix-preference`/`miuix-icons`/`miuix-blur`/`miuix-nav` 均 `0.9.4`、
> `com.xzakota.hyper.notification:focus-api:1.4`。本文只讲"长什么样 + 怎么搭"，不讲 RFCOMM 协议。

**全仓不存在**（别去找）：`LazyVerticalGrid`、自定义 `Canvas` 绘制、Haze 实时模糊、ViewModel、
`runBlocking`（grep 零命中）、`NavHost`/compose-navigation、`SuperSwitch`/`SuperArrow`。

## 0. 全局约定

- **主题包装**：`AppTheme(colorSchemeMode, accentMode) { … }` 包住所有 Compose 入口（`ui\Theme.kt:13`）。
- **页面背景**：`MiuixTheme.colorScheme.surface`，封装为 `fun appBackground(): Color`（`ui\MainUI.kt:1003`）。
- **卡片**：Miuix `Card`；横向外边距 12.dp 由调用方给，卡片自身不管（`PodDetailPage.kt:225`）。
- **文案**：全部 `stringResource(R.string.xxx)`，无硬编码中文（五文件一致）。
- **动画**：单常量 `ANIM_DURATION = 300`，所有 `tween` 共用（`AncSwitch.kt:47`）。
- **compact 双档**：同一组件用 `compact: Boolean` 切两套尺寸（弹窗横屏 / 内页；`AncSwitch.kt:59`、`PodStatus.kt:51`）。

## 1. AncSwitch（降噪模式选择器）

### 1.1 公开签名（`AncSwitch.kt:49`）

```kotlin
@Composable
fun AncSwitch(
    ancStatus: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    compact: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: ((Boolean) -> Unit)? = null
)
```

无 `Modifier` 参数——根 `Column` 自己 `fillMaxWidth()`，卡片外边距由调用方 `Card` 提供。

### 1.2 视觉结构（自上而下）

```
Column(fillMaxWidth, padding(vertical = 16dp / compact 8dp))
├─ Row(fillMaxWidth, CenterVertically)，每格 Modifier.weight(1f)
│    ① 降噪 NOISE_CANCELLATION  ② 自适应 ADAPTIVE（仅 adaptiveModeEnabled 时插入）
│    ③ 通透 TRANSPARENCY        ④ 关闭 OFF
├─ if (ancStatus.isNoiseCancellation()) → AncStrengthTabRow（智能/轻度/中度/深度）
└─ if (ancStatus == TRANSPARENCY && onTransparencyVocalEnhancementChange != null)
                                          → ResponsiveAncTabRow（通透 / 人声增强）
```

**模式数量是动态的**：3 或 4 个一级按钮，随 `adaptiveModeEnabled` 变，不是写死 4 个；顺序有语义——
启用 → 自适应 → 通透 → 完全关闭，线性递进。

### 1.3 AncButton（`AncSwitch.kt:275`）

`Column(horizontalAlignment = CenterHorizontally)`，从下到上：图标盒 `Box(size)`（`60.dp` / compact `40.dp`）→
选中图标 `Image.size` 撑满盒（`60.dp` / `40.dp`）、未选中 `48.dp` / `32.dp` → `Spacer(4.dp)` →
标签 `Text`（`14.sp` / `12.sp`，均 `Medium`）。

- 图标是**成对 drawable**：`offIconRes` / `onIconRes`（`ic_openanc_*`、`ic_adaptive_*`、`ic_transparent_*`、
  `ic_closeanc_*`），`Crossfade(targetState = isSelected, tween(300))` 切换；未选中灰阶描边、选中实心彩色。
- 选中用**尺寸 + 资源**双通道表达，**不叠加背景 pill 色块**；标签色 `animateColorAsState(tween(300))`：
  选中 `MiuixTheme.colorScheme.primary`，否则 `onBackground`，字重不随选中变。

### 1.4 按压反馈（关键 mechanic）

```kotlin
val interactionSource = remember { MutableInteractionSource() }
Column(modifier = modifier
    .pressable(interactionSource = interactionSource, indication = SinkFeedback())
    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick))
```

`pressable` 给 Miuix 的"下沉"反馈（`SinkFeedback`），`clickable` 只接手势且 `indication = null` 关掉 Material ripple
——否则两套反馈叠加。`interactionSource` 必须 `remember` 且两处共用同一实例。

### 1.5 二级行一：AncStrengthTabRow（`AncSwitch.kt:173`）

仅当 `ancStatus.isNoiseCancellation()`（`Packets.kt:139` 覆盖 NC + SMART/LIGHT/MEDIUM/DEEP）时出现。

- `BoxWithConstraints(fillMaxWidth, padding(top = 16dp / compact 8dp), contentAlignment = Center)` 读 `maxWidth` 做响应式：
  `fillMaxWidth(if (maxWidth >= 480.dp) 0.8f else 1f)`（平板/横屏收窄 80%）。
- **两层叠放**：底层 `TabRowWithContour`，上层同尺寸 `Row` 覆盖。因为 `tabs` 传的是 `tabs.map { "" }`，
  文字由上层自己画，才能做三态——"选中 = primary + SemiBold"、"辅助高亮 = primary@72% + SemiBold"、
  "其余 = onBackground + Medium"。
- `selectedTabIndex = modes.indexOf(ancStatus).takeIf { it >= 0 } ?: 0`；`assistHighlightedIndex =
  if (isSmart) smartLevelIndex else null`，`smartLevelIndex` 映射 `LIGHT→1, MEDIUM→2, DEEP→3`，其它 `null`。
  即智能降噪正贴合某档时，那档以 72% primary 提示，选中块仍停在"智能"。
- 上层 Row 每格 `weight(1f)` + `Box(contentAlignment = Center)`，格间 `Spacer(size(itemSpacing))`（末格不加）。

### 1.6 二级行二：ResponsiveAncTabRow（`AncSwitch.kt:241`）

通透模式二选一：`[通透, 人声增强]`，`selectedTabIndex = if (vocalEnhancement) 1 else 0`。
这里把真实文案交给 `TabRowWithContour`，不做覆盖层（两格、无辅助高亮态，不值得）。`topPadding` 默认 `8.dp / 16.dp`。

### 1.7 TabRow 尺寸参数（两行共用，AncSwitch 顶层算好传入）

`minWidth` 恒 `0.dp`；其余随 compact 变：`maxWidth`（单 tab 上限）`98.dp` / `72.dp`、`height` `45.dp` / `38.dp`、
`itemSpacing` `9.dp` / `4.dp`、`outerPadding`（左右）`12.dp` / `8.dp`。

### 1.8 themedPainterResource（`AncSwitch.kt:336`）— 夜间换图兼容层

`AppTheme` 用 `CompositionLocalProvider(LocalConfiguration …)` 覆写夜间模式，但 `painterResource` 走
`context.resources.configuration`，两者可能不一致：

1. 比较 `context.resources.configuration.uiMode` 与 `LocalConfiguration.current.uiMode` 的 `NIGHT_MASK`；相等 → 直接
   `painterResource(id)`（最省）。
2. 不等 → `context.createConfigurationContext(Configuration(...).apply { uiMode = … })`，
   `remember(context, themeNightMode)` 缓存这套 `Resources`。
3. 取 drawable：`BitmapDrawable` 直接转 `BitmapPainter`；矢量/其它则
   `Bitmap.createBitmap(intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1), ARGB_8888)`，
   `drawable.setBounds(0,0,w,h); drawable.draw(canvas)` 再包 `BitmapPainter`；`remember(id, themeNightMode)`。

`PodStatus.kt:218` 有个简化版直接强转 `(getDrawable(...) as BitmapDrawable)`——只对位图安全，**矢量会崩**。
重建时统一用 AncSwitch 的完整版。

## 2. PodStatus（电量行）

### 2.1 公开签名（`PodStatus.kt:43`）

```kotlin
@Composable
fun PodStatus(
    batteryParams: BatteryParams,
    wearStatus: WearStatus = WearStatus(),
    modifier: Modifier = Modifier,
    compact: Boolean = false
)
```

`BatteryParams(left/right/case: PodParams?)`、`PodParams(battery, isCharging, isConnected, rawStatus)`；
`WearStatus(left/right/case: WearState?)`，`WearState = DISCONNECTED(0x00)|IN_CASE(0x04)|REMOVED(0x05)|WEARING(0x07)`。

### 2.2 布局

`Row(fillMaxWidth, CenterVertically)`：
`BatteryColumn(左, weight=1f) │0.5dp│ BatteryColumn(右, weight=1f) │0.5dp│ BatteryColumn(盒, weight=1f)`。

- 竖线 `Box(Modifier.width(0.5.dp).height(dividerHeight).background(dividerColor))`，`dividerHeight = 56.dp / compact 40.dp`。
- `dividerColor` **硬编码、不是主题 token**：夜间 `0xFF333333`、日间 `0xFFEEEEEE`，由 `isSystemInDarkTheme()` 判断。

### 2.3 BatteryColumn（`PodStatus.kt:94`）

`Box(modifier, Center)` → `Column(alignStart, padding(vertical = 4.dp / 2.dp))`，顺序四件套：

1. `Row`：标签 `Text` 16.sp **Bold** + `WearStatusIcon`（前距 2.dp）。
2. `Spacer(4.dp)`。
3. 电量文本 13.sp、`Color.Gray`；连上 `"$level%"`、断开 `"-"`。
4. 电池 `Image` 24.dp，`themedPainterResource(getBatteryIconRes(...))`。

- **标签补齐**：`if (label.length < 3) label.padEnd(3, '\u3000') else label`——全角空格把"左/右"撑成"耳机盒"宽，
  列内左对齐才齐。不测量文本、不加 `Modifier.width`。
- **佩戴图标**：`Box(size(18.dp), Center)` 内 `Icon(size(14.dp), tint = Color.Gray)`，
  `WEARING → AppIcons.Contacts`、`REMOVED → AppIcons.RemoveContact`，其余（含 `null`/IN_CASE）`return@Box` 不画；
  18dp 盒保证有无图标时行高一致。
- **电池图标分级**：`index = ceil(level/10)` 落 1..10（`<=10→1`、`<=20→2` … `else→10`），充电用
  `R.drawable.charge_1..10`、否则 `R.drawable.common_1..10`。
- **断连保持最后值**：

```kotlin
var lastKnownLevel by remember { mutableIntStateOf(100) }
if (level > 0) { lastKnownLevel = level }
val displayLevel = if (isConnected) "$level%" else "-"
val iconLevel = if (isConnected) level else lastKnownLevel
```

文本降到 `-`，图标仍用最后有效值（初值 100），避免掉连瞬间闪 0% 空电池。

## 3. PodDetailPage（耳机详情页）

### 3.1 签名（`PodDetailPage.kt:48`）

```kotlin
@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String, batteryParams: BatteryParams, wearStatus: WearStatus = WearStatus(),
    ancMode: NoiseControlMode, onAncModeChange: (NoiseControlMode) -> Unit, …
```

其余参数全带默认值、纯受控 + 回调：`smartAncLevel`、`transparencyVocalEnhancement`、
`onTransparencyVocalEnhancementChange`、`gameMode`、`gameModeSupported`、`onGameModeChange`、
`spatialAudioMode: Int`、`onSpatialAudioModeChange`、`dualDeviceConnection`、`onDualDeviceConnectionChange`、
`spatialAudioSupported`、`spatialSoundSupported`、`adaptiveModeEnabled`、`equalizerVisible`、
`dualDeviceSupported`、`onOpenEqualizer`、`boxImagePath: String?`。组件内**零状态、零业务调用**。

### 3.2 两种朝向（`PodDetailPage.kt:75`）

`val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE`

- **竖屏**：`LazyColumn(fillMaxSize, contentPadding = 外部传入, horizontalAlignment = Center)`，
  item0 = 耳机盒图片（`fillMaxWidth(0.7f)` + `padding(vertical = 16.dp)` + `ContentScale.FillWidth`），
  随后 `podControlItems(...)`，末尾 `Spacer(bottomContentPadding)`。
- **横屏**：`Row(fillMaxSize)`——左列 `weight(1f)`（`padding(horizontal = 12.dp)`，中心对齐）图片 + 耳机名；
  右列 `weight(1f)` 是 `LazyColumn(contentPadding = top 12.dp, verticalArrangement = Center)`。
  图片 `fillMaxWidth(0.82f).widthIn(max = 360.dp)`，耳机名 `22.sp Bold, padding(top = 12.dp)`。分支末尾 `return`。
- 图片：

```kotlin
private fun rememberPodImagePainter(path: String?) = remember(path) {
    path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull()
        ?.let { bmp -> BitmapPainter(bmp.asImageBitmap()) } }
} ?: painterResource(R.drawable.img_box)
```

### 3.3 区块顺序（`podControlItems`，`PodDetailPage.kt:194`）

| # | 外层 | 内容 | 可见条件 |
|---|---|---|---|
| 1 | `Card(padding(horizontal = 12.dp))` | `PodStatus(padding(horizontal 12, vertical 16))` | 始终 |
| 2 | `Card(padding(horizontal = 12, vertical = 12))` | `AncSwitch(...)` 全参透传 | 始终 |
| 3 | `Card(padding(horizontal = 12))` | 3.4 的设置组 | 始终（内部逐项判条件） |
| 4 | — | `Spacer(bottomContentPadding)` | 始终 |

卡 1 与卡 2 之间**没有**额外 `vertical` 间距，靠卡 2 的 `vertical = 12.dp` 提供 12dp 间隙。

### 3.4 第三张卡内部顺序（同一 `Card` 内纵向堆叠，Miuix 自动分组画分隔线）

1. `SwitchPreference` 游戏模式 —— `gameModeSupported`；title 游戏模式 / summary 低延迟音频模式。
2. `OverlayDropdownPreference` 空间音频 —— `spatialAudioSupported`；`items = [关闭, 固定, 头部跟踪]`，
   `selectedIndex = spatialAudioValues.indexOf(spatialAudioMode).coerceAtLeast(0)`；值域 `OFF=0 / FIXED=1 / HEAD_TRACKING=2`。
3. `SwitchPreference` 空间音频开关 —— `spatialSoundSupported`；`checked = spatialAudioMode != OFF`，
   开→写 `FIXED`、关→写 `OFF`（与 2 共用状态）。
4. `BasicComponent` 大师调音（EQ 入口）—— `equalizerVisible`；`onClick = onOpenEqualizer`，
   `endActions = { Icon(MiuixIcons.Basic.ArrowRight) }`，即"箭头型可跳转行"。
5. `SwitchPreference` 双设备连接 —— `dualDeviceSupported`。

`equalizerVisible` 由宿主判定：`capabilities.eqPresets.isNotEmpty() || capabilities.customEqSupported`（`ui\MainUI.kt:698`）。

### 3.5 容器与外观

- 详情页**不是**导航目的地：`EarphonesTabPage` 里用 `AnimatedContent(targetState = showEarphoneDetail)`
  在 `PodDetailPage` 与 `DevicePickerPage` 间切换（`EarphonesTabPage.kt:55`），并挂
  `Modifier.overScrollVertical().nestedScroll(nestedScrollConnection)`。
- 卡片就是 Miuix `Card`（默认圆角/容器色）；`SuperSwitch`/`SuperArrow` 的等价物是 `SwitchPreference` 与
  `BasicComponent(+ArrowRight)`。
- 背景由外层 `Scaffold` 的 `containerColor` / `Modifier.background(appBackground())` 给，页面自身不画背景。

### 3.6 子页导航

- 状态：`remember { mutableStateListOf<NavKey>(Screen.Main) }`（`ui\App.kt:30`）；`Screen` 是
  `@Serializable sealed interface Screen : NavKey`，含 `Main/About/Theme/LeAudio/Equalizer/RfcommDebug`。
- 渲染：`NavDisplay(backStack, onBack = { if (size>1) removeLast() else (context as? Activity)?.finish() },
  content = navContent)`，`navContent` 内 `entry<Screen.Equalizer> { … }`（`ui\MainUI.kt:912`）。
- 子页外壳：`Scaffold(topBar = TopAppBar(title, largeTitle, scrollBehavior = MiuixScrollBehavior(
  rememberTopAppBarState()), navigationIcon = IconButton { Icon(MiuixIcons.Back) }) { backStack.removeLast() })`，
  body 挂 `.overScrollVertical().nestedScroll(behavior.nestedScrollConnection)`。
- EQ 行只是 `onOpenEqualizer = { backStack.add(Screen.Equalizer) }`（`ui\MainUI.kt:701`）。**没有** URL/Router。

## 4. PopupActivity（耳机弹窗）

### 4.1 窗口本体：半透明 Activity，不是 Dialog

`themes.xml` 的 `Theme.OppoPods.Popup`（parent `android:Theme.Material.Light.NoActionBar`）含
`windowIsTranslucent=true`、`windowBackground=@android:color/transparent`、`windowNoTitle=true`、
`backgroundDimEnabled=false`、`colorBackgroundCacheHint=@null`。`AndroidManifest.xml:45` 上挂
`android:theme="@style/Theme.OppoPods.Popup"`、`taskAffinity=""`、`excludeFromRecents="true"`、`exported="true"`，
intent-filter action `chen.action.oppopods.show_pods_ui`。**没有** `windowIsFloating`、**没有** `configChanges`
（旋转即重建）。真正的浮层由 Compose 的 `OverlayDialog` 画在透明窗口上。
入口：通知的 `PendingIntent.getActivity(... setClassName("moe.chenxy.oppopods", "moe.chenxy.oppopods.PopupActivity")
putExtra("android.bluetooth.device.extra.DEVICE", device))`（`MiBluetoothToastHook.kt:124`）。

### 4.2 onCreate 流程（`PopupActivity.kt:64`）

1. `attachBaseContext` 先 `AppLocale.rememberDeviceLocale`，再按 `app_language` 套语言。
2. `ConfigManager.refreshFromPrefs(prefs)`；读 `BluetoothDevice`（`TIRAMISU` 走类型化 `getParcelableExtra(key, Class)`，
   低版本 `@Suppress("DEPRECATION")` 旧签名）。
3. `notificationClickAction != NOTIFICATION_CLICK_MODULE_POPUP`（0=弹窗 1=系统设置 2=欢律 App）→ 路由后 `finish()`，**不建 UI**。
4. `setContent { AppTheme(colorSchemeMode = theme_mode 0/1/2 → System/Light/Dark, accentMode = accent_mode) { PopupContent(...) } }`。

### 4.3 内容树（`PopupContent`，`PopupActivity.kt:153`）

```kotlin
Scaffold(containerColor = Color.Transparent) {
    OverlayDialog(
        title = deviceName.ifEmpty { stringResource(R.string.app_name) },
        show = showDialog,
        backgroundColor = dialogBgColor,          // 夜间 0xFF1A1A1A / 日间 0xFFF7F7F7
        onDismissRequest = { showDialog = false },
        onDismissFinished = { onDone() }          // finish()
    ) { Portrait / Landscape body }
}
```

- 宽高/圆角全交给 `OverlayDialog`（不设 `Modifier.width`）；作者只控**竖向密度**与横屏尺寸。
- 背景是**纯色，没有 blur**：`miuix-blur` 只用于主页底部导航（`MainBottomBar` 的 `textureBlur`/`LayerBackdrop`）。
- `dialogBgColor` 由 `isSystemInDarkTheme()` 与 `theme_mode` 合成（`1→false, 2→true, else→system`），
  不读 `MiuixTheme.colorScheme`——窗口底色要先于主题可见。

### 4.4 竖屏 body（`PopupActivity.kt:347`）

```
Column(fillMaxWidth)
├─ Card(fillMaxWidth)  PodStatus(padding h12 v16)
├─ Spacer 12 ── Card(fillMaxWidth)  AncSwitch(adaptiveModeEnabled = capabilities.adaptiveSupported)
├─ Spacer 12 ── Card(fillMaxWidth)  SwitchPreference 游戏模式
└─ Spacer 16 ── Row(spacedBy 12)  TextButton("更多", weight 1f) | TextButton("完成", weight 1f)
```

### 4.5 横屏 body（`PopupActivity.kt:405`）

`Row(fillMaxWidth.widthIn(min = 560.dp).height(240.dp), spacedBy 10.dp)`：

- 左列 `weight(0.60f).fillMaxHeight()`，`verticalArrangement = Center`：
  `Card{ PodStatus(compact = true, padding h10 v10) }` → `Spacer 8` → `Card{ AncSwitch(compact = true, …) }`。
- 右列 `weight(0.40f).fillMaxHeight()` 居中：游戏模式**整卡按钮**（`CardDefaults.defaultColors(...)`,
  `pressFeedbackType = PressFeedbackType.Sink`, `showIndication = true`, `onClick = { onGameModeChange(!gameMode) }`,
  `onLongPress = {}`），开启 `primary` + 白字，关闭 `surfaceContainer` + `onSurfaceContainer`；
  文字居中 `padding(vertical = 12.dp)` → `Spacer 6` → `TextButton 更多 fillMaxWidth` → `Spacer 6` → `TextButton 完成`。
- 横屏**不给** `SwitchPreference`（表单行太矮），改为可点卡片——有意的形态替换。

### 4.6 数据来源：广播 + 双兜底

- `DisposableEffect(Unit)` 里 `registerReceiver(receiver, IntentFilter(6 个 action), Context.RECEIVER_EXPORTED)`，
  同时发 `ACTION_PODS_UI_INIT` 与 `ACTION_REFRESH_STATUS`（均 `setPackage("com.android.bluetooth")` +
  `FLAG_RECEIVER_FOREGROUND`）请蓝牙栈回灌；`onDispose` 里 `runCatching { unregisterReceiver }`。
- 收到 `ACTION_PODS_CONNECTED` 才 `showDialog = true`。
- `LaunchedEffect(Unit)`：`delay(500)` 仍未显示就**强制显示**（超时兜底）；随后 `while(true) { delay(15_000); 发刷新广播 }`。
- 写操作一律**乐观更新 + 广播**：先改本地 `mutableStateOf`，再 `sendBroadcast(ACTION_ANC_SELECT / ACTION_GAME_MODE_SET /
  ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET)`；ANC 码 `1=关 2=降噪 3=通透 4=自适应 5=智能 6=轻度 7=中度 8=深度`，
  回调反向映射回 `NoiseControlMode`。

### 4.7 "更多"与关闭

- `onMore`：**重新** `ConfigManager.refreshFromPrefs(prefs)`（启动到点击可能已过几秒，配置可能变），按
  `moreClickAction`（0=欢律 `com.heytap.headset`，1=系统设置，2=模块主页）路由后 `finish()`；欢律不存在则回落模块主页。
- 系统设置项：`com.android.settings/com.android.settings.bluetooth.MiuiHeadsetActivity`，额外塞
  `MIUI_HEADSET_SUPPORT = ConfigManager.fakeSupport()`、`COME_FROM = "MIUI_BLUETOOTH_SETTINGS"`、
  `DEVICE_ID = fakeDeviceId()`、`FLAG_ACTIVITY_NEW_TASK`，`runCatching` 失败回落模块主页。
- "完成"只置 `showDialog = false`，退出动画结束由 `onDismissFinished → finish()` 收尾（不要直接 finish，否则没有收起动画）；
  点外部/返回走 `onDismissRequest` 置 false，同样经 `onDismissFinished` 退出。

## 5. FocusIslandUtil（HyperOS 焦点岛）

### 5.1 公开 API（`FocusIslandUtil.kt:25`）

```kotlin
object FocusIslandUtil {
    fun showBatteryIsland(
        context: Context, prefs: SharedPreferences,
        batteryParams: BatteryParams, address: String,
    ): Boolean
}
```

返回 `true` 表示已 `notify`，`false` 表示前置条件不满足或异常（内部 `try/catch` + `Log.e`，绝不抛）。
常量：`CHANNEL_ID = "oppopods_focus_island"`、`CHANNEL_NAME = "OppoPods Battery"`、
`NOTIFICATION_ID = 10086`、`DISMISS_DELAY_MS = 4000L`（另有未用的 `ISLAND_TIMEOUT_SECONDS = 3`）。

### 5.2 前置条件

1. 左右耳**至少一只** `isConnected`，否则 `return false`（盒子电量不参与焦点岛）。
2. 两张位图必须都取到：`PodImageLoader.loadIslandLeftBitmap/RightBitmap(context, prefs, address)`，
   为空则 `return false`（`Log.e "Failed to decode earphone icon bitmaps"`）。位图来自用户自定义图或内置资产，按耳机地址查 prefs。

### 5.3 组装（`com.xzakota.hyper.notification.focus.FocusNotification.buildV3 { }`，返回 `Bundle?`，`null` 即失败）

外层：`createPicture("key_pic_left", leftIcon)` / `("key_pic_right", rightIcon)`（`Icon.createWithBitmap(...)`，
**图片内嵌**，SystemUI 无需访问模块资源）、`enableFloat = true`、`ticker = "OppoPods"`、`tickerPic = picLeft`、
`isShowNotification = false`（只显示岛，不显示通知栏条目）。

`island { }` 子块：

| 键 | 值 |
|---|---|
| `islandProperty` | `1` |
| `bigIslandArea.imageTextInfoLeft` | `type = 1`；`picInfo { type = 1; pic = picLeft }`；`textInfo { title = leftText; content = "%" }` |
| `bigIslandArea.imageTextInfoRight` | `type = 2`；`picInfo { type = 1; pic = picRight }`；`textInfo { title = rightText; content = "%" }` |
| `shareData` | `title = "OppoPods"`；`content = shareContent = "L: 80%  R: 78%"`（两空格分隔） |

- `leftText/rightText` 是**纯数字**（不含 `%`），百分号单独放 `textInfo.content`，字号/基线与数字分离。
- 断开的一侧文本为 `"-"`，`shareData` 只拼接连着的一侧。

### 5.4 发通知与自动消失

1. 每次调用都 `createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, IMPORTANCE_DEFAULT).apply {
   setSound(null, null); enableVibration(false); setAllowBubbles(true) })`（重复创建幂等且便宜）。
2. `Notification.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
   .setContentTitle("OppoPods").setContentText(contentText).setTicker("OppoPods").addExtras(extras).build()`，
   然后 `nm.notify(NOTIFICATION_ID, notification)`。
3. `Handler(Looper.getMainLooper()).postDelayed({ runCatching { nm.cancel(NOTIFICATION_ID) } }, 4000)`——4 秒自动收回；
   无交互回调、无 `setAutoCancel`。

### 5.5 触发时机与开关

链路：`RfcommController` 判时机 → `MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt()` → 广播
`chen.action.oppopods.sendstrongtoast`（带 `batteryParams` + `address`）→ `MiBluetoothToastHook.registerNotificationReceiver`
收广播后按 `ConfigManager.islandMode()` 分派（`MiBluetoothToastHook.kt:254`）：`ISLAND_MODE_NONE (0)` 什么都不做；
`ISLAND_MODE_OFFICIAL (1)` 调 `MiuiStrongToastUtil.showOfficialConnectToast(...)`（官方 `setStatus(1,
"strong_toast_action", bundle)`）；`ISLAND_MODE_MODULE (2)` 调 **`FocusIslandUtil.showBatteryIsland(...)`**。

时机判定（`RfcommController.kt:610`）：

```kotlin
private fun shouldShowIsland(timing: Int): Boolean = when (ConfigManager.islandMode()) {
    ISLAND_MODE_OFFICIAL -> timing == ISLAND_SHOW_TIMING_CONNECTED
    ISLAND_MODE_MODULE   -> timing in ConfigManager.islandShowTimings()
    else -> false
}
```

`timing` = `CONNECTED(0) / WEARING(1) / REMOVED(2) / IN_CASE(3)`，由佩戴变化映射（`islandShowTimingForChange`：
`WEARING→1`、`REMOVED→2`、`IN_CASE→3`，其余 `null`）：模块模式可勾选"戴上/摘下/入盒也弹"，官方模式只在连接时弹。
`focus_notification`（默认 `true`）控制的是**常驻通知**（`updatepodsnotification` 分支），关掉时 `cancelNotification`
并 `return`；焦点岛走另一条 `sendstrongtoast` 链路，两者互不阻塞。

## 6. Miuix 组件清单与主题

### 6.1 这五个文件实际用到的组件

| 组件 | 全名 | 观感 / 用法 |
|---|---|---|
| `Text` / `Icon` | `…kmp.basic.*` | 替掉 Material 对应组件；`contentDescription = null` 表示装饰性 |
| `Card` | `…basic.Card` | 大圆角容器；**同卡内多子项自动分组画分隔线**，设置项塞一个 Card 即可 |
| `CardDefaults.defaultColors` | `…basic.CardDefaults` | 自定义卡底色/内容色（横屏游戏模式按钮） |
| `Scaffold` / `TextButton` | `…basic.*` | `containerColor = Color.Transparent` 用于透明弹窗；按钮圆角填充、`weight(1f)` 等分 |
| `BasicComponent` | `…basic.BasicComponent` | 标题+副标题+尾部件一行，`endActions` 放 `ArrowRight` 即"可跳转" |
| `SwitchPreference` | `…preference.SwitchPreference` | `title/summary/checked/onCheckedChange`，自带 Miuix 开关 |
| `OverlayDropdownPreference` | `…preference.OverlayDropdownPreference` | 点开是浮层选择列表，`items/selectedIndex/onSelectedIndexChange` |
| `TabRowWithContour` | `…basic.TabRowWithContour` | 分段选择器：选中格是**带外描边的滑块（contour）**；参数 `tabs/minWidth/maxWidth/height/itemSpacing` |
| `OverlayDialog` | `…overlay.OverlayDialog` | 居中浮层，`title/show/backgroundColor/onDismissRequest/onDismissFinished`（**两回调分开**，finished 才退出 Activity） |
| `TopAppBar` / `MiuixScrollBehavior` | `…basic.*` | 子页大标题 + 折叠滚动 |
| `MiuixIcons.Basic.ArrowRight` / `MiuixIcons.Back` | `…icon.*` | 箭头 / 返回 |
| `SinkFeedback` + `pressable` / `PressFeedbackType.Sink` | `…utils.*` | Miuix 按压"下沉"反馈，替代 ripple |
| `overScrollVertical()` | `…utils.overScrollVertical` | 列表越界回弹，MIUI 手感必需 |
| `textureBlur` / `LayerBackdrop` / `rememberLayerBackdrop` | `…blur.*` | 仅主页底栏模糊，弹窗不用 |

### 6.2 Theme.kt（`ui\Theme.kt`，46 行，必须照抄）

```kotlin
@Composable
fun AppTheme(colorSchemeMode: ColorSchemeMode = ColorSchemeMode.System,
             accentMode: Int = 0, content: @Composable () -> Unit) {
    val actualMode = if (accentMode == 1) when (colorSchemeMode) {
        ColorSchemeMode.Light -> ColorSchemeMode.MonetLight
        ColorSchemeMode.Dark  -> ColorSchemeMode.MonetDark
        else                  -> ColorSchemeMode.MonetSystem
    } else colorSchemeMode
    val controller = remember(actualMode) { ThemeController(actualMode) }
    …
}
```

1. `accentMode == 1` 换 **Monet**（抽壁纸色），否则固定 `Light/Dark/System`。
2. `ThemeController(actualMode)` 必须 `remember(actualMode)`，换主题要重建 controller。
3. **强制 Light/Dark 时**除 `MiuixTheme(controller, content)` 外还要 `CompositionLocalProvider(LocalConfiguration provides
   overrideConfig)`，其中 `overrideConfig = Configuration(currentConfig).apply { uiMode = (uiMode and NIGHT_MASK.inv())
   or nightMode }`；`System/Monet*` 分支不覆写。这是让 `painterResource` 与 `LocalConfiguration` 同时进入正确夜间态的
   唯一办法（`drawable-night-nodpi` 的电池图标依赖它）。
4. 所有页面必须在这层内渲染；`PopupActivity` 也单独套了一次（`PopupActivity.kt:82`）。
5. token 对照：`primary`（ANC 选中图标/文字、TabRow 选中、横屏游戏模式开启底色）、`onBackground`（未选中文字）、
   `surface`（页面背景）、`surfaceContainer`/`onSurfaceContainer`（横屏游戏模式关闭态）、`onSurface`（卡片正文）。
   非 token 的硬编码只有 `0xFF333333`/`0xFFEEEEEE`（电量分隔线）、`0xFF1A1A1A`/`0xFFF7F7F7`（弹窗底色）、
   `Color.Gray`（电量百分比、佩戴图标 tint）。

## 7. 作者防过的 Compose 坑（照搬即可）

1. **双反馈叠层**：`pressable(SinkFeedback)` + `clickable(indication = null)`，`MutableInteractionSource` 用 `remember { }` 且**同一实例**传给两者；用 ripple 会变成两层水波。
2. **组合期写状态**：`PodStatus` 在组合期写 `lastKnownLevel`；只有值真变才重组且下一帧收敛，**不构成死循环**；重建建议 `remember { mutableIntStateOf(100) }` + `rememberSaveable`。
3. **remember 带 key**：`remember(actualMode)`、`remember(context, themeNightMode)`、`remember(id, themeNightMode)`、`remember(path)`；缺 key 会导致换主题/换图不刷新。
4. **LocalContext 只在组合期取**：`val context = LocalContext.current` 后 `remember { context.getSharedPreferences(...) }`；不要在每个 lambda 里重复取，也不要把 context 捕获进闭包去跑 IO。
5. **不要 `runBlocking`**：全仓零命中；等待一律 `LaunchedEffect + delay`（弹窗 500ms 兜底、15s 轮询），注册/注销一律 `DisposableEffect { … onDispose { runCatching { unregister } } }`。
6. **`LocalConfiguration` 与 `context.resources.configuration` 会打架**：主题覆写只改 CompositionLocal 不改 Context 的 `Resources`，所以"随夜间模式换图"必须走 1.8 的 `createConfigurationContext` 双路径。
7. **弹窗旋转重建**：`PopupActivity` 无 `configChanges`，旋转会 `onCreate` 重来、`showDialog` 归 false，靠 500ms 超时兜底重新显示；改 `560dp/240dp` 尺寸时要知道这个重建代价。
8. **乐观更新 vs 广播回声**：先改本地 state 再发广播，设备回推的 `ACTION_*_CHANGED` 会覆盖同名 state；两端必须共用**同一套映射表**（`1..8 ↔ NoiseControlMode`），否则开关会闪回。
9. **早退**：`PodDetailPage` 横屏分支末尾 `return`；`WearStatusIcon` 用 `if (x == null) return@Box`；Composable 里早退合法，但必须在所有 `remember`/`LaunchedEffect` 之后，否则 key 数量随分支变。
10. **文本对齐不测量**：`padEnd(3, '\u3000')` 用全角空格凑宽度，避免 `SubcomposeLayout`；换成拉丁标签（`L/R`）时要改固定 `Modifier.width`。
11. **`AnimatedContent` 而非 `if`**：详情/设备列表切换保留转场（`EarphonesTabPage.kt:55`），包 `Modifier.fillMaxSize()`。
12. **动画都带 `label`**：`Crossfade` / `animateColorAsState` 给 `label = "anc_icon"` / `"anc_text_color"`，缺省会告警。

## 8. 检查清单：把 Oppo 换成 FIIL 需要改哪几处

> FIIL 五档：**关 / 降噪 / 监听 / 开放 / 风中**。无"自适应"，无 SMART/LIGHT/MEDIUM/DEEP 强度分层，
> 无"通透人声增强"独立子模式（若 FIIL 有等价子项再单独加）。

| # | 位置 | 现状（Oppo） | 改成（FIIL） |
|---|---|---|---|
| 1 | `pods\Packets.kt:127` `enum class NoiseControlMode` | `OFF / NC / NC_SMART / NC_LIGHT / NC_MEDIUM / NC_DEEP / ADAPTIVE / TRANSPARENCY`（8 值） | `OFF / ANC / MONITOR / OPEN / WIND`（5 值）：删 `NC_*` 与 `ADAPTIVE`，新增 `MONITOR`(监听)、`OPEN`(开放)、`WIND`(风中) |
| 2 | `Packets.kt:139` `isNoiseCancellation()` | 覆盖 NC + 4 强度值 | 收窄为 `this == ANC`（无强度分层后只剩一处使用） |
| 3 | `AncSwitch.kt:70-113` 一级按钮 Row | 3~4 个 `AncButton`，各 `weight(1f)`，图标盒 60dp | **5 个**等宽：360dp 屏减卡内 24dp ≈ 336dp ÷ 5 = 67dp/格，60dp 图标 + 14sp 文字会顶格 → `boxSize 52dp`、选中 52dp / 未选中 42dp、文字 12sp，或整行走 `compact = true` |
| 4 | `AncSwitch.kt:75-112` 图标资源 | `ic_openanc_*`、`ic_adaptive_*`、`ic_transparent_*`、`ic_closeanc_*` 各 on/off 共 8 张 | 换 10 张：`ic_anc_*`、`ic_monitor_*`、`ic_open_*`、`ic_wind_*`、`ic_off_*`（灰阶描边 → 实心彩色） |
| 5 | `AncSwitch.kt:84-94` 自适应按钮 | `if (adaptiveModeEnabled) { AncButton(ADAPTIVE) }` | **整段删除**，并删 `adaptiveModeEnabled` 参数（`PopupActivity.kt:326/339`、`PodDetailPage.kt:69/244` 传参一并删） |
| 6 | `AncSwitch.kt:115-150` 强度子行 | `isNoiseCancellation()` 时出 `AncStrengthTabRow`（智能/轻度/中度/深度 + 72% 辅助高亮） | 无强度分层 → **整块删除**（连 `AncStrengthTabRow`、`smartAncLevel`、`assistHighlightedIndex`）；若 FIIL 有分档，改为各档子参数并复用同一 `TabRowWithContour` |
| 7 | `AncSwitch.kt:152-168` 通透子行 | `TRANSPARENCY` 且回调非空时出「通透 / 人声增强」 | 无等价子模式 → 删；若"开放"有子项 → 条件改 `ancStatus == OPEN`，标签改「开放 / 环境音」 |
| 8 | `AncSwitch.kt:59-64` 尺寸表 | 一级 60/40dp、子行 45/38dp、间距 9/4dp | 5 格重算：`boxSize 52/36dp`、`iconSize 52/42` 与 `42/32`、标签 12/11sp；子行参数保留备用 |
| 9 | `PopupActivity.kt:183-196 / 260-278` 状态码映射 | `1=关 2=降噪 3=通透 4=自适应 5=智能 6=轻度 7=中度 8=深度` | 重定义 `1=关 2=降噪 3=监听 4=开放 5=风中`（**广播契约变了，收发两端同步改**） |
| 10 | `MiBluetoothToastHook.kt:302-308` 循环切换顺序 | `[2,4,3,1]`（有自适应）或 `[2,3,1]`，且 `localAncMode in 5..8` 归并为 2 | 改 `[2,3,4,5,1]`（降噪→监听→开放→风中→关），删 `5..8` 归并分支 |
| 11 | `PopupActivity.kt:315-341` 横/竖屏 body | 竖屏三卡 + 更多·完成；横屏左 0.60 右 0.40 | 结构照搬；仅当 5 格在 `560dp` 横屏下过宽时强制 `compact = true`（现横屏已这么传） |
| 12 | `PodDetailPage.kt:236-248` 第二张卡 | `AncSwitch(adaptiveModeEnabled = …, transparencyVocalEnhancement = …)` | 去掉这两个实参；battery/wear/ancMode/onAncModeChange/compact 缺省不动 |
| 13 | `PodStatus.kt` 三列 | 左 / 右 / 耳机盒 + 两条 0.5dp 竖线 | 若 FIIL 不报盒子电量：`case == null` 时**隐藏第三列与第二条竖线**（两列各 `weight(1f)`），不留空列，标签补齐随"耳机盒"一起去掉 |
| 14 | `res\values\strings.xml` + `values-zh-rCN` | 降噪/自适应/通透/关闭；智能/轻度/中度/深度；左/右/耳机盒 | 新增 `mode_anc/mode_monitor/mode_open/mode_wind/mode_off`；删 `adaptive_title`、`noise_cancellation_smart`/`light`/`medium`/`deep`、`transparency_*`（后台/设置页引用一并清理） |
| 15 | `FocusIslandUtil.kt` 全文 | 左右耳两条 `imageTextInfo` + `shareData "L: x%  R: y%"` | **结构无需改**，只换 `ticker`/`shareData.title`/通知 `contentTitle` 的品牌串与左右图标位图；盒子电量本就不参与 |
| 16 | `pods\DeviceCapabilities.kt:5` | `adaptiveSupported` 等 | 删 `adaptiveSupported`；`gameModeSupported / spatialAudioSupported / dualDeviceSupported` 按 FIIL 实际能力保留；`detectDeviceCapabilities` 与 `DeviceModelRegistry` 换 FIIL 的 productId/型号表 |
| 17 | `Theme.kt` / `AppTheme` | Light/Dark + Monet + 覆写 `LocalConfiguration` | **完全不动**；只确认 `drawable-night-nodpi` 里新电池图有夜间变体 |
| 18 | 回归验证 | — | 5 档任意切换不闪回、断连不显示 0%、横屏 5 格不截断、弹窗 500ms 兜底与 15s 轮询仍在、焦点岛 4s 自动收回 |
