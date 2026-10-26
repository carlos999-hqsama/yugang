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
    private val pluginPageVisible: () -> Boolean = { true },
    private val trustedAgentVoiceSource: Boolean = false,
    private val browserGesture: () -> Boolean = { false },
    private val browserDownloadEvent: (JSONObject) -> Unit = {},
    private val pluginServiceHandler: ((String, JSONObject, PluginCancellation) -> JSONObject)? =
        null,
) {
    private val io = PluginBridgeExecutor()
    private val voiceFrames = Executors.newSingleThreadExecutor()
    private val hostCalls=java.util.concurrent.ConcurrentHashMap<String,PluginCancellation>()
    private val serviceCancellation = PluginCancellation()
    private val streamOwner = "page:" + java.util.UUID.randomUUID()
    private val browserOwner = java.util.UUID.randomUUID().toString()
    @Volatile private var pageStreams = PluginCancellation()
    private val detachServiceStreams = serviceCancellation.onCancel { pageStreams.cancel() }
    @Volatile private var closed = false
    private val filePreviews = FilePreviewDialog(act, pluginIdentity)
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
        if(method=="hostCall"&&(argsJson?.length?:0)>384*1024){replyTo(id,JSONObject().put("error","通用能力参数过大").put("code","invalid_arguments"));return}
        val a =
            try {
                PluginBridgeArguments.parse(argsJson)
            } catch (_: IllegalArgumentException) {
                rejectInvalidArguments(method, id)
                return
            }
        if(method=="notificationAccess"){
            act.runOnUiThread{try{gate();check(act.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED));val identity=pluginIdentity
                if(identity!=null){check(pluginPageVisible());PluginHostServices.requireCurrent(act,identity.first,identity.second,"plugin.notifications.read")}else check(trustedAgentVoiceSource)
                PluginNotificationInbox.settings(act);replyTo(id,JSONObject().put("value",JSONObject().put("opened",true)))
            }catch(_:Exception){replyTo(id,JSONObject().put("error","请在前台打开通知使用权设置").put("code","foreground_required"))}}
            return
        }
        if (method == "hostCancel") {
            hostCalls[a.optString("requestId")]?.cancel()
            replyTo(id,JSONObject().put("value",true));return
        }
        if (method == "hostCapabilities") {
            try { io.execute {
                try { gate();val identity=pluginIdentity
                    val result=if(identity==null)PluginCapabilities.describe(PluginContract.capabilities,true).put("diagnostics",PluginCallAdmission.diagnostics()).put("storage",PluginPlatform.store(act).recoveryStatus())
                        else PluginHostServices.discovery(act,identity.first,identity.second,true)
                    replyTo(id,JSONObject().put("value",result))
                }catch(_:Exception){replyTo(id,JSONObject().put("error","能力目录暂不可用").put("code","unavailable"))}
            }}catch(_:Exception){replyTo(id,JSONObject().put("error","宿主调用繁忙").put("code","busy"))};return
        }
        if (method == "hostCall") {
            val identity=pluginIdentity
            if(identity==null){replyTo(id,JSONObject().put("error","通用能力调用需要已安装插件身份").put("code","identity_required"));return}
            val token=PluginCancellation();val detach=serviceCancellation.onCancel{token.cancel()}
            hostCalls[id]=token;val answered=java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(value:JSONObject){if(!answered.compareAndSet(false,true))return;hostCalls.remove(id,token);detach();token.cancel();if(!closed)replyTo(id,value)}
            try{
                val capability=a.getString("capability");val arguments=a.optJSONObject("arguments")?:JSONObject()
                require(arguments.toString().toByteArray().size<=PluginCapabilities.MAX_ARGUMENT_BYTES){"通用能力参数过大"}
                val spec=PluginCapabilities.operation(capability,arguments.getString("op"),true)
                PluginHostServices.requireCurrent(act,identity.first,identity.second,capability)
                if(arguments.getString("op") in spec.foreground){
                    act.runOnUiThread{try{gate();token.check();check(pluginPageVisible()&&act.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)){"此操作需要可见的前台插件页面"}
                        act.hostUi.execute(identity.first,identity.second,capability,arguments,token){value->finish(if(value.has("error"))value else JSONObject().put("value",value))}
                    }catch(_:Exception){finish(JSONObject().put("error","系统交互未打开，请回到插件页面重试").put("code","foreground_required"))}}
                }else{
                    val userInitiated=capability=="plugin.transfer"&&arguments.optString("op") in setOf("download","upload","resume")&&pluginPageVisible()&&browserGesture()
                    io.execute{try{gate();val value=PluginHostServices.call(act,identity.first,identity.second,capability,arguments,token,page=true,userInitiated=userInitiated);finish(JSONObject().put("value",value))}
                        catch(e:Exception){finish(if(e is PluginRequestException)e.payload() else JSONObject().put("error",e.message?:"能力调用失败").put("code","host_error"))}}
                }
            }catch(e:Exception){finish(JSONObject().put("error",e.message?:"能力调用失败").put("code",if(e is java.util.concurrent.RejectedExecutionException)"busy" else "invalid_request"))}
            return
        }
        if (method == "contentOpen" || method == "botAttachmentPick") {
            if (!trustedAgentVoiceSource || pluginIdentity != null) {
                replyTo(id, JSONObject().put("error", "此接口只供核心对话使用"));return
            }
            val token = PluginCancellation()
            val detach = serviceCancellation.onCancel { token.cancel() }
            val answered = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(result: JSONObject) {
                if (!answered.compareAndSet(false, true)) return
                detach();token.cancel()
                if (!closed) replyTo(id, result)
            }
            try {
                if (method == "contentOpen") io.execute {
                    try {
                        token.check();gate()
                        ChatContent(act, net).open(browserOwner, a, token) { value -> finish(JSONObject().put("value", value)) }
                    } catch (error: Exception) { finish(JSONObject().put("error", error.message ?: "内容未打开")) }
                } else act.runOnUiThread {
                    try {
                        token.check();gate()
                        act.chooseBotFiles { uris ->
                            try {
                                io.execute {
                                    try {
                                        token.check()
                                        val selected = if (uris.isEmpty()) org.json.JSONArray() else act.botAttachments.stage(uris)
                                        if (closed || token.cancelled) {
                                            for (index in 0 until selected.length()) act.botAttachments.remove(selected.getJSONObject(index).getString("handle"))
                                            finish(JSONObject().put("error", "页面已关闭"))
                                        } else finish(JSONObject().put("value", selected))
                                    } catch (error: Exception) { finish(JSONObject().put("error", error.message ?: "文件读取失败")) }
                                }
                            } catch (_: java.util.concurrent.RejectedExecutionException) { finish(JSONObject().put("error", "页面已关闭")) }
                        }
                    } catch (error: Exception) { finish(JSONObject().put("error", error.message ?: "无法选择文件")) }
                }
            } catch (error: Exception) { finish(JSONObject().put("error", error.message ?: "页面已关闭")) }
            return
        }
        if (method == "pluginNotify" && a.optString("op") == "request") {
            val identity = pluginIdentity
            if (identity == null) { replyTo(id, JSONObject().put("error", "通知权限申请仅供已安装的前台插件页面使用"));return }
            val answered = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(result: JSONObject) { if (!closed && answered.compareAndSet(false, true)) replyTo(id, result) }
            try {
                io.execute {
                    try {
                        gate()
                        act.runOnUiThread {
                            try {
                                if (closed) return@runOnUiThread
                                gate()
                                PluginPlatform.withActiveRevision(act, identity.first, identity.second) {
                                    PluginPlatform.store(act).requireCapability(identity.first, "plugin.notification")
                                }
                                val (current, immediate) =
                                    PluginNotificationPermission.requestDecision(
                                        act,
                                        pageVisible = pluginPageVisible(),
                                    )
                                if (immediate == "page_hidden") {
                                    finish(JSONObject().put("error", "插件页面当前不可见，不能申请通知权限"))
                                    return@runOnUiThread
                                }
                                if (immediate != null) {
                                    finish(
                                        JSONObject().put(
                                            "value",
                                            PluginNotificationPermissionPolicy.requestResult(
                                                current,
                                                immediate,
                                                requested = false,
                                            ),
                                        )
                                    )
                                    return@runOnUiThread
                                }
                                check(pluginPageVisible()) { "插件页面当前不可见，不能申请通知权限" }
                                PluginBackground.requestNotificationPermission(act, identity.first, a) { result ->
                                    try {
                                        gate();serviceCancellation.check()
                                        check(pluginPageVisible()) { "插件页面已隐藏，通知权限结果不再应用于此插件" }
                                        PluginPlatform.store(act).requireRevision(identity.first, identity.second)
                                        finish(JSONObject().put("value", result))
                                    } catch (_: Exception) { finish(JSONObject().put("error", "页面已失效，通知权限结果不再应用于此插件")) }
                                }
                            } catch (error: Exception) { finish(JSONObject().put("error", error.message ?: "无法申请通知权限")) }
                        }
                    } catch (error: Exception) { finish(JSONObject().put("error", error.message ?: "无法申请通知权限")) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { finish(JSONObject().put("error", "页面已关闭")) }
            return
        }
        if (method == "siteBrowserOpen") {
            val identity = pluginIdentity
            if (identity == null) {
                replyTo(id, JSONObject().put("error", "仅已安装插件页面可打开网站"));return
            }
            try {
                io.execute {
                    try {
                        gate()
                        PluginSiteBrowserHost.open(act, browserOwner, identity.first, identity.second, a, browserGesture,
                            onClosed = { receipt ->
                                if (!closed) runCatching { gate();replyTo(id, JSONObject().put("value", receipt)) }
                            },
                            onDownload = { receipt -> if (!closed) runCatching { gate();browserDownloadEvent(receipt) } })
                    } catch (error: Exception) { if (!closed) replyTo(id, JSONObject().put("error", error.message ?: "网站未打开")) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { if (!closed) replyTo(id, JSONObject().put("error", "页面已关闭，网站未打开")) }
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
                        else if (e is BotAttachmentException) e.payload()
                        else JSONObject().put("error", e.message ?: e.javaClass.simpleName),
                    )
                }
            }
        }
        if (ui) act.runOnUiThread(task) else try { io.execute(task) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            if(!closed)replyTo(id,JSONObject().put("error","宿主调用繁忙，请稍后重试").put("code","busy").put("retryable",true))
        }
    }

    fun close() {
        pluginIdentity?.let {PluginBluetooth.release(it.first)}
        closed = true
        serviceCancellation.cancel()
        filePreviews.close()
        PluginSiteBrowserHost.closeOwner(browserOwner)
        detachServiceStreams()
        io.shutdownNow()
        voiceFrames.shutdownNow()
    }

    /** Hidden pages cannot retain network streams; resume installs a fresh stream boundary. */
    fun pause() {
        pluginIdentity?.let {PluginBluetooth.release(it.first)}
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
            "plugins" -> {
                val identity = pluginIdentity
                when(a.optString("action")){
                    "fetch" -> if(identity!=null)PluginPlatform.pageFetch(act,identity.first,identity.second,a,serviceCancellation,browserOwner) else PluginFetch.fetch(a,serviceCancellation,scope="core:$browserOwner")
                    "fetchCancel" -> if(identity!=null)PluginPlatform.cancelPageFetch(act,identity.first,identity.second,a.optString("requestId"),browserOwner) else JSONObject().put("cancelled",PluginFetch.cancel(a.optString("requestId"),"core:$browserOwner"))
                    else -> PluginPlatform.manage(act,a,serviceCancellation)
                }
            }
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
            "pluginApiSession" -> pluginService("plugin.apiSession", a)
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
            "previewFile" -> {
                filePreviews.show(FileTextContent.fromJson(a))
                JSONObject().put("preview", true)
            }
            "openFile" -> DownloadedFiles.open(act, a)
            "bots" -> bots.call(a)
            "botAttachmentUpload" -> {
                check(trustedAgentVoiceSource && pluginIdentity == null) { "此接口只供核心对话使用" }
                act.botAttachments.upload(a, serviceCancellation) { progress -> if (!closed) act.emit("attachmentProgress", progress) }
            }
            "botAttachmentRemove" -> {
                check(trustedAgentVoiceSource && pluginIdentity == null) { "此接口只供核心对话使用" }
                act.botAttachments.remove(a.getString("handle"))
            }
            "botAttachmentCancel" -> {
                check(trustedAgentVoiceSource && pluginIdentity == null) { "此接口只供核心对话使用" }
                JSONObject().put("cancelled", act.botAttachments.cancel(a.getString("requestId")))
            }
            "botAttachmentForgetReceipt" -> {
                check(trustedAgentVoiceSource && pluginIdentity == null) { "此接口只供核心对话使用" }
                act.requireBotSession(a.getString("botId"), a.getString("sessionId"))
                act.botAttachments.forgetReceipt(a.getString("handle"), a.getString("sessionId"))
                true
            }
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
