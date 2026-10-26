package com.personaldashboard.app

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Proxy
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stages Android picker content under an app-private cache and uploads the original bytes directly
 * to the local DSH binary-upload endpoint.
 *
 * [stage] and [upload] are blocking and must be called off the UI thread. Staged entries remain
 * until [remove] or [close]. Successfully uploaded receipts are reused only in the same session,
 * after host validation, and are never persisted across process restarts.
 */
class BotAttachmentTransfer private constructor(
    private val context: Context?,
    cacheRoot: File,
    private val dshBaseUrl: () -> String,
    private val dshCookie: () -> String,
    private val validateSession: (botId: String, sessionId: String) -> Unit,
) {
    constructor(
        context: Context,
        cfg: Config,
        validateSession: (botId: String, sessionId: String) -> Unit,
    ) : this(
        context.applicationContext,
        File(context.applicationContext.cacheDir, BotAttachmentPolicy.CACHE_ROOT),
        { cfg.dshUrl },
        { cfg.dshCookie },
        validateSession,
    )

    private data class ReceiptKey(
        val sessionId: String,
        val handle: String,
    )

    private data class StagedAttachment(
        val handle: String,
        val name: String,
        val mediaType: String,
        val kind: String,
        val bytes: Long,
        val durationMs: Long?,
        val file: File,
    )

    private data class CachedReceipt(
        val receiptId: String,
        val attachmentId: String,
        val name: String,
        val bytes: Long,
    )

    private inner class UploadOperation(
        val requestId: String,
        val handle: String,
        val key: ReceiptKey,
        private val parent: PluginCancellation,
    ) {
        @Volatile private var cancelled = false
        @Volatile private var call: Call? = null
        @Volatile var requestStarted = false
        val bytesSent = AtomicLong()

        fun attachCall(next: Call) {
            call = next
            if (isCancelled()) next.cancel()
        }

        fun cancel() {
            cancelled = true
            call?.cancel()
        }

        fun isCancelled(): Boolean =
            cancelled ||
                parent.cancelled ||
                Thread.currentThread().isInterrupted ||
                call?.isCanceled() == true

        fun checkActive(outcomeUnknown: Boolean = false) {
            if (isCancelled()) {
                throw BotAttachmentException(
                    "cancelled",
                    if (outcomeUnknown) "上传已取消；服务端结果需核对" else "上传已取消",
                    cancelled = true,
                    outcomeUnknown = outcomeUnknown,
                )
            }
        }
    }

    private val random = SecureRandom()
    private val stateLock = Any()
    private val stageGate = Any()
    private val pendingCancels = PendingRequestCancellations()
    private val stages = LinkedHashMap<String, StagedAttachment>()
    private val receipts = HashMap<ReceiptKey, CachedReceipt>()
    private val uploads = HashMap<String, UploadOperation>()
    private val uploadKeys = HashMap<ReceiptKey, String>()
    private var stagedBytes = 0L
    @Volatile private var closeRequested = false
    @Volatile private var activeStageSource: InputStream? = null
    private var closed = false

    private val instanceDirectory =
        File(cacheRoot, "run-${randomToken(16)}").also { directory ->
            try {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw BotAttachmentException("storage_unavailable", "无法创建附件暂存目录")
                }
            } catch (_: SecurityException) {
                throw BotAttachmentException("storage_unavailable", "无法创建附件暂存目录")
            }
        }

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .build()

    /**
     * Copies current content-URI streams into private cache. No source URI or local cache path is
     * returned. A failed or over-limit batch deletes only files created by that batch. Entries
     * contain handle/name/mediaType/bytes/kind, plus durationMs only when the staged video itself
     * yields a real duration; no thumbnail is fabricated.
     */
    fun stage(uris: List<Uri>): JSONArray {
        BotAttachmentPolicy.requireBatchSize(uris.size)
        return synchronized(stageGate) {
            val resolver =
                context?.contentResolver
                    ?: throw BotAttachmentException("staging_unavailable", "当前环境不能暂存附件")
            synchronized(stateLock) {
                requireOpenLocked()
                BotAttachmentPolicy.requireCapacity(stages.size, stagedBytes, uris.size, 0)
            }
            val existingBytes = synchronized(stateLock) { stagedBytes }
            val created = ArrayList<StagedAttachment>(uris.size)
            val createdFiles = ArrayList<File>(uris.size)
            var batchBytes = 0L
            var published = false
            try {
                for (uri in uris) {
                    if (uri.scheme?.lowercase(Locale.ROOT) != ContentResolver.SCHEME_CONTENT) {
                        throw BotAttachmentException(
                            "content_uri_required",
                            "只能暂存本次选择授予读取权限的内容 URI",
                        )
                    }
                    val name = displayName(resolver, uri)
                    val mediaType = mediaType(resolver, uri, name)
                    val handle = newHandle(created.map { it.handle }.toSet())
                    val file = createStageFile()
                    createdFiles += file
                    val bytes =
                        copyContentUri(
                            resolver,
                            uri,
                            file,
                            existingBytes,
                            batchBytes,
                        )
                    BotAttachmentPolicy.requireFileBytes(bytes)
                    if (batchBytes + bytes > BotAttachmentPolicy.MAX_STAGED_BYTES) {
                        throw BotAttachmentException(
                            "staging_limit_reached",
                            "暂存文件总大小超过 256 MiB",
                        )
                    }
                    requireStagingActive()
                    val kind = BotAttachmentPolicy.kindFor(mediaType)
                    created +=
                        StagedAttachment(
                            handle = handle,
                            name = name,
                            mediaType = mediaType,
                            kind = kind,
                            bytes = bytes,
                            durationMs = readVideoDurationMs(file, kind),
                            file = file,
                        )
                    batchBytes += bytes
                }
                val result = JSONArray()
                created.forEach { result.put(it.toJson()) }
                synchronized(stateLock) {
                    requireOpenLocked()
                    BotAttachmentPolicy.requireCapacity(
                        stages.size,
                        stagedBytes,
                        created.size,
                        batchBytes,
                    )
                    created.forEach { stages[it.handle] = it }
                    stagedBytes += batchBytes
                }
                published = true
                result
            } finally {
                if (!published) createdFiles.forEach { runCatching { it.delete() } }
            }
        }
    }

    /**
     * Streams one staged file to DSH. The host session callback runs before either a cached receipt
     * is returned or bytes are sent. Progress is synchronous on the upload thread and throttled to
     * roughly 100 ms, with an initial and final event.
     */
    fun upload(
        args: JSONObject,
        cancellation: PluginCancellation = PluginCancellation(),
        progress: (JSONObject) -> Unit = {},
    ): JSONObject {
        val handle = requiredArgument(args, "handle")
        val botId = requiredArgument(args, "botId")
        val sessionId = requiredArgument(args, "sessionId")
        val requestId = requiredArgument(args, "requestId")
        requireHandle(handle)
        val key = ReceiptKey(sessionId, handle)
        val operation = UploadOperation(requestId, handle, key, cancellation)
        beginUpload(operation)
        val detach = cancellation.onCancel { operation.cancel() }
        try {
            operation.checkActive()
            validateSessionOrThrow(botId, sessionId)
            operation.checkActive()

            val staged =
                synchronized(stateLock) {
                    requireOpenLocked()
                    stages[handle]
                        ?: throw BotAttachmentException("handle_not_found", "附件暂存已过期或已删除")
                }
            synchronized(stateLock) {
                receipts[key]?.let { cached ->
                    operation.checkActive()
                    return staged.toJson(cached)
                }
            }
            if (!staged.file.isFile || staged.file.length() != staged.bytes) {
                throw BotAttachmentException("stage_missing", "本地附件暂存不完整，请重新选择")
            }

            val uploaded =
                send(
                    staged = staged,
                    sessionId = sessionId,
                    requestId = requestId,
                    operation = operation,
                    progress = progress,
                )
            if (operation.isCancelled()) operation.checkActive(outcomeUnknown = true)
            synchronized(stateLock) {
                if (closed || stages[handle] !== staged || operation.isCancelled()) {
                    throw BotAttachmentException(
                        "cancelled",
                        "附件已移除或传输组件已关闭；服务端结果需核对",
                        cancelled = true,
                        outcomeUnknown = true,
                    )
                }
                receipts[key] = uploaded
            }
            return staged.toJson(uploaded)
        } finally {
            detach()
            synchronized(stateLock) {
                if (uploads[requestId] === operation) uploads.remove(requestId)
                if (uploadKeys[key] == requestId) uploadKeys.remove(key)
            }
        }
    }

    /**
     * Cancels the exact active request, or records a bounded tombstone so a bridge action that
     * reaches this object later cannot start. An empty or oversized id is ignored.
     */
    fun cancel(requestId: String): Boolean {
        if (
            requestId.isEmpty() ||
            requestId.length > BotAttachmentPolicy.MAX_REQUEST_ID_LENGTH
        ) {
            return false
        }
        val operation: UploadOperation?
        synchronized(stateLock) {
            if (closed) return false
            operation = uploads[requestId]
            if (operation == null) pendingCancels.mark(requestId)
        }
        operation?.cancel()
        return true
    }

    /** Deletes one staged entry, its cached receipts and any active uploads of that exact handle. */
    internal fun preview(handle: String): Pair<File, JSONObject> = synchronized(stateLock) {
        requireHandle(handle)
        requireOpenLocked()
        val staged = stages[handle] ?: throw BotAttachmentException("handle_not_found", "附件暂存已过期或已删除")
        check(staged.file.isFile && staged.file.length() == staged.bytes) { "附件暂存不完整，请重新选择" }
        staged.file to staged.toJson()
    }

    /** A definite FILE_NOT_STAGED rejection permits a fresh upload on the user's next retry. */
    internal fun forgetReceipt(handle: String, sessionId: String) = synchronized(stateLock) {
        requireHandle(handle)
        receipts.remove(ReceiptKey(sessionId, handle))
        Unit
    }

    fun remove(handle: String): JSONObject {
        requireHandle(handle)
        val operations: List<UploadOperation>
        val removed: StagedAttachment?
        synchronized(stageGate) {
            synchronized(stateLock) {
                removed = stages.remove(handle)
                if (removed != null) {
                    stagedBytes = (stagedBytes - removed.bytes).coerceAtLeast(0L)
                }
                receipts.keys.removeAll { it.handle == handle }
                operations = uploads.values.filter { it.handle == handle }
                operations.forEach { it.cancel() }
            }
        }
        val existed = removed != null
        val deleted = removed?.let { !it.file.exists() || it.file.delete() } ?: false
        return JSONObject()
            .put("removed", existed)
            .put("deleted", deleted)
            .put("cancelledUploads", operations.size)
    }

    /** Cancels every active upload, removes staged/cached entries and deletes this instance's cache. */
    fun close() {
        closeRequested = true
        runCatching { activeStageSource?.close() }
        val active: List<UploadOperation>
        synchronized(stateLock) {
            if (closed) return
            closed = true
            stages.clear()
            receipts.clear()
            stagedBytes = 0L
            pendingCancels.clear()
            active = uploads.values.toList()
            active.forEach { it.cancel() }
            uploads.clear()
            uploadKeys.clear()
        }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        // Wait for an in-flight stage copy to observe closeRequested and close its streams.
        synchronized(stageGate) { runCatching { instanceDirectory.deleteRecursively() } }
    }

    /**
     * Adds an already-memory-backed file for JVM loopback tests only. Production callers use
     * [stage], which preserves the picker's temporary content grant and copies the real stream.
     */
    internal fun stageTestBytesForLocalLoopback(
        name: String,
        bytes: ByteArray,
        mediaType: String = BotAttachmentPolicy.OCTET_STREAM,
    ): String {
        return synchronized(stageGate) {
            requireOpen()
            BotAttachmentPolicy.requireFileBytes(bytes.size.toLong())
            val cleanName = BotAttachmentPolicy.sanitizeDisplayName(name)
            val cleanType = BotAttachmentPolicy.normalizeMediaType(mediaType)
            val handle = newHandle(emptySet())
            val file = createStageFile()
            try {
                FileOutputStream(file).use { it.write(bytes) }
                val staged =
                    StagedAttachment(
                        handle = handle,
                        name = cleanName,
                        mediaType = cleanType,
                        kind = BotAttachmentPolicy.kindFor(cleanType),
                        bytes = bytes.size.toLong(),
                        durationMs = null,
                        file = file,
                    )
                synchronized(stateLock) {
                    requireOpenLocked()
                    BotAttachmentPolicy.requireCapacity(
                        stages.size,
                        stagedBytes,
                        1,
                        staged.bytes,
                    )
                    stages[handle] = staged
                    stagedBytes += staged.bytes
                }
                handle
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }

    private fun beginUpload(operation: UploadOperation) {
        synchronized(stateLock) {
            requireOpenLocked()
            if (pendingCancels.consume(operation.requestId)) {
                throw BotAttachmentException("cancelled", "上传已取消", cancelled = true)
            }
            if (uploads.containsKey(operation.requestId)) {
                throw BotAttachmentException("request_in_progress", "该上传请求正在使用中")
            }
            if (uploadKeys.containsKey(operation.key)) {
                throw BotAttachmentException("upload_in_progress", "该附件正在上传")
            }
            uploads[operation.requestId] = operation
            uploadKeys[operation.key] = operation.requestId
        }
    }

    private fun send(
        staged: StagedAttachment,
        sessionId: String,
        requestId: String,
        operation: UploadOperation,
        progress: (JSONObject) -> Unit,
    ): CachedReceipt {
        val endpoint = uploadEndpoint(sessionId, staged.name)
        val cookie =
            try {
                dshCookie()
            } catch (_: Exception) {
                throw BotAttachmentException("credentials_unavailable", "登录凭据不可用，请重新配对")
            }
        val request =
            try {
                Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
                    .post(StagedUploadBody(staged, operation, requestId, progress))
                    .build()
            } catch (_: Exception) {
                throw BotAttachmentException("credentials_unavailable", "登录凭据不可用，请重新配对")
            }

        val call = client.newCall(request)
        operation.attachCall(call)
        operation.checkActive()
        operation.requestStarted = true
        var status = 0
        try {
            call.execute().use { response ->
                status = response.code
                if (!response.isSuccessful) throw httpFailure(status)
                val payload = readBoundedResponse(response, status)
                val receipt = BotAttachmentPolicy.parseReceipt(payload, staged.bytes, status)
                return CachedReceipt(
                    receiptId = receipt.receiptId,
                    attachmentId = receipt.file.attachmentId,
                    name = receipt.file.name,
                    bytes = receipt.file.bytes,
                )
            }
        } catch (error: BotAttachmentException) {
            throw error
        } catch (error: IOException) {
            val cancelled = operation.isCancelled()
            val timeout = !cancelled && error is InterruptedIOException
            val unknown =
                operation.requestStarted && operation.bytesSent.get() >= staged.bytes
            val code =
                when {
                    cancelled -> "cancelled"
                    timeout -> "timeout"
                    else -> "network_error"
                }
            val message =
                when {
                    cancelled && unknown -> "上传已取消；服务端结果需核对"
                    cancelled -> "上传已取消"
                    timeout && unknown -> "上传超时；服务端结果需核对"
                    timeout -> "上传超时"
                    else -> "上传连接失败"
                }
            throw BotAttachmentException(
                code,
                message,
                status = status,
                cancelled = cancelled,
                timeout = timeout,
                retryable = !cancelled && !timeout && !unknown,
                outcomeUnknown = unknown,
            )
        } catch (_: Exception) {
            val cancelled = operation.isCancelled()
            val unknown =
                operation.requestStarted && operation.bytesSent.get() >= staged.bytes
            throw BotAttachmentException(
                if (cancelled) "cancelled" else "upload_failed",
                when {
                    cancelled && unknown -> "上传已取消；服务端结果需核对"
                    cancelled -> "上传已取消"
                    else -> "上传失败，请稍后重试"
                },
                status = status,
                cancelled = cancelled,
                outcomeUnknown = unknown,
            )
        }
    }

    private fun uploadEndpoint(sessionId: String, name: String): HttpUrl {
        val base =
            try {
                dshBaseUrl().trim().trimEnd('/')
            } catch (_: Exception) {
                throw BotAttachmentException("runtime_unavailable", "对话运行时地址不可用")
            }
        if (!Config.isLoopback(base)) {
            throw BotAttachmentException("loopback_required", "dsh 地址只能是本机回环地址")
        }
        return try {
            "$base/api/session/uploadFileBinary"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("sessionId", sessionId)
                .addQueryParameter("name", name)
                .build()
        } catch (_: Exception) {
            throw BotAttachmentException("invalid_request", "上传地址或参数无效")
        }
    }

    private fun readBoundedResponse(response: Response, status: Int): ByteArray {
        val body =
            response.body
                ?: throw BotAttachmentPolicy.invalidResponse(status)
        if (body.contentLength() > BotAttachmentPolicy.MAX_RESPONSE_BYTES) {
            throw BotAttachmentPolicy.responseTooLarge(status)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size().toLong() + count > BotAttachmentPolicy.MAX_RESPONSE_BYTES) {
                    throw BotAttachmentPolicy.responseTooLarge(status)
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun httpFailure(status: Int): BotAttachmentException {
        val code =
            when {
                status in 300..399 -> "redirect_rejected"
                status == 401 || status == 403 -> "unauthorized"
                status == 413 -> "file_rejected"
                status == 429 -> "rate_limited"
                status >= 500 -> "service_unavailable"
                else -> "http_error"
            }
        val message =
            when (code) {
                "redirect_rejected" -> "上传地址发生重定向，已拒绝"
                "unauthorized" -> "登录已失效，请重新配对"
                "file_rejected" -> "文件超出上传服务限制"
                "rate_limited" -> "上传过于频繁，请稍后重试"
                "service_unavailable" -> "上传服务暂不可用"
                else -> "上传失败（HTTP $status）"
            }
        return BotAttachmentException(
            code,
            message,
            status = status,
            retryable = status == 429 || status >= 500,
        )
    }

    private fun validateSessionOrThrow(botId: String, sessionId: String) {
        try {
            validateSession(botId, sessionId)
        } catch (_: Exception) {
            throw BotAttachmentException("session_invalid", "当前会话不可用")
        }
    }

    private fun copyContentUri(
        resolver: ContentResolver,
        uri: Uri,
        destination: File,
        existingBytes: Long,
        completedBatchBytes: Long,
    ): Long {
        var copied = 0L
        try {
            val source =
                resolver.openInputStream(uri)
                    ?: throw BotAttachmentException("source_unavailable", "无法读取所选文件")
            activeStageSource = source
            try {
                source.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(BotAttachmentPolicy.COPY_BUFFER_BYTES)
                        while (true) {
                            requireStagingActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            copied += count
                            BotAttachmentPolicy.requireFileBytes(copied)
                            if (
                                existingBytes + completedBatchBytes + copied >
                                    BotAttachmentPolicy.MAX_STAGED_BYTES
                            ) {
                                throw BotAttachmentException(
                                    "staging_limit_reached",
                                    "暂存文件总大小超过 256 MiB",
                                )
                            }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
            } finally {
                if (activeStageSource === source) activeStageSource = null
            }
        } catch (error: BotAttachmentException) {
            throw error
        } catch (_: SecurityException) {
            requireStagingActive()
            throw BotAttachmentException("permission_denied", "没有读取所选文件的权限")
        } catch (_: IOException) {
            requireStagingActive()
            throw BotAttachmentException("source_unavailable", "无法读取或暂存所选文件")
        } catch (_: Exception) {
            requireStagingActive()
            throw BotAttachmentException("source_unavailable", "无法读取所选文件")
        }
        return copied
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        var raw: String? = null
        try {
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                        raw = cursor.getString(column)
                    }
                }
        } catch (_: Exception) {
            // The subsequent stream open still decides whether the picker grant is usable.
        }
        return BotAttachmentPolicy.sanitizeDisplayName(raw ?: uri.lastPathSegment)
    }

    private fun mediaType(resolver: ContentResolver, uri: Uri, name: String): String {
        val declared = runCatching { resolver.getType(uri) }.getOrNull()
        val normalized = BotAttachmentPolicy.normalizeMediaType(declared)
        if (normalized != BotAttachmentPolicy.OCTET_STREAM) return normalized
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val inferred =
            extension
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }.getOrNull() }
        return BotAttachmentPolicy.normalizeMediaType(inferred ?: declared)
    }

    private fun readVideoDurationMs(file: File, kind: String): Long? {
        if (kind != "video") return null
        return runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                } finally {
                    runCatching { retriever.release() }
                }
            }
            .getOrNull()
    }

    private fun createStageFile(): File =
        try {
            File.createTempFile("stage-", ".bin", instanceDirectory)
        } catch (_: Exception) {
            throw BotAttachmentException("storage_unavailable", "无法创建本地附件暂存")
        }

    private fun newHandle(additionalUsed: Set<String>): String {
        while (true) {
            val candidate = randomToken(BotAttachmentPolicy.HANDLE_BYTES)
            if (candidate !in additionalUsed &&
                synchronized(stateLock) { !stages.containsKey(candidate) }
            ) {
                return candidate
            }
        }
    }

    private fun randomToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun requireOpen() {
        synchronized(stateLock) { requireOpenLocked() }
    }

    private fun requireOpenLocked() {
        if (closed || closeRequested) {
            throw BotAttachmentException("closed", "附件传输组件已关闭")
        }
    }

    private fun requireStagingActive() {
        if (closeRequested) {
            throw BotAttachmentException("cancelled", "附件暂存已取消", cancelled = true)
        }
    }

    private fun requiredArgument(args: JSONObject, name: String): String {
        val value = args.opt(name)
        val maxLength =
            if (name == "requestId") BotAttachmentPolicy.MAX_REQUEST_ID_LENGTH
            else BotAttachmentPolicy.MAX_SESSION_ARGUMENT_LENGTH
        if (value !is String || value.isBlank() || value.length > maxLength) {
            throw BotAttachmentException("invalid_argument", "上传参数无效")
        }
        return value
    }

    private fun requireHandle(handle: String) {
        if (
            handle.length !in 16..96 ||
            handle.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }
        ) {
            throw BotAttachmentException("invalid_handle", "附件暂存句柄无效")
        }
    }

    private fun StagedAttachment.toJson(): JSONObject =
        JSONObject()
            .put("handle", handle)
            .put("name", name)
            .put("mediaType", mediaType)
            .put("bytes", bytes)
            .put("kind", kind)
            .apply { durationMs?.let { put("durationMs", it) } }

    private fun StagedAttachment.toJson(receipt: CachedReceipt): JSONObject =
        toJson()
            .put("receiptId", receipt.receiptId)
            .put(
                "file",
                JSONObject()
                    .put("attachmentId", receipt.attachmentId)
                    .put("name", receipt.name)
                    .put("bytes", receipt.bytes),
            )

    private inner class StagedUploadBody(
        private val staged: StagedAttachment,
        private val operation: UploadOperation,
        private val requestId: String,
        private val progress: (JSONObject) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM

        override fun contentLength(): Long = staged.bytes

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            var lastReport = 0L
            fun report() {
                lastReport = System.nanoTime()
                val event =
                    JSONObject()
                        .put("requestId", requestId)
                        .put("handle", staged.handle)
                        .put("bytes", operation.bytesSent.get())
                        .put("total", staged.bytes)
                        .put("phase", "uploading")
                runCatching { progress(event) }
            }

            if (!staged.file.isFile || staged.file.length() != staged.bytes) {
                throw IOException("staged file changed")
            }
            FileInputStream(staged.file).use { input ->
                val buffer = ByteArray(BotAttachmentPolicy.COPY_BUFFER_BYTES)
                var sent = 0L
                report()
                while (true) {
                    if (operation.isCancelled()) throw IOException("upload cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (sent + count > staged.bytes) throw IOException("staged file grew")
                    sink.write(buffer, 0, count)
                    sent += count
                    operation.bytesSent.set(sent)
                    val now = System.nanoTime()
                    if (
                        sent == staged.bytes ||
                            now - lastReport >= BotAttachmentPolicy.PROGRESS_INTERVAL_NANOS
                    ) {
                        report()
                    }
                }
                if (sent != staged.bytes) throw IOException("staged file shrank")
            }
            sink.flush()
        }
    }

    internal companion object {
        private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

        /** Test-only entry point; the production constructor always reads the host [Config]. */
        internal fun forLocalLoopbackTest(
            cacheRoot: File,
            loopbackBaseUrl: String,
            cookie: String,
            validateSession: (botId: String, sessionId: String) -> Unit,
        ): BotAttachmentTransfer =
            BotAttachmentTransfer(
                null,
                cacheRoot,
                { loopbackBaseUrl },
                { cookie },
                validateSession,
            )
    }
}
