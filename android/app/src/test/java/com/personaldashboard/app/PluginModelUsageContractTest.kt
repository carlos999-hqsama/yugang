package com.personaldashboard.app

import java.nio.file.Files
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginModelUsageContractTest {
    private val directories = mutableListOf<java.io.File>()

    @After
    fun cleanDirectories() {
        directories.forEach { it.deleteRecursively() }
    }

    private fun directory() =
        Files.createTempDirectory("pd-plugin-model-usage").toFile().also { directories += it }

    @Test
    fun `strict usage accepts positive total and zero-valued fallback components`() {
        val explicit =
            JSONObject()
                .put("total_tokens", 37)
                .put("prompt_tokens", "ignored-when-total-is-valid")
                .put("completion_tokens", JSONObject.NULL)
        assertEquals(37L, PluginModelPolicy.parseUsage(payload(explicit)))
        assertEquals(
            37L,
            PluginModelPolicy.parseUsage(
                payload(JSONObject().put("prompt_tokens", 0).put("completion_tokens", 37)),
            ),
        )
        assertEquals(
            37L,
            PluginModelPolicy.parseUsage(
                payload(JSONObject().put("prompt_tokens", 37).put("completion_tokens", 0)),
            ),
        )
    }

    @Test
    fun `invalid explicit total never falls back and keeps unknown reservation`() {
        for (invalid in listOf(100.9, "100", 100.0, 0, -1, JSONObject.NULL)) {
            val usage =
                JSONObject()
                    .put("total_tokens", invalid)
                    .put("prompt_tokens", 12)
                    .put("completion_tokens", 25)
            assertUsageInvalid(usage)
        }

        MockWebServer().use { server ->
            server.start()
            val budgets = PluginModelStore(directory())
            val model =
                PluginModel.forTests(
                    budgets,
                    null,
                    "synthetic-key",
                    server.url("/chat/completions").toString(),
                )
            for ((index, invalid) in listOf(100.9, "100").withIndex()) {
                val usage =
                    JSONObject()
                        .put("total_tokens", invalid)
                        .put("prompt_tokens", 12)
                        .put("completion_tokens", 25)
                server.enqueue(MockResponse().setBody(payload(usage).toString()))
                val result = model.call("usage-plugin-$index", request())
                assertUnknownUsage(result, budgets, "usage-plugin-$index")
                assertEquals(index + 1, server.requestCount)
            }
        }
    }

    @Test
    fun `invalid fallback and overflowing sum are unknown instead of fabricated usage`() {
        for (usage in
            listOf(
                JSONObject().put("prompt_tokens", 0).put("completion_tokens", 0),
                JSONObject().put("prompt_tokens", -1).put("completion_tokens", 2),
                JSONObject().put("prompt_tokens", 1.5).put("completion_tokens", 2),
                JSONObject().put("prompt_tokens", 1).put("completion_tokens", "2"),
            )) {
            assertUsageInvalid(usage)
        }

        MockWebServer().use { server ->
            val overflow =
                JSONObject()
                    .put("prompt_tokens", Long.MAX_VALUE)
                    .put("completion_tokens", 1L)
            server.enqueue(MockResponse().setBody(payload(overflow).toString()))
            server.start()
            val budgets = PluginModelStore(directory())
            val model =
                PluginModel.forTests(
                    budgets,
                    null,
                    "synthetic-key",
                    server.url("/chat/completions").toString(),
                )
            val result = model.call("overflow-plugin", request())
            assertUnknownUsage(result, budgets, "overflow-plugin")
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `valid output at exact 100 percent reports top-level and status overBudget`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(payload(usage(100L)).toString()))
            server.start()
            val budgets = PluginModelStore(directory())
            budgets.setBudget("model-plugin", 100L, "2026-10-23")
            val model =
                PluginModel.forTests(
                    budgets,
                    null,
                    "synthetic-key",
                    server.url("/chat/completions").toString(),
                )
            val result = model.call("model-plugin", request(maxOutput = 80, content = "hi"))
            assertTrue(result.getBoolean("ok"))
            assertTrue(result.getBoolean("overBudget"))
            assertTrue(result.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(100L, result.getJSONObject("budget").getLong("usedTokens"))
            val status = model.call("model-plugin", JSONObject().put("op", "status"))
            assertTrue(status.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(100L, status.getJSONObject("budget").getLong("usedTokens"))
        }
    }

    @Test
    fun `invalid output at exact 100 percent keeps usage and reports the same overBudget`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(payload(usage(100L), JSONArray()).toString()),
            )
            server.start()
            val budgets = PluginModelStore(directory())
            budgets.setBudget("model-plugin", 100L, "2026-10-23")
            val model =
                PluginModel.forTests(
                    budgets,
                    null,
                    "synthetic-key",
                    server.url("/chat/completions").toString(),
                )
            val result = model.call("model-plugin", request(maxOutput = 80, content = "hi"))
            assertFalse(result.getBoolean("ok"))
            assertEquals("invalid_output", result.getString("outcome"))
            assertTrue(result.getBoolean("overBudget"))
            assertTrue(result.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(100L, result.getJSONObject("budget").getLong("usedTokens"))
            assertEquals(0L, result.getJSONObject("budget").getLong("reservedTokens"))
            val status = model.call("model-plugin", JSONObject().put("op", "status"))
            assertTrue(status.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(100L, status.getJSONObject("budget").getLong("usedTokens"))
        }
    }

    private fun assertUnknownUsage(
        result: JSONObject,
        store: PluginModelStore,
        pluginId: String,
    ) {
        assertFalse(result.getBoolean("ok"))
        assertEquals("unknown", result.getString("outcome"))
        assertEquals("model_usage_missing", result.getJSONObject("error").getString("code"))
        assertEquals(0L, result.getJSONObject("budget").getLong("usedTokens"))
        assertTrue(result.getJSONObject("budget").getLong("reservedTokens") > 0)
        val record = store.record(pluginId, "2026-10-23")
        assertEquals(0L, record.usedTokens)
        assertTrue(record.reservedTokens > 0)
    }

    private fun assertUsageInvalid(usage: JSONObject) {
        try {
            PluginModelPolicy.parseUsage(payload(usage))
            throw AssertionError("非法 usage 不得截断、回退或溢出成真实用量：$usage")
        } catch (_: RuntimeException) {}
    }

    private fun request(maxOutput: Int = 10, content: String = "hello") =
        JSONObject()
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
            .put("maxOutputTokens", maxOutput)

    private fun usage(total: Long) =
        JSONObject()
            .put("prompt_tokens", 12L)
            .put("completion_tokens", total - 12L)
            .put("total_tokens", total)

    private fun payload(usage: JSONObject, choices: JSONArray = validChoices()) =
        JSONObject().put("usage", usage).put("choices", choices)

    private fun validChoices() =
        JSONArray()
            .put(
                JSONObject()
                    .put("message", JSONObject().put("content", "ok"))
                    .put("finish_reason", "stop"),
            )
}
