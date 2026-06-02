package org.bodhirasa.sama.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val engine = SyncEngine()

    private fun file(path: String, fp: String, mtime: Long = 0): FileEntry =
        FileEntry(path, isDir = false, size = fp.length.toLong(), modifiedMillis = mtime, fingerprint = fp)

    private fun dir(path: String): FileEntry =
        FileEntry(path, isDir = true, size = 0, modifiedMillis = 0, fingerprint = "DIR")

    private fun snap(vararg e: FileEntry) = FolderSnapshot(e.toList())

    // Baseline where the file had the given fingerprint on both sides last sync.
    private fun synced(vararg p: Pair<String, String>) =
        LastSyncState(p.associate { (path, fp) -> path to SyncFingerprints(fp, fp) })

    @Test
    fun newLocalFileUploads() {
        val plan = engine.diff(snap(file("a.md", "v1")), snap(), LastSyncState.EMPTY)
        assertEquals(listOf(SyncAction.Upload("a.md")), plan.actions)
    }

    @Test
    fun newRemoteFileDownloads() {
        val plan = engine.diff(snap(), snap(file("a.md", "v1")), LastSyncState.EMPTY)
        assertEquals(listOf(SyncAction.Download("a.md")), plan.actions)
    }

    @Test
    fun unchangedFileIsNoOp() {
        val plan = engine.diff(snap(file("a.md", "v1")), snap(file("a.md", "v1")), synced("a.md" to "v1"))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun deletedRemotelyDeletesLocal() {
        val plan = engine.diff(snap(file("a.md", "v1")), snap(), synced("a.md" to "v1"))
        assertEquals(listOf(SyncAction.DeleteLocal("a.md")), plan.actions)
    }

    @Test
    fun deletedLocallyDeletesRemote() {
        val plan = engine.diff(snap(), snap(file("a.md", "v1")), synced("a.md" to "v1"))
        assertEquals(listOf(SyncAction.DeleteRemote("a.md")), plan.actions)
    }

    @Test
    fun localChangedRemoteUnchangedUploads() {
        val plan = engine.diff(
            snap(file("a.md", "v2")),
            snap(file("a.md", "v1")),
            synced("a.md" to "v1")
        )
        assertEquals(listOf(SyncAction.Upload("a.md")), plan.actions)
    }

    @Test
    fun remoteChangedLocalUnchangedDownloads() {
        val plan = engine.diff(
            snap(file("a.md", "v1")),
            snap(file("a.md", "v2")),
            synced("a.md" to "v1")
        )
        assertEquals(listOf(SyncAction.Download("a.md")), plan.actions)
    }

    @Test
    fun bothChangedNewerLocalWins() {
        val plan = engine.diff(
            snap(file("a.md", "local2", mtime = 200)),
            snap(file("a.md", "remote2", mtime = 100)),
            synced("a.md" to "v1")
        )
        assertEquals(listOf(SyncAction.Upload("a.md")), plan.actions)
    }

    @Test
    fun bothChangedNewerRemoteWins() {
        val plan = engine.diff(
            snap(file("a.md", "local2", mtime = 100)),
            snap(file("a.md", "remote2", mtime = 200)),
            synced("a.md" to "v1")
        )
        assertEquals(listOf(SyncAction.Download("a.md")), plan.actions)
    }

    @Test
    fun noHistoryBothExistNewerWins() {
        // First sync, file already differs: newer modification time wins, no conflict copy.
        val plan = engine.diff(
            snap(file("note.md", "phoneEdit", mtime = 500)),
            snap(file("note.md", "oldRemote", mtime = 100)),
            LastSyncState.EMPTY
        )
        assertEquals(listOf(SyncAction.Upload("note.md")), plan.actions)
    }

    @Test
    fun editLocalVersusDeleteRemoteKeepsEdit() {
        val plan = engine.diff(snap(file("a.md", "v2")), snap(), synced("a.md" to "v1"))
        assertEquals(listOf(SyncAction.Upload("a.md")), plan.actions)
    }

    @Test
    fun editRemoteVersusDeleteLocalKeepsEdit() {
        val plan = engine.diff(snap(), snap(file("a.md", "v2")), synced("a.md" to "v1"))
        assertEquals(listOf(SyncAction.Download("a.md")), plan.actions)
    }

    @Test
    fun ignoredFilesAreSkipped() {
        val engineWithIgnore = SyncEngine(ignore = GlobIgnoreRule(listOf(".obsidian/workspace.json")))
        val plan = engineWithIgnore.diff(
            snap(file(".obsidian/workspace.json", "x"), file("note.md", "v1")),
            snap(),
            LastSyncState.EMPTY
        )
        assertEquals(listOf(SyncAction.Upload("note.md")), plan.actions)
    }

    @Test
    fun newNestedDirsMakeParentBeforeChildOnRemote() {
        val plan = engine.diff(
            snap(dir("docs"), dir("docs/sub"), file("docs/sub/a.md", "v1")),
            snap(),
            LastSyncState.EMPTY
        )
        assertEquals(
            listOf(
                SyncAction.MakeDirRemote("docs"),
                SyncAction.MakeDirRemote("docs/sub"),
                SyncAction.Upload("docs/sub/a.md")
            ),
            plan.actions
        )
    }
}
