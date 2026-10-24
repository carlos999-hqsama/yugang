package com.personaldashboard.app

import org.json.JSONObject

/** Pure request, response and media-format rules for the host-owned TTS service. */
internal object PluginTtsPolicy {
    const val PROVIDER_SYSTEM = "android-system"
    const val PROVIDER_FALLBACK = "baidu-fanyi-gettts"
    // Keep time for player startup and the native action's final result round trip.
    private const val PLAYBACK_RESULT_RESERVE_MS = 2_000L

    fun fitsActionPlayback(durationMs: Long, remainingMs: Long): Boolean =
        durationMs > 0 &&
            remainingMs > PLAYBACK_RESULT_RESERVE_MS &&
            durationMs <= remainingMs - PLAYBACK_RESULT_RESERVE_MS

    const val MAX_TEXT_CHARS = 600
    const val MAX_TEXT_UTF8_BYTES = 1_800
    const val MAX_FALLBACK_BYTES = 2 * 1024 * 1024
    const val FALLBACK_FORMAT = "mp3"
    const val SYSTEM_FORMAT = "wav"
    val SUPPORTED_FORMATS = setOf(SYSTEM_FORMAT, FALLBACK_FORMAT)

    fun status(system: JSONObject): JSONObject {
        val systemAvailable = system.optBoolean("available")
        // An available system voice can still be online. Unknown is not confirmed offline.
        val requiresNetwork = !systemAvailable || !system.optBoolean("offlineConfirmed")
        return JSONObject()
            .put("ok", true)
            .put("available", true)
            .put("provider", if (systemAvailable) PROVIDER_SYSTEM else PROVIDER_FALLBACK)
            .put("requiresNetwork", requiresNetwork)
            .put("networkChecked", false)
            .put("languages", org.json.JSONArray(listOf("zh-CN")))
            .put(
                "supportedFormats",
                org.json.JSONArray(listOf(if (systemAvailable) SYSTEM_FORMAT else FALLBACK_FORMAT)),
            )
            .put("maxTextChars", MAX_TEXT_CHARS)
            .put("system", system)
    }

    fun fallbackClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    data class Audio(
        val bytes: ByteArray,
        val format: String,
    )

    fun validateText(raw: String): String {
        val text = raw.trim()
        val utf8 = text.toByteArray(Charsets.UTF_8).size
        require(text.isNotEmpty() && text.length <= MAX_TEXT_CHARS) {
            "文本长度必须在 1–$MAX_TEXT_CHARS 个字符之间"
        }
        require(utf8 <= MAX_TEXT_UTF8_BYTES) { "文本 UTF-8 长度超过 $MAX_TEXT_UTF8_BYTES 字节" }
        require(!text.contains('\u0000')) { "文本不能包含空字节" }
        return text
    }

    fun validateRequestedFormat(raw: String?): String? {
        if (raw == null || raw.isEmpty()) return null
        val format = raw.lowercase()
        require(format in SUPPORTED_FORMATS) {
            "不支持的音频格式；可用 ${SUPPORTED_FORMATS.joinToString("/")}"
        }
        return format
    }

    fun validRequestId(raw: String): Boolean =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(raw)

    /**
     * Formats are identified from bytes. An engine-provided filename or response content type is
     * never treated as proof that the file is WAV or MP3.
     */
    fun detectFormat(bytes: ByteArray): String? {
        if (bytes.size < 16) return null
        if (
            bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() &&
                bytes[11] == 'E'.code.toByte()
        )
            return SYSTEM_FORMAT
        if (
            bytes.size >= 10 &&
                bytes[0] == 'I'.code.toByte() &&
                bytes[1] == 'D'.code.toByte() &&
                bytes[2] == '3'.code.toByte()
        ) {
            return FALLBACK_FORMAT
        }
        if (looksLikeMpegFrame(bytes)) return FALLBACK_FORMAT
        return null
    }

    private fun looksLikeMpegFrame(bytes: ByteArray): Boolean {
        if (bytes[0] != 0xff.toByte() || (bytes[1].toInt() and 0xe0) != 0xe0) return false
        val versionBits = (bytes[1].toInt() shr 3) and 0x03
        val layerBits = (bytes[1].toInt() shr 1) and 0x03
        val bitrateIndex = (bytes[2].toInt() shr 4) and 0x0f
        val sampleIndex = (bytes[2].toInt() shr 2) and 0x03
        return versionBits != 1 &&
            layerBits != 0 &&
            bitrateIndex !in setOf(0, 15) &&
            sampleIndex != 3
    }

    /**
     * Baidu's free endpoint historically returns either audio or a JSON envelope containing base64.
     * Accept only these two shapes and only bytes sniffed as MP3.
     */
    fun parseFallback(contentType: String?, body: ByteArray): Audio {
        if (body.isEmpty() || body.size > MAX_FALLBACK_BYTES) error("fallback_invalid_response")
        val type = contentType.orEmpty().lowercase()
        val direct =
            if (type.startsWith("audio/") || type.startsWith("application/octet-stream")) body
            else null
        if (detectFormat(direct ?: ByteArray(0)) == FALLBACK_FORMAT)
            return Audio(body, FALLBACK_FORMAT)

        val encoded =
            try {
                val value = JSONObject(String(body, Charsets.UTF_8))
                require(value.optInt("errno", -1) == 0 && !value.isNull("data")) {
                    "fallback_error_${value.optInt("errno", -1)}"
                }
                value.getString("data")
            } catch (_: Exception) {
                null
            } ?: error("fallback_invalid_response")
        val decoded =
            try {
                java.util.Base64.getMimeDecoder().decode(encoded)
            } catch (_: Exception) {
                error("fallback_invalid_base64")
            }
        if (detectFormat(decoded) != FALLBACK_FORMAT) error("fallback_not_mp3")
        return Audio(decoded, FALLBACK_FORMAT)
    }

    fun error(code: String, message: String): JSONObject =
        JSONObject().put("code", code).put("message", message)
}
