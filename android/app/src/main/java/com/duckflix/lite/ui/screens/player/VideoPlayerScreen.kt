package com.duckflix.lite.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.LoadingIndicator
import com.duckflix.lite.ui.components.SourceSelectionScreen
import kotlinx.coroutines.delay

@Composable
fun VideoPlayerScreen(
    onNavigateBack: () -> Unit,
    viewModel: VideoPlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val backgroundFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    // Request focus on background only when controls are hidden
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

    // Auto-hide controls after 5 seconds of inactivity
    LaunchedEffect(uiState.showControls, uiState.isPlaying) {
        if (uiState.showControls && uiState.isPlaying) {
            delay(5000)
            viewModel.hideControls()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                // Only handle key events when controls are hidden
                if (!uiState.showControls) {
                    Modifier
                        .focusRequester(backgroundFocusRequester)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyUp) {
                                when (keyEvent.key) {
                                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                                        viewModel.togglePlayPause()
                                        viewModel.showControls()
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        viewModel.seekForward()
                                        viewModel.showControls()
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        viewModel.seekBackward()
                                        viewModel.showControls()
                                        true
                                    }
                                    Key.DirectionUp, Key.DirectionDown -> {
                                        viewModel.showControls()
                                        true
                                    }
                                    Key.Back, Key.Escape -> {
                                        onNavigateBack()
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                        .focusable()
                } else {
                    Modifier
                }
            )
    ) {
        when {
            uiState.error != null -> {
                ErrorScreen(
                    message = uiState.error ?: "Unknown error",
                    onRetry = viewModel::retryPlayback
                )
            }

            viewModel.player != null -> {
                // ExoPlayer view
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = viewModel.player
                            useController = false // Use custom controls
                            keepScreenOn = true
                        }
                    },
                    update = { view ->
                        // Ensure player is attached when view updates
                        view.player = viewModel.player
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Custom controls overlay
                if (uiState.showControls) {
                    PlayerControls(
                        isPlaying = uiState.isPlaying,
                        isLoading = uiState.isLoading,
                        currentPosition = uiState.currentPosition,
                        duration = uiState.duration,
                        isAutoPlayEnabled = uiState.autoPlayEnabled,
                        onPlayPauseClick = viewModel::togglePlayPause,
                        onSeekForward = viewModel::seekForward,
                        onSeekBackward = viewModel::seekBackward,
                        onSeekTo = viewModel::seekTo,
                        onBackClick = onNavigateBack,
                        onShowTracks = viewModel::showTrackSelection,
                        onToggleAutoPlay = viewModel::toggleAutoPlay,
                        onHideControls = viewModel::hideControls
                    )
                }

                // Track selection dialog
                if (uiState.showTrackSelection) {
                    TrackSelectionDialog(
                        audioTracks = uiState.audioTracks,
                        subtitleTracks = uiState.subtitleTracks,
                        onAudioTrackSelected = viewModel::selectAudioTrack,
                        onSubtitleTrackSelected = viewModel::selectSubtitleTrack,
                        onDismiss = viewModel::hideTrackSelection
                    )
                }

                // Restore focus when dialog closes
                LaunchedEffect(uiState.showTrackSelection) {
                    if (!uiState.showTrackSelection) {
                        delay(100)
                        try {
                            if (uiState.showControls) {
                                // Controls are visible, focus will handle itself
                            } else {
                                backgroundFocusRequester.requestFocus()
                            }
                        } catch (e: IllegalArgumentException) {
                            // Focus system not ready, ignore
                        }
                    }
                }

                // Series Complete Overlay
                if (uiState.showSeriesCompleteOverlay) {
                    SeriesCompleteOverlay(
                        onDismiss = viewModel::dismissSeriesComplete,
                        onNavigateBack = onNavigateBack
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

                // Next Episode Countdown
                if (uiState.autoPlayCountdown > 0 && uiState.nextEpisodeInfo != null) {
                    NextEpisodeOverlay(
                        nextEpisode = uiState.nextEpisodeInfo!!,
                        countdown = uiState.autoPlayCountdown,
                        onCancel = viewModel::cancelAutoPlay
                    )
                }
            }
        }

        // Loading/Download progress overlay
        when {
            uiState.loadingPhase == LoadingPhase.CHECKING_CACHE -> {
                // Show slot machine animation for Zurg cache check (no extra messaging)
                SourceSelectionScreen(
                    message = "",
                    showProgress = false,
                    logoUrl = uiState.logoUrl,
                    backdropUrl = uiState.posterUrl,
                    onCancel = {
                        viewModel.cancelDownload()
                        onNavigateBack()
                    }
                )
            }

            uiState.loadingPhase == LoadingPhase.SEARCHING -> {
                // Show slot machine animation with Prowlarr search message
                SourceSelectionScreen(
                    message = "One moment, finding the best source",
                    showProgress = false,
                    logoUrl = uiState.logoUrl,
                    backdropUrl = uiState.posterUrl,
                    onCancel = {
                        viewModel.cancelDownload()
                        onNavigateBack()
                    }
                )
            }

            uiState.loadingPhase == LoadingPhase.DOWNLOADING -> {
                // Show slot machine animation WITH progress bar
                SourceSelectionScreen(
                    message = uiState.downloadMessage,
                    showProgress = true,
                    progress = uiState.downloadProgress,
                    logoUrl = uiState.logoUrl,
                    backdropUrl = uiState.posterUrl,
                    onCancel = {
                        viewModel.cancelDownload()
                        onNavigateBack()
                    },
                    onComeBackLater = {
                        // Don't cancel download, just go back
                        onNavigateBack()
                    }
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    currentPosition: Long,
    duration: Long,
    isAutoPlayEnabled: Boolean,
    onPlayPauseClick: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onBackClick: () -> Unit,
    onShowTracks: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onHideControls: () -> Unit
) {
    val playPauseFocusRequester = remember { FocusRequester() }

    // Request focus on play/pause button when controls appear
    LaunchedEffect(Unit) {
        delay(100)
        playPauseFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .onKeyEvent { keyEvent ->
                // Handle Back key to hide controls
                if (keyEvent.type == KeyEventType.KeyUp &&
                    (keyEvent.key == Key.Back || keyEvent.key == Key.Escape)) {
                    onHideControls()
                    true
                } else {
                    false
                }
            }
    ) {
        // Top bar with back button and track selection
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = com.duckflix.lite.ui.components.TVSafeArea.HorizontalPadding,
                    vertical = com.duckflix.lite.ui.components.TVSafeArea.VerticalPadding
                )
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.duckflix.lite.ui.components.FocusableButton(
                onClick = onBackClick,
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = "◀ Back",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }

            com.duckflix.lite.ui.components.FocusableButton(
                onClick = onShowTracks,
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = "Audio & Subs",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }

        // Center playback control (with top padding to avoid top bar)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 80.dp)
        ) {
            // Play/Pause button
            com.duckflix.lite.ui.components.FocusableButton(
                onClick = onPlayPauseClick,
                enabled = !isLoading,
                modifier = Modifier
                    .size(96.dp)
                    .focusRequester(playPauseFocusRequester)
            ) {
                Text(
                    text = if (isPlaying) "⏸" else "▶",
                    style = MaterialTheme.typography.displayLarge,
                    color = if (isLoading) Color.Gray else Color.White
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
                .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Auto-play toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isAutoPlayEnabled) "Auto-play: ON" else "Auto-play: OFF",
                    color = if (isAutoPlayEnabled) Color(0xFF00FF00) else Color.White.copy(0.7f),
                    style = MaterialTheme.typography.bodyLarge
                )
                com.duckflix.lite.ui.components.FocusableButton(
                    onClick = onToggleAutoPlay,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text(
                        text = if (isAutoPlayEnabled) "⏯ Disable" else "⏯ Enable",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

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
            var previewPosition by remember { mutableStateOf(currentPosition) }
            val sliderInteractionSource = remember { MutableInteractionSource() }

            // Monitor drag interactions
            LaunchedEffect(sliderInteractionSource) {
                sliderInteractionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> {
                            isDragging = true
                        }
                        is DragInteraction.Stop, is DragInteraction.Cancel -> {
                            if (isDragging) {
                                onSeekTo(previewPosition)
                                isDragging = false
                            }
                        }
                    }
                }
            }

            androidx.compose.material3.Slider(
                value = if (isDragging) previewPosition.toFloat() else currentPosition.toFloat(),
                onValueChange = { newValue ->
                    if (isDragging) {
                        previewPosition = newValue.toLong()
                    } else {
                        onSeekTo(newValue.toLong())
                    }
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable(),
                interactionSource = sliderInteractionSource,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
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

@Composable
private fun TrackSelectionDialog(
    audioTracks: List<com.duckflix.lite.ui.screens.player.TrackInfo>,
    subtitleTracks: List<com.duckflix.lite.ui.screens.player.TrackInfo>,
    onAudioTrackSelected: (String) -> Unit,
    onSubtitleTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var audioExpanded by remember { mutableStateOf(false) }
    var subtitleExpanded by remember { mutableStateOf(false) }

    val selectedAudio = audioTracks.find { it.isSelected }
    val selectedSubtitle = subtitleTracks.find { it.isSelected }

    val audioFocusRequester = remember { FocusRequester() }
    val subtitleFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }

    // Request initial focus on the first available dropdown
    LaunchedEffect(Unit) {
        delay(100)
        try {
            if (audioTracks.isNotEmpty()) {
                audioFocusRequester.requestFocus()
            } else {
                subtitleFocusRequester.requestFocus()
            }
        } catch (e: IllegalArgumentException) {
            // Focus system not ready or dialog closing, ignore
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 400.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "Audio & Subtitles",
                    style = MaterialTheme.typography.headlineMedium
                )

                // Audio track dropdown
                if (audioTracks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Audio Track",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box {
                            com.duckflix.lite.ui.components.FocusableButton(
                                onClick = { audioExpanded = !audioExpanded },
                                modifier = Modifier
                                    .width(260.dp)
                                    .height(48.dp)
                                    .focusRequester(audioFocusRequester)
                                    .focusProperties {
                                        down = subtitleFocusRequester
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedAudio?.label ?: "Default",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (audioExpanded) "▲" else "▼",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = audioExpanded,
                                onDismissRequest = { audioExpanded = false },
                                modifier = Modifier.width(260.dp)
                            ) {
                                audioTracks.forEach { track ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = track.label,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (track.isSelected) {
                                                    Text(
                                                        text = " ✓",
                                                        color = MaterialTheme.colorScheme.primary,
                                                        style = MaterialTheme.typography.bodyLarge
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onAudioTrackSelected(track.id)
                                            audioExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Subtitle track dropdown
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Subtitles",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box {
                        com.duckflix.lite.ui.components.FocusableButton(
                            onClick = { subtitleExpanded = !subtitleExpanded },
                            modifier = Modifier
                                .width(260.dp)
                                .height(48.dp)
                                .focusRequester(subtitleFocusRequester)
                                .focusProperties {
                                    up = if (audioTracks.isNotEmpty()) audioFocusRequester else FocusRequester.Default
                                    down = closeFocusRequester
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedSubtitle?.label ?: "None",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (subtitleExpanded) "▲" else "▼",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = subtitleExpanded,
                            onDismissRequest = { subtitleExpanded = false },
                            modifier = Modifier.width(260.dp)
                        ) {
                            // "None" option to disable subtitles
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "None (Disable Subtitles)",
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (subtitleTracks.none { it.isSelected }) {
                                            Text(
                                                text = " ✓",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onSubtitleTrackSelected("none")
                                    subtitleExpanded = false
                                }
                            )

                            subtitleTracks.forEach { track ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = track.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (track.isSelected) {
                                                Text(
                                                    text = " ✓",
                                                    color = MaterialTheme.colorScheme.primary,
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        onSubtitleTrackSelected(track.id)
                                        subtitleExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Close button
                com.duckflix.lite.ui.components.FocusableButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(closeFocusRequester)
                        .focusProperties {
                            up = subtitleFocusRequester
                        }
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun SeriesCompleteOverlay(
    onDismiss: () -> Unit,
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
                    com.duckflix.lite.ui.components.FocusableButton(onClick = onNavigateBack) {
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
