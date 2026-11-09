package moe.chenxy.fiilpods

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
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
import moe.chenxy.fiilpods.utils.LogExporter

class MainActivity : ComponentActivity() {
    private companion object {
        // An Activity recreation can overlap an old crash-page worker. Keep the gate at
        // process scope so that overlap cannot race LogExporter's shared timestamp formatters.
        val crashExportGate = CrashExportGate()
    }

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

        // Android 13+ 必须先拿到 POST_NOTIFICATIONS 运行时权限，
        // 否则「焦点通知」开得再对，NotificationManager.notify() 也会被系统静默丢弃
        // （用户 m27504 Bug 2 的根因之一）。必须在 super.onCreate() 之后、
        // 且 Activity 尚未 STARTED 时注册 launcher，所以放在这里。
        moe.chenxy.fiilpods.utils.NotifPermission.ensure(this)

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
     * 只用一个 ScrollView + TextView，并在底部放三个按钮：
     *  - 「复制」把堆栈写进剪贴板（用户直接粘贴给我）
     *  - 「导出诊断日志」把崩溃记录连同当前配置与最近运行日志写入 FileProvider
     *    已暴露的 logs/ 目录，再打开系统分享面板；不能直接分享 crash_last.txt，因为
     *    它不在 FileProvider 声明的路径内。
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
            setText(getString(R.string.crash_page_title, getString(R.string.app_name)))
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
            setText(getString(R.string.crash_copy_stack))
            setOnClickListener {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                val label = getString(R.string.crash_clip_label)
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
                android.widget.Toast.makeText(
                    this@MainActivity,
                    getString(R.string.crash_copied),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        })

        val clearButton = android.widget.Button(this).apply {
            setText(getString(R.string.crash_clear_retry))
            setOnClickListener {
                CrashHandler.clear(this@MainActivity)
                recreate()
            }
        }
        val exportButton = android.widget.Button(this).apply {
            setText(getString(R.string.export_log))
            setOnClickListener {
                if (!crashExportGate.tryBegin()) return@setOnClickListener
                isEnabled = false
                clearButton.isEnabled = false
                setText(getString(R.string.export_log_running))
                exportCrashDiagnostics(this, clearButton)
            }
        }
        column.addView(exportButton)
        column.addView(clearButton)

        scroll.addView(column)
        return scroll
    }

    /**
     * CrashHandler keeps the record until the user explicitly clears it, so this export
     * can safely run before the retry button becomes available again. The potentially
     * multi-megabyte read/write stays off the main thread; every view/Activity action
     * returns through [runOnUiThread].
     */
    private fun exportCrashDiagnostics(
        exportButton: android.widget.Button,
        clearButton: android.widget.Button,
    ) {
        Thread {
            val result = LogExporter.export(applicationContext)
            crashExportGate.finish()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton.isEnabled = true
                exportButton.setText(getString(R.string.export_log))
                clearButton.isEnabled = true
                result.fold(
                    onSuccess = { export ->
                        Toast.makeText(
                            this,
                            getString(
                                R.string.export_log_done,
                                export.logLines,
                                LogExporter.humanBytes(export.bytes),
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                        // The export is written under FileProvider's logs/ root. Sharing the
                        // raw crash_last.txt would throw because that file is intentionally
                        // outside the provider paths.
                        val shared = runCatching {
                            startActivity(
                                Intent.createChooser(
                                    LogExporter.shareIntent(this, export.file),
                                    getString(R.string.export_log_share_title),
                                ),
                            )
                        }
                        shared.onFailure { error ->
                            // The file is already on disk and the success toast above already
                            // fired, so a share failure is reported on its own line instead of
                            // being folded into the "export failed" message.
                            Toast.makeText(
                                this,
                                getString(
                                    R.string.export_log_share_failed,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            getString(
                                R.string.export_log_failed,
                                error.message ?: error.javaClass.simpleName,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }.start()
    }
}
