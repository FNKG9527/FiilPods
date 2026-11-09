package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for ANC reply confirmation, independent of Android/Bluetooth state. */
class AncConfirmationPolicyTest {
    private val confirmWindowMs = 3_500L

    @Test
    fun `stale old ANC reply is ignored and cannot acknowledge the pending target`() {
        val pending = AncConfirmationPolicy.Pending(target = 2, sentAtMs = 100L)

        val decision = AncConfirmationPolicy.evaluateReply(
            pending = pending,
            deviceValue = 1,
            nowMs = 200L,
            confirmationWindowMs = confirmWindowMs,
        )

        assertFalse(decision.acceptDeviceValue)
        assertFalse(decision.confirmsPendingOperation)
        assertFalse(decision.clearPendingTarget)
    }

    @Test
    fun `target-matching ANC reply is accepted and acknowledges the pending target`() {
        val pending = AncConfirmationPolicy.Pending(target = 2, sentAtMs = 100L)

        val decision = AncConfirmationPolicy.evaluateReply(
            pending = pending,
            deviceValue = 2,
            nowMs = 200L,
            confirmationWindowMs = confirmWindowMs,
        )

        assertTrue(decision.acceptDeviceValue)
        assertTrue(decision.confirmsPendingOperation)
        assertTrue(decision.clearPendingTarget)
    }

    @Test
    fun `after confirmation window device state is accepted but does not claim command success`() {
        val pending = AncConfirmationPolicy.Pending(target = 2, sentAtMs = 100L)

        val decision = AncConfirmationPolicy.evaluateReply(
            pending = pending,
            deviceValue = 1,
            nowMs = 3_600L,
            confirmationWindowMs = confirmWindowMs,
        )

        assertTrue(decision.acceptDeviceValue)
        assertFalse(decision.confirmsPendingOperation)
        assertTrue(decision.clearPendingTarget)
    }

    @Test
    fun `old timeout cannot clear a later target with equal values`() {
        val first = AncConfirmationPolicy.Pending(target = 2, sentAtMs = 100L)
        val later = AncConfirmationPolicy.Pending(target = 2, sentAtMs = 200L)

        assertFalse(AncConfirmationPolicy.isCurrent(later, first))
        assertTrue(AncConfirmationPolicy.isCurrent(later, later))
    }
}
