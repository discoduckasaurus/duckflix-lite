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

        // Initialize loading phrases cache with defaults
        LoadingPhrasesCache.setDefaults()

        // Fetch loading phrases from API
        applicationScope.launch {
            try {
                val response = api.getLoadingPhrases()
                LoadingPhrasesCache.setPhrases(response.phrasesA, response.phrasesB)
                println("[DuckFlixApp] Loading phrases loaded: ${response.phrasesA.size} A phrases, ${response.phrasesB.size} B phrases")
            } catch (e: Exception) {
                println("[DuckFlixApp] Failed to load loading phrases, using defaults: ${e.message}")
                // Defaults already set above
            }
        }
    }
}
