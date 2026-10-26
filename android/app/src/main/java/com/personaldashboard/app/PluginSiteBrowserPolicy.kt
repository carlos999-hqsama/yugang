package com.personaldashboard.app

import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONObject

/**
 * Pure validation and naming rules for the host-owned site browser.
 *
 * The browser is not a plugin surface. Plugin code can only submit `{url}`; every other field,
 * especially any model- or JavaScript-supplied gesture claim, is rejected here.
 */
object PluginSiteBrowserPolicy {
    const val MAX_URL_CHARS = 4096
    const val MAX_FILENAME_CHARS = 120
    const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024
    const val PREVIEW_REFUSAL = "预览不允许打开站内浏览器"
    const val CORE_PROFILE_NAME = "ygxcorebrowser1"
    const val DOCUMENT_PROFILE_NAME = "ygxdocumentbrowser1"
    const val DOCUMENT_HOST = "dshfile.pd.local"
    const val DOCUMENT_HANDOFF_URL = "openCoreUrl"
    private const val PROFILE_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val DOWNLOAD_LIMIT_MESSAGE = "下载超过 256 MiB 上限"

    class DownloadSizeLimitException : IOException(DOWNLOAD_LIMIT_MESSAGE)

    /** Only these schemes may ever become a WebView navigation or download target. */
    fun url(raw: String?): String {
        val value = raw?.trim().orEmpty()
        require(value.isNotEmpty()) { "缺少网站地址" }
        require(value.length <= MAX_URL_CHARS) { "网站地址过长" }
        require(value.none { it.isISOControl() || it.isWhitespace() }) { "网站地址包含非法字符" }

        val uri =
            try {
                URI(value)
            } catch (_: Exception) {
                throw IllegalArgumentException("网站地址无效")
            }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "站内浏览器仅支持 HTTP(S) 链接" }
        require(uri.rawUserInfo == null && uri.userInfo == null) { "网站地址不能包含账号信息" }
        require(!uri.host.isNullOrBlank()) { "网站地址缺少主机名" }
        require(uri.port in 1..65535 || uri.port == -1) { "网站地址端口无效" }

