package com.duckflix.lite.di

import com.duckflix.lite.data.dvr.DvrStorageManager
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for DVR components.
 * DvrStorageManager is @Singleton @Inject so it's auto-provided.
 * HlsRecorder is created per-recording in the service (not a singleton).
 * OkHttpClient and RecordingDao are already provided by NetworkModule and DatabaseModule.
 */
@Module
@InstallIn(SingletonComponent::class)
object DvrModule
