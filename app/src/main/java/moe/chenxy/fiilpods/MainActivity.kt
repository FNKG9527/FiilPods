package moe.chenxy.fiilpods

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.ui.App
import moe.chenxy.fiilpods.ui.AppLocale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(newBase, newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("app_language", AppLocale.SYSTEM))
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 上一次启动若崩过，先用**纯 framework 控件**把堆栈直接显示在界面上。
        // 不碰 Compose / MIUIX —— 崩溃可能就出在那两层，用它们画崩溃页等于二次崩溃。
        val crashText = CrashHandler.readLatest(this)
        if (crashText != null) {
            setContentView(buildCrashView(crashText))
            return
        }

        setContent {
            val prefs = remember { getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
            val themeMode = remember { mutableStateOf(prefs.getInt("theme_mode", 0)) }
            val accentMode = remember { mutableStateOf(prefs.getInt("accent_mode", 0)) }
            val floatingBottomBar = remember { mutableStateOf(prefs.getBoolean("floating_bottom_bar", false)) }
            val blurBottomBar = remember { mutableStateOf(prefs.getBoolean("blur_bottom_bar", false)) }
            val appLanguage = remember { mutableStateOf(prefs.getInt("app_language", AppLocale.SYSTEM)) }
            val systemDark = isSystemInDarkTheme()
            val darkMode = when (themeMode.value) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false

                onDispose {}
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.value = it
                    prefs.edit().putInt("theme_mode", it).apply()
                },
                accentMode = accentMode,
                onAccentModeChange = {
                    accentMode.value = it
                    prefs.edit().putInt("accent_mode", it).apply()
                },
                floatingBottomBar = floatingBottomBar,
                onFloatingBottomBarChange = {
                    floatingBottomBar.value = it
                    prefs.edit().putBoolean("floating_bottom_bar", it).apply()
                },
                blurBottomBar = blurBottomBar,
                onBlurBottomBarChange = {
                    blurBottomBar.value = it
                    prefs.edit().putBoolean("blur_bottom_bar", it).apply()
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    prefs.edit().putInt("app_language", it).apply()
                },
            )
        }
    }

    /**
     * 纯 framework 的崩溃页。
     *
     * 刻意不用 Compose/MIUIX：崩溃很可能就发生在它们内部，用同一套栈来画错误页会立刻二次崩溃。
     * 只用一个 ScrollView + TextView，并在底部放两个按钮：
     *  - 「复制」把堆栈写进剪贴板（用户直接粘贴给我）
     *  - 「清除并重试」删掉记录后重启界面，让它回到正常路径
     */
    private fun buildCrashView(text: String): android.view.View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        column.addView(android.widget.TextView(this).apply {
            setText(getString(R.string.app_name) + " · 上次启动崩溃")
            textSize = 20f
            setTextColor(0xFF1A1A1A.toInt())
        })

        column.addView(android.widget.TextView(this).apply {
            setText(text)
            textSize = 11f
            setTextColor(0xFF333333.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(12), 0, dp(12))
            setTextIsSelectable(true)
        })

        column.addView(android.widget.Button(this).apply {
            setText("复制堆栈")
            setOnClickListener {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("FiilPods crash", text))
                android.widget.Toast.makeText(this@MainActivity, "已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        })

        column.addView(android.widget.Button(this).apply {
            setText("清除并重试")
            setOnClickListener {
                CrashHandler.clear(this@MainActivity)
                recreate()
            }
        })

        scroll.addView(column)
        return scroll
    }
}
