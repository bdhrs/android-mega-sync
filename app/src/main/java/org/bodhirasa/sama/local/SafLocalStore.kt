package org.bodhirasa.sama.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.bodhirasa.sama.sync.FileEntry
import org.bodhirasa.sama.sync.Fingerprints
import org.bodhirasa.sama.sync.FolderSnapshot
import org.bodhirasa.sama.sync.LocalStore

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
