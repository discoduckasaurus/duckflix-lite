package com.duckflix.lite.ui.theme

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * tvOS-inspired visual effects for DuckFlix Lite.
 *
 * Design principles (from mockup):
 * - Continuous horizontal gradient spanning full row width
 * - Gradient: purple (#7B2FF7) → magenta (#E91E8C) → pink (#FF5F6D) → cyan (#00D4FF)
 * - Cards sit ON TOP of gradient, revealing their position's color
 * - On focus: scale up slightly + white/light border
 * - Buttons: glassmorphism (frosted glass look)
 */

/**
 * Gradient colors for the app - purple → red → pink
 */
object TvOsColors {
    // Purple (left) → Red (middle) → Pink (right)
    val gradientColors = listOf(
        Color(0xFF6B1EEF),  // Purple (left)
        Color(0xFFE93326),  // Red (middle)
        Color(0xFFE93397)   // Pink (right)
    )
}

/**
 * Row wrapper that draws a horizontal gradient background.
 * The gradient spans the full width, creating the effect where each card
 * reveals its unique section of the gradient based on position.
 *
 * IMPORTANT: This wraps a LazyRow with proper contentPadding to prevent
 * clipping when cards scale up on focus.
 */
@Composable
fun TvOsGradientRow(
    modifier: Modifier = Modifier,
    gradientHeight: Dp = 280.dp,
    gradientAlpha: Float = 0.5f,
    verticalPadding: Dp = 16.dp,
    horizontalPadding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    colors = TvOsColors.gradientColors.map { it.copy(alpha = gradientAlpha) }
                )
            )
            .padding(vertical = verticalPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        content()
    }
}

/**
 * LazyRow wrapper for card rows. No background - glow is per-card via CardWithGlow.
 * Uses scaled spacing that respects UI scale settings.
 */
@Composable
fun TvOsGradientLazyRow(
    modifier: Modifier = Modifier,
    gradientAlpha: Float = 0.6f,
    verticalPadding: Dp = 24.dp.scaled(),
    itemSpacing: Dp = 24.dp.scaled(),  // ~20dp at small scale, scales up from there
    content: LazyListScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
            .graphicsLayer { clip = false }
    ) {
        LazyRow(
            modifier = Modifier.graphicsLayer { clip = false },
            contentPadding = PaddingValues(start = 32.dp.scaled(), end = 200.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(itemSpacing),
            content = content
        )
    }
}

/**
 * Get glow color based on horizontal position (0.0 = left, 1.0 = right)
 */
fun getGlowColorForPosition(normalizedPosition: Float): Color {
    val colors = TvOsColors.gradientColors
    val segment = normalizedPosition * (colors.size - 1)
    val index = segment.toInt().coerceIn(0, colors.size - 2)
    val fraction = segment - index

    return androidx.compose.ui.graphics.lerp(colors[index], colors[index + 1], fraction)
}

/**
 * Per-card glow wrapper. Creates a uniform glowing border around the poster:
 * - Extends gap/2 on all sides (left, right, top, bottom)
 * - Fades from transparent at outer edge to solid at poster edge on all sides
 * - Adjacent glows meet exactly at the gap midpoint horizontally
 */
@Composable
fun CardWithGlow(
    index: Int,
    totalItems: Int,
    glowAlpha: Float = 0.7f,
    gap: Dp = 24.dp.scaled(),
    content: @Composable () -> Unit
) {
    val normalizedPosition = if (totalItems > 1) index.toFloat() / (totalItems - 1) else 0.5f
    val glowColor = getGlowColorForPosition(normalizedPosition)

    Box(modifier = Modifier.graphicsLayer { clip = false }) {
        // Glow layer - drawn first (behind content)
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { clip = false }
                .drawBehind {
                    val gapPx = gap.toPx()
                    val extendPx = gapPx / 2f  // Same extension on all sides

                    val glowWidth = size.width + gapPx
                    val glowHeight = size.height + gapPx
                    val fadeRatioH = extendPx / glowWidth
                    val fadeRatioV = extendPx / glowHeight

                    val topLeft = Offset(-extendPx, -extendPx)
                    val glowSize = Size(glowWidth, glowHeight)

                    // Use a layer to isolate the blend operation
                    val layerRect = androidx.compose.ui.geometry.Rect(
                        topLeft.x, topLeft.y,
                        topLeft.x + glowSize.width, topLeft.y + glowSize.height
                    )
                    drawContext.canvas.saveLayer(layerRect, androidx.compose.ui.graphics.Paint())

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
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                    )

                    drawContext.canvas.restore()
                }
        )

        // Content layer - on top
        content()
    }
}

