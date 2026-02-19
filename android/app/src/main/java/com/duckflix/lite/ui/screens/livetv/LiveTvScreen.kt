package com.duckflix.lite.ui.screens.livetv

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.LogoButton
import com.duckflix.lite.ui.components.TVSafeArea
import com.duckflix.lite.ui.components.TVSafeContent
import com.duckflix.lite.ui.components.livetv.CurrentProgramInfo
import com.duckflix.lite.ui.theme.Dimens
import com.duckflix.lite.ui.components.livetv.EpgGrid
import com.duckflix.lite.ui.components.livetv.PipPlayer

/**
 * Live TV screen with EPG grid and PiP player
 */
@Composable
fun LiveTvScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDvr: () -> Unit = {},
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Hide keyboard when entering Live TV screen
    LaunchedEffect(Unit) {
        keyboardController?.hide()
    }

    // Note: Focus handling is now done inside EpgGrid/ChannelRow components

    // Debug: Log state changes
    LaunchedEffect(uiState.channels.size, uiState.isLoading, uiState.error) {
        println("[LiveTV-UI] State: channels=${uiState.channels.size}, loading=${uiState.isLoading}, error=${uiState.error}")
    }

    // Handle global key events
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp) {
                    when (keyEvent.key) {
                        Key.Back, Key.Escape -> {
                            if (uiState.isFullscreen) {
                                viewModel.exitFullscreen()
                                true
                            } else {
                                onNavigateBack()
                                true
                            }
                        }
                        Key.ChannelUp -> {
                            viewModel.channelUp()
                            true
                        }
                        Key.ChannelDown -> {
                            viewModel.channelDown()
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
        // Debug: log which branch we're rendering
        println("[LiveTV-UI] when branch check: isFullscreen=${uiState.isFullscreen}, isLoading=${uiState.isLoading}, channels=${uiState.channels.size}, error=${uiState.error}")

        when {
            uiState.isFullscreen -> {
                // Fullscreen player mode
                println("[LiveTV-UI] Rendering FULLSCREEN branch")
                LiveTvFullscreenPlayer(
                    player = viewModel.player,
                    channel = uiState.selectedChannel,
                    isPlaying = uiState.isPlaying,
                    audioTracks = uiState.audioTracks,
                    subtitleTracks = uiState.subtitleTracks,
                    showAudioPanel = uiState.showAudioPanel,
                    showSubtitlePanel = uiState.showSubtitlePanel,
                    error = uiState.error,
                    isRecovering = uiState.isRecovering,
                    onBack = viewModel::exitFullscreen,
                    onChannelUp = viewModel::channelUp,
                    onChannelDown = viewModel::channelDown,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onToggleAudioPanel = viewModel::toggleAudioPanel,
                    onToggleSubtitlePanel = viewModel::toggleSubtitlePanel,
                    onSelectAudioTrack = viewModel::selectAudioTrack,
                    onSelectSubtitleTrack = viewModel::selectSubtitleTrack,
                    onDismissPanels = viewModel::dismissPanels,
                    onRetry = viewModel::refreshStream
                )
            }

            uiState.isLoading && uiState.channels.isEmpty() -> {
                // Initial loading state
                println("[LiveTV-UI] Rendering LOADING branch")
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Loading channels...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            uiState.error != null && uiState.channels.isEmpty() -> {
                // Error state
                println("[LiveTV-UI] Rendering ERROR branch")
                ErrorScreen(
                    message = uiState.error ?: "Failed to load channels",
                    onRetry = viewModel::refreshChannels
                )
            }

            else -> {
                // EPG grid mode
                println("[LiveTV-UI] Rendering EPG grid mode, channels: ${uiState.channels.size}, filtered: ${viewModel.getFilteredChannels().size}")
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header and PiP section with safe padding
                    Column(
                        modifier = Modifier.padding(
                            horizontal = TVSafeArea.HorizontalPadding,
                            vertical = TVSafeArea.VerticalPadding
                        )
                    ) {
                        // Header row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LIVE TV",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White
                            )

                            // DVR button
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFE53935).copy(alpha = 0.15f))
                                    .clickable { onNavigateToDvr() }
                                    .focusable()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "DVR",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFFE53935)
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Logo at top right
                            LogoButton(
                                onClick = onNavigateBack,
                                size = Dimens.tabBarHeight
                            )
                        }

                        // Top section: PiP player + Program info
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // PiP Player (left side)
                            PipPlayer(
                                player = viewModel.player,
                                channel = uiState.selectedChannel,
                                isLoading = uiState.isLoading,
                                modifier = Modifier
                                    .width(320.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )

                            // Current program info (right side)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E1E1E))
                            ) {
                                CurrentProgramInfo(
                                    channel = uiState.selectedChannel
                                )
                            }
                        }
                    }

                    // EPG Grid - stretches to screen edges (no safe padding)
                    EpgGrid(
                            channels = viewModel.getFilteredChannels(),
                            selectedChannel = uiState.selectedChannel,
                            epgStartTime = uiState.epgStartTime,
                            epgEndTime = uiState.epgEndTime,
                            currentTime = uiState.currentTime,
                            onChannelClick = { channel ->
                                viewModel.selectChannel(channel)
                                viewModel.goFullscreen()
                            },
                            onProgramClick = { channel, program ->
                                if (program.isCurrentlyAiring) {
                                    viewModel.recordNow(channel, program)
                                    Toast.makeText(context, "Recording: ${program.title}", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.scheduleRecording(channel, program)
                                    Toast.makeText(context, "Scheduled: ${program.title}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            focusTrigger = uiState.focusTrigger
                        )
                    }
            }
        }

        // Error toast overlay (for non-fatal errors)
        AnimatedVisibility(
            visible = uiState.error != null && uiState.channels.isNotEmpty(),
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFB71C1C))
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}
