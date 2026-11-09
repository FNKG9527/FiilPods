package moe.chenxy.fiilpods.hook

/**
 * Framework-free normalization for commands emitted by [SettingsControlCard].
 *
 * Keeping this boundary free of [android.content.Intent] lets the Settings-host
 * control-card contract be unit tested without Android view instrumentation.
 */
internal sealed class CardCommandRoute {
    data class Command(
        val command: String,
        val value: Int?,
        val flag: Boolean?,
    ) : CardCommandRoute()

    data class Adaptive(
        val kind: String,
        val enabled: Boolean,
    ) : CardCommandRoute()

    data class Ldac(
        val enabled: Boolean,
    ) : CardCommandRoute()

    companion object {
        fun normalize(cmd: String, value: Int?, flag: Boolean?): CardCommandRoute = when {
            cmd.startsWith(ADAPTIVE_PREFIX) -> Adaptive(
                kind = cmd.removePrefix(ADAPTIVE_PREFIX),
                enabled = flag ?: false,
            )
            cmd == LDAC_COMMAND -> Ldac(enabled = flag ?: false)
            else -> Command(command = cmd, value = value, flag = flag)
        }

        private const val ADAPTIVE_PREFIX = "adaptive:"
        private const val LDAC_COMMAND = "ldac"
    }
}
