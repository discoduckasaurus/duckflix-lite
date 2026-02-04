package com.duckflix.lite.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.duckflix.lite.data.bandwidth.StutterDetector
import com.duckflix.lite.data.local.dao.PlaybackErrorDao
import com.duckflix.lite.data.local.dao.WatchProgressDao
import com.duckflix.lite.data.local.dao.WatchlistDao
import com.duckflix.lite.data.local.entity.PlaybackErrorEntity
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

data class AudioTrackCandidate(
    val trackId: String,
    val label: String,
    val language: String?,
    val title: String?,
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
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPercentage: Int = 0,
    val posterUrl: String? = null,
    val logoUrl: String? = null, // English logo (transparent PNG) for loading screens
    val autoPlayEnabled: Boolean = false,
    val nextEpisodeInfo: com.duckflix.lite.data.remote.dto.NextEpisodeResponse? = null,
    val movieRecommendations: List<com.duckflix.lite.data.remote.dto.MovieRecommendationItem>? = null,
    val selectedRecommendation: com.duckflix.lite.data.remote.dto.MovieRecommendationItem? = null,
    val isLoadingNextContent: Boolean = false,
    val showSeriesCompleteOverlay: Boolean = false,
    val showRecommendationsOverlay: Boolean = false,
    val autoPlayCountdown: Int = 0
)

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DuckFlixApi,
    private val watchProgressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao,
    private val playbackErrorDao: PlaybackErrorDao,
    private val autoPlaySettingsDao: com.duckflix.lite.data.local.dao.AutoPlaySettingsDao,
    private val okHttpClient: okhttp3.OkHttpClient,
    private val stutterDetector: StutterDetector,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tmdbId: Int = checkNotNull(savedStateHandle["tmdbId"])
    private val contentTitle: String = savedStateHandle["title"] ?: "Video"
    private val contentType: String = savedStateHandle["type"] ?: "movie"
    private val year: String? = savedStateHandle["year"]
    private val season: Int? = savedStateHandle.get<Int>("season")?.takeIf { it != -1 }
    private val episode: Int? = savedStateHandle.get<Int>("episode")?.takeIf { it != -1 }
    private val resumePosition: Long? = savedStateHandle.get<Long>("resumePosition")?.takeIf { it != -1L }
    private val posterUrl: String? = savedStateHandle["posterUrl"]
    private val logoUrl: String? = savedStateHandle["logoUrl"]
    private val originalLanguage: String? = savedStateHandle["originalLanguage"]
    private var pendingSeekPosition: Long? = null
    private var currentStreamUrl: String? = null
    private var currentErrorId: Int? = null
    private var isSelectingTrack = false // Prevent infinite loop during auto-selection

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var _player: ExoPlayer? = null
    val player: ExoPlayer?
        get() = _player

    private var autoPlayCountdownJob: kotlinx.coroutines.Job? = null
    var onAutoPlayNext: ((season: Int, episode: Int) -> Unit)? = null
    var onAutoPlayRecommendation: ((tmdbId: Int) -> Unit)? = null

    init {
        // Set initial posterUrl and logoUrl from server
        _uiState.value = _uiState.value.copy(
            posterUrl = posterUrl,
            logoUrl = logoUrl
        )

        println("[LOGO-DEBUG] Received logoUrl from navigation: $logoUrl")
        println("[LOGO-DEBUG] Received posterUrl from navigation: $posterUrl")

        // Initialize stutter detector with playback settings
        viewModelScope.launch {
            stutterDetector.initialize()
            stutterDetector.reset() // Reset for new content
        }

        loadAutoPlaySettings()
        initializePlayer()
        loadVideoUrl()
        startPositionUpdates()

        // NEVER fetch from TMDB - ONLY use server-provided logoUrl
        // If logoUrl is null, the loading screen will fallback to posterUrl automatically
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
                        Player.STATE_BUFFERING -> {
                            // Record buffering event and check for fallback
                            handleBufferingEvent()
                            "BUFFERING"
                        }
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

                    if (playbackState == Player.STATE_ENDED) {
                        handleVideoEnded()
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

                    // Log error to database for tracking and fallback strategies
                    viewModelScope.launch {
                        try {
                            val errorEntity = PlaybackErrorEntity(
                                tmdbId = tmdbId,
                                title = contentTitle,
                                type = contentType,
                                season = season,
                                episode = episode,
                                errorCode = error.errorCodeName,
                                errorMessage = error.message ?: "Unknown error",
                                errorCause = error.cause?.message,
                                streamUrl = currentStreamUrl
                            )
                            val errorId = playbackErrorDao.insertError(errorEntity)
                            currentErrorId = errorId.toInt()

                            println("[ERROR-TRACKING] Logged error ID: $errorId for $contentTitle")
                            println("[ERROR-TRACKING] Error details: ${error.errorCodeName} - ${error.message}")

                            // Check if we've seen this error before and had a successful fallback
                            val resolvedErrors = playbackErrorDao.getResolvedErrorsByCode(error.errorCodeName)
                            if (resolvedErrors.isNotEmpty()) {
                                println("[ERROR-TRACKING] Found ${resolvedErrors.size} previously resolved errors with code ${error.errorCodeName}")
                                // Future: Apply known fallback strategy based on resolved errors
                            }
                        } catch (e: Exception) {
                            println("[ERROR] Failed to log playback error: ${e.message}")
                        }
                    }

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

            // Build list of audio tracks with metadata
            val candidates = mutableListOf<AudioTrackCandidate>()

            player.currentTracks.groups.forEach { group ->
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)

                    when {
                        format.sampleMimeType?.startsWith("audio/") == true -> {
                            val trackId = "${group.mediaTrackGroup.id}_$i"
                            val label = format.label ?: format.language ?: "Audio ${audioTracks.size + 1}"
                            println("[TRACK-CREATE] Audio: ID='$trackId', Label='$label', Lang='${format.language}', Selected=$isSelected")

                            audioTracks.add(
                                TrackInfo(
                                    id = trackId,
                                    label = label,
                                    isSelected = isSelected
                                )
                            )

                            candidates.add(
                                AudioTrackCandidate(
                                    trackId = trackId,
                                    label = label,
                                    language = format.language,
                                    title = format.label,
                                    isSelected = isSelected
                                )
                            )
                        }
                        format.sampleMimeType?.startsWith("text/") == true ||
                        format.sampleMimeType?.startsWith("application/") == true -> {
                            val subtitleId = "${group.mediaTrackGroup.id}_$i"
                            val label = format.label ?: format.language ?: "Subtitle ${subtitleTracks.size + 1}"
                            println("[TRACK-CREATE] Subtitle: ID='$subtitleId', Label='$label', Selected=$isSelected")

                            subtitleTracks.add(
                                TrackInfo(
                                    id = subtitleId,
                                    label = label,
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

            // ALWAYS run smart audio track selection (don't let ExoPlayer's default selection override us)
            // Use flag to prevent infinite loop when selectAudioTrack calls updateAvailableTracks
            if (candidates.isNotEmpty() && !isSelectingTrack) {
                isSelectingTrack = true
                try {
                    val bestTrack = selectBestAudioTrack(candidates)
                    if (bestTrack != null && !bestTrack.isSelected) {
                        println("[AUTO-SELECT] Overriding ExoPlayer's selection - selecting best track: ${bestTrack.trackId} (${bestTrack.label})")
                        selectAudioTrack(bestTrack.trackId)
                    } else if (bestTrack != null && bestTrack.isSelected) {
                        println("[AUTO-SELECT] Best track already selected: ${bestTrack.trackId} (${bestTrack.label})")
                    }
                } finally {
                    isSelectingTrack = false
                }
            }
        }
    }

    /**
     * Smart audio track selection based on originalLanguage metadata
     */
    private fun selectBestAudioTrack(candidates: List<AudioTrackCandidate>): AudioTrackCandidate? {
        if (candidates.isEmpty()) return null

        val origLang = originalLanguage?.lowercase() ?: "en"
        println("[AudioTrack] Movie original language: $origLang")
        println("[AudioTrack] Available tracks:")
        candidates.forEachIndexed { index, track ->
            println("[AudioTrack]   [$index] ID='${track.trackId}', Lang='${track.language}', Title='${track.title}', Label='${track.label}'")
        }

        // Priority order for matching
        val priorities = listOf<(AudioTrackCandidate) -> Boolean>(
            // 1. Exact ISO code match (2 or 3 letter)
            { track ->
                val langLower = track.language?.lowercase()
                langLower == origLang || langLower?.take(2) == origLang.take(2)
            },

            // 2. English variants (eng, en, english)
            { track ->
                val langLower = track.language?.lowercase()
                val titleLower = track.title?.lowercase()
                langLower in listOf("eng", "en", "english") ||
                titleLower?.contains("english") == true
            },

            // 3. "Original" track when original language is English
            { track ->
                val titleLower = track.title?.lowercase()
                titleLower?.contains("original") == true && origLang == "en"
            },

            // 4. Undefined/null when original is English
            { track ->
                (track.language == null || track.language.lowercase() == "und") && origLang == "en"
            },

            // 5. Any track matching the original language ISO code (fuzzy match on first 2 chars)
            { track ->
                val langLower = track.language?.lowercase()
                langLower?.take(2) == origLang.take(2)
            }
        )

        // Try each priority level
        for (matcher in priorities) {
            val matchedTrack = candidates.firstOrNull(matcher)
            if (matchedTrack != null) {
                println("[AudioTrack] Selected best track: ID='${matchedTrack.trackId}', Label='${matchedTrack.label}', Lang='${matchedTrack.language}'")
                return matchedTrack
            }
        }

        // Fallback to first track
        println("[AudioTrack] No match found, selecting first track")
        return candidates.firstOrNull()
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
        currentStreamUrl = streamUrl

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

        // For movies with auto-play enabled, prefetch recommendations after 90 seconds
        if (_uiState.value.autoPlayEnabled && contentType == "movie") {
            viewModelScope.launch {
                delay(90000) // 90 seconds
                prefetchNextContent()
            }
        }
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
                // Different completion thresholds: 95% for TV shows, 90% for movies
                val completionThreshold = if (contentType == "tv") 0.95 else 0.90
                val isCompleted = player.currentPosition >= (player.duration * completionThreshold)
                val progress = WatchProgressEntity(
                    tmdbId = tmdbId,
                    title = contentTitle,
                    type = contentType,
                    year = year,
                    posterUrl = posterUrl,
                    position = player.currentPosition,
                    duration = player.duration,
                    lastWatchedAt = System.currentTimeMillis(),
                    isCompleted = isCompleted,
                    season = season,
                    episode = episode
                )
                watchProgressDao.saveProgress(progress)

                // Sync to server for recommendations
                try {
                    val syncRequest = com.duckflix.lite.data.remote.dto.WatchProgressSyncRequest(
                        tmdbId = tmdbId,
                        type = contentType,
                        title = contentTitle,
                        posterPath = posterUrl?.substringAfter("w500"), // Extract just the path
                        releaseDate = year,
                        position = player.currentPosition,
                        duration = player.duration,
                        season = season,
                        episode = episode
                    )
                    api.syncWatchProgress(syncRequest)
                } catch (e: Exception) {
                    println("[WARN] Failed to sync watch progress to server: ${e.message}")
                    // Non-critical - local progress is still saved
                }

                // Auto-remove from watchlist when completed (movies only)
                if (isCompleted && contentType == "movie") {
                    try {
                        watchlistDao.remove(tmdbId)
                        // Also remove from server
                        api.removeFromWatchlist(tmdbId, contentType)
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

    fun retryPlayback() {
        _uiState.value = _uiState.value.copy(
            error = null,
            isLoading = true
        )
        loadVideoUrl()
    }

    fun selectAudioTrack(trackId: String) {
        _player?.let { player ->
            try {
                println("[TRACK-SELECT] Attempting to select audio track: $trackId")

                val parts = trackId.split("_")
                if (parts.size < 2) {
                    println("[TRACK-SELECT] ERROR: Invalid track ID format: $trackId")
                    return
                }

                // Extract BOTH group ID and track index
                val groupId = parts.dropLast(1).joinToString("_")
                val trackIndex = parts.last().toIntOrNull()

                if (trackIndex == null) {
                    println("[TRACK-SELECT] ERROR: Invalid track index in ID: $trackId")
                    return
                }

                println("[TRACK-SELECT] Parsed - Group ID: '$groupId', Track Index: $trackIndex")

                // Find matching group by comparing group ID
                player.currentTracks.groups.forEachIndexed { groupIndex, group ->
                    val currentGroupId = group.mediaTrackGroup.id

                    // CRITICAL FIX: Check BOTH group ID and track index
                    if (currentGroupId == groupId) {
                        if (trackIndex < group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            if (format.sampleMimeType?.startsWith("audio/") == true) {
                                val label = format.label ?: format.language ?: "Audio $trackIndex"
                                println("[TRACK-SELECT] SUCCESS: Selecting audio - Group: '$currentGroupId', Index: $trackIndex, Label: '$label'")

                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(
                                        TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                                    )
                                    .build()
                                updateAvailableTracks()
                                return
                            }
                        }
                    }
                }

                println("[TRACK-SELECT] ERROR: No matching track found for ID: $trackId")
            } catch (e: Exception) {
                println("[ERROR] Failed to select audio track: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun selectSubtitleTrack(trackId: String) {
        _player?.let { player ->
            try {
                println("[TRACK-SELECT] Attempting to select subtitle track: $trackId")

                if (trackId == "none") {
                    println("[TRACK-SELECT] Disabling subtitles")
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                    updateAvailableTracks()
                    return
                }

                val parts = trackId.split("_")
                if (parts.size < 2) {
                    println("[TRACK-SELECT] ERROR: Invalid track ID format: $trackId")
                    return
                }

                // Extract BOTH group ID and track index
                val groupId = parts.dropLast(1).joinToString("_")
                val trackIndex = parts.last().toIntOrNull()

                if (trackIndex == null) {
                    println("[TRACK-SELECT] ERROR: Invalid track index in ID: $trackId")
                    return
                }

                println("[TRACK-SELECT] Parsed - Group ID: '$groupId', Track Index: $trackIndex")

                // Find matching group by comparing group ID
                player.currentTracks.groups.forEachIndexed { groupIndex, group ->
                    val currentGroupId = group.mediaTrackGroup.id

                    // CRITICAL FIX: Check BOTH group ID and track index
                    if (currentGroupId == groupId) {
                        if (trackIndex < group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            if (format.sampleMimeType?.startsWith("text/") == true ||
                                format.sampleMimeType?.startsWith("application/") == true) {
                                val label = format.label ?: format.language ?: "Subtitle $trackIndex"
                                println("[TRACK-SELECT] SUCCESS: Selecting subtitle - Group: '$currentGroupId', Index: $trackIndex, Label: '$label'")

                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(
                                        TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                                    )
                                    .build()
                                updateAvailableTracks()
                                return
                            }
                        }
                    }
                }

                println("[TRACK-SELECT] ERROR: No matching track found for ID: $trackId")
            } catch (e: Exception) {
                println("[ERROR] Failed to select subtitle track: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // fetchTmdbLogo() method REMOVED - We ONLY use server-provided logoPath from our API
    // Server API returns logoPath in detail endpoint, which is passed via navigation

    /**
     * Handle buffering event for adaptive bitrate - detect stuttering
     */
    private fun handleBufferingEvent() {
        viewModelScope.launch {
            if (stutterDetector.shouldTriggerFallback()) {
                println("[INFO] Stutter detected, requesting lower quality fallback")

                val fallbackUrl = stutterDetector.requestFallback(
                    tmdbId = tmdbId,
                    type = contentType,
                    year = year,
                    season = season,
                    episode = episode,
                    duration = (_uiState.value.duration / 60000).toInt(), // Convert ms to minutes
                    currentBitrate = null, // Could extract from track info if needed
                    scope = viewModelScope
                )

                if (fallbackUrl != null) {
                    println("[INFO] Switching to fallback URL: $fallbackUrl")

                    // Mark current error as resolved if we had one
                    currentErrorId?.let { errorId ->
                        try {
                            playbackErrorDao.markAsResolved(errorId)
                            println("[ERROR-TRACKING] Marked error $errorId as resolved via fallback")
                        } catch (e: Exception) {
                            println("[ERROR] Failed to mark error as resolved: ${e.message}")
                        }
                    }

                    currentStreamUrl = fallbackUrl

                    // Save current position
                    val currentPos = _player?.currentPosition ?: 0

                    // Load fallback URL
                    _player?.apply {
                        setMediaItem(MediaItem.fromUri(fallbackUrl))
                        prepare()
                        seekTo(currentPos)
                        play()
                    }
                } else {
                    println("[WARN] No fallback URL available")
                }
            } else {
                stutterDetector.recordBufferEvent()
            }
        }
    }

    // Auto-play methods
    private fun loadAutoPlaySettings() {
        viewModelScope.launch {
            val settings = autoPlaySettingsDao.getSettingsOnce()
            if (settings != null) {
                val shouldEnable = if (contentType == "tv") {
                    settings.enabled && settings.lastSeriesTmdbId == tmdbId
                } else {
                    settings.sessionEnabled
                }
                _uiState.value = _uiState.value.copy(autoPlayEnabled = shouldEnable)
            }
        }
    }

    fun toggleAutoPlay() {
        viewModelScope.launch {
            val newState = !_uiState.value.autoPlayEnabled
            _uiState.value = _uiState.value.copy(autoPlayEnabled = newState)

            val settings = autoPlaySettingsDao.getSettingsOnce() ?: com.duckflix.lite.data.local.entity.AutoPlaySettingsEntity()
            if (contentType == "tv") {
                autoPlaySettingsDao.saveSettings(
                    settings.copy(enabled = newState, lastSeriesTmdbId = if (newState) tmdbId else null)
                )
            } else {
                autoPlaySettingsDao.saveSettings(settings.copy(sessionEnabled = newState))
            }

            if (newState && _player?.duration != null) {
                prefetchNextContent()
            }
        }
    }

    private fun prefetchNextContent() {
        if (_uiState.value.isLoadingNextContent) return
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingNextContent = true)
                if (contentType == "tv" && season != null && episode != null) {
                    val nextEpisode = api.getNextEpisode(tmdbId, season, episode)
                    _uiState.value = _uiState.value.copy(
                        nextEpisodeInfo = nextEpisode,
                        isLoadingNextContent = false
                    )
                } else {
                    val recommendations = api.getContentRecommendations(tmdbId, limit = 4)
                    _uiState.value = _uiState.value.copy(
                        movieRecommendations = recommendations.recommendations,
                        selectedRecommendation = recommendations.recommendations.randomOrNull(),
                        isLoadingNextContent = false
                    )
                }
            } catch (e: Exception) {
                println("[AutoPlay] Prefetch failed: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoadingNextContent = false)
            }
        }
    }

    private fun handleVideoEnded() {
        if (!_uiState.value.autoPlayEnabled) return
        if (contentType == "tv") handleTVEpisodeEnded() else handleMovieEnded()
    }

    private fun handleTVEpisodeEnded() {
        val nextEpisode = _uiState.value.nextEpisodeInfo
        if (nextEpisode == null || !nextEpisode.hasNext) {
            _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = true)
            return
        }
        startAutoPlayCountdown {
            onAutoPlayNext?.invoke(nextEpisode.season!!, nextEpisode.episode!!)
        }
    }

    private fun handleMovieEnded() {
        val selected = _uiState.value.selectedRecommendation
        if (selected == null || _uiState.value.movieRecommendations.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(showRecommendationsOverlay = true)
            return
        }
        startAutoPlayCountdown {
            onAutoPlayRecommendation?.invoke(selected.tmdbId)
        }
    }

    private fun startAutoPlayCountdown(onComplete: () -> Unit) {
        autoPlayCountdownJob?.cancel()
        _uiState.value = _uiState.value.copy(autoPlayCountdown = 5)
        autoPlayCountdownJob = viewModelScope.launch {
            for (i in 5 downTo 1) {
                _uiState.value = _uiState.value.copy(autoPlayCountdown = i)
                delay(1000)
            }
            onComplete()
        }
    }

    fun cancelAutoPlay() {
        autoPlayCountdownJob?.cancel()
        _uiState.value = _uiState.value.copy(
            autoPlayCountdown = 0,
            showSeriesCompleteOverlay = false,
            showRecommendationsOverlay = false
        )
    }

    fun dismissSeriesComplete() {
        _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = false)
    }

    fun dismissRecommendations() {
        _uiState.value = _uiState.value.copy(showRecommendationsOverlay = false)
    }

    fun selectRecommendation(rec: com.duckflix.lite.data.remote.dto.MovieRecommendationItem) {
        _uiState.value = _uiState.value.copy(selectedRecommendation = rec)
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
