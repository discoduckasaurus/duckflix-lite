package com.duckflix.lite

import androidx.media3.exoplayer.ExoPlayer

/**
 * Simple singleton that tracks active ExoPlayer instances.
 * ViewModels register/unregister their players here.
 * MainActivity calls onAppBackground/onAppForeground on lifecycle transitions.
 */
object ActivePlayerRegistry {

    interface PlayerHandle {
        fun onAppBackground()
        fun onAppForeground()
    }

    private val handles = mutableSetOf<PlayerHandle>()

    fun register(handle: PlayerHandle) {
        handles.add(handle)
    }

    fun unregister(handle: PlayerHandle) {
        handles.remove(handle)
    }

    fun notifyBackground() {
        handles.forEach { it.onAppBackground() }
    }

    fun notifyForeground() {
        handles.forEach { it.onAppForeground() }
    }
}
