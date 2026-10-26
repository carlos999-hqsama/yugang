package com.personaldashboard.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real loopback streaming, receipt reuse, response bounds and cancellation behavior. */
class BotAttachmentTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun transferAt(
        baseUrl: String,
        validate: (String, String) -> Unit = { _, _ -> },
    ): BotAttachmentTransfer =
        BotAttachmentTransfer.forLocalLoopbackTest(
            temporary.newFolder(),
            baseUrl,
            "dsh-auth=test-cookie",
            validate,
        )

    private fun transfer(
        server: MockWebServer,
        validate: (String, String) -> Unit = { _, _ -> },
    ): BotAttachmentTransfer =
        transferAt(server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'), validate)

    private fun arguments(
        handle: String,
        requestId: String,
        botId: String = "bot-1",
        sessionId: String = "session-1",
    ): JSONObject =
        JSONObject()
            .put("handle", handle)
            .put("requestId", requestId)
            .put("botId", botId)
            .put("sessionId", sessionId)

    private fun success(receiptId: String, name: String, bytes: Int): String =
        JSONObject()
            .put("ok", true)
            .put(
                "value",
                JSONObject()
                    .put("receiptId", receiptId)
                    .put(
                        "file",
                        JSONObject()
                            .put("attachmentId", "digest-$receiptId")
                            .put("name", name)
                            .put("bytes", bytes),
                    ),
            )
            .toString()

    @Test
    fun `streams the original bytes and reuses only a receipt from the same session`() {
        MockWebServer().use { server ->
            server.start()
            val validated = CopyOnWriteArrayList<Pair<String, String>>()
            val transfer = transfer(server) { bot, session -> validated += bot to session }
            try {
                val bytes = byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte(), 0x0a)
                val handle = transfer.stageTestBytesForLocalLoopback("年度 报告.bin", bytes)
                val progress = CopyOnWriteArrayList<JSONObject>()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(success("receipt-1", "年度 报告.bin", bytes.size)),
                )

                val first =
                    transfer.upload(
                        arguments(handle, "request-1"),
                        progress = { progress += JSONObject(it.toString()) },
                    )
                assertEquals("receipt-1", first.getString("receiptId"))
                assertEquals("digest-receipt-1", first.getJSONObject("file").getString("attachmentId"))
                assertEquals("年度 报告.bin", first.getString("name"))
                assertEquals("file", first.getString("kind"))
                assertEquals(bytes.size.toLong(), first.getLong("bytes"))
                assertFalse(first.has("uri"))

                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("POST", request.method)
                assertEquals("/api/session/uploadFileBinary", request.requestUrl!!.encodedPath)
                assertEquals("session-1", request.requestUrl!!.queryParameter("sessionId"))
                assertEquals("年度 报告.bin", request.requestUrl!!.queryParameter("name"))
                assertEquals("application/octet-stream", request.getHeader("Content-Type"))
                assertEquals("dsh-auth=test-cookie", request.getHeader("Cookie"))
                assertArrayEquals(bytes, request.body.readByteArray())

                assertTrue(progress.isNotEmpty())
                assertTrue(progress.all { it.getString("phase") == "uploading" })
                assertEquals("request-1", progress.first().getString("requestId"))
                assertEquals(handle, progress.first().getString("handle"))
                assertEquals(bytes.size.toLong(), progress.last().getLong("total"))
                assertEquals(bytes.size.toLong(), progress.last().getLong("bytes"))

                val reused = transfer.upload(arguments(handle, "request-2"))
                assertEquals("receipt-1", reused.getString("receiptId"))
                val reusedFromAnotherBot =
                    transfer.upload(arguments(handle, "request-2b", botId = "bot-2"))
                assertEquals("receipt-1", reusedFromAnotherBot.getString("receiptId"))
                assertEquals(1, server.requestCount)

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(success("receipt-2", "年度 报告.bin", bytes.size)),
                )
                val otherSession =
                    transfer.upload(
                        arguments(handle, "request-3", sessionId = "session-2"),
                    )
                assertEquals("receipt-2", otherSession.getString("receiptId"))
                assertEquals(2, server.requestCount)
                val secondRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("session-2", secondRequest.requestUrl!!.queryParameter("sessionId"))
                assertArrayEquals(bytes, secondRequest.body.readByteArray())
                assertTrue(validated.contains("bot-1" to "session-1"))
                assertTrue(validated.contains("bot-1" to "session-2"))
            } finally {
                transfer.close()
            }
        }
    }

    @Test
    fun `session validation happens before any network send`() {
        val transfer =
            transferAt("http://127.0.0.1:9") { _, _ ->
                throw IllegalStateException("session is no longer owned by this bot")
            }
        try {
            val handle = transfer.stageTestBytesForLocalLoopback("file.txt", byteArrayOf(1, 2, 3))
            val failure =
                assertThrows(BotAttachmentException::class.java) {
                    transfer.upload(arguments(handle, "invalid-session"))
                }
            assertEquals("session_invalid", failure.code)
            assertFalse(failure.message.contains("no longer owned"))
        } finally {
            transfer.close()
        }
    }

    @Test
    fun `non-loopback configuration and close never start a request`() {
        val transfer = transferAt("https://127.0.0.1:9")
        try {
            val handle = transfer.stageTestBytesForLocalLoopback("file.txt", byteArrayOf(1))
            val failure =
                assertThrows(BotAttachmentException::class.java) {
                    transfer.upload(arguments(handle, "non-loopback"))
                }
            assertEquals("loopback_required", failure.code)
        } finally {
            transfer.close()
        }
        val closed =
            assertThrows(BotAttachmentException::class.java) {
                transfer.stageTestBytesForLocalLoopback("after-close.txt", byteArrayOf(1))
            }
        assertEquals("closed", closed.code)
    }

    @Test
    fun `idle remove reports deleted and prevents handle reuse`() {
        val transfer = transferAt("http://127.0.0.1:9")
        try {
            val handle = transfer.stageTestBytesForLocalLoopback("remove-idle.txt", byteArrayOf(1))
            val removed = transfer.remove(handle)
            assertTrue(removed.getBoolean("removed"))
            assertTrue(removed.getBoolean("deleted"))
            assertEquals(0, removed.getInt("cancelledUploads"))
            val missing =
                assertThrows(BotAttachmentException::class.java) {
                    transfer.upload(arguments(handle, "removed-handle"))
                }
            assertEquals("handle_not_found", missing.code)
        } finally {
            transfer.close()
        }
    }

    @Test
    fun `rejected malformed and redirected responses never become success`() {
        MockWebServer().use { server ->
            server.start()
            val transfer = transfer(server)
            try {
                val handle = transfer.stageTestBytesForLocalLoopback("file.txt", byteArrayOf(1, 2, 3))

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"ok":false,"error":{"message":"provider detail"}}"""),
                )
                val rejected =
                    assertThrows(BotAttachmentException::class.java) {
                        transfer.upload(arguments(handle, "rejected"))
                    }
                assertEquals("upload_rejected", rejected.code)
                assertFalse(rejected.message.contains("provider detail"))
                assertFalse(rejected.outcomeUnknown)

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """{"ok":true,"value":{"receiptId":"r","file":{"attachmentId":"a","name":"n","bytes":999}}}""",
                        ),
                )
                val malformed =
                    assertThrows(BotAttachmentException::class.java) {
                        transfer.upload(arguments(handle, "malformed"))
                    }
                assertEquals("invalid_response", malformed.code)
                assertTrue(malformed.outcomeUnknown)

                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", server.url("/must-not-follow")),
                )
                val redirected =
                    assertThrows(BotAttachmentException::class.java) {
                        transfer.upload(arguments(handle, "redirected"))
                    }
                assertEquals("redirect_rejected", redirected.code)
                assertEquals(3, server.requestCount)
            } finally {
                transfer.close()
            }
        }
    }

    @Test
    fun `response bodies are capped before parsing`() {
        MockWebServer().use { server ->
            server.start()
            val transfer = transfer(server)
            try {
                val handle = transfer.stageTestBytesForLocalLoopback("file.txt", byteArrayOf(1))
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("x".repeat(BotAttachmentPolicy.MAX_RESPONSE_BYTES + 1)),
                )
                val failure =
                    assertThrows(BotAttachmentException::class.java) {
                        transfer.upload(arguments(handle, "too-large-response"))
                    }
                assertEquals("response_too_large", failure.code)
                assertTrue(failure.outcomeUnknown)
            } finally {
                transfer.close()
            }
        }
    }

    @Test
    fun `cancel before registration is exact`() {
        val transfer = transferAt("http://127.0.0.1:9")
        try {
            val handle =
                transfer.stageTestBytesForLocalLoopback(
                    "cancel.bin",
                    ByteArray(1024) { it.toByte() },
                )
            assertTrue(transfer.cancel("queued-request"))
            val queued =
                assertThrows(BotAttachmentException::class.java) {
                    transfer.upload(arguments(handle, "queued-request"))
                }
            assertEquals("cancelled", queued.code)
            assertFalse(transfer.cancel(""))
        } finally {
            transfer.close()
        }
    }

    @Test
    fun `active cancel reaches OkHttp and reports an unknown server outcome`() {
        MockWebServer().use { server ->
            server.start()
            val transfer = transfer(server)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val bytes = ByteArray(32 * 1024) { it.toByte() }
                val handle = transfer.stageTestBytesForLocalLoopback("cancel.bin", bytes)
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(success("late", "cancel.bin", bytes.size))
                        .setBodyDelay(3, TimeUnit.SECONDS),
                )
                val sent = CountDownLatch(1)
                val future =
                    executor.submit<JSONObject> {
                        transfer.upload(arguments(handle, "active-request")) { event ->
                            if (event.getLong("bytes") == event.getLong("total")) sent.countDown()
                        }
                    }
                assertTrue(sent.await(3, TimeUnit.SECONDS))
                assertTrue(transfer.cancel("active-request"))
                val thrown = assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
                val failure = thrown.cause as BotAttachmentException
                assertEquals("cancelled", failure.code)
                assertTrue(failure.cancelled)
                assertTrue(failure.outcomeUnknown)
                server.takeRequest(2, TimeUnit.SECONDS)
                server.enqueue(MockResponse().setBody(success("retried", "cancel.bin", bytes.size)))
                val retried=transfer.upload(arguments(handle,"active-request"))
                assertEquals("retried",retried.getString("receiptId"))
                assertTrue(server.takeRequest(2,TimeUnit.SECONDS) != null)
            } finally {
                executor.shutdownNow()
                transfer.close()
            }
        }
    }

    @Test
    fun `remove deletes the staged file and cancels that handle upload`() {
        MockWebServer().use { server ->
            server.start()
            val transfer = transfer(server)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val bytes = ByteArray(16 * 1024) { it.toByte() }
                val handle = transfer.stageTestBytesForLocalLoopback("remove.bin", bytes)
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(success("late", "remove.bin", bytes.size))
                        .setBodyDelay(3, TimeUnit.SECONDS),
                )
                val sent = CountDownLatch(1)
                val future =
                    executor.submit<JSONObject> {
                        transfer.upload(arguments(handle, "remove-active")) { event ->
                            if (event.getLong("bytes") == event.getLong("total")) sent.countDown()
                        }
                    }
                assertTrue(sent.await(3, TimeUnit.SECONDS))
                val removed = transfer.remove(handle)
                assertTrue(removed.getBoolean("removed"))
                assertTrue(removed.getBoolean("deleted"))
                assertEquals(1, removed.getInt("cancelledUploads"))
                val thrown = assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
                assertTrue((thrown.cause as BotAttachmentException).cancelled)

                val missing =
                    assertThrows(BotAttachmentException::class.java) {
                        transfer.upload(arguments(handle, "after-remove"))
                    }
                assertEquals("handle_not_found", missing.code)
            } finally {
                executor.shutdownNow()
                transfer.close()
            }
        }
    }
}
