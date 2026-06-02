package org.bodhirasa.sama.sync

import org.bodhirasa.sama.mega.MegaClient

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val dirsCreated: Int = 0,
    val newState: LastSyncState = LastSyncState.EMPTY
)

class Synchronizer(
    private val mega: MegaClient,
    private val local: LocalStore,
    private val engine: SyncEngine
) {

    fun sync(remoteRoot: String, last: LastSyncState): SyncResult {
        val plan = engine.diff(local.snapshot(), mega.listFolder(remoteRoot), last)

        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var dirsCreated = 0

        for (action in plan.actions) {
            when (action) {
                is SyncAction.Upload -> {
                    mega.upload(action.path, local.read(action.path)); uploaded++
                }
                is SyncAction.Download -> {
                    local.write(action.path, mega.download(action.path)); downloaded++
                }
                is SyncAction.DeleteLocal -> {
                    local.delete(action.path); deletedLocal++
                }
                is SyncAction.DeleteRemote -> {
                    mega.delete(action.path); deletedRemote++
                }
                is SyncAction.MakeDirLocal -> {
                    local.makeDir(action.path); dirsCreated++
                }
                is SyncAction.MakeDirRemote -> {
                    mega.makeDir(action.path); dirsCreated++
                }
            }
        }

        // Baseline for the next sync: each side's fingerprint after reconciling.
        val newState = LastSyncState.pair(local.snapshot(), mega.listFolder(remoteRoot))
        return SyncResult(uploaded, downloaded, deletedLocal, deletedRemote, dirsCreated, newState)
    }
}
