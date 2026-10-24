package com.personaldashboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Parses bridge arguments before dispatch. A null argument retains the documented `{}` default;
 * malformed documents and non-object JSON values are rejected.
 */
internal object PluginBridgeArguments {
    const val ERROR_MESSAGE = "调用参数必须是 JSON 对象"
    const val ERROR_CODE = "invalid_arguments"

    enum class ErrorRoute {
        CALLBACK,
        SSE,
        WS,
    }

    fun parse(raw: String?): JSONObject {
        val source = raw ?: "{}"
        try {
            val parser = org.json.JSONTokener(source)
            val value = parser.nextValue()
            require(value is JSONObject && parser.nextClean() == '\u0000')
            return value
        } catch (e: Exception) {
            throw IllegalArgumentException(ERROR_MESSAGE, e)
        }
    }

    /**
     * Streaming open calls have no callback pending; their SDK consumers wait on these channels.
     */
    fun errorRoute(method: String): ErrorRoute =
        when (method) {
            "sseOpen" -> ErrorRoute.SSE
            "wsOpen" -> ErrorRoute.WS
            else -> ErrorRoute.CALLBACK
        }
}

/**
 * JS 桥：window.PDNative.call(method, id, argsJson)。 结果通过 window.__pd_cb(id, {value}|{error})
 * 异步回传；网络/IO 在后台线程执行，UI 操作切回主线程。
 */
