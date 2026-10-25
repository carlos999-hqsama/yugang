package com.personaldashboard.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One offscreen rendering of an installed plugin page, used by the Bot verification channel.
 *
 * It renders with the same surface as the foreground page ([PluginPageSurface]: same origin rules,
 * same strict CSP, same framework assets, same package rules) and talks to the same native channel
 * ([Bridge]). It never opens the foreground page, never navigates the app and never writes the
 * installed plugin state: the bridge allowlist in [PluginPreviewPolicy] refuses management and
 * external actions before they run.
 *
 * The WebView lives in a background host view that is attached to the activity but never takes
 * focus and never receives touches, so the user keeps the foreground page and the input focus.
 */
class PluginPreview(
    private val activity: MainActivity,
    private val cfg: Config,
    private val panes: Panes,
    bundle: JSONObject,
    private val widthPx: Int,
    private val heightPx: Int,
    originTag: String,
    private val installed: Boolean,
    private val network: PluginPreviewPolicy.Network,
    private val allowAudio: Boolean = false,
    private val onClosed: (String) -> Unit = {},
) {
    private val surface = PluginPageSurface(activity, panes, bundle, originTag)
    private val store = PluginPlatform.store(activity)
    private val closed = AtomicBoolean(false)
    @Volatile private var readyLatch = CountDownLatch(1)
    @Volatile private var readyComplete = false
    private val consoleErrors = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val net = Net(cfg) { channel, value -> emit(channel, value) }
    private val previewCapabilities = PluginContract.caps(bundle)
    private val bridge =
        Bridge(
            activity,
            cfg,
            net,
            panes,
            ::reply,
            ::gate,
            ::ready,
            { blockedNavigation() },
            pluginServiceHandler = { capability, request, cancellation ->
                if (capability == "plugin.notification" && request.optString("op") == "status") {
                    require("plugin.notification" in previewCapabilities) { "插件未声明 plugin.notification" }
                    PluginNotificationPermission.status(activity, preview = true)
                } else {
                    require(capability == "plugin.model" && request.optString("op") != "budget") {
                        "预览不允许修改宿主服务配置"
                    }
                    PluginModel.forPreview(activity, cfg, previewCapabilities)
                        .call(surface.id, request, cancellation)
                }
            },
        )

    private var host: FrameLayout? = null
    @Volatile private var appResumed = true
    @Volatile private var closeReason: String = ""
    /** True while the preview origin is being swept by the host owned purge document. */
    @Volatile private var purging = false
    @Volatile private var closedAt: Long = 0L

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private val web =
        object : WebView(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
        }

    init {
        configurePluginWebSettings(web.settings)
        web.settings.mediaPlaybackRequiresUserGesture = !allowAudio
        web.settings.offscreenPreRaster = true
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        web.setBackgroundColor(0xFFFFFFFF.toInt())
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        web.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    net.closeAll()
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    req: WebResourceRequest,
                ): WebResourceResponse? {
                    if (closed.get()) return if (purgeRequest(req)) null else surface.forbidden()
                    val framework = surface.frameworkKey(req.url)
                    if (framework != null)
                        return if (req.method == "GET") surface.frameworkResponse(framework)
                        else surface.forbidden()
                    val key = surface.packageKey(req.url)
                    if (key != null)
                        return if (req.method == "GET") surface.packageResponse(key)
                        else surface.forbidden()
                    if (req.url.scheme == "https" && req.url.host == Panes.FILE_HOST)
                        return networkResponse { surface.imageResponse(req) }
                    return if (surface.remoteResource(req)) networkResponse { null }
                    else surface.forbidden()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    req: WebResourceRequest,
                ): Boolean {
                    if (purgeRequest(req) || surface.packageKey(req.url) != null) return false
                    // Expected containment, not a renderer failure. Keep the preview session alive.
                    return true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame)
                        recordError("load", error.description?.toString() ?: "页面加载失败")
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    close("渲染进程已退出")
                    return true
                }
            }
        web.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        recordError("console", consoleMessage.message().take(500))
                    }
                    return true
                }

                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    // There is no browser promise to reply to; just decline this one chooser.
                    callback.onReceiveValue(null)
                    return true
                }
            }
        web.addJavascriptInterface(PreviewNative(), "PDNative")
        attach()
        web.loadUrl(surface.startUrl)
    }

    /** The plugin page never gets to call the host directly: the allowlist is checked first. */
    private inner class PreviewNative {
        @JavascriptInterface
        fun isPreview(): Boolean = true

        @JavascriptInterface
        fun call(method: String, id: String, argsJson: String?) {
            val args =
                try {
                    PluginBridgeArguments.parse(argsJson)
                } catch (_: Exception) {
                    // A malformed request is refused before policy or the host bridge sees it;
                    // it never falls back to an empty argument set.
                    refuseCall(
                        method,
                        id,
                        PluginPreviewPolicy.BridgeFailure(
                            PluginBridgeArguments.ERROR_CODE,
                            "预览请求参数不是合法 JSON",
                        ),
                        recordPageError = true,
                    )
                    return
                }
            val refusal = PluginPreviewPolicy.bridgeFailure(method, args)
            if (refusal != null) {
                refuseCall(method, id, refusal)
                return
            }
            if (
                method == "plugins" &&
                    args.optString("action") == "fetch" &&
                    simulateNativeFetch(args, id)
            )
                return
            if (method == "pluginModel" && args.optString("op", "chat") != "status") {
                if (network.mode == "slow") Thread.sleep(network.delayMs)
                if (network.mode == "offline") {
                    reply(
                        id,
                        JSONObject()
                            .put(
                                "value",
                                JSONObject()
                                    .put("ok", false)
                                    .put("outcome", "known_not_sent")
                                    .put(
                                        "error",
                                        JSONObject()
                                            .put("code", "offline")
                                            .put("message", "预览网络已断开，未发出模型请求"),
                                    ),
                            ),
                    )
                    return
                }
            }
            if (PluginPreviewPolicy.usesNetwork(method)) {
                if (network.mode == "slow") Thread.sleep(network.delayMs)
                if (network.mode == "offline") {
                    if (method == "sseOpen") {
                        emit(
                            "sse:$id",
                            JSONObject().put("kind", "error").put("error", "预览网络已断开，未发出宿主请求"),
                        )
                        return
                    }
                    reply(
                        id,
                        JSONObject()
                            .put("error", "预览网络已断开，未发出宿主请求")
                            .put("code", "offline")
                            .put("status", 0),
                    )
                    return
                }
            }
            bridge.call(method, id, argsJson)
        }
    }

    /**
     * Refuses one bridge call before it runs. Streams have no call callback - the plugin SDK only
     * listens on `sse:<id>` - so a stream error must never be sent through `__pd_cb`.
     */
    private fun refuseCall(
        method: String,
        id: String,
        failure: PluginPreviewPolicy.BridgeFailure,
        recordPageError: Boolean = false,
    ) {
        if (recordPageError) recordError("arguments", failure.message)
        when (PluginBridgeArguments.errorRoute(method)) {
            PluginBridgeArguments.ErrorRoute.SSE ->
                emit("sse:$id", failure.payload().put("kind", "error"))
            PluginBridgeArguments.ErrorRoute.WS -> {
                val event =
                    JSONObject()
                        .put("kind", "close")
                        .put("code", 1008)
                        .put("reason", failure.message)
                        .put("errorCode", failure.code)
                        .put("expected", failure.expected)
                failure.reason?.let { event.put("previewReason", it) }
                emit("ws:$id", event)
            }
            PluginBridgeArguments.ErrorRoute.CALLBACK -> reply(id, failure.payload())
        }
    }

    /**
     * Only the host owned sweep document may still be requested after close; no plugin resource or
     * navigation is ever let through with it.
     */
    private fun purgeRequest(req: WebResourceRequest): Boolean =
        purging &&
            req.isForMainFrame &&
            (req.url.scheme == "data" || req.url.toString().startsWith("${surface.storageOrigin}/"))

    /** The same network profile covers direct WebView resources and native.fetch requests. */
    private fun networkResponse(response: () -> WebResourceResponse?): WebResourceResponse? {
        return when (network.mode) {
            "offline" -> surface.forbidden(503, "Preview Network Offline")
            "slow" ->
                try {
                    Thread.sleep(network.delayMs)
                    response()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    surface.forbidden(499, "Preview Delay Cancelled")
                }
            else -> response()
        }
    }

    /** Returns true when the preview replies itself instead of entering PluginPlatform.fetch. */
    private fun simulateNativeFetch(args: JSONObject, id: String): Boolean {
        if (network.mode != "online") {
            try {
                PluginFetch.validateUrl(args.optString("url"))
            } catch (_: Exception) {
                return false
            }
        }
        if (network.mode == "slow") {
            try {
                Thread.sleep(network.delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                reply(id, JSONObject().put("error", "预览网络延迟已取消"))
                return true
            }
        }
        if (network.mode != "offline") return false
        val body =
            JSONObject()
                .put("status", 0)
                .put("code", "offline")
                .put("timeout", false)
                .put("cancelled", false)
                .put("retryable", true)
                .put("ok", false)
                .put("contentType", "")
                .put("bytes", 0)
                .put("truncated", false)
                .put("finalUrl", args.optString("url"))
                .put("headers", JSONObject())
                .put("text", "")
        reply(id, JSONObject().put("value", body))
        return true
    }

    /**
     * A background, non interactive host view: the preview never covers or steals the foreground.
     */
    private fun attach() {
        val content: ViewGroup =
            activity.findViewById<ViewGroup>(android.R.id.content)
                ?: (activity.window.decorView as ViewGroup)
        val container = FrameLayout(activity)
        container.alpha = 1f
        container.isFocusable = false
        container.isFocusableInTouchMode = false
        container.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        container.addView(web, FrameLayout.LayoutParams(widthPx, heightPx))
        content.addView(container, 0, FrameLayout.LayoutParams(widthPx, heightPx))
        host = container
    }

    /** Waits until the page framework finished booting (or the deadline passed). */
    fun awaitReady(timeoutMs: Long = PluginPreviewPolicy.PAGE_READY_TIMEOUT_MS): Boolean =
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && readyComplete && !closed.get()

    fun isClosed(): Boolean = closed.get()

    fun pluginId(): String = surface.id

    fun revision(): String = surface.revision

    fun storageOrigin(): String = surface.storageOrigin

    fun closedReason(): String = closeReason

    fun closedAt(): Long = closedAt

    fun status(): JSONObject =
        JSONObject()
            .put("plugin", surface.id)
            .put("revision", surface.revision)
            .put("viewport", JSONObject().put("width", widthPx).put("height", heightPx))
            .put("origin", surface.storageOrigin)
            .put("network", network.json())
            .put("allowAudio", allowAudio)
            .put("closed", closed.get())
            .put("reason", closeReason)
            .put("closedAt", closedAt)

    /** DOM text, console errors and the real rendered frame. */
    fun snapshot(selectorRaw: String?, offsetRaw: Int?, limitRaw: Int?): JSONObject {
        val selector =
            selectorRaw?.trim().orEmpty().let {
                if (it.isEmpty()) "" else PluginPreviewPolicy.selector(it)
            }
        val (offset, limit) = PluginPreviewPolicy.snapshotPage(offsetRaw, limitRaw)
        val raw =
            evaluate(script(SNAPSHOT_JS, selector = selector, offset = offset, limit = limit))
                ?: return JSONObject().put("ok", false).put("error", "预览页面没有响应")
        val parsed =
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                return JSONObject().put("ok", false).put("error", "预览返回了无法解析的内容")
            }
        if (parsed.has("error"))
            return JSONObject().put("ok", false).put("error", parsed.optString("error"))
        val result =
            JSONObject()
                .put("ok", true)
                .put("plugin", surface.id)
                .put("url", parsed.optString("url"))
                .put("title", parsed.optString("title"))
                .put("text", parsed.optString("text"))
                .put("html", parsed.optString("html"))
                .put("textOffset", offset)
                .put("textLimit", limit)
                .put("textLength", parsed.optInt("textLength"))
                .put("textLimitReached", parsed.optBoolean("textLimitReached"))
                .put("textLimitReason", parsed.optString("textLimitReason"))
                .put("htmlTruncated", parsed.optBoolean("htmlTruncated"))
                .put("truncated", parsed.optBoolean("truncated"))
                .put(
                    "nextOffset",
                    if (parsed.isNull("nextOffset")) JSONObject.NULL
                    else parsed.optInt("nextOffset"),
                )
                .put("nodes", parsed.optInt("nodes"))
                .put("viewport", parsed.optJSONObject("viewport"))
                .put("scroll", parsed.optJSONObject("scroll") ?: JSONObject())
                .put("errors", errorsJson())
        val capture = capturePng()
        result.put("png", capture.optJSONObject("png") ?: JSONObject())
        result.put("capture", capture.optString("capture"))
        result.put("captureReason", capture.optString("reason"))
        return result
    }

    /** Click one element by CSS selector inside the preview. */
    fun click(selectorRaw: String?): JSONObject {
        val selector = PluginPreviewPolicy.selector(selectorRaw)
        val raw =
            evaluate(script(CLICK_JS, selector = selector))
                ?: return JSONObject().put("ok", false).put("error", "预览页面没有响应")
        return parse(raw)
    }

    /** Type into one element by CSS selector and fire the usual input events. */
    fun input(selectorRaw: String?, textRaw: String?): JSONObject {
        val selector = PluginPreviewPolicy.selector(selectorRaw)
        val text = PluginPreviewPolicy.inputText(textRaw)
        val raw =
            evaluate(script(INPUT_JS, selector = selector, text = text))
                ?: return JSONObject().put("ok", false).put("error", "预览页面没有响应")
        return parse(raw)
    }

    /** Scroll the window or one element. */
    fun scroll(selectorRaw: String?, offsetRaw: Int?): JSONObject {
        val selector =
            selectorRaw?.trim().orEmpty().let {
                if (it.isEmpty()) "" else PluginPreviewPolicy.selector(it)
            }
        val offset = PluginPreviewPolicy.scrollOffset(offsetRaw)
        val raw =
            evaluate(script(SCROLL_JS, selector = selector, offset = offset))
                ?: return JSONObject().put("ok", false).put("error", "预览页面没有响应")
        return parse(raw)
    }

    /** Reload only this WebView; the foreground page and current app selection are untouched. */
    fun reload(): JSONObject =
        try {
            val started =
                onUi {
                    if (closed.get()) false
                    else {
                        readyComplete = false
                        readyLatch = CountDownLatch(1)
                        web.reload()
                        true
                    }
                } ?: false
            JSONObject().put("ok", started).put("reloaded", started)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("reloaded", false).put("error", e.message ?: "重新加载失败")
        }

    /** Clear only this preview's WebView storage origin; never use WebView's clear-all APIs. */
    fun clearStorage(): JSONObject {
        return try {
            onUi {
                WebStorage.getInstance().deleteOrigin(surface.storageOrigin)
                true
            } ?: return JSONObject().put("ok", false).put("error", "预览存储清理没有响应")
            val raw =
                evaluate(CLEAR_STORAGE_JS)
                    ?: return JSONObject().put("ok", false).put("error", "预览页面没有响应")
            parse(raw).put("origin", surface.storageOrigin)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "预览存储清理失败")
        }
    }

    /** The app went to the background: stop rendering, keep the session. */
    fun pause() {
        appResumed = false
        onUi {
            if (!closed.get()) {
                web.evaluateJavascript(
                    "window.__pd_plugin_visible=false;document.querySelectorAll('audio,video').forEach(e=>e.pause());window.__pd_plugin_lifecycle?.(false)",
                    null,
                )
                web.onPause()
            }
            null
        }
    }

    /** The app came back: rendering continues in the same offscreen host view. */
    fun resume() {
        appResumed = true
        onUi {
            if (!closed.get()) {
                web.onResume()
                web.evaluateJavascript(
                    "window.__pd_plugin_visible=true;window.__pd_plugin_lifecycle?.(true)",
                    null,
                )
            }
            null
        }
    }

    /**
     * Release everything: WebView, native bridge, network client and the host view.
     *
     * The preview origin is temporary, so its drafts must not outlive the session: the plugin's own
     * teardown runs first, then the WebView navigates to a host owned document on that exact origin
     * which clears localStorage/sessionStorage. Only after that sweep reported an empty origin does
     * the WebView get destroyed and the origin deleted, so a late write from the plugin page cannot
     * restore a cleared draft. No foreground and no sibling origin is touched.
     */
    fun close(reason: String = "已关闭") {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        closedAt = System.currentTimeMillis()
        onClosed(reason)
        readyLatch.countDown()
        bridge.close()
        net.dispose()
        activity.runOnUiThread {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            val swept = AtomicBoolean(false)
            val teardown = Runnable {
                if (!finished.compareAndSet(false, true)) return@Runnable
                // Temporary origins are never reused; release their isolated DOM storage too.
                runCatching { WebStorage.getInstance().deleteOrigin(surface.storageOrigin) }
                web.stopLoading()
                web.removeJavascriptInterface("PDNative")
                (web.parent as? ViewGroup)?.removeView(web)
                host?.let { (it.parent as? ViewGroup)?.removeView(it) }
                host = null
                web.destroy()
                // A second sweep once the renderer is gone: its teardown flush cannot bring a
                // cleared draft back.
                handler.postDelayed(
                    Runnable {
                        runCatching {
                            WebStorage.getInstance().deleteOrigin(surface.storageOrigin)
                        }
                    },
                    POST_DESTROY_DELETE_DELAY_MS,
                )
            }
            val purge =
                object : Runnable {
                    private var polls = 0

                    override fun run() {
                        if (finished.get()) return
                        if (polls++ >= MAX_PURGE_POLLS) {
                            recordError("storage", "预览存储清理超时，已删除该预览 origin")
                            teardown.run()
                            return
                        }
                        // Re-arm first: an evaluation dropped by the navigation must not stop the
                        // sweep check.
                        handler.postDelayed(this, PURGE_POLL_INTERVAL_MS)
                        try {
                            web.evaluateJavascript(PURGE_STATE_JS) { result ->
                                if (finished.get()) return@evaluateJavascript
                                when (result) {
                                    null,
                                    "null" -> Unit
                                    PURGE_DONE -> teardown.run()
                                    else -> {
                                        recordError("storage", "预览存储清理失败：$result")
                                        teardown.run()
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            teardown.run()
                        }
                    }
                }
            // The purge document replaces the plugin document, so nothing the plugin left behind
            // can write the cleared draft back.
            fun sweep() {
                if (finished.get() || !swept.compareAndSet(false, true)) return
                purging = true
                val started =
                    try {
                        web.loadDataWithBaseURL(
                            "${surface.storageOrigin}/",
                            PURGE_PAGE,
                            "text/html",
                            "utf-8",
                            null,
                        )
                        true
                    } catch (_: Exception) {
                        false
                    }
                if (!started) {
                    teardown.run()
                    return
                }
                handler.postDelayed(purge, PURGE_POLL_INTERVAL_MS)
                handler.postDelayed({ teardown.run() }, PURGE_TIMEOUT_MS)
            }
            try {
                // The plugin's own teardown still runs; the sweep wipes whatever it writes.
                web.evaluateJavascript(UNLOAD_JS) {
                    handler.postDelayed({ sweep() }, UNLOAD_GRACE_MS)
                }
            } catch (_: Exception) {
                sweep()
            }
            // A renderer that never answers the unload hook is still swept, never left behind.
            handler.postDelayed({ sweep() }, UNLOAD_TIMEOUT_MS)
            null
        }
    }

    private fun recordError(kind: String, message: String) {
        val entry = JSONObject().put("kind", kind).put("message", message.take(500))
        consoleErrors.add(entry)
        while (consoleErrors.size > PluginPreviewPolicy.MAX_CONSOLE_ERRORS) consoleErrors.removeAt(
            0,
        )
    }

    private fun errorsJson(): org.json.JSONArray {
        val array = org.json.JSONArray()
        synchronized(consoleErrors) { consoleErrors.forEach { array.put(it) } }
        return array
    }

    private fun emit(channel: String, value: JSONObject) {
        if (closed.get()) return
        val js =
            "window.__pd_ev && window.__pd_ev(${JSONObject.quote(channel)}, ${JSONObject.quote(value.toString())})"
        onUi {
            if (!closed.get()) web.evaluateJavascript(js, null)
            null
        }
    }

    private fun reply(id: String, value: JSONObject) {
        if (closed.get()) return
        val js = "window.__pd_cb(${JSONObject.quote(id)}, ${JSONObject.quote(value.toString())})"
        onUi {
            if (!closed.get()) web.evaluateJavascript(js, null)
            null
        }
    }

    private fun ready() {
        if (closed.get()) return
        readyComplete = true
        onUi {
            if (!closed.get()) {
                web.evaluateJavascript(
                    "window.__pd_plugin_visible=$appResumed;window.__pd_plugin_lifecycle?.($appResumed)",
                    null,
                )
            }
            null
        }
        readyLatch.countDown()
    }

    private fun blockedNavigation() {
        // Bridge navigation is an expected preview refusal; it must not poison later snapshots.
    }

    private fun gate() {
        check(!closed.get()) { "预览已关闭" }
        if (installed) store.requireRevision(surface.id, surface.revision)
    }

    private fun evaluate(script: String, timeoutMs: Long = 8_000): String? {
        if (closed.get()) return null
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        onUi {
            if (closed.get()) {
                latch.countDown()
                return@onUi null
            }
            try {
                web.evaluateJavascript(script) { value ->
                    holder[0] =
                        try {
                            (JSONTokener(value).nextValue() as? String) ?: value
                        } catch (_: Exception) {
                            value
                        }
                    latch.countDown()
                }
            } catch (_: Exception) {
                latch.countDown()
            }
            null
        }
        val completed =
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                close("预览操作已取消")
                throw e
            }
        if (!completed) {
            close("预览操作超时")
            return null
        }
        return holder[0]
    }

    /** Runs a block on the UI thread and waits for it; used for every WebView call. */
    private fun <T> onUi(timeoutMs: Long = 1_500, block: () -> T): T? {
        // Called from the UI thread already (app lifecycle): run inline, waiting would deadlock.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Any?>(1)
        val expired = AtomicBoolean(false)
        activity.runOnUiThread {
            if (expired.get()) {
                latch.countDown()
                return@runOnUiThread
            }
            try {
                holder[0] = block()
            } catch (e: Throwable) {
                holder[0] = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            expired.set(true)
            return null
        }
        val value = holder[0]
        if (value is Throwable) throw value
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /** The real rendered frame; never a placeholder image. */
    private fun capturePng(): JSONObject =
        try {
            val visual = CountDownLatch(1)
            onUi {
                web.postVisualStateCallback(
                    0L,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            visual.countDown()
                        }
                    },
                )
                web.invalidate()
                null
            }
            check(visual.await(3, TimeUnit.SECONDS)) { "预览渲染帧尚未就绪" }
            val bitmap = onUi {
                val width = web.width
                val height = web.height
                if (width <= 0 || height <= 0) return@onUi null
                val scale =
                    Math.min(
                        1.0,
                        PluginPreviewPolicy.MAX_CAPTURE_EDGE_PX.toDouble() /
                            Math.max(width, height),
                    )
                val image =
                    Bitmap.createBitmap(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                val canvas = Canvas(image)
                canvas.scale(scale.toFloat(), scale.toFloat())
                web.draw(canvas)
                image
            }
            if (bitmap == null) {
                JSONObject().put("capture", "unavailable").put("reason", "预览尚未完成布局")
            } else {
                val sourceWidth = bitmap.width
                val sourceHeight = bitmap.height
                val bytes = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                val data = bytes.toByteArray()
                val distinct = distinctColors(bitmap)
                bitmap.recycle()
                if (data.size > PluginPreviewPolicy.MAX_PNG_BYTES) {
                    JSONObject().put("capture", "unavailable").put("reason", "截图超过大小限制")
                } else {
                    JSONObject()
                        .put("capture", if (distinct <= 1) "flat" else "ok")
                        .put("reason", if (distinct <= 1) "画面只有单一颜色，可能是空页面或当前设备没有产生渲染帧" else "")
                        .put(
                            "png",
                            JSONObject()
                                .put("mediaType", "image/png")
                                .put("bytes", data.size)
                                .put("width", sourceWidth)
                                .put("height", sourceHeight)
                                .put("base64", Base64.encodeToString(data, Base64.NO_WRAP)),
                        )
                }
            }
        } catch (e: Exception) {
            JSONObject()
                .put("capture", "unavailable")
                .put("reason", (e.message ?: "截图失败").take(200))
        }

    /** Counts distinct colors on a sampled grid; one color means an empty frame. */
    private fun distinctColors(bitmap: Bitmap): Int {
        val colors = HashSet<Int>()
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                colors.add(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        return colors.size
    }

    private fun parse(raw: String): JSONObject =
        try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "预览返回了无法解析的内容")
        }

    private fun script(
        template: String,
        selector: String = "",
        text: String = "",
        offset: Int = 0,
        limit: Int = 0,
    ): String =
        Regex("__SELECTOR__|__TEXT__|__OFFSET__|__LIMIT__").replace(template) { match ->
            when (match.value) {
                "__SELECTOR__" -> JSONObject.quote(selector)
                "__TEXT__" -> JSONObject.quote(text)
                "__OFFSET__" -> offset.toString()
                else -> limit.toString()
            }
        }

    private companion object {
        const val SNAPSHOT_JS =
            """
(function(){
  try {
    var root = document.getElementById('plugin-root') || document.body;
    var body = document.body;
    var textSource = __SELECTOR__ ? document.querySelector(__SELECTOR__) : body;
    if (__SELECTOR__ && !textSource) return JSON.stringify({error:'未找到匹配元素'});
    var offset = __OFFSET__, limit = __LIMIT__, textLength=0, pageText='', textLimitReached=false;
    var walker=document.createTreeWalker(textSource,NodeFilter.SHOW_TEXT), textNode, previousBlock=null;
    function blockFor(node){
      for(var e=node;e && e!==textSource;e=e.parentElement){var d=getComputedStyle(e).display;if(d==='block'||d==='list-item'||d==='flex'||d==='grid'||d==='table-cell')return e;}
      return textSource;
    }
    function takeText(value){
      for(var symbol of value){if(textLength>=1000000){textLimitReached=true;return false;}if(textLength>=offset&&textLength<offset+limit)pageText+=symbol;textLength++;}
      return true;
    }
    while((textNode=walker.nextNode())){
      var parent=textNode.parentElement;
      if(!parent || parent.closest('script,style,noscript,[hidden]') || !parent.getClientRects().length)continue;
      var computed=getComputedStyle(parent);if(computed.visibility==='hidden'||computed.visibility==='collapse')continue;
      var currentBlock=blockFor(parent);
      if(previousBlock && currentBlock!==previousBlock && !takeText('\n'))break;
      previousBlock=currentBlock;if(!takeText(textNode.nodeValue||''))break;
    }
    var budget=524288, nodeBudget=2000;
    function boundedClone(node){
      if(budget<=0 || nodeBudget--<=0)return null;
      if(node.nodeType===3){var t=String(node.nodeValue||'').slice(0,budget);budget-=t.length;return document.createTextNode(t);}
      if(node.nodeType!==1)return null;
      var copy=node.cloneNode(false);budget-=node.tagName.length*2+5;
      for(var a of Array.from(copy.attributes)){if(budget<=0){copy.removeAttribute(a.name);continue;}var value=a.value.slice(0,Math.min(2048,budget));copy.setAttribute(a.name,value);budget-=a.name.length+value.length+4;}
      for(var child of node.childNodes){if(budget<=0 || nodeBudget<=0)break;var part=boundedClone(child);if(part)copy.appendChild(part);}
      return copy;
    }
    var htmlClone=root?boundedClone(root):null;
    var htmlBytes=new TextEncoder().encode(htmlClone?htmlClone.outerHTML:'');
    var htmlTruncated=budget<=0 || nodeBudget<=0 || htmlBytes.length>524288;
    var htmlText=new TextDecoder().decode(htmlBytes.subarray(0,524288),{stream:true});
    return JSON.stringify({
      viewport: {width: window.innerWidth, height: window.innerHeight},
      title: document.title || '',
      url: String(location.href || ''),
      text: pageText,
      textLength: textLength,
      textLimitReached: textLimitReached,
      textLimitReason: textLimitReached ? '全文超过100万字符，请缩小selector分段读取' : '',
      truncated: textLimitReached || offset + limit < textLength,
      nextOffset: offset + limit < textLength ? offset + limit : null,
      html: htmlText,
      htmlTruncated: htmlTruncated,
      nodes: document.getElementsByTagName('*').length,
      scroll: {x: window.scrollX || 0, y: window.scrollY || 0, height: document.documentElement ? document.documentElement.scrollHeight : 0}
    });
  } catch (e) { return JSON.stringify({error: String((e && e.message) || e)}); }
})()
"""
        const val CLEAR_STORAGE_JS =
            """
(function(){
  try {
    localStorage.clear();
    sessionStorage.clear();
    return JSON.stringify({ok:true});
  } catch (e) { return JSON.stringify({ok:false,error:String((e && e.message) || e)}); }
})()
"""
        const val UNLOAD_JS = "window.__pd_plugin_unload?.().catch(()=>{})"
        /** Grace for the plugin's own asynchronous teardown before its origin is swept. */
        const val UNLOAD_GRACE_MS = 250L
        /** Upper bound for the unload hook; the sweep runs even when the renderer stays silent. */
        const val UNLOAD_TIMEOUT_MS = 900L
        const val PURGE_POLL_INTERVAL_MS = 40L
        const val MAX_PURGE_POLLS = 40
        const val PURGE_TIMEOUT_MS = 1_600L
        const val POST_DESTROY_DELETE_DELAY_MS = 250L
        const val PURGE_STATE_JS = "window.__pd_preview_cleared || null"
        /** evaluateJavascript returns JSON, so the sweep marker arrives quoted. */
        const val PURGE_DONE = "\"done\""
        /**
         * Host owned document on the preview's own origin. It replaces the plugin page, sweeps
         * localStorage/sessionStorage twice and only reports `done` once the origin reads empty.
         */
        const val PURGE_PAGE =
            """<!doctype html><meta charset="utf-8"><script>
(function(){
  var failure=null;
  function sweep(){
    try { localStorage.clear(); sessionStorage.clear(); }
    catch (e) { failure = failure || String((e && e.message) || e); }
  }
  function report(){
    var left=-1;
    try { left = localStorage.length + sessionStorage.length; }
    catch (e) { failure = failure || String((e && e.message) || e); }
    window.__pd_preview_cleared = !failure && left === 0 ? 'done' : (failure || 'left:' + left);
  }
  sweep();
  setTimeout(function(){ sweep(); report(); }, 120);
})();
</script>"""
        const val CLICK_JS =
            """
(function(){
  try {
    var el = document.querySelector(__SELECTOR__);
    if (!el) return JSON.stringify({ok:false, error:'未找到匹配元素'});
    try { el.scrollIntoView({block:'center', inline:'nearest'}); } catch (e) {}
    if (typeof el.click === 'function') el.click();
    else el.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
    return JSON.stringify({ok:true, tag: el.tagName, text: String(el.innerText || '').slice(0,120)});
  } catch (e) { return JSON.stringify({ok:false, error: String((e && e.message) || e)}); }
})()
"""
        const val INPUT_JS =
            """
(function(){
  try {
    var el = document.querySelector(__SELECTOR__);
    if (!el) return JSON.stringify({ok:false, error:'未找到匹配元素'});
    var value = __TEXT__;
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
    if (desc && desc.set) desc.set.call(el, value);
    else if ('value' in el) el.value = value;
    else el.textContent = value;
    el.dispatchEvent(new Event('input', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
    return JSON.stringify({ok:true, tag: el.tagName, value: String(el.value || '').slice(0,120)});
  } catch (e) { return JSON.stringify({ok:false, error: String((e && e.message) || e)}); }
})()
"""
        const val SCROLL_JS =
            """
(function(){
  try {
    var el = __SELECTOR__ ? document.querySelector(__SELECTOR__) : null;
    if(__SELECTOR__ && !el)return JSON.stringify({ok:false,error:'未找到匹配元素'});
    if (el) { el.scrollTop = __OFFSET__; return JSON.stringify({ok:true, scope:'element', scrollTop: el.scrollTop}); }
    window.scrollTo(0, __OFFSET__);
    return JSON.stringify({ok:true, scope:'window', scrollY: window.scrollY || 0});
  } catch (e) { return JSON.stringify({ok:false, error: String((e && e.message) || e)}); }
})()
"""
    }
}
