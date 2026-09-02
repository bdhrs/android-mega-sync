package org.bodhirasa.androidmegasync.sync

import android.content.Context

class ExclusionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<String> =
        prefs.getString(KEY_PATHS, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun save(paths: List<String>) {
        prefs.edit().putString(KEY_PATHS, paths.joinToString("\n")).apply()
    }

    private companion object {
        const val PREFS_NAME = "exclusions"
        const val KEY_PATHS = "paths"
    }
}
