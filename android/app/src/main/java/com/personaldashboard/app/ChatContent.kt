package com.personaldashboard.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import org.json.JSONObject

/** Chat files and ordinary pages share the native browser surface, with separate source grants. */
internal class ChatContent(private val activity: MainActivity, private val net: Net) {
    fun open(owner: String, args: JSONObject, cancellation: PluginCancellation, reply: (JSONObject) -> Unit) {
        if (args.has("url")) {
            PluginSiteBrowserHost.openCore(activity, owner, JSONObject().put("url", args.getString("url")).put("title", args.optString("title", "网页")), reply)
            return
        }
        if (args.has("dataUrl")) {
            require(args.optBoolean("download")) { "图片内容只支持保存" }
            val raw = args.getString("dataUrl")
            require(raw.length <= 12 * 1024 * 1024) { "图片超过保存上限" }
            val match = Regex("^data:(image/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/=]+)$").matchEntire(raw) ?: error("图片数据无效")
            val bytes = Base64.getDecoder().decode(match.groupValues[2])
            require(bytes.size <= 8 * 1024 * 1024) { "图片超过保存上限" }
            reply(save(bytes.inputStream(), bytes.size.toLong(), ChatContentPolicy.name(args.optString("name", "图片.png")), match.groupValues[1], cancellation))
            return
        }
        val botId = args.getString("botId")
        val workspace = activity.requireBotSession(botId, args.optString("sessionId").takeIf { it.isNotEmpty() })
        if (args.has("handle")) {
            val staged = activity.botAttachments.preview(args.getString("handle"))
            val file = staged.first
            val metadata = staged.second
            val url = "https://${ChatContentPolicy.FILE_HOST}/__draft/${ChatContentPolicy.encode(args.getString("handle"))}/${ChatContentPolicy.encode(metadata.getString("name"))}"
            val mime = metadata.getString("mediaType")
            if (args.optBoolean("download") || !ChatContentPolicy.inline(mime)) {
                val saved=save(file.inputStream(),file.length(),metadata.getString("name"),mime,cancellation)
                if (!args.optBoolean("download")) activity.runOnUiThread { reply(saved.put("viewer",DownloadedFiles.open(activity,JSONObject().put("uri",saved.getString("uri"))))) }
                else reply(saved)
                return
            }
            openSurface(owner, url, metadata.getString("name"), mime, cancellation, reply) { request ->
                if (request.url.toString() == url) {
                    val rangeHeader = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
                    if (rangeHeader == null) response(file.inputStream(), mime, file.length(), 200, mapOf("Accept-Ranges" to "bytes"))
                    else try {
                        val range = ChatContentPolicy.byteRange(rangeHeader, file.length())
                        val input = java.io.RandomAccessFile(file, "r").apply { seek(range.first) }
                        var left = range.last - range.first + 1
                        val stream = object : InputStream() {
                            override fun read(): Int { if (left == 0L) return -1;val value=input.read();if(value >= 0)left--;return value }
                            override fun read(bytes: ByteArray, offset: Int, size: Int): Int {
                                if (left == 0L) return -1
                                val count=input.read(bytes,offset,minOf(size.toLong(),left).toInt());if(count>0)left-=count;return count
                            }
                            override fun close() { input.close() }
                        }
                        response(stream,mime,range.last-range.first+1,206,mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes ${range.first}-${range.last}/${file.length()}"))
                    } catch (_: Exception) { WebResourceResponse("text/plain","utf-8",416,"Range Not Satisfiable",mapOf("Content-Range" to "bytes */${file.length()}"),ByteArray(0).inputStream()) }
                } else forbidden()
            }
            return
        }
        val target = ChatContentPolicy.target(args, workspace)
        if (args.optBoolean("download") || !ChatContentPolicy.inline(target.mime)) {
            net.dshRaw(target.upstream, cancellation = cancellation).use { response ->
                check(response.isSuccessful) { "文件读取失败，请重试" }
                val body = response.body ?: error("文件没有内容")
                val saved = save(body.byteStream(), body.contentLength(), target.name, target.mime, cancellation)
                if (!args.optBoolean("download")) activity.runOnUiThread {
                    reply(saved.put("viewer", DownloadedFiles.open(activity, JSONObject().put("uri", saved.getString("uri")))))
                } else reply(saved)
            }
            return
        }
        openSurface(owner, target.url, target.name, target.mime, cancellation, reply) { request ->
            val main = request.isForMainFrame
            val referrer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            val source = ChatContentPolicy.upstream(target, request.url.toString())
            if (request.method != "GET" || source == null || (!main && referrer?.startsWith("https://${ChatContentPolicy.FILE_HOST}/") != true)) forbidden()
            else fetch(source, request.requestHeaders, cancellation, main)
        }
    }

    private fun openSurface(owner: String, sourceUrl: String, title: String, mime: String, cancellation: PluginCancellation, reply: (JSONObject) -> Unit, resource: (WebResourceRequest) -> WebResourceResponse?) {
        val player = mime.startsWith("video/") || mime.startsWith("audio/")
        val viewerUrl = if (player) "$sourceUrl?__view=media" else sourceUrl
        PluginSiteBrowserHost.openDocument(activity, owner, viewerUrl, title, { request ->
            if (player && request.isForMainFrame && request.url.toString() == viewerUrl) {
                val tag = if (mime.startsWith("video/")) "video" else "audio"
                val safe = sourceUrl.replace("&", "&amp;").replace("\"", "&quot;")
                val html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>body{margin:0;background:#111;color:#eee;display:grid;place-items:center;min-height:100vh;font:14px sans-serif}video,audio{width:100%;max-height:85vh}p{padding:16px}a{color:#eee}</style><$tag controls playsinline preload=metadata src=\"$safe\"></$tag><p><a href=\"$safe\" download>保存文件</a> · 无法播放时可保存后用其他应用打开</p>"
                WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Content-Security-Policy" to "default-src 'none'; media-src 'self'; style-src 'unsafe-inline'", "Cache-Control" to "no-store"), html.byteInputStream())
            } else { cancellation.check();resource(request) }
        }, { receipt ->
            val external = receipt.optString("openCoreUrl")
            if (external.isNotEmpty()) PluginSiteBrowserHost.openCore(activity, owner, JSONObject().put("url", external), reply)
            else reply(receipt)
        })
    }

    fun fetch(upstream: String, headers: Map<String, String>, cancellation: PluginCancellation, renderText: Boolean = false): WebResourceResponse { return try {
        val response = net.dshRaw(upstream, headers, cancellation)
        val body = response.body ?: throw IOException("文件没有内容")
        val mime = response.header("Content-Type").orEmpty().substringBefore(';').ifBlank { "application/octet-stream" }
        if (renderText && PluginMime.isText(mime) && mime != "text/html") {
            response.use {
                val text = FileTextContent.read(body.byteStream(), body.contentLength(), mime, "文件", cancellation::check).text
                val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><style>body{margin:20px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:16px/1.7 monospace}</style><pre>$escaped</pre>"
                return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'"), html.byteInputStream())
            }
        }
        val upstreamHeaders = response.headers.names().associateWith {
            if (it.equals("Content-Security-Policy", true)) response.headers.values(it).joinToString(", ")
            else response.header(it).orEmpty()
        }
        val kept = ChatContentPolicy.responseHeaders(mime, upstreamHeaders)
        WebResourceResponse(mime, "utf-8".takeIf { PluginMime.isText(mime) }, response.code, response.message.ifBlank { "Response" }, kept, object : FilterInputStream(body.byteStream()) { override fun close() { try { super.close() } finally { response.close() } } })
    } catch (_: Exception) { forbidden(502, "Bad Gateway") } }

    private fun save(input: InputStream, expected: Long, name: String, mime: String, cancellation: PluginCancellation): JSONObject {
        require(expected <= ChatContentPolicy.MAX_DOWNLOAD_BYTES) { "文件超过 256 MiB" }
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/鱼缸")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建下载文件")
        try {
            var count = 0L
            resolver.openOutputStream(uri, "w")!!.use { output ->
                input.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        cancellation.check()
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= ChatContentPolicy.MAX_DOWNLOAD_BYTES) { "文件超过 256 MiB" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            cancellation.check()
            check(expected < 0 || count == expected) { "文件未完整下载，请重试" }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "无法完成下载" }
            return DownloadedFiles.receipt(activity, uri, name, mime, count)
        } catch (error: Exception) { resolver.delete(uri, null, null);throw error }
    }
    private fun response(input: InputStream, mime: String, length: Long, status: Int, headers: Map<String,String>) = WebResourceResponse(mime, null, status, "OK", ChatContentPolicy.responseHeaders(mime, headers + mapOf("Content-Length" to length.toString())), input)
    private fun forbidden(status: Int = 403, reason: String = "Forbidden") = WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArray(0).inputStream())
}
