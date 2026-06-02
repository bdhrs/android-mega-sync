package org.bodhirasa.sama.sync

interface LocalStore {
    fun snapshot(): FolderSnapshot
    fun read(path: String): ByteArray
    fun write(path: String, content: ByteArray)
    fun delete(path: String)
    fun makeDir(path: String)
}
