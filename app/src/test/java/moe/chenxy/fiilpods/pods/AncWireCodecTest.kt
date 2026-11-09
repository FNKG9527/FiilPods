package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AncWireCodecTest {
    @Test
    fun `Mate raw table preserves ANC semantics and wire round trips`() {
        val expectedByRaw = listOf(
            AncSemantic.OFF,
            AncSemantic.NC,
            AncSemantic.TRANSPARENCY,
            AncSemantic.LISTEN,
            AncSemantic.WIND,
        )

        expectedByRaw.forEachIndexed { raw, semantic ->
            assertEquals(semantic, AncWireCodec.semanticFromWire(AncWireDialect.MATE, raw))
            assertEquals(raw, AncWireCodec.wireFromSemantic(AncWireDialect.MATE, semantic))
        }
    }

    @Test
    fun `GAIA raw table preserves ANC semantics and wire round trips`() {
        val expectedByRaw = listOf(
            AncSemantic.OFF,
            AncSemantic.NC,
            AncSemantic.LISTEN,
            AncSemantic.TRANSPARENCY,
            AncSemantic.WIND,
        )

        expectedByRaw.forEachIndexed { raw, semantic ->
            assertEquals(semantic, AncWireCodec.semanticFromWire(AncWireDialect.GAIA, raw))
            assertEquals(raw, AncWireCodec.wireFromSemantic(AncWireDialect.GAIA, semantic))
        }
    }

    @Test
    fun `Oppo statuses are derived from dialect-aware wire semantics`() {
        val mateExpectedByRaw = listOf(1, 2, 3, 9, 10)
        val gaiaExpectedByRaw = listOf(1, 2, 9, 3, 10)

        mateExpectedByRaw.forEachIndexed { raw, status ->
            assertEquals(status, AncWireCodec.oppoStatusFromWire(AncWireDialect.MATE, raw))
        }
        gaiaExpectedByRaw.forEachIndexed { raw, status ->
            assertEquals(status, AncWireCodec.oppoStatusFromWire(AncWireDialect.GAIA, raw))
        }
    }

    @Test
    fun `unknown raw values remain unknown rather than becoming OFF`() {
        val invalidValues = listOf(-1, 5, Int.MIN_VALUE, Int.MAX_VALUE)

        AncWireDialect.entries.forEach { dialect ->
            invalidValues.forEach { raw ->
                assertNull(AncWireCodec.semanticFromWire(dialect, raw))
                assertNull(AncWireCodec.oppoStatusFromWire(dialect, raw))
            }
        }
    }

    @Test
    fun `Oppo semantic status table is stable across wire dialects`() {
        val expectedBySemantic = mapOf(
            AncSemantic.OFF to 1,
            AncSemantic.NC to 2,
            AncSemantic.TRANSPARENCY to 3,
            AncSemantic.LISTEN to 9,
            AncSemantic.WIND to 10,
        )

        expectedBySemantic.forEach { (semantic, status) ->
            assertEquals(status, AncWireCodec.oppoStatusFromSemantic(semantic))
        }
    }
}
