package com.personaldashboard.app

import android.app.job.JobParameters
import android.app.job.JobService

/** Manifest-internal JobService bridge; all routing state comes from encrypted task records. */
class PluginTransferJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext ?: return false
        val extras = params.extras
        val plugin = extras?.getString(PluginTransfers.EXTRA_PLUGIN).orEmpty()
        val taskId = extras?.getString(PluginTransfers.EXTRA_TASK_ID).orEmpty()
        if (plugin.isBlank() || taskId.isBlank()) {
            jobFinished(params, false)
            return false
        }
        if(android.os.Build.VERSION.SDK_INT>=34&&params.isUserInitiatedJob){
            val manager=context.getSystemService(android.app.NotificationManager::class.java)
            val channel="plugin-transfers"
            manager?.createNotificationChannel(android.app.NotificationChannel(channel,"文件传输",android.app.NotificationManager.IMPORTANCE_LOW))
            val notification=android.app.Notification.Builder(context,channel).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("鱼缸文件传输").setContentText("正在处理文件，可在插件中查看进度或取消").setOngoing(true).build()
            setNotification(params,params.jobId,notification,JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        val started =
            try {
                PluginTransfers.startScheduledJob(
                    context = context,
                    plugin = plugin,
                    taskId = taskId,
                    jobId = params.jobId,
                ) { wantsReschedule -> jobFinished(params, wantsReschedule) }
            } catch (_: Exception) {
                false
            }
        if (!started) jobFinished(params, false)
        return started
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val context = applicationContext ?: return false
        val extras = params.extras ?: return false
        val plugin = extras.getString(PluginTransfers.EXTRA_PLUGIN).orEmpty()
        val taskId = extras.getString(PluginTransfers.EXTRA_TASK_ID).orEmpty()
        if (plugin.isBlank() || taskId.isBlank()) return false
        return try {
            PluginTransfers.stopScheduledJob(context, plugin, taskId, params.jobId)
        } catch (_: Exception) {
            false
        }
    }
}
