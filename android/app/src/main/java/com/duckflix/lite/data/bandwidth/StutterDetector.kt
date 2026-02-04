package com.duckflix.lite.data.bandwidth

import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.FallbackRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects buffering/stuttering during playback and requests lower quality fallback
 */
@Singleton
class StutterDetector @Inject constructor(
    private val api: DuckFlixApi,
    private val bandwidthTester: BandwidthTester
) {
    private val _isFallbackActive = MutableStateFlow(false)
    val isFallbackActive: StateFlow<Boolean> = _isFallbackActive.asStateFlow()

    private var stutterBufferLowThreshold = 3 // Default: 3 buffer events
    private var stutterConsecutiveThreshold = 2 // Default: 2 consecutive within window
    private var stutterTimeWindowMs = 30000 // Default: 30 seconds

    private val bufferEvents = mutableListOf<Long>()

    /**
     * Initialize by fetching playback settings from server
     */
    suspend fun initialize() {
        val settings = bandwidthTester.getPlaybackSettings()
        settings?.let {
            stutterBufferLowThreshold = it.stutterBufferLowThreshold
            stutterConsecutiveThreshold = it.stutterConsecutiveThreshold
            stutterTimeWindowMs = it.stutterTimeWindowMs
        }
    }

    /**
     * Record a buffering event
     * @return true if fallback should be triggered
     */
    fun recordBufferEvent(): Boolean {
        val now = System.currentTimeMillis()
        bufferEvents.add(now)

        // Remove old events outside the time window
        bufferEvents.removeAll { it < now - stutterTimeWindowMs }

        // Check if we've exceeded the threshold
        return bufferEvents.size >= stutterBufferLowThreshold
    }

    /**
     * Request a lower quality fallback from the server
     */
    suspend fun requestFallback(
        tmdbId: Int,
        type: String,
        year: String?,
        season: Int? = null,
        episode: Int? = null,
        duration: Int? = null,
        currentBitrate: Int? = null,
        scope: CoroutineScope
    ): String? {
        if (_isFallbackActive.value) {
            // Already in fallback mode
            return null
        }

        return try {
            val request = FallbackRequest(
                tmdbId = tmdbId,
                type = type,
                year = year,
                season = season,
                episode = episode,
                duration = duration,
                currentBitrate = currentBitrate
            )

            val response = api.requestFallback(request)

            if (response.streamUrl != null) {
                _isFallbackActive.value = true
                response.streamUrl
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Reset stutter detection (call when starting new content or quality changes)
     */
    fun reset() {
        bufferEvents.clear()
        _isFallbackActive.value = false
    }

    /**
     * Check if conditions are met for fallback trigger
     */
    fun shouldTriggerFallback(): Boolean {
        val now = System.currentTimeMillis()
        bufferEvents.removeAll { it < now - stutterTimeWindowMs }

        // Check for consecutive events
        if (bufferEvents.size >= stutterConsecutiveThreshold) {
            val recentEvents = bufferEvents.takeLast(stutterConsecutiveThreshold)
            val timeSpan = recentEvents.last() - recentEvents.first()

            // If consecutive events happened within a short time, trigger fallback
            return timeSpan < (stutterTimeWindowMs / 3)
        }

        return bufferEvents.size >= stutterBufferLowThreshold
    }
}
