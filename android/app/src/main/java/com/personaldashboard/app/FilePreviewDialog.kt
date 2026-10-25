package com.personaldashboard.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import java.util.UUID

/** In-app text viewer with no host bridge, credentials, filesystem or remote resource access. */
class FilePreviewDialog(private val activity: MainActivity) {
    private val dialogs = mutableSetOf<Dialog>() // UI thread only.
    @Volatile private var closed = false

    fun close() {
        closed = true
        activity.runOnUiThread { dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(content: FileTextContent) {
        check(!closed) { "文件预览已关闭" }
        activity.runOnUiThread {
            if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
            val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val close = Button(activity).apply {
                text = "关闭 ${content.name}"
                setOnClickListener { dialog.dismiss() }
            }
            val url = "https://file-preview.pd.local/${UUID.randomUUID()}"
            val html = if (content.mime == "text/html") content.text else
                "<!doctype html><html><meta charset=utf-8><style>body{margin:24px;color:#222;background:#fff}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:16px/1.7 monospace}</style><pre>" +
                    content.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre></html>"
            var rendererGone = false
            val page = WebView(activity).apply {
                settings.javaScriptEnabled = content.mime == "text/html"
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        if (request.method == "GET" && request.isForMainFrame && request.url.toString() == url)
                            return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf(
                                "Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'",
                                "Cache-Control" to "no-store"), html.byteInputStream())
                        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArray(0).inputStream())
                    }
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean { rendererGone = true; dialog.dismiss(); return true }
                }
            }
            layout.addView(close, LinearLayout.LayoutParams(-1, -2))
            layout.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
            dialog.setContentView(layout)
            dialog.setOnDismissListener { dialogs.remove(dialog); if (!rendererGone) page.stopLoading(); page.destroy() }
            dialogs.add(dialog)
            dialog.show()
            page.loadUrl(url)
        }
    }
}
