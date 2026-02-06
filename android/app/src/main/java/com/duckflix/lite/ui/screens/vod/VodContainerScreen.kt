package com.duckflix.lite.ui.screens.vod

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.duckflix.lite.Screen
import com.duckflix.lite.ui.components.ExitConfirmationDialog
import com.duckflix.lite.ui.components.FocusableCard
import com.duckflix.lite.ui.components.VodTab
import com.duckflix.lite.ui.components.VodTabBar
import com.duckflix.lite.ui.screens.home.HomeViewModel
import com.duckflix.lite.ui.screens.mystuff.MyStuffTab
import com.duckflix.lite.ui.screens.discover.DiscoverTab
import com.duckflix.lite.ui.screens.advancedsearch.AdvancedSearchTab
import com.duckflix.lite.ui.screens.providers.ClassicModeTab

/**
 * Main VOD container screen that replaces HomeScreen content.
 * Contains top navigation menu, VodTabBar, and tab content area.
 */
@Composable
fun VodContainerScreen(
    navController: NavController,
    vodViewModel: VodContainerViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val vodUiState by vodViewModel.uiState.collectAsState()
    val homeUiState by homeViewModel.uiState.collectAsState()
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

        // Top Menu Row: [VOD*] [Live TV] [DVR] [Admin?] [Settings]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // VOD tile - highlighted as selected
            CompactMenuTile(
                title = "VOD",
                isSelected = true,
                onClick = { /* Already on VOD screen */ }
            )
            CompactMenuTile(
                title = "Live TV",
                isSelected = false,
                onClick = { navController.navigate(Screen.LiveTV.route) }
            )
            CompactMenuTile(
                title = "DVR",
                isSelected = false,
                onClick = { navController.navigate(Screen.Dvr.route) }
            )
            if (vodUiState.isAdmin) {
                CompactMenuTile(
                    title = "Admin",
                    isSelected = false,
                    onClick = { navController.navigate(Screen.Admin.route) }
                )
            }
            CompactMenuTile(
                title = "Settings",
                isSelected = false,
                onClick = { navController.navigate(Screen.Settings.route) }
            )
        }

        // VodTabBar: [My Stuff] [Search] [Discover] [Classic Mode]
        VodTabBar(
            tabs = VodTab.entries,
            selectedTab = vodUiState.selectedTab,
            onTabSelected = { tab -> vodViewModel.selectTab(tab) }
        )

        // Tab content area with Crossfade animation
        Crossfade(
            targetState = vodUiState.selectedTab,
            label = "VodTabContent"
        ) { selectedTab ->
            when (selectedTab) {
                VodTab.MY_STUFF -> {
                    MyStuffTab(
                        continueWatching = homeUiState.continueWatching,
                        watchlist = homeUiState.watchlist,
                        recommendations = homeUiState.recommendations,
                        isLoadingRecommendations = homeUiState.isLoadingRecommendations,
                        recommendationsError = homeUiState.recommendationsError,
                        onContinueWatchingClick = { item ->
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
                        onContinueWatchingDismiss = { item ->
                            item.jobId?.let { homeViewModel.dismissFailedDownload(it) }
                        },
                        onContinueWatchingRetry = { item ->
                            homeViewModel.retryFailedDownload(item) {
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
                        },
                        onWatchlistClick = { item ->
                            navController.navigate(Screen.Detail.createRoute(item.tmdbId, item.type))
                        },
                        onWatchlistLongPress = { item ->
                            homeViewModel.removeFromWatchlist(item.tmdbId)
                        },
                        onRecommendationClick = { item ->
                            navController.navigate(Screen.Detail.createRoute(item.id, item.mediaType))
                        }
                    )
                }
                VodTab.SEARCH -> {
                    AdvancedSearchTab(
                        onContentClick = { tmdbId, mediaType ->
                            navController.navigate(Screen.Detail.createRoute(tmdbId, mediaType))
                        }
                    )
                }
                VodTab.DISCOVER -> {
                    DiscoverTab(
                        onContentClick = { tmdbId, mediaType ->
                            navController.navigate(Screen.Detail.createRoute(tmdbId, mediaType))
                        }
                    )
                }
                VodTab.CLASSIC_MODE -> {
                    ClassicModeTab(
                        onProviderClick = { provider ->
                            navController.navigate(
                                Screen.ProviderDetail.createRoute(
                                    providerId = provider.providerId,
                                    name = provider.providerName,
                                    logoUrl = provider.logoUrl
                                )
                            )
                        }
                    )
                }
            }
        }

        // Bottom spacing for TV safe zone
        Spacer(modifier = Modifier.height(48.dp))
    }
}

/**
 * Compact menu tile for top navigation bar.
 * Shows primary color background when selected.
 */
@Composable
private fun CompactMenuTile(
    title: String,
    isSelected: Boolean,
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
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isSelected) {
                        Modifier.background(MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier
                    }
                ),
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

/**
 * Placeholder composable for tabs that are not yet implemented
 */
@Composable
private fun PlaceholderTab(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}
