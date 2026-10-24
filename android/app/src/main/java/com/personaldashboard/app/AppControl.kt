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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * 内置 Node 与 Android 原生侧的最小同 UID IPC。使用 abstract namespace LocalServerSocket， 不监听 TCP、不申请 Android
 * 权限；socket 名称只通过进程环境变量 PD_APP_SOCKET 传递。 服务独立于 Activity，Runtime 启动时创建，Runtime.stopWorker 真正成功后才关闭。
 */
object AppControl {
    private const val TAG = "PD-APP-CONTROL"
    private const val REQUEST_TIMEOUT_MS = 5_000L
    private const val MAX_QUEUED_CLIENTS = 8

    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val lock = Any()
    private val threadSeq = AtomicInteger()

    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ThreadPoolExecutor? = null
    private var config: Config? = null
    private var bots: BotWorkspaceStore? = null
    @Volatile private var running = false
    @Volatile private var socketName: String? = null

    fun addListener(listener: (JSONObject) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (JSONObject) -> Unit) {
        listeners -= listener
    }

    /** 幂等启动；返回必须只交给子进程环境的 abstract socket 名称。 */
    fun start(context: Context): String =
        synchronized(lock) {
            if (running && server != null && acceptThread?.isAlive == true) {
                return socketName ?: error("AppControl socket 名称丢失")
            }

            cleanupLocked()
            val nextName = "pd-app-" + UUID.randomUUID()
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
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
                    { r ->
                        Thread(r, "pd-app-request-${threadSeq.incrementAndGet()}").apply {
                            isDaemon = true
                        }
                    },
                    ThreadPoolExecutor.AbortPolicy(),
                )

            server = nextServer
            config = nextConfig
            bots = BotWorkspaces.get(context)
            executor = nextExecutor
            socketName = nextName
            running = true
            val nextThread =
                Thread({ acceptLoop(nextServer) }, "pd-app-accept").apply { isDaemon = true }
            acceptThread = nextThread
            try {
                nextThread.start()
            } catch (e: Throwable) {
                cleanupLocked()
                throw e
            }
            nextName
        }

    /** 关闭监听、排队/已接客户端和线程；幂等。 */
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
        val oldServer = server
        server = null
        socketName = null
        config = null
        bots = null
        val oldExecutor = executor
        executor = null
        acceptThread = null
        runCatching { oldServer?.close() }
        clients.toList().forEach(::closeClient)
        clients.clear()
        oldExecutor?.shutdownNow()
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
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_TIMEOUT_MS)
                val pool =
                    synchronized(lock) {
                        if (running && server === activeServer) executor else null
                    }
                if (pool == null) {
                    closeClient(client)
                    continue
                }
                try {
                    pool.execute { handleClient(client, deadline) }
                } catch (_: RejectedExecutionException) {
                    runCatching { respondError(client, "请求过多，请稍后重试") }
                    closeClient(client)
                }
            }
        } catch (_: RuntimeException) {
            failed = running
            if (failed) Log.w(TAG, "accept loop failed")
        } finally {
            if (failed)
                synchronized(lock) {
                    if (server === activeServer) cleanupLocked()
                }
        }
    }

    private fun handleClient(client: LocalSocket, deadline: Long) {
        try {
            if (!running) return
            val request = decodeUtf8(readRequestLine(client, deadline))
            when (val command = AppControlPolicy.parseRequest(request)) {
                is AppControlPolicy.Command.BotRoute -> {
                    val current = synchronized(lock) { bots } ?: throw RequestFailure("Bot 服务未运行")
                    val input =
                        JSONObject()
                            .put("op", "messageRoute")
                            .put("sessionId", command.sessionId)
                            .put("cwd", command.cwd)
                    command.targetId?.let { input.put("targetId", it) }
                    command.targetSessionId?.let { input.put("targetSessionId", it) }
                    command.admission?.let { input.put("admission", it) }
                    command.deliveryId?.let { input.put("deliveryId", it) }
                    respondSuccess(client, current.call(input))
                }
                is AppControlPolicy.Command.BotContext -> {
                    val current = synchronized(lock) { bots } ?: throw RequestFailure("Bot 服务未运行")
                    respondSuccess(
                        client,
                        current.call(
                            JSONObject()
                                .put("op", "context")
                                .put("sessionId", command.sessionId)
                                .put("cwd", command.cwd),
                        ),
                    )
                }
                is AppControlPolicy.Command.Get -> {
                    val current = synchronized(lock) { config } ?: throw RequestFailure("设置服务未运行")
                    val value = current.appControlSettings().toJson()
                    respondSuccess(client, value)
                }
                is AppControlPolicy.Command.Update -> {
                    val current = synchronized(lock) { config } ?: throw RequestFailure("设置服务未运行")
                    val settings =
                        try {
                            current.updateAppControl(command.patch)
                        } catch (_: Exception) {
                            Log.w(TAG, "settings commit failed")
                            throw RequestFailure("保存设置失败")
                        }

                    if (command.patch.keepAwakeWhileRunning != null) {
                        runCatching { DshService.refreshProtection() }
                    }
                    listeners.forEach { listener ->
                        runCatching { listener(settings.toJson()) }
                    }
                    respondSuccess(client, settings.toJson())
                }
            }
        } catch (_: SocketTimeoutException) {
            runCatching { respondError(client, "请求超时") }
        } catch (e: RequestFailure) {
            runCatching { respondError(client, e.message ?: "请求失败") }
        } catch (e: AppControlPolicy.Rejected) {
            runCatching { respondError(client, e.message ?: "请求无效") }
        } catch (_: IOException) {
            if (running) runCatching { respondError(client, "读取请求失败") }
        } catch (_: Exception) {
            Log.w(TAG, "request failed")
            runCatching { respondError(client, "处理请求失败") }
        } finally {
            closeClient(client)
        }
    }

    private fun readRequestLine(socket: LocalSocket, deadline: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        val input = socket.inputStream
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw SocketTimeoutException()
            socket.soTimeout =
                minOf(
                        TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L),
                        Int.MAX_VALUE.toLong(),
                    )
                    .toInt()
            val next = input.read()
            if (next == -1) break
            if (next == '\n'.code) return withoutCarriageReturn(bytes.toByteArray())
            if (bytes.size() >= AppControlPolicy.MAX_REQUEST_BYTES) {
                throw RequestFailure("请求超过 8192 字节")
            }
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
        val output = socket.outputStream
        output.write(bytes)
        output.flush()
    }

    private fun closeClient(client: LocalSocket) {
        clients -= client
        runCatching { client.close() }
    }

    private class RequestFailure(message: String) : Exception(message)
}
