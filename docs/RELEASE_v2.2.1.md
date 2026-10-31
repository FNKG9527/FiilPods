# FiilPods v2.2.1 发布说明

> 基线：`2aa474f` (v2.1.0) + `d4eb3cf`（README）。
> 本版做八件事：修用户提出的 5 个实机 Bug、保证「伪装耳机型号」端到端可用、
> 修掉「连接/断开反复来回跳」的自激回路（第九节）、
> 从 FIIL+ 逆向资源里取全套耳机产品图，主页按连接型号显示对应图标（第十节）、
> 修掉融合中心切模式后图标仍卡在旧值的问题（第十一节：通知投递时机）、
> 针对 MiLink 18.2 的「策略模式」重构补齐 ProfileContext 层 hook（第十二节）、
> 修掉切模式时图标「闪一下旧值再闪回来」的问题（第十三节：待确认窗口）、
> 重修 Bug 2 焦点通知 —— 旧的两条 hook 路径在 HyperOS 4 上全部失效（第十四节）、
> 并**删掉 OPPO 的 LE Audio 设置页**（第十五节：只有 UI 壳、零 hook 实现）。
> 不重写架构。断连回路用的是「记账 + 闸门 + 递增冷却」的最小刹车，
> **不是** 2.2.0 那一套被否掉的熔断器（见 9.5 与 9.8）。

| 项 | 值 |
| --- | --- |
| 应用名 | `FiilPods 2.2 Fix`（`res/values/strings.xml:2`、`res/values-zh-rCN/strings.xml:3`） |
| versionCode / versionName | 5 / `2.2.1`（`app/build.gradle.kts:30-31`） |
| 改动面 | 25 files changed, +2144 / −655（另有 4 个新增源文件、31 个新增图片资源、3 个删除的源文件） |
| 新增文件 | `pods/FiilCodec.kt`（422 行）、`pods/PodImages.kt`（105 行）、`utils/NotifPermission.kt`（75 行）、`app/src/test/java/moe/chenxy/fiilpods/pods/PodImagesTest.kt`（108 行） |
| 删除文件 | `config/LeAudioConfig.kt`、`ui/pages/LeAudioSettingsPage.kt`、`ui/dialogs/LeAudioDevicesDialog.kt`（共 −362 行，见第十五节） |
| 新增资源 | `res/drawable-nodpi/` 下 31 个 webp（30 张官方产品图 + `fiil_unknow` 兜底），合计 498,288 B |
| 删除资源 | `strings.xml` / `values-zh-rCN/strings.xml` 各 42 条 `le_audio_*` 字符串（共 84 条） |
| 构建 | `clean` + `testDebugUnitTest` + `assembleDebug` + `assembleRelease` → BUILD SUCCESSFUL in 4m 33s, EXIT=0 |
| 单测 | suites=4, tests=57, failures=0, errors=0, skipped=0 |
| 产物 | `dist/FiilPods-v2.2.1-debug.apk`(35,670,731 B)、`dist/FiilPods-v2.2.1-release.apk`(4,543,336 B) |
| release SHA256 | `BA45F8140C37CC4406BAC0D713F619B28B58AF2E35AA12C7B4533E77FE6D2FAF` |
| debug SHA256 | `D7EF9DFF19E3AA77EA1A7C779B5BF8326E2DF2FA1DE6E7F721E605455CEEC3E1` |
| 上一版（v2.2.0）release SHA256 | `BA6E4FDB75A4111F816498D053E45C09A2FC322ECC08835E6A0560952EC5477D` |

> 构建方式说明：**必须用 `clean` 后重建的产物交付**。此前增量构建出的 release 包
> 虽然源码 mtime 全部早于 APK、内容一致，但 SHA256 与 clean 重建结果不同
> （`797BEE53…` → `C9BBE47F…` → `413CB435…` → `608345B2…` → `9F10F075…` → `86078BFF…` → `F513DCC6…`），
> 说明增量构建存在缓存差异，不能作为交付依据。

---

## 一、Bug 1：耳机控制页看不到 LDAC 开关

### 排查结论

两层原因，缺一不可：

1. **UI 层：参数没传下去。** `podControlItems()` 是 `LazyListScope` 的私有扩展函数，
   它有两个调用点（横屏 / 竖屏各一处）。形参 `ldacState: Int = -1` 有默认值，
   `if (ldacState >= 0) { SwitchPreference(...LDAC...) }` 是渲染门槛。
   两处调用点都没有传 `ldacState` / `onLdacChange` → 永远走默认值 `-1` → **整块控件不渲染**。
   这就是「找不到入口」的直接原因，与权限、编解码器能力都无关。
2. **状态源：第一次进页面拿不到。** `FiilCodec.publishState()` 只在系统
   `CODEC_CONFIG_CHANGED` 回调里被顺带触发。用户首次打开耳机详情页时状态没变过，
   模块不主动回读，UI 就收不到 `ACTION_PODS_LDAC_CHANGED`，`ldacState` 停在 `-1`。
   因此还要在 `ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS` 分支里主动回读一次。

### 改动文件

| 文件 | 改动 |
| --- | --- |
| `ui/pages/PodDetailPage.kt` | 新增 `ldacState: Int = -1` / `onLdacChange: (Boolean) -> Unit = {}` 形参（`PodDetailPage` 与 `podControlItems` 各一份）；两处调用点补实参；新增 `if (ldacState >= 0)` 渲染块；文件末尾新增 4 个字面量常量 |
| `ui/pages/EarphonesTabPage.kt` | 同上两个形参 + 透传 |
| `ui/MainTabs.kt` | 横屏/竖屏两个分支各加两个形参并透传 |
| `ui/MainUI.kt` | `ldacState` 状态、收 `ACTION_PODS_LDAC_CHANGED`、注册 filter、`setLdacEnabled()` 广播 `ACTION_LDAC_SET` |
| `utils/miuiStrongToast/data/OppoPodsAction.kt` | 新增 `ACTION_LDAC_SET` / `ACTION_PODS_LDAC_CHANGED` / `EXTRA_LDAC_ENABLED` / `EXTRA_LDAC_SUPPORTED` / `EXTRA_LDAC_KNOWN` |
| `pods/FiilCodec.kt`（新增） | `object FiilCodec`：`query()` / `setLdac()` / `openCodecSettings()` / `publishState()` |
| `pods/FiilRfcommController.kt` | 补回 `connectedDevice(): BluetoothDevice?`（**必须校验 `isConnected`**） |
| `hook/BluetoothProcessHook.kt` | filter 加 `ACTION_LDAC_SET`；UI_INIT/REFRESH 分支主动 `publishState`；新增 `ACTION_LDAC_SET` 处理分支 |

### Hook 点（无 Xposed hook，全部是对系统蓝牙进程的反射）

```
com.android.bluetooth.a2dp.A2dpService#getCodecStatus(BluetoothDevice)
com.android.bluetooth.a2dp.A2dpService#setCodecConfigPreference(BluetoothDevice, BluetoothCodecConfig)
com.android.bluetooth.a2dp.A2dpService#setUserCodecStatus(BluetoothDevice, String, int)
静态字段 com.android.bluetooth.a2dp.A2dpService.sA2dpService
静态字段 com.android.bluetooth.btservice.AdapterService.sAdapterService → getA2dpService(): Optional
```

### 逐字常量为什么不用 `R.string`

`app/build.gradle.kts` 的 release 开了 `isShrinkResources = true`。只被单处引用的字符串
会被 R8 连同资源 ID 一起剥掉，而 dex 里字段引用还在 → 运行时 `Resources$NotFoundException`。
LDAC 的 4 条文案量极小，直接内联：

```kotlin
internal const val LDAC_TITLE: String = "LDAC 编解码器"
internal const val LDAC_SUMMARY: String = "启用高解析蓝牙音频（需耳机与手机同时支持）"
internal const val LDAC_UNSUPPORTED: String = "当前耳机不支持 LDAC"
internal const val LDAC_ENABLED: String = "已启用"
```

### `connectedDevice()` 必须校验 `isConnected`

`deviceAddress` 只在连上时赋值，但 `disconnect()` / `cleanupSocket()` **不清空它**
（要保留「上次连的是谁」供重连用）。断开后若直接 `getRemoteDevice(旧地址)` 去查
`getCodecStatus`，会拿到 null → `ldacState` 又是 `-1` → 开关复隐。

```kotlin
fun connectedDevice(): BluetoothDevice? {
    if (!isConnected) return null
    val addr = deviceAddress ?: return null
    return runCatching {
        appContext?.getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?.adapter?.getRemoteDevice(addr)
    }.getOrNull()
}
```

---

## 二、Bug 2：焦点通知开关无作用、通知栏没有耳机控制卡片

### 排查结论

四层原因：

1. **权限缺失**：`AndroidManifest.xml` 没有 `POST_NOTIFICATIONS`，代码里也没做运行时申请。
   Android 13+ 未授权时 `NotificationManager.notify()` 直接被系统丢弃。
2. **渠道不对**：模块自己建了私有渠道 `fiilpods_battery_v2`，`IMPORTANCE_LOW`。
   HyperOS 认领「蓝牙耳机卡片」靠的是渠道 id 必须是 `BTHeadset<MAC>`，
   而且必须 `IMPORTANCE_DEFAULT` 才进「重要」区，否则被折叠成静默通知。
3. **缺少焦点通知契约字段**：只调了 `setContentTitle/Text`，没有 `miui.focus.*` extras
   （`FocusNotification.buildV3`），SystemUI 不会把它渲染成耳机卡片。
4. **没有按用户发布**：用的是 `nm.notify(id, n)`，原生用的是
   `notifyAsUser(tag, id, n, UserHandle.ALL)`。多用户/工作资料下前者会落到当前用户，
   卡片不出现。

### 改动文件

| 文件 | 改动 |
| --- | --- |
| `AndroidManifest.xml` | 声明 `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` / `VIBRATE` |
| `utils/NotifPermission.kt`（新增） | `granted(ctx)` / `ensure(activity)`（`registerForActivityResult` 申请） |
| `MainActivity.kt` / `PopupActivity.kt` | `super.onCreate()` 后调 `NotifPermission.ensure(this)` |
| `hook/MiBluetoothToastHook.kt` | `updateNotification()` 整体重写；新增 `buildFocusExtras()`；`cancelNotification()` 改用 `cancelAsUser` |

### Hook 点

```
com.android.bluetooth.ble.app.MiuiBluetoothNotification
  ← 2 参构造器（findConstructorByParamCount(cls.name, 2)），读字段 mContext 作为 Hook 上下文
com.android.bluetooth.btservice.AdapterService#onCreate()
  ← 兜底上下文来源（AdapterService.this）
```

### 通知构造要点

```kotlin
val channelId = "$CHANNEL_PREFIX$address"   // CHANNEL_PREFIX = "BTHeadset" → "BTHeadsetB0:F1:.."
NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
    setSound(null, null); enableVibration(false); setShowBadge(false)
    setAllowBubbles(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
}
```

`FocusNotification.buildV3 { ... }` 组四段：`iconTextInfo`（图标 + 标题 + 正文）、
`island.bigIslandArea`（左图右文）、`textButton`（降噪循环 + 断开两个按钮）、
再对 `miui.focus.param` 做 **AOD 注入**（`param_v2.aodTitle` / `aodPic`），
最后 `nm.notifyAsUser(channelId, 10003, builder.build(), SystemApisUtils.getUserAllUserHandle())`。

> ⚠️ `buildFocusExtras(...)` 的形参**不能**叫 `content`。Kotlin 里外层形参会遮蔽
> `iconTextInfo {}` / `textInfo {}` receiver 的 `content` 属性，写成
> `content = content` 会被解析成给 `val` 赋新值 → 编译报 `'val' cannot be reassigned`。
> 本版统一命名为 `contentText`。

---

## 三、Bug 3：超级岛电量显示不出来

### 排查结论

1. **根本没有调用点**：`FocusIslandUtil.showBatteryIsland()` 在 2.1.0 里**没有任何地方调用**。
   用户在设置页把超级岛来源选成「模块」后，通知栏什么都不会出现。
2. **发布方式不对**：用了 `nm.notify(id, n)`，需要 `notifyAsUser(..., UserHandle.ALL)`；
   cancel 同理要用 `cancelAsUser`。
3. **单耳时显示 `-%`**：`textInfo { title = leftText; content = "%" }` 是无条件挂载的，
   未连接一侧 `leftText` 是 `"-"`，岛上就出 `-%`。应改成按 `isConnected` 条件挂载。
4. **盒电量为死分支**：`if (contentParts.isEmpty()) { ... 盒电量 ... }` 恒不成立
   （前面只要有任一侧连接就必然加过元素）。条件是「有一侧没连」而非「列表为空」。

### 改动文件

| 文件 | 改动 |
| --- | --- |
| `hook/MiBluetoothToastHook.kt` | `onBatteryChanged()` 按 `islandMode` 三路互斥分流；新增 `showModuleFocusIsland()` |
| `utils/FocusIslandUtil.kt` | `notifyAsUser` / `cancelAsUser`；`textInfo` 条件挂载；盒电量条件修正；删掉多余的 `?: return false` |

### 两条岛通道必须互斥

```kotlin
when (islandMode) {
    ConfigManager.ISLAND_MODE_MODULE   -> showModuleFocusIsland(...)  // 模块自制岛
    ConfigManager.ISLAND_MODE_OFFICIAL -> showBatteryToast(...)       // 官方强提示
    else -> Unit                                                      // 只留常驻通知
}
updateNotification(...)   // 通知卡片三条通道共用
```

两条岛同时下发会互相顶掉，表现为「岛上电量一闪就空」。

### Hook 点

同 Bug 2（`MiuiBluetoothNotification` 2 参构造器取 `mContext`）。

---

## 四、Bug 4：融合设备中心切降噪/通透后图标不刷新

### 排查结论

三层，每一层都能单独让功能失效：

1. **`setAncStateBlock` 返回值语义搞错。** 它返回的是**操作结果码**
   （100 成功 / 201 请求失败 / 202 超时 / 206 目标不匹配 / 207 不支持降噪 /
   208 非法档位 / 209~212 佩戴状态不允许），不是 ANC 状态值。
   旧实现 `param.setResult(miLinkAncState())` 回 0/1/2，调用方
   `ProfileImpl.updateHeadsetMode`（`:2979-3050`）用 `if (ancState == 100)` 判成功 →
   一律判失败 → **判定设置失败、不刷新 UI**（要重进页面才显示）。
2. **`openAnc` / `closeAnc` / `openTransparent` 返回值也搞错。** 它们返回的是
   **成功标志**（1 = 已下发），不是 ANC 状态。旧代码 `closeAnc → 0`、
   `openTransparent → 2`，而 `setAncStateBlock` 内部是
   `if (iCloseAnc != 1) return 201 /* request failed */` → 整次切换必失败。
3. **`getWearStatus` 返回值格式非法。** 它是**单值字符串**（`WearStatus`：
   `AllWorn="1"` / `LeftWorn="2"` / `RightWorn="3"` / `NotWorn="0"` / `Error="-1"`）。
   旧实现回 `"0,0"`，而 `AncBatteryController.isSupportOpAnc`（`:510-577`）拿它做
   `wearStatus.hashCode()` 的 switch，只认 `48/49/50/51` 与 `1444`；
   `"0,0"` 的 hashCode 是 `48256` → fall through → `return 212`（佩戴状态不允许）
   → 每一次 ANC 切换都直接失败。

### 改动文件

`hook/milink/MiLinkServiceHook.kt`

