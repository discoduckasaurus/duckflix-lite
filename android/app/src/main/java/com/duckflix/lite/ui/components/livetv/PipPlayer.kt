package com.duckflix.lite.ui.components.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.duckflix.lite.data.remote.dto.LiveTvChannel

/**
 * Picture-in-Picture style player for Live TV
 * Shows small video preview with channel logo overlay
 */
@Composable
fun PipPlayer(
    player: ExoPlayer?,
    channel: LiveTvChannel?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    ) {
        // Video player
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false  // No controls in PiP mode
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    view.player = player
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Loading indicator overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Channel logo overlay (bottom-right)
        channel?.logoUrl?.let { logoUrl ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(logoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = channel.effectiveDisplayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(48.dp)
            )
        }
    }
}
