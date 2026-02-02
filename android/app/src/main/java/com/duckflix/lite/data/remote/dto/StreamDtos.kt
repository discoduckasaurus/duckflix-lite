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
data class StreamUrlResponse(
    val streamUrl: String,
    val source: String, // "zurg" or "rd"
    val fileName: String?
)

@JsonClass(generateAdapter = true)
data class StreamUrlStartResponse(
    val immediate: Boolean,
    val streamUrl: String? = null,
    val source: String? = null,
    val fileName: String? = null,
    val jobId: String? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class StreamProgressResponse(
    val status: String, // 'searching' | 'downloading' | 'completed' | 'error'
    val progress: Int,
    val message: String,
    val streamUrl: String? = null,
    val fileName: String? = null,
    val source: String? = null,
    val error: String? = null
)
