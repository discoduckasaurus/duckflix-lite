package com.duckflix.lite.ui.components

import androidx.compose.foundation.BorderStroke
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

/**
 * D-pad navigable range slider for rating/runtime filters.
 *
 * Displays a label, the current range values, and a Material3 RangeSlider.
 * When focused, shows a focus indicator and responds to D-pad left/right
 * for basic value adjustment.
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

    // Calculate step size for D-pad navigation
    val rangeSpan = valueRange.endInclusive - valueRange.start
    val stepSize = if (steps > 0) {
        rangeSpan / (steps + 1)
    } else {
        rangeSpan / 20f // Default to 5% increments for continuous slider
    }

    val focusBorderModifier = if (isFocused) {
        Modifier.border(
            BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
            shape
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .then(focusBorderModifier)
            .padding(16.dp)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            // Decrease the end value (shrink range from right)
                            val newEnd = (value.endInclusive - stepSize)
                                .coerceAtLeast(value.start + stepSize)
                                .coerceIn(valueRange)
                            onValueChange(value.start..newEnd)
                            true
                        }
                        Key.DirectionRight -> {
                            // Increase the end value (expand range to right)
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
            // Header row with label and current range display
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
                    color = if (isFocused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.7f)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Range slider
            RangeSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                    activeTickColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveTickColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            )

            // Min/Max labels below slider
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
 * Convenience overload for single-value slider (not a range).
 * Useful for simple filters like "minimum rating".
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

    // Calculate step size for D-pad navigation
    val rangeSpan = valueRange.endInclusive - valueRange.start
    val stepSize = if (steps > 0) {
        rangeSpan / (steps + 1)
    } else {
        rangeSpan / 20f // Default to 5% increments
    }

    val focusBorderModifier = if (isFocused) {
        Modifier.border(
            BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
            shape
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .then(focusBorderModifier)
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
            // Header row with label and current value display
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
                    color = if (isFocused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.7f)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Single-value slider
            androidx.compose.material3.Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                    activeTickColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveTickColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            )

            // Min/Max labels below slider
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
