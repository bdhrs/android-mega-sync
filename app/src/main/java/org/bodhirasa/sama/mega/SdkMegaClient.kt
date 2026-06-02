package org.bodhirasa.sama.mega

import org.bodhirasa.sama.sync.FileEntry
import org.bodhirasa.sama.sync.Fingerprints
import org.bodhirasa.sama.sync.FolderSnapshot
import nz.mega.sdk.MegaApiAndroid
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaRequest
import nz.mega.sdk.MegaRequestListenerInterface
import nz.mega.sdk.MegaTransfer
import nz.mega.sdk.MegaTransferListenerInterface
import java.io.File
import java.util.concurrent.CountDownLatch

// Bridges the MEGA SDK's asynchronous, listener-based API to the synchronous
// MegaClient interface the sync engine expects. The Synchronizer drives one
// operation at a time, so blocking each call until its request/transfer
// finishes keeps the engine simple. Paths are relative to the sync pair's
// remote root, captured on the most recent listFolder() call.
class SdkMegaClient(
    private val api: MegaApiAndroid,
    private val cacheDir: File
) : MegaClient {

    private var remoteRootPath: String = ""

    override fun login(email: String, password: String): String {
        awaitRequest { api.login(email, password, it) }
        awaitRequest { api.fetchNodes(it) }
        return api.dumpSession() ?: error("MEGA login succeeded but no session was returned")
    }

    override fun resumeSession(sessionToken: String): Boolean {
        awaitRequest { api.fastLogin(sessionToken, it) }
        awaitRequest { api.fetchNodes(it) }
        return true
    }

    override fun currentSession(): String? = api.dumpSession()

    override fun listFolder(remotePath: String): FolderSnapshot {
        remoteRootPath = remotePath
        val root = rootNode() ?: return FolderSnapshot.EMPTY
        val entries = mutableListOf<FileEntry>()
        walk(root, "", entries)
        return FolderSnapshot(entries)
    }

    private fun walk(parent: MegaNode, prefix: String, out: MutableList<FileEntry>) {
        for (child in api.getChildren(parent)) {
            val name = child.name ?: continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isFolder) {
                out.add(FileEntry(path, isDir = true, size = 0, modifiedMillis = child.modificationTime * 1000, fingerprint = Fingerprints.DIR))
                walk(child, path, out)
            } else {
                val fp = child.fingerprint ?: "${child.size}:${child.modificationTime}"
                out.add(FileEntry(path, isDir = false, size = child.size, modifiedMillis = child.modificationTime * 1000, fingerprint = fp))
            }
        }
    }

    override fun download(remotePath: String): ByteArray {
        val node = nodeFor(remotePath) ?: error("No remote node at $remotePath")
        val temp = File.createTempFile("sama-dl", null, cacheDir)
        try {
            awaitTransfer { api.startDownload(node, temp.absolutePath, null, null, false, null, MegaTransfer.COLLISION_CHECK_FINGERPRINT, MegaTransfer.COLLISION_RESOLUTION_OVERWRITE, it) }
            return temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    override fun upload(remotePath: String, content: ByteArray) {
        val parentPath = remotePath.substringBeforeLast('/', "")
        val name = remotePath.substringAfterLast('/')
        val parent = nodeFor(parentPath) ?: error("No remote parent for $remotePath")
        val temp = File.createTempFile("sama-up", null, cacheDir)
        try {
            temp.writeBytes(content)
            awaitTransfer { api.startUpload(temp.absolutePath, parent, name, temp.lastModified() / 1000, null, true, false, null, it) }
        } finally {
            temp.delete()
        }
    }

    override fun delete(remotePath: String) {
        val node = nodeFor(remotePath) ?: return
        awaitRequest { api.remove(node, it) }
    }

    override fun makeDir(remotePath: String) {
        val parentPath = remotePath.substringBeforeLast('/', "")
        val name = remotePath.substringAfterLast('/')
        val parent = nodeFor(parentPath) ?: error("No remote parent for $remotePath")
        if (api.getChildren(parent).any { it.name == name && it.isFolder }) return
        awaitRequest { api.createFolder(name, parent, it) }
    }

    private fun rootNode(): MegaNode? {
        val base = api.rootNode ?: return null
        return if (remoteRootPath.isEmpty()) base else api.getNodeByPath(remoteRootPath, base)
    }

    private fun nodeFor(relativePath: String): MegaNode? {
        val root = rootNode() ?: return null
        return if (relativePath.isEmpty()) root else api.getNodeByPath(relativePath, root)
    }

    private fun awaitRequest(start: (MegaRequestListenerInterface) -> Unit) {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<MegaError>(1)
        start(object : MegaRequestListenerInterface {
            override fun onRequestStart(api: MegaApiJava, request: MegaRequest) {}
            override fun onRequestUpdate(api: MegaApiJava, request: MegaRequest) {}
            override fun onRequestTemporaryError(api: MegaApiJava, request: MegaRequest, e: MegaError) {}
            override fun onRequestFinish(api: MegaApiJava, request: MegaRequest, e: MegaError) {
                result[0] = e
                latch.countDown()
            }
        })
        latch.await()
        val e = result[0]
        if (e != null && e.errorCode != MegaError.API_OK) {
            error("MEGA request failed: ${e.errorString} (${e.errorCode})")
        }
    }

    private fun awaitTransfer(start: (MegaTransferListenerInterface) -> Unit) {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<MegaError>(1)
        start(object : MegaTransferListenerInterface {
            override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) {}
            override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {}
            override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {}
            override fun onTransferData(api: MegaApiJava, transfer: MegaTransfer, buffer: ByteArray): Boolean = true
            override fun onFolderTransferUpdate(
                api: MegaApiJava, transfer: MegaTransfer, stage: Int, folderCount: Long,
                createdFolderCount: Long, fileCount: Long, currentFolder: String?, currentFileLeafName: String?
            ) {}
            override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
                result[0] = e
                latch.countDown()
            }
        })
        latch.await()
        val e = result[0]
        if (e != null && e.errorCode != MegaError.API_OK) {
            error("MEGA transfer failed: ${e.errorString} (${e.errorCode})")
        }
    }
}
