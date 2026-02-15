package com.duckflix.lite.data.bandwidth

import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.BandwidthReportRequest
import com.duckflix.lite.data.remote.dto.BandwidthReportResponse
import com.duckflix.lite.data.remote.dto.BandwidthStatusResponse
import com.duckflix.lite.data.remote.dto.PlaybackSettingsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bandwidth test result containing both measured speed and test duration
 */
data class BandwidthTestResult(
    val mbps: Double,
    val durationMs: Long
)

/**
 * Handles bandwidth testing and reporting for adaptive bitrate streaming.
 *
 * The streaming test works by downloading random data for a specified duration,
 * measuring bytes received from first byte arrival (excludes connection setup).
 */
@Singleton
class BandwidthTester @Inject constructor(
    private val api: DuckFlixApi
) {
    companion object {
        private const val BITS_PER_BYTE = 8.0
        private const val MS_PER_SECOND = 1000.0
        private const val MBPS_DIVISOR = 1_000_000.0
        private const val DEFAULT_TEST_DURATION = 5 // seconds
    }

    // Scope for fire-and-forget background tests
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Streaming bandwidth test using the new test-stream endpoint.
     * Timer starts when first byte arrives (excludes connection setup time).
     *
     * @param duration Test duration in seconds (1-10, default 5)
     * @return BandwidthTestResult with Mbps and actual duration, or null if failed
     */
    suspend fun measureBandwidthStream(duration: Int = DEFAULT_TEST_DURATION): BandwidthTestResult? =
        withContext(Dispatchers.IO) {
            try {
                // Add timeout to prevent hanging if server doesn't send data
                kotlinx.coroutines.withTimeoutOrNull((duration + 10) * 1000L) {
                    val response = api.downloadBandwidthTestStream(duration)
                    val inputStream = response.byteStream()
                    val buffer = ByteArray(65536) // 64KB buffer for efficiency

                    var bytesDownloaded = 0L
                    var firstByteTime: Long? = null
                    var endTime: Long = 0

                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (firstByteTime == null) {
                            firstByteTime = System.nanoTime()
                        }
                        bytesDownloaded += bytesRead
                        endTime = System.nanoTime()
                    }

                    inputStream.close()

                    if (firstByteTime != null && bytesDownloaded > 0) {
                        val elapsedNanos = endTime - firstByteTime
                        val elapsedMs = elapsedNanos / 1_000_000
                        val elapsedSeconds = elapsedNanos / 1_000_000_000.0

                        // Calculate Mbps: totalBytes * 8 / elapsedSeconds / 1,000,000
                        val mbps = (bytesDownloaded * BITS_PER_BYTE) / elapsedSeconds / MBPS_DIVISOR

                        println("[Bandwidth] Stream test: ${bytesDownloaded} bytes in ${elapsedMs}ms = ${String.format("%.1f", mbps)} Mbps")
                        BandwidthTestResult(mbps = mbps, durationMs = elapsedMs)
                    } else {
                        println("[Bandwidth] Stream test failed: no bytes received")
                        null
                    }
                } ?: run {
                    println("[Bandwidth] Stream test timed out after ${duration + 10} seconds")
                    null
                }
            } catch (e: Exception) {
                println("[Bandwidth] Stream test error: ${e.message}")
                e.printStackTrace()
                null
            }
        }

    /**
     * Legacy fixed-file bandwidth test (kept for fallback compatibility).
     * @return Measured bandwidth in Mbps, or null if test failed
     */
    suspend fun measureBandwidth(): Double? = withContext(Dispatchers.IO) {
        try {
            val response = api.downloadBandwidthTest()
            val inputStream = response.byteStream()
            val buffer = ByteArray(8192)

            var bytesDownloaded = 0L
            var firstByteTime: Long? = null
            var endTime: Long = 0

            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (firstByteTime == null) {
                    firstByteTime = System.nanoTime()
                }
                bytesDownloaded += bytesRead
                endTime = System.nanoTime()
            }

            inputStream.close()

            if (firstByteTime != null && bytesDownloaded > 0) {
                val elapsedSeconds = (endTime - firstByteTime) / 1_000_000_000.0
                val mbps = (bytesDownloaded * BITS_PER_BYTE) / elapsedSeconds / MBPS_DIVISOR
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
     * Report measured bandwidth to server with optional duration and trigger info.
     *
     * @param measuredMbps The measured bandwidth in Mbps
     * @param durationMs How long the test ran (for reliability assessment)
     * @param trigger What triggered this test: "startup", "episode-end", "bandwidth-retest", "manual"
     * @return BandwidthReportResponse with reliability info, or null if failed
     */
    suspend fun reportBandwidth(
        measuredMbps: Double,
        durationMs: Long? = null,
        trigger: String? = null
    ): BandwidthReportResponse? {
        return try {
            api.reportBandwidth(BandwidthReportRequest(
                measuredMbps = measuredMbps,
                durationMs = durationMs,
                trigger = trigger
            ))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get current bandwidth status from server.
     * Check needsTest or suggestRetest to determine if a test should be run.
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
     * Perform streaming bandwidth test and report to server.
     * Uses the new streaming test endpoint for accurate measurement.
     *
     * @param trigger What triggered this test
     * @return BandwidthReportResponse with reliability info, or null if failed
     */
    suspend fun performTestAndReport(trigger: String = "startup"): BandwidthReportResponse? {
        val result = measureBandwidthStream() ?: return null
        return reportBandwidth(
            measuredMbps = result.mbps,
            durationMs = result.durationMs,
            trigger = trigger
        )
    }

    /**
     * Fire-and-forget bandwidth test running in background.
     * Does not block caller or delay any UI/playback operations.
     *
     * @param trigger What triggered this test
     */
    fun performTestAndReportInBackground(trigger: String) {
        backgroundScope.launch {
            try {
                println("[Bandwidth] Starting background test (trigger: $trigger)")
                val result = performTestAndReport(trigger)
                if (result != null) {
                    println("[Bandwidth] Background test complete: ${String.format("%.1f", result.recorded)} Mbps (reliable: ${result.reliable})")
                } else {
                    println("[Bandwidth] Background test failed")
                }
            } catch (e: Exception) {
                println("[Bandwidth] Background test error: ${e.message}")
            }
        }
    }

    /**
     * Check if bandwidth test is needed based on server status.
     * @return true if a test should be run (no measurement exists or stale)
     */
    suspend fun needsTest(): Boolean {
        val status = getBandwidthStatus() ?: return true
        return status.needsTest == true || status.suggestRetest == true
    }
}
