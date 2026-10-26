package com.personaldashboard.app

import android.app.job.JobParameters
import android.app.job.JobService

class PluginBackgroundJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean =
        try {
            val record =
                PluginBackgroundStore(applicationContext).all().firstOrNull {
                    it.jobId == params.jobId &&
                        it.generation == params.extras.getString("generation")
                }
            record != null && PluginBackground.execute(applicationContext, record) {
                jobFinished(params, false)
            }
        } catch (_: Exception) {
            // A JobService callback must never crash the app when private storage is damaged or
            // an operation fails. Returning false reports that no work remains from this call.
            false
        }

    override fun onStopJob(params: JobParameters): Boolean =
        try {
            val record =
                PluginBackgroundStore(applicationContext).all().firstOrNull {
                    it.jobId == params.jobId &&
                        it.generation == params.extras.getString("generation")
                }
            record?.let {
                PluginBackgroundCoordinator.preempt(it.pluginId, it.generation) {
                    // The coordinator may invoke this callback after onStopJob returns.
                    runCatching {
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
            }
            false
        } catch (_: Exception) {
            // Storage failures and malformed system inputs are reported by returning false.
            false
        }
}
