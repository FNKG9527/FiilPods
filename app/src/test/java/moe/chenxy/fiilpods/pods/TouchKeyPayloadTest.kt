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

    @Test
    fun mateConstantsMatchOfficialCommands() {
        assertEquals(34, FiilMate.Cid.SET_KEY)
        assertEquals(5, FiilMate.Query.KEY)
    }
}
