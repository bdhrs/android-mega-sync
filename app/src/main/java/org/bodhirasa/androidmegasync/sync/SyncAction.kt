package org.bodhirasa.androidmegasync.sync

sealed interface SyncAction {
    val path: String

    data class Upload(override val path: String) : SyncAction
    data class Download(override val path: String) : SyncAction
    data class DeleteLocal(override val path: String) : SyncAction
    data class DeleteRemote(override val path: String) : SyncAction
    data class MakeDirLocal(override val path: String) : SyncAction
    data class MakeDirRemote(override val path: String) : SyncAction
}

data class SyncPlan(val actions: List<SyncAction>) {
    val isEmpty: Boolean get() = actions.isEmpty()

    companion object {
        val EMPTY = SyncPlan(emptyList())
    }
}