class Bridge(
    private val act: MainActivity,
    private val cfg: Config,
    private val net: Net,
    private val panes: Panes,
    private val replyTo: (String, JSONObject) -> Unit = act::reply,
    private val gate: () -> Unit = {},
    private val onReady: () -> Unit = act::onPageReady,
    private val navigate: ((String) -> Unit)? = null,
    private val pluginIdentity: Pair<String, String>? = null,
    private val trustedAgentVoiceSource: Boolean = false,
    private val pluginServiceHandler: ((String, JSONObject, PluginCancellation) -> JSONObject)? =
        null,
) {
    private val io = Executors.newCachedThreadPool()
    private val voiceFrames = Executors.newSingleThreadExecutor()
    private val serviceCancellation = PluginCancellation()
    private val streamOwner = "page:" + java.util.UUID.randomUUID()
    @Volatile private var pageStreams = PluginCancellation()
    private val detachServiceStreams = serviceCancellation.onCancel { pageStreams.cancel() }
    @Volatile private var closed = false
    private val bots by lazy { BotWorkspaces.get(act) }

    @JavascriptInterface
    fun call(method: String, id: String, argsJson: String?) {
        val receivedAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
        val startedAtNanos = System.nanoTime()
        if (closed) return
        try {
            gate()
        } catch (_: Exception) {
            return
        }
        val a =
            try {
                PluginBridgeArguments.parse(argsJson)
            } catch (_: IllegalArgumentException) {
                rejectInvalidArguments(method, id)
                return
            }
        if (method == "agentVoiceScope" || method == "agentVoiceFrame") {
            if (!trustedAgentVoiceSource) {
                replyTo(id, JSONObject().put("error", "此接口只供核心对话使用"))
                return
            }
            runCatching {
                voiceFrames.execute {
                    if (closed) return@execute
                    runCatching {
                        gate()
                        if (method == "agentVoiceScope") act.pluginVoice.scope(a)
                        else act.pluginVoice.feed(a, receivedAtNanos)
                        replyTo(id, JSONObject().put("value", true))
                    }
                        .onFailure { replyTo(id, JSONObject().put("error", it.message)) }
                }
            }
            return
        }
        when (method) {
            // 流式通道：无回调，事件走 __pd_ev
            "sseOpen" -> {
                runCatching {
                    io.execute {
                        runCatching {
                            gate()
                            if (!closed) net.sseOpen(id, a)
                        }
                    }
                }
                return
            }
            "sseClose" -> {
                net.sseClose(id)
                return
            }
            "wsOpen" -> {
                runCatching {
                    io.execute {
                        runCatching {
                            gate()
                            if (!closed) net.wsOpen(id, a)
                        }
                    }
                }
                return
            }
            "wsSend" -> {
                net.wsSend(id, a.optString("text"))
                return
            }
            "wsClose" -> {
                net.wsClose(id)
                return
            }
        }
        val ui =
            method in
                setOf(
                    "licenses",
                    "pluginNavigate",
                    "pluginManager",
                    "copy",
                    "toast",
                    "openExternal",
                    "openDshbox",
                    "voice",
                    "browser",
                    "dshWeb",
                    "ready",
                    "dshUnpair",
                    "backgroundSettings",
                )
        val task = Runnable {
            if (closed) return@Runnable
            try {
                gate()
                val value = run(method, a, startedAtNanos)
                gate()
                replyTo(id, JSONObject().put("value", value))
            } catch (e: Throwable) {
                runCatching {
                    gate()
                    replyTo(
                        id,
                        if (e is PluginRequestException) e.payload()
                        else JSONObject().put("error", e.message ?: e.javaClass.simpleName),
                    )
                }
            }
        }
        if (ui) act.runOnUiThread(task) else io.execute(task)
    }

    fun close() {
        closed = true
        serviceCancellation.cancel()
        detachServiceStreams()
        io.shutdownNow()
        voiceFrames.shutdownNow()
    }

    /** Hidden pages cannot retain network streams; resume installs a fresh stream boundary. */
    fun pause() {
        pageStreams.cancel()
    }

    fun resume() {
        if (!closed && pageStreams.cancelled) pageStreams = PluginCancellation()
    }

    private fun rejectInvalidArguments(method: String, id: String) {
        val message = PluginBridgeArguments.ERROR_MESSAGE
        when (PluginBridgeArguments.errorRoute(method)) {
            PluginBridgeArguments.ErrorRoute.CALLBACK ->
                runCatching {
                    gate()
                    if (!closed)
                        replyTo(
                            id,
                            JSONObject()
                                .put("error", message)
                                .put("code", PluginBridgeArguments.ERROR_CODE),
                        )
                }
            PluginBridgeArguments.ErrorRoute.SSE -> net.rejectSse(id, message)
            PluginBridgeArguments.ErrorRoute.WS -> net.rejectWs(id, message)
        }
    }

    private fun pluginService(
        capability: String,
        request: JSONObject,
        receivedAt: Long = android.os.SystemClock.elapsedRealtime(),
    ): JSONObject {
        pluginServiceHandler?.let {
            return it(capability, request, serviceCancellation)
        }
        val identity = pluginIdentity ?: error("此接口只供插件页面使用")
        return PluginPlatform.pageCapability(
            act,
            cfg,
            identity.first,
            identity.second,
            capability,
            request,
            serviceCancellation,
            receivedAt,
        )
    }

    private fun run(m: String, a: JSONObject, startedAtNanos: Long): Any =
        when (m) {
            "licenses" -> {
                act.showLicenses()
                true
            }
            "pluginNavigate" -> {
                (navigate ?: act::openPlugin)(a.getString("id"))
                true
            }
            "pluginManager" -> {
                act.openPluginManager()
                true
            }
            "plugins" -> PluginPlatform.manage(act, a, serviceCancellation)
            "pluginVoice" -> {
                val identity = pluginIdentity ?: error("仅供可见插件页面使用")
                act.pluginVoice.call(identity.first, identity.second, a, serviceCancellation)
            }
            "fetchStreamStart" -> {
                val identity = pluginIdentity ?: error("此接口只供插件页面使用")
                PluginPlatform.startPageStream(
                    act,
                    identity.first,
                    identity.second,
                    a,
                    pageStreams,
                    startedAtNanos,
                    streamOwner,
                )
            }
            "fetchStreamPoll" -> {
                val identity = pluginIdentity ?: error("此接口只供插件页面使用")
                PluginPlatform.pollPageStream(
                    act,
                    identity.first,
                    identity.second,
                    a.getString("requestId"),
                    streamOwner,
                )
            }
            "fetchStreamCancel" -> {
                val identity = pluginIdentity ?: error("此接口只供插件页面使用")
                PluginPlatform.cancelPageStream(
                    identity.first,
                    identity.second,
                    a.getString("requestId"),
                    streamOwner,
                )
            }
            "pluginTts" ->
                pluginService(
                    "plugin.tts",
                    a,
                    android.os.SystemClock.elapsedRealtime() -
                        (System.nanoTime() - startedAtNanos) / 1000000,
                )
            "pluginModel" -> pluginService("plugin.model", a)
            "pluginMedia" -> pluginService("plugin.media", a)
            "pluginBackground" -> pluginService("plugin.background", a)
            "pluginNotify" -> pluginService("plugin.notification", a)
            "deepseekModels" -> DeepseekModels().read(a.optString("key").takeIf { it.isNotBlank() } ?: cfg.deepseekKey)
            "getConfig" -> act.publicConfig()
            "setConfig" -> {
                val modeBefore = cfg.dshMode
                cfg.apply(a)
                if (a.has("keepAwakeWhileRunning")) DshService.refreshProtection()
                if (a.has("dshUrl") || cfg.dshMode != modeBefore)
                    act.runOnUiThread { panes.resetDshWeb() }
                if (cfg.dshMode != modeBefore) {
                    if (cfg.embedded) DshService.start(act)
                    else DshService.start(act, DshService.ACT_STOP)
                } else if (a.has("deepseekKey") && cfg.embedded)
                    DshService.start(act, DshService.ACT_RESTART) // 新 Key 通过环境变量生效，需要重启 dsh
                act.publicConfig()
            }
            "rtStatus" -> Runtime.status(act)
            "rtDiag" -> Runtime.diagnostics(act)
            "rtStart" -> {
                DshService.start(act)
                true
            }
            "rtRestart" -> {
                DshService.start(act, DshService.ACT_RESTART)
                true
            }
            "backgroundSettings" -> act.openBackgroundSettings(a.optString("which"))
            "bots" -> bots.call(a)
            "http" -> {
                if (
                    cfg.embedded &&
                        cfg.keepAwakeWhileRunning &&
                        a.optString("target") == "dsh" &&
                        a.optString("path") == "/api/session/prompt" &&
                        a.optString("method").equals("POST", true)
                )
                    check(
                        DshService.protectPendingTask()
                            .get(1, java.util.concurrent.TimeUnit.SECONDS),
                    ) {
                        "后台保活尚未就绪，请重试"
                    }
                net.http(a)
            }
            "dshPair" -> {
                net.pair(a.optString("input"))
                act.runOnUiThread { panes.resetDshWeb() }
                true
            }
            "dshUnpair" -> {
                cfg.unpair()
                panes.resetDshWeb()
                true
            }
            "copy" -> {
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("pd", a.optString("text")))
                true
            }
            "toast" -> true // 页面内已有同款 toast，这里不重复弹系统 Toast
            "openExternal" -> act.openExternal(a.optString("url"))
            "openDshbox" -> act.openDshbox()
            "voice" -> act.startVoice()
            "browser" -> {
                panes.browser(a)
                true
            }
            "dshWeb" -> {
                panes.dshWeb(a)
                true
            }
            "ready" -> {
                onReady()
                true
            }
            else -> throw IllegalArgumentException("未知方法 $m")
        }
}
