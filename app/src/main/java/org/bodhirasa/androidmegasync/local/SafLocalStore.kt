package org.bodhirasa.androidmegasync.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.bodhirasa.androidmegasync.sync.FileEntry
import org.bodhirasa.androidmegasync.sync.Fingerprints
import org.bodhirasa.androidmegasync.sync.FolderSnapshot
import org.bodhirasa.androidmegasync.sync.LocalStore

class SafLocalStore(
    private val context: Context,
    treeUri: Uri,
    private val fingerprint: (ByteArray) -> String
) : LocalStore {

    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open local tree: $treeUri")

    override fun snapshot(): FolderSnapshot {
        val entries = mutableListOf<FileEntry>()
        walk(root, "", entries)
        return FolderSnapshot(entries)
    }

    // Lists only the immediate children of `path` (name, isDir) — no content read,
    // no recursion. Each SAF listFiles() call is its own ContentProvider round trip,
    // so walking a whole tree upfront (as snapshot() does, for sync diffing) is far
    // too slow for a browse/pick UI; one level at a time keeps each screen fast.
    fun listChildren(path: String): List<Pair<String, Boolean>> {
        val dir = if (path.isEmpty()) root else resolve(path) ?: return emptyList()
        return dir.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            name to child.isDirectory
        }
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<FileEntry>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                out.add(FileEntry(path, isDir = true, size = 0, modifiedMillis = child.lastModified(), fingerprint = Fingerprints.DIR))
                walk(child, path, out)
            } else {
                val content = readDoc(child)
                out.add(FileEntry(path, isDir = false, size = child.length(), modifiedMillis = child.lastModified(), fingerprint = fingerprint(content)))
            }
        }
    }

    override fun read(path: String): ByteArray =
        readDoc(resolve(path) ?: error("No local file at $path"))

    private fun readDoc(doc: DocumentFile): ByteArray =
        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            ?: error("Cannot read ${doc.uri}")

    override fun write(path: String, content: ByteArray) {
        val segments = path.split("/")
        val parent = ensureDir(segments.dropLast(1))
        val name = segments.last()
        val existing = parent.findFile(name)
        existing?.delete()
        val file = parent.createFile("application/octet-stream", name)
            ?: error("Cannot create local file $path")
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(content) }
            ?: error("Cannot write $path")
    }

    override fun delete(path: String) {
        resolve(path)?.delete()
    }

    override fun makeDir(path: String) {
        ensureDir(path.split("/"))
    }

    private fun resolve(path: String): DocumentFile? {
        var current: DocumentFile? = root
        for (segment in path.split("/")) {
            current = current?.findFile(segment) ?: return null
        }
        return current
    }

    private fun ensureDir(segments: List<String>): DocumentFile {
        var current = root
        for (segment in segments) {
            if (segment.isEmpty()) continue
            current = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: current.createDirectory(segment)
                ?: error("Cannot create directory segment '$segment'")
        }
        return current
    }
}
