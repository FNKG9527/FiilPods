# OppoPods 架构复刻规格书（UI/UX 金标准）

> 参考仓库：`<repo>\refs\OppoPods`（下称 `<REPO>`）
> 源码根：`<REPO>/app/src/main/java/moe/chenxy/oppopods/`
> 本文为**新模块的移植蓝图**。所有路径相对 `<REPO>`，行号对应当前 checkout。
> 阅读者目标：在 HyperOS 上从零复刻同等 UI/UX 与协议层。

---

## §0 一句话架构

**Xposed 侧（多进程）× 传输侧（RFCOMM/SPP）× UI 侧（独立 Activity + Compose/Miuix）** 三段解耦，全部通过**广播 + 共享 SharedPreferences 镜像**通信：

```
com.android.bluetooth ─┐
com.milink.service   ─┼─ hook 进程内 ── RFCOMM ── 耳机
com.xiaomi.bluetooth ─┘        │
                               │ 广播 ACTION_PODS_*
                               ▼
                    moe.chenxy.oppopods (主进程)
                    MainActivity / PopupActivity (Compose+Miuix)
```

**关键反直觉结论（务必先读）**：
1. **模块从不把 Compose 注入系统设置页**。全仓 `ComposeView`/`addView`/`WindowManager.addView` 零命中。所谓"注入系统设置"= **hook MIUI 耳机详情页的数据方法与 Binder 代理**（返回伪造的 `deviceId/support/ancLevel`），页面本身仍是 MIUI 原生页。见 §2.1。
2. **RFCOMM 读循环不做长度域重组**：一次 `read()` 返回的 chunk 即一帧。见 §3.1.5。
3. **模块自身 Assets 在 hook 进程里要 `createPackageContext` 才能读**。见 §3.3。
4. **超级岛有两条互斥实现路径**：官方通道（劫持 `com.xiaomi.bluetooth` 的 `headset_wear_notification`）与模块自建通道（自建 NotificationChannel + focus-api V3）。见 §6。

---

## §1 Gradle / 清单 / 入口

### 1.1 版本目录 `gradle/libs.versions.toml:1-43`

| 项 | 值 |
|---|---|
| agp | `9.1.1` |
| kotlin | `2.4.20` |
| ksp | `2.3.6` |
| **`libxposedApi`** | **`102.0.0`** |
| **`libxposedService`** | **`102.0.0`** |
| composeBom | `2026.09.00` |
| miuix / miuixNav | `0.9.4` |
| **focusApi** | **`1.4`** |
| coreKtx / activity-compose | `1.19.1` / `1.13.0` |
| kotlinxSerializationJson | `1.11.0` |

插件 ID（`[plugins]` 段）：

```
com.android.application      = agp
com.android.library          = agp
com.google.devtools.ksp      = ksp
org.lsposed.lsplugin.apksign = 1.4      # 用 gradle.properties 签名，无需 keystore 路径硬编码
org.lsposed.lsplugin.resopt  = 1.6      # LSPosed 资源混淆/优化，供模块资源不被查重
kotlin.plugin.serialization  = kotlin
kotlin.plugin.parcelize      = kotlin
kotlin.plugin.compose        = kotlin
```

依赖坐标（**极易搞错**）：

```toml
io.github.libxposed:api                      # -> compileOnly   编译期 API（XposedModule/XposedInterface）
io.github.libxposed:service                  # -> implementation 运行期（远程偏好/作用域查询）
top.yukonga.miuix.kmp:miuix-ui-android
top.yukonga.miuix.kmp:miuix-preference-android
top.yukonga.miuix.kmp:miuix-icons-android
top.yukonga.miuix.kmp:miuix-blur-android
top.yukonga.miuix.kmp:miuix-nav              # 类型化导航 NavKey/NavBackStack
com.xzakota.hyper.notification:focus-api     # 超级岛/焦点通知
```

### 1.2 `settings.gradle.kts:1-27`

```kotlin
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
repositories { google(); mavenCentral()
  maven("https://api.xposed.info/")                                        // libxposed
  maven("https://s01.oss.sonatype.org/content/repositories/releases/")     // miuix/focus-api 镜像
  maven("https://jitpack.io") }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
rootProject.name = "OppoPods"
include(":app")
```

### 1.3 根 `build.gradle.kts:5`

```kotlin
classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
```
> 必须显式升级 KGP：AGP 9 自带 KGP 2.2.10，与本仓 Kotlin 2.4.20 不匹配会报 compiler 版本错。

### 1.4 `gradle.properties`

```
android.experimental.enableNewResourceShrinker.preciseShrinking=true
android.enableAppCompileTimeRClass=true
android.useAndroidX=true
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1024m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
ksp.useKSP2=true
```

### 1.5 `app/build.gradle.kts:1-117` 关键块

```kotlin
plugins {                                                              // :1-8
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.lsplugin.resopt)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.parcelize)
    alias(libs.plugins.compose.compiler)
}

apksign {                                                              // :10-15
    storeFileProperty     = "KEYSTORE_FILE"          // 值从 ~/.gradle/gradle.properties 取，不进仓库
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty      = "KEY_ALIAS"
    keyPasswordProperty   = "KEY_PASSWORD"
}

android {                                                              // :17-76
    namespace     = "moe.chenxy.oppopods"            // :18
    compileSdk    = 37                               // :19  ← HyperOS 3 / Android 16
    defaultConfig {
        applicationId = "moe.chenxy.oppopods"        // :22
        minSdk        = 35                           // :23
        targetSdk     = 36                           // :24
        versionCode   = 17                           // :25
        versionName   = "2.2.0"                      // :26
        buildConfigField("long", "BUILD_TIMESTAMP", System.currentTimeMillis().toString())   // :27
    }
    buildTypes {
        debug  { isDebuggable = true; isMinifyEnabled = false }                          // :31-38
        release{ isMinifyEnabled = true; isShrinkResources = true; isDebuggable = false } // :39-47
        create("releaseFast") {                                                          // :48-53
            initWith(getByName("release")); isMinifyEnabled = false; isShrinkResources = false
            matchingFallbacks += listOf("release")       // 装机调试用：不混淆但签名同 release
        }
    }
    dependenciesInfo.includeInApk = false            // :56  ← 抹掉 Play 签名块，LSPosed 模块常见做法
    buildFeatures { buildConfig = true; compose = true }     // :58-61
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}"      // :63-75
                            excludes += "/META-INF/**.version"
                            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
                            excludes += "okhttp3/**"; excludes += "kotlin/**"
                            excludes += "org/**"; excludes += "**.properties"
                            excludes += "**.bin"; excludes += "kotlin-tooling-metadata.json" } }
}

java   { toolchain { languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_22.majorVersion) } }  // :78-82
kotlin { jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt()) }                                   // :84-86
configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")                          // :88-90
}

dependencies {                                                                                          // :92-117
    implementation(libs.coreKtx)
    compileOnly(libs.libxposedApi)        // ★ 不能换成 implementation
    implementation(libs.libxposedService)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview); debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix)            // miuix-ui-android
    implementation(libs.miuix.preference) // miuix-preference-android
    implementation(libs.miuix.icons)      // miuix-icons-android
    implementation(libs.miuix.blur)       // miuix-blur-android
    implementation(libs.miuix.nav)        // miuix-nav
    implementation(libs.focus.api)        // com.xzakota.hyper.notification:focus-api:1.4
    testImplementation(libs.junit)
}
```

> `exclude lifecycle-viewmodel-ktx`：miuix 0.9.4 传递依赖与 compose BOM 2026.09 冲突，必须排（否则 `ViewModelProvider` 类重复 / `NoSuchMethodError`）。
> `releaseFast` 是给"装到自己手机上看效果"用的：release 的清单/资源处理 + debug 的免混淆，避免每次都要等 R8。
> `dependenciesInfo.includeInApk = false`：去掉 `META-INF/BNDLTOOL` 依赖元数据块，减小体积并避免 LSPosed 的模块查重误判。

### 1.6 混淆 `app/proguard-rules.pro`

```
# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile

# Keep Xposed entry point
-keep class moe.chenxy.oppopods.hook.HookEntry { *; }        # 入口按类全名字符串反射构造

# Keep all hooker classes (referenced by name in Xposed framework)
-keep class moe.chenxy.oppopods.hook.** { *; }

# Keep Parcelable data classes (used in broadcast extras)
-keep class moe.chenxy.oppopods.utils.miuiStrongToast.data.** { *; }
```
> `-repackageclasses` + `-allowaccessmodification` 是反 LSPosed 模块特征检测的常用组合；但入口类必须 keep —— `META-INF/xposed/java_init.list` 里是**类全名字符串**，被重命名/移包后 LSPosed 直接报"入口类不存在"，模块表现为"已激活但无任何效果"。
> `strongToast/data/**` 必须 keep：这些 `@Serializable`/`@Parcelize` 类要跨进程放进 `Bundle`，混淆后字段名对不上会静默丢数据。

### 1.7 `AndroidManifest.xml:1-79`

```xml
<!-- :4-10 -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
<queries>
    <package android:name="com.heytap.headset"/>     <!-- 欢律 App，跳转目标 -->
    <package android:name="com.android.settings"/>   <!-- 系统设置，跳转目标 -->
</queries>

<!-- :12-20 -->
<application android:name=".OppoPodsApp"
             android:icon="@mipmap/ic_launcher"
             android:roundIcon="@mipmap/ic_launcher_round"
             android:label="@string/app_name"
             android:extractNativeLibs="false"
             android:enableOnBackInvokedCallback="true"
             android:supportsRtl="true"
             android:theme="@style/Theme.OppoPods">

  <!-- :22-34 桌面图标指向别名，便于"隐藏桌面图标" -->
  <activity-alias android:name=".LauncherActivity" android:enabled="true" android:exported="true"
                  android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round"
                  android:label="@string/app_name" android:targetActivity=".MainActivity">
      <intent-filter>
          <action android:name="android.intent.action.MAIN"/>
          <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
  </activity-alias>

  <!-- :36-44 主界面 + LSPosed 模块设置入口（★ 只有 category，没有 MAIN+LAUNCHER） -->
  <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|mnc|colorMode|
                                   density|fontScale|fontWeightAdjustment|keyboard|layoutDirection|
                                   locale|mcc|navigation|smallestScreenSize|touchscreen|uiMode">
      <intent-filter>
          <action android:name="android.intent.action.MAIN"/>
          <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS"/>
      </intent-filter>
  </activity>

  <!-- :45-54 快捷弹窗（透明、独立任务栈、不进最近任务） -->
  <activity android:name=".PopupActivity" android:exported="true"
            android:theme="@style/Theme.OppoPods.Popup"
            android:taskAffinity="" android:excludeFromRecents="true">
      <intent-filter>
          <action android:name="chen.action.oppopods.show_pods_ui"/>
          <category android:name="android.intent.category.DEFAULT"/>
      </intent-filter>
  </activity>

  <!-- :55-59 图片跨进程分发 -->
  <provider android:name=".config.PodImageProvider"
            android:authorities="moe.chenxy.oppopods.podimages"
            android:exported="true" android:grantUriPermissions="false"/>

  <!-- :61-76 Xposed 元数据 -->
  <meta-data android:name="xposedmodule"      android:value="true"/>
  <meta-data android:name="xposeddescription" android:value="OPPO Earphones Support for Xiaomi HyperOS."/>
  <meta-data android:name="xposedminversion"  android:value="101"/>
  <meta-data android:name="xposedscope"       android:resource="@array/xposedscope"/>
</application>
```
> ★ 关键：`MainActivity` 的 intent-filter **只有 `MODULE_SETTINGS` 一个 category**，桌面入口完全交给 `activity-alias`。这样"隐藏桌面图标"只需 `setComponentEnabledSetting(.LauncherActivity, DISABLED)`，而 LSPosed 的"打开模块"仍然可用（`MainUI.kt:1034-1050` 就是这么实现的）。

`res/values/arrays.xml`：
```xml
<string-array name="xposedscope">
    <item>com.android.bluetooth</item>
    <item>com.milink.service</item>
    <item>com.xiaomi.bluetooth</item>
</string-array>
```

### 1.8 LSPosed 如何找到入口类（**最重要的一节**）

本仓同时存在**两条互斥的声明方式**，只有一条真正生效：

| 路径 | 内容 | 状态 |
|---|---|---|
| `app/src/main/assets/xposed_init` | `moe.chenxy.oppopods.hook.HookEntry_YukiHookXposedInit` | ❌ **死文件**（旧 YukiHookAPI 命名，类不存在） |
| `app/src/main/resources/META-INF/xposed/java_init.list` | `moe.chenxy.oppopods.hook.HookEntry` | ✅ **权威入口（libxposed 现代方式）** |
| `app/src/main/resources/META-INF/xposed/module.prop` | `minApiVersion=101` / `targetApiVersion=101` / `staticScope=true` | ✅ |
| `app/src/main/resources/META-INF/xposed/scope.list` | 三个包名逐行 | ✅ 与 `xposedscope` 数组等价，二选一 |

**新模块照抄结论**：
- 用 `src/main/resources/META-INF/xposed/` 三件套（`java_init.list` + `module.prop` + `scope.list`），**不要再写 `assets/xposed_init`**。
- 入口类必须 `class XxxEntry : io.github.libxposed.api.XposedModule()`，实现 `onPackageLoaded(param: PackageLoadedParam)`。
- 每个注入进程都会各调一次 `onPackageLoaded`，用 `param.isFirstPackage` 去重才能保证只初始化一次。

