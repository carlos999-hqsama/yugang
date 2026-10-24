package com.personaldashboard.app

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real streaming behavior: ordered bounded chunks, terminal metadata, limits and cancellation. */
class PluginFetchStreamTest {
    @Test
    fun parentCancellationDuringSessionInitializationDoesNotInvokeAnUnassignedDetach() {
        val parent = PluginCancellation().apply { cancel() }
        val request =
            PluginFetchStream.parse(
                JSONObject()
                    .put("url", "https://example.com/never-sent")
                    .put("requestId", "parent-cancel-construction"),
            )
        val session = PluginFetchStream.StreamSession(request, parent, System.nanoTime())
        try {
            assertTrue(session.call.isCanceled())
            val result = session.poll()
            assertTrue(result.getBoolean("done"))
            assertEquals(
                "cancelled",
                result.getJSONArray("events").getJSONObject(0).getString("code"),
            )
            assertFalse(PluginFetch.calls.containsKey(request.requestId))
        } finally {
            session.close()
        }
    }

    private fun drain(requestId: String): List<JSONObject> {
        val events = mutableListOf<JSONObject>()
        while (true) {
            val value = PluginFetchStream.poll(requestId)
            for (i in 0 until value.getJSONArray("events").length()) events +=
                value.getJSONArray("events").getJSONObject(i)
            if (value.getBoolean("done")) return events
            Thread.sleep(10)
        }
    }