        return try {
            uri.toURL().toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("网站地址无效")
        }
    }

    fun redirectUrl(base: String, location: String?): String {
        require(!location.isNullOrBlank()) { "下载跳转无效" }
        return url(URI(url(base)).resolve(location).toString())
    }

    fun previewRefusal(): String = PREVIEW_REFUSAL

    /**
     * Parse the one and only plugin request field. Unknown keys are errors rather than ignored:
     * a forged `userGesture` must break the API, not be reinterpreted by the host.
     */
    fun openRequest(args: JSONObject): String {
        require(args.length() == 1 && args.has("url")) { "站内浏览器请求只能是 {url}" }
        return url(args.getString("url"))
    }

    /** Core accepts an optional display title, but never gesture claims or document URLs. */
    fun openCoreRequest(args: JSONObject): String {
        require(args.has("url") && args.keys().asSequence().all { it == "url" || it == "title" }) {
            "Core 浏览器只接受 url 和 title"
        }
        return coreUrl(args.getString("url"))
    }

    /** The reserved document origin is never an ordinary Core browser target. */
    fun coreUrl(raw: String?): String {
        val value = url(raw)
        require(!isDocumentOrigin(value)) { "受控文档地址不能作为普通网页打开" }
        return value
    }

    /** Initial documents must name a path below the controlled local origin. */
    fun documentInitialUrl(raw: String?): String {
        val value = documentResourceUrl(raw)
        require(URI(value).rawPath.orEmpty().length > 1) { "文档地址必须包含文件路径" }
        return value
    }

    /** Controlled files always pass through the native resource provider. */
    fun documentResourceUrl(raw: String?): String {
        val value = url(raw)
        require(isDocumentOrigin(value)) { "文档资源仅限受控同源地址" }
        return value
    }

    /** HTML reports may load ordinary CDN resources; these never enter the file provider. */
    fun documentNetworkResource(raw: String?, isMainFrame: Boolean): Boolean =
        !isMainFrame && runCatching { !isDocumentOrigin(url(raw)) }.getOrDefault(false)

    /**
     * Only a real main-frame GET gesture can leave a document. The host returns this target to its
     * native caller instead of loading an external page with document resource access.
     */
    fun documentHandoffUrl(
        raw: String?,
        isMainFrame: Boolean,
        hasGesture: Boolean,
        method: String,
    ): String? {
        val value = url(raw)
        if (isDocumentOrigin(value) || !isMainFrame || !hasGesture || !method.equals("GET", true)) {
            return null
        }
        return value
    }

    /**
     * Profile names are injective, revision-independent, alphanumeric, and at most 53 characters.
     * Base-37 input plus the original length preserves distinction between leading hyphens and 0s.
     */
    fun profileName(pluginId: String): String {
        PluginContract.id(pluginId)
        var number = BigInteger.ZERO
        val base = BigInteger.valueOf(37)
        for (char in pluginId) {
            val digit =
                when (char) {
                    in '0'..'9' -> char - '0'
                    in 'a'..'z' -> char - 'a' + 10
                    '-' -> 36
                    else -> throw IllegalArgumentException("插件 ID 无效")
                }
            number = number.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }

        var encoded = ""
        var remaining = number
        val outputBase = BigInteger.valueOf(36)
        if (remaining == BigInteger.ZERO) encoded = "0"
        while (remaining > BigInteger.ZERO) {
            val division = remaining.divideAndRemainder(outputBase)
            encoded = PROFILE_ALPHABET[division[1].toInt()] + encoded
            remaining = division[0]
        }
        return "yg" + pluginId.length.toString().padStart(2, '0') + encoded
    }

    /** The exact close event returned to the calling plugin surface; no cookie data is included. */
    fun closeReceipt(
        opened: Boolean,
        finalUrl: String,
    ): JSONObject =
        JSONObject()
            .put("opened", opened)
            .put("closed", true)
            .put("finalUrl", finalUrl)

    /** A document close can explicitly ask its native caller to continue in a Core browser. */
    fun documentHandoffReceipt(
        opened: Boolean,
        finalUrl: String,
        externalUrl: String,
    ): JSONObject =
        closeReceipt(opened, finalUrl).put(DOCUMENT_HANDOFF_URL, coreUrl(externalUrl))

    private fun isDocumentOrigin(value: String): Boolean {
        val uri = URI(value)
        return uri.scheme.equals("https", true) &&
            uri.host.equals(DOCUMENT_HOST, true) &&
            uri.port == -1
    }

    /**
     * Derive a bounded MediaStore display name. Path components, control characters, and header
     * line breaks never reach the fixed Download/鱼缸 destination.
     */
    fun downloadName(
        contentDisposition: String?,
        url: String,
    ): String {
        val fromHeader = contentDisposition
            ?.takeIf { it.length <= 2048 }
            ?.let { disposition ->
                headerFilename(disposition) ?: encodedHeaderFilename(disposition)
            }
            ?.let(::cleanFilename)

        val fromUrl =
            cleanFilename(
                runCatching {
                    val path = URI(url).rawPath.orEmpty()
                    URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
                }.getOrDefault(""),
            )

        return fromHeader?.takeIf { it.isNotEmpty() }
            ?: fromUrl.takeIf { it.isNotEmpty() }
            ?: "download"
    }

    fun extension(mime: String?): String =
        when (mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "text/html" -> "html"
            "text/css" -> "css"
            "text/javascript", "application/javascript" -> "js"
            "application/json" -> "json"
            "text/xml", "application/xml" -> "xml"
            "text/plain", "text/csv" -> "txt"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            "audio/mpeg" -> "mp3"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "application/vnd.android.package-archive" -> "apk"
            else -> ""
        }

    /** Parse the response's advertised size and reject an over-limit transfer before MediaStore use. */
    fun contentLength(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val advertised =
            header.trim().toLongOrNull() ?: throw IllegalArgumentException("下载大小无效")
        downloadSize(advertised, 0)
        return advertised
    }

    /** Bound both the advertised and actual transfer size; this policy is used by the copy loop. */
    fun downloadSize(
        advertisedBytes: Long?,
        actualBytes: Long,
    ): Long {
        require(actualBytes >= 0) { "下载大小无效" }
        advertisedBytes?.let {
            require(it >= 0) { "下载大小无效" }
            if (it > MAX_DOWNLOAD_BYTES) throw DownloadSizeLimitException()
        }
        if (actualBytes > MAX_DOWNLOAD_BYTES) throw DownloadSizeLimitException()
        return actualBytes
    }

    private fun headerFilename(disposition: String): String? {
        val match = Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*(\"([^\"]*)\"|[^;]*)").find(disposition)
        val value = match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
            ?: match?.groupValues?.get(1)?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    private fun encodedHeaderFilename(disposition: String): String? {
        val match =
            Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)")
                .find(disposition)
                ?: return null
        val parts = match.groupValues[1].trim().split("'", "'")
        if (parts.size != 3) return null
        return runCatching { URLDecoder.decode(parts[2], "UTF-8") }.getOrNull()
    }

    private fun cleanFilename(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
        val safe =
            buildString {
                for (char in name) {
                    if (char.isISOControl() || char in setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')) {
                        continue
                    }
                    append(char)
                }
            }
                .trim()
                .trim('.')
        if (safe.isEmpty()) return ""

        val bounded = StringBuilder()
        var count = 0
        for (char in safe) {
            if (char.isHighSurrogate() && count == MAX_FILENAME_CHARS - 1) break
            if (count >= MAX_FILENAME_CHARS) break
            bounded.append(char)
            count++
        }
        return bounded.toString()
    }
}
