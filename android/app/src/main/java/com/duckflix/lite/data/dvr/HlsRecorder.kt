package com.duckflix.lite.data.dvr

import com.duckflix.lite.data.local.dao.RecordingDao
import com.duckflix.lite.data.local.entity.RecordingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads HLS segments from a live stream and concatenates them into a .ts file.
 * MPEG-TS is concatenation-safe — segments can be appended directly.
 */
class HlsRecorder(
    private val okHttpClient: OkHttpClient,
    private val recordingDao: RecordingDao
) {
    companion object {
        private const val TAG = "HlsRecorder"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val SIZE_UPDATE_INTERVAL = 5 // update DB every N segments
        private const val BASE_URL = "https://lite.duckflix.tv/api"
    }

    @Volatile
    var isStopped = false
        private set

    fun stop() {
        isStopped = true
    }

    /**
     * Record a live HLS stream to disk.
     * Runs until scheduledEnd is reached, manually stopped, or fatal error.
     * Updates the RecordingEntity in the database as it progresses.
     */
    suspend fun record(recording: RecordingEntity): RecordingEntity {
        var current = recording.copy(
            status = "recording",
            actualStart = System.currentTimeMillis()
        )
        recordingDao.updateRecording(current)
        println("[$TAG] Starting recording: ${current.channelName} - ${current.programTitle}")

        val outputFile = File(current.filePath ?: return current.copy(status = "failed", errorMessage = "No file path"))
        outputFile.parentFile?.mkdirs()

        var totalBytes = 0L
        var segmentCount = 0
        val downloadedSegments = mutableSetOf<String>() // track by URI to avoid duplicates

        try {
            FileOutputStream(outputFile, true).use { fos ->
                val streamUrl = "$BASE_URL/livetv/stream/${current.channelId}"
                var masterPlaylistUrl = streamUrl
                var mediaPlaylistUrl: String? = null
                var retries = 0

                while (coroutineContext.isActive && !isStopped) {
                    // Check if scheduled end has passed
                    if (System.currentTimeMillis() >= current.scheduledEnd) {
                        println("[$TAG] Scheduled end reached, stopping")
                        break
                    }

                    try {
                        // Resolve media playlist URL if we don't have one
                        if (mediaPlaylistUrl == null) {
                            mediaPlaylistUrl = resolveMediaPlaylist(masterPlaylistUrl)
                            if (mediaPlaylistUrl == null) {
                                // The master URL might itself be the media playlist
                                mediaPlaylistUrl = masterPlaylistUrl
                            }
                        }

                        // Fetch media playlist
                        val playlist = fetchPlaylist(mediaPlaylistUrl!!)
                        if (playlist == null) {
                            retries++
                            if (retries >= MAX_RETRIES) {
                                // Try re-resolving from master
                                mediaPlaylistUrl = null
                                retries = 0
                            }
                            delay(RETRY_DELAY_MS)
                            continue
                        }

                        val segments = parseSegments(playlist, mediaPlaylistUrl!!)
                        var targetDuration = parseTargetDuration(playlist)

                        // Download new segments
                        for (segmentUrl in segments) {
                            if (isStopped || !coroutineContext.isActive) break
                            if (segmentUrl in downloadedSegments) continue

                            val segmentData = downloadSegment(segmentUrl)
                            if (segmentData != null) {
                                fos.write(segmentData)
                                fos.flush()
                                totalBytes += segmentData.size
                                segmentCount++
                                downloadedSegments.add(segmentUrl)

                                // Periodically update file size in DB
                                if (segmentCount % SIZE_UPDATE_INTERVAL == 0) {
                                    current = current.copy(fileSize = totalBytes)
                                    recordingDao.updateRecording(current)
                                }
                            }
                        }

                        retries = 0

                        // Wait before polling again (roughly one segment duration)
                        val pollDelay = (targetDuration * 1000L).coerceIn(2000L, 10000L)
                        delay(pollDelay)

                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println("[$TAG] Error during recording: ${e.message}")
                        retries++
                        if (retries >= MAX_RETRIES) {
                            // Try fresh from master playlist
                            mediaPlaylistUrl = null
                            retries = 0
                        }
                        delay(RETRY_DELAY_MS)
                    }
                }
            }

            // Finalize recording
            current = current.copy(
                status = if (isStopped && System.currentTimeMillis() < current.scheduledEnd) "completed" else "completed",
                actualEnd = System.currentTimeMillis(),
                fileSize = totalBytes
            )
            recordingDao.updateRecording(current)
            println("[$TAG] Recording complete: ${current.programTitle}, $totalBytes bytes, $segmentCount segments")
            return current

        } catch (e: CancellationException) {
            current = current.copy(
                status = "completed",
                actualEnd = System.currentTimeMillis(),
                fileSize = totalBytes
            )
            recordingDao.updateRecording(current)
            throw e
        } catch (e: Exception) {
            println("[$TAG] Fatal recording error: ${e.message}")
            current = current.copy(
                status = "failed",
                errorMessage = e.message,
                actualEnd = System.currentTimeMillis(),
                fileSize = totalBytes
            )
            recordingDao.updateRecording(current)
            return current
        }
    }

    /**
     * Fetch a master playlist and find the media playlist URL (highest bandwidth variant).
     */
    private fun resolveMediaPlaylist(masterUrl: String): String? {
        val body = fetchPlaylist(masterUrl) ?: return null

        // If it contains #EXTINF, it's already a media playlist
        if (body.contains("#EXTINF:")) return null

        // Parse EXT-X-STREAM-INF lines and pick highest bandwidth
        var bestBandwidth = -1L
        var bestUri: String? = null

        val lines = body.lines()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val uri = lines.getOrNull(i + 1)?.trim()
                if (bw > bestBandwidth && uri != null && !uri.startsWith("#")) {
                    bestBandwidth = bw
                    bestUri = uri
                }
            }
        }

        return bestUri?.let { resolveUrl(masterUrl, it) }
    }

    private fun fetchPlaylist(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            println("[$TAG] Failed to fetch playlist $url: ${e.message}")
            null
        }
    }

    /**
     * Parse segment URIs from a media playlist.
     */
    private fun parseSegments(playlist: String, playlistUrl: String): List<String> {
        val segments = mutableListOf<String>()
        val lines = playlist.lines()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXTINF:")) {
                val uri = lines.getOrNull(i + 1)?.trim()
                if (uri != null && !uri.startsWith("#") && uri.isNotBlank()) {
                    segments.add(resolveUrl(playlistUrl, uri))
                }
            }
        }
        return segments
    }

    private fun parseTargetDuration(playlist: String): Int {
        val match = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(playlist)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 6
    }

    private fun downloadSegment(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: Exception) {
            println("[$TAG] Failed to download segment: ${e.message}")
            null
        }
    }

    /**
     * Resolve a potentially relative URL against a base URL.
     */
    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.startsWith("/")) {
            val origin = Regex("(https?://[^/]+)").find(base)?.value ?: return relative
            return "$origin$relative"
        }
        val baseDir = base.substringBeforeLast("/")
        return "$baseDir/$relative"
    }
}
