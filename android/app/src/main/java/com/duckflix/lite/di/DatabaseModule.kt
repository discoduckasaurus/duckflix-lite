package com.duckflix.lite.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    /**
     * Migration 10→11: Change watch_progress from single PK (tmdbId) to
     * composite PK (tmdbId, season, episode) for per-episode progress tracking.
     * Converts nullable season/episode to non-null with 0 default.
     */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS watch_progress_new (
                    tmdbId INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    type TEXT NOT NULL,
                    year TEXT,
                    posterUrl TEXT,
                    position INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    lastWatchedAt INTEGER NOT NULL,
                    isCompleted INTEGER NOT NULL DEFAULT 0,
                    season INTEGER NOT NULL DEFAULT 0,
                    episode INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (tmdbId, season, episode)
                )
            """)
            db.execSQL("""
                INSERT OR REPLACE INTO watch_progress_new
                    (tmdbId, title, type, year, posterUrl, position, duration, lastWatchedAt, isCompleted, season, episode)
                SELECT tmdbId, title, type, year, posterUrl, position, duration, lastWatchedAt, isCompleted,
                    COALESCE(season, 0), COALESCE(episode, 0)
                FROM watch_progress
            """)
            db.execSQL("DROP TABLE watch_progress")
            db.execSQL("ALTER TABLE watch_progress_new RENAME TO watch_progress")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): DuckFlixDatabase = Room.databaseBuilder(
        context,
        DuckFlixDatabase::class.java,
        "duckflix.db"
    )
        .addMigrations(MIGRATION_10_11)
        .fallbackToDestructiveMigration() // Safety net for other version jumps
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