```kotlin
private const val OP_OK = 100          // MIUI 操作类 API 成功码，全系统统一
private const val OP_DISPATCHED = 1    // MxBluetoothManager 侧「命令已下发」
private const val WEAR_ALL_WORN = "1"  // WearStatus.AllWorn
```

三处替换：

```kotlin
hookDeviceResult(className, "getWearStatus") { WEAR_ALL_WORN }          // 原 "0,0"
hookAncCommand(className, "openAnc", FiilMate.Maf.NOISE_CANCEL, OP_DISPATCHED)   // 原 1
hookAncCommand(className, "closeAnc", FiilMate.Maf.OFF, OP_DISPATCHED)           // 原 0
hookAncCommand(className, "openTransparent", FiilMate.Maf.OPEN, OP_DISPATCHED)   // 原 2
// hookAncStateBlock 末尾
param.setResult(OP_OK)                                                  // 原 miLinkAncState()
```

### Hook 点

```
com.miui.headset.runtime.AncBatteryController#setAncStateBlock(BluetoothDevice, int)
com.miui.headset.runtime.AncBatteryController#getWearStatus(BluetoothDevice)
com.miui.headset.runtime.AncBatteryController#getAncState(BluetoothDevice)
com.miui.headset.runtime.AncBatteryController#getBatteryLevel(BluetoothDevice)
com.miui.headset.runtime.AncBatteryController#getDeviceId(BluetoothDevice)
com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager#openAnc(BluetoothDevice)
com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager#closeAnc(BluetoothDevice)
com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager#openTransparent(BluetoothDevice)
```

---

## 五、Bug 5：融合中心「更多设置」进原生页后按钮无效

### 排查结论

两层：

1. **Hook 根本没装上（签名错误）。** AIDL 生成的是
   `setCommonCommand(int, String, BluetoothDevice)` 与 `changeAncMode(int, BluetoothDevice)`，
   参数是**原始 int**。Kotlin 里 `Int::class.java` 等于 `java.lang.Integer`（装箱类），
   `getDeclaredMethod` 找不到 → 抛 `NoSuchMethodException`，被 `hookProxyBefore` 的
   `runCatching` 吞掉，只在 logcat 留一句 `skipped`。于是真正下发降噪的那个方法
   **从来没被 hook 过**。
2. **返回值策略错，撞上宿主门控。** `setCommonCommand` 的 code `102` 问的是
   「佩戴状态是否已同步」。`01010607` 命中 `HeadsetIDConstants.isK73Headset`
   （原生代码里就在这个集合），走门控 B：

```java
// MiuiHeadsetFragment.java:3197-3205
} else if (HeadsetIDConstants.isK73Headset(mDeviceId)
        || HeadsetIDConstants.isK75Headset(mDeviceId)) {
    if (z && RECORD_SYNCED.equals(commonCommand)) {        // "0"
        ToastUtil.show(miheadset_anc_indicate); return;    // ← 弹「无法设置」直接返回
    } else if (z && !RECORD_UNSYNCED.equals(commonCommand)) {  // != "1"
        ToastUtil.show(miheadset_single_ear_indicate); return;
    }
}
```

旧实现固定返回 `"0"` = `RECORD_SYNCED` → **必然走进第一个分支弹提示并 return**，
永远到不了 `:3216 mService.changeAncMode(i, mDevice)`。这就是「点了没反应」。
**只有 `"1"`（`RECORD_UNSYNCED`）能穿门。**

3. **附带修**：`HeadsetIDConstants.checkSupport` / `isTWS01Headset` / `isK77sHeadset`
   的真实签名都是 `static boolean xxx(String)`，旧代码用 `hookStringStatic` 把入参
   String 回写进去，属于类型错配。`01010607` 本身在原生白名单里所以没炸，
   用户一旦把伪装型号改成白名单外的 id，`checkStartMiuiHeadset` 就会 `return false`
   → 原生耳机设置页打不开。

### 改动文件

`hook/SettingsHook.kt`

```kotlin
private const val COMMAND_WEAR_UNSYNCED = "1"   // RECORD_UNSYNCED

// 两处签名修正
hookProxyBefore(CLS_SERVICE_PROXY, "setCommonCommand",
    Int::class.javaPrimitiveType!!, String::class.java, BluetoothDevice::class.java) { ... }
hookProxyBefore(CLS_SERVICE_PROXY, "changeAncMode",
    Int::class.javaPrimitiveType!!, BluetoothDevice::class.java) { ... }

// 返回值映射
private fun commonCommandReply(code: Int): String = when (code) {
    102 -> COMMAND_WEAR_UNSYNCED   // 佩戴状态：唯一能穿过 updateAncMode/updateAncLevel 门控的值
    106 -> "000011101110"          // 设备型号串，宿主强制 length()==12
    110 -> "1"                     // 版本号，会被 Integer.parseInt
    112 -> COMMAND_WEAR_UNSYNCED   // 重力感应
    115 -> "true"                  // 通知详情开关
    117 -> "true"                  // 静默升级
    109 -> ""                      // 会被 updateAtUiInfo 当 4 段 '|' 载荷解析，给空串短路
    else -> COMMAND_WEAR_UNSYNCED
}
```

`hookStringStatic(className, methodName, result: (String) -> Any)`
→ `hookBooleanStatic(className, methodName, result: Boolean)`，三处调用改成
`checkSupport → true`、`isTWS01Headset → true`、`isK77sHeadset → false`，
其余逻辑不变（仍按 `fakeDeviceId()` 前缀过滤，不污染真实小米耳机）。

### Hook 点

```
com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy
  #setCommonCommand(int, String, BluetoothDevice)   TRANSACTION=14
  #changeAncMode(int, BluetoothDevice)              TRANSACTION=9
  #changeAncLevel(String, BluetoothDevice)          TRANSACTION=10
  #checkSupport(BluetoothDevice)  #getDeviceInfo(String)  #isMiTWS(String)  #checkIsMiTWS(String)
com.android.settings.bluetooth.HeadsetIDConstants
  #checkSupport(String)  #isTWS01Headset(String)  #isK77sHeadset(String)
com.android.settings.bluetooth.MiuiHeadsetFragment
  #updateAncMode(int, boolean)   #updateAncLevel(String, boolean)
```

---

## 六、「伪装耳机型号」保持可用（本版重点之一）

2.1.0 本来就有全套可编辑链路（`ConfigManager.fakeDeviceId` / `fakeSupport` /
`updateFakeDeviceId` / `SettingsPage` 的 TextField / 两条 strings）——**本版原样保留，
撤销所有「删掉这个入口」的计划**。缺的只是「注入侧改成读运行时配置」这一环，
否则用户改了等于没改：

| 位置 | 改动 |
| --- | --- |
| `hook/SettingsHook.kt:157-162` | `fakeDeviceId()` / `fakeSupport()` 从读常量改为读 `ConfigManager`（`runCatching` 兜底常量） |
| `hook/milink/MiLinkServiceHook.kt:255` | `fakeDeviceId()` 同样改读 `ConfigManager` |
| `hook/HookContext.kt:81` | prefs 监听白名单补 `PREF_KEY_FAKE_DEVICE_ID`（原只有 3 个 key） |
| `hook/HookContext.kt:101` | 新增 `refreshConfigFromPrefs()` |
| `BluetoothProcessHook` / `SettingsHook` / `MiLinkServiceHook` | `FiilAction.ACTION_CONFIG_CHANGED` 分支调 `refreshConfigFromPrefs()`（原来只打日志） |
| `ui/MainUI.kt:1227-1228` | `broadcastConfigChanged` 双发两个 action 常量 |

> 为什么双发：模块内同时存在 `OppoPodsAction.ACTION_CONFIG_CHANGED`
> （`chen.action.oppopods.config_changed`）与 `FiilAction.ACTION_CONFIG_CHANGED`
> （`moe.chenxy.fiilpods.action.CONFIG_CHANGED`）两套常量，而三个 hook 注册的都是
> **FiilAction 版**，UI 发的却是 OppoPodsAction 版 —— 三个进程里的
> `refreshConfigFromPrefs()` 全在死分支里。双发一次即可兼容。

---

## 七、代码审查后的加固（第三轮）

对上面五 Bug 的改动做了一轮逐行审查，修掉 6 处「代码写完了、但实际不会按预期工作」的缺陷。
这些都不是新功能，而是原实现里的真实漏洞。

### 7.1 `setDefaults(-1)` —— 传 `-1` 等于「全部默认提醒都开」

`hook/MiBluetoothToastHook.kt:660`

```kotlin
// 改前
// -1 = 不发出任何默认提醒（声音/震动/灯），耳机卡片靠焦点通知动画提示。
.setDefaults(-1)

// 改后
.setDefaults(0)
```

`Notification.DEFAULT_ALL` 的定义就是 `~0`，也就是 **-1**。原注释把语义写反了：传 -1 是
把「声音 | 震动 | 灯」三个默认位**全部打开**。当前靠渠道的 `setSound(null, null)` +
`enableVibration(false)` 压住了实际声音，所以现象上没暴露；但只要渠道重要性被调高
（系统设置里用户手动改、或未来代码改 `IMPORTANCE_DEFAULT` → `HIGH`），这张常驻卡片
就会每次电量刷新都响一声。传 `0` 才是「不申请任何默认提醒」。

### 7.2 全断开时刷出「未连接」常驻卡片

`hook/MiBluetoothToastHook.kt:238-251`

`FiilRfcommController.publishState()` 的电量段是**无条件**带齐三个 `EXTRA_*_CONNECTED`
的（`FiilRfcommController.kt:1939-1944`），所以 `BatterySnapshot.fromExtras()` 在耳机
全断开时依然返回非 null 快照；`updateNotification()` 再把它渲染成 `content="未连接"`。
结果是用户把耳机收回盒里之后，通知栏那张卡片永远显示「未连接」——既没用又占位。

修法：`onBatteryChanged` 里 `if (!snapshot.hasAny()) { cancelNotification(ctx); return }`，
与「收到断开广播」走同一条清理路径。

### 7.3 LDAC 离线误点后开关永久停在错误位置

`hook/BluetoothProcessHook.kt:479-497`

UI 的 `setLdacEnabled()` 是**乐观更新**（点下去立刻把开关拨到目标位置），真值完全依赖
蓝牙进程回推 `ACTION_PODS_LDAC_CHANGED`。而 `ACTION_LDAC_SET` 分支在
`device == null` 时只打一行日志就返回，**不回读状态** —— 一次离线误点就会让开关永久
停在用户点的那一侧：用户以为 LDAC 开了，实际耳机根本没跑 LDAC，而且再进页面也不会被纠正。

修法：无设备分支补 `FiilCodec.publishState(hostContext, null, null)`。`publishState(null)`
会发出 `known=false`，UI 收到后把 `ldacState` 归到 `-1`，开关整个藏起来 —— 这才是诚实表现。

### 7.4 从蓝牙进程 `startActivity` 会被后台启动限制静默拦掉

`pods/FiilCodec.kt` + `hook/BluetoothProcessHook.kt:508-515` + `ui/MainUI.kt:360-366`

`openCodecSettings()` 唯一调用点是 `BluetoothProcessHook`，跑在 `com.android.bluetooth`
进程里。那是**后台进程**，Android 10+ 对后台进程启动 Activity 有硬性限制：`startActivity`
不抛异常，但 Activity 根本不会显示 —— 用户点开 LDAC 失败后什么反应都没有。

修法：把「跳设置页」拆成两半。

| 角色 | 方法 | 说明 |
| --- | --- | --- |
| 蓝牙进程（后台） | `FiilCodec.requestOpenCodecSettings(ctx)` | 只发 `ACTION_OPEN_CODEC_SETTINGS` 广播，不碰 Activity |
| 模块 App（前台） | `FiilCodec.openCodecSettings(ctx)` | 收到广播后真正 `startActivity` |

新增常量 `OppoPodsAction.ACTION_OPEN_CODEC_SETTINGS = "chen.action.oppopods.open_codec_settings"`
（`utils/miuiStrongToast/data/OppoPodsAction.kt:68`），并在 `MainUI` 的 receiver filter 里注册。

### 7.5 `setLdac` 的日志恒打印 `codecType=null`

`pods/FiilCodec.kt:245`

```kotlin
// 改前：newConfig 是 BluetoothCodecConfig，currentCodecType 内部找的是 getCodecConfig()
Log.i(TAG, "setLdac($enable) dispatched, codecType=${currentCodecType(rebuildStatus(newConfig))}")
// 改后
Log.i(TAG, "setLdac($enable) dispatched, codecType=${codecTypeOf(newConfig)}")
```

`currentCodecType()` 期望入参是 `BluetoothCodecStatus`（它会调 `getCodecConfig()`），
传 `BluetoothCodecConfig` 必然反射失败 → 日志永远是 `codecType=null`，排查时看不到
真实切到了哪个编码器。同时删掉只为这行日志存在的 `rebuildStatus(config) = config` 假包装。

### 7.6 `openCodecSettings` 候选列表里有个非 Activity

`pods/FiilCodec.kt:294-298`

原候选含 `com.android.settings.bluetooth.BluetoothCodecPreferenceController` ——
那是个 `PreferenceController`（设置页内部的控制器类），**不是 Activity**，
`setClassName` 必然抛 `ActivityNotFoundException`。它永远不会成功，只会让循环多跑一轮，
并且误导后来读代码的人以为存在第二条可用入口。已删除。

### 7.7 超级岛撤岛竞态（`FocusIslandUtil`）

`utils/FocusIslandUtil.kt`

旧实现每次展示都 `postDelayed(4s) { cancel() }`，而电量更新很密（30s 心跳 + 每次
MAF/佩戴/电量变化）。t=0 展示 → t=1s 又更新展示一次 → t=4s 第一个定时器把**第二次**
的岛撤掉了。表现为「岛上电量一闪就空」。

修法：加 `showToken` 单调自增 + `@Volatile`，延迟撤销的回调只在「自己仍是最新一次」时
才真正 cancel。

### 7.8 复核后判定为「不是缺陷」的两项

| 审查建议 | 结论 |
| --- | --- |
| `RECEIVER_EXPORTED` 应改 `RECEIVER_NOT_EXPORTED` | **反向错误**。模块 UI 与被 hook 的蓝牙/设置进程是不同 UID，`NOT_EXPORTED` 会把模块与注入进程之间的全部广播挡掉，模块直接失效。高危入口（`ACTION_LDAC_SET` / `ACTION_RFCOMM_DEBUG_SEND`）需要的是运行时令牌或 debug-only 注册，属独立设计任务，本轮不做。 |
| `FocusIslandUtil` 的 `textInfo{title=数字; content="%"}` 形态错误 | **不是缺陷**。与 `refs/Oppopods` 的参考实现逐行一致（`git diff` 只有品牌串差异），是已验证可用的形态。 |

### 7.9 明确未做（用户约束）

`BluetoothProcessHook.kt:358-362` 的「UI_INIT 未连接就补一次 connect」与 `MainUI` 的
`LaunchedEffect(selectedTab, hookConnected.value)` 属**断连风暴**范畴。用户明确要求
「不带入 2.2.0 的断连自愈/节流/熔断改造」，故本轮不动。release APK 已核验这四个
特征串均**不存在**：`connect throttled` / `connectBlocking: already connected` /
`MAX_SILENT_ATTEMPTS` / `silentAttemptStreak`。

---

## 八、第四轮复核：classloader 传错让 Bug 1 完全不生效（P0）

第 7 节的六项加固完成后，又做了一轮「不看结论、只读代码」的复核，发现一处
**会让 Bug 1 在真机上彻底失效**的缺陷。它比第 7 节任何一项都严重 —— 前面那些
修的是「行为不理想」，这一处修的是「功能根本没跑起来」。