### 1.9 资源

`res/values/themes.xml`（原文，注意两个 theme 都 `parent="android:Theme.Material.Light.NoActionBar"`，无自定义 item；`xmlns:tools` 声明存在但未使用）
```xml
<style name="Theme.OppoPods"        parent="android:Theme.Material.Light.NoActionBar"/>
<style name="Theme.OppoPods.Popup"  parent="android:Theme.Material.Light.NoActionBar">
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowNoTitle">true</item>
    <item name="android:backgroundDimEnabled">false</item>
    <item name="android:colorBackgroundCacheHint">@null</item>
</style>
```
> Popup 主题只做"透明 + 无标题 + 无背景调暗"。真正的窗口形态由 Compose 里 `Scaffold(containerColor = Color.Transparent)` + `OverlayDialog` 决定。

`assets/`：`device_models.json`（`{"whiteList":[...]}`，OPPO 官方机型能力表，体积大）、`eq_mode_names.json`（modeType→中文名）、`eq_mode_names.en.json`。

---

## §2 Hook 层

### 2.0 `hook/HookEntry.kt:12-51`

```kotlin
class HookEntry : XposedModule() {                                   // :12
    private val TAG = "OppoPods-HookEntry"                           // :13
    private val configListeners =
        mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()   // :14

    @RequiresApi(Build.VERSION_CODES.Q)                              // :16
    override fun onPackageLoaded(param: PackageLoadedParam) {        // :17
        if (!param.isFirstPackage) return                            // :18  ★ 每进程只跑一次

        when (param.packageName) {                                   // :20
            "com.android.bluetooth" -> {                             // :21-25
                loadHook(OppoLeAudioHook(), param.defaultClassLoader, param.packageName)
                loadHook(HeadsetStateDispatcher, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
            //"com.android.settings" -> loadHook(SettingsHeadsetHook, ...)  // :26 已注释
            "com.milink.service" -> loadHook(MiLinkServiceHook, param.defaultClassLoader, param.packageName)  // :27
            "com.xiaomi.bluetooth" -> {                              // :28-31
                loadHook(MiBluetoothToastHook, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
        }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String) {  // :35
        Log.module = this                       // :36
        hook.module = this                      // :37
        hook.appClassLoader = classLoader       // :38  param.defaultClassLoader
        hook.packageName = packageName          // :39
        hook.prefs = getRemotePreferences("oppopods_settings")   // :40  ★ 跨进程偏好
        Log.d(TAG, "loadHook package=$packageName hook=${hook.javaClass.simpleName}")
        ConfigManager.init(hook.prefs)          // :42
        val configListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->   // :43
            if (key == ConfigManager.PREF_KEY_CONFIG_JSON) ConfigManager.refreshFromPrefs(sp)  // :44-46
        }
        configListeners.add(configListener)     // :48   ← 必须强引用，否则被 GC 后回调静默失效
        hook.prefs.registerOnSharedPreferenceChangeListener(configListener)   // :49
        hook.onHook()                           // :50
    }
}
```
> 三个易错点：① 类加载器取自 `param.defaultClassLoader`（不是 `param` 本身）；② `OnSharedPreferenceChangeListener` **必须存进 `configListeners` 列表**，SharedPreferences 内部只持弱引用，不保留就会在 GC 后停止推送；③ `BluetoothUpstreamHeadsetHook` 被**实例化两次**（故它是 `class` 而非 `object`），分别挂在两个注入进程里。

### 2.1 `hook/SettingsHeadsetHook.kt:19-773`

**scope**：`com.android.settings`（**当前在 HookEntry 中被注释**——因为它依赖蓝牙进程转发广播，独立开启会在设置进程里拿不到数据；如需"完全体"再打开）。

**目标**：让 MIUI 的「已连接耳机详情页」相信自己连的是受支持的小米耳机，从而渲染 NC/EQ/电量 UI，而数据由本模块伪造。

| 行 | 目标 | 动作 |
|---|---|---|
| `:59-90` | `com.android.settings.bluetooth.MiuiHeadsetActivity#onCreate(Bundle)` 与 `MiuiHeadsetActivityPlugin#onCreate(Bundle)` | `hookBefore`：`registerStatusReceiver(activity)`；取 `intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE")`；若 `isOppoPod` → `putExtra("MIUI_HEADSET_SUPPORT", fakeSupport())`、`putExtra("COME_FROM", …?:"MIUI_BLUETOOTH_SETTINGS")`、`putExtra("DEVICE_ID", fakeDeviceId())` |
| `:92-102` | `MiuiHeadsetActivity#getDeviceID()` / `getSupport()` | `hookAfter` 读字段 `mDevice`，`result = fakeDeviceId()` / `fakeSupport()` |
| `:104-154` | `HeadsetIDConstants#checkSupport(String)` / `isTWS01Headset(String)` / `isK77sHeadset(String)` / `isBleMmaConnect(...)` ×2 | 恒返回 true（`isK77sHeadset`→false） |
| `:156-179` | `com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy` | `checkSupport`→`fakeSupport()`；`getDeviceInfo`→`fakeSupport()`；`isSupportAudioSwitch`→`"1"`；`setCommonCommand(cmd,..)`→ `cmd==102 ? "0" : "1"`；`connect`/`getDeviceConfig`/`getCommonConfig`→`null`；`changeAncMode(int,device)`→ `oppoAncFromSettings`；`changeAncLevel(String,device)`→ `oppoAncFromLevelCommand` |
| `:267-291` | `…bluetooth.tws.MiuiHeadsetBattery` 4 参构造 | `hookConstructorAfter`：注册广播；`hookBefore onBatteryChanged(String)` → 吞掉原生回调，改用本模块 `updateBatteryView` 反射调 `onBatteryChanged(int,int,int)` |
| `:293-348` | `MiuiHeadsetFragment#onCreateView/onServiceConnected/refreshStatus(String,String)/handleConnectMmaFailed(String)` | 创建时登记 `headsetFragments`、拉状态、3s 轮询；吞掉 `MMA_CONNECTION_FAILED` |
| `:350-367` | `MiuiHeadsetFragment#updateAncMode(Int,Boolean)` / `updateAncLevel(String,Boolean)` | 用户点击 → 推回 OPPO 协议 |
| `:464-479` | 反射 `updateAtUiInfo(String)` | 下发 `"${ancMode}|0100;0101;0102;0103;0200;0201|${batteryString}|00"` |

**协议映射（这套字符串编码是全仓硬约束，必须照抄）**

```
settingsAncMode():     2|5|6|7|8 → "1"(降噪)   3 → "2"(通透)   else "0"(关)
settingsAncLevel():    5→"0103"  6→"0101"  7→"0100"  8→"0102"  3→"0201"/"0200"  else "0000"
settingsRefreshPayload(): MutableList<String>(16)
    [0]=左电量  [1]=右电量  [2]=仓电量  [7]=ancLevel  [8]="false"
    [11]/[13]/[14]="00"        其余 ""  → joinToString(",")
batteryValue: 未连接 → 255; 否则 (充电 ? level or 0x80 : level)
```
> **同一套 16 槽 payload 在 4 个文件里各写了一遍**（`RfcommController.kt:364-394`、`SettingsHeadsetHook.kt:580-595`、`BluetoothUpstreamHeadsetHook.kt:657-678`）。移植时应抽成单例，但**槽位语义不得改**。

**广播出口**（`setPackage` 定向）：
- `sendOppoAnc` → `ACTION_ANC_SELECT{status}` → `com.android.bluetooth`（`:634-644`）
- `sendAncChanged` → `ACTION_PODS_ANC_CHANGED` → `[APP_ID, com.android.settings, com.milink.service]`（`:669-678`）
- `requestBluetoothStatus` → `ACTION_PODS_UI_INIT` + `ACTION_REFRESH_STATUS` → `com.android.bluetooth`（`:424-433`）
- 状态镜像：`saveState/loadState`（`:716-766`）把 `address/name/anc/transparency_vocal_enhancement/left,right,case 电量+充电+连接` 写进 `oppopods_milink_state`。

### 2.2 `hook/HeadsetStateDispatcher.kt:18-98`（**连接发起者**）

scope `com.android.bluetooth`。这是**整个系统的入口开关**：耳机一被 A2DP 连上，模块才开始连 RFCOMM。

```kotlin
hookAfter("com.android.bluetooth.btservice.AdapterService".onCreate) { registerAppRequestReceiver(instance as Context) }

hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
    val device = args[0] as BluetoothDevice; val curr = args[2] as Int
    getObjectField(instance, "mHandler").post {
        if (isOppoPod(device)) {                    // device.name?.contains("oppo", ignoreCase = true)
            statusBarManager.setIconVisibility("wireless_headset", true)
            if (curr == STATE_CONNECTED) RfcommController.connectPod(context, device, prefs)
            else if (curr in [STATE_DISCONNECTING, STATE_DISCONNECTED]) RfcommController.disconnectedPod()
        } else statusBarManager.setIconVisibility("wireless_headset", false)
    }
}
```
接收的请求广播：`ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS` → 回 `ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE`；`ACTION_CONNECT_POD_REQUEST` → `connectPod(..., appRequested=true)`；`ACTION_DISCONNECT_POD_REQUEST` → `disconnectedPod`。

### 2.3 `hook/BluetoothUpstreamHeadsetHook.kt:24-895`（**最有含金量的一段**）

**作用**：在蓝牙进程内**冒充 `IMiuiHeadsetService` 的 Binder 实现**，把 MIUI 原生耳机页/通知的数据源整条替换。

```kotlin
private const val DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService"
```

**为什么是"运行时类名"**：`BluetoothHeadsetService.onBind()` 返回的是一个匿名 `Binder` 子类，编译期不可知，所以只能：

```kotlin
// :126-154
hookAfter("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService".onBind(Intent)) {
    registerStatusReceiver(instance as Context)
    installHeadsetBinderHooks(result.javaClass)      // ← 拿到运行时类再 hook 其方法
}
// 兜底：直接尝试已知混淆名 "…headset.BinderC6776v" / "…headset.v"
```

`installHeadsetBinderHooks`（`:215-296`，按类名去重）安装：

| 方法 | 结果 |
|---|---|
| `checkSupport(BluetoothDevice)` | `fakeSupport()` |
| `getDeviceInfo()` | `fakeSupport()` |
| `isSupportAudioSwitch()` | `"1"`（候选名 `isSupportAudioSwitch` / `mo19775z1` / `z1`） |
| `isMiTWS()` / `checkIsMiTWS()` | `true`（候选 `isMiTWS`/`mo19771O0`/`O0`，`checkIsMiTWS`/`mo19766B`/`B`） |
| `getRingFindState()` | `false` |
| `setCommonCommand(int, String, BluetoothDevice)` | 102→`"1"`，123→`"4"`，else `"1"` |
| `connect(…)` / `getDeviceConfig(…)` / `getCommonConfig(…)` | `result=null` + `sendRealStatus()` + 350ms 延迟再刷 |
| `changeAncMode(int, device)` | `oppoAncFromMiuiMode`：1→2(降噪) 2→3(通透) else 1(关) |
| `changeAncLevel(String, device)` | `sendOppoAncLevel`：`"0103"→5 "0101"→6 "0100"→7 "0102"→8 "01"→7 "02"→3` |
| `register(IMiuiHeadsetCallback)` / `registerCallbackDevice(cb, device)` | 记住 callback（`callbacks[cb.asBinder()]`）并吞掉，主动 `sendRealStatus`，350ms 后补一次 |

**回调反推**（这是让原生页"活"起来的机制）：

```kotlin
// :638-655
private fun sendRealStatus(address: String, reason: String) {
    val payload = RfcommController.miuiRefreshPayload(battery, anc, transparencyVocalEnhancement)
    handler.post { callbacks.values.forEach { invoke(it, "refreshStatus", address, payload) } }
}
```

**通知层替换**（`:46-124`）：

```kotlin
hookBefore(MiuiBluetoothNotificationApi.showNewConnectedToast(Int,Int,Int,Int,BluetoothDevice,String)) {
    if (isOppoPod(args[4])) {
        val b = effectiveBattery()
        args[1] = displayBattery(b.left); args[2] = displayBattery(b.right)
        args[3] = displayWearState(b, args[3])          // 双耳1 右2 左3
        result = null                                    // 吞掉官方 toast
        callMethod(currentMiuiBluetoothNotification(), "showConnectedToast", 2, left, right, wear, …)
    }
}
hookBefore(MiuiBluetoothNotification.invokeStatusBar(Context, String, Bundle)) {
    // notifyId == "headset_wear_notification" 时按 islandMode 决定吞掉或改写
}
hookAfter(MiuiBluetoothNotification.updateParameters(C4705R2)) {
    // 混淆字段：f18110e=BluetoothDevice  f18107b=左  f18108c=右  f18109d=wear
}
```

### 2.4 `hook/MiBluetoothToastHook.kt:32-367`（**超级岛核心**）

scope `com.xiaomi.bluetooth`。`Log` 前缀 `OppoPods`。`if (!ConfigManager.focusNotification()) return`（`:43-46`）——总开关。

