package org.bodhirasa.androidmegasync.sync

import android.content.Context

class LastSyncStore(context: Context) {

    private val prefs = context.getSharedPreferences("last_sync", Context.MODE_PRIVATE)

    fun load(pairId: String): LastSyncState =
        prefs.getString(pairId, null)?.let { LastSyncCodec.decode(it) } ?: LastSyncState.EMPTY

    fun save(pairId: String, state: LastSyncState) {
        prefs.edit().putString(pairId, LastSyncCodec.encode(state)).apply()
    }
}
