package org.bodhirasa.androidmegasync

import android.content.Context

// Re-establishes the MEGA session on the calling thread. Safe to call repeatedly;
// only resumes once per process, and never on the UI thread. The client is a process
// singleton, so this state belongs here rather than in each screen.
object MegaSession {

    @Volatile
    private var resumed = false

    fun ensure(context: Context) {
        if (resumed) return
        synchronized(this) {
            if (resumed) return
            val client = MegaClientProvider.get(context)
            // After a fresh login the singleton client is already authenticated;
            // only resume from the stored token when it isn't.
            if (client.currentSession() == null) {
                SessionStore(context).token?.let { client.resumeSession(it) }
            }
            resumed = true
        }
    }
}
