package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoGameModePolicyTest {
    @Test
    fun `enabled auto mode claims a confirmed music baseline for a known game`() {
        val policy = AutoGameModePolicy()

        assertEquals(
            AutoGameModePolicy.LowLatency.GAME,
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.AUTO, policy.ownership)
    }

    @Test
    fun `existing game or video baseline is manual and never auto claimed`() {
        val policy = AutoGameModePolicy()

        policy.observeConfirmedMode(AutoGameModePolicy.LowLatency.GAME)
        assertEquals(AutoGameModePolicy.Ownership.MANUAL, policy.ownership)
        assertNull(
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.GAME,
            ),
        )

        val videoBaseline = AutoGameModePolicy()
        videoBaseline.observeConfirmedMode(AutoGameModePolicy.LowLatency.VIDEO)
        assertEquals(AutoGameModePolicy.Ownership.MANUAL, videoBaseline.ownership)
    }

    @Test
    fun `evaluation records a pre-existing game as a manual baseline`() {
        val policy = AutoGameModePolicy()

        assertNull(
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.GAME,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.MANUAL, policy.ownership)
    }

    @Test
    fun `known non-game restores music only after auto claimed game`() {
        val policy = AutoGameModePolicy()
        policy.evaluate(
            enabled = true,
            classification = AutoGameModePolicy.Classification.GAME,
            confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
        )

        assertEquals(
            AutoGameModePolicy.LowLatency.MUSIC,
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.NON_GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.GAME,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.NONE, policy.ownership)
    }

    @Test
    fun `manual action prevents automatic restoration`() {
        val policy = AutoGameModePolicy()
        policy.evaluate(
            enabled = true,
            classification = AutoGameModePolicy.Classification.GAME,
            confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
        )

        policy.markManualAction()

        assertEquals(AutoGameModePolicy.Ownership.MANUAL, policy.ownership)
        assertNull(
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.NON_GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.GAME,
            ),
        )
    }

    @Test
    fun `unknown classification never changes a confirmed music baseline`() {
        val policy = AutoGameModePolicy()

        assertNull(
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.UNKNOWN,
                confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.NONE, policy.ownership)
        assertEquals(
            AutoGameModePolicy.LowLatency.GAME,
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
            ),
        )
    }

    @Test
    fun `reset clears manual ownership and permits a new music baseline`() {
        val policy = AutoGameModePolicy()
        policy.observeConfirmedMode(AutoGameModePolicy.LowLatency.VIDEO)

        policy.reset()

        assertEquals(AutoGameModePolicy.Ownership.NONE, policy.ownership)
        assertEquals(
            AutoGameModePolicy.LowLatency.GAME,
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
            ),
        )
    }

    @Test
    fun `disconnect clears automatic ownership and ignores unknown classification`() {
        val policy = AutoGameModePolicy()
        policy.evaluate(
            enabled = true,
            classification = AutoGameModePolicy.Classification.GAME,
            confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
        )

        assertNull(
            policy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.UNKNOWN,
                confirmedMode = AutoGameModePolicy.LowLatency.GAME,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.AUTO, policy.ownership)

        policy.onDisconnected()

        assertEquals(AutoGameModePolicy.Ownership.NONE, policy.ownership)
    }

    @Test
    fun `automatic game transition requires every safety precondition`() {
        val disabledPolicy = AutoGameModePolicy()
        assertNull(
            disabledPolicy.evaluate(
                enabled = false,
                classification = AutoGameModePolicy.Classification.GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.NONE, disabledPolicy.ownership)

        val nonGamePolicy = AutoGameModePolicy()
        assertNull(
            nonGamePolicy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.NON_GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.NONE, nonGamePolicy.ownership)

        val unconfirmedPolicy = AutoGameModePolicy()
        assertNull(
            unconfirmedPolicy.evaluate(
                enabled = true,
                classification = AutoGameModePolicy.Classification.GAME,
                confirmedMode = null,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.NONE, unconfirmedPolicy.ownership)
    }

    @Test
    fun `automatic ownership does not restore music from video`() {
        val policy = AutoGameModePolicy()
        policy.evaluate(
            enabled = true,
            classification = AutoGameModePolicy.Classification.GAME,
            confirmedMode = AutoGameModePolicy.LowLatency.MUSIC,
        )

        assertNull(
            policy.evaluate(
                enabled = false,
                classification = AutoGameModePolicy.Classification.NON_GAME,
                confirmedMode = AutoGameModePolicy.LowLatency.VIDEO,
            ),
        )
        assertEquals(AutoGameModePolicy.Ownership.AUTO, policy.ownership)
    }

    @Test
    fun `only the initial confirmed baseline decides manual ownership`() {
        val policy = AutoGameModePolicy()
        policy.observeConfirmedMode(AutoGameModePolicy.LowLatency.MUSIC)

        policy.observeConfirmedMode(AutoGameModePolicy.LowLatency.GAME)

        assertEquals(AutoGameModePolicy.Ownership.NONE, policy.ownership)
    }
}
