package com.duckflix.lite.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.duckflix.lite.data.local.entity.AutoPlaySettingsEntity

@Dao
interface AutoPlaySettingsDao {
    @Query("SELECT * FROM autoplay_settings WHERE id = 1")
    suspend fun getSettingsOnce(): AutoPlaySettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: AutoPlaySettingsEntity)
}
