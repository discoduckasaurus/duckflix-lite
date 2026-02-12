package com.duckflix.lite.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.duckflix.lite.ui.theme.tvOsFocusEffects
import com.duckflix.lite.ui.theme.tvOsScaleOnly
import kotlinx.coroutines.launch

/**
 * Card component optimized for TV D-pad navigation
 * Shows focus border when focused
 */
@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable ColumnScope.() -> Unit
) {
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .tvOsFocusEffects(isFocused = isFocused)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .focusable(interactionSource = interactionSource),
        interactionSource = interactionSource,
        border = if (isFocused) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/**
 * Small focusable card for menu items
 */
@Composable
fun MenuCard(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FocusableCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .width(240.dp)
            .height(160.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Media card for poster-style content (no internal padding)
 * Used for movie/TV show poster cards in carousels
 *
 * Features tvOS-style focus effects:
 * - Scales up on focus (glow scales with it)
 * - Colored border matching glow when focused
 * - Built-in glow that extends beyond card bounds
 * - BringIntoViewRequester ensures margin around card is visible when focused
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    borderColor: Color = Color.White,
    glowAlpha: Float = 0.55f,
    glowExtend: Dp = 12.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.() -> Unit
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    // Single box with bringIntoView that requests extra margin when focused
    Box(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onSizeChanged { cardSize = it }
            .onFocusChanged { focusState ->
                if (focusState.hasFocus && cardSize != IntSize.Zero) {
                    coroutineScope.launch {
                        // Request extra space around the card (extends beyond bounds)
                        val marginStart = 32f * 3  // ~32dp in pixels (density ~3)
                        val marginEnd = 48f * 3    // ~48dp in pixels
                        bringIntoViewRequester.bringIntoView(
                            Rect(
                                left = -marginStart,
                                top = 0f,
                                right = cardSize.width.toFloat() + marginEnd,
                                bottom = cardSize.height.toFloat()
                            )
                        )
                    }
                }
            }
                .graphicsLayer { clip = false }
                .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.06f)
            .drawBehind {
                val extendPx = glowExtend.toPx()
                val glowWidth = size.width + (extendPx * 2)
                val glowHeight = size.height + (extendPx * 2)
                val fadeRatioH = extendPx / glowWidth
                val fadeRatioV = extendPx / glowHeight
                val topLeft = androidx.compose.ui.geometry.Offset(-extendPx, -extendPx)
                val glowSize = androidx.compose.ui.geometry.Size(glowWidth, glowHeight)

                // Use a layer to isolate the blend operation
                val layerRect = androidx.compose.ui.geometry.Rect(
                    topLeft.x, topLeft.y,
                    topLeft.x + glowSize.width, topLeft.y + glowSize.height
                )
                drawContext.canvas.saveLayer(layerRect, androidx.compose.ui.graphics.Paint())

                // First pass: horizontal gradient (fades left/right edges)
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            fadeRatioH to borderColor.copy(alpha = glowAlpha),
                            (1f - fadeRatioH) to borderColor.copy(alpha = glowAlpha),
                            1f to Color.Transparent
                        ),
                        startX = -extendPx,
                        endX = size.width + extendPx
                    ),
                    topLeft = topLeft,
                    size = glowSize
                )

                // Second pass: vertical gradient mask (fades top/bottom edges)
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            fadeRatioV to Color.White,
                            (1f - fadeRatioV) to Color.White,
                            1f to Color.Transparent
                        ),
                        startY = -extendPx,
                        endY = size.height + extendPx
                    ),
                    topLeft = topLeft,
                    size = glowSize,
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                )

                drawContext.canvas.restore()
            }
    ) {
        Card(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    }
                )
                .focusable(interactionSource = interactionSource),
            interactionSource = interactionSource,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            border = if (isFocused) {
                BorderStroke(2.dp, borderColor.copy(alpha = 0.85f))
            } else {
                null
            },
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A1A)
            )
        ) {
            Box(content = content)
        }
    }
}
