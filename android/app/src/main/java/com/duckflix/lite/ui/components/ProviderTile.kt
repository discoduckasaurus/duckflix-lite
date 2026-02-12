package com.duckflix.lite.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.WatchProvider
import com.duckflix.lite.ui.theme.getGlowColorForPosition
import com.duckflix.lite.ui.theme.tvOsScaleOnly

/**
 * Tile component for displaying streaming providers in Classic Mode.
 * Uses gradient glow border like MediaCard.
 * Height reduced 10% from 252dp to 227dp.
 *
 * Focus state: scale + solid border
 * Always visible: gradient glow
 */
@Composable
fun ProviderTile(
    provider: WatchProvider,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    totalItems: Int = 1,
    glowAlpha: Float = 0.55f,
    glowExtend: Dp = 12.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val normalizedPosition = if (totalItems > 1) index.toFloat() / (totalItems - 1) else 0.5f
    val glowColor = getGlowColorForPosition(normalizedPosition)
    val cornerRadius = 8.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(227.dp) // 10% reduction from 252dp
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
                drawContext.canvas.saveLayer(layerRect, Paint())

                // First pass: horizontal gradient (fades left/right edges)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            fadeRatioH to glowColor.copy(alpha = glowAlpha),
                            (1f - fadeRatioH) to glowColor.copy(alpha = glowAlpha),
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
                    brush = Brush.verticalGradient(
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
                    blendMode = BlendMode.DstIn
                )

                drawContext.canvas.restore()
            }
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .focusable(interactionSource = interactionSource),
            interactionSource = interactionSource,
            shape = RoundedCornerShape(cornerRadius),
            border = if (isFocused) {
                BorderStroke(2.dp, glowColor.copy(alpha = 0.85f))
            } else {
                null
            },
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A1A)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Main area with centered logo
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (provider.logoUrl != null) {
                        AsyncImage(
                            model = provider.logoUrl,
                            contentDescription = provider.providerName,
                            modifier = Modifier.size(72.dp), // Slightly smaller logo
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        // No logo - show first letter as placeholder
                        Text(
                            text = provider.providerName.take(1).uppercase(),
                            style = MaterialTheme.typography.displayLarge,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }

                // Bottom info area (scaled down from 88dp to 79dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(79.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = provider.providerName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
