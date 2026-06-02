package org.bodhirasa.sama.mega

import org.bodhirasa.sama.sync.FolderSnapshot

interface MegaClient {

    fun login(email: String, password: String): String

    fun resumeSession(sessionToken: String): Boolean

    fun currentSession(): String?

    // Paths passed to and returned from these methods are relative to the
    // sync pair's remote root, so the engine never deals in MEGA-absolute paths.
    fun listFolder(remotePath: String): FolderSnapshot

    fun download(remotePath: String): ByteArray

    fun upload(remotePath: String, content: ByteArray)

    fun delete(remotePath: String)

    fun makeDir(remotePath: String)
}
