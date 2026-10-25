package com.personaldashboard.app

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real host-side streaming reads over the same request policy as [PluginFetch].
 *
 * Consumers use start/poll/cancel. Polling keeps callback memory bounded: a slow consumer gets an
 * explicit backpressure failure after queued chunks, never silently dropped/reordered data.
 */
internal object PluginFetchStream {
    const val MAX_ACTIVE_STREAMS = 16
    const val MAX_QUEUE_CHUNKS = 64
    const val MAX_POLL_EVENTS = 16
    const val CHUNK_BYTES = 8192
    // Cover queued bridge work through the existing 80-second host deadline.
    const val PENDING_CANCEL_MS = 120_000L
    const val MAX_PENDING_CANCELS = 128

    private val streams = ConcurrentHashMap<String, StreamSession>()
    internal val pendingCancels = ConcurrentHashMap<String, PendingCancel>()
    private val pendingCancelSequence = AtomicLong()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pd-plugin-fetch-stream").apply { isDaemon = true }
    }

    internal fun parse(args: JSONObject): PluginFetch.FetchRequest {
        val source =
            if (args.optString("requestId").isEmpty())
                JSONObject(args.toString()).put("requestId", java.util.UUID.randomUUID().toString())
            else args
        return PluginFetch.parse(source)
    }

    fun start(
        args: JSONObject,
        cancellation: PluginCancellation,
        receivedAtNanos: Long = System.nanoTime(),
        onFinished: () -> Unit = {},
        cookieJar: okhttp3.CookieJar? = null,
    ): JSONObject = start(parse(args), cancellation, receivedAtNanos, onFinished, cookieJar)

    fun start(
        request: PluginFetch.FetchRequest,
        cancellation: PluginCancellation,
        receivedAtNanos: Long,
        onFinished: () -> Unit,
        cookieJar: okhttp3.CookieJar? = null,
    ): JSONObject {
        val requestId = request.requestId
        consumePendingCancel(requestId)
        cancellation.check()
        val session = StreamSession(request, cancellation, receivedAtNanos, cookieJar)
        try {
            synchronized(streams) {
                require(streams.size < MAX_ACTIVE_STREAMS) { "流式请求过多" }
                require(streams.putIfAbsent(requestId, session) == null) { "请求编号正在使用" }
            }
        } catch (e: Exception) {
            session.close()
            throw e
        }
        if (PluginFetch.calls.putIfAbsent(requestId, session.call) != null) {
            streams.remove(requestId, session)
            session.close()
            throw IllegalArgumentException("请求编号正在使用")
        }

        try {
            cancellation.check()
            executor.execute {
                session.read()
                onFinished()
            }
        } catch (e: Exception) {
            discard(requestId, session)
            onFinished()
            throw e
        }
        return JSONObject().put("requestId", requestId)
    }

    fun poll(requestId: String): JSONObject {
        val session = streams[requestId] ?: error("流式请求不存在或已结束")
        val result = session.poll()
        if (result.optBoolean("done")) {
            streams.remove(requestId, session)
            session.close()
        }
        return result
    }

    fun cancel(requestId: String): Boolean {
        val session = streams.remove(requestId) ?: return false
        PluginFetch.calls.remove(requestId, session.call)
        session.cancel("请求已取消")
        return true
    }

    /**
     * A cancel can win a race with a queued start because bridge calls and their acknowledgements
     * are asynchronous. Keys are fully scoped by PluginPlatform, and the short tombstone is both
     * time- and count-bounded so an abandoned start cannot retain state indefinitely.
     */
    @Synchronized
    fun cancelBeforeStart(
        requestId: String,
        nowNanos: Long = System.nanoTime(),
    ): Boolean {
        if (requestId.isEmpty()) return false
        val existing = pendingCancels[requestId]
        prunePendingCancels(nowNanos)
        if (existing != null) return existing.expiresAtNanos > nowNanos
        while (pendingCancels.size >= MAX_PENDING_CANCELS) {
            pendingCancels.entries
                .minByOrNull { it.value.sequence }
                ?.let { oldest -> pendingCancels.remove(oldest.key, oldest.value) } ?: break
        }
        val expiresAtNanos = nowNanos + PENDING_CANCEL_MS * 1_000_000
        val pending =
            pendingCancels.putIfAbsent(
                requestId,
                PendingCancel(pendingCancelSequence.incrementAndGet(), expiresAtNanos),
            )
        return pending == null || pending.expiresAtNanos > nowNanos
    }

    @Synchronized
    internal fun discardPendingCancels(pluginId: String) {
        pendingCancels.keys.removeIf { it.startsWith("$pluginId\u0000") }
    }

    @Synchronized
    internal fun consumePendingCancel(requestId: String) {
        prunePendingCancels(System.nanoTime())
        pendingCancels.remove(requestId) ?: return
        error("请求已取消")
    }

    private fun prunePendingCancels(nowNanos: Long) {
        pendingCancels.entries.removeIf { nowNanos >= it.value.expiresAtNanos }
    }

    internal data class PendingCancel(
        val sequence: Long,
        val expiresAtNanos: Long,
    )

    internal fun discard(requestId: String, session: StreamSession) {
        streams.remove(requestId, session)
        session.close()
    }

    internal class StreamSession(
        private val request: PluginFetch.FetchRequest,
        private val parent: PluginCancellation,
        private val receivedAtNanos: Long,
        cookieJar: okhttp3.CookieJar? = null,
    ) {
        private val id = request.requestId
        private val token = PluginCancellation()
        private val queue = ArrayBlockingQueue<JSONObject>(MAX_QUEUE_CHUNKS)
        private var terminal: JSONObject? = null
        private var terminalDelivered = false
        @Volatile private var firstChunkMs: Long? = null
        @Volatile private var finalUrl = request.url.toString()
        @Volatile private var status = 0
        @Volatile private var contentType = ""
        @Volatile private var bytesSoFar = 0L
        private val lock = Any()
        val call: Call =
            PluginFetch.newStreamCall(
                request,
                (request.timeoutMs - (System.nanoTime() - receivedAtNanos) / 1_000_000)
                    .coerceAtLeast(1),
                cookieJar,
            )
        private val detachParent = parent.onCancel {
            streams.remove(id, this)
            // onCancel can run synchronously before detachParent has been assigned.
            // The parent's hook is already removed by cancellation; do not detach it here.
            finishCancelled("插件页面或动作已释放")
        }

        init {
            token.onCancel { call.cancel() }
        }

        fun read() {
            try {
                parent.check()
                token.check()
                val remaining =
                    request.timeoutMs - (System.nanoTime() - receivedAtNanos) / 1_000_000
                if (remaining <= 0)
                    throw PluginRequestException(
                        "timeout",
                        "请求超时",
                        finalUrl = request.url.toString(),
                        timeout = true,
                        retryable = true,
                    )
                call.execute().use { response ->
                    finalUrl = response.request.url.toString()
                    status = response.code
                    contentType = response.header("Content-Type").orEmpty()
                    val input = response.body?.byteStream()
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val count = input?.read(buffer) ?: -1
                        if (count <= 0) break
                        bytesSoFar += count.toLong()
                        if (bytesSoFar > request.maxBytes) {
                            finish(tooLarge(response))
                            call.cancel()
                            return
                        }
                        if (firstChunkMs == null) firstChunkMs = elapsedMs()
                        val firstChunk = bytesSoFar.toLong() == count.toLong()
                        if (!enqueue(chunkEvent(count, buffer, firstChunk))) {
                            call.cancel()
                            return
                        }
                    }
                    finish(doneEvent(response))
                }
            } catch (e: Exception) {
                val failure =
                    when (e) {
                        is PluginRequestException -> e.payload()
                        is IllegalStateException ->
                            JSONObject()
                                .put("error", e.message ?: "请求已取消")
                                .put("code", "cancelled")
                                .put("cancelled", true)
                        is IOException ->
                            PluginRequestException.network(
                                    e,
                                    finalUrl,
                                    status,
                                    parent.cancelled ||
                                        token.cancelled ||
                                        (call.isCanceled() && e !is InterruptedIOException),
                                )
                                .payload()
                        else ->
                            JSONObject()
                                .put("error", e.message ?: e.javaClass.simpleName)
                                .put("code", "network_error")
                                .put("retryable", true)
                    }
                finish(
                    failure
                        .put("kind", "error")
                        .put("status", status)
                        .put("contentType", contentType)
                        .put("bytesSoFar", bytesSoFar)
                        .put("finalUrl", finalUrl)
                        .put("firstChunkMs", firstChunkMs ?: JSONObject.NULL),
                )
            } finally {
                PluginFetch.calls.remove(id, call)
                token.cancel()
            }
        }

        fun cancel(reason: String) {
            detachParent()
            finishCancelled(reason)
        }

        private fun finishCancelled(reason: String) {
            finish(
                metadata()
                    .put("kind", "error")
                    .put("error", reason)
                    .put("code", "cancelled")
                    .put("cancelled", true),
            )
            token.cancel()
        }

        fun close() {
            detachParent()
            token.cancel()
        }

        fun poll(): JSONObject {
            synchronized(lock) {
                val events = JSONArray()
                while (events.length() < MAX_POLL_EVENTS) {
                    val event = queue.poll() ?: break
                    events.put(event)
                }
                val end = terminal
                if (end != null && queue.isEmpty() && !terminalDelivered) {
                    events.put(end)
                    terminalDelivered = true
                }
                return JSONObject()
                    .put("events", events)
                    .put("done", terminalDelivered && queue.isEmpty())
            }
        }

        private fun enqueue(event: JSONObject): Boolean =
            synchronized(lock) {
                if (terminal != null) return false
                if (queue.offer(event)) return true
                finish(
                    metadata()
                        .put("kind", "error")
                        .put("error", "流式响应消费过慢，请降低数据量后重试")
                        .put("code", "backpressure")
                        .put("retryable", true),
                )
                false
            }

        private fun finish(event: JSONObject) {
            synchronized(lock) {
                if (terminal == null) terminal = event
            }
        }

        private fun elapsedMs() = (System.nanoTime() - receivedAtNanos) / 1_000_000

        private fun metadata() =
            JSONObject()
                .put("status", status)
                .put("contentType", contentType)
                .put("bytesSoFar", bytesSoFar)
                .put("finalUrl", finalUrl)
                .put("firstChunkMs", firstChunkMs ?: JSONObject.NULL)

        private fun chunkEvent(count: Int, buffer: ByteArray, first: Boolean) =
            metadata()
                .put("kind", "chunk")
                .put("first", first)
                .put("chunkBytes", count)
                .put("base64", java.util.Base64.getEncoder().encodeToString(buffer.copyOf(count)))

        private fun doneEvent(response: okhttp3.Response): JSONObject {
            val headers = JSONObject()
            for (name in response.headers.names()) {
                if (name.equals("set-cookie", true)) continue
                headers.put(
                    name.lowercase(),
                    response.headers.values(name).joinToString(", "),
                )
            }
            return metadata()
                .put("kind", "done")
                .put("ok", response.isSuccessful)
                .put("code", if (response.isSuccessful) "ok" else "http_error")
                .put("retryable", response.code == 429 || response.code >= 500)
                .put("emptyBody", bytesSoFar == 0L)
                .put("headers", headers)
        }

        private fun tooLarge(response: okhttp3.Response) =
            PluginRequestException(
                    "response_too_large",
                    "响应超过 ${request.maxBytes / 1024} KiB 限制",
                    response.code,
                    finalUrl,
                )
                .payload()
                .put("kind", "error")
                .put("contentType", contentType)
                .put("bytesSoFar", bytesSoFar)
                .put("firstChunkMs", firstChunkMs ?: JSONObject.NULL)
    }
}
