package com.duckflix.lite.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subtitle_preferences")
data class SubtitlePreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val preferredLanguage: String? = null,     // "en", "fr", etc.
    val subtitlesEnabled: Boolean = false,      // were subs on last time?
    val subtitleSize: Int = 1,                  // 0=Small, 1=Medium, 2=Large
    val subtitleColor: Int = 0,                 // 0=White, 1=Yellow, 2=Green, 3=Cyan
    val subtitleBackground: Int = 0,            // 0=None, 1=Black, 2=Semi-transparent
    val subtitleEdge: Int = 1                   // 0=None, 1=Drop shadow, 2=Outline
)
