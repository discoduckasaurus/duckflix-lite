package com.duckflix.lite.ui.screens.home

import android.app.Activity
import java.net.URLDecoder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.duckflix.lite.Screen
import com.duckflix.lite.data.local.entity.WatchlistEntity
import com.duckflix.lite.data.remote.dto.ContinueWatchingItem
import com.duckflix.lite.data.remote.dto.DisplayState
import com.duckflix.lite.data.remote.dto.RecommendationItem
import com.duckflix.lite.data.remote.dto.TrendingResult
import com.duckflix.lite.ui.components.ExitConfirmationDialog
import com.duckflix.lite.ui.components.FocusableCard
import com.duckflix.lite.ui.components.MediaCard

@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    BackHandler {
        showExitDialog = true
    }

    if (showExitDialog) {
        ExitConfirmationDialog(
            onConfirm = {
                (context as? Activity)?.finish()
            },
            onDismiss = {
                showExitDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with title
        Text(
            text = "DuckFlix Lite",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Compact Main Menu - smaller tiles in a row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CompactMenuTile(
                title = "Search",
                onClick = { navController.navigate(Screen.Search.route) }
            )
            CompactMenuTile(
                title = "Live TV",
                onClick = { /* TODO: navController.navigate(Screen.LiveTV.route) */ }
            )
            CompactMenuTile(
                title = "DVR",
                onClick = { /* TODO: navController.navigate(Screen.Dvr.route) */ }
            )
            if (uiState.isAdmin) {
                CompactMenuTile(
                    title = "Admin",
                    onClick = { navController.navigate(Screen.Admin.route) }
                )
            }
            CompactMenuTile(
                title = "Settings",
                onClick = { navController.navigate(Screen.Settings.route) }
            )
        }

        // Continue Watching Section
        if (uiState.continueWatching.isNotEmpty()) {
            ContentSection(title = "Continue Watching") {
                ContinueWatchingRow(
                    items = uiState.continueWatching,
                    onItemClick = { item ->
                        // Navigate to player if ready to play, otherwise to detail
                        if (item.isReadyToPlay) {
                            navController.navigate(
                                Screen.Player.createRoute(
                                    tmdbId = item.tmdbId,
                                    title = item.title,
                                    year = null,
                                    type = item.type,
                                    season = item.season,
                                    episode = item.episode,
                                    resumePosition = if (item.position > 0) item.position else null,
                                    posterUrl = item.posterUrl
                                )
                            )
                        } else {
                            navController.navigate(Screen.Detail.createRoute(item.tmdbId, item.type))
                        }
                    },
                    onDismiss = { item ->
                        item.jobId?.let { viewModel.dismissFailedDownload(it) }
                    },
                    onRetry = { item ->
                        viewModel.retryFailedDownload(item) {
                            navController.navigate(
                                Screen.Player.createRoute(
                                    tmdbId = item.tmdbId,
                                    title = item.title,
                                    year = null,
                                    type = item.type,
                                    season = item.season,
                                    episode = item.episode,
                                    resumePosition = null,
                                    posterUrl = item.posterUrl
                                )
                            )
                        }
                    }
                )
            }
        }

        // My Watchlist Section
        if (uiState.watchlist.isNotEmpty()) {
            ContentSection(title = "My Watchlist") {
                WatchlistRow(
                    items = uiState.watchlist,
                    onItemClick = { item ->
                        navController.navigate(Screen.Detail.createRoute(item.tmdbId, item.type))
                    },
                    onItemLongPress = { item ->
                        viewModel.removeFromWatchlist(item.tmdbId)
                    }
                )
            }
        }

        // Recommended For You Section
        if (uiState.recommendations.isNotEmpty()) {
            ContentSection(title = "Recommended For You") {
                RecommendationsRow(
                    items = uiState.recommendations,
                    onItemClick = { item ->
                        navController.navigate(Screen.Detail.createRoute(item.id, item.mediaType))
                    }
                )
            }
        } else if (uiState.isLoadingRecommendations) {
            ContentSection(title = "Recommended For You") {
                LoadingRow()
            }
        } else if (uiState.recommendationsError != null) {
            ContentSection(title = "Recommended For You") {
                Text(
                    text = "Error: ${uiState.recommendationsError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Red,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Trending Movies Section
        if (uiState.trendingMovies.isNotEmpty()) {
            ContentSection(title = "Trending Movies") {
                TrendingRow(
                    items = uiState.trendingMovies,
                    onItemClick = { item ->
                        navController.navigate(Screen.Detail.createRoute(item.id, "movie"))
                    }
                )
            }
        } else if (uiState.isLoadingTrendingMovies) {
            ContentSection(title = "Trending Movies") {
                LoadingRow()
            }
        } else if (uiState.trendingMoviesError != null) {
            ContentSection(title = "Trending Movies") {
                Text(
                    text = "Error: ${uiState.trendingMoviesError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Red,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Trending TV Section
        if (uiState.trendingTV.isNotEmpty()) {
            ContentSection(title = "Trending TV Shows") {
                TrendingRow(
                    items = uiState.trendingTV,
                    onItemClick = { item ->
                        navController.navigate(Screen.Detail.createRoute(item.id, "tv"))
                    }
                )
            }
        } else if (uiState.isLoadingTrendingTV) {
            ContentSection(title = "Trending TV Shows") {
                LoadingRow()
            }
        } else if (uiState.trendingTVError != null) {
            ContentSection(title = "Trending TV Shows") {
                Text(
                    text = "Error: ${uiState.trendingTVError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Red,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Bottom spacing for TV safe zone
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun CompactMenuTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier
            .width(140.dp)
            .height(80.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ContentSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
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
                    .width(128.dp)
                    .height(240.dp),
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
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDismiss: (ContinueWatchingItem) -> Unit,
    onRetry: (ContinueWatchingItem) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { item ->
            ContinueWatchingCard(
                item = item,
                onClick = { onItemClick(item) },
                onDismiss = { onDismiss(item) },
                onRetry = { onRetry(item) }
            )
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }

    MediaCard(
        onClick = onClick,
        modifier = Modifier
            .width(128.dp)
            .height(240.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        if (item.isFailed) {
                            showContextMenu = true
                        }
                    }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Poster image - uses series poster for TV shows (not episode still)
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                contentScale = ContentScale.Crop
            )

            // Overlay for downloading or error states
            when (item.displayState) {
                DisplayState.DOWNLOADING -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
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
                            .height(190.dp)
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
                        DisplayState.ERROR -> "⚠ Unavailable"
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

    // Context menu for failed downloads
    if (showContextMenu) {
        AlertDialog(
            onDismissRequest = { showContextMenu = false },
            title = { Text("Download Failed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = item.title)
                    if (item.errorMessage != null) {
                        Text(
                            text = "Error: ${item.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Red
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        showContextMenu = false
                        onRetry()
                    }) {
                        Text("Retry")
                    }
                    TextButton(onClick = {
                        showContextMenu = false
                        onDismiss()
                    }) {
                        Text("Dismiss")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showContextMenu = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun WatchlistRow(
    items: List<WatchlistEntity>,
    onItemClick: (WatchlistEntity) -> Unit,
    onItemLongPress: (WatchlistEntity) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { item ->
            MediaCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(128.dp)
                    .height(240.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { onItemLongPress(item) }
                        )
                    }
            ) {
                Column {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp),
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
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
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
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items) { item ->
            MediaCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(128.dp)
                    .height(240.dp)
            ) {
                Column {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp),
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
                                text = "★ ${String.format("%.1f", item.voteAverage)}",
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
private fun TrendingRow(
    items: List<TrendingResult>,
    onItemClick: (TrendingResult) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { item ->
            MediaCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(128.dp)
                    .height(240.dp)
            ) {
                Column {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp),
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
                                text = "★ ${String.format("%.1f", item.voteAverage)}",
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
