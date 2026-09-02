package org.bodhirasa.androidmegasync.sync

// How a pair is named on screen. A pair has no user-given name, so every label is
// derived from its folders; kept pure and in one place so the list row, the pair
// screen and the sync status all word an unconfigured side the same way.
object PairDisplay {

    const val NO_LOCAL = "(no local folder)"
    const val VAULT_ROOT = "(vault root)"

    fun localLabel(readablePath: String): String = readablePath.ifEmpty { NO_LOCAL }

    fun remoteLabel(remoteRoot: String): String = remoteRoot.ifEmpty { VAULT_ROOT }

    fun title(readableLocalPath: String, remoteRoot: String): String =
        "${localLabel(readableLocalPath)}  →  ${remoteLabel(remoteRoot)}"

    fun exclusions(count: Int): String = when (count) {
        0 -> "No exclusions"
        1 -> "1 exclusion"
        else -> "$count exclusions"
    }
}