- **建通知** `createPodsNotification(device, context, batteryParams)`（`:55-239`）：
  - 从 `com.xiaomi.bluetooth` 资源取字符串 ID：`miheadset_notification_Box/LeftEar/RightEar/Disconnect`，取色 `system_notification_accent_color`（`android` 包）。
  - `NotificationChannel("BTHeadset$address", alias, IMPORTANCE_DEFAULT)` + `setSound(null,null)` + `setAllowBubbles(true)`。
  - `Bundle.putParcelable("Device", device)` → `Intent("com.android.bluetooth.headset.notification").putExtra("btData", bundle).putExtra("disconnect","1").setIdentifier("BTHeadset$address")`。
  - 断开 Action：`Notification.Action(285737079, disconnectLabel, PendingIntent.getBroadcast(...,201326592))`；ANC 循环 Action：`Intent(ACTION_CYCLE_ANC).setPackage("com.android.bluetooth")`。
  - 图标：`PodImageLoader.loadBoxBitmap(...) ?: moduleContext.resources.getDrawable(R.drawable.img_box)`，其中 `moduleContext = context.createPackageContext("moe.chenxy.oppopods", CONTEXT_IGNORE_SECURITY)`（**跨进程读模块资源唯一可靠方式**）。
  - 点击：`Intent("chen.action.oppopods.show_pods_ui").setClassName("moe.chenxy.oppopods","moe.chenxy.oppopods.PopupActivity")`。
- **focus-api 调用**（`:135-199`，见 §6.1）。
- **AOD 注入**（`:200-216`）：解析 `focusExtras.getString("miui.focus.param")` → `pv2.put("aodTitle","L x% | R y%")`、`pv2.put("aodPic","key_headset")` → 写回。
- **发送**：`notifyAsUser("BTHeadset$address", 10003, notification, SystemApisUtils.getUserAllUserHandle())`；ID 固定 `10003`。
- **接收器**（`:254-331`）四个自定义 action + `ACTION_PODS_ANC_CHANGED` + `ACTION_CYCLE_ANC`：
  - `chen.action.oppopods.sendstrongtoast` → 按 `islandMode` 走 `FocusIslandUtil.showBatteryIsland`（模块）或 `MiuiStrongToastUtil.showOfficialConnectToast`（官方）
  - `chen.action.oppopods.updatepodsnotification` / `cancelpodsnotification`

### 2.5 `hook/milink/MiLinkServiceHook.kt:28-690`（**融合设备中心**）

scope `com.milink.service`。

| 行 | 目标 | 语义 |
|---|---|---|
| `:54-65` | `com.xiaomi.mxbluetoothsdk.service.MxBluetoothService#getInstanceForIsMiTWS(Context)`、`…manager.MxBluetoothManager#…` | `hookBefore` 抓 `Context` 注册接收器 |
| `:67-90` | 同上两类的 `checkIsMiTWS/getDeviceId/getBatteryLevel/getAncState/getDeviceRunInfo/getWearStatus/isLeAudio/openAnc/closeAnc/openTransparent/isMiTWS/isSupportAudioSwitch/getRingFindState` | **逐个返回伪造值**（`getDeviceId→fakeDeviceId()`，`openAnc→1`，`getWearStatus→"0,0"`，`isLeAudio→false`） |
| `:92-113` | `com.miui.headset.runtime.ProfileContext`、`…AncBatteryController`、`com.miui.headset.api.HeadsetInfo`（Kotlin data class 的 `componentN()` 也要 hook） | 数据源替换 |
| `:115-128` | `hookAfter method(BluetoothDevice)` | 缓存 `runtimeOwner`，`notifyHeadsetPropertyChanged(instance, device, 4)` |
| `:155-176` | `AncBatteryController#setAncStateBlock(BluetoothDevice, Int)` | `hookBefore` → 推 `ACTION_ANC_SELECT`，`result = miLinkAncState()`，并 `notifyHeadsetPropertyChanged(…,8)(…,4)` |
| `:457-489` | `AncBatteryController#setFindRing(BluetoothDevice, Int)` | 借"查找耳机"卡位做**游戏模式开关**：`result=100`，`sendOppoGameMode`，`notifyHeadsetPropertyChanged(…,10)`（`:459-470`） |
| `:472-489` | `com.miui.circulate.world.sticker.ui.SynergyView#setTitle(Int)`（兜底混淆名 `…sticker.p067ui.SynergyView`） | 资源 entry name == `mi_audio_ringing_view` 且 source res 是 `circulate_headset_control_audio_find_earphone`/`…stop_find_earphone` → 改写成「游戏模式」卡片：`setSynergyTitle`/`setSynergySubtitle`/`setSynergyIcon`；子 View 通过 `resources.getIdentifier(name,"id",package)` 找 `item_title`/`item_subtitle`/`item_icon` |

**属性变更通知（唯一让米链刷新 UI 的通道）** `:602-608`：

```kotlin
private fun notifyHeadsetPropertyChanged(controller: Any, device: BluetoothDevice, updateType: Int) {
    getObjectField(controller, "headsetPropertyChangeListener").invoke(device, updateType)
}
// updateType 观测值（实测）：4=电量 / 8=ANC / 9=空间音频 / 10=游戏模式
// 调用点：:124(4) :171-172(8,4) :204/:233(10) :467(10) :580-581(9,4)
```

**空间音频映射** `:387-430`：

```
miLinkSpatialMode():  HEAD_TRACKING → (deviceSpatialType==1 ? 11 : 9)   FIXED → 1   OFF → 0
oppoSpatialFromMiLink: 9|11 → HEAD_TRACKING   1 → FIXED
miLinkGameModeState(): panel 关闭 → -1 ; currentGameMode ? 103 : 0
miLinkBatteryLevels(): [box, left, right, caseCharging, leftCharging, rightCharging]
```

**面板开关**：`spatialAudioPanelEnabled() = MILINK_CARD_SPATIAL_AUDIO in milinkCardFeatures() && capabilities.spatialAudioSupported`；`gameModePanelEnabled()` 同理。

### 2.6 `hook/milink/MiLinkSpatialAudioHook.kt:11-161`

`internal class MiLinkSpatialAudioHook(private val hook: MiLinkServiceHook)`。集中处理空间音频的**读写、模型字段、回调**：

```
hookMxBluetoothRuntime()  getSpatialMode → miLinkSpatialMode()
hookSpatialCommand()      setSpatialMode(BluetoothDevice, Int) → oppoSpatialFromMiLink
hookHeadsetRuntimeDisplay():
    AncBatteryController.getMiAudioEffect            → miLinkSpatialMode
    AncBatteryController.setMiAudioEffect(device,int) / setHeadTracking(device) → result = 100
    AncBatteryModel.get/setDeviceSpatialType         → 强制
    "AncBatteryController$mmaCallback$1".onDeviceSpatialType/.onReportSpatialState → 吞 + notifySpatialUiChanged
    ProfileContext.getAudioSpatialEffectState        → miLinkSpatialMode
    ProfileContext.setAudioEffectState(device,String,Int) → 拦截
    HeadsetInfo.getAudioEffectState/component10      → miLinkAudioEffectState
hookCirculateHeadsetServiceInfo():
    after "com.miui.circulate.api.service.CirculateServiceInfo".setHeadsetId(String,Int)
    → 若面板关闭，向 serviceProperties Bundle 塞 "headset_switch_state"=0（隐藏空间音频卡）
```

### 2.7 `hook/leaudio/*`（**协议层"骗过 Android 栈"**）

#### `hook/leaudio/OppoLeAudioHook.kt:18-119`

OPPO 私有 HFP AT 握手。Android 的 LE Audio 判定依赖耳机自报的 AT 命令，OPPO 固件报的是自家格式，HyperOS 不认 → 模块在 HFP 状态机里**代答**。

```kotlin
// OppoLeAudioHook.kt:45-81
atString = findMethod("com.android.bluetooth.hfp.HeadsetNativeInterface", "atResponseString",
                      BluetoothDevice::class.java, String::class.java)      // @hide, 需反射
atCode   = findMethod(..., "atResponseCode", BluetoothDevice, Int, Int)

hookBefore(findMethod("com.android.bluetooth.hfp.HeadsetStateMachine", "processUnknownAt",
                      String::class.java, BluetoothDevice::class.java)) {
    val config = ConfigManager.current().leAudio
    if (!config.enabled || !config.respondAt) return@hookBefore
    val device = args[1] as? BluetoothDevice ?: return@hookBefore
    if (!connection.isTarget(device)) return@hookBefore
    connection.rememberClassic(device)
    val command = (args[0] as? String)?.trim()?.uppercase(Locale.ROOT)?.replace(" ", "")?.removePrefix("AT")
    val name = command.takeWhile { it != '=' && it != ':' && it != '?' }     // 取命令名
    val reply = when (name) {
        "+VDID" -> "+VDID: ${config.vendorId}"                              // 默认 "1946"
        "+VDSF" -> "+VDSF: 7"
        "+VDSP" -> "+VDSP=1,1"
        "+OESF" -> {
            val values = command.substringAfter('=', "").split(',')
            val key = values.getOrNull(0)?.toIntOrNull()
            val requested = values.getOrNull(1)?.toIntOrNull()
            if (key == null || requested == null) "+OESF=0,0"
            else "+OESF=$key,${requested and (config.parsedMask() ?: 0x3F)}"
        }
        else -> return@hookBefore                                          // 非自家命令，放行
    }
    val native = getObjectField(instance, "mNativeInterface") ?: return@hookBefore
    if (send(native, device, reply, acknowledge = true)) result = null      // send() 内部走 atString/atCode
}
// :83-100
hookBefore(HeadsetStateMachine$HeadsetStateBase#exit) { handler.removeCallbacksAndMessages(getObjectField(instance,"this\$0")) }
hookAfter (HeadsetStateMachine$Connected#enter)      { postAtTime(+500L){ send("+VDSP=1,1", acknowledge=false) } }
```
> 注意 `getObjectField(instance, "this\$0")` —— 内部类持有外部 `HeadsetStateMachine` 的方式；`$` 在 Kotlin 字符串里要转义。

#### `hook/leaudio/LeAudioConnectionHook.kt:27-504`

**504 行全是"让 LE Audio 组连接稳定"的胶水**。核心数据结构（`:28-47`）—— 13 个 `ConcurrentHashMap.newKeySet<String>()` 状态集：

```
buds, userOff, paused, poked, starved, startup, completed,
established, discoveryRetried, acl, held<String,BluetoothGatt>,
contexts<Int,Int>, knownGroups<String,Set<String>>
```

目标类常量（`:497-503`）：

```kotlin
private const val AS   = "com.android.bluetooth.btservice.AdapterService"
private const val A2DP = "com.android.bluetooth.a2dp.A2dpService"
private const val HFP  = "com.android.bluetooth.hfp.HeadsetService"
private const val LEA  = "com.android.bluetooth.le_audio.LeAudioService"
private const val NI   = "com.android.bluetooth.le_audio.LeAudioNativeInterface"
```

`onHook()`（`:54-192`）装载的关键钩子：

| 目标 | 处理 |
|---|---|
| `AdapterService#onCreate` | `captureAdapter(instance)` 抓 adapter 并恢复 `oppopods_le_audio_buds` |
| `AdapterService#connectAllEnabledProfiles(device)` | 用户显式请求 → `resume()`；否则若 paused → `result=0`；`repairClassic(device)` |
| `AdapterService#disconnectAllEnabledProfiles(device,int)` | 记 `paused`，主线程 `stopGroup(members)` |
| `A2dpService/HfpService/LeAudioService#setConnectionPolicy(device,int)` | **区分"用户写的 FORBIDDEN"与"内部写的 FORBIDDEN"**：用 `Throwable().stackTrace.any{ it.className == LEA && it.methodName == "setConnectionPolicy" }` 判定来源；内部写 → `result=true`（静默阻断） |
| `A2dpService#okToConnect` / `HfpService#okToAcceptConnection` | `gate(param, hfp)` |
| `LeAudioService#connect` / `okToConnect` / `deviceConnected` / `deviceDisconnected` / `messageFromNative` | 见下 |
| `LeAudioNativeInterface#connectLeAudio` / `setEnableState` | paused → `result=false` |
| `AdapterService#onProfileServiceStateChanged` | `args[1]==12`（LE_AUDIO profile）→ `startupConnect()` |
| `RemoteDevices#aclStateChangeCallback(...)` 7 参 | ACL 上/下行 → `hold(device)` / `release(addr)` |
| `PhonePolicy#autoConnect` / `autoConnectLeAudio` | 开机自动重连 |

`messageFromNative`（`:135-162`）—— 直接读 `LeAudioStackEvent` 的 `type/valueInt1/valueInt2/valueInt5`：

```
type==4 (配置上报) && targetGroup(id):  mask=valueInt5; contexts[id]=mask; adopt(id); if(mask!=0) activate(id)
type==2 && valueInt2==1:                activate(id); applyGame()
type==1 (状态):  state==2 → established
                 state==0 → ACL 还在?(retryDiscovery):(release); complete(device)
```

`internalCall{}`（`:444-449`）用 `ThreadLocal<Boolean> moduleCall` 标记"这是模块自己发起的调用"，供 `explicitRequest()`（`:268`）区分用户操作与内部重连——**没有这个标记，模块会被自己的调用递归触发**。

