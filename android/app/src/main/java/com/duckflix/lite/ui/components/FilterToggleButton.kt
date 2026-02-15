package com.duckflix.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.duckflix.lite.ui.theme.TvOsColors
import com.duckflix.lite.ui.theme.tvOsScaleOnly

/**
 * Toggle button for TV/Movie filters
 * Grey when unfocused, gradient when focused or selected
 * Optimized for TV D-pad navigation with clear focus indication
 */
@Composable
fun FilterToggleButton(
    label: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)

    val gradientBrush = Brush.linearGradient(
        colors = TvOsColors.gradientColors
    )

    // Grey when not focused and not selected, gradient when focused or selected
    val backgroundModifier = when {
        isFocused -> Modifier.background(brush = gradientBrush, shape = shape)
        isSelected -> Modifier.background(brush = gradientBrush, shape = shape)
        else -> Modifier.background(color = Color(0xFF3A3A3A), shape = shape)
    }

    val borderModifier = if (isFocused) {
        Modifier.border(1.5.dp, Color.White, shape)
    } else {
        Modifier
    }

    val textColor = when {
        isFocused || isSelected -> Color.White
        else -> Color.White.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.03f)
            .clip(shape)
            .then(backgroundModifier)
            .then(borderModifier)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
    }
}
