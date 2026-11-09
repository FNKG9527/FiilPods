package moe.chenxy.fiilpods.pods

/**
 * 完整状态快照的显式接收方。
 *
 * 蓝牙进程是状态唯一权威；模块 UI 与四个已注入系统宿主都依赖同一份 JSON。
 * 使用包名列表而不是隐式广播，避免把模块内部状态暴露给无关应用。
 */
object StateBroadcastTargets {
    fun packages(modulePackage: String): List<String> =
        (listOf(modulePackage) + FiilAction.STATUS_TARGETS).distinct()
}
