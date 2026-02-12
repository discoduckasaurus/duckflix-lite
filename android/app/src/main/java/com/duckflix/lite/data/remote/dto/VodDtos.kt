package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.JsonClass
import java.net.URLDecoder

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
    val posterPath: String?
) {
    val posterUrl: String?
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }
}

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
    val logoPath: String? = null,  // Title logo for loading screen
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
        get() = posterPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it  // Already a full URL, use as-is
            } else {
                "https://image.tmdb.org/t/p/w500$it"  // TMDB path, prepend base URL
            }
        }

    val logoUrl: String?
        get() = logoPath?.let {
            if (it.startsWith("http://") || it.startsWith("https://")) {
                it
            } else {
                "https://image.tmdb.org/t/p/w500$it"
            }
        }

    // Decode URL-encoded title (server sends + for spaces)
    val displayTitle: String
        get() = try {
            URLDecoder.decode(title, "UTF-8")
        } catch (e: Exception) {
            title
        }

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