### 8.1 根因：`BluetoothDevice::class.java.classLoader` 是 `null`

`pods/FiilCodec.kt` 里读 / 写 LDAC 都要先拿到系统蓝牙进程里的 `A2dpService` 实例，
靠反射两个类：

- `com.android.bluetooth.btservice.AdapterService`（静态字段 `sAdapterService` → `getA2dpService()`）
- `com.android.bluetooth.a2dp.A2dpService`（静态字段 `sA2dpService`）

这两个类都在 **`com.android.bluetooth` 这个 APK** 里，只有该 APK 的
`PathClassLoader` 能看到它们。而原实现两条路线都写：

```kotlin
val cl = classLoader ?: ClassLoader.getSystemClassLoader()
```

调用方传进来的 `classLoader` 又是：

```kotlin
// BluetoothProcessHook.kt，改前
FiilCodec.publishState(ctx, dev, dev?.javaClass?.classLoader)   // UI_INIT 分支
val cl = device.javaClass.classLoader                            // ACTION_LDAC_SET 分支
```

`BluetoothDevice` 是 **framework 类**，由 boot classloader 加载，
`BluetoothDevice.class.getClassLoader()` 返回 **`null`**。于是：

```
Class.forName("com.android.bluetooth.a2dp.A2dpService", false, null)
  → 只用 bootstrap classpath 查找 → ClassNotFoundException
  → a2dpServiceOrNull() 返回 null
  → query() 返回 null
  → publishState() 发出 EXTRA_LDAC_KNOWN=false
  → MainUI.kt 把 ldacState 落成 -1
  → PodDetailPage 的 if (ldacState >= 0) 不成立 → LDAC 开关**永远不渲染**
```

也就是说：Bug 1 的 UI 代码、广播链路、反射实现全都对，唯独 classloader 传错，
导致**用户在真机上依然看不到 LDAC 开关**。这解释了为什么这类「代码看着没问题、
装上却没用」的缺陷最难查。

### 8.2 反证：同一个文件里早就有正确用法

`hook/BluetoothProcessHook.kt` 自己的 `enumerateA2dpConnected()`（`:133-144`）与
`a2dpServiceInstance()`（`:147-166`）就在反射同一个 `A2dpService`，用的是 hook
自己的 `classLoader`（= `HookContext` 从 `param.defaultClassLoader` 拿到的、
com.android.bluetooth 这个 APK 的 `PathClassLoader`），**实测可用**。
也就是说正确的 classloader 一直就在手边，只是新写的 LDAC 代码没用它。

### 8.3 三处修法

**① `hook/BluetoothProcessHook.kt` UI_INIT 分支**（`:350` 起）：第三个参数由
`dev?.javaClass?.classLoader` 改为 `classLoader`。

**② `hook/BluetoothProcessHook.kt` ACTION_LDAC_SET 分支**（`:496` 起）：
`val cl = device.javaClass.classLoader` 改为 `val cl = classLoader`。

**③ `pods/FiilCodec.kt` 增加 classloader 兜底**，让上层不必再关心 classloader 从哪来：

```kotlin
private fun candidateClassLoaders(preferred: ClassLoader?): List<ClassLoader> {
    val out = ArrayList<ClassLoader>(4)
    preferred?.let { out.add(it) }
    runCatching { Thread.currentThread().contextClassLoader }.getOrNull()?.let { out.add(it) }
    runCatching { FiilCodec::class.java.classLoader }.getOrNull()?.let { out.add(it) }
    runCatching { BluetoothDevice::class.java.classLoader }.getOrNull()?.let { out.add(it) }
    return out.distinct()
}

private fun loadClassOrNull(name: String, preferred: ClassLoader?): Pair<Class<*>, ClassLoader>? {
    for (cl in candidateClassLoaders(preferred)) {
        val cls = runCatching { Class.forName(name, false, cl) }.getOrNull()
        if (cls != null) return cls to cl
    }
    return null
}
```

`a2dpServiceOrNull()` 两条路线都改成先 `loadClassOrNull(...)?.second`，加载不到就
直接返回 null（不再用 `ClassLoader.getSystemClassLoader()` 这种必然失败的兜底）。

### 8.4 断开时未重置 `ldacState`

`ui/MainUI.kt` 的 `ACTION_PODS_DISCONNECTED` 分支补上 `ldacState.value = -1`。

改前：耳机断开后开关会带着上一台耳机的状态留在界面上，用户点它 →
`setLdacEnabled()` 先乐观更新成 1/0，再向蓝牙进程发 `ACTION_LDAC_SET`，
而蓝牙进程此时 `connectedDevice()` 返回 null，只能回一个 known=false ——
界面闪一下再消失，观感是「点了没用」。改后断开态直接不渲染这一项。

### 8.5 复核后确认「不是缺陷」的三项

| 审查建议 | 结论 |
| --- | --- |
| `MiBluetoothToastHook.kt:555` 的 `"未连接"` 分支已不可达 | 是 `:247-251` `hasAny()` 提前 return 之后的**防御性残留**，无害；保留可防未来改动重新引入空卡片。 |
| `PodDetailPage.kt` 的 `LDAC_UNSUPPORTED` 常量零引用 | 死常量，不产生行为，留着不占体积（release 已 shrink）。 |
| `FiilCodec.describe()` 用了 deprecated 的 `BluetoothAdapter.getDefaultAdapter()` | 仅诊断用，不在任何热路径上，唯一编译警告即来自此处。 |

---

## 九、连接/断开反复来回跳：自激回路的修复

### 9.1 问题

用户观察到：耳机有时候会在「已连接」和「已断开」之间**一直来回跳**，停不下来。
复核确认这不是观察错觉，代码里确实存在**两条无界回路**，而且都是 2.1.0 基线自带的，
不是本版 5 个 Bug 的修复引入的。

### 9.2 回路 A：静默探测 ↔ 自动重连（周期 8.5 秒）

`pods/FiilRfcommController.kt` 的 `scheduleSilenceProbe(address)`：

```kotlin
Thread.sleep(SILENCE_TIMEOUT_MS)          // 3500ms
val rx = lastRxAt
if (rx != 0L) return@Thread               // 收到过数据就不管
disconnect("silent-channel")              // 否则判定「静默」，断开
```

关键在于 `"silent-channel"` **不在** `MANUAL_DISCONNECT_REASONS` 里
（那个集合只有 `user-request` / `stop` / `a2dp-disconnected` / `reconnect` / `multipoint-switch`），
于是 `disconnect()` 末尾这段必然执行：

```kotlin
if (wasConnected && addr != null && !manual) scheduleReconnect(addr, RECONNECT_ATTEMPTS)
```

→ 断开后 5 秒必定重连 → 重连成功后再次挂探测线程 → 3.5 秒后再判定静默 → 再断开。
**周期 8.5 秒，没有任何终止条件。**

原本有一个「自愈」动作：重连时 `preferCustomNext = !wasCustom` 翻转 SPP 通道，
一旦真收到数据 `readLoop` 会清掉这个标记。所以**正常情况**下最多多闪一次就稳定了。
但下面三种情况它会永远转下去：

- 两条 SPP 通道都被对端静默（耳机固件异常 / 通道被占用）；
- 耳机已被别的手机（多点）抢走，本机 SPP 能建链但对端不回数据；
- 自定义 UUID 服务在这台耳机上不可用，翻转后仍拿不到数据。

### 9.3 回路 B：`readLoop` 退出 ↔ 自动重连（周期 5 秒）

`readLoop` 里 `if (socket === sock) disconnect("read-loop-exit")`，
`"read-loop-exit"` 与 `"input-failed"`、`"write-failed"` 同样不在 `MANUAL_DISCONNECT_REASONS` 里，
所以同样触发补连。当对端「连上就踢」（典型的双设备抢占）时：
`connect → 被踢 → 5 秒 → connect → 被踢`，**周期 5 秒、无界、而且没有任何自愈动作**，
比回路 A 更危险 —— A 至少还会换通道，B 只是原样重试。

### 9.4 为什么原本停不下来

```
git grep "failStreak|consecutiveFail|giveUp|backoff|throttle"   → 0 命中
```

`scheduleReconnect(address, attempts)` 的 `attempts` 参数**形同虚设**：每次
`disconnect()` 都**新开**一个 `attempts = 2 或 3` 的重连线程，而
`reconnectThread?.isAlive` 只防「同时有两个重连线程」，不防「一轮 3 次用完，下一轮重新开始」。
且 `connectBlocking` 一旦成功就 `return@Thread`，计数直接归零 —— 于是失败永远不会累积。

还有一个叠加点火源：`ui/MainUI.kt` 的 `LaunchedEffect(Unit)` 每 30 秒发一次
`ACTION_PODS_UI_INIT`，`hook/BluetoothProcessHook.kt` 收到后如果当前未连接就补连一次。
它本身不造成 UI 跳变（有 `if (!isConnected)` 守卫），但在断开窗口内会额外点一次火。

### 9.5 修法：最小刹车（不是 2.2.0 那套熔断器）

用户明确说过不要 2.2.0 那一套断连自愈改造（`connect throttled` /
`MAX_SILENT_ATTEMPTS` / `silentAttemptStreak` 三个串在本版 APK 里**全为 False**）。
所以这里只在**既有重连路径上加记账与闸门**，不新增状态机、不改协议层：

#### 新增常量（`pods/FiilRfcommController.kt`）

```kotlin
private const val MAX_CONSECUTIVE_FAILURES = 3
private const val RECONNECT_COOLDOWN_MS    = 60_000L
private const val MAX_COOLDOWN_MS          = 15 * 60_000L
private const val STABLE_CONNECTION_MS     = 8_000L
private const val MAX_RECONNECT_DELAY_MS   = 30_000L
```

#### 新增状态字段

```kotlin
@Volatile private var everReceivedData: Boolean = false   // 本轮连接是否真收到过字节
@Volatile private var lastConnectAt: Long = 0L            // 本轮连接建立时刻
@Volatile private var consecutiveFailures: Int = 0        // 连续「不健康」断开次数
@Volatile private var cooldownUntil: Long = 0L            // 冷却截止时刻，0 = 不在冷却
@Volatile private var cooldownLevel: Int = 0              // 冷却等级（用于递增窗口）
@Volatile private var userRequestedDisconnect: Boolean = false
```

`everReceivedData` 是布尔印记，和 `lastRxAt` 的时间戳判据是两件事：
`lastRxAt` 会被历史值污染（上一轮收到过数据就一直非 0），
布尔印记在每次连接成功时显式清零，所以能准确回答「**这一轮**有没有收到过数据」。

#### 健康判定 `noteDisconnectHealth(reason, wasConnected, manual)`

在 `disconnect()` 里、**紧挨着 `scheduleReconnect` 之前**调用
（必须在它之前，因为 `scheduleReconnect` 要看 `consecutiveFailures` 决定还试不试）：

```kotlin
if (manual || !wasConnected) return          // 用户主动断开不记账（否则手动断也会被计入失败）
val heldMs = System.currentTimeMillis() - lastConnectAt
val stable = everReceivedData && heldMs >= STABLE_CONNECTION_MS
if (stable) {
    consecutiveFailures = 0; cooldownLevel = 0; cooldownUntil = 0L
    return
}
consecutiveFailures++
if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return
val window = (RECONNECT_COOLDOWN_MS shl cooldownLevel.coerceAtMost(10))
    .coerceAtMost(MAX_COOLDOWN_MS)
cooldownUntil = System.currentTimeMillis() + window
cooldownLevel++
publishLinkFailed()
```

「连上超过 8 秒且真的收到过数据」才算健康，否则记一次失败。
这样既能容忍偶发的一次掉线，也能识别「连上就被踢」这种抖一下的假连接。

#### 闸门一：`connect(device)` 入口

```kotlin
if (isCoolingDown()) { Log.i(TAG, "connect ignored: in cooldown ..."); return }
if (userRequestedDisconnect) { Log.i(TAG, "connect ignored: user requested disconnect"); return }
```

#### 闸门二：`scheduleReconnect` 入口 + 循环内

```kotlin
if (isCoolingDown()) return
// 循环内：
val backoff = RECONNECT_DELAY_MS * consecutiveFailures.coerceAtMost(6)
val wait = backoff.coerceIn(RECONNECT_DELAY_MS, MAX_RECONNECT_DELAY_MS)
Thread.sleep(wait)
if (isConnected) return@Thread
if (isCoolingDown()) { Log.i(TAG, "reconnect loop exits: cooling down ..."); return@Thread }
```

**这一步是必需的**：`scheduleReconnect` 内部直接调 `connectBlocking()`，
绕过了 `connect()` 上的闸门。只加闸门一是不够的。

#### 闸门三：`scheduleMultipointReconnect` 入口 + 循环内

`multipoint` 是「畅连切换」专用的独立重连路径，同样直调 `connectBlocking()`，
所以同样要挡，否则它会成为绕过刹车的后门。

#### 闸门四：`scheduleSilenceProbe` 断开之后

```kotlin
disconnect("silent-channel")
if (isCoolingDown()) {
    Log.i(TAG, "silence probe exits: cooling down after repeated unhealthy connects")
    return@Thread
}
```

**这条最容易漏**：静默探测走的是「断开 → 换通道 → 重连」，
如果只挡 `scheduleReconnect`，它会以另一种形式变成 SPP→GATT→SPP 的无界翻转。

#### 健康印记写入点（3 处）

| 位置 | 写入 |
| --- | --- |
| `connectBlocking` SPP 成功分支（`isConnected = true` 之后） | `everReceivedData = false; lastConnectAt = System.currentTimeMillis()` |
| `onGattReady`（`isConnected = true` 之后） | 同上 |
| `readLoop`（`lastRxAt = ...` 之后） | `everReceivedData = true` |

#### 冷却递增而不是定长

第一轮失败 3 次 → 冷却 60 秒 → 再失败 3 次 → 120 秒 → 240 秒 … 封顶 15 分钟。

`isCoolingDown()` 到期时**只清 `cooldownUntil`，不清 `consecutiveFailures` / `cooldownLevel`**——
这是故意的：冷却结束只是「允许再试一次」，不代表问题解决了。
所以如果耳机确实连不上，下一轮很快又会攒满 3 次失败，
窗口直接翻倍，而不是回到 60 秒从头再来。

（如果到期时把计数也清零，就只是把回路从「每 5 秒一次」拉长到「每 60 秒一次」，
永不终止 —— 这一点是本轮自查时发现的，第一版实现就是错的。）

#### 用户手动操作穿透一切限流

```kotlin
fun connectUserInitiated(device: BluetoothDevice) {
    clearFailureStreak()               // 清三个状态字段并打日志
    userRequestedDisconnect = false
    connect(device)
}
```

`hook/BluetoothProcessHook.kt` 里用户可见的两个连接入口
（`ACTION_CONNECT_POD_REQUEST` 广播、`"connect"` 命令）都改走它；
`connectIfIdle`、A2DP `STATE_CONNECTED`、UI_INIT 心跳这三条**自动**路径继续走 `connect()`，
这样刹车对自动路径生效、对用户操作不生效。

#### 用户主动断开后不再被自动连回来

`disconnect()` 的 manual 分支里：

```kotlin
if (reason == "user-request" || reason == "stop") userRequestedDisconnect = true
```

之后所有自动路径（含 UI_INIT 30 秒心跳补连、静默探测、重连循环）都会被 `connect()` 入口挡掉。
解除条件只有两个：用户手动点连接，或者 A2DP 层重新连上
（`onExternalConnectEvent()` 收到外部连接事件时清标记并打日志）。
这样「我点断开，它自己又连回来」的观感问题一并解决。

