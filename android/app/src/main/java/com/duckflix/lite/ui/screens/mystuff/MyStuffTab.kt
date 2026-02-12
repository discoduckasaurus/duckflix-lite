@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.duckflix.lite.ui.screens.mystuff

import java.net.URLDecoder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.duckflix.lite.ui.theme.Dimens
import com.duckflix.lite.data.local.entity.WatchlistEntity
import com.duckflix.lite.data.remote.dto.ContinueWatchingItem
import com.duckflix.lite.data.remote.dto.DisplayState
import com.duckflix.lite.data.remote.dto.RecommendationItem
import com.duckflix.lite.ui.components.ContinueWatchingActionDialog
import com.duckflix.lite.ui.components.MediaCard
import com.duckflix.lite.ui.components.WatchlistActionDialog
import com.duckflix.lite.ui.theme.TvOsGradientLazyRow
import com.duckflix.lite.ui.theme.rememberGlowColor

/**
 * My Stuff tab content - displays user's personal content:
 * - Continue Watching
 * - My Watchlist
 * - Recommended For You
 *
 * Reuses HomeViewModel state and callbacks passed as parameters.
 */
@Composable
fun MyStuffTab(
    continueWatching: List<ContinueWatchingItem>,
    watchlist: List<WatchlistEntity>,
    recommendations: List<RecommendationItem>,
    isLoadingRecommendations: Boolean,
    recommendationsError: String?,
    onContinueWatchingResume: (ContinueWatchingItem) -> Unit,
    onContinueWatchingRetry: (ContinueWatchingItem) -> Unit,
    onContinueWatchingDetails: (ContinueWatchingItem) -> Unit,
    onContinueWatchingRemove: (ContinueWatchingItem) -> Unit,
    onWatchlistDetails: (WatchlistEntity) -> Unit,
    onWatchlistRemove: (WatchlistEntity) -> Unit,
    onRecommendationClick: (RecommendationItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // Dialog state
    var selectedContinueWatchingItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var selectedWatchlistItem by remember { mutableStateOf<WatchlistEntity?>(null) }

    // Continue Watching Action Dialog
    selectedContinueWatchingItem?.let { item ->
        ContinueWatchingActionDialog(
            item = item,
            onResume = {
                selectedContinueWatchingItem = null
                onContinueWatchingResume(it)
            },
            onRetry = {
                selectedContinueWatchingItem = null
                onContinueWatchingRetry(it)
            },
            onDetails = {
                selectedContinueWatchingItem = null
                onContinueWatchingDetails(it)
            },
            onRemove = {
                selectedContinueWatchingItem = null
                onContinueWatchingRemove(it)
            },
            onDismiss = { selectedContinueWatchingItem = null }
        )
    }

    // Watchlist Action Dialog
    selectedWatchlistItem?.let { item ->
        WatchlistActionDialog(
            item = item,
            onDetails = {
                selectedWatchlistItem = null
                onWatchlistDetails(it)
            },
            onRemove = {
                selectedWatchlistItem = null
                onWatchlistRemove(it)
            },
            onDismiss = { selectedWatchlistItem = null }
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Continue Watching Section
        if (continueWatching.isNotEmpty()) {
            ContentSection(title = "Continue Watching") {
                ContinueWatchingRow(
                    items = continueWatching,
                    onItemClick = { item -> selectedContinueWatchingItem = item }
                )
            }
        }

        // My Watchlist Section
        if (watchlist.isNotEmpty()) {
            ContentSection(title = "My Watchlist") {
                WatchlistRow(
                    items = watchlist,
                    onItemClick = { item -> selectedWatchlistItem = item }
                )
            }
        }

        // Recommended For You Section
        if (recommendations.isNotEmpty()) {
            ContentSection(title = "Recommended For You") {
                RecommendationsRow(
                    items = recommendations,
                    onItemClick = onRecommendationClick
                )
            }
        } else if (isLoadingRecommendations) {
            ContentSection(title = "Recommended For You") {
                LoadingRow()
            }
        } else if (recommendationsError != null) {
            ContentSection(title = "Recommended For You") {
                Text(
                    text = "Error: $recommendationsError",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Red,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Show empty state if no content
        if (continueWatching.isEmpty() && watchlist.isEmpty() && recommendations.isEmpty() && !isLoadingRecommendations) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Your personal content will appear here.\nStart watching something to get started!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ContentSection(
    title: String,
    content: @Composable () -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { focusState ->
                if (focusState.hasFocus) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title has horizontal padding, row content extends edge-to-edge
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        content()
    }
}

@Composable
private fun LoadingRow() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(4) {
            Card(
                modifier = Modifier
                    .width(Dimens.posterCardWidth)
                    .height(Dimens.posterCardHeight),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<ContinueWatchingItem>,
    onItemClick: (ContinueWatchingItem) -> Unit
) {
    TvOsGradientLazyRow(
        verticalPadding = 20.dp,
    ) {
        items(items.size) { index ->
            val item = items[index]
            ContinueWatchingCard(
                item = item,
                onClick = { onItemClick(item) },
                glowColor = rememberGlowColor(index, items.size)
            )
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    glowColor: Color = Color.White
) {
    MediaCard(
        onClick = onClick,
        modifier = Modifier
            .width(Dimens.posterCardWidth)
            .height(Dimens.posterCardHeight),
        borderColor = glowColor
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Poster image - uses series poster for TV shows (not episode still)
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.posterImageHeight),
                contentScale = ContentScale.Crop
            )

            // Overlay for downloading or error states
            when (item.displayState) {
                DisplayState.DOWNLOADING -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.posterImageHeight)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (item.downloadStatus == "searching") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    strokeWidth = 4.dp,
                                    color = Color.White
                                )
                            } else {
                                CircularProgressIndicator(
                                    progress = { item.downloadProgress / 100f },
                                    modifier = Modifier.size(48.dp),
                                    strokeWidth = 4.dp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                DisplayState.ERROR -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.posterImageHeight)
                            .background(Color(0x99FF0000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5252)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "!",
                                style = MaterialTheme.typography.headlineLarge,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                else -> { /* No overlay */ }
            }

            // Info section at bottom - 2 lines: bold title + combined episode/progress
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Line 1: Title in BOLD (URL-decoded to fix "+" issue)
                Text(
                    text = try {
                        URLDecoder.decode(item.title, "UTF-8")
                    } catch (e: Exception) {
                        item.title
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Line 2: Combined episode and status (e.g., "S01E01 | 4%")
                val secondLineText = buildString {
                    // Episode info for TV shows
                    if (item.type == "tv" && item.season != null && item.episode != null) {
                        append("S%02dE%02d".format(item.season, item.episode))
                    }

                    // Status based on state
                    val statusText = when (item.displayState) {
                        DisplayState.DOWNLOADING -> item.downloadMessage ?: "Preparing..."
                        DisplayState.ERROR -> "! Unavailable"
                        DisplayState.IN_PROGRESS -> "${item.progressPercent}%"
                        DisplayState.READY -> ""
                    }

                    // Combine with separator if both parts exist
                    if (isNotEmpty() && statusText.isNotEmpty()) {
                        append(" | ")
                        append(statusText)
                    } else if (statusText.isNotEmpty()) {
                        append(statusText)
                    }
                }

                if (secondLineText.isNotEmpty()) {
                    Text(
                        text = secondLineText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchlistRow(
    items: List<WatchlistEntity>,
    onItemClick: (WatchlistEntity) -> Unit
) {
    TvOsGradientLazyRow(
        verticalPadding = 20.dp,
    ) {
        items(items.size) { index ->
            val item = items[index]
            val glowColor = rememberGlowColor(index, items.size)
            MediaCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(Dimens.posterCardWidth)
                    .height(Dimens.posterCardHeight),
                borderColor = glowColor
            ) {
                Column {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.posterImageHeight),
                        contentScale = ContentScale.Crop
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Show rating if available
                        if (item.voteAverage != null && item.voteAverage > 0) {
                            Text(
                                text = String.format("%.1f", item.voteAverage),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD700).copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationsRow(
    items: List<RecommendationItem>,
    onItemClick: (RecommendationItem) -> Unit
) {
    TvOsGradientLazyRow(
        verticalPadding = 20.dp,
    ) {
        items(items.size) { index ->
            val item = items[index]
            val glowColor = rememberGlowColor(index, items.size)
            MediaCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(Dimens.posterCardWidth)
                    .height(Dimens.posterCardHeight),
                borderColor = glowColor
            ) {
                Column {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.posterImageHeight),
                        contentScale = ContentScale.Crop
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.voteAverage != null && item.voteAverage > 0) {
                            Text(
                                text = String.format("%.1f", item.voteAverage),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD700).copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }
    }
}
