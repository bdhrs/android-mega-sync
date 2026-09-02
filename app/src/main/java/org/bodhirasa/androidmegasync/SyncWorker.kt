package org.bodhirasa.androidmegasync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.bodhirasa.androidmegasync.sync.ExclusionStore
import org.bodhirasa.androidmegasync.sync.PairSyncer
import org.bodhirasa.androidmegasync.sync.SyncPairStore
import org.bodhirasa.androidmegasync.sync.SyncRuns
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // Runs before anything reads exclusions per pair: a background sync arriving
        // before the app is next opened must still honour the pre-upgrade list.
        ExclusionStore.migrateLegacyGlobalList(applicationContext)
        val pairs = SyncPairStore(applicationContext).load().filter { it.localTreeUri.isNotEmpty() }
        if (pairs.isEmpty()) return Result.success()
        if (SessionStore(applicationContext).token == null) return Result.success()

        // A manual sync may be running in the same process. Two runs over one pair both
        // write its baseline, and the loser's baseline need not match what was
        // transferred — so wait for the next scheduled slot instead.
        if (!SyncRuns.tryStart(WORK_NAME)) return Result.retry()
        return try {
            // One failing pair shouldn't stop the others; the retry covers them all, and
            // a pair that did succeed is a no-op on the next attempt.
            var failed = false
            for (pair in pairs) {
                runCatching { PairSyncer.sync(applicationContext, pair) }.onFailure { failed = true }
            }
            if (failed) Result.retry() else Result.success()
        } finally {
            SyncRuns.finish()
        }
    }

    companion object {
        private const val WORK_NAME = "periodic-sync"

        fun schedule(context: Context, intervalHours: Long = 6) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }
}
