package org.bodhirasa.androidmegasync

import org.bodhirasa.androidmegasync.sync.SyncResult

// The one wording for a finished run, so a single pair and "Sync all" report alike.
fun syncSummary(result: SyncResult): String {
    val head = if (result.cancelled) "Sync cancelled" else "Sync done"
    return "$head — up ${result.uploaded}, down ${result.downloaded}, " +
        "del local ${result.deletedLocal}, del mega ${result.deletedRemote}, " +
        "dirs ${result.dirsCreated}\n${result.scanSummary}"
}
