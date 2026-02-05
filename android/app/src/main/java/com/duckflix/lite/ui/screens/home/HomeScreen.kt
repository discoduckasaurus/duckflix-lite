package com.duckflix.lite.ui.screens.home

import android.app.Activity
import java.net.URLDecoder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.duckflix.lite.Screen
import com.duckflix.lite.data.local.entity.WatchlistEntity
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
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Only handle Back when dialog is not showing
    BackHandler(enabled = !showExitDialog) {
        showExitDialog = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
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
                onClick = { navController.navigate(Screen.Search.route) },
                coroutineScope = coroutineScope,
                scrollState = scrollState
            )
            CompactMenuTile(
                title = "Live TV",
                onClick = { /* TODO: navController.navigate(Screen.LiveTV.route) */ },
                coroutineScope = coroutineScope,
                scrollState = scrollState
            )
            CompactMenuTile(
                title = "DVR",
                onClick = { /* TODO: navController.navigate(Screen.Dvr.route) */ },
                coroutineScope = coroutineScope,
                scrollState = scrollState
            )
            if (uiState.isAdmin) {
                CompactMenuTile(
                    title = "Admin",
                    onClick = { navController.navigate(Screen.Admin.route) },
                    coroutineScope = coroutineScope,
                    scrollState = scrollState
                )
            }
            CompactMenuTile(
                title = "Settings",
                onClick = { navController.navigate(Screen.Settings.route) },
                coroutineScope = coroutineScope,
                scrollState = scrollState
            )
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

        // Exit confirmation dialog - placed last so it renders on top
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
    }
}

@Composable
private fun CompactMenuTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coroutineScope: CoroutineScope? = null,
    scrollState: androidx.compose.foundation.ScrollState? = null
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier
            .width(140.dp)
            .height(80.dp)
            .onFocusChanged { focusState ->
                if (focusState.isFocused && coroutineScope != null && scrollState != null) {
                    coroutineScope.launch {
                        scrollState.animateScrollTo(0)
                    }
                }
            }
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