### 9.6 失败时给用户明确反馈

新增 `OppoCompatBridge.publishLinkFailed(ctx)`：

```kotlin
fun publishLinkFailed(ctx: Context) {
    if (lastState == "error") return
    lastState = "error"
    lastConnected = false
    send(ctx, OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
        putExtra("state", "error")
    }
}
```

`ui/MainUI.kt:226-233` 已有针对 `state == "error"` 的 `LaunchedEffect`：
弹「连接失败」对话框并打开设备选择页。所以进入冷却时用户会看到一次明确的提示，
而不是继续盯着连接状态自己跳。

### 9.7 修复后的行为对照

| 场景 | 修复前 | 修复后 |
| --- | --- | --- |
| 耳机固件异常、SPP 一直静默 | 每 8.5 秒 连/断 一次，无限 | 3 次不健康连接后冷却 60s，再失败翻倍到 120s/240s…封顶 15min，并提示「连接失败」 |
| 耳机被另一台手机占着、连上就被踢 | 每 5 秒 连/断 一次，无限 | 同上，且不会一直刷日志 |
| 耳机正常、偶发一次掉线 | 自己连回来 | 自己连回来（`stable` 判据不记账） |
| 用户点「断开」 | 被 UI_INIT 心跳 / 静默探测连回来 | 不再自动连回，直到用户手动连或 A2DP 重新连上 |
| 用户手动点「连接」 | 立即尝试 | 立即尝试，且清空全部失败/冷却状态 |

### 9.8 本轮 APK 特征串核验

刹车相关 17 条**全部存在**：
`connect ignored: in cooldown after`、`connect ignored: user requested disconnect`、
`unhealthy disconnect (`、`giving up auto-reconnect for`、`cooldown #`、
`reconnect loop exits: cooling down`、`multipoint reconnect exits: cooling down`、
`silence probe exits: cooling down`、
`user-requested disconnect cleared by external connect event`、
`failure streak cleared by user action`、`link was stable (`、
`cooldownLevel`、`MAX_COOLDOWN_MS`、`userRequestedDisconnect`、
`onExternalConnectEvent`、`publishLinkFailed`、`connectUserInitiated`。

被用户否掉的 2.2.0 那套**全部不存在**（4 条全 False）：
`connect throttled`、`MAX_SILENT_ATTEMPTS`、`silentAttemptStreak`、
`connectBlocking: already connected`。

### 9.9 排查指引

若仍观察到来回跳，按顺序查：

1. `logcat -s FiilPods-Rfcomm | grep -E "unhealthy disconnect|giving up auto-reconnect"`
   —— 有输出说明刹车在工作，问题在耳机侧（供电/固件/被抢占）。
2. `grep "connect ignored: in cooldown"` —— 有输出说明自动重连被正确拦住了，
   此时界面若还显示「已连接」是别的路径在改状态，查 `publishState()` 的调用方。
3. `grep "user-requested disconnect cleared"` —— 出现这条说明 A2DP 层自己把耳机连回来了，
   这是系统行为，不在本模块控制范围内。

---

## 十、按连接型号显示耳机产品图（本轮新增功能）

需求原话：「从 fiilapp 逆向的文件里寻找全部耳机的图片，适配到 fiilpods 模块里面，
链接什么蓝牙耳机就在主页显示什么蓝牙耳机的图标」。

### 10.1 图片来源与型号对应关系

图片全部取自 FIIL+ 3.5.2（`fiil.apk`，94,576,645 B）的
`res/drawable/fiil_earphones_*.png` —— apktool 产物在
`D:\DeepSeek\fiil_analysis\apktool_out\res\drawable\`。

**型号对应关系不靠猜**，来自同 APK 内的两个权威来源：

1. `assets/header_list_zh.json` 的 `headsetList[].headsetModel`（形如 `F046`）
   → 去掉前两位就是 earType。这不是巧合，官方 `EarPhoneModel.getEarID()`
   （`smali_classes2/com/fengeek/f002/u/a.smali`）就是
   `Integer.parseInt(getHeadsetModel().substring(2))`，与 `FiilModels` 的编号体系同源。
2. `com.fengeek.fragment.f`（即 `ImageResMapper`）的
   `Map<String,Integer>` —— 31 条 `imgUrl → drawable id` 的映射，
   未命中返回 `0x7f0801e2` = `fiil_unknow`（官方兜底图，实体文件 6,203 B）。

共 30 个实体图片文件（其中 `t1_lite.jpg` / `t1_x.jpg` 是 jpg，无 alpha）。

### 10.2 已落地的映射（`pods/PodImages.kt`）

28 条 earType → `R.drawable.fiil_*`，兜底 `fiil_unknow`：

| 系列 | earType → 图片 |
| --- | --- |
| CC | 51→cc3、39→cc_pro2、37→cc_nano、28→cc_pro、30→cc2、21→cc |
| GS | 43→**gs1**、45→gs_lite、48→gs_links |
| Key | 49→**key_pro2_1**、46→key_max、41→key_pro、38→key |
| Atom | 47→atom |
| CG | 33→cg_pro、31→cg |
| T | 35→t2_pro、27→t1_pro、29→t1_lite、26→t1_xs、22→**t1_x**、17→t1 |
| Diva | 8→diva、5→diva_pro、247→diva2、250→diva2_pro |
| FIIL | 2→wireless |
| Aviator | 42→fiil_speaker_aviator |

**四处「不直观」的对应，都有据可查**：

- 43（GS）用 `gs1` 不用 `gs`：`header_list_zh.json` 里 F043 的 `imgUrl`
  就是 `fiil_earphones_gs1`，`fiil_earphones_gs` 是 GS 系列的**系列头图**。
- 49（Key Pro2）用 `key_pro2_1`（800×800 精修版）不用 `key_pro2`，
  同理 `key_pro2` 是系列头图。
- 22（T1 X）：实体文件 `t1_x.jpg` 存在，但官方 `ImageResMapper` **漏登记**了这个键，
  所以 FIIL+ 自己的列表里 T1 X 永远显示兜底图。本模块补上——这是对官方行为的**有意修正**。
- 247/250（Diva2 / Diva2 Pro）：`header_list_zh.json` 里与 Diva / Diva Pro
  共用 `F008` / `F005`（同 key 重复出现两次），只按 `headsetModel` 查会把四台耳机
  压成两张图。本模块用独立的 earType 区分，四张图互不相同。

官方只给 26 个型号出图，`FiilModels` 覆盖 43 个 earType，
所以 15 个老型号（Driifter / Carat / Bestie / Runner / Belt / VOX 等）
没有官方产品图，一律落 `fiil_unknow` —— 与官方 App 的行为一致。

### 10.3 体积控制：PNG → WebP

30 张原图合计 **2,452,085 B（2.34 MB）**，直接塞进 `res/` 会让 APK 明显变胖。
用 Pillow 转 WebP（`quality=92`、`method=6`，超过 800 边长的先 LANCZOS 缩到 800），
输出到 `res/drawable-nodpi/`：

| | 字节 |
| --- | --- |
| 转换前（30 张） | 2,452,085 |
| 转换后（31 张，含兜底） | **498,288** |
| 压缩比 | **20.3%**（省 1,953,797 B） |

转换脚本留在 `_dev/convert_pod_images.py`（不参与打包）。
release APK 里实际只有 29 个 webp：`diva` / `diva_pro` / `diva2_pro`
三张官方源图字节完全相同，AAPT2 自动去重；映射表仍然指向三个独立资源 id，
AAPT2 会把它们解析到同一份数据，行为不受影响。

### 10.4 UI 接线

三处来源、三个落点：

1. **earType 从哪来**：蓝牙进程在 `OppoCompatBridge.publishConnection()` 里
   把 `FiilStatus.earType` 塞进 `product_id` extra（`OppoCompatBridge.kt:172-198`）。
   `MainUI.kt` 收到 `ACTION_PODS_CONNECTED` 后解析成 Int 存进
   `connectedEarType`（`mutableIntStateOf(-1)`）；
   解析失败回退用 `FiilModels.earTypeOfName(deviceName)` 按名字推断；
   再不行落 `-1`。
2. **断开时清零**：`ACTION_PODS_CONNECTION_STATE_CHANGED` 的 `disconnected` 分支
   与 `ACTION_PODS_DISCONNECTED` 分支都把 `connectedEarType` 打回 `-1`，
   否则卡片会带着上一台耳机的图留在主页。
3. **主页**：`HomePage.kt` 新增 `ConnectedEarphoneCard` ——
   左侧 88.dp 盒子里 `ContentScale.Fit` 渲染型号图（官方图尺寸不统一，
   580×420 / 800×800 / 240×240 都有，必须 Fit 否则 800×800 那张会把卡片撑爆），
   右侧型号名（`FiilModels.nameOf(earType)`，20sp SemiBold）+ 副标题
   （设备名与型号名不同时显示型号名，否则显示「已连接」）。
   点击切到耳机页。未连接时不渲染这张卡片。
4. **详情页**：`PodDetailPage` 新增 `earType` 形参，
   `rememberPodImagePainter(path, earType)` 的回落顺序改为
   「用户自定义产品图 → 型号产品图 → `img_box` 通用图」。
   `earType <= 0` 时**仍然**用 `img_box` 而不是 `fiil_unknow`：
   `fiil_unknow` 的语义是「官方也不认识这型号」，用在详情页会让人误以为识别失败。
5. **透传链**：`MainUI → MainTabsScaffold → ModuleTabPage → HomePage`
   与 `MainTabsScaffold → EarphonesTabShell → EarphonesTabPage → PodDetailPage`
   全部补齐 `connectedEarType` / `earType` 形参（都带默认值，不破坏既有调用）。

### 10.5 回归保护

新增 `app/src/test/java/moe/chenxy/fiilpods/pods/PodImagesTest.kt`（8 项）：

- 未知 earType 落 `fiil_unknow`，`drawableOrNull` 返回 null；
- `drawableOf` 对 -5..400 任何输入都**不返回 0**（`painterResource(0)` 会直接抛异常崩界面）；
- 映射表里每个 earType 都必须是 `FiilModels` 认识的型号（防两边编号漂移）；
- 映射表里任何一项都不许指向兜底图（等于没配却让 UI 以为有图）；
- 逐条钉住 Key 系 / GS 系 / CC3 / Atom 这几个最容易被人「顺手改成更像的那个」的对应；
- 钉住 T1 X（22）必须有图（这是对官方的有意修正，被谁「对齐官方」删掉会红）;
- Diva 四张图必须互不相同（防有人按 headsetModel 合并）；
- `drawableOfName` 对已知名 / 未知名 / null 都给出正确结果且不返回 0。

单测从 49 项增至 **57 项**。

## 十一、融合中心切模式后图标仍卡旧值：通知投递时机的修复

> 用户在 v2.2.0 第一版上实测：「在融合设备中心里面切换模式的图标还是会卡住在原来的
> 图标上面，需要退出这个页面再进去才能显示到对应的位置或者二次点击，点击切换功能是
> 正常的」。即**功能本身已经生效**（第四节修的返回码起作用了），但**图标不刷新**。

### 11.1 现象拆解

「功能生效但界面不更新」说明两件事是分开的：

| 环节 | 状态 |
| --- | --- |
| 切换请求真的发到耳机了 | ✅ 已生效（第四节：`setAncStateBlock` 返回 `100`） |
| 融合中心收到「模式已变」通知并重画卡片 | ❌ 没收到 / 收到太早 |

所以问题不在协议层，而在 **MiLink 的 `headsetPropertyChangeListener` 通知链路**。

### 11.2 官方是怎么发的（逆向实证）

反编译 `_dev/milink_out/sources/com/miui/headset/runtime/`：

```java
// DiscoveryKt.java:14
private static final HandlerEx SYSTEM_BROADCAST_HANDLER =
        new HandlerThreadEx("system_broadcast").getHandler();

// AncBatteryController.java:597-604
private final void notifyPropertyChanged(final BluetoothDevice dev, final int type, long delay) {
    DiscoveryKt.getSYSTEM_BROADCAST_HANDLER().postDelayed(new Runnable() { ... }, delay);
}
```

结论：**官方永远是「postDelayed 到独立的 `system_broadcast` 线程」异步投递，从不同步直调。**

`updateType` 取值（`DiscoveryImpl.onHeadsetPropertyChanged`）：`4`=电量变化、`8`=**模式变化**。

而这条通知在 `setAncStateBlock` 里的位置极其关键 —— 它写在**函数体最后一行**：

```java
// AncBatteryController.java:968-1085
public final int setAncStateBlock(BluetoothDevice dev, int mode) {
    ... isSupportOpAnc / model 判空 / isSameAddress 校验 ...
    ... openAnc / closeAnc / openTransparent ...
    ... opAncSync 等 5000ms ...
    notifyPropertyChanged(ancBatteryModel.getBluetoothDevice(), 8, OP_UPDATE_DELAY); // :1083, delay=25
    return 100;                                                                       // :1084
}
```

### 11.3 原来为什么卡住（两个叠加成因）

**成因一：官方那条通知被我们整段跳过了。**

`hookAncStateBlock()` 用的是 `hookBefore` + `param.setResult(OP_OK)`，函数体根本不执行 ——
`:1083` 那条 `notifyPropertyChanged(dev, 8, 25ms)` 自然不会发。

**成因二：自己补的通知时序不对。**

旧实现 `notifyPropertyChanged` 是**同步** `callMethod(listener, "invoke", ...)`：

```kotlin
// 旧实现（错误）
callMethod(listener, "invoke", device, updateType)   // 就在调用方栈内立刻执行
```

它在 `ProfileContext.setAncState` 的 binder 线程栈内投递，此时：
- 面板的 `assembleHeadsetInfo()` 还没轮到执行（`deviceUpdateLock` 被占）；
- 或者执行了但读到的是 `ancState` 尚未落定的中间态。

于是卡片重绘拿到的还是旧值 → 表现为「图标卡在原位」。
用户「退出页面再进来」之所以能好，是因为重进走的是另一条路径：`DiscoveryImpl.assembleHeadsetInfo()`
在锁内**重新读一遍** `ProfileContext.getAncState(dev)`，那时字段已经稳定了。

### 11.4 顺带查出的一处无效写入

`AncBatteryModel.java` 全文只有**两个**可变字段：

```java
private volatile int ancState;                    // getAncState() / setAncState(int)
private volatile List<Integer> battery;           // getBattery() / setBattery(List)
private final BluetoothDevice bluetoothDevice;    // 构造注入，不可变
```

而旧 `syncAncModel` 写了三个：

```kotlin
// 旧实现（前两行无效）
setObjectField(model, "bluetoothAncState", state)   // 字段不存在 → 静默跳过
setObjectField(model, "miLinkAncState", state)      // 字段不存在 → 静默跳过
setObjectField(model, "ancState", state)            // ✅ 唯一真起作用的
```

`HookContext.setObjectField` 沿超类链找不到 `getDeclaredField` 时**静默返回**，
所以这两行从写下那天起就没生效过 —— 不是 bug 的成因，但是噪声，一并删掉。

### 11.5 修法（`hook/milink/MiLinkServiceHook.kt`）

**(1) 新增常量**（对齐官方 `OP_UPDATE_DELAY = 25`）

```kotlin
private const val CLS_DISCOVERY_KT = "com.miui.headset.runtime.DiscoveryKt"
private const val PROPERTY_CHANGED_DELAY_MS = 25L      // 官方 OP_UPDATE_DELAY
private const val PROPERTY_CHANGED_RETRY_MS = 350L     // 兜底重发
```

**(2) 新增字段 + 惰性解析官方 Handler**

```kotlin
@Volatile private var systemBroadcastHandler: android.os.Handler? = null
@Volatile private var systemBroadcastHandlerResolved = false

