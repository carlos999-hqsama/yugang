package com.personaldashboard.app

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import org.json.JSONObject

/**
 * Host-owned background transfer service. The data-only public API accepts URLs, but status
 * responses never include URLs, headers, tokens, ETags or raw server/exception messages.
 */
object PluginTransfers {
    internal const val EXTRA_PLUGIN = "com.personaldashboard.app.transfer.plugin"
    internal const val EXTRA_TASK_ID = "com.personaldashboard.app.transfer.task"
    private const val STORE_DIRECTORY = "plugin-transfers"
    private const val TEMP_DIRECTORY = "plugin-transfers"
    private const val TASK_KEY_SEPARATOR = "\u0000"
    private const val DOWNLOAD_CHUNK = 64 * 1024
    private const val UPLOAD_CHUNK = 64 * 1024

    private val liveByJob = ConcurrentHashMap<Int, LiveJob>()
    private val liveByTask = ConcurrentHashMap<String, LiveJob>()
    private val workerIds = AtomicInteger(1)
    private val executor =
        ThreadPoolExecutor(
            PluginTransferPolicy.MAX_ACTIVE_TRANSFERS,
            PluginTransferPolicy.MAX_ACTIVE_TRANSFERS,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(PluginTransferPolicy.MAX_EXECUTOR_QUEUE),
            ThreadFactory { runnable ->
                Thread(runnable, "plugin-transfer-${workerIds.getAndIncrement()}").apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

    private val http =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    private val taskFences=Array(64){Any()}
    internal fun taskFence(plugin:String,taskId:String):Any=taskFences[(taskKey(plugin,taskId).hashCode().ushr(1))%taskFences.size]

    fun call(
        context: Context,
        plugin: String,
        revision: String,
        args: JSONObject,
        cancellation: PluginCancellation,
        userInitiated: Boolean = false,
    ): JSONObject {
        val app = context.applicationContext
        val pluginId = PluginContract.id(plugin)
        PluginTransferPolicy.validateRevision(revision)
        PluginTransferPolicy.requireUserInitiated(app, userInitiated)
        cancellation.check()
        require(args.toString().toByteArray(Charsets.UTF_8).size <= PluginCapabilities.MAX_ARGUMENT_BYTES) {
            "传输参数过大"
        }
        val operation = args.optString("op")
        PluginCapabilities.operation("plugin.transfer", operation, false)
        requireRevision(app, pluginId, revision)
        return when (operation) {
            "download" -> PluginPlatform.withActiveRevision(app,pluginId,revision){cancellation.check();enqueueDownload(app, pluginId, revision, args, cancellation, userInitiated)}
            "upload" -> PluginPlatform.withActiveRevision(app,pluginId,revision){cancellation.check();enqueueUpload(app, pluginId, revision, args, cancellation, userInitiated)}
            "status" -> taskStatus(app, pluginId, revision, PluginTransferPolicy.parseTaskId(args))
            "list" -> listTasks(app, pluginId, revision)
            "cancel" -> cancelTask(app, pluginId, revision, PluginTransferPolicy.parseTaskId(args))
            "resume" -> resumeTask(app, pluginId, revision, args, cancellation, userInitiated)
            else -> error("不支持的传输操作")
        }
    }

    /** Cancels every task owned by this plugin. remove=true is the uninstall cleanup path. */
    fun cancelPlugin(context: Context, id: String, remove: Boolean = false) {
        val app = context.applicationContext
        val plugin = PluginContract.id(id)
        val repository = Repository(app, plugin)
        liveByTask.values.filter{it.plugin==plugin}.toList().forEach{it.stop(discardPartial=true)}
        if (remove) {
            app.getSystemService(JobScheduler::class.java)?.allPendingJobs.orEmpty().filter{it.service.className==PluginTransferJob::class.java.name&&it.extras.getString(EXTRA_PLUGIN)==plugin}.forEach{cancelScheduledJob(app,it.id)}
            repository.remove()
            val temp=tempDirectory(app,plugin);check(!temp.exists()||temp.deleteRecursively()){"传输临时文件清理未完成"}
            return
        }
        val cancelled = runCatching { repository.cancelAllActive() }.getOrDefault(emptyList())
        for (row in cancelled) {
            cancelScheduledJob(app, row.optInt("jobId", 0))
            if (row.optString("operation") == PluginTransferPolicy.Operation.DOWNLOAD.wire) {
                deleteTaskTemp(app, plugin, row.optString("taskId"))
            }
        }
    }

    /** Re-schedules only queued work and resumable downloads. Uploads are never replayed here. */
    fun recover(context: Context) {
        val app = context.applicationContext
        val root = File(app.filesDir, STORE_DIRECTORY)
        val files = root.listFiles()?.filter { it.isFile && (it.name.endsWith(".bin")||it.name.endsWith(".bin.bak")) } ?: return
        for (plugin in files.map{it.name.removeSuffix(".bak").removeSuffix(".bin")}.distinct()) {
            if (runCatching { PluginContract.id(plugin) }.isFailure) continue
            val repository = Repository(app, plugin)
            val tasks = runCatching { repository.all() }.getOrDefault(emptyList())
            for (row in tasks) {
                val state = PluginTransferPolicy.State.from(row.optString("state")) ?: continue
                val taskId = row.optString("taskId")
                val revision = row.optString("revision")
                if (taskId.isBlank() || revision.isBlank()) continue
                if (liveFor(plugin, taskId) != null) continue
                val operation = PluginTransferPolicy.Operation.from(row.optString("operation")) ?: continue
                if (
                    operation == PluginTransferPolicy.Operation.UPLOAD &&
                        row.optString("protocol") !in
                        setOf(
                            PluginTransferPolicy.UploadProtocol.PLAIN.wire,
                            PluginTransferPolicy.UploadProtocol.TUS.wire,
                        )
                ) continue
                if (runCatching { requireRevision(app, plugin, revision) }.isFailure) {
                    repository.updateOwned(taskId,revision,null){if(PluginTransferPolicy.State.from(it.optString("state"))?.terminal==false){it.put("state",PluginTransferPolicy.State.CANCELLED.wire);true}else false}
                    cancelScheduledJob(app,row.optInt("jobId",0));deleteTaskTemp(app,plugin,taskId);continue
                }
                val recoveredState=PluginTransferPolicy.interruptedState(operation,state,false)
                if(recoveredState!=state)runCatching{repository.updateOwned(taskId,revision,setOf(state.wire)){
                    it.put("state",recoveredState.wire)
                    if(recoveredState==PluginTransferPolicy.State.UNKNOWN)it.put("error",PluginTransferPolicy.uploadUnknown().toJson())
                    true
                }}
                if (!PluginTransferPolicy.canAutoRecover(recoveredState)) continue
                var jobId = row.optInt("jobId", 0)
                if (jobId <= 0) {
                    jobId = runCatching { allocateJobId(app, taskId) }.getOrNull() ?: continue
                    runCatching { repository.setJobId(taskId, revision, jobId) }
                }
                runCatching { scheduleTask(app, plugin, row, jobId, userInitiated = false) }
            }
        }
    }

    /** Called by [PluginTransferJob] on its worker thread. */
    internal fun startScheduledJob(context:Context,plugin:String,taskId:String,jobId:Int,onFinished:(Boolean)->Unit):Boolean =
        synchronized(taskFence(plugin,taskId)){startScheduledJobLocked(context,plugin,taskId,jobId,onFinished)}

    private fun startScheduledJobLocked(
        context: Context,
        plugin: String,
        taskId: String,
        jobId: Int,
        onFinished: (Boolean) -> Unit,
    ): Boolean {
        val app = context.applicationContext
        if (runCatching { PluginContract.id(plugin) }.isFailure) return false
        if (runCatching { PluginContract.validateResourceId(taskId) }.isFailure) return false
        val repository = Repository(app, plugin)
        val row = runCatching { repository.get(taskId) }.getOrNull() ?: return false
        if (row.optString("plugin") != plugin || row.optInt("jobId", 0) != jobId) return false
        val revision = row.optString("revision")
        if (runCatching { PluginTransferPolicy.validateRevision(revision) }.isFailure) return false
        if (runCatching { requireRevision(app, plugin, revision) }.isFailure) return false
        val operation = PluginTransferPolicy.Operation.from(row.optString("operation")) ?: return false
        val protocol =
            if (operation == PluginTransferPolicy.Operation.UPLOAD) {
                PluginTransferPolicy.UploadProtocol.from(row.optString("protocol")) ?: return false
            } else {
                null
            }
        val storedState = PluginTransferPolicy.State.from(row.optString("state")) ?: return false
        val existing = liveFor(plugin, taskId)
        if(existing?.stopped==true){runCatching{onFinished(true)};return true}
        val state=PluginTransferPolicy.interruptedState(operation,storedState,existing!=null)
        if(state!=storedState)repository.updateOwned(taskId,revision,setOf(storedState.wire)){
            it.put("state",state.wire);if(state==PluginTransferPolicy.State.UNKNOWN)it.put("error",PluginTransferPolicy.uploadUnknown().toJson());true
        }
        if (!PluginTransferPolicy.canRun(operation, protocol, state, existing != null)) return false
        val live = LiveJob(app, plugin, taskId, revision, jobId, onFinished)
        if (liveByTask.putIfAbsent(taskKey(plugin, taskId), live) != null) return false
        if (liveByJob.putIfAbsent(jobId, live) != null) {
            liveByTask.remove(taskKey(plugin, taskId), live)
            return false
        }
        try {
            val work=Runnable{live.queued=null;runWorker(app,live)}
            live.queued=work;executor.execute(work)
            if(live.stopped&&executor.remove(work)){removeLive(live);live.finish(false)}
            return true
        } catch (_: RejectedExecutionException) {
            removeLive(live)
            runCatching { onFinished(true) }
            return true
        }
    }

    /** Called from JobService.onStopJob. The boolean is safe-to-reschedule, not safe-to-replay. */
    internal fun stopScheduledJob(context:Context,plugin:String,taskId:String,jobId:Int):Boolean =
        synchronized(taskFence(plugin,taskId)){stopScheduledJobLocked(context,plugin,taskId,jobId)}

    private fun stopScheduledJobLocked(
        context: Context,
        plugin: String,
        taskId: String,
        jobId: Int,
    ): Boolean {
        val app = context.applicationContext
        if (runCatching { PluginContract.id(plugin) }.isFailure) return false
        if (runCatching { PluginContract.validateResourceId(taskId) }.isFailure) return false
        val live = liveByJob[jobId]
        if (live != null && (live.plugin != plugin || live.taskId != taskId)) return false
        live?.stop()
        val repository = Repository(app, plugin)
        val row = runCatching { repository.get(taskId) }.getOrNull() ?: return false
        if (row.optInt("jobId", 0) != jobId || row.optString("plugin") != plugin) return false
        val revision = row.optString("revision")
        val operation = PluginTransferPolicy.Operation.from(row.optString("operation")) ?: return false
        val state = PluginTransferPolicy.State.from(row.optString("state")) ?: return false
        if (state.terminal) return false
        if (operation == PluginTransferPolicy.Operation.DOWNLOAD) {
            runCatching {
                repository.updateOwned(
                    taskId,
                    revision,
                    setOf(
                        PluginTransferPolicy.State.QUEUED.wire,
                        PluginTransferPolicy.State.RUNNING.wire,
                        PluginTransferPolicy.State.PAUSED_DOWNLOAD.wire,
                    ),
                ) {
                    it.put("state", PluginTransferPolicy.State.PAUSED_DOWNLOAD.wire)
                    it.remove("error")
                    true
                }
            }
            return true
        }
        val protocol = PluginTransferPolicy.UploadProtocol.from(row.optString("protocol"))
            ?: return false
        val networkStarted = live?.networkStarted?.get() == true || state == PluginTransferPolicy.State.RUNNING
        if (state == PluginTransferPolicy.State.QUEUED && !networkStarted) {
            // No body byte has been handed to the network. It is safe for JobScheduler to retry.
            return true
        }
        runCatching {
            repository.updateOwned(taskId, revision, setOf("queued","running","paused-upload")) {
                when (protocol) {
                    PluginTransferPolicy.UploadProtocol.PLAIN -> {
                        it.put("state", PluginTransferPolicy.State.UNKNOWN.wire)
                            .put("error", PluginTransferPolicy.uploadUnknown().toJson())
                    }
                    PluginTransferPolicy.UploadProtocol.TUS -> {
                        it.put("state", PluginTransferPolicy.State.PAUSED_UPLOAD.wire)
                            .put(
                                "error",
                                PluginTransferPolicy.SafeError(
                                    "upload_interrupted",
                                    "上传已暂停，可继续以核对服务端偏移",
                                    retryable = true,
                                ).toJson(),
                            )
                    }
                }
                true
            }
        }
        return false
    }

    private fun enqueueDownload(
        context: Context,
        plugin: String,
        revision: String,
        args: JSONObject,
        cancellation: PluginCancellation,
        userInitiated: Boolean,
    ): JSONObject {
        val request = PluginTransferPolicy.parseDownload(args)
        cancellation.check()
        requireRevision(context, plugin, revision)
        val taskId = UUID.randomUUID().toString()
        val task =
            newTaskBase(plugin, revision, taskId, PluginTransferPolicy.Operation.DOWNLOAD)
                .put("url", request.url).put("connectionId",args.optString("connectionId"))
                .put("name", request.name)
                .put("mime", request.mime)
                .put("bytes", 0L)
                .put("total", -1L)
                .put("userInitiated", userInitiated)
        val jobId = allocateJobId(context, taskId)
        task.put("jobId", jobId)
        return persistAndScheduleNew(context, plugin, revision, task, cancellation, userInitiated)
    }

    private fun enqueueUpload(
        context: Context,
        plugin: String,
        revision: String,
        args: JSONObject,
        cancellation: PluginCancellation,
        userInitiated: Boolean,
    ): JSONObject {
        val request = PluginTransferPolicy.parseUpload(args)
        cancellation.check()
        requireRevision(context, plugin, revision)
        val stat =
            runCatching { PluginFiles(context, plugin).stat(request.handle) }
                .getOrElse { throw IllegalArgumentException("文件句柄已失效") }
        require(!stat.optBoolean("directory")) { "不能上传文件夹句柄" }
        val size = stat.optLong("size", -1L)
        require(size <= PluginTransferPolicy.MAX_FILE_BYTES) { "文件超过 256 MiB" }
        val taskId = UUID.randomUUID().toString()
        val jobId = allocateJobId(context, taskId)
        val task =
            newTaskBase(plugin, revision, taskId, PluginTransferPolicy.Operation.UPLOAD)
                .put("url", request.url).put("connectionId",args.optString("connectionId"))
                .put("handle", request.handle)
                .put("method", request.method.name)
                .put("protocol", request.protocol.wire)
                .put("bytes", 0L)
                .put("total", size)
                .put("userInitiated", userInitiated)
                .put("jobId", jobId)
        return persistAndScheduleNew(context, plugin, revision, task, cancellation, userInitiated)
    }

    private fun persistAndScheduleNew(
        context: Context,
        plugin: String,
        revision: String,
        task: JSONObject,
        cancellation: PluginCancellation,
        userInitiated: Boolean,
    ): JSONObject {
        val repository = Repository(context, plugin)
        val taskId = task.getString("taskId")
        val jobId = task.getInt("jobId")
        val cancelledDuringCall = java.util.concurrent.atomic.AtomicBoolean(false)
        val detach = cancellation.onCancel { cancelledDuringCall.set(true) }
        try {
            cancellation.check()
            val pruned = repository.create(task)
            for (oldTaskId in pruned) deleteTaskTemp(context, plugin, oldTaskId)
            if (cancelledDuringCall.get()) {
                repository.removeOwned(taskId, revision)
                deleteTaskTemp(context, plugin, taskId)
                throw PluginRequestException("cancelled", "传输已取消", cancelled = true)
            }
            val scheduled =
                runCatching { scheduleTask(context, plugin, task, jobId, userInitiated) }
                    .getOrDefault(false)
            if (!scheduled) {
                repository.removeOwned(taskId, revision)
                deleteTaskTemp(context, plugin, taskId)
                throw PluginRequestException(
                    "job_scheduling_failed",
                    "无法调度传输任务",
                    retryable = true,
                )
            }
            if (cancelledDuringCall.get()) {
                cancelTask(context,plugin,revision,taskId)
                throw PluginRequestException("cancelled", "传输已取消", cancelled = true)
            }
            return JSONObject().put("taskId", taskId)
        } catch (error: Throwable) {
            if (error !is PluginRequestException) {
                cancelScheduledJob(context, jobId)
                repository.removeOwned(taskId, revision)
                deleteTaskTemp(context, plugin, taskId)
            }
            throw error
        } finally {
            detach()
        }
    }

    private fun newTaskBase(
        plugin: String,
        revision: String,
        taskId: String,
        operation: PluginTransferPolicy.Operation,
    ): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("version", 1)
            .put("taskId", taskId)
            .put("plugin", plugin)
            .put("revision", revision)
            .put("operation", operation.wire)
            .put("state", PluginTransferPolicy.State.QUEUED.wire)
            .put("createdAt", now)
            .put("updatedAt", now)
            .put("bytes", 0L)
            .put("total", -1L)
    }

    private fun resumeTask(
        context: Context,
        plugin: String,
        revision: String,
        args: JSONObject,
        cancellation: PluginCancellation,
        userInitiated: Boolean,
    ): JSONObject {
        cancellation.check()
        val taskId = PluginTransferPolicy.parseTaskId(args)
        val repository = Repository(context, plugin)
        val row = repository.get(taskId) ?: throw IllegalArgumentException("传输任务不存在")
        require(row.optString("revision") == revision) { "传输任务不属于当前插件修订" }
        if (liveFor(plugin, taskId) != null) return publicStatus(plugin, row)
        val operation = PluginTransferPolicy.Operation.from(row.optString("operation"))
            ?: throw IllegalArgumentException("传输任务类型无效")
        val protocol =
            if (operation == PluginTransferPolicy.Operation.UPLOAD) {
                PluginTransferPolicy.UploadProtocol.from(row.optString("protocol"))
                    ?: throw IllegalArgumentException("上传协议无效")
            } else {
                null
            }
        val state = PluginTransferPolicy.State.from(row.optString("state"))
            ?: throw IllegalArgumentException("传输任务状态无效")
        if (state == PluginTransferPolicy.State.QUEUED) {
            val jobId = row.optInt("jobId", 0)
            if (jobId > 0) {
                runCatching { scheduleTask(context, plugin, row, jobId, userInitiated) }
            }
            return publicStatus(plugin, row)
        }
        require(PluginTransferPolicy.canExplicitResume(operation, protocol, state)) {
            "此传输任务不能自动重放"
        }
        val previousError = row.optJSONObject("error")?.let { JSONObject(it.toString()) }
        val updated =
            repository.updateOwned(
                taskId,
                revision,
                setOf(state.wire),
            ) {
                it.put("state", PluginTransferPolicy.State.QUEUED.wire)
                    .put("updatedAt", System.currentTimeMillis())
                it.remove("error")
                true
            } ?: throw IllegalStateException("传输任务状态已变化")
        try {
            cancellation.check()
        } catch (error: Throwable) {
            repository.updateOwned(
                taskId,
                revision,
                setOf(PluginTransferPolicy.State.QUEUED.wire),
            ) {
                it.put("state", state.wire)
                if (previousError == null) it.remove("error") else it.put("error", previousError)
                true
            }
            throw error
        }
        val jobId = updated.optInt("jobId", 0)
        val scheduled = jobId > 0 && runCatching {
            scheduleTask(context, plugin, updated, jobId, userInitiated)
        }.getOrDefault(false)
        if (!scheduled) {
            repository.updateOwned(taskId, revision, setOf(PluginTransferPolicy.State.QUEUED.wire)) {
                it.put("state", state.wire)
                if (previousError == null) it.remove("error") else it.put("error", previousError)
                true
            }
            throw PluginRequestException(
                "job_scheduling_failed",
                "无法调度传输任务",
                retryable = true,
            )
        }
        return publicStatus(plugin, repository.get(taskId) ?: updated)
    }

    private fun taskStatus(
        context: Context,
        plugin: String,
        revision: String,
        taskId: String,
    ): JSONObject {
        val row = Repository(context, plugin).get(taskId)
            ?: throw IllegalArgumentException("传输任务不存在")
        require(row.optString("revision") == revision) { "传输任务不属于当前插件修订" }
        return publicStatus(plugin, row)
    }

    private fun listTasks(context: Context, plugin: String, revision: String): JSONObject {
        val tasks = Repository(context, plugin).all()
            .filter { it.optString("revision") == revision }
            .sortedByDescending { it.optLong("createdAt", 0L) }
            .take(PluginTransferPolicy.MAX_TASKS_PER_PLUGIN)
        val array = org.json.JSONArray()
        for (row in tasks) array.put(publicStatus(plugin, row))
        return JSONObject().put("tasks", array)
    }

    private fun cancelTask(context:Context,plugin:String,revision:String,taskId:String):JSONObject =
        synchronized(taskFence(plugin,taskId)){cancelTaskLocked(context,plugin,revision,taskId)}

    private fun cancelTaskLocked(
        context: Context,
        plugin: String,
        revision: String,
        taskId: String,
    ): JSONObject {
        val repository = Repository(context, plugin)
        var row = repository.get(taskId) ?: throw IllegalArgumentException("传输任务不存在")
        require(row.optString("revision") == revision) { "传输任务不属于当前插件修订" }
        val state = PluginTransferPolicy.State.from(row.optString("state"))
            ?: throw IllegalArgumentException("传输任务状态无效")
        if (state.terminal) return publicStatus(plugin, row)
        val live=liveFor(plugin, taskId)
        live?.stop(discardPartial=true)
        row=repository.get(taskId)?:row
        val currentState=PluginTransferPolicy.State.from(row.optString("state"))?:state
        if(currentState.terminal)return publicStatus(plugin,row)
        val uncertain=row.optString("operation")=="upload"&&(live?.networkStarted?.get()==true||currentState==PluginTransferPolicy.State.RUNNING)
        val updated =
            repository.updateOwned(taskId, revision, setOf("queued","running","paused-download","paused-upload")) {
                it.put("state", if(uncertain)PluginTransferPolicy.State.UNKNOWN.wire else PluginTransferPolicy.State.CANCELLED.wire)
                    .put(
                        "error",
                        (if(uncertain)PluginTransferPolicy.uploadUnknown() else PluginTransferPolicy.SafeError(
                            "cancelled",
                            "传输已取消",
                            cancelled = true,
                        )).toJson(),
                    )
                true
            } ?: repository.get(taskId) ?: row
        cancelScheduledJob(context, row.optInt("jobId", 0))
        deleteTaskTemp(context, plugin, taskId)
        return publicStatus(plugin, updated)
    }

    private fun publicStatus(plugin: String, row: JSONObject): JSONObject {
        val storedState = PluginTransferPolicy.State.from(row.optString("state"))
            ?: PluginTransferPolicy.State.FAILED
        val live =
            if (storedState.terminal) {
                null
            } else {
                liveFor(plugin, row.optString("taskId"))?.takeUnless { it.stopped }
            }
        val state = if (live != null) PluginTransferPolicy.State.RUNNING else storedState
        val storedBytes = row.optLong("bytes", 0L).coerceAtLeast(0L)
        val storedTotal = row.optLong("total", -1L)
        val bytes = if (live != null) maxOf(storedBytes, live.bytes.get().coerceAtLeast(0L)) else storedBytes
        val liveTotal = live?.total?.get() ?: -1L
        val total = if (liveTotal >= 0) liveTotal else storedTotal
        val handle =
            if (state == PluginTransferPolicy.State.COMPLETED) {
                row.optString("resultHandle").takeIf {
                    it.isNotBlank() && runCatching { PluginContract.validateResourceId(it) }.isSuccess
                }
            } else {
                null
            }
        val result =
            JSONObject()
                .put("taskId", row.optString("taskId"))
                .put("state", state.wire)
                .put("bytes", bytes)
                .put("total", total)
                .put("handle", handle ?: JSONObject.NULL)
        val error = sanitizedError(row.optJSONObject("error"))
        result.put("error", error ?: JSONObject.NULL)
        return result
    }

    private fun sanitizedError(error: JSONObject?): JSONObject? {
        if (error == null) return null
        return JSONObject()
            .put("code", error.optString("code").take(64))
            .put("message", error.optString("message").take(PluginTransferPolicy.MAX_ERROR_CHARS))
            .put("status", error.optInt("status", 0).coerceAtLeast(0))
            .put("retryable", error.optBoolean("retryable", false))
            .put("cancelled", error.optBoolean("cancelled", false))
    }

    private fun scheduleTask(
        context: Context,
        plugin: String,
        row: JSONObject,
        jobId: Int,
        userInitiated: Boolean,
    ): Boolean {
        if (jobId <= 0) return false
        val taskId = row.optString("taskId")
        if (taskId.isBlank()) return false
        if (liveFor(plugin, taskId) != null) return true
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val existing = scheduler.getPendingJob(jobId)
        if (existing != null) {
            val extras = existing.extras
            return extras.getString(EXTRA_PLUGIN) == plugin && extras.getString(EXTRA_TASK_ID) == taskId
        }
        val extras =
            PersistableBundle().apply {
                putString(EXTRA_PLUGIN, plugin)
                putString(EXTRA_TASK_ID, taskId)
            }
        fun build(useUserInitiated: Boolean): JobInfo {
            val builder =
                JobInfo.Builder(
                    jobId,
                    ComponentName(context, PluginTransferJob::class.java),
                )
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(extras)
            builder.setPersisted(
                !useUserInitiated && context.checkSelfPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED) ==
                    PackageManager.PERMISSION_GRANTED,
            )
            if (useUserInitiated && Build.VERSION.SDK_INT >= 34) {
                builder.setUserInitiated(true)
            }
            return builder.build()
        }
        val info =
            try {
                build(userInitiated)
            } catch (_: RuntimeException) {
                if (!userInitiated) return false
                runCatching { build(false) }.getOrNull() ?: return false
            }
        return runCatching { scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS }
            .getOrDefault(false)
    }

