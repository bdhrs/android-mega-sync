package org.bodhirasa.androidmegasync

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.bodhirasa.androidmegasync.local.SafLocalStore
import org.bodhirasa.androidmegasync.sync.ExclusionStore
import org.bodhirasa.androidmegasync.sync.LastSyncStore
import org.bodhirasa.androidmegasync.sync.PathListIgnoreRule
import org.bodhirasa.androidmegasync.sync.SyncEngine
import org.bodhirasa.androidmegasync.sync.SyncPair
import org.bodhirasa.androidmegasync.sync.SyncPairStore
import org.bodhirasa.androidmegasync.sync.Synchronizer

class MainActivity : AppCompatActivity() {

    private val pairId = "pair-1"
    private lateinit var pairStore: SyncPairStore
    private lateinit var lastSyncStore: LastSyncStore
    private lateinit var status: TextView
    private lateinit var syncButton: Button
    private lateinit var lockableButtons: List<Button>

    @Volatile
    private var sessionResumed = false

    @Volatile
    private var syncing = false

    @Volatile
    private var cancelRequested = false

    // Re-establishes the MEGA session on the calling background thread. Safe to
    // call repeatedly; only resumes once per process. Must not run on the UI thread.
    private fun ensureSession() {
        if (sessionResumed) return
        synchronized(this) {
            if (sessionResumed) return
            val client = MegaClientProvider.get(this)
            // After a fresh login the singleton client is already authenticated;
            // only resume from the stored token when it isn't.
            if (client.currentSession() == null) {
                SessionStore(this).token?.let { client.resumeSession(it) }
            }
            sessionResumed = true
        }
    }

    private val pickLocal = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val existing = pairStore.single()
            pairStore.setSingle(SyncPair(pairId, existing?.remoteRoot ?: "", uri.toString()))
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairStore = SyncPairStore(this)
        lastSyncStore = LastSyncStore(this)
        status = findViewById(R.id.status)
        syncButton = findViewById(R.id.syncNow)
        setSyncButton(cancel = false)

        val pickLocalButton = findViewById<Button>(R.id.pickLocal)
        val pickRemoteButton = findViewById<Button>(R.id.pickRemote)
        val exclusionsButton = findViewById<Button>(R.id.exclusions)
        val logoutButton = findViewById<Button>(R.id.logout)
        lockableButtons = listOf(pickLocalButton, pickRemoteButton, exclusionsButton, logoutButton)

        pickLocalButton.setOnClickListener { pickLocal.launch(null) }
        pickRemoteButton.setOnClickListener { browseRemote() }
        exclusionsButton.setOnClickListener {
            startActivity(Intent(this, ExclusionsActivity::class.java))
        }
        syncButton.setOnClickListener {
            if (syncing) cancelRequested = true else syncNow()
        }
        logoutButton.setOnClickListener {
            SessionStore(this).clear()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        render()
        SyncWorker.schedule(this)
    }

    private fun browseRemote() {
        status.text = "Loading MEGA folders…"
        Thread {
            val result = runCatching {
                ensureSession()
                MegaClientProvider.get(this).listFolder("").entries
                    .filter { it.isDir }
                    .map { it.path }
                    .sorted()
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { dirs -> showRemoteDialog(dirs) },
                    onFailure = { status.text = "Couldn't load MEGA folders: ${it.message}" }
                )
            }
        }.start()
    }

    private fun showRemoteDialog(dirs: List<String>) {
        val options = listOf("[ vault root ]") + dirs
        AlertDialog.Builder(this)
            .setTitle("Pick MEGA folder")
            .setItems(options.toTypedArray()) { _, which ->
                val remoteRoot = if (which == 0) "" else dirs[which - 1]
                val existing = pairStore.single()
                pairStore.setSingle(SyncPair(pairId, remoteRoot, existing?.localTreeUri ?: ""))
                render()
            }
            .show()
    }

    private fun setSyncButton(cancel: Boolean) {
        syncButton.text = if (cancel) "Cancel" else "Sync now"
        syncButton.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (cancel) "#C62828" else "#2E7D32"))
    }

    private fun syncNow() {
        val pair = pairStore.single()
        if (pair == null || pair.localTreeUri.isEmpty()) {
            status.text = "Pick a local folder first."
            return
        }
        syncing = true
        cancelRequested = false
        setSyncButton(cancel = true)
        lockableButtons.forEach { it.isEnabled = false }
        status.text = "Syncing…"
        Thread {
            val result = runCatching {
                ensureSession()
                val client = MegaClientProvider.get(this)
                val local = SafLocalStore(this, Uri.parse(pair.localTreeUri), client::contentFingerprint)
                val ignore = PathListIgnoreRule(ExclusionStore(this).load())
                val sync = Synchronizer(client, local, SyncEngine(ignore = ignore))
                sync.sync(
                    pair.remoteRoot,
                    lastSyncStore.load(pairId),
                    onProgress = { msg -> runOnUiThread { status.text = msg } },
                    shouldCancel = { cancelRequested }
                ).also {
                    lastSyncStore.save(pairId, it.newState)
                }
            }
            runOnUiThread {
                syncing = false
                setSyncButton(cancel = false)
                lockableButtons.forEach { it.isEnabled = true }
                status.text = result.fold(
                    onSuccess = {
                        val head = if (it.cancelled) "Sync cancelled" else "Sync done"
                        "$head — up ${it.uploaded}, down ${it.downloaded}, " +
                            "del local ${it.deletedLocal}, del mega ${it.deletedRemote}, " +
                            "dirs ${it.dirsCreated}"
                    },
                    onFailure = { "Sync failed: ${it.message}" }
                )
            }
        }.start()
    }

    private fun render() {
        val pair = pairStore.single()
        val local = pair?.localTreeUri?.takeIf { it.isNotEmpty() }?.let { readableLocalPath(it) } ?: "(none)"
        val remote = pair?.remoteRoot?.takeIf { it.isNotEmpty() } ?: "(vault root)"
        status.text = "Local: $local\nMEGA: $remote"
    }

    private fun readableLocalPath(uriString: String): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriString))
        docId.substringAfter(':', docId)
    }.getOrDefault(uriString)
}
