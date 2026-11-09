package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateBroadcastTargetsTest {
    @Test
    fun `complete state reaches module and every system status host`() {
        val modulePackage = "io.github.fnkg9527.fiilpods"
        val targets = StateBroadcastTargets.packages(modulePackage)

        assertEquals(targets.size, targets.toSet().size)
        assertTrue(modulePackage in targets)
        assertTrue("com.android.settings" in targets)
        assertTrue("com.android.bluetooth" in targets)
        assertTrue("com.xiaomi.bluetooth" in targets)
        assertTrue("com.milink.service" in targets)
    }
}
