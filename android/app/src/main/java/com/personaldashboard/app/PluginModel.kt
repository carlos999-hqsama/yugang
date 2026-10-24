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
        val day = today()
        return when (op) {
            "status" -> status(pluginId, keyProvider().isNotEmpty(), day)
            "budget" -> {
                val limit = request.getLong("dailyTokenLimit")
                PluginModelPolicy.validateBudget(limit)
                budgetJson(day, modelStore.setBudget(pluginId, limit, day))
            }
            "chat" ->
                complete(
                    pluginId,
                    PluginModelPolicy.messages(request.getJSONArray("messages")),
                    request,
                    cancellation,
                    day,
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
            val before = modelStore.record(pluginId, day)
            PluginModelPolicy.preflight(
                before.usedTokens + before.reservedTokens,
                before.budgetTokens,
                estimatedInput,
                outputTokens,
            )
            modelStore.reserve(pluginId, reservation, day)
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
                if (!response.isSuccessful) {
                    return unknownResult(
                        pluginId,
                        if (response.isRedirect) "model_cross_origin_redirect"
                        else "model_http_error",
                        "模型服务返回 HTTP ${response.code}，结果未知；保守预留保留到日切",
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
                            .put("budget", budgetJson(day, settled))
                    }
                val result =
                    JSONObject()
                        .put("ok", true)
                        .put("provider", PluginModelPolicy.PROVIDER)
                        .put("model", model)
                        .put("text", output.first)
                        .put("finishReason", output.second)
                        .put("usage", JSONObject().put("totalTokens", usage))
                        .put("budget", budgetJson(day, settled))
                if (settled.overBudget) {
                    result
                        .put("overBudget", true)
                        .put(
                            "warning",
                            "provider 真实用量 ${settled.usedTokens}/${settled.budgetTokens} tokens，已超过插件预算",
                        )
                }
                if (cancellation.cancelled) {
                    result.put("cancelled", true).put("warning", "响应已在取消后返回；真实用量已记账")
                }
                return result
            }
        } catch (e: Exception) {
            return if (networkStarted.get()) {
                unknownResult(pluginId, "model_network_unknown", "模型网络结果未知；保守预留保留到日切")
            } else if (
                reservedTokens > 0 &&
                    e is IllegalStateException &&
                    e.message?.contains("插件操作已取消") == true
            ) {
                val settled = modelStore.releaseReservation(pluginId, reservedTokens, day)
                reservedTokens = 0
                structuredError("model_cancelled_before_request", "插件请求在网络发出前取消，未消耗远端额度")
                    .put("budget", budgetJson(day, settled))
            } else if (reservedTokens > 0) {
                val settled = modelStore.releaseReservation(pluginId, reservedTokens, day)
                reservedTokens = 0
                structuredError("model_request_construction_failed", "模型请求未能发出；本次预留已释放")
                    .put("budget", budgetJson(day, settled))
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

    private fun unknownResult(pluginId: String, code: String, message: String): JSONObject {
        // The call is already reserved. Read without mutation so concurrent plugin calls still
        // see the same reservation and cannot spend it twice.
        val day = today()
        val record = modelStore.record(pluginId, day)
        return JSONObject()
            .put("ok", false)
            .put("outcome", "unknown")
            .put("error", JSONObject().put("code", code).put("message", message))
            .put("budget", budgetJson(day, record))
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

    private fun status(pluginId: String, configured: Boolean, day: String): JSONObject {
        val budget = modelStore.record(pluginId, day)
        return JSONObject()
            .put("configured", configured)
            .put("provider", PluginModelPolicy.PROVIDER)
            .put("defaultModel", PluginModelPolicy.DEFAULT_MODEL)
            .put("allowedModels", JSONArray(PluginModelPolicy.ALLOWED_MODELS))
            .put("thinking", "disabled")
            .put("limits", limits())
            .put("budget", budgetJson(day, budget))
    }

    private fun budgetJson(day: String, budget: PluginModelStore.BudgetRecord): JSONObject =
        JSONObject()
            .put("date", day)
            .put("limitTokens", budget.budgetTokens)
            .put("usedTokens", budget.usedTokens)
            .put("reservedTokens", budget.reservedTokens)
            .put("remainingTokens", budget.availableTokens)
            .put("overBudget", budget.overBudget)

    private fun limits(): JSONObject =
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
            .put("dailyHardLimitTokens", PluginModelPolicy.MAX_DAILY_BUDGET_TOKENS)
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
        ): PluginModel {
            val url = endpoint.toHttpUrl()
            require(url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost")) {
                "测试注入只允许本机 MockWebServer"
            }
            return PluginModel(modelStore, pluginStore, { key }, url) { today }
        }
    }
}
