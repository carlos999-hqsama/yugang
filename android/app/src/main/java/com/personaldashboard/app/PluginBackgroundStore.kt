package com.personaldashboard.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-private background state. All instances for the same canonical path share a lock and every
 * transaction reloads from disk; no process-wide mutable cache is used.
 */
class PluginBackgroundStore(directory: File) {
    private val file = File(directory, "background.json")
    private val lock = pathLock(file.absoluteFile.canonicalPath)

    constructor(context: Context) : this(File(context.filesDir, "plugin-background"))

    fun get(pluginId: String): ScheduleRecord? =
        synchronized(lock) {
            val id = PluginContract.id(pluginId)
            loadRequired().getJSONObject("schedules").optJSONObject(id)?.let(::schedule)
        }

    fun all(): List<ScheduleRecord> =
        synchronized(lock) {
            val rows = loadRequired().getJSONObject("schedules")
            rows.keys().asSequence().toList().mapNotNull { id ->
                rows.optJSONObject(id)?.let(::schedule)
            }
        }

    fun put(record: ScheduleRecord) =
        synchronized(lock) {
            val normalized = normalizeRecord(record)
            validateRecord(normalized)
            val root = loadRequired()
            root.getJSONObject("schedules")
                .put(normalized.pluginId, normalized.toJson().put("pluginId", normalized.pluginId))
            save(root)
        }

    fun remove(pluginId: String) =
        synchronized(lock) {
            val id = PluginContract.id(pluginId)
            val root = loadRequired()
            root.getJSONObject("schedules").remove(id)
            save(root)
        }

    fun markRun(
        pluginId: String,
        atMs: Long,
        status: String,
        durationMs: Long,
        generation: String? = null,
        result: PluginBackgroundPolicy.ActionResult? = null,
        errorMessage: String? = null,
    ) =
        synchronized(lock) {
            val id = PluginContract.id(pluginId)
            val root = loadRequired()
            val row = root.getJSONObject("schedules").optJSONObject(id)
            if (row == null) {
                // A generation-bearing write is a receipt for one scheduled run. If its row was
                // removed meanwhile, there is nothing to update and it must not recreate the row.
                if (generation != null) return
                error("插件后台计划不存在，不能写入执行状态")
            }
            if (generation != null && row.getString("generation") != generation) return
            val finalStatus = PluginBackgroundPolicy.status(status)
            val storedResult = result?.let(PluginBackgroundPolicy::prepareActionResult)
            val storedError =
                if (finalStatus == "ok" || finalStatus == "never") null
                else PluginBackgroundPolicy.prepareErrorMessage(errorMessage)
            val record =
                schedule(row)
                    .copy(
                        lastRunAtMs = atMs,
                        lastStatus = finalStatus,
                        lastDurationMs =
                            durationMs.coerceIn(0, PluginBackgroundPolicy.MAX_RUN_MS + 60_000L),
                        lastResultCaptured = true,
                        lastActionResultPresent = storedResult != null,
                        lastActionResult = storedResult?.value,
                        lastResultFormat = storedResult?.format,
                        lastResultTruncated = storedResult?.truncated == true,
                        lastResultRedacted = storedResult?.redacted == true,
                        lastErrorMessage = storedError?.message,
                        lastErrorTruncated = storedError?.truncated == true,
                        lastErrorRedacted = storedError?.redacted == true,
                    )
            validateRecord(record)
            root.getJSONObject("schedules").put(id, record.toJson().put("pluginId", id))
            save(root)
        }

    /**
     * Reserve one notification atomically with the rate decision; no second store instance can
     * bypass it.
     */
    fun reserveNotification(pluginId: String, atMs: Long): Boolean =
        synchronized(lock) {
            val id = PluginContract.id(pluginId)
            val root = loadRequired()
            val notifications =
                root.optJSONObject("notifications")
                    ?: JSONObject().also {
                        root.put("notifications", it)
                    }
            val old = notifications.optJSONArray(id) ?: JSONArray()
            val previous =
                (0 until old.length()).mapNotNull { index ->
                    old.optLong(index, -1).takeIf { value -> value > 0 }
                }
            if (!PluginBackgroundPolicy.notificationAllowed(previous, atMs)) return false
            val kept = JSONArray()
            previous.filter { it > atMs - 48L * 60 * 60 * 1000 }.forEach { kept.put(it) }
            kept.put(atMs)
            notifications.put(id, kept)
            save(root)
            true
        }

