package com.fetch.tv.core.sync.androidtv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "TvChannelSync"
// Older versions scheduled a persisted periodic job under this id. It is only cancelled now.
private const val JOB_ID_PERIODIC = 10_010
private const val JOB_ID_IMMEDIATE = 10_011

/**
 * One-off job that rebuilds the Continue Watching preview channel after boot or when the
 * launcher asks for programs (`INITIALIZE_PROGRAMS`). Reads from the CW enrichment cache on
 * disk, which is populated by the HomeViewModel CW pipeline during normal app usage.
 *
 * There is no periodic job (roadmap G2): each run cold-started the whole process while the TV
 * sat idle. The channel is reconciled when the app goes to the background, which covers every
 * change the user makes; only an episode airing while Fetch is closed waits for the next launch.
 *
 * Dependencies are obtained via Hilt EntryPoint since JobService is not a supported
 * Hilt injection target.
 */
class TvChannelRefreshJobService : JobService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TvChannelJobEntryPoint {
        fun channelSyncService(): AndroidTvChannelSyncService
        fun channelManager(): AndroidTvChannelManager
    }

    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, TvChannelJobEntryPoint::class.java
        )
        val syncService = entryPoint.channelSyncService()
        val manager = entryPoint.channelManager()

        if (!manager.isSupported()) {
            jobFinished(params, false)
            return false
        }

        jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope!!.launch {
            try {
                // Read from CW enrichment cache and reconcile with current settings.
                // The cache is on disk so it's available even when the app hasn't been
                // in the foreground recently.
                syncService.reconcileFromCache()
                Log.d(TAG, "Background job: reconciled from CW cache")
            } catch (e: Exception) {
                Log.w(TAG, "Background job reconcile failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobScope?.cancel()
        return true // reschedule on stop
    }

    companion object {
        /**
         * Removes the persisted periodic job older versions scheduled (every 15 minutes, later
         * every 6 hours). Without this it would keep waking the process after an update.
         */
        fun cancelPeriodic(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.allPendingJobs.none { it.id == JOB_ID_PERIODIC }) return
            scheduler.cancel(JOB_ID_PERIODIC)
            Log.d(TAG, "Cancelled the legacy periodic TV channel refresh")
        }

        fun scheduleImmediate(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(
                JOB_ID_IMMEDIATE,
                ComponentName(context, TvChannelRefreshJobService::class.java)
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(5_000)
                .build()
            scheduler.schedule(job)
            Log.d(TAG, "Scheduled immediate TV channel refresh")
        }
    }
}
