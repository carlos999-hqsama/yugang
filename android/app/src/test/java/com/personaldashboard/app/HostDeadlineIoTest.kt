package com.personaldashboard.app

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostDeadlineIoTest {
    @Test
    fun expiredGuardsUnblockTwoWritersAndLeaveNoQueuedExpiry() {
        HostDeadlineGuard.Scope("deadline-writer-test").use { scope ->
            repeat(2) {
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                    Socket(InetAddress.getLoopbackAddress(), server.localPort).use { reader ->
                        server.accept().use { writer ->
                            reader.receiveBufferSize = 1024
                            writer.sendBufferSize = 1024
                            val started = CountDownLatch(1)
                            val finished = CountDownLatch(1)
                            val cancelled = CountDownLatch(1)
                            val completed = AtomicBoolean(false)
                            val thread = Thread {
                                try {
                                    started.countDown()
                                    writer.getOutputStream().write(ByteArray(8 * 1024 * 1024))
                                    completed.set(true)
                                } catch (_: java.io.IOException) {
                                    // Total deadline closes the socket while the peer does not
                                    // read.
                                } finally {
                                    finished.countDown()
                                }
                            }
                                .apply {
                                    isDaemon = true
                                    start()
                                }
                            try {
                                assertTrue(started.await(2, TimeUnit.SECONDS))
                                assertFalse(
                                    "a non-reading peer must leave the response blocked",
                                    finished.await(50, TimeUnit.MILLISECONDS),
                                )
                                val guard = scope.arm(30) { writer.close() }
                                guard.bindCancellation { cancelled.countDown() }
                                assertTrue(finished.await(3, TimeUnit.SECONDS))
                                assertTrue(cancelled.await(3, TimeUnit.SECONDS))
                                assertFalse(completed.get())
                            } finally {
                                writer.close()
                                thread.join(2000)
                            }
                        }
                    }
                }
            }
            assertTrue(scope.live.isEmpty())
            assertEquals(0, scope.queuedExpiries)
            val normal = scope.arm(5000) { fail("finished request expired") }
            normal.finish()
            assertEquals(0, scope.queuedExpiries)
        }
    }

    @Test
    fun deadlineCancelsTheRealFetchAndReleasesItsRequestId() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val requestId = UUID.randomUUID().toString()
            val cancellation = PluginCancellation()
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "deadline-fetch-test").apply { isDaemon = true }
            }
            try {
                val result =
                    executor.submit<JSONObject> {
                        PluginFetch.fetch(
                            JSONObject()
                                .put("url", server.url("/wait").toString())
                                .put("requestId", requestId)
                                .put("timeoutMs", 15000),
                            cancellation,
                        )
                    }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                val closed = AtomicBoolean(false)
                HostDeadlineGuard.Scope("deadline-fetch-guard").use { scope ->
                    val guard = scope.arm(30) { closed.set(true) }
                    guard.bindCancellation { cancellation.cancel() }
                    // Native transport errors are typed exceptions; the WebView bridge
                    // turns this metadata into the documented rejected Promise.
                    val failure =
                        assertThrows(java.util.concurrent.ExecutionException::class.java) {
                                result.get(3, TimeUnit.SECONDS)
                            }
                            .cause
                    assertTrue(closed.get())
                    assertTrue(failure is PluginRequestException)
                    assertTrue((failure as PluginRequestException).cancelled)
                    assertEquals("cancelled", failure.code)
                    assertFalse(PluginFetch.calls.containsKey(requestId))
                }
            } finally {
                cancellation.cancel()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun serviceScopeCloseDisarmsEveryQueuedGuard() {
        val scope = HostDeadlineGuard.Scope("deadline-stop-test")
        val closed = AtomicBoolean(false)
        repeat(4) { scope.arm(10000) { closed.set(true) } }
        assertEquals(4, scope.live.size)
        scope.close()
        assertTrue(scope.live.isEmpty())
        assertEquals(0, scope.queuedExpiries)
        assertFalse(closed.get())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            Thread.getAllStackTraces().keys.any { it.name == "deadline-stop-test" } &&
                System.nanoTime() < deadline
        ) Thread.sleep(5)
        assertFalse(Thread.getAllStackTraces().keys.any { it.name == "deadline-stop-test" })
        scope.close()
        assertThrows(java.util.concurrent.RejectedExecutionException::class.java) {
            scope.arm(1000) {}
        }
    }

    @Test
    fun idleServiceDoesNotCreateDeadlineThreads() {
        val name = "deadline-idle-contract"
        HostDeadlineGuard.Scope(name).use {
            assertFalse(Thread.getAllStackTraces().keys.any { it.name == name })
        }
    }
}
