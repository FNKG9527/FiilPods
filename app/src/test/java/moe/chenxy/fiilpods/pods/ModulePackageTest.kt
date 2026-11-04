package moe.chenxy.fiilpods.pods

import moe.chenxy.fiilpods.BuildConfig
import moe.chenxy.fiilpods.config.PodImagePrefs
import moe.chenxy.fiilpods.utils.LogFileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「运行时身份」与会话里那份 applicationId 的一致性。
 *
 * 背景：v3.1.0 为了进 LSPosed 官方仓库，把 applicationId 从 `moe.chenxy.fiilpods`
 * 改成了 `io.github.fnkg9527.fiilpods`，但 **Kotlin 包名与 namespace 仍是旧的**
 * `moe.chenxy.fiilpods`（类全名不变，`java_init.list`、manifest 相对类名、R 都不用动）。
 *
 * 于是项目里同时存在两个「包名」，写法上极易混淆：
 *  - 写 `moe.chenxy.fiilpods` 的地方 = 类全名 / namespace，**永远不要**拿去
 *    `createPackageContext` / `Intent.setPackage`；
 *  - 必须是 applicationId 的地方 = `FiilConfig.MODULE_PACKAGE`、provider authority、
 *    广播 action 前缀。
 *
 * 这类写错的后果全是**静默**的：`createPackageContext` 抛 NameNotFoundException 被
 * `runCatching` 吞掉 → 配置读不到、图标加载不出来、跨进程广播发不到，界面上只是
 * 「没反应」。所以这里用编译期就带进来的 [BuildConfig.APPLICATION_ID] 做基准逐项钉死。
 */
class ModulePackageTest {

    @Test
    fun `MODULE_PACKAGE is the application id, not the kotlin package`() {
        assertEquals(BuildConfig.APPLICATION_ID, FiilConfig.MODULE_PACKAGE)
        // 顺带把「两个包名已经不是一回事」这件事也钉住，
        // 否则以后有人看到 MODULE_PACKAGE 与时髦的 kotlin 包名不一致，会当成 bug 改回去。
        assertTrue(
            "applicationId 应当与 Kotlin 包名不同 —— 如果哪天真的把包名也一起改了，" +
                "这条断言与 FiilConfig 的注释要一起更新",
            BuildConfig.APPLICATION_ID != "moe.chenxy.fiilpods",
        )
        assertEquals("io.github.fnkg9527.fiilpods", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `provider authority is unique per application id`() {
        // authority 全设备唯一：一旦与旧版（moe.chenxy.fiilpods）撞车，
        // 新旧两版就无法共存，安装直接 INSTALL_FAILED_CONFLICTING_PROVIDER。
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.podimages",
            PodImagePrefs.AUTHORITY,
        )
        // 「导出日志」的 FileProvider 走同一个命名规则。它必须是 exported=false，
        // 且 authority 不能与上面那个撞车，否则 manifest 合并直接失败。
        assertEquals(
            "${BuildConfig.APPLICATION_ID}.logs",
            LogFileProvider.AUTHORITY,
        )
        assertTrue(
            "两个 provider 的 authority 不能相同",
            LogFileProvider.AUTHORITY != PodImagePrefs.AUTHORITY,
        )
    }

    @Test
    fun `every broadcast action carries the application id prefix`() {
        val actions = listOf(
            FiilAction.ACTION_PODS_CONNECTED,
            FiilAction.ACTION_PODS_DISCONNECTED,
            FiilAction.ACTION_PODS_BATTERY_CHANGED,
            FiilAction.ACTION_PODS_ANC_CHANGED,
            FiilAction.ACTION_STATE,
            FiilAction.ACTION_OP_FAILED,
            FiilAction.ACTION_REFRESH_STATUS,
            FiilAction.ACTION_PODS_UI_INIT,
            FiilAction.ACTION_ANC_SELECT,
            FiilAction.ACTION_COMMAND,
            FiilAction.ACTION_REQUEST_STATE,
            FiilAction.ACTION_CONFIG_CHANGED,
            FiilAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE,
            FiilAction.ACTION_SHOW_PODS_UI,
        )
        val prefix = "${BuildConfig.APPLICATION_ID}.action."
        actions.forEach { action ->
            assertTrue("action 前缀不对: $action", action.startsWith(prefix))
        }
        assertEquals("全部 action 应当互不相同", actions.size, actions.toSet().size)
    }

    @Test
    fun `rfcomm controller actions match the shared contract`() {
        // FiilRfcommController 里另有三份同名字面量（历史原因），
        // 换前缀时漏改一处，蓝牙进程与 UI 就会各说各话。
        assertEquals(FiilAction.ACTION_STATE, FiilRfcommController.ACTION_STATE)
        assertEquals(FiilAction.ACTION_COMMAND, FiilRfcommController.ACTION_COMMAND)
        assertEquals(FiilAction.ACTION_REQUEST_STATE, FiilRfcommController.ACTION_REQUEST_STATE)
    }
}