    /** Roll back a reservation only when posting the already-counted notification failed. */
    fun rollbackNotification(pluginId: String, atMs: Long) =
        synchronized(lock) {
            val id = PluginContract.id(pluginId)
            val root = loadRequired()
            val notifications = root.optJSONObject("notifications") ?: return
            val old = notifications.optJSONArray(id) ?: return
            val next = JSONArray()
            var removed = false
            for (index in 0 until old.length()) {
                val value = old.optLong(index, -1)
                if (!removed && value == atMs) {
                    removed = true
                    continue
                }
                if (value > 0) next.put(value)
            }
            if (removed) {
                notifications.put(id, next)
                save(root)
            }
        }

    fun notificationTimes(pluginId: String): List<Long> =
        synchronized(lock) {
            val id = PluginContract.id(pluginId)
            val rows =
                loadRequired().optJSONObject("notifications")?.optJSONArray(id)
                    ?: return emptyList()
            (0 until rows.length()).mapNotNull { index ->
                rows.optLong(index, -1).takeIf { value -> value > 0 }
            }
        }

    private fun schedule(row: JSONObject): ScheduleRecord {
        val resultPresent = row.optBoolean("lastActionResultPresent", false)
        require(!resultPresent || row.has("lastActionResult")) { "插件后台结果记录无效" }
        val record =
            normalizeRecord(
                ScheduleRecord(
                    pluginId = row.getString("pluginId"),
                    jobId = row.getInt("jobId"),
                    revision = row.getString("revision"),
                    action = row.getString("action"),
                    intervalMs = row.getLong("intervalMs"),
                    payload = row.optJSONObject("payload") ?: JSONObject(),
                    createdAtMs = row.optLong("createdAtMs", 0L),
                    generation = row.getString("generation"),
                    lastRunAtMs = row.optLong("lastRunAtMs", 0L),
                    lastStatus = PluginBackgroundPolicy.status(row.optString("lastStatus")),
                    lastDurationMs = row.optLong("lastDurationMs", -1L),
                    lastResultCaptured = row.optBoolean("lastResultCaptured", false),
                    lastActionResultPresent = resultPresent,
                    lastActionResult =
                        if (resultPresent) row.opt("lastActionResult") else null,
                    lastResultFormat = row.optString("lastResultFormat").ifEmpty { null },
                    lastResultTruncated = row.optBoolean("lastResultTruncated", false),
                    lastResultRedacted = row.optBoolean("lastResultRedacted", false),
                    lastErrorMessage = row.optString("lastErrorMessage").ifEmpty { null },
                    lastErrorTruncated = row.optBoolean("lastErrorTruncated", false),
                    lastErrorRedacted = row.optBoolean("lastErrorRedacted", false),
                ),
            )
        validateRecord(record)
        return record
    }

