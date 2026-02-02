package com.duckflix.lite

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DuckFlixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Application initialization
    }
}
