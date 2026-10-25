package com.personaldashboard.app

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object PluginPlatform {
    private var instance: PluginStore? = null
    private val lifecycle = Any()

    private data class Run(
        val id: String,
        val bundle: JSONObject,
        val cancellation: PluginCancellation,
        val actionDeadlineNanos: Long? = null,
    )

    private val runs = java.util.concurrent.ConcurrentHashMap<String, Run>()
    private val streamCleanups = ConcurrentHashMap<String, () -> Unit>()
    private val actionLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val managementLocks = ConcurrentHashMap<String, Any>()
    private val recoveringRemovals = java.util.concurrent.atomic.AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun store(context: Context): PluginStore {
        instance?.let {
            return it
        }
        return PluginStore(File(context.filesDir, "PLUGINS/.registry")).also { instance = it }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun changed() {
        listeners.forEach { runCatching { it() } }
    }

    fun manage(
        context: Context,
        a: JSONObject,
        cancellation: PluginCancellation? = null,
        deadlineNanos: Long? = null,
    ): JSONObject {
        val s = store(context)
        val id = a.optString("id")
        val action = a.getString("action")
        if (action == "invoke")
            return invoke(
                context,
                id,
                a.getString("actionName"),
                JSONObject(a.optString("inputsJson", "{}")),
                cancellation ?: PluginCancellation(),
                deadlineNanos = deadlineNanos,
            )
        if (action == "fetch") return PluginFetch.fetch(a, cancellation ?: PluginCancellation())
        if (action == "fetchCancel")
            return JSONObject().put("cancelled", PluginFetch.cancel(a.optString("requestId")))
        if (action == "list") return s.list()
        if (action == "read") return s.read(id)
        if (action == "install") PluginContract.validate(a.getJSONObject("bundle"))
        val changedId =
            if (action == "install")
                a.getJSONObject("bundle").getJSONObject("manifest").getString("id")
            else id
        require(action in setOf("install", "enable", "rollback", "remove")) { "不支持此插件管理操作" }
        val key = PluginContract.id(changedId)
        return synchronized(managementLocks.getOrPut(key) { Any() }) {
            if (action == "remove") {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                    "插件移除必须在后台执行"
                }
                synchronized(lifecycle) {
                    cancelPluginRuns(key)
                    check(PluginTts.invalidateRevision(context, key, null)) { "插件语音文件清理失败" }
                    s.remove(key)
                }
                // Close old pages first. All slow browser work runs outside the global action lock.
                changed()
                cleanupRemoval(context, key)
            } else {
                if (action == "install" && !s.installed(key)) {
                    // A pending old uninstall must finish before this ID can be installed again.
                    if (key in s.pendingRemovals()) {
                        val cleanup = cleanupRemoval(context, key)
                        check(!cleanup.optBoolean("cleanupPending")) { "旧插件清理未完成，请稍后重试安装" }
                    } else PluginBrowserStorage.clear(context, key)
                }
                val result =
                    synchronized(lifecycle) {
                        cancelPluginRuns(key)
                        check(PluginTts.invalidateRevision(context, key, null)) { "插件语音文件清理失败" }
                        when (action) {
                            "install" -> s.install(a.getJSONObject("bundle"))
                            "enable" -> s.enable(key, a.getBoolean("enabled"))
                            else -> s.rollback(key)
                        }
                    }
                try {
                    val sync = PluginBackground.reconcile(context)
                    if (sync.optInt("errors") > 0)
                        result.put("warning", sync.optString("error", "后台计划暂未同步"))
                } catch (_: Exception) {
                    result.put("warning", "插件更改已保存，后台计划暂未同步，将在返回 App 后重试")
                }
                changed()
                result
            }
        }
    }

    private fun cancelPluginRuns(id: String) {
        PluginTts.stopPlugin(id)
        PluginSiteBrowserHost.closePlugin(id)
        PluginFetchStream.discardPendingCancels(id)
        runs.entries
            .filter { it.value.id == id }
            .forEach { (key, run) ->
                run.cancellation.cancel()
                if (key.startsWith("stream:")) {
                    runs.remove(key, run)
                    streamCleanups.remove(key)?.invoke()
                }
            }
    }

    /**
     * Caller owns this ID's management lock; other plugins continue while WebView clears storage.
     */
    private fun cleanupRemoval(context: Context, id: String): JSONObject {
        val s = store(context)
        var cleared = true
        fun clean(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                cleared = false
                android.util.Log.w("PluginCleanup", "Cleanup pending: ${e.javaClass.simpleName}")
            }
        }
        clean { PluginImages(context).remove(id) }
        clean { PluginBackground.cancel(context, id) }
        clean {
            context
                .getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(id, PluginBackgroundPolicy.jobId(id))
            android.app.PendingIntent.getActivity(
                    context,
                    PluginBackgroundPolicy.jobId(id),
                    PluginBackground.openIntent(context, id),
                    android.app.PendingIntent.FLAG_NO_CREATE or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                ?.cancel()
        }
        clean { check(PluginTts.invalidateRevision(context, id, null)) }
        clean { PluginBrowserStorage.clear(context, id) }
        clean { PluginApiSessions.of(context).clearPlugin(id) }
        clean { PluginSiteBrowserHost.clearPlugin(context, id) }
        if (cleared) clean { s.finishRemoval(id) }
        return s.list().put("removed", true).put("cleanupPending", !cleared).apply {
            if (!cleared) put("warning", "插件已移除，专属数据尚未清理完，将在返回 App 后重试")
        }
    }

    /**
     * Retry only still-pending IDs under the same lock as installation; never erase a reinstall.
     */
    fun recoverPendingRemovals(context: Context) {
        if (!recoveringRemovals.compareAndSet(false, true)) return
        try {
            val s = store(context)
            for (id in s.pendingRemovals()) synchronized(managementLocks.getOrPut(id) { Any() }) {
                if (id in s.pendingRemovals()) {
                    changed()
                    cleanupRemoval(context, id)
                }
            }
        } finally {
            recoveringRemovals.set(false)
        }
    }

    fun capability(
        context: Context,
        cfg: Config,
        a: JSONObject,
        receivedAtNanos: Long = System.nanoTime(),
    ): JSONObject {
        val id = a.getString("id")
        val capability = a.getString("capability")
        val run = runs[a.getString("invocationId")] ?: error("插件动作已结束或取消")
        require(run.id == id) { "插件调用身份不一致" }
        run.cancellation.check()
        store(context).requireCapability(id, capability)
        require(capability in PluginContract.caps(run.bundle)) { "插件未声明此能力" }
        val arguments = a.optJSONObject("arguments") ?: JSONObject()
        require(store(context).read(id).getString("_revision") == a.getString("revision")) {
            "插件已更新，旧动作已停止"
        }
        return when (capability) {
            "plugin.state" -> {
                val s = store(context)
                synchronized(run.cancellation) {
                    run.cancellation.check()
                    s.setData(id, arguments.getJSONObject("state"), run.bundle)
                }
                JSONObject().put("saved", true)
            }
            "plugin.media" -> media(context, id, arguments, run.cancellation)
            "plugin.apiSession" -> withActiveRevision(context, id, run.bundle.getString("_revision")) {
                run.cancellation.check()
                PluginApiSessions.of(context).call(id, arguments)
            }
            "plugin.model" -> PluginModel(context, cfg).call(id, arguments, run.cancellation)
            "plugin.background" ->
                background(
                    context,
                    id,
                    arguments,
                    run.bundle.getString("_revision"),
                    run.cancellation,
                )
            "plugin.notification" ->
                notification(
                    context,
                    id,
                    arguments,
                    run.bundle.getString("_revision"),
                    run.cancellation,
                )
            "plugin.tts" ->
                PluginTts(context)
                    .call(
                        id,
                        arguments,
                        run.bundle.getString("_revision"),
                        run.cancellation,
                        android.os.SystemClock.elapsedRealtime() -
                            (System.nanoTime() - receivedAtNanos) / 1000000,
                        run.actionDeadlineNanos,
                    )
            "app.call" ->
                appCall(
                    context,
                    cfg,
                    arguments,
                    run.cancellation,
                    run.id,
                    run.bundle.getString("_revision"),
                    "action:" + a.getString("invocationId"),
                    receivedAtNanos,
                )
            else -> error("宿主尚不支持此能力")
        }
    }

    /** Page calls have the same immutable revision and cancellation boundary as native actions. */
    fun pageCapability(
        context: Context,
        cfg: Config,
        id: String,
        revision: String,
        capability: String,
        arguments: JSONObject,
        parent: PluginCancellation,
        receivedAt: Long = android.os.SystemClock.elapsedRealtime(),
    ): JSONObject {
        val key = java.util.UUID.randomUUID().toString()
        val token = PluginCancellation()
        val detachParent = parent.onCancel { token.cancel() }
        try {
            synchronized(lifecycle) {
                val bundle = store(context).activeBundle(id)
                require(bundle.getString("_revision") == revision) { "插件已更新，旧页面已失效" }
                store(context).requireCapability(id, capability)
                require(capability in PluginContract.caps(bundle)) { "插件未声明此能力" }
                token.check()
                runs[key] = Run(id, bundle, token)
            }
            return when (capability) {
                "plugin.apiSession" -> PluginApiSessions.of(context).call(id, arguments)
                "plugin.model" -> PluginModel(context, cfg).call(id, arguments, token)
                "plugin.media" -> media(context, id, arguments, token)
                "plugin.tts" -> PluginTts(context).call(id, arguments, revision, token, receivedAt)
                "plugin.background" -> background(context, id, arguments, revision, token)
                "plugin.notification" -> notification(context, id, arguments, revision, token)
                else -> throw IllegalArgumentException("不支持的插件服务")
            }.also { token.check() }
        } finally {
            detachParent()
            runs.remove(key)
            token.cancel()
        }
    }

    /** Credentials are chosen from host identity, never from request-supplied plugin IDs. */
    private fun sessionJar(context: Context, id: String, revision: String, args: JSONObject): okhttp3.CookieJar? {
        val credentials = args.optString("credentials", "omit")
        require(credentials in setOf("omit", "include")) { "credentials 只支持 omit 或 include" }
        if (credentials == "omit") return null
        return synchronized(lifecycle) {
            val bundle = store(context).activeBundle(id)
            require(bundle.getString("_revision") == revision) { "插件已更新，旧页面已失效" }
            store(context).requireCapability(id, "plugin.apiSession")
            require("plugin.apiSession" in PluginContract.caps(bundle)) { "插件未声明 plugin.apiSession" }
            PluginApiSessions.of(context).credentialsJar(args, id)
        }
    }

    fun pageFetch(context: Context, id: String, revision: String, args: JSONObject, parent: PluginCancellation): JSONObject {
        val key = java.util.UUID.randomUUID().toString()
        val token = PluginCancellation()
        val detach = parent.onCancel { token.cancel() }
        try {
            val jar = synchronized(lifecycle) {
                val bundle = store(context).activeBundle(id)
                require(bundle.getString("_revision") == revision) { "插件已更新，旧页面已失效" }
                token.check()
                val value = sessionJar(context, id, revision, args)
                runs[key] = Run(id, bundle, token)
                value
            }
            return PluginFetch.fetch(args, token, jar).also { token.check() }
        } finally { detach(); runs.remove(key); token.cancel() }
    }

    /** Page streams are revision-bound runs, so update/disable/removal cancels their calls. */
    fun startPageStream(
        context: Context,
        id: String,
        revision: String,
        args: JSONObject,
        parent: PluginCancellation,
        receivedAtNanos: Long,
        owner: String = "page",
    ): JSONObject {
        val externalRequest = PluginFetchStream.parse(args)
        val scoped = streamId(id, revision, owner, externalRequest.requestId)
        val request = externalRequest.copy(requestId = scoped)
        val key = "stream:$scoped"
        val token = PluginCancellation()
        val detachParent = parent.onCancel { token.cancel() }
        if (streamCleanups.putIfAbsent(key, detachParent) != null) {
            detachParent()
            error("请求编号正在使用")
        }
        var credentialJar: okhttp3.CookieJar? = null
        val run =
            try {
                synchronized(lifecycle) {
                    PluginFetchStream.consumePendingCancel(scoped)
                    val bundle = store(context).activeBundle(id)
                    require(bundle.getString("_revision") == revision) { "插件已更新，旧页面已失效" }
                    token.check()
                    credentialJar = sessionJar(context, id, revision, args)
                    val value = Run(id, bundle, token)
                    require(runs.putIfAbsent(key, value) == null) { "请求编号正在使用" }
                    value
                }
            } catch (e: Exception) {
                streamCleanups.remove(key)?.invoke()
                throw e
            }
        token.onCancel {
            runs.remove(key, run)
            streamCleanups.remove(key)?.invoke()
        }
        fun finishRun() {
            if (runs.remove(key, run)) token.cancel()
            streamCleanups.remove(key)?.invoke()
        }
        return try {
            PluginFetchStream.start(
                    request,
                    token,
                    receivedAtNanos,
                    {},
                    credentialJar,
                )
                .put("requestId", externalRequest.requestId)
        } catch (e: Exception) {
            finishRun()
            throw e
        }
    }

    fun pollPageStream(
        context: Context,
        id: String,
        revision: String,
        requestId: String,
        owner: String = "page",
    ): JSONObject {
        val scoped = streamId(id, revision, owner, requestId)
        val key = "stream:$scoped"
        val run = runs[key] ?: error("流式请求不存在或已结束")
        require(run.id == id) { "插件流式请求身份不一致" }
        synchronized(lifecycle) {
            store(context).requireRevision(id, revision)
            run.cancellation.check()
        }
        val value = PluginFetchStream.poll(scoped)
        if (value.optBoolean("done")) {
            if (runs.remove(key, run)) run.cancellation.cancel()
            streamCleanups.remove(key)?.invoke()
        }
        return value
    }

    internal fun streamId(id: String, revision: String, owner: String, requestId: String): String =
        "$id\u0000$revision\u0000$owner\u0000$requestId"

    fun cancelPageStream(
        id: String,
        revision: String,
        requestId: String,
        owner: String = "page",
    ): JSONObject {
        val scoped = streamId(id, revision, owner, requestId)
        val key = "stream:$scoped"
        val run =
            synchronized(lifecycle) {
                runs[key]
                    ?: return JSONObject()
                        .put(
                            "cancelled",
                            PluginFetchStream.cancelBeforeStart(scoped),
                        )
            }
        require(run.id == id) { "插件流式请求身份不一致" }
        val cancelled = PluginFetchStream.cancel(scoped)
        if (runs.remove(key, run)) run.cancellation.cancel()
        streamCleanups.remove(key)?.invoke()
        return JSONObject().put("cancelled", cancelled)
    }

    private fun media(
        context: Context,
        pluginId: String,
        request: JSONObject,
        cancellation: PluginCancellation,
    ): JSONObject {
        val media = PluginImages(context)
        val thread = request.getString("threadId")
        return when (request.getString("op")) {
            "payload" ->
                JSONObject()
                    .put("images", media.payload(pluginId, thread, request.getJSONArray("images")))
            "image" ->
                JSONObject()
                    .put(
                        "dataUrl",
                        media
                            .payload(
                                pluginId,
                                thread,
                                org.json
                                    .JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("id", request.getString("mediaId"))
                                            .put("name", request.optString("name", "图片.jpg")),
                                    ),
                            )
                            .getJSONObject(0)
                            .getString("dataUrl"),
                    )
            "preview" -> media.preview(pluginId, thread, request.getString("previewId"))
            "stagePreviews" ->
                media.stagePreviews(
                    pluginId,
                    thread,
                    request.getJSONObject("detail"),
                    cancellation,
                )
            "clear" -> {
                cancellation.check()
                val refs = request.optJSONArray("images")
                if (refs == null) media.clearAll(pluginId, thread)
                else media.clear(pluginId, thread, refs)
                JSONObject().put("ok", true)
            }
            else -> throw IllegalArgumentException("不支持的附件操作")
        }
    }

    private fun notification(
        context: Context,
        id: String,
        request: JSONObject,
        revision: String,
        token: PluginCancellation,
    ): JSONObject =
        synchronized(lifecycle) {
            store(context).requireRevision(id, revision)
            token.check()
            PluginBackground.notify(context, id, request)
        }

    internal fun <T> withActiveRevision(
        context: Context,
        id: String,
        revision: String,
        block: () -> T,
    ): T =
        synchronized(lifecycle) {
            store(context).requireRevision(id, revision)
            block()
        }

    private fun background(
        context: Context,
        id: String,
        request: JSONObject,
        revision: String,
        token: PluginCancellation,
    ): JSONObject =
        synchronized(lifecycle) {
            store(context).requireRevision(id, revision)
            token.check()
            when (request.getString("op")) {
                "schedule" -> PluginBackground.schedule(context, id, request)
                "status" -> PluginBackground.status(context, id)
                "cancel" -> PluginBackground.cancel(context, id)
                else -> throw IllegalArgumentException("不支持的后台任务操作")
            }
        }

    /** Native attachment reads share revision/cancellation boundaries with plugin actions. */
    fun uiMedia(
        context: Context,
        id: String,
        revision: String,
        args: JSONObject,
        cancellation: PluginCancellation,
    ): JSONObject {
        val key = java.util.UUID.randomUUID().toString()
        synchronized(lifecycle) {
            val bundle = store(context).activeBundle(id)
            require(bundle.getString("_revision") == revision)
            cancellation.check()
            runs[key] = Run(id, bundle, cancellation)
        }
        try {
            return media(context, id, args, cancellation)
        } finally {
            runs.remove(key)
            cancellation.cancel()
        }
    }

    fun importImages(
        context: Context,
        id: String,
        revision: String,
        thread: String,
        uris: List<android.net.Uri>,
    ): JSONArray {
        val key = java.util.UUID.randomUUID().toString()
        val token = PluginCancellation()
        synchronized(lifecycle) {
            val bundle = store(context).activeBundle(id)
            require(bundle.getString("_revision") == revision)
            runs[key] = Run(id, bundle, token)
        }
        try {
            val media = PluginImages(context)
            val refs = media.import(id, thread, uris, token)
            return refs
        } finally {
            runs.remove(key)
            token.cancel()
        }
    }

    private fun appCall(
        context: Context,
        cfg: Config,
        request: JSONObject,
        cancellation: PluginCancellation,
        pluginId: String,
        revision: String,
        streamOwner: String,
        receivedAtNanos: Long,
    ): JSONObject {
        val args = request.optJSONObject("args") ?: JSONObject()
        return when (request.getString("method")) {
            "http" ->
                Net(cfg, retryNetworkFailures = false) { _, _ -> }
                    .http(args.apply { put("timeoutMs", 25000) }) { call ->
                        cancellation.onCancel { call.cancel() }
                        cancellation.check()
                    }
                    .apply { remove("headers") }
            "fetch" -> PluginFetch.fetch(args, cancellation, sessionJar(context, pluginId, revision, args))
            "fetchStreamStart" ->
                startPageStream(
                    context,
                    pluginId,
                    revision,
                    args,
                    cancellation,
                    receivedAtNanos,
                    streamOwner,
                )
            "fetchStreamPoll" ->
                pollPageStream(
                    context,
                    pluginId,
                    revision,
                    args.getString("requestId"),
                    streamOwner,
                )
            "fetchStreamCancel" ->
                cancelPageStream(pluginId, revision, args.getString("requestId"), streamOwner)
            "plugin.media" -> media(context, pluginId, args, cancellation)
            else -> throw IllegalArgumentException("宿主尚未提供此插件接口")
        }
    }

    fun invoke(
        context: Context,
        id: String,
        action: String,
        inputs: JSONObject,
        cancellation: PluginCancellation = PluginCancellation(),
        expectedRevision: String? = null,
        deadlineNanos: Long? = null,
    ): JSONObject {
        // The caller owns its cancellation boundary (for example a reusable page bridge).
        // Finishing this action must close only the child, including failure paths.
        val invocationCancellation = PluginCancellation()
        val detachParent = cancellation.onCancel { invocationCancellation.cancel() }
        try {
            return synchronized(actionLocks.getOrPut(id) { Any() }) {
                invocationCancellation.check()
                val s = store(context)
                val invocationId = java.util.UUID.randomUUID().toString()
                val bundle =
                    synchronized(lifecycle) {
                        s.requireActive(id)
                        val current = s.read(id)
                        require(
                            expectedRevision == null ||
                                current.getString("_revision") == expectedRevision,
                        ) {
                            "插件已更新，旧后台动作已停止"
                        }
                        require(current.getJSONObject("manifest").getString("kind") == "native") {
                            "插件类型无效"
                        }
                        require(Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(action)) { "插件动作无效" }
                        invocationCancellation.check()
                        runs[invocationId] =
                            Run(
                                id,
                                current,
                                invocationCancellation,
                                // A queued RPC can have less time left than a fresh process.
                                minOf(
                                    System.nanoTime() +
                                        java.util.concurrent.TimeUnit.SECONDS.toNanos(
                                            PluginProcess.EXECUTION_TIMEOUT_SECONDS,
                                        ),
                                    deadlineNanos ?: Long.MAX_VALUE,
                                ),
                            )
                        current
                    }
                try {
                    val result =
                        PluginProcess.run(
                            context,
                            JSONObject()
                                .put("bundle", bundle)
                                .put("action", action)
                                .put("inputs", inputs)
                                .put("state", s.data(id))
                                .put("invocationId", invocationId),
                            invocationCancellation,
                        )
                    PluginContract.validateView(result.getJSONObject("view"))
                    synchronized(invocationCancellation) {
                        invocationCancellation.check()
                        s.setData(id, result.optJSONObject("state") ?: s.data(id), bundle)
                    }
                    result
                } finally {
                    runs.remove(invocationId)
                }
            }
        } finally {
            detachParent()
            invocationCancellation.cancel()
        }
    }
}

