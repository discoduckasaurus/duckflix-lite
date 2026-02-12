package com.duckflix.lite.ui.components.livetv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.duckflix.lite.data.remote.dto.LiveTvChannel

/**
 * Channel row showing logo and name (clickable to go fullscreen)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    channel: LiveTvChannel,
    isSelected: Boolean,
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    displayNumber: Int? = null,  // Position in list (1-based), overrides channel.channelNumber
    requestInitialFocus: Boolean = false,  // If true, this row will request focus on compose
    focusTrigger: Int = 0  // Changes when returning from fullscreen to re-trigger focus
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    // Create local focus requester if we need to request initial focus
    val localFocusRequester = remember { FocusRequester() }
    val effectiveFocusRequester = focusRequester ?: localFocusRequester

    // Scroll into view when focused
    LaunchedEffect(isFocused) {
        if (isFocused) {
            bringIntoViewRequester.bringIntoView()
        }
    }

    // Request focus when this row should have initial focus
    // Use focusTrigger as key to re-trigger focus when returning from fullscreen
    LaunchedEffect(requestInitialFocus, focusTrigger) {
        if (requestInitialFocus) {
            kotlinx.coroutines.delay(100)
            try {
                effectiveFocusRequester.requestFocus()
                println("[ChannelRow] Requested focus for ${channel.effectiveDisplayName} (trigger=$focusTrigger)")
            } catch (e: Exception) {
                println("[ChannelRow] Focus request failed: ${e.message}")
            }
        }
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .height(height)
            .bringIntoViewRequester(bringIntoViewRequester)
            .focusRequester(effectiveFocusRequester)
            .focusable(interactionSource = interactionSource),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(4.dp),
        border = when {
            isFocused -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isSelected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            else -> null
        },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isFocused -> Color(0xFF2A2A2A)
                isSelected -> Color(0xFF252525)
                else -> Color(0xFF1E1E1E)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Channel number - use displayNumber (position) if provided, otherwise fall back to channel.channelNumber
            val numberToShow = displayNumber ?: channel.channelNumber
            numberToShow?.let { number ->
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.width(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Channel logo
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF333333)),
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
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    // Fallback: First letter of channel name
                    Text(
                        text = channel.effectiveDisplayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Channel name
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = channel.effectiveDisplayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected || isFocused) Color.White else Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Group (if available)
                channel.group?.let { group ->
                    Text(
                        text = group,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
