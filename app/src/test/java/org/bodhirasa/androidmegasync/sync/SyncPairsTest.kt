package org.bodhirasa.androidmegasync.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPairsTest {

    @Test
    fun firstIdIsPairOne() {
        assertEquals("pair-1", nextPairId(emptyList()))
    }

    @Test
    fun skipsExistingIds() {
        val existing = listOf(pair("pair-1"), pair("pair-2"))
        assertEquals("pair-3", nextPairId(existing))
    }

    @Test
    fun continuesPastHighestEvenWhenLowerIdsWereDeleted() {
        assertEquals("pair-8", nextPairId(listOf(pair("pair-7"))))
    }

    @Test
    fun ignoresIdsThatAreNotNumbered() {
        assertEquals("pair-1", nextPairId(listOf(pair("legacy"))))
    }

    @Test
    fun ignoresNumericIdsWithoutThePairPrefix() {
        assertEquals("pair-1", nextPairId(listOf(pair("7"))))
    }

    @Test
    fun allocatedNumberNeverReissuesADeletedHighestId() {
        // pair-3 was created then deleted: the counter stands at 4, the remaining pairs
        // only reach 2, and 3 must not come back or the new pair inherits pair-3's
        // exclusions and sync baseline.
        assertEquals(4, allocatedPairNumber(storedCounter = 4, existing = listOf(pair("pair-1"), pair("pair-2"))))
    }

    @Test
    fun allocatedNumberRisesAboveStoredPairsWhenTheCounterIsMissing() {
        assertEquals(3, allocatedPairNumber(storedCounter = 0, existing = listOf(pair("pair-2"))))
    }

    @Test
    fun upsertKeepsAnExistingPairsPosition() {
        val pairs = listOf(pair("pair-1"), pair("pair-2"))
        val edited = SyncPair("pair-1", remoteRoot = "Vault", localTreeUri = "content://tree/a")
        assertEquals(listOf(edited, pair("pair-2")), upsertPair(pairs, edited))
    }

    @Test
    fun upsertAppendsAnUnknownPair() {
        assertEquals(listOf(pair("pair-1"), pair("pair-2")), upsertPair(listOf(pair("pair-1")), pair("pair-2")))
    }

    @Test
    fun removeDropsOnlyTheNamedPair() {
        assertEquals(listOf(pair("pair-2")), removePair(listOf(pair("pair-1"), pair("pair-2")), "pair-1"))
    }

    @Test
    fun titleNamesBothSides() {
        assertEquals("Vault  →  Obsidian/Vault", PairDisplay.title("Vault", "Obsidian/Vault"))
    }

    @Test
    fun titleMarksAnUnconfiguredSide() {
        assertEquals("(no local folder)  →  (vault root)", PairDisplay.title("", ""))
    }

    @Test
    fun exclusionCountIsSingularForOne() {
        assertEquals("No exclusions", PairDisplay.exclusions(0))
        assertEquals("1 exclusion", PairDisplay.exclusions(1))
        assertEquals("4 exclusions", PairDisplay.exclusions(4))
    }

    private fun pair(id: String) = SyncPair(id, remoteRoot = "", localTreeUri = "")
}
