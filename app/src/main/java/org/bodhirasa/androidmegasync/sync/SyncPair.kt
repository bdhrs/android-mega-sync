package org.bodhirasa.androidmegasync.sync

data class SyncPair(
    val id: String,
    val remoteRoot: String,
    val localTreeUri: String
)

const val PAIR_ID_PREFIX = "pair-"

// The lowest number no stored pair has used. Pure so it can be unit-tested without a
// Context; it is only the floor for SyncPairStore.allocateId, which also remembers the
// numbers it has already handed out — a deleted pair's id must never come back, or a
// new pair would inherit the old one's exclusions and sync baseline.
fun nextPairNumber(existing: List<SyncPair>): Int {
    val highest = existing
        .mapNotNull { it.id.takeIf { id -> id.startsWith(PAIR_ID_PREFIX) }?.removePrefix(PAIR_ID_PREFIX)?.toIntOrNull() }
        .maxOrNull() ?: 0
    return highest + 1
}

fun nextPairId(existing: List<SyncPair>): String = "$PAIR_ID_PREFIX${nextPairNumber(existing)}"

// The number SyncPairStore hands out: the higher of the counter it has already reached
// and the floor above the stored pairs. Deleting the highest-numbered pair therefore
// does not free its id — the counter has moved past it.
fun allocatedPairNumber(storedCounter: Int, existing: List<SyncPair>): Int =
    maxOf(storedCounter, nextPairNumber(existing))

// Position matters: the first pair in the list is the one the legacy exclusion list is
// migrated onto, so replacing a pair must not move it.
fun upsertPair(pairs: List<SyncPair>, pair: SyncPair): List<SyncPair> =
    if (pairs.any { it.id == pair.id }) pairs.map { if (it.id == pair.id) pair else it } else pairs + pair

fun removePair(pairs: List<SyncPair>, id: String): List<SyncPair> = pairs.filterNot { it.id == id }

object SyncPairCodec {

    private const val FIELD_SEP = "\t"
    private const val RECORD_SEP = "\n"

    fun encode(pairs: List<SyncPair>): String =
        pairs.joinToString(RECORD_SEP) { listOf(it.id, it.remoteRoot, it.localTreeUri).joinToString(FIELD_SEP) }

    fun decode(text: String): List<SyncPair> =
        text.split(RECORD_SEP)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(FIELD_SEP)
                SyncPair(id = parts[0], remoteRoot = parts[1], localTreeUri = parts[2])
            }
}
