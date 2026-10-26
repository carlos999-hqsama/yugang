package com.personaldashboard.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import okio.buffer

/**
 * 所有内置 dsh 请求都在原生侧完成：补登录 Cookie，不带 Origin，Host 只能是回环地址。
 * 页面 JS 从不接触登录 Cookie。插件自己的外网请求走无凭据的 PluginFetch。
 */
class Net(
    private val cfg: Config,
    private val retryNetworkFailures: Boolean = true,
    private val emit: (String, JSONObject) -> Unit,
) {
    val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(retryNetworkFailures)
            .pingInterval(0, TimeUnit.SECONDS) // dsh 主动 ping，OkHttp 自动回 pong
            .build()
    private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    private val sses = ConcurrentHashMap<String, EventSource>()
    private val sockets = ConcurrentHashMap<String, WebSocket>()

    private fun base(target: String): String {
        require(target == "dsh") { "仅支持内置对话运行时通道" }
        val b = cfg.dshUrl
        require(b.isNotEmpty()) { "未配置 dsh 地址" }
        require(Config.isLoopback(b)) { "dsh 地址只能是本机回环地址" }
        return b.trimEnd('/')
    }

    private fun auth(target: String, rb: Request.Builder): Request.Builder {
        require(target == "dsh")
        cfg.dshCookie.takeIf { it.isNotEmpty() }?.let { rb.header("Cookie", it) }
        return rb
    }

    private fun checkPath(p: String): String {
        require(p.startsWith("/") && !p.startsWith("//")) { "非法路径" }
        return p
    }

    /** 统一 HTTP：返回 {status, headers, text}；网络错误抛异常（JS 侧显示“连接失败”） */
    fun http(a: JSONObject, registerCall: ((okhttp3.Call) -> Unit)? = null): JSONObject {
        val target = a.optString("target")
        val method = a.optString("method", "GET").uppercase()
        val url = base(target) + checkPath(a.optString("path"))
        val body = if (a.isNull("body")) null else a.optString("body")
        val timeout = a.optLong("timeoutMs", 20000).coerceIn(1000, 600000)
        val rb = Request.Builder().url(url)
        rb.method(
            method,
            body?.toRequestBody(JSON)
                ?: if (method == "POST" || method == "PUT" || method == "PATCH")
                    ByteArray(0).toRequestBody(JSON)
                else null,
        )
        val c =
            client
                .newBuilder()
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(
                    retryNetworkFailures &&
                        !(method != "GET" && a.optString("path").startsWith("/api/main-bot/")),
                )
                .build()
        try {
            val call = c.newCall(auth(target, rb).build())
            registerCall?.invoke(call)
            call.execute().use { r ->
                val h = JSONObject()
                for (n in r.headers.names()) if (!n.equals("set-cookie", true))
                    h.put(n.lowercase(), r.headers.values(n).joinToString(", "))
                return JSONObject()
                    .put("status", r.code)
                    .put("headers", h)
                    .put("text", r.body?.string() ?: "")
            }
        } catch (e: java.io.IOException) {
            throw RuntimeException(
                "dsh 连接失败：${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    /** 后台保活只消费 running 布尔，不保存或回传会话内容。 */
    fun hasRunningSessions(): Boolean {
        val body =
            JSONObject()
                .put("type", "client-request")
                .put("rpcId", java.util.UUID.randomUUID().toString())
                .put("method", "session/list")
                .put(
                    "payload",
                    JSONObject().put("args", JSONObject().put("_request", JSONObject())),
                )
        val response =
            http(
                JSONObject()
                    .put("target", "dsh")
                    .put("method", "POST")
                    .put("path", "/api/session/list")
                    .put("body", body.toString())
                    .put("timeoutMs", 3000),
            )
        check(response.optInt("status") in 200..299) { "任务状态暂不可用" }
        val result = JSONObject(response.getString("text")).getJSONObject("result")
        check(result.optBoolean("ok")) { "任务状态暂不可用" }
        val items = result.getJSONObject("value").getJSONArray("items")
        return (0 until items.length()).any { items.getJSONObject(it).optBoolean("running") }
    }

    /** dsh 原始请求（文件代理用），返回 Response，调用方负责关闭 */
    fun dshRaw(pathAndQuery: String, headers: Map<String, String> = emptyMap(), cancellation: PluginCancellation? = null): Response {
        val request = auth("dsh", Request.Builder().url(base("dsh") + checkPath(pathAndQuery)))
        headers.filterKeys { it.equals("Range", true) || it.equals("If-Range", true) }.forEach { (name, value) -> request.header(name, value) }
        val call = client.newBuilder().callTimeout(120, TimeUnit.SECONDS).retryOnConnectionFailure(false).build().newCall(request.build())
        val detach = cancellation?.onCancel { call.cancel() } ?: {}
        try {
            cancellation?.check()
            val response = call.execute()
            val body = response.body
            if (body == null) { detach();return response }
            val source = object : okio.ForwardingSource(body.source()) {
                override fun close() { try { super.close() } finally { detach() } }
            }.buffer()
            return response.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Exception) { detach();throw error }
    }

    /** 配对：用 DSHBox 启动链接里的一次性 token 换 dsh 的 30 天登录 Cookie */
    fun pair(input: String) {
        val tok = parseToken(input) ?: throw IllegalArgumentException("没有找到 token 参数")
        // Pair only the configured local endpoint; an incoming link cannot change it.
        Regex("^(http://[^/?#]+)").find(input.trim())?.groupValues?.get(1)?.let { origin ->
            require(Config.isLoopback(origin)) { "启动链接不是本机回环地址，已拒绝" }
            require(origin == cfg.dshUrl) { "启动链接与当前配置的 dsh 地址不一致" }
        }
        val req =
            Request.Builder()
                .url(base("dsh") + "/?token=" + java.net.URLEncoder.encode(tok, "UTF-8"))
                .build()
        val r =
            try {
                client.newCall(req).execute()
            } catch (e: java.io.IOException) {
                throw RuntimeException("连不上 dsh（${cfg.dshUrl}）：dsh 没有在运行", e)
            }
        r.use {
            val sc =
                it.headers.values("Set-Cookie").firstOrNull { c -> c.startsWith("dsh-auth-") }
                    ?: throw RuntimeException(
                        if (it.code == 401 || it.code == 403) "令牌无效或已过期（dsh 重启后需重新获取启动链接）"
                        else "dsh 返回 ${it.code}，未下发登录 Cookie",
                    )
            cfg.markPaired(sc.substringBefore(';'))
        }
    }

    @Volatile private var disposed = false

    // ---------------------------------------------------------------- SSE
    /** Reject on the channel consumed by the SSE SDK so its open attempt cannot hang. */
    fun rejectSse(id: String, message: String) {
        if (disposed) return
        emit("sse:$id", JSONObject().put("kind", "error").put("error", message))
    }

    @Synchronized
    fun sseOpen(id: String, a: JSONObject) {
        if (disposed) return
        val target = a.optString("target")
        val url =
            try {
                base(target) + checkPath(a.optString("path"))
            } catch (e: Exception) {
                rejectSse(id, e.message ?: "SSE 地址无效")
                return
            }
        val req =
            auth(target, Request.Builder().url(url).header("Accept", "text/event-stream")).build()
        val streamId = id
        val es =
            EventSources.createFactory(streamClient)
                .newEventSource(
                    req,
                    object : EventSourceListener() {
                        override fun onOpen(eventSource: EventSource, response: Response) {
                            emit("sse:$id", JSONObject().put("kind", "open"))
                        }

                        override fun onEvent(
                            eventSource: EventSource,
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            emit(
                                "sse:$streamId",
                                JSONObject()
                                    .put("kind", "event")
                                    .put("event", type ?: "message")
                                    .put("data", data),
                            )
                        }

                        override fun onClosed(eventSource: EventSource) {
                            sses.remove(id)
                            emit(
                                "sse:$id",
                                JSONObject().put("kind", "error").put("error", "SSE 已关闭"),
                            )
                        }

                        override fun onFailure(
                            eventSource: EventSource,
                            t: Throwable?,
                            response: Response?,
                        ) {
                            if (sses.remove(id) == null) return
                            emit(
                                "sse:$id",
                                JSONObject()
                                    .put("kind", "error")
                                    .put(
                                        "error",
                                        response?.let { "HTTP ${it.code}" }
                                            ?: (t?.message ?: "SSE 断开"),
                                    )
                                    .put("status", response?.code ?: 0),
                            )
                        }
                    },
                )
        sses[id] = es
    }

    fun sseClose(id: String) {
        sses.remove(id)?.cancel()
    }

    // ---------------------------------------------------------------- WebSocket（dsh
    // /api/remote.mux）
    /** Reject on the channel consumed by the WebSocket SDK so its open attempt cannot hang. */
    fun rejectWs(id: String, reason: String) {
        if (disposed) return
        emit(
            "ws:$id",
            JSONObject().put("kind", "close").put("code", 1006).put("reason", reason),
        )
    }

    @Synchronized
    fun wsOpen(id: String, a: JSONObject) {
        if (disposed) return
        val target = a.optString("target")
        val http =
            try {
                base(target) + checkPath(a.optString("path"))
            } catch (e: Exception) {
                rejectWs(id, e.message ?: "WebSocket 地址无效")
                return
            }
        val req =
            auth(target, Request.Builder().url(http.replaceFirst(Regex("^http"), "ws"))).build()
        val s =
            streamClient.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        emit("ws:$id", JSONObject().put("kind", "open"))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        emit("ws:$id", JSONObject().put("kind", "message").put("data", text))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        sockets.remove(id)
                        emit(
                            "ws:$id",
                            JSONObject()
                                .put("kind", "close")
                                .put("code", code)
                                .put("reason", reason),
                        )
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        sockets.remove(id)
                        // 握手失败时把 HTTP 状态码当 close code 交给 JS（401/403 → 需要重新配对）
                        emit(
                            "ws:$id",
                            JSONObject()
                                .put("kind", "close")
                                .put("code", response?.code ?: 1006)
                                .put("reason", t.message ?: ""),
                        )
                    }
                },
            )
        sockets[id] = s
    }

    fun wsSend(id: String, text: String) {
        sockets[id]?.send(text)
    }

    fun wsClose(id: String) {
        sockets.remove(id)?.close(1000, null)
    }

    @Synchronized
    fun closeAll() {
        sses.values.forEach { it.cancel() }
        sses.clear()
        sockets.values.forEach { it.cancel() }
        sockets.clear()
    }

    @Synchronized
    fun dispose() {
        disposed = true
        closeAll()
        client.dispatcher.cancelAll()
        streamClient.dispatcher.cancelAll()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun isAutoPairLink(input: String): Boolean = runCatching {
            val uri = java.net.URI(input)
            uri.scheme == "http" &&
                uri.host in setOf("127.0.0.1", "localhost") &&
                uri.port == 3080 &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                uri.path in setOf("", "/")
        }
            .getOrDefault(false)

        fun parseToken(input: String): String? {
            val s = input.trim()
            Regex("[?&]token=([^&#\\s]+)").find(s)?.let {
                return java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
            }
            return if (Regex("^[\\w.~-]{16,}$").matches(s)) s else null
        }
    }
}