### 2.8 Hook 基础设施 `hook/HookContext.kt:10-156`

```kotlin
abstract class HookContext {
    lateinit var module: XposedModule
    lateinit var appClassLoader: ClassLoader
    lateinit var prefs: SharedPreferences
    lateinit var packageName: String
    abstract fun onHook()
    fun fakeDeviceId() = ConfigManager.fakeDeviceId()
    fun fakeSupport()  = ConfigManager.fakeSupport()          // "${fakeDeviceId},000000000000000010000000"

    fun findClass(name: String) = Class.forName(name, false, appClassLoader)
    fun findMethod(cls: String, name: String, vararg params: Class<*>): Method
    fun findMethodByParamCount(cls: String, name: String, count: Int): Method
    fun hookAfter(m: Method, block: HookParam.() -> Unit) {
        module.hook(m).intercept { chain ->
            HookParam(chain, chain.proceed()).apply(block).result
        }
    }
    fun hookBefore(m: Method, block: HookParam.() -> Unit) {
        module.hook(m).intercept { chain ->
            val p = HookParam(chain, null)
            p.apply(block)
            if (p.hasResult) p.result else chain.proceed()
        }
    }
}

object Log {                      // :63-106 自建日志，受 logLevel 控制
    @Volatile var module: XposedModule? = null
    fun d(t: String, m: String) { if (ConfigManager.logLevel() >= LOG_LEVEL_DEBUG) module?.log(INFO, t, m) }
    ...
}

class HookParam(val chain: XposedInterface.Chain, initialResult: Any?) {   // :108-118
    val args: Array<Any?> = chain.args
    val instance: Any?     = chain.thisObject
    var hasResult: Boolean = false; private set
    var result: Any? = initialResult
        set(v) { field = v; hasResult = true }
}
```
`getObjectField/setObjectField/callMethod`（`:120-156`）**沿父类链向上查找**并 `isAccessible = true` —— 这是 hook 混淆类字段的唯一可行写法。

---

## §3 设备/协议层

### 3.1 `pods/RfcommController.kt:1-1330`（**核心中的核心**）

`object RfcommController`，`@SuppressLint("MissingPermission","StaticFieldLeak")`。

#### 3.1.1 常量

```kotlin
:44  private const val AUTO_RECONNECT_DELAY_MS  = 120_000L    // 断线后 2 分钟重连
:45  private const val APP_UI_ACTIVE_TIMEOUT_MS = 75_000L     // 前台活跃窗口
:104 private val OPPO_RFCOMM_UUID = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")
:497-499 createRfcommSocket(device) = device.createRfcommSocketToServiceRecord(OPPO_RFCOMM_UUID)
```

#### 3.1.2 连接生命周期

```
connectPod(ctx, device, prefs, appRequested)                        :501-554
 ├─ cancel connectionJob / reconnectJob / readerJob               (:502-506)
 ├─ closeSocketOnly()                                             (:508)
 ├─ mContext/mDevice/mPrefs/cachedDeviceName = ...                (:512-520)
 ├─ currentProductId = null; gameModeFeatureId = ...              (:521-523)
 ├─ ConfigManager.refreshFromPrefs()                               (:527)
 ├─ ctx.registerReceiver(broadcastReceiver, IntentFilter(18 actions), RECEIVER_EXPORTED)
 ├─ MediaControl.mContext; mediaRouter = MediaRouter2.getInstance   (:537-540)
 ├─ startRoutesScan()                                              (:541)
 ├─ isConnected = true; changeUIConnectionState("connecting")       (:542-543)
 └─ connectRfcomm(initialDelayMs = 500L)                           (:554)

connectRfcomm(initialDelayMs)                                       :628-662   (Dispatchers.IO)
 ├─ delay(initialDelayMs)
 ├─ guard: !isConnected || !::mDevice.isInitialized → return
 ├─ closeSocketOnly()
 ├─ newSocket = createRfcommSocket(mDevice); newSocket.connect()
 ├─ socket = newSocket; reconnectAttempts.set(0); reconnectPending = false
 ├─ startPacketReader(newSocket.inputStream)
 ├─ delay(300); sendPacketSafe(buildQueryProductId())              // 先问型号
 ├─ delay(50);  sendPacketSafe(Enums.QUERY_NOTIFICATION_SUPPORT)
 ├─ delay(50);  sendStatusQueryPackets(false)                      // 状态四连
 └─ scheduleAutoGameModeOnConnect(waitForProductId = true)
     catch (IOException) → changeUIConnectionState("error"); scheduleReconnect("connect failed")

scheduleReconnect(reason, immediate=false)                          :664-693
 ├─ guard: isConnected && ::mDevice.isInitialized && mContext != null
 ├─ RfcommLog.w("RFCOMM/reconnect", reason)
 ├─ closeSocketOnly(); reconnectPending = true
 ├─ immediate: (connectionJob 活跃则跳过) 立即 connectRfcomm()
 └─ 否则: reconnectJob 已活跃则返回; attempt = reconnectAttempts.incrementAndGet()
         launch { delay(AUTO_RECONNECT_DELAY_MS); connectRfcomm() }

reconnectNowForRequest(reason)  :695-698   // socket 在且无 pending 就直接返回，否则立即重连（发命令前调用）
closeSocketOnly()               :700-707   // readerJob.cancel(); socket.close(); socket = null
```

#### 3.1.3 重试语义（复制时容易漏）

- **两套重试**：`connectRfcomm` 的首次 `delay(initialDelayMs)`（100/500ms 抖动）与 `scheduleReconnect` 的 **120s 固定退避**（`:686-688`）。没有指数退避，没有次数上限。
- **`reconnectPending`** 是状态机锁：`true` 时 `reconnectNowForRequest`（`:695-698`）会**立刻**重连（用户主动操作优先于退避）。
- **失败必回调 UI**：`changeUIConnectionState("error")` 广播 `ACTION_PODS_CONNECTION_STATE_CHANGED`，UI 用它显示 `InfiniteProgressIndicator` / 错误弹窗。
- **断开入口**：`disconnectedPod(context, device)`（`:936`）—— 清 socket、`isConnected=false`、停 `MediaRouter2` 扫描、注销接收器。

#### 3.1.4 写队列

**没有写队列**。`sendPacketSafe`（`:979-995`）直接在 IO 协程内同步写：

```kotlin
fun sendPacketSafe(packet: ByteArray, requestReason: String? = null) {
    if (requestReason != null) reconnectNowForRequest(requestReason)
    val os = outputStream ?: run { RfcommLog.w(ctx,"RFCOMM/TX","socket null"); scheduleReconnect("socket null before send"); return }
    try { os.write(packet); os.flush() }
    catch (e: IOException) { scheduleReconnect("send error") }
}
```
命令间靠**调用方 `delay(50)` 硬编码间隔**（`sendStatusQueryPackets` 每个 50ms）。移植时建议改成 Channel + 串行化 writer，但**协议侧时序要求必须保留（≥50ms/包）**。

#### 3.1.5 读循环（**无帧重组**）

```kotlin
// :709-735
private fun startPacketReader(input: InputStream) = scope.launch(Dispatchers.IO) {
    val buffer = ByteArray(1024)
    while (isConnected) {
        val n = input.read(buffer)
        if (n > 0) {
            val packet = buffer.copyOfRange(0, n)     // ← 一个 read() chunk = 一个完整帧
            RfcommLog.d(ctx, "RFCOMM/RX", packet.toHexString())
            handleOppoPacket(packet)
        } else if (n == -1) { scheduleReconnect("stream ended"); break }
    }
}   // catch IOException → scheduleReconnect("read error")
```

> **风险与对策**：OPPO 耳机在单次 SPP 传输里通常一帧一包，故能工作；但 `0x0204` 主动上报、EQ 详情这类长包有被 TCP 式分片的风险。**新模块必须在 §3.2 的 header 里校验 `packet[1] + 2 == packet.size`，不足则继续 read 拼接**，这是本仓唯一的架构缺陷。

#### 3.1.6 收包分发（`handleOppoPacket` `:737-934`）

严格顺序（先匹配的消费，后续不再执行）：

```
1  ProductIdParser.parse            → currentProductId 更新 → 能力重算
2  NotificationSupportParser.parse  → 对 (id and 0xFF) < 0xF0 的 id 回 REGISTER_MULTI_NOTIFICATION
3  SmartAncLevelParser.parse
4  BatteryParser.parse              (cmd 0x8106)
5  BatteryParser.parseActiveReport  (cmd 0x0204, reportType 0x01)
6  WearStatusParser.parse
7  SpatialAudioParser.parseModeNotify
8  parseSetResponseStatus / parseSpatialSoundSwitchSetResponse
9  EqPresetParser.parse
10 EqDetailsParser.parseAll
11 AncModeParser.parse(packet, currentCapabilities().ancImplementation)
12 TransparencyVocalEnhancementParser.parse
13 GameModeParser.parseStatus
14 SwitchFeatureSetParser.parse
else → Log "Unknown OPPO packet"
```

#### 3.1.7 ANC 内外两套整数（**移植必踩的坑**）

```
RfcommController 内部序（广播 ACTION_ANC_SELECT / UI 状态）：
   1=OFF  2=NOISE_CANCELLATION  3=TRANSPARENCY  4=ADAPTIVE
   5=NC_SMART  6=NC_LIGHT  7=NC_MEDIUM  8=NC_DEEP          (:852-861)

Packets.AncMode 线上原始值（payload 字节）：
   OFF=0x01  NC=0x02  SMART=0x80  LIGHT=0x40  MEDIUM=0x20  DEEP=0x10
   TRANSPARENCY=0x04  ADAPTIVE_HIGH=0x00  ADAPTIVE_LOW=0x08   (:105-116)

MIUI 侧（Settings/上游 Binder）：
   整体模式 0=关 1=降噪 2=通透；等级字符串 "0100".."0201"
```
`setANCMode`（`:1168-1205`）：`ancImplementation == COMPATIBLE` 时 **1↔2 互换**，发完 `delay(350)` 再 `sendStatusQueryPackets(false)` 回读确认。

`cycleAnc()`（`:1147-1166`）：`adaptiveSupported ? [2,4,3,1] : [2,3,1]`。

`queryStatus(immediateReconnect)`（`:1206-1210`）→ `sendStatusQueryPackets`（`:1212-1225`）：`QUERY_STATUS`,50ms,`QUERY_BATTERY`,50ms,`QUERY_ANC`,50ms,`QUERY_EQ`（`customEqSupported` 时再加 `QUERY_ALL_EQ`）。

#### 3.1.8 广播收发

- **18 个 action 的 IntentFilter** 在 `connectPod` 注册（`:530-536`）：`ACTION_PODS_UI_INIT/UI_CLOSED/ANC_SELECT/REFRESH_STATUS/GAME_MODE_SET/AUTO_GAME_MODE_CHANGED/TRANSPARENCY_VOCAL_ENHANCEMENT_SET/SPATIAL_AUDIO_SET/EQ_PRESET_SET/EQ_PRESET_SAVE/EQ_PRESET_DELETE/DUAL_DEVICE_CONNECTION_SET/CYCLE_ANC/CONFIG_CHANGED/RFCOMM_LOG_CONNECT/RFCOMM_LOG_DISCONNECT/RFCOMM_LOG_CLEAR/RFCOMM_DEBUG_SEND`。
- 处理入口 `handleUIEvent`（`:196-305`）。
- **对外广播必须带 `setPackage` + `FLAG_RECEIVER_FOREGROUND`**（`:338-347`），否则 Android 14+ 隐式广播被拦。`sendExternalPodsStatusBroadcast`（`:556-570`）对 `[com.milink.service, com.xiaomi.bluetooth, com.android.settings]` 逐个 `setPackage` 再发。
- **App 前台心跳**：`markAppUiActive()`（`:349-361`）记 `SystemClock.elapsedRealtime()`，`isAppUiActive()` 判 75s；只有活跃时才向外部广播（省电 + 避免后台骚扰）。

#### 3.1.9 电量/岛 payload 生成（`：364-394`）

```kotlin
fun miuiRefreshPayload(battery, anc, transparencyVocalEnhancement): String {
    val p = MutableList(16) { "" }
    p[0] = miuiBatteryValue(battery.left)    // 左
    p[1] = miuiBatteryValue(battery.right)   // 右
    p[2] = miuiBatteryValue(battery.case)    // 仓
    p[7] = miuiAncLevel(anc, transparencyVocalEnhancement)
    p[8] = "true"
    p[11] = "00"; p[13] = "00"; p[14] = "00"
    return p.joinToString(",")
}
// 未连接 → "255"; 否则 (充电 ? level or 128 : level)
```

`setRegularBatteryLevel`（`:1292-1304`）—— 反射写 `BluetoothDevice#setBatteryLevel(int)`，失败再写 `mAdapterService`/`mService.setBatteryLevel(device, level, false)`，用于让**系统状态栏**也显示电量。

`disconnectAudio/connectAudio`（`:1227-1290`）：反射 `BluetoothProfile.HEADSET` 代理 `connect/disconnect` + `MediaRouter2.transferTo(...)` + `statusBarManager.setIconVisibility("wireless_headset", true)`。

### 3.2 `pods/Packets.kt:1-971`（帧格式与字节布局）

