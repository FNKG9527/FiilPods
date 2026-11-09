package moe.chenxy.fiilpods.pods

/** Pure JVM ownership policy for automatic game-mode transitions. */
class AutoGameModePolicy {
    enum class Ownership {
        NONE,
        AUTO,
        MANUAL,
    }

    enum class Classification {
        GAME,
        NON_GAME,
        UNKNOWN,
    }

    enum class LowLatency {
        MUSIC,
        GAME,
        VIDEO,
    }

    var ownership: Ownership = Ownership.NONE
        private set

    private var initialModeObserved = false

    /**
     * Records the first confirmed headset mode for this connection without issuing a command.
     * A pre-existing game or video mode belongs to the user, not to automation.
     */
    fun observeConfirmedMode(mode: LowLatency) {
        if (initialModeObserved) return

        initialModeObserved = true
        if (ownership == Ownership.NONE && mode != LowLatency.MUSIC) {
            ownership = Ownership.MANUAL
        }
    }

    /** Records a user-initiated low-latency action so automation will not override it. */
    fun markManualAction() {
        initialModeObserved = true
        ownership = Ownership.MANUAL
    }

    /** Clears connection-scoped ownership and waits for a new confirmed baseline. */
    fun reset() {
        initialModeObserved = false
        ownership = Ownership.NONE
    }

    fun onDisconnected() = reset()

    /** Returns the one low-latency command that is safe to issue, or null for no command. */
    fun evaluate(
        enabled: Boolean,
        classification: Classification,
        confirmedMode: LowLatency?,
    ): LowLatency? {
        confirmedMode?.let(::observeConfirmedMode)
        if (classification == Classification.UNKNOWN) return null

        if (
            enabled &&
            classification == Classification.GAME &&
            confirmedMode == LowLatency.MUSIC &&
            ownership == Ownership.NONE
        ) {
            ownership = Ownership.AUTO
            return LowLatency.GAME
        }

        if (
            classification == Classification.NON_GAME &&
            confirmedMode == LowLatency.GAME &&
            ownership == Ownership.AUTO
        ) {
            ownership = Ownership.NONE
            return LowLatency.MUSIC
        }

        return null
    }
}
