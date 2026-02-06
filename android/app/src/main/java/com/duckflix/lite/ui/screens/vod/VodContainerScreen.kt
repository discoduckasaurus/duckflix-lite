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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.duckflix.lite.ui.components.ExitConfirmationDialog
import com.duckflix.lite.ui.components.LogoButton
import com.duckflix.lite.ui.components.LogoutConfirmationDialog
import com.duckflix.lite.ui.components.VodTab
import com.duckflix.lite.ui.components.VodTabBar
import com.duckflix.lite.ui.screens.home.HomeViewModel
import com.duckflix.lite.ui.screens.mystuff.MyStuffTab
import com.duckflix.lite.ui.screens.discover.DiscoverTab
import com.duckflix.lite.ui.screens.advancedsearch.AdvancedSearchTab
import com.duckflix.lite.ui.screens.providers.ClassicModeTab
import com.duckflix.lite.ui.theme.Dimens
import com.duckflix.lite.ui.theme.TextSizes
import com.duckflix.lite.ui.theme.scaled

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
    var showLogoutDialog by remember { mutableStateOf(false) }

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

    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onLogout = {
                vodViewModel.logout {
                    navController.navigate(Screen.LoginUsername.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            },
            onDismiss = {
                showLogoutDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Top navigation row - edge-to-edge with LogoButton + Menu tiles
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.screenPaddingVertical, start = Dimens.edgePadding, end = Dimens.edgePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.itemSpacing)
        ) {
            LogoButton(
                onClick = { showLogoutDialog = true },
                size = Dimens.menuTileHeight
            )
            CompactMenuTile(
                title = "VOD",
                isSelected = true,
                onClick = { /* Already on VOD screen */ },
                modifier = Modifier.weight(1f)
            )
            CompactMenuTile(
                title = "Live TV",
                isSelected = false,
                onClick = { navController.navigate(Screen.LiveTV.route) },
                modifier = Modifier.weight(1f)
            )
            CompactMenuTile(
                title = "DVR",
                isSelected = false,
                onClick = { /* Coming soon */ },
                modifier = Modifier.weight(1f),
                enabled = false
            )
            CompactMenuTile(
                title = "Settings",
                isSelected = false,
                onClick = { navController.navigate(Screen.Settings.route) },
                modifier = Modifier.weight(1f)
            )
        }

        // VodTabBar row - same padding as top row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.spacingMedium, start = Dimens.edgePadding, end = Dimens.edgePadding)
        ) {
            VodTabBar(
                tabs = VodTab.entries,
                selectedTab = vodUiState.selectedTab,
                onTabSelected = { tab -> vodViewModel.selectTab(tab) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Content with padding
        Column(
            modifier = Modifier.padding(horizontal = Dimens.screenPaddingHorizontal, vertical = Dimens.spacingMedium),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMedium)
        ) {

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
}

/**
 * Compact menu tile for top navigation bar.
 * Shows primary color background when selected, focus border when focused.
 */
@Composable
private fun CompactMenuTile(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    val backgroundColor = when {
        !enabled -> Color(0xFF2A2A2A)  // Greyed out
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }

    val borderModifier = if (isFocused && enabled) {
        Modifier.border(4.dp, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }

    val textColor = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .height(Dimens.menuTileHeight)
            .clip(shape)
            .then(borderModifier)
            .background(backgroundColor, shape)
            .then(
                if (enabled) {
                    Modifier
                        .focusable(interactionSource = interactionSource)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = TextSizes.menuTile,
            color = textColor
        )
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
