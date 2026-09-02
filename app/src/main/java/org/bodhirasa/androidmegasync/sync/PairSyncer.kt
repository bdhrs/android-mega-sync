package org.bodhirasa.androidmegasync.sync

import android.content.Context
import android.net.Uri
import org.bodhirasa.androidmegasync.MegaClientProvider
import org.bodhirasa.androidmegasync.MegaSession
import org.bodhirasa.androidmegasync.local.SafLocalStore

// The one place a pair is turned into a running sync. The manual button, "Sync all"
// and the background worker all come through here, so a pair's session, exclusions,
// scan policy and baseline are wired the same way whatever triggered the run.
// Callers must hold the SyncRuns lock — only one sync runs at a time in this process.
object PairSyncer {

    fun sync(
        context: Context,
        pair: SyncPair,
        onProgress: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false }
    ): SyncResult {
        MegaSession.ensure(context)
        val client = MegaClientProvider.get(context)
        val pairStore = SyncPairStore(context)
        val ignore = PathListIgnoreRule(ExclusionStore(context).load(pair.id))
        val lastSyncStore = LastSyncStore(context)
        val last = lastSyncStore.load(pair.id)
        val local = SafLocalStore(
            context,
            Uri.parse(pair.localTreeUri),
            client::contentFingerprint,
            LocalScanPolicy.fromLastSync(ignore, last)
        )
        val sync = Synchronizer(client, local, SyncEngine(ignore = ignore))
        return sync.sync(pair.remoteRoot, last, onProgress, shouldCancel).also {
            // The pair can be deleted while this run is in flight. Writing its baseline
            // then would resurrect state for a pair that no longer exists, and that
            // state plans deletions if the id is ever seen again.
            if (pairStore.find(pair.id) != null) lastSyncStore.save(pair.id, it.newState)
        }
    }
}
