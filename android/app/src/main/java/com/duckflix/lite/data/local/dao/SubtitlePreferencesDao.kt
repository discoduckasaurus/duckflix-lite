package com.duckflix.lite.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.duckflix.lite.data.local.entity.SubtitlePreferencesEntity

@Dao
interface SubtitlePreferencesDao {
    @Query("SELECT * FROM subtitle_preferences WHERE id = 1")
    suspend fun getSettings(): SubtitlePreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: SubtitlePreferencesEntity)
}
