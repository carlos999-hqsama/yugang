package com.personaldashboard.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * One plugin bundle rendered as an isolated origin.
 *
 * The foreground page (see [PluginPage]) and the Bot verification preview (see `PluginPreview`)
 * share this surface, so both use the same origin, the same strict CSP, the same framework assets
 * and the same package/framework resource rules. Only the host window differs.
 */
internal class PluginPageSurface(
    private val activity: MainActivity,
    private val panes: Panes,
    bundle: JSONObject,
    originTag: String = "",
) {
    val id = PluginContract.id(bundle.getJSONObject("manifest").getString("id"))
    val revision =
        bundle.getString("_revision").also {
            require(Regex("[A-Za-z0-9-]{1,128}").matches(it)) { "插件修订号无效" }
        }
    /**
     * The preview uses its own origin so it never shares browser storage with the foreground page.
     */
    val host = "plugin-$id$originTag.pd.local"
    val storageOrigin = "https://$host"
    val origin = "https://$host/$revision/"
    val files =
        bundle.getJSONObject("resources").also { bundledFiles ->
            require(bundledFiles.length() > 0) { "插件没有页面资源" }
            bundledFiles.keys().asSequence().forEach { key ->
                require(
                    bundledFiles.get(key) is String &&
                        SAFE_PATH.matches(key) &&
                        key.split('/').none { it == "." || it == ".." },
                ) {
                    "插件资源路径无效"
                }
            }
        }
    val entry =
        bundle
            .getJSONObject("view")
            .also {
                require(it.getString("type") == "page") { "插件视图必须是 page" }
            }
            .getString("entry")
            .also { requireFileKey(it) }

    private val bootConfig =
        JSONObject()
            .put("pluginId", id)
            .put("entry", origin + entry)
            .put(
                "styles",
                org.json.JSONArray().also { styles ->
                    val names = bundle.getJSONObject("view").getJSONArray("styles")
                    for (i in 0 until names.length()) styles.put(origin + names.getString(i))
                },
            )
    private val shell =
        """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">""" +
            """<meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<link rel="stylesheet" href="/framework/v1/base.css"></head><body>""" +
            """<div id="plugin-root"></div><script type="module" src="__host_boot.js"></script></body></html>"""
    private val bootstrap = "import {boot} from '/framework/v1/page.js'; boot($bootConfig);"

    val startUrl
        get() = origin + "__host_page.html"

    fun packageKey(url: Uri): String? {
        if (url.scheme != "https" || url.host != host || url.port != -1 || url.encodedQuery != null)
            return null
        if (!url.toString().startsWith(origin)) return null
        val path = url.encodedPath ?: return null
        val key = path.removePrefix("/$revision/")
        if (!SAFE_PATH.matches(key) || key.split('/').any { it == "." || it == ".." }) return null
        return key.takeIf { it in setOf("__host_page.html", "__host_boot.js") || files.has(it) }
    }

    fun packageResponse(key: String): WebResourceResponse {
        val body =
            when (key) {
                "__host_page.html" -> shell
                "__host_boot.js" -> bootstrap
                else -> files.getString(key)
            }.toByteArray(Charsets.UTF_8)
        return WebResourceResponse(
            mime(key),
            "utf-8",
            200,
            "OK",
            securityHeaders(),
            ByteArrayInputStream(body),
        )
    }

    fun privateTtsKey(url: Uri): String? {
        if (url.scheme != "https" || url.host != host || url.port != -1 || url.encodedQuery != null)
            return null
        val path = url.encodedPath ?: return null
        val prefix = "/$revision/__host_tts/"
        if (!path.startsWith(prefix)) return null
        val fileName = path.removePrefix(prefix)
        return fileName.takeIf { Regex("^[0-9a-f-]{36}\\.(wav|mp3)$").matches(it) }
    }

    /**
     * Serve a synthesized plugin-private file as an ordinary media URL; no JS bridge is involved.
     */
    fun privateTtsResponse(fileName: String): WebResourceResponse =
        try {
            val file =
                PluginTts.file(activity, id, revision, fileName)
                    ?: return forbidden(404, "Not Found")
            WebResourceResponse(
                if (fileName.endsWith(".wav")) "audio/wav" else "audio/mpeg",
                null,
                200,
                "OK",
                securityHeaders(),
                java.io.FileInputStream(file),
            )
        } catch (_: Exception) {
            forbidden(404, "Not Found")
        }

    fun frameworkKey(url: Uri): String? {
        if (url.scheme != "https" || url.host != host || url.port != -1 || url.encodedQuery != null)
            return null
        val path = url.encodedPath ?: return null
        if (!path.startsWith("/framework/v1/")) return null
        val key = path.removePrefix("/framework/v1/")
        return key.takeIf { PluginContract.resourcePath(it) }
    }

    fun frameworkResponse(key: String): WebResourceResponse =
        try {
            WebResourceResponse(
                mime(key),
                "utf-8",
                200,
                "OK",
                securityHeaders(),
                activity.assets.open("plugin-framework/v1/$key"),
            )
        } catch (_: Exception) {
            forbidden(404, "Not Found")
        }

    fun imageResponse(req: WebResourceRequest): WebResourceResponse {
        if (req.method != "GET" || req.isForMainFrame || req.url.encodedQuery != null)
            return forbidden()
        val path =
            try {
                Uri.decode(req.url.encodedPath ?: return forbidden())
            } catch (_: Exception) {
                return forbidden()
            }
        val segments = path.split('/').drop(1)
        if (
            !path.startsWith("/") ||
                path.startsWith("//") ||
                path.contains('\u0000') ||
                path.contains('\\') ||
                segments.any { it.isEmpty() || it == "." || it == ".." }
        )
            return forbidden()
        return try {
            panes.fetchDshFile(path).also { response ->
                response.responseHeaders =
                    HashMap(response.responseHeaders ?: emptyMap()).apply {
                        putAll(securityHeaders())
                    }
            }
        } catch (_: Exception) {
            forbidden(502, "Bad Gateway")
        }
    }

    fun forbidden(status: Int = 403, reason: String = "Forbidden") =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            status,
            reason,
            securityHeaders(),
            ByteArrayInputStream(ByteArray(0)),
        )

    fun securityHeaders(): Map<String, String> =
        mapOf(
            "Content-Security-Policy" to CSP,
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        )

    private fun requireFileKey(key: String) {
        require(
            SAFE_PATH.matches(key) &&
                key.split('/').none { it == "." || it == ".." } &&
                files.has(key),
        ) {
            "插件资源路径无效"
        }
    }

    // HTTPS data/media are online. Executable resources remain package-local under CSP.
    fun remoteResource(req: WebResourceRequest): Boolean =
        !req.isForMainFrame &&
            req.url.scheme == "https" &&
            req.url.host != null &&
            !req.url.host!!.endsWith(".pd.local")

    companion object {
        val SAFE_PATH = Regex("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")
        const val CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob: https:; font-src 'self'; media-src https: blob:; " +
                "connect-src https: wss:; object-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"

        fun mime(key: String): String =
            when (key.substringAfterLast('.', "").lowercase()) {
                "html",
                "htm" -> "text/html"
                "css" -> "text/css"
                "js",
                "mjs" -> "text/javascript"
                "json",
                "map" -> "application/json"
                "svg" -> "image/svg+xml"
                "txt" -> "text/plain"
                else ->
                    MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(key.substringAfterLast('.', ""))
                        ?: "application/octet-stream"
            }
    }
}

