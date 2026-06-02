package org.bodhirasa.sama.sync

import org.bodhirasa.sama.mega.FakeMegaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SynchronizerTest {

    private fun engine() = SyncEngine()
    private fun bytes(s: String) = s.toByteArray()
    private fun str(b: ByteArray) = String(b)

    @Test
    fun bidirectionalNewFilesConverge() {
        val local = InMemoryLocalStore(seedFiles = mapOf("a.md" to bytes("A")))
        val mega = FakeMegaClient(seedFiles = mapOf("b.md" to bytes("B")))
        val sync = Synchronizer(mega, local, engine())

        val result = sync.sync(remoteRoot = "", last = LastSyncState.EMPTY)

        assertEquals(setOf("a.md", "b.md"), local.files.keys)
        assertEquals("A", str(local.files["a.md"]!!))
        assertEquals("B", str(local.files["b.md"]!!))
        assertEquals(1, result.uploaded)
        assertEquals(1, result.downloaded)

        // Second sync with the recorded baseline is a no-op.
        val again = sync.sync(remoteRoot = "", last = result.newState)
        assertEquals(0, again.uploaded + again.downloaded + again.deletedLocal + again.deletedRemote)
    }

    @Test
    fun deletePropagates() {
        val local = InMemoryLocalStore(seedFiles = mapOf("a.md" to bytes("A")))
        val mega = FakeMegaClient(seedFiles = mapOf("a.md" to bytes("A")))
        val sync = Synchronizer(mega, local, engine())

        // First sync establishes the baseline.
        val baseline = sync.sync(remoteRoot = "", last = LastSyncState.EMPTY).newState

        // Delete locally, then sync: the deletion should propagate to remote.
        local.delete("a.md")
        val result = sync.sync(remoteRoot = "", last = baseline)

        assertTrue(local.files.isEmpty())
        assertEquals(1, result.deletedRemote)
        assertTrue(mega.listFolder("").entries.isEmpty())
    }

    @Test
    fun bothChangedNewerWinsNoConflictCopy() {
        // Same file edited on both sides since the baseline. Equal mtimes (0) ->
        // local wins by the tie rule. Crucially: no ~conflict copy is created.
        val local = InMemoryLocalStore(seedFiles = mapOf("note.md" to bytes("ORIGINAL")))
        val mega = FakeMegaClient(seedFiles = mapOf("note.md" to bytes("ORIGINAL")))
        val sync = Synchronizer(mega, local, engine())
        val baseline = sync.sync(remoteRoot = "", last = LastSyncState.EMPTY).newState

        local.write("note.md", bytes("LOCAL EDIT"))
        mega.upload("note.md", bytes("REMOTE EDIT"))
        val result = sync.sync(remoteRoot = "", last = baseline)

        assertEquals("LOCAL EDIT", str(local.files["note.md"]!!))
        assertEquals("LOCAL EDIT", str(mega.download("note.md")))
        assertEquals(setOf("note.md"), local.files.keys) // no ~conflict file
        assertEquals(setOf("note.md"), mega.listFolder("").byPath.keys)
        assertEquals(1, result.uploaded)
    }
}
