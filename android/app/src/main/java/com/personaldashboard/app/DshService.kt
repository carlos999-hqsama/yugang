package com.personaldashboard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 前台服务：让内置 dsh 在切到后台 / 锁屏时继续跑（长任务、审批等待）。 通知里有「重启」「停止」。停止后回到 App 会自动再次启动。 */
class DshService : Service() {
    private lateinit var cfg: Config
    private lateinit var net: Net
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var latestStartId = 0
    private var destroyed = false
    private var stopping = false
    private lateinit var taskWake: PowerManager.WakeLock
    private val monitor = Executors.newSingleThreadScheduledExecutor()
    private var taskGraceUntil = 0L
    private val listener: (JSONObject) -> Unit = { j ->
        main.post {
            if (!destroyed) {
                refreshWakeForRuntime()
                notifyState(
                    j.optString("state"),
                    j.optString("message"),
                    j.optDouble("progress", 0.0),
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        net = Net(cfg) { _, _ -> }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Harness 运行时", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        taskWake =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PersonalDashboard:HarnessTask")
                .apply { setReferenceCounted(false) }
        instance = this
        Runtime.addListener(listener)
        monitor.scheduleWithFixedDelay(
            { pollRunning() },
            0,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        stopping = intent?.action == ACT_STOP
        if (stopping) taskGraceUntil = 0
        if (intent?.action == ACT_RESTART) {
            taskGraceUntil = 0
            releaseTaskWake()
        }
        val n = build(Runtime.message.ifEmpty { "正在启动 Harness…" }, Runtime.progress, Runtime.state)
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        when (intent?.action) {
            ACT_STOP ->
                Runtime.stop { stopped ->
                    main.post {
                        // 只接受本次显式停止的完成结果，重启内部的 stopped 状态不撤掉保护。
                        if (!destroyed && stopped && latestStartId == startId) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(startId)
                        }
                    }
                }
            ACT_RESTART -> Runtime.restart(this, cfg, net)
            else -> Runtime.ensure(this, cfg, net)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (instance === this) instance = null
        Runtime.removeListener(listener)
        monitor.shutdownNow()
        releaseTaskWake()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun wakeAllowed() = !destroyed && !stopping && cfg.embedded && cfg.keepAwakeWhileRunning

    private fun releaseTaskWake() {
        if (taskWake.isHeld) taskWake.release()
    }

    private fun renewTaskWake() {
        taskWake.acquire(TASK_PROTECTION_MILLIS)
    }

    private fun refreshWakeForRuntime() {
        if (destroyed || !cfg.embedded || !cfg.keepAwakeWhileRunning || Runtime.state != "ready")
            releaseTaskWake()
    }

    // 页面冻结或退出后也由前台服务自行检查本机任务；失败不续租，最长30秒自动释放。
    private fun pollRunning() {
        val request = latestStartId
        val running =
            if (cfg.embedded && cfg.keepAwakeWhileRunning && Runtime.state == "ready")
                runCatching { net.hasRunningSessions() }.getOrNull()
            else null
        main.post {
            if (destroyed || request != latestStartId) return@post
            refreshWakeForRuntime()
            if (!wakeAllowed() || Runtime.state != "ready") return@post
            if (running == true) {
                taskGraceUntil = 0
                renewTaskWake()
            } else if (running == false && SystemClock.elapsedRealtime() >= taskGraceUntil)
                releaseTaskWake()
        }
    }

    private fun protectTaskStart(): Boolean {
        if (!wakeAllowed() || Runtime.state != "ready") return false
        // 保护提交请求到 running 状态出现之间的窗口；后续由原生检查接手。
        taskGraceUntil = SystemClock.elapsedRealtime() + TASK_PROTECTION_MILLIS
        renewTaskWake()
        monitor.execute { pollRunning() }
        return taskWake.isHeld
    }

    private var lastShown = ""

    private fun notifyState(state: String, msg: String, p: Double) {
        val key = "$state|$msg|${(p * 50).toInt()}"
        if (key == lastShown) return
        lastShown = key
        getSystemService(NotificationManager::class.java).notify(ID, build(msg, p, state))
    }

    private fun build(msg: String, p: Double, state: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        fun act(a: String, rc: Int) =
            PendingIntent.getService(
                this,
                rc,
                Intent(this, DshService::class.java).setAction(a),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val b =
            Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.notification_whale)
                .setContentTitle("鱼缸")
                .setContentText(msg)
                .setOngoing(true)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "重启", act(ACT_RESTART, 1)).build())
                .addAction(Notification.Action.Builder(null, "停止", act(ACT_STOP, 2)).build())
        if (state == "installing") b.setProgress(1000, (p * 1000).toInt(), false)
        return b.build()
    }

    companion object {
        private const val POLL_INTERVAL_SECONDS = 5L
        private const val TASK_PROTECTION_MILLIS = 30_000L

        @Volatile private var instance: DshService? = null

        fun protectPendingTask(): CompletableFuture<Boolean> {
            val done = CompletableFuture<Boolean>()
            val s = instance ?: return done.apply { complete(false) }
            val action = Runnable {
                try {
                    done.complete(!s.destroyed && s.protectTaskStart())
                } catch (e: Exception) {
                    done.completeExceptionally(e)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run() else s.main.post(action)
            return done
        }

        fun refreshProtection() {
            instance?.let { s ->
                s.main.post {
                    if (!s.destroyed) {
                        s.refreshWakeForRuntime()
                        s.monitor.execute { s.pollRunning() }
                    }
                }
            }
        }

        const val CH = "runtime"
        const val ID = 3090
        const val ACT_STOP = "com.personaldashboard.app.STOP"
        const val ACT_RESTART = "com.personaldashboard.app.RESTART"

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, DshService::class.java).setAction(action)
            ctx.startForegroundService(i)
        }
    }
}
