package com.personaldashboard.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * 原生覆盖层：
 * 1) 内置浏览器：每个标签一个 WebView，按 JS 上报的矩形（CSS px）叠在主界面上； 用户打开的文件标签可访问
 *    https://dshfile.pd.local/<绝对路径>，原生带 Cookie 读取文件； 保留报告相对图片，限制跨站内嵌页面；普通网页标签不能借用这个文件通道。
 * 2) 内嵌 dsh web（备用模式）：一个 WebView 直接打开 dsh 自带网页，Cookie 由 CookieManager 注入。
 */
class Panes(
    private val act: MainActivity,
    private val root: FrameLayout,
    private val net: Net,
    private val cfg: Config,
) {
    private var closed = false
    private val tabs = LinkedHashMap<String, WebView>()
    private val urls = HashMap<String, String>()

    private class FileAccess(initialUrl: String) {
        @Volatile var granted = isFileUrl(initialUrl)
        @Volatile var documentIsFile = granted
        @Volatile var pendingFilePath: String? = if (granted) filePath(initialUrl) else null
    }

    private val fileAccess = HashMap<String, FileAccess>()
    private var dshWeb: WebView? = null
    private val density
        get() = act.resources.displayMetrics.density

    private fun place(v: View, rect: JSONObject?) {
        val r = rect ?: return
        val lp =
            FrameLayout.LayoutParams(
                (r.optDouble("w") * density).toInt().coerceAtLeast(1),
                (r.optDouble("h") * density).toInt().coerceAtLeast(1),
            )
        lp.leftMargin = act.paneOffsetX + (r.optDouble("x") * density).toInt()
        lp.topMargin = (r.optDouble("y") * density).toInt()
        v.layoutParams = lp
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWeb(): WebView =
        WebView(act).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

    fun browser(st: JSONObject) {
        if (closed) return
        val visible = st.optBoolean("visible") && act.coreVisible
        val active = st.optString("active")
        val list = st.optJSONArray("tabs")
        val want = LinkedHashMap<String, String>()
        if (list != null)
            for (i in 0 until list.length()) list.optJSONObject(i)?.let {
                want[it.optString("id")] = it.optString("url")
            }
        // 关闭已移除的标签
        for (id in tabs.keys.toList()) if (id !in want) {
            tabs.remove(id)?.let {
                root.removeView(it)
                it.destroy()
            }
            urls.remove(id)
            fileAccess.remove(id)
        }
        for ((id, url) in want) {
            val access = fileAccess.getOrPut(id) { FileAccess(url) }
            // Only the trusted address bar grants an existing tab access to local reports.
            // Ordinary URL/progress feedback must never grant that access.
            if (id == active && st.optString("cmd") == "navigate") {
                access.documentIsFile = isFileUrl(url)
                if (access.documentIsFile) access.granted = true
                access.pendingFilePath = if (access.documentIsFile) filePath(url) else null
            }
            val w =
                tabs.getOrPut(id) {
                    newWeb().also {
                        configureTab(id, it, access)
                        root.addView(it)
                    }
                }
            if (url.isNotEmpty() && urls[id] != url) {
                urls[id] = url
                if (w.url != url) w.loadUrl(url)
            }
            place(w, st.optJSONObject("rect"))
            w.visibility = if (visible && id == active) View.VISIBLE else View.GONE
        }
        tabs[active]?.let { w ->
            when (st.optString("cmd")) {
                "back",
                "fwd" -> {
                    val step = if (st.optString("cmd") == "back") -1 else 1
                    val history = w.copyBackForwardList()
                    val target = history.getItemAtIndex(history.currentIndex + step)
                    if (target != null) {
                        fileAccess[active]?.documentIsFile = isFileUrl(target.url)
                        fileAccess[active]?.pendingFilePath =
                            if (isFileUrl(target.url)) filePath(target.url) else null
                        w.goBackOrForward(step)
                    }
                }
                "reload" -> w.reload()
            }
        }
    }

    private fun report(id: String, w: WebView, loading: Boolean, progress: Int) {
        val u = w.url ?: urls[id] ?: ""
        act.emit(
            "browser",
            JSONObject()
                .put("tabId", id)
                .put("url", u)
                .put("title", w.title ?: "")
                .put("canBack", w.canGoBack())
                .put("canFwd", w.canGoForward())
                .put("loading", loading)
                .put("progress", progress),
        )
    }

    private fun configureTab(id: String, w: WebView, access: FileAccess) {
        w.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    req: WebResourceRequest,
                ): WebResourceResponse? {
                    val u = req.url
                    if (u.host != FILE_HOST) return null
                    // A previous remote document may still run while a native file navigation
                    // loads.
                    // Its subresources must not borrow the destination's pending authorization.
                    val referrer =
                        req.requestHeaders.entries
                            .firstOrNull { it.key.equals("Referer", true) }
                            ?.value
                    val fileInitiator =
                        (referrer != null && isFileUrl(referrer)) ||
                            (req.isForMainFrame && access.pendingFilePath == filePath(u.toString()))
                    if (
                        !access.granted ||
                            !access.documentIsFile ||
                            !fileInitiator ||
                            !isFileUrl(u.toString()) ||
                            req.method != "GET"
                    )
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            403,
                            "Forbidden",
                            emptyMap(),
                            ByteArray(0).inputStream(),
                        )
                    return fetchDshFile(Uri.decode(u.encodedPath ?: "/")).also { response ->
                        response.responseHeaders =
                            (response.responseHeaders ?: emptyMap()) +
                                mapOf(
                                    "Cross-Origin-Resource-Policy" to "same-origin",
                                    "Cache-Control" to "no-store",
                                    "Referrer-Policy" to "same-origin",
                                    "Content-Security-Policy" to
                                        "frame-ancestors 'self'; frame-src 'self'; child-src 'self'",
                                )
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    req: WebResourceRequest,
                ): Boolean {
                    val s = req.url.scheme ?: return true
                    if (s == "http" || s == "https") {
                        val file = isFileUrl(req.url.toString())
                        if (file && (!access.granted || !access.documentIsFile)) return true
                        if (file && req.isForMainFrame) {
                            val referrer =
                                req.requestHeaders.entries
                                    .firstOrNull { it.key.equals("Referer", true) }
                                    ?.value
                            if (
                                access.pendingFilePath != filePath(req.url.toString()) &&
                                    (referrer == null || !isFileUrl(referrer))
                            )
                                return true
                        }
                        if (req.isForMainFrame) {
                            access.documentIsFile = file
                            if (!file) access.pendingFilePath = null
                            urls[id] = req.url.toString()
                        }
                        return false
                    }
                    if (req.hasGesture()) act.openExternal(req.url.toString())
                    return true // External apps require a real user activation.
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    access.documentIsFile = access.documentIsFile && isFileUrl(url)
                    if (!isFileUrl(url)) access.pendingFilePath = null
                    urls[id] = url
                    report(id, view, true, 10)
                }

                override fun onPageCommitVisible(view: WebView, url: String) {
                    if (isFileUrl(url) && access.pendingFilePath == filePath(url))
                        access.pendingFilePath = null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    report(id, view, false, 100)
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    // 网页崩溃只关掉这个标签的 WebView，不能拖垮整个 App；下次同步状态时按原地址重建
                    val url = urls.remove(id) ?: view.url ?: ""
                    tabs.remove(id)
                    fileAccess.remove(id)
                    root.removeView(view)
                    view.destroy()
                    act.emit(
                        "browser",
                        JSONObject()
                            .put("tabId", id)
                            .put("url", url)
                            .put("title", "页面已崩溃，点刷新重新加载")
                            .put("canBack", false)
                            .put("canFwd", false)
                            .put("loading", false)
                            .put("progress", 100)
                            .put("crashed", true),
                    )
                    return true
                }
            }
        w.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, p: Int) {
                    report(id, view, p < 100, p)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    report(id, view, view.progress < 100, view.progress)
                }
            }
    }

    /** dsh 产物文件代理（只读 GET；Cookie 留在原生侧） */
    fun fetchDshFile(abs: String): WebResourceResponse {
        return try {
            val r = net.dshRaw("/api/file?path=" + Uri.encode(abs))
            val ct = r.header("Content-Type") ?: "application/octet-stream"
            val mime = ct.substringBefore(';').trim()
            val charset = Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1)
            val headers = HashMap<String, String>()
            r.headers
                .names()
                .filter { n ->
                    n.lowercase() !in
                        setOf(
                            "content-security-policy",
                            "content-disposition",
                            "x-frame-options",
                            "set-cookie",
                            "content-type",
                            "content-length",
                            "content-encoding",
                            "transfer-encoding",
                            "connection",
                        )
                }
                .forEach { n -> headers[n] = r.headers.values(n).joinToString(", ") }
            val reason = r.message.ifEmpty { if (r.isSuccessful) "OK" else "Error" }
            WebResourceResponse(mime, charset, r.code, reason, headers, r.body!!.byteStream())
        } catch (e: Exception) {
            WebResourceResponse(
                "text/plain",
                "utf-8",
                502,
                "Bad Gateway",
                emptyMap(),
                ("无法从 dsh 读取文件：" + e.message).byteInputStream(),
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun dshWeb(st: JSONObject) {
        if (closed) return
        val visible = st.optBoolean("visible") && act.coreVisible
        if (visible && dshWeb == null) {
            val w = newWeb()
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cfg.dshCookie
                .takeIf { it.isNotEmpty() }
                ?.let { cm.setCookie(cfg.dshUrl, "$it; Path=/; HttpOnly; SameSite=Strict") }
            w.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        req: WebResourceRequest,
                    ): Boolean {
                        val u = req.url.toString()
                        if (u.startsWith(cfg.dshUrl)) return false
                        if (req.hasGesture()) act.openExternal(u)
                        return true // 内嵌 dsh web 只允许停留在 dsh 本身
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        root.removeView(view)
                        view.destroy()
                        dshWeb = null
                        act.emit("toast", JSONObject().put("text", "内嵌 dsh web 已崩溃，请重新打开"))
                        return true
                    }
                }
            root.addView(w)
            dshWeb = w
            w.loadUrl(cfg.dshUrl + "/")
        }
        dshWeb?.let { w ->
            place(w, st.optJSONObject("rect"))
            w.visibility = if (visible) View.VISIBLE else View.GONE
            if (st.optString("cmd") == "reload") w.reload()
        }
    }

    /** 返回键：先让可见的内嵌页面后退 */
    fun back(): Boolean {
        dshWeb
            ?.takeIf { it.visibility == View.VISIBLE && it.canGoBack() }
            ?.let {
                it.goBack()
                return true
            }
        return false
    }

    fun hideAll() {
        tabs.values.forEach { it.visibility = View.GONE }
        dshWeb?.visibility = View.GONE
    }

    fun resetDshWeb() {
        dshWeb?.let {
            root.removeView(it)
            it.destroy()
        }
        dshWeb = null
    }

    fun close() {
        if (closed) return
        closed = true
        tabs.values.forEach {
            it.stopLoading()
            root.removeView(it)
            it.destroy()
        }
        tabs.clear()
        urls.clear()
        fileAccess.clear()
        dshWeb?.let {
            it.stopLoading()
            root.removeView(it)
            it.destroy()
        }
        dshWeb = null
    }

    companion object {
        const val FILE_HOST = "dshfile.pd.local"

        private fun isFileUrl(url: String): Boolean =
            Uri.parse(url).let {
                it.scheme == "https" && it.host == FILE_HOST && it.port in setOf(-1, 443)
            }

        private fun filePath(url: String): String = Uri.decode(Uri.parse(url).encodedPath ?: "/")
    }
}
