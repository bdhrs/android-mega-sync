package org.bodhirasa.androidmegasync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastSyncCodecTest {

    @Test
    fun roundTripsFingerprintsAndCacheKeys() {
        val state = LastSyncState(
            mapOf("notes/a.md" to SyncFingerprints("10:aaa", "10:bbb", 10, 1_700_000_000_000))
        )
        val decoded = LastSyncCodec.decode(LastSyncCodec.encode(state))
        assertEquals(state, decoded)
    }

    @Test
    fun decodesLegacyThreeFieldRecords() {
        // Written before the size/modified-time cache columns existed.
        val decoded = LastSyncCodec.decode("notes/a.md\t10:aaa\t10:bbb")
        val entry = decoded.entries.getValue("notes/a.md")
        assertEquals("10:aaa", entry.local)
        assertEquals("10:bbb", entry.remote)
        assertEquals(0, entry.localSize)
        assertEquals(0, entry.localModifiedMillis)
    }

    @Test
    fun legacyRecordsNeverProduceACacheHit() {
        val last = LastSyncCodec.decode("notes/a.md\t10:aaa\t10:bbb")
        val policy = LocalScanPolicy.fromLastSync(IgnoreRule.NONE, last)
        assertNull(policy.reusableFingerprint("notes/a.md", 0, 0, hasMetadata = true))
    }

    @Test
    fun cachedFingerprintIsReusedOnlyOnAnExactMetadataMatch() {
        val last = LastSyncState(
            mapOf("notes/a.md" to SyncFingerprints("10:aaa", "10:bbb", 10, 1_700_000_000_000))
        )
        val policy = LocalScanPolicy.fromLastSync(IgnoreRule.NONE, last)
        assertEquals("10:aaa", policy.reusableFingerprint("notes/a.md", 10, 1_700_000_000_000, true))
        assertNull(policy.reusableFingerprint("notes/a.md", 11, 1_700_000_000_000, true))
        assertNull(policy.reusableFingerprint("notes/a.md", 10, 1_700_000_000_001, true))
        assertNull(policy.reusableFingerprint("notes/missing.md", 10, 1_700_000_000_000, true))
    }

    @Test
    fun baselineRecordsTheLocalSideMetadata() {
        val local = FolderSnapshot(listOf(FileEntry("a.md", false, 7, 123, "7:aaa")))
        val remote = FolderSnapshot(listOf(FileEntry("a.md", false, 7, 999, "7:bbb")))
        val entry = LastSyncState.pair(local, remote).entries.getValue("a.md")
        assertEquals("7:aaa", entry.local)
        assertEquals("7:bbb", entry.remote)
        assertEquals(7, entry.localSize)
        assertEquals(123, entry.localModifiedMillis)
    }
}
