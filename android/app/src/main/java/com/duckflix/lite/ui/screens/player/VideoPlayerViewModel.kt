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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.duckflix.lite.data.bandwidth.BandwidthTester
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

/** Decode URL-encoded titles from server (server sometimes sends + instead of spaces) */
private fun decodeTitle(title: String?): String? {
    return title?.replace("+", " ")
}

data class TrackInfo(
    val id: String,
    val label: String,
    val isSelected: Boolean,
    val language: String? = null
)

/** Normalize subtitle/audio track labels to clean, consistent format */
private fun normalizeTrackLabel(label: String?, language: String?, fallback: String): String {
    val raw = label ?: language ?: return fallback

    // Detect flags from the raw label
    val flags = mutableListOf<String>()
    val lower = raw.lowercase()
    if (lower.contains("sdh")) flags.add("SDH")
    if (lower.contains("forced")) flags.add("Forced")
    if (lower.contains("commentary") || lower.contains("comment")) flags.add("Commentary")
    if (lower.contains("descriptive") || lower.contains("description")) flags.add("AD")
    if (lower.contains("dolby")) flags.add("Dolby")
    if (lower.contains("atmos")) flags.add("Atmos")
    if (lower.contains("cc") && !lower.contains("acc")) flags.add("CC")

    // Resolve language name from code or raw string
    val langName = resolveLanguageName(language) ?: resolveLanguageName(raw) ?: cleanLanguageName(raw)

    return if (flags.isEmpty()) langName
    else "$langName (${flags.joinToString(", ")})"
}

private val languageMap = mapOf(
    "en" to "English", "eng" to "English", "english" to "English",
    "fr" to "French", "fre" to "French", "fra" to "French", "french" to "French",
    "es" to "Spanish", "spa" to "Spanish", "spanish" to "Spanish",
    "de" to "German", "ger" to "German", "deu" to "German", "german" to "German",
    "it" to "Italian", "ita" to "Italian", "italian" to "Italian",
    "pt" to "Portuguese", "por" to "Portuguese", "portuguese" to "Portuguese",
    "ja" to "Japanese", "jpn" to "Japanese", "japanese" to "Japanese",
    "ko" to "Korean", "kor" to "Korean", "korean" to "Korean",
    "zh" to "Chinese", "zho" to "Chinese", "chi" to "Chinese", "chinese" to "Chinese",
    "ru" to "Russian", "rus" to "Russian", "russian" to "Russian",
    "ar" to "Arabic", "ara" to "Arabic", "arabic" to "Arabic",
    "hi" to "Hindi", "hin" to "Hindi", "hindi" to "Hindi",
    "nl" to "Dutch", "nld" to "Dutch", "dut" to "Dutch", "dutch" to "Dutch",
    "pl" to "Polish", "pol" to "Polish", "polish" to "Polish",
    "sv" to "Swedish", "swe" to "Swedish", "swedish" to "Swedish",
    "da" to "Danish", "dan" to "Danish", "danish" to "Danish",
    "no" to "Norwegian", "nor" to "Norwegian", "norwegian" to "Norwegian",
    "fi" to "Finnish", "fin" to "Finnish", "finnish" to "Finnish",
    "tr" to "Turkish", "tur" to "Turkish", "turkish" to "Turkish",
    "th" to "Thai", "tha" to "Thai", "thai" to "Thai",
    "vi" to "Vietnamese", "vie" to "Vietnamese", "vietnamese" to "Vietnamese",
    "he" to "Hebrew", "heb" to "Hebrew", "hebrew" to "Hebrew",
    "uk" to "Ukrainian", "ukr" to "Ukrainian", "ukrainian" to "Ukrainian",
    "cs" to "Czech", "ces" to "Czech", "cze" to "Czech", "czech" to "Czech",
    "ro" to "Romanian", "ron" to "Romanian", "rum" to "Romanian", "romanian" to "Romanian",
    "hu" to "Hungarian", "hun" to "Hungarian", "hungarian" to "Hungarian",
    "el" to "Greek", "ell" to "Greek", "gre" to "Greek", "greek" to "Greek",
    "id" to "Indonesian", "ind" to "Indonesian", "indonesian" to "Indonesian",
    "ms" to "Malay", "msa" to "Malay", "may" to "Malay", "malay" to "Malay",
    "und" to "Unknown"
)

private fun resolveLanguageName(input: String?): String? {
    if (input == null) return null
    // Strip everything after first space/dash/paren to get the core language token
    val token = input.trim().split(Regex("[\\s\\-_(]"))[0].lowercase()
    return languageMap[token]
}

