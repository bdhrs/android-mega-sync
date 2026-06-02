package org.bodhirasa.sama

import android.content.Context
import org.bodhirasa.sama.mega.FakeMegaClient
import org.bodhirasa.sama.mega.MegaClient
import org.bodhirasa.sama.mega.SdkMegaClient
import nz.mega.sdk.MegaApiAndroid
import java.io.File

// Single switch-point between the real SdkMegaClient (backed by MegaApiAndroid)
// and the in-memory FakeMegaClient used for offline development and tests.
// Set USE_FAKE = true to drive the app with the seeded fake vault.
object MegaClientProvider {

    private const val USE_FAKE = false
    private const val APP_KEY = "SamaSync"
    private const val USER_AGENT = "Sama/0.1"

    @Volatile
    private var instance: MegaClient? = null

    fun get(context: Context): MegaClient =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): MegaClient {
        if (USE_FAKE) {
            return FakeMegaClient(
                seedFiles = mapOf(
                    "Welcome.md" to "# Welcome to your fake MEGA vault\n".toByteArray(),
                    "Daily/2026-06-01.md" to "First daily note.\n".toByteArray()
                ),
                seedDirs = setOf("Daily")
            )
        }
        val cache = File(context.cacheDir, "mega").apply { mkdirs() }
        val api = MegaApiAndroid(APP_KEY, USER_AGENT, context.filesDir.absolutePath)
        return SdkMegaClient(api, cache)
    }
}
