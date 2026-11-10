package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for the outbound RFCOMM log bridge.
 *
 * The queue must treat records, rather than equal strings, as the unit of acknowledgement:
 * identical log messages may be produced in a tight loop. The bridge itself is deliberately
 * JVM-only so these concurrency guarantees can be checked without Android binder mocks.
 */
class RemoteLogBridgeTest {

    @Test
    fun `acknowledging an old equal line keeps a newer equal line`() {
        val queue = RemoteLogQueue<String>(maxPending = 8, maxBatch = 1)
        queue.enqueue("same")
        val inFlight = checkNotNull(queue.beginBatch())
        queue.enqueue("same")

        assertTrue(queue.acknowledge(inFlight))
        assertEquals(listOf("same"), queue.pendingValues())
    }

    @Test
    fun `stale acknowledgement after clear cannot remove post-clear data`() {
        val queue = RemoteLogQueue<String>(maxPending = 8, maxBatch = 1)
        queue.enqueue("same")
        val beforeClear = checkNotNull(queue.beginBatch())
        queue.clear()
        queue.enqueue("same")

        assertFalse(queue.acknowledge(beforeClear))
        assertEquals(listOf("same"), queue.pendingValues())
    }

    @Test
    fun `cleared rejected batch is no longer current even when post-clear records exist`() {
        val queue = RemoteLogQueue<String>(maxPending = 8, maxBatch = 1)
        queue.enqueue("before-clear")
        val rejected = checkNotNull(queue.beginBatch())
        assertEquals(listOf("before-clear"), queue.reject(rejected))

        queue.clear()
        queue.enqueue("after-clear")

        // A fallback writer for `rejected` must not use a non-empty queue as a proxy for
        // liveness: that queue now owns only post-clear records.
        assertFalse(queue.isGenerationCurrent(rejected))
        assertEquals(listOf("after-clear"), queue.pendingValues())
    }

    @Test
    fun `bounded queue returns newest overflow for fallback without disturbing FIFO data`() {
        val queue = RemoteLogQueue<String>(maxPending = 2, maxBatch = 1)
        assertTrue(queue.enqueue("first").isEmpty())
        assertTrue(queue.enqueue("second").isEmpty())

        assertEquals(listOf("third"), queue.enqueue("third"))
        assertEquals(listOf("first", "second"), queue.pendingValues())
    }

    @Test
    fun `concurrent equal enqueues retain every record`() {
        val queue = RemoteLogQueue<String>(maxPending = 256, maxBatch = 256)
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val complete = CountDownLatch(4)
        try {
            repeat(4) {
                executor.execute {
                    ready.countDown()
                    assertTrue("workers did not start", start.await(2, TimeUnit.SECONDS))
                    repeat(50) { assertTrue(queue.enqueue("same").isEmpty()) }
                    complete.countDown()
                }
            }
            assertTrue("workers did not become ready", ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertTrue("concurrent enqueues did not finish", complete.await(2, TimeUnit.SECONDS))

            assertEquals(200, queue.pendingCount())
            assertEquals(200, queue.pendingValues().count { it == "same" })
            assertTrue(queue.acknowledge(checkNotNull(queue.beginBatch())))
            assertEquals(0, queue.pendingCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `quiet failed batch retries without another enqueue and records fallback once`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val attempts = AtomicInteger()
            val fallbackCalls = AtomicInteger()
            val delivered = CountDownLatch(1)
            val bridge = SerializedRemoteLogBridge(
                maxPending = 8,
                maxBatch = 20,
                flushIntervalMs = 20,
                executor = executor,
                send = { lines ->
                    if (attempts.incrementAndGet() == 1) {
                        false
                    } else {
                        assertEquals(listOf("quiet"), lines)
                        delivered.countDown()
                        true
                    }
                },
                fallback = { lines ->
                    assertEquals(listOf("quiet"), lines)
                    fallbackCalls.incrementAndGet()
                },
            )

            bridge.enqueue("quiet")

            assertTrue("quiet batch was not retried", delivered.await(2, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())
            assertEquals(1, fallbackCalls.get())
            // deliver 回调在 send() 内先 countDown，桥接器随后才把批次从 pending 摘掉；
            // 这里等它落账，避免断言跑在清理之前（整套测试并发时尤其明显）。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (bridge.pendingCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            assertEquals(0, bridge.pendingCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `clear barrier waits for an in-flight send before clearing canonical state`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val sendStarted = CountDownLatch(1)
            val releaseSend = CountDownLatch(1)
            val cleared = CountDownLatch(1)
            val bridge = SerializedRemoteLogBridge(
                maxPending = 8,
                maxBatch = 1,
                flushIntervalMs = 1_000,
                executor = executor,
                send = {
                    sendStarted.countDown()
                    assertTrue("test send was not released", releaseSend.await(2, TimeUnit.SECONDS))
                    true
                },
                fallback = { error("fallback is not expected") },
            )

            bridge.enqueue("before-clear")
            assertTrue("send did not start", sendStarted.await(2, TimeUnit.SECONDS))
            bridge.clearThen { cleared.countDown() }

            assertFalse("clear overtook an in-flight send", cleared.await(100, TimeUnit.MILLISECONDS))
            releaseSend.countDown()
            assertTrue("clear barrier did not run", cleared.await(2, TimeUnit.SECONDS))
            assertEquals(0, bridge.pendingCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `hex safely handles a negative display limit`() {
        assertEquals(" …(+3)", RfcommLog.hex(byteArrayOf(0x01, 0x02, 0x03), limit = -1))
    }

    @Test
    fun `async dispatcher keeps newest bounded FIFO entries off the enqueue caller`() {
        val executor = Executors.newSingleThreadExecutor()
        val workerReady = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val observed = mutableListOf<String>()
        val callbackThreads = mutableListOf<String>()
        try {
            executor.execute {
                workerReady.countDown()
                releaseWorker.await(2, TimeUnit.SECONDS)
            }
            assertTrue("worker did not block", workerReady.await(2, TimeUnit.SECONDS))

            val callerThread = Thread.currentThread().name
            val dispatcher = SerializedAsyncDispatcher<String>(
                maxPending = 2,
                executor = executor,
            ) { line ->
                observed += line
                callbackThreads += Thread.currentThread().name
                delivered.countDown()
            }

            dispatcher.enqueue("first")
            dispatcher.enqueue("second")
            dispatcher.enqueue("third")
            assertEquals(2, dispatcher.pendingCount())

            releaseWorker.countDown()
            assertTrue("bounded entries were not dispatched", delivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("second", "third"), observed)
            assertTrue(callbackThreads.all { it != callerThread })
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `sender never runs on the enqueue caller thread`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val callerThread = Thread.currentThread().name
            val senderThread = AtomicReference<String>()
            val sent = CountDownLatch(1)
            val bridge = SerializedRemoteLogBridge(
                maxPending = 8,
                maxBatch = 1,
                flushIntervalMs = 1_000,
                executor = executor,
                send = {
                    senderThread.set(Thread.currentThread().name)
                    sent.countDown()
                    true
                },
                fallback = { error("fallback is not expected") },
            )

            bridge.enqueue("immediate")

            assertTrue("batch was not dispatched", sent.await(2, TimeUnit.SECONDS))
            assertNotEquals(callerThread, senderThread.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
