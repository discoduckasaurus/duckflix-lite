package com.duckflix.lite.data.local.dao

import androidx.room.*
import com.duckflix.lite.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserById(id: Int): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("DELETE FROM users")
    suspend fun deleteAll()
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE isFavorite = 1")
    fun getFavoriteChannels(): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_programs WHERE channelId = :channelId AND endTime > :currentTime")
    suspend fun getProgramsForChannel(channelId: String, currentTime: Long): List<EpgProgramEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startTime DESC")
    fun getAllRecordings(): Flow<List<RecordingEntity>>

    @Insert
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Delete
    suspend fun deleteRecording(recording: RecordingEntity)
}

@Dao
interface WatchProgressDao {
    // Get the most recently watched entry for a show (latest episode or movie progress)
    @Query("SELECT * FROM watch_progress WHERE tmdbId = :tmdbId ORDER BY lastWatchedAt DESC LIMIT 1")
    suspend fun getProgress(tmdbId: Int): WatchProgressEntity?

    // Get progress for a specific episode
    @Query("SELECT * FROM watch_progress WHERE tmdbId = :tmdbId AND season = :season AND episode = :episode")
    suspend fun getEpisodeProgress(tmdbId: Int, season: Int, episode: Int): WatchProgressEntity?

    // Get all episode progress for a specific season of a show
    @Query("SELECT * FROM watch_progress WHERE tmdbId = :tmdbId AND season = :season")
    suspend fun getSeasonProgress(tmdbId: Int, season: Int): List<WatchProgressEntity>

    // Continue Watching: most recent incomplete episode per show
    @Query("""
        SELECT wp.* FROM watch_progress wp
        INNER JOIN (
            SELECT tmdbId, MAX(lastWatchedAt) as maxWatched
            FROM watch_progress
            WHERE isCompleted = 0
            GROUP BY tmdbId
        ) latest ON wp.tmdbId = latest.tmdbId AND wp.lastWatchedAt = latest.maxWatched
        ORDER BY wp.lastWatchedAt DESC
        LIMIT 10
    """)
    fun getContinueWatching(): Flow<List<WatchProgressEntity>>

    @Query("""
        SELECT wp.* FROM watch_progress wp
        INNER JOIN (
            SELECT tmdbId, MAX(lastWatchedAt) as maxWatched
            FROM watch_progress
            GROUP BY tmdbId
        ) latest ON wp.tmdbId = latest.tmdbId AND wp.lastWatchedAt = latest.maxWatched
        ORDER BY wp.lastWatchedAt DESC
        LIMIT 20
    """)
    fun getRecentlyWatched(): Flow<List<WatchProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE tmdbId = :tmdbId")
    suspend fun deleteProgress(tmdbId: Int)

    // Storage cap enforcement
    @Query("SELECT COUNT(*) FROM watch_progress")
    suspend fun getCount(): Int

    @Query("""
        DELETE FROM watch_progress WHERE rowid IN (
            SELECT rowid FROM watch_progress ORDER BY lastWatchedAt ASC LIMIT :count
        )
    """)
    suspend fun deleteOldest(count: Int)
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT 20")
    fun getRecentSearches(): Flow<List<RecentSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSearch(search: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE tmdbId = :tmdbId")
    suspend fun deleteSearch(tmdbId: Int)

    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT COUNT(*) > 0 FROM watchlist WHERE tmdbId = :tmdbId")
    suspend fun isInWatchlist(tmdbId: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE tmdbId = :tmdbId")
    suspend fun remove(tmdbId: Int)

    @Query("DELETE FROM watchlist")
    suspend fun clearAll()
}