#### 3.2.1 帧结构（文件头注释 `:3-8`）

```
+--------+-----------+--------+------------+--------+-----------+-------------+
| 0xAA   | TotalLen  | 00 00  | Cmd (LE16) | Seq(1) | PayLen(LE16)| Payload     |
| [0]    | [1]       | [2..3] | [4..5]     | [6]    | [7..8]      | [9..]       |
+--------+-----------+--------+------------+--------+-----------+-------------+
TotalLen = 7 + PayLen            → 整帧字节数 = 2 + TotalLen
Payload 偏移 = 9；Seq 默认 0xF0
```

```kotlin
// :13-29
fun buildPacket(cmd: Int, seq: Int = 0xF0, payload: ByteArray = byteArrayOf()): ByteArray {
    val totalLen = 7 + payload.size
    val packet = ByteArray(2 + totalLen)
    packet[0] = 0xAA.toByte()
    packet[1] = totalLen.toByte()
    packet[4] = (cmd and 0xFF).toByte(); packet[5] = ((cmd shr 8) and 0xFF).toByte()
    packet[6] = seq.toByte()
    packet[7] = (payload.size and 0xFF).toByte(); packet[8] = ((payload.size shr 8) and 0xFF).toByte()
    payload.copyInto(packet, 9)
    return packet
}
```

#### 3.2.2 命令字 `Cmd`（`:196-247`）

| 常量 | 值 | 方向 |
|---|---|---|
| `QUERY_PRODUCT_ID` / `PRODUCT_ID_RESPONSE` | `0x0103` / `0x8103` | 问/答型号 |
| `QUERY_EQ_ALL` | `0x0122` | 全量 EQ |
| `SET_EQ_DETAIL` | `0x0418` | 写 EQ 条 |
| `SET_ANC` | `0x0404` | ANC 设置 |
| `SET_GAME_MODE` | `0x0403` | 游戏模式 |
| `SET_SPATIAL_AUDIO` | `0x0422` | 空间音频 |
| `QUERY_BATTERY` / `BATTERY_RESPONSE` | `0x0106` / `0x8106` | |
| `QUERY_ANC_MODE` / `ANC_MODE_RESPONSE` / `ANC_MODE_NOTIFY` | `0x010C` / `0x810C` / `0x0204` | |
| `QUERY_STATUS` / `QUERY_STATUS_RESPONSE` | `0x010D` / `0x810D` | |
| `SET_GAME_MODE_RESPONSE` | `0x8403` | |
| `SET_SPATIAL_AUDIO_RESPONSE` / `SET_SPATIAL_SOUND_SWITCH_RESPONSE` | `0x8422` / `0x8403` | |
| `SPATIAL_AUDIO_NOTIFY` | `0x0510` | |
| `SET_EQ` | `0x0406` | |
| `QUERY_EQ_PRESET` / `EQ_PRESET_RESPONSE` / `EQ_PRESET_NOTIFY` | `0x010F` / `0x810F` / `0x0504` | |
| `QUERY_NOTIFICATION_SUPPORT` / `NOTIFICATION_SUPPORT_RESPONSE` | `0x0200` / `0x8200` | |
| `REGISTER_MULTI_NOTIFICATION` | `0x0205` | |

#### 3.2.3 预置帧

```
QUERY_BATTERY  = AA 07 00 00 06 01 F0 00 00                                  (:361-363 硬编码)
QUERY_STATUS   = AA 13 00 00 0D 01 00 0C 00
                 0B 05 04 0B 11 13 18 06 1B 1C 27 28                          (:442-445 硬编码)
QUERY_ANC      = buildPacket(Cmd.QUERY_ANC_MODE, payload = [0x01, 0x01])      (:379-381)
QUERY_EQ       = buildPacket(Cmd.QUERY_EQ_PRESET, payload = byteArrayOf())    (:421-423)
QUERY_NOTIFICATION_SUPPORT = buildPacket(0x0200, payload = byteArrayOf())     (:366-368)
QUERY_EQ_ALL   = buildPacket(0x0122, payload = byteArrayOf(0x01, 0x05))       (:33-34)
QUERY_PRODUCT_ID = buildPacket(0x0103)                                        (:31)
```
后两个 `QUERY_BATTERY`/`QUERY_STATUS` 是**直接写字面量的 9/21 字节数组**（注释里也贴了 hex），而不是 `buildPacket` —— 因为它们需要精确的顺序与不需要可读性。移植时照抄字节，别"重构"成 builder。

#### 3.2.4 payload 构造器

```kotlin
fun registerMultiNotification(ids: ByteArray) = buildPacket(0x0205, 0xF0, byteArrayOf(ids.size.toByte()) + ids)  // :371-376
fun gameModePacket(enabled: Boolean, featureId: Int): ByteArray      // :403
fun spatialAudioPacket(mode: Int): ByteArray                          // :409
fun eqPresetPacket(presetId: Int): ByteArray                          // :415
fun spatialSoundSwitchPacket(enabled: Boolean): ByteArray             // :426
fun dualDeviceConnectionPacket(enabled: Boolean): ByteArray           // :432
fun buildSaveEqualizer(id, name, frequencies, gains, minValue=-6, maxValue=6)   // :36-54
fun buildDeleteEqualizer(entry)                                       // :56
fun buildEqDetailPayload(...)                                         // :69-98
```

**EQ 详情 payload 字节布局**（`:69-98`）：

```
[0]    action      1=新增 2=修改 3=删除
[1]    minValue    (signed, 默认 -6)
[2]    maxValue    (默认 6)
[3]    id          预设 id（新增时 0）
[4]    nameLen     UTF-8 字节数（≤255）
[5..]  name        UTF-8
[5+nameLen]        freqCount（≤32）
之后每段 3 字节 × freqCount: { Freq LE16, Gain i8 }
```

#### 3.2.5 解析器

```kotlin
object BatteryParser {                       // :456-566
    fun parse(packet: ByteArray): BatteryResult?          // cmd 0x8106，payload 成对 [Index(1B), Raw(1B)]
    fun parseActiveReport(packet: ByteArray): BatteryResult?  // cmd 0x0204, payload=[reportType][count][idx,raw]*
}
// level = raw and 0x7F ; charging = (raw and 0x80) != 0
// Index: 1=LEFT 2=RIGHT 3=CASE
data class BatteryInfo(val level: Int, val isCharging: Boolean)
data class BatteryResult(val left: BatteryInfo?, val right: BatteryInfo?, val case: BatteryInfo?)
```

其余解析器（同文件）：`ProductIdParser:290`、`EqDetailsParser.parseAll:249-288`（`data[10]` 为 count，条目 `selected,min,max,id,nameLen,name,freqCount,{freq LE16,gain}×n`）、`SpatialAudioParser:568-610`、`EqPresetParser:612`、`WearStatusParser:633-681`、`AncModeParser.parse(data, implementation, default=STANDARD):683`、`TransparencyVocalEnhancementParser:745`、`GameModeParser.parseStatus:780`、`SwitchFeatureSetParser:852`、`NotificationSupportParser:910`、`SmartAncLevelParser:939`（位图扫描）。

### 3.3 `pods/DeviceModelRegistry.kt:9-197`

```kotlin
object DeviceModelRegistry {                          // pods/DeviceModelRegistry.kt:9-197
    const val ASSET_NAME = "device_models.json"               // :10
    const val EQ_MODE_NAMES_ASSET_NAME = "eq_mode_names.json"     // :11
    const val EQ_MODE_NAMES_EN_ASSET_NAME = "eq_mode_names.en.json" // :12
    @Volatile private var entries: List<JSONObject> = emptyList()   // :14-15
    @Volatile private var byId: Map<String, JSONObject> = emptyMap() // :17-18
    @Volatile private var eqModeNames: Map<Int, String> = emptyMap() // :20-21

    @Synchronized fun ensureLoaded(context: Context)          // :23-53  ★跨进程关键
    fun byProductId(context: Context, productId: String?): ModelCapabilities?  // :55
    fun byDeviceName(context: Context, deviceName: String?): ModelCapabilities? // :61
    private fun parse(entry: JSONObject): ModelCapabilities   // :74
    private fun parseEqPresets(function: JSONObject?): List<EqPresetInfo>      // :105
}
data class ModelCapabilities(                             // :183-197，注意与 DeviceCapabilities 字段几乎一一对应
    modelId, modelName, adaptiveSupported, spatialAudioSupported, spatialSoundSwitchSupported,
    legacyAnc, dualDeviceSupported, customEqSupported, eqPresets, customEqFrequencies,
    customEqMaxPresets, gameModeFeatureId, gameModeSupported
)
```

**★ `ensureLoaded` 的跨进程 hack**（`:24-36`，唯一正确写法）：

```kotlin
if (entries.isNotEmpty()) return                       // 幂等：已加载就直接返回
val ownContext = if (context.packageName == BuildConfig.APPLICATION_ID) context
                 else runCatching {
                     context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
                 }.getOrDefault(context)               // 失败回落，不 crash
eqModeNames = loadEqModeNames(ownContext)
val text = ownContext.assets.open(ASSET_NAME).use { it.readBytes().toString(Charsets.UTF_8) }
val array = JSONObject(text).optJSONArray("whiteList") ?: JSONArray()
entries = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
byId = buildMap { for (entry in list) { val id = entry.optString("id").uppercase()...; 
    if (existing != null && existing.has("function") && !entry.has("function")) continue;  // 有 function 的条目优先
    put(id, entry) } }
// 在 com.android.bluetooth / com.milink.service 里，context.assets 没有本模块的 assets，
// 必须重建本模块的 Context 才能 AssetManager.open("device_models.json")
```

**JSON 结构**：`{"whiteList":[ {id, productId, name, modeType, protocolIndex, childrenMode, equalizerMode, equalizerModeCompat, equalizerModeByVersion, ...}, ... ]}`，`byId` 用 `id.uppercase()` 建 map；`byDeviceName` 先规范化（`:180` `name.lowercase().filter { it.isLetterOrDigit() }`）。

**能力推导**（`parse` `:74-104`）：

```
adaptiveSupported            = modeType in {6, 10}
spatialSoundSwitchSupported  = spatialTypes 非空 && 不含 HEAD_TRACKING
legacyAnc                    = childrenMode == null && modeType == 5 && protocolIndex == 0
gameModeFeatureId            = hasGameSound(type != 0) ? MAIN : LOW_LATENCY
eqPresets                    = equalizerMode / equalizerModeCompat / equalizerModeByVersion
                               以 protocolIndex 为 id，eqModeNames[modeType] 为名称
```

### 3.4 `pods/DeviceCapabilities.kt:1-67`

```kotlin
@Serializable
data class DeviceCapabilities(
    val adaptiveSupported: Boolean = false,
    val spatialAudioSupported: Boolean = false,
    val spatialSoundSwitchSupported: Boolean = false,
    val ancImplementation: AncImplementation = AncImplementation.STANDARD,
    val dualDeviceSupported: Boolean = false,
    val customEqSupported: Boolean = false,
    val eqPresets: List<EqPresetInfo> = emptyList(),
    val customEqFrequencies: List<Int> = emptyList(),
    val customEqMaxPresets: Int = 0,
    val gameModeFeatureId: Int = GameModeFeature.LOW_LATENCY,
    val gameModeSupported: Boolean = false,
)
@Serializable data class EqPresetInfo(val id: Int, val name: String, val modeType: Int = -1)
@Serializable data class EqDevicePreset(val id: Int, val name: String, val selected: Boolean = false,
                                        val minValue: Int = -6, val maxValue: Int = 6,
                                        val frequencies: List<Int>, val gains: List<Int>)
object EqDefaults { val FREQUENCIES = listOf(62, 250, 1000, 4000, 8000, 16000) }
fun detectDeviceCapabilities(context, deviceName, productId): DeviceCapabilities
```

---

## §4 UI 层

### 4.1 宿主与主题

| 文件 | 职责 |
|---|---|
| `MainActivity.kt:18-82` | `ComponentActivity`；`attachBaseContext` 先应用语言（`:19-23`）；`setContent` 里读 5 个偏好 → `enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT,TRANSPARENT){darkMode})` + `window.isNavigationBarContrastEnforced = false`（`:42-51`）→ `App(...)` |
| `OppoPodsApp.kt:9-51` | `Application` 实现 `XposedServiceHelper.OnServiceListener`；`onServiceBind(service){ xposedService = service }`；`companion` 里 `@Volatile var xposedService` + `CopyOnWriteArraySet<(XposedService?)->Unit>` 监听器集合（UI 用它判断作用域是否激活） |
| `ui/App.kt:13-48` | `ColorSchemeMode` 映射；`backStack = remember { mutableStateListOf<NavKey>(Screen.Main) }`；`AppLocale.Provider(appLanguage){ AppTheme(colorSchemeMode, accentMode){ MainUI(...) } }` |
| `ui/Theme.kt:13-46` | `accentMode==1` → `MonetLight/MonetDark/MonetSystem`（Miuix 动态取色）；非 Monet 时用 `CompositionLocalProvider(LocalConfiguration = ...)` 覆盖 `uiMode` 再 `MiuixTheme(controller, content)` |
| `ui/AppLocale.kt:16-89` | `SYSTEM=0/CHINESE=1/ENGLISH=2`；`rememberDeviceLocale` / `apply` / `applyAndRecreate` / `@Composable Provider(language, content)` —— **不重建 Activity 切语言** |

