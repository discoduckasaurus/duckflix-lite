package com.duckflix.lite.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * TV-remote-friendly dropdown for selecting from a list of options.
 * Supports both single-select and multi-select modes.
 *
 * Uses pill-shaped trigger button matching FilterToggleButton styling.
 * Focus border appears when trigger is focused via D-pad navigation.
 */
@Composable
fun <T> FilterDropdown(
    label: String,
    options: List<T>,
    selectedOptions: List<T>,
    onSelectionChange: (List<T>) -> Unit,
    optionLabel: (T) -> String,
    multiSelect: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)

    // Determine display text based on selection state
    val displayText = when {
        selectedOptions.isEmpty() -> label
        selectedOptions.size == 1 -> optionLabel(selectedOptions.first())
        else -> "${selectedOptions.size} ${label.lowercase()}"
    }

    // Styling matches FilterToggleButton
    val hasSelection = selectedOptions.isNotEmpty()
    val backgroundColor = when {
        hasSelection -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
    }
    val textColor = when {
        hasSelection -> Color.White
        else -> Color.White.copy(alpha = 0.7f)
    }

    val borderModifier = if (isFocused) {
        Modifier.border(
            BorderStroke(4.dp, MaterialTheme.colorScheme.primary),
            shape
        )
    } else {
        Modifier
    }

    Box(modifier = modifier) {
        // Trigger button
        Box(
            modifier = Modifier
                .height(48.dp)
                .clip(shape)
                .then(borderModifier)
                .background(backgroundColor, shape)
                .focusable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { expanded = true }
                )
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand dropdown",
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
        ) {
            options.forEach { option ->
                val isSelected = selectedOptions.contains(option)
                val itemInteractionSource = remember { MutableInteractionSource() }
                val isItemFocused by itemInteractionSource.collectIsFocusedAsState()

                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = optionLabel(option),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (multiSelect && isSelected) {
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        if (multiSelect) {
                            // Toggle selection in multi-select mode
                            val newSelection = if (isSelected) {
                                selectedOptions - option
                            } else {
                                selectedOptions + option
                            }
                            onSelectionChange(newSelection)
                        } else {
                            // Replace selection in single-select mode
                            onSelectionChange(listOf(option))
                            expanded = false
                        }
                    },
                    modifier = Modifier
                        .focusable(interactionSource = itemInteractionSource)
                        .then(
                            if (isItemFocused) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            } else {
                                Modifier
                            }
                        ),
                    interactionSource = itemInteractionSource
                )
            }

            // Done button for multi-select mode
            if (multiSelect) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                val doneInteractionSource = remember { MutableInteractionSource() }
                val isDoneFocused by doneInteractionSource.collectIsFocusedAsState()

                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Done",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = { expanded = false },
                    modifier = Modifier
                        .focusable(interactionSource = doneInteractionSource)
                        .then(
                            if (isDoneFocused) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            } else {
                                Modifier
                            }
                        ),
                    interactionSource = doneInteractionSource
                )
            }
        }
    }
}
