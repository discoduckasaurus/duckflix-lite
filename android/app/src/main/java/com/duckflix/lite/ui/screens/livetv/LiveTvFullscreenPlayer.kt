package com.duckflix.lite.ui.screens.livetv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duckflix.lite.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.duckflix.lite.data.remote.dto.LiveTvChannel
import com.duckflix.lite.ui.components.CircularBackButton
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fullscreen Live TV player with channel info overlay
 */
@Composable
fun LiveTvFullscreenPlayer(
    player: ExoPlayer?,
    channel: LiveTvChannel?,
    isPlaying: Boolean,
    audioTracks: List<LiveTvTrackInfo>,
    subtitleTracks: List<LiveTvTrackInfo>,
    showAudioPanel: Boolean,
    showSubtitlePanel: Boolean,
    error: String?,
    isRecovering: Boolean,
    onBack: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleAudioPanel: () -> Unit,
    onToggleSubtitlePanel: () -> Unit,
    onSelectAudioTrack: (String) -> Unit,
    onSelectSubtitleTrack: (String) -> Unit,
    onDismissPanels: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val audioButtonFocusRequester = remember { FocusRequester() }
    val ccButtonFocusRequester = remember { FocusRequester() }
    var showControls by remember { mutableStateOf(false) }
    var activityCounter by remember { mutableStateOf(0) }

    // Channel info popup state (shown briefly when changing channels)
    var showChannelPopup by remember { mutableStateOf(false) }
    var channelPopupCounter by remember { mutableStateOf(0) }

    // Request focus when entering fullscreen
    LaunchedEffect(Unit) {
        delay(100)
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
            // Focus system not ready
        }
    }

    // Auto-hide controls after 5 seconds and recapture focus to main container
    LaunchedEffect(showControls, activityCounter) {
        if (showControls) {
            delay(5000)
            showControls = false
            // Recapture focus to main container so we can handle keys when overlay is hidden
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Focus system not ready
            }
        }
    }

    // Focus play/pause button when controls become visible
    LaunchedEffect(showControls) {
        if (showControls && !showAudioPanel && !showSubtitlePanel) {
            delay(100)
            try {
                playPauseFocusRequester.requestFocus()
            } catch (e: Exception) {
                // Focus system not ready
            }
        }
    }

    // Show channel popup when channel changes (but not on initial load)
    LaunchedEffect(channel?.id) {
        if (channel != null) {
            showChannelPopup = true
            channelPopupCounter++
        }
    }

    // Auto-hide channel popup after 5 seconds
    LaunchedEffect(showChannelPopup, channelPopupCounter) {
        if (showChannelPopup) {
            delay(5000)
            showChannelPopup = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            // Use onPreviewKeyEvent to intercept keys BEFORE they reach children
            // This is critical for handling directional keys when overlay is hidden
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp) {
                    when (keyEvent.key) {
                        Key.Back, Key.Escape -> {
                            // Priority: dismiss panels first, then hide controls, then exit
                            when {
                                showAudioPanel || showSubtitlePanel -> {
                                    onDismissPanels()
                                    true
                                }
                                showControls -> {
                                    showControls = false
                                    // Recapture focus to main container
                                    try { focusRequester.requestFocus() } catch (_: Exception) {}
                                    true
                                }
                                else -> {
                                    onBack()
                                    true
                                }
                            }
                        }
                        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                            if (!showControls) {
                                // Overlay hidden: show overlay AND toggle play/pause
                                onTogglePlayPause()
                                showControls = true
                                activityCounter++
                                true
                            } else {
                                // Overlay visible: let focus system handle button clicks
                                false
                            }
                        }
                        Key.DirectionUp -> {
                            if (!showControls) {
                                // OVERLAY HIDDEN: change channel up (don't show overlay)
                                onChannelUp()
                                true // Consume event
                            } else {
                                // Overlay visible: let focus system navigate
                                activityCounter++
                                false
                            }
                        }
                        Key.ChannelUp -> {
                            // Channel buttons always change channel (don't show overlay)
                            onChannelUp()
                            true
                        }
                        Key.DirectionDown -> {
                            if (!showControls) {
                                // OVERLAY HIDDEN: change channel down (don't show overlay)
                                onChannelDown()
                                true // Consume event
                            } else {
                                // Overlay visible: let focus system navigate
                                activityCounter++
                                false
                            }
                        }
                        Key.ChannelDown -> {
                            // Channel buttons always change channel (don't show overlay)
                            onChannelDown()
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (!showControls) {
                                // OVERLAY HIDDEN: just show overlay, don't pause or change channel
                                showControls = true
                                activityCounter++
                                true // Consume event
                            } else {
                                // Overlay visible: let focus system navigate
                                activityCounter++
                                false
                            }
                        }
                        // Media remote control buttons
                        Key.MediaPlayPause -> {
                            onTogglePlayPause()
                            true
                        }
                        Key.MediaPlay -> {
                            player?.play()
                            true
                        }
                        Key.MediaPause -> {
                            player?.pause()
                            true
                        }
                        Key.MediaNext -> {
                            onChannelUp()
                            true
                        }
                        Key.MediaPrevious -> {
                            onChannelDown()
                            true
                        }
                        else -> {
                            // Any other key shows controls if hidden
                            if (!showControls) {
                                showControls = true
                                activityCounter++
                                true
                            } else {
                                activityCounter++
                                false
                            }
                        }
                    }
                } else {
                    // Also handle KeyDown for direction keys when overlay hidden to prevent any default behavior
                    if (keyEvent.type == KeyEventType.KeyDown && !showControls) {
                        when (keyEvent.key) {
                            Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                            else -> false
                        }
                    } else {
                        false
                    }
                }
            }
            .focusable()
    ) {
        // Video player with subtitles
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                        setKeepContentOnPlayerReset(true)  // Keep last frame during recovery
                        // Configure subtitle view with visible styling
                        subtitleView?.apply {
                            setApplyEmbeddedStyles(true)
                            setApplyEmbeddedFontSizes(true)
                            // Set a visible caption style
                            setStyle(
                                CaptionStyleCompat(
                                    android.graphics.Color.WHITE,                    // foreground
                                    android.graphics.Color.argb(180, 0, 0, 0),       // background (semi-transparent black)
                                    android.graphics.Color.TRANSPARENT,              // window
                                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,            // edge type
                                    android.graphics.Color.BLACK,                    // edge color
                                    null                                             // typeface
                                )
                            )
                            setFractionalTextSize(0.0533f) // Default subtitle size
                            bringToFront()
                        }
                    }
                },
                update = { view ->
                    view.player = player
                    view.subtitleView?.bringToFront()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
            )
        }

        // Controls overlay with channel info
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FullscreenOverlay(
                channel = channel,
                isPlaying = isPlaying,
                showAudioPanel = showAudioPanel,
                showSubtitlePanel = showSubtitlePanel,
                onBack = onBack,
                onTogglePlayPause = onTogglePlayPause,
                onToggleAudioPanel = onToggleAudioPanel,
                onToggleSubtitlePanel = onToggleSubtitlePanel,
                playPauseFocusRequester = playPauseFocusRequester,
                audioButtonFocusRequester = audioButtonFocusRequester,
                ccButtonFocusRequester = ccButtonFocusRequester,
                onUserActivity = { activityCounter++ }
            )
        }

        // Audio track selection panel
        AnimatedVisibility(
            visible = showAudioPanel,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            TrackSelectionPanel(
                title = "Audio Track",
                tracks = audioTracks,
                onTrackSelected = { onSelectAudioTrack(it); onDismissPanels() },
                onDismiss = onDismissPanels
            )
        }

        // Subtitle selection panel
        AnimatedVisibility(
            visible = showSubtitlePanel,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            TrackSelectionPanel(
                title = "Subtitles",
                tracks = subtitleTracks,
                showOffOption = true,
                onTrackSelected = { onSelectSubtitleTrack(it); onDismissPanels() },
                onDismiss = onDismissPanels
            )
        }

        // Stream error/recovery overlay
        AnimatedVisibility(
            visible = error != null || isRecovering,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            StreamErrorOverlay(
                error = error,
                isRecovering = isRecovering,
                onRetry = onRetry
            )
        }

        // Channel info popup (shown when changing channels without overlay)
        AnimatedVisibility(
            visible = showChannelPopup && !showControls && channel != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            channel?.let { ch ->
                ChannelInfoPopup(channel = ch)
            }
        }
    }
}

