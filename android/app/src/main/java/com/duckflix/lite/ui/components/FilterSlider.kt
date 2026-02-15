package com.duckflix.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.duckflix.lite.ui.theme.TvOsColors
import com.duckflix.lite.ui.theme.tvOsScaleOnly

/**
 * D-pad navigable range slider for rating/runtime filters.
 * Grey background, gradient border when focused.
 */
@Composable
fun FilterSlider(
    label: String,
    value: ClosedFloatingPointRange<Float>,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    steps: Int = 0,
    valueFormatter: (Float) -> String = { it.toString() },
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    val rangeSpan = valueRange.endInclusive - valueRange.start
    val stepSize = if (steps > 0) {
        rangeSpan / (steps + 1)
    } else {
        rangeSpan / 20f
    }

    val backgroundModifier = Modifier.background(
        color = if (isFocused) Color(0xFF3A3A3A) else Color(0xFF2A2A2A),
        shape = shape
    )

    val borderModifier = if (isFocused) {
        Modifier.border(1.5.dp, Color.White, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.02f)
            .clip(shape)
            .then(backgroundModifier)
            .then(borderModifier)
            .padding(16.dp)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            val newEnd = (value.endInclusive - stepSize)
                                .coerceAtLeast(value.start + stepSize)
                                .coerceIn(valueRange)
                            onValueChange(value.start..newEnd)
                            true
                        }
                        Key.DirectionRight -> {
                            val newEnd = (value.endInclusive + stepSize)
                                .coerceAtMost(valueRange.endInclusive)
                            onValueChange(value.start..newEnd)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = "${valueFormatter(value.start)} - ${valueFormatter(value.endInclusive)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFocused) TvOsColors.gradientColors[1] else Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            RangeSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = TvOsColors.gradientColors[1],
                    activeTrackColor = TvOsColors.gradientColors[1],
                    inactiveTrackColor = Color(0xFF4A4A4A),
                    activeTickColor = Color.White,
                    inactiveTickColor = Color(0xFF5A5A5A)
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = valueFormatter(valueRange.start),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Text(
                    text = valueFormatter(valueRange.endInclusive),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Single-value slider variant.
 * Grey background, gradient border when focused.
 */
@Composable
fun FilterSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
    valueFormatter: (Float) -> String = { it.toString() },
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    val rangeSpan = valueRange.endInclusive - valueRange.start
    val stepSize = if (steps > 0) {
        rangeSpan / (steps + 1)
    } else {
        rangeSpan / 20f
    }

    val backgroundModifier = Modifier.background(
        color = if (isFocused) Color(0xFF3A3A3A) else Color(0xFF2A2A2A),
        shape = shape
    )

    val borderModifier = if (isFocused) {
        Modifier.border(1.5.dp, Color.White, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.02f)
            .clip(shape)
            .then(backgroundModifier)
            .then(borderModifier)
            .padding(16.dp)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            val newValue = (value - stepSize).coerceIn(valueRange)
                            onValueChange(newValue)
                            true
                        }
                        Key.DirectionRight -> {
                            val newValue = (value + stepSize).coerceIn(valueRange)
                            onValueChange(newValue)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = valueFormatter(value),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFocused) TvOsColors.gradientColors[1] else Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            androidx.compose.material3.Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = TvOsColors.gradientColors[1],
                    activeTrackColor = TvOsColors.gradientColors[1],
                    inactiveTrackColor = Color(0xFF4A4A4A),
                    activeTickColor = Color.White,
                    inactiveTickColor = Color(0xFF5A5A5A)
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = valueFormatter(valueRange.start),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Text(
                    text = valueFormatter(valueRange.endInclusive),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}
