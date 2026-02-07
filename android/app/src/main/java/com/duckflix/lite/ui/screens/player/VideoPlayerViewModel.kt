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
    SEARCHING,    // Finding sources (unified - no cache check phase)
    DOWNLOADING,  // Server downloading from Real-Debrid
    BUFFERING,    // ExoPlayer buffering
    READY         // Ready to play
}

data class PlayerUiState(
    val isLoading: Boolean = true,
    val loadingPhase: LoadingPhase = LoadingPhase.SEARCHING,
    val downloadProgress: Int = 0,
    val downloadMessage: String = "",
    val sourceStatus: String = "", // Secondary status like "Trying source 1", "Requesting Download"
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
    val showRandomEpisodeError: Boolean = false,
    val autoPlayCountdown: Int = 0,
    val showVideoIssuesPanel: Boolean = false,
    val showAudioPanel: Boolean = false,
    val showSubtitlePanel: Boolean = false,
    val isReportingBadLink: Boolean = false,
    val displayQuality: String = "", // e.g., "2160p" from server
    // Prefetch state for seamless auto-play
    val prefetchJobId: String? = null,
    val prefetchNextEpisode: com.duckflix.lite.data.remote.dto.PrefetchEpisodeInfo? = null,
    val hasPrefetched: Boolean = false
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
    private val isRandomPlayback: Boolean = savedStateHandle.get<Boolean>("isRandom") ?: false
    private var pendingSeekPosition: Long? = null
    private var currentStreamUrl: String? = null
    private var currentErrorId: Int? = null
    private var isSelectingTrack = false // Prevent infinite loop during auto-selection
    private var isAddingSubtitles = false // Don't show errors during subtitle loading

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
                    val currentPos = player.currentPosition
                    val duration = player.duration.coerceAtLeast(0)

                    _uiState.value = _uiState.value.copy(
                        currentPosition = currentPos,
                        duration = duration,
                        bufferedPercentage = player.bufferedPercentage
                    )

                    // Trigger prefetch at 75% for TV episodes
                    if (contentType == "tv" &&
                        duration > 0 &&
                        !_uiState.value.hasPrefetched &&
                        _uiState.value.autoPlayEnabled &&
                        season != null && episode != null) {
                        val progressPercent = (currentPos * 100 / duration).toInt()
                        if (progressPercent >= 75) {
                            triggerPrefetch()
                        }
                    }
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

        // Audio codec support: Enable FFmpeg extension for DDP5.1 Atmos, DTS, TrueHD, etc.
        // EXTENSION_RENDERER_MODE_PREFER tries FFmpeg software decoder first (handles EAC3/Atmos)
        // and falls back to MediaCodec hardware decoders if needed
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        _player = ExoPlayer.Builder(context, renderersFactory)
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

                        // Clear subtitle loading flag on successful ready state
                        if (isAddingSubtitles) {
                            println("[SUBTITLES] Subtitle loading completed successfully")
                            isAddingSubtitles = false
                        }

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

                    // If error occurred while adding subtitles, don't crash playback
                    if (isAddingSubtitles) {
                        println("[SUBTITLES] Error during subtitle loading - ignoring and continuing playback")
                        isAddingSubtitles = false
                        return
                    }

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
                        loadingPhase = LoadingPhase.READY,
                        error = "⚠️ Unfortunately this title isn't available at the moment. Please try again later."
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

    /**
     * Validates that a stream URL is valid before attempting playback
     */
    private fun isValidStreamUrl(url: String?): Boolean {
        return url != null && url.startsWith("http")
    }

    private fun loadVideoUrl() {
        viewModelScope.launch {
            try {
                // Check VOD session
                api.checkVodSession()

                _uiState.value = _uiState.value.copy(
                    loadingPhase = LoadingPhase.SEARCHING,
                    downloadMessage = ""  // Server will provide via polling
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

                    // Validate stream URL before attempting playback
                    if (!isValidStreamUrl(startResponse.streamUrl)) {
                        throw Exception("Unable to find a working stream. Please try again later.")
                    }

                    _uiState.value = _uiState.value.copy(
                        loadingPhase = LoadingPhase.SEARCHING,
                        downloadMessage = "Preparing playback..."
                    )

                    // Start player loading in background immediately
                    startPlayback(startResponse.streamUrl!!, startResponse.fileName)

                    // Fetch subtitles in background (non-blocking)
                    fetchSubtitlesInBackground(startResponse.subtitles)

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
                    loadingPhase = LoadingPhase.READY,
                    error = "⚠️ Unfortunately this title isn't available at the moment. Please try again later."
                )
            }
        }
    }

    private suspend fun pollDownloadProgress(jobId: String) {
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 3

        try {
            while (true) {
                delay(2000) // Poll every 2 seconds

                try {
                    val progress = api.getStreamProgress(jobId)
                    consecutiveErrors = 0 // Reset error counter on successful poll

                    println("[POLL] Status: ${progress.status}, Progress: ${progress.progress}%, Message: '${progress.message}', Error: '${progress.error}'")

                    // Check for error conditions first - error field or error-like messages
                    if (progress.error != null) {
                        throw Exception(progress.error)
                    }

                    // Update loading phase based on server status
                    val loadingPhase = when (progress.status) {
                        "searching" -> LoadingPhase.SEARCHING
                        "downloading" -> LoadingPhase.DOWNLOADING
                        else -> _uiState.value.loadingPhase
                    }

                    // Use server message directly - server provides comprehensive, user-friendly messages
                    _uiState.value = _uiState.value.copy(
                        loadingPhase = loadingPhase,
                        downloadProgress = progress.progress,
                        downloadMessage = progress.message
                    )

                    when (progress.status) {
                        "completed" -> {
                            // Validate stream URL before attempting playback
                            if (!isValidStreamUrl(progress.streamUrl)) {
                                throw Exception("Unable to find a working stream. Please try again later.")
                            }

                            println("[INFO] Download complete, preparing playback")

                            // Update to preparing state
                            _uiState.value = _uiState.value.copy(
                                loadingPhase = LoadingPhase.SEARCHING,
                                downloadProgress = 100,
                                downloadMessage = "Preparing playback..."
                            )

                            // Start player loading in background immediately
                            startPlayback(progress.streamUrl!!, progress.fileName)

                            // Fetch subtitles in background (non-blocking)
                            fetchSubtitlesInBackground(progress.subtitles)

                            // Wait 3 seconds for animation while player loads in background
                            delay(3000)

                            // After animation, transition to ready state (will hide overlay and show player)
                            _uiState.value = _uiState.value.copy(
                                loadingPhase = LoadingPhase.READY
                            )
                            break
                        }
                        "failed", "error" -> {
                            // Use server message if available, otherwise generic message
                            val errorMsg = progress.message.takeIf {
                                it.isNotBlank() && !it.equals("searching", ignoreCase = true)
                            } ?: "Unable to find a working stream. Please try again later."
                            throw Exception(errorMsg)
                        }
                        "searching", "downloading" -> {
                            // Continue polling - these are expected active states
                            println("[POLL] Still ${progress.status}, continuing poll loop")
                        }
                        else -> {
                            // Continue polling for unknown states
                            println("[POLL] Unknown status '${progress.status}', continuing poll loop")
                        }
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
                    println("[ERROR] Poll request failed (${consecutiveErrors}/$maxConsecutiveErrors): ${e.message}")

                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        println("[ERROR] Too many consecutive poll failures, giving up")
                        throw Exception("Connection lost while searching for stream")
                    }

                    // Continue polling despite individual request failures
                }
            }
        } catch (e: Exception) {
            println("[ERROR] Download polling failed: ${e.message}")
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadingPhase = LoadingPhase.READY,
                error = "⚠️ ${e.message ?: "Unfortunately this title isn't available at the moment. Please try again later."}"
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
                        logoPath = _uiState.value.logoUrl?.substringAfter("w500"), // Extract logo path
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

    fun play() {
        _player?.play()
    }

    fun pause() {
        _player?.pause()
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
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
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
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
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
                    if (isRandomPlayback) {
                        // For random playback, get another random episode
                        val randomEpisode = api.getRandomEpisode(tmdbId)
                        // Convert to NextEpisodeResponse format
                        val nextEpisode = com.duckflix.lite.data.remote.dto.NextEpisodeResponse(
                            hasNext = true,
                            season = randomEpisode.season,
                            episode = randomEpisode.episode,
                            title = randomEpisode.title,
                            inCurrentPack = true // Random episodes are always available
                        )
                        _uiState.value = _uiState.value.copy(
                            nextEpisodeInfo = nextEpisode,
                            isLoadingNextContent = false
                        )
                    } else {
                        // For sequential playback, get next episode
                        // Pass title for pack-check optimization (prevents server-side error log)
                        val nextEpisode = api.getNextEpisode(tmdbId, season, episode, contentTitle)
                        _uiState.value = _uiState.value.copy(
                            nextEpisodeInfo = nextEpisode,
                            isLoadingNextContent = false
                        )
                    }
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
        // Check if we have a prefetch job ready
        val prefetchEpisode = _uiState.value.prefetchNextEpisode
        val prefetchJobId = _uiState.value.prefetchJobId

        if (prefetchJobId != null && prefetchEpisode != null) {
            // Try to use prefetched content for seamless playback
            viewModelScope.launch {
                val isReady = promotePrefetchJob()
                if (isReady) {
                    println("[AutoPlay] Using prefetched content for S${prefetchEpisode.season}E${prefetchEpisode.episode}")
                    startAutoPlayCountdown {
                        onAutoPlayNext?.invoke(prefetchEpisode.season, prefetchEpisode.episode)
                    }
                } else {
                    // Prefetch not ready, fall back to normal flow
                    println("[AutoPlay] Prefetch not ready, using normal auto-play flow")
                    handleTVEpisodeEndedFallback()
                }
            }
            return
        }

        // No prefetch available, use normal flow
        handleTVEpisodeEndedFallback()
    }

    private fun handleTVEpisodeEndedFallback() {
        val nextEpisode = _uiState.value.nextEpisodeInfo
        if (nextEpisode == null || !nextEpisode.hasNext) {
            // In random mode, try to fetch a random episode on-the-fly if prefetch failed
            if (isRandomPlayback) {
                fetchRandomEpisodeAndPlay()
                return
            }
            // Show series complete only in sequential mode
            _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = true)
            return
        }

        // Validate season and episode are not null before invoking
        val season = nextEpisode.season
        val episode = nextEpisode.episode
        if (season == null || episode == null) {
            println("[ERROR] Next episode has hasNext=true but season or episode is null: season=$season, episode=$episode")
            // Don't show series complete in random mode
            if (!isRandomPlayback) {
                _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = true)
            }
            return
        }

        startAutoPlayCountdown {
            onAutoPlayNext?.invoke(season, episode)
        }
    }

    private fun fetchRandomEpisodeAndPlay() {
        viewModelScope.launch {
            try {
                println("[AutoPlay] Fetching random episode on-the-fly for tmdbId=$tmdbId")
                val randomEpisode = api.getRandomEpisode(tmdbId)
                println("[AutoPlay] Got random episode: S${randomEpisode.season}E${randomEpisode.episode}")

                // Start countdown and play
                startAutoPlayCountdown {
                    onAutoPlayNext?.invoke(randomEpisode.season, randomEpisode.episode)
                }
            } catch (e: Exception) {
                println("[AutoPlay] Failed to fetch random episode: ${e.message}")
                // Show error overlay to user
                _uiState.value = _uiState.value.copy(showRandomEpisodeError = true)
            }
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
            showRecommendationsOverlay = false,
            showRandomEpisodeError = false
        )
    }

    fun dismissSeriesComplete() {
        _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = false)
    }

    fun dismissRecommendations() {
        _uiState.value = _uiState.value.copy(showRecommendationsOverlay = false)
    }

    fun dismissRandomEpisodeError() {
        _uiState.value = _uiState.value.copy(showRandomEpisodeError = false)
    }

    /**
     * Trigger prefetch of next episode at 75% playback.
     * Starts downloading next content in background for seamless auto-play.
     */
    private fun triggerPrefetch() {
        if (_uiState.value.hasPrefetched) return
        val currentSeason = season ?: return
        val currentEpisode = episode ?: return

        _uiState.value = _uiState.value.copy(hasPrefetched = true)

        viewModelScope.launch {
            try {
                println("[Prefetch] Triggering prefetch for next episode after S${currentSeason}E${currentEpisode}")
                val mode = if (isRandomPlayback) "random" else "sequential"
                val request = com.duckflix.lite.data.remote.dto.PrefetchNextRequest(
                    tmdbId = tmdbId,
                    title = contentTitle,
                    year = year,
                    type = contentType,
                    currentSeason = currentSeason,
                    currentEpisode = currentEpisode,
                    mode = mode
                )
                val response = api.prefetchNext(request)

                if (response.hasNext && response.jobId != null) {
                    println("[Prefetch] Started prefetch job ${response.jobId} for S${response.nextEpisode?.season}E${response.nextEpisode?.episode}")
                    _uiState.value = _uiState.value.copy(
                        prefetchJobId = response.jobId,
                        prefetchNextEpisode = response.nextEpisode
                    )
                } else {
                    println("[Prefetch] No next episode available (series finale or error)")
                }
            } catch (e: Exception) {
                println("[Prefetch] Failed to prefetch: ${e.message}")
                // Non-critical - auto-play will fall back to regular flow
            }
        }
    }

    /**
     * Promote prefetch job when ready to play next episode.
     * Returns true if promotion succeeded and playback can start immediately.
     * Also re-seeds the autoplay pipeline for the next episode in the chain.
     */
    private suspend fun promotePrefetchJob(): Boolean {
        val jobId = _uiState.value.prefetchJobId ?: return false

        try {
            println("[Prefetch] Promoting job $jobId")
            val response = api.promotePrefetch(jobId)

            if (response.success && response.status == "completed" && response.streamUrl != null) {
                println("[Prefetch] Job ready! Stream URL available")

                // Re-seed autoplay pipeline for the next episode in the chain
                if (response.hasNext && response.nextEpisode != null) {
                    println("[Prefetch] Chain continues: next is S${response.nextEpisode.season}E${response.nextEpisode.episode}")

                    // Update nextEpisodeInfo for the autoplay UI ("Up Next: S1E5 - Title")
                    val nextEpisodeInfo = com.duckflix.lite.data.remote.dto.NextEpisodeResponse(
                        hasNext = true,
                        season = response.nextEpisode.season,
                        episode = response.nextEpisode.episode,
                        title = response.nextEpisode.title,
                        inCurrentPack = true  // Prefetched content is always available
                    )

                    _uiState.value = _uiState.value.copy(
                        nextEpisodeInfo = nextEpisodeInfo,
                        // Clear old prefetch state - will be replaced by new prefetch
                        prefetchJobId = null,
                        prefetchNextEpisode = null,
                        hasPrefetched = false  // Allow new prefetch to trigger
                    )

                    // Trigger prefetch for the NEXT episode (the one after what we're about to play)
                    // Use contentInfo from promote response to build the prefetch request
                    val contentInfo = response.contentInfo
                    if (contentInfo != null) {
                        triggerChainPrefetch(
                            tmdbId = contentInfo.tmdbId,
                            title = contentInfo.title,
                            year = contentInfo.year,
                            type = contentInfo.type,
                            currentSeason = contentInfo.season,
                            currentEpisode = contentInfo.episode
                        )
                    }
                } else {
                    println("[Prefetch] No next episode after promoted content (series finale)")
                    // Clear prefetch state but don't set nextEpisodeInfo - series is ending
                    _uiState.value = _uiState.value.copy(
                        nextEpisodeInfo = null,
                        prefetchJobId = null,
                        prefetchNextEpisode = null
                    )
                }

                return true
            } else {
                println("[Prefetch] Job not ready: status=${response.status}, progress=${response.progress}")
                return false
            }
        } catch (e: Exception) {
            println("[Prefetch] Promote failed: ${e.message}")
            return false
        }
    }

    /**
     * Trigger prefetch for the next episode in the autoplay chain.
     * Called after promoting a prefetch job to keep the pipeline filled.
     */
    private fun triggerChainPrefetch(
        tmdbId: Int,
        title: String,
        year: String?,
        type: String,
        currentSeason: Int,
        currentEpisode: Int
    ) {
        viewModelScope.launch {
            try {
                println("[Prefetch] Triggering chain prefetch for next episode after S${currentSeason}E${currentEpisode}")
                val mode = if (isRandomPlayback) "random" else "sequential"
                val request = com.duckflix.lite.data.remote.dto.PrefetchNextRequest(
                    tmdbId = tmdbId,
                    title = title,
                    year = year,
                    type = type,
                    currentSeason = currentSeason,
                    currentEpisode = currentEpisode,
                    mode = mode
                )
                val response = api.prefetchNext(request)

                if (response.hasNext && response.jobId != null) {
                    println("[Prefetch] Chain prefetch started: job ${response.jobId} for S${response.nextEpisode?.season}E${response.nextEpisode?.episode}")
                    _uiState.value = _uiState.value.copy(
                        prefetchJobId = response.jobId,
                        prefetchNextEpisode = response.nextEpisode,
                        hasPrefetched = true
                    )
                } else {
                    println("[Prefetch] No next episode to prefetch (chain ends)")
                }
            } catch (e: Exception) {
                println("[Prefetch] Chain prefetch failed: ${e.message}")
                // Non-critical - autoplay will fall back to regular flow
            }
        }
    }

    fun selectRecommendation(rec: com.duckflix.lite.data.remote.dto.MovieRecommendationItem) {
        _uiState.value = _uiState.value.copy(selectedRecommendation = rec)
    }

    fun setVideoIssuesPanelVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(
            showVideoIssuesPanel = visible,
            showAudioPanel = if (visible) false else _uiState.value.showAudioPanel,
            showSubtitlePanel = if (visible) false else _uiState.value.showSubtitlePanel
        )
    }

    fun toggleAudioPanel() {
        val newState = !_uiState.value.showAudioPanel
        _uiState.value = _uiState.value.copy(
            showAudioPanel = newState,
            showSubtitlePanel = false,
            showVideoIssuesPanel = false
        )
    }

    fun toggleSubtitlePanel() {
        val newState = !_uiState.value.showSubtitlePanel
        _uiState.value = _uiState.value.copy(
            showSubtitlePanel = newState,
            showAudioPanel = false,
            showVideoIssuesPanel = false
        )
    }

    fun reportBadLink() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isReportingBadLink = true)

                // Use current job ID if available, or create a new request
                val jobId = _uiState.value.downloadJobId ?: "unknown"
                val request = com.duckflix.lite.data.remote.dto.ReportBadRequest(
                    jobId = jobId,
                    reason = "User reported bad stream from player"
                )

                val response = api.reportBadStream(request)

                if (response.success && response.newJobId != null) {
                    // Server provided new job - start polling for new stream
                    println("[REPORT-BAD] Got new job ID: ${response.newJobId}")
                    _uiState.value = _uiState.value.copy(
                        isReportingBadLink = false,
                        showVideoIssuesPanel = false,
                        loadingPhase = LoadingPhase.SEARCHING,
                        downloadJobId = response.newJobId
                    )
                    pollDownloadProgress(response.newJobId)
                } else {
                    println("[REPORT-BAD] No new stream available: ${response.message}")
                    _uiState.value = _uiState.value.copy(
                        isReportingBadLink = false,
                        error = response.message ?: "No alternative streams available"
                    )
                }
            } catch (e: Exception) {
                println("[ERROR] Failed to report bad link: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isReportingBadLink = false,
                    error = "Failed to report issue: ${e.message}"
                )
            }
        }
    }

    /**
     * Fetch subtitles in background after playback starts
     * This is non-blocking - subtitles will be added dynamically when available
     */
    private fun fetchSubtitlesInBackground(initialSubtitles: List<com.duckflix.lite.data.remote.dto.SubtitleDto>?) {
        // If subtitles already provided, process them
        if (!initialSubtitles.isNullOrEmpty()) {
            println("[SUBTITLES] Using ${initialSubtitles.size} subtitles from stream response")
            processSubtitles(initialSubtitles)
            return
        }

        // Fetch subtitles in background
        viewModelScope.launch {
            try {
                println("[SUBTITLES] Fetching subtitles in background for $contentTitle")

                val request = com.duckflix.lite.data.remote.dto.SubtitleSearchRequest(
                    tmdbId = tmdbId,
                    title = contentTitle,
                    year = year,
                    type = contentType,
                    season = season,
                    episode = episode
                )

                val response = api.searchSubtitles(request)

                if (response.subtitles.isNotEmpty()) {
                    println("[SUBTITLES] Found ${response.subtitles.size} subtitles, processing...")
                    processSubtitles(response.subtitles)
                } else {
                    println("[SUBTITLES] No subtitles found")
                }
            } catch (e: Exception) {
                println("[SUBTITLES] Failed to fetch subtitles: ${e.message}")
                // Non-critical - playback continues without subtitles
            }
        }
    }

    /**
     * Process subtitles - route embedded vs external subtitles differently
     */
    private fun processSubtitles(subtitles: List<com.duckflix.lite.data.remote.dto.SubtitleDto>) {
        // Separate embedded from external subtitles
        val embeddedSubs = subtitles.filter { it.source == "embedded" && it.streamIndex != null }
        val externalSubs = subtitles.filter { it.source != "embedded" && it.url != null }

        println("[SUBTITLES] Found ${embeddedSubs.size} embedded, ${externalSubs.size} external subtitles")

        // Store embedded subtitle info for track selection
        if (embeddedSubs.isNotEmpty()) {
            storeEmbeddedSubtitleInfo(embeddedSubs)
        }

        // Add external subtitles to player
        if (externalSubs.isNotEmpty()) {
            addExternalSubtitles(externalSubs)
        }
    }

    // Store server-provided embedded subtitle metadata for enhanced track info
    private var embeddedSubtitleInfo: Map<Int, com.duckflix.lite.data.remote.dto.SubtitleDto> = emptyMap()

    /**
     * Store embedded subtitle info from server for use when selecting tracks
     */
    private fun storeEmbeddedSubtitleInfo(subtitles: List<com.duckflix.lite.data.remote.dto.SubtitleDto>) {
        embeddedSubtitleInfo = subtitles.associateBy { it.streamIndex!! }
        println("[SUBTITLES] Stored ${embeddedSubtitleInfo.size} embedded subtitle track mappings")

        // Update track list to include enhanced metadata
        updateAvailableTracks()
    }

    /**
     * Select an embedded subtitle track by stream index
     */
    fun selectEmbeddedSubtitleByIndex(streamIndex: Int) {
        _player?.let { player ->
            try {
                println("[SUBTITLES] Selecting embedded subtitle at stream index: $streamIndex")

                // Enable text track type
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()

                // Find and select the subtitle track at the given stream index
                var trackFound = false
                player.currentTracks.groups.forEachIndexed { groupIndex, group ->
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        if (format.sampleMimeType?.startsWith("text/") == true ||
                            format.sampleMimeType?.startsWith("application/") == true) {
                            // Check if this matches the stream index
                            // Stream index in video files typically maps to track order
                            if (groupIndex == streamIndex || i == streamIndex) {
                                println("[SUBTITLES] Found embedded subtitle track at group $groupIndex, index $i")
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(
                                        TrackSelectionOverride(group.mediaTrackGroup, listOf(i))
                                    )
                                    .build()
                                trackFound = true
                                updateAvailableTracks()
                                return
                            }
                        }
                    }
                }

                if (!trackFound) {
                    println("[SUBTITLES] Could not find embedded subtitle at stream index $streamIndex")
                }
            } catch (e: Exception) {
                println("[ERROR] Failed to select embedded subtitle: ${e.message}")
            }
        }
    }

    /**
     * Add external subtitle tracks to ExoPlayer
     * Only processes subtitles with valid URLs (filters out embedded subtitles)
     */
    private fun addExternalSubtitles(subtitles: List<com.duckflix.lite.data.remote.dto.SubtitleDto>) {
        _player?.let { player ->
            try {
                val currentMediaItem = player.currentMediaItem ?: return

                // Filter to only subtitles with URLs (external/OpenSubtitles)
                val externalSubs = subtitles.filter { it.url != null }
                if (externalSubs.isEmpty()) {
                    println("[SUBTITLES] No external subtitles with URLs to add")
                    return
                }

                val subtitleConfigs = externalSubs.mapNotNull { subtitle ->
                    val url = subtitle.url ?: return@mapNotNull null
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                        .setMimeType("text/vtt") // Assuming VTT format
                        .setLanguage(subtitle.languageCode ?: subtitle.language)
                        .setLabel(subtitle.label ?: subtitle.language)
                        .setId(subtitle.id?.toString() ?: "ext_${subtitle.language}")
                        .build()
                }

                if (subtitleConfigs.isEmpty()) {
                    println("[SUBTITLES] No valid subtitle configurations to add")
                    return
                }

                // Create new media item with subtitles
                val newMediaItem = currentMediaItem.buildUpon()
                    .setSubtitleConfigurations(subtitleConfigs)
                    .build()

                // Save current position
                val currentPosition = player.currentPosition
                val wasPlaying = player.isPlaying

                // Set flag so error handler ignores errors during subtitle loading
                // Flag is cleared in onPlaybackStateChanged when STATE_READY or in onPlayerError
                isAddingSubtitles = true

                // Replace media item with subtitle-enabled version
                player.setMediaItem(newMediaItem)
                player.prepare()
                player.seekTo(currentPosition)
                if (wasPlaying) {
                    player.play()
                }

                println("[SUBTITLES] Added ${subtitleConfigs.size} external subtitle tracks")
            } catch (e: Exception) {
                isAddingSubtitles = false // Clear on synchronous exceptions
                println("[ERROR] Failed to add external subtitles: ${e.message}")
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
