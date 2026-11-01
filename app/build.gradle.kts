plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.resopt)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.parcelize)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "moe.chenxy.fiilpods"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.fnkg9527.fiilpods"
        minSdk = 35
        targetSdk = 36
        // v2：修复 Mate 机型无法切换模式（GAIA/Mate 协议族分流）、补齐模块内日志抓取。
        // v1 的源码与产物完整保留在 dist/v1-src 与 dist/FiilPods-v1.0.0-*.apk。
        // v2.1：修用户 m26286 报的三条 ——
        //   ① 融合设备中心切模式后图标要再点一次才更新（回写 AncBatteryModel 字段）
        //   ② 模块页误报「模块服务未响应」（UI_INIT/REFRESH 时回发 ALIVE 心跳）
        //   ③ 设置页开启焦点通知后通知栏无通知（HookEntry 注入远端 prefs + 模块自制超级岛接线）
        // v2.2.1：删除从 OppoPods 复制过来的「OPPO LE Audio」设置页。
        //   该页在 fiil_mod 里只有 UI 壳（LeAudioSettingsPage / LeAudioConfig /
        //   LeAudioDevicesDialog + 84 条字符串），hook 侧一行实现都没有 ——
        //   OppoPods 的 504 行 LeAudioConnectionHook 与 119 行 OppoLeAudioHook 从未移植。
        //   FIIL 走 SPP/RFCOMM MATE 协议，与 OPPO 的 VDID/VDSF/OESF/VDSP 私有 AT
        //   握手无关，所以这页对 FIIL 耳机纯属空转。整页连同入口、导航、配置字段、
        //   字符串资源一并移除。版本号保持 2.2.x 线，仅递增 patch。
        // v2.2.2：修「详情页看不到 LDAC 开关」。
        //   LDAC 是**耳机侧开关**（RFCOMM cid=1 cmdId=13 写 / cid=39 cmdId=143 读），
        //   v2.2.0/2.2.1 只做了手机侧反射 A2dpService.setCodecConfigPreference，
        //   耳机端从没人去打开它 —— 于是 A2DP 握手不声明 LDAC 能力，
        //   FiilCodec.query() 永远返回 supported=false，详情页 `if (ldacState >= 0)`
        //   不成立，开关永不渲染。这是个死锁：不显示就没入口打开，不打开就永远不显示。
        //   现在「是否显示」改为按 earType 能力判（FiilModels.supportsLdac），
        //   开关状态读耳机侧 143/142，点击时双管齐下（耳机侧 cmdId=13 + 手机侧 A2DP）。
        // v2.2.3：修「图标不跟着耳机型号走」。
        //   ① 融合设备中心：`BluetoothServiceClient.getHeadsetType(BluetoothDevice)`
        //      把 FIIL 的 fakeDeviceId("01010607") 丢给 `vg/a.b(String)`，那是小米自家
        //      6 位编码空间，命不中 → 恒返回 0 → 卡片图标恒为普通入耳图。改为按
        //      earType 的真实形态返回 7（头戴）/3（普通），图标由官方 `a0.a()` 的
        //      packed-switch 选 `circulate_device_headset_headphones` / `circulate_headset_icon`。
        //   ② 通知栏：`ACTION_PODS_BATTERY_CHANGED` 补 `EXTRA_EAR_TYPE`，
        //      焦点通知大图改走 `PodImageLoader.loadModelBoxBitmap`（自定义图 →
        //      型号官方图 → 通用图）。之前只有「自定义 or 通用盒子图」，所以接什么
        //      型号都长一样。
        // v3.0.0：全新大版本。功能面与 v2.2.3 一致（不再新增 hook），
        //   仅升版本号 + 应用名改为 "FiilPods 3.0"，用来给「FIIL 耳机原生态接入
        //   小米耳机」这条链路立一个正式基线：融合设备中心的模式切换/图标、
        //   系统级降噪读写、通知栏与超级岛、LDAC、伪装型号、电量与佩戴，
        //   全部走小米自家的那套 API，FIIL 自己的功能也都能正常使用与切换。
        // v3.1.0：只改 package（applicationId），功能面与 3.0.0 完全一致。
        //   原因：LSPosed 官方仓库（Xposed-Modules-Repo）要求 applicationId 反推的
        //   域名归提交者所有，或使用 io.github.{用户名} 前缀。原包名 moe.chenxy.fiilpods
        //   反推是 chenxy.moe —— 那是上游 OppoPods 作者的域名，我们没有 DNS 控制权，
        //   机器人直接判 invalid（工单 Xposed-Modules-Repo/submission#2392）。
        //   改为 io.github.fnkg9527.fiilpods 后可自动通过校验。
        //   namespace 与 Kotlin 包名保持 moe.chenxy.fiilpods 不动（类全名不变，
        //   java_init.list、manifest 相对类名、R 全部无需改动），只把
        //   applicationId / provider authority / 广播 action 前缀 / MODULE_PACKAGE
        //   这几处「运行时身份」换成新值。
        versionCode = 9
        versionName = "3.1.0"
        buildConfigField("long", "BUILD_TIMESTAMP", System.currentTimeMillis().toString())
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // 用 debug keystore 签名，保证 assembleRelease 产出可直接安装的 APK
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo.includeInApk = false

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "**.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_21.majorVersion)
    }
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_21.majorVersion.toInt())
}

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
}

dependencies {
    implementation(libs.coreKtx)
    compileOnly(libs.libxposedApi)
    implementation(libs.libxposedService)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    // 注意：不要加 material3 / material-icons-extended —— 全工程零引用，
    // 却会分别拖进 16MB / 40MB 的 dex，是之前 69MB APK 的元凶。
    // MIUIX 自身会传递 material3，release 由 R8 裁掉未用部分。

    implementation(libs.miuix)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    // HyperOS 焦点通知（超级岛）API —— 从 OppoPods 移植的 FocusIslandUtil 依赖它
    implementation(libs.focus.api)

    testImplementation(libs.junit)
}
