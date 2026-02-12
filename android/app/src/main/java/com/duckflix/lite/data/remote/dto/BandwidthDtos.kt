package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BandwidthReportRequest(
    @Json(name = "measuredMbps") val measuredMbps: Double,
    @Json(name = "durationMs") val durationMs: Long? = null,
    @Json(name = "trigger") val trigger: String? = null // "startup", "episode-end", "bandwidth-retest", "manual"
)

@JsonClass(generateAdapter = true)
data class BandwidthStatusResponse(
    @Json(name = "measuredBandwidthMbps") val measuredBandwidthMbps: Double?,
    @Json(name = "safetyMargin") val safetyMargin: Double?,
    @Json(name = "effectiveBandwidthMbps") val effectiveBandwidthMbps: Double?,
    @Json(name = "measuredAt") val measuredAt: String?,
    @Json(name = "hasMeasurement") val hasMeasurement: Boolean? = null,
    @Json(name = "maxBitrateMbps") val maxBitrateMbps: Double? = null,
    @Json(name = "isStale") val isStale: Boolean? = null,
    @Json(name = "needsTest") val needsTest: Boolean? = null,
    @Json(name = "suggestRetest") val suggestRetest: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class BandwidthReportResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "recorded") val recorded: Double?,
    @Json(name = "reliable") val reliable: Boolean?,
    @Json(name = "maxBitrate") val maxBitrate: Double?
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