    /** Defense in depth for direct puts and old files: only normalized snapshots reach status. */
    private fun normalizeRecord(record: ScheduleRecord): ScheduleRecord {
        val currentStatus = PluginBackgroundPolicy.status(record.lastStatus)
        if (currentStatus == "never" || record.lastRunAtMs <= 0L) {
            return record.copy(
                lastStatus = currentStatus,
                lastResultCaptured = false,
                lastActionResultPresent = false,
                lastActionResult = null,
                lastResultFormat = null,
                lastResultTruncated = false,
                lastResultRedacted = false,
                lastErrorMessage = null,
                lastErrorTruncated = false,
                lastErrorRedacted = false,
            )
        }
        val hasResult = record.lastActionResultPresent || record.lastActionResult != null
        val storedResult =
            if (hasResult)
                PluginBackgroundPolicy.prepareActionResult(
                    PluginBackgroundPolicy.ActionResult(record.lastActionResult),
                )
            else null
        val storedError =
            if (currentStatus == "ok") null
            else PluginBackgroundPolicy.prepareErrorMessage(record.lastErrorMessage)
        val keepPrefixFormat =
            record.lastResultFormat == "json_prefix" && record.lastActionResult is String
        return record.copy(
            lastStatus = currentStatus,
            lastResultCaptured = record.lastResultCaptured || storedResult != null,
            lastActionResultPresent = storedResult != null,
            lastActionResult = storedResult?.value,
            lastResultFormat =
                if (keepPrefixFormat) "json_prefix" else storedResult?.format,
            lastResultTruncated =
                record.lastResultTruncated || storedResult?.truncated == true,
            lastResultRedacted = record.lastResultRedacted || storedResult?.redacted == true,
            lastErrorMessage = storedError?.message,
            lastErrorTruncated =
                record.lastErrorTruncated || storedError?.truncated == true,
            lastErrorRedacted = record.lastErrorRedacted || storedError?.redacted == true,
        )
    }

    private fun validateRecord(record: PluginBackgroundStore.ScheduleRecord) {
        PluginContract.id(record.pluginId)
        PluginBackgroundPolicy.action(record.action)
        PluginBackgroundPolicy.interval(record.intervalMs)
        PluginBackgroundPolicy.payload(record.payload)
        require(
            record.jobId > 0 &&
                record.revision.isNotEmpty() &&
                record.generation.length in 1..128 &&
                record.createdAtMs >= 0 &&
                record.lastRunAtMs >= 0 &&
                record.lastDurationMs in -1L..PluginBackgroundPolicy.MAX_RUN_MS + 60_000L,
        ) {
            "插件后台计划记录无效"
        }
        if (record.lastActionResultPresent) {
            require(
                PluginBackgroundPolicy.resultJson(record.lastActionResult)
                    .toByteArray(Charsets.UTF_8)
                    .size <= PluginBackgroundPolicy.MAX_ACTION_RESULT_BYTES,
            ) { "插件后台结果超过存储上限" }
            require(record.lastResultFormat in setOf("json", "json_prefix")) {
                "插件后台结果格式无效"
            }
        }
        require(
            record.lastErrorMessage == null ||
                record.lastErrorMessage.toByteArray(Charsets.UTF_8).size <=
                    PluginBackgroundPolicy.MAX_ACTION_ERROR_BYTES &&
                '\n' !in record.lastErrorMessage &&
                '\r' !in record.lastErrorMessage,
        ) { "插件后台错误记录无效" }
    }

    private fun loadRequired(): JSONObject {
        if (!file.isFile) {
            return JSONObject().put("schedules", JSONObject()).put("notifications", JSONObject())
        }
        val parsed = runCatching {
            JSONObject(file.readText())
        }
            .getOrElse {
                throw IllegalStateException("宿主插件后台存储损坏，已停止后台状态操作", it)
            }
        if (parsed.opt("schedules") !is JSONObject) {
            error("宿主插件后台存储结构损坏，已停止后台状态操作")
        }
        // A missing field may be initialized; a field that already exists but is not an object is
        // damaged history that must fail closed and keep the original bytes on disk.
        val notifications = parsed.opt("notifications")
        if (notifications == null) {
            parsed.put("notifications", JSONObject())
        } else if (notifications !is JSONObject) {
            error("宿主插件后台通知存储损坏，已停止后台状态操作")
        }
        val schedules = parsed.getJSONObject("schedules")
        schedules.keys().asSequence().forEach { key ->
            PluginContract.id(key)
            val row = schedules.getJSONObject(key)
            if (row.getString("pluginId") != key) error("插件后台计划身份损坏")
            schedule(row)
        }
        parsed.getJSONObject("notifications").keys().asSequence().forEach { key ->
            PluginContract.id(key)
            val rows =
                parsed.getJSONObject("notifications").optJSONArray(key)
                    ?: error("插件通知计数损坏，已停止后台状态操作")
            for (index in 0 until rows.length()) {
                if (rows.optLong(index, -1) <= 0) {
                    error("插件通知计数损坏，已停止后台状态操作")
                }
            }
        }
        return parsed
    }

