package com.duckflix.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.duckflix.lite.ui.theme.TvOsColors
import com.duckflix.lite.ui.theme.tvOsScaleOnly

/**
 * Button optimized for TV D-pad navigation
 * Grey when unfocused, gradient (purple→red→pink) when focused
 */
@Composable
fun FocusableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    focusRequester: FocusRequester? = null,
    index: Int = 0,
    totalItems: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    // Gradient brush for focused state
    val gradientBrush = Brush.linearGradient(
        colors = TvOsColors.gradientColors
    )

    // Grey for unfocused, gradient for focused
    val backgroundModifier = if (isFocused && enabled) {
        Modifier.background(brush = gradientBrush, shape = shape)
    } else {
        Modifier.background(
            color = if (enabled) Color(0xFF3A3A3A) else Color(0xFF2A2A2A),
            shape = shape
        )
    }

    val borderModifier = if (isFocused && enabled) {
        Modifier.border(2.dp, gradientBrush, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.03f)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .then(backgroundModifier)
            .then(borderModifier)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        ProvideTextStyle(
            value = MaterialTheme.typography.labelLarge.copy(
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f)
            )
        ) {
            content()
        }
    }
}

/**
 * Outlined button with focus state
 * Grey outline when unfocused, gradient when focused
 */
@Composable
fun FocusableOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    index: Int = 0,
    totalItems: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    val gradientBrush = Brush.linearGradient(
        colors = TvOsColors.gradientColors
    )

    val borderModifier = if (isFocused && enabled) {
        Modifier.border(2.dp, gradientBrush, shape)
    } else {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), shape)
    }

    Box(
        modifier = modifier
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.03f)
            .clip(shape)
            .background(Color.Transparent, shape)
            .then(borderModifier)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        ProvideTextStyle(
            value = MaterialTheme.typography.labelLarge.copy(
                color = if (isFocused) TvOsColors.gradientColors[1] else Color.White
            )
        ) {
            content()
        }
    }
}
