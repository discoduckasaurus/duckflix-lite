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
    private const val KEY_LAST_FETCHED = "last_fetched_at"
    private const val SEPARATOR = "|||"
    private const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours

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

        // Persist to SharedPreferences with fetch timestamp
        prefs?.edit()?.apply {
            putString(KEY_PHRASES_A, phrasesA.joinToString(SEPARATOR))
            putString(KEY_PHRASES_B, phrasesB.joinToString(SEPARATOR))
            putLong(KEY_LAST_FETCHED, System.currentTimeMillis())
            apply()
        }
        println("[LoadingPhrases] Cached ${phrasesA.size} A, ${phrasesB.size} B phrases to storage")
    }

    /**
     * Check if we need to fetch phrases from API.
     * Returns true if we haven't fetched this session, or if the cache is older than 24 hours.
     */
    fun needsRetry(): Boolean {
        if (!hasFetchedFromApi) {
            val lastFetched = prefs?.getLong(KEY_LAST_FETCHED, 0L) ?: 0L
            if (lastFetched == 0L) return true // Never fetched
            val age = System.currentTimeMillis() - lastFetched
            return age >= STALE_THRESHOLD_MS
        }
        return false
    }

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