    private fun allocateJobId(context: Context, taskId: String): Int {
        val scheduler = context.getSystemService(JobScheduler::class.java)
            ?: throw IllegalStateException("系统不支持后台任务")
        var candidate = PluginTransferPolicy.jobIdFor(taskId)
        repeat(32) {
            val pending = scheduler.getPendingJob(candidate)
            if (pending == null) return candidate
            val extras = pending.extras
            if (extras.getString(EXTRA_TASK_ID) == taskId) return candidate
            candidate = if (candidate == Int.MAX_VALUE) 1 else candidate + 1
        }
        throw IllegalStateException("无法分配传输任务编号")
    }

    private fun cancelScheduledJob(context: Context, jobId: Int) {
        if (jobId <= 0) return
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(jobId) }
    }

    private fun requireRevision(context: Context, plugin: String, revision: String) {
        PluginPlatform.store(context).requireRevision(plugin, revision)
    }

    private fun requireRevisionOrThrow(context: Context, plugin: String, revision: String) {
        try {
            requireRevision(context, plugin, revision)
        } catch (_: Exception) {
            throw RevisionChanged()
        }
    }

    private fun liveFor(plugin: String, taskId: String): LiveJob? = liveByTask[taskKey(plugin, taskId)]

    private fun taskKey(plugin: String, taskId: String): String = plugin + TASK_KEY_SEPARATOR + taskId

    private fun removeLive(live: LiveJob) = synchronized(taskFence(live.plugin,live.taskId)) {
        liveByJob.remove(live.jobId, live)
        liveByTask.remove(taskKey(live.plugin, live.taskId), live)
    }

    private fun tempDirectory(context: Context, plugin: String): File =
        File(context.cacheDir, "$TEMP_DIRECTORY/${PluginContract.id(plugin)}")

    private fun taskTempFile(context: Context, plugin: String, taskId: String): File {
        PluginContract.validateResourceId(taskId)
        return File(tempDirectory(context, plugin), "$taskId.part")
    }

    private fun deleteTaskTemp(context: Context, plugin: String, taskId: String) {
        if (runCatching { PluginContract.validateResourceId(taskId) }.isFailure) return
        runCatching { val file=File(context.cacheDir,"plugin-transfers/${PluginContract.id(plugin)}/$taskId.part");file.delete();file.parentFile?.let{if(it.listFiles()?.isEmpty()==true)it.delete()} }
    }

    private fun runWorker(context: Context, live: LiveJob) {
        try {
            val repository = Repository(context, live.plugin)
            val row = repository.get(live.taskId) ?: return
            if (row.optString("plugin") != live.plugin || row.optString("revision") != live.revision) return
            requireRevisionOrThrow(context, live.plugin, live.revision)
            val operation = PluginTransferPolicy.Operation.from(row.optString("operation")) ?: return
            val state = PluginTransferPolicy.State.from(row.optString("state")) ?: return
            checkLive(live)
            when (operation) {
                PluginTransferPolicy.Operation.DOWNLOAD -> {
                    if (
                        state != PluginTransferPolicy.State.QUEUED &&
                        state != PluginTransferPolicy.State.PAUSED_DOWNLOAD
                    ) return
                    runDownload(context, live, repository, row)
                }
                PluginTransferPolicy.Operation.UPLOAD -> {
                    if (state != PluginTransferPolicy.State.QUEUED) return
                    if (PluginTransferPolicy.UploadProtocol.from(row.optString("protocol")) == null) return
                    runUpload(context, live, repository, row)
                }
            }
        } catch (_: RevisionChanged) {
            // The host revision gate owns invalidation. Never publish a result for a stale revision.
        } catch (_: TransferStopped) {
            // onStopJob or an explicit cancel already persisted the safe terminal/paused state.
        } catch (_: Exception) {
            if (!live.stopped) runCatching { unexpectedFailure(context, live) }
        } finally {
            if(live.discardPartial)deleteTaskTemp(context,live.plugin,live.taskId)
            removeLive(live)
            if (!live.stopped) live.finish(false)
        }
    }

    private fun unexpectedFailure(context: Context, live: LiveJob) {
        val repository = Repository(context, live.plugin)
        val row = repository.get(live.taskId) ?: return
        val operation = PluginTransferPolicy.Operation.from(row.optString("operation")) ?: return
        if (runCatching { requireRevision(context, live.plugin, live.revision) }.isFailure) return
        when (operation) {
            PluginTransferPolicy.Operation.DOWNLOAD -> {
                markDownloadPaused(
                    live,
                    repository,
                    PluginTransferPolicy.SafeError("internal_error", "下载暂时中断", retryable = true),
                )
            }
            PluginTransferPolicy.Operation.UPLOAD -> {
                val protocol = PluginTransferPolicy.UploadProtocol.from(row.optString("protocol"))
                if (protocol == PluginTransferPolicy.UploadProtocol.TUS) {
                    markTusPaused(
                        live,
                        repository,
                        PluginTransferPolicy.SafeError("internal_error", "上传暂时中断", retryable = true),
                    )
                } else if (live.networkStarted.get()) {
                    markPlainUploadUnknown(live, repository)
                } else {
                    markUploadFailed(
                        live,
                        repository,
                        PluginTransferPolicy.SafeError("internal_error", "上传失败"),
                    )
                }
            }
        }
    }

    private fun runDownload(
        context: Context,
        live: LiveJob,
        repository: Repository,
        row: JSONObject,
    ) {
        val file = taskTempFile(context, live.plugin, live.taskId)
        file.parentFile?.mkdirs()
        var existing = file.takeIf { it.exists() }?.length() ?: 0L
        if (existing > PluginTransferPolicy.MAX_FILE_BYTES) {
            truncate(file)
            existing = 0L
            markDownloadFailed(
                context,
                live,
                repository,
                PluginTransferPolicy.SafeError("file_too_large", "下载文件超过 256 MiB"),
            )
            return
        }
        var etag = row.optString("etag", "").takeIf { PluginTransferPolicy.isStrongEtag(it) }
        var total = row.optLong("total", -1L).takeIf { it >= 0L }
        val sendRange = existing > 0L && etag != null
        if (existing > 0L && !sendRange) {
            // Without a strong validator an old partial file cannot be safely appended to.
            truncate(file)
            existing = 0L
            total = null
            etag = null
            persistDownloadProgress(repository, live, 0L, null, null)
        }
        live.bytes.set(existing)
        live.total.set(total ?: -1L)
        val builder =
            Request.Builder()
                .url(row.getString("url")).apply { authorize(context,live,row,this) }
                .get()
                .header("Accept-Encoding", "identity")
        if (sendRange) {
            builder.header("Range", "bytes=$existing-")
            if (etag != null) builder.header("If-Range", etag)
        }
        val request = builder.build()
        try {
            withNetwork(live, request) { response ->
                val encoding = response.header("Content-Encoding")?.trim()?.lowercase()
                if (response.code in 200..299 && encoding != null && encoding != "identity") {
                    truncate(file)
                    markDownloadFailed(
                        context,
                        live,
                        repository,
                        PluginTransferPolicy.SafeError(
                            "unsupported_encoding",
                            "服务器返回了不支持的压缩内容",
                            status = response.code,
                        ),
                    )
                    return@withNetwork
                }
                when {
                    response.code == 200 -> downloadFullResponse(
                        context,
                        live,
                        repository,
                        response,
                        file,
                    )
                    response.code == 206 -> downloadPartialResponse(
                        context,
                        live,
                        repository,
                        response,
                        file,
                        existing,
                        sendRange,
                        etag,
                    )
                    response.code == 416 -> {
                        truncate(file)
                        live.bytes.set(0L)
                        persistDownloadProgress(repository, live, 0L, null, null)
                        markDownloadPaused(
                            live,
                            repository,
                            PluginTransferPolicy.SafeError(
                                "range_not_satisfiable",
                                "服务器拒绝断点范围，已清除断点",
                                status = 416,
                                retryable = true,
                            ),
                        )
                    }
                    response.code in TRANSIENT_HTTP_CODES || response.code >= 500 -> {
                        markDownloadPaused(
                            live,
                            repository,
                            PluginTransferPolicy.SafeError(
                                "http_status",
                                "服务器暂时不可用",
                                status = response.code,
                                retryable = true,
                            ),
                        )
                    }
                    else -> {
                        truncate(file)
                        markDownloadFailed(
                            context,
                            live,
                            repository,
                            PluginTransferPolicy.SafeError(
                                "http_status",
                                "下载请求失败",
                                status = response.code,
                            ),
                        )
                    }
                }
            }
        } catch (_: TransferStopped) {
            throw TransferStopped()
        } catch (_: RevisionChanged) {
            throw RevisionChanged()
        } catch (error: IOException) {
            if (live.stopped) throw TransferStopped()
            markDownloadPaused(live, repository, PluginTransferPolicy.networkError(error, false))
        }
    }

    private fun downloadFullResponse(
        context: Context,
        live: LiveJob,
        repository: Repository,
        response: Response,
        file: File,
    ) {
        val declared = response.body?.contentLength()?.takeIf { it >= 0L }
        if (declared != null && declared > PluginTransferPolicy.MAX_FILE_BYTES) {
            truncate(file)
            markDownloadFailed(
                context,
                live,
                repository,
                PluginTransferPolicy.SafeError("file_too_large", "下载文件超过 256 MiB"),
            )
            return
        }
        // A 200 means the server ignored Range or this was a fresh request. Never append it.
        truncate(file)
        val responseEtag = strongResponseEtag(response)
        val total = declared
        live.bytes.set(0L)
        live.total.set(total ?: -1L)
        persistDownloadProgress(repository, live, 0L, total, responseEtag)
        try {
            copyDownloadBody(
                context,
                live,
                repository,
                response,
                file,
                0L,
                declared,
                responseEtag,
                total,
            )
        } catch (_: TransferStopped) {
            throw TransferStopped()
        } catch (_: RevisionChanged) {
            throw RevisionChanged()
        } catch (error: BoundedBodyException) {
            truncate(file)
            markDownloadFailed(context, live, repository, error.safeError)
        } catch (error: IOException) {
            if (live.stopped) throw TransferStopped()
            markDownloadPaused(live, repository, PluginTransferPolicy.networkError(error, false))
        }
    }

    private fun downloadPartialResponse(
        context: Context,
        live: LiveJob,
        repository: Repository,
        response: Response,
        file: File,
        existing: Long,
        sentRange: Boolean,
        previousEtag: String?,
    ) {
        val range = PluginTransferPolicy.parseContentRange(response.header("Content-Range"))
        if (range == null || range.start != existing) {
            truncate(file)
            markDownloadFailed(
                context,
                live,
                repository,
                PluginTransferPolicy.SafeError("range_mismatch", "服务器返回了无效的断点范围"),
            )
            return
        }
        val rangeBytes = range.endInclusive - range.start + 1L
        val declared = response.body?.contentLength()?.takeIf { it >= 0L }
        val total = range.total
        if (
            rangeBytes <= 0L ||
                range.endInclusive >= PluginTransferPolicy.MAX_FILE_BYTES ||
                (total != null && total > PluginTransferPolicy.MAX_FILE_BYTES) ||
                (declared != null && declared != rangeBytes)
        ) {
            truncate(file)
            markDownloadFailed(
                context,
                live,
                repository,
                PluginTransferPolicy.SafeError("file_too_large", "下载文件超过 256 MiB 或范围无效"),
            )
            return
        }
        if (!sentRange && (range.start != 0L || (total != null && range.endInclusive + 1L != total))) {
            truncate(file)
            markDownloadFailed(
                context,
                live,
                repository,
                PluginTransferPolicy.SafeError("range_mismatch", "服务器返回了意外的部分内容"),
            )
            return
        }
        val responseEtag = strongResponseEtag(response)
        if (sentRange && previousEtag != null && responseEtag != null && responseEtag != previousEtag) {
            truncate(file)
            markDownloadFailed(
                context,
                live,
                repository,
                PluginTransferPolicy.SafeError("range_mismatch", "服务器内容已变化，已清除断点"),
            )
            return
        }
        val persistedEtag = responseEtag ?: previousEtag
        live.bytes.set(existing)
        live.total.set(total ?: -1L)
        persistDownloadProgress(repository, live, existing, total, persistedEtag)
        try {
            copyDownloadBody(
                context,
                live,
                repository,
                response,
                file,
                existing,
                rangeBytes,
                persistedEtag,
                total,
            )
        } catch (_: TransferStopped) {
            throw TransferStopped()
        } catch (_: RevisionChanged) {
            throw RevisionChanged()
        } catch (error: BoundedBodyException) {
            truncate(file)
            markDownloadFailed(context, live, repository, error.safeError)
        } catch (error: IOException) {
            if (live.stopped) throw TransferStopped()
            markDownloadPaused(live, repository, PluginTransferPolicy.networkError(error, false))
        }
    }

    private fun copyDownloadBody(
        context: Context,
        live: LiveJob,
        repository: Repository,
        response: Response,
        file: File,
        startingBytes: Long,
        allowedBytes: Long?,
        etag: String?,
        total: Long?,
    ) {
        val body = response.body ?: throw IOException("missing body")
        var copied = 0L
        var lastProgress = startingBytes
        var nextRevisionCheck = ((startingBytes / PluginTransferPolicy.REVISION_CHECK_BYTES) + 1L) *
            PluginTransferPolicy.REVISION_CHECK_BYTES
        val output = FileOutputStream(file, true)
        try {
            body.byteStream().use { input ->
                val buffer = ByteArray(DOWNLOAD_CHUNK)
                while (true) {
                    checkLive(live)
                    val remaining = allowedBytes?.minus(copied)
                    if (remaining != null && remaining <= 0L) {
                        if (input.read() >= 0) {
                            throw BoundedBodyException(
                                PluginTransferPolicy.SafeError(
                                    "range_mismatch",
                                    "服务器响应超过声明的范围",
                                ),
                            )
                        }
                        break
                    }
                    val maxRead =
                        if (remaining == null) {
                            buffer.size
                        } else {
                            minOf(buffer.size.toLong(), remaining + 1L).toInt()
                        }
                    val read = input.read(buffer, 0, maxRead)
                    if (read < 0) break
                    if (allowedBytes != null && copied + read > allowedBytes) {
                        throw BoundedBodyException(
                            PluginTransferPolicy.SafeError(
                                "range_mismatch",
                                "服务器响应超过声明的范围",
                            ),
                        )
                    }
                    if (startingBytes + copied + read > PluginTransferPolicy.MAX_FILE_BYTES) {
                        throw BoundedBodyException(
                            PluginTransferPolicy.SafeError(
                                "file_too_large",
                                "下载文件超过 256 MiB",
                            ),
                        )
                    }
                    output.write(buffer, 0, read)
                    copied += read
                    val current = startingBytes + copied
                    live.bytes.set(current)
                    if (current >= nextRevisionCheck) {
                        requireRevisionOrThrow(
                            live.context,
                            live.plugin,
                            live.revision,
                        )
                        nextRevisionCheck =
                            ((current / PluginTransferPolicy.REVISION_CHECK_BYTES) + 1L) *
                                PluginTransferPolicy.REVISION_CHECK_BYTES
                    }
                    if (current - lastProgress >= PluginTransferPolicy.PROGRESS_COMMIT_BYTES) {
                        persistDownloadProgress(repository, live, current, total, etag)
                        lastProgress = current
                    }
                }
            }
        } finally {
            output.close()
        }
        if (allowedBytes != null && copied != allowedBytes) {
            throw IOException("incomplete response")
        }
        val finalBytes = startingBytes + copied
        if (total != null && finalBytes != total) {
            throw IOException("incomplete response")
        }
        finishDownload(context, live, repository, file, finalBytes, total ?: finalBytes, etag)
    }

    private fun runUpload(
        context: Context,
        live: LiveJob,
        repository: Repository,
        row: JSONObject,
    ) {
        val files = PluginFiles(context, live.plugin)
        val handle = row.optString("handle")
        val stat =
            runCatching { files.stat(handle) }.getOrElse {
                markUploadFailed(
                    live,
                    repository,
                    PluginTransferPolicy.SafeError("file_unavailable", "上传文件已失效"),
                )
                return
            }
        if (stat.optBoolean("directory")) {
            markUploadFailed(
                live,
                repository,
                PluginTransferPolicy.SafeError("file_unavailable", "不能上传文件夹"),
            )
            return
        }
        val size = stat.optLong("size", -1L)
        if (size > PluginTransferPolicy.MAX_FILE_BYTES) {
            markUploadFailed(
                live,
                repository,
                PluginTransferPolicy.SafeError("file_too_large", "文件超过 256 MiB"),
            )
            return
        }
        val protocol = PluginTransferPolicy.UploadProtocol.from(row.optString("protocol"))
        when (protocol) {
            PluginTransferPolicy.UploadProtocol.PLAIN -> uploadPlain(
                context,
                live,
                repository,
                row,
                files,
                handle,
                size,
                stat.optString("mime"),
            )
            PluginTransferPolicy.UploadProtocol.TUS -> {
                if (size < 0L) {
                    markUploadFailed(
                        live,
                        repository,
                        PluginTransferPolicy.SafeError(
                            "file_size_unknown",
                            "tus 上传要求文件大小已知",
                        ),
                    )
                } else {
                    uploadTus(context, live, repository, row, files, handle, size)
                }
            }
            null -> markUploadFailed(
                live,
                repository,
                PluginTransferPolicy.SafeError("invalid_protocol", "上传协议无效"),
            )
        }
    }

    private fun uploadPlain(
        context: Context,
        live: LiveJob,
        repository: Repository,
        row: JSONObject,
        files: PluginFiles,
        handle: String,
        size: Long,
        mime: String,
    ) {
        val method =
            runCatching { PluginTransferPolicy.UploadMethod.valueOf(row.getString("method")) }
                .getOrElse {
                    markUploadFailed(
                        live,
                        repository,
                        PluginTransferPolicy.SafeError("invalid_method", "上传方法无效"),
                    )
                    return
                }
        if (!markUploadRunning(live, repository, 0L, size)) return
        val body =
            PlainUploadBody(
                files = files,
                handle = handle,
                size = size,
                mime = PluginTransferPolicy.safeMime(mime),
                live = live,
                onBytes = { sent ->
                    onUploadProgress(context, repository, live, sent, size)
                },
            )
        val request =
            Request.Builder()
                .url(row.getString("url")).apply { authorize(context,live,row,this) }
                .method(method.name, body)
                .build()
        var status = 0
        try {
            withNetwork(live, request) { response ->
                status = response.code
            }
            if (status !in 200..299) {
                markPlainUploadUnknown(live, repository, status)
                return
            }
            completeUpload(
                live,
                repository,
                handle,
                live.bytes.get().coerceAtLeast(0L),
                if (size >= 0L) size else live.bytes.get().coerceAtLeast(0L),
            )
        } catch (_: TransferStopped) {
            throw TransferStopped()
        } catch (_: RevisionChanged) {
            throw RevisionChanged()
        } catch (error: IOException) {
            if (live.stopped) throw TransferStopped()
            markPlainUploadUnknown(live, repository)
        }
    }

    private fun uploadTus(
        context: Context,
        live: LiveJob,
        repository: Repository,
        row: JSONObject,
        files: PluginFiles,
        handle: String,
        size: Long,
    ) {
        if (!markUploadRunning(live, repository, 0L, size)) return
        val headRequest =
            Request.Builder()
                .url(row.getString("url")).apply { authorize(context,live,row,this) }
                .head()
                .header("Tus-Resumable", "1.0.0")
                .build()
        var headStatus = 0
        var offset = -1L
        var remoteLength = -1L
        try {
            withNetwork(live, headRequest) { response ->
                headStatus = response.code
                if (response.code == 200) {
                    offset = response.header("Upload-Offset")?.toLongOrNull() ?: -1L
                    remoteLength = response.header("Upload-Length")?.toLongOrNull() ?: -1L
                }
            }
        } catch (_: TransferStopped) {
            throw TransferStopped()
        } catch (_: RevisionChanged) {
            throw RevisionChanged()
        } catch (error: IOException) {
            if (live.stopped) throw TransferStopped()
            markTusPaused(live, repository, PluginTransferPolicy.networkError(error, false))
            return
        }
        when {
            headStatus in TRANSIENT_HTTP_CODES || headStatus >= 500 -> {
                markTusPaused(
                    live,
                    repository,
                    PluginTransferPolicy.SafeError(
                        "http_status",
                        "服务器暂时不可用",
                        status = headStatus,
                        retryable = true,
                    ),
                )
                return
            }
            headStatus == 404 || headStatus == 405 || headStatus == 501 -> {
                markUploadFailed(
                    live,
                    repository,
                    PluginTransferPolicy.SafeError(
                        "tus_session_not_found",
                        "未找到明确的 tus 上传会话",
                        status = headStatus,
                    ),
                )
                return
            }
            headStatus != 200 -> {
                markUploadFailed(
                    live,
                    repository,
                    PluginTransferPolicy.SafeError(
                        "http_status",
                        "无法读取 tus 上传状态",
                        status = headStatus,
                    ),
                )
                return
            }
        }
        if (offset < 0L || offset > size) {
            markUploadFailed(
                live,
                repository,
                PluginTransferPolicy.SafeError("tus_offset_invalid", "tus 服务端偏移无效"),
            )
            return
        }
        if (remoteLength >= 0L && remoteLength != size) {
            markUploadFailed(
                live,
                repository,
                PluginTransferPolicy.SafeError("tus_length_mismatch", "tus 会话文件长度不匹配"),
            )
            return
        }
        onlyUpdateUploadProgress(repository, live, offset, size, PluginTransferPolicy.State.RUNNING)
        if (offset == size) {
            live.bytes.set(offset)
            completeUpload(live, repository, handle, size, size)
            return
        }
        val body =
            TusUploadBody(
                files = files,
                handle = handle,
                size = size,
                offset = offset,
                live = live,
                onBytes = { sent ->
                    onUploadProgress(context, repository, live, offset + sent, size)
                },
            )
        val patchRequest =
            Request.Builder()
                .url(row.getString("url")).apply { authorize(context,live,row,this) }
                .patch(body)
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", offset.toString())
                .build()
        var patchStatus = 0
        var responseOffset = -1L
        try {
            withNetwork(live, patchRequest) { response ->
                patchStatus = response.code
                if (response.code in 200..299) {
                    responseOffset = response.header("Upload-Offset")?.toLongOrNull() ?: -1L
                }
            }
        } catch (_: TransferStopped) {
            throw TransferStopped()
        } catch (_: RevisionChanged) {
            throw RevisionChanged()
        } catch (error: IOException) {
            if (live.stopped) throw TransferStopped()
            markTusPaused(live, repository, PluginTransferPolicy.networkError(error, false))
            return
        }
        when {
            patchStatus in 200..299 -> {
                if (responseOffset != size) {
                    markTusPaused(
                        live,
                        repository,
                        PluginTransferPolicy.SafeError(
                            "tus_offset_unconfirmed",
                            "tus 服务端未确认最终偏移",
                            status = patchStatus,
                            retryable = true,
                        ),
                    )
                } else {
                    live.bytes.set(size)
                    completeUpload(live, repository, handle, size, size)
                }
            }
            patchStatus in setOf(409, 412, 423) ||
                patchStatus in TRANSIENT_HTTP_CODES ||
                patchStatus >= 500 -> {
                markTusPaused(
                    live,
                    repository,
                    PluginTransferPolicy.SafeError(
                        "http_status",
                        "tus 上传暂时中断",
                        status = patchStatus,
                        retryable = true,
                    ),
                )
            }
            else -> {
                markUploadFailed(
                    live,
                    repository,
                    PluginTransferPolicy.SafeError(
                        "http_status",
                        "tus 上传失败",
                        status = patchStatus,
                    ),
                )
            }
        }
    }

    private fun onUploadProgress(
        context: Context,
        repository: Repository,
        live: LiveJob,
        sent: Long,
        total: Long,
    ) {
        if (live.stopped) throw TransferStopped()
        val current = sent.coerceAtLeast(0L)
        val previous = live.bytes.getAndSet(current)
        live.total.set(total)
        if (current >= PluginTransferPolicy.MAX_FILE_BYTES + 1L) {
            throw IOException("upload exceeds file limit")
        }
        val crossedRevision =
            current / PluginTransferPolicy.REVISION_CHECK_BYTES >
                previous / PluginTransferPolicy.REVISION_CHECK_BYTES
        if (crossedRevision) requireRevisionOrThrow(context, live.plugin, live.revision)
        if (current - live.lastPersistedBytes.get() >= PluginTransferPolicy.PROGRESS_COMMIT_BYTES) {
            onlyUpdateUploadProgress(
                repository,
                live,
                current,
                total,
                PluginTransferPolicy.State.RUNNING,
            )
            live.lastPersistedBytes.set(current)
        }
    }

    private fun markUploadRunning(
        live: LiveJob,
        repository: Repository,
        bytes: Long,
        total: Long,
    ): Boolean {
        if (live.stopped) return false
        requireRevisionOrThrow(live.context, live.plugin, live.revision)
        live.total.set(total)
        live.bytes.set(bytes)
        val updated =
            repository.updateOwned(
                live.taskId,
                live.revision,
                setOf(PluginTransferPolicy.State.QUEUED.wire),
            ) {
                if (live.stopped) return@updateOwned false
                it.put("state", PluginTransferPolicy.State.RUNNING.wire)
                    .put("bytes", bytes)
                    .put("total", total)
                it.remove("error")
                true
            }
        return updated != null
    }

    private fun onlyUpdateUploadProgress(
        repository: Repository,
        live: LiveJob,
        bytes: Long,
        total: Long,
        state: PluginTransferPolicy.State,
    ) {
        if (live.stopped) return
        repository.updateOwned(
            live.taskId,
            live.revision,
            setOf(state.wire),
        ) {
            if (live.stopped) return@updateOwned false
            it.put("bytes", bytes.coerceAtLeast(0L)).put("total", total)
            true
        }
    }

    private fun completeUpload(
        live: LiveJob,
        repository: Repository,
        handle: String,
        bytes: Long,
        total: Long,
    ) {
        if (live.stopped) throw TransferStopped()
        requireRevisionOrThrow(live.context, live.plugin, live.revision)
        repository.updateOwned(
            live.taskId,
            live.revision,
            setOf(PluginTransferPolicy.State.RUNNING.wire),
        ) {
            if (live.stopped) return@updateOwned false
            it.put("state", PluginTransferPolicy.State.COMPLETED.wire)
                .put("bytes", bytes.coerceAtLeast(0L))
                .put("total", total.coerceAtLeast(bytes))
                .put("resultHandle", handle)
            it.remove("error")
            true
        }
    }

    private fun markPlainUploadUnknown(
        live: LiveJob,
        repository: Repository,
        status: Int = 0,
    ) {
        if (live.stopped) return
        requireRevisionOrThrow(live.context, live.plugin, live.revision)
        val row = repository.get(live.taskId) ?: return
        val bytes = maxOf(row.optLong("bytes", 0L), live.bytes.get()).coerceAtLeast(0L)
        val total = if (live.total.get() >= 0L) live.total.get() else row.optLong("total", -1L)
        repository.updateOwned(
            live.taskId,
            live.revision,
            setOf(
                PluginTransferPolicy.State.QUEUED.wire,
                PluginTransferPolicy.State.RUNNING.wire,
            ),
        ) {
            if (live.stopped) return@updateOwned false
            it.put("state", PluginTransferPolicy.State.UNKNOWN.wire)
                .put("bytes", bytes)
                .put("total", total)
                .put("error", PluginTransferPolicy.uploadUnknown(status).toJson())
            true
        }
    }

    private fun markTusPaused(
        live: LiveJob,
        repository: Repository,
        error: PluginTransferPolicy.SafeError,
    ) {
        if (live.stopped) return
        requireRevisionOrThrow(live.context, live.plugin, live.revision)
        repository.updateOwned(
            live.taskId,
            live.revision,
            setOf(
                PluginTransferPolicy.State.QUEUED.wire,
                PluginTransferPolicy.State.RUNNING.wire,
            ),
        ) {
            if (live.stopped) return@updateOwned false
            it.put("state", PluginTransferPolicy.State.PAUSED_UPLOAD.wire)
                .put("error", error.toJson())
            true
        }
    }

    private fun markUploadFailed(
        live: LiveJob,
        repository: Repository,
        error: PluginTransferPolicy.SafeError,
    ) {
        if (live.stopped) return
        requireRevisionOrThrow(live.context, live.plugin, live.revision)
        repository.updateOwned(
            live.taskId,
            live.revision,
            setOf(
                PluginTransferPolicy.State.QUEUED.wire,
                PluginTransferPolicy.State.RUNNING.wire,
            ),
        ) {
            if (live.stopped) return@updateOwned false
            it.put("state", PluginTransferPolicy.State.FAILED.wire)
                .put("error", error.toJson())
            true
        }
    }

    private fun finishDownload(context:Context,live:LiveJob,repository:Repository,file:File,bytes:Long,total:Long,etag:String?) {
        requireRevisionOrThrow(context,live.plugin,live.revision)
        PluginPlatform.withActiveRevision(context,live.plugin,live.revision){finishCurrentDownload(context,live,repository,file,bytes,total,etag)}
    }

    private fun finishCurrentDownload(
        context: Context,
        live: LiveJob,
        repository: Repository,
        file: File,
        bytes: Long,
        total: Long,
        etag: String?,
    ) {
        if (live.stopped) throw TransferStopped()
        requireRevisionOrThrow(context, live.plugin, live.revision)
        val row = repository.get(live.taskId) ?: run {
            file.delete()
            return
        }
        val name = row.optString("name")
        val mime = PluginTransferPolicy.safeMime(row.optString("mime"))
        val imported =
            runCatching { PluginFiles(context, live.plugin).importFile(file, name, mime) }
                .getOrElse {
                    file.delete()
                    markDownloadFailed(
                        context,
                        live,
                        repository,
                        PluginTransferPolicy.SafeError("import_failed", "无法交付下载文件"),
                    )
                    return
                }
        val handle = imported.optString("handle")
        if (runCatching { PluginContract.validateResourceId(handle) }.isFailure) {
            releaseHandle(context, live.plugin, handle)
            file.delete()
            markDownloadFailed(
                context,
                live,
                repository,
                PluginTransferPolicy.SafeError("import_failed", "无法交付下载文件"),
            )
            return
        }
        val stillCurrent =
            !live.stopped && runCatching {
                requireRevision(context, live.plugin, live.revision)
            }.isSuccess
        if (!stillCurrent) {
            releaseHandle(context, live.plugin, handle)
            file.delete()
            if (live.stopped) throw TransferStopped() else throw RevisionChanged()
        }
        live.bytes.set(bytes)
        live.total.set(total)
        val updated =
            try {
                repository.updateOwned(
                    live.taskId,
                    live.revision,
                    setOf(
                        PluginTransferPolicy.State.QUEUED.wire,
                        PluginTransferPolicy.State.RUNNING.wire,
                        PluginTransferPolicy.State.PAUSED_DOWNLOAD.wire,
                    ),
                ) {
                    if (live.stopped) return@updateOwned false
                    it.put("state", PluginTransferPolicy.State.COMPLETED.wire)
                        .put("bytes", bytes.coerceAtLeast(0L))
                        .put("total", total.coerceAtLeast(bytes))
                        .put("resultHandle", handle)
                    if (etag == null) it.remove("etag") else it.put("etag", etag)
                    it.remove("error")
                    true
                }
            } catch (error: Exception) {
                releaseHandle(context, live.plugin, handle)
                file.delete()
                throw error
            }
        if (updated == null) {
            releaseHandle(context, live.plugin, handle)
        } else {
            file.delete()
        }
    }

    private fun releaseHandle(context: Context, plugin: String, handle: String) {
        if (handle.isBlank()) return
        runCatching {
            PluginFiles(context, plugin).call(
                JSONObject().put("op", "release").put("handle", handle),
            )
        }
    }

    private fun markDownloadPaused(
        live: LiveJob,
        repository: Repository,
        error: PluginTransferPolicy.SafeError,
    ) {
        if (live.stopped) return
        requireRevisionOrThrow(live.context, live.plugin, live.revision)
        val row = repository.get(live.taskId) ?: return
        val bytes = maxOf(row.optLong("bytes", 0L), live.bytes.get()).coerceAtLeast(0L)
        val total = if (live.total.get() >= 0L) live.total.get() else row.optLong("total", -1L)
        repository.updateOwned(
            live.taskId,
            live.revision,
            setOf(
                PluginTransferPolicy.State.QUEUED.wire,
                PluginTransferPolicy.State.RUNNING.wire,
                PluginTransferPolicy.State.PAUSED_DOWNLOAD.wire,
            ),
        ) {
            if (live.stopped) return@updateOwned false
            it.put("state", PluginTransferPolicy.State.PAUSED_DOWNLOAD.wire)
                .put("bytes", bytes)
                .put("total", total)
                .put("error", error.toJson())
            true
        }
    }

    private fun markDownloadFailed(
        context: Context,
        live: LiveJob,
        repository: Repository,
        error: PluginTransferPolicy.SafeError,
    ) {
        deleteTaskTemp(context, live.plugin, live.taskId)
        if (live.stopped) return
        requireRevisionOrThrow(context, live.plugin, live.revision)
        repository.updateOwned(
            live.taskId,
            live.revision,
            setOf(
                PluginTransferPolicy.State.QUEUED.wire,
                PluginTransferPolicy.State.RUNNING.wire,
                PluginTransferPolicy.State.PAUSED_DOWNLOAD.wire,
            ),
        ) {
            if (live.stopped) return@updateOwned false
            it.put("state", PluginTransferPolicy.State.FAILED.wire)
                .put("error", error.toJson())
            true
        }
    }

    private fun persistDownloadProgress(
        repository: Repository,
        live: LiveJob,
        bytes: Long,
        total: Long?,
        etag: String?,
    ) {
        if (live.stopped) return
        repository.updateOwned(
            live.taskId,
            live.revision,
            setOf(
                PluginTransferPolicy.State.QUEUED.wire,
                PluginTransferPolicy.State.RUNNING.wire,
                PluginTransferPolicy.State.PAUSED_DOWNLOAD.wire,
            ),
        ) {
            if (live.stopped) return@updateOwned false
            it.put("bytes", bytes.coerceAtLeast(0L))
                .put("total", total ?: -1L)
            if (etag == null) it.remove("etag") else it.put("etag", etag)
            true
        }
    }

    private fun strongResponseEtag(response: Response): String? =
        response.header("ETag")?.trim()?.takeIf { PluginTransferPolicy.isStrongEtag(it) }

    private fun truncate(file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { }
    }

    private class CredentialRequest
    private val credentialHttp by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }
    private fun authorize(context:Context,live:LiveJob,row:JSONObject,builder:Request.Builder) {
        val connection=row.optString("connectionId")
        if(connection.isBlank())return
        PluginConnections.headers(context,live.plugin,live.revision,connection,row.getString("url"),live.cancellation).forEach{(name,value)->builder.header(name,value)}
        builder.tag(CredentialRequest::class.java,CredentialRequest())
    }

    private fun <T> withNetwork(
        live: LiveJob,
        request: Request,
        block: (Response) -> T,
    ): T {
        checkLive(live)
        val call = (if(request.tag(CredentialRequest::class.java)!=null)credentialHttp else http).newCall(request)
        synchronized(taskFence(live.plugin,live.taskId)){
            checkLive(live)
            live.call=call;live.networkStarted.set(true)
        }
        val detach = live.cancellation.onCancel { call.cancel() }
        try {
            checkLive(live)
            call.execute().use { response -> return block(response) }
        } finally {
            detach()
            live.call = null
        }
    }

    private fun checkLive(live: LiveJob) {
        if (live.stopped || live.cancellation.cancelled || Thread.currentThread().isInterrupted) {
            throw TransferStopped()
        }
    }

    private class Repository(context: Context, private val plugin: String) {
        private val store = PluginEncryptedStore(context.applicationContext, STORE_DIRECTORY, plugin)

        fun get(taskId: String): JSONObject? {
            val tasks = store.read().optJSONObject("tasks") ?: return null
            val row = tasks.optJSONObject(taskId) ?: return null
            if (row.optString("plugin") != plugin) return null
            return JSONObject(row.toString())
        }

        fun all(): List<JSONObject> {
            val tasks = store.read().optJSONObject("tasks") ?: return emptyList()
            return tasks.keys().asSequence()
                .mapNotNull { tasks.optJSONObject(it) }
                .filter { it.optString("plugin") == plugin }
                .map { JSONObject(it.toString()) }
                .toList()
        }

        fun create(row: JSONObject): List<String> {
            val pruned = mutableListOf<String>()
            store.update { root ->
                val tasks = ensureTasks(root)
                val active = tasks.keys().asSequence().count { key ->
                    val state = PluginTransferPolicy.State.from(
                        tasks.optJSONObject(key)?.optString("state") ?: "",
                    )
                    state == null || !state.terminal
                }
                require(active < PluginTransferPolicy.MAX_PENDING_PER_PLUGIN) {
                    "传输队列已满，请等待或取消任务"
                }
                if (tasks.length() >= PluginTransferPolicy.MAX_TASKS_PER_PLUGIN) {
                    val removeCount = tasks.length() - PluginTransferPolicy.MAX_TASKS_PER_PLUGIN + 1
                    val terminal =
                        tasks.keys().asSequence()
                            .mapNotNull { key ->
                                val item = tasks.optJSONObject(key) ?: return@mapNotNull null
                                val state = PluginTransferPolicy.State.from(item.optString("state"))
                                if (state?.terminal == true) {
                                    key to item.optLong("createdAt", 0L)
                                } else {
                                    null
                                }
                            }
                            .sortedBy { it.second }
                            .map { it.first }
                            .toList()
                    require(terminal.size >= removeCount) { "传输任务过多，请先完成或取消" }
                    for (key in terminal.take(removeCount)) {
                        tasks.remove(key)
                        pruned += key
                    }
                }
                tasks.put(row.getString("taskId"), JSONObject(row.toString()))
                root.put("version", 1).put("tasks", tasks)
            }
            return pruned
        }

        fun updateOwned(
            taskId: String,
            revision: String,
            allowedStates: Set<String>?,
            change: (JSONObject) -> Boolean,
        ): JSONObject? {
            var result: JSONObject? = null
            store.update { root ->
                val tasks = root.optJSONObject("tasks") ?: return@update
                val row = tasks.optJSONObject(taskId) ?: return@update
                if (row.optString("plugin") != plugin || row.optString("revision") != revision) {
                    return@update
                }
                if (allowedStates != null && row.optString("state") !in allowedStates) return@update
                if (!change(row)) return@update
                row.put("updatedAt", System.currentTimeMillis())
                val copy = JSONObject(row.toString())
                tasks.put(taskId, copy)
                result = JSONObject(copy.toString())
            }
            return result
        }

        fun setJobId(taskId: String, revision: String, jobId: Int): JSONObject? =
            updateOwned(taskId, revision, null) {
                it.put("jobId", jobId)
                true
            }

        fun removeOwned(taskId: String, revision: String) {
            store.update { root ->
                val tasks = root.optJSONObject("tasks") ?: return@update
                val row = tasks.optJSONObject(taskId) ?: return@update
                if (row.optString("revision") == revision) tasks.remove(taskId)
                root.put("tasks", tasks)
            }
        }

        fun cancelAllActive(): List<JSONObject> {
            val cancelled = mutableListOf<JSONObject>()
            store.update { root ->
                val tasks = ensureTasks(root)
                for (key in tasks.keys().asSequence().toList()) {
                    val row = tasks.optJSONObject(key) ?: continue
                    val state = PluginTransferPolicy.State.from(row.optString("state")) ?: continue
                    if (state.terminal) continue
                    row.put("state", PluginTransferPolicy.State.CANCELLED.wire)
                        .put(
                            "error",
                            PluginTransferPolicy.SafeError(
                                "cancelled",
                                "任务已取消",
                                cancelled = true,
                            ).toJson(),
                        )
                        .put("updatedAt", System.currentTimeMillis())
                    tasks.put(key, row)
                    cancelled += JSONObject(row.toString())
                }
                root.put("tasks", tasks)
            }
            return cancelled
        }

        fun remove() {
            store.remove()
        }

        private fun ensureTasks(root: JSONObject): JSONObject {
            val existing = root.optJSONObject("tasks")
            if (existing != null) return existing
            return JSONObject().also { root.put("tasks", it) }
        }
    }

    private class LiveJob(
        val context: Context,
        val plugin: String,
        val taskId: String,
        val revision: String,
        val jobId: Int,
        private val onFinished: (Boolean) -> Unit,
    ) {
        val cancellation = PluginCancellation()
        val bytes = AtomicLong(0L)
        val total = AtomicLong(-1L)
        val lastPersistedBytes = AtomicLong(0L)
        val networkStarted = AtomicBoolean(false)

        @Volatile var stopped = false
        @Volatile var discardPartial=false
        @Volatile var call: Call? = null
        @Volatile var queued:Runnable?=null
        private val finished = AtomicBoolean(false)

        fun stop(discardPartial:Boolean=false) {
            synchronized(taskFence(plugin,taskId)){
                if(discardPartial)this.discardPartial=true
                if(stopped)return@synchronized
                stopped=true;cancellation.cancel();call?.cancel()
                queued?.let{if(executor.remove(it)){removeLive(this);finish(false)}}
            }
        }

        fun finish(wantsReschedule: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { onFinished(wantsReschedule) }
        }
    }

    private class PlainUploadBody(
        private val files: PluginFiles,
        private val handle: String,
        private val size: Long,
        private val mime: String,
        private val live: LiveJob,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mime.toMediaTypeOrNull()

        override fun contentLength(): Long = size

        override fun writeTo(sink: BufferedSink) {
            files.input(handle).use { input ->
                copyUpload(input, sink, size, live, onBytes)
            }
        }
    }

    private class TusUploadBody(
        private val files: PluginFiles,
        private val handle: String,
        private val size: Long,
        private val offset: Long,
        private val live: LiveJob,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = TUS_CONTENT_TYPE

        override fun contentLength(): Long = size - offset

        override fun writeTo(sink: BufferedSink) {
            files.input(handle).use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val amount = input.skip(offset - skipped)
                    if (amount <= 0L) {
                        if (input.read() < 0) throw IOException("tus source is shorter than offset")
                        skipped += 1L
                    } else {
                        skipped += amount
                    }
                    if (live.stopped) throw TransferStopped()
                }
                copyUpload(input, sink, size - offset, live, onBytes)
            }
        }
    }

    private class TransferStopped : IOException("transfer stopped")

    private class RevisionChanged : RuntimeException()

    private class BoundedBodyException(val safeError: PluginTransferPolicy.SafeError) :
        IOException(safeError.code)

    private fun copyUpload(
        input: InputStream,
        sink: BufferedSink,
        requestedLength: Long,
        live: LiveJob,
        onBytes: (Long) -> Unit,
    ) {
        val limit =
            when {
                requestedLength >= 0L -> requestedLength
                else -> PluginTransferPolicy.MAX_FILE_BYTES
            }
        require(limit <= PluginTransferPolicy.MAX_FILE_BYTES) { "文件超过 256 MiB" }
        var sent = 0L
        val buffer = ByteArray(UPLOAD_CHUNK)
        while (true) {
            checkLive(live)
            val remaining = limit - sent
            if (remaining <= 0L) {
                if (input.read() >= 0) throw IOException("upload source exceeds declared size")
                break
            }
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1L).toInt())
            if (read < 0) break
            if (read.toLong() > remaining) throw IOException("upload source exceeds declared size")
            val chunk = Buffer().write(buffer, 0, read)
            sink.writeAll(chunk)
            sent += read
            onBytes(sent)
        }
        if (requestedLength >= 0L && sent != requestedLength) {
            throw IOException("upload source is shorter than declared size")
        }
    }

    private val TRANSIENT_HTTP_CODES = setOf(408, 425, 429)
    private val TUS_CONTENT_TYPE: MediaType =
        "application/offset+octet-stream".toMediaTypeOrNull()
            ?: error("tus content type")
}
