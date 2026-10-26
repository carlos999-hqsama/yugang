package com.personaldashboard.app

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/** Bounded, data-only admission rules shared by the worker and its JobService. */
internal object PluginTransferPolicy {
    const val MAX_FILE_BYTES = PluginFiles.MAX_BYTES
    const val MAX_ACTIVE_TRANSFERS = 2
    const val MAX_EXECUTOR_QUEUE = 8
    const val MAX_TASKS_PER_PLUGIN = 32
    const val MAX_PENDING_PER_PLUGIN = 8
    const val MAX_NAME_CHARS = 180
    const val MAX_MIME_CHARS = 160
    const val MAX_URL_CHARS = 8192
    const val MAX_REVISION_CHARS = 256
    const val MAX_ERROR_CHARS = 256
    const val PROGRESS_COMMIT_BYTES = 1024L * 1024L
    const val REVISION_CHECK_BYTES = 4L * 1024L * 1024L

    enum class Operation(val wire: String) {
        DOWNLOAD("download"),
        UPLOAD("upload"),
        ;

        companion object {
            fun from(value: String): Operation? = values().firstOrNull { it.wire == value }
        }
    }

    enum class UploadMethod {
        PUT,
        POST,
    }

    enum class UploadProtocol(val wire: String) {
        PLAIN("plain"),
        TUS("tus"),
        ;

        companion object {
            fun from(value: String): UploadProtocol? = values().firstOrNull { it.wire == value }
        }
    }

    /** State is deliberately explicit so recovery can never infer that an upload is safe to replay. */
    enum class State(val wire: String) {
        QUEUED("queued"),
        RUNNING("running"),
        PAUSED_DOWNLOAD("paused-download"),
        PAUSED_UPLOAD("paused-upload"),
        COMPLETED("completed"),
        FAILED("failed"),
        UNKNOWN("unknown"),
        CANCELLED("cancelled"),
        ;

        val terminal: Boolean
            get() = this == COMPLETED || this == FAILED || this == UNKNOWN || this == CANCELLED

        companion object {
            fun from(value: String): State? = values().firstOrNull { it.wire == value }
        }
    }

    data class DownloadRequest(
        val url: String,
        val name: String,
        val mime: String,
    )

    data class UploadRequest(
        val url: String,
        val handle: String,
        val method: UploadMethod,
        val protocol: UploadProtocol,
    )

    data class ContentRange(
        val start: Long,
        val endInclusive: Long,
        val total: Long?,
    )

    data class SafeError(
        val code: String,
        val message: String,
        val status: Int = 0,
        val retryable: Boolean = false,
        val cancelled: Boolean = false,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("code", code.take(64))
                .put("message", message.take(MAX_ERROR_CHARS))
                .put("status", status.coerceAtLeast(0))
                .put("retryable", retryable)
                .put("cancelled", cancelled)
    }

    fun parseDownload(args: JSONObject): DownloadRequest {
        val rawName = args.optString("name", "")
        require(validFileName(rawName)) { "下载文件名无效" }
        return DownloadRequest(
            url = parseUrl(args.optString("url", "")),
            name = rawName,
            mime = validMimeOrNull(args.optString("mime", "")) ?: DEFAULT_MIME,
        )
    }

    fun parseUpload(args: JSONObject): UploadRequest {
        val rawMethod = args.optString("method", "").ifBlank { UploadMethod.PUT.name }
        val method = parseUploadMethod(rawMethod)
        val rawProtocol = args.optString("protocol", "").ifBlank { UploadProtocol.PLAIN.wire }
        val protocol = parseUploadProtocol(rawProtocol)
        val handle = args.optString("handle", "")
        PluginContract.validateResourceId(handle)
        return UploadRequest(
            url = parseUrl(args.optString("url", "")),
            handle = handle,
            method = method,
            protocol = protocol,
        )
    }

    fun parseUrl(raw: String): String {
        require(raw.length <= MAX_URL_CHARS) { "传输链接过长" }
        return PluginContract.linkUrl(raw)
    }

    fun parseTaskId(args: JSONObject): String {
        val id = args.optString("taskId", "").ifBlank { args.optString("id", "") }
        PluginContract.validateResourceId(id)
        return id
    }

    fun parseUploadMethod(value: String): UploadMethod =
        when (value.trim().uppercase(Locale.ROOT)) {
            "PUT" -> UploadMethod.PUT
            "POST" -> UploadMethod.POST
            else -> throw IllegalArgumentException("上传方法只支持 PUT 或 POST")
        }

