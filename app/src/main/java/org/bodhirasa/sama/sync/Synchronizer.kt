package org.bodhirasa.sama.sync

import org.bodhirasa.sama.mega.MegaClient

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val dirsCreated: Int = 0,
    val cancelled: Boolean = false,
    val newState: LastSyncState = LastSyncState.EMPTY
)

class Synchronizer(
    private val mega: MegaClient,
    private val local: LocalStore,
    private val engine: SyncEngine
) {

    // onProgress is called on the calling thread with a short human-readable
    // status line (scan phases, then "i/N  <action> <path>" per step).
    fun sync(
        remoteRoot: String,
        last: LastSyncState,
        onProgress: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false }
    ): SyncResult {
        onProgress("Scanning local files…")
        val localSnap = local.snapshot()
        onProgress("Scanning MEGA…")
        val remoteSnap = mega.listFolder(remoteRoot)

        val plan = engine.diff(localSnap, remoteSnap, last)
        val total = plan.actions.size

        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var dirsCreated = 0

        plan.actions.forEachIndexed { i, action ->
            // Stop before the next file. Already-transferred files are recognised
            // as in-sync by content on the next run, so keeping the old baseline
            // (we skip Finalising on cancel) is safe.
            if (shouldCancel()) {
                onProgress("Cancelled")
                return SyncResult(uploaded, downloaded, deletedLocal, deletedRemote, dirsCreated, cancelled = true, newState = last)
            }
            onProgress("${i + 1}/$total  ${label(action)}")
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

        // Derive the new baseline from the snapshots we already scanned plus the
        // actions just applied — no second full re-scan. After a transfer both
        // sides hold the same content, so the moved side takes the other's entry.
        val finalLocal = localSnap.byPath.toMutableMap()
        val finalRemote = remoteSnap.byPath.toMutableMap()
        for (action in plan.actions) {
            when (action) {
                is SyncAction.Upload -> localSnap.byPath[action.path]?.let { finalRemote[action.path] = it }
                is SyncAction.Download -> remoteSnap.byPath[action.path]?.let { finalLocal[action.path] = it }
                is SyncAction.DeleteLocal -> finalLocal.remove(action.path)
                is SyncAction.DeleteRemote -> finalRemote.remove(action.path)
                is SyncAction.MakeDirLocal -> remoteSnap.byPath[action.path]?.let { finalLocal[action.path] = it }
                is SyncAction.MakeDirRemote -> localSnap.byPath[action.path]?.let { finalRemote[action.path] = it }
            }
        }
        val newState = LastSyncState.pair(
            FolderSnapshot(finalLocal.values.toList()),
            FolderSnapshot(finalRemote.values.toList())
        )
        return SyncResult(uploaded, downloaded, deletedLocal, deletedRemote, dirsCreated, newState = newState)
    }

    private fun label(action: SyncAction): String = when (action) {
        is SyncAction.Upload -> "↑ ${action.path}"
        is SyncAction.Download -> "↓ ${action.path}"
        is SyncAction.DeleteLocal -> "✕ local ${action.path}"
        is SyncAction.DeleteRemote -> "✕ remote ${action.path}"
        is SyncAction.MakeDirLocal, is SyncAction.MakeDirRemote -> "+ dir ${action.path}"
    }
}
