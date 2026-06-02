package org.bodhirasa.sama.sync

class InMemoryLocalStore(
    seedFiles: Map<String, ByteArray> = emptyMap(),
    seedDirs: Set<String> = emptySet()
) : LocalStore {

    val files = seedFiles.toMutableMap()
    val dirs = seedDirs.toMutableSet()

    override fun snapshot(): FolderSnapshot {
        val entries = buildList {
            dirs.forEach { add(FileEntry(it, isDir = true, size = 0, modifiedMillis = 0, fingerprint = Fingerprints.DIR)) }
            files.forEach { (path, content) ->
                add(FileEntry(path, isDir = false, size = content.size.toLong(), modifiedMillis = 0, fingerprint = Fingerprints.of(content)))
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
    }

    override fun makeDir(path: String) {
        dirs.add(path)
    }
}
