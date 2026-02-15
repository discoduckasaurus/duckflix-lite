package com.duckflix.lite

import android.app.Application
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.utils.LoadingPhrasesCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DuckFlixApplication : Application() {

    @Inject
    lateinit var api: DuckFlixApi

    // Global exception handler for uncaught coroutine exceptions
    // This prevents app crashes from network errors during internet outages
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        println("[DuckFlixApp] Uncaught coroutine exception: ${throwable.javaClass.simpleName} - ${throwable.message}")
        throwable.printStackTrace()
        // Don't rethrow - let the app continue running
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    override fun onCreate() {
        super.onCreate()

        // Set global uncaught exception handler for non-coroutine threads
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Log the error but let the default handler decide if it's fatal
            println("[DuckFlixApp] Uncaught exception on ${thread.name}: ${throwable.javaClass.simpleName} - ${throwable.message}")
            throwable.printStackTrace()

            // Let the default handler handle it (may crash for truly fatal errors)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Load cached phrases immediately (synchronous, fast)
        // This runs before Hilt injection completes, so phrases are ready instantly
        LoadingPhrasesCache.initFromStorage(this)

        // Refresh phrases from API in background (only if stale or never fetched)
        if (LoadingPhrasesCache.needsRetry()) {
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
        } else {
            println("[DuckFlixApp] Loading phrases cache is fresh, skipping API fetch")
        }
    }
}
