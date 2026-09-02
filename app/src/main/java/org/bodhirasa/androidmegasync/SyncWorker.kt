package org.bodhirasa.androidmegasync

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.bodhirasa.androidmegasync.local.SafLocalStore
import org.bodhirasa.androidmegasync.sync.ExclusionStore
import org.bodhirasa.androidmegasync.sync.LastSyncStore
import org.bodhirasa.androidmegasync.sync.LocalScanPolicy
import org.bodhirasa.androidmegasync.sync.PathListIgnoreRule
import org.bodhirasa.androidmegasync.sync.SyncEngine
import org.bodhirasa.androidmegasync.sync.SyncPairStore
import org.bodhirasa.androidmegasync.sync.Synchronizer
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val pairStore = SyncPairStore(applicationContext)
        val lastSyncStore = LastSyncStore(applicationContext)
        val pair = pairStore.single() ?: return Result.success()
        if (pair.localTreeUri.isEmpty()) return Result.success()
        val token = SessionStore(applicationContext).token ?: return Result.success()

        return runCatching {
            val mega = MegaClientProvider.get(applicationContext)
            mega.resumeSession(token)
            val ignore = PathListIgnoreRule(ExclusionStore(applicationContext).load())
            val last = lastSyncStore.load(PAIR_ID)
            val local = SafLocalStore(
                applicationContext,
                Uri.parse(pair.localTreeUri),
                mega::contentFingerprint,
                LocalScanPolicy.fromLastSync(ignore, last)
            )
            val result = Synchronizer(mega, local, SyncEngine(ignore = ignore)).sync(pair.remoteRoot, last)
            lastSyncStore.save(PAIR_ID, result.newState)
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val PAIR_ID = "pair-1"
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
