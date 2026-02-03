package com.duckflix.lite.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.duckflix.lite.data.local.dao.WatchProgressDao
import com.duckflix.lite.data.local.dao.WatchlistDao
import com.duckflix.lite.data.local.entity.WatchProgressEntity
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.StreamUrlRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrackInfo(
    val id: String,
    val label: String,
    val isSelected: Boolean
)

enum class LoadingPhase {
    CHECKING_CACHE,      // Checking Zurg + RD cache
    SEARCHING,           // Searching for sources (no progress UI shown)
    DOWNLOADING,         // RD downloading torrent
    BUFFERING,           // ExoPlayer buffering
    READY                // Ready to play
}

data class PlayerUiState(
    val isLoading: Boolean = true,
    val loadingPhase: LoadingPhase = LoadingPhase.CHECKING_CACHE,
    val downloadProgress: Int = 0,
    val downloadMessage: String = "",
    val downloadJobId: String? = null,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val showControls: Boolean = true,
    val showTrackSelection: Boolean = false,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPercentage: Int = 0,
    val posterUrl: String? = null,
    val logoUrl: String? = null // TODO: Fetch from TMDB API later
)

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DuckFlixApi,
    private val watchProgressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao,
    private val okHttpClient: okhttp3.OkHttpClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tmdbId: Int = checkNotNull(savedStateHandle["tmdbId"])
    private val contentTitle: String = savedStateHandle["title"] ?: "Video"
    private val contentType: String = savedStateHandle["type"] ?: "movie"
    private val year: String? = savedStateHandle["year"]
    private val season: Int? = savedStateHandle.get<Int>("season")?.takeIf { it != -1 }
    private val episode: Int? = savedStateHandle.get<Int>("episode")?.takeIf { it != -1 }
    private val resumePosition: Long? = savedStateHandle.get<Long>("resumePosition")?.takeIf { it != -1L }
    private var pendingSeekPosition: Long? = null
    private val posterUrl: String? = savedStateHandle["posterUrl"]

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var _player: ExoPlayer? = null
    val player: ExoPlayer?
        get() = _player

    init {
        // Set initial posterUrl
        _uiState.value = _uiState.value.copy(posterUrl = posterUrl)

        initializePlayer()
        loadVideoUrl()
        startPositionUpdates()
        fetchTmdbLogo()
    }

    private fun startPositionUpdates() {
        // Update position every second
        viewModelScope.launch {
            while (_player != null) {
                delay(1000)
                _player?.let { player ->
                    _uiState.value = _uiState.value.copy(
                        currentPosition = player.currentPosition,
                        duration = player.duration.coerceAtLeast(0),
                        bufferedPercentage = player.bufferedPercentage
                    )
                }
            }
        }
    }

    private fun initializePlayer() {
        // Simple configuration like main app - defaults work best for HEVC/x265
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("ExoPlayer/DuckFlix")
        )

        _player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateName = when(playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN"
                    }
                    println("[DEBUG] ExoPlayer state: $stateName ($playbackState)")
                    _uiState.value = _uiState.value.copy(
                        isLoading = playbackState == Player.STATE_BUFFERING,
                        duration = duration
                    )

                    if (playbackState == Player.STATE_READY) {
                        println("[INFO] ExoPlayer: Media ready, duration=${duration}ms, playing=${isPlaying}")

                        // Handle pending seek position now that we know the duration
                        pendingSeekPosition?.let { seekPos ->
                            if (duration > 0 && seekPos < duration) {
                                println("[DEBUG] Applying pending seek to $seekPos ms (duration: $duration ms)")
                                seekTo(seekPos)
                                pendingSeekPosition = null
                            } else {
                                println("[WARN] Skipping invalid seek position $seekPos ms (duration: $duration ms)")
                                pendingSeekPosition = null
                            }
                        }

                        // Force play if not playing
                        if (!isPlaying) {
                            println("[DEBUG] ExoPlayer ready but not playing, forcing play()")
                            play()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    println("[DEBUG] ExoPlayer isPlaying: $isPlaying")
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    println("[DEBUG] ExoPlayer tracks changed")
                    updateAvailableTracks()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    println("[ERROR] ExoPlayer error: ${error.errorCodeName} - ${error.message}")
                    println("[ERROR] ExoPlayer error cause: ${error.cause?.message}")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Playback error: ${error.errorCodeName}\n${error.message}"
                    )
                }
            })

            playWhenReady = true
        }
    }

    private fun updateAvailableTracks() {
        _player?.let { player ->
            val audioTracks = mutableListOf<TrackInfo>()
            val subtitleTracks = mutableListOf<TrackInfo>()

            player.currentTracks.groups.forEach { group ->
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)

                    when {
                        format.sampleMimeType?.startsWith("audio/") == true -> {
                            audioTracks.add(
                                TrackInfo(
                                    id = "${group.mediaTrackGroup.id}_$i",
                                    label = format.label ?: format.language ?: "Audio ${audioTracks.size + 1}",
                                    isSelected = isSelected
                                )
                            )
                        }
                        format.sampleMimeType?.startsWith("text/") == true ||
                        format.sampleMimeType?.startsWith("application/") == true -> {
                            subtitleTracks.add(
                                TrackInfo(
                                    id = "${group.mediaTrackGroup.id}_$i",
                                    label = format.label ?: format.language ?: "Subtitle ${subtitleTracks.size + 1}",
                                    isSelected = isSelected
                                )
                            )
                        }
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks
            )
        }
    }

    private fun loadVideoUrl() {
        viewModelScope.launch {
            try {
                // Check VOD session
                api.checkVodSession()

                _uiState.value = _uiState.value.copy(
                    loadingPhase = LoadingPhase.CHECKING_CACHE,
                    downloadMessage = "Checking for cached content..."
                )

                val streamRequest = StreamUrlRequest(
                    tmdbId = tmdbId,
                    title = contentTitle,
                    year = year,
                    type = contentType,
                    season = season,
                    episode = episode
                )

                // Start stream URL retrieval
                val startResponse = api.startStreamUrl(streamRequest)

                if (startResponse.immediate) {
                    // Content is cached (Zurg or RD cache) - start loading immediately in background
                    println("[INFO] Immediate playback from ${startResponse.source}")

                    _uiState.value = _uiState.value.copy(
                        loadingPhase = LoadingPhase.SEARCHING,
                        downloadMessage = "Preparing playback..."
                    )

                    // Start player loading in background immediately
                    startPlayback(startResponse.streamUrl!!, startResponse.fileName)

                    // Wait 3 seconds for animation while player loads in background
                    delay(3000)

                    // After animation, transition to ready state (will hide overlay and show player)
                    _uiState.value = _uiState.value.copy(
                        loadingPhase = LoadingPhase.READY
                    )
                } else {
                    // Need to download from RD - poll for progress
                    val jobId = startResponse.jobId!!
                    println("[INFO] Starting RD download, jobId: $jobId")

                    _uiState.value = _uiState.value.copy(
                        loadingPhase = LoadingPhase.SEARCHING,
                        downloadJobId = jobId
                    )

                    pollDownloadProgress(jobId)
                }
            } catch (e: Exception) {
                println("[ERROR] VideoPlayerViewModel: Failed to load video: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to start playback: ${e.message}"
                )
            }
        }
    }

    private suspend fun pollDownloadProgress(jobId: String) {
        try {
            while (true) {
                delay(2000) // Poll every 2 seconds

                val progress = api.getStreamProgress(jobId)

                // Update loading phase based on server status
                val loadingPhase = when (progress.status) {
                    "searching" -> LoadingPhase.SEARCHING
                    "downloading" -> LoadingPhase.DOWNLOADING
                    else -> _uiState.value.loadingPhase
                }

                _uiState.value = _uiState.value.copy(
                    loadingPhase = loadingPhase,
                    downloadProgress = progress.progress,
                    downloadMessage = progress.message
                )

                when (progress.status) {
                    "completed" -> {
                        println("[INFO] Download complete, preparing playback")

                        // Update to preparing state
                        _uiState.value = _uiState.value.copy(
                            loadingPhase = LoadingPhase.SEARCHING,
                            downloadProgress = 100,
                            downloadMessage = "Preparing playback..."
                        )

                        // Start player loading in background immediately
                        startPlayback(progress.streamUrl!!, progress.fileName)

                        // Wait 3 seconds for animation while player loads in background
                        delay(3000)

                        // After animation, transition to ready state (will hide overlay and show player)
                        _uiState.value = _uiState.value.copy(
                            loadingPhase = LoadingPhase.READY
                        )
                        break
                    }
                    "error" -> {
                        throw Exception(progress.error ?: "Download failed")
                    }
                    else -> {
                        // Continue polling
                        println("[DEBUG] Download progress: ${progress.progress}% - ${progress.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("[ERROR] Download polling failed: ${e.message}")
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Download failed: ${e.message}"
            )
        }
    }

    private fun startPlayback(streamUrl: String, fileName: String?) {
        println("[DEBUG] startPlayback: Setting media item: $streamUrl")

        // Explicitly set MIME type based on file extension to override RD's "application/force-download"
        val mimeType = when {
            streamUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            streamUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
            streamUrl.contains(".avi", ignoreCase = true) -> "video/x-msvideo"
            else -> null
        }

        val mediaItem = if (mimeType != null) {
            MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(mimeType)
                .build()
        } else {
            MediaItem.fromUri(streamUrl)
        }

        _player?.apply {
            playWhenReady = true  // Set BEFORE prepare to start immediately when ready
            setMediaItem(mediaItem)
            prepare()

            // Store resume position to seek after player is ready and duration is known
            if (resumePosition != null && resumePosition > 0) {
                println("[DEBUG] Storing pending seek position: $resumePosition ms")
                pendingSeekPosition = resumePosition
            }
        }

        println("[DEBUG] startPlayback: Using MIME type: $mimeType, Player state = ${_player?.playbackState}, playWhenReady = ${_player?.playWhenReady}")

        // Clear download job but don't change loading phase - let caller control it
        _uiState.value = _uiState.value.copy(
            downloadJobId = null
            // Let caller control loadingPhase for animation timing
            // Let the player listener handle isLoading based on actual buffering state
        )

        startHeartbeat()
    }

    fun cancelDownload() {
        viewModelScope.launch {
            _uiState.value.downloadJobId?.let { jobId ->
                try {
                    api.cancelStream(jobId)
                    println("[INFO] Cancelled download job: $jobId")
                } catch (e: Exception) {
                    println("[ERROR] Failed to cancel download: ${e.message}")
                }
            }
        }
    }

    private fun startHeartbeat() {
        // Start VOD heartbeat
        viewModelScope.launch {
            while (_player != null) {
                delay(30000) // 30 seconds
                try {
                    api.sendVodHeartbeat()
                    saveProgress() // Also save progress with heartbeat
                } catch (e: Exception) {
                    println("[ERROR] VideoPlayerViewModel: Heartbeat failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun saveProgress() {
        _player?.let { player ->
            if (player.duration > 0) {
                val isCompleted = player.currentPosition >= (player.duration * 0.9)
                val progress = WatchProgressEntity(
                    tmdbId = tmdbId,
                    title = contentTitle,
                    type = contentType,
                    year = year,
                    posterUrl = null, // TODO: Get from detail response
                    position = player.currentPosition,
                    duration = player.duration,
                    lastWatchedAt = System.currentTimeMillis(),
                    isCompleted = isCompleted
                )
                watchProgressDao.saveProgress(progress)

                // Auto-remove from watchlist when completed (movies only)
                if (isCompleted && contentType == "movie") {
                    try {
                        watchlistDao.remove(tmdbId)
                    } catch (e: Exception) {
                        // Watchlist removal failure is not critical
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        _player?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun seekForward() {
        _player?.let { player ->
            val newPosition = (player.currentPosition + 10000).coerceAtMost(player.duration)
            player.seekTo(newPosition)
        }
    }

    fun seekBackward() {
        _player?.let { player ->
            val newPosition = (player.currentPosition - 10000).coerceAtLeast(0)
            player.seekTo(newPosition)
        }
    }

    fun seekTo(position: Long) {
        _player?.seekTo(position)
    }

    fun toggleControls() {
        _uiState.value = _uiState.value.copy(showControls = !_uiState.value.showControls)
    }

    fun showControls() {
        _uiState.value = _uiState.value.copy(showControls = true)
    }

    fun hideControls() {
        _uiState.value = _uiState.value.copy(showControls = false)
    }

    fun showTrackSelection() {
        _uiState.value = _uiState.value.copy(showTrackSelection = true)
    }

    fun hideTrackSelection() {
        _uiState.value = _uiState.value.copy(showTrackSelection = false)
    }

    fun retryPlayback() {
        _uiState.value = _uiState.value.copy(
            error = null,
            isLoading = true
        )
        loadVideoUrl()
    }

    fun selectAudioTrack(trackId: String) {
        // TODO: Implement audio track selection
        // This requires using TrackSelectionOverride
    }

    fun selectSubtitleTrack(trackId: String) {
        // TODO: Implement subtitle track selection
        // This requires using TrackSelectionOverride
    }

    private fun fetchTmdbLogo() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val apiKey = "2e0fbf76c02c5ed160c195b216daa7b3"
                val endpoint = if (contentType == "tv") "tv" else "movie"
                val url = "https://api.themoviedb.org/3/$endpoint/$tmdbId/images?api_key=$apiKey"

                println("[DEBUG] Fetching TMDB logo from: $url")

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val jsonString = response.body?.string()

                println("[DEBUG] TMDB API response code: ${response.code}")

                if (response.isSuccessful && jsonString != null) {
                    println("[DEBUG] TMDB response length: ${jsonString.length}")

                    // Parse JSON to extract logo file_path
                    // Using simple string matching since we don't have Gson/Moshi in scope
                    val logosMatch = """"logos"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
                        .find(jsonString)

                    if (logosMatch != null) {
                        val logosArray = logosMatch.groupValues[1]
                        println("[DEBUG] Logos array found, length: ${logosArray.length}")

                        val filePathMatch = """"file_path"\s*:\s*"(/[^"]+)"""".toRegex()
                            .find(logosArray)

                        if (filePathMatch != null) {
                            val filePath = filePathMatch.groupValues[1]
                            val logoUrl = "https://image.tmdb.org/t/p/w500$filePath" // Use w500 for better quality
                            println("[INFO] ✅ Fetched TMDB logo: $logoUrl")

                            _uiState.value = _uiState.value.copy(logoUrl = logoUrl)
                        } else {
                            println("[WARN] ⚠️ No logo file_path found in logos array")
                            println("[DEBUG] First 200 chars of logos array: ${logosArray.take(200)}")
                        }
                    } else {
                        println("[WARN] ⚠️ No logos array found in TMDB response")
                        println("[DEBUG] First 500 chars of response: ${jsonString.take(500)}")
                    }
                } else {
                    println("[ERROR] ❌ Failed to fetch TMDB images: ${response.code}")
                    println("[DEBUG] Response body: ${jsonString?.take(200)}")
                }
            } catch (e: Exception) {
                println("[ERROR] ❌ Exception fetching TMDB logo: ${e.message}")
                e.printStackTrace()
                // Non-critical, continue without logo
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        // Save final progress and end session
        viewModelScope.launch {
            try {
                saveProgress()
                api.endVodSession()
            } catch (e: Exception) {
                println("[ERROR] VideoPlayerViewModel: Failed to save final progress: ${e.message}")
            }
        }

        _player?.release()
        _player = null
    }
}
