package com.duckflix.lite.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.LiveTvChannel
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
    val focusTrigger: Int = 0  // Increments to trigger focus restoration after fullscreen exit
)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    private var _player: ExoPlayer? = null
    val player: ExoPlayer?
        get() = _player

    private var epgRefreshJob: Job? = null
    private var timeUpdateJob: Job? = null

    // Base URL for constructing stream URLs
    private val baseUrl = "https://lite.duckflix.tv/api"

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
    }

    private fun initializePlayer() {
        // Create HTTP data source factory with auth header
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("ExoPlayer/DuckFlix-LiveTV")
            .setDefaultRequestProperties(
                authToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            )

        // Use HLS media source factory for live streams
        val mediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)
            .setAllowChunklessPreparation(true)

        // Audio codec support: Enable FFmpeg extension for various audio codecs
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        _player = ExoPlayer.Builder(context, renderersFactory)
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
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateAvailableTracks(tracks)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        println("[LiveTV] Player error: ${error.errorCodeName} - ${error.message}")
                        _uiState.value = _uiState.value.copy(
                            error = "Stream error: ${error.message}"
                        )
                    }
                })
                playWhenReady = true
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

                _uiState.value = _uiState.value.copy(
                    channels = response.channels,
                    isLoading = false,
                    epgStartTime = epgStart,
                    epgEndTime = epgEnd,
                    currentTime = now
                )

                // Auto-select first channel if none selected
                if (_uiState.value.selectedChannel == null && response.channels.isNotEmpty()) {
                    selectChannel(response.channels.first())
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
        _uiState.value = _uiState.value.copy(
            selectedChannel = channel,
            error = null,
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
        val currentIndex = channels.indexOfFirst { it.id == _uiState.value.selectedChannel?.id }
        if (currentIndex > 0) {
            selectChannel(channels[currentIndex - 1])
        }
    }

    fun channelDown() {
        val channels = getFilteredChannels()
        val currentIndex = channels.indexOfFirst { it.id == _uiState.value.selectedChannel?.id }
        if (currentIndex < channels.size - 1) {
            selectChannel(channels[currentIndex + 1])
        }
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

    override fun onCleared() {
        super.onCleared()
        epgRefreshJob?.cancel()
        timeUpdateJob?.cancel()
        _player?.release()
        _player = null
    }
}
