package com.personaldashboard.app

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-owned DeepSeek route. Plugins cannot choose a provider or endpoint and never receive the
 * key. The package-private test factory accepts only a loopback MockWebServer URL.
 */
class PluginModel
private constructor(
    private val modelStore: PluginModelStore,
    private val pluginStore: PluginStore?,
    private val keyProvider: () -> String,
    private val dailyHardLimitTokens: () -> Long,
    private val endpoint: HttpUrl,
    private val today: () -> String,
) {
    constructor(
        context: Context,
        cfg: Config,
    ) : this(
        PluginModelStore(context),
        PluginPlatform.store(context),
        { cfg.deepseekKey },
        { cfg.pluginModelDailyHardLimitTokens },
        OFFICIAL_ENDPOINT.toHttpUrl(),
        { LocalDate.now().toString() },
    )

    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    fun call(
        pluginId: String,
        request: JSONObject,
        cancellation: PluginCancellation = PluginCancellation(),
    ): JSONObject {
        PluginContract.id(pluginId)
        requireAccess(pluginId)
        val op = request.optString("op", "chat")
        PluginModelPolicy.validateRequest(op, request)
        val dailyHardLimit = PluginModelPolicy.validateDailyHardLimit(dailyHardLimitTokens())
        val day = today()
        return when (op) {
            "status" -> status(pluginId, keyProvider().isNotEmpty(), day, dailyHardLimit)
            "budget" -> {
                val limit = request.getLong("dailyTokenLimit")
                PluginModelPolicy.validateBudget(limit, dailyHardLimit)
                budgetJson(day, modelStore.setBudget(pluginId, limit, day), dailyHardLimit)
            }
            "chat" ->
                complete(
                    pluginId,
                    PluginModelPolicy.messages(request.getJSONArray("messages")),
                    request,
                    cancellation,
                    day,
                    dailyHardLimit,
                )
            "translate" ->
                complete(
                    pluginId,
                    PluginModelPolicy.translation(
                        request.getString("text"),
                        request.optString(
                            "targetLanguage",
                            PluginModelPolicy.DEFAULT_TRANSLATION_TARGET,
                        ),
                    ),
                    request,
                    cancellation,
                    day,
                    dailyHardLimit,
                )
            else -> throw IllegalArgumentException("不支持的模型操作")
        }
    }

    private fun complete(
        pluginId: String,
        messages: List<Pair<String, String>>,
        request: JSONObject,
        cancellation: PluginCancellation,
        day: String,
        dailyHardLimit: Long,
    ): JSONObject {
        val key = keyProvider()
        require(key.isNotEmpty()) { "宿主尚未配置 DeepSeek Key" }
        val model =
            PluginModelPolicy.validateModel(
                request.optString("model", PluginModelPolicy.DEFAULT_MODEL),
            )
        val timeout = PluginModelPolicy.timeout(request.optLong("timeoutMs", 0L))
        val outputTokens = PluginModelPolicy.outputTokens(request.optInt("maxOutputTokens", 0))

        val permit = perPlugin.getOrPut(pluginId) { Semaphore(1) }
        if (!permit.tryAcquire()) error("该插件已有模型请求正在执行")
        var globalAcquired = false
        val networkStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        var reservedTokens = 0L
        try {
            if (!global.tryAcquire()) error("宿主模型并发已满，请稍后重试")
            globalAcquired = true
            cancellation.check()

            val estimatedInput = PluginModelPolicy.estimateTokens(messages)
            val reservation = estimatedInput.toLong() + outputTokens
            try {
                modelStore.reserve(pluginId, reservation, day, dailyHardLimit)
            } catch (e: PluginModelStore.BudgetReservationRejected) {
                return quotaResult(day, e)
            }
            reservedTokens = reservation

            val body =
                JSONObject()
                    .put("model", model)
                    .put(
                        "messages",
                        JSONArray(
                            messages.map { (role, content) ->
                                JSONObject().put("role", role).put("content", content)
                            },
                        ),
                    )
                    .put("max_tokens", outputTokens)
                    .put("thinking", JSONObject().put("type", "disabled"))
                    .put("stream", false)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            val call =
                client
                    .newBuilder()
                    .callTimeout(timeout, TimeUnit.MILLISECONDS)
                    .eventListener(
                        object : okhttp3.EventListener() {
                            override fun requestHeadersStart(call: okhttp3.Call) {
                                networkStarted.set(true)
                            }
                        },
                    )
                    .build()
                    .newCall(
                        Request.Builder()
                            .url(endpoint)
                            .header("Authorization", "Bearer $key")
                            .post(body.toRequestBody(JSON.toMediaType()))
                            .build(),
                    )
            cancellation.onCancel { call.cancel() }
            cancellation.check()

            call.execute().use { response ->
                if (response.code == 402 || response.code == 429) {
                    val rejected =
                        providerRejected(pluginId, day, reservedTokens, response.code, dailyHardLimit)

                    reservedTokens = 0
                    return rejected
                }
                if (!response.isSuccessful) {
                    return unknownResult(
                        pluginId,
                        if (response.isRedirect) "model_cross_origin_redirect"
                        else "model_http_error",
                        "模型服务返回 HTTP ${response.code}，结果未知；保守预留保留到日切",
                        dailyHardLimit,
                    )
                }
                val payloadText = readBounded(pluginId, response.body?.byteStream())
                val payload =
                    try {
                        JSONObject(payloadText)
                    } catch (_: Exception) {
                        return unknownResult(
                            pluginId,
                            "model_response_invalid",
                            "模型响应不是有效 JSON，结果未知；保守预留保留到日切",
                            dailyHardLimit,
                        )
                    }
                val usage =
                    try {
                        PluginModelPolicy.parseUsage(payload)
                    } catch (_: Exception) {
                        return unknownResult(
                            pluginId,
                            "model_usage_missing",
                            "模型响应缺少有效 usage，结果未知；保守预留保留到日切",
                            dailyHardLimit,
                        )
                    }
                val settled = modelStore.settleUsage(pluginId, usage, reservedTokens, day)
                reservedTokens = 0
                val output =
                    try {
                        PluginModelPolicy.parseOutput(payload)
                    } catch (_: Exception) {
                        return JSONObject()
                            .put("ok", false)
                            .put("outcome", "invalid_output")
                            .put(
                                "error",
                                JSONObject()
                                    .put("code", "model_output_invalid")
                                    .put("message", "模型输出无效；provider 真实用量已记账"),
                            )
                            .put("usage", JSONObject().put("totalTokens", usage))
                            .withSettledBudget(day, settled, dailyHardLimit)
                    }
                val result =
                    JSONObject()
                        .put("ok", true)
                        .put("provider", PluginModelPolicy.PROVIDER)
                        .put("model", model)
                        .put("text", output.first)
                        .put("finishReason", output.second)
                        .put("usage", JSONObject().put("totalTokens", usage))
                        .withSettledBudget(day, settled, dailyHardLimit)
                if (cancellation.cancelled) {
                    result.put("cancelled", true).put("warning", "响应已在取消后返回；真实用量已记账")
                }
                return result
            }
        } catch (e: Exception) {
            return if (networkStarted.get()) {
                unknownResult(
                    pluginId,
                    "model_network_unknown",
                    "模型网络结果未知；保守预留保留到日切",
                    dailyHardLimit,
                )
            } else if (
                reservedTokens > 0 &&
                    e is IllegalStateException &&
                    e.message?.contains("插件操作已取消") == true
            ) {
                val settled = modelStore.releaseReservation(pluginId, reservedTokens, day)
                reservedTokens = 0
                structuredError("model_cancelled_before_request", "插件请求在网络发出前取消，未消耗远端额度")
                    .withBudget(day, settled, dailyHardLimit)
            } else if (reservedTokens > 0 && e is IOException) {
                val settled = modelStore.releaseReservation(pluginId, reservedTokens, day)
                reservedTokens = 0
                structuredError(
                        "model_connection_failed_before_request",
                        "模型连接在请求头发出前失败；本次预留已释放，未消耗远端额度",
                    )
                    .withBudget(day, settled, dailyHardLimit)
            } else if (reservedTokens > 0) {
                val settled = modelStore.releaseReservation(pluginId, reservedTokens, day)
                reservedTokens = 0
                structuredError("model_request_construction_failed", "模型请求未能发出；本次预留已释放")
                    .withBudget(day, settled, dailyHardLimit)
            } else {
                throw e
            }
        } finally {
            if (reservedTokens > 0 && !networkStarted.get()) {
                runCatching { modelStore.releaseReservation(pluginId, reservedTokens, day) }
            }
            if (globalAcquired) global.release()
            permit.release()
        }
    }

    private fun readBounded(pluginId: String, input: java.io.InputStream?): String {
        val output = ByteArrayOutputStream(PluginModelPolicy.MAX_RESPONSE_BYTES.coerceAtMost(8192))
        input ?: return ""
        input.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > PluginModelPolicy.MAX_RESPONSE_BYTES) {
                    throw IOException("bounded_response_exceeded:$pluginId")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toString("UTF-8")
    }

    private fun quotaResult(
        day: String,
        rejected: PluginModelStore.BudgetReservationRejected,
    ): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("outcome", "known_not_sent")
            .put("reason", rejected.reason)
            .put(
                "error",
                JSONObject()
                    .put("code", "quota")
                    .put("reason", rejected.reason)
                    .put("message", rejected.message.orEmpty())
                    .put("requestedTokens", rejected.requestedTokens),
            )
            .withBudget(day, rejected.record, rejected.dailyHardLimitTokens)

    private fun providerRejected(
        pluginId: String,
        day: String,
        reservedTokens: Long,
        httpStatus: Int,
        dailyHardLimit: Long,
    ): JSONObject {
        val settled = modelStore.releaseReservation(pluginId, reservedTokens, day)
        val (code, reason, message) =
            when (httpStatus) {
                402 ->
                    Triple(
                        "provider_limit",
                        "provider_insufficient_balance",
                        "DeepSeek 返回 HTTP 402：账户余额不足；未自动重试或增加插件预算",
                    )
                429 ->
                    Triple(
                        "rate_limit",
                        "provider_rate_limited",
                        "DeepSeek 返回 HTTP 429：请求被限流；未自动重试或增加插件预算",
                    )
                else -> error("仅明确分类 provider 402/429")
            }
        return JSONObject()
            .put("ok", false)
            .put("outcome", "provider_rejected")
            .put("reason", reason)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("reason", reason)
                    .put("httpStatus", httpStatus)
                    .put("message", message),
            )
            .withBudget(day, settled, dailyHardLimit)
    }

    private fun unknownResult(
        pluginId: String,
        code: String,
        message: String,
        dailyHardLimit: Long,
    ): JSONObject {
        // The call is already reserved. Read without mutation so concurrent plugin calls still
        // see the same reservation and cannot spend it twice.
        val day = today()
        val record = modelStore.record(pluginId, day)
        return JSONObject()
            .put("ok", false)
            .put("outcome", "unknown")
            .put("error", JSONObject().put("code", code).put("message", message))
            .withBudget(day, record, dailyHardLimit)
    }

    private fun structuredError(code: String, message: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("outcome", "known_not_sent")
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun requireAccess(pluginId: String) {
        val store = pluginStore ?: return
        val capabilities = PluginContract.caps(store.activeBundle(pluginId))
        require(PluginModelPolicy.CAPABILITY in capabilities) {
            "插件未声明 ${PluginModelPolicy.CAPABILITY}"
        }
    }

    private fun status(
        pluginId: String,
        configured: Boolean,
        day: String,
        dailyHardLimit: Long,
    ): JSONObject {
        val budget = modelStore.record(pluginId, day)
        return JSONObject()
            .put("configured", configured)
            .put("provider", PluginModelPolicy.PROVIDER)
            .put("defaultModel", PluginModelPolicy.DEFAULT_MODEL)
            .put("allowedModels", JSONArray(PluginModelPolicy.ALLOWED_MODELS))
            .put("thinking", "disabled")
            .put("limits", limits(dailyHardLimit))
            .withBudget(day, budget, dailyHardLimit)
    }

    private fun JSONObject.withBudget(
        day: String,
        budget: PluginModelStore.BudgetRecord,
        dailyHardLimit: Long,
    ): JSONObject {
        val value = budgetJson(day, budget, dailyHardLimit)
        put("budget", value)
        value.optJSONObject("warning")?.let { put("budgetWarning", it) }
        return this
    }

    private fun JSONObject.withSettledBudget(
        day: String,
        settled: PluginModelStore.BudgetRecord,
        dailyHardLimit: Long,
    ): JSONObject {
        withBudget(day, settled, dailyHardLimit)
        val effectiveBudget = settled.effectiveBudgetTokens(dailyHardLimit)
        if (settled.overBudgetAt(effectiveBudget)) {
            put("overBudget", true)
            put(
                "warning",
                "provider 真实用量 ${settled.usedTokens}/$effectiveBudget tokens，已达到或超过当前有效每日预算",
            )
        }
        return this
    }

    private fun budgetJson(
        day: String,
        budget: PluginModelStore.BudgetRecord,
        dailyHardLimit: Long,
    ): JSONObject {
        PluginModelPolicy.validateDailyHardLimit(dailyHardLimit)
        val effectiveBudget = budget.effectiveBudgetTokens(dailyHardLimit)
        val result =
            JSONObject()
                .put("date", day)
                .put("limitTokens", effectiveBudget)
                .put("configuredLimitTokens", budget.budgetTokens)
                .put("usedTokens", budget.usedTokens)
                .put("reservedTokens", budget.reservedTokens)
                .put("remainingTokens", budget.availableAt(effectiveBudget))
                .put("overBudget", budget.overBudgetAt(effectiveBudget))
                .put("budgetExhausted", budget.exhaustedAt(effectiveBudget))
        budgetWarning(budget, effectiveBudget)?.let { result.put("warning", it) }
        return result
    }

    private fun budgetWarning(
        budget: PluginModelStore.BudgetRecord,
        effectiveBudget: Long,
    ): JSONObject? {
        val basis =
            PluginModelPolicy.budgetWarningBasis(
                budget.usedTokens,
                budget.reservedTokens,
                effectiveBudget,
            )
                ?: return null
        val realUsage = basis == "used"
        return JSONObject()
            .put("code", "quota")
            .put("reason", "daily_budget_80_percent")
            .put("thresholdPercent", PluginModelPolicy.BUDGET_WARNING_THRESHOLD_PERCENT)
            .put("basis", basis)
            .put("usedTokens", budget.usedTokens)
            .put("reservedTokens", budget.reservedTokens)
            .put("limitTokens", effectiveBudget)
            .put("remainingTokens", budget.availableAt(effectiveBudget))
            .put(
                "message",
                if (realUsage)
                    "插件本地当前有效每日预算真实用量已达到 80%（${budget.usedTokens}/$effectiveBudget tokens）"
                else
                    "真实用量与未知结果预留合计已达到当前有效每日预算的 80%；预留不计作真实用尽",
            )
    }

    private fun limits(dailyHardLimit: Long): JSONObject =
        JSONObject()
            .put("maxMessages", PluginModelPolicy.MAX_MESSAGES)
            .put("maxMessageChars", PluginModelPolicy.MAX_MESSAGE_CHARS)
            .put("maxInputChars", PluginModelPolicy.MAX_INPUT_CHARS)
            .put("maxInputBytes", PluginModelPolicy.MAX_INPUT_BYTES)
            .put("maxInputTokenEstimate", PluginModelPolicy.MAX_INPUT_TOKEN_ESTIMATE)
            .put("maxOutputChars", PluginModelPolicy.MAX_OUTPUT_CHARS)
            .put("minOutputTokens", PluginModelPolicy.MIN_OUTPUT_TOKENS)
            .put("maxOutputTokens", PluginModelPolicy.MAX_OUTPUT_TOKENS)
            .put("defaultOutputTokens", PluginModelPolicy.DEFAULT_OUTPUT_TOKENS)
            .put("defaultTimeoutMs", PluginModelPolicy.DEFAULT_TIMEOUT_MS)
            .put("minTimeoutMs", PluginModelPolicy.MIN_TIMEOUT_MS)
            .put("maxTimeoutMs", PluginModelPolicy.MAX_TIMEOUT_MS)
            .put("maxConcurrentCalls", PluginModelPolicy.MAX_CONCURRENT_CALLS)
            .put("dailyHardLimitTokens", dailyHardLimit)
            .put("maxResponseBytes", PluginModelPolicy.MAX_RESPONSE_BYTES)

    companion object {
        private const val JSON = "application/json; charset=utf-8"
        private const val OFFICIAL_ENDPOINT = "https://api.deepseek.com/chat/completions"
        private val global = Semaphore(PluginModelPolicy.MAX_CONCURRENT_CALLS)
        private val perPlugin = ConcurrentHashMap<String, Semaphore>()

        /** A validated draft shares the same per-plugin budget, without requiring installation. */
        internal fun forPreview(
            context: Context,
            cfg: Config,
            capabilities: Set<String>,
        ): PluginModel {
            require(PluginModelPolicy.CAPABILITY in capabilities) { "草稿未声明 plugin.model" }
            return PluginModel(
                PluginModelStore(context),
                null,
                { cfg.deepseekKey },
                { cfg.pluginModelDailyHardLimitTokens },
                OFFICIAL_ENDPOINT.toHttpUrl(),
                { LocalDate.now().toString() },
            )
        }

        internal fun forTests(
            modelStore: PluginModelStore,
            pluginStore: PluginStore?,
            key: String,
            endpoint: String,
            today: String = "2026-10-23",
            dailyHardLimitTokens: Long = Config.DEFAULT_PLUGIN_MODEL_DAILY_HARD_LIMIT_TOKENS,
        ): PluginModel {
            val url = endpoint.toHttpUrl()
            require(url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost")) {
                "测试注入只允许本机 MockWebServer"
            }
            return PluginModel(modelStore, pluginStore, { key }, { dailyHardLimitTokens }, url) {
                today
            }
        }
    }
}
