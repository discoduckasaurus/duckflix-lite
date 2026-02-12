package com.duckflix.lite.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.duckflix.lite.BuildConfig
import com.duckflix.lite.R

@Composable
fun LogoutConfirmationDialog(
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val backButtonFocusRequester = remember { FocusRequester() }
    val logoutButtonFocusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .focusable()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 400.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Duck logo image
                    Image(
                        painter = painterResource(id = R.drawable.dfl_logo),
                        contentDescription = "DuckFlix Logo",
                        modifier = Modifier
                            .size(80.dp)
                            .padding(bottom = 16.dp)
                    )

                    // DuckFlix (bold)
                    Text(
                        text = "DuckFlix",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Lite V1.1.0-beta
                    Text(
                        text = "Lite V${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Hi Mom! (italic)
                    Text(
                        text = "Hi Mom!",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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
                                .focusRequester(backButtonFocusRequester)
                                .focusProperties {
                                    left = logoutButtonFocusRequester
                                },
                            index = 0,
                            totalItems = 2
                        ) {
                            Text("Back")
                        }

                        FocusableButton(
                            onClick = onLogout,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(logoutButtonFocusRequester)
                                .focusProperties {
                                    right = backButtonFocusRequester
                                },
                            index = 1,
                            totalItems = 2
                        ) {
                            Text("Logout")
                        }
                    }
                }
            }
        }
    }

    // Auto-focus Back button after a short delay
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            backButtonFocusRequester.requestFocus()
        } catch (e: Exception) {
            // Focus system not ready, ignore
        }
    }
}
