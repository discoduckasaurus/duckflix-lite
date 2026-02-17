package com.duckflix.lite.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.duckflix.lite.data.local.dao.AutoPlaySettingsDao
import com.duckflix.lite.data.local.dao.ChannelDao
import com.duckflix.lite.data.local.dao.EpgDao
import com.duckflix.lite.data.local.dao.PlaybackErrorDao
import com.duckflix.lite.data.local.dao.RecentSearchDao
import com.duckflix.lite.data.local.dao.RecordingDao
import com.duckflix.lite.data.local.dao.SubtitlePreferencesDao
import com.duckflix.lite.data.local.dao.UserDao
import com.duckflix.lite.data.local.dao.WatchProgressDao
import com.duckflix.lite.data.local.dao.WatchlistDao
import com.duckflix.lite.data.local.entity.AutoPlaySettingsEntity
import com.duckflix.lite.data.local.entity.ChannelEntity
import com.duckflix.lite.data.local.entity.EpgProgramEntity
import com.duckflix.lite.data.local.entity.PlaybackErrorEntity
import com.duckflix.lite.data.local.entity.RecentSearchEntity
import com.duckflix.lite.data.local.entity.RecordingEntity
import com.duckflix.lite.data.local.entity.SubtitlePreferencesEntity
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
        WatchlistEntity::class,
        PlaybackErrorEntity::class,
        AutoPlaySettingsEntity::class,
        SubtitlePreferencesEntity::class
    ],
    version = 12,
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
    abstract fun playbackErrorDao(): PlaybackErrorDao
    abstract fun autoPlaySettingsDao(): AutoPlaySettingsDao
    abstract fun subtitlePreferencesDao(): SubtitlePreferencesDao
}
