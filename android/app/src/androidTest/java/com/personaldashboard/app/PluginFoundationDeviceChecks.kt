package com.personaldashboard.app

import android.app.Notification
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration acceptance checks for the generic Android plugin foundation.
 *
 * This class never asks Android for a UI permission and never contacts a remote service. It uses
 * one synthetic installed plugin, a second random-only plugin id for handle isolation, app-private
 * files, Android Keystore, JobScheduler and a loopback HTTP server. A caller should invoke [run] on
 * an instrumentation/background thread and on a dedicated emulator.
 */
class PluginFoundationDeviceChecks(private val test: android.app.Instrumentation) {
    private val context get() = test.targetContext
    private var deadlineAt = 0L

    fun run() {
        deadlineAt = SystemClock.elapsedRealtime() + OVERALL_TIMEOUT_MS
        check(
            Build.MODEL.contains("sdk", ignoreCase = true) ||
                Build.FINGERPRINT.contains("emulator", ignoreCase = true),
        ) { "Plugin foundation acceptance 只允许在专用模拟器运行" }

        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val peerId = "foundation-acceptance-peer-$suffix"
        val encryptedDirectory = "foundation-acceptance-keystore-$suffix"
        val store = PluginPlatform.store(context)
        check(!store.installed(PRIMARY_ID)) {
            "插件 $PRIMARY_ID 已存在；拒绝覆盖现有 fixture"
        }
        check(PRIMARY_ID !in store.pendingRemovals()) {
            "插件 $PRIMARY_ID 存在待清理的旧 fixture；拒绝覆盖"
        }
        check(!File(context.filesDir, "plugin-files/$PRIMARY_ID").exists()) {
            "插件 $PRIMARY_ID 的旧文件句柄仍存在；拒绝覆盖"
        }
        check(
            !File(context.filesDir, "plugin-transfers/$PRIMARY_ID.bin").exists() &&
                !File(context.filesDir, "plugin-transfers/$PRIMARY_ID.bin.bak").exists(),
        ) { "插件 $PRIMARY_ID 的旧传输记录仍存在；拒绝覆盖" }
        check(
            !File(context.cacheDir, "plugin-transfers/$PRIMARY_ID").exists() &&
                !File(context.cacheDir, "plugin-export/$PRIMARY_ID").exists(),
        ) { "插件 $PRIMARY_ID 的旧缓存仍存在；拒绝覆盖" }
        check(PluginBackgroundStore(context).get(PRIMARY_ID) == null) {
            "插件 $PRIMARY_ID 的旧后台计划仍存在；拒绝覆盖"
        }
        check(!store.installed(peerId)) { "随机隔离 fixture id 已被占用" }
        check(!File(context.filesDir, encryptedDirectory).exists()) {
            "随机 Keystore fixture 目录已被占用"
        }

        var installAttempted = false
        var installedByTest = false
        var httpFixture: LocalHttpFixture? = null
        var failure: Throwable? = null
        try {
            val bundle =
                JSONObject()
                    .put(
                        "manifest",
                        JSONObject()
                            .put("apiVersion", 1)
                            .put("id", PRIMARY_ID)
                            .put("title", "Foundation acceptance")
                            .put("version", "1.0.0")
                            .put("kind", "native")
                            .put(
                                "capabilities",
                                JSONArray(
                                    listOf(
                                        "plugin.state",
                                        "plugin.files",
                                        "plugin.transfer",
                                        "plugin.connection",
                                        "plugin.notifications.read",
                                        "plugin.background",
                                        "plugin.notification",
                                    ),
                                ),
                            ),
                    )
                    .put(
                        "view",
                        JSONObject()
                            .put("type", "text")
                            .put("text", "Synthetic plugin foundation acceptance fixture"),
                    )
            installAttempted = true
            PluginPlatform.manage(
                context,
                JSONObject().put("action", "install").put("bundle", bundle),
            )
            installedByTest = true
            check(store.installed(PRIMARY_ID)) { "PluginPlatform 报告安装成功但注册表不可见" }
            val revision = store.read(PRIMARY_ID).getString("_revision")
            pass("synthetic plugin $PRIMARY_ID installed through PluginPlatform.manage")

            checkFileProviderAndIsolation(peerId)
            checkConcurrentFileWrites()
            checkVideoMetadata()
            checkEncryptedStore(peerId, encryptedDirectory)
            checkNotificationListener(revision)
            checkBackgroundJobs()

            ensureDeadline("starting loopback HTTP fixture")
            val fixture =
                LocalHttpFixture(
                    downloadBody = fixedBytes(128 * 1024 + 17, 0x51A7_0001L),
                ).apply { start() }
            httpFixture = fixture
            checkApiConnections(revision,fixture)
            checkOAuthGenerations(revision,fixture)
            checkTransfers(revision, fixture)
            checkCancelRegistrationFence(revision)
        } catch (error: Throwable) {
            failure = error
            reportFailure(error)
            throw error
        } finally {
            val cleanupFailure =
                cleanupFixtures(
                    installAttempted = installAttempted,
                    installedByTest = installedByTest,
                    peerId = peerId,
                    encryptedDirectory = encryptedDirectory,
                    httpFixture = httpFixture,
                )
            if (cleanupFailure != null) {
                reportFailure(cleanupFailure)
                if (failure == null) throw cleanupFailure else failure.addSuppressed(cleanupFailure)
            } else if (failure == null) {
                pass("fixture cleanup: plugin, jobs, handles and temporary encrypted files removed")
            }
        }
    }