    fun parseUploadProtocol(value: String): UploadProtocol =
        when (value.trim().lowercase(Locale.ROOT)) {
            UploadProtocol.PLAIN.wire -> UploadProtocol.PLAIN
            UploadProtocol.TUS.wire -> UploadProtocol.TUS
            else -> throw IllegalArgumentException("上传协议只支持 plain 或 tus")
        }

    fun validateRevision(revision: String) {
        require(
            revision.length in 1..MAX_REVISION_CHARS &&
                revision.none { it.isISOControl() },
        ) { "插件修订标识无效" }
    }

    fun requireUserInitiated(context: Context, requested: Boolean) {
        require(!requested || isActuallyForeground(context)) {
            "userInitiated 只能由真实前台的插件页面声明"
        }
    }

    fun isActuallyForeground(context: Context): Boolean {
        val manager =
            context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
        val pid = Process.myPid()
        val state = manager.runningAppProcesses?.firstOrNull { it.pid == pid } ?: return false
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    fun interruptedState(operation: Operation, state: State, hasLiveWorker: Boolean): State =
        if(state != State.RUNNING || hasLiveWorker) state
        else if(operation == Operation.DOWNLOAD) State.PAUSED_DOWNLOAD else State.UNKNOWN

    fun canAutoRecover(state: State): Boolean =
        state == State.QUEUED || state == State.PAUSED_DOWNLOAD

    fun canRun(
        operation: Operation,
        protocol: UploadProtocol?,
        state: State,
        hasLiveWorker: Boolean,
    ): Boolean {
        if (hasLiveWorker || state.terminal) return false
        return when (operation) {
            Operation.DOWNLOAD -> state == State.QUEUED || state == State.PAUSED_DOWNLOAD
            // An upload is only runnable before its first byte is sent. A paused or unknown
            // upload must be explicitly reconciled/resumed, never silently replayed.
            Operation.UPLOAD -> state == State.QUEUED && protocol != null
        }
    }

    fun canExplicitResume(
        operation: Operation,
        protocol: UploadProtocol?,
        state: State,
    ): Boolean =
        when {
            operation == Operation.DOWNLOAD -> state == State.PAUSED_DOWNLOAD
            protocol == UploadProtocol.TUS ->
                state == State.PAUSED_UPLOAD || state == State.UNKNOWN
            else -> false
        }

    fun validFileName(name: String): Boolean =
        name.length in 1..MAX_NAME_CHARS && name.none { it.isISOControl() }

    fun validMimeOrNull(mime: String): String? {
        val value = mime.trim()
        return value.takeIf {
            it.length in 1..MAX_MIME_CHARS &&
                MIME_TOKEN.matches(it) &&
                it.none { ch -> ch == '\r' || ch == '\n' }
        }
    }

    fun safeMime(mime: String): String = validMimeOrNull(mime) ?: DEFAULT_MIME

    fun isStrongEtag(value: String?): Boolean =
        value != null &&
            value.length in 2..512 &&
            !value.startsWith("W/") &&
            value.first() == '"' &&
            value.last() == '"' &&
            value.none { it == '\r' || it == '\n' }

    fun parseContentRange(value: String?): ContentRange? {
        val match = value?.trim()?.let { CONTENT_RANGE.matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (start < 0 || end < start) return null
        if (match.groupValues[3] != "*" && total == null) return null
        if (total != null && (total < 0 || end >= total)) return null
        return ContentRange(start, end, total)
    }

    fun networkError(error: Throwable, cancelled: Boolean): SafeError {
        if (cancelled) return SafeError("cancelled", "传输已取消", cancelled = true)
        val timeout =
            error is SocketTimeoutException ||
                (error is InterruptedIOException && !Thread.currentThread().isInterrupted)
        return if (timeout) {
            SafeError("timeout", "网络请求超时", retryable = true)
        } else {
            SafeError("network_error", "网络请求失败", retryable = true)
        }
    }

    fun uploadUnknown(status: Int = 0): SafeError =
        SafeError(
            code = "upload_result_unknown",
            message = "上传结果未知，请向服务端核实后再新建任务",
            status = status,
            retryable = false,
        )

    fun jobIdFor(taskId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray(Charsets.UTF_8))
        var value = 0
        for (index in 0 until 4) value = (value shl 8) or (digest[index].toInt() and 0xff)
        value = value and Int.MAX_VALUE
        return if (value == 0) 1 else value
    }

    private val MIME_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}")
    private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    const val DEFAULT_MIME = "application/octet-stream"
}
