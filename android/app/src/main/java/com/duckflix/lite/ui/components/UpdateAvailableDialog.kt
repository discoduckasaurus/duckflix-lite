package com.duckflix.lite.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun UpdateAvailableDialog(
    currentVersion: String,
    newVersion: String,
    releaseNotes: String?,
    isDownloading: Boolean,
    downloadProgress: Float,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit
) {
    val laterButtonFocusRequester = remember { FocusRequester() }
    val updateButtonFocusRequester = remember { FocusRequester() }
    val animatedProgress by animateFloatAsState(targetValue = downloadProgress, label = "progress")

    Dialog(
        onDismissRequest = { if (!isDownloading) onLater() },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
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
                        text = "Update Available",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Text(
                        text = "v$currentVersion → v$newVersion",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    if (!releaseNotes.isNullOrBlank()) {
                        Text(
                            text = releaseNotes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                    }

                    if (isDownloading) {
                        Text(
                            text = "Downloading... ${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .padding(bottom = 0.dp),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            FocusableButton(
                                onClick = onLater,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(laterButtonFocusRequester)
                                    .focusProperties {
                                        left = updateButtonFocusRequester
                                    },
                                index = 0,
                                totalItems = 2
                            ) {
                                Text("Later")
                            }

                            FocusableButton(
                                onClick = onUpdateNow,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(updateButtonFocusRequester)
                                    .focusProperties {
                                        right = laterButtonFocusRequester
                                    },
                                index = 1,
                                totalItems = 2
                            ) {
                                Text("Update Now")
                            }
                        }
                    }
                }
            }
        }
    }

    // Auto-focus Update Now button
    LaunchedEffect(isDownloading) {
        if (!isDownloading) {
            kotlinx.coroutines.delay(100)
            try {
                updateButtonFocusRequester.requestFocus()
            } catch (e: Exception) {
                // Focus system not ready, ignore
            }
        }
    }
}
