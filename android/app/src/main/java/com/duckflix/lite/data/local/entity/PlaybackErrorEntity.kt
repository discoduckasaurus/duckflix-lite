package com.duckflix.lite.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_errors")
data class PlaybackErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val tmdbId: Int,
    val title: String,
    val type: String, // "movie" or "tv"
    val season: Int?,
    val episode: Int?,
    val errorCode: String, // ExoPlayer error code name
    val errorMessage: String,
    val errorCause: String?,
    val streamUrl: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val resolved: Boolean = false // Whether a fallback was successful
)
