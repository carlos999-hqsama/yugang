package com.personaldashboard.app

import java.net.URI
import java.net.URLDecoder
import java.math.BigInteger
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
    const val PREVIEW_REFUSAL = "预览不允许打开站内浏览器"
    private const val PROFILE_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

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
