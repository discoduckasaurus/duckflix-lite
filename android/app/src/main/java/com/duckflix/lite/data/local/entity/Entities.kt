package com.duckflix.lite.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: Int,
    val username: String,
    val isAdmin: Boolean,
    val rdExpiryDate: String?
)

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val isFavorite: Boolean = false
)

@Entity(tableName = "epg_programs")
data class EpgProgramEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val channelId: String,
    val title: String,
    val filePath: String,
    val startTime: Long,
    val endTime: Long,
    val status: String
)

@Entity(
    tableName = "watch_progress",
    primaryKeys = ["tmdbId", "season", "episode"]
)
data class WatchProgressEntity(
    val tmdbId: Int,
    val title: String,
    val type: String, // "movie" or "tv"
    val year: String?,
    val posterUrl: String?,
    val position: Long, // milliseconds
    val duration: Long, // milliseconds
    val lastWatchedAt: Long, // timestamp
    val isCompleted: Boolean = false,
    val season: Int = 0, // 0 for movies, season number for TV
    val episode: Int = 0 // 0 for movies, episode number for TV
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val type: String, // "movie" or "tv"
    val year: String?,
    val posterUrl: String?,
    val searchedAt: Long, // timestamp
    val voteAverage: Double? = null
)

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val type: String, // "movie" or "tv"
    val year: String?,
    val posterUrl: String?,
    val addedAt: Long, // timestamp
    val voteAverage: Double? = null // Rating for movies/shows
)
