package com.duckflix.lite.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * Button optimized for TV D-pad navigation
 * Shows enhanced focus state
 */
@Composable
fun FocusableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    focusRequester: FocusRequester? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isFocused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        modifier = modifier
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            ),
        enabled = enabled,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        border = if (isFocused) {
            BorderStroke(3.dp, MaterialTheme.colorScheme.onPrimary)
        } else {
            null
        },
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isFocused) 8.dp else 2.dp
        )
    ) {
        content()
    }
}

/**
 * Outlined button with focus state
 */
@Composable
fun FocusableOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isFocused by interactionSource.collectIsFocusedAsState()

    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        border = BorderStroke(
            width = if (isFocused) 3.dp else 1.dp,
            color = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
    ) {
        content()
    }
}
