package com.duckflix.lite.data.local.dao

import androidx.room.*
import com.duckflix.lite.data.local.entity.PlaybackErrorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackErrorDao {
    @Query("SELECT * FROM playback_errors ORDER BY timestamp DESC LIMIT 100")
    fun getAllErrors(): Flow<List<PlaybackErrorEntity>>

    @Query("""
        SELECT * FROM playback_errors
        WHERE tmdbId = :tmdbId AND type = :type
        AND (:season IS NULL OR season = :season)
        AND (:episode IS NULL OR episode = :episode)
        ORDER BY timestamp DESC
        LIMIT 5
    """)
    suspend fun getErrorsForContent(
        tmdbId: Int,
        type: String,
        season: Int?,
        episode: Int?
    ): List<PlaybackErrorEntity>

    @Query("""
        SELECT * FROM playback_errors
        WHERE errorCode = :errorCode
        AND resolved = 1
        ORDER BY timestamp DESC
        LIMIT 10
    """)
    suspend fun getResolvedErrorsByCode(errorCode: String): List<PlaybackErrorEntity>

    @Insert
    suspend fun insertError(error: PlaybackErrorEntity): Long

    @Query("UPDATE playback_errors SET resolved = 1 WHERE id = :errorId")
    suspend fun markAsResolved(errorId: Int)

    @Query("DELETE FROM playback_errors WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