**安全区/状态栏处理**：不使用 `WindowInsets` 手工计算；`enableEdgeToEdge` + `Scaffold(contentPadding)` + 每页把 `contentPadding` 与 `pageBottomContentPadding` 传进 `LazyColumn.contentPadding`（底部 `if (overlayBottomBar) 104.dp else 28.dp`，`MainUI.kt:147-154`）。

### 4.2 导航骨架

```kotlin
// ui/MainUI.kt:84-98
@Serializable sealed interface Screen : NavKey {
    data object Main : Screen
    data object About : Screen
    data object Theme : Screen
    data object LeAudio : Screen
    data object Equalizer : Screen
    data object RfcommDebug : Screen
}
// :989-999
NavDisplay(backStack, onBack = { if (backStack.size > 1) backStack.removeLast() else (context as? Activity)?.finish() },
           content = navContent)
```
每个子页统一壳（`MainUI.kt:665-987`）：

```kotlin
Scaffold(topBar = TopAppBar(title, largeTitle, scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState()),
                            navigationIcon = { IconButton({ backStack.removeLast() }) { Icon(MiuixIcons.Back) } })
) { padding ->
    Box(Modifier.background(appBackground()).padding(padding)) {
        Page(Modifier.fillMaxSize().overScrollVertical().nestedScroll(behavior.nestedScrollConnection), contentPadding)
    }
}
```

### 4.3 页面清单

| 文件:行 | 符号 | 角色 |
|---|---|---|
| `ui/MainUI.kt:102-1050` | `MainUI` + `Screen` + 20+ 个命令函数 | 全局状态中枢：所有广播接收、所有指令下发、导航 |
| `ui/MainTabs.kt:63-656` | `MainTabsScaffold` / `ModuleTabPage:338` / `EarphonesTabShell:389` / `SettingsTabPage:495` / `LandscapeDetailActions:571` / `EarphoneDetailActions:626` | 三 Tab + `HorizontalPager` + 每 Tab 的 TopAppBar |
| `ui/MainTab.kt:11-22` | `enum MainTab(icon){Module=AppIcons.Home, Earphones=AppIcons.Headphones, Settings=MiuixIcons.Settings}` + `MainTab.title()` | Tab 定义 |
| `ui/MainBottomNavigation.kt:18-65` | `MainBottomNavigation` | `NavigationBar` / `FloatingNavigationBar`；`blur && backdrop != null` 时套 `Modifier.textureBlur(backdrop, RoundedCornerShape(50.dp|0.dp))`，`zIndex(2f)` |
| `ui/MainTabsPagerState.kt:29,60` | `animateToPage` / `syncPage` | Tab↔Pager 双向同步（3 个 `LaunchedEffect`） |
| `ui/pages/HomePage.kt:45-296` | `HomePage`、`StatusGrid:93`、`StatusCard:133`、`StatCard:207`、`InfoCard:237` | 状态总览：作用域是否激活 / 蓝牙开关 / 配对数量 / 系统与模块版本 |
| `ui/pages/EarphonesTabPage.kt:20-105` | `EarphonesTabPage` | `AnimatedContent(showEarphoneDetail)` 在 `PodDetailPage` 与 `DevicePickerPage` 间切换 |
| `ui/pages/PodDetailPage.kt:48-312` | `PodDetailPage` + `podControlItems:194` | 耳机详情主界面（见 §4.5） |
| `ui/pages/DevicePickerPage.kt:68-317` | `DevicePickerPage`、`DeviceRow:269` | 设备列表 / 手动 MAC 连接 / 权限申请 |
| `ui/pages/SettingsPage.kt:41-323` | `SettingsPage` | 设置总页（见 §4.6） |
| `ui/pages/ThemeSettingsPage.kt:18-79` | `ThemeSettingsPage` | 明暗/取色/悬浮底栏/毛玻璃 |
| `ui/pages/EqualizerPage.kt:47-396` | `EqualizerPage`、`EqCheckboxRow:149`、`CustomEqRow:186`、`AddEqualizerRow:222`、`EqualizerWindowDialog:252`、`DeleteEqualizerDialog:291`、`EqualizerSliders:325` | 内置/自定义 EQ，自定义条带滑杆 |
| `ui/pages/LeAudioSettingsPage.kt:29-213` | `LeAudioSettingsPage` | LE Audio 开关、设备白名单、AT 应答参数（`vendorId`/`oesfMask` 校验） |
| `ui/pages/RfcommDebugPage.kt:60-294` | `RfcommDebugPage` | 实时 `RFCOMM/RX|TX` 日志、HEX 手工发包 |
| `ui/pages/AboutPage.kt:28-77` | `AboutPage` | 上游项目链接 |

### 4.4 组件清单

| 文件:行 | 符号 | 角色 |
|---|---|---|
| `ui/components/AncSwitch.kt:50-369` | `AncSwitch` / `AncStrengthTabRow:173` / `ResponsiveAncTabRow:241` / `AncButton:275` / `themedPainterResource:336` | **降噪切换器（金标准）**，见 §4.7 |
| `ui/components/PodStatus.kt:44-240` | `PodStatus` / `BatteryColumn:94` / `WearStatusIcon:152` / `getBatteryIconRes:170` / `themedPainterResource:218` | 三段电量（左/右/仓），见 §4.8 |
| `ui/components/AppIcons.kt:11-109` | `object AppIcons { Home, Headphones, Contacts, RemoveContact, ... }` | 自绘 ImageVector（MIUI 没有对应图标） |
| `ui/dialogs/RestartScopeDialog.kt:32-119` | `data class RestartScope(packageName,label)` + `RestartScopeDialog` | 重启作用域多选（默认全选） |
| `ui/dialogs/PodImageConfigDialog.kt:49-213` | `PodImageConfigDialog(onSave: (String,String,Map<PodImageResource,Uri?>,Set<PodImageResource>)->Unit)` | 自定义耳机图片（盒/左/右），`ActivityResultContracts.GetContent()` |
| `ui/dialogs/MelodyImageImportDialog.kt:52-216` | `MelodyImageImportDialog` | 从欢律 App 沙盒导入图片（需 root，见 `RootManager.scanMelodyImageCandidates`） |
| `ui/dialogs/LeAudioDevicesDialog.kt:37-111` | `LeAudioDevicesDialog` | 选择参与 LE Audio 的已配对设备 |

### 4.5 `PodDetailPage` 参数与内容顺序

```kotlin
fun PodDetailPage(modifier, contentPadding, bottomContentPadding: Dp = 16.dp,
    podName: String, batteryParams: BatteryParams, wearStatus: WearStatus = WearStatus(),
    ancMode: NoiseControlMode, onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    transparencyVocalEnhancement: Boolean = false, onTransparencyVocalEnhancementChange: (Boolean) -> Unit = {},
    gameMode: Boolean = false, gameModeSupported: Boolean = false, onGameModeChange: (Boolean) -> Unit = {},
    spatialAudioMode: Int = ConfigManager.SPATIAL_AUDIO_OFF, onSpatialAudioModeChange: (Int) -> Unit = {},
    dualDeviceConnection: Boolean = false, onDualDeviceConnectionChange: (Boolean) -> Unit = {},
    spatialAudioSupported: Boolean = false, spatialSoundSupported: Boolean = false,
    adaptiveModeEnabled: Boolean = true, equalizerVisible: Boolean = false,
    dualDeviceSupported: Boolean = false, onOpenEqualizer: () -> Unit = {},
    boxImagePath: String? = null)
```

布局：`LocalConfiguration.orientation == LANDSCAPE` → `Row[ 左：Image(rememberPodImagePainter(boxImagePath), fillMaxWidth(0.82f), widthIn max 360.dp, ContentScale.FillWidth) + Text(podName,22.sp,Bold) | 右：LazyColumn{ podControlItems } ]`；竖屏 → 单 `LazyColumn`（图片 `fillMaxWidth(0.7f)`）。

`podControlItems` 顺序（`:194-`）：
1. `Card{ PodStatus(...) }`
2. `Card{ AncSwitch(...) }`
3. `Card{` **按能力动态增删**：`gameModeSupported → SwitchPreference(game_mode, game_mode_summary)`；`spatialAudioSupported → OverlayDropdownPreference(spatial_audio, items=[off, spatial_audio_fixed, spatial_audio_head_tracking], selectedIndex = spatialAudioValues.indexOf(mode))`；`spatialSoundSupported → SwitchPreference(spatial_sound, checked = mode != OFF, onCheckedChange → FIXED/OFF)`；`equalizerVisible → BasicComponent(eq_preset_title, eq_preset_summary, endActions = MiuixIcons.Basic.ArrowRight, onClick = onOpenEqualizer)`；`dualDeviceSupported → SwitchPreference(dual_device_connection)` `}`
4. `Spacer(bottomContentPadding)`

### 4.6 `SettingsPage` 结构

```
Card{ BasicComponent(主题与外观 → Screen.Theme, ArrowRight) }
Card{ OverlayDropdownPreference(语言)、OverlayDropdownPreference(日志级别)、SwitchPreference(隐藏桌面图标) }
Card{ SwitchPreference(焦点通知/超级岛)
      AnimatedVisibility(focusNotification) {                 // folmeSpring(damping=0.9f, response=0.3f) + fadeIn(tween(220, 40))
          OverlayDropdownPreference(通知点击行为)
          AnimatedVisibility(== MODULE_POPUP) { OverlayDropdownPreference(更多按钮行为) }
      }
      OverlayDropdownPreference(超级岛模式)                    // NONE / OFFICIAL / MODULE
      if (islandMode == MODULE) OverlayDropdownPreference(显示时机, 多选, collapseOnSelection = false)
      SwitchPreference(自动游戏模式)
      OverlayDropdownPreference(融合设备中心卡片, 多选)
      BasicComponent(伪造设备 ID) + TextField(trim 校验)
}
Card{ BasicComponent(LE Audio → Screen.LeAudio) }
Card{ BasicComponent(关于 → Screen.About, summary = "OppoPods-Enhanced") }
```

### 4.7 `AncSwitch.kt:50-369`（**降噪组件，逐参数复刻**）

```kotlin
@Composable
fun AncSwitch(
    ancStatus: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    compact: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: ((Boolean) -> Unit)? = null,
)
```

结构（`:59-170`）：

```
Column(Modifier.fillMaxWidth().padding(vertical = if(compact) 8.dp else 16.dp))
 ├─ Row(Modifier.fillMaxWidth(), verticalAlignment = CenterVertically)      // 无 spacedBy，靠 weight(1f) 均分
 │    ├ AncButton(ic_openanc_off / ic_openanc_on,      R.string.noise_cancellation_title,   NC)
 │    ├ if (adaptiveModeEnabled)  AncButton(ic_adaptive_off / ic_adaptive_on, R.string.adaptive_title, ADAPTIVE)
 │    ├ AncButton(ic_transparent_off / ic_transparent_on, R.string.transparency_title,       TRANSPARENCY)
 │    └ AncButton(ic_closeanc_off / ic_closeanc_on,    R.string.off,                        OFF)
 ├─ if (ancStatus.isNoiseCancellation())  AncStrengthTabRow(modes = [SMART, LIGHT, MEDIUM, DEEP])
 └─ if (ancStatus == TRANSPARENCY && onTransparencyVocalEnhancementChange != null)
        ResponsiveAncTabRow(tabs = [transparency_title, transparency_vocal_enhancement])
```

尺寸（`compact` = 弹窗/横屏场景）：`verticalPadding 16dp→8dp`、`tabMaxWidth 98dp→72dp`、`tabHeight 45dp→38dp`、`tabSpacing 9dp→4dp`、`tabOuterPadding 12dp→8dp`、`tabMinWidth 0.dp`（固定）、`AncButton.boxSize 60dp→40dp`。

`AncButton`（`:275-329`）动画细节（**观感的关键**）：

```kotlin
val boxSize  = if (compact) 40.dp else 60.dp
val iconSize = if (compact) (if (isSelected) 40.dp else 32.dp) else (if (isSelected) 60.dp else 48.dp)
val textColor by animateColorAsState(
    if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
    tween(ANIM_DURATION /* 300 */), label = "anc_text_color")

Column(Modifier.pressable(interactionSource, indication = SinkFeedback())
               .clickable(interactionSource, indication = null, onClick = onClick)) {
    Box(Modifier.size(boxSize), Center) {
        Crossfade(targetState = isSelected, tween(300), label = "anc_icon") { sel ->
            Image(themedPainterResource(if (sel) onIconRes else offIconRes),
                  modifier = Modifier.size(if (sel) boxSize else (if (compact) 32.dp else 48.dp)))
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(label, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Medium, color = textColor)
}
```

`AncStrengthTabRow`（`:173-238`）：`BoxWithConstraints` 内**叠两层** —— 底层 `TabRowWithContour(tabs = tabs.map { "" }, minWidth, maxWidth = tabMaxWidth, height, itemSpacing)` 只画底板与滑动指示条；上层 `Row(rowModifier.height(height))` 用 `weight(1f)` 排真实 `Text`（选中 `primary`，`assistHighlighted` = `primary.copy(alpha = 0.72f)` 且 `SemiBold`）。宽度：`fillMaxWidth(if (maxWidth >= 480.dp) 0.8f else 1f)`。
`assistHighlightedIndex` 由 `smartAncLevel` 推导（LIGHT→1/MEDIUM→2/DEEP→3），用于 **SMART 档位下让强度档视觉跟随耳机自动上报**（用户没点也会动）。
`ResponsiveAncTabRow`（`:241-272`）不需要 overlay，直接 `TabRowWithContour` 带真实文字。

