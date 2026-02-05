package com.duckflix.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp

@Composable
fun ExitConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelButtonFocusRequester = remember { FocusRequester() }
    val exitButtonFocusRequester = remember { FocusRequester() }
    val dialogFocusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .focusRequester(dialogFocusRequester)
            // Intercept all key events to prevent them from reaching elements behind
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp) {
                    when (keyEvent.key) {
                        Key.Back, Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                } else {
                    // Consume KeyDown events to prevent propagation
                    false
                }
            }
            .focusable()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 500.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Exit DuckFlix Lite?",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "Are you sure you want to exit?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FocusableButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(cancelButtonFocusRequester)
                            .focusProperties {
                                // Trap focus within dialog - wrap to exit button
                                left = exitButtonFocusRequester
                            }
                    ) {
                        Text("Cancel")
                    }

                    FocusableButton(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(exitButtonFocusRequester)
                            .focusProperties {
                                // Trap focus within dialog - wrap to cancel button
                                right = cancelButtonFocusRequester
                            }
                    ) {
                        Text("Yes, Exit")
                    }
                }
            }
        }
    }

    // Auto-focus Cancel button after a short delay
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            cancelButtonFocusRequester.requestFocus()
        } catch (e: Exception) {
            // Focus system not ready, ignore
        }
    }
}
