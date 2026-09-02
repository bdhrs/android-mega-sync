package org.bodhirasa.androidmegasync.sync

// The local and remote fingerprints a file had at the last successful sync.
// The two sides use different fingerprint schemes and are never compared to
// each other — each side is only ever compared to its own last-recorded value.
//
// localSize and localModifiedMillis are the local file's metadata at that same
// moment. They serve only as a cache key for skipping the content read on the
// next scan (see LocalScanPolicy.fromLastSync) and are never compared across
// sides. Both zero means "unknown" — a record restored from the pre-cache
// format — and disables reuse for that path.
data class SyncFingerprints(
    val local: String,
    val remote: String,
    val localSize: Long = 0,
    val localModifiedMillis: Long = 0
)

data class LastSyncState(val entries: Map<String, SyncFingerprints>) {
    companion object {
        val EMPTY = LastSyncState(emptyMap())

        // Baseline built from both sides after a successful sync: a file present
        // on both sides records each side's current fingerprint.
        fun pair(local: FolderSnapshot, remote: FolderSnapshot): LastSyncState =
            LastSyncState(
                (local.byPath.keys intersect remote.byPath.keys).associateWith { path ->
                    val l = local.byPath.getValue(path)
                    SyncFingerprints(l.fingerprint, remote.byPath.getValue(path).fingerprint, l.size, l.modifiedMillis)
                }
            )
    }
}

object LastSyncCodec {

    private const val FIELD_SEP = "\t"
    private const val RECORD_SEP = "\n"

    fun encode(state: LastSyncState): String =
        state.entries.entries.joinToString(RECORD_SEP) { (path, fp) ->
            listOf(path, fp.local, fp.remote, fp.localSize.toString(), fp.localModifiedMillis.toString())
                .joinToString(FIELD_SEP)
        }

    // Records written before the local size/modified-time fields existed have
    // only three columns; they decode with both set to zero, which costs one
    // full scan after the upgrade and no spurious transfers.
    fun decode(text: String): LastSyncState =
        LastSyncState(
            text.split(RECORD_SEP)
                .filter { it.isNotBlank() }
                .associate { line ->
                    val parts = line.split(FIELD_SEP, limit = 5)
                    parts[0] to SyncFingerprints(
                        local = parts[1],
                        remote = parts[2],
                        localSize = parts.getOrNull(3)?.toLongOrNull() ?: 0,
                        localModifiedMillis = parts.getOrNull(4)?.toLongOrNull() ?: 0
                    )
                }
        )
}
