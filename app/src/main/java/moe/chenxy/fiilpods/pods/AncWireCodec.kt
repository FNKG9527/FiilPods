package moe.chenxy.fiilpods.pods

/** The protocol family that defines ANC wire-value ordering. */
enum class AncWireDialect {
    MATE,
    GAIA,
}

/** ANC meaning independent of protocol byte ordering and UI implementation details. */
enum class AncSemantic {
    OFF,
    NC,
    TRANSPARENCY,
    LISTEN,
    WIND,
}

/**
 * Pure JVM codec for FIIL ANC values.
 *
 * Mate and GAIA agree on OFF/NC/WIND but reverse the wire meanings of raw values 2 and 3.
 * Conversion functions deliberately return null for unknown raw values so callers cannot
 * silently treat malformed or unsupported device state as OFF.
 */
object AncWireCodec {
    /** Converts a protocol-specific ANC wire value (0..4) into its semantic meaning. */
    fun semanticFromWire(dialect: AncWireDialect, raw: Int): AncSemantic? = when (dialect) {
        AncWireDialect.MATE -> when (raw) {
            0 -> AncSemantic.OFF
            1 -> AncSemantic.NC
            2 -> AncSemantic.TRANSPARENCY
            3 -> AncSemantic.LISTEN
            4 -> AncSemantic.WIND
            else -> null
        }

        AncWireDialect.GAIA -> when (raw) {
            0 -> AncSemantic.OFF
            1 -> AncSemantic.NC
            2 -> AncSemantic.LISTEN
            3 -> AncSemantic.TRANSPARENCY
            4 -> AncSemantic.WIND
            else -> null
        }
    }

    /** Converts ANC meaning to the wire value required by [dialect]. */
    fun wireFromSemantic(dialect: AncWireDialect, semantic: AncSemantic): Int = when (dialect) {
        AncWireDialect.MATE -> when (semantic) {
            AncSemantic.OFF -> 0
            AncSemantic.NC -> 1
            AncSemantic.TRANSPARENCY -> 2
            AncSemantic.LISTEN -> 3
            AncSemantic.WIND -> 4
        }

        AncWireDialect.GAIA -> when (semantic) {
            AncSemantic.OFF -> 0
            AncSemantic.NC -> 1
            AncSemantic.LISTEN -> 2
            AncSemantic.TRANSPARENCY -> 3
            AncSemantic.WIND -> 4
        }
    }

    /** Converts ANC meaning to the one-based Oppo-compatible status consumed by UI clients. */
    fun oppoStatusFromSemantic(semantic: AncSemantic): Int = when (semantic) {
        AncSemantic.OFF -> OPPO_STATUS_OFF
        AncSemantic.NC -> OPPO_STATUS_NC
        AncSemantic.TRANSPARENCY -> OPPO_STATUS_TRANSPARENCY
        AncSemantic.LISTEN -> OPPO_STATUS_LISTEN
        AncSemantic.WIND -> OPPO_STATUS_WIND
    }

    /** Converts a dialect-specific wire value directly to an Oppo-compatible UI status. */
    fun oppoStatusFromWire(dialect: AncWireDialect, raw: Int): Int? =
        semanticFromWire(dialect, raw)?.let(::oppoStatusFromSemantic)

    const val OPPO_STATUS_OFF = 1
    const val OPPO_STATUS_NC = 2
    const val OPPO_STATUS_TRANSPARENCY = 3
    const val OPPO_STATUS_LISTEN = 9
    const val OPPO_STATUS_WIND = 10
}
