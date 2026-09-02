package org.bodhirasa.androidmegasync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanPolicyTest {

    @Test
    fun ignoredFileIsPruned() {
        val policy = LocalScanPolicy(PathListIgnoreRule(listOf(".obsidian")))
        assertTrue(policy.isIgnored(".obsidian"))
        assertTrue(policy.isIgnored(".obsidian/workspace.json"))
        assertFalse(policy.isIgnored("notes/a.md"))
    }

    @Test
    fun nothingIsPrunedByDefault() {
        assertFalse(LocalScanPolicy().isIgnored(".obsidian/workspace.json"))
    }

    @Test
    fun fingerprintIsNotReusedWithoutACache() {
        assertNull(LocalScanPolicy().reusableFingerprint("a.md", 10, 100, hasMetadata = true))
    }

    @Test
    fun fingerprintIsReusedWhenSizeAndModifiedTimeMatch() {
        val policy = policyCaching("a.md", size = 10, modifiedMillis = 100, fingerprint = "10:abc")
        assertEquals("10:abc", policy.reusableFingerprint("a.md", 10, 100, hasMetadata = true))
    }

    @Test
    fun fingerprintIsNotReusedWhenSizeOrModifiedTimeDiffers() {
        val policy = policyCaching("a.md", size = 10, modifiedMillis = 100, fingerprint = "10:abc")
        assertNull(policy.reusableFingerprint("a.md", 11, 100, hasMetadata = true))
        assertNull(policy.reusableFingerprint("a.md", 10, 101, hasMetadata = true))
        assertNull(policy.reusableFingerprint("b.md", 10, 100, hasMetadata = true))
    }

    @Test
    fun fingerprintIsNotReusedWhenProviderGaveNoMetadata() {
        // A provider that returns null size or last-modified leaves no reliable
        // change signal, so the content must be read even on an exact match.
        val policy = policyCaching("a.md", size = 10, modifiedMillis = 100, fingerprint = "10:abc")
        assertNull(policy.reusableFingerprint("a.md", 10, 100, hasMetadata = false))
    }

    // Built through fromLastSync, not a hand-written lambda, so these cases fail
    // if the real size/modified-time comparison is broken or removed.
    private fun policyCaching(path: String, size: Long, modifiedMillis: Long, fingerprint: String) =
        LocalScanPolicy.fromLastSync(
            IgnoreRule.NONE,
            LastSyncState(mapOf(path to SyncFingerprints(fingerprint, "remote", size, modifiedMillis)))
        )
}