private fun obtainSystemBroadcastHandler(): android.os.Handler? {
    if (!systemBroadcastHandlerResolved) {
        systemBroadcastHandlerResolved = true
        systemBroadcastHandler = runCatching {
            callStaticMethod(CLS_DISCOVERY_KT, "getSYSTEM_BROADCAST_HANDLER")
        }.getOrNull() as? android.os.Handler
        if (systemBroadcastHandler == null) {
            logW("system_broadcast handler unavailable, falling back to main looper")
        }
    }
    return systemBroadcastHandler ?: mainHandler
}
```

`HandlerEx extends Handler`（`HandlerEx.java:14`），所以直接 `postDelayed` 合法，
不需要再反射 `postDelayed` 方法。拿不到时（宿主版本漂移 / 类被裁）回落本进程主线程
Handler —— 异步性质一致，刷新照样生效。

**(3) 重写 `notifyPropertyChanged`：改异步投递 + 兜底重发**

```kotlin
fun notifyPropertyChanged(controller: Any?, device: BluetoothDevice?, updateType: Int) {
    if (controller == null || device == null) return
    val handler = obtainSystemBroadcastHandler() ?: return
    val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }
        .getOrNull() ?: return
    val fire = Runnable { runCatching { callMethod(listener, "invoke", device, updateType) } }
    // 主投递：对齐官方的 25ms，落在 system_broadcast 线程，等调用方栈退出后再刷。
    handler.postDelayed(fire, PROPERTY_CHANGED_DELAY_MS)
    // 兜底重发：部分 ROM 上 ProfileImpl 会在之后再用自己的缓存覆盖一次，
    // 25ms 那次刷完又被盖回旧值，于是表现为「还是卡住」。
    if (updateType == UPDATE_ANC || updateType == UPDATE_BATTERY) {
        handler.postDelayed(fire, PROPERTY_CHANGED_RETRY_MS)
    }
}
```

**(4) `syncAncModel` 只写真实字段**（删掉两个不存在的，也删掉与调用方重复的通知）

```kotlin
private fun syncAncModel(owner: Any?, device: BluetoothDevice?, maf: Int) {
    if (owner == null || device == null) return
    val state = miLinkAncStateFrom(maf)
    val models = buildList {
        runCatching { getObjectField(owner, "ancBatteryModel") }.getOrNull()?.let { add(it) }
        runCatching { getObjectField(lastAncBatteryController, "ancBatteryModel") }
            .getOrNull()?.let { add(it) }
    }
    models.distinctBy { it.javaClass.name }.forEach { model ->
        if (!isTargetAncBatteryModel(model)) return@forEach
        setObjectField(model, "ancState", state)   // ← 唯一存在的字段
    }
}
```

**(5) `hookAncStateBlock` / `hookAncCommand` 补 `saveState()`**

复核时发现的**第二个真实缺陷**：两条 hook 都只改内存字段 `ancMode`，从不落盘。

```kotlin
ancMode = mafFromMiLink(mode)
saveState()          // ← 本轮新增，必须在 sendAncSelect 之前
sendAncSelect(ancMode)
```

为什么必须写：`loadState()` 在**每个取值 getter 开头**都会被调用
（`miLinkAncState()` / `miLinkBatteryLevels()` / `miLinkSwitchState()` … 共 8 处），
而 `loadState()` 用 `prefs.getInt("anc", ancMode)` **无条件覆盖**内存字段。
所以不落盘的话：融合中心切了通透 → 内存变成 OPEN → 下一次任何 getter 调
`loadState()` 又把旧的 NOISE_CANCEL 读回来 → 表现成「设过通透，过一阵自己变回降噪」。
`saveState()` 写的是全部 12 个字段（`address`/`name`/`anc`/`cinema`/`low_latency`/
`ear_type`/三路电量/三个充电标志），所以它同时也顺手保住了当前设备上下文。

**(6) `hookAncStateBlock` 逻辑不变**，只补了 KDoc 说明「为什么必须自己补通知」：

```kotlin
lastAncBatteryController = param.instance
captureContext(param.instance)
val mode = param.args[1] as? Int ?: return@hookBefore
ancMode = mafFromMiLink(mode)
saveState()                                                   // ← 同上，必须落盘
sendAncSelect(ancMode)
syncAncModel(param.instance, device, ancMode)
notifyPropertyChanged(param.instance, device, UPDATE_ANC)      // 8 = HeadsetModeChanged
notifyPropertyChanged(param.instance, device, UPDATE_BATTERY)  // 4 = 电量
param.setResult(OP_OK)                                        // 100 = success
```

### 11.6 应用改名（用户要求：版本号不变，名字改）

| 位置 | 改动 |
| --- | --- |
| `res/values/strings.xml:2` | `FiilPods` → `FiilPods 2.2 Fix` |
| `res/values-zh-rCN/strings.xml:3` | `FiilPods` → `FiilPods 2.2 Fix` |
| `app/build.gradle.kts:23-24` | **不动**：`versionCode = 4` / `versionName = "2.2.0"` |

`AndroidManifest.xml:25` 与 `:37` 都引用 `@string/app_name`，自动跟随。
`aapt2 dump badging` 实测两个 APK 的 `application-label` 全部为 `FiilPods 2.2 Fix`，
`versionCode='4' versionName='2.2.0'` 保持不变。

未改动的无害项：`strings.xml` 的 `about`（About FiilPods / 关于 FiilPods）、
`themes.xml` 的 `Theme.FiilPods` 样式名 —— 前者是文案不是应用名，后者不影响显示。

### 11.7 本轮核验结果

| 项 | 结果 |
| --- | --- |
| `:app:clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` | **BUILD SUCCESSFUL in 4m 21s, EXIT=0**（95 tasks: 57 executed / 36 from cache / 2 up-to-date） |
| 单测 | suites=4, tests=57, failures=0, errors=0, skipped=0 |
| `application-label` | `FiilPods 2.2 Fix`（release + debug 全语种一致） |
| `versionCode / versionName` | `4` / `2.2.0`（未变） |
| release APK 新特征串 | `system_broadcast handler unavailable`、`getSYSTEM_BROADCAST_HANDLER`、`com.miui.headset.runtime.DiscoveryKt`、`setAncStateBlock skipped`、`milink hooks installed`、`headsetPropertyChangeListener` —— **全 True** |
| 既有能力未回退 | 连接刹车 10 条串、LDAC 4 条串、通知/原生页 5 条串 —— **全 True** |
| 被否的 2.2.0 自愈串 | `connect throttled` / `MAX_SILENT_ATTEMPTS` / `silentAttemptStreak` / `connectBlocking: already connected` —— **全 False** |
| 源码 vs 产物一致性 | `source files newer than apk: 0` |

### 11.8 排查指引（万一仍然卡）

1. `logcat -s MiLinkServiceHook` 看有没有 `system_broadcast handler unavailable` ——
   有的话说明 `DiscoveryKt.getSYSTEM_BROADCAST_HANDLER()` 反射失败，通知落到了主线程，
   时序上应该仍然正确（只是线程不同），但如果这台 ROM 上主线程被别的东西卡住就会延迟。
2. 确认 `com.miui.headset.runtime.DiscoveryKt` 类名没变：
   `adb shell dumpsys package com.milink.service | grep -i discovery` 或直接 `dexdump` 核对。
3. 若「切一次不刷、切两次才刷」，说明 25ms 那次被覆盖、350ms 兜底那次才生效 ——
   可以把 `PROPERTY_CHANGED_RETRY_MS` 调小到 150ms 再试。
4. 若完全不刷新，先确认第八节那个 classloader 问题没复发（`ldac publish skipped` 类日志），
   再看 `notifyPropertyChanged` 是否因为 `headsetPropertyChangeListener` 字段取不到而提前 return。

## 十二、MiLink 18.2 把 ANC 读写改成「策略模式」：补 ProfileContext 层 hook

第十一节改的是**通知的投递时机**，但用户实测仍然卡旧值。本轮换思路：
不再靠猜和靠日志（这台机器上 `logcat` 完全不可用，见 12.6），
改为把手机上真实的 MiLink APK 拉下来做静态逆向，逐行确认数据链。

### 12.1 本机实际运行的 MiLink 版本

| 项 | 值 |
| --- | --- |
| 安装路径 | `/product/app/MiLinkOS4Cn/MiLinkOS4Cn.apk` |
| 体积 | 71,349,759 B |
| 版本 | **18.2.0.12** |
| 逆向方式 | `apktool` 解 `classes*.dex` → 4 个 smali 目录，只读取，未改动手机任何文件 |

> 之前几轮修复依据的是**旧版**逆向（`_dev/milink_out/`）。18.2 是一次大重构，
> 类结构虽然同构，但 `AncBatteryController` 内部逻辑完全换了 —— 这是前几轮一直没修好的根本原因。

### 12.2 18.2 的核心变化：`IHeadsetStateStrategy` 策略模式

`AncBatteryController` 新增了三个字段：

```java
private IHeadsetStateStrategy activeStrategy;
private AirPodsHeadsetStrategy airPodsHeadsetStrategy;
private ThirdPartyHeadsetStrategy thirdPartyHeadsetStrategy;
```

派发逻辑（`AncBatteryController.smali:584-622 findActiveStrategy`）：

```
if (airPodsHeadsetStrategy.isAirpodsDevice(dev)) return airPodsHeadsetStrategy;
if (thirdPartyHeadsetStrategy.isThirdPartDevice(dev)) return thirdPartyHeadsetStrategy;
return null;
```

策略由 `HeadsetStrategyFactory.create(type, ...)` 决定：`type=5/6 → AirPods`、`type=9 → ThirdParty`、其他 → null。

**FIIL 走的就是 `ThirdPartyHeadsetStrategy`，而这个类的两个关键方法是硬编码短路：**

| 方法 | smali 行号 | 方法体 |
| --- | --- | --- |
| `getAncState(BluetoothDevice)` | `ThirdPartyHeadsetStrategy.smali:931` | `const/4 p1, -0x1` → **永远返回 -1** |
| `setAncStateBlock(BluetoothDevice, int)` | `:1450` | `const/16 p1, 0xcd` → **永远返回 205** |

### 12.3 缺陷 A（决定性）：hook 挂错了层

`DiscoveryImpl.assembleHeadsetInfo()`（`DiscoveryImpl.smali:556`）在锁内构造 `HeadsetInfo`：

```smali
:598 invoke-virtual {v2, v7}, ProfileContext;->getAncState(BluetoothDevice)I      # ← mode
:642 invoke-direct/range {v2 .. v13}, HeadsetInfo;-><init>(String;String;String;List;IIIIIII)V
```

也就是说 **`HeadsetInfo.mode` 来自 `ProfileContext.getAncState`**，而 `ProfileContext.getAncState`
（`ProfileContext.smali:1067`）的方法体只有一条路径：

```
if (!isInstalled()) return -1;
return ancBatteryController.getAncState(dev);
```

`AncBatteryController.getAncState`（`:2584`）对 FIIL 的处理是：
deviceId 不在 `Lvg/b;->i()` 小米设备表里 → 落到 `findActiveStrategy(dev)` → `ThirdPartyHeadsetStrategy.getAncState` → **-1**。

**旧实现只 hook 了 `AncBatteryController.getAncState`，从来没有 hook `ProfileContext` 这一层。**
而 `assembleHeadsetInfo` 走的恰恰是 `ProfileContext`。这就是「退出重进才对」的指纹：
重进页面走的是另一条会命中 `HeadsetInfo.getMode()` hook 的路径，所以第二次才显示正确。

同理，`assembleHeadsetInfo` 还调了这三个 `ProfileContext` 方法，也全都没 hook：

| HeadsetInfo 字段 | 18.2 的取值来源 | 旧实现是否 hook |
| --- | --- | --- |
| `mode` | `ProfileContext.getAncState(dev)` | ❌ |
| `findRingState` | `ProfileContext.getFindRingState(dev)` | ❌ |
| `audioEffectState` | `ProfileContext.getAudioSpatialEffectState(dev)` | ❌ |
| `switchState` | `ProfileContext.getSwitchState(address)` | ❌ |

### 12.4 缺陷 B：通知发给了不持有 listener 的对象

`hookAncCommand` 挂在 `MxBluetoothService` / `MxBluetoothManager` 的
`openAnc` / `closeAnc` / `openTransparent` 上，原实现写的是：

```kotlin
notifyPropertyChanged(param.instance, device, UPDATE_ANC)
```

但 `notifyPropertyChanged` 第一步就是反射取 `headsetPropertyChangeListener` 字段，
而**这两个类都没有这个字段** —— `getObjectField` 取不到直接 return，通知等于没发。
真正持有该字段的是 `AncBatteryController`（即 `lastAncBatteryController`）。

### 12.5 缺陷 C：`ancBatteryModel` 在 FIIL 上可能一直为 null

18.2 起 `AncBatteryController.ancBatteryModel` 只在「小米自家设备」分支
（`Lvg/b;->i().get(deviceId) != null`）里被赋值。FIIL 走第三方策略，该字段可能一直是 null。
于是 `syncAncModel` 的两个取法（`owner.ancBatteryModel` + `lastAncBatteryController.ancBatteryModel`）
都拿不到，`ancState` 写不进去。

### 12.6 为什么这一轮不再依赖日志

这台机器（25102RKBEC / Android 17 / HyperOS）上 logcat 被彻底阻断，实测：

```
adb shell "logcat -g"
  main: ring buffer is 64 KiB (62 KiB consumed, 0 B readable)
  system: 1 MiB (256 KiB consumed, 0 B readable)
