package com.duckflix.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.ContinueWatchingItem
import com.duckflix.lite.data.remote.dto.DisplayState

@Composable
fun ContinueWatchingActionDialog(
    item: ContinueWatchingItem,
    onResume: (ContinueWatchingItem) -> Unit,
    onRetry: (ContinueWatchingItem) -> Unit,
    onDetails: (ContinueWatchingItem) -> Unit,
    onRemove: (ContinueWatchingItem) -> Unit,
    onDismiss: () -> Unit
) {
    val firstButtonFocusRequester = remember { FocusRequester() }
    val secondButtonFocusRequester = remember { FocusRequester() }
    val thirdButtonFocusRequester = remember { FocusRequester() }

    val isFailed = item.isFailed

    // Use Dialog composable to ensure it renders above all other content
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
                .background(Color.Black.copy(alpha = 0.85f))
                .focusable()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Item info row with poster
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Poster thumbnail
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(120.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF2A2A2A))
                    ) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.displayTitle,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        // Error overlay for failed items
                        if (item.isFailed) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Red.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "!",
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // Title and episode info
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = item.displayTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Episode info for TV shows
                        if (item.type == "tv" && item.season != null && item.episode != null) {
                            Text(
                                text = "S${item.season} E${item.episode}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }

                        // Progress or status info
                        when (item.displayState) {
                            DisplayState.ERROR -> {
                                Text(
                                    text = item.errorMessage ?: "Download failed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFF5252),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DisplayState.DOWNLOADING -> {
                                Text(
                                    text = "Downloading... ${item.downloadProgress}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF64B5F6)
                                )
                            }
                            DisplayState.IN_PROGRESS -> {
                                val progressPercent = item.progressPercent
                                Text(
                                    text = "$progressPercent% watched",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                            DisplayState.READY -> {
                                // No additional text needed
                            }
                        }
                    }
                }

                // Action buttons - vertical stack
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // First button: Resume or Retry
                    if (isFailed) {
                        FocusableButton(
                            onClick = { onRetry(item) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(firstButtonFocusRequester)
                                .focusProperties {
                                    up = thirdButtonFocusRequester
                                    down = secondButtonFocusRequester
                                }
                        ) {
                            Text("Retry Download")
                        }
                    } else {
                        FocusableButton(
                            onClick = { onResume(item) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(firstButtonFocusRequester)
                                .focusProperties {
                                    up = thirdButtonFocusRequester
                                    down = secondButtonFocusRequester
                                }
                        ) {
                            Text(if (item.position > 0) "Resume" else "Play")
                        }
                    }

                    // Second button: Details
                    FocusableOutlinedButton(
                        onClick = { onDetails(item) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(secondButtonFocusRequester)
                            .focusProperties {
                                up = firstButtonFocusRequester
                                down = thirdButtonFocusRequester
                            }
                    ) {
                        Text("View Details")
                    }

                    // Third button: Remove
                    FocusableOutlinedButton(
                        onClick = { onRemove(item) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(thirdButtonFocusRequester)
                            .focusProperties {
                                up = secondButtonFocusRequester
                                down = firstButtonFocusRequester
                            }
                    ) {
                        Text(if (isFailed) "Dismiss" else "Remove")
                    }
                }
            }
        }
        }
    }

    // Auto-focus the first button
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            firstButtonFocusRequester.requestFocus()
        } catch (e: Exception) {
            // Focus system not ready, ignore
        }
    }
}
