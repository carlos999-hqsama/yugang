package com.personaldashboard.app

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import org.json.JSONObject

/** Structured failure for attachment staging and upload; never carries a response body or cookie. */
class BotAttachmentException(
    val code: String,
    override val message: String,
    val status: Int = 0,
    val cancelled: Boolean = false,
    val timeout: Boolean = false,
    val retryable: Boolean = false,
    val outcomeUnknown: Boolean = false,
) : RuntimeException(message) {
    fun payload(): JSONObject =
        JSONObject()
            .put("error", message)
            .put("code", code)
            .put("status", status)
            .put("cancelled", cancelled)
            .put("timeout", timeout)
            .put("retryable", retryable)
            .put("outcomeUnknown", outcomeUnknown)
}

/**
 * Pure limits and wire-shape checks shared by staging, upload and JVM tests.
 *
 * This file deliberately has no Android I/O so malformed names, response bounds and cancellation
 * tombstones can be checked without opening a provider or a network connection.
 */
internal object BotAttachmentPolicy {
    const val MAX_ATTACHMENTS = 6
    const val MAX_STAGED_FILES = 64
    const val MAX_FILE_BYTES = 256L * 1024L * 1024L
    const val MAX_STAGED_BYTES = 256L * 1024L * 1024L
    const val MAX_RESPONSE_BYTES = 64 * 1024
    const val COPY_BUFFER_BYTES = 64 * 1024
    const val PROGRESS_INTERVAL_NANOS = 100_000_000L
    const val HANDLE_BYTES = 32
    const val MAX_DISPLAY_NAME_CODE_POINTS = 180
    const val MAX_REQUEST_ID_LENGTH = 256
    const val MAX_SESSION_ARGUMENT_LENGTH = 512
    const val MAX_RECEIPT_FIELD_LENGTH = 512
    const val OCTET_STREAM = "application/octet-stream"
    const val DEFAULT_FILE_NAME = "附件"
    const val CACHE_ROOT = "bot-attachment-transfer"

    private const val MAX_RAW_NAME_CHARS = 4096
    private const val MIME_TOKEN = "[a-z0-9!#$%&'+.^_`|~-]+"
    private val MEDIA_TYPE = Regex("^$MIME_TOKEN/$MIME_TOKEN$")

    data class ServerFile(
        val attachmentId: String,
        val name: String,
        val bytes: Long,
    )

    data class ServerReceipt(
        val receiptId: String,
        val file: ServerFile,
    )

    fun requireBatchSize(count: Int) {
        if (count !in 1..MAX_ATTACHMENTS) {
            throw BotAttachmentException("invalid_batch", "每次只能暂存 1 至 $MAX_ATTACHMENTS 个文件")
        }
    }

    fun requireCapacity(
        activeCount: Int,
        stagedBytes: Long,
        incomingCount: Int,
        incomingBytes: Long,
    ) {
        requireBatchSize(incomingCount)
        if (activeCount < 0 || stagedBytes < 0 || incomingBytes < 0) {
            throw BotAttachmentException("invalid_limit", "附件暂存状态异常")
        }
        if (activeCount + incomingCount > MAX_STAGED_FILES) {
            throw BotAttachmentException("too_many_files", "暂存文件过多，请移除草稿或放弃失败消息后重试")
        }
        if (stagedBytes + incomingBytes > MAX_STAGED_BYTES) {
            throw BotAttachmentException("staging_limit_reached", "暂存文件总大小超过 256 MiB")
        }
    }

    fun requireFileBytes(bytes: Long) {
        if (bytes > MAX_FILE_BYTES) {
            throw BotAttachmentException("file_too_large", "单个文件不能超过 256 MiB")
        }
    }

    fun normalizeMediaType(raw: String?): String {
        val base = raw?.substringBefore(';')?.trim()?.lowercase()
        return if (base != null && MEDIA_TYPE.matches(base)) base else OCTET_STREAM
    }

    fun kindFor(mediaType: String): String =
        if (mediaType.startsWith("video/")) "video" else "file"

    /** Display-only name: keeps Chinese and internal spaces, removes path/control syntax. */
    fun sanitizeDisplayName(raw: String?): String {
        val source = raw.orEmpty().take(MAX_RAW_NAME_CHARS)
        val normalized = runCatching { Normalizer.normalize(source, Normalizer.Form.NFC) }.getOrDefault(source)
        val cleaned = StringBuilder()
        var codePoints = 0
        var index = 0
        while (index < normalized.length && codePoints < MAX_DISPLAY_NAME_CODE_POINTS) {
            val codePoint = normalized.codePointAt(index)
            index += Character.charCount(codePoint)
            val unsafe =
                codePoint == '/'.code ||
                    codePoint == '\\'.code ||
                    Character.isISOControl(codePoint) ||
                    Character.getType(codePoint) == Character.FORMAT.toInt()
            cleaned.appendCodePoint(if (unsafe) '_'.code else codePoint)
            codePoints++
        }
        val name = cleaned.toString().trim { it.isWhitespace() || it == '.' }
        return name.ifEmpty { DEFAULT_FILE_NAME }
    }

    fun parseReceipt(payload: ByteArray, expectedBytes: Long, status: Int): ServerReceipt {
        if (payload.size > MAX_RESPONSE_BYTES) {
            throw responseTooLarge(status)
        }
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString()
            } catch (_: Exception) {
                throw invalidResponse(status)
            }
        val root =
            try {
                JSONObject(text)
            } catch (_: Exception) {
                throw invalidResponse(status)
            }
        val ok = root.opt("ok")
        if (ok == false) {
            throw BotAttachmentException(
                "upload_rejected",
                "上传服务未接收文件",
                status = status,
            )
        }
        if (ok != true) throw invalidResponse(status)
        val value = root.optJSONObject("value") ?: throw invalidResponse(status)
        val receiptId = requiredString(value, "receiptId", status)
        val file = value.optJSONObject("file") ?: throw invalidResponse(status)
        val attachmentId = requiredString(file, "attachmentId", status)
        val name = requiredString(file, "name", status)
        val rawBytes = file.opt("bytes")
        if (rawBytes !is Number) throw invalidResponse(status)
        val bytes = rawBytes.toLong()
        if (bytes < 0 || (rawBytes is Double && rawBytes != bytes.toDouble())) {
            throw invalidResponse(status)
        }
        if (bytes != expectedBytes) {
            throw invalidResponse(status)
        }
        return ServerReceipt(receiptId, ServerFile(attachmentId, name, bytes))
    }

    fun invalidResponse(status: Int): BotAttachmentException =
        BotAttachmentException(
            "invalid_response",
            "上传服务返回的数据无法确认",
            status = status,
            outcomeUnknown = true,
        )

    fun responseTooLarge(status: Int): BotAttachmentException =
        BotAttachmentException(
            "response_too_large",
            "上传服务响应超过 64 KiB 限制",
            status = status,
            outcomeUnknown = true,
        )

    private fun requiredString(value: JSONObject, field: String, status: Int): String {
        val raw = value.opt(field)
        if (raw !is String || raw.isBlank() || raw.length > MAX_RECEIPT_FIELD_LENGTH) {
            throw invalidResponse(status)
        }
        return raw
    }
}

