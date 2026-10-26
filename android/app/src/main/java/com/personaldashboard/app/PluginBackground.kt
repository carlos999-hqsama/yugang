package com.personaldashboard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/** Native periodic work for active plugin revisions. */
object PluginBackground {
    private const val CHANNEL = "plugin-background"

    @Synchronized
    fun schedule(context: Context, pluginId: String, request: JSONObject): JSONObject {
        val id = PluginContract.id(pluginId)
        val plugins = PluginPlatform.store(context)
        val bundle = plugins.activeBundle(id)
        require(PluginBackgroundPolicy.BACKGROUND_CAPABILITY in PluginContract.caps(bundle)) {
            "插件未声明 ${PluginBackgroundPolicy.BACKGROUND_CAPABILITY}"
        }
        val revision = bundle.getString("_revision")
        val action = PluginBackgroundPolicy.action(request.getString("action"))
        val interval =
            PluginBackgroundPolicy.interval(
                if (request.has("intervalMs")) request.getLong("intervalMs")
                else PluginBackgroundPolicy.intervalMinutes(request.getLong("intervalMinutes")),
            )
        val payload =
            PluginBackgroundPolicy.payload(request.optJSONObject("payload") ?: JSONObject())
        val scheduler =
            context.getSystemService(JobScheduler::class.java) ?: error("此设备没有 JobScheduler")
        val backgroundStore = PluginBackgroundStore(context)
        var oldJobId: Int? = null

        run {
            val foreignJobIds =
                scheduler.allPendingJobs
                    .filter { it.service.className != PluginBackgroundJob::class.java.name }
                    .map { it.id }
                    .toSet()
            val jobId =
                PluginBackgroundPolicy.jobId(
                    id,
                    backgroundStore.all().filterNot { it.pluginId == id }.map { it.jobId }.toSet() +
                        foreignJobIds,
                )
            oldJobId = backgroundStore.get(id)?.jobId
            // Persist before contacting JobScheduler. A storage error therefore cannot strand an
            // accepted system job without a recoverable host record.
            backgroundStore.put(
                PluginBackgroundStore.ScheduleRecord(
                    pluginId = id,
                    jobId = jobId,
                    revision = revision,
                    action = action,
                    intervalMs = interval,
                    payload = payload,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            // A revision replacement invalidates an action already running for the old bundle.
            PluginBackgroundCoordinator.cancel(id)
            val info = jobInfo(context, backgroundStore.get(id)!!)
            try {
                val result = scheduler.schedule(info)
                check(result == JobScheduler.RESULT_SUCCESS) {
                    "系统未接受插件后台任务；计划已保留，App 恢复时会重试对齐"
                }
            } catch (e: Exception) {
                val old = oldJobId
                if (old != null) scheduler.cancel(old)
                throw e
            }
            if (oldJobId != null && oldJobId != jobId) scheduler.cancel(oldJobId)
        }
        return PluginBackgroundPolicy.statusJson(backgroundStore.get(id), true)
            .put("accepted", true)
            .put("systemMinimumIntervalMs", PluginBackgroundPolicy.MIN_INTERVAL_MS)
    }

    @Synchronized
    fun status(context: Context, pluginId: String): JSONObject {
        val id = PluginContract.id(pluginId)
        val record = PluginBackgroundStore(context).get(id)
        val pending =
            record?.let { item ->
                context.getSystemService(JobScheduler::class.java)?.allPendingJobs?.any {
                    it.id == item.jobId && it.extras.getString("generation") == item.generation
                }
            } ?: false
        return PluginBackgroundPolicy.statusJson(record, pending)
    }

    @Synchronized
    fun cancel(context: Context, pluginId: String, generation: String? = null): JSONObject {
        val id = PluginContract.id(pluginId)
        val store = PluginBackgroundStore(context)
        val record = run {
            val current = store.get(id)
            if (generation != null && current?.generation != generation)
                return JSONObject().put("cancelled", false)
            PluginBackgroundCoordinator.cancel(id, generation)
            current?.let { context.getSystemService(JobScheduler::class.java)?.cancel(it.jobId) }
            store.remove(id)
            current
        }
        return JSONObject().put("cancelled", true).put("hadSchedule", record != null)
    }

    /**
     * Legacy calls without op still post. Status is synchronous and side-effect free; runtime
     * permission requests must use [requestNotificationPermission] so Android's result is async.
     */
    fun notify(
        context: Context,
        pluginId: String,
        request: JSONObject,
        preview: Boolean = false,
    ): JSONObject {
        val id = requireNotificationCapability(context, pluginId)
        return when (PluginNotificationPermissionPolicy.operation(request)) {
            "status" -> PluginNotificationPermission.status(context, preview)
            "send" -> postNotification(context, id, request)
            "request" ->
                throw IllegalStateException("通知权限申请必须使用宿主前台异步授权入口")
            else -> throw IllegalArgumentException("不支持的通知操作")
        }
    }

    /**
     * MainActivity/page-only entry. PluginPlatform must validate the immutable revision before
     * calling this and must not hold its lifecycle lock while waiting for or delivering callback.
     */
    fun requestNotificationPermission(
        activity: MainActivity,
        pluginId: String,
        request: JSONObject,
        preview: Boolean = false,
        callback: (JSONObject) -> Unit,
    ) {
        val id = requireNotificationCapability(activity, pluginId)
        require(PluginNotificationPermissionPolicy.operation(request) == "request") {
            "通知权限申请操作无效"
        }
        PluginNotificationPermission.request(activity, preview) { result ->
            callback(result.put("pluginId", id))
        }
    }

    private fun requireNotificationCapability(context: Context, pluginId: String): String {
        val id = PluginContract.id(pluginId)
        val plugins = PluginPlatform.store(context)
        val capabilities = PluginContract.caps(plugins.activeBundle(id))
        require(PluginBackgroundPolicy.NOTIFICATION_CAPABILITY in capabilities) {
            "插件未声明 ${PluginBackgroundPolicy.NOTIFICATION_CAPABILITY}"
        }
        return id
    }

    private fun postNotification(context: Context, id: String, request: JSONObject): JSONObject {
        val title = request.getString("title")
        val body = request.getString("body")
        PluginBackgroundPolicy.notification(title, body)
        val manager =
            context.getSystemService(NotificationManager::class.java)
                ?: return JSONObject().put("shown", false).put("reason", "notification_unavailable")
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return JSONObject().put("shown", false).put("reason", "notification_permission")
        }
        if (!manager.areNotificationsEnabled()) {
            return JSONObject().put("shown", false).put("reason", "notification_permission")
        }

        val store = PluginBackgroundStore(context)
        val now = System.currentTimeMillis()
        if (!store.reserveNotification(id, now)) {
            return JSONObject().put("shown", false).put("reason", "rate_limited")
        }
        try {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "插件后台通知", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    PluginBackgroundPolicy.jobId(id),
                    openIntent(context, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            // Tag = exact plugin id, numeric id = job id: colliding hash-based job ids stay
            // separate notifications, and platform cancel(tag, id) always addresses one plugin.
            manager.notify(
                PluginBackgroundPolicy.notificationTag(id),
                PluginBackgroundPolicy.jobId(id),
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.notification_whale)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
        } catch (e: Exception) {
            store.rollbackNotification(id, now)
            return JSONObject().put("shown", false).put("reason", "notification_failed")
        }
        return JSONObject().put("shown", true)
    }

    /**
     * Call on app resume/start: remove stale jobs and restore records missing after a process
     * restart.
     *
     * A plan is only dropped when the plugin registry proves that its plugin is missing or
     * disabled, or when a readable active bundle proves a revision/capability mismatch. Unreadable
     * input keeps the record and is reported through "retained"/"errors".
     */
    @Synchronized
    fun reconcile(context: Context): JSONObject {
        val store = PluginBackgroundStore(context)
        val plugins = PluginPlatform.store(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        var cancelled = 0
        var restored = 0
        var retained = 0
        var errors = 0
        var error = ""
        // Only the registry itself may prove that a plugin is gone or disabled. A registry that
        // cannot be parsed keeps every plan in place and is reported, never read as "missing".
        val installed: Map<String, Boolean>? =
            try {
                val rows = plugins.list().getJSONArray("plugins")
                (0 until rows.length()).associate { index ->
                    val manifest = rows.getJSONObject(index)
                    manifest.getString("id") to manifest.getBoolean("enabled")
                }
            } catch (_: Exception) {
                errors++
                error = "插件注册表不可判定，已保留全部后台计划"
                null
            }
        for (snapshot in store.all()) {
            val pluginId = snapshot.pluginId
            val current = store.get(pluginId)
            if (current == null) {
                // The record disappeared meanwhile; its system job is cancelled as an orphan below.
                PluginBackgroundCoordinator.cancel(pluginId)
                cancelled++
                continue
            }
            if (installed == null) {
                retained++
                continue
            }
            if (installed[pluginId] != true) {
                cancelRecord(scheduler, store, current)
                cancelled++
                continue
            }
            val valid =
                try {
                    val bundle = plugins.activeBundle(pluginId)
                    PluginBackgroundPolicy.scheduleValid(
                        current,
                        enabled = true,
                        revision = bundle.getString("_revision"),
                        capabilities = PluginContract.caps(bundle),
                    )
                } catch (_: Exception) {
                    // An unreadable active bundle is not proof that the plugin disappeared.
                    errors++
                    error = "插件 $pluginId 的当前版本不可判定，已保留后台计划"
                    retained++
                    continue
                }
            if (!valid) {
                cancelRecord(scheduler, store, current)
                cancelled++
                continue
            }
            if (
                scheduler?.allPendingJobs?.none {
                    it.id == current.jobId &&
                        it.extras.getString("generation") == current.generation
                } == true
            ) {
                if (scheduler.schedule(jobInfo(context, current)) == JobScheduler.RESULT_SUCCESS)
                    restored++
                else {
                    retained++
                    errors++
                    error = "系统暂未接受后台计划，已保留记录等待下次恢复"
                }
            }
        }
        scheduler
            ?.allPendingJobs
            ?.filter { job ->
                PluginBackgroundPolicy.isHostJobId(job.id) &&
                    job.service.className == PluginBackgroundJob::class.java.name &&
                    store.all().none { record ->
                        record.jobId == job.id &&
                            record.generation == job.extras.getString("generation")
                    }
            }
            ?.forEach { job ->
                scheduler.cancel(job.id)
                cancelled++
            }
        return JSONObject()
            .put("cancelled", cancelled)
            .put("restored", restored)
            .put("retained", retained)
            .put("errors", errors)
            .apply { if (error.isNotEmpty()) put("error", error) }
    }

    private fun cancelRecord(
        scheduler: JobScheduler?,
        store: PluginBackgroundStore,
        record: PluginBackgroundStore.ScheduleRecord,
    ) {
        scheduler?.cancel(record.jobId)
        store.remove(record.pluginId)
        PluginBackgroundCoordinator.cancel(record.pluginId)
    }

    /**
     * A notification tap may only identify a plugin; MainActivity decides how to open it. The data
     * URI carries exactly the validated plugin id, so `PendingIntent.filterEquals()` distinguishes
     * two plugins even when their job ids collide, while extras stay unchanged.
     */
    fun openIntent(context: Context, pluginId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction("com.personaldashboard.app.OPEN_PLUGIN")
            .setData(Uri.parse(PluginBackgroundPolicy.notificationOpenUri(pluginId)))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("pluginId", PluginContract.id(pluginId))

    internal fun execute(
        context: Context,
        record: PluginBackgroundStore.ScheduleRecord,
        done: () -> Unit,
    ): Boolean {
        val app = context.applicationContext
        return PluginBackgroundCoordinator.start(record.pluginId, record.generation) { cancellation
            ->
            val started = SystemClock.elapsedRealtime()
            val store = PluginBackgroundStore(app)
            var status = "failed"
            var actionResult: PluginBackgroundPolicy.ActionResult? = null
            var actionError: String? = null
            try {
                val plugins = PluginPlatform.store(app)
                val bundle = plugins.activeBundle(record.pluginId)
                check(store.get(record.pluginId)?.generation == record.generation) { "stale" }
                check(
                    PluginBackgroundPolicy.scheduleValid(
                        record,
                        enabled = true,
                        revision = bundle.getString("_revision"),
                        capabilities = PluginContract.caps(bundle),
                    ),
                ) {
                    "stale"
                }
                val invoked =
                    PluginPlatform.invoke(
                        app,
                        record.pluginId,
                        record.action,
                        record.payload,
                        cancellation,
                        expectedRevision = record.revision,
                    )
                // Only the action's explicit result is eligible for the bounded status snapshot.
                // view/state and every other return field remain outside background persistence.
                actionResult = PluginBackgroundPolicy.explicitActionResult(invoked)
                status = "ok"
            } catch (e: Exception) {
                status =
                    when {
                        e is IllegalStateException && e.message == "stale" -> "stale"
                        cancellation.cancelled -> "cancelled"
                        e is java.util.concurrent.TimeoutException -> "timeout"
                        e is IllegalArgumentException -> "rejected"
                        else -> "failed"
                    }
                actionError = e.message
            } finally {
                runCatching {
                    PluginBackgroundCoordinator.finish(record.pluginId, cancellation, status) {
                        finalStatus ->
                        store.markRun(
                            record.pluginId,
                            System.currentTimeMillis(),
                            finalStatus,
                            SystemClock.elapsedRealtime() - started,
                            generation = record.generation,
                            result = actionResult,
                            errorMessage = actionError,
                        )
                    }
                }
                if (status == "stale") cancel(app, record.pluginId, record.generation)
                cancellation.cancel()
                done()
            }
        }
    }

    private fun jobInfo(context: Context, record: PluginBackgroundStore.ScheduleRecord): JobInfo =
        JobInfo.Builder(record.jobId, ComponentName(context, PluginBackgroundJob::class.java))
            .setExtras(
                android.os.PersistableBundle().apply { putString("generation", record.generation) },
            )
            .setPeriodic(record.intervalMs)
            .setPersisted(true)
            .setBackoffCriteria(
                JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS,
                JobInfo.BACKOFF_POLICY_LINEAR,
            )
            .build()
}

/** One active background action per plugin and a host-wide 120-second runtime deadline. */
internal object PluginBackgroundCoordinator {
    private data class Active(
        val generation: String,
        val cancellation: PluginCancellation,
        var preempted: Boolean = false,
    )

    private val active = ConcurrentHashMap<String, Active>()
    private val main = Handler(Looper.getMainLooper())

    fun start(pluginId: String, generation: String, block: (PluginCancellation) -> Unit): Boolean {
        val id = PluginContract.id(pluginId)
        val cancellation = PluginCancellation()
        val entry = Active(generation, cancellation)
        if (active.putIfAbsent(id, entry) != null) return false
        val stop = Runnable { cancellation.cancel() }
        main.postDelayed(stop, PluginBackgroundPolicy.MAX_RUN_MS)
        val thread =
            Thread(
                {
                    try {
                        block(cancellation)
                    } finally {
                        main.removeCallbacks(stop)
                        active.remove(id, entry)
                    }
                },
                "pd-plugin-background",
            )
        return try {
            thread.start()
            true
        } catch (_: Exception) {
            main.removeCallbacks(stop)
            active.remove(id, entry)
            false
        }
    }

    fun cancel(pluginId: String, generation: String? = null) {
        active[PluginContract.id(pluginId)]?.let {
            if (generation == null || it.generation == generation) it.cancellation.cancel()
        }
    }

    /** Serialize the system stop receipt with this run's final write, not later periodic runs. */
    fun preempt(pluginId: String, generation: String, write: () -> Unit) {
        val entry = active[PluginContract.id(pluginId)]?.takeIf { it.generation == generation }
        if (entry == null) {
            write()
            return
        }
        synchronized(entry.cancellation) {
            entry.preempted = true
            write()
            entry.cancellation.cancel()
        }
    }

    fun finish(
        pluginId: String,
        cancellation: PluginCancellation,
        status: String,
        write: (String) -> Unit,
    ) {
        synchronized(cancellation) {
            val entry =
                active[PluginContract.id(pluginId)]?.takeIf { it.cancellation === cancellation }
            write(if (entry?.preempted == true) "preempted" else status)
        }
    }
}