/** Foreground and preview plugin pages share the same WebView settings. */
internal fun configurePluginWebSettings(settings: WebSettings) {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        // targetSdk >= 16 already defaults file-origin cross/universal access to false.
        cacheMode = WebSettings.LOAD_NO_CACHE
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
        mediaPlaybackRequiresUserGesture = true
    }
}

/** An isolated WebView whose origin and every resource come from one active plugin bundle. */
class PluginPage(
    private val activity: MainActivity,
    private val cfg: Config,
    private val panes: Panes,
    bundle: JSONObject,
    private val onNavigate: (String) -> Unit,
    private val onExit: () -> Unit,
) {
    private val store = PluginPlatform.store(activity)
    private val surface = PluginPageSurface(activity, panes, bundle)
    private val id = surface.id
    private val revision = surface.revision

    private val closedToken = AtomicBoolean(false)
    private val exitSent = AtomicBoolean(false)
    private val closed
        get() = closedToken.get()

    private var gestureAt = 0L
    private var visible = true

    private val net = Net(cfg) { channel, value -> emit(channel, value) }
    private val bridge =
        Bridge(
            activity,
            cfg,
            net,
            panes,
            ::reply,
            ::gate,
            ::ready,
            ::navigate,
            pluginIdentity = id to revision,
            pluginPageVisible = { alive() && view.isShown },
            browserGesture = { alive() && visible && consumeRecentGesture() },
            browserDownloadEvent = { emit("browserDownload", it) },
        )

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    val view: WebView =
        object : WebView(activity) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_UP)
                        gestureAt = SystemClock.elapsedRealtime()
                    return super.dispatchTouchEvent(event)
                }
            }
            .apply {
                configurePluginWebSettings(settings)
                setBackgroundColor(0xFFFFFFFF.toInt())
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            req: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (!alive()) return forbidden()
                            val framework = surface.frameworkKey(req.url)
                            if (framework != null)
                                return if (req.method == "GET") surface.frameworkResponse(framework)
                                else forbidden()
                            val key = surface.packageKey(req.url)
                            if (key != null)
                                return if (req.method == "GET") surface.packageResponse(key)
                                else forbidden()
                            val ttsKey = surface.privateTtsKey(req.url)
                            if (ttsKey != null)
                                return if (req.method == "GET") surface.privateTtsResponse(ttsKey)
                                else forbidden()
                            if (req.url.scheme == "https" && req.url.host == Panes.FILE_HOST)
                                return surface.imageResponse(req)
                            return if (surface.remoteResource(req)) null else forbidden()
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            req: WebResourceRequest,
                        ): Boolean {
                            if (surface.packageKey(req.url) != null) return false
                            if (req.url.scheme == "https" && req.url.host == Panes.FILE_HOST)
                                return true
                            val url = req.url.toString()
                            if (
                                (req.url.scheme == "http" || req.url.scheme == "https") &&
                                    (consumeRecentGesture() || req.hasGesture())
                            ) {
                                PluginSiteBrowserHost.openUserLink(activity, "page-link:${this@PluginPage.id}", this@PluginPage.id, this@PluginPage.revision, url)
                            }
                            return true
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            activity.runOnUiThread { requestExit() }
                            return true
                        }
                    }
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onShowFileChooser(
                            view: WebView,
                            callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams,
                        ): Boolean {
                            activity.choosePluginFiles(params) { uris ->
                                activity.runOnUiThread {
                                    if (!alive() || uris.isEmpty()) callback.onReceiveValue(null)
                                    else callback.onReceiveValue(uris.toTypedArray())
                                }
                            }
                            return true
                        }
                    }
                addJavascriptInterface(bridge, "PDNative")
                loadUrl(surface.startUrl)
            }

    fun emit(channel: String, value: JSONObject) {
        if (!alive()) return
        val js =
            "window.__pd_ev && window.__pd_ev(${JSONObject.quote(channel)}, ${JSONObject.quote(value.toString())})"
        activity.runOnUiThread { if (alive()) view.evaluateJavascript(js, null) }
    }

    fun back() {
        activity.runOnUiThread {
            if (!alive()) return@runOnUiThread
            view.evaluateJavascript(BACK_SCRIPT) { result ->
                if (alive() && result?.trim() in setOf("false", "\"false\"")) requestExit()
            }
        }
    }

    fun pause() {
        activity.pluginVoice.pageVisible(id, revision, false)
        if (!closed) {
            bridge.pause()
            visible = false
            view.evaluateJavascript(
                "window.__pd_plugin_visible=false;window.__pd_plugin_lifecycle?.(false)",
                null,
            )
            view.onPause()
        }
    }

    fun resume() {
        if (!closed) {
            activity.pluginVoice.pageVisible(id, revision, true)
            bridge.resume()
            visible = true
            view.onResume()
            view.evaluateJavascript(
                "window.__pd_plugin_visible=true;window.__pd_plugin_lifecycle?.(true)",
                null,
            )
        }
    }

    fun close(permanentlyRemoved: Boolean = false) {
        if (!closedToken.compareAndSet(false, true)) return
        closeResources(permanentlyRemoved)
    }

    private fun closeResources(permanentlyRemoved: Boolean = false) {
        activity.pluginVoice.pageVisible(id, revision, false)
        bridge.close()
        net.dispose()
        activity.runOnUiThread {
            val destroyed = AtomicBoolean(false)
            val destroy = Runnable {
                if (destroyed.compareAndSet(false, true)) {
                    view.stopLoading()
                    view.removeJavascriptInterface("PDNative")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
            }
            if (permanentlyRemoved) {
                // An unload hook must not restore a draft after uninstall cleanup.
                destroy.run()
                return@runOnUiThread
            }
            // Hooks get a chance to release JS state; native requests are already closed.
            try {
                view.evaluateJavascript("window.__pd_plugin_unload?.().catch(()=>{})") {
                    destroy.run()
                }
            } catch (_: Exception) {
                destroy.run()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(destroy, 300)
        }
    }

    private fun requestExit() {
        if (!exitSent.compareAndSet(false, true) || !closedToken.compareAndSet(false, true)) return
        closeResources()
        onExit()
    }

    private fun reply(id: String, value: JSONObject) {
        if (!alive()) return
        val js = "window.__pd_cb(${JSONObject.quote(id)}, ${JSONObject.quote(value.toString())})"
        activity.runOnUiThread { if (alive()) view.evaluateJavascript(js, null) }
    }

    private fun ready() {
        activity.pluginVoice.pageVisible(id, revision, visible)
        activity.runOnUiThread {
            if (alive())
                view.evaluateJavascript(
                    "window.__pd_plugin_visible=$visible;window.__pd_plugin_lifecycle?.($visible)",
                    null,
                )
        }
    }

    private fun navigate(target: String) {
        if (alive()) activity.runOnUiThread { if (alive()) onNavigate(target) }
    }

    private fun gate() {
        check(!closed) { "插件页面已关闭" }
        store.requireRevision(id, revision)
    }

    private fun alive() =
        try {
            gate()
            true
        } catch (_: Exception) {
            false
        }

    private fun forbidden(status: Int = 403, reason: String = "Forbidden") =
        surface.forbidden(status, reason)

    private fun consumeRecentGesture(): Boolean {
        val recent = gestureAt > 0 && SystemClock.elapsedRealtime() - gestureAt <= GESTURE_MS
        gestureAt = 0
        return recent
    }

    private companion object {
        const val GESTURE_MS = 750L
        const val BACK_SCRIPT =
            "(function(){try{var f=window.__pd_back;return f?f():false}catch(e){return false}})()"
    }
}
