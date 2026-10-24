package com.personaldashboard.app

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Runtime-to-native service socket for plugin management, capabilities and preview.
 *
 * The channel is an abstract-namespace [LocalServerSocket] restricted to this Android UID. It never
 * listens on TCP and never carries a host credential. Long-running preview work gets its own
 * bounded executor so a rendering plugin cannot starve normal plugin calls.
 *
 * Every connection lives under one [HostDeadlineGuard] covering read, dispatch and write: expiry
 * closes the socket first so a blocked read or write fails, then cancels that request's real work
 * (for example a plugin fetch call). Normal completion disarms the guard and [stop] disarms all of
 * them. Capability calls keep their parent action's cancellation; a capability connection only
 * bounds the socket, never cancels sibling calls.
 */
object HostRuntimeControl {
    private const val TAG = "PD-HOST-RUNTIME"
    private const val REQUEST_DEADLINE_MS = 80_000L
    private const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    private const val WORKER_THREADS = 2
    private const val MAX_QUEUED_CLIENTS = 4

    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val previewRequests = ConcurrentHashMap.newKeySet<PluginCancellation>()
    private val lock = Any()
    private val threadSeq = AtomicInteger()

    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ThreadPoolExecutor? = null
    private var pluginExecutor: ThreadPoolExecutor? = null
    private var previewExecutor: ThreadPoolExecutor? = null
    private var guardScope: HostDeadlineGuard.Scope? = null
    private var config: Config? = null
    private var appContext: Context? = null

    @Volatile private var running = false
    @Volatile private var socketName: String? = null

