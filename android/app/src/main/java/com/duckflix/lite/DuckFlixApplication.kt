package com.duckflix.lite

import android.app.Application
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.utils.LoadingPhrasesCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DuckFlixApplication : Application() {

    @Inject
    lateinit var api: DuckFlixApi

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Load cached phrases immediately (synchronous, fast)
        // This runs before Hilt injection completes, so phrases are ready instantly
        LoadingPhrasesCache.initFromStorage(this)

        // Refresh phrases from API in background
        applicationScope.launch {
            try {
                val response = api.getLoadingPhrases()
                LoadingPhrasesCache.setPhrases(response.phrasesA, response.phrasesB)
                println("[DuckFlixApp] Loading phrases refreshed: ${response.phrasesA.size} A, ${response.phrasesB.size} B")
            } catch (e: Exception) {
                println("[DuckFlixApp] Failed to refresh loading phrases: ${e.message}")
                // Cached or default phrases already loaded
            }
        }
    }
}