/**
 * Get the glow color for a given position - use this for border color matching
 */
@Composable
fun rememberGlowColor(index: Int, totalItems: Int): Color {
    val normalizedPosition = if (totalItems > 1) index.toFloat() / (totalItems - 1) else 0.5f
    return getGlowColorForPosition(normalizedPosition)
}

/**
 * Per-card glow modifier (legacy - use CardWithGlow for better z-ordering)
 */
@Composable
fun Modifier.tvOsCardGlow(
    index: Int,
    totalItems: Int,
    glowAlpha: Float = 0.5f,
    glowRadius: Dp = 35.dp
): Modifier {
    val normalizedPosition = if (totalItems > 1) index.toFloat() / (totalItems - 1) else 0.5f
    val glowColor = getGlowColorForPosition(normalizedPosition)

    return this.drawBehind {
        val expandPx = glowRadius.toPx()
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowColor.copy(alpha = glowAlpha),
                    glowColor.copy(alpha = glowAlpha * 0.5f),
                    glowColor.copy(alpha = glowAlpha * 0.2f),
                    Color.Transparent
                ),
                center = Offset(size.width / 2, size.height / 2),
                radius = maxOf(size.width, size.height) / 2 + expandPx
            ),
            topLeft = Offset(-expandPx, -expandPx),
            size = Size(size.width + expandPx * 2, size.height + expandPx * 2),
            cornerRadius = CornerRadius(12.dp.toPx())
        )
    }
}

/**
 * Scale effect for cards - the main visual effect.
 * Creates the "lift" effect when a card is focused.
 *
 * @param isFocused Whether the card is currently focused
 * @param focusedScale Scale factor when focused (default 1.08 = 8%)
 */
@Composable
fun Modifier.tvOsScaleOnly(
    isFocused: Boolean,
    focusedScale: Float = 1.08f
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusedScale else 1.0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "scale"
    )

    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        // CRITICAL: Don't clip so scaled content is visible
        clip = false
    }
}

/**
 * Full focus effects for cards - scale + subtle white glow.
 */
@Composable
fun Modifier.tvOsFocusEffects(
    isFocused: Boolean,
    enableScale: Boolean = true,
    focusedScale: Float = 1.08f,
    enableBorderGlow: Boolean = true,
    enableSheen: Boolean = false,
    respectTransparency: Boolean = false,
    elementScale: Float = focusedScale,
    glowScale: Float = 1.0f
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) elementScale else 1.0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "cardScale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isFocused) 0.4f else 0f,
        animationSpec = tween(200),
        label = "glowAlpha"
    )

    var result = this

    // Apply scale
    if (enableScale) {
        result = result.graphicsLayer {
            scaleX = scale
            scaleY = scale
            clip = false
        }
    }

    // Subtle white glow behind card when focused
    if (enableBorderGlow) {
        result = result.drawBehind {
            if (glowAlpha > 0f) {
                val expandSize = 6.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = glowAlpha * 0.5f),
                    topLeft = Offset(-expandSize, -expandSize),
                    size = Size(size.width + expandSize * 2, size.height + expandSize * 2),
                    cornerRadius = CornerRadius(8.dp.toPx())
                )
            }
        }
    }

    return result
}

/**
 * Glass-style modifier for buttons.
 * Creates a semi-transparent, frosted glass appearance.
 */
@Composable
fun Modifier.tvOsGlassButton(
    isFocused: Boolean,
    baseAlpha: Float = 0.2f,
    focusedAlpha: Float = 0.35f
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (isFocused) focusedAlpha else baseAlpha,
        animationSpec = tween(200),
        label = "glassAlpha"
    )

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "buttonScale"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            clip = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                renderEffect = RenderEffect.createBlurEffect(
                    12f, 12f, Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            }
        }
        .background(
            color = Color.White.copy(alpha = alpha),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        )
}

/**
 * Simple wrapper - kept for backwards compatibility.
 */
@Composable
fun TvOsPositionProvider(
    index: Int,
    totalItems: Int,
    content: @Composable () -> Unit
) {
    content()
}
