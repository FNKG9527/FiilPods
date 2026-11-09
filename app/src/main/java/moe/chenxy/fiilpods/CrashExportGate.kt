package moe.chenxy.fiilpods

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards the crash-page export action.
 *
 * The framework-only crash page has no Compose coroutine scope, and reading/log-window
 * selection can take enough time for a second tap to start a concurrent export. Keep
 * that policy separate from the Activity so it stays deterministic and unit-testable.
 */
class CrashExportGate {
    private val exporting = AtomicBoolean(false)

    /** Returns true only for the caller that acquired the single in-flight export slot. */
    fun tryBegin(): Boolean = exporting.compareAndSet(false, true)

    /** Releases the slot after the background export attempt finishes. */
    fun finish() {
        exporting.set(false)
    }

    fun isExporting(): Boolean = exporting.get()
}
