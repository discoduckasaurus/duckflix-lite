package com.duckflix.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.duckflix.lite.data.local.entity.WatchlistEntity

@Composable
fun WatchlistActionDialog(
    item: WatchlistEntity,
    onDetails: (WatchlistEntity) -> Unit,
    onRemove: (WatchlistEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val firstButtonFocusRequester = remember { FocusRequester() }
    val secondButtonFocusRequester = remember { FocusRequester() }

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
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Title and type info
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Year if available
                            if (!item.year.isNullOrBlank()) {
                                Text(
                                    text = item.year,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }

                            // Rating if available
                            if (item.voteAverage != null && item.voteAverage > 0) {
                                Text(
                                    text = String.format("%.1f", item.voteAverage),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFFD700)
                                )
                            }
                        }
                    }

                    // Action buttons - vertical stack
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // First button: Details
                        FocusableButton(
                            onClick = { onDetails(item) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(firstButtonFocusRequester)
                                .focusProperties {
                                    up = secondButtonFocusRequester
                                    down = secondButtonFocusRequester
                                }
                        ) {
                            Text("View Details")
                        }

                        // Second button: Remove
                        FocusableOutlinedButton(
                            onClick = { onRemove(item) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(secondButtonFocusRequester)
                                .focusProperties {
                                    up = firstButtonFocusRequester
                                    down = firstButtonFocusRequester
                                }
                        ) {
                            Text("Remove from Watchlist")
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
