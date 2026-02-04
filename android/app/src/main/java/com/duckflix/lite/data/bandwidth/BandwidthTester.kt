package com.duckflix.lite.data.bandwidth

import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.BandwidthReportRequest
import com.duckflix.lite.data.remote.dto.BandwidthStatusResponse
import com.duckflix.lite.data.remote.dto.PlaybackSettingsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * Handles bandwidth testing and reporting for adaptive bitrate streaming
 */
@Singleton
class BandwidthTester @Inject constructor(
    private val api: DuckFlixApi
) {
    companion object {
        private const val TEST_FILE_SIZE_MB = 5.0
        private const val BYTES_PER_MB = 1_048_576.0
        private const val BITS_PER_BYTE = 8.0
        private const val MS_PER_SECOND = 1000.0
    }

    /**
     * Download test file and measure bandwidth
     * @return Measured bandwidth in Mbps, or null if test failed
     */
    suspend fun measureBandwidth(): Double? = withContext(Dispatchers.IO) {
        try {
            var bytesDownloaded = 0L

            val elapsedMs = measureTimeMillis {
                val response = api.downloadBandwidthTest()
                val inputStream = response.byteStream()
                val buffer = ByteArray(8192)

                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    bytesDownloaded += bytesRead
                }

                inputStream.close()
            }

            if (elapsedMs > 0 && bytesDownloaded > 0) {
                // Calculate Mbps: (bytes * 8 bits/byte) / (ms / 1000) / 1,000,000
                val megabits = (bytesDownloaded.toDouble() * BITS_PER_BYTE) / (BYTES_PER_MB * BITS_PER_BYTE)
                val seconds = elapsedMs.toDouble() / MS_PER_SECOND
                val mbps = megabits / seconds

                mbps
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Report measured bandwidth to server
     */
    suspend fun reportBandwidth(measuredMbps: Double): BandwidthStatusResponse? {
        return try {
            api.reportBandwidth(BandwidthReportRequest(measuredMbps))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get current bandwidth status from server
     */
    suspend fun getBandwidthStatus(): BandwidthStatusResponse? {
        return try {
            api.getBandwidthStatus()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get playback settings (stutter thresholds)
     */
    suspend fun getPlaybackSettings(): PlaybackSettingsResponse? {
        return try {
            api.getPlaybackSettings()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Perform bandwidth test and report to server
     * @return BandwidthStatusResponse with effective bandwidth, or null if failed
     */
    suspend fun performTestAndReport(): BandwidthStatusResponse? {
        val measuredMbps = measureBandwidth() ?: return null
        return reportBandwidth(measuredMbps)
    }
}
