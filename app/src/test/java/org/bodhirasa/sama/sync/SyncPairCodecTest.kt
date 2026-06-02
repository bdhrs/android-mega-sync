package org.bodhirasa.sama.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPairCodecTest {

    @Test
    fun roundTripsSinglePair() {
        val pairs = listOf(
            SyncPair(id = "p1", remoteRoot = "Obsidian/Vault", localTreeUri = "content://tree/primary%3AVault")
        )
        assertEquals(pairs, SyncPairCodec.decode(SyncPairCodec.encode(pairs)))
    }

    @Test
    fun roundTripsMultiplePairs() {
        val pairs = listOf(
            SyncPair("p1", "Obsidian/Vault", "content://tree/a"),
            SyncPair("p2", "Backups", "content://tree/b")
        )
        assertEquals(pairs, SyncPairCodec.decode(SyncPairCodec.encode(pairs)))
    }

    @Test
    fun decodesEmptyAsEmptyList() {
        assertEquals(emptyList<SyncPair>(), SyncPairCodec.decode(""))
    }
}
