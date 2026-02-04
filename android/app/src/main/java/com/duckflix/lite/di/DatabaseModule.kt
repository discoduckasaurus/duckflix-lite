package com.duckflix.lite.di

import android.content.Context
import androidx.room.Room
import com.duckflix.lite.data.local.DuckFlixDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): DuckFlixDatabase = Room.databaseBuilder(
        context,
        DuckFlixDatabase::class.java,
        "duckflix.db"
    )
        .fallbackToDestructiveMigration() // For development
        .build()

    @Provides
    fun provideUserDao(database: DuckFlixDatabase) = database.userDao()

    @Provides
    fun provideChannelDao(database: DuckFlixDatabase) = database.channelDao()

    @Provides
    fun provideEpgDao(database: DuckFlixDatabase) = database.epgDao()

    @Provides
    fun provideRecordingDao(database: DuckFlixDatabase) = database.recordingDao()

    @Provides
    fun provideWatchProgressDao(database: DuckFlixDatabase) = database.watchProgressDao()

    @Provides
    fun provideRecentSearchDao(database: DuckFlixDatabase) = database.recentSearchDao()

    @Provides
    fun provideWatchlistDao(database: DuckFlixDatabase) = database.watchlistDao()

    @Provides
    fun providePlaybackErrorDao(database: DuckFlixDatabase) = database.playbackErrorDao()

    @Provides
    fun provideAutoPlaySettingsDao(database: DuckFlixDatabase) = database.autoPlaySettingsDao()
}