adb shell "logcat -d | wc -l"            → 0
adb shell "su -c 'logcat -d | wc -l'"    → 0     # root 也不行
```

`ro.debuggable = 0`、`persist.logd.limit = Off`。所以本轮全部结论来自 **smali 静态确证**，
每一条都带行号，可在 `_dev/milink_18_smali/` 下逐行复核。

### 12.7 本轮修法（四处）

全部在 `app/src/main/java/moe/chenxy/fiilpods/hook/milink/MiLinkServiceHook.kt`：

**(1) 补四个 `ProfileContext` 层 hook**（`hookHeadsetRuntimeDisplay()` 内，现 `:286-289`）：

```kotlin
hookDeviceResult(CLS_PROFILE_CONTEXT, "getAncState") { miLinkAncState() }
hookDeviceResult(CLS_PROFILE_CONTEXT, "getFindRingState") { miLinkGameModeState() }
hookDeviceResult(CLS_PROFILE_CONTEXT, "getAudioSpatialEffectState") { miLinkSpatialMode() }
hookAddressResult(CLS_PROFILE_CONTEXT, "getSwitchState") { miLinkSwitchState() }
```

这是本轮最关键的一处：`assembleHeadsetInfo` 的 `mode` 现在直接由我们给出，
不再经过 `findActiveStrategy` → `ThirdPartyHeadsetStrategy` 那条返回 -1 的路。

**(2) `hookAncCommand` 的通知对象修正**（现 `:380-382`）：

```kotlin
notifyPropertyChanged(lastAncBatteryController, device, UPDATE_ANC)
notifyPropertyChanged(param.instance, device, UPDATE_ANC)
```

两个都发：`lastAncBatteryController` 才是真正持有 listener 的对象，
`param.instance` 保留是为了宿主版本漂移时哪个有效都不会漏。

**(3) `syncAncModel` 增加第三个模型来源**（现 `:905` 起）：
在 `buildList` 里补 `getObjectField(lastProfileContext, "ancBatteryModel")`；
拿不到任何 model 时打一条 `logD("syncAncModel: no ancBatteryModel on host, relying on ProfileContext hook")`，
明确告诉排查者「这条链路已由 ProfileContext hook 兜住」。

**(4) 上一轮的异步投递保留**（第十一节）：`obtainSystemBroadcastHandler()` 反射
`DiscoveryKt.getSYSTEM_BROADCAST_HANDLER()`。已在 18.2 上复核：
`AncBatteryController.notifyPropertyChanged`（`:899-913`）**依然**走
`DiscoveryKt.getSYSTEM_BROADCAST_HANDLER().postDelayed(...)`，方案继续成立。

### 12.8 本轮核验结果

| 项 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL in 43s, EXIT=0** |
| `:app:clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` | **BUILD SUCCESSFUL in 5m 13s, EXIT=0**（95 tasks: 58 executed / 35 from cache / 2 up-to-date） |
| 单测 | suites=4, tests=57, failures=0, errors=0, skipped=0 |
| `versionCode / versionName` | `4` / `2.2.0`（未变） |
| release APK 本轮新特征串 | `hookHeadsetRuntimeDisplay`、`ProfileContext`、`getAncState`、`getFindRingState`、`getAudioSpatialEffectState`、`getSwitchState`、`syncAncModel`、`no ancBatteryModel on host, relying on ProfileContext hook`、`system_broadcast handler unavailable`、`getSYSTEM_BROADCAST_HANDLER`、`setAncStateBlock`、`getMode`、`FiilPods 2.2 Fix` —— **全 True** |
| 既有能力未回退 | `伪装耳机型号`、`LDAC 编解码器`、`FiilPods 2.2 Fix`（UTF-8）—— **全 True** |
| 被否的 2.2.0 自愈串 | `connect throttled` / `MAX_SILENT_ATTEMPTS` / `silentAttemptStreak` —— **全 False** |
| 源码 vs 产物一致性 | `source files newer than apk: 0` |

### 12.9 万一仍然卡旧值，按这个顺序查

1. **先确认 hook 真的装上了**：LSPosed 里 `moe.chenxy.fiilpods` 必须启用，
   作用域必须包含 `com.milink.service`（用 root 读 `/data/adb/lspd/config/modules_config.db`
   的 `scope` 表即可核对，不需要日志）。
2. **确认 `ProfileContext` 的类名/方法名没再变**：
   在 `_dev/milink_18_smali/smali_classes3/com/miui/headset/runtime/ProfileContext.smali`
   里搜 `.method public final getAncState(Landroid/bluetooth/BluetoothDevice;)I`。
3. **确认 `ThirdPartyHeadsetStrategy` 还是那两个硬编码**：
   `:931 getAncState` 应为 `const/4 p1, -0x1`，`:1450 setAncStateBlock` 应为 `const/16 p1, 0xcd`。
   如果哪天官方改成真实实现，本模块这几个 hook 就成了冗余，可以删掉。
4. **确认 `assembleHeadsetInfo` 还在调 `ProfileContext.getAncState`**：
   `DiscoveryImpl.smali:556` 起，搜 `ProfileContext;->getAncState`。
5. 如果以上都对还卡，那就是**融合中心 UI 侧**缓存了 `HeadsetDeviceInfo`，
   与 MiLink 无关 —— 此时抓 `com.miui.circulate` 的
   `HeadsetDeviceManager.convertToBluetoothDevice`（`smali_classes2`）看它读的是
   `HeadsetInfo.getMode()` 还是 `HeadsetInfo.mode` 字段（`copy()` 会绕过 getter，见 12.10）。

### 12.10 已知的残余风险（如实记录）

`DiscoveryHost.assembleDeepCopyHeadsetHost(String, HeadsetInfo)`（`DiscoveryHost.smali:497-550`）
里用的是：

```smali
:504 const/16 v13, 0x7ff              # copy$default 的 mask，11 位全 1 → 全部字段复用原值
:532 invoke-static/range {v1 .. v14}, HeadsetInfo;->copy$default(...)
```

`copy()` **复制的是字段值，不经过 getter**。所以如果融合中心有某条路径读的是
`HeadsetHost.headsetInfo.mode` 字段（而不是 `getMode()`），我们的 hook 就覆盖不到。
当前证据表明 `HeadsetDeviceManager.convertToBluetoothDevice` 走的是 `getMode()`
（`smali_classes2/com/miui/circulate/api/protocol/headset/HeadsetDeviceManager.smali:314`），
所以主链路是安全的；这一条作为残余风险记录在此，若日后仍复现，优先查这里。

## 十三、切模式时图标「闪一下旧值再闪回来」：待确认窗口的修复

第八轮补完 `ProfileContext` 层 hook 之后，用户实测反馈**现象变了**：

> 现在切换图标也是一样，只不过这次我进到了小米原生耳机的设置页面了，
> 能在里面切换模式了。只不过切换模式也会出现相似问题，
> 如：降噪切换到开放（通透），显示切换到通透会**闪一下闪到原来的模式，
> 再闪一下闪到通透**。

这一条把问题从「不刷新」重新定性为「**新值 → 闪回旧值 → 再回新值**」。
两者根因完全不同：不刷新是值根本没送出去；闪回是**值送出去了，又被旧值盖回来**。

### 13.1 完整因果链（五步，逐行可查）

1. 用户在融合中心点「通透」→ `MiLinkServiceHook.hookAncStateBlock` 立刻执行
   `ancMode = mafFromMiLink(mode)`（新值）、`syncAncModel(...)` 把
   `AncBatteryModel.ancState = 2`、`notifyPropertyChanged(UPDATE_ANC)` ——
   界面**当场显示「通透」**（正确）。
2. 同一个调用里 `sendAncSelect(ancMode)` 把 `ACTION_ANC_SELECT` 发给蓝牙进程；
   蓝牙进程 `BluetoothProcessHook` 收到后调 `FiilRfcommController.setAnc(2)`，
   发出 FIIL 命令。**但耳机回包 `MAF_STATUS` 要 1~2 秒才到**，这期间
   `FiilRfcommController.ancMode` 仍然是**旧值**（降噪=1）。
   实测依据（用户日志 2026-10-30）：`19:49:24.380` 发 `setAnc mode=2`，
   `19:49:26.467` 才收到 `RX 0A 28 03 00 03 0C 01 02` 状态回报，间隔 **2.1s**。
3. 在这 2 秒窗口内，蓝牙进程任何一次 `publishState()`（30s 电量心跳、
   A2DP 事件、连接状态刷新、佩戴变化）都会用 `snapshot()` 把
   **`ancMode` 的当前值（旧值）** 打包进 `ACTION_STATE` 广播出去
   （`FiilRfcommController.kt` 的 `snapshot()` 里 `ancMode = ancMode` 这一行）。
4. `MiLinkServiceHook.handleBroadcast` 的 `ACTION_STATE` 分支**无条件**执行
   `ancMode = status.ancMode` —— 于是刚写进去的新值被旧值覆盖，
   紧跟着的 `notifyPanel(UPDATE_BATTERY)` 让卡片带着旧值重绘 → **闪回降噪**。
5. 耳机回包真的到了，`ancMode` 更新为 2，再 `publishState()` → **又闪回通透**。

用户看到的就是「通透 → 闪降噪 → 闪通透」。

### 13.2 修法：两侧各加一个「待确认窗口」

问题的本质是**乐观写入与到期确认之间没有隔离**。修法是在
「刚下发、还没确认」这段时间里，把链路两侧的过期快照都挡掉。

#### ① 蓝牙进程侧（`pods/FiilRfcommController.kt`）

`setAnc(mode)` 在发命令**之前**先落地本地值与待确认目标：

```kotlin
ancMode = mode
pendingAncTarget = mode
pendingAncAt = android.os.SystemClock.elapsedRealtime()
```

三处耳机回包（`FiilMate.Query.MAF_STATUS`、Mate 主动通知、`handleAnc` 的 GAIA
回包）原来都是直接 `ancMode = v`，现在统一走新增的 `applyAncFromDevice(value)`：

| 条件 | 行为 |
|---|---|
| 没有待确认目标（`pendingAncTarget < 0`） | 正常采纳 |
| 窗口已过（≥ `ANC_CONFIRM_WINDOW_MS`） | 清目标后采纳 —— 耳机最终说了算 |
| 窗口内且 `value == target` | 采纳并清目标（提前确认，后续恢复正常接纳） |
| 窗口内但 `value != target` | **丢弃**，日志 `anc reply $value ignored, pending target=$target` |

`ANC_CONFIRM_WINDOW_MS = 3_500L`：比实测最坏回包 2.1s 留 1.4s 余量，
又明显小于 30s 电量心跳周期，窗口内不会长时间屏蔽耳机真实状态。
**窗口过期后一定采纳耳机回报**，所以耳机真切换失败时不会停在成功态。

#### ② miLink 进程侧（`hook/milink/MiLinkServiceHook.kt`）

新增 `pendingAncTarget` / `pendingAncAt` 两个字段与 `markAncPending(target)`，
在两个下发点各调一次：`hookAncStateBlock`（融合中心 / 原生耳机页走这条）
与 `hookAncCommand`（耳机实体键或 `MxBluetoothService` 主动切换走这条）——
只覆盖一条的话，另一条仍会把旧值带回来。

`ACTION_STATE` 分支改为：

```kotlin
val pending = pendingAncTarget
val inWindow = pending >= 0 &&
    android.os.SystemClock.elapsedRealtime() - pendingAncAt < ANC_SETTLE_MS
if (!inWindow) {
    ancMode = status.ancMode
    pendingAncTarget = -1
} else if (status.ancMode == pending) {
    ancMode = status.ancMode      // 回包确认了目标值，提前结算
    pendingAncTarget = -1
} else {
    logD("ACTION_STATE anc=${status.ancMode} ignored, pending=$pending")
}
```

`ANC_SETTLE_MS = 3_000L`：覆盖最坏回包延迟，同时远小于心跳周期。

### 13.3 为什么两侧都要加

只加 miLink 侧：蓝牙进程内部的 `ancMode` 仍会被过期回报打回旧值，
下一次心跳（30s 后）广播出来的还是旧值，问题只是**延后**而非消失。

只加蓝牙进程侧：`ACTION_STATE` 里带的已经是正确的乐观值，但 miLink 侧
若在窗口内收到**更早发出、尚未落地**的 `ACTION_STATE`（广播有排队），
照样会覆盖。两侧同时挡，才能保证窗口内的旧值一律进不来。

### 13.4 顺带确认的另一件事

本轮同时确认 **Bug 5 已修好**：用户说「这次我进到了小米原生耳机的设置页面了，
能在里面切换模式了」—— 即第六轮改的 `SettingsHook` 方法签名
（`Int::class.javaPrimitiveType` 取代装箱的 `Int::class.java`）确实生效了，
「更多设置」按钮现在能正确打开原生页。

### 13.5 本轮核验结果

- `:app:compileDebugKotlin` → BUILD SUCCESSFUL（仅既有 deprecation 警告）。
- `:app:clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
  → **BUILD SUCCESSFUL in 4m 47s，EXIT=0**（95 tasks: 57 executed / 36 from cache / 2 up-to-date）。
- 单测 **suites=4 tests=57 failures=0 errors=0 skipped=0**
  （`AddressMaskTest` 6 / `ModelCapabilityTest` 16 / `PodImagesTest` 8 / `ProtocolFrameTest` 27）。
- 产物：`dist/FiilPods-v2.2.0-debug.apk` 35,808,507 B；
  `dist/FiilPods-v2.2.0-release.apk` 4,567,512 B。
  release SHA256 = `F513DCC6F99D13D4BFFA02B4A81FD022CA62FD9AD043A379D665C1968BF7847F`；
  debug SHA256 = `4CE98E88821407D4F4CC2DFDC56D0C46C5A8404B05C6BF4B18FCFBE7B9DE86F1`。
- 源码 mtime 全部早于 APK（`source newer than apk: 0`）。
- APK 特征串核验（release，13 条新增/回归串全 True）：
  `applyAncFromDevice`、`ANC_CONFIRM_WINDOW_MS`、`anc reply `、`markAncPending`、
  `pendingAncTarget`、`ANC_SETTLE_MS`、`ACTION_STATE anc=`、
  `getSYSTEM_BROADCAST_HANDLER`、`syncAncModel: no ancBatteryModel on host`、
  `candidateClassLoaders`、`ldac set -> want=`、`unhealthy disconnect (`、
  `giving up auto-reconnect for`。
  被用户否掉的 2.2.0 三串仍全 False：
  `connect throttled`、`MAX_SILENT_ATTEMPTS`、`silentAttemptStreak`。

### 13.6 排查指引

若日后仍看到「闪一下旧值」，按顺序查：

1. `logcat -s FiilPods-Rfcomm` 搜 `anc reply ` —— 有这条说明**蓝牙进程侧**
   正在丢弃过期回报（工作在生效）；若窗口过短导致旧值漏进来，把
   `ANC_CONFIRM_WINDOW_MS` 加大到 5000。
2. `logcat -s MiLinkServiceHook` 搜 `ACTION_STATE anc=` —— 这条说明 **miLink 侧**
   在挡旧值；若频繁出现且伴随着用户抱怨「还是旧的」，说明
   `ANC_SETTLE_MS` 太长把真实更新也挡了，减小到 2000。
3. 两侧日志都**没有** → 说明覆盖来源不在这两条链路，改查
   `DiscoveryImpl.onHeadsetPropertyChanged` 的 `updateType` 是否为 8
   （见 11.3 的 type 表）。
4. 确认 `FiilRfcommController.setAnc` 里 `ancMode = mode` 那两行还在
   （`pendingAncTarget` 的赋值紧跟在后面）；被后续改动误删就会立刻复发。

## 十四、Bug 2 二次复发：焦点通知在 HyperOS 4 上「两条 hook 路径全部失效」

> 第二节记录的是**第一次**修 Bug 2（权限、渠道名、焦点契约字段、`notifyAsUser`）。
> 那一套在旧 ROM 上是对的，但在这台机器（Android 17 / SDK 37 / HyperOS 4 /
> `com.xiaomi.bluetooth` = BluetoothExtension 27.7 MB）上**完全没生效**。
> 本节记录这一轮的逆向取证与修法。

### 14.1 现场证据

只读 `adb` 查询（未改动手机任何文件）：

```
$ adb shell settings get global mibt_memory_trim
null
$ adb shell dumpsys notification | grep "moe.chenxy.fiilpods"
    AppSettings: moe.chenxy.fiilpods (10475) importance=DEFAULT userSet=true
$ adb shell appops get moe.chenxy.fiilpods POST_NOTIFICATION
    POST_NOTIFICATION: allow
$ adb shell dumpsys notification | grep -c "pkg=moe.chenxy.fiilpods"
0
```

⇒ 权限、渠道重要性、appops **全部正常**，但**一个活动通知都没有**。
说明问题不在「怎么发通知」，而在「**根本没走到发通知那一步**」——
接收器没注册，电量广播没人收。

### 14.2 逆向：`MiuiBluetoothNotification` 到底在什么时候被创建

先确定类归属。`MiuiBluetoothNotification` **只存在于 `com.xiaomi.bluetooth`**：

```
_dev/bt_ext_smali/smali/com/android/bluetooth/ble/app/MiuiBluetoothNotification.smali
```

而 `com.android.bluetooth`（AOSP，`_dev/bt_smali`）里**没有这个类**。
它的两个构造器：