`themedPainterResource:336-368`：因 `drawable-night-nodpi` 资源要跟随**应用内**主题（而非系统主题）切换：

```kotlin
val nightMode = LocalConfiguration.current.uiMode and UI_MODE_NIGHT_MASK
if (nightMode == themeNightMode) painterResource(id)
else createConfigurationContext(overriddenConfig).getDrawable(id)?.let {
    if (it is BitmapDrawable) BitmapPainter(it.bitmap.asImageBitmap())
    else BitmapPainter(renderToBitmap(it))       // 非 bitmap 先画到 Canvas
}
```

### 4.8 `PodStatus.kt:44-240`（电量组件）

```kotlin
@Composable fun PodStatus(batteryParams: BatteryParams, wearStatus: WearStatus = WearStatus(),
                          modifier: Modifier = Modifier, compact: Boolean = false)
```
- `Row`：`BatteryColumn(左) weight(1f)` | `Box(width 0.5.dp, height if(compact) 40.dp else 56.dp, color 0xFF333333(暗)/0xFFEEEEEE(亮))` | 右 | 分隔线 | 仓。
- `BatteryColumn`（`:94-151`）：`var lastKnownLevel by remember { mutableStateOf(100) }`（**断开后保留最后电量**）；`displayLevel = if (connected) "N%" else "-"`；标签 `paddedLabel` 用 `'\u3000'` 补到 3 字符，保证「左」「右」与「耳机盒」对齐；`Text(label,16.sp,Bold)` + `WearStatusIcon(18.dp 盒 / 14.dp 图标)` + `Text(level,13.sp,Gray)` + `Image(battery 24.dp)`。
- 图标分档（`:170-217`）：`≤10,≤20,…,≤90,else 10` → `R.drawable.charge_1..10`（充电）/ `common_1..10`。
- 佩戴图标（`:152-169`）：`WEARING → AppIcons.Contacts`，`REMOVED → AppIcons.RemoveContact`，`IN_CASE → null`。

---

## §5 快捷弹窗 `PopupActivity.kt:57-491`

由超级岛/通知点击拉起（`chen.action.oppopods.show_pods_ui`）。

```kotlin
onCreate(:64-93)
 ├ prefs = ConfigManager.PREFS_NAME; appConfig = ConfigManager.refreshFromPrefs(prefs)
 ├ device = intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE")
 ├ if (appConfig.notificationClickAction != NOTIFICATION_CLICK_MODULE_POPUP) { openNotificationTarget(); finish() }
 └ setContent { AppTheme(...) { PopupContent(onMore = ..., onDone = ...) } }

openSystemSettings(:125-140)      // ★ 打开被模块改写过的 MIUI 耳机页
  Intent().setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")
    .putExtra("android.bluetooth.device.extra.DEVICE", device)
    .putExtra("bluetoothaddress", device.address)
    .putExtra("MIUI_HEADSET_SUPPORT", ConfigManager.fakeSupport())
    .putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
    .putExtra("DEVICE_ID", ConfigManager.fakeDeviceId())
    .addFlags(FLAG_ACTIVITY_NEW_TASK)

openHeyTapOrModule(): packageManager.getLaunchIntentForPackage("com.heytap.headset") ?: 模块自身
```

`PopupContent`（`:153-344`）：
- 本地状态 `batteryParams/ancMode/gameMode/transparencyVocalEnhancement/deviceName/productId`
- `DisposableEffect` 注册 6 个 `ACTION_PODS_*`（`RECEIVER_EXPORTED`）；`onStart` 发 `ACTION_PODS_UI_INIT` + `ACTION_REFRESH_STATUS` 到 `com.android.bluetooth`
- `LaunchedEffect`：`delay(500)` → `showDialog = true` 兜底（蓝牙进程没响应也能弹）；之后 `while(true){ delay(15_000); ACTION_REFRESH_STATUS }` 轮询
- 容器：`Scaffold(containerColor = Color.Transparent)` + Miuix `OverlayDialog(title = deviceName, backgroundColor = 暗 0xFF1A1A1A / 亮 0xFFF7F7F7, onDismissFinished = onDone)`
- 横屏 `LandscapePopupBody`：`Row(widthIn min 560.dp, height 240.dp)`，左 `weight(0.60f)` = `PodStatus(compact)` + `AncSwitch(compact)`，右 `weight(0.40f)` = 游戏模式 Card + 更多/完成；竖屏 `PortraitPopupBody`：4 张 Card 堆叠。

---

## §6 超级岛 / 融合设备中心 / 快捷弹窗

### 6.1 两条互斥的超级岛实现

| | OFFICIAL（`ISLAND_MODE_OFFICIAL`） | MODULE（`ISLAND_MODE_MODULE`） |
|---|---|---|
| 触发 | 模块广播 `chen.action.oppopods.sendstrongtoast` 到 `com.xiaomi.bluetooth` → `MiuiStrongToastUtil.showOfficialConnectToast` | 模块进程直接 `FocusIslandUtil.showBatteryIsland` |
| 依赖 | 必须 root/作用域含 `com.xiaomi.bluetooth` | 无（只需 SystemUI 认 focus extras） |
| 通知 ID | 劫持 `headset_wear_notification` | 自建 `10086` |
| 动画 | 复用欢律 `earphone_left_inear.mp4` 等 | 静态图 |
| 定位 | `miuiStrongToast` 的 `setStatus(1,"strong_toast_action",bundle)` | `FocusNotification.buildV3` + `nm.notify` |

**官方通道原理**（`utils/miuiStrongToast/MiuiStrongToastUtil.kt:26-266`）：

```kotlin
// :52-55 / :95-98 / :160-163 / :180-183  —— 四处都是同一句反射
val sb = context.getSystemService(Context.STATUS_BAR_SERVICE)
sb.javaClass.getMethod("setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java)
            .invoke(sb, 1, "strong_toast_action", bundle)
```

对外 API：`showStringToast(:32)`、`showPodsBatteryToast(:61)`、`showPodsBatteryToastByMiuiBt(:105)`、`showOfficialConnectToast(:117)`、`hideOfficialToast(:170)`、`showPodsNotificationByMiuiBt(:224)`、`cancelPodsNotificationByMiuiBt(:236)`；常量对象 `Category(:246)`、`StrongToastCategory(:259)`。

Bundle 由 `StringToastBundle.Builder`（`utils/miuiStrongToast/StringToastBundle.kt:5-48`）构造，键名固定：

```
package_name, strong_toast_category, target, param, island_param,
notifyId, duration, level, rapid_rate, charge,
string_toast_charge_flag, status_bar_strong_toast
```
隐藏：`setStatus(1, "strong_toast_action", bundle{package_name, status_bar_strong_toast="hide_strong_toast"})`。

**视频资源的跨进程授权**（`:197-222`，最容易被忽略的一步）：

```kotlin
val file = File(context.filesDir, "$name.mp4").apply { writeBytes(bytes); setReadable(true, false) }
grantUriPermission("com.android.systemui", uri, FLAG_GRANT_READ_URI_PERMISSION)
"content://com.xiaomi.bluetooth.fileprovider/internal_files/$name.mp4"
```
> 必须伪装成 `com.xiaomi.bluetooth` 的 fileprovider authority，否则 SystemUI 拒绝解码。

### 6.2 focus-api 用法（模块自建通道）

`utils/FocusIslandUtil.kt:17-131` 与 `MiBluetoothToastHook.kt:135-199` 是同一套 DSL 的两个实例。常量：`CHANNEL_ID="oppopods_focus_island"`(:19)、`NOTIFICATION_ID=10086`(:21)、`DISMISS_DELAY_MS=4000L`(:23)；入口 `fun showBatteryIsland(context, prefs, batteryParams, address)`(:25)。

```kotlin
FocusNotification.buildV3 {
    val pic = createPicture("key_headset", bitmap)        // 或 "key_pic_left"/"key_pic_right"
    enableFloat   = true
    ticker        = "OppoPods"
    tickerPic     = pic
    updatable     = true
    isShowNotification = false                            // MiBluetoothToastHook 用
    iconTextInfo { animIconInfo { type = 0; src = pic }; title = ...; content = ... }
    island {
        islandProperty = 1
        bigIslandArea {
            imageTextInfoLeft { type = 1; picInfo { type = 1; pic = pic }; textInfo { title = "80"; content = "%" } }
            imageTextInfoRight{ type = 2; picInfo { type = 1; pic = pic }; textInfo { title = "75"; content = "%" } }
        }
        shareData { title = ...; content = ...; shareContent = ... }   // 长按分享
    }
    textButton {                                          // 岛内按钮
        addActionInfo { action = createAction("key_anc_cycle", notificationAction); actionTitle = ancLabel }
        addActionInfo { action = createAction("key_disconnect", notificationAction) }
    }
}
// 然后
nm.notify(10086, Notification.Builder(ctx, "oppopods_focus_island")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .apply { extras?.let { addExtras(it) } }.build())
```

> **`keyPic` 必须是 `Icon.createWithBitmap`**：注释明确写了 SystemUI 不能去加载模块资源（跨包 `Resources` 会抛 `Resources.NotFoundException`）。
> 通知 4s 后自动 `cancel`（`DISMISS_DELAY_MS = 4000L`）。

### 6.3 AOD / 息屏显示

`MiBluetoothToastHook.kt:200-216`：从 `focus` extras 取 `miui.focus.param`（JSON 字符串）→ 塞 `aodTitle`（`"L 80% | R 75%"`）与 `aodPic`（`"key_headset"`）→ 写回。这是 HyperOS 息屏上显示电量的唯一入口。

### 6.4 融合设备中心（小米妙享/MiLink）

见 §2.5 / §2.6。要点：
1. 用 `MxBluetoothService/MxBluetoothManager` 的 `getInstanceForIsMiTWS(Context)` 拿到 Context 才能注册接收器。
2. **UI 刷新只能靠 `headsetPropertyChangeListener` 回调**，没有别的通道。
3. 卡片改写靠 `SynergyView.setTitle(int)` 的资源名匹配（`mi_audio_ringing_view`）—— 这是 hyperos 里最脆的一处 hook，版本升级必失效；必须保留 TODO 兜底（找不到就放弃改卡，不影响其他功能）。
4. 隐藏卡片：`CirculateServiceInfo.setHeadsetId` 里往 `serviceProperties` 塞 `"headset_switch_state" = 0`。

---

## §7 配置与持久化

### 7.1 `config/ConfigManager.kt:23-309`

**偏好文件名**：`oppopods_settings`（与 `HookEntry` 的 `getRemotePreferences("oppopods_settings")` **必须完全一致**）。

```kotlin
@Serializable                       // config/ConfigManager.kt:9-21（注意：定义在 object 之外，文件顶层）
data class AppConfig(
    val fakeDeviceId: String = ConfigManager.DEFAULT_FAKE_DEVICE_ID,
    val logLevel: Int = ConfigManager.LOG_LEVEL_BASIC,
    val islandMode: Int = ConfigManager.ISLAND_MODE_OFFICIAL,
    val islandShowTimings: Set<Int> = emptySet(),
    val notificationClickAction: Int = ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP,
    val moreClickAction: Int = ConfigManager.MORE_CLICK_MODULE,
    val milinkCardFeatures: Set<Int> = ConfigManager.DEFAULT_MILINK_CARD_FEATURES,
    val autoGameMode: Boolean = false,
    val focusNotification: Boolean = true,
    val leAudio: LeAudioConfig = LeAudioConfig(),
)
```
```kotlin
object ConfigManager {
    private const val TAG = "OppoPods-Config"
    const val PREFS_NAME = "oppopods_settings"                 // :25
    const val PREF_KEY_CONFIG_JSON = "config_json"             // :26  ← 变更监听只监听这一个 key
    // 镜像 key（每个字段单独存一份，readConfig 时优先读散键）  :27-35
    PREF_KEY_FAKE_DEVICE_ID "fake_device_id"
    PREF_KEY_LOG_LEVEL "log_level"
    PREF_KEY_ISLAND_MODE "island_mode"
    PREF_KEY_ISLAND_SHOW_TIMINGS "island_show_timings"
    PREF_KEY_NOTIFICATION_CLICK_ACTION "notification_click_action"
    PREF_KEY_MORE_CLICK_ACTION "more_click_action"
    PREF_KEY_MILINK_CARD_FEATURES "milink_card_features"
    PREF_KEY_AUTO_GAME_MODE "auto_game_mode"
    PREF_KEY_FOCUS_NOTIFICATION "focus_notification"

    const val DEFAULT_FAKE_DEVICE_ID = "01010607"              // :36
    LOG_LEVEL_OFF/BASIC/DEBUG                       = 0/1/2    // :37-39
    ISLAND_MODE_NONE/OFFICIAL/MODULE                = 0/1/2    // :40-42
    ISLAND_SHOW_TIMING_CONNECTED/WEARING/REMOVED/IN_CASE = 0/1/2/3   // :43-46
    NOTIFICATION_CLICK_MODULE_POPUP/SYSTEM_SETTINGS/HEYTAP = 0/1/2   // :47-49
    MORE_CLICK_HEYTAP/SYSTEM_SETTINGS/MODULE        = 0/1/2    // :50-52
    SPATIAL_AUDIO_OFF/FIXED/HEAD_TRACKING           = 0/1/2    // :53-55
    MILINK_CARD_GAME_MODE = 0 ; MILINK_CARD_SPATIAL_AUDIO = 1   // :56-57
    DEFAULT_MILINK_CARD_FEATURES = setOf(0, 1)                 // :58
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }   // :60-63
    @Volatile private var cachedConfig: AppConfig = AppConfig()                  // :65-66
}
```
API：`init(:68)` / `refreshFromPrefs(:74)` / `current(:82)` / `fakeDeviceId(:84)` / `fakeSupport(:102)` / `save(prefs, config)(:155)` / `save(prefs, service, config)(:163)` / `writePrefs(:175)` / `readConfig(:190)`。