    private fun save(next: JSONObject) {
        file.parentFile?.mkdirs()
        check(file.parentFile?.isDirectory == true) { "无法创建宿主插件后台目录" }
        val temp = File(file.parentFile, "background.new")
        FileOutputStream(temp).use { output ->
            output.write(next.toString().toByteArray())
            output.fd.sync()
        }
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    data class ScheduleRecord(
        val pluginId: String,
        val jobId: Int,
        val revision: String,
        val action: String,
        val intervalMs: Long,
        val payload: JSONObject,
        val createdAtMs: Long,
        val generation: String = java.util.UUID.randomUUID().toString(),
        val lastRunAtMs: Long = 0L,
        val lastStatus: String = "never",
        val lastDurationMs: Long = -1L,
        val lastResultCaptured: Boolean = false,
        val lastActionResultPresent: Boolean = false,
        val lastActionResult: Any? = null,
        val lastResultFormat: String? = null,
        val lastResultTruncated: Boolean = false,
        val lastResultRedacted: Boolean = false,
        val lastErrorMessage: String? = null,
        val lastErrorTruncated: Boolean = false,
        val lastErrorRedacted: Boolean = false,
    ) {
        fun toJson(): JSONObject {
            val json =
                JSONObject()
                    .put("jobId", jobId)
                    .put("revision", revision)
                    .put("action", action)
                    .put("intervalMs", intervalMs)
                    .put("payload", payload)
                    .put("createdAtMs", createdAtMs)
                    .put("generation", generation)
                    .put("lastRunAtMs", lastRunAtMs)
                    .put("lastStatus", lastStatus)
                    .put("lastDurationMs", lastDurationMs)
                    .put("lastResultCaptured", lastResultCaptured)
                    .put("lastActionResultPresent", lastActionResultPresent)
                    .put("lastResultFormat", lastResultFormat)
                    .put("lastResultTruncated", lastResultTruncated)
                    .put("lastResultRedacted", lastResultRedacted)
                    .put("lastErrorMessage", lastErrorMessage)
                    .put("lastErrorTruncated", lastErrorTruncated)
                    .put("lastErrorRedacted", lastErrorRedacted)
            if (lastActionResultPresent) {
                json.put("lastActionResult", lastActionResult ?: JSONObject.NULL)
            }
            return json
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        internal fun pathLock(path: String): Any = locks.getOrPut(path) { Any() }
    }
}

/** Pure background and notification policy; no JobScheduler side effects live here. */
object PluginBackgroundPolicy {
    const val BACKGROUND_CAPABILITY = "plugin.background"
    const val NOTIFICATION_CAPABILITY = "plugin.notification"
    const val MIN_INTERVAL_MS = 15 * 60 * 1000L
    const val MAX_INTERVAL_MS = 24 * 60 * 60 * 1000L
    const val MAX_PAYLOAD_BYTES = 16 * 1024
    const val MAX_ACTION_RESULT_BYTES = 4 * 1024
    const val MAX_ACTION_ERROR_BYTES = 512
    const val MAX_RUN_MS = 120_000L
    private const val MAX_ACTION_STRING_BYTES = MAX_ACTION_RESULT_BYTES
    private const val MAX_ACTION_DEPTH = 6
    private const val MAX_ACTION_ITEMS = 32
    private const val MAX_ACTION_NODES = 128
    private const val TRUNCATED = "[TRUNCATED]"
    val ALLOWED_STATUS =
        setOf("never", "ok", "rejected", "cancelled", "timeout", "preempted", "failed", "stale")

    const val MAX_NOTIFICATION_TITLE_CHARS = 60
    const val MAX_NOTIFICATION_BODY_CHARS = 480
    const val MIN_NOTIFICATION_INTERVAL_MS = 60_000L
    const val MAX_NOTIFICATIONS_PER_HOUR = 4
    const val MAX_NOTIFICATIONS_PER_DAY = 20

    private const val JOB_ID_BASE = 7_100_000
    private const val JOB_ID_RANGE = 99_999

    fun jobId(pluginId: String, occupiedJobIds: Set<Int> = emptySet()): Int {
        val id = PluginContract.id(pluginId)
        var result = JOB_ID_BASE + Math.floorMod(id.hashCode(), JOB_ID_RANGE)
        while (result in occupiedJobIds) {
            result = JOB_ID_BASE + Math.floorMod(result + 1 - JOB_ID_BASE, JOB_ID_RANGE)
        }
        return result
    }

    fun isHostJobId(jobId: Int): Boolean = jobId in JOB_ID_BASE until JOB_ID_BASE + JOB_ID_RANGE

    /**
     * Notification identity is a tag that is exactly the plugin id while the numeric notification
     * id stays the hash-based job id. Two plugins whose job ids collide therefore keep separate
     * notifications and platform `cancel(tag, id)` pairs always address one plugin, with no
     * persistent notification-id allocation table.
     */
    fun notificationTag(pluginId: String): String = PluginContract.id(pluginId)

    /**
     * Intent identity for a notification tap. `PendingIntent.filterEquals()` compares intent data,
     * so this URI only has to stay unique per plugin: it contains the validated plugin id and
     * nothing derived from the collision-prone hash.
     */
    fun notificationOpenUri(pluginId: String): String =
        "pd-plugin-notification://open/${PluginContract.id(pluginId)}"

    fun action(value: String): String {
        require(Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}").matches(value)) { "后台插件动作无效" }
        return value
    }

    fun intervalMinutes(value: Long): Long {
        val minuteMs = 60_000L
        require(value in MIN_INTERVAL_MS / minuteMs..MAX_INTERVAL_MS / minuteMs) {
            "后台间隔必须在 15 分钟至 24 小时之间"
        }
        return value * minuteMs
    }

    fun interval(value: Long): Long {
        require(value in MIN_INTERVAL_MS..MAX_INTERVAL_MS) {
            "后台周期必须在 15 分钟到 24 小时之间"
        }
        return value
    }

    fun payload(value: JSONObject): JSONObject {
        require(value.toString().toByteArray().size <= MAX_PAYLOAD_BYTES) {
            "后台任务 payload 超过 16 KiB"
        }
        return JSONObject(value.toString())
    }

    fun status(value: String): String {
        val normalized = if (value in ALLOWED_STATUS) value else "failed"
        return normalized.take(16)
    }

    fun scheduleValid(
        record: PluginBackgroundStore.ScheduleRecord,
        enabled: Boolean,
        revision: String,
        capabilities: Set<String>,
    ): Boolean {
        return enabled &&
            record.revision == revision &&
            BACKGROUND_CAPABILITY in capabilities &&
            runCatching {
                action(record.action)
                interval(record.intervalMs)
                payload(record.payload)
            }
                .isSuccess
    }

    fun notification(title: String, body: String) {
        require(title.isNotEmpty() && title.length <= MAX_NOTIFICATION_TITLE_CHARS) {
            "通知标题长度必须在 1–$MAX_NOTIFICATION_TITLE_CHARS 字内"
        }
        require(body.isNotEmpty() && body.length <= MAX_NOTIFICATION_BODY_CHARS) {
            "通知正文长度必须在 1–$MAX_NOTIFICATION_BODY_CHARS 字内"
        }
        require(
            title.toByteArray(Charsets.UTF_8).size <= 512 &&
                body.toByteArray(Charsets.UTF_8).size <= 2048,
        ) {
            "通知标题或正文超过字节上限"
        }
    }

    fun notificationAllowed(previous: List<Long>, nowMs: Long): Boolean {
        val sorted = previous.sorted()
        if (sorted.isNotEmpty() && nowMs - sorted.last() < MIN_NOTIFICATION_INTERVAL_MS)
            return false
        if (sorted.count { nowMs - it < 60 * 60 * 1000L } >= MAX_NOTIFICATIONS_PER_HOUR)
            return false
        if (sorted.count { nowMs - it < 24L * 60 * 60 * 1000 } >= MAX_NOTIFICATIONS_PER_DAY)
            return false
        return true
    }

    /** Raw explicit action result. It is sanitized before entering ScheduleRecord persistence. */
    data class ActionResult(val value: Any?)

    data class StoredActionResult(
        val value: Any,
        val format: String,
        val truncated: Boolean,
        val redacted: Boolean,
    )

    data class StoredErrorMessage(
        val message: String?,
        val truncated: Boolean,
        val redacted: Boolean,
    )

    private data class TextSnapshot(
        val value: String,
        val truncated: Boolean,
        val redacted: Boolean,
    )

    private class SanitizeState {
        var nodes = 0
        var truncated = false
        var redacted = false
    }

    private val sensitiveText =
        Regex(
            "(?i)([\\\"']?(?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|cookie|authorization|password|passwd|secret|api[_-]?key)[\\\"']?\\s*[:=]\\s*)(bearer\\s+[^\\s,;&}]+|\\\"[^\\\"]*\\\"|'[^']*'|[^,;&}\\r\\n]+)",
        )
    private val bearerText = Regex("(?i)\\bbearer\\s+[^\\s,;&}]+")
    private val userInfoText = Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@")
    private val errorUrlText = Regex("(?i)https?://[^\\s,;]+")
    private val rawErrorText =
        Regex(
            "(?i)([{}]|^\\s*\\[(?=[\\s\\\"0-9tfn-])|\\bstack ?trace\\b|\\b(?:raw |original )?requests?\\b[\\\"']?\\s*[:=]|\\bheaders?\\b[\\\"']?\\s*[:=]|\\blogs?\\b[\\\"']?\\s*[:=]|\\btool logs?\\b|\\bmodel logs?\\b|\\b(?:get|post|put|patch|delete|head|options)\\s+https?://)",
        )

    fun explicitActionResult(invoked: JSONObject): ActionResult? =
        if (invoked.has("result")) ActionResult(invoked.opt("result")) else null

    fun prepareActionResult(input: ActionResult): StoredActionResult {
        val state = SanitizeState()
        val value = sanitizeValue(input.value, 0, state)
        val json = resultJson(value)
        if (json.toByteArray(Charsets.UTF_8).size > MAX_ACTION_RESULT_BYTES) {
            // A valid string summary is preferable to cutting a JSON object into invalid JSON.
            return StoredActionResult(jsonPrefix(json), "json_prefix", true, state.redacted)
        }
        return StoredActionResult(value, "json", state.truncated, state.redacted)
    }

    fun prepareErrorMessage(message: String?): StoredErrorMessage {
        if (message.isNullOrBlank()) return StoredErrorMessage(null, false, false)
        if (rawErrorText.containsMatchIn(message)) {
            // Structured request dumps, headers and stack/log payloads never become error storage.
            return StoredErrorMessage(null, false, true)
        }
        val firstLine = message.lineSequence().firstOrNull().orEmpty()
        val withoutUrls = errorUrlText.replace(firstLine, "[REDACTED_URL]")
        val text = sanitizeText(withoutUrls, MAX_ACTION_ERROR_BYTES)
        return StoredErrorMessage(
            text.value.ifBlank { null },
            text.truncated || firstLine.length < message.length,
            text.redacted || withoutUrls != firstLine,
        )
    }

    fun resultJson(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject, is JSONArray -> value.toString()
            is String -> JSONObject.quote(value)
            is Number -> JSONObject.numberToString(value)
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

    private fun sanitizeValue(value: Any?, depth: Int, state: SanitizeState): Any {
        state.nodes++
        if (state.nodes > MAX_ACTION_NODES || depth > MAX_ACTION_DEPTH) {
            state.truncated = true
            return TRUNCATED
        }
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is String -> {
                val text = sanitizeText(value, MAX_ACTION_STRING_BYTES)
                state.truncated = state.truncated || text.truncated
                state.redacted = state.redacted || text.redacted
                text.value
            }
            is Boolean -> value
            is Number -> {
                if (runCatching { JSONObject.numberToString(value) }.isSuccess) value
                else {
                    state.truncated = true
                    value.toString()
                }
            }
            is JSONObject -> sanitizeObject(value, depth, state)
            is JSONArray -> sanitizeArray(value, depth, state)
            else -> {
                state.truncated = true
                TRUNCATED
            }
        }
    }

    private fun sanitizeObject(value: JSONObject, depth: Int, state: SanitizeState): JSONObject {
        val output = JSONObject()
        var count = 0
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (blockedResultKey(key)) {
                state.redacted = true
                continue
            }
            if (count >= MAX_ACTION_ITEMS) {
                state.truncated = true
                break
            }
            if (key.toByteArray(Charsets.UTF_8).size > 128) {
                state.truncated = true
                continue
            }
            output.put(key, sanitizeValue(value.opt(key), depth + 1, state))
            count++
        }
        return output
    }

    private fun sanitizeArray(value: JSONArray, depth: Int, state: SanitizeState): JSONArray {
        val output = JSONArray()
        for (index in 0 until value.length()) {
            if (index >= MAX_ACTION_ITEMS) {
                state.truncated = true
                break
            }
            output.put(sanitizeValue(value.opt(index), depth + 1, state))
        }
        return output
    }

    private fun blockedResultKey(key: String): Boolean {
        val normalized = key.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        if (
            normalized.contains("token") ||
                normalized.contains("cookie") ||
                normalized.contains("authorization") ||
                normalized.contains("password") ||
                normalized.contains("passwd") ||
                normalized.contains("secret") ||
                normalized.contains("apikey") ||
                normalized.contains("credential") ||
                normalized.contains("passphrase") ||
                normalized.contains("privatekey") ||
                normalized in setOf("auth", "authentication", "pwd")
        )
            return true
        return normalized in
            setOf(
                "request",
                "requests",
                "rawrequest",
                "originalrequest",
                "requestbody",
                "requestjson",
                "headers",
                "requestheaders",
                "responseheaders",
                "httpheaders",
                "log",
                "logs",
                "rawlog",
                "rawlogs",
                "toolcalls",
                "toolhistory",
                "modelhistory",
                "chathistory",
                "messages",
                "prompt",
                "prompts",
            ) ||
            normalized.endsWith("request") ||
            normalized.endsWith("requests") ||
            normalized.endsWith("headers") ||
            normalized.endsWith("log") ||
            normalized.endsWith("logs") ||
            normalized.contains("toolcall")
    }

    private fun sanitizeText(value: String, maxBytes: Int): TextSnapshot {
        var sanitized = sensitiveText.replace(value) { it.groupValues[1] + "\"[REDACTED]\"" }
        sanitized = bearerText.replace(sanitized, "Bearer [REDACTED]")
        sanitized = userInfoText.replace(sanitized, "$1[REDACTED]@")
        val redacted = sanitized != value
        if (sanitized.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return TextSnapshot(sanitized, false, redacted)
        }
        return TextSnapshot(utf8Prefix(sanitized, maxBytes - 3) + "…", true, redacted)
    }

    private fun utf8Prefix(value: String, maxBytes: Int): String {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            val width = Character.charCount(point)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            index += width
        }
        return value.substring(0, index)
    }

