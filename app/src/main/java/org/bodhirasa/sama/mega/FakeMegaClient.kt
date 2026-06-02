package org.bodhirasa.sama.mega

import org.bodhirasa.sama.sync.FileEntry
import org.bodhirasa.sama.sync.Fingerprints
import org.bodhirasa.sama.sync.FolderSnapshot

class FakeMegaClient(
    seedFiles: Map<String, ByteArray> = emptyMap(),
    seedDirs: Set<String> = emptySet()
) : MegaClient {

    private val files = seedFiles.toMutableMap()
    private val dirs = seedDirs.toMutableSet()
    private var session: String? = null

    override fun login(email: String, password: String): String {
        session = "fake-session-for-$email"
        return session!!
    }

    override fun resumeSession(sessionToken: String): Boolean {
        session = sessionToken
        return true
    }

    override fun currentSession(): String? = session

    override fun listFolder(remotePath: String): FolderSnapshot {
        val entries = buildList {
            dirs.forEach { add(FileEntry(it, isDir = true, size = 0, modifiedMillis = 0, fingerprint = Fingerprints.DIR)) }
            files.forEach { (path, content) ->
                add(FileEntry(path, isDir = false, size = content.size.toLong(), modifiedMillis = 0, fingerprint = Fingerprints.of(content)))
            }
        }
        return FolderSnapshot(entries)
    }

    override fun download(remotePath: String): ByteArray =
        files[remotePath] ?: error("No remote file at $remotePath")

    override fun upload(remotePath: String, content: ByteArray) {
        files[remotePath] = content
    }

    override fun delete(remotePath: String) {
        files.remove(remotePath)
        dirs.remove(remotePath)
    }

    override fun makeDir(remotePath: String) {
        dirs.add(remotePath)
    }

    override fun contentFingerprint(content: ByteArray): String = Fingerprints.of(content)
}
