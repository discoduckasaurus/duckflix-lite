package com.duckflix.lite.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import com.duckflix.lite.ui.components.CircularBackButton
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.LoadingIndicator
import com.duckflix.lite.ui.components.SourceSelectionScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VideoPlayerScreen(
    onNavigateBack: () -> Unit,
    viewModel: VideoPlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val backgroundFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    // Track when controls become visible to trigger focus request
    var controlsVisibleKey by remember { mutableStateOf(0) }
    LaunchedEffect(uiState.showControls) {
        if (uiState.showControls) {
            controlsVisibleKey++
        }
    }

    // Request focus on background when controls are hidden
    // Also request focus on initial launch to ensure we capture key events
    LaunchedEffect(uiState.showControls) {
        if (!uiState.showControls) {
            delay(100) // Small delay to ensure focus system is ready
            try {
                backgroundFocusRequester.requestFocus()
            } catch (e: IllegalArgumentException) {
                // Focus system not ready, ignore
            }
        }
    }

    // Request focus on initial launch to ensure Back key is captured
    LaunchedEffect(Unit) {
        delay(100)
        try {
            backgroundFocusRequester.requestFocus()
        } catch (e: IllegalArgumentException) {
            // Focus system not ready, ignore
        }
    }

    // Activity counter - incremented on user interaction to reset auto-hide timer
    var activityCounter by remember { mutableStateOf(0) }

    // Auto-hide controls after 5 seconds of inactivity
    // Restarts when activityCounter changes (user interaction)
    LaunchedEffect(uiState.showControls, uiState.isPlaying, activityCounter) {
        if (uiState.showControls && uiState.isPlaying) {
            delay(5000)
            viewModel.hideControls()
        }
    }

    // Track last seek time to enable continuous seeking
    var lastSeekTime by remember { mutableStateOf(0L) }
    val seekDebounceMs = 150L // Allow seeking every 150ms when holding key

    // Seek indicator state
    var seekIndicator by remember { mutableStateOf<String?>(null) }
    var indicatorJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Track panel dismissal to prevent accidental player exit
    // When a panel closes, we briefly block the "exit player" action on Back
    var panelJustDismissed by remember { mutableStateOf(false) }
    var wasPanelOpen by remember { mutableStateOf(false) }
    val isPanelOpen = uiState.showVideoIssuesPanel || uiState.showAudioPanel || uiState.showSubtitlePanel

    // Detect when any panel closes and set the flag
    LaunchedEffect(isPanelOpen) {
        if (isPanelOpen) {
            wasPanelOpen = true
        } else if (wasPanelOpen) {
            // Panel was open and now closed - set the flag
            wasPanelOpen = false
            panelJustDismissed = true
            delay(500) // Block exit for 500ms after panel dismissal
            panelJustDismissed = false
        }
    }

    // Accumulated seek state for smooth rapid seeking (Bug 4 fix)
    var accumulatedSeekTarget by remember { mutableStateOf<Long?>(null) }
    var seekApplyJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(backgroundFocusRequester)
            .onKeyEvent { keyEvent ->
                val currentTime = System.currentTimeMillis()

                // Handle on KeyDown for continuous seeking (only when controls hidden)
                if (keyEvent.type == KeyEventType.KeyDown && !uiState.showControls) {
                    when (keyEvent.key) {
                        Key.DirectionRight -> {
                            // Allow seeking if enough time has passed (debounce)
                            if (currentTime - lastSeekTime >= seekDebounceMs) {
                                // Accumulate seek target locally instead of calling seekForward each time
                                val basePosition = accumulatedSeekTarget ?: uiState.currentPosition
                                accumulatedSeekTarget = (basePosition + 10000).coerceAtMost(uiState.duration)
                                lastSeekTime = currentTime

                                // Show accumulated offset indicator
                                val totalOffset = accumulatedSeekTarget!! - uiState.currentPosition
                                seekIndicator = if (totalOffset >= 0) "+${totalOffset / 1000}s" else "${totalOffset / 1000}s"

                                // Cancel previous apply job and schedule new one
                                seekApplyJob?.cancel()
                                seekApplyJob = coroutineScope.launch {
                                    delay(300) // Wait for rapid key events to settle
                                    accumulatedSeekTarget?.let { target ->
                                        viewModel.seekTo(target)
                                        accumulatedSeekTarget = null
                                    }
                                    delay(700)
                                    seekIndicator = null
                                }
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            // Allow seeking if enough time has passed (debounce)
                            if (currentTime - lastSeekTime >= seekDebounceMs) {
                                // Accumulate seek target locally instead of calling seekBackward each time
                                val basePosition = accumulatedSeekTarget ?: uiState.currentPosition
                                accumulatedSeekTarget = (basePosition - 10000).coerceAtLeast(0)
                                lastSeekTime = currentTime

                                // Show accumulated offset indicator
                                val totalOffset = accumulatedSeekTarget!! - uiState.currentPosition
                                seekIndicator = if (totalOffset >= 0) "+${totalOffset / 1000}s" else "${totalOffset / 1000}s"

                                // Cancel previous apply job and schedule new one
                                seekApplyJob?.cancel()
                                seekApplyJob = coroutineScope.launch {
                                    delay(300) // Wait for rapid key events to settle
                                    accumulatedSeekTarget?.let { target ->
                                        viewModel.seekTo(target)
                                        accumulatedSeekTarget = null
                                    }
                                    delay(700)
                                    seekIndicator = null
                                }
                            }
                            true
                        }
                        else -> false
                    }
                } else if (keyEvent.type == KeyEventType.KeyUp) {
                    when (keyEvent.key) {
                        Key.Back, Key.Escape -> {
                            // Back button handling priority:
                            // 1. If a panel is open, dismiss the panel
                            // 2. If panel was just dismissed OR controls are visible, hide controls
                            // 3. If controls are hidden (and no recent panel dismissal), exit the player
                            when {
                                uiState.showVideoIssuesPanel -> {
                                    viewModel.setVideoIssuesPanelVisible(false)
                                    panelJustDismissed = true
                                    true
                                }
                                uiState.showAudioPanel -> {
                                    viewModel.toggleAudioPanel()
                                    panelJustDismissed = true
                                    true
                                }
                                uiState.showSubtitlePanel -> {
                                    viewModel.toggleSubtitlePanel()
                                    panelJustDismissed = true
                                    true
                                }
                                uiState.showControls || panelJustDismissed -> {
                                    // Controls visible OR panel just closed - hide controls (don't exit)
                                    viewModel.hideControls()
                                    panelJustDismissed = false // Clear the flag
                                    true
                                }
                                else -> {
                                    // Controls hidden - exit player
                                    onNavigateBack()
                                    true
                                }
                            }
                        }
                        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                            if (!uiState.showControls) {
                                viewModel.togglePlayPause()
                                viewModel.showControls()
                                true
                            } else {
                                false // Let controls handle it
                            }
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (!uiState.showControls) {
                                viewModel.showControls()
                                true
                            } else {
                                false // Let controls handle navigation
                            }
                        }
                        // Media remote control buttons
                        Key.MediaPlayPause -> {
                            viewModel.togglePlayPause()
                            true
                        }
                        Key.MediaPlay -> {
                            viewModel.play()
                            true
                        }
                        Key.MediaPause -> {
                            viewModel.pause()
                            true
                        }
                        Key.MediaFastForward -> {
                            viewModel.seekForward()
                            true
                        }
                        Key.MediaRewind -> {
                            viewModel.seekBackward()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .focusable()
    ) {
        when {
            uiState.error != null -> {
                ErrorScreen(
                    message = uiState.error ?: "Unknown error",
                    onRetry = viewModel::retryPlayback
                )
            }

            viewModel.player != null -> {
                // ExoPlayer view with HDR support
                // SurfaceView is required for HDR passthrough (TextureView cannot pass HDR metadata)
                // System automatically handles HDR-to-SDR tone mapping on non-HDR displays
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = viewModel.player
                            useController = false // Use custom controls
                            keepScreenOn = true
                            // Use SurfaceView for HDR passthrough (default, but be explicit)
                            // This ensures HDR10/HDR10+/Dolby Vision content displays correctly
                            // On non-HDR displays, Android handles tone mapping automatically
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // We handle buffering UI
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { view ->
                        // Ensure player is attached when view updates
                        view.player = viewModel.player
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Seek indicator (shows +10s / -10s when seeking without controls)
                seekIndicator?.let { text ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(bottom = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = 0.6f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                }

                // Custom controls overlay
                if (uiState.showControls) {
                    PlayerControls(
                        controlsVisibleKey = controlsVisibleKey,
                        isPlaying = uiState.isPlaying,
                        isLoading = uiState.isLoading,
                        currentPosition = uiState.currentPosition,
                        duration = uiState.duration,
                        isAutoPlayEnabled = uiState.autoPlayEnabled,
                        logoUrl = uiState.logoUrl,
                        displayQuality = uiState.displayQuality,
                        onPlayPauseClick = viewModel::togglePlayPause,
                        onSeekForward = viewModel::seekForward,
                        onSeekBackward = viewModel::seekBackward,
                        onSeekTo = viewModel::seekTo,
                        onBackClick = onNavigateBack,
                        audioTracks = uiState.audioTracks,
                        subtitleTracks = uiState.subtitleTracks,
                        onAudioTrackSelected = viewModel::selectAudioTrack,
                        onSubtitleTrackSelected = viewModel::selectSubtitleTrack,
                        onToggleAutoPlay = viewModel::toggleAutoPlay,
                        onHideControls = viewModel::hideControls,
                        showVideoIssuesPanel = uiState.showVideoIssuesPanel,
                        showAudioPanel = uiState.showAudioPanel,
                        showSubtitlePanel = uiState.showSubtitlePanel,
                        isReportingBadLink = uiState.isReportingBadLink,
                        onToggleVideoIssuesPanel = { viewModel.setVideoIssuesPanelVisible(!uiState.showVideoIssuesPanel) },
                        onToggleAudioPanel = viewModel::toggleAudioPanel,
                        onToggleSubtitlePanel = viewModel::toggleSubtitlePanel,
                        onReportBadLink = viewModel::reportBadLink,
                        onDismissVideoIssuesPanel = { viewModel.setVideoIssuesPanelVisible(false) },
                        onDismissAudioPanel = { viewModel.toggleAudioPanel() },
                        onDismissSubtitlePanel = { viewModel.toggleSubtitlePanel() },
                        hasNextEpisode = uiState.nextEpisodeInfo?.hasNext == true,
                        onNextEpisode = viewModel::playNextNow,
                        onUserActivity = { activityCounter++ },
                        contentType = uiState.contentType,
                        currentSeason = uiState.currentSeason,
                        currentEpisode = uiState.currentEpisode,
                        episodeTitle = uiState.episodeTitle,
                        onSeekStart = viewModel::onSeekStart,
                        onSeekEnd = viewModel::onSeekEnd,
                        onForceEnglishSubtitles = viewModel::forceEnglishSubtitles,
                        isForceEnglishLoading = uiState.isForceEnglishLoading
                    )
                }


                // Series Complete Overlay
                if (uiState.showSeriesCompleteOverlay) {
                    SeriesCompleteOverlay(
                        onDismiss = viewModel::dismissSeriesComplete,
                        onNavigateBack = onNavigateBack
                    )
                }

                // Random Episode Error Overlay
                if (uiState.showRandomEpisodeError) {
                    RandomEpisodeErrorOverlay(
                        onNavigateBack = {
                            viewModel.dismissRandomEpisodeError()
                            onNavigateBack()
                        }
                    )
                }

                // Recommendations Overlay
                if (uiState.showRecommendationsOverlay) {
                    RecommendationsOverlay(
                        recommendations = uiState.movieRecommendations ?: emptyList(),
                        selectedRecommendation = uiState.selectedRecommendation,
                        countdown = uiState.autoPlayCountdown,
                        onSelectRecommendation = viewModel::selectRecommendation,
                        onCancel = {
                            viewModel.cancelAutoPlay()
                            viewModel.dismissRecommendations()
                        },
                        onNavigateBack = onNavigateBack
                    )
                }

                // Next Episode Countdown (full screen)
                if (uiState.autoPlayCountdown > 0 && uiState.nextEpisodeInfo != null) {
                    NextEpisodeOverlay(
                        nextEpisode = uiState.nextEpisodeInfo!!,
                        countdown = uiState.autoPlayCountdown,
                        onCancel = viewModel::cancelAutoPlay
                    )
                }

                // Up Next overlay (shows at 95% when no skip credits data)
                // Don't show when skip credits button is visible (skip credits replaces this)
                if (uiState.showUpNextOverlay &&
                    uiState.nextEpisodeInfo != null &&
                    !uiState.showSkipCredits) {
                    UpNextOverlay(
                        nextEpisode = uiState.nextEpisodeInfo!!,
                        onPlayNow = viewModel::playNextNow,
                        onDismiss = viewModel::dismissUpNextOverlay
                    )
                }

                // Skip buttons (intro/recap/credits)
                // Always render but visibility is controlled by AnimatedVisibility inside
                SkipButtonsOverlay(
                    showIntro = uiState.showSkipIntro,
                    showRecap = uiState.showSkipRecap,
                    showCredits = uiState.showSkipCredits,
                    onSkipIntro = viewModel::skipIntro,
                    onSkipRecap = viewModel::skipRecap,
                    onSkipCredits = viewModel::skipCredits
                )
            }
        }

        // Loading overlay - shown during SEARCHING or DOWNLOADING
        if (uiState.loadingPhase == LoadingPhase.SEARCHING ||
            uiState.loadingPhase == LoadingPhase.DOWNLOADING) {

            // Show progress bar when progress >= 20 OR in DOWNLOADING phase
            val shouldShowProgress = uiState.downloadProgress >= 20 ||
                                    uiState.loadingPhase == LoadingPhase.DOWNLOADING

            SourceSelectionScreen(
                message = uiState.downloadMessage,
                showProgress = shouldShowProgress,
                progress = uiState.downloadProgress,
                logoUrl = uiState.logoUrl,
                backdropUrl = uiState.posterUrl,
                onCancel = {
                    viewModel.cancelDownload()
                    onNavigateBack()
                },
                onComeBackLater = if (shouldShowProgress && uiState.downloadProgress < 100) {
                    { onNavigateBack() }
                } else null
            )
        }
    }
}

@Composable
private fun PlayerControls(
    controlsVisibleKey: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    currentPosition: Long,
    duration: Long,
    isAutoPlayEnabled: Boolean,
    logoUrl: String?,
    displayQuality: String,
    onPlayPauseClick: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onBackClick: () -> Unit,
    audioTracks: List<com.duckflix.lite.ui.screens.player.TrackInfo>,
    subtitleTracks: List<com.duckflix.lite.ui.screens.player.TrackInfo>,
    onAudioTrackSelected: (String) -> Unit,
    onSubtitleTrackSelected: (String) -> Unit,
    onToggleAutoPlay: () -> Unit,
    onHideControls: () -> Unit,
    showVideoIssuesPanel: Boolean,
    showAudioPanel: Boolean,
    showSubtitlePanel: Boolean,
    isReportingBadLink: Boolean,
    onToggleVideoIssuesPanel: () -> Unit,
    onToggleAudioPanel: () -> Unit,
    onToggleSubtitlePanel: () -> Unit,
    onReportBadLink: () -> Unit,
    onDismissVideoIssuesPanel: () -> Unit,
    onDismissAudioPanel: () -> Unit,
    onDismissSubtitlePanel: () -> Unit,
    onUserActivity: () -> Unit = {},
    hasNextEpisode: Boolean = false,
    onNextEpisode: () -> Unit = {},
    contentType: String = "movie",
    currentSeason: Int? = null,
    currentEpisode: Int? = null,
    episodeTitle: String? = null,
    onSeekStart: () -> Unit = {},
    onSeekEnd: () -> Unit = {},
    onForceEnglishSubtitles: () -> Unit = {},
    isForceEnglishLoading: Boolean = false
) {
    val playPauseFocusRequester = remember { FocusRequester() }
    val autoplayButtonFocusRequester = remember { FocusRequester() }
    val audioButtonFocusRequester = remember { FocusRequester() }
    val ccButtonFocusRequester = remember { FocusRequester() }
    val issuesButtonFocusRequester = remember { FocusRequester() }
    val nextEpisodeFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val sliderFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Request focus on play/pause button when controls appear (unless a panel is open)
    LaunchedEffect(controlsVisibleKey) {
        delay(100)
        if (!showVideoIssuesPanel && !showAudioPanel && !showSubtitlePanel) {
            playPauseFocusRequester.requestFocus()
        }
    }

    // Dim controls when any panel is open
    val controlsAlpha = if (showVideoIssuesPanel || showAudioPanel || showSubtitlePanel) 0.3f else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .onKeyEvent { keyEvent ->
                // Handle Back key to hide controls (unless a panel is open - they handle their own back)
                if (keyEvent.type == KeyEventType.KeyUp &&
                    (keyEvent.key == Key.Back || keyEvent.key == Key.Escape)) {
                    if (!showVideoIssuesPanel && !showAudioPanel && !showSubtitlePanel) {
                        onHideControls()
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
    ) {
        // Top bar with back button and centered logo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = com.duckflix.lite.ui.components.TVSafeArea.HorizontalPadding,
                    vertical = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding
                )
                .align(Alignment.TopStart)
                .alpha(controlsAlpha)
        ) {
            // Back button - left aligned
            CircularBackButton(
                onClick = onBackClick,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .focusProperties {
                        right = playPauseFocusRequester
                    },
                focusRequester = backButtonFocusRequester
            )

            // Logo + episode info + quality - centered
            Column(
                modifier = Modifier.align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (logoUrl != null) {
                    coil.compose.AsyncImage(
                        model = logoUrl,
                        contentDescription = "Title",
                        modifier = Modifier
                            .height(80.dp)
                            .widthIn(max = 300.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
                // Episode info for TV shows: S##E## on first line, title on second line
                if (contentType == "tv" && currentSeason != null && currentEpisode != null) {
                    Text(
                        text = "S${currentSeason.toString().padStart(2, '0')}E${currentEpisode.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (!episodeTitle.isNullOrBlank()) {
                        Text(
                            text = episodeTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
                if (displayQuality.isNotEmpty()) {
                    Text(
                        text = displayQuality,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Center playback control
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(controlsAlpha)
        ) {
            // Play/Pause button - custom icon as the button itself
            val playPauseInteractionSource = remember { MutableInteractionSource() }
            val isPlayPauseFocused by playPauseInteractionSource.collectIsFocusedAsState()

            Image(
                painter = painterResource(
                    id = if (isPlaying)
                        com.duckflix.lite.R.drawable.ic_pause
                    else
                        com.duckflix.lite.R.drawable.ic_play
                ),
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier
                    .size(96.dp)
                    .focusRequester(playPauseFocusRequester)
                    .focusProperties {
                        right = audioButtonFocusRequester
                        down = sliderFocusRequester
                    }
                    .focusable(interactionSource = playPauseInteractionSource)
                    .clickable(
                        interactionSource = playPauseInteractionSource,
                        indication = null,
                        enabled = !isLoading
                    ) { onPlayPauseClick() }
                    .then(if (isPlayPauseFocused) Modifier.border(4.dp, Color.White, CircleShape) else Modifier),
                alpha = if (isLoading) 0.5f else 1.0f
            )
        }

        // Bottom-right control stack (Autoplay, Audio, CC, ?)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = com.duckflix.lite.ui.components.TVSafeArea.HorizontalPadding,
                    bottom = 200.dp
                )
                .alpha(controlsAlpha),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlayerIconButton(
                iconRes = com.duckflix.lite.R.drawable.df_autoplay,
                label = if (isAutoPlayEnabled) "Autoplay On" else "Autoplay Off",
                onClick = onToggleAutoPlay,
                isActive = isAutoPlayEnabled,
                focusRequester = autoplayButtonFocusRequester,
                focusUp = null,
                focusDown = audioButtonFocusRequester,
                focusLeft = playPauseFocusRequester,
                onFocusChanged = { if (it) onUserActivity() }
            )
            PlayerIconButton(
                iconRes = com.duckflix.lite.R.drawable.df_audio_track,
                label = "Audio Track",
                onClick = onToggleAudioPanel,
                focusRequester = audioButtonFocusRequester,
                focusUp = autoplayButtonFocusRequester,
                focusDown = ccButtonFocusRequester,
                focusLeft = playPauseFocusRequester,
                onFocusChanged = { if (it) onUserActivity() }
            )
            PlayerIconButton(
                iconRes = com.duckflix.lite.R.drawable.df_subs,
                label = if (subtitleTracks.any { it.isSelected }) "Subtitles On" else "Subtitles Off",
                onClick = onToggleSubtitlePanel,
                isActive = subtitleTracks.any { it.isSelected },
                focusRequester = ccButtonFocusRequester,
                focusUp = audioButtonFocusRequester,
                focusDown = issuesButtonFocusRequester,
                onFocusChanged = { if (it) onUserActivity() }
            )
            PlayerIconButton(
                iconRes = com.duckflix.lite.R.drawable.df_help,
                label = "Video Issues",
                onClick = onToggleVideoIssuesPanel,
                focusRequester = issuesButtonFocusRequester,
                focusUp = ccButtonFocusRequester,
                focusDown = if (hasNextEpisode) nextEpisodeFocusRequester else null,
                onFocusChanged = { if (it) onUserActivity() }
            )
            // Next Episode button - only shown for TV shows with a next episode
            if (hasNextEpisode) {
                PlayerIconButton(
                    iconRes = com.duckflix.lite.R.drawable.df_next_episode,
                    label = "Next Episode",
                    onClick = onNextEpisode,
                    focusRequester = nextEpisodeFocusRequester,
                    focusUp = issuesButtonFocusRequester,
                    focusLeft = playPauseFocusRequester,
                    onFocusChanged = { if (it) onUserActivity() }
                )
            }
        }

        // Bottom bar with timeline and info
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = com.duckflix.lite.ui.components.TVSafeArea.HorizontalPadding,
                    vertical = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding
                )
                .align(Alignment.BottomCenter)
                .alpha(controlsAlpha),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Timeline with position and duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(currentPosition),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )

                Text(
                    text = formatTime(duration),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }

            // Progress bar (focusable slider with scrubbing support)
            var isDragging by remember { mutableStateOf(false) }
            var isKeyboardSeeking by remember { mutableStateOf(false) }
            var previewPosition by remember { mutableStateOf(currentPosition) }
            val sliderInteractionSource = remember { MutableInteractionSource() }
            val isSliderFocused by sliderInteractionSource.collectIsFocusedAsState()
            var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
            // Track timing for rapid seek continuation (Bug 4 fix)
            var lastSliderSeekTime by remember { mutableStateOf(0L) }
            val sliderSeekTimeoutMs = 500L

            // Monitor drag interactions
            LaunchedEffect(sliderInteractionSource) {
                sliderInteractionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> {
                            isDragging = true
                            onSeekStart()
                        }
                        is DragInteraction.Stop, is DragInteraction.Cancel -> {
                            if (isDragging) {
                                onSeekTo(previewPosition)
                                isDragging = false
                                onSeekEnd()
                            }
                        }
                    }
                }
            }

            androidx.compose.material3.Slider(
                value = if (isDragging || isKeyboardSeeking) previewPosition.toFloat() else currentPosition.toFloat(),
                onValueChange = { newValue ->
                    if (isDragging || isKeyboardSeeking) {
                        previewPosition = newValue.toLong()
                    } else {
                        onSeekTo(newValue.toLong())
                    }
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(sliderFocusRequester)
                    .focusProperties {
                        up = playPauseFocusRequester
                    }
                    .onKeyEvent { keyEvent ->
                        val seekStep = 10000L // 10 seconds in milliseconds
                        val currentTime = System.currentTimeMillis()

                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.DirectionRight -> {
                                    if (!isKeyboardSeeking) {
                                        // First key press - pause and start seeking
                                        isKeyboardSeeking = true
                                        wasPlayingBeforeSeek = isPlaying
                                        if (isPlaying) onPlayPauseClick() // Pause
                                        onSeekStart() // Hide skip buttons during seek
                                        // Only reset previewPosition if this is a fresh seek (not rapid continuation)
                                        val timeSinceLastSeek = currentTime - lastSliderSeekTime
                                        if (timeSinceLastSeek > sliderSeekTimeoutMs) {
                                            previewPosition = currentPosition
                                        }
                                    }
                                    lastSliderSeekTime = currentTime
                                    // Seek forward
                                    previewPosition = (previewPosition + seekStep).coerceAtMost(duration)
                                    true
                                }
                                Key.DirectionLeft -> {
                                    if (!isKeyboardSeeking) {
                                        // First key press - pause and start seeking
                                        isKeyboardSeeking = true
                                        wasPlayingBeforeSeek = isPlaying
                                        if (isPlaying) onPlayPauseClick() // Pause
                                        onSeekStart() // Hide skip buttons during seek
                                        // Only reset previewPosition if this is a fresh seek (not rapid continuation)
                                        val timeSinceLastSeek = currentTime - lastSliderSeekTime
                                        if (timeSinceLastSeek > sliderSeekTimeoutMs) {
                                            previewPosition = currentPosition
                                        }
                                    }
                                    lastSliderSeekTime = currentTime
                                    // Seek backward
                                    previewPosition = (previewPosition - seekStep).coerceAtLeast(0)
                                    true
                                }
                                else -> false
                            }
                        } else if (keyEvent.type == KeyEventType.KeyUp) {
                            when (keyEvent.key) {
                                Key.DirectionRight, Key.DirectionLeft -> {
                                    // Use delayed application to handle rapid KeyDown+KeyUp pairs
                                    coroutineScope.launch {
                                        delay(200)
                                        val timeSinceLastSeek = System.currentTimeMillis() - lastSliderSeekTime
                                        if (isKeyboardSeeking && timeSinceLastSeek >= 200) {
                                            // Apply the seek
                                            onSeekTo(previewPosition)
                                            isKeyboardSeeking = false
                                            onSeekEnd() // Allow skip buttons to show again
                                            // Resume playback if it was playing before
                                            if (wasPlayingBeforeSeek && !isPlaying) {
                                                onPlayPauseClick()
                                            }
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
                    .focusable(interactionSource = sliderInteractionSource),
                interactionSource = sliderInteractionSource,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = if (isSliderFocused) Color.White else PlayerControlColors.PinkSolid,
                    activeTrackColor = PlayerControlColors.PinkSolid,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )

            // Control hints
            Text(
                text = "◀ ▶ Navigate/Seek  •  OK Select  •  Back Hide Controls",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        // Side panels (slide in from right)
        androidx.compose.animation.AnimatedVisibility(
            visible = showVideoIssuesPanel,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            VideoIssuesPanel(
                onRetryClick = onReportBadLink,
                onDismiss = onDismissVideoIssuesPanel,
                isLoading = isReportingBadLink
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showAudioPanel,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            AudioPanel(
                audioTracks = audioTracks,
                onTrackSelected = onAudioTrackSelected,
                onDismiss = onDismissAudioPanel
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showSubtitlePanel,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            SubtitlePanel(
                subtitleTracks = subtitleTracks,
                onTrackSelected = onSubtitleTrackSelected,
                onForceEnglish = onForceEnglishSubtitles,
                isForceEnglishLoading = isForceEnglishLoading,
                onDismiss = onDismissSubtitlePanel
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

// Gradient colors for player controls
object PlayerControlColors {
    val TealGradient = listOf(Color(0xFF00BFA5), Color(0xFF00E676))
    val PinkGradient = listOf(Color(0xFFE91E63), Color(0xFFAD1457))
    val PinkSolid = Color(0xFFE91E63)
}

@Composable
private fun GradientIconButton(
    icon: String,
    colors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onUnfocused: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused() else onUnfocused()
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(colors),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 3.dp,
                        color = Color.White,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    )
                } else {
                    Modifier
                }
            )
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )
    }
}

/**
 * Player icon button with PNG asset, tooltip on focus, and optional active/inactive state
 */
@Composable
private fun PlayerIconButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    focusRequester: FocusRequester? = null,
    focusUp: FocusRequester? = null,
    focusDown: FocusRequester? = null,
    focusLeft: FocusRequester? = null,
    focusRight: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Notify when focus changes
    LaunchedEffect(isFocused) {
        onFocusChanged(isFocused)
    }

    // Opacity: full when active or focused, reduced when inactive
    val alpha = when {
        isFocused -> 1f
        isActive -> 0.9f
        else -> 0.4f
    }

    // Fixed-size box that never changes - button is always in the same position
    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        // The button image - fixed position
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            modifier = Modifier
                .fillMaxSize()
                .alpha(alpha)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusProperties {
                    if (focusUp != null) up = focusUp
                    if (focusDown != null) down = focusDown
                    if (focusLeft != null) left = focusLeft
                    if (focusRight != null) right = focusRight
                }
                .focusable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onClick() }
                .then(
                    if (isFocused) {
                        Modifier.border(3.dp, Color.White, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    }
                )
        )

        // Tooltip - positioned outside the box layout using wrapContentSize(unbounded = true)
        // This allows it to overflow without affecting the parent's size
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .wrapContentSize(unbounded = true)
                .offset(x = (-8).dp) // Small gap from button edge
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isFocused,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = (-100).dp) // Position text to the left
                        .background(
                            color = Color.Black.copy(alpha = 0.8f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun VideoIssuesPanel(
    onRetryClick: () -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val retryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        retryFocusRequester.requestFocus()
    }

    androidx.compose.material3.Surface(
        modifier = modifier
            .width(400.dp)
            .fillMaxHeight()
            .padding(
                start = 0.dp,
                top = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding,
                end = com.duckflix.lite.ui.components.TVSafeArea.HorizontalPadding,
                bottom = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding
            )
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp &&
                    (keyEvent.key == Key.Back || keyEvent.key == Key.Escape)) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Video Issues?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "If you're experiencing buffering, playback errors, or quality issues, we can try finding an alternative source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.weight(1f))

            com.duckflix.lite.ui.components.FocusableButton(
                onClick = onRetryClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(retryFocusRequester),
                enabled = !isLoading
            ) {
                Text(
                    text = if (isLoading) "Searching..." else "Retry with Different Source",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            com.duckflix.lite.ui.components.FocusableButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun AudioPanel(
    audioTracks: List<TrackInfo>,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstTrackFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        firstTrackFocusRequester.requestFocus()
    }

    androidx.compose.material3.Surface(
        modifier = modifier
            .width(400.dp)
            .fillMaxHeight()
            .padding(
                start = 0.dp,
                top = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding,
                end = com.duckflix.lite.ui.components.TVSafeArea.HorizontalPadding,
                bottom = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding
            )
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp &&
                    (keyEvent.key == Key.Back || keyEvent.key == Key.Escape)) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Audio",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            audioTracks.forEachIndexed { index, track ->
                com.duckflix.lite.ui.components.FocusableButton(
                    onClick = {
                        onTrackSelected(track.id)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstTrackFocusRequester) else Modifier)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = track.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (track.isSelected) {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitlePanel(
    subtitleTracks: List<TrackInfo>,
    onTrackSelected: (String) -> Unit,
    onForceEnglish: () -> Unit,
    isForceEnglishLoading: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstTrackFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        firstTrackFocusRequester.requestFocus()
    }

    androidx.compose.material3.Surface(
        modifier = modifier
            .width(400.dp)
            .fillMaxHeight()
            .padding(
                start = 0.dp,
                top = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding,
                end = com.duckflix.lite.ui.components.TVSafeArea.HorizontalPadding,
                bottom = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding
            )
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp &&
                    (keyEvent.key == Key.Back || keyEvent.key == Key.Escape)) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // "None" option
            com.duckflix.lite.ui.components.FocusableButton(
                onClick = {
                    onTrackSelected("none")
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(firstTrackFocusRequester)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (subtitleTracks.none { it.isSelected }) {
                        Text(
                            text = "✓",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Subtitle tracks
            subtitleTracks.forEach { track ->
                com.duckflix.lite.ui.components.FocusableButton(
                    onClick = {
                        onTrackSelected(track.id)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = track.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (track.isSelected) {
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Divider
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            )

            // Force English button
            com.duckflix.lite.ui.components.FocusableButton(
                onClick = onForceEnglish,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isForceEnglishLoading
            ) {
                Text(
                    text = if (isForceEnglishLoading) "Downloading..." else "Force English",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}


@Composable
private fun SeriesCompleteOverlay(
    onDismiss: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    // Request focus when overlay appears
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .focusable(), // Trap focus within overlay
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .width(500.dp)
                .padding(48.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "Series Complete",
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = "You've reached the end of this series",
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.duckflix.lite.ui.components.FocusableButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.focusRequester(focusRequester)
                    ) {
                        Text("Back to Series")
                    }
                    com.duckflix.lite.ui.components.FocusableButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun RandomEpisodeErrorOverlay(
    onNavigateBack: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    // Request focus when overlay appears
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .focusable(), // Trap focus within overlay
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .width(500.dp)
                .padding(48.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "Failed to Fetch Random Episode",
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = "Please try again later",
                    style = MaterialTheme.typography.bodyLarge
                )
                com.duckflix.lite.ui.components.FocusableButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.focusRequester(focusRequester)
                ) {
                    Text("Home")
                }
            }
        }
    }
}

@Composable
private fun NextEpisodeOverlay(
    nextEpisode: com.duckflix.lite.data.remote.dto.NextEpisodeResponse,
    countdown: Int,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(48.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Next Episode",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "S${nextEpisode.season}E${nextEpisode.episode}: ${nextEpisode.title}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Starting in $countdown seconds...",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                com.duckflix.lite.ui.components.FocusableButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun RecommendationsOverlay(
    recommendations: List<com.duckflix.lite.data.remote.dto.MovieRecommendationItem>,
    selectedRecommendation: com.duckflix.lite.data.remote.dto.MovieRecommendationItem?,
    countdown: Int,
    onSelectRecommendation: (com.duckflix.lite.data.remote.dto.MovieRecommendationItem) -> Unit,
    onCancel: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .widthIn(max = 800.dp)
                .padding(48.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = if (countdown > 0) "Playing next in $countdown..." else "Recommended",
                    style = MaterialTheme.typography.headlineMedium
                )

                // 2x2 grid of recommendation cards
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    recommendations.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { rec ->
                                com.duckflix.lite.ui.components.FocusableCard(
                                    onClick = { onSelectRecommendation(rec) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(220.dp)
                                ) {
                                    Column {
                                        coil.compose.AsyncImage(
                                            model = rec.posterUrl,
                                            contentDescription = rec.title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(160.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = rec.title,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            if (rec.tmdbId == selectedRecommendation?.tmdbId) {
                                                Text(
                                                    text = "✓ Selected",
                                                    color = MaterialTheme.colorScheme.primary,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.duckflix.lite.ui.components.FocusableButton(onClick = onNavigateBack) {
                        Text("Back")
                    }
                    com.duckflix.lite.ui.components.FocusableButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/**
 * Up Next overlay that shows in the last 5% of video playback.
 * Non-blocking overlay with auto-focused "Play Now" button.
 * Does not auto-hide, user must interact or wait for video to end.
 */
@Composable
private fun UpNextOverlay(
    nextEpisode: com.duckflix.lite.data.remote.dto.NextEpisodeResponse,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val playNowFocusRequester = remember { FocusRequester() }

    // Auto-focus the Play Now button when overlay appears
    LaunchedEffect(Unit) {
        playNowFocusRequester.requestFocus()
    }

    // Handle back button to dismiss
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.BottomEnd
    ) {
        // Positioned at bottom-right with padding
        androidx.compose.material3.Surface(
            modifier = Modifier
                .padding(32.dp)
                .widthIn(max = 400.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.9f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Text(
                    text = "Up Next",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                // Episode info
                Text(
                    text = "S${nextEpisode.season}E${nextEpisode.episode}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                if (!nextEpisode.title.isNullOrBlank()) {
                    Text(
                        text = nextEpisode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                // Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    com.duckflix.lite.ui.components.FocusableButton(
                        onClick = onPlayNow,
                        modifier = Modifier.focusRequester(playNowFocusRequester)
                    ) {
                        Text("Play Now")
                    }
                    com.duckflix.lite.ui.components.FocusableButton(
                        onClick = onDismiss
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

/**
 * Skip buttons overlay for intro, recap, and credits.
 * Positioned at bottom-right but left of the timestamp, above transport controls.
 */
@Composable
private fun SkipButtonsOverlay(
    showIntro: Boolean,
    showRecap: Boolean,
    showCredits: Boolean,
    onSkipIntro: () -> Unit,
    onSkipRecap: () -> Unit,
    onSkipCredits: () -> Unit
) {
    // Focus requesters for each button
    val introFocusRequester = remember { FocusRequester() }
    val recapFocusRequester = remember { FocusRequester() }
    val creditsFocusRequester = remember { FocusRequester() }

    // Request focus when buttons become visible (with small delay to ensure composable is ready)
    LaunchedEffect(showIntro) {
        if (showIntro) {
            delay(100)
            try {
                introFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // Focus requester not attached yet
            }
        }
    }

    LaunchedEffect(showRecap) {
        if (showRecap) {
            delay(100)
            try {
                recapFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // Focus requester not attached yet
            }
        }
    }

    LaunchedEffect(showCredits) {
        if (showCredits) {
            delay(100)
            try {
                creditsFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // Focus requester not attached yet
            }
        }
    }

    // Position: bottom of screen, right side but with enough margin to not overlap timestamp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 110.dp, end = 180.dp), // Above controls, left of timestamp
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Intro skip button
            androidx.compose.animation.AnimatedVisibility(
                visible = showIntro,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(200)
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(200)
                )
            ) {
                SkipButton(
                    text = "Skip Intro",
                    onClick = onSkipIntro,
                    focusRequester = introFocusRequester
                )
            }

            // Recap skip button
            androidx.compose.animation.AnimatedVisibility(
                visible = showRecap,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(200)
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(200)
                )
            ) {
                SkipButton(
                    text = "Skip Recap",
                    onClick = onSkipRecap,
                    focusRequester = recapFocusRequester
                )
            }

            // Credits skip button
            androidx.compose.animation.AnimatedVisibility(
                visible = showCredits,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(200)
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(200)
                )
            ) {
                SkipButton(
                    text = "Skip Credits",
                    onClick = onSkipCredits,
                    focusRequester = creditsFocusRequester
                )
            }
        }
    }
}

/**
 * Individual skip button using FocusableButton for consistent TV behavior.
 */
@Composable
private fun SkipButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester
) {
    com.duckflix.lite.ui.components.FocusableButton(
        onClick = onClick,
        focusRequester = focusRequester
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text)
            Text("»", style = MaterialTheme.typography.titleMedium)
        }
    }
}
