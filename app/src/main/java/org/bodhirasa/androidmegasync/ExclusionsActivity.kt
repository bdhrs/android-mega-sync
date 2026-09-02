package org.bodhirasa.androidmegasync

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.bodhirasa.androidmegasync.sync.ExclusionStore

// The exclusions of one folder pair.
class ExclusionsActivity : AppCompatActivity() {

    private lateinit var store: ExclusionStore
    private lateinit var pairId: String
    private lateinit var exclusionList: ListView

    // The picker pre-ticks existing exclusions and returns the full edited set —
    // unticking one there removes it, so this replaces rather than unions.
    private val browse = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val edited = result.data?.getStringArrayListExtra(BrowsePickerActivity.EXTRA_SELECTED_PATHS)
        if (result.resultCode == RESULT_OK && edited != null) {
            store.save(pairId, edited)
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exclusions)

        // Only ever launched for a pair. Without an id there is no bucket to read or
        // write, and defaulting to one would quietly edit the wrong pair's list.
        val requestedId = intent.getStringExtra(EXTRA_PAIR_ID)
        if (requestedId == null) {
            finish()
            return
        }
        pairId = requestedId
        store = ExclusionStore(this)
        exclusionList = findViewById(R.id.exclusionList)
        exclusionList.emptyView = findViewById(R.id.emptyState)

        findViewById<Button>(R.id.browseSource).setOnClickListener { browse(BrowsePickerActivity.ROOT_LOCAL) }
        findViewById<Button>(R.id.browseDestination).setOnClickListener { browse(BrowsePickerActivity.ROOT_REMOTE) }

        render()
    }

    private fun browse(root: String) {
        browse.launch(
            Intent(this, BrowsePickerActivity::class.java)
                .putExtra(BrowsePickerActivity.EXTRA_ROOT, root)
                .putExtra(BrowsePickerActivity.EXTRA_PAIR_ID, pairId)
        )
    }

    private fun render() {
        val paths = store.load(pairId)
        exclusionList.adapter = object : ArrayAdapter<String>(this, R.layout.row_exclusion, paths) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.row_exclusion, parent, false)
                val path = getItem(position)!!
                view.findViewById<TextView>(R.id.path).text = path
                view.findViewById<Button>(R.id.delete).setOnClickListener {
                    store.save(pairId, store.load(pairId) - path)
                    render()
                }
                return view
            }
        }
    }

    companion object {
        const val EXTRA_PAIR_ID = "pair_id"
    }
}
