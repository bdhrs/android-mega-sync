package org.bodhirasa.sama

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.bodhirasa.sama.local.SafLocalStore
import org.bodhirasa.sama.sync.LastSyncStore
import org.bodhirasa.sama.sync.SyncEngine
import org.bodhirasa.sama.sync.SyncPair
import org.bodhirasa.sama.sync.SyncPairStore
import org.bodhirasa.sama.sync.Synchronizer

class MainActivity : AppCompatActivity() {

    private val pairId = "pair-1"
    private lateinit var pairStore: SyncPairStore
    private lateinit var lastSyncStore: LastSyncStore
    private lateinit var status: TextView

    @Volatile
    private var sessionResumed = false

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

        findViewById<Button>(R.id.pickLocal).setOnClickListener { pickLocal.launch(null) }
        findViewById<Button>(R.id.pickRemote).setOnClickListener { browseRemote() }
        findViewById<Button>(R.id.syncNow).setOnClickListener { syncNow() }
        findViewById<Button>(R.id.logout).setOnClickListener {
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

    private fun syncNow() {
        val pair = pairStore.single()
        if (pair == null || pair.localTreeUri.isEmpty()) {
            status.text = "Pick a local folder first."
            return
        }
        status.text = "Syncing…"
        Thread {
            val result = runCatching {
                ensureSession()
                val local = SafLocalStore(this, Uri.parse(pair.localTreeUri))
                val sync = Synchronizer(MegaClientProvider.get(this), local, SyncEngine())
                sync.sync(pair.remoteRoot, lastSyncStore.load(pairId)).also {
                    lastSyncStore.save(pairId, it.newState)
                }
            }
            runOnUiThread {
                status.text = result.fold(
                    onSuccess = {
                        "Sync done — up ${it.uploaded}, down ${it.downloaded}, " +
                            "del local ${it.deletedLocal}, del remote ${it.deletedRemote}, " +
                            "dirs ${it.dirsCreated}"
                    },
                    onFailure = { "Sync failed: ${it.message}" }
                )
            }
        }.start()
    }

    private fun render() {
        val pair = pairStore.single()
        val local = pair?.localTreeUri?.takeIf { it.isNotEmpty() } ?: "(none)"
        val remote = pair?.remoteRoot?.takeIf { it.isNotEmpty() } ?: "(vault root)"
        status.text = "Local: $local\nRemote: $remote"
    }
}
