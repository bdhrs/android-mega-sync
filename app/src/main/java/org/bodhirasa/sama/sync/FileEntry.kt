package org.bodhirasa.sama.sync

data class FileEntry(
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMillis: Long,
    val fingerprint: String
)

data class FolderSnapshot(val entries: List<FileEntry>) {
    val byPath: Map<String, FileEntry> = entries.associateBy { it.path }

    companion object {
        val EMPTY = FolderSnapshot(emptyList())
    }
}
