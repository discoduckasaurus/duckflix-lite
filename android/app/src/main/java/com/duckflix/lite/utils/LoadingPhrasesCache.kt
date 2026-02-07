package com.duckflix.lite.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Singleton cache for loading phrases with persistent storage.
 * Loads cached phrases synchronously on startup, then refreshes from API in background.
 * If initial fetch fails, retries on next content load.
 */
object LoadingPhrasesCache {
    private const val PREFS_NAME = "loading_phrases_cache"
    private const val KEY_PHRASES_A = "phrases_a"
    private const val KEY_PHRASES_B = "phrases_b"
    private const val SEPARATOR = "|||"

    var phrasesA: List<String> = emptyList()
        private set

    var phrasesB: List<String> = emptyList()
        private set

    private var prefs: SharedPreferences? = null

    // Track whether we've successfully fetched from API this session
    @Volatile
    var hasFetchedFromApi: Boolean = false
        private set

    /**
     * Initialize cache from persistent storage. Call this early in Application.onCreate()
     * before Hilt injection completes. This is synchronous and fast.
     */
    fun initFromStorage(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val storedA = prefs?.getString(KEY_PHRASES_A, null)
        val storedB = prefs?.getString(KEY_PHRASES_B, null)

        if (storedA != null && storedB != null) {
            phrasesA = storedA.split(SEPARATOR).filter { it.isNotBlank() }
            phrasesB = storedB.split(SEPARATOR).filter { it.isNotBlank() }
            println("[LoadingPhrases] Loaded ${phrasesA.size} A, ${phrasesB.size} B phrases from cache")
        } else {
            // No cache - use defaults
            setDefaults()
            println("[LoadingPhrases] No cache found, using defaults")
        }
    }

    /**
     * Update phrases from API response and persist to storage
     */
    fun setPhrases(phrasesA: List<String>, phrasesB: List<String>) {
        this.phrasesA = phrasesA
        this.phrasesB = phrasesB
        this.hasFetchedFromApi = true

        // Persist to SharedPreferences
        prefs?.edit()?.apply {
            putString(KEY_PHRASES_A, phrasesA.joinToString(SEPARATOR))
            putString(KEY_PHRASES_B, phrasesB.joinToString(SEPARATOR))
            apply()
        }
        println("[LoadingPhrases] Cached ${phrasesA.size} A, ${phrasesB.size} B phrases to storage")
    }

    /**
     * Check if we need to retry fetching phrases from API
     */
    fun needsRetry(): Boolean = !hasFetchedFromApi

    fun setDefaults() {
        phrasesA = listOf(
            "Loading",
            "Buffering",
            "Processing",
            "Preparing",
            "Fetching",
            "Analyzing",
            "Calibrating",
            "Downloading",
            "Caching",
            "Streaming"
        )
        phrasesB = listOf(
            "content",
            "data",
            "buffers",
            "packets",
            "frames",
            "assets",
            "algorithms",
            "cache",
            "streams"
        )
    }

    fun isInitialized(): Boolean {
        return phrasesA.isNotEmpty() && phrasesB.isNotEmpty()
    }
}
