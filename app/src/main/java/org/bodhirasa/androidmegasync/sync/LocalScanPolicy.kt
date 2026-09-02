package org.bodhirasa.androidmegasync.sync

// The two rules that decide how much work a local scan does per file: whether
// the file is scanned at all, and whether its fingerprint can be reused from
// the last sync instead of reading and hashing its content.
//
// Kept out of SafLocalStore so both rules are unit-testable — SafLocalStore
// itself needs a live ContentResolver and cannot run in a JVM unit test.
class LocalScanPolicy(
    private val ignore: IgnoreRule = IgnoreRule.NONE,
    private val cachedFingerprint: (path: String, size: Long, modifiedMillis: Long) -> String? =
        { _, _, _ -> null }
) {

    // Excluded paths are pruned during the walk rather than filtered afterwards
    // in SyncEngine, so an excluded folder costs no directory listings, no
    // content reads and no hashing at all.
    fun isIgnored(path: String): Boolean = ignore.isIgnored(path)

    // The fingerprint to reuse for an unchanged file, or null when the file must
    // be read and hashed. hasMetadata is false when the storage provider left
    // size or last-modified null, in which case there is no reliable change
    // signal and the content is always read.
    fun reusableFingerprint(
        path: String,
        size: Long,
        modifiedMillis: Long,
        hasMetadata: Boolean
    ): String? =
        if (!hasMetadata) null else cachedFingerprint(path, size, modifiedMillis)

    companion object {
        // Reuses the fingerprint recorded at the last sync when the file's size
        // and modification time are both unchanged — the same quick-check rsync
        // uses by default. An edit leaving both identical is missed until the
        // file is touched again; modification time is used only as a local cache
        // key and never compared between the local and remote sides, so the
        // engine's mtime-independent cross-device identity is unaffected.
        fun fromLastSync(ignore: IgnoreRule, last: LastSyncState): LocalScanPolicy =
            LocalScanPolicy(ignore) { path, size, modifiedMillis ->
                last.entries[path]
                    ?.takeIf {
                        it.localModifiedMillis != 0L &&
                            it.localSize == size &&
                            it.localModifiedMillis == modifiedMillis
                    }
                    ?.local
            }
    }
}
