package moe.chenxy.fiilpods.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class CardCommandRouteTest {
    @Test
    fun `adaptive card command keeps kind and boolean flag`() {
        assertEquals(
            CardCommandRoute.Adaptive(kind = "NOISE", enabled = true),
            CardCommandRoute.normalize("adaptive:NOISE", null, true),
        )
    }

    @Test
    fun `ldac card command uses authoritative ldac route`() {
        assertEquals(
            CardCommandRoute.Ldac(enabled = false),
            CardCommandRoute.normalize("ldac", null, false),
        )
    }

    @Test
    fun `ordinary card command is passed through unchanged`() {
        assertEquals(
            CardCommandRoute.Command(command = "lowLatencyMode", value = 2, flag = null),
            CardCommandRoute.normalize("lowLatencyMode", 2, null),
        )
    }
}
