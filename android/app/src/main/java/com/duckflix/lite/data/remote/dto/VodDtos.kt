package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VodSessionCheckResponse(
    val success: Boolean,
    val message: String?
)

@JsonClass(generateAdapter = true)
data class VodHeartbeatResponse(
    val success: Boolean
)

@JsonClass(generateAdapter = true)
data class NextEpisodeResponse(
    val hasNext: Boolean,
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val inCurrentPack: Boolean
)

@JsonClass(generateAdapter = true)
data class MovieRecommendationItem(
    val tmdbId: Int,
    val title: String,
    val year: String?,
    val posterUrl: String?
)

@JsonClass(generateAdapter = true)
data class MovieRecommendationsResponse(
    val recommendations: List<MovieRecommendationItem>
)

// Continue Watching with Download States
@JsonClass(generateAdapter = true)
data class ContinueWatchingItem(
    val itemId: String,
    val tmdbId: Int,
    val type: String,
    val title: String,
    val posterPath: String?,
    val season: Int? = null,
    val episode: Int? = null,

    // Playback progress
    val position: Long = 0,
    val duration: Long = 0,

    // Download state
    val isDownloading: Boolean = false,
    val isFailed: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadStatus: String? = null,
    val downloadMessage: String? = null,
    val errorMessage: String? = null,
    val jobId: String? = null,

    val updatedAt: String
) {
    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

    val isReadyToPlay: Boolean
        get() = !isDownloading && !isFailed && position >= 0

    val progressPercent: Int
        get() = if (isDownloading) downloadProgress
                else if (duration > 0) ((position * 100) / duration).toInt()
                else 0

    val displayState: DisplayState
        get() = when {
            isFailed -> DisplayState.ERROR
            isDownloading -> DisplayState.DOWNLOADING
            position > 0 -> DisplayState.IN_PROGRESS
            else -> DisplayState.READY
        }
}

enum class DisplayState {
    READY,        // Normal tile, ready to play
    IN_PROGRESS,  // Has watch progress, resume playback
    DOWNLOADING,  // Active download, show spinner
    ERROR         // Failed download, show error icon
}

@JsonClass(generateAdapter = true)
data class ContinueWatchingResponse(
    val continueWatching: List<ContinueWatchingItem>,
    val activeDownloads: Int = 0,
    val failedDownloads: Int = 0
)

// Loading Phrases
@JsonClass(generateAdapter = true)
data class LoadingPhrasesResponse(
    val phrasesA: List<String>,
    val phrasesB: List<String>
)
