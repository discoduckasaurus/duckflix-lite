package com.duckflix.lite.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.duckflix.lite.data.local.dao.ChannelDao
import com.duckflix.lite.data.local.dao.EpgDao
import com.duckflix.lite.data.local.dao.RecentSearchDao
import com.duckflix.lite.data.local.dao.RecordingDao
import com.duckflix.lite.data.local.dao.UserDao
import com.duckflix.lite.data.local.dao.WatchProgressDao
import com.duckflix.lite.data.local.dao.WatchlistDao
import com.duckflix.lite.data.local.entity.ChannelEntity
import com.duckflix.lite.data.local.entity.EpgProgramEntity
import com.duckflix.lite.data.local.entity.RecentSearchEntity
import com.duckflix.lite.data.local.entity.RecordingEntity
import com.duckflix.lite.data.local.entity.UserEntity
import com.duckflix.lite.data.local.entity.WatchProgressEntity
import com.duckflix.lite.data.local.entity.WatchlistEntity

@Database(
    entities = [
        UserEntity::class,
        ChannelEntity::class,
        EpgProgramEntity::class,
        RecordingEntity::class,
        WatchProgressEntity::class,
        RecentSearchEntity::class,
        WatchlistEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class DuckFlixDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun channelDao(): ChannelDao
    abstract fun epgDao(): EpgDao
    abstract fun recordingDao(): RecordingDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun watchlistDao(): WatchlistDao
}