### 7.2 跨进程读写（**三层，缺一不可**）

```
① 本模块主进程  → context.getSharedPreferences("oppopods_settings", MODE_PRIVATE)     读写
② 被 hook 进程  → module.getRemotePreferences("oppopods_settings")                    读写（libxposed 远程偏好）
③ 两边同步       → ConfigManager.save(prefs, service: XposedService?, config) 同时写 ① 与 ②
```

```kotlin
// :163-173
fun save(prefs: SharedPreferences, service: XposedService?, config: AppConfig) {
    cache(config); writePrefs(prefs, config)
    service?.getRemotePreferences(PREFS_NAME)?.let { writePrefs(it, config) }
}
// :175-188  ★ 必须 commit() 而不是 apply()：跨进程要立刻可见
private fun writePrefs(p: SharedPreferences, c: AppConfig) {
    p.edit().putString(PREF_KEY_CONFIG_JSON, Json.encodeToString(c))
             .putString("fake_device_id", c.fakeDeviceId)
             .putInt("log_level", c.logLevel)
             ... 全部镜像 key ...
             .commit()
}
// :190-234 readConfig：优先读散键（可能被旧版写入），为 null 再回落到 config_json；
//           迁移 "open_heytap" → MORE_CLICK_HEYTAP
```
> `getRemotePreferences` 对象上注册的 `OnSharedPreferenceChangeListener` 会在**主进程写偏好后**于被 hook 进程触发 → `ConfigManager.refreshFromPrefs()`。这是唯一的实时配置推送通道，`MainUI` 里的配置 setter 还会额外发 `ACTION_CONFIG_CHANGED` 到对应 scope 作为兜底（`MainUI.kt:721-789`）。

### 7.3 其它持久化

| 文件 | 内容 |
|---|---|
| `config/PodImagePrefs.kt:12-188` | `enum PodImageResource(fileSuffix){BOX("box"),LEFT("left"),RIGHT("right")}(:12)`；`data class EarphonePref(:19)`；`AUTHORITY="moe.chenxy.oppopods.podimages"`(:35)、`IMAGE_DIR="pod_images"`(:37)；API `load(:44)/find(:51)/findOrLatest(:56)/imageDir(:60)/upsertConnected(:62)/saveImages(:80)/saveImageBytes(:110)`；`fun EarphonePref.imageUri(resource)(:180)` → `content://moe.chenxy.oppopods.podimages/<file>` |
| `config/PodImageProvider.kt:11-51` | `ContentProvider.openFile`：**仅 `r` 模式**，校验 `lastPathSegment` 在已登记文件名集合内、canonical path 前缀正确，否则 `SecurityException` |
| `config/LeAudioConfig.kt:7-38` | `enabled, deviceAddresses:Set<String>, respondAt=true, sendVdsp=true, vendorId="1946", oesfMask="0x3f", leFirst, poke, wakeClassic, fixPolicy, gateHfp, holdGatt, adoptContexts, gameContext, completeGroup`；`parsedMask()` 解析 hex/dec → 0..0xFFFF；`normalized()` 用 `BluetoothAdapter.checkBluetoothAddress` 过滤 MAC |
| `hook/SettingsHeadsetHook.kt:716-766` | `oppopods_milink_state`：`address/name/anc/transparency_vocal_enhancement/left,right,case` + 充电/连接 |
| `hook/milink/MiLinkServiceHook.kt:640-689` | 同名偏好，另加 `game_mode/spatial_audio_mode` |
| `hook/leaudio/LeAudioConnectionHook.kt:198-241` | `oppopods_le_audio_buds`：`all:Set<String>` + `classic:String` |
| `MainActivity.kt:29-34` | 纯 UI 偏好：`theme_mode / accent_mode / floating_bottom_bar / blur_bottom_bar / app_language`（**不受 ConfigManager 管理**，散键存储） |

### 7.4 广播命名（`utils/miuiStrongToast/data/OppoPodsAction.kt:1-43`）

```
chen.action.oppopods.*  ←→  ACTION_PODS_*（主 UI ↔ 蓝牙进程）
chen.action.oppopods.sendstrongtoast / updatepodsnotification / cancelpodsnotification  ←→ com.xiaomi.bluetooth
ACTION_RFCOMM_LOG / RFCOMM_LOG_CONNECT|DISCONNECT|CLEAR / RFCOMM_DEBUG_SEND            ←→ 调试页
ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE  ← 蓝牙进程存活心跳
```

---

## §8 新模块必须「逐字照抄」的清单

### 8.1 构建/清单层（不做必然失败）

- [ ] `compileSdk 37`，`minSdk 35`，`targetSdk 36`，`JavaLanguageVersion 22`。
- [ ] `compileOnly(libs.libxposed.api)` + `implementation(libs.libxposed.service)` —— 写成 `implementation` 会在注入进程里被类加载器引爆（API 由宿主提供）。
- [ ] `resources/META-INF/xposed/` 三件套：`java_init.list`（**一行，只写入口类全名，不带 `L`/`;`/注释**）、`module.prop`（`minApiVersion=101` / `targetApiVersion=101` / `staticScope=true`）、`scope.list`（逐行包名）。**不要再写 `assets/xposed_init`** —— 本仓那份是旧 YukiHookAPI 残留，类不存在，纯死文件。
- [ ] 入口类 `class XxxEntry : io.github.libxposed.api.XposedModule()`，重写 `onPackageLoaded(param: PackageLoadedParam)`，第一句 `if (!param.isFirstPackage) return`。
- [ ] `AndroidManifest` 四条 meta-data：`xposedmodule` / `xposeddescription` / `xposedminversion=101` / `xposedscope=@array/xposedscope`。
- [ ] `MainActivity` 的 `<category android:name="de.robv.android.xposed.category.MODULE_SETTINGS"/>`（否则 LSPosed 不能从模块页跳进来）。
- [ ] `xposeddescription` 保持英文一句话（LSPosed 模块列表按它显示）。
- [ ] 图标：`@mipmap/ic_launcher` + `@mipmap/ic_launcher_round` **都要有**（LSPosed 与启动器各取一个）；`activity-alias` 用于"隐藏桌面图标"。
- [ ] 不申请 `QUERY_ALL_PACKAGES`；用 `<queries>` 白名单（`com.heytap.headset`、`com.android.settings`）。
- [ ] `proguard` 保留 `hook.**` 与 `**/strongToast/data/**`；开 `-repackageclasses -allowaccessmodification`。

### 8.2 协议层（字节级）

- [ ] 帧头 `0xAA`、`TotalLen = 7 + PayLen`、`Cmd` LE16 在 `[4..5]`、`Seq` 默认 `0xF0`、`PayLen` LE16 在 `[7..8]`、payload 从 `9` 开始。
- [ ] `QUERY_STATUS = AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28` —— 这个 21 字节魔数不能"优化"。
- [ ] 上电序列：`QUERY_PRODUCT_ID` → (+300ms) → `QUERY_NOTIFICATION_SUPPORT` → (+50ms) → `QUERY_STATUS/BATTERY/ANC/EQ` 各隔 50ms。
- [ ] ANC 设置后 `delay(350)` 回读。
- [ ] `NotificationSupportParser` 只在 `(id and 0xFF) < 0xF0` 时回 `REGISTER_MULTI_NOTIFICATION`。
- [ ] **补上本仓缺失的长度校验与重组**（`packet[1] + 2 == packet.size`），否则长包偶发解析失败。

### 8.3 HyperOS 兼容层

- [ ] `getRemotePreferences("oppopods_settings")` 与本地偏好**同名**。
- [ ] 对外广播一律 `setPackage(...)` + `FLAG_RECEIVER_FOREGROUND`；接收器注册一律带 `Context.RECEIVER_EXPORTED`（Android 14+ 必须显式声明）。
- [ ] 在被 hook 进程里读本模块资源，`createPackageContext(BuildConfig.APPLICATION_ID, CONTEXT_IGNORE_SECURITY)`。
- [ ] 通知里传给 SystemUI 的图片必须是 `Icon.createWithBitmap`，禁止传资源 ID / 跨包 Resources。
- [ ] 官方通道需要 `grantUriPermission("com.android.systemui", uri, FLAG_GRANT_READ_URI_PERMISSION)` 且 authority 伪装为 `com.xiaomi.bluetooth.fileprovider`。
- [ ] 劫持 MIUI 通知 ID `headset_wear_notification`（想接管"摘下耳机自动弹岛"必须用它）。
- [ ] 蓝牙设备图标：`statusBarManager.setIconVisibility("wireless_headset", true/false)`。
- [ ] 电量回写系统：反射 `BluetoothDevice#setBatteryLevel(int)`，失败回落 `mAdapterService.setBatteryLevel(device, level, false)`。
- [ ] 作用域重启走 `RootManager.restartPackages`（`su -c "am force-stop pkg"`），并对包名做 `^[A-Za-z0-9_.]+$` 校验防注入。

### 8.4 UI 层（观感一致性）

- [ ] Miuix 0.9.4 全套（`miuix-ui` + `miuix-preference` + `miuix-icons` + `miuix-blur` + `miuix-nav`）；不要混用 Material3 组件，阴影/圆角会不一致。
- [ ] `enableEdgeToEdge(TRANSPARENT, TRANSPARENT)` + `window.isNavigationBarContrastEnforced = false`。
- [ ] `AncSwitch` 的 300ms `tween` + `Crossfade` + `SinkFeedback` 按压反馈；图标 selected/未选尺寸比 1 : 0.8。
- [ ] `PodStatus` 断连后**保留最后电量**并显示占位标签；用 `'\u3000'` 对齐三列。
- [ ] 夜间图标必须走 `themedPainterResource`（应用内主题优先于系统主题）。
- [ ] 页面统一 `Scaffold + TopAppBar(largeTitle, MiuixScrollBehavior) + overScrollVertical() + nestedScroll(...)`。
- [ ] 底部栏悬浮/毛玻璃：`textureBlur(backdrop, RoundedCornerShape(50.dp))` + `zIndex(2f)`；列表底部留 `104.dp`。

---

## §9 移植路线（建议顺序）

1. **骨架可跑**：Gradle 全套 + 入口三件套 + `HookContext`/`Log`/`HookParam` → 装上 LSPosed 能看到模块、能 log。
2. **传输可通**：`Packets` 常量表 + `RfcommController.connectPod/connectRfcomm/startPacketReader/sendPacketSafe`（**先补长度重组**）→ `RfcommDebugPage` 能看到 RX/TX HEX。
3. **能力可读**：`ProductIdParser` → `DeviceModelRegistry` → `DeviceCapabilities` → 详情页动态显隐。
4. **UI 可用**：`MainActivity` + `App/Theme/AppLocale` + `MainUI` + `MainTabs` + `PodDetailPage` + `AncSwitch` + `PodStatus`。
5. **系统整合**：`HeadsetStateDispatcher`（自动连）→ `BluetoothUpstreamHeadsetHook`（接管原生页/通知）→ `MiLinkServiceHook`（融合设备中心）。
6. **锦上添花**：`FocusIslandUtil`（模块岛）→ `MiuiStrongToastUtil`（官方岛 + 视频）→ `LeAudio` 全套。

## §10 已知脆弱点（移植时预留降级路径）

| 位置 | 脆弱原因 | 降级策略 |
|---|---|---|
| `MiLinkServiceHook:457-490` `SynergyView.setTitle` | 依赖资源名 `mi_audio_ringing_view`，HyperOS 版本一改就失效 | `runCatching` 包裹；失败即放弃改卡 |
| `BluetoothUpstreamHeadsetHook` 方法名候选表 | MIUI 混淆名每版变化 | 保持 `listOf("原语名","moXXXX","X")` 候选数组 |
| `MiuiBluetoothNotification.updateParameters` 字段名 `C4705R2`/`f18110e` | 混淆字段按版本重排 | 按类型（`BluetoothDevice` / `int`）匹配而非按名 |
| RFCOMM 无帧重组 | 长包分片 | 新增 `packet[1]+2` 校验 + 累积缓冲 |
| 120s 固定重连、无指数退避 | 耳机长时间离开后再回连慢 | 保留常量，改为可配置 |
| 18 个广播 action 拼在 4 个文件里 | 一处拼错全链路静默失败 | 抽 `OppoPodsAction` 单一真源 |
