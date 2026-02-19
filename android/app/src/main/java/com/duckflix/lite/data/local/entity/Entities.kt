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
    val channelName: String,
    val programTitle: String,
    val programDescription: String? = null,
    val scheduledStart: Long,        // epoch ms — when to start recording
    val scheduledEnd: Long,          // epoch ms — when to stop
    val actualStart: Long? = null,   // epoch ms — when recording actually began
    val actualEnd: Long? = null,
    val filePath: String? = null,    // set when recording starts
    val fileSize: Long = 0,
    val status: String = "scheduled", // scheduled, recording, completed, failed, cancelled
    val errorMessage: String? = null,
    val storageType: String = "external", // external (USB) or internal
    val createdAt: Long = System.currentTimeMillis()
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
