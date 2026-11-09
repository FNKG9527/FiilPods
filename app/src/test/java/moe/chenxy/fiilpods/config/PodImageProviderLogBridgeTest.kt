package moe.chenxy.fiilpods.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-level boundary checks for the exported provider's log-only call path. */
class PodImageProviderLogBridgeTest {

    @Test
    fun `appendLog trusts only this module or the resolved bluetooth uid`() {
        assertTrue(PodImageProvider.isTrustedLogCaller(callingUid = 10001, moduleUid = 10001, bluetoothUid = 10042))
        assertTrue(PodImageProvider.isTrustedLogCaller(callingUid = 10042, moduleUid = 10001, bluetoothUid = 10042))
        assertFalse(PodImageProvider.isTrustedLogCaller(callingUid = 19999, moduleUid = 10001, bluetoothUid = 10042))
        assertFalse(PodImageProvider.isTrustedLogCaller(callingUid = 19999, moduleUid = 10001, bluetoothUid = null))
    }

    @Test
    fun `appendLog rejects oversized line counts before conversion`() {
        assertTrue(
            PodImageProvider.isBoundedAppendPayload(
                lines = arrayOf("one", "two"),
                maxLines = 2,
                maxUtf8Bytes = 64,
            ),
        )
        assertFalse(
            PodImageProvider.isBoundedAppendPayload(
                lines = arrayOf("one", "two", "three"),
                maxLines = 2,
                maxUtf8Bytes = 64,
            ),
        )
    }

    @Test
    fun `appendLog counts utf8 bytes including written newlines`() {
        // `你` is three UTF-8 bytes and appendBridgedLines writes one newline.
        assertTrue(
            PodImageProvider.isBoundedAppendPayload(
                lines = arrayOf("你"),
                maxLines = 1,
                maxUtf8Bytes = 4,
            ),
        )
        assertFalse(
            PodImageProvider.isBoundedAppendPayload(
                lines = arrayOf("你"),
                maxLines = 1,
                maxUtf8Bytes = 3,
            ),
        )
    }

    @Test
    fun `appendLog rejects embedded line breaks so one caller entry stays one file line`() {
        assertFalse(
            PodImageProvider.isBoundedAppendPayload(
                lines = arrayOf("first\nforged"),
                maxLines = 1,
                maxUtf8Bytes = 64,
            ),
        )
        assertFalse(
            PodImageProvider.isBoundedAppendPayload(
                lines = arrayOf("first\rforged"),
                maxLines = 1,
                maxUtf8Bytes = 64,
            ),
        )
    }

    @Test
    fun `appendLog rejects malformed surrogate input instead of undercounting it`() {
        assertFalse(
            PodImageProvider.isBoundedAppendPayload(
                lines = arrayOf("broken\uD800"),
                maxLines = 1,
                maxUtf8Bytes = 64,
            ),
        )
    }

    @Test
    fun `appendLog rejects null array elements before materializing a list`() {
        assertFalse(
            PodImageProvider.isBoundedAppendPayload(
                lines = arrayOf<String?>("valid", null),
                maxLines = 2,
                maxUtf8Bytes = 64,
            ),
        )
    }
}
