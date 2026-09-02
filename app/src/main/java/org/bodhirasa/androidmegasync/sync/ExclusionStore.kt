package org.bodhirasa.androidmegasync.sync

import android.content.Context

class ExclusionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(pairId: String): List<String> =
        prefs.getString(pairId, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun save(pairId: String, paths: List<String>) {
        prefs.edit().putString(pairId, paths.joinToString("\n")).apply()
    }

    fun remove(pairId: String) {
        prefs.edit().remove(pairId).apply()
    }

    companion object {
        private const val PREFS_NAME = "exclusions"
        private const val LEGACY_KEY_PATHS = "paths"

        // Before pairs were plural there was one exclusion list under a fixed key. Hand
        // it to the pair that owned it rather than dropping the user's list on upgrade,
        // and keep it until there is a pair to receive it. Called from every entry point
        // that can reach a sync, because a background sync running before the app is
        // next opened would otherwise sync files the user had excluded.
        fun migrateLegacyGlobalList(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val legacy = prefs.getString(LEGACY_KEY_PATHS, null) ?: return
            val firstPairId = SyncPairStore(context).load().firstOrNull()?.id ?: return
            if (prefs.getString(firstPairId, null) == null) {
                prefs.edit().putString(firstPairId, legacy).remove(LEGACY_KEY_PATHS).apply()
            } else {
                prefs.edit().remove(LEGACY_KEY_PATHS).apply()
            }
        }
    }
}
