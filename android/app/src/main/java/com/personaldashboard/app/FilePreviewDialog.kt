package com.personaldashboard.app

import android.webkit.WebResourceResponse
import android.widget.Toast
import java.util.UUID

/** Bounded in-memory text uses the same chrome as other documents, without a host bridge. */
class FilePreviewDialog(private val activity: MainActivity, private val pluginIdentity: Pair<String,String>? = null) {
    private val owner = "text-preview:${UUID.randomUUID()}"
    @Volatile private var closed = false

    fun close() { closed = true;PluginSiteBrowserHost.closeOwner(owner) }

    fun show(content: FileTextContent) {
        check(!closed) { "文件预览已关闭" }
        val url = "https://${ChatContentPolicy.FILE_HOST}/__text_preview/${UUID.randomUUID()}/index.html"
        val html = if (content.mime == "text/html") content.text else
            "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><style>body{margin:20px;color:#222;background:#fff}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:16px/1.7 monospace}</style><pre>" +
                content.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>"
        PluginSiteBrowserHost.openDocument(activity, owner, url, content.name, { request ->
            if (!closed && request.method == "GET" && request.isForMainFrame && request.url.toString() == url)
                WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf(
                    "Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'",
                    "Cache-Control" to "no-store"), html.byteInputStream())
            else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArray(0).inputStream())
        }, { result ->
            val external = result.optString("openCoreUrl")
            if (external.isNotEmpty() && !closed) {
                val plugin = pluginIdentity
                if (plugin == null) PluginSiteBrowserHost.openCore(activity, owner, org.json.JSONObject().put("url", external), {})
                else PluginSiteBrowserHost.openUserLink(activity, owner, plugin.first, plugin.second, external)
            }
            if (!result.optBoolean("opened") && !closed) Toast.makeText(activity, result.optString("reason", "文件未打开"), Toast.LENGTH_LONG).show()
        })
    }
}
