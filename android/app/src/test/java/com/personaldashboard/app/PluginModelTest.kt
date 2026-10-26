package com.personaldashboard.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PluginModelTest {
    private val directories = mutableListOf<java.io.File>()
    private fun directory() = Files.createTempDirectory("pd-plugin-model").toFile().also { directories += it }
    @org.junit.After fun cleanDirectories() { directories.forEach { it.deleteRecursively() } }
    private fun store(dir: java.io.File) = PluginModelStore(dir)

    private fun response(
        text: String? = "ok",
        total: Int = 37,
        choices: JSONArray = JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", text))
            .put("finish_reason", "stop"))
    ): String = JSONObject()
        .put("choices", choices)
        .put("usage", JSONObject()
            .put("prompt_tokens", 12)
            .put("completion_tokens", 25)
            .put("total_tokens", total))
        .toString()

    private fun request(max: Int = 10) = JSONObject()
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
        .put("maxOutputTokens", max)

    @Test
    fun `connection failure before HTTP headers releases the unsent reservation`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val budgets=store(directory())
        val result=PluginModel.forTests(budgets,null,"synthetic-key","http://127.0.0.1:$port/chat/completions")
            .call("model-plugin",request())
        assertFalse(result.getBoolean("ok"))
        assertEquals("known_not_sent",result.getString("outcome"))
        assertEquals("model_connection_failed_before_request", result.getJSONObject("error").getString("code"))
        val record = budgets.record("model-plugin", "2026-10-23")
        assertEquals(0L, record.reservedTokens)
        assertEquals(0L, record.usedTokens)
    }

    @Test
    fun `status exposes current official models and accounting without secrets`() {
        val result = PluginModel.forTests(store(directory()), null, "", "http://127.0.0.1:1")
            .call("model-plugin", JSONObject().put("op", "status"))
        assertFalse(result.getBoolean("configured"))
        assertEquals("deepseek-flash", result.getString("defaultModel"))
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), result.getJSONArray("allowedModels").let { a ->
            (0 until a.length()).map { a.getString(it) }
        })
        assertEquals("disabled", result.getString("thinking"))
        assertEquals(0L, result.getJSONObject("budget").getLong("reservedTokens"))
        assertFalse(result.toString().contains("secret", ignoreCase = true))
        assertFalse(result.toString().contains("Authorization"))
    }

    @Test
    fun `successful call uses official models fixed non-thinking mode and provider usage`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response(total = 37)))
            server.start()
            val model = PluginModel.forTests(store(directory()), null, "secret-key", server.url("/chat/completions").toString())
            val result = model.call("model-plugin", request())
            assertTrue(result.getBoolean("ok"))
            assertEquals("deepseek-flash", result.getString("model"))
            assertEquals(37L, result.getJSONObject("usage").getLong("totalTokens"))
            assertEquals(0L, result.getJSONObject("budget").getLong("reservedTokens"))

            val recorded = server.takeRequest()
            val sent = JSONObject(recorded.body.readUtf8())
            assertEquals("deepseek-flash", sent.getString("model"))
            assertEquals("disabled", sent.getJSONObject("thinking").getString("type"))
            assertEquals(10, sent.getInt("max_tokens"))
            assertEquals("Bearer secret-key", recorded.getHeader("Authorization"))
        }
    }

    @Test
    fun `valid usage is settled even when output shape is invalid`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response(text = null, choices = JSONArray())))
            server.start()
            val budgets = store(directory())
            val result = PluginModel.forTests(budgets, null, "key", server.url("/chat/completions").toString())
                .call("model-plugin", request())
            assertFalse(result.getBoolean("ok"))
            assertEquals("invalid_output", result.getString("outcome"))
            assertEquals("model_output_invalid", result.getJSONObject("error").getString("code"))
            val record = budgets.record("model-plugin", "2026-10-23")
            assertEquals(37L, record.usedTokens)
            assertEquals(0L, record.reservedTokens)
        }
    }

    @Test
    fun `missing usage is unknown and keeps its reservation`() {
        MockWebServer().use { server ->
            val noUsage = JSONObject(response())
            noUsage.remove("usage")
            server.enqueue(MockResponse().setBody(noUsage.toString()))
            server.start()
            val budgets = store(directory())
            budgets.setBudget("model-plugin",100,"2026-10-23")
            val model = PluginModel.forTests(budgets, null, "key", server.url("/chat/completions").toString())
            val first = model.call("model-plugin", request(60))
            assertEquals("unknown", first.getString("outcome"))
            val reserved = first.getJSONObject("budget").getLong("reservedTokens")
            assertTrue(reserved > 0)
            assertEquals(0L, first.getJSONObject("budget").getLong("usedTokens"))

            // The unknown reservation cannot be silently released and spent again.
            val rejected = model.call("model-plugin", request(60))
            assertFalse(rejected.getBoolean("ok"))
            assertEquals("quota", rejected.getJSONObject("error").getString("code"))
            assertEquals(
                PluginModelPolicy.REASON_RESERVATION_EXCEEDS_REMAINING,
                rejected.getJSONObject("error").getString("reason"),
            )
            assertFalse(rejected.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `http error and cancellation after send are unknown and reserve conservatively`() {
        MockWebServer().use { server ->
            server.start()
            val budgets = store(directory())
            val model = PluginModel.forTests(budgets, null, "key", server.url("/chat/completions").toString())
            server.enqueue(MockResponse().setResponseCode(500))
            val http = model.call("model-plugin", request().put("timeoutMs", 5_000L))
            assertEquals("unknown", http.getString("outcome"))
            assertEquals("model_http_error", http.getJSONObject("error").getString("code"))
            assertTrue(http.getJSONObject("budget").getLong("reservedTokens") > 0)
            assertEquals(1, server.requestCount)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancellation = PluginCancellation()
            val pool = Executors.newSingleThreadExecutor()
            try {
                val pending = pool.submit<JSONObject> {
                    model.call("other-plugin", JSONObject(request().toString())
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "second"))), cancellation)
                }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                cancellation.cancel()
                val cancelled = pending.get(5, TimeUnit.SECONDS)
                assertEquals("unknown", cancelled.getString("outcome"))
                assertTrue(cancelled.getJSONObject("budget").getLong("reservedTokens") > 0)
            } finally {
                cancellation.cancel(); pool.shutdownNow()
            }
        }
    }

    @Test
    fun `budget exhaustion and unknown reservations prevent a network request`() {
        MockWebServer().use { server ->
            server.start()
            val budgets = store(directory())
            budgets.setBudget("model-plugin", 100L, "2026-10-23")
            val model = PluginModel.forTests(budgets, null, "key", server.url("/chat/completions").toString())
            val insufficient = model.call("model-plugin", request().put("maxOutputTokens", 100))
            assertFalse(insufficient.getBoolean("ok"))
            assertEquals("known_not_sent", insufficient.getString("outcome"))
            assertEquals("quota", insufficient.getJSONObject("error").getString("code"))
            assertEquals(
                PluginModelPolicy.REASON_RESERVATION_EXCEEDS_REMAINING,
                insufficient.getJSONObject("error").getString("reason"),
            )
            assertEquals(100L, insufficient.getJSONObject("budget").getLong("limitTokens"))
            assertEquals(0, server.requestCount)

            budgets.setBudget("model-plugin", 200L, "2026-10-23")
            val noUsage = JSONObject(response())
            noUsage.remove("usage")
            server.enqueue(MockResponse().setBody(noUsage.toString()))
            val unknown = model.call("model-plugin", request(100))
            assertTrue(unknown.getJSONObject("budget").getLong("reservedTokens") > 0)
            val reserved = model.call("model-plugin", request(100))
            assertFalse(reserved.getBoolean("ok"))
            assertEquals("quota", reserved.getJSONObject("error").getString("code"))
            assertEquals(
                PluginModelPolicy.REASON_RESERVATION_EXCEEDS_REMAINING,
                reserved.getJSONObject("error").getString("reason"),
            )
            assertFalse(reserved.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `duplicate plugin calls are rejected while global capacity is available`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val model = PluginModel.forTests(store(directory()), null, "key", server.url("/chat/completions").toString())
            val cancellation = PluginCancellation()
            val pool = Executors.newSingleThreadExecutor()
            try {
                val first = pool.submit<JSONObject> { model.call("model-plugin", request(), cancellation) }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                try {
                    model.call("model-plugin", request())
                    throw AssertionError("同插件不得并发重复调用")
                } catch (_: IllegalStateException) {}
                cancellation.cancel()
                assertEquals("unknown", first.get(3, TimeUnit.SECONDS).getString("outcome"))
            } finally {
                cancellation.cancel(); pool.shutdownNow()
            }
        }
    }

    @Test
    fun `real provider usage above budget is reported instead of hidden`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response(total = 150)))
            server.start()
            val budgets = store(directory())
            budgets.setBudget("model-plugin", 100L, "2026-10-23")
            val result = PluginModel.forTests(budgets, null, "key", server.url("/chat/completions").toString())
                .call(
                    "model-plugin",
                    JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                        .put("maxOutputTokens", 80)
                )
            assertTrue(result.getBoolean("ok"))
            assertTrue(result.getBoolean("overBudget"))
            val record = budgets.record("model-plugin", "2026-10-23")
            assertEquals(150L, record.usedTokens)
            assertEquals(0L, record.availableTokens)
        }
    }

    @Test(timeout = 8_000L)
    fun `call timeout keeps its reservation as unknown`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val budgets = store(directory())
            val result = PluginModel.forTests(budgets, null, "key", server.url("/chat/completions").toString())
                .call("model-plugin", request().put("timeoutMs", 5_000L))
            assertEquals("unknown", result.getString("outcome"))
            assertEquals("model_network_unknown", result.getJSONObject("error").getString("code"))
            assertTrue(result.getJSONObject("budget").getLong("reservedTokens") > 0)
            assertEquals(0L, budgets.record("model-plugin", "2026-10-23").usedTokens)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `large responses are rejected by streaming bound before payload parsing`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(PluginModelPolicy.MAX_RESPONSE_BYTES + 1)))
            server.start()
            val result = PluginModel.forTests(store(directory()), null, "key", server.url("/chat/completions").toString())
                .call("model-plugin", request())
            assertEquals("unknown", result.getString("outcome"))
            assertEquals("model_network_unknown", result.getJSONObject("error").getString("code"))
            assertFalse(result.toString().contains("xxxxx"))
        }
    }

    @Test
    fun `cross origin redirect is refused and no retry occurs`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://evil.example/steal"))
            server.start()
            val result = PluginModel.forTests(store(directory()), null, "key", server.url("/chat/completions").toString())
                .call("model-plugin", request())
            assertEquals("unknown", result.getString("outcome"))
            assertEquals("model_cross_origin_redirect", result.getJSONObject("error").getString("code"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `sdk accepts no arbitrary extra parameters and defaults translation to zh-CN`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response(total = 30)))
            server.start()
            val model = PluginModel.forTests(store(directory()), null, "key", server.url("/chat/completions").toString())
            try {
                model.call("model-plugin", request().put("temperature", 1.0))
                throw AssertionError("不得透传任意模型参数")
            } catch (_: IllegalArgumentException) {}

            val translate = model.call(
                "model-plugin",
                JSONObject().put("op", "translate").put("text", "hello").put("maxOutputTokens", 10)
            )
            assertTrue(translate.getBoolean("ok"))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            val content = body.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(content.contains("zh-CN"))
        }
    }

    @Test
    fun `interleaved store instances keep usage for two plugins`() {
        val dir = directory()
        val a = store(dir); val b = store(dir)
        a.reserve("plugin-a", 30L, "2026-10-23", 200_000L)
        b.reserve("plugin-b", 40L, "2026-10-23", 200_000L)
        val ready = CountDownLatch(2)
        val done = CountDownLatch(2)
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2) { index ->
                pool.execute {
                    ready.countDown()
                    runCatching {
                        if (index == 0) a.settleUsage("plugin-a", 20L, 30L, "2026-10-23")
                        else b.settleUsage("plugin-b", 35L, 40L, "2026-10-23")
                    }.onFailure { failures.incrementAndGet() }
                    done.countDown()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(done.await(2, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, failures.get())
        val first = a.record("plugin-a", "2026-10-23")
        val second = b.record("plugin-b", "2026-10-23")
        assertEquals(20L to 0L, first.usedTokens to first.reservedTokens)
        assertEquals(35L to 0L, second.usedTokens to second.reservedTokens)

        // A new day resets accounting but retains each user-configured limit.
        a.setBudget("plugin-a", 500L, "2026-10-23")
        b.setBudget("plugin-b", 7_000L, "2026-10-23")
        assertEquals(500L, a.record("plugin-a", "2026-10-24").budgetTokens)
        assertEquals(7_000L, b.record("plugin-b", "2026-10-24").budgetTokens)
        assertEquals(0L, a.record("plugin-a", "2026-10-24").usedTokens)
        assertEquals(0L, b.record("plugin-b", "2026-10-24").reservedTokens)
    }

    @Test
    fun `corrupt model storage fails closed instead of resetting budgets`() {
        val dir = directory()
        store(dir).setBudget("model-plugin", 500L, "2026-10-23")
        java.io.File(dir, "model-budgets.json").writeText("{broken")
        try {
            store(dir).record("model-plugin", "2026-10-23")
            throw AssertionError("损坏预算存储不能被静默重置")
        } catch (_: IllegalStateException) {}
    }
}
