package com.duckflix.lite.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * TV-optimized search bar that requires click/select to activate.
 *
 * Behavior:
 * - When navigated to with D-pad: shows focus border but NO keyboard
 * - When Enter/Select pressed: activates text input and shows keyboard
 * - When navigating away: deactivates and hides keyboard
 *
 * This prevents unwanted keyboard popups when users navigate past the search bar.
 */
@Composable
fun FocusableSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    focusRequester: FocusRequester? = null,
    onNavigateUp: (() -> Unit)? = null,
    onNavigateDown: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Track whether the search bar is in "active" editing mode
    var isEditing by remember { mutableStateOf(false) }

    // Interaction source for the outer clickable card
    val cardInteractionSource = remember { MutableInteractionSource() }
    val isCardFocused by cardInteractionSource.collectIsFocusedAsState()

    // Focus requester for the inner text field
    val textFieldFocusRequester = remember { FocusRequester() }

    // When editing mode activates, focus text field and show keyboard with delay
    LaunchedEffect(isEditing) {
        if (isEditing) {
            delay(50) // Small delay to let composition settle
            try {
                textFieldFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // FocusRequester not attached yet
            }
            delay(100) // Additional delay for keyboard
            keyboardController?.show()
        }
    }

    Card(
        onClick = {
            // Activate editing mode on click
            isEditing = true
        },
        modifier = modifier
            .height(height)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .focusable(interactionSource = cardInteractionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && !isEditing) {
                    when (keyEvent.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            // Activate editing on select
                            isEditing = true
                            true
                        }
                        Key.DirectionUp -> {
                            onNavigateUp?.invoke()
                            true
                        }
                        Key.DirectionDown -> {
                            onNavigateDown?.invoke()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        interactionSource = cardInteractionSource,
        shape = MaterialTheme.shapes.medium,
        border = if (isCardFocused || isEditing) {
            BorderStroke(4.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isCardFocused || isEditing) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Always show the text field, but only make it interactive when editing
            if (isEditing) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(textFieldFocusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.Escape, Key.Back -> {
                                        // Exit editing mode
                                        isEditing = false
                                        keyboardController?.hide()
                                        true
                                    }
                                    Key.Enter -> {
                                        // Perform search and exit editing
                                        onSearch()
                                        isEditing = false
                                        keyboardController?.hide()
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        isEditing = false
                                        keyboardController?.hide()
                                        onNavigateUp?.invoke()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        isEditing = false
                                        keyboardController?.hide()
                                        onNavigateDown?.invoke()
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSearch()
                            isEditing = false
                            keyboardController?.hide()
                        }
                    )
                )
            } else {
                // Display current value or placeholder when not editing
                Text(
                    text = if (value.isEmpty()) placeholder else value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}
