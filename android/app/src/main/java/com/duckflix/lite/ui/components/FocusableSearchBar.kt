package com.duckflix.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.duckflix.lite.ui.theme.TvOsColors
import com.duckflix.lite.ui.theme.tvOsScaleOnly

/**
 * TV-optimized search bar with grey-to-gradient focus effect.
 * Grey when unfocused, gradient border when focused/editing.
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

    var isEditing by remember { mutableStateOf(false) }
    val cardInteractionSource = remember { MutableInteractionSource() }
    val isCardFocused by cardInteractionSource.collectIsFocusedAsState()
    val textFieldFocusRequester = remember { FocusRequester() }
    val cardFocusRequester = remember { FocusRequester() }

    val shape = RoundedCornerShape(12.dp)
    val gradientBrush = Brush.linearGradient(
        colors = TvOsColors.gradientColors
    )

    LaunchedEffect(isEditing) {
        if (isEditing) {
            delay(50)
            try {
                textFieldFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {}
            delay(150)
            keyboardController?.show()
        } else {
            delay(50)
            try {
                cardFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {}
        }
    }

    // Background: grey normally, slightly brighter when editing
    val backgroundColor = when {
        isEditing -> Color(0xFF4A4A4A)
        isCardFocused -> Color(0xFF3A3A3A)
        else -> Color(0xFF2A2A2A)
    }

    // Border: gradient when focused or editing
    val borderModifier = if (isCardFocused || isEditing) {
        Modifier.border(2.dp, gradientBrush, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .height(height)
            .tvOsScaleOnly(isFocused = isCardFocused || isEditing, focusedScale = 1.02f)
            .focusRequester(cardFocusRequester)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(backgroundColor, shape)
            .then(borderModifier)
            .focusable(interactionSource = cardInteractionSource)
            .clickable(
                interactionSource = cardInteractionSource,
                indication = null,
                onClick = { isEditing = true }
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && !isEditing) {
                    when (keyEvent.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
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
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
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
                                    isEditing = false
                                    keyboardController?.hide()
                                    true
                                }
                                Key.Enter -> {
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
                    color = Color.White,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize
                ),
                cursorBrush = SolidColor(TvOsColors.gradientColors[1]),
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
            Text(
                text = if (value.isEmpty()) placeholder else value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (value.isEmpty()) {
                    Color.White.copy(alpha = 0.5f)
                } else {
                    Color.White
                }
            )
        }
    }
}
