package moe.chenxy.fiilpods.pods

/**
 * Connection-scoped adapter around [AutoGameModePolicy].
 *
 * Android-facing code feeds it only two facts: a verified foreground-app category and a
 * low-latency mode confirmed by an actual headset status frame. Keeping those facts separate
 * prevents the controller's default `0` value from being mistaken for a music-mode baseline.
 */
class AutoGameModeRuntime(
    private val policy: AutoGameModePolicy = AutoGameModePolicy(),
) {
    private var enabled = false
    private var classification = AutoGameModePolicy.Classification.UNKNOWN
    private var confirmedMode: AutoGameModePolicy.LowLatency? = null

    /** Applies the authoritative preference value; disabling clears all connection state. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        if (!value) {
            enabled = false
            // A config-disable broadcast is a connection boundary even when we were already
            // disabled: it must erase any stale/manual state before a future enable.
            resetSession()
            return
        }
        if (enabled) return
        enabled = true
    }

    /** Records an ordered, verified foreground-app classification. */
    @Synchronized
    fun onForegroundClassification(
        value: AutoGameModePolicy.Classification,
    ): AutoGameModePolicy.LowLatency? {
        classification = value
        return policy.evaluate(enabled, classification, confirmedMode)
    }

    /**
     * Records a headset mode only after a real low-latency query/notification response.
     * The returned command is the sole automation write that may be issued.
     */
    @Synchronized
    fun onConfirmedLowLatency(
        value: AutoGameModePolicy.LowLatency,
    ): AutoGameModePolicy.LowLatency? {
        confirmedMode = value
        return policy.evaluate(enabled, classification, confirmedMode)
    }

    /** Marks user low-latency ingress before its protocol command is sent. */
    @Synchronized
    fun markManualAction() {
        policy.markManualAction()
    }

    /** Drops the baseline and ownership when the headset connection ends. */
    @Synchronized
    fun onDisconnected() {
        resetSession()
    }

    /** A confirmed headset status is required before automation can issue any command. */
    @Synchronized
    fun hasConfirmedBaseline(): Boolean = confirmedMode != null

    private fun resetSession() {
        policy.onDisconnected()
        classification = AutoGameModePolicy.Classification.UNKNOWN
        confirmedMode = null
    }
}