private fun cleanLanguageName(raw: String): String {
    // Remove known flag words and clean up
    var cleaned = raw
        .replace(Regex("(?i)\\b(sdh|forced|dolby|digital|atmos|commentary|descriptive|cc)\\b"), "")
        .replace(Regex("[\\-_/,]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isBlank()) cleaned = raw.trim()
    // Capitalize first letter
    return cleaned.replaceFirstChar { it.uppercase() }
}

/**
 * Deduplicate subtitle tracks: keep one per language.
 * Forced/Commentary/Dolby tracks are kept as separate entries since they're functionally different.
 * Among regular tracks for the same language, prefer plain > SDH > CC.
 */
private fun deduplicateSubtitleTracks(tracks: List<TrackInfo>): List<TrackInfo> {
    if (tracks.size <= 1) return tracks

    // Classify each track
    data class ClassifiedTrack(val track: TrackInfo, val lang: String, val category: String)

    val classified = tracks.map { track ->
        val lower = track.label.lowercase()
        // Extract base language (text before any parenthetical)
        val lang = track.label.replace(Regex("\\s*\\(.*\\)"), "").trim()
        val category = when {
            lower.contains("forced") -> "forced"
            lower.contains("commentary") || lower.contains("comment") -> "commentary"
            lower.contains("dolby") -> "dolby"
            lower.contains("sdh") -> "sdh"
            lower.contains("cc") && !lower.contains("acc") -> "cc"
            else -> "plain"
        }
        ClassifiedTrack(track, lang, category)
    }

    // Group by language
    val result = mutableListOf<TrackInfo>()
    val byLang = classified.groupBy { it.lang }

    for ((_, langTracks) in byLang) {
        // Always keep forced/commentary/dolby as separate entries
        val special = langTracks.filter { it.category in listOf("forced", "commentary", "dolby") }
        result.addAll(special.map { it.track })

        // For regular tracks (plain, sdh, cc), keep the best one
        val regular = langTracks.filter { it.category !in listOf("forced", "commentary", "dolby") }
        if (regular.isNotEmpty()) {
            // Priority: plain > sdh > cc > first available
            val best = regular.firstOrNull { it.category == "plain" }
                ?: regular.firstOrNull { it.category == "sdh" }
                ?: regular.firstOrNull { it.category == "cc" }
                ?: regular.first()
            result.add(best.track)
        }
    }

    println("[SUBTITLES] Dedup: ${tracks.size} tracks -> ${result.size} tracks")
    return result
}

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
    val contentType: String = "movie", // "movie" or "tv"
    val currentSeason: Int? = null,
    val currentEpisode: Int? = null,
    val episodeTitle: String? = null,
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
    val showUpNextOverlay: Boolean = false, // Shows in last 5% of video
    val showSubtitlePanel: Boolean = false,
    val isReportingBadLink: Boolean = false,
    val displayQuality: String = "", // e.g., "2160p" from server
    // Prefetch state for seamless auto-play
    val prefetchJobId: String? = null,
    val prefetchNextEpisode: com.duckflix.lite.data.remote.dto.PrefetchEpisodeInfo? = null,
    val hasPrefetched: Boolean = false,
    // Skip markers state (intro/recap/credits)
    val skipMarkers: com.duckflix.lite.data.remote.dto.SkipMarkers? = null,
    val showSkipIntro: Boolean = false,
    val showSkipRecap: Boolean = false,
    val showSkipCredits: Boolean = false,
    val introDismissed: Boolean = false,
    val recapDismissed: Boolean = false,
    val creditsDismissed: Boolean = false,
    val isSeeking: Boolean = false, // Track if user is actively seeking (hide skip buttons during seek)
    val isForceEnglishLoading: Boolean = false,
    // Subtitle style preferences (from settings)
    val subtitleSize: Int = 1,          // 0=Small, 1=Medium, 2=Large
    val subtitleColor: Int = 0,         // 0=White, 1=Yellow, 2=Green, 3=Cyan
    val subtitleBackground: Int = 0,    // 0=None, 1=Black, 2=Semi-transparent
    val subtitleEdge: Int = 1           // 0=None, 1=Drop shadow, 2=Outline
)

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DuckFlixApi,
    private val watchProgressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao,
    private val playbackErrorDao: PlaybackErrorDao,
    private val autoPlaySettingsDao: com.duckflix.lite.data.local.dao.AutoPlaySettingsDao,
    private val subtitlePreferencesDao: com.duckflix.lite.data.local.dao.SubtitlePreferencesDao,
    private val okHttpClient: okhttp3.OkHttpClient,
    private val stutterDetector: StutterDetector,
    private val bandwidthTester: BandwidthTester,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tmdbId: Int = checkNotNull(savedStateHandle["tmdbId"])
    private val contentTitle: String = (savedStateHandle.get<String>("title") ?: "Video").let { rawTitle ->
        try { java.net.URLDecoder.decode(rawTitle, "UTF-8") } catch (e: Exception) { rawTitle }
    }
    private val contentType: String = savedStateHandle["type"] ?: "movie"
    private val year: String? = savedStateHandle["year"]
    private val season: Int? = savedStateHandle.get<Int>("season")?.takeIf { it != -1 }
    private val episode: Int? = savedStateHandle.get<Int>("episode")?.takeIf { it != -1 }
    private val resumePosition: Long? = savedStateHandle.get<Long>("resumePosition")?.takeIf { it != -1L }
    private val posterUrl: String? = savedStateHandle["posterUrl"]
    private val logoUrl: String? = savedStateHandle["logoUrl"]
    private val originalLanguage: String? = savedStateHandle["originalLanguage"]
    private val isRandomPlayback: Boolean = savedStateHandle.get<Boolean>("isRandom") ?: false
    private val episodeTitle: String? = savedStateHandle.get<String>("episodeTitle")?.let {
        try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
    }
    private val isAutoplay: Boolean = savedStateHandle.get<Boolean>("isAutoplay") ?: false

    init {
        println("[VIEWMODEL-INIT] VideoPlayerViewModel created:")
        println("[VIEWMODEL-INIT]   tmdbId=$tmdbId")
        println("[VIEWMODEL-INIT]   title='$contentTitle'")
        println("[VIEWMODEL-INIT]   type=$contentType")
        println("[VIEWMODEL-INIT]   season=$season, episode=$episode")
        println("[VIEWMODEL-INIT]   isRandom=$isRandomPlayback, isAutoplay=$isAutoplay")
    }
    private var pendingSeekPosition: Long? = null
    private var currentStreamUrl: String? = null
    private var currentJobId: String? = null // Persisted for error reporting after playback starts
    private var currentErrorId: Int? = null
    private var isSelectingTrack = false // Prevent infinite loop during auto-selection
    private var isAddingSubtitles = false // Don't show errors during subtitle loading
    private var pendingAutoSelectSubtitle = false // Auto-select last subtitle after tracks update
    private var autoRetryCount = 0
    private val maxAutoRetries = 1 // Only auto-retry once, then show error with manual retry option
    private var upNextOverlayDismissed = false // Track if user dismissed the Up Next overlay

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var _player: ExoPlayer? = null
    val player: ExoPlayer?
        get() = _player

    private var autoPlayCountdownJob: kotlinx.coroutines.Job? = null
    var onAutoPlayNext: ((season: Int, episode: Int, episodeTitle: String?) -> Unit)? = null
    var onAutoPlayRecommendation: ((tmdbId: Int) -> Unit)? = null

    init {
        // Set initial posterUrl, logoUrl, and episode info from navigation
        _uiState.value = _uiState.value.copy(
            posterUrl = posterUrl,
            logoUrl = logoUrl,
            contentType = contentType,
            currentSeason = season,
            currentEpisode = episode,
            episodeTitle = episodeTitle
        )

        println("[LOGO-DEBUG] Received logoUrl from navigation: $logoUrl")
        println("[LOGO-DEBUG] Received posterUrl from navigation: $posterUrl")

        // Initialize stutter detector with playback settings
        viewModelScope.launch {
            try {
                stutterDetector.initialize()
                stutterDetector.reset() // Reset for new content
            } catch (e: Exception) {
                println("[VideoPlayer] Failed to initialize stutter detector: ${e.message}")
                // Non-critical - playback will continue without adaptive fallback
            }
        }

        loadAutoPlaySettings()
        loadSubtitleStylePreferences()
        initializePlayer()
        loadVideoUrl()
        startPositionUpdates()
        retryLoadingPhrasesIfNeeded()

        // NEVER fetch from TMDB - ONLY use server-provided logoUrl
        // If logoUrl is null, the loading screen will fallback to posterUrl automatically
    }

    /**
     * Retry fetching loading phrases if the initial fetch on app launch failed.
     * This ensures phrases are available for the loading screen.
     */
    private fun retryLoadingPhrasesIfNeeded() {
        if (!com.duckflix.lite.utils.LoadingPhrasesCache.needsRetry()) return

        viewModelScope.launch {
            try {
                println("[VideoPlayer] Retrying loading phrases fetch...")
                val response = api.getLoadingPhrases()
                com.duckflix.lite.utils.LoadingPhrasesCache.setPhrases(response.phrasesA, response.phrasesB)
                println("[VideoPlayer] Loading phrases retry succeeded: ${response.phrasesA.size} A, ${response.phrasesB.size} B")
            } catch (e: Exception) {
                println("[VideoPlayer] Loading phrases retry failed: ${e.message}")
                // Non-critical - will use defaults/cache
            }
        }
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

                    if (duration > 0) {
                        val progressPercent = (currentPos * 100 / duration).toInt()

                        // Trigger prefetch at 75% for TV episodes
                        if (contentType == "tv" &&
                            !_uiState.value.hasPrefetched &&
                            _uiState.value.autoPlayEnabled &&
                            season != null && episode != null) {
                            if (progressPercent >= 75) {
                                triggerPrefetch()
                            }
                        }

                        // Show "Up Next" overlay in the last 5 seconds of the file for TV series with autoplay enabled
                        // BUT only if we don't have skip credits marker (skip credits replaces Up Next)
                        // The overlay shows the 5-second countdown before auto-playing next episode
                        val hasSkipCredits = _uiState.value.skipMarkers?.credits != null
                        val remainingMs = duration - currentPos
                        val isInLast5Seconds = remainingMs <= 5000 && remainingMs > 0
                        if (contentType == "tv" &&
                            _uiState.value.autoPlayEnabled &&
                            !upNextOverlayDismissed &&
                            !_uiState.value.showUpNextOverlay &&
                            _uiState.value.nextEpisodeInfo?.hasNext == true &&
                            !hasSkipCredits &&  // Only show if no skip credits data
                            isInLast5Seconds) {
                            _uiState.value = _uiState.value.copy(showUpNextOverlay = true)
                            // Start the countdown when overlay appears
                            startAutoPlayCountdownFromOverlay()
                        }
                    }

                    // Update skip button visibility based on position
                    updateSkipButtonVisibility(currentPos)
                }
            }
        }
    }

    /**
     * Update skip button visibility based on current playback position.
     * Buttons show during the first half of their segment only.
     * Min segment length: 3 seconds. Tolerance: 1 second for position matching.
     */
    private fun updateSkipButtonVisibility(currentPosMs: Long) {
        val markers = _uiState.value.skipMarkers ?: return
        val state = _uiState.value

        // Don't update during seeking
        if (state.isSeeking) return

        val currentPosSec = currentPosMs / 1000.0
        val tolerance = 1.0 // 1 second tolerance for position matching

        var showIntro = false
        var showRecap = false
        var showCredits = false

        // Check intro marker (show for 75% of segment duration)
        markers.intro?.let { intro ->
            val segmentDuration = intro.end - intro.start
            if (segmentDuration >= 3.0 && !state.introDismissed) {
                val visibleEnd = intro.start + (segmentDuration * 0.75)
                showIntro = currentPosSec >= (intro.start - tolerance) && currentPosSec <= visibleEnd
            }
        }

        // Check recap marker (show for 75% of segment duration)
        markers.recap?.let { recap ->
            val segmentDuration = recap.end - recap.start
            if (segmentDuration >= 3.0 && !state.recapDismissed) {
                val visibleEnd = recap.start + (segmentDuration * 0.75)
                showRecap = currentPosSec >= (recap.start - tolerance) && currentPosSec <= visibleEnd
            }
        }

        // If both intro and recap would show, prioritize recap (comes first chronologically)
        if (showIntro && showRecap) {
            showIntro = false
        }

        // Check credits marker (show for 75% of segment duration)
        markers.credits?.let { credits ->
            val segmentDuration = credits.end - credits.start
            if (segmentDuration >= 3.0 && !state.creditsDismissed) {
                val visibleEnd = credits.start + (segmentDuration * 0.75)
                showCredits = currentPosSec >= (credits.start - tolerance) && currentPosSec <= visibleEnd
            }
        }

        // Update state if changed
        if (showIntro != state.showSkipIntro ||
            showRecap != state.showSkipRecap ||
            showCredits != state.showSkipCredits) {
            _uiState.value = state.copy(
                showSkipIntro = showIntro,
                showSkipRecap = showRecap,
                showSkipCredits = showCredits
            )
        }
    }

    private fun initializePlayer() {
        // Simple configuration like main app - defaults work best for HEVC/x265
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(okHttpClient)
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
            // Disable subtitle auto-selection by ExoPlayer — we handle it via sticky prefs or manual selection
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
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

                    // Auto-select the last subtitle if we just added one via Force English
                    if (pendingAutoSelectSubtitle) {
                        pendingAutoSelectSubtitle = false
                        autoSelectLastSubtitle()
                    } else if (pendingAutoSelectEnglish) {
                        // Retry finding English track from Force English (may take multiple onTracksChanged calls)
                        tryAutoSelectEnglish()
                    } else {
                        // Try to restore saved subtitle preference (sticky subs)
                        autoSelectSavedSubtitlePreference()
                    }

                    // Log video format info including HDR status for debugging
                    tracks.groups.forEach { group ->
                        if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected) {
                            for (i in 0 until group.length) {
                                if (group.isTrackSelected(i)) {
                                    val format = group.getTrackFormat(i)
                                    val colorInfo = format.colorInfo
                                    val hdrType = when (colorInfo?.colorTransfer) {
                                        androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10/HDR10+"
                                        androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
                                        else -> if (colorInfo?.colorSpace == androidx.media3.common.C.COLOR_SPACE_BT2020) "HDR (BT.2020)" else "SDR"
                                    }
                                    println("[VIDEO] Resolution: ${format.width}x${format.height}, " +
                                            "Codec: ${format.codecs ?: format.sampleMimeType}, " +
                                            "HDR: $hdrType, " +
                                            "ColorSpace: ${colorInfo?.colorSpace}, " +
                                            "ColorTransfer: ${colorInfo?.colorTransfer}")
                                }
                            }
                        }
                    }
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

                    // Handle error with potential auto-retry
                    handlePlaybackError(error)
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
                            val label = normalizeTrackLabel(format.label, format.language, "Subtitle ${subtitleTracks.size + 1}")
                            println("[TRACK-CREATE] Subtitle: ID='$subtitleId', Label='$label', RawLabel='${format.label}', Lang='${format.language}', Selected=$isSelected")

                            subtitleTracks.add(
                                TrackInfo(
                                    id = subtitleId,
                                    label = label,
                                    isSelected = isSelected,
                                    language = format.language
                                )
                            )
                        }
                    }
                }
            }

            // Deduplicate subtitle tracks: one per language unless functionally different
            val deduped = deduplicateSubtitleTracks(subtitleTracks)

            // Filter out "Unknown" / unidentified language tracks — they're unusable noise
            val filtered = deduped.filter { track ->
                val lang = track.language?.lowercase()
                val isUnknown = lang == null || lang == "und"
                if (isUnknown) {
                    println("[SUBTITLES] Filtering out unknown track: ${track.label} (lang=${track.language})")
                }
                !isUnknown
            }

            _uiState.value = _uiState.value.copy(
                audioTracks = audioTracks,
                subtitleTracks = filtered
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

    /**
     * Generate user-friendly error message based on ExoPlayer error code.
     * Helps diagnose why playback failed even when the server provided a valid URL.
     */
    private fun getPlaybackErrorMessage(error: androidx.media3.common.PlaybackException): String {
        val prefix = "⚠️ "

        return when {
            // IO/Network errors - stream URL may be valid but connection failed
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "${prefix}Network connection failed. Please check your internet and try again."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "${prefix}Connection timed out. The stream server may be slow - please try again."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                // Extract HTTP status if available
                val cause = error.cause?.message ?: ""
                when {
                    cause.contains("404") -> "${prefix}Stream not found (404). The file may have been removed."
                    cause.contains("403") -> "${prefix}Access denied (403). The stream link may have expired."
                    cause.contains("500") || cause.contains("502") || cause.contains("503") ->
                        "${prefix}Stream server error. Please try again in a moment."
                    else -> "${prefix}Stream unavailable (HTTP error). Please try again."
                }
            }

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "${prefix}Stream file not found. Please try a different source."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                "${prefix}Permission denied. The stream link may have expired."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                "${prefix}Secure connection required. Please report this issue."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                "${prefix}Stream read error. Please try again."

            // Timeout during read (stream stalled)
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT ->
                "${prefix}Stream timed out. The source may be too slow - try another source."

            // Decoder/codec errors
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "${prefix}Video codec not supported on this device. Try reporting for an alternative."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "${prefix}Decoder error. This video format may not be supported."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "${prefix}Decoding failed. The video file may be corrupted."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "${prefix}Video quality too high for this device. Try reporting for a lower quality version."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "${prefix}Video format not supported. Try reporting for an alternative."

            // Audio renderer errors
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
                "${prefix}Audio initialization failed. Please try again."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                "${prefix}Audio playback error. Please try again."

            // Parsing/container errors
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                "${prefix}Video file is corrupted. Try reporting for an alternative."

            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "${prefix}Video container format not supported. Try reporting for an alternative."

            // DRM errors (shouldn't happen with our content but just in case)
            error.errorCodeName.startsWith("ERROR_CODE_DRM") ->
                "${prefix}DRM protected content cannot be played."

            // Unrecognized errors - show the actual error code for debugging
            else -> {
                val codeInfo = if (error.errorCodeName != "ERROR_CODE_UNSPECIFIED") {
                    " (${error.errorCodeName})"
                } else {
                    ""
                }
                "${prefix}Playback failed$codeInfo. Please try again or report for an alternative."
            }
        }
    }

    /**
     * Handle ExoPlayer playback errors with automatic reporting and retry.
     * For recoverable errors (codec issues, HTTP errors), reports to server and tries alternative source.
     */
    private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
        viewModelScope.launch {
            // Log error to database for local tracking
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
            } catch (e: Exception) {
                println("[ERROR] Failed to log playback error: ${e.message}")
            }

            // Check if error is recoverable (worth trying alternative source)
            val isRecoverableError = isRecoverablePlaybackError(error)
            val hasJobId = currentJobId != null
            val canRetry = autoRetryCount < maxAutoRetries

            println("[ERROR-RECOVERY] Recoverable: $isRecoverableError, HasJobId: $hasJobId, CanRetry: $canRetry (attempt ${autoRetryCount + 1}/$maxAutoRetries)")

            if (isRecoverableError && hasJobId && canRetry) {
                autoRetryCount++
                println("[ERROR-RECOVERY] Attempting auto-recovery by requesting alternative source...")

                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    loadingPhase = LoadingPhase.SEARCHING,
                    downloadMessage = "Finding alternative source...",
                    error = null
                )

                try {
                    // Report bad stream to server and request alternative
                    val request = com.duckflix.lite.data.remote.dto.ReportBadRequest(
                        jobId = currentJobId!!,
                        reason = "ExoPlayer error: ${error.errorCodeName}"
                    )
                    val response = api.reportBadStream(request)

                    if (response.success && response.newJobId != null) {
                        println("[ERROR-RECOVERY] Got alternative source job: ${response.newJobId}")
                        currentJobId = response.newJobId

                        // Mark previous error as resolved since we're trying alternative
                        currentErrorId?.let { errorId ->
                            try {
                                playbackErrorDao.markAsResolved(errorId)
                            } catch (e: Exception) {
                                println("[ERROR] Failed to mark error as resolved: ${e.message}")
                            }
                        }

                        // Start polling for new source
                        _uiState.value = _uiState.value.copy(downloadJobId = response.newJobId)
                        pollDownloadProgress(response.newJobId)
                        return@launch
                    } else {
                        println("[ERROR-RECOVERY] Server has no alternative: ${response.message}")
                        // Fall through to show error
                    }
                } catch (e: Exception) {
                    println("[ERROR-RECOVERY] Failed to request alternative: ${e.message}")
                    // Fall through to show error
                }
            }

            // No recovery possible - show error to user
            val userMessage = getPlaybackErrorMessage(error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadingPhase = LoadingPhase.READY,
                error = userMessage
            )
        }
    }

    /**
     * Determine if a playback error is worth attempting recovery (alternative source).
     * Returns true for codec issues, HTTP errors, and format problems.
     * Returns false for network connectivity issues (user should fix network first).
     */
    private fun isRecoverablePlaybackError(error: androidx.media3.common.PlaybackException): Boolean {
        return when (error.errorCode) {
            // Decoder/codec errors - different source might have compatible codec
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

            // HTTP errors - source might be dead/expired, try alternative
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> true

            // Parsing errors - corrupted file, try alternative
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> true

            // Audio errors - try alternative with different audio codec
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> true

            // Stream timeout - could be slow server, try alternative
            androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true

            // Network connectivity - user needs to fix network, don't retry
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> false

            // Other/unknown - don't retry
            else -> false
        }
    }

    private fun loadVideoUrl() {
        // Reset retry count for new content
        autoRetryCount = 0
        currentJobId = null

        println("[STREAM-DEBUG] loadVideoUrl() called for tmdbId=$tmdbId, title='$contentTitle', season=$season, episode=$episode")

        viewModelScope.launch {
            try {
                // Skip session check for autoplay - we just finished playing, session is valid
                // Server hangs on session check during autoplay transitions
                if (isAutoplay) {
                    println("[STREAM-DEBUG] Skipping checkVodSession() for autoplay continuation")
                } else {
                    // Check VOD session with timeout and retry
                    println("[STREAM-DEBUG] Calling checkVodSession()...")
                    var sessionChecked = false
                    for (attempt in 1..2) {
                        try {
                            kotlinx.coroutines.withTimeout(10000L) {
                                api.checkVodSession()
                            }
                            sessionChecked = true
                            break
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            println("[STREAM-DEBUG] checkVodSession() attempt $attempt timed out")
                            if (attempt == 1) {
                                println("[STREAM-DEBUG] Retrying after 1s delay...")
                                delay(1000)
                            }
                        }
                    }
                    if (!sessionChecked) {
                        throw Exception("Session check timed out after retries")
                    }
                    println("[STREAM-DEBUG] checkVodSession() succeeded")
                }

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
                println("[STREAM-DEBUG] Calling startStreamUrl() with request: $streamRequest")

                // Start stream URL retrieval with timeout for autoplay
                // Server can hang during autoplay transitions
                val startResponse = if (isAutoplay) {
                    var response: com.duckflix.lite.data.remote.dto.StreamUrlStartResponse? = null
                    for (attempt in 1..3) {
                        try {
                            response = kotlinx.coroutines.withTimeout(15000L) {
                                api.startStreamUrl(streamRequest)
                            }
                            break
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            println("[STREAM-DEBUG] startStreamUrl() attempt $attempt timed out")
                            if (attempt < 3) {
                                println("[STREAM-DEBUG] Retrying after 2s delay...")
                                delay(2000)
                            }
                        }
                    }
                    response ?: throw Exception("Stream request timed out. Please try again.")
                } else {
                    api.startStreamUrl(streamRequest)
                }
                println("[STREAM-DEBUG] startStreamUrl() responded: immediate=${startResponse.immediate}, jobId=${startResponse.jobId}")

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
                    currentJobId = jobId // Store for error reporting
                    println("[INFO] Starting RD download, jobId: $jobId")

                    _uiState.value = _uiState.value.copy(
                        loadingPhase = LoadingPhase.SEARCHING,
                        downloadJobId = jobId
                    )

                    pollDownloadProgress(jobId)
                }
            } catch (e: Exception) {
                println("[ERROR] VideoPlayerViewModel: Failed to load video: ${e.message}")
                val errorMsg = e.message?.let { msg ->
                    when {
                        msg.contains("401") || msg.contains("Unauthorized") ->
                            "Session expired. Please log in again."
                        msg.contains("timeout", ignoreCase = true) ->
                            "Server timeout. Please try again."
                        msg.contains("Unable to resolve host") || msg.contains("No address associated") ->
                            "No internet connection. Please check your network."
                        msg.contains("Unable to find") || msg.contains("No sources") ->
                            msg // Use server's message about no sources
                        else -> "Unfortunately this title isn't available at the moment. Please try again later."
                    }
                } ?: "Unfortunately this title isn't available at the moment. Please try again later."

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingPhase = LoadingPhase.READY,
                    error = "⚠️ $errorMsg"
                )
            }
        }
    }

    private suspend fun pollDownloadProgress(jobId: String) {
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 3
        val maxPollDuration = 5 * 60 * 1000L // 5 minute timeout for the entire poll loop
        val pollStartTime = System.currentTimeMillis()

        try {
            while (true) {
                delay(2000) // Poll every 2 seconds

                // Safety timeout — don't poll forever
                if (System.currentTimeMillis() - pollStartTime > maxPollDuration) {
                    throw Exception("Search timed out. Please try again later.")
                }

                try {
                    val progress = api.getStreamProgress(jobId)
                    consecutiveErrors = 0 // Reset error counter on successful poll

                    println("[POLL] Status: ${progress.status}, Progress: ${progress.progress}%, Message: '${progress.message}', Error: '${progress.error}'")

                    // Check for error/failed status
                    if (progress.status == "error" || progress.status == "failed" || progress.error != null) {
                        val errorMsg = progress.error ?: progress.message ?: ""

                        // "trying next source" = server is still cycling through sources, keep polling
                        if (errorMsg.contains("trying next source", ignoreCase = true)) {
                            println("[POLL] Source timed out, server trying next — continuing poll")
                            _uiState.value = _uiState.value.copy(
                                loadingPhase = LoadingPhase.SEARCHING,
                                downloadMessage = "Trying another source..."
                            )
                        } else {
                            // Definitive failure — all sources exhausted
                            val displayMsg = errorMsg.takeIf { it.isNotBlank() }
                                ?: "Unable to find a working stream. Please try again later."
                            println("[POLL] Job failed definitively: $displayMsg")
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                loadingPhase = LoadingPhase.READY,
                                error = "⚠️ $displayMsg"
                            )
                            return
                        }
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

                    // Check if server suggests a bandwidth retest (stale measurement or fallback used)
                    if (progress.suggestBandwidthRetest == true) {
                        println("[Bandwidth] Server suggests retest - triggering background test")
                        bandwidthTester.performTestAndReportInBackground("bandwidth-retest")
                    }

                    // Store skip markers when they arrive (may come with completed status or slightly after)
                    if (progress.skipMarkers != null && _uiState.value.skipMarkers == null) {
                        println("[SkipMarkers] Received markers: intro=${progress.skipMarkers.intro != null}, recap=${progress.skipMarkers.recap != null}, credits=${progress.skipMarkers.credits != null}")
                        _uiState.value = _uiState.value.copy(skipMarkers = progress.skipMarkers)
                    }

                    when (progress.status) {
                        "completed" -> {
                            // Validate stream URL before attempting playback
                            if (!isValidStreamUrl(progress.streamUrl)) {
                                throw Exception("Unable to find a working stream. Please try again later.")
                            }

                            println("[INFO] Download complete, preparing playback")

                            // Extract next episode info from progress response (primary source)
                            // This is the server's authoritative data for autoplay
                            if (contentType == "tv" && progress.hasNextEpisode != null) {
                                val nextEpisodeInfo = if (progress.hasNextEpisode && progress.nextEpisode != null) {
                                    com.duckflix.lite.data.remote.dto.NextEpisodeResponse(
                                        hasNext = true,
                                        season = progress.nextEpisode.season,
                                        episode = progress.nextEpisode.episode,
                                        title = decodeTitle(progress.nextEpisode.title),
                                        inCurrentPack = true // Server resolved it, so it's available
                                    )
                                } else {
                                    // hasNextEpisode = false means series finale
                                    com.duckflix.lite.data.remote.dto.NextEpisodeResponse(
                                        hasNext = false,
                                        season = null,
                                        episode = null,
                                        title = null,
                                        inCurrentPack = false
                                    )
                                }
                                _uiState.value = _uiState.value.copy(nextEpisodeInfo = nextEpisodeInfo)
                                println("[AutoPlay] Next episode from progress: hasNext=${nextEpisodeInfo.hasNext}, S${nextEpisodeInfo.season}E${nextEpisodeInfo.episode}")
                            }

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
                            // Should not reach here — handled above before the when block
                            return
                        }
                        "searching", "downloading" -> {
                            // Continue polling - these are expected active states
                            println("[POLL] Still ${progress.status}, continuing poll loop")
                        }
                        else -> {
                            // Unknown status — treat as error rather than polling forever
                            println("[POLL] Unexpected status '${progress.status}', treating as error")
                            val errorMsg = progress.message.takeIf {
                                it.isNotBlank() && !it.equals("searching", ignoreCase = true)
                            } ?: "Unable to find a working stream (status: ${progress.status})"
                            throw Exception(errorMsg)
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
        println("[DEBUG] startPlayback: Setting media item")
        // Clear subtitle state from previous content
        embeddedSubtitleInfo = emptyMap()
        isAddingSubtitles = false
        println("[DEBUG] Stream URL length: ${streamUrl.length}, starts with https: ${streamUrl.startsWith("https")}")
        println("[DEBUG] Stream URL (truncated): ${streamUrl.take(100)}...")
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
            // Reset subtitle track state so previous "disabled" doesn't carry over
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()

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
            saveProgressWithValues(player.currentPosition, player.duration)
        }
    }

    private suspend fun saveProgressWithValues(position: Long, duration: Long) {
        if (duration > 0) {
            // Different completion thresholds: 95% for TV shows, 90% for movies
            val completionThreshold = if (contentType == "tv") 0.95 else 0.90
            val isCompleted = position >= (duration * completionThreshold)
            val progress = WatchProgressEntity(
                tmdbId = tmdbId,
                title = contentTitle,
                type = contentType,
                year = year,
                posterUrl = posterUrl,
                position = position,
                duration = duration,
                lastWatchedAt = System.currentTimeMillis(),
                isCompleted = isCompleted,
                season = season ?: 0,
                episode = episode ?: 0
            )
            watchProgressDao.saveProgress(progress)

            // Enforce 150MB cap (~500K rows at ~300 bytes each)
            try {
                val count = watchProgressDao.getCount()
                if (count > 500_000) {
                    watchProgressDao.deleteOldest(count - 500_000)
                }
            } catch (_: Exception) { }

            // Sync to server for recommendations
            try {
                val syncRequest = com.duckflix.lite.data.remote.dto.WatchProgressSyncRequest(
                    tmdbId = tmdbId,
                    type = contentType,
                    title = contentTitle,
                    posterPath = posterUrl?.substringAfter("w500"), // Extract just the path
                    logoPath = _uiState.value.logoUrl?.substringAfter("w500"), // Extract logo path
                    releaseDate = year,
                    position = position,
                    duration = duration,
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

    /**
     * Called when user starts seeking (dragging the seek bar).
     * Hide skip buttons during seek.
     */
    fun onSeekStart() {
        _uiState.value = _uiState.value.copy(
            isSeeking = true,
            showSkipIntro = false,
            showSkipRecap = false,
            showSkipCredits = false
        )
    }

    /**
     * Called when user finishes seeking.
     * Skip button visibility will be updated on next position update.
     */
    fun onSeekEnd() {
        _uiState.value = _uiState.value.copy(isSeeking = false)
    }

    /**
     * Skip to the end of the intro segment.
     */
    fun skipIntro() {
        val intro = _uiState.value.skipMarkers?.intro ?: return
        val endMs = (intro.end * 1000).toLong()
        println("[SkipMarkers] Skipping intro to ${intro.end}s")
        _player?.seekTo(endMs)
        _uiState.value = _uiState.value.copy(
            showSkipIntro = false,
            introDismissed = true
        )
    }

    /**
     * Skip to the end of the recap segment.
     */
    fun skipRecap() {
        val recap = _uiState.value.skipMarkers?.recap ?: return
        val endMs = (recap.end * 1000).toLong()
        println("[SkipMarkers] Skipping recap to ${recap.end}s")
        _player?.seekTo(endMs)
        _uiState.value = _uiState.value.copy(
            showSkipRecap = false,
            recapDismissed = true
        )
    }

    /**
     * Skip credits - behavior depends on hasPostCredits flag.
     * - If hasPostCredits: seek to credits.end (post-credits scene)
     * - If no post-credits: play next episode IMMEDIATELY (no countdown)
     */
    fun skipCredits() {
        val credits = _uiState.value.skipMarkers?.credits ?: return
        println("[SkipMarkers] Skipping credits, hasPostCredits=${credits.hasPostCredits}")

        _uiState.value = _uiState.value.copy(
            showSkipCredits = false,
            creditsDismissed = true
        )

        if (credits.hasPostCredits == true) {
            // Seek to post-credits scene
            val endMs = (credits.end * 1000).toLong()
            println("[SkipMarkers] Seeking to post-credits scene at ${credits.end}s")
            _player?.seekTo(endMs)
            // Dismiss Up Next overlay temporarily - it will re-trigger when post-credits ends
            _uiState.value = _uiState.value.copy(showUpNextOverlay = false)
        } else {
            // No post-credits, play next episode immediately (skip the countdown)
            println("[SkipMarkers] No post-credits, playing next episode immediately")
            playNextEpisodeImmediately()
        }
    }

    /**
     * Play the next episode immediately without countdown.
     * Used when user explicitly skips credits.
     */
    private fun playNextEpisodeImmediately() {
        // Cancel any existing countdown
        autoPlayCountdownJob?.cancel()
        _uiState.value = _uiState.value.copy(
            autoPlayCountdown = 0,
            showUpNextOverlay = false
        )

        // Trigger background bandwidth test
        bandwidthTester.performTestAndReportInBackground("skip-credits")

        if (contentType != "tv" || !_uiState.value.autoPlayEnabled) return

        // In random mode, fetch a fresh random episode
        if (isRandomPlayback) {
            viewModelScope.launch {
                try {
                    val randomEpisode = api.getRandomEpisode(tmdbId)
                    val decodedTitle = decodeTitle(randomEpisode.title)
                    println("[SkipCredits] Random mode - playing S${randomEpisode.season}E${randomEpisode.episode}")
                    onAutoPlayNext?.invoke(randomEpisode.season, randomEpisode.episode, decodedTitle)
                } catch (e: Exception) {
                    println("[SkipCredits] Failed to fetch random episode: ${e.message}")
                    _uiState.value = _uiState.value.copy(showRandomEpisodeError = true)
                }
            }
            return
        }

        // Try prefetched content first for seamless playback
        val prefetchEpisode = _uiState.value.prefetchNextEpisode
        val prefetchJobId = _uiState.value.prefetchJobId

        if (prefetchJobId != null && prefetchEpisode != null) {
            viewModelScope.launch {
                val isReady = promotePrefetchJob()
                if (isReady) {
                    println("[SkipCredits] Using prefetched S${prefetchEpisode.season}E${prefetchEpisode.episode}")
                    onAutoPlayNext?.invoke(prefetchEpisode.season, prefetchEpisode.episode, prefetchEpisode.title)
                } else {
                    // Prefetch not ready, use nextEpisodeInfo
                    playNextFromEpisodeInfo()
                }
            }
            return
        }

        // No prefetch, use nextEpisodeInfo directly
        playNextFromEpisodeInfo()
    }

    /**
     * Play next episode using nextEpisodeInfo (fallback when no prefetch).
     */
    private fun playNextFromEpisodeInfo() {
        val nextEpisode = _uiState.value.nextEpisodeInfo
        val prefetchEpisode = _uiState.value.prefetchNextEpisode

        val season: Int?
        val episode: Int?
        val episodeTitle: String?

        when {
            nextEpisode != null && nextEpisode.hasNext && nextEpisode.season != null && nextEpisode.episode != null -> {
                season = nextEpisode.season
                episode = nextEpisode.episode
                episodeTitle = nextEpisode.title
                println("[SkipCredits] Using nextEpisodeInfo: S${season}E${episode}")
            }
            prefetchEpisode != null -> {
                season = prefetchEpisode.season
                episode = prefetchEpisode.episode
                episodeTitle = prefetchEpisode.title
                println("[SkipCredits] Using prefetchNextEpisode: S${season}E${episode}")
            }
            else -> {
                println("[SkipCredits] No next episode info available")
                _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = true)
                return
            }
        }

        if (season == null || episode == null) {
            println("[SkipCredits] Invalid next episode data")
            _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = true)
            return
        }

        onAutoPlayNext?.invoke(season, episode, episodeTitle)
    }

    /**
     * Dismiss a skip button without skipping.
     */
    fun dismissSkipIntro() {
        _uiState.value = _uiState.value.copy(
            showSkipIntro = false,
            introDismissed = true
        )
    }

    fun dismissSkipRecap() {
        _uiState.value = _uiState.value.copy(
            showSkipRecap = false,
            recapDismissed = true
        )
    }

    fun dismissSkipCredits() {
        _uiState.value = _uiState.value.copy(
            showSkipCredits = false,
            creditsDismissed = true
        )
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
                    // Save preference: subtitles disabled
                    saveSubtitlePreference(enabled = false, language = null)
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
                                // Save preference: subtitles enabled with this language
                                saveSubtitlePreference(enabled = true, language = format.language)
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
            val shouldEnable = if (contentType == "tv") {
                if (settings?.lastSeriesTmdbId == tmdbId) {
                    // Same series - use the per-title toggle state
                    settings.enabled
                } else {
                    // New series - use the global default setting
                    settings?.autoplaySeriesDefault ?: true
                }
            } else {
                settings?.sessionEnabled ?: false
            }
            _uiState.value = _uiState.value.copy(autoPlayEnabled = shouldEnable)
        }
    }

    private fun loadSubtitleStylePreferences() {
        viewModelScope.launch {
            try {
                val prefs = subtitlePreferencesDao.getSettings() ?: return@launch
                _uiState.value = _uiState.value.copy(
                    subtitleSize = prefs.subtitleSize,
                    subtitleColor = prefs.subtitleColor,
                    subtitleBackground = prefs.subtitleBackground,
                    subtitleEdge = prefs.subtitleEdge
                )
            } catch (e: Exception) {
                println("[SUBTITLES] Failed to load style preferences: ${e.message}")
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
                // When autoplay is enabled mid-playback:
                // 1. Ensure we have next episode info (may already be set from progress response)
                prefetchNextContent()
                // 2. Trigger source prefetch if we have next episode info and haven't prefetched yet
                if (_uiState.value.nextEpisodeInfo?.hasNext == true && !_uiState.value.hasPrefetched) {
                    triggerPrefetch()
                }
            }
        }
    }

    private fun prefetchNextContent() {
        if (_uiState.value.isLoadingNextContent) return

        // If we already have next episode info from progress response, don't re-fetch
        // (except for random playback which needs fresh random selection)
        val existingNextEpisode = _uiState.value.nextEpisodeInfo
        if (!isRandomPlayback && existingNextEpisode != null) {
            println("[AutoPlay] Already have next episode info from progress response, skipping fetch")
            return
        }

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
        // Trigger background bandwidth test between episodes (non-blocking)
        bandwidthTester.performTestAndReportInBackground("episode-end")

        if (!_uiState.value.autoPlayEnabled) return

        // If countdown is already running (from Up Next overlay), let it finish
        // The countdown will trigger playNextEpisodeImmediately() when done
        if (autoPlayCountdownJob?.isActive == true && _uiState.value.autoPlayCountdown > 0) {
            println("[AutoPlay] Video ended but countdown already running (${_uiState.value.autoPlayCountdown}s remaining)")
            return
        }

        // If Up Next overlay was dismissed, don't auto-play
        if (upNextOverlayDismissed) {
            println("[AutoPlay] Video ended but Up Next was dismissed, not auto-playing")
            return
        }

        if (contentType == "tv") handleTVEpisodeEnded() else handleMovieEnded()
    }

    private fun handleTVEpisodeEnded() {
        // In random mode, always fetch a fresh random episode
        if (isRandomPlayback) {
            fetchRandomEpisodeAndPlay()
            return
        }

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
                        onAutoPlayNext?.invoke(prefetchEpisode.season, prefetchEpisode.episode, prefetchEpisode.title)
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
        // Try nextEpisodeInfo first, then fall back to prefetchNextEpisode
        val nextEpisode = _uiState.value.nextEpisodeInfo
        val prefetchEpisode = _uiState.value.prefetchNextEpisode

        // Determine which source to use for next episode info
        val season: Int?
        val episode: Int?
        val episodeTitle: String?

        when {
            nextEpisode != null && nextEpisode.hasNext && nextEpisode.season != null && nextEpisode.episode != null -> {
                // Use nextEpisodeInfo
                season = nextEpisode.season
                episode = nextEpisode.episode
                episodeTitle = nextEpisode.title
                println("[AutoPlay] Using nextEpisodeInfo: S${season}E${episode} - $episodeTitle")
            }
            prefetchEpisode != null -> {
                // Fall back to prefetchNextEpisode
                season = prefetchEpisode.season
                episode = prefetchEpisode.episode
                episodeTitle = prefetchEpisode.title
                println("[AutoPlay] Using prefetchNextEpisode as fallback: S${season}E${episode} - $episodeTitle")
            }
            else -> {
                // No next episode info available
                println("[AutoPlay] No next episode info available")
                // In random mode, try to fetch a random episode on-the-fly
                if (isRandomPlayback) {
                    fetchRandomEpisodeAndPlay()
                    return
                }
                // Show series complete only in sequential mode
                _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = true)
                return
            }
        }

        // Validate season and episode
        if (season == null || episode == null) {
            println("[ERROR] Next episode season or episode is null: season=$season, episode=$episode")
            if (!isRandomPlayback) {
                _uiState.value = _uiState.value.copy(showSeriesCompleteOverlay = true)
            }
            return
        }

        startAutoPlayCountdown {
            onAutoPlayNext?.invoke(season, episode, episodeTitle)
        }
    }

    private fun fetchRandomEpisodeAndPlay() {
        viewModelScope.launch {
            try {
                println("[AutoPlay] Fetching random episode on-the-fly for tmdbId=$tmdbId")
                val randomEpisode = api.getRandomEpisode(tmdbId)
                val decodedTitle = decodeTitle(randomEpisode.title)
                println("[AutoPlay] Got random episode: S${randomEpisode.season}E${randomEpisode.episode} - $decodedTitle")

                // Start countdown and play
                startAutoPlayCountdown {
                    onAutoPlayNext?.invoke(randomEpisode.season, randomEpisode.episode, decodedTitle)
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

    /**
     * Start autoplay countdown when Up Next overlay appears (in last 5 seconds of file).
     * This runs the countdown while the overlay is visible, then triggers next episode.
     */
    private fun startAutoPlayCountdownFromOverlay() {
        autoPlayCountdownJob?.cancel()
        _uiState.value = _uiState.value.copy(autoPlayCountdown = 5)
        autoPlayCountdownJob = viewModelScope.launch {
            for (i in 5 downTo 1) {
                _uiState.value = _uiState.value.copy(autoPlayCountdown = i)
                delay(1000)
            }
            // Countdown finished, play next episode
            playNextEpisodeImmediately()
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

    fun dismissUpNextOverlay() {
        upNextOverlayDismissed = true
        autoPlayCountdownJob?.cancel()
        _uiState.value = _uiState.value.copy(
            showUpNextOverlay = false,
            autoPlayCountdown = 0
        )
    }

    fun playNextNow() {
        // User clicked "Play Now" - trigger immediate playback
        println("[NextEpisode] User clicked Play Now")
        playNextEpisodeImmediately()
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

                if (response.hasNext && response.jobId != null && response.nextEpisode != null) {
                    println("[Prefetch] Started prefetch job ${response.jobId} for S${response.nextEpisode.season}E${response.nextEpisode.episode}")

                    // Also set nextEpisodeInfo as fallback in case prefetch promote fails
                    val nextEpisodeInfo = com.duckflix.lite.data.remote.dto.NextEpisodeResponse(
                        hasNext = true,
                        season = response.nextEpisode.season,
                        episode = response.nextEpisode.episode,
                        title = decodeTitle(response.nextEpisode.title),
                        inCurrentPack = true  // Prefetch means it's available
                    )

                    _uiState.value = _uiState.value.copy(
                        prefetchJobId = response.jobId,
                        prefetchNextEpisode = response.nextEpisode,
                        nextEpisodeInfo = nextEpisodeInfo  // Set fallback info
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
            // Use a 5-second timeout to avoid blocking autoplay if server is slow
            val response = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                api.promotePrefetch(jobId)
            }

            if (response == null) {
                println("[Prefetch] Promote timed out after 5s, falling back to normal autoplay")
                return false
            }

            println("[Prefetch] Promote response: success=${response.success}, status=${response.status}, error=${response.error}")

            // Check for error response
            if (response.error != null) {
                println("[Prefetch] Server error: ${response.error}")
                return false
            }

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
                        title = decodeTitle(response.nextEpisode.title),
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
     * Force-fetch English subtitles from OpenSubtitles (bypasses cache, costs 1 daily quota)
     */
    fun forceEnglishSubtitles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isForceEnglishLoading = true)
            try {
                println("[SUBTITLES] Force-fetching English subtitles for $contentTitle")

                val request = com.duckflix.lite.data.remote.dto.SubtitleSearchRequest(
                    tmdbId = tmdbId,
                    title = contentTitle,
                    year = year,
                    type = contentType,
                    season = season,
                    episode = episode,
                    languageCode = "en",
                    force = true
                )

                val response = api.forceSearchSubtitle(request)

                if (response.success && response.subtitle != null) {
                    println("[SUBTITLES] Force English subtitle found, adding to player...")
                    pendingAutoSelectSubtitle = true
                    addExternalSubtitles(listOf(response.subtitle))
                    // Dismiss panel and save English as preferred language
                    _uiState.value = _uiState.value.copy(showSubtitlePanel = false)
                    saveSubtitlePreference(enabled = true, language = "en")
                } else {
                    println("[SUBTITLES] Force English subtitle not found")
                }
            } catch (e: Exception) {
                println("[SUBTITLES] Failed to force-fetch English subtitles: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isForceEnglishLoading = false)
            }
        }
    }

    /**
     * Auto-select the last (most recently added) subtitle track
     */
    private var pendingAutoSelectEnglish = false

    private fun autoSelectLastSubtitle() {
        // After adding external subs via Force English, ExoPlayer replaces the media item
        // which triggers multiple onTracksChanged calls. The new subtitle track may not
        // appear until a later call. Set a flag so we keep trying on each onTracksChanged.
        pendingAutoSelectEnglish = true
        tryAutoSelectEnglish()
    }

    private fun tryAutoSelectEnglish() {
        val tracks = _uiState.value.subtitleTracks
        val englishTrack = tracks.lastOrNull { track ->
            val lang = track.language?.take(2)?.lowercase()
            lang == "en"
        }
        if (englishTrack != null) {
            pendingAutoSelectEnglish = false
            println("[SUBTITLES] Auto-selecting Force English subtitle: ${englishTrack.label}")
            selectSubtitleTrack(englishTrack.id)
        } else {
            println("[SUBTITLES] English track not available yet, will retry on next tracks update")
        }
    }

    /**
     * Save subtitle language preference to DB for sticky subs across sessions.
     * Resolves "und"/null to a real language code from the track label if possible.
     */
    private fun saveSubtitlePreference(enabled: Boolean, language: String?) {
        viewModelScope.launch {
            try {
                // Resolve the language — if "und" or null, try to get it from the currently selected track label
                var resolvedLang = language
                if (resolvedLang == null || resolvedLang.lowercase() == "und") {
                    // Try to find the selected track and reverse-map its label to a language code
                    val selectedTrack = _uiState.value.subtitleTracks.firstOrNull { it.isSelected }
                    if (selectedTrack != null) {
                        val labelLower = selectedTrack.label.replace(Regex("\\s*\\(.*\\)"), "").trim().lowercase()
                        // Reverse lookup: find a language code from the label
                        resolvedLang = languageMap.entries
                            .firstOrNull { (_, name) -> name.lowercase() == labelLower }
                            ?.key?.take(2) // Normalize to 2-letter code
                    }
                    println("[SUBTITLES] Resolved language from label: $resolvedLang (original: $language)")
                }

                val current = subtitlePreferencesDao.getSettings()
                    ?: com.duckflix.lite.data.local.entity.SubtitlePreferencesEntity()

                if (!enabled) {
                    // Disabling subs — keep the saved language for next time
                    subtitlePreferencesDao.saveSettings(current.copy(subtitlesEnabled = false))
                    println("[SUBTITLES] Saved preference: disabled (keeping language=${current.preferredLanguage})")
                    return@launch
                }

                // Don't save "und" or null as the preferred language
                if (resolvedLang == null || resolvedLang.lowercase() == "und") {
                    println("[SUBTITLES] Skipping save — couldn't resolve language")
                    return@launch
                }

                subtitlePreferencesDao.saveSettings(
                    current.copy(subtitlesEnabled = true, preferredLanguage = resolvedLang)
                )
                println("[SUBTITLES] Saved preference: enabled=true, language=$resolvedLang")
            } catch (e: Exception) {
                println("[SUBTITLES] Failed to save preference: ${e.message}")
            }
        }
    }

    /**
     * Auto-select subtitle track from saved preferences (sticky subs).
     * Called from onTracksChanged when not handling a Force English addition.
     * Matches by language code (e.g. "en") since track IDs change per video.
     */
    private var hasRestoredSubtitlePref = false
    private fun autoSelectSavedSubtitlePreference() {
        if (hasRestoredSubtitlePref) return
        hasRestoredSubtitlePref = true

        viewModelScope.launch {
            try {
                val prefs = subtitlePreferencesDao.getSettings()

                // Determine target language: saved preference, or default to English
                val targetLang: String
                val isExplicitlyDisabled: Boolean

                if (prefs != null && prefs.preferredLanguage != null) {
                    // User has a saved preference
                    if (!prefs.subtitlesEnabled) {
                        println("[SUBTITLES] User explicitly turned off subs — respecting preference")
                        return@launch
                    }
                    targetLang = prefs.preferredLanguage.take(2).lowercase()
                    isExplicitlyDisabled = false
                } else {
                    // No saved preference — default to auto-selecting English
                    targetLang = "en"
                    isExplicitlyDisabled = false
                    println("[SUBTITLES] No saved preference — defaulting to English")
                }

                val tracks = _uiState.value.subtitleTracks
                if (tracks.isEmpty()) {
                    println("[SUBTITLES] No subtitle tracks available to auto-select")
                    return@launch
                }

                // Match by language code (normalize to 2-letter), skip unknown/undefined tracks
                val match = tracks.firstOrNull { track ->
                    val trackLang = track.language?.take(2)?.lowercase()
                    trackLang != null && trackLang != "un" && trackLang == targetLang
                }
                // Fallback: match by label if language codes didn't work (e.g. language="und" but label="English")
                ?: tracks.firstOrNull { track ->
                    val labelLang = track.label.replace(Regex("\\s*\\(.*\\)"), "").trim().lowercase()
                    val resolvedCode = languageMap.entries
                        .firstOrNull { (_, name) -> name.lowercase() == labelLang }
                        ?.key?.take(2)
                    resolvedCode == targetLang
                }

                if (match != null) {
                    println("[SUBTITLES] Auto-selecting: ${match.label} (lang=${match.language}, target=$targetLang)")
                    selectSubtitleTrack(match.id)
                } else {
                    println("[SUBTITLES] No track matching language '$targetLang' found")
                }
            } catch (e: Exception) {
                println("[SUBTITLES] Failed to restore subtitle preference: ${e.message}")
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

                val newConfigs = externalSubs.mapNotNull { subtitle ->
                    val url = subtitle.url ?: return@mapNotNull null
                    // Default to SRT — OpenSubtitles primarily serves SRT, and our server
                    // proxies them without a file extension
                    val mimeType = when {
                        url.endsWith(".vtt", ignoreCase = true) -> "text/vtt"
                        url.endsWith(".ass", ignoreCase = true) || url.endsWith(".ssa", ignoreCase = true) -> "text/x-ssa"
                        else -> "application/x-subrip" // SRT is the most common format
                    }
                    println("[SUBTITLES] Adding external sub: url=$url, mimeType=$mimeType, lang=${subtitle.languageCode ?: subtitle.language}")
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                        .setMimeType(mimeType)
                        .setLanguage(subtitle.languageCode ?: subtitle.language)
                        .setLabel(subtitle.label ?: subtitle.language)
                        .setId(subtitle.id?.toString() ?: "ext_${subtitle.language}")
                        .build()
                }

                if (newConfigs.isEmpty()) {
                    println("[SUBTITLES] No valid subtitle configurations to add")
                    return
                }

                // Merge with any existing subtitle configurations instead of replacing
                val existingConfigs = currentMediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val mergedConfigs = existingConfigs + newConfigs

                // Create new media item with merged subtitles
                val newMediaItem = currentMediaItem.buildUpon()
                    .setSubtitleConfigurations(mergedConfigs)
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

                println("[SUBTITLES] Added ${newConfigs.size} external subtitle tracks (${mergedConfigs.size} total)")
            } catch (e: Exception) {
                isAddingSubtitles = false // Clear on synchronous exceptions
                println("[ERROR] Failed to add external subtitles: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        // Capture player state BEFORE releasing - saveProgress needs these values
        // but the coroutine runs async and player will be null by then
        val player = _player
        val currentPosition = player?.currentPosition ?: 0L
        val duration = player?.duration ?: 0L

        // Save final progress and end session
        viewModelScope.launch {
            try {
                saveProgressWithValues(currentPosition, duration)
                api.endVodSession()
            } catch (e: Exception) {
                println("[ERROR] VideoPlayerViewModel: Failed to save final progress: ${e.message}")
            }
        }

        player?.release()
        _player = null
    }
}
