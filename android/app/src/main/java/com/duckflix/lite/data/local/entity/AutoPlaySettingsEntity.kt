package com.duckflix.lite.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "autoplay_settings")
data class AutoPlaySettingsEntity(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val lastSeriesTmdbId: Int? = null,
    val sessionEnabled: Boolean = false
)
