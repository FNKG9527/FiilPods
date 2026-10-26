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
        applicationId = "moe.chenxy.fiilpods"
        minSdk = 35
        targetSdk = 36
        // v2：修复 Mate 机型无法切换模式（GAIA/Mate 协议族分流）、补齐模块内日志抓取。
        // v1 的源码与产物完整保留在 dist/v1-src 与 dist/FiilPods-v1.0.0-*.apk。
        // v2.1：修用户 m26286 报的三条 ——
        //   ① 融合设备中心切模式后图标要再点一次才更新（回写 AncBatteryModel 字段）
        //   ② 模块页误报「模块服务未响应」（UI_INIT/REFRESH 时回发 ALIVE 心跳）
        //   ③ 设置页开启焦点通知后通知栏无通知（HookEntry 注入远端 prefs + 模块自制超级岛接线）
        versionCode = 3
        versionName = "2.1.0"
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
