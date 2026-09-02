package org.bodhirasa.androidmegasync.sync

class InMemoryLocalStore(
    seedFiles: Map<String, ByteArray> = emptyMap(),
    seedDirs: Set<String> = emptySet(),
    seedModified: Map<String, Long> = emptyMap(),
    // Mirrors SafLocalStore: the same policy decides which paths are walked at
    // all and which fingerprints are reused instead of recomputed, so the
    // Synchronizer tests exercise the real scan rules rather than assuming them.
    private val policy: LocalScanPolicy = LocalScanPolicy()
) : LocalStore {

    val files = seedFiles.toMutableMap()
    val dirs = seedDirs.toMutableSet()
    val modified = seedModified.toMutableMap()

    override fun snapshot(): FolderSnapshot {
        val entries = buildList {
            dirs.filterNot { policy.isIgnored(it) }.forEach {
                add(FileEntry(it, isDir = true, size = 0, modifiedMillis = 0, fingerprint = Fingerprints.DIR))
            }
            files.forEach { (path, content) ->
                if (policy.isIgnored(path)) return@forEach
                val size = content.size.toLong()
                val modifiedMillis = modified[path] ?: 0
                val reusable = policy.reusableFingerprint(path, size, modifiedMillis, hasMetadata = true)
                add(
                    FileEntry(
                        path,
                        isDir = false,
                        size = size,
                        modifiedMillis = modifiedMillis,
                        fingerprint = reusable ?: Fingerprints.of(content)
                    )
                )
            }
        }
        return FolderSnapshot(entries)
    }

    override fun read(path: String): ByteArray = files[path] ?: error("No local file at $path")

    override fun write(path: String, content: ByteArray) {
        files[path] = content
    }

    override fun delete(path: String) {
        files.remove(path)
        dirs.remove(path)
        modified.remove(path)
    }

    override fun makeDir(path: String) {
        dirs.add(path)
    }
}
