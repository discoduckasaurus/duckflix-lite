package com.duckflix.lite.utils

/**
 * Singleton cache for loading phrases fetched from API
 */
object LoadingPhrasesCache {
    var phrasesA: List<String> = emptyList()
        private set

    var phrasesB: List<String> = emptyList()
        private set

    fun setPhrases(phrasesA: List<String>, phrasesB: List<String>) {
        this.phrasesA = phrasesA
        this.phrasesB = phrasesB
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
