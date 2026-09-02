package org.bodhirasa.androidmegasync.sync

import android.content.Context

class SyncPairStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<SyncPair> =
        prefs.getString(KEY_PAIRS, null)?.let { SyncPairCodec.decode(it) } ?: emptyList()

    fun save(pairs: List<SyncPair>) {
        prefs.edit().putString(KEY_PAIRS, SyncPairCodec.encode(pairs)).apply()
    }

    fun find(id: String): SyncPair? = load().firstOrNull { it.id == id }

    // Ids are handed out from a stored counter, never derived from the current pairs
    // alone: deleting the highest-numbered pair must not free its id for reuse, and two
    // "add pair" screens open at once must not be given the same one.
    fun allocateId(): String {
        val number = allocatedPairNumber(prefs.getInt(KEY_NEXT_NUMBER, 0), load())
        prefs.edit().putInt(KEY_NEXT_NUMBER, number + 1).apply()
        return "$PAIR_ID_PREFIX$number"
    }

    // Replaces the pair with the same id, keeping its position in the list, or appends
    // it when the id is new.
    fun upsert(pair: SyncPair) = save(upsertPair(load(), pair))

    // A pair's exclusions and sync baseline go with it. A baseline left behind is the
    // most dangerous thing in this app: entries with no local counterpart are planned
    // as remote deletions, so an orphaned one must never outlive its pair.
    fun remove(id: String) {
        save(removePair(load(), id))
        ExclusionStore(app).remove(id)
        LastSyncStore(app).remove(id)
    }

    private companion object {
        const val PREFS_NAME = "sync_pairs"
        const val KEY_PAIRS = "pairs"
        const val KEY_NEXT_NUMBER = "next_number"
    }
}
