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

/** The authenticated DSH fallback and image proxy. User browsing uses PluginSiteBrowserHost. */
class Panes(
    private val act: MainActivity,
    private val root: FrameLayout,
    private val net: Net,
    private val cfg: Config,
) {
    private var closed = false
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
                        if (req.hasGesture()) PluginSiteBrowserHost.openCore(act, "dsh-web", JSONObject().put("url", u), {})
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
        PluginSiteBrowserHost.closeForNavigation(act)
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
