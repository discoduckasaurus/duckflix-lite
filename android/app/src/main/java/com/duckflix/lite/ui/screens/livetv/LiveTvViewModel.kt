package com.duckflix.lite.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.ActivePlayerRegistry
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.duckflix.lite.data.dvr.DvrStorageManager
import com.duckflix.lite.data.local.dao.RecordingDao
import com.duckflix.lite.data.local.entity.RecordingEntity
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.LiveTvChannel
import com.duckflix.lite.data.remote.dto.LiveTvProgram
import com.duckflix.lite.service.DvrSchedulerWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveTvTrackInfo(
    val id: String,
    val label: String,
    val language: String?,
    val isSelected: Boolean
)

data class LiveTvUiState(
    val channels: List<LiveTvChannel> = emptyList(),
    val selectedChannel: LiveTvChannel? = null,
    val isFullscreen: Boolean = false,
    val isLoading: Boolean = true,
    val isPlaying: Boolean = true,
    val error: String? = null,
    val showFavoritesOnly: Boolean = false,
    val epgStartTime: Long = 0,    // EPG window start (Unix timestamp seconds)
    val epgEndTime: Long = 0,       // EPG window end (Unix timestamp seconds)
    val currentTime: Long = System.currentTimeMillis() / 1000,  // Current time for EPG positioning
    val audioTracks: List<LiveTvTrackInfo> = emptyList(),
    val subtitleTracks: List<LiveTvTrackInfo> = emptyList(),
    val showAudioPanel: Boolean = false,
    val showSubtitlePanel: Boolean = false,
    val focusTrigger: Int = 0,  // Increments to trigger focus restoration after fullscreen exit
    val isRecovering: Boolean = false,  // True during auto-recovery attempts
    val retryCount: Int = 0             // Track retry attempts for UI feedback
)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DuckFlixApi,
    private val recordingDao: RecordingDao,
    private val storageManager: DvrStorageManager
) : ViewModel() {

    companion object {
        private const val MAX_RESTART_ATTEMPTS = 3
        private const val RESTART_WINDOW_MS = 60_000L  // 60s window for consecutive restart tracking
        private const val STALL_TIMEOUT_MS = 10000L  // 10s - gives server time to switch backup streams
    }

    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    private var _player: ExoPlayer? = null
    val player: ExoPlayer?
        get() = _player

    private var epgRefreshJob: Job? = null
    private var timeUpdateJob: Job? = null
    private var stallDetectionJob: Job? = null
    private val restartTimestamps = mutableListOf<Long>()

    // Base URL for constructing stream URLs
    private val baseUrl = "https://lite.duckflix.tv/api"

    private val playerHandle = object : ActivePlayerRegistry.PlayerHandle {
        override fun onAppBackground() {
            println("[LiveTV] App backgrounded — stopping player")
            stallDetectionJob?.cancel()
            _player?.stop()
        }

        override fun onAppForeground() {
            val channelId = _uiState.value.selectedChannel?.id ?: return
            println("[LiveTV] App foregrounded — resuming stream")
            val streamUrl = "$baseUrl/livetv/stream/$channelId"
            playStream(streamUrl)
        }
    }

    // Get auth token for HLS requests
    private val authToken: String?
        get() {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    "auth_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                encryptedPrefs.getString("auth_token", null)
            } catch (e: Exception) {
                println("[LiveTV] Failed to get auth token: ${e.message}")
                null
            }
        }

    init {
        initializePlayer()
        loadChannels()
        startTimeUpdates()
        startEpgRefresh()
        ActivePlayerRegistry.register(playerHandle)
    }

    private fun initializePlayer() {
        _player = createPlayerInstance()
    }

    /**
     * Create a fresh ExoPlayer instance with HLS support and event listener.
     * Each instance has its own HLS playlist tracker and sequence state.
     */
    private fun createPlayerInstance(): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("ExoPlayer/DuckFlix-LiveTV")
            .setDefaultRequestProperties(
                authToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            )

        val mediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
            .setAllowChunklessPreparation(true)

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateName = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN"
                        }
                        println("[LiveTV] Player state: $stateName")

                        when (playbackState) {
                            Player.STATE_READY -> {
                                // Stream recovered — segments are loading, reset restart tracking
                                restartTimestamps.clear()
                                stallDetectionJob?.cancel()
                                _uiState.value = _uiState.value.copy(
                                    isRecovering = false,
                                    retryCount = 0,
                                    error = null
                                )
                            }
                            Player.STATE_BUFFERING -> {
                                startStallDetection()
                            }
                            Player.STATE_IDLE, Player.STATE_ENDED -> {
                                stallDetectionJob?.cancel()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateAvailableTracks(tracks)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        println("[LiveTV] Player error: ${error.errorCodeName} - ${error.message}")
                        handleStreamError()
                    }
                })
                playWhenReady = true
            }
    }

    /**
     * Release the current player and create a fresh instance.
     * Clears all internal HLS state (playlist tracker, chunk source, media-sequence tracking)
     * so the next manifest is parsed from scratch — necessary when the server switches
     * upstream sources (CDN <-> TVPass) and MEDIA-SEQUENCE jumps discontinuously.
     */
    private fun recreatePlayer() {
        println("[LiveTV] Recreating player instance for clean HLS state")
        val oldPlayer = _player
        _player = createPlayerInstance()
        oldPlayer?.release()
    }

    private fun handleStreamError() {
        stallDetectionJob?.cancel()
        val channelId = _uiState.value.selectedChannel?.id ?: return

        val now = System.currentTimeMillis()
        // Prune restart timestamps outside the 60s window
        restartTimestamps.removeAll { now - it > RESTART_WINDOW_MS }

        if (restartTimestamps.size < MAX_RESTART_ATTEMPTS) {
            restartTimestamps.add(now)
            val attempt = restartTimestamps.size
            _uiState.value = _uiState.value.copy(
                isRecovering = true,
                retryCount = attempt,
                error = null
            )
            viewModelScope.launch {
                // Lightweight restart: stop and re-prepare on the same proxy URL.
                // Don't report error to server — it already detects failures via segment activity.
                _player?.stop()
                println("[LiveTV] Player restart after error (attempt $attempt/$MAX_RESTART_ATTEMPTS)")
                delay(1500L)

                val streamUrl = "$baseUrl/livetv/stream/$channelId"
                playStream(streamUrl)
            }
        } else {
            // All restart attempts exhausted — now report to server and show error UI
            viewModelScope.launch {
                try {
                    api.reportLiveTvStreamError(channelId)
                    println("[LiveTV] All $MAX_RESTART_ATTEMPTS restarts failed, reported error to server for channel $channelId")
                } catch (e: Exception) {
                    println("[LiveTV] Failed to report stream error: ${e.message}")
                }
            }
            _uiState.value = _uiState.value.copy(
                error = "Stream unavailable. Tap retry to reconnect.",
                isRecovering = false
            )
        }
    }

    private fun startStallDetection() {
        stallDetectionJob?.cancel()
        stallDetectionJob = viewModelScope.launch {
            delay(STALL_TIMEOUT_MS)
            // Still buffering after timeout = stall
            if (_player?.playbackState == Player.STATE_BUFFERING) {
                println("[LiveTV] Stream stalled, triggering recovery")
                handleStreamError()
            }
        }
    }

    fun refreshStream() {
        val channel = _uiState.value.selectedChannel ?: return
        _uiState.value = _uiState.value.copy(error = null, isRecovering = true)

        // Reset restart tracking on manual refresh
        restartTimestamps.clear()

        viewModelScope.launch {
            // Report error to trigger server-side failover before fetching new manifest
            try {
                api.reportLiveTvStreamError(channel.id)
                println("[LiveTV] Reported stream error for manual retry on channel ${channel.id}")
            } catch (e: Exception) {
                println("[LiveTV] Failed to report stream error: ${e.message}")
            }

            // Full player recreation for manual retry (more aggressive reset)
            recreatePlayer()
            val streamUrl = "$baseUrl/livetv/stream/${channel.id}"
            playStream(streamUrl)
        }
    }

    private fun loadChannels() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                val response = api.getLiveTvChannels()

                // Debug: Log raw response
                println("[LiveTV] API Response: ${response.channels.size} channels")
                println("[LiveTV] API epgStart: ${response.epgStart}, epgEnd: ${response.epgEnd}")
                response.channels.take(3).forEach { ch ->
                    println("[LiveTV] Channel: id=${ch.id}, name=${ch.name}, displayName=${ch.displayName}, logo=${ch.logo}, number=${ch.channelNumber}")
                    println("[LiveTV]   currentProgram: ${ch.currentProgram?.title}")
                    println("[LiveTV]   upcomingPrograms: ${ch.upcomingPrograms.size}")
                }

                // Calculate EPG window (default: current hour - 1 hour to + 3 hours)
                val now = System.currentTimeMillis() / 1000
                val epgStart = response.epgStart ?: (now - 3600)
                val epgEnd = response.epgEnd ?: (now + 10800)

                println("[LiveTV] Computed EPG window: start=$epgStart, end=$epgEnd, now=$now")
                println("[LiveTV] EPG duration: ${(epgEnd - epgStart) / 60} minutes")

                // Filter out deactivated channels and sort by sort_order
                val activeChannels = response.channels
                    .filter { it.isActive }
                    .sortedBy { it.sortOrder }
                println("[LiveTV] Filtered to ${activeChannels.size} active channels (${response.channels.size - activeChannels.size} deactivated)")

                _uiState.value = _uiState.value.copy(
                    channels = activeChannels,
                    isLoading = false,
                    epgStartTime = epgStart,
                    epgEndTime = epgEnd,
                    currentTime = now
                )

                // Auto-select first channel if none selected
                if (_uiState.value.selectedChannel == null && activeChannels.isNotEmpty()) {
                    selectChannel(activeChannels.first())
                }

                println("[LiveTV] State updated: ${_uiState.value.channels.size} channels, showFavoritesOnly=${_uiState.value.showFavoritesOnly}")
                println("[LiveTV] Filtered channels: ${getFilteredChannels().size}")
            } catch (e: Exception) {
                println("[LiveTV] Failed to load channels: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load channels: ${e.message}"
                )
            }
        }
    }

    fun selectChannel(channel: LiveTvChannel) {
        if (_uiState.value.selectedChannel?.id == channel.id) return

        println("[LiveTV] Selecting channel: ${channel.effectiveDisplayName}")

        // Reset recovery state when switching channels
        restartTimestamps.clear()
        stallDetectionJob?.cancel()

        _uiState.value = _uiState.value.copy(
            selectedChannel = channel,
            error = null,
            isRecovering = false,
            retryCount = 0,
            showAudioPanel = false,
            showSubtitlePanel = false
        )

        // Construct stream URL and start playback
        val streamUrl = "$baseUrl/livetv/stream/${channel.id}"
        playStream(streamUrl)
    }

    private fun playStream(streamUrl: String) {
        println("[LiveTV] Playing stream: $streamUrl")

        _player?.apply {
            // Stop current playback
            stop()

            // Update HTTP headers with current auth token
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("ExoPlayer/DuckFlix-LiveTV")
                .setDefaultRequestProperties(
                    authToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
                )

            val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(streamUrl))

            setMediaSource(mediaSource)
            prepare()
            play()
        }
    }

    fun goFullscreen() {
        _uiState.value = _uiState.value.copy(
            isFullscreen = true,
            showAudioPanel = false,
            showSubtitlePanel = false
        )
    }

    fun exitFullscreen() {
        _uiState.value = _uiState.value.copy(
            isFullscreen = false,
            showAudioPanel = false,
            showSubtitlePanel = false,
            focusTrigger = _uiState.value.focusTrigger + 1  // Trigger focus restoration
        )
    }

    fun channelUp() {
        val channels = getFilteredChannels()
        if (channels.isEmpty()) return
        val currentIndex = channels.indexOfFirst { it.id == _uiState.value.selectedChannel?.id }
        // Loop: if at first channel, go to last; otherwise go to previous
        val newIndex = if (currentIndex <= 0) channels.size - 1 else currentIndex - 1
        selectChannel(channels[newIndex])
    }

    fun channelDown() {
        val channels = getFilteredChannels()
        if (channels.isEmpty()) return
        val currentIndex = channels.indexOfFirst { it.id == _uiState.value.selectedChannel?.id }
        // Loop: if at last channel, go to first; otherwise go to next
        val newIndex = if (currentIndex >= channels.size - 1 || currentIndex < 0) 0 else currentIndex + 1
        selectChannel(channels[newIndex])
    }

    fun toggleFavorites() {
        _uiState.value = _uiState.value.copy(
            showFavoritesOnly = !_uiState.value.showFavoritesOnly
        )
    }

    fun getFilteredChannels(): List<LiveTvChannel> {
        return if (_uiState.value.showFavoritesOnly) {
            _uiState.value.channels.filter { it.isFavorite }
        } else {
            _uiState.value.channels
        }
    }

    fun refreshChannels() {
        loadChannels()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
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

    fun toggleAudioPanel() {
        _uiState.value = _uiState.value.copy(
            showAudioPanel = !_uiState.value.showAudioPanel,
            showSubtitlePanel = false
        )
    }

    fun toggleSubtitlePanel() {
        _uiState.value = _uiState.value.copy(
            showSubtitlePanel = !_uiState.value.showSubtitlePanel,
            showAudioPanel = false
        )
    }

    fun dismissPanels() {
        _uiState.value = _uiState.value.copy(
            showAudioPanel = false,
            showSubtitlePanel = false
        )
    }

    private fun updateAvailableTracks(tracks: Tracks) {
        val audioTracks = mutableListOf<LiveTvTrackInfo>()
        val subtitleTracks = mutableListOf<LiveTvTrackInfo>()

        println("[LiveTV] updateAvailableTracks: ${tracks.groups.size} groups")
        for (group in tracks.groups) {
            val trackType = group.type
            val typeName = when (trackType) {
                C.TRACK_TYPE_AUDIO -> "AUDIO"
                C.TRACK_TYPE_TEXT -> "TEXT"
                C.TRACK_TYPE_VIDEO -> "VIDEO"
                else -> "OTHER($trackType)"
            }
            println("[LiveTV] Group type=$typeName, length=${group.length}, hashCode=${group.hashCode()}")

            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)

                when (trackType) {
                    C.TRACK_TYPE_AUDIO -> {
                        val label = format.label ?: format.language?.let { java.util.Locale(it).displayLanguage } ?: "Track ${audioTracks.size + 1}"
                        val trackInfo = LiveTvTrackInfo(
                            id = "${group.hashCode()}_$i",
                            label = label,
                            language = format.language,
                            isSelected = isSelected
                        )
                        audioTracks.add(trackInfo)
                        println("[LiveTV]   Audio track: id=${trackInfo.id}, label=$label, selected=$isSelected")
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val label = format.label ?: format.language?.let { java.util.Locale(it).displayLanguage } ?: "Subtitle ${subtitleTracks.size + 1}"
                        val trackInfo = LiveTvTrackInfo(
                            id = "${group.hashCode()}_$i",
                            label = label,
                            language = format.language,
                            isSelected = isSelected
                        )
                        subtitleTracks.add(trackInfo)
                        println("[LiveTV]   Subtitle track: id=${trackInfo.id}, label=$label, selected=$isSelected")
                    }
                }
            }
        }

        println("[LiveTV] Total: ${audioTracks.size} audio tracks, ${subtitleTracks.size} subtitle tracks")
        _uiState.value = _uiState.value.copy(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks
        )
    }

    fun selectAudioTrack(trackId: String) {
        _player?.let { player ->
            val tracks = player.currentTracks
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until group.length) {
                    if ("${group.hashCode()}_$i" == trackId) {
                        val override = TrackSelectionOverride(group.mediaTrackGroup, i)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .build()
                        return@let
                    }
                }
            }
        }
    }

    fun selectSubtitleTrack(trackId: String) {
        println("[LiveTV] selectSubtitleTrack called with trackId: $trackId")
        _player?.let { player ->
            if (trackId == "off") {
                // Disable all text tracks
                println("[LiveTV] Disabling subtitles")
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                return@let
            }

            // Enable text tracks first
            println("[LiveTV] Enabling text tracks")
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()

            val tracks = player.currentTracks
            println("[LiveTV] Looking for subtitle track in ${tracks.groups.size} groups")
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                println("[LiveTV] Found TEXT group with ${group.length} tracks, hashCode=${group.hashCode()}")
                for (i in 0 until group.length) {
                    val currentId = "${group.hashCode()}_$i"
                    println("[LiveTV] Track $i: id=$currentId, looking for $trackId, match=${currentId == trackId}")
                    if (currentId == trackId) {
                        println("[LiveTV] Found matching subtitle track, applying override")
                        val override = TrackSelectionOverride(group.mediaTrackGroup, i)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .build()
                        println("[LiveTV] Subtitle track override applied")
                        return@let
                    }
                }
            }
            println("[LiveTV] WARNING: No matching subtitle track found for id: $trackId")
        }
    }

    private fun startTimeUpdates() {
        timeUpdateJob?.cancel()
        timeUpdateJob = viewModelScope.launch {
            while (true) {
                delay(60000) // Update every minute
                _uiState.value = _uiState.value.copy(
                    currentTime = System.currentTimeMillis() / 1000
                )
            }
        }
    }

    private fun startEpgRefresh() {
        epgRefreshJob?.cancel()
        epgRefreshJob = viewModelScope.launch {
            while (true) {
                delay(300000) // Refresh every 5 minutes
                println("[LiveTV] Auto-refreshing EPG data")
                loadChannels()
            }
        }
    }

    /**
     * Record the currently-airing program on a channel (starts immediately).
     */
    fun recordNow(channel: LiveTvChannel, program: LiveTvProgram) {
        viewModelScope.launch {
            val filePath = storageManager.generateFilePath(channel.effectiveDisplayName, program.title)
            val recording = RecordingEntity(
                channelId = channel.id,
                channelName = channel.effectiveDisplayName,
                programTitle = program.title,
                programDescription = program.description,
                scheduledStart = System.currentTimeMillis(),
                scheduledEnd = program.stop * 1000, // EPG times are in seconds
                filePath = filePath,
                storageType = storageManager.getStorageType()
            )
            val id = recordingDao.insertRecording(recording).toInt()

            val workRequest = OneTimeWorkRequestBuilder<DvrSchedulerWorker>()
                .setInputData(workDataOf(DvrSchedulerWorker.KEY_RECORDING_ID to id))
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
            println("[LiveTV] Started recording: ${program.title} on ${channel.effectiveDisplayName}")
        }
    }

    /**
     * Schedule a future program for recording.
     */
    fun scheduleRecording(channel: LiveTvChannel, program: LiveTvProgram) {
        viewModelScope.launch {
            val filePath = storageManager.generateFilePath(channel.effectiveDisplayName, program.title)
            val recording = RecordingEntity(
                channelId = channel.id,
                channelName = channel.effectiveDisplayName,
                programTitle = program.title,
                programDescription = program.description,
                scheduledStart = program.start * 1000, // EPG times are in seconds
                scheduledEnd = program.stop * 1000,
                filePath = filePath,
                storageType = storageManager.getStorageType()
            )
            recordingDao.insertRecording(recording)

            // Ensure periodic scheduler is running
            val periodicWork = androidx.work.PeriodicWorkRequestBuilder<DvrSchedulerWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DvrSchedulerWorker.WORK_NAME_PERIODIC,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
            println("[LiveTV] Scheduled recording: ${program.title} on ${channel.effectiveDisplayName}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        ActivePlayerRegistry.unregister(playerHandle)
        epgRefreshJob?.cancel()
        timeUpdateJob?.cancel()
        stallDetectionJob?.cancel()
        _player?.release()
        _player = null
    }
}