```smali
:179 .method public constructor <init>(Landroid/content/Context;Landroid/os/Looper;)V
:278 .method public constructor <init>(Landroid/os/Looper;Lcom/android/bluetooth/ble/app/headset/BluetoothHeadsetService;)V
```

字段 `:129 .field private mContext:Landroid/content/Context;`。
两个构造器内部都会 `registerNotificationReceiver(this)`（构造器 2 在 `:360`）
并发 `sendMessage(0x65 MSG_INIT_LOCAL_SOURCE)`。

#### 路径 A —— `MiuiApplication`（被门控，真机上永不执行）

`_dev/bt_ext_smali/smali/com/android/bluetooth/ble/MiuiApplication.smali:1507-1544`：

```smali
1507 invoke-static DeviceUtils->isMemoryTrimEnabled()Z
1509 move-result v0
1511 if-eqz v0, :cond_0          # v0 == 0 → 直接跳过，不创建
1513 const-string v0, "com.xiaomi.bluetooth"
1515 invoke-static Application->getProcessName()
1523 if-eqz v0, :cond_0          # 进程名也必须是 com.xiaomi.bluetooth
1530 new-instance MiuiBluetoothNotification
1542 invoke-direct {v0,v2,v3} <init>(Context, Looper)   # ← 构造器 1
```

`isMemoryTrimEnabled()`（`DeviceUtils.smali`）= `Settings.Global.getInt(cr, "mibt_memory_trim", 0) != 0`。
真机实测该键 **不存在**（返回 `null`）⇒ 恒等于 0 ⇒ **这条分支永远不进**
⇒ 构造器 1 **永远不会被 new**。

#### 路径 B —— `BluetoothHeadsetService`（不受门控，真机实际走这条）

`_dev/bt_ext_smali/smali/com/android/bluetooth/ble/app/headset/BluetoothHeadsetService.smali`：

```smali
19152 invoke-direct {p0}, -> W1()V          # 在 onCreate (:19048) 内
6453 .method private W1()V
6456 new-instance BluetoothHeadsetService$$ExternalSyntheticLambda3
6460 invoke-static {v0}, Lj0/a;->b(Runnable)   # 投递到子线程
   → ExternalSyntheticLambda3.run() (:26-34)
   → BluetoothHeadsetService.a(BluetoothHeadsetService)V (:6706)
   → q2() (:11085)
11288 invoke-static DeviceUtils->isMemoryTrimEnabled()
11292 if-eqz v4, :cond_3      # v4 == 0 → 【反而跳到创建分支】
11294 sget mMiuiBluetoothNotification
11296 if-eqz v4, :cond_3      # 为 null → 也跳到创建分支
11300 attachHeadsetService(...)  # 已存在则复用
11305 :cond_3 new-instance MiuiBluetoothNotification
11315 invoke-direct {v4,v5,v6} <init>(Looper, BluetoothHeadsetService)   # ← 构造器 2
11317 sput mMiuiBluetoothNotification
```

⇒ **真机实际走的是构造器 2 `(Looper, BluetoothHeadsetService)`。**

### 14.3 旧实现为什么一条都没挂上

两处都失效，而且失效方式不同：

**(1) 构造器 hook 押错了重载。**
`hook/HookContext.kt:131-136` 的 `findConstructorByParamCount(className, count)` 是

```kotlin
findClass(className).declaredConstructors.firstOrNull { it.parameterTypes.size == count }
```

两个构造器都是 2 参，它返回**声明顺序第一个** = 构造器 1 `(Context, Looper)`。
而构造器 1 只在路径 A 被 new，路径 A 被 `mibt_memory_trim` 门控挡住
⇒ **hook 永不触发** ⇒ 接收器从未注册 ⇒ 电量广播没人收 ⇒ 通知栏永远没有卡片。

**(2) 兜底路径挂在了错误的进程里。**
旧代码兜底是 `findMethodByParamCount("com.android.bluetooth.btservice.AdapterService", "onCreate", 0)`。
但 `hook/HookEntry.kt:55` 是 `PKG_MI_BLUETOOTH -> MiBluetoothToastHook(...)`，
即本模块装在 **`com.xiaomi.bluetooth`**；而 `AdapterService` 只在
`com.android.bluetooth`（AOSP）里 —— BluetoothExtension 全目录
`Find-ChildItem -Filter AdapterService.smali` → **NOT FOUND**。
所以在这个进程里 `findMethodByParamCount` 必然返回 `null`，兜底注册永远不执行。

### 14.4 修法：三条通道 + 幂等注册 + 落盘诊断日志

`app/src/main/java/moe/chenxy/fiilpods/hook/MiBluetoothToastHook.kt`

**通道 1（主）`Application.attach`** —— 唯一「必然发生且最早」的时机。
`com.xiaomi.bluetooth` 是被 `com.android.bluetooth` 通过 `bindService` 拉起的独立进程，
manifest 里 `android:name="com.android.bluetooth.ble.MiuiApplication"`，
进程一起来就走 `Application#attach(Context)`：

```kotlin
val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
attach.isAccessible = true
hookAfter(attach) { param ->
    val app = param.instance as? Application ?: return@hookAfter
    doRegister("Application.attach", app, app)
}
```

**通道 2 `BluetoothHeadsetService.onCreate`** —— 不依赖任何 Settings 开关；
`if-eqz v4, :cond_3` 的跳转方向说明 `isMemoryTrimEnabled()==0` 时**反而**落到创建分支，
所以它一定会建：

```kotlin
val m = findMethodByParamCount(CLS_BLUETOOTH_HEADSET_SERVICE, "onCreate", 0)
    ?: return@runCatching
hookAfter(m) { param ->
    val svc = param.instance as? Context ?: return@hookAfter
    doRegister("BluetoothHeadsetService.onCreate", svc, svc.applicationContext ?: svc)
}
```

**通道 3 两个构造器全挂** —— 不再押注单一重载，
`declaredConstructors.forEach` 把两个都挂上，日志里带上参数签名便于分辨：

```kotlin
val cls = findClass(CLS_MIUI_BLUETOOTH_NOTIFICATION)
cls.declaredConstructors.forEach { ctor ->
    val signature = ctor.parameterTypes.joinToString(",") { it.simpleName }
    hookConstructorAfter(ctor) { param ->
        val ctx = getObjectField(param.instance, "mContext") as? Context
            ?: return@hookConstructorAfter
        doRegister("ctor($signature)", ctx, ctx.applicationContext ?: ctx)
    }
}
```

**幂等注册** `doRegister(channel, raw, app)`：三条通道可能在同一进程里先后命中，
`registerReceiver` 内部用 `if (receiverRegistered) return` 保证只注册一次；
`appContext` 后到的更「正确」Context（Service 的 `applicationContext`
比 Service 自身更适合长期持有）会覆盖先到的。

**落盘诊断日志** `appendLog(msg)`：这台机器 `logcat` 的 `main` 环形缓冲区
只有 64 KiB 且长期「0 B readable」，LSPosed 的 `verbose.log` 也不记录模块内部的
`Log.i` —— 事后抓取拿不到任何东西。所以凡是「hook 到底装没装 / 接收器到底注册没注册」
这类必须先确认的事实，一律同时写进：

```
/sdcard/Android/data/moe.chenxy.fiilpods/files/bluetooth_notification_debug.log
```

只保留最近 400 行，避免无限增长。

### 14.5 上一轮审查还顺带发现的一处失效字段

同一份 smali 显示 `HeadsetInfo` 在 18.2 上是 **11 个字段**：

```
(address, name, deviceId, powers, mode, volume, type, wiredState, switchState, audioEffectState, findRingState)
```

`component1..component11` 的对应关系是 **1=address 2=name 3=deviceId 4=powers 5=mode
6=volume 7=type 8=**wiredState** 9=switchState 10=audioEffectState 11=findRingState**。
旧代码写的 `hookHeadsetInfoNoArg("component8") { miLinkSwitchState() }` 在旧版
（9 字段，8=switchState）是对的，在 18.2 上**写错了字段**（写进了 `wiredState`）。
已改到 `component9`。同理 `component11`（旧版是 `findRingState` 之外的第 11 位）
现在是 `findRingState`，已按新顺序对齐。

### 14.6 本轮核验结果

| 项 | 结果 |
| --- | --- |
| `:app:clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` | BUILD SUCCESSFUL in 4m 49s, EXIT=0 |
| 单测 | suites=4, tests=57, failures=0, errors=0, skipped=0 |
| `aapt2 dump badging` | `versionCode='4' versionName='2.2.0'`、`application-label:'FiilPods 2.2 Fix'` |
| 源码滞后检查 | `source files newer than apk: 0` |
| release APK 新特征串（10 条） | `Application.attach`、`BluetoothHeadsetService.onCreate`、`com.android.bluetooth.ble.app.headset.BluetoothHeadsetService`、`com.android.bluetooth.ble.app.MiuiBluetoothNotification`、`receiver registered in xiaomi.bluetooth`、`register receiver FAILED`、`battery rx name=`、`bluetooth_notification_debug.log`、`ctors SKIPPED`、`getExternalFilesDir` —— **全部 True** |
| 回归串（5 条） | `getSYSTEM_BROADCAST_HANDLER`、`system_broadcast handler unavailable`、`setAncStateBlock`、`hookBooleanStatic`、`notifyAsUser` —— 全部 True |
| 中文字串（3 条） | `FiilPods 2.2 Fix`、`LDAC 编解码器`、`伪装耳机型号` —— 全部 True |
| 被否的 2.2.0 断连自愈四串 | `connect throttled`、`MAX_SILENT_ATTEMPTS`、`silentAttemptStreak`、`connectBlocking: already connected` —— **全部 False**（约束遵守） |

### 14.7 装完必须重启蓝牙进程

`ps -A -o PID,ETIME,NAME` 实测两个蓝牙进程已经跑了 50 分钟：

```
3041  3024  50:25  com.android.bluetooth
3458  3024  50:24  com.xiaomi.bluetooth
```

LSPosed 的 hook 只在**进程启动时**注入。装完新 APK 后如果不停进程，
`com.xiaomi.bluetooth` 里跑的仍是旧 hook（旧 hook 里压根没有 `Application.attach`），
看起来会「和没修一样」。所以验证前必须按第十六节第 41 条关开蓝牙
（或重启手机），让两个进程重新拉起。

### 14.8 排查指引（若仍不出现通知卡片）

1. 先确认进程是新的：`adb shell ps -A -o PID,ETIME,NAME | grep bluetooth`，
   `ETIME` 应该只有几分钟。
2. 导出落盘日志（**不需要 root**，这是应用自己的外部私有目录）：
   ```
   adb shell run-as moe.chenxy.fiilpods cat \
     /sdcard/Android/data/moe.chenxy.fiilpods/files/bluetooth_notification_debug.log
   ```
   若 `run-as` 不可用，装个文件管理器从 `Android/data/moe.chenxy.fiilpods/files/` 拷出来。
3. 按日志内容判断：
   - **有 `channel=Application.attach registered=false`** → 通道 1 命中，
     接着应有一行 `receiver registered in xiaomi.bluetooth`。
   - **只有 `channel=BluetoothHeadsetService.onCreate`** → 通道 1 没赶上注入时机，
     通道 2 兜住了，功能一样正常。
   - **三行 `... SKIPPED: ...`** → 三条通道全部失败，把这三行原文发出来。
   - **有 `receiver registered` 但之后没有 `battery rx ...`** → 接收器挂上了但广播没到，
     说明发送侧（`com.android.bluetooth` 进程里的 `broadcastToTargets`）没发到
     `com.xiaomi.bluetooth`，去查第十节的发送目标列表。
   - **有 `battery rx ...` 但仍无卡片** → 接收链路通了，问题退回第二节记录的那几层
     （渠道 id / 重要性 / 焦点契约字段 / `notifyAsUser`），按第二节逐项复核。

## 十五、删除 OPPO 的 LE Audio 设置页（2.2.1 新增）

### 15.1 为什么删：只有 UI 壳，零 hook 实现

用户问「设置页面的 oppo le audio 这个设置页面 fiil 有没有类似的页面？」。逐层核实后的结论是
**有页面、但只有 UI 壳、一行 hook 都没有**：

| 层 | 现状 |
| --- | --- |
| 数据层 | `config/LeAudioConfig.kt`（38 行）——16 个字段的 `data class` + `parsedMask()` + `normalized()`，整体从 OppoPods 复制 |
| UI 层 | `ui/pages/LeAudioSettingsPage.kt`（213 行）+ `ui/dialogs/LeAudioDevicesDialog.kt`（111 行），开关全是本地 state |
| 接线 | `SettingsPage` 入口卡片 → `MainTabs` 四处透传 → `MainUI` 的 `Screen.LeAudio` 路由，链路**完整** |
| **hook 层** | **空**。`hook/` 下无 `leaudio` 目录；`hook/HookEntry.kt:53-56` 只注册 4 个 hook（`BluetoothProcessHook` / `SettingsHook` / `MiBluetoothToastHook` / `MiLinkServiceHook`），没有 LeAudio |

对照 `refs/OppoPods`：真正干活的 `hook/leaudio/LeAudioConnectionHook.kt`（504 行）与
`hook/leaudio/OppoLeAudioHook.kt`（119 行）**从未移植过来**。它们 hook 的目标是
AOSP 的 `com.android.bluetooth.le_audio.LeAudioService` / `LeAudioNativeInterface` /
`LeAudioStackEvent` / `btservice.PhonePolicy#autoConnectLeAudio` /
`btservice.RemoteDevices#aclStateChangeCallback`，服务的是 OPPO 私有 AT 握手
（`VDID` / `VDSF` / `OESF` / `VDSP`）。

FIIL 侧也没有任何关联：`pods/` 协议层搜 `LC3|LE Audio|BleAudio|LE_AUDIO|LEA` → **零命中**；
`pods/FiilModels.kt` 的 43 个 earType 能力表完全不涉及 LE Audio。

所以这个页面在任何 FIIL 耳机上都不可能起任何作用 —— 用户只能看到一堆拨不动的开关。
按用户要求「删除」，整块移除。

### 15.2 全源码复核：三处「leAudio」残留都是误报

删完后再全项目搜 `leAudio|LeAudio|LE_AUDIO`，剩 3 处，逐一确认**都不是本功能**：

| 位置 | 判定 |
| --- | --- |
| `hook/milink/MiLinkServiceHook.kt:275` `hookDeviceResult(className, "isLeAudio") { false }` | **必须保留**。这是故意把 FIIL 排除在 LE Audio 外，让融合中心不要给 FIIL 出 LE Audio 卡片 |
| `hook/milink/MiLinkSpatialAudioHook.kt:44` 与 `:217` 的 `hookProfileAudioEffectState()` | 误报。`Profile` 末尾与 `Audio` 拼接后恰好连续出现 `leAudio`，与 LE Audio 无关 |

### 15.3 删除清单（逐文件）

**数据层 `config/ConfigManager.kt`**
- `AppConfig` 删 `val leAudio: LeAudioConfig = LeAudioConfig(),`（原 `:20`）
- 删整个 `fun updateLeAudio(prefs, service, config: LeAudioConfig)`（原 `:151-153`）
- 两个 `save(...)` 重载里的 `config.copy(..., leAudio = config.leAudio.normalized())`
  → 只留 `config.copy(fakeDeviceId = config.fakeDeviceId.normalizedFakeDeviceId())`（原 `:157`、`:165`）
- `AppConfig.normalized()` 删 `leAudio = leAudio.normalized(),`，并修掉 `copy()` 里漏写的
  `fakeDeviceId`（原 `:237` 一带）
