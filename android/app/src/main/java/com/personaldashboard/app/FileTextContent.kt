package com.personaldashboard.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import org.json.JSONObject

/** Bounded text supplied by a connector or a plugin; never an arbitrary filesystem path. */
data class FileTextContent(val mime: String, val name: String, val text: String, val bytes: Int) {
    fun toJson(): JSONObject =
        JSONObject().put("mime", mime).put("name", name).put("bytes", bytes).apply {
            if (mime == "text/html") put("html", text)
            else put("dataUrl", "data:$mime;charset=utf-8;base64," + Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8)))
        }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024

        fun mime(raw: String): String = raw.substringBefore(';').trim().lowercase(Locale.ROOT)

        fun isText(raw: String): Boolean = PluginMime.isText(raw)

        fun name(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "").take(180).ifBlank { "交付文件" }

        fun read(input: InputStream, length: Long, contentType: String, rawName: String, checkActive: () -> Unit = {}): FileTextContent {
            require(isText(contentType)) { "该文件不是可预览的文本，请下载后打开" }
            require(length <= MAX_BYTES) { "文本文件超过 2 MiB，请下载后打开" }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size().toLong() + count <= MAX_BYTES) { "文本文件超过 2 MiB，请下载后打开" }
                out.write(buffer, 0, count)
            }
            checkActive()
            check(length < 0 || out.size().toLong() == length) { "文件未完整读取，请重试" }
            val data = out.toByteArray()
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try { decoder.decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF") }
                catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("文件不是有效的 UTF-8 文本，请下载后打开") }
            return FileTextContent(mime(contentType), name(rawName), text, data.size)
        }

        fun fromJson(args: JSONObject): FileTextContent {
            val fields = listOf("html", "text", "dataUrl").filter { args.has(it) && !args.isNull(it) }
            require(fields.size == 1) { "请提供 html、text 或 dataUrl 中的一项" }
            val field = fields.single()
            val value = args.get(field)
            require(value is String) { "文件内容必须是字符串" }
            val name = args.optString("name", "交付文件")
            if (field == "dataUrl") {
                require(value.length <= (MAX_BYTES + 2) / 3 * 4 + 160) { "文本文件超过 2 MiB" }
                val match = Regex("^data:([^;,]+)(?:;charset=utf-8)?;base64,([A-Za-z0-9+/=]*)$", RegexOption.IGNORE_CASE).matchEntire(value)
                    ?: throw IllegalArgumentException("只支持文本文件的 base64 data URL")
                val type = mime(match.groupValues[1])
                if (args.has("mime")) require(type == mime(args.getString("mime"))) { "文件类型与 data URL 不一致" }
                val data = try { Base64.getDecoder().decode(match.groupValues[2]) }
                    catch (_: IllegalArgumentException) { throw IllegalArgumentException("文件 data URL 无效") }
                return read(data.inputStream(), data.size.toLong(), type, name)
            }
            val type = args.optString("mime", if (field == "html") "text/html" else "text/plain")
            require(field != "html" || mime(type) == "text/html") { "html 内容必须声明 text/html" }
            val data = value.toByteArray(StandardCharsets.UTF_8)
            return read(data.inputStream(), data.size.toLong(), type, name)
        }
    }
}
