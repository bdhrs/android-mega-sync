package org.bodhirasa.androidmegasync.sync

// Which sync, if any, this process is running. Activities are recreated on rotation and
// can be killed outright, so a run's state cannot live in one: without this, a
// recreated screen shows idle buttons and can start a second sync of the same pair,
// with both runs writing that pair's baseline.
object SyncRuns {

    @Volatile
    private var label: String? = null

    @Volatile
    private var cancel = false

    // The run in progress, for a screen to show, or null when idle.
    val running: String?
        get() = label

    val cancelRequested: Boolean
        get() = cancel

    @Synchronized
    fun tryStart(label: String): Boolean {
        if (this.label != null) return false
        this.label = label
        cancel = false
        return true
    }

    @Synchronized
    fun finish() {
        label = null
        cancel = false
    }

    fun requestCancel() {
        cancel = true
    }
}
