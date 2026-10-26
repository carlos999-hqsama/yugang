package com.personaldashboard.app

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginModelBudgetTest {
    private val directories = mutableListOf<java.io.File>()

    @After
    fun cleanDirectories() {
        directories.forEach { it.deleteRecursively() }
    }

    private fun directory() =
        Files.createTempDirectory("pd-plugin-model-budget").toFile().also { directories += it }

    private fun model(
        store: PluginModelStore,
        endpoint: String = "http://127.0.0.1:1",
        hardLimit: Long = Config.DEFAULT_PLUGIN_MODEL_DAILY_HARD_LIMIT_TOKENS,
    ) =
        PluginModel.forTests(
            store,
            null,
            "synthetic-key",
            endpoint,
            dailyHardLimitTokens = hardLimit,
        )

    private fun request(maxOutputTokens: Int = 10, content: String = "hello") =
        JSONObject()
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
            .put("maxOutputTokens", maxOutputTokens)

    private fun response(totalTokens: Long) =
        JSONObject()
            .put(
                "choices",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("message", JSONObject().put("content", "ok"))
                            .put("finish_reason", "stop"),
                    ),
            )
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", totalTokens - 1)
                    .put("completion_tokens", 1)
                    .put("total_tokens", totalTokens),
            )
            .toString()

    @Test
    fun `default accepts 200000 and configurable ceiling never rewrites existing accounting`() {
        val dir = directory()
        val budgets = PluginModelStore(dir)
        val defaultModel = model(budgets)
        val status = defaultModel.call("model-plugin", JSONObject().put("op", "status"))
        assertEquals(
            200_000L,
            status.getJSONObject("limits").getLong("dailyHardLimitTokens"),
        )

        val accepted =
            defaultModel.call(
                "model-plugin",
                JSONObject().put("op", "budget").put("dailyTokenLimit", 200_000L),
            )
        assertEquals(200_000L, accepted.getLong("limitTokens"))
        assertEquals(200_000L, accepted.getLong("configuredLimitTokens"))
        budgets.reserve("model-plugin", 400L, "2026-10-23", 200_000L)
        budgets.reserve("model-plugin", 600L, "2026-10-23", 200_000L)
        budgets.settleUsage("model-plugin", 400L, 600L, "2026-10-23")

        val raised =
            model(PluginModelStore(dir), hardLimit = 300_000L)
        val raisedStatus = raised.call("model-plugin", JSONObject().put("op", "status"))
        assertEquals(
            300_000L,
            raisedStatus.getJSONObject("limits").getLong("dailyHardLimitTokens"),
        )
        assertBudget(raisedStatus.getJSONObject("budget"), 200_000L, 400L, 400L)
        val aboveDefault =
            raised.call(
                "model-plugin",
                JSONObject().put("op", "budget").put("dailyTokenLimit", 250_000L),
            )
        assertBudget(aboveDefault, 250_000L, 400L, 400L)

        val lowered = model(PluginModelStore(dir), hardLimit = 150_000L)
        val loweredStatus = lowered.call("model-plugin", JSONObject().put("op", "status"))
        assertEquals(
            150_000L,
            loweredStatus.getJSONObject("limits").getLong("dailyHardLimitTokens"),
        )
        assertBudget(loweredStatus.getJSONObject("budget"), 150_000L, 400L, 400L)
        assertEquals(250_000L, loweredStatus.getJSONObject("budget").getLong("configuredLimitTokens"))
        assertEquals(149_200L, loweredStatus.getJSONObject("budget").getLong("remainingTokens"))

        for (invalid in listOf(0L, 200_000L)) {
            try {
                lowered.call(
                    "model-plugin",
                    JSONObject().put("op", "budget").put("dailyTokenLimit", invalid),
                )
                throw AssertionError("非正数或超过当前宿主上限的预算必须拒绝")
            } catch (_: IllegalArgumentException) {}
        }
        assertBudget(
            PluginModelStore(dir).record("model-plugin", "2026-10-23"),
            250_000L,
            400L,
            400L,
        )
    }

    @Test
    fun `lowered host cap drives status warning and admission and restoring preserves configuration`() {
        MockWebServer().use { server ->
            server.start()
            val dir = directory()
            val budgets = PluginModelStore(dir)
            val endpoint = server.url("/chat/completions").toString()
            val original = model(budgets, endpoint, hardLimit = 200_000L)
            original.call(
                "model-plugin",
                JSONObject().put("op", "budget").put("dailyTokenLimit", 200_000L),
            )
            budgets.reserve("model-plugin", 90_000L, "2026-10-23", 200_000L)

            val lowered = model(PluginModelStore(dir), endpoint, hardLimit = 100_000L)
            val loweredStatus = lowered.call("model-plugin", JSONObject().put("op", "status"))
            val loweredBudget = loweredStatus.getJSONObject("budget")
            assertBudget(loweredBudget, 100_000L, 0L, 90_000L)
            assertEquals(200_000L, loweredBudget.getLong("configuredLimitTokens"))
            assertEquals(10_000L, loweredBudget.getLong("remainingTokens"))
            assertFalse(loweredBudget.getBoolean("overBudget"))
            assertFalse(loweredBudget.getBoolean("budgetExhausted"))
            assertEquals(
                "used_plus_reserved",
                loweredBudget.getJSONObject("warning").getString("basis"),
            )
            assertEquals(100_000L, loweredBudget.getJSONObject("warning").getLong("limitTokens"))

            val largeRequest = request(maxOutputTokens = 4_096, content = "x".repeat(7_000))
            val blocked = lowered.call("model-plugin", largeRequest)
            assertQuota(blocked, PluginModelPolicy.REASON_RESERVATION_EXCEEDS_REMAINING)
            assertEquals(11_112L, blocked.getJSONObject("error").getLong("requestedTokens"))
            assertEquals(10_000L, blocked.getJSONObject("budget").getLong("remainingTokens"))
            assertEquals(200_000L, blocked.getJSONObject("budget").getLong("configuredLimitTokens"))
            assertFalse(blocked.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(0, server.requestCount)

            val restored = model(PluginModelStore(dir), endpoint, hardLimit = 200_000L)
            val restoredStatus = restored.call("model-plugin", JSONObject().put("op", "status"))
            val restoredBudget = restoredStatus.getJSONObject("budget")
            assertBudget(restoredBudget, 200_000L, 0L, 90_000L)
            assertEquals(200_000L, restoredBudget.getLong("configuredLimitTokens"))
            assertEquals(110_000L, restoredBudget.getLong("remainingTokens"))
            assertFalse(restoredBudget.has("warning"))

            server.enqueue(MockResponse().setBody(response(37L)))
            val accepted = restored.call("model-plugin", largeRequest)
            assertTrue(accepted.getBoolean("ok"))
            assertBudget(accepted.getJSONObject("budget"), 200_000L, 37L, 90_000L)
            assertEquals(109_963L, accepted.getJSONObject("budget").getLong("remainingTokens"))
            assertEquals(1, server.requestCount)
            assertBudget(
                PluginModelStore(dir).record("model-plugin", "2026-10-23"),
                200_000L,
                37L,
                90_000L,
            )
        }
    }

    @Test
    fun `concurrent reservations share the lowered cap and restore without clearing either plugin`() {
        val dir = directory()
        val first = PluginModelStore(dir)
        val second = PluginModelStore(dir)
        first.setBudget("plugin-a", 200_000L, "2026-10-23")
        first.setBudget("plugin-b", 200_000L, "2026-10-23")
        val results = ConcurrentLinkedQueue<Result<PluginModelStore.BudgetRecord>>()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2) { index ->
                pool.execute {
                    ready.countDown()
                    try {
                        start.await()
                        val target = if (index == 0) first else second
                        results.add(
                            runCatching {
                                target.reserve("plugin-a", 60_000L, "2026-10-23", 100_000L)
                            },
                        )
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        val failure = results.first { it.isFailure }.exceptionOrNull()
        assertTrue(failure is PluginModelStore.BudgetReservationRejected)
        assertEquals(
            PluginModelPolicy.REASON_RESERVATION_EXCEEDS_REMAINING,
            (failure as PluginModelStore.BudgetReservationRejected).reason,
        )
        assertBudget(first.record("plugin-a", "2026-10-23"), 200_000L, 0L, 60_000L)
        assertBudget(first.record("plugin-b", "2026-10-23"), 200_000L, 0L, 0L)

        first.reserve("plugin-a", 100_000L, "2026-10-23", 200_000L)
        assertBudget(first.record("plugin-a", "2026-10-23"), 200_000L, 0L, 160_000L)
        assertBudget(second.record("plugin-b", "2026-10-23"), 200_000L, 0L, 0L)
    }

    @Test
    fun `config accepts positive integer hard limits without a fixed 200000 ceiling`() {
        assertEquals(200_000L, Config.parsePluginModelDailyHardLimit(200_000))
        assertEquals(300_000L, Config.parsePluginModelDailyHardLimit(300_000L))
        for (invalid in listOf(0, -1L, 1.5, "200000", null)) {
            try {
                Config.parsePluginModelDailyHardLimit(invalid)
                throw AssertionError("配置只接受正整数：$invalid")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun `host daily hard limit is finite and positive`() {
        for (invalid in listOf(0L, -1L)) {
            try {
                model(PluginModelStore(directory()), hardLimit = invalid)
                    .call("model-plugin", JSONObject().put("op", "status"))
                throw AssertionError("宿主日预算硬上限不能为零、负数或无限额")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun `real usage at 80 percent returns a structured local quota warning`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response(80L)))
            server.start()
            val budgets = PluginModelStore(directory())
            budgets.setBudget("model-plugin", 100L, "2026-10-23")
            val result =
                model(budgets, server.url("/chat/completions").toString())
                    .call("model-plugin", request(maxOutputTokens = 80, content = "hi"))

            assertTrue(result.getBoolean("ok"))
            assertEquals(80L, result.getJSONObject("usage").getLong("totalTokens"))
            val warning = result.getJSONObject("budgetWarning")
            assertEquals("quota", warning.getString("code"))
            assertEquals("daily_budget_80_percent", warning.getString("reason"))
            assertEquals(80, warning.getInt("thresholdPercent"))
            assertEquals("used", warning.getString("basis"))
            assertEquals(80L, warning.getLong("usedTokens"))
            assertEquals(
                "daily_budget_80_percent",
                result.getJSONObject("budget").getJSONObject("warning").getString("reason"),
            )
            assertFalse(result.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(0L, budgets.record("model-plugin", "2026-10-23").reservedTokens)
        }
    }

    @Test
    fun `real exhaustion reserved exhaustion and partial remainder have distinct reasons`() {
        MockWebServer().use { server ->
            server.start()
            val budgets = PluginModelStore(directory())
            budgets.setBudget("used-plugin", 100L, "2026-10-23")
            budgets.reserve("used-plugin", 60L, "2026-10-23", 200_000L)
            budgets.settleUsage("used-plugin", 100L, 60L, "2026-10-23")
            budgets.setBudget("reserved-plugin", 100L, "2026-10-23")
            budgets.reserve("reserved-plugin", 100L, "2026-10-23", 200_000L)
            budgets.setBudget("partial-plugin", 200L, "2026-10-23")
            budgets.reserve("partial-plugin", 50L, "2026-10-23", 200_000L)
            budgets.reserve("partial-plugin", 50L, "2026-10-23", 200_000L)
            budgets.settleUsage("partial-plugin", 50L, 50L, "2026-10-23")
            val model = model(budgets, server.url("/chat/completions").toString())

            val used = model.call("used-plugin", request())
            assertQuota(used, PluginModelPolicy.REASON_DAILY_BUDGET_EXHAUSTED)
            assertTrue(used.getJSONObject("budget").getBoolean("budgetExhausted"))
            assertTrue(used.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(100L, used.getJSONObject("budget").getLong("usedTokens"))

            val reserved = model.call("reserved-plugin", request())
            assertQuota(reserved, PluginModelPolicy.REASON_DAILY_BUDGET_RESERVED)
            assertFalse(reserved.getJSONObject("budget").getBoolean("budgetExhausted"))
            assertFalse(reserved.getJSONObject("budget").getBoolean("overBudget"))
            assertEquals(0L, reserved.getJSONObject("budget").getLong("usedTokens"))
            assertEquals(100L, reserved.getJSONObject("budget").getLong("reservedTokens"))
            assertEquals(
                "used_plus_reserved",
                reserved.getJSONObject("budgetWarning").getString("basis"),
            )

            val partial = model.call("partial-plugin", request(maxOutputTokens = 80))
            assertQuota(partial, PluginModelPolicy.REASON_RESERVATION_EXCEEDS_REMAINING)
            assertEquals(100L, partial.getJSONObject("budget").getLong("remainingTokens"))
            assertEquals(50L, partial.getJSONObject("budget").getLong("usedTokens"))
            assertEquals(50L, partial.getJSONObject("budget").getLong("reservedTokens"))
            assertEquals(0, server.requestCount)

            assertEquals(100L, budgets.record("used-plugin", "2026-10-23").usedTokens)
            assertEquals(
                100L,
                budgets.record("reserved-plugin", "2026-10-23").reservedTokens,
            )
            val unchanged = budgets.record("partial-plugin", "2026-10-23")
            assertEquals(50L to 50L, unchanged.usedTokens to unchanged.reservedTokens)
        }
    }

    @Test
    fun `provider 402 and 429 are distinct and other errors are not balance failures`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(402))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            val budgets = PluginModelStore(directory())
            val model = model(budgets, server.url("/chat/completions").toString())

            val insufficientBalance = model.call("model-plugin", request())
            assertEquals("provider_rejected", insufficientBalance.getString("outcome"))
            assertEquals(
                "provider_limit",
                insufficientBalance.getJSONObject("error").getString("code"),
            )
            assertEquals(
                "provider_insufficient_balance",
                insufficientBalance.getJSONObject("error").getString("reason"),
            )
            assertEquals(402, insufficientBalance.getJSONObject("error").getInt("httpStatus"))
            assertEquals(0L, insufficientBalance.getJSONObject("budget").getLong("reservedTokens"))
            assertEquals(1, server.requestCount)

            val rateLimited = model.call("model-plugin", request())
            assertEquals("rate_limit", rateLimited.getJSONObject("error").getString("code"))
            assertEquals(
                "provider_rate_limited",
                rateLimited.getJSONObject("error").getString("reason"),
            )
            assertEquals(429, rateLimited.getJSONObject("error").getInt("httpStatus"))
            assertEquals(0L, rateLimited.getJSONObject("budget").getLong("reservedTokens"))
            assertEquals(2, server.requestCount)

            val other = model.call("model-plugin", request())
            assertEquals("unknown", other.getString("outcome"))
            assertEquals("model_http_error", other.getJSONObject("error").getString("code"))
            assertFalse(other.getJSONObject("error").getString("code").contains("limit"))
            assertTrue(other.getJSONObject("budget").getLong("reservedTokens") > 0)
            assertEquals(0L, other.getJSONObject("budget").getLong("usedTokens"))
            assertEquals(3, server.requestCount)
            assertEquals(
                PluginModelPolicy.DEFAULT_DAILY_BUDGET_TOKENS,
                budgets.record("model-plugin", "2026-10-23").budgetTokens,
            )
        }
    }

    private fun assertQuota(result: JSONObject, reason: String) {
        assertFalse(result.getBoolean("ok"))
        assertEquals("known_not_sent", result.getString("outcome"))
        assertEquals(reason, result.getString("reason"))
        assertEquals("quota", result.getJSONObject("error").getString("code"))
        assertEquals(reason, result.getJSONObject("error").getString("reason"))
        assertTrue(result.getJSONObject("error").getLong("requestedTokens") > 0)
        assertTrue(result.getJSONObject("budget").getLong("limitTokens") > 0)
    }

    private fun assertBudget(
        budget: PluginModelStore.BudgetRecord,
        limit: Long,
        used: Long,
        reserved: Long,
    ) {
        assertEquals(limit, budget.budgetTokens)
        assertEquals(used, budget.usedTokens)
        assertEquals(reserved, budget.reservedTokens)
    }

    private fun assertBudget(
        budget: JSONObject,
        limit: Long,
        used: Long,
        reserved: Long,
    ) {
        assertEquals(limit, budget.getLong("limitTokens"))
        assertEquals(used, budget.getLong("usedTokens"))
        assertEquals(reserved, budget.getLong("reservedTokens"))
    }
}
