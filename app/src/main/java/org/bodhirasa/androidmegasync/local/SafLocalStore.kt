package org.bodhirasa.androidmegasync.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.bodhirasa.androidmegasync.sync.FileEntry
import org.bodhirasa.androidmegasync.sync.Fingerprints
import org.bodhirasa.androidmegasync.sync.FolderSnapshot
import org.bodhirasa.androidmegasync.sync.LocalScanPolicy
import org.bodhirasa.androidmegasync.sync.LocalStore

class SafLocalStore(
    private val context: Context,
    private val treeUri: Uri,
    private val fingerprint: (ByteArray) -> String,
    private val policy: LocalScanPolicy = LocalScanPolicy()
) : LocalStore {

    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open local tree: $treeUri")

    // Derived exactly as DocumentFile.fromTreeUri does, so the cursor-based walk
    // and the DocumentFile-based write paths can never address different folders.
    private val rootDocumentId: String =
        if (DocumentsContract.isDocumentUri(context, treeUri)) DocumentsContract.getDocumentId(treeUri)
        else DocumentsContract.getTreeDocumentId(treeUri)

    override fun snapshot(): FolderSnapshot {
        val entries = mutableListOf<FileEntry>()
        walk(rootDocumentId, "", entries)
        return FolderSnapshot(entries)
    }

    // Lists only the immediate children of `path` (name, isDir) — no content read,
    // no recursion. Walking a whole tree upfront (as snapshot() does, for sync
    // diffing) is far too slow for a browse/pick UI; one level at a time keeps
    // each screen fast.
    fun listChildren(path: String): List<Pair<String, Boolean>> {
        val parentId = resolveDocumentId(path) ?: return emptyList()
        return queryChildren(parentId).map { it.name to it.isDir }
    }

    private fun walk(parentDocumentId: String, prefix: String, out: MutableList<FileEntry>) {
        for (child in queryChildren(parentDocumentId)) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (policy.isIgnored(path)) continue
            if (child.isDir) {
                out.add(FileEntry(path, isDir = true, size = 0, modifiedMillis = child.modifiedMillis, fingerprint = Fingerprints.DIR))
                walk(child.documentId, path, out)
            } else {
                val reusable = policy.reusableFingerprint(path, child.size, child.modifiedMillis, child.hasMetadata)
                val fp = reusable ?: fingerprint(readUri(documentUri(child.documentId)))
                out.add(FileEntry(path, isDir = false, size = child.size, modifiedMillis = child.modifiedMillis, fingerprint = fp))
            }
        }
    }

    private data class Child(
        val documentId: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val modifiedMillis: Long,
        // False when the provider left size or last-modified null, so callers
        // must not treat this file's metadata as a reliable change signal.
        val hasMetadata: Boolean
    )

    // One ContentProvider query returns every child's metadata at once. Reaching
    // the same data through DocumentFile costs a separate query per child for
    // each of name, mime type, size and last-modified — four round trips per
    // file, which is what made the sync's local scan take minutes on a real vault.
    //
    // Columns are looked up by name, never by the order they were requested in:
    // a DocumentsProvider is free to ignore the projection and return its own
    // column set (the platform's own default projection uses a different order),
    // and indexing positionally against such a provider would read a mime type
    // as a file name and mangle the whole snapshot.
    private fun queryChildren(parentDocumentId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        // A provider that cannot answer at all (revoked permission, unmounted
        // volume, dead provider process) must not look like an empty directory:
        // that would present the whole subtree as locally deleted and plan a
        // remote deletion for every file in it.
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: error("Cannot list children of $parentDocumentId in $treeUri")
        val children = mutableListOf<Child>()
        cursor.use {
            val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                error("Local storage provider returned no document id, name or mime type for $parentDocumentId")
            }
            while (it.moveToNext()) {
                val documentId = it.getString(idColumn) ?: continue
                val name = it.getString(nameColumn) ?: continue
                val hasSize = sizeColumn >= 0 && !it.isNull(sizeColumn)
                val hasModified = modifiedColumn >= 0 && !it.isNull(modifiedColumn)
                children.add(
                    Child(
                        documentId = documentId,
                        name = name,
                        isDir = it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (hasSize) it.getLong(sizeColumn) else 0L,
                        modifiedMillis = if (hasModified) it.getLong(modifiedColumn) else 0L,
                        hasMetadata = hasSize && hasModified
                    )
                )
            }
        }
        return children
    }

    private fun resolveDocumentId(path: String): String? {
        if (path.isEmpty()) return rootDocumentId
        var current = rootDocumentId
        for (segment in path.split("/")) {
            if (segment.isEmpty()) continue
            current = queryChildren(current).firstOrNull { it.name == segment }?.documentId ?: return null
        }
        return current
    }

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun read(path: String): ByteArray =
        readUri((resolve(path) ?: error("No local file at $path")).uri)

    private fun readUri(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot read $uri")

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
