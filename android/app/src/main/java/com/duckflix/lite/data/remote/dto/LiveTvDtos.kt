package com.duckflix.lite.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LiveTvProgram(
    val start: Long,           // Unix timestamp (seconds)
    val stop: Long,            // Unix timestamp (seconds)
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val icon: String? = null,
    @Json(name = "episode_num") val episodeNum: String? = null
) {
    val durationMinutes: Int
        get() = ((stop - start) / 60).toInt()

    val isCurrentlyAiring: Boolean
        get() {
            val now = System.currentTimeMillis() / 1000
            return now in start until stop
        }

    val progressPercent: Float
        get() {
            val now = System.currentTimeMillis() / 1000
            return if (now < start) 0f
            else if (now >= stop) 100f
            else ((now - start).toFloat() / (stop - start).toFloat() * 100f)
        }
}

@JsonClass(generateAdapter = true)
data class LiveTvChannel(
    val id: String,
    val name: String,
    @Json(name = "display_name") val displayName: String? = null,
    val group: String? = null,
    val url: String? = null,       // Direct stream URL (optional, may use constructed URL)
    val logo: String? = null,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    @Json(name = "channel_number") val channelNumber: Int? = null,
    @Json(name = "is_favorite") val isFavorite: Boolean = false,
    @Json(name = "is_active") val isActive: Boolean = true,  // Filter out deactivated channels
    @Json(name = "current_program") val currentProgram: LiveTvProgram? = null,
    @Json(name = "upcoming_programs") val upcomingPrograms: List<LiveTvProgram> = emptyList()
) {
    val effectiveDisplayName: String
        get() = displayName ?: name

    val logoUrl: String?
        get() = logo?.takeIf { it.isNotBlank() }?.let { path ->
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                "https://lite.duckflix.tv$path"  // Prepend base URL for relative paths
            }
        }

    // All programs for EPG display (current + upcoming)
    val allPrograms: List<LiveTvProgram>
        get() = listOfNotNull(currentProgram) + upcomingPrograms
}

@JsonClass(generateAdapter = true)
data class LiveTvChannelsResponse(
    val channels: List<LiveTvChannel>,
    @Json(name = "epg_start") val epgStart: Long? = null,    // EPG window start (Unix timestamp)
    @Json(name = "epg_end") val epgEnd: Long? = null          // EPG window end (Unix timestamp)
)