    @Test
    fun `chunks arrive in order with response metadata and empty bodies are explicit`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setChunkedBody("abcdef", 2),
            )
            server.start()
            val start =
                PluginFetchStream.start(
                    JSONObject().put("url", server.url("/items").toString()),
                    PluginCancellation(),
                )
            val events = drain(start.getString("requestId"))
            val chunks = events.filter { it.getString("kind") == "chunk" }
            val first = chunks.first()
            assertEquals("chunk", first.getString("kind"))
            assertTrue(first.getBoolean("first"))
            assertTrue(chunks.size > 1)
            assertEquals(200, first.getInt("status"))
            assertEquals("application/octet-stream", first.getString("contentType"))
            assertEquals(server.url("/items").toString(), first.getString("finalUrl"))
            assertTrue(first.getLong("firstChunkMs") >= 0)
            assertEquals("abcdef", chunks.joinToString("") { decode(it.getString("base64")) })
            assertEquals(6L, chunks.last().getLong("bytesSoFar"))
            assertEquals(1, chunks.count { it.getBoolean("first") })
            val done = events.last()
            assertEquals("done", done.getString("kind"))
            assertFalse(done.getBoolean("emptyBody"))
            assertEquals(6L, done.getLong("bytesSoFar"))

            server.enqueue(MockResponse().setResponseCode(204))
            val empty =
                PluginFetchStream.start(
                    JSONObject().put("url", server.url("/empty").toString()),
                    PluginCancellation(),
                )
            val emptyEvents = drain(empty.getString("requestId"))
            assertEquals("done", emptyEvents.single().getString("kind"))
            assertTrue(emptyEvents.single().getBoolean("emptyBody"))
            assertTrue(emptyEvents.single().isNull("firstChunkMs"))
        }
    }

    @Test
    fun `exceeding max bytes cancels the call and reports structured failure`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("x".repeat(2048)),
            )
            server.start()
            val start =
                PluginFetchStream.start(
                    JSONObject().put("url", server.url("/large").toString()).put("maxBytes", 1024),
                    PluginCancellation(),
                )
            val events = drain(start.getString("requestId"))
            val error = events.last()
            assertEquals("error", error.getString("kind"))
            assertEquals("response_too_large", error.getString("code"))
            assertEquals(200, error.getInt("status"))
            assertEquals("application/octet-stream", error.getString("contentType"))
            assertFalse(PluginFetch.calls.containsKey(start.getString("requestId")))
        }
    }

    @Test
    fun `a full queue preserves order then reports backpressure`() {
        val body =
            (0 until PluginFetchStream.CHUNK_BYTES * (PluginFetchStream.MAX_QUEUE_CHUNKS + 1))
                .joinToString("") { ('a'.code + it % 23).toChar().toString() }
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setChunkedBody(body, PluginFetchStream.CHUNK_BYTES),
            )
            server.start()
            val start =
                PluginFetchStream.start(
                    JSONObject().put("url", server.url("/slow-consumer").toString()),
                    PluginCancellation(),
                )
            val id = start.getString("requestId")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (PluginFetch.calls.containsKey(id) && System.nanoTime() < deadline) Thread.sleep(
                10,
            )
            assertFalse(PluginFetch.calls.containsKey(id))
            val events = drain(id)
            val chunks = events.filter { it.getString("kind") == "chunk" }
            assertEquals(PluginFetchStream.MAX_QUEUE_CHUNKS, chunks.size)
            val received = chunks.joinToString("") { decode(it.getString("base64")) }
            assertEquals(body.take(received.length), received)
            assertTrue(chunks.all { it.getInt("chunkBytes") <= PluginFetchStream.CHUNK_BYTES })
            assertEquals("backpressure", events.last().getString("code"))
            assertTrue(events.last().getBoolean("retryable"))
        }
    }

    @Test
    fun `cancel interrupts the real OkHttp call`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("delayed")
                    .setBodyDelay(2, TimeUnit.SECONDS),
            )
            server.start()
            val start =
                PluginFetchStream.start(
                    JSONObject().put("url", server.url("/cancel").toString()),
                    PluginCancellation(),
                )
            val requestId = start.getString("requestId")
            val call = PluginFetch.calls[requestId]
            Thread.sleep(100)
            assertTrue(PluginFetchStream.cancel(requestId))
            assertTrue(call!!.isCanceled())
            assertFalse(PluginFetch.calls.containsKey(requestId))
        }
    }

    @Test
    fun `caller lifecycle discards completed but unread streams`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("done"))
            server.start()
            val parent = PluginCancellation()
            val id =
                PluginFetchStream.start(
                        JSONObject().put("url", server.url("/finished").toString()),
                        parent,
                    )
                    .getString("requestId")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (PluginFetch.calls.containsKey(id) && System.nanoTime() < deadline) Thread.sleep(
                10,
            )
            assertFalse(PluginFetch.calls.containsKey(id))
            parent.cancel()
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                PluginFetchStream.poll(id)
            }
        }
    }

    @Test
    fun `timeout interrupts a stalled body and reports elapsed request metadata`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("late")
                    .setBodyDelay(2, TimeUnit.SECONDS),
            )
            server.start()
            val start =
                PluginFetchStream.start(
                    JSONObject()
                        .put("url", server.url("/stalled").toString())
                        .put("timeoutMs", 1000),
                    PluginCancellation(),
                )
            val events = drain(start.getString("requestId"))
            val error = events.single()
            assertEquals("error", error.getString("kind"))
            assertEquals("timeout", error.getString("code"))
            assertTrue(error.getBoolean("timeout"))
            assertEquals(200, error.getInt("status"))
            assertEquals(server.url("/stalled").toString(), error.getString("finalUrl"))
            assertFalse(PluginFetch.calls.containsKey(start.getString("requestId")))
        }
    }

    @Test
    fun `cancel before queued start rejects the scoped request without starting a call`() {
        try {
            MockWebServer().use { server ->
                server.start()
                val scoped =
                    PluginPlatform.streamId("pending-plugin", "r1", "page:owner", "same-id")
                val cancelled =
                    PluginPlatform.cancelPageStream(
                        "pending-plugin",
                        "r1",
                        "same-id",
                        "page:owner",
                    )
                assertTrue(cancelled.getBoolean("cancelled"))
                val request =
                    PluginFetchStream.parse(JSONObject().put("url", server.url("/late").toString()))
                        .copy(requestId = scoped)
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    PluginFetchStream.start(request, PluginCancellation(), System.nanoTime(), {})
                }
                assertFalse(PluginFetch.calls.containsKey(scoped))
                assertEquals(0, server.requestCount)
            }
        } finally {
            PluginFetchStream.discardPendingCancels("pending-plugin")
        }
    }

    @Test
    fun `a pending cancel cannot cross plugin revision or owner scope`() {
        try {
            MockWebServer().use { server ->
                server.start()
                listOf(
                        Triple("other-plugin", "r1", "page:owner"),
                        Triple("pending-owner-plugin", "r2", "page:owner"),
                        Triple("pending-owner-plugin", "r1", "page:other"),
                    )
                    .forEach { (pluginId, revision, owner) ->
                        assertTrue(
                            PluginPlatform.cancelPageStream(pluginId, revision, "shared-id", owner)
                                .getBoolean("cancelled"),
                        )
                    }
                val scoped =
                    PluginPlatform.streamId("pending-owner-plugin", "r1", "page:owner", "shared-id")
                val parent = PluginCancellation()
                PluginFetchStream.start(
                    PluginFetchStream.parse(
                            JSONObject().put("url", server.url("/owner").toString()),
                        )
                        .copy(requestId = scoped),
                    parent,
                    System.nanoTime(),
                    {},
                )
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!PluginFetch.calls.containsKey(scoped) && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
                assertTrue(PluginFetch.calls.containsKey(scoped))
                parent.cancel()
                val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (
                    PluginFetch.calls.containsKey(scoped) && System.nanoTime() < cleanupDeadline
                ) {
                    Thread.sleep(10)
                }
                assertFalse(PluginFetch.calls.containsKey(scoped))
            }
        } finally {
            PluginFetchStream.discardPendingCancels("other-plugin")
            PluginFetchStream.discardPendingCancels("pending-owner-plugin")
        }
    }

    @Test
    fun `pending cancels expire and retain only a bounded plugin cleanup set`() {
        val nowNanos = System.nanoTime()
        val expiring = PluginPlatform.streamId("pending-bound", "r1", "page:a", "expire")
        assertTrue(PluginFetchStream.cancelBeforeStart(expiring, nowNanos))
        assertFalse(
            PluginFetchStream.cancelBeforeStart(
                expiring,
                nowNanos + PluginFetchStream.PENDING_CANCEL_MS * 1_000_000 + 1,
            ),
        )
        assertFalse(PluginFetchStream.pendingCancels.containsKey(expiring))

        (0 until PluginFetchStream.MAX_PENDING_CANCELS + 1).forEach { index ->
            val key = PluginPlatform.streamId("pending-bound", "r1", "page:$index", "id-$index")
            assertTrue(PluginFetchStream.cancelBeforeStart(key, nowNanos))
        }
        assertEquals(PluginFetchStream.MAX_PENDING_CANCELS, PluginFetchStream.pendingCancels.size)

        val prefixPlugin = "pending-bound-prefix"
        PluginFetchStream.cancelBeforeStart(
            PluginPlatform.streamId(prefixPlugin, "r1", "page:a", "id"),
            nowNanos,
        )
        PluginFetchStream.discardPendingCancels(prefixPlugin)
        assertTrue(
            PluginFetchStream.pendingCancels.keys.none {
                it.startsWith("$prefixPlugin\u0000")
            },
        )
    }

    private fun decode(value: String): String =
        String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)

    @Test
    fun samePublicRequestIdIsIndependentAcrossPluginRevisionAndCaller() {
        MockWebServer().use { server ->
            val owners =
                listOf(
                    Triple("a", "r1", "page:one"),
                    Triple("b", "r1", "page:one"),
                    Triple("a", "r2", "page:one"),
                    Triple("a", "r1", "action:one"),
                )
            val keys = owners.map { (id, revision, owner) ->
                PluginPlatform.streamId(id, revision, owner, "same")
            }
            assertEquals(4, keys.toSet().size)
            server.start()
            val bodies = owners.indices.map { "payload-$it" }.toSet()
            bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
            keys.forEach { key ->
                val request =
                    PluginFetchStream.parse(JSONObject().put("url", server.url("/").toString()))
                PluginFetchStream.start(
                    request.copy(requestId = key),
                    PluginCancellation(),
                    System.nanoTime(),
                    {},
                )
            }
            val received =
                keys
                    .map { key ->
                        drain(key)
                            .filter { it.getString("kind") == "chunk" }
                            .joinToString("") { decode(it.getString("base64")) }
                    }
                    .toSet()
            assertEquals(bodies, received)
        }
    }
}