    private fun jsonPrefix(value: String): String {
        val output = StringBuilder()
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            val width = Character.charCount(point)
            val candidate = output.toString() + String(Character.toChars(point)) + "…"
            if (JSONObject.quote(candidate).toByteArray(Charsets.UTF_8).size > MAX_ACTION_RESULT_BYTES)
                break
            output.append(String(Character.toChars(point)))
            index += width
        }
        return output.append("…").toString()
    }

    /** Actual explicit result plus the bounded host receipt; legacy runs remain distinguishable. */
    fun lastResult(record: PluginBackgroundStore.ScheduleRecord): JSONObject? {
        if (record.lastStatus == "never" || record.lastRunAtMs <= 0L) return null
        val receipt =
            JSONObject()
                .put("status", status(record.lastStatus))
                .put("ok", record.lastStatus == "ok")
                .put("generation", record.generation)
                .put("finishedAtMs", record.lastRunAtMs)
                .put("durationMs", record.lastDurationMs)
        if (record.lastActionResultPresent) {
            val stored = prepareActionResult(ActionResult(record.lastActionResult))
            return receipt
                .put("kind", "action_result")
                .put("hasActionResult", true)
                .put("result", stored.value)
                .put(
                    "format",
                    if (record.lastResultFormat == "json_prefix" && stored.value is String)
                        "json_prefix"
                    else stored.format,
                )
                .put("truncated", record.lastResultTruncated || stored.truncated)
                .put("redacted", record.lastResultRedacted || stored.redacted)
        }
        return receipt
            .put("kind", "execution_receipt")
            .put("hasActionResult", false)
            .put(
                "resultUnavailableReason",
                if (record.lastResultCaptured) "not_returned" else "legacy_or_not_recorded",
            )
    }

    /** Fixed error code with an optional bounded, redacted message from this execution. */
    fun lastError(record: PluginBackgroundStore.ScheduleRecord): JSONObject? {
        val current = status(record.lastStatus)
        if (current == "never" || current == "ok" || record.lastRunAtMs <= 0L) return null
        val stored = prepareErrorMessage(record.lastErrorMessage)
        val fallback =
            when (current) {
                "rejected" -> "后台动作被宿主拒绝"
                "cancelled" -> "后台动作已取消"
                "timeout" -> "后台动作执行超时"
                "preempted" -> "系统提前停止了本次后台执行"
                "stale" -> "后台计划已失效"
                else -> "后台动作执行失败"
            }
        return JSONObject()
            .put("code", current)
            .put("message", stored.message ?: fallback)
            .put("messageSource", if (stored.message != null) "recorded" else "fallback")
            .put("truncated", record.lastErrorTruncated || stored.truncated)
            .put("redacted", record.lastErrorRedacted || stored.redacted)
    }

    /**
     * JobScheduler exposes no exact next firing timestamp. This is only a local estimate based on
     * the latest terminal receipt (or creation time), clamped to the status snapshot time when the
     * estimated window is already due. It is never a promise of exact or immediate execution.
     */
    fun nextRunAt(
        record: PluginBackgroundStore.ScheduleRecord,
        systemScheduled: Boolean,
        nowMs: Long,
    ): Long? {
        if (!systemScheduled) return null
        val basis = if (record.lastRunAtMs > 0L) record.lastRunAtMs else record.createdAtMs
        if (basis < 0L) return null
        val earliest =
            if (Long.MAX_VALUE - basis < record.intervalMs) Long.MAX_VALUE
            else basis + record.intervalMs
        return maxOf(nowMs.coerceAtLeast(0L), earliest)
    }

    fun statusJson(
        record: PluginBackgroundStore.ScheduleRecord?,
        systemScheduled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): JSONObject {
        if (record == null)
            return JSONObject()
                .put("scheduled", false)
                .put("lastStatus", "never")
                .put("lastResult", JSONObject.NULL)
                .put("lastError", JSONObject.NULL)
                .put("nextRunAt", JSONObject.NULL)
                .put("nextRunAtEstimated", true)
        val next = nextRunAt(record, systemScheduled, nowMs)
        return JSONObject()
            .put("scheduled", systemScheduled)
            .put("jobId", record.jobId)
            .put("action", record.action)
            .put("intervalMs", record.intervalMs)
            .put("payloadBytes", record.payload.toString().toByteArray().size)
            .put("revision", record.revision)
            .put("lastRunAtMs", record.lastRunAtMs)
            .put("lastStatus", record.lastStatus)
            .put("lastDurationMs", record.lastDurationMs)
            .put("lastResult", lastResult(record) ?: JSONObject.NULL)
            .put("lastError", lastError(record) ?: JSONObject.NULL)
            .put("nextRunAt", next ?: JSONObject.NULL)
            .put("nextRunAtEstimated", true)
            .put("maxRunMs", MAX_RUN_MS)
    }
}
