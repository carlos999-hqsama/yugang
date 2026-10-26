package com.personaldashboard.app

import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject

/** Host-owned locations for chat content. Display names never become arbitrary disk paths. */
internal object ChatContentPolicy {
    const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024
    const val FILE_HOST = "dshfile.pd.local"
    // Scripts and CDN assets remain usable, but documents cannot read the provider's origin.
    const val DOCUMENT_SANDBOX = "sandbox allow-scripts"
    fun responseHeaders(mime: String, upstream: Map<String, String>): Map<String, String> {
        val kept = upstream.filterKeys { it.lowercase(Locale.ROOT) in setOf("accept-ranges", "content-range", "content-length", "etag", "last-modified", "content-security-policy") }.toMutableMap()
        val cspKey = kept.keys.firstOrNull { it.equals("Content-Security-Policy", true) }
        val csp = cspKey?.let { kept.remove(it) }?.trim()
        val active = mime.substringBefore(';').trim().lowercase(Locale.ROOT) in setOf("text/html", "image/svg+xml")
        if (active) {
            // The API's download-only policy becomes the isolated interactive document policy.
            // Any other upstream policy remains independently enforced, never relaxed.
            // CSP header policy lists are comma-delimited (W3C CSP section 2.2), not one directive list.
            kept["Content-Security-Policy"] = if (csp.isNullOrEmpty() || csp == "sandbox; default-src 'none'") DOCUMENT_SANDBOX else "$csp, $DOCUMENT_SANDBOX"
        } else if (!csp.isNullOrEmpty()) kept["Content-Security-Policy"] = csp
        kept["Cache-Control"] = "no-store"
        kept["X-Content-Type-Options"] = "nosniff"
        return kept
    }
    data class Target(val url: String, val upstream: String, val name: String, val mime: String, val workspace: String, val attachment: Boolean)

    fun name(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.take(180).ifBlank { "文件" }
    fun encode(raw: String): String = URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
    fun encodedPath(path: String): String = path.split('/').joinToString("/") { encode(it) }
    fun path(raw: String, workspace: String): String {
        require(raw.startsWith("$workspace/") && raw.none { it.isISOControl() || it == '\\' }) { "只能打开当前 Bot 工作区的文件" }
        require(raw.split('/').none { it == "." || it == ".." }) { "文件路径无效" }
        return raw
    }
    fun target(args: JSONObject, workspace: String): Target {
        val attachmentId = args.optString("attachmentId")
        if (attachmentId.isNotEmpty()) {
            require(Regex("sha256:[a-f0-9]{64}").matches(attachmentId)) { "附件编号无效" }
            val session = args.getString("sessionId")
            require(session.isNotBlank() && session.length <= 160 && session.none { it.isISOControl() }) { "会话编号无效" }
            val originalName = args.getString("name")
            require(originalName.isNotBlank() && originalName.length <= 255 && originalName.none { it.isISOControl() || it == '/' || it == '\\' }) { "附件名称无效" }
            val proxy = "/__attachments/${encode(session)}/${attachmentId.removePrefix("sha256:")}/${encode(originalName)}"
            return Target("https://$FILE_HOST$proxy", "/api/yugang/session-file?sessionId=${encode(session)}&attachmentId=${encode(attachmentId)}&name=${encode(originalName)}", originalName, mime(originalName), workspace, true)
        }
        val file = path(args.getString("path"), workspace)
        return Target("https://$FILE_HOST${encodedPath(file)}", "/api/yugang/workspace-file?path=${encode(file)}", name(file), mime(file), workspace, false)
    }
    fun upstream(target: Target, url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != FILE_HOST || uri.port !in setOf(-1, 443) || uri.rawQuery != null) return null
        if (target.attachment) return target.upstream.takeIf { url == target.url }
        val requested = runCatching { path(uri.path, target.workspace) }.getOrNull() ?: return null
        return "/api/yugang/workspace-file?path=${encode(requested)}"
    }
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html"
        "md", "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
    fun inline(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") || PluginMime.isText(mime)

    fun byteRange(header: String, length: Long): LongRange {
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: error("文件范围无效")
        require(length > 0 && match.groupValues.drop(1).any { it.isNotEmpty() }) { "文件范围无效" }
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        val start = if (first.isEmpty()) (length - last.toLong().also { require(it > 0) }).coerceAtLeast(0) else first.toLong()
        val end = if (first.isEmpty() || last.isEmpty()) length - 1 else minOf(last.toLong(), length - 1)
        require(start in 0 until length && end >= start) { "文件范围无效" }
        return start..end
    }
}
