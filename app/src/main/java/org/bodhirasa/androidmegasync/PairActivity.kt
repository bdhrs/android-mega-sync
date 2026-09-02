package org.bodhirasa.androidmegasync

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.bodhirasa.androidmegasync.sync.ExclusionStore
import org.bodhirasa.androidmegasync.sync.PairDisplay
import org.bodhirasa.androidmegasync.sync.PairSyncer
import org.bodhirasa.androidmegasync.sync.SyncPair
import org.bodhirasa.androidmegasync.sync.SyncPairStore
import org.bodhirasa.androidmegasync.sync.SyncRuns

// One folder pair: its two folders, its exclusions, and a manual sync. Opened with no
// pair id to add a new pair, which is only stored once one of its folders is picked.
class PairActivity : AppCompatActivity() {

    private lateinit var pairStore: SyncPairStore
    private lateinit var pair: SyncPair
    private lateinit var localValue: TextView
    private lateinit var remoteValue: TextView
    private lateinit var exclusionsButton: Button
    private lateinit var status: TextView
    private lateinit var syncButton: Button
    private lateinit var lockableButtons: List<Button>
    private var dialog: AlertDialog? = null

    private val pickLocal = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            update(pair.copy(localTreeUri = uri.toString()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)

        pairStore = SyncPairStore(this)
        // The id must survive rotation and process death: the launching Intent carries
        // none for a new pair, so a recreated screen would otherwise allocate a second
        // id and orphan the pair the user had just configured.
        val requestedId = intent.getStringExtra(EXTRA_PAIR_ID)
        val restoredId = savedInstanceState?.getString(STATE_PAIR_ID)
        val id = restoredId ?: requestedId ?: pairStore.allocateId()
        val stored = pairStore.find(id)
        // Reached by opening a pair that has since been deleted elsewhere. Editing a
        // ghost would silently create a new pair under a dead id.
        if (restoredId == null && requestedId != null && stored == null) {
            finish()
            return
        }
        pair = stored ?: SyncPair(id, "", "")
        title = if (stored == null) "Add folder pair" else "Folder pair"

        localValue = findViewById(R.id.localValue)
        remoteValue = findViewById(R.id.remoteValue)
        exclusionsButton = findViewById(R.id.exclusions)
        status = findViewById(R.id.status)
        syncButton = findViewById(R.id.syncNow)

        val pickLocalButton = findViewById<Button>(R.id.pickLocal)
        val pickRemoteButton = findViewById<Button>(R.id.pickRemote)
        val deleteButton = findViewById<Button>(R.id.deletePair)
        lockableButtons = listOf(pickLocalButton, pickRemoteButton, exclusionsButton, deleteButton)

        pickLocalButton.setOnClickListener { pickLocal.launch(null) }
        pickRemoteButton.setOnClickListener { browseRemote() }
        exclusionsButton.setOnClickListener {
            startActivity(
                Intent(this, ExclusionsActivity::class.java)
                    .putExtra(ExclusionsActivity.EXTRA_PAIR_ID, pair.id)
            )
        }
        syncButton.setOnClickListener {
            if (SyncRuns.running != null) SyncRuns.requestCancel() else syncNow()
        }
        deleteButton.setOnClickListener { confirmDelete() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAIR_ID, pair.id)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (SyncRuns.running != null) showRunInProgress()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    // A pair only earns a place in the list once it has a folder, so an abandoned
    // "add" leaves nothing behind. Both picks persist — including MEGA's vault root,
    // which is an empty remote path and must not read as "nothing chosen".
    private fun update(updated: SyncPair) {
        pair = updated
        pairStore.upsert(pair)
        render()
    }

    private fun browseRemote() {
        status.text = "Loading MEGA folders…"
        Thread {
            val result = runCatching {
                MegaSession.ensure(this)
                MegaClientProvider.get(this).listFolder("").entries
                    .filter { it.isDir }
                    .map { it.path }
                    .sorted()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { dirs -> showRemoteDialog(dirs) },
                    onFailure = { status.text = "Couldn't load MEGA folders: ${it.message}" }
                )
            }
        }.start()
    }

    private fun showRemoteDialog(dirs: List<String>) {
        val options = listOf("[ ${PairDisplay.VAULT_ROOT} ]") + dirs
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle("Pick MEGA folder")
            .setItems(options.toTypedArray()) { _, which ->
                update(pair.copy(remoteRoot = if (which == 0) "" else dirs[which - 1]))
            }
            .setOnDismissListener { render() }
            .show()
    }

    private fun confirmDelete() {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle("Delete this folder pair?")
            .setMessage("Its exclusions and sync history are forgotten. No files are deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                pairStore.remove(pair.id)
                finish()
            }
            .show()
    }

    private fun setSyncButton(cancel: Boolean) {
        syncButton.text = if (cancel) "Cancel" else "Sync now"
        syncButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (cancel) R.color.button_danger else R.color.button_primary)
        )
    }

    // A run started before this instance existed — after a rotation, or by the list
    // screen. Show it as busy and wait for it rather than offering a second sync.
    private fun showRunInProgress() {
        setSyncButton(cancel = true)
        lockableButtons.forEach { it.isEnabled = false }
        if (status.text.isEmpty()) status.text = "A sync is running…"
        whenSyncIdle {
            setSyncButton(cancel = false)
            lockableButtons.forEach { it.isEnabled = true }
            render()
        }
    }

    private fun syncNow() {
        if (pair.localTreeUri.isEmpty()) {
            status.text = "Pick a local folder first."
            return
        }
        if (!SyncRuns.tryStart(pair.id)) {
            status.text = "Another sync is already running."
            showRunInProgress()
            return
        }
        setSyncButton(cancel = true)
        lockableButtons.forEach { it.isEnabled = false }
        status.text = "Syncing…"
        Thread {
            val result = runCatching {
                PairSyncer.sync(
                    this,
                    pair,
                    onProgress = { msg -> runOnUiThread { if (!isDestroyed) status.text = msg } },
                    shouldCancel = { SyncRuns.cancelRequested }
                )
            }
            SyncRuns.finish()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setSyncButton(cancel = false)
                lockableButtons.forEach { it.isEnabled = true }
                status.text = result.fold(
                    onSuccess = { syncSummary(it) },
                    onFailure = { "Sync failed: ${it.message}" }
                )
            }
        }.start()
    }

    private fun render() {
        localValue.text = PairDisplay.localLabel(readableLocalPath(pair.localTreeUri))
        remoteValue.text = PairDisplay.remoteLabel(pair.remoteRoot)
        exclusionsButton.text = "Exclusions — ${PairDisplay.exclusions(ExclusionStore(this).load(pair.id).size)}"
    }

    companion object {
        const val EXTRA_PAIR_ID = "pair_id"
        private const val STATE_PAIR_ID = "state_pair_id"
    }
}
