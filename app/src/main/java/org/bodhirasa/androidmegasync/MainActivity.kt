package org.bodhirasa.androidmegasync

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.bodhirasa.androidmegasync.sync.ExclusionStore
import org.bodhirasa.androidmegasync.sync.PairDisplay
import org.bodhirasa.androidmegasync.sync.PairSyncer
import org.bodhirasa.androidmegasync.sync.SyncPair
import org.bodhirasa.androidmegasync.sync.SyncPairStore
import org.bodhirasa.androidmegasync.sync.SyncRuns

// The folder pairs the app knows about. Each row opens PairActivity; "Sync all" runs
// them one after another.
class MainActivity : AppCompatActivity() {

    private lateinit var pairStore: SyncPairStore
    private lateinit var pairList: ListView
    private lateinit var status: TextView
    private lateinit var syncAllButton: Button
    private lateinit var lockableButtons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairStore = SyncPairStore(this)
        // The upgrade path: hand the old single exclusion list to the pair that owned
        // it before anything reads exclusions per pair.
        ExclusionStore.migrateLegacyGlobalList(this)
        pairList = findViewById(R.id.pairList)
        status = findViewById(R.id.status)
        syncAllButton = findViewById(R.id.syncAll)
        setSyncButton(cancel = false)

        val addButton = findViewById<Button>(R.id.addPair)
        lockableButtons = listOf(addButton)

        pairList.emptyView = findViewById(R.id.emptyState)
        addButton.setOnClickListener { startActivity(Intent(this, PairActivity::class.java)) }
        syncAllButton.setOnClickListener {
            if (SyncRuns.running != null) SyncRuns.requestCancel() else syncAll()
        }
        SyncWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (SyncRuns.running != null) showRunInProgress()
    }

    private fun render() {
        val pairs = pairStore.load()
        val exclusions = ExclusionStore(this)
        pairList.adapter = object : ArrayAdapter<SyncPair>(this, R.layout.row_pair, pairs) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.row_pair, parent, false)
                val pair = getItem(position)!!
                view.findViewById<TextView>(R.id.pairTitle).text =
                    PairDisplay.title(readableLocalPath(pair.localTreeUri), pair.remoteRoot)
                view.findViewById<TextView>(R.id.pairSubtitle).text =
                    PairDisplay.exclusions(exclusions.load(pair.id).size)
                return view
            }
        }
        pairList.setOnItemClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, PairActivity::class.java)
                    .putExtra(PairActivity.EXTRA_PAIR_ID, pairs[position].id)
            )
        }
        syncAllButton.isEnabled = pairs.any { it.localTreeUri.isNotEmpty() }
    }

    private fun setSyncButton(cancel: Boolean) {
        syncAllButton.text = if (cancel) "Cancel" else "Sync all"
        syncAllButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (cancel) R.color.button_danger else R.color.button_primary)
        )
    }

    // A run started before this instance existed — after a rotation, or from a pair's
    // own screen. Show it as busy and wait for it rather than offering a second sync.
    private fun showRunInProgress() {
        setSyncButton(cancel = true)
        lockableButtons.forEach { it.isEnabled = false }
        pairList.isEnabled = false
        if (status.text.isEmpty()) status.text = "A sync is running…"
        whenSyncIdle {
            setSyncButton(cancel = false)
            lockableButtons.forEach { it.isEnabled = true }
            pairList.isEnabled = true
            render()
        }
    }

    // Pairs run in turn, not in parallel: they share one MEGA client and the phone's
    // storage, and a serial run keeps the status line meaningful. The whole batch holds
    // the process-wide lock, so a pair cannot also be synced from its own screen or by
    // the background worker while this runs.
    private fun syncAll() {
        val pairs = pairStore.load().filter { it.localTreeUri.isNotEmpty() }
        if (pairs.isEmpty()) {
            status.text = "Add a folder pair first."
            return
        }
        if (!SyncRuns.tryStart(SYNC_ALL_LABEL)) {
            status.text = "Another sync is already running."
            showRunInProgress()
            return
        }
        setSyncButton(cancel = true)
        lockableButtons.forEach { it.isEnabled = false }
        pairList.isEnabled = false
        status.text = "Syncing ${pairs.size} pair(s)…"
        Thread {
            val lines = mutableListOf<String>()
            for ((index, pair) in pairs.withIndex()) {
                if (SyncRuns.cancelRequested) break
                val heading = "Pair ${index + 1}/${pairs.size} — " +
                    PairDisplay.title(readableLocalPath(pair.localTreeUri), pair.remoteRoot)
                val done = lines.joinToString("\n")
                runOnUiThread {
                    if (!isDestroyed) status.text = listOf(done, heading).filter { it.isNotEmpty() }.joinToString("\n")
                }
                val result = runCatching {
                    PairSyncer.sync(
                        this,
                        pair,
                        onProgress = { msg ->
                            runOnUiThread {
                                if (!isDestroyed) {
                                    status.text = listOf(done, heading, msg).filter { it.isNotEmpty() }.joinToString("\n")
                                }
                            }
                        },
                        shouldCancel = { SyncRuns.cancelRequested }
                    )
                }
                lines += heading
                lines += result.fold(onSuccess = { syncSummary(it) }, onFailure = { "Sync failed: ${it.message}" })
            }
            val report = lines.joinToString("\n")
            val cancelled = SyncRuns.cancelRequested
            SyncRuns.finish()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setSyncButton(cancel = false)
                lockableButtons.forEach { it.isEnabled = true }
                pairList.isEnabled = true
                status.text = if (cancelled) "$report\nCancelled".trim() else report
                render()
            }
        }.start()
    }

    private companion object {
        const val SYNC_ALL_LABEL = "all pairs"
    }
}