@Composable
private fun FullscreenOverlay(
    channel: LiveTvChannel?,
    isPlaying: Boolean,
    showAudioPanel: Boolean,
    showSubtitlePanel: Boolean,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleAudioPanel: () -> Unit,
    onToggleSubtitlePanel: () -> Unit,
    playPauseFocusRequester: FocusRequester,
    audioButtonFocusRequester: FocusRequester,
    ccButtonFocusRequester: FocusRequester,
    onUserActivity: () -> Unit
) {
    val isPanelOpen = showAudioPanel || showSubtitlePanel
    val controlsAlpha = if (isPanelOpen) 0.3f else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.7f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.8f)
                    )
                )
            )
    ) {
        // Top bar with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .align(Alignment.TopStart)
                .alpha(controlsAlpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularBackButton(onClick = onBack)

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = "LIVE TV",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        // Center play/pause button
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(controlsAlpha)
        ) {
            val playPauseInteractionSource = remember { MutableInteractionSource() }
            val isPlayPauseFocused by playPauseInteractionSource.collectIsFocusedAsState()

            Image(
                painter = painterResource(
                    id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                ),
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier
                    .size(96.dp)
                    .focusRequester(playPauseFocusRequester)
                    .focusProperties {
                        right = audioButtonFocusRequester
                    }
                    .focusable(interactionSource = playPauseInteractionSource)
                    .clickable(
                        interactionSource = playPauseInteractionSource,
                        indication = null
                    ) {
                        onTogglePlayPause()
                        onUserActivity()
                    }
                    .then(if (isPlayPauseFocused) Modifier.border(4.dp, Color.White, CircleShape) else Modifier)
            )
        }

        // Right side control buttons (Audio, CC)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
                .alpha(controlsAlpha),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LiveTvIconButton(
                iconRes = R.drawable.df_audio_track,
                label = "Audio",
                onClick = {
                    onToggleAudioPanel()
                    onUserActivity()
                },
                focusRequester = audioButtonFocusRequester,
                focusUp = null,
                focusDown = ccButtonFocusRequester,
                focusLeft = playPauseFocusRequester
            )
            LiveTvIconButton(
                iconRes = R.drawable.df_subs,
                label = "Subtitles",
                onClick = {
                    onToggleSubtitlePanel()
                    onUserActivity()
                },
                focusRequester = ccButtonFocusRequester,
                focusUp = audioButtonFocusRequester,
                focusDown = null,
                focusLeft = playPauseFocusRequester
            )
        }

        // Bottom info panel
        if (channel != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Channel logo
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (channel.logoUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(channel.logoUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = channel.effectiveDisplayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(56.dp)
                            )
                        } else {
                            Text(
                                text = channel.effectiveDisplayName.take(2).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        // Channel name
                        Text(
                            text = channel.effectiveDisplayName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )

                        // Current program
                        channel.currentProgram?.let { program ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = program.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Time info
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${formatTime(program.start)} - ${formatTime(program.stop)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.6f)
                                )

                                program.category?.let { category ->
                                    Text(
                                        text = category,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Progress bar
                            if (program.isCurrentlyAiring) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { program.progressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = Color.White.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }

                    // Channel number badge (if available)
                    channel.channelNumber?.let { number ->
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = number.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Control hints
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    ControlHint(icon = "^/v", text = "Channel")
                    ControlHint(icon = "OK", text = "Pause")
                    ControlHint(icon = "<", text = "Back to Guide")
                }
            }
        }
    }
}

@Composable
private fun StreamErrorOverlay(
    error: String?,
    isRecovering: Boolean,
    onRetry: () -> Unit
) {
    val retryFocusRequester = remember { FocusRequester() }

    // Auto-focus the retry button when showing error
    LaunchedEffect(error) {
        if (error != null && !isRecovering) {
            delay(100)
            try {
                retryFocusRequester.requestFocus()
            } catch (e: Exception) {
                // Focus not ready
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isRecovering) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "Reconnecting...",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            } else {
                // Error icon (warning triangle using text)
                Text(
                    text = "⚠",
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White
                )
                Text(
                    text = error ?: "Stream error",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Focusable retry button
                RetryButton(
                    onClick = onRetry,
                    focusRequester = retryFocusRequester
                )
            }
        }
    }
}

@Composable
private fun RetryButton(
    onClick: () -> Unit,
    focusRequester: FocusRequester
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .background(
                if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f),
                RoundedCornerShape(8.dp)
            )
            .then(
                if (isFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
            .padding(horizontal = 32.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Retry",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
    }
}

@Composable
private fun ControlHint(
    icon: String,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date(timestamp * 1000))
}

/**
 * Compact channel info popup shown when changing channels
 */
@Composable
private fun ChannelInfoPopup(
    channel: LiveTvChannel
) {
    Box(
        modifier = Modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Channel logo
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(channel.logoUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = channel.effectiveDisplayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(48.dp)
                    )
                } else {
                    Text(
                        text = channel.effectiveDisplayName.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier.width(280.dp)
            ) {
                // Channel name with optional number
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    channel.channelNumber?.let { number ->
                        Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = channel.effectiveDisplayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Current program info
                channel.currentProgram?.let { program ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Description (if available)
                    program.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Time and progress
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "${formatTime(program.start)} - ${formatTime(program.stop)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.5f)
                        )

                        if (program.isCurrentlyAiring) {
                            LinearProgressIndicator(
                                progress = { program.progressPercent / 100f },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.White.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTvIconButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    focusUp: FocusRequester?,
    focusDown: FocusRequester?,
    focusLeft: FocusRequester?
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            modifier = Modifier
                .size(48.dp)
                .focusRequester(focusRequester)
                .focusProperties {
                    if (focusUp != null) up = focusUp
                    if (focusDown != null) down = focusDown
                    if (focusLeft != null) left = focusLeft
                }
                .focusable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onClick() }
                .then(
                    if (isFocused) Modifier.border(3.dp, Color.White, RoundedCornerShape(12.dp))
                    else Modifier
                )
        )
        if (isFocused) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun TrackSelectionPanel(
    title: String,
    tracks: List<LiveTvTrackInfo>,
    showOffOption: Boolean = false,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        try {
            firstItemFocusRequester.requestFocus()
        } catch (e: Exception) {
            // Focus not ready
        }
    }

    Surface(
        modifier = Modifier
            .width(350.dp)
            .fillMaxHeight()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp &&
                    (keyEvent.key == Key.Back || keyEvent.key == Key.Escape)
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        color = Color(0xFF1A1A1A),
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showOffOption) {
                    item {
                        TrackItem(
                            label = "Off",
                            isSelected = tracks.none { it.isSelected },
                            onClick = { onTrackSelected("off") },
                            focusRequester = firstItemFocusRequester
                        )
                    }
                }

                items(tracks) { track ->
                    TrackItem(
                        label = track.label,
                        isSelected = track.isSelected,
                        onClick = { onTrackSelected(track.id) },
                        focusRequester = if (!showOffOption && tracks.indexOf(track) == 0) firstItemFocusRequester else null
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .background(
                when {
                    isFocused -> Color.White.copy(alpha = 0.2f)
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(8.dp)
            )
            .then(
                if (isFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                else Modifier
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
        )
        if (isSelected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
