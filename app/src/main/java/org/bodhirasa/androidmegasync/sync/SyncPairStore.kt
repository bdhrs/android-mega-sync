package org.bodhirasa.androidmegasync.sync

import android.content.Context

class SyncPairStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<SyncPair> =
        prefs.getString(KEY_PAIRS, null)?.let { SyncPairCodec.decode(it) } ?: emptyList()

    fun save(pairs: List<SyncPair>) {
        prefs.edit().putString(KEY_PAIRS, SyncPairCodec.encode(pairs)).apply()
    }

    fun single(): SyncPair? = load().firstOrNull()

    fun setSingle(pair: SyncPair) = save(listOf(pair))

    private companion object {
        const val PREFS_NAME = "sync_pairs"
        const val KEY_PAIRS = "pairs"
    }
}
