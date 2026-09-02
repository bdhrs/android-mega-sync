package org.bodhirasa.androidmegasync

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import org.bodhirasa.androidmegasync.sync.SyncRuns

// A screen recreated part-way through a sync has no callback waiting for it — the
// thread that started the run is reporting to an instance that is gone. Polling the
// process-wide state is the cheapest way for the new instance to notice the run
// finishing and put its buttons back.
fun AppCompatActivity.whenSyncIdle(onIdle: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    val tick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (SyncRuns.running == null) onIdle() else handler.postDelayed(this, POLL_MILLIS)
        }
    }
    handler.postDelayed(tick, POLL_MILLIS)
}

private const val POLL_MILLIS = 500L