    /** Idempotently open the channel; the returned name goes only to the child environment. */
    fun start(context: Context): String =
        synchronized(lock) {
            if (running && server != null && acceptThread?.isAlive == true) {
                return socketName ?: error("HostRuntimeControl socket 名称丢失")
            }

            cleanupLocked()
            val nextName = "pd-host-runtime-" + UUID.randomUUID()
            val nextServer = LocalServerSocket(nextName)
            val nextConfig =
                try {
                    Config(context.applicationContext)
                } catch (e: Exception) {
                    runCatching { nextServer.close() }
                    throw e
                }
            val nextExecutor =
                ThreadPoolExecutor(
                    WORKER_THREADS,
                    WORKER_THREADS,
                    0L,
                    TimeUnit.MILLISECONDS,
                    ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
                    { r ->
                        Thread(r, "pd-host-runtime-${threadSeq.incrementAndGet()}").apply {
                            isDaemon = true
                        }
                    },
                    ThreadPoolExecutor.AbortPolicy(),
                )

            server = nextServer
            config = nextConfig
            appContext = context.applicationContext
            executor = nextExecutor
            pluginExecutor =
                ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.MILLISECONDS,
                    SynchronousQueue(),
                    { r -> Thread(r, "pd-plugin-invoke").apply { isDaemon = true } },
                    ThreadPoolExecutor.AbortPolicy(),
                )
            previewExecutor =
                ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    ArrayBlockingQueue(2),
                    { r -> Thread(r, "pd-plugin-preview").apply { isDaemon = true } },
                    ThreadPoolExecutor.AbortPolicy(),
                )
            guardScope = HostDeadlineGuard.Scope("pd-host-deadline")
            socketName = nextName
            running = true
            val nextThread =
                Thread({ acceptLoop(nextServer) }, "pd-host-runtime-accept").apply {
                    isDaemon = true
                }
            acceptThread = nextThread
            try {
                nextThread.start()
            } catch (e: Throwable) {
                cleanupLocked()
                throw e
            }
            nextName
        }

    fun stop() {
        val oldThread: Thread?
        synchronized(lock) {
            oldThread = acceptThread
            cleanupLocked()
        }
        if (oldThread != null && oldThread !== Thread.currentThread()) {
            oldThread.interrupt()
            runCatching { oldThread.join(1_500L) }
        }
    }

    private fun cleanupLocked() {
        running = false
        previewRequests.toList().forEach { it.cancel() }
        previewRequests.clear()
        PluginPreviewManager.closeAll()
        val oldServer = server
        server = null
        socketName = null
        config = null
        appContext = null
        val oldPluginExecutor = pluginExecutor
        pluginExecutor = null
        val oldPreviewExecutor = previewExecutor
        previewExecutor = null
        val oldExecutor = executor
        executor = null
        acceptThread = null
        val oldGuardScope = guardScope
        guardScope = null
        runCatching { oldServer?.close() }
        clients.toList().forEach(::closeClient)
        clients.clear()
        oldGuardScope?.close()
        oldExecutor?.shutdownNow()
        oldPluginExecutor?.shutdownNow()
        oldPreviewExecutor?.shutdownNow()
    }

    private fun acceptLoop(activeServer: LocalServerSocket) {
        var failed = false
        try {
            while (running) {
                val client =
                    try {
                        activeServer.accept()
                    } catch (_: IOException) {
                        if (running) {
                            failed = true
                            Log.w(TAG, "accept failed")
                        }
                        break
                    }
                if (!sameUid(client)) {
                    closeClient(client)
                    continue
                }
                clients += client
                val deadline =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_DEADLINE_MS)
                val (pool, scope) =
                    synchronized(lock) {
                        if (running && server === activeServer) (executor to guardScope)
                        else (null to null)
                    }
                if (pool == null || scope == null) {
                    closeClient(client)
                    continue
                }
                val guard =
                    try {
                        // Total deadline for read, dispatch and write: expiry closes the socket
                        // to unblock a pending read or write, then cancels the request's work.
                        scope.arm(REQUEST_DEADLINE_MS) { closeClient(client) }
                    } catch (_: RejectedExecutionException) {
                        closeClient(client)
                        continue
                    }
                try {
                    pool.execute { handleClient(client, deadline, guard) }
                } catch (_: RejectedExecutionException) {
                    runCatching { respondError(client, "请求过多，请稍后重试") }
                    guard.finish()
                    closeClient(client)
                }
            }
        } catch (_: RuntimeException) {
            failed = running
            if (failed) Log.w(TAG, "accept loop failed")
        } finally {
            if (failed) {
                synchronized(lock) {
                    if (server === activeServer) cleanupLocked()
                }
            }
        }
    }

    private fun handleClient(client: LocalSocket, deadline: Long, guard: HostDeadlineGuard) {
        var delegated = false
        try {
            if (!running) return
            val requestBytes = readRequestLine(client, deadline)
            val receivedAtNanos = System.nanoTime()
            val request = decodeUtf8(requestBytes)
            val json = JSONObject(request)
            val action = json.getString("action")
            require(action in setOf("plugin_manage", "plugin_capability", "plugin_preview")) {
                "宿主运行时不支持此请求"
            }
            val context = synchronized(lock) { appContext } ?: throw RequestFailure("插件通道未就绪")

            if (action == "plugin_preview") {
                val pool = synchronized(lock) { previewExecutor } ?: throw RequestFailure("预览通道未就绪")
                val previewRequest = json.getJSONObject("request")
                val cancellation = PluginCancellation()
                guard.bindCancellation { cancellation.cancel() }
                synchronized(lock) {
                    check(running && previewExecutor === pool) { "预览通道已停止" }
                    previewRequests += cancellation
                }
                try {
                    pool.execute {
                        val finished = AtomicBoolean(false)
                        val disconnect = disconnectWatcher(client, cancellation, deadline, finished)
                        try {
                            val value = PluginPreviewManager.handle(previewRequest, cancellation)
                            finished.set(true)
                            respondSuccess(client, value)
                        } catch (e: Exception) {
                            if (e is InterruptedException) cancellation.cancel()
                            finished.set(true)
                            runCatching { respondError(client, e.message ?: "预览失败") }
                        } finally {
                            finished.set(true)
                            disconnect.interrupt()
                            previewRequests.remove(cancellation)
                            guard.finish()
                            closeClient(client)
                        }
                    }
                } catch (e: Exception) {
                    previewRequests.remove(cancellation)
                    cancellation.cancel()
                    throw e
                }
                delegated = true
                return
            }

            if (
                action == "plugin_manage" &&
                    json.getJSONObject("request").optString("action") == "invoke"
            ) {
                val pool =
                    synchronized(lock) { pluginExecutor } ?: throw RequestFailure("插件执行通道未就绪")
                val requestArgs = json.getJSONObject("request")
                val cancellation = PluginCancellation()
                guard.bindCancellation { cancellation.cancel() }
                pool.execute {
                    val finished = AtomicBoolean(false)
                    val disconnect = disconnectWatcher(client, cancellation, deadline, finished)
                    try {
                        val value =
                            PluginPlatform.manage(context, requestArgs, cancellation, deadline)
                        finished.set(true)
                        respondSuccess(client, value)
                    } catch (e: Exception) {
                        finished.set(true)
                        runCatching { respondError(client, e.message ?: "插件执行失败，结果需核对") }
                    } finally {
                        finished.set(true)
                        disconnect.interrupt()
                        cancellation.cancel()
                        guard.finish()
                        closeClient(client)
                    }
                }
                delegated = true
                return
            }

            if (action == "plugin_manage") {
                // Non-invoke management (fetch, install, ...): a disconnect or the total deadline
                // must cancel the request's real work instead of letting it run to its own timeout.
                val cancellation = PluginCancellation()
                guard.bindCancellation { cancellation.cancel() }
                val finished = AtomicBoolean(false)
                val disconnect = disconnectWatcher(client, cancellation, deadline, finished)
                try {
                    val value =
                        PluginPlatform.manage(context, json.getJSONObject("request"), cancellation)
                    finished.set(true)
                    respondSuccess(client, value)
                } finally {
                    finished.set(true)
                    disconnect.interrupt()
                    cancellation.cancel()
                }
            } else {
                // Capability work runs under the parent action's cancellation. This connection's
                // guard only closes the socket at the deadline; it must never cancel sibling calls.
                val value =
                    PluginPlatform.capability(
                        context,
                        synchronized(lock) { config } ?: throw RequestFailure("插件通道未就绪"),
                        json,
                        receivedAtNanos,
                    )
                respondSuccess(client, value)
            }
        } catch (_: SocketTimeoutException) {
            runCatching { respondError(client, "请求超时") }
        } catch (e: RequestFailure) {
            runCatching { respondError(client, e.message ?: "请求失败") }
        } catch (e: IllegalArgumentException) {
            runCatching { respondError(client, e.message ?: "插件请求无效") }
        } catch (_: IOException) {
            if (running) runCatching { respondError(client, "读取请求失败") }
        } catch (_: Exception) {
            Log.w(TAG, "request failed")
            runCatching { respondError(client, "处理请求失败") }
        } finally {
            if (!delegated) {
                guard.finish()
                closeClient(client)
            }
        }
    }

    private fun disconnectWatcher(
        client: LocalSocket,
        cancellation: PluginCancellation,
        deadline: Long,
        finished: AtomicBoolean,
    ): Thread = Thread {
        try {
            while (
                !finished.get() &&
                    !cancellation.cancelled &&
                    running &&
                    !Thread.currentThread().isInterrupted
            ) {
                if (System.nanoTime() >= deadline) {
                    cancellation.cancel()
                    break
                }
                try {
                    client.soTimeout =
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                            .coerceIn(1, Int.MAX_VALUE.toLong())
                            .toInt()
                    if (client.inputStream.read() == -1) {
                        if (!finished.get() && !Thread.currentThread().isInterrupted)
                            cancellation.cancel()
                        break
                    }
                } catch (_: SocketTimeoutException) {
                    // Recheck the total deadline on the next iteration.
                } catch (_: IOException) {
                    cancellation.cancel()
                    break
                }
            }
        } catch (_: Exception) {
            if (!finished.get() && !Thread.currentThread().isInterrupted) cancellation.cancel()
        }
    }
        .apply {
            isDaemon = true
            start()
        }

    private fun readRequestLine(socket: LocalSocket, deadline: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        val input = socket.inputStream
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw SocketTimeoutException()
            socket.soTimeout =
                TimeUnit.NANOSECONDS.toMillis(remaining)
                    .coerceAtLeast(1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            val next = input.read()
            if (next == -1) break
            if (next == '\n'.code) return withoutCarriageReturn(bytes.toByteArray())
            if (bytes.size() >= MAX_REQUEST_BYTES) throw RequestFailure("请求过大")
            bytes.write(next)
        }
        if (bytes.size() == 0) throw RequestFailure("请求为空")
        return withoutCarriageReturn(bytes.toByteArray())
    }

    private fun withoutCarriageReturn(bytes: ByteArray): ByteArray =
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.copyOf(bytes.size - 1)
        else bytes

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw RequestFailure("请求不是有效的 UTF-8")
        }

    private fun sameUid(socket: LocalSocket): Boolean = runCatching {
        socket.peerCredentials.uid == android.os.Process.myUid()
    }
        .getOrDefault(false)

    private fun respondSuccess(socket: LocalSocket, value: JSONObject) {
        writeJson(socket, JSONObject().put("value", value))
    }

    private fun respondError(socket: LocalSocket, message: String) {
        writeJson(socket, JSONObject().put("error", message))
    }

    private fun writeJson(socket: LocalSocket, value: JSONObject) {
        val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
        val output =
            if (bytes.size > MAX_RESPONSE_BYTES) {
                (JSONObject().put("error", "响应过大").toString() + "\n").toByteArray(Charsets.UTF_8)
            } else bytes
        socket.outputStream.write(output)
        socket.outputStream.flush()
    }

    private fun closeClient(client: LocalSocket) {
        clients -= client
        runCatching { client.shutdownOutput() }
        runCatching { client.shutdownInput() }
        runCatching { client.close() }
    }

    private class RequestFailure(message: String) : Exception(message)
}
