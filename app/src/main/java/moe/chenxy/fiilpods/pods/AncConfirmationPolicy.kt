package moe.chenxy.fiilpods.pods

/**
 * Pure rules for accepting device-reported ANC state while a local mode change is pending.
 *
 * A pending instance is intentionally an identity token, not just a `(target, sentAt)` value:
 * two consecutive requests may have the same target, and a delayed timeout/reply from the older
 * request must never settle the newer request.
 */
internal object AncConfirmationPolicy {
    data class Pending(
        val target: Int,
        val sentAtMs: Long,
    )

    data class ReplyDecision(
        /** Whether the controller should adopt [deviceValue] as its authoritative mode. */
        val acceptDeviceValue: Boolean,
        /** Whether this reply proves the currently pending command reached its target. */
        val confirmsPendingOperation: Boolean,
        /** Whether the pending optimistic target is no longer valid. */
        val clearPendingTarget: Boolean,
    )

    /**
     * Evaluate one authoritative ANC/MAF status reply.
     *
     * During the confirmation window, a mismatched reply is an old snapshot from before the
     * switch and must neither overwrite optimistic state nor acknowledge the command.  A matching
     * reply is the only reply that confirms the pending operation.  Once the window has elapsed,
     * the device state wins, but it is intentionally not treated as command success.
     */
    fun evaluateReply(
        pending: Pending?,
        deviceValue: Int,
        nowMs: Long,
        confirmationWindowMs: Long,
    ): ReplyDecision = when {
        pending == null -> ReplyDecision(
            acceptDeviceValue = true,
            confirmsPendingOperation = false,
            clearPendingTarget = false,
        )
        nowMs - pending.sentAtMs >= confirmationWindowMs -> ReplyDecision(
            acceptDeviceValue = true,
            confirmsPendingOperation = false,
            clearPendingTarget = true,
        )
        deviceValue == pending.target -> ReplyDecision(
            acceptDeviceValue = true,
            confirmsPendingOperation = true,
            clearPendingTarget = true,
        )
        else -> ReplyDecision(
            acceptDeviceValue = false,
            confirmsPendingOperation = false,
            clearPendingTarget = false,
        )
    }

    /** Identity comparison protects a later same-value target from an earlier timeout. */
    fun isCurrent(current: Pending?, candidate: Pending): Boolean = current === candidate
}
