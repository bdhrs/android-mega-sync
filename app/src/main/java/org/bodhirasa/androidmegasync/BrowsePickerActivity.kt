package org.bodhirasa.androidmegasync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.bodhirasa.androidmegasync.local.SafLocalStore
import org.bodhirasa.androidmegasync.sync.ExclusionStore
import org.bodhirasa.androidmegasync.sync.FileEntry
import org.bodhirasa.androidmegasync.sync.SyncPair
import org.bodhirasa.androidmegasync.sync.SyncPairStore

// One row in the browse list: ".." (go up) has path == null.
private data class Row(val name: String, val path: String?, val isDir: Boolean)

class BrowsePickerActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var pathList: ListView
    private lateinit var root: String
    private lateinit var pair: SyncPair

    private var currentPath = ""
    private var rows: List<Row> = emptyList()
    private val selected = mutableSetOf<String>()

    // Fetched once per session — MEGA's node tree is already cached locally by the
    // SDK, so a full recursive listFolder() is cheap; only the local SAF side needs
    // one-directory-at-a-time loading to stay fast (see SafLocalStore.listChildren).
    private var remoteEntries: List<FileEntry>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse_picker)

        status = findViewById(R.id.status)
        pathList = findViewById(R.id.pathList)
        val excludeButton = findViewById<Button>(R.id.exclude)

        root = intent.getStringExtra(EXTRA_ROOT) ?: ROOT_LOCAL
        title = if (root == ROOT_LOCAL) "Browse Local" else "Browse MEGA"
        val storedPair = SyncPairStore(this).single()
        if (storedPair == null || (root == ROOT_LOCAL && storedPair.localTreeUri.isEmpty())) {
            status.text = "Pick a folder on this side first."
            excludeButton.isEnabled = false
            return
        }
        pair = storedPair
        selected.addAll(ExclusionStore(this).load())

        excludeButton.setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SELECTED_PATHS, ArrayList(selected)))
            finish()
        }

        load(currentPath)
    }

    private fun load(path: String) {
        currentPath = path
        status.text = "Loading…"
        Thread {
            val result = runCatching { childRows(path) }
            runOnUiThread {
                // A faster later navigation can finish before an earlier one; only
                // apply this load's result if it's still the folder being shown.
                if (isFinishing || path != currentPath) return@runOnUiThread
                result.fold(
                    onSuccess = { children ->
                        rows = (if (path.isEmpty()) emptyList() else listOf(Row("..", null, isDir = true))) + children
                        renderStatus()
                        pathList.adapter = RowAdapter()
                    },
                    onFailure = { status.text = "Couldn't load: ${it.message}" }
                )
            }
        }.start()
    }

    private fun childRows(path: String): List<Row> {
        val children = if (root == ROOT_LOCAL) {
            val client = MegaClientProvider.get(this)
            SafLocalStore(this, Uri.parse(pair.localTreeUri), client::contentFingerprint)
                .listChildren(path)
                .map { (name, isDir) -> name to isDir }
        } else {
            val client = MegaClientProvider.get(this)
            if (client.currentSession() == null) {
                SessionStore(this).token?.let { client.resumeSession(it) }
            }
            val entries = remoteEntries ?: client.listFolder(pair.remoteRoot).entries.also { remoteEntries = it }
            entries.filter { it.path.substringBeforeLast('/', "") == path }
                .map { it.path.substringAfterLast('/') to it.isDir }
        }
        return children.sortedWith(compareBy({ !it.second }, { it.first }))
            .map { (name, isDir) -> Row(name, if (path.isEmpty()) name else "$path/$name", isDir) }
    }

    private fun renderStatus() {
        val where = if (root == ROOT_LOCAL) "Local" else "MEGA"
        val here = currentPath.ifEmpty { "(root)" }
        status.text = "$where — $here (${selected.size} selected)"
    }

    private inner class RowAdapter : ArrayAdapter<Row>(this@BrowsePickerActivity, R.layout.row_browse_item, rows) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.row_browse_item, parent, false)
            val row = getItem(position)!!
            val nameView = view.findViewById<TextView>(R.id.name)
            val checkBox = view.findViewById<CheckBox>(R.id.check)
            nameView.text = if (row.isDir && row.path != null) "${row.name}/" else row.name

            if (row.path == null) {
                checkBox.visibility = View.INVISIBLE
                checkBox.setOnClickListener(null)
                view.setOnClickListener { load(currentPath.substringBeforeLast('/', "")) }
            } else {
                checkBox.visibility = View.VISIBLE
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = row.path in selected
                checkBox.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(row.path) else selected.remove(row.path)
                    renderStatus()
                }
                view.setOnClickListener {
                    if (row.isDir) load(row.path) else checkBox.toggle()
                }
            }
            return view
        }
    }

    companion object {
        const val EXTRA_ROOT = "root"
        const val EXTRA_SELECTED_PATHS = "selected_paths"
        const val ROOT_LOCAL = "local"
        const val ROOT_REMOTE = "remote"
    }
}
