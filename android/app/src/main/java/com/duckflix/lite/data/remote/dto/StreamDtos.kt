package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class StreamUrlRequest(
    val tmdbId: Int,
    val title: String,
    val year: String?,
    val type: String,
    val season: Int? = null,
    val episode: Int? = null
)

@JsonClass(generateAdapter = true)
data class SubtitleDto(
    val id: Int? = null,           // OpenSubtitles ID (null for embedded)
    val language: String,
    val languageCode: String? = null, // ISO language code (e.g., "en", "es")
    val url: String? = null,        // Download URL (null for embedded)
    val label: String? = null,
    val source: String = "opensubtitles", // "embedded" or "opensubtitles"
    val streamIndex: Int? = null    // Track index for embedded subtitles
)

@JsonClass(generateAdapter = true)
data class StreamUrlResponse(
    val streamUrl: String,
    val source: String, // "zurg" or "rd"
    val fileName: String?,
    val subtitles: List<SubtitleDto>? = null
)

@JsonClass(generateAdapter = true)
data class StreamUrlStartResponse(
    val immediate: Boolean,
    val streamUrl: String? = null,
    val source: String? = null,
    val fileName: String? = null,
    val jobId: String? = null,
    val message: String? = null,
    val subtitles: List<SubtitleDto>? = null
)

/**
 * Next episode info returned in progress response (for TV episodes)
 */
@JsonClass(generateAdapter = true)
data class ProgressNextEpisode(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val overview: String? = null
)

/**
 * Skip marker for intro, recap, or credits segments
 */
@JsonClass(generateAdapter = true)
data class SkipMarker(
    val start: Double,  // Start time in seconds
    val end: Double,    // End time in seconds
    val source: String? = null,  // "introdb", "chapters", or "chapters-heuristic"
    val label: String? = null,   // Optional label from chapters
    val confidence: Double? = null,  // Confidence score for introdb matches
    val hasPostCredits: Boolean? = null  // Only for credits - true if post-credits scene exists
)

/**
 * Container for all skip markers (intro, recap, credits)
 */
@JsonClass(generateAdapter = true)
data class SkipMarkers(
    val intro: SkipMarker? = null,
    val recap: SkipMarker? = null,
    val credits: SkipMarker? = null
)

@JsonClass(generateAdapter = true)
data class StreamProgressResponse(
    val status: String, // 'searching' | 'downloading' | 'completed' | 'error'
    val progress: Int,
    val message: String,
    val streamUrl: String? = null,
    val fileName: String? = null,
    val source: String? = null,
    val error: String? = null,
    val quality: String? = null, // e.g., "2160p", "1080p"
    val subtitles: List<SubtitleDto>? = null,
    val suggestBandwidthRetest: Boolean? = null, // True when bandwidth measurement may be stale or inaccurate
    val hasNextEpisode: Boolean? = null, // True if there's a next episode, false if series finale
    val nextEpisode: ProgressNextEpisode? = null, // Next episode info (resolved async from TMDB)
    val skipMarkers: SkipMarkers? = null // Intro/recap/credits skip timestamps
)

@JsonClass(generateAdapter = true)
data class ReportBadRequest(
    val jobId: String,
    val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class ReportBadResponse(
    val success: Boolean,
    val newJobId: String?,
    val reportedCount: Int?,
    val message: String?,
    val excludedCount: Int?
)

@JsonClass(generateAdapter = true)
data class SubtitleSearchRequest(
    val tmdbId: Int,
    val title: String,
    val year: String?,
    val type: String,
    val season: Int? = null,
    val episode: Int? = null,
    val languageCode: String? = null,
    val force: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class SubtitleSearchResponse(
    val subtitles: List<SubtitleDto>
)

@JsonClass(generateAdapter = true)
data class ForceSubtitleResponse(
    val success: Boolean,
    val subtitle: SubtitleDto? = null
)
