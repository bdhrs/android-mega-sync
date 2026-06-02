package org.bodhirasa.sama.sync

// Reconciles a local and remote folder against the last-synced baseline.
//
// Each side is compared only to its own fingerprint from the last sync (the two
// sides use different fingerprint schemes and are never compared directly). When
// a file genuinely changed on both sides, or has no recorded history, the newer
// modification time wins (last-writer-wins) — no conflict copies.
class SyncEngine(
    private val ignore: IgnoreRule = IgnoreRule.NONE
) {

    fun diff(local: FolderSnapshot, remote: FolderSnapshot, last: LastSyncState): SyncPlan {
        val paths = (local.byPath.keys + remote.byPath.keys + last.entries.keys)
            .filterNot { ignore.isIgnored(it) }

        val creates = mutableListOf<SyncAction>()
        val deletes = mutableListOf<SyncAction>()

        for (path in paths) {
            val l = local.byPath[path]
            val r = remote.byPath[path]
            val base = last.entries[path]

            if (l?.isDir == true || r?.isDir == true) {
                reconcileDir(path, l != null, r != null, base != null, creates, deletes)
                continue
            }

            reconcileFile(path, l, r, base, creates, deletes)
        }

        // Parents before children for creates; children before parents for deletes.
        creates.sortBy { it.path }
        deletes.sortByDescending { it.path }

        return SyncPlan(creates + deletes)
    }

    private fun reconcileDir(
        path: String,
        locExists: Boolean,
        remExists: Boolean,
        wasTracked: Boolean,
        creates: MutableList<SyncAction>,
        deletes: MutableList<SyncAction>
    ) {
        when {
            locExists && !remExists ->
                if (wasTracked) deletes.add(SyncAction.DeleteLocal(path))
                else creates.add(SyncAction.MakeDirRemote(path))
            !locExists && remExists ->
                if (wasTracked) deletes.add(SyncAction.DeleteRemote(path))
                else creates.add(SyncAction.MakeDirLocal(path))
        }
    }

    private fun reconcileFile(
        path: String,
        l: FileEntry?,
        r: FileEntry?,
        base: SyncFingerprints?,
        creates: MutableList<SyncAction>,
        deletes: MutableList<SyncAction>
    ) {
        when {
            l != null && r != null -> {
                val locChanged = base == null || l.fingerprint != base.local
                val remChanged = base == null || r.fingerprint != base.remote
                when {
                    !locChanged && !remChanged -> {} // in sync
                    locChanged && remChanged -> creates.add(newerWins(path, l, r))
                    locChanged -> creates.add(SyncAction.Upload(path))
                    else -> creates.add(SyncAction.Download(path))
                }
            }
            l != null && r == null -> when {
                base == null -> creates.add(SyncAction.Upload(path))                  // new local
                l.fingerprint != base.local -> creates.add(SyncAction.Upload(path))   // edited locally, deleted remotely -> keep edit
                else -> deletes.add(SyncAction.DeleteLocal(path))                     // deleted remotely
            }
            l == null && r != null -> when {
                base == null -> creates.add(SyncAction.Download(path))                // new remote
                r.fingerprint != base.remote -> creates.add(SyncAction.Download(path)) // edited remotely, deleted locally -> keep edit
                else -> deletes.add(SyncAction.DeleteRemote(path))                    // deleted locally
            }
            // both gone: nothing to do
        }
    }

    // Last-writer-wins: the side with the newer modification time is propagated.
    // Ties go to the local copy (deterministic).
    private fun newerWins(path: String, l: FileEntry, r: FileEntry): SyncAction =
        if (l.modifiedMillis >= r.modifiedMillis) SyncAction.Upload(path) else SyncAction.Download(path)
}
