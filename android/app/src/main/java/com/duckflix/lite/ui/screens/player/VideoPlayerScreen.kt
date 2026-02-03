package com.duckflix.lite.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
            backgroundFocusRequester.requestFocus()
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
                        onPlayPauseClick = viewModel::togglePlayPause,
                        onSeekForward = viewModel::seekForward,
                        onSeekBackward = viewModel::seekBackward,
                        onSeekTo = viewModel::seekTo,
                        onBackClick = onNavigateBack,
                        onShowTracks = viewModel::showTrackSelection,
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
    onPlayPauseClick: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onBackClick: () -> Unit,
    onShowTracks: () -> Unit,
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
                .padding(horizontal = 48.dp, vertical = 24.dp)
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

        // Center playback control
        Box(
            modifier = Modifier.align(Alignment.Center)
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
                .padding(horizontal = 48.dp, vertical = 32.dp)
                .align(Alignment.BottomCenter),
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

            // Progress bar (focusable slider)
            androidx.compose.material3.Slider(
                value = if (duration > 0) currentPosition.toFloat() else 0f,
                onValueChange = { newValue ->
                    onSeekTo(newValue.toLong())
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable(),
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Text(
                    text = "Audio & Subtitles",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Audio tracks
                if (audioTracks.isNotEmpty()) {
                    Text(
                        text = "Audio Tracks",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    audioTracks.forEach { track ->
                        Text(
                            text = "${if (track.isSelected) "✓ " else "  "}${track.label}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (track.isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Subtitle tracks
                if (subtitleTracks.isNotEmpty()) {
                    Text(
                        text = "Subtitles",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    subtitleTracks.forEach { track ->
                        Text(
                            text = "${if (track.isSelected) "✓ " else "  "}${track.label}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (track.isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                com.duckflix.lite.ui.components.FocusableButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}
