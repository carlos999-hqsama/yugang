package com.personaldashboard.app

import android.app.job.JobParameters
import android.app.job.JobService

class PluginBackgroundJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val record =
            PluginBackgroundStore(applicationContext).all().firstOrNull {
                it.jobId == params.jobId && it.generation == params.extras.getString("generation")
            }
        if (record == null) {
            return false
        }
        return PluginBackground.execute(applicationContext, record) {
            jobFinished(params, false)
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val record =
            PluginBackgroundStore(applicationContext).all().firstOrNull {
                it.jobId == params.jobId && it.generation == params.extras.getString("generation")
            }
        record?.let {
            PluginBackgroundCoordinator.preempt(it.pluginId, it.generation) {
                PluginBackgroundStore(applicationContext)
                    .markRun(
                        it.pluginId,
                        System.currentTimeMillis(),
                        "preempted",
                        -1L,
                        it.generation,
                    )
            }
        }
        return false
    }
}