- `changedFields()` 删 `if (oldConfig.leAudio != newConfig.leAudio) { add("leAudio=...") }`（原 `:277-279`）

**删除的 3 个文件（git 标 `D`，共 −362 行）**
`config/LeAudioConfig.kt`、`ui/pages/LeAudioSettingsPage.kt`、`ui/dialogs/LeAudioDevicesDialog.kt`

**UI 接线**
- `ui/MainUI.kt`：删 `import ...ui.pages.LeAudioSettingsPage`（原 `:62`）、
  `data object LeAudio : Screen`（原 `:97`）、`val leAudioConfig = remember { ... }`（原 `:184`）、
  `onOpenLeAudio = { backStack.add(Screen.LeAudio) },`（原 `:966`）、
  以及整个 `entry<Screen.LeAudio> { ... }` 块（原 `:1048-1081`，含 TopAppBar 与
  `LeAudioSettingsPage(config=..., onConfigChange={ ... ConfigManager.updateLeAudio(...) ... })`）
- `ui/MainTabs.kt`：删 4 处 `onOpenLeAudio`（两处形参声明、两处实参透传）
- `ui/pages/SettingsPage.kt`：删入口卡片 `item { Card { BasicComponent(title = le_audio_title, ...) } }`
  （原 `:294-305`）+ 形参 `onOpenLeAudio: () -> Unit = {},`（原 `:67`）

**字符串资源（84 条）**
- `res/values/strings.xml`：删 `:144-185` 共 42 行（`le_audio_title` … `le_audio_saved_device`），202 → 160 行
- `res/values-zh-rCN/strings.xml`：删 `:148-189` 共 42 行，203 → 161 行

### 15.4 老用户数据兼容：不需要迁移

`ConfigManager` 的 JSON 解析器是 `Json { ignoreUnknownKeys = true; encodeDefaults = true }`
（`config/ConfigManager.kt:60-62`）。老用户的 prefs 里如果还留着 `"leAudio": {...}`，
`ignoreUnknownKeys = true` 会**自动忽略**这个未知字段，不会抛异常、不会读失败。
第一次保存时该字段自然消失。

### 15.5 版本号

按用户要求升一版：`app/build.gradle.kts` 原 `versionCode = 4` / `versionName = "2.2.0"`
（`:23-24`）→ **`versionCode = 5` / `versionName = "2.2.1"`**（现 `:30-31`），
并在上方补了 8 行注释说明为何删除该页。**应用名 `FiilPods 2.2 Fix` 保持不变**。

### 15.6 核验结果

| 项 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL in 1m 2s，EXIT=0；仅两条既有 deprecation 警告（`MainUI.kt:767 getDefaultAdapter`、`MiBluetoothToastHook.kt:774 setDefaults`） |
| clean 全量构建 | BUILD SUCCESSFUL in 4m 33s，EXIT=0（95 tasks: 71 executed / 22 from cache / 2 up-to-date） |
| 单测 | suites=4, tests=57, failures=0, errors=0, skipped=0 |
| 源码滞后检查 | `source files newer than apk: 0` |
| `aapt2 dump badging` | `versionCode='5' versionName='2.2.1'`、`application-label:'FiilPods 2.2 Fix'` |
| `aapt2 dump resources` | release APK 内 **`le_audio` 资源 0 条**；`LeAudioSettingsPage` / `LeAudioConfig` / `LeAudioDevicesDialog` 三个类名**零命中** |
| APK 体积 | release 4,543,336 B，比 2.2.0 的 4,567,512 B **小 24,176 B** |
| 回归特征串（17 条） | `candidateClassLoaders`、`loadClassOrNull`、`ldac set -> want=`、`ACTION_LDAC_SET`、`getSYSTEM_BROADCAST_HANDLER`、`Application.attach`、`BluetoothHeadsetService.onCreate`、`receiver registered in xiaomi.bluetooth`、`bluetooth_notification_debug.log`、`battery rx name=`、`setAncStateBlock`、`hookBooleanStatic`、`notifyAsUser`、`connectUserInitiated`、`giving up auto-reconnect for`、`ldac publish skipped`、`battery snapshot has no connected parts` —— **全部 True** |
| 中文字串（3 条） | `LDAC 编解码器`、`伪装耳机型号`、`需在系统设置中开启 LDAC` —— 全部 True |
| 被否的 2.2.0 断连自愈三串 | `connect throttled`、`MAX_SILENT_ATTEMPTS`、`silentAttemptStreak` —— **全部 False** |
| LeAudio 三串 | `LeAudioSettingsPage`、`le_audio_title`、`LeAudioDevicesDialog` —— **全部 False** |

> ⚠️ 工具层踩坑：PowerShell 里用 `[System.IO.File]::ReadAllBytes("dist\...")` 做字符串核验
> **必须传绝对路径**。.NET API 按进程工作目录解析相对路径，不认 PowerShell 的 `cd`，
> 会报 `Could not find a part of the path 'D:\DeepSeek\dist\...'`。

## 十六、回归验证清单

1. 耳机控制页能看到 LDAC 开关，开关能真正切 LDAC（`dumpsys bluetooth_manager` 里 codec 变 LDAC）。
2. 通知栏出现耳机卡片：电量三行、降噪循环按钮、断开按钮、AOD 显示。
3. 设置页「超级岛来源 = 模块」时，耳机连上会弹岛显示左右耳电量；单耳时不出现 `-%`。
4. 融合设备中心切降噪/通透 → 图标立刻变，不需要重进页面。
5. 融合中心 › 更多设置 › 降噪三个按钮能生效，不再提示「无法设置」。
6. 设置页把「伪装耳机型号」改成任意 8 位 id → 立刻生效（不用重启）；
   改回 `01010607` 也立刻恢复。
7. `./gradlew :app:testDebugUnitTest` 57 项全绿（`AddressMaskTest` 6 / `ModelCapabilityTest` 16 / `PodImagesTest` 8 / `ProtocolFrameTest` 27）。
8. **（本轮新增）** 耳机断开时点 LDAC 开关 → 开关自动消失，不会停在「开」的假状态。
9. **（本轮新增）** 耳机收回充电盒、三路全断开 → 通知栏卡片自动消失，不留「未连接」。
10. **（本轮新增）** 系统拒绝改编码器时，会自动跳到系统「蓝牙音频编解码器」页
    （若该页不存在则落到蓝牙设置总页 + Toast「需在系统设置中开启 LDAC」）。
11. **（本轮新增）** 连续快速更新电量（例如反复戴上摘下）→ 超级岛不会一闪就空。
12. **（第四轮新增，P0）** 耳机连上后进控制页 → **LDAC 开关必须出现**。
    若不出现，先在 LSPosed 日志里搜 `ldac publish skipped`：
    有这条 = `publishState` 抛异常；没有这条但开关仍不出现 = `EXTRA_LDAC_KNOWN` 为 false，
    即 `a2dpServiceOrNull()` 没拿到实例（classloader 或 ROM 差异）。
13. **（第四轮新增）** 打开 LDAC 开关 → `logcat -s FiilPods-Codec` 应看到
    `setLdac(true) dispatched, codecType=4`；`dumpsys bluetooth_manager | grep -i codec`
    应看到 `codec: LDAC`。
14. **（第四轮新增）** 断开耳机 → 控制页里 LDAC 那一项**直接消失**（不是灰掉、不是停在旧状态）。
15. **（第五轮新增）** 把耳机放进充电盒（或远离）让它连不上 → 连接状态**最多跳 3 次就停**，
    之后界面稳定在「已断开」，并弹一次「连接失败」回到设备选择页；
    `logcat -s FiilPods-Rfcomm` 应出现 `unhealthy disconnect (` ×3 后跟一条
    `giving up auto-reconnect for 60000ms`，**不再**每 5/8.5 秒刷一次。
16. **（第五轮新增）** 继续等 1 分钟以上 → 若仍连不上，冷却窗口应翻倍到 120000ms
    （日志 `cooldown #2`），而不是回到 60000ms。
17. **（第五轮新增）** 在模块里点「断开」→ 耳机**不会**自己连回来（等 2 分钟以上也不回）；
    此时日志里若出现 `connect ignored: user requested disconnect` 就是刹车在生效。
18. **（第五轮新增）** 手动点「连接」→ 立即尝试，且日志出现
    `failure streak cleared by user action`；若之前处于冷却期，这次操作应能穿透冷却。
19. **（第六轮新增）** 连上 FIIL Key Max → 主页顶部出现「当前耳机」卡片，
    显示 Key Max 的产品图 + 型号名，点击切到耳机页。
20. **（第六轮新增）** 换一台不同型号（例如 CC Pro2 / GS Lite / Atom）→
    卡片上的图**立刻跟着换**，不是停在上一台的图。
21. **（第六轮新增）** 断开耳机 → 主页那张「当前耳机」卡片**消失**（不是留在原地显示旧图）。
22. **（第六轮新增）** 连一台官方没出图的老型号（Driifter / Carat / Runner 等）→
    卡片显示 `fiil_unknow` 兜底图（与 FIIL+ 官方 App 一致），不会空白、不会崩。
23. **（第六轮新增）** 耳机详情页在**没有自定义产品图**的情况下，
    显示的是该型号的产品图，而不是以前的通用 `img_box`。
24. **（第六轮新增）** 在设置里给某台耳机设了自定义产品图 → 详情页仍优先显示自定义图
    （型号图只在没有自定义图时兜底）。
25. **（第六轮新增）** `./gradlew :app:testDebugUnitTest` 57 项全绿
    （新增的 8 项 `PodImagesTest` 钉住型号↔图片映射）。
26. **（第七轮新增）** 融合设备中心里**连点降噪 → 通透 → 关闭**，
    **每点一次图标立刻跟着变**，全程不退出页面、不需要点第二次。
    （这是本次用户报的 BUG1，核心回归项。）
27. **（第七轮新增）** 在融合中心切完模式后**停 1 秒再看** ——
    图标**保持在新模式**，不会被「兜底重发」那次刷新盖回旧值。
28. **（第七轮新增）** `logcat -s MiLinkServiceHook` 里**不应**出现
    `system_broadcast handler unavailable`；出现了说明反射没拿到官方 Handler
    （功能仍应可用，但要按 11.8 排查）。
29. **（第七轮新增）** 桌面/设置里应用名显示 `FiilPods 2.2 Fix`，
    但「应用信息」里的版本号仍是 `2.2.0`（versionCode 4）。
30. **（第七轮新增）** 从旧版升级安装（不卸载）→ 数据与设置项保留，
    LSPosed 作用域无需重新勾选。
31. **（第八轮新增）** 在**这台**机器（MiLink 18.2.0.12 / Android 17 / HyperOS）上，
    融合中心连点降噪 → 通透 → 关闭，**每一次图标都立刻变**。
    这一轮补的是 `ProfileContext` 层的四个 hook，是「退出重进才对」的真正根因（见 12.3）。
32. **（第八轮新增）** 同理验证「游戏模式」与「空间音频」两个开关：
    它们和降噪走同一条 `assembleHeadsetInfo` 链，功能与图标都应立刻生效。
33. **（第八轮新增）** 融合中心里**断连再重连耳机** → 电量与降噪图标都重新出现，
    不会停在断连前的旧值。
34. **（第八轮新增）** 若日后官方 MiLink 升级导致再次卡旧值，按 **12.9** 的五步排查：
    先核对 LSPosed 作用域，再核对 `ProfileContext.getAncState` 与
    `ThirdPartyHeadsetStrategy` 的两个硬编码返回值是否还在。
35. **（第九轮新增，本轮核心）** 融合中心里**降噪 → 通透**，全程盯着图标：
    必须**直接变成通透、停住**，不允许出现「先跳回降噪再跳到通透」的闪动。
    反向（通透 → 降噪）同样验一遍。
36. **（第九轮新增）** 在**小米原生耳机设置页**里切模式，同样不允许闪回旧值
    （这条链走的是同一个 `hookAncStateBlock`，但 UI 侧重绘时机不同，必须单独验）。
37. **（第九轮新增）** 切完模式**立刻**（1 秒内）去看电量有没有变化、或戴上摘下耳机
    触发一次状态刷新 → 图标**不能被这次刷新带回旧模式**（这正是第 3 步广播的复现路径）。
38. **（第九轮新增）** 把耳机切到「通透」后**关掉蓝牙再打开**，重连后图标应显示通透
    （值已落盘到 `fiilpods_milink_state`），不是回到降噪。
39. **（第九轮新增）** `logcat -s FiilPods-Rfcomm` 切模式时若出现
    `anc reply N ignored, pending target=M` → 说明窗口正在挡过期回报（正常）；
    若**一次都没有**出现，而用户仍反馈闪动，说明旧值不是从耳机回报进来的，
    按 **13.6** 第 3 步查 `updateType`。
40. **（第九轮新增）** 连着快速点 3 次模式（降噪→通透→关闭）→ 最终图标等于**最后一次**
    点的模式，中间不残留、不闪回。

41. **（第十轮新增，本轮核心）** 装完新 APK 后**先关开蓝牙一次**（或重启手机），
    让 `com.xiaomi.bluetooth` / `com.android.bluetooth` 重新拉起 —— LSPosed hook
    只在进程启动时注入，不停进程会跑旧 hook（见 **14.7**）。
42. **（第十轮新增）** 连上耳机 → 通知栏应出现耳机卡片（电量三行 + 降噪循环 + 断开 + AOD）。
    若没有，按 **14.8** 导出落盘日志：
    `adb shell run-as moe.chenxy.fiilpods cat /sdcard/Android/data/moe.chenxy.fiilpods/files/bluetooth_notification_debug.log`
43. **（第十轮新增）** 日志里必须有 `receiver registered in xiaomi.bluetooth` 这一行。
    有它才说明三条通道里至少有一条挂上了。
44. **（第十轮新增）** 日志里 `channel=` 那一行决定走的哪条通道：
    `Application.attach`（主）/ `BluetoothHeadsetService.onCreate`（兜底）/
    `ctor(...)`（构造器）。**任意一条命中即可**，不是必须三条都出现。
45. **（第十轮新增）** 日志里若出现 `... SKIPPED:`，那三行就是失败原因原文；
    三条通道全部 SKIPPED 才算彻底失败，需要把原文发出来定位。
46. **（第十轮新增）** 有 `receiver registered` 但日志里**一直**没有 `battery rx ...` →
    接收器挂上了但广播没送达，去查第十节的发送目标列表
    （`FiilAction.STATUS_TARGETS` 是否含 `com.xiaomi.bluetooth`）。
47. **（第十轮新增）** 有 `battery rx ...` 但仍无卡片 → 接收链路已通，问题退回第二节那几层
    （渠道 id `BTHeadset<MAC>` / `IMPORTANCE_DEFAULT` / `miui.focus.*` 契约字段 / `notifyAsUser`）。
48. **（第十轮新增）** 关掉「焦点通知」开关 → 卡片立刻消失；再打开 → 耳机连着的状态下
    **不需要重启蓝牙进程**就能重新出现（接收器是常挂的，开关判断下沉到显示时刻）。
49. **（2.2.1 新增）** 进「设置」页 → **看不到**「LE Audio」那一项（整块已删，见第十五节）。
    若还在，说明装的仍是 2.2.0（`versionName` 应为 `2.2.1`）。
50. **（2.2.1 新增）** 冒烟：LDAC 开关能出现且能切、通知卡片能出现、融合中心切模式图标跟随、
    主页「当前耳机」卡片显示对应型号图 —— 这四项都**不受本次删除影响**。
    另外「设置」页里其余项（伪装耳机型号、超级岛、焦点通知等）位置与功能应完全不变。
