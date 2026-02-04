package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BandwidthReportRequest(
    @Json(name = "measuredMbps") val measuredMbps: Double
)

@JsonClass(generateAdapter = true)
data class BandwidthStatusResponse(
    @Json(name = "measuredBandwidthMbps") val measuredBandwidthMbps: Double?,
    @Json(name = "safetyMargin") val safetyMargin: Double?,
    @Json(name = "effectiveBandwidthMbps") val effectiveBandwidthMbps: Double?,
    @Json(name = "measuredAt") val measuredAt: String?
)

@JsonClass(generateAdapter = true)
data class PlaybackSettingsResponse(
    @Json(name = "stutterBufferLowThreshold") val stutterBufferLowThreshold: Int,
    @Json(name = "stutterConsecutiveThreshold") val stutterConsecutiveThreshold: Int,
    @Json(name = "stutterTimeWindowMs") val stutterTimeWindowMs: Int
)

@JsonClass(generateAdapter = true)
data class FallbackRequest(
    val tmdbId: Int,
    val type: String, // "movie" or "tv"
    val year: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val duration: Int? = null,
    @Json(name = "currentBitrate") val currentBitrate: Int? = null
)