/**
 * 通用外网请求：插件页面与原生动作共用同一条通路。
 *
 * - 只允许 http/https，地址里不能带账号；可访问公网、局域网和本地服务（内置对话运行时使用独立 dsh 目标）
 * - 不注入任何宿主凭据：dsh Cookie 等宿主认证信息不会进入这里
 * - 方法、请求头数量与长度、正文大小、超时与响应大小都有上限
 * - 支持 requestId 取消与宿主取消信号
 */
internal object PluginFetch {
    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val MAX_TIMEOUT_MS = 60_000L
    const val MAX_REQUEST_BYTES = 1024 * 1024
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_BINARY_BYTES = 4 * 1024 * 1024
    const val MAX_HEADERS = 16
    const val MAX_HEADER_VALUE = 2048
    val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    private val HEADER_DENY =
        setOf(
            "cookie",
            "host",
            "connection",
            "content-length",
            "transfer-encoding",
            "proxy-authorization",
            "proxy-connection",
            "upgrade",
            "te",
            "trailer",
        )
    internal val calls = ConcurrentHashMap<String, Call>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(MAX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Standard HTTP redirects; OkHttp strips Authorization when the target host changes.
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun cancel(requestId: String): Boolean {
        if (requestId.isEmpty()) return false
        val call = calls.remove(requestId) ?: return false
        call.cancel()
        return true
    }

    /** A validated request; parsing is pure so the policy stays unit testable. */
    internal data class FetchRequest(
        val url: okhttp3.HttpUrl,
        val method: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val contentType: String,
        val timeoutMs: Long,
        val maxBytes: Long,
        val responseType: String,
        val requestId: String,
        val credentials: String = "omit",
    )

    internal fun parse(args: JSONObject): FetchRequest {
        val credentials = args.optString("credentials", "omit")
        require(credentials in setOf("omit", "include")) { "credentials 只支持 omit 或 include" }
        val url = validateUrl(args.getString("url"))
        val method = args.optString("method", "GET").trim().uppercase()
        require(method in METHODS) { "不支持的请求方法" }
        val timeout = args.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(1000L, MAX_TIMEOUT_MS)
        val maxBytes =
            args
                .optLong("maxBytes", MAX_RESPONSE_BYTES.toLong())
                .coerceIn(1024L, MAX_BINARY_BYTES.toLong())
        val responseType = args.optString("responseType", "auto")
        require(responseType in setOf("auto", "text", "base64")) { "不支持的响应类型" }
        val requestId = args.optString("requestId")
        val headers = LinkedHashMap<String, String>()
        val rawHeaders = args.optJSONObject("headers")
        if (rawHeaders != null) {
            require(rawHeaders.length() <= MAX_HEADERS) { "请求头过多" }
            for (name in rawHeaders.keys()) {
                val lower = name.lowercase()
                require(lower !in HEADER_DENY) { "不允许设置请求头 $lower" }
                require(name.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "请求头名称无效" }
                val value = rawHeaders.optString(name)
                require(
                    value.length <= MAX_HEADER_VALUE &&
                        !value.contains('\n') &&
                        !value.contains('\r'),
                ) {
                    "请求头内容无效"
                }
                headers[name] = value
            }
        }
        val bodyBytes: ByteArray? =
            when {
                !args.isNull("bodyBase64") ->
                    try {
                        Base64.decode(args.getString("bodyBase64"), Base64.DEFAULT)
                    } catch (_: Exception) {
                        throw IllegalArgumentException("请求正文不是有效的 Base64")
                    }
                !args.isNull("body") -> args.optString("body").toByteArray(Charsets.UTF_8)
                else -> null
            }
        if (bodyBytes != null) require(bodyBytes.size <= MAX_REQUEST_BYTES) { "请求正文超过 1 MiB" }
        val contentType =
            args.optString("contentType", "").takeIf { it.isNotEmpty() && it != "null" }
                ?: "application/json; charset=utf-8"
        return FetchRequest(
            url,
            method,
            headers,
            bodyBytes,
            contentType,
            timeout,
            maxBytes,
            responseType,
            requestId,
            credentials,
        )
    }

    fun fetch(
        args: JSONObject,
        cancellation: PluginCancellation = PluginCancellation(),
        cookieJar: okhttp3.CookieJar? = null,
    ): JSONObject {
        val request = parse(args)
        val url = request.url
        val method = request.method
        val timeout = request.timeoutMs
        val maxBytes = request.maxBytes
        val responseType = request.responseType
        val requestId = request.requestId
        val builder = Request.Builder().url(url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val mediaType =
            request.contentType.toMediaTypeOrNull()
                ?: "application/json; charset=utf-8".toMediaTypeOrNull()!!
        val body =
            when {
                request.body != null -> request.body.toRequestBody(mediaType)
                method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(mediaType)
                else -> null
            }
        builder.method(method, body)
        val call =
            client
                .newBuilder()
                .cookieJar(if (request.credentials == "include") requireNotNull(cookieJar) { "缺少插件身份，不能使用持久登录会话" } else okhttp3.CookieJar.NO_COOKIES)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()
                .newCall(builder.build())
        if (requestId.isNotEmpty())
            require(calls.putIfAbsent(requestId, call) == null) { "请求编号正在使用" }
        val detachCancellation = cancellation.onCancel { call.cancel() }
        var finalUrl = url.toString()
        var status = 0
        try {
            cancellation.check()
            call.execute().use { response ->
                finalUrl = response.request.url.toString()
                status = response.code
                val contentType = response.header("Content-Type").orEmpty()
                val payload = ByteArrayOutputStream()
                var total = 0
                var truncated = false
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) {
                            truncated = true
                            break
                        }
                        payload.write(buffer, 0, count)
                    }
                }
                val bytes = payload.toByteArray()
                val binary =
                    when (responseType) {
                        "base64" -> true
                        "text" -> false
                        else -> !PluginMime.isText(contentType)
                    }
                if (truncated && binary)
                    throw PluginRequestException(
                        "response_too_large",
                        "响应超过 ${maxBytes / 1024} KiB 限制",
                        status,
                        finalUrl,
                    )
                val headersOut = JSONObject()
                for (name in response.headers.names()) {
                    if (name.equals("set-cookie", true)) continue
                    headersOut.put(
                        name.lowercase(),
                        response.headers.values(name).joinToString(", "),
                    )
                }
                val result =
                    JSONObject()
                        .put("status", response.code)
                        .put("ok", response.isSuccessful)
                        .put("timeout", false)
                        .put("cancelled", false)
                        .put("code", if (response.isSuccessful) "ok" else "http_error")
                        .put("retryable", response.code == 429 || response.code >= 500)
                        .put("contentType", contentType)
                        .put("bytes", total)
                        .put("truncated", truncated)
                        .put("finalUrl", response.request.url.toString())
                        .put("headers", headersOut)
                if (binary) result.put("base64", java.util.Base64.getEncoder().encodeToString(bytes))
                else result.put("text", String(bytes, Charsets.UTF_8))
                cancellation.check()
                return result
            }
        } catch (e: IOException) {
            throw PluginRequestException.network(
                e,
                finalUrl,
                status,
                cancellation.cancelled ||
                    (call.isCanceled() && e !is java.io.InterruptedIOException),
            )
        } finally {
            detachCancellation()
            if (requestId.isNotEmpty()) calls.remove(requestId, call)
        }
    }

    /** Stream requests reuse exactly the synchronous request policy and redirect configuration. */
    internal fun newStreamCall(
        request: FetchRequest,
        timeoutMs: Long,
        cookieJar: okhttp3.CookieJar? = null,
    ): Call {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val mediaType =
            request.contentType.toMediaTypeOrNull()
                ?: "application/json; charset=utf-8".toMediaTypeOrNull()!!
        val body =
            when {
                request.body != null -> request.body.toRequestBody(mediaType)
                request.method in setOf("POST", "PUT", "PATCH") ->
                    ByteArray(0).toRequestBody(mediaType)
                else -> null
            }
        builder.method(request.method, body)
        return client
            .newBuilder()
            .cookieJar(if (request.credentials == "include") requireNotNull(cookieJar) { "缺少插件身份，不能使用持久登录会话" } else okhttp3.CookieJar.NO_COOKIES)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
    }

    /** HTTP(S) targets, without automatically borrowing host credentials. */
    internal fun validateUrl(raw: String): okhttp3.HttpUrl {
        val value = raw.trim()
        require(value.isNotEmpty()) { "缺少请求地址" }
        val parsed = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("请求地址无效")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "请求地址不能包含账号信息" }
        require(parsed.scheme == "http" || parsed.scheme == "https") { "只支持 HTTP 或 HTTPS 地址" }
        return parsed
    }
}
