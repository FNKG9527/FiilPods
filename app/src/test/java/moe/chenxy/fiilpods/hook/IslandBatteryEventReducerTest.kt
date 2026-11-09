package moe.chenxy.fiilpods.hook

import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.pods.FiilStatus
import moe.chenxy.fiilpods.pods.IslandTimingPolicy
import moe.chenxy.fiilpods.pods.IslandTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandBatteryEventReducerTest {
    @Test
    fun `status-derived unchanged battery false to true wear edge is rendered once`() {
        val reducer = IslandBatteryEventReducer()
        val policy = IslandTimingPolicy(setOf(ConfigManager.ISLAND_SHOW_TIMING_WEARING))
        val notWorn = status(wearLeft = false)
        val worn = status(wearLeft = true)
        val notWornSnapshot = reducer.snapshotFromStatus(notWorn)
        val wornSnapshot = reducer.snapshotFromStatus(worn)

        assertNotNull(notWornSnapshot)
        assertNotNull(wornSnapshot)
        assertEquals(notWornSnapshot, wornSnapshot)
        assertEquals(IslandTrigger.WEARING, policy.triggerForWear(false, false, worn))

        reducer.markStatusDerived(wornSnapshot!!, worn)
        // ACTION_PODS_BATTERY_CHANGED commonly follows ACTION_STATE with the same values.
        // It must not render this WEARING edge a second time.
        assertTrue(reducer.consumeDuplicateBattery(wornSnapshot, worn))
        assertFalse(reducer.consumeDuplicateBattery(wornSnapshot, worn))
    }

    @Test
    fun `status-derived unchanged battery true to false wear edge is rendered once`() {
        val reducer = IslandBatteryEventReducer()
        val policy = IslandTimingPolicy(setOf(ConfigManager.ISLAND_SHOW_TIMING_REMOVED))
        val worn = status(wearLeft = true)
        val removed = status(wearLeft = false)
        val wornSnapshot = reducer.snapshotFromStatus(worn)
        val removedSnapshot = reducer.snapshotFromStatus(removed)

        assertNotNull(wornSnapshot)
        assertNotNull(removedSnapshot)
        assertEquals(wornSnapshot, removedSnapshot)
        assertEquals(IslandTrigger.REMOVED, policy.triggerForWear(true, false, removed))

        reducer.markStatusDerived(removedSnapshot!!, removed)
        assertTrue(reducer.consumeDuplicateBattery(removedSnapshot, removed))
        assertFalse(reducer.consumeDuplicateBattery(removedSnapshot, removed))
    }

    @Test
    fun `a different battery action consumes rather than wrongly suppresses the stale status event`() {
        val reducer = IslandBatteryEventReducer()
        val status = status(wearLeft = true)
        val statusSnapshot = reducer.snapshotFromStatus(status)!!
        val changedBattery = statusSnapshot.copy(
            left = statusSnapshot.left!!.copy(battery = statusSnapshot.left.battery - 1),
        )

        reducer.markStatusDerived(statusSnapshot, status)

        assertFalse(reducer.consumeDuplicateBattery(changedBattery, status))
        assertFalse(reducer.consumeDuplicateBattery(statusSnapshot, status))
    }

    @Test
    fun `status without a live connected battery snapshot is not routed`() {
        val reducer = IslandBatteryEventReducer()

        assertNull(reducer.snapshotFromStatus(FiilStatus(connected = false, batteryLeft = 80)))
        assertNull(reducer.snapshotFromStatus(FiilStatus(connected = true)))
    }

    private fun status(wearLeft: Boolean): FiilStatus = FiilStatus(
        connected = true,
        address = "12:34:56:78:90:AB",
        name = "FIIL",
        earType = 46,
        batteryLeft = 80,
        batteryRight = 80,
        wearLeft = wearLeft,
        wearRight = false,
    )
}
