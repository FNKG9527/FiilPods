package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchKeyPayloadTest {

    @Test
    fun sensitivityPayloadUsesOfficialSideAndLevelBytes() {
        // 左耳低/中/高 → {1,44}/{1,37}/{1,73}
        assertArrayEquals(byteArrayOf(1, 44), TouchKeyPayload.sensitivityPayload(0, 0))
        assertArrayEquals(byteArrayOf(1, 37), TouchKeyPayload.sensitivityPayload(0, 1))
        assertArrayEquals(byteArrayOf(1, 73), TouchKeyPayload.sensitivityPayload(0, 2))
        // 右耳低/中/高 → {0,44}/{0,37}/{0,73}
        assertArrayEquals(byteArrayOf(0, 44), TouchKeyPayload.sensitivityPayload(1, 0))
        assertArrayEquals(byteArrayOf(0, 73), TouchKeyPayload.sensitivityPayload(1, 2))
    }

    @Test
    fun sensitivityPayloadClampsUnknownLevel() {
        assertArrayEquals(byteArrayOf(1, 44), TouchKeyPayload.sensitivityPayload(0, -3))
        assertArrayEquals(byteArrayOf(1, 73), TouchKeyPayload.sensitivityPayload(0, 9))
    }

    @Test
    fun parseSensitivityReadsRightThenLeft() {
        // payload[1]=右耳=中(37)，payload[2]=左耳=高(73)
        val (left, right) = TouchKeyPayload.parseSensitivity(byteArrayOf(0, 37, 73))!!
        assertEquals(2, left)
        assertEquals(1, right)
    }

    @Test
    fun parseSensitivityRejectsShortPayload() {
        assertNull(TouchKeyPayload.parseSensitivity(byteArrayOf(0, 37)))
        assertNull(TouchKeyPayload.parseSensitivity(ByteArray(0)))
    }

    @Test
    fun parseSensitivityMarksUnknownWire() {
        val (left, right) = TouchKeyPayload.parseSensitivity(byteArrayOf(0, 99, 44))!!
        assertEquals(0, left)
        assertEquals(-1, right)
    }

    @Test
    fun parseKeyActionsAcceptsCountPrefix() {
        val payload = ByteArray(31)
        payload[0] = 30
        // 10 组：keyId, type=1, value
        for (i in 0 until 10) {
            payload[1 + i * 3] = (i + 1).toByte()
            payload[2 + i * 3] = 1
            payload[3 + i * 3] = (i + 2).toByte()
        }
        val actions = TouchKeyPayload.parseKeyActions(payload)!!
        assertEquals(11, actions.size)
        assertEquals(-1, actions[0])
        assertEquals(2, actions[1])
        assertEquals(11, actions[10])
    }

    @Test
    fun parseKeyActionsAcceptsLayoutWithoutPrefix() {
        val payload = ByteArray(30)
        for (i in 0 until 10) {
            payload[i * 3] = (i + 1).toByte()
            payload[i * 3 + 1] = 1
            payload[i * 3 + 2] = 7
        }
        val actions = TouchKeyPayload.parseKeyActions(payload)!!
        assertEquals(7, actions[1])
        assertEquals(7, actions[10])
    }

    @Test
    fun parseKeyActionsRejectsShortPayload() {
        assertNull(TouchKeyPayload.parseKeyActions(ByteArray(20)))
    }

    /**
     * 官方 F049/F051/F053 的 `onRead` 只要 `buff.length >= 24`，即只有 8 组。
     * 旧实现硬要求 30 字节，这类机型（含 Key Max 只回短包的情况）永远解析失败。
     */
    @Test
    fun parseKeyActionsAcceptsEightGroupPayload() {
        val payload = ByteArray(24)
        for (i in 0 until 8) {
            payload[i * 3] = (i + 1).toByte()
            payload[i * 3 + 1] = 1
            payload[i * 3 + 2] = 3
        }
        val actions = TouchKeyPayload.parseKeyActions(payload)!!
        assertEquals(11, actions.size)
        assertEquals(3, actions[1])
        assertEquals(3, actions[8])
        // 9/10 号键没出现在回包里，保持未知而不是被当成「无操作」。
        assertEquals(-1, actions[9])
        assertEquals(-1, actions[10])
    }

    @Test
    fun parseKeyActionsAcceptsEightGroupsWithCountPrefix() {
        val payload = ByteArray(25)
        payload[0] = 30
        for (i in 0 until 8) {
            payload[1 + i * 3] = (i + 1).toByte()
            payload[1 + i * 3 + 1] = 1
            payload[1 + i * 3 + 2] = 7
        }
        val actions = TouchKeyPayload.parseKeyActions(payload)!!
        assertEquals(7, actions[1])
        assertEquals(7, actions[8])
    }

    /** 回包能解出 10 组时，不能因为首选布局只解出 8 组就丢掉后两个键。 */
    @Test
    fun parseKeyActionsKeepsAllTenGroups() {
        val payload = ByteArray(30)
        for (i in 0 until 10) {
            payload[i * 3] = (i + 1).toByte()
            payload[i * 3 + 1] = 1
            payload[i * 3 + 2] = 4
        }
        val actions = TouchKeyPayload.parseKeyActions(payload)!!
        assertEquals(4, actions[1])
        assertEquals(4, actions[10])
    }

    /** 回包到了但一个键都没解出来 → 全 -1（UI 据此显示「该机型未上报」）。 */
    @Test
    fun parseKeyActionsMarksAllUnknownForGarbage() {
        val payload = ByteArray(24) { 0x55 }
        val actions = TouchKeyPayload.parseKeyActions(payload)!!
        assertEquals(11, actions.size)
        assertEquals(0, actions.count { it >= 0 })
    }

    @Test
    fun mateConstantsMatchOfficialCommands() {
        assertEquals(34, FiilMate.Cid.SET_KEY)
        assertEquals(5, FiilMate.Query.KEY)
    }
}