    private fun checkFileProviderAndIsolation(peerId: String) {
        ensureDeadline("FileProvider import/read/write")
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val sourceA = File(context.cacheDir, "foundation-acceptance-source-$suffix.bin")
        val sourceB = File(context.cacheDir, "foundation-acceptance-peer-$suffix.bin")
        val filesA = PluginFiles(context, PRIMARY_ID)
        val filesB = PluginFiles(context, peerId)
        var handleA: String? = null
        var handleB: String? = null
        try {
            val initial = fixedBytes(70_003, 0xA11CE001L)
            val replacement = fixedBytes(4_097, 0xA11CE002L)
            val appended = fixedBytes(777, 0xA11CE003L)
            sourceA.writeBytes(initial)
            val imported =
                filesA.importFile(
                    sourceA,
                    "foundation-acceptance-$suffix.bin",
                    "application/octet-stream",
                )
            handleA = imported.getString("handle")
            val uri = filesA.uri(handleA)
            check(uri.scheme == "content") { "导入结果不是 Android content URI: $uri" }
            check(uri.authority == context.packageName + ".plugin-files") {
                "导入结果未使用生产 FileProvider authority: $uri"
            }
            check(filesA.stat(handleA).getString("mime") == "application/octet-stream")
            check(
                readFileHandle(filesA, handleA).contentEquals(initial),
            ) { "FileProvider 导入文件通过 PluginFiles 读取后字节不一致" }
            val resolverRead =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Android ContentResolver 无法打开导入 URI")
            check(resolverRead.contentEquals(initial)) {
                "Android ContentResolver 读取 FileProvider 文件字节不一致"
            }

            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(replacement)
            } ?: error("Android ContentResolver 无法写入 FileProvider URI")
            check(readFileHandle(filesA, handleA).contentEquals(replacement)) {
                "Android ContentResolver 写入后 PluginFiles 看不到新内容"
            }
            filesA.call(
                JSONObject()
                    .put("op", "write")
                    .put("handle", handleA)
                    .put("base64", base64(appended))
                    .put("append", true),
            )
            val resolverAfterAppend =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Android ContentResolver 无法重新打开 FileProvider URI")
            check(resolverAfterAppend.contentEquals(replacement + appended)) {
                "PluginFiles append 后 Android ContentResolver 读到的字节不一致"
            }

            check(runCatching { filesB.stat(handleA) }.isFailure) {
                "另一个插件可以读取当前插件的文件句柄"
            }
            val peerBytes = fixedBytes(2_003, 0xA11CE004L)
            sourceB.writeBytes(peerBytes)
            handleB =
                filesB
                    .importFile(
                        sourceB,
                        "foundation-acceptance-peer-$suffix.bin",
                        "application/octet-stream",
                    )
                    .getString("handle")
            check(handleA != handleB)
            check(runCatching { filesA.stat(handleB) }.isFailure) {
                "当前插件可以读取另一个插件的文件句柄"
            }
            check(readFileHandle(filesB, handleB).contentEquals(peerBytes))

            pass(
                "real Android FileProvider import/read/write/append via PluginFiles and ContentResolver",
            )

            filesA.call(JSONObject().put("op", "release").put("handle", handleA))
            handleA = null
            filesB.call(JSONObject().put("op", "release").put("handle", handleB))
            handleB = null
            check(runCatching { filesA.stat(imported.getString("handle")) }.isFailure) {
                "release 后文件句柄仍可访问"
            }
            check(runCatching { context.contentResolver.openInputStream(uri) }.isFailure) {
                "release 后 FileProvider 文件仍存在"
            }
            pass("FileProvider owned file is deleted and its handle is rejected after release")
            pass("cross-plugin file handles are isolated in both directions")
        } finally {
            handleA?.let { runCatching { filesA.call(JSONObject().put("op", "release").put("handle", it)) } }
            handleB?.let { runCatching { filesB.call(JSONObject().put("op", "release").put("handle", it)) } }
            sourceA.delete()
            sourceB.delete()
        }
    }

    private fun checkEncryptedStore(peerId: String, directory: String) {
        ensureDeadline("Android Keystore encrypted-store check")
        val root = File(context.filesDir, directory)
        val owner = PluginEncryptedStore(context, directory, PRIMARY_ID)
        val peer = PluginEncryptedStore(context, directory, peerId)
        val marker = "FOUNDATION_KEYSTORE_MARKER_" + UUID.randomUUID().toString().take(8)
        val protectedBytes = fixedBytes(257, 0x5EC2E7L)
        try {
            check(owner.read().length() == 0) { "新 Keystore fixture 不是空记录" }
            owner.update {
                it.put("owner", PRIMARY_ID)
                    .put("marker", marker)
                    .put("bytes", base64(protectedBytes))
            }
            check(peer.read().length() == 0) { "第二个插件读到了第一个插件的加密记录" }
            val reopened = PluginEncryptedStore(context, directory, PRIMARY_ID).read()
            check(reopened.getString("owner") == PRIMARY_ID)
            check(reopened.getString("marker") == marker)
            check(Base64.decode(reopened.getString("bytes"), Base64.DEFAULT).contentEquals(protectedBytes)) {
                "Keystore 重新打开后的合成字节不一致"
            }
            peer.update {
                it.put("owner", peerId).put("marker", "PEER_ONLY_$marker")
            }
            check(owner.read().getString("marker") == marker) { "插件 B 写入覆盖了插件 A" }
            check(peer.read().getString("owner") == peerId)
            val cipherFile = File(root, "$PRIMARY_ID.bin")
            check(cipherFile.isFile && cipherFile.length() > protectedBytes.size) {
                "PluginEncryptedStore 未写入真实密文文件"
            }
            val ciphertext = cipherFile.readBytes().toString(Charsets.ISO_8859_1)
            check(!ciphertext.contains(marker) && !ciphertext.contains(base64(protectedBytes))) {
                "PluginEncryptedStore 文件中出现明文字节"
            }
            peer.remove()
            check(owner.read().getString("owner") == PRIMARY_ID) { "删除插件 B 的记录影响了插件 A" }
            owner.remove()
            check(!cipherFile.exists()) { "插件 A 删除后密文文件仍存在" }
            pass("real Android Keystore PluginEncryptedStore encrypts and isolates per-plugin records")
        } finally {
            runCatching { owner.remove() }
            runCatching { peer.remove() }
            root.deleteRecursively()
        }
    }

    private fun checkNotificationListener(revision: String) {
        ensureDeadline("notification listener state")
        val enabled =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        val cancellation = PluginCancellation()
        try {
            fun notificationCall(args: JSONObject): JSONObject =
                PluginHostServices.call(
                    context,
                    PRIMARY_ID,
                    revision,
                    "plugin.notifications.read",
                    args,
                    cancellation,
                )

            val status = notificationCall(JSONObject().put("op", "status"))
            check(status.getBoolean("enabled") == enabled) {
                "通知 listener status 与 Android 实际授予状态不一致"
            }
            if (!enabled) {
                check(!status.getBoolean("connected"))
                check(status.getString("reason") == "permission_required")
                val subscribe =
                    runCatching {
                        notificationCall(
                            JSONObject()
                                .put("op", "subscribe")
                                .put("packages", JSONArray().put(context.packageName)),
                        )
                    }.exceptionOrNull()
                check(subscribe != null) { "未授权通知 listener 仍允许订阅" }
                check(subscribe.message.orEmpty().contains("通知使用权")) {
                    "未授权通知 listener 的拒绝原因不清晰: ${subscribe.message}"
                }
                pass("notification listener unauthorized state is permission_required and subscription is refused")
                return
            }

            if (!status.getBoolean("connected")) {
                check(status.getString("reason") == "service_disconnected")
                val subscribe =
                    runCatching {
                        notificationCall(
                            JSONObject()
                                .put("op", "subscribe")
                                .put("packages", JSONArray().put(context.packageName)),
                        )
                    }.exceptionOrNull()
                check(subscribe != null) { "通知 listener 未连接时仍允许订阅" }
                pass("notification listener authorized-but-disconnected state is reported without forcing UI permission")
                return
            }

            val subscription =
                notificationCall(
                    JSONObject()
                        .put("op", "subscribe")
                        .put("packages", JSONArray().put(context.packageName)),
                )
            val subscriptionId = subscription.getString("subscriptionId")
            try {
                val suffix = UUID.randomUUID().toString().take(8)
                val allowedTitle = "FOUNDATION_ALLOWED_$suffix"
                val blockedTitle = "FOUNDATION_BLOCKED_$suffix"
                @Suppress("DEPRECATION")
                fun synthetic(packageName: String, title: String, notificationId: Int): StatusBarNotification {
                    val notification =
                        Notification.Builder(context)
                            .setSmallIcon(android.R.drawable.stat_notify_chat)
                            .setContentTitle(title)
                            .setContentText("synthetic notification fixture")
                            .build()
                    return StatusBarNotification(
                        packageName,
                        packageName,
                        notificationId,
                        "foundation-acceptance-$suffix",
                        Process.myUid(),
                        Process.myPid(),
                        0,
                        notification,
                        Process.myUserHandle(),
                        System.currentTimeMillis(),
                    )
                }
                PluginNotificationInbox.event(
                    synthetic(context.packageName, allowedTitle, 9_101),
                    "posted",
                )
                PluginNotificationInbox.event(
                    synthetic("com.invalid.foundation", blockedTitle, 9_102),
                    "posted",
                )
                val polled =
                    notificationCall(
                        JSONObject()
                            .put("op", "poll")
                            .put("subscriptionId", subscriptionId),
                    )
                val events = polled.getJSONArray("events")
                var allowedSeen = false
                var blockedSeen = false
                for (index in 0 until events.length()) {
                    val row = events.getJSONObject(index)
                    if (row.optString("title") == allowedTitle) {
                        allowedSeen = true
                        check(row.getString("package") == context.packageName)
                    }
                    if (row.optString("title") == blockedTitle) blockedSeen = true
                }
                check(allowedSeen) { "通知 listener 未收到允许包的真实筛选事件" }
                check(!blockedSeen) { "通知 listener 返回了未订阅包的事件" }
                pass("notification listener package allowlist filters synthetic events through the real inbox")
            } finally {
                runCatching {
                    notificationCall(
                        JSONObject()
                            .put("op", "unsubscribe")
                            .put("subscriptionId", subscriptionId),
                    )
                }
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun checkBackgroundJobs() {
        ensureDeadline("background JobInfo persistence check")
        val backgroundStore = PluginBackgroundStore(context)
        check(backgroundStore.get(PRIMARY_ID) == null) { "后台 fixture 预先存在" }
        for (invalidMinutes in listOf(14L, 1_441L)) {
            val rejected =
                runCatching {
                    PluginBackground.schedule(
                        context,
                        PRIMARY_ID,
                        JSONObject()
                            .put("action", "noop")
                            .put("intervalMinutes", invalidMinutes),
                    )
                }.exceptionOrNull()
            check(rejected is IllegalArgumentException) {
                "后台周期边界 $invalidMinutes 分钟未被拒绝: $rejected"
            }
            check(backgroundStore.get(PRIMARY_ID) == null) {
                "被拒绝的后台边界写入了计划记录"
            }
        }

        val first =
            PluginBackground.schedule(
                context,
                PRIMARY_ID,
                JSONObject()
                    .put("action", "noop")
                    .put("intervalMinutes", 15)
                    .put("payload", JSONObject().put("fixture", "foundation-acceptance")),
            )
        check(first.getBoolean("accepted"))
        val firstRecord = backgroundStore.get(PRIMARY_ID) ?: error("后台计划未持久化")
        check(firstRecord.intervalMs == 15L * 60L * 1000L)
        val scheduler = context.getSystemService(JobScheduler::class.java)
            ?: error("设备没有 JobScheduler")
        val firstInfo =
            awaitValue("system accepted 15-minute background JobInfo", 5_000) {
                scheduler.getPendingJob(firstRecord.jobId)
            }
        check(firstInfo.isPeriodic)
        check(firstInfo.intervalMillis == 15L * 60L * 1000L)
        check(firstInfo.isPersisted) { "后台 JobInfo 未标记为系统持久化" }
        check(firstInfo.service == ComponentName(context, PluginBackgroundJob::class.java))
        check(firstInfo.extras.getString("generation") == firstRecord.generation) {
            "后台 JobInfo generation 与持久化记录不一致"
        }

        val repeated =
            PluginBackground.schedule(
                context,
                PRIMARY_ID,
                JSONObject()
                    .put("action", "noop")
                    .put("intervalMinutes", 30)
                    .put("payload", JSONObject().put("fixture", "repeated")),
            )
        check(repeated.getBoolean("accepted"))
        val repeatedRecord =
            PluginBackgroundStore(context).get(PRIMARY_ID)
                ?: error("重复调度后后台记录消失")
        check(repeatedRecord.jobId == firstRecord.jobId)
        check(repeatedRecord.generation != firstRecord.generation)
        check(repeatedRecord.intervalMs == 30L * 60L * 1000L)
        val repeatedInfo =
            awaitValue("system replaced the repeated background JobInfo", 5_000) {
                scheduler.getPendingJob(repeatedRecord.jobId)
            }
        check(repeatedInfo.isPersisted)
        check(repeatedInfo.intervalMillis == 30L * 60L * 1000L)
        check(repeatedInfo.extras.getString("generation") == repeatedRecord.generation)
        check(
            PluginBackground.status(context, PRIMARY_ID).getBoolean("scheduled"),
        ) { "后台状态未报告系统计划存在" }
        pass("background below-min/above-max rejection, repeat replacement and persisted real JobInfo")
    }

    private fun checkCancelRegistrationFence(revision:String) {
        val id=UUID.randomUUID().toString();val jobId=PluginTransferPolicy.jobIdFor(id)
        check(context.getSystemService(JobScheduler::class.java).getPendingJob(jobId)==null)
        val storage=PluginEncryptedStore(context,"plugin-transfers",PRIMARY_ID)
        storage.update{data->val tasks=data.optJSONObject("tasks")?:JSONObject().also{data.put("tasks",it)};tasks.put(id,JSONObject().put("taskId",id).put("plugin",PRIMARY_ID).put("revision",revision).put("operation","download").put("state","queued").put("jobId",jobId))}
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor();val entered=CountDownLatch(1)
        try{
            lateinit var worker:java.util.concurrent.Future<Boolean>
            synchronized(PluginTransfers.taskFence(PRIMARY_ID,id)){
                worker=pool.submit<Boolean>{entered.countDown();PluginTransfers.startScheduledJob(context,PRIMARY_ID,id,jobId){}}
                check(entered.await(1,TimeUnit.SECONDS));Thread.sleep(100)
                check(!worker.isDone){"Worker registration bypassed the cancellation fence"}
                val cancelled=PluginTransfers.call(context,PRIMARY_ID,revision,JSONObject().put("op","cancel").put("taskId",id),PluginCancellation())
                check(cancelled.getString("state")=="cancelled")
            }
            check(!worker.get(3,TimeUnit.SECONDS)){"Worker registered after cancellation"}
            check(PluginTransfers.call(context,PRIMARY_ID,revision,JSONObject().put("op","status").put("taskId",id),PluginCancellation()).getString("state")=="cancelled")
            pass("cancel and delayed worker registration share one task fence; cancelled work cannot start")
        }finally{pool.shutdownNow();storage.update{it.optJSONObject("tasks")?.remove(id)}}
    }

    private fun checkVideoMetadata() {
        val source=File(context.cacheDir,"foundation-video.mp4")
        test.context.assets.open("foundation-video.mp4").use{input->source.outputStream().use{input.copyTo(it)}}
        val files=PluginFiles(context,PRIMARY_ID);val handle=files.importFile(source,"fixture.mp4","video/mp4").getString("handle")
        try{
            val metadata=files.call(JSONObject().put("op","video").put("handle",handle))
            check(metadata.getInt("width")==160&&metadata.getInt("height")==90&&metadata.getLong("durationMs") in 900..1100)
            val thumbnail=files.call(JSONObject().put("op","thumbnail").put("handle",handle))
            val bytes=Base64.decode(thumbnail.getString("base64"),Base64.DEFAULT)
            val image=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("Video thumbnail unavailable")
            try{check(image.width*90==image.height*160&&maxOf(image.width,image.height)<=320)}finally{image.recycle()}
            pass("real MP4 metadata and JPEG first-frame thumbnail preserve video aspect ratio")
        }finally{files.release(handle);source.delete()}
    }

    private fun checkConcurrentFileWrites() {
        val source=File(context.cacheDir,"foundation-write-race.bin").apply{writeBytes(byteArrayOf(1))}
        val files=PluginFiles(context,PRIMARY_ID);val handle=files.importFile(source,"race.bin","application/octet-stream").getString("handle")
        try{
            context.contentResolver.openFileDescriptor(files.uri(handle),"rw")!!.use{fd->
                android.system.Os.lseek(fd.fileDescriptor,PluginFiles.MAX_BYTES-PluginFiles.CHUNK_BYTES-1,android.system.OsConstants.SEEK_SET)
                android.system.Os.write(fd.fileDescriptor,byteArrayOf(0),0,1)
            }
            val go=CountDownLatch(1);val pool=java.util.concurrent.Executors.newFixedThreadPool(8)
            try{val jobs=(0 until 8).map{pool.submit<Boolean>{go.await();runCatching{files.call(JSONObject().put("op","write").put("handle",handle).put("append",true).put("base64",base64(ByteArray(PluginFiles.CHUNK_BYTES))))}.isSuccess}}
                go.countDown();check(jobs.count{it.get(10,TimeUnit.SECONDS)}==1){"Concurrent append crossed file limit"}
                check(files.stat(handle).getLong("size")==PluginFiles.MAX_BYTES)
            }finally{pool.shutdownNow()}
            pass("concurrent file appends serialize size check and cannot exceed 256 MiB")
        }finally{files.release(handle);source.delete()}
    }

    private fun checkOAuthGenerations(revision:String,fixture:LocalHttpFixture) {
        val activity=test.startActivitySync(android.content.Intent(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val monitor=test.addMonitor(android.content.IntentFilter(android.content.Intent.ACTION_VIEW),android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true)
        val key="oauth-fixture";val encrypted=PluginEncryptedStore(context,"plugin-connections",PRIMARY_ID)
        try{
            test.runOnMainSync{PluginConnections.configure(activity,PRIMARY_ID,revision,JSONObject().put("connectionId",key).put("kind","oauth").put("baseUrl",fixture.baseUrl+"/api/").put("authorizationUrl","https://auth.invalid/authorize").put("tokenUrl","https://auth.invalid/token").put("clientId","fixture"),PluginCancellation()) {}}
            encrypted.update{it.getJSONObject(key).put("token","synthetic-old-access").put("refreshToken","synthetic-old-refresh").put("authorization","ready")}
            val old=encrypted.read().getJSONObject(key).getString("configurationId")
            test.runOnMainSync{PluginConnections.authorize(activity,PRIMARY_ID,revision,JSONObject().put("connectionId",key))}
            val pending=encrypted.read().getJSONObject(key);val generation=pending.getString("configurationId");check(generation!=old)
            val commit=PluginConnections::class.java.declaredMethods.single{it.name=="saveTokens"&&it.parameterCount==8}.apply{isAccessible=true}
            fun commitToken(value:String,configuration:String,attempt:String?,token:PluginCancellation)=runCatching{commit.invoke(PluginConnections,context,PRIMARY_ID,key,JSONObject().put("access_token",value).put("expires_in",3600),revision,configuration,attempt,token)}
            check(commitToken("synthetic-stale-refresh",old,null,PluginCancellation()).isFailure)
            check(runCatching{PluginConnections.headers(context,PRIMARY_ID,revision,key,fixture.baseUrl+"/api/ok",PluginCancellation())}.isFailure)
            check(commitToken("synthetic-new-account",generation,pending.getString("authorizationAttempt"),PluginCancellation()).isSuccess)
            check(!encrypted.read().getJSONObject(key).has("refreshToken")){"Old account refresh token survived reauthorization"}
            val cancelled=PluginCancellation().apply{cancel()};check(commitToken("synthetic-cancelled",generation,null,cancelled).isFailure)
            check(encrypted.read().getJSONObject(key).getString("token")=="synthetic-new-account")
            encrypted.update{it.getJSONObject(key).put("authorization","pending").put("authorizationRevision","stale-revision").put("authorizationExpiresAt",System.currentTimeMillis()+100000)}
            check(PluginConnections.call(context,PRIMARY_ID,revision,JSONObject().put("op","status").put("connectionId",key),PluginCancellation()).getString("authorization")=="ready")
            pass("OAuth reauthorization invalidates stale refresh, clears old account refresh token and rejects cancelled commit")
        }finally{
            test.removeMonitor(monitor);PluginConnections.call(context,PRIMARY_ID,revision,JSONObject().put("op","remove").put("connectionId",key),PluginCancellation())
            test.runOnMainSync{activity.finish()}
        }
    }

    private fun checkApiConnections(revision:String,fixture:LocalHttpFixture) {
        val key="acceptance-api";val secret="synthetic-fixture-key";val store=PluginEncryptedStore(context,"plugin-connections",PRIMARY_ID)
        store.update{it.put(key,JSONObject().put("configurationId","fixture").put("name","Fixture API").put("kind","apiKey").put("baseUrl",fixture.baseUrl+"/api/").put("header","X-API-Key").put("prefix","").put("token",secret))}
        fun call(op:String,path:String="")=PluginHostServices.call(context,PRIMARY_ID,revision,"plugin.connection",JSONObject().put("op",op).put("connectionId",key).put("path",path),PluginCancellation())
        check(!call("status").toString().contains(secret)){"Connection metadata exposed credential"}
        check(call("request","ok").getInt("status")==200)
        check(fixture.apiCredential.get()==secret){"Host did not inject scoped credential"}
        check(call("request","redirect").getInt("status")==302)
        check(!fixture.apiRedirectFollowed.get()){ "Credential request followed redirect" }
        check(runCatching{call("request","../outside")}.isFailure)
        check(runCatching{call("request",fixture.baseUrl.replace("127.0.0.1","localhost")+"/api/ok")}.isFailure)
        check(PluginEncryptedStore(context,"plugin-connections","foundation-peer").read().length()==0)
        val same="same-id";PluginFetch.cancel(same,"owner-a")
        val scopedArgs=JSONObject().put("url",fixture.baseUrl+"/api/ok").put("requestId",same)
        check(PluginFetch.fetch(scopedArgs,scope="owner-b").getInt("status")==200)
        check((runCatching{PluginFetch.fetch(scopedArgs,scope="owner-a")}.exceptionOrNull() as? PluginRequestException)?.payload()?.optBoolean("cancelled")==true)
        call("remove");check(runCatching{call("request","ok")}.isFailure)
        pass("real scoped API credential injection, metadata isolation, redirect refusal and connection removal")
    }

    private fun checkTransfers(revision: String, fixture: LocalHttpFixture) {
        ensureDeadline("PluginTransfers download")
        fun transfer(args: JSONObject): JSONObject =
            PluginTransfers.call(
                context,
                PRIMARY_ID,
                revision,
                args,
                PluginCancellation(),
            )

        fun status(taskId: String): JSONObject =
            transfer(JSONObject().put("op", "status").put("taskId", taskId))

        fun enqueue(args: JSONObject): String {
            val taskId = transfer(args).getString("taskId")
            val initial = status(taskId)
            check(initial.getString("state") in setOf("queued", "running", "completed")) {
                "新传输任务状态异常: $initial"
            }
            return taskId
        }

        fun finish(taskId: String): JSONObject {
            val worker = startTransferWorker(taskId, rev = revision)
            var latest = awaitTransferTerminal(taskId, rev = revision)
            if (worker != null) {
                check(worker.await(remainingMs("PluginTransfers worker $taskId"), TimeUnit.MILLISECONDS)) {
                    "PluginTransfers worker 未在期限内结束: $taskId"
                }
                latest = awaitTransferTerminal(taskId, rev = revision, timeoutMs = 5_000)
            }
            cancelPendingTransferJob(taskId)
            return latest
        }

        val downloadBytes = fixture.downloadBody
        val downloadTask =
            enqueue(
                JSONObject()
                    .put("op", "download")
                    .put("url", fixture.baseUrl + "/download")
                    .put("name", "foundation-download.bin")
                    .put("mime", "application/octet-stream"),
            )
        val downloaded = finish(downloadTask)
        check(downloaded.getString("state") == "completed") {
            "本地 HTTP 下载失败: $downloaded"
        }
        check(downloaded.getLong("bytes") == downloadBytes.size.toLong())
        check(downloaded.getLong("total") == downloadBytes.size.toLong())
        check(
            fixture.awaitDownload(minOf(5_000L, remainingMs("download fixture request"))),
        ) { "本地 HTTP fixture 未收到下载请求" }
        check(fixture.downloadMethod.get() == "GET")
        val downloadedHandle = downloaded.getString("handle")
        check(downloadedHandle.isNotBlank())
        val files = PluginFiles(context, PRIMARY_ID)
        check(readFileHandle(files, downloadedHandle).contentEquals(downloadBytes)) {
            "下载交付的 FileProvider 文件字节不一致"
        }
        files.call(JSONObject().put("op", "release").put("handle", downloadedHandle))
        pass("real local HTTP download completed through PluginTransfers and delivered via FileProvider")

        ensureDeadline("PluginTransfers upload")
        val uploadBytes = fixedBytes(96 * 1024 + 3, 0x51A7_0002L)
        val uploadSource =
            File(
                context.cacheDir,
                "foundation-acceptance-upload-${UUID.randomUUID().toString().take(8)}.bin",
            )
        var uploadHandle: String? = null
        try {
            uploadSource.writeBytes(uploadBytes)
            uploadHandle =
                files
                    .importFile(
                        uploadSource,
                        "foundation-upload.bin",
                        "application/octet-stream",
                    )
                    .getString("handle")
            val uploadTask =
                enqueue(
                    JSONObject()
                        .put("op", "upload")
                        .put("url", fixture.baseUrl + "/upload")
                        .put("handle", uploadHandle)
                        .put("method", "PUT")
                        .put("protocol", "plain"),
                )
            val uploaded = finish(uploadTask)
            check(uploaded.getString("state") == "completed") {
                "本地 HTTP 上传失败: $uploaded"
            }
            check(uploaded.getLong("bytes") == uploadBytes.size.toLong())
            check(uploaded.getLong("total") == uploadBytes.size.toLong())
            check(uploaded.getString("handle") == uploadHandle)
            check(
                fixture.awaitUpload(minOf(5_000L, remainingMs("upload fixture request"))),
            ) { "本地 HTTP fixture 未收到上传请求" }
            check(fixture.uploadMethod.get() == "PUT")
            check(fixture.uploadContentType.get().orEmpty().startsWith("application/octet-stream")) {
                "上传 Content-Type 未使用文件 MIME: ${fixture.uploadContentType.get()}"
            }
            check(fixture.uploadBody.get().contentEquals(uploadBytes)) {
                "本地 HTTP 服务器收到的上传字节不一致"
            }
            pass("real local HTTP PUT upload completed through PluginTransfers with exact source bytes")
        } finally {
            uploadHandle?.let {
                runCatching { files.call(JSONObject().put("op", "release").put("handle", it)) }
            }
            uploadSource.delete()
        }

        val retiring=enqueue(JSONObject().put("op","download").put("url",fixture.baseUrl+"/retire").put("name","retire.bin"))
        startTransferWorker(retiring,revision)
        check(fixture.awaitRetireStarted(5000)){"Retirement fixture did not start"}
        val job=PluginEncryptedStore(context,"plugin-transfers",PRIMARY_ID).read().getJSONObject("tasks").getJSONObject(retiring).getInt("jobId")
        synchronized(PluginTransfers.taskFence(PRIMARY_ID,retiring)){
            check(PluginTransfers.stopScheduledJob(context,PRIMARY_ID,retiring,job))
            val retry=java.util.concurrent.atomic.AtomicBoolean(false)
            check(PluginTransfers.startScheduledJob(context,PRIMARY_ID,retiring,job){retry.set(it)})
            check(retry.get()){ "Reschedule was lost while stopped worker was retiring" }
        }
        transfer(JSONObject().put("op","cancel").put("taskId",retiring));cancelPendingTransferJob(retiring)
        pass("system pause during worker retirement explicitly returns the replacement job for retry")

        ensureDeadline("PluginTransfers cancellation")
        val cancelTask =
            enqueue(
                JSONObject()
                    .put("op", "download")
                    .put("url", fixture.baseUrl + "/slow")
                    .put("name", "foundation-cancel.bin")
                    .put("mime", "application/octet-stream"),
            )
        val cancelWorker = startTransferWorker(cancelTask, rev = revision)
        check(
            fixture.awaitSlowStarted(minOf(10_000L, remainingMs("slow download request"))),
        ) { "慢速下载未到达本地 HTTP fixture" }
        check(
            fixture.awaitSlowRequestActive(minOf(5_000L, remainingMs("slow download active phase"))),
        ) { "慢速下载未进入活动网络阶段" }
        val cancelled =
            transfer(JSONObject().put("op", "cancel").put("taskId", cancelTask))
        check(cancelled.getString("state") == "cancelled") {
            "取消传输未立即返回 cancelled: $cancelled"
        }
        if (cancelWorker != null) {
            check(cancelWorker.await(remainingMs("cancelled transfer worker"), TimeUnit.MILLISECONDS)) {
                "取消后 PluginTransfers worker 未退出"
            }
        }
        check(
            fixture.awaitSlowDisconnected(minOf(10_000L, remainingMs("slow download cancellation"))),
        ) {
            "取消后 OkHttp 未关闭本地 HTTP 连接"
        }
        val cancelledStatus = awaitTransferTerminal(cancelTask, rev = revision, timeoutMs = 10_000)
        check(cancelledStatus.getString("state") == "cancelled")
        check(cancelledStatus.getJSONObject("error").getBoolean("cancelled"))
        await("cancelled worker removed partial download",5_000) {
            !File(context.cacheDir,"plugin-transfers/$PRIMARY_ID/$cancelTask.part").exists()
        }
        cancelPendingTransferJob(cancelTask)
        pass("real slow HTTP download cancellation, terminal status and partial-file cleanup")
    }

    /**
     * The dedicated emulator forces the persisted system JobInfo through JobScheduler, exercising
     * the real JobService entry and worker. If Android already started it, wait for that run.
     */
    private fun startTransferWorker(taskId: String, rev: String): CountDownLatch? {
        ensureDeadline("waiting for transfer JobScheduler entry")
        val scheduler = context.getSystemService(JobScheduler::class.java)
            ?: error("设备没有 JobScheduler")
        val finished = CountDownLatch(1)
        val stop = minOf(deadlineAt, SystemClock.elapsedRealtime() + 5_000)
        var lastStatus = ""
        while (SystemClock.elapsedRealtime() < stop) {
            val status =
                PluginTransfers.call(
                    context,
                    PRIMARY_ID,
                    rev,
                    JSONObject().put("op", "status").put("taskId", taskId),
                    PluginCancellation(),
                )
            lastStatus = status.getString("state")
            if (lastStatus in TERMINAL_TRANSFER_STATES) return null
            if (lastStatus == "running") return null
            val job =
                scheduler.allPendingJobs.firstOrNull {
                    it.extras.getString(PluginTransfers.EXTRA_PLUGIN) == PRIMARY_ID &&
                        it.extras.getString(PluginTransfers.EXTRA_TASK_ID) == taskId
                }
            if (job != null) {
                val output=test.uiAutomation.executeShellCommand("cmd jobscheduler run -f ${context.packageName} ${job.id}").use{java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8)}
                check(output.contains("Running job")){"System JobService did not start: $output"}
                return null
            }
            Thread.sleep(50)
        }
        error(
            "找不到可启动的传输 JobInfo 且任务未进入终态: task=$taskId state=$lastStatus",
        )
    }

    private fun awaitTransferTerminal(
        taskId: String,
        rev: String,
        timeoutMs: Long = 20_000,
    ): JSONObject {
        var latest =
            PluginTransfers.call(
                context,
                PRIMARY_ID,
                rev,
                JSONObject().put("op", "status").put("taskId", taskId),
                PluginCancellation(),
            )
        await("transfer $taskId terminal state", timeoutMs) {
            latest =
                PluginTransfers.call(
                    context,
                    PRIMARY_ID,
                    rev,
                    JSONObject().put("op", "status").put("taskId", taskId),
                    PluginCancellation(),
                )
            latest.getString("state") in TERMINAL_TRANSFER_STATES
        }
        return latest
    }

    private fun cancelPendingTransferJob(taskId: String) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.allPendingJobs
            .filter {
                it.extras.getString(PluginTransfers.EXTRA_PLUGIN) == PRIMARY_ID &&
                    it.extras.getString(PluginTransfers.EXTRA_TASK_ID) == taskId
            }
            .forEach { scheduler.cancel(it.id) }
    }

    private fun readFileHandle(files: PluginFiles, handle: String): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            ensureDeadline("reading synthetic file handle")
            val page =
                files.call(
                    JSONObject()
                        .put("op", "read")
                        .put("handle", handle)
                        .put("offset", offset)
                        .put("length", PluginFiles.CHUNK_BYTES),
                )
            val chunk = Base64.decode(page.getString("base64"), Base64.DEFAULT)
            out.write(chunk)
            val count = page.getInt("bytes")
            check(count >= 0)
            offset += count
            if (page.getBoolean("eof")) break
            check(count > 0) { "文件读取未前进且未标记 EOF" }
        }
        return out.toByteArray()
    }

    private fun cleanupFixtures(
        installAttempted: Boolean,
        installedByTest: Boolean,
        peerId: String,
        encryptedDirectory: String,
        httpFixture: LocalHttpFixture?,
    ): Throwable? {
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            runCatching(action).onFailure { failures += it }
        }

        httpFixture?.let { attempt { it.close() } }
        val ownsPrimary =
            installedByTest ||
                (
                    installAttempted &&
                        runCatching { PluginPlatform.store(context).installed(PRIMARY_ID) }
                            .getOrDefault(false)
                )
        if (ownsPrimary) {
            val pendingBackground =
                runCatching { PluginBackgroundStore(context).get(PRIMARY_ID) }.getOrNull()
            attempt { PluginBackground.cancel(context, PRIMARY_ID) }
            val removeError =
                runCatching {
                    PluginPlatform.manage(
                        context,
                        JSONObject().put("action", "remove").put("id", PRIMARY_ID),
                    )
                }.exceptionOrNull()
            runCatching { PluginPlatform.recoverPendingRemovals(context) }
            attempt { PluginFiles.removePlugin(context, PRIMARY_ID) }

            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler?.allPendingJobs
                ?.filter {
                    it.extras.getString(PluginTransfers.EXTRA_PLUGIN) == PRIMARY_ID
                }
                ?.forEach {
                    attempt { scheduler.cancel(it.id) }
                }
            if (pendingBackground != null) {
                attempt {
                    check(scheduler?.getPendingJob(pendingBackground.jobId) == null) {
                        "清理后后台 JobInfo 仍存在"
                    }
                }
            }

            val store = PluginPlatform.store(context)
            val installed = runCatching { store.installed(PRIMARY_ID) }.getOrDefault(true)
            val pending =
                runCatching { PRIMARY_ID in store.pendingRemovals() }.getOrDefault(true)
            if (installed || pending) {
                failures +=
                    IllegalStateException(
                        "无法移除测试插件 $PRIMARY_ID（removeError=${removeError?.message}）",
                        removeError,
                    )
            }
            attempt {
                check(PluginBackgroundStore(context).get(PRIMARY_ID) == null) {
                    "清理后后台计划记录仍存在"
                }
            }
            for (
                path in
                    listOf(
                        File(context.filesDir, "plugin-files/$PRIMARY_ID"),
                        File(context.filesDir, "plugin-transfers/$PRIMARY_ID.bin"),
                        File(context.filesDir, "plugin-transfers/$PRIMARY_ID.bin.bak"),
                        File(context.cacheDir, "plugin-transfers/$PRIMARY_ID"),
                        File(context.cacheDir, "plugin-export/$PRIMARY_ID"),
                    )
            ) {
                attempt {
                    check(!path.exists()) { "清理后测试路径仍存在: $path" }
                }
            }
        }

        attempt { PluginFiles.removePlugin(context, peerId) }
        attempt {
            check(!File(context.filesDir, "plugin-files/$peerId").exists()) {
                "清理后隔离插件文件目录仍存在"
            }
        }
        attempt {
            check(!File(context.cacheDir, "plugin-export/$peerId").exists()) {
                "清理后隔离插件导出目录仍存在"
            }
        }
        attempt {
            check(File(context.filesDir, encryptedDirectory).deleteRecursively()) {
                "无法删除测试 Keystore 目录"
            }
        }
        return failures.firstOrNull()?.also { first ->
            failures.drop(1).forEach(first::addSuppressed)
        }
    }

    private fun <T : Any> awaitValue(label: String, timeoutMs: Long, value: () -> T?): T {
        var latest: T? = null
        await(label, timeoutMs) {
            latest = value()
            latest != null
        }
        return latest ?: error("等待 $label 时得到空值")
    }

    private fun await(label: String, timeoutMs: Long, probe: () -> Boolean) {
        ensureDeadline(label)
        val stop = minOf(deadlineAt, SystemClock.elapsedRealtime() + timeoutMs)
        var last: Throwable? = null
        while (SystemClock.elapsedRealtime() < stop) {
            try {
                if (probe()) return
            } catch (error: Throwable) {
                last = error
            }
            Thread.sleep(50)
        }
        val deadlineHit = SystemClock.elapsedRealtime() >= deadlineAt
        val message =
            if (deadlineHit) {
                "总体 ${OVERALL_TIMEOUT_MS / 1000}s deadline 已到，仍在等待 $label"
            } else {
                "等待 $label 超时（${timeoutMs}ms）"
            }
        throw AssertionError(
            message + (last?.let { "；最后一次错误: ${it.javaClass.simpleName}: ${it.message}" } ?: ""),
            last,
        )
    }

    private fun ensureDeadline(label: String) {
        val remaining = deadlineAt - SystemClock.elapsedRealtime()
        check(remaining > 0L) {
            "Plugin foundation acceptance 超过 ${OVERALL_TIMEOUT_MS / 1000}s deadline：$label"
        }
    }

    private fun remainingMs(label: String): Long {
        ensureDeadline(label)
        return (deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
    }

    private fun pass(text: String) {
        test.sendStatus(
            0,
            Bundle().apply { putString("stream", "PASS: $text\n") },
        )
    }

    private fun reportFailure(error: Throwable) {
        val detail =
            buildString {
                append("FAIL: PluginFoundationDeviceChecks: ")
                append(error.javaClass.name)
                append(": ")
                append(error.message.orEmpty())
                append('\n')
                error.stackTrace.take(8).forEach { append("  at $it\n") }
            }
        runCatching {
            test.sendStatus(0, Bundle().apply { putString("stream", detail) })
        }
    }

    private fun base64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fixedBytes(size: Int, seed: Long): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    /** Small loopback-only HTTP/1.1 server; no java.net.HttpServer is available on Android. */
    private class LocalHttpFixture(val downloadBody: ByteArray) {
        private val server =
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${server.localPort}"

        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val downloadStarted = CountDownLatch(1)
        private val uploadSeen = CountDownLatch(1)
        private val retireStarted=CountDownLatch(1)
        private val slowStarted = CountDownLatch(1)
        private val slowActive = CountDownLatch(1)
        private val slowDisconnected = CountDownLatch(1)
        val apiCredential=AtomicReference<String>()
        val apiRedirectFollowed=java.util.concurrent.atomic.AtomicBoolean(false)
        val downloadMethod = AtomicReference<String>()
        val uploadMethod = AtomicReference<String>()
        val uploadContentType = AtomicReference<String>()
        val uploadBody = AtomicReference<ByteArray>()

        private val acceptThread =
            Thread(
                {
                    while (!server.isClosed) {
                        val socket =
                            try {
                                server.accept()
                            } catch (_: IOException) {
                                break
                            }
                        sockets += socket
                        Thread({ handle(socket) }, "foundation-http-fixture")
                            .apply { isDaemon = true }
                            .start()
                    }
                },
                "foundation-http-accept",
            ).apply { isDaemon = true }

        fun start() {
            acceptThread.start()
        }

        fun awaitDownload(timeoutMs: Long): Boolean =
            downloadStarted.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun awaitUpload(timeoutMs: Long): Boolean =
            uploadSeen.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun awaitRetireStarted(timeoutMs:Long)=retireStarted.await(timeoutMs,TimeUnit.MILLISECONDS)

        fun awaitSlowStarted(timeoutMs: Long): Boolean =
            slowStarted.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun awaitSlowRequestActive(timeoutMs: Long): Boolean =
            slowActive.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun awaitSlowDisconnected(timeoutMs: Long): Boolean =
            slowDisconnected.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun close() {
            runCatching { server.close() }
            sockets.toList().forEach { runCatching { it.close() } }
            acceptThread.join(1_000)
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 15_000
                socket.use {
                    val input = BufferedInputStream(it.getInputStream())
                    val head = readHead(input) ?: return
                    val lines = head.split("\r\n")
                    val request = lines.firstOrNull().orEmpty().split(" ")
                    check(request.size >= 2) { "HTTP 请求行无效" }
                    val method = request[0]
                    val path = request[1].substringBefore('?')
                    val headers =
                        lines.drop(1).mapNotNull { line ->
                            val colon = line.indexOf(':')
                            if (colon <= 0) null
                            else line.substring(0, colon).lowercase(Locale.ROOT) to
                                line.substring(colon + 1).trim()
                        }.toMap()
                    val output = it.getOutputStream()
                    when (path) {
                        "/api/ok" -> {apiCredential.set(headers["x-api-key"]);writeResponse(output,"200 OK",linkedMapOf("Content-Type" to "text/plain"),"OK".toByteArray())}
                        "/api/redirect" -> writeResponse(output,"302 Found",linkedMapOf("Location" to "$baseUrl/outside"),ByteArray(0))
                        "/outside" -> {apiRedirectFollowed.set(true);writeResponse(output,"200 OK",linkedMapOf(),ByteArray(0))}
                        "/download" -> {
                            downloadMethod.set(method)
                            downloadStarted.countDown()
                            writeResponse(
                                output,
                                "200 OK",
                                linkedMapOf(
                                    "Content-Type" to "application/octet-stream",
                                    "ETag" to "\"foundation-acceptance-1\"",
                                ),
                                downloadBody,
                            )
                        }
                        "/upload" -> {
                            val length = headers["content-length"]?.toIntOrNull() ?: -1
                            check(length in 0..PluginTransferPolicy.MAX_FILE_BYTES.toInt()) {
                                "上传请求缺少有界 Content-Length"
                            }
                            uploadMethod.set(method)
                            uploadContentType.set(headers["content-type"])
                            uploadBody.set(readExactly(input, length))
                            uploadSeen.countDown()
                            writeResponse(
                                output,
                                "201 Created",
                                linkedMapOf("Content-Type" to "text/plain"),
                                ByteArray(0),
                            )
                        }
                        "/slow", "/retire" -> {
                            val total = 4L * 1024L * 1024L
                            val headBytes =
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/octet-stream\r\n" +
                                        "Content-Length: $total\r\n" +
                                        "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.ISO_8859_1)
                            output.write(headBytes)
                            output.flush()
                            if(path=="/retire")retireStarted.countDown() else {slowStarted.countDown();slowActive.countDown()}
                            val chunk = ByteArray(4 * 1024)
                            try {
                                while (!server.isClosed) {
                                    Thread.sleep(50)
                                    output.write(chunk)
                                    output.flush()
                                }
                            } catch (_: IOException) {
                                if(path=="/slow")slowDisconnected.countDown()
                            }
                        }
                        else ->
                            writeResponse(
                                output,
                                "404 Not Found",
                                linkedMapOf("Content-Type" to "text/plain"),
                                "not found".toByteArray(),
                            )
                    }
                }
            } catch (_: Throwable) {
                // A cancelled transfer closes its socket; the endpoint latches record that event.
            } finally {
                sockets.remove(socket)
            }
        }

        private fun readHead(input: BufferedInputStream): String? {
            val out = ByteArrayOutputStream()
            var matched = 0
            while (out.size() < 64 * 1024) {
                val value = input.read()
                if (value < 0) return null
                out.write(value)
                matched =
                    when (value) {
                        '\r'.code -> if (matched == 2) 3 else 1
                        '\n'.code ->
                            when (matched) {
                                1 -> 2
                                3 -> 4
                                else -> 0
                            }
                        else -> 0
                    }
                if (matched == 4) return out.toByteArray().toString(Charsets.ISO_8859_1)
            }
            error("HTTP 请求头过大")
        }

        private fun readExactly(input: BufferedInputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                check(read >= 0) { "HTTP 请求正文提前结束" }
                offset += read
            }
            return bytes
        }

        private fun writeResponse(
            output: OutputStream,
            status: String,
            headers: Map<String, String>,
            body: ByteArray,
        ) {
            val head = buildString {
                append("HTTP/1.1 ")
                append(status)
                append("\r\n")
                headers.forEach { (name, value) ->
                    append(name)
                    append(": ")
                    append(value)
                    append("\r\n")
                }
                append("Content-Length: ")
                append(body.size)
                append("\r\nConnection: close\r\n\r\n")
            }
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        }
    }

    private companion object {
        const val PRIMARY_ID = "foundation-acceptance"
        const val OVERALL_TIMEOUT_MS = 90_000L
        val TERMINAL_TRANSFER_STATES = setOf("completed", "failed", "unknown", "cancelled")
    }
}
