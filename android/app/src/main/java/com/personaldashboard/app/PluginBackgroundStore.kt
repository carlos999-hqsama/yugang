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
            validateRecord(record)
            val root = loadRequired()
            root
                .getJSONObject("schedules")
                .put(record.pluginId, record.toJson().put("pluginId", record.pluginId))
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
            val record =
                schedule(row)
                    .copy(
                        lastRunAtMs = atMs,
                        lastStatus = PluginBackgroundPolicy.status(status),
                        lastDurationMs =
                            durationMs.coerceIn(0, PluginBackgroundPolicy.MAX_RUN_MS + 60_000L),
                    )
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
        val record =
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
            )
        validateRecord(record)
        return record
    }

    private fun validateRecord(record: PluginBackgroundStore.ScheduleRecord) {
        PluginContract.id(record.pluginId)
        PluginBackgroundPolicy.action(record.action)
        PluginBackgroundPolicy.interval(record.intervalMs)
        PluginBackgroundPolicy.payload(record.payload)
        require(
            record.jobId > 0 && record.revision.isNotEmpty() && record.generation.isNotEmpty(),
        ) {
            "插件后台计划记录无效"
        }
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
    ) {
        fun toJson(): JSONObject =
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
    const val MAX_RUN_MS = 120_000L
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

    fun statusJson(
        record: PluginBackgroundStore.ScheduleRecord?,
        systemScheduled: Boolean,
    ): JSONObject {
        if (record == null) return JSONObject().put("scheduled", false).put("lastStatus", "never")
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
            .put("maxRunMs", MAX_RUN_MS)
    }
}
