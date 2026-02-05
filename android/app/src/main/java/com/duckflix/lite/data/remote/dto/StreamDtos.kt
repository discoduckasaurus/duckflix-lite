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
    val subtitles: List<SubtitleDto>? = null
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
    val episode: Int? = null
)

@JsonClass(generateAdapter = true)
data class SubtitleSearchResponse(
    val subtitles: List<SubtitleDto>
)
