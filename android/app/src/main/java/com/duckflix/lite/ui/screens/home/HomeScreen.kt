package com.duckflix.lite.ui.screens.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.duckflix.lite.Screen
import com.duckflix.lite.data.local.entity.WatchProgressEntity
import com.duckflix.lite.data.local.entity.WatchlistEntity
import com.duckflix.lite.ui.components.FocusableCard
import com.duckflix.lite.ui.components.MenuCard

@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "DuckFlix Lite",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Main Menu
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MenuCard(
                title = "Search",
                subtitle = "Find Movies & TV Shows",
                onClick = { navController.navigate(Screen.Search.route) }
            )

            MenuCard(
                title = "Live TV",
                subtitle = "Watch Live Channels",
                onClick = { /* TODO: navController.navigate(Screen.LiveTV.route) */ }
            )

            MenuCard(
                title = "DVR",
                subtitle = "Your Recordings",
                onClick = { /* TODO: navController.navigate(Screen.Dvr.route) */ }
            )

            MenuCard(
                title = "Settings",
                subtitle = "App Settings",
                onClick = { navController.navigate(Screen.Settings.route) }
            )
        }

        // Continue Watching Section
        if (uiState.continueWatching.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Continue Watching",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            ContinueWatchingRow(
                items = uiState.continueWatching,
                onItemClick = { item ->
                    navController.navigate(Screen.Detail.createRoute(item.tmdbId, item.type))
                }
            )
        }

        // My Watchlist Section
        if (uiState.watchlist.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "My Watchlist",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
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
}

@Composable
private fun ContinueWatchingRow(
    items: List<WatchProgressEntity>,
    onItemClick: (WatchProgressEntity) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items) { item ->
            FocusableCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(150.dp)
                    .height(260.dp)
            ) {
                Column {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp),
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1
                        )
                        val progressPercent = ((item.position.toFloat() / item.duration) * 100).toInt()
                        Text(
                            text = "$progressPercent% watched",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
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
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items) { item ->
            FocusableCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(200.dp)
                    .height(340.dp)
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
                            .height(210.dp),
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1
                        )
                        if (item.year != null) {
                            Text(
                                text = item.year,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}
