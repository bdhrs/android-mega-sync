package org.bodhirasa.sama.sync

// The local and remote fingerprints a file had at the last successful sync.
// The two sides use different fingerprint schemes and are never compared to
// each other — each side is only ever compared to its own last-recorded value.
data class SyncFingerprints(val local: String, val remote: String)

data class LastSyncState(val entries: Map<String, SyncFingerprints>) {
    companion object {
        val EMPTY = LastSyncState(emptyMap())

        // Baseline built from both sides after a successful sync: a file present
        // on both sides records each side's current fingerprint.
        fun pair(local: FolderSnapshot, remote: FolderSnapshot): LastSyncState =
            LastSyncState(
                (local.byPath.keys intersect remote.byPath.keys).associateWith { path ->
                    SyncFingerprints(local.byPath.getValue(path).fingerprint, remote.byPath.getValue(path).fingerprint)
                }
            )
    }
}

object LastSyncCodec {

    private const val FIELD_SEP = "\t"
    private const val RECORD_SEP = "\n"

    fun encode(state: LastSyncState): String =
        state.entries.entries.joinToString(RECORD_SEP) { (path, fp) ->
            listOf(path, fp.local, fp.remote).joinToString(FIELD_SEP)
        }

    fun decode(text: String): LastSyncState =
        LastSyncState(
            text.split(RECORD_SEP)
                .filter { it.isNotBlank() }
                .associate { line ->
                    val parts = line.split(FIELD_SEP, limit = 3)
                    parts[0] to SyncFingerprints(parts[1], parts[2])
                }
        )
}
