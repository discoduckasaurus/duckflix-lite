package com.duckflix.lite.ui.screens.vod

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.duckflix.lite.Screen
import com.duckflix.lite.ui.components.LogoButton
import com.duckflix.lite.ui.components.VodTab
import com.duckflix.lite.ui.components.VodTabBar
import com.duckflix.lite.ui.screens.home.HomeViewModel
import com.duckflix.lite.ui.screens.mystuff.MyStuffTab
import com.duckflix.lite.ui.screens.discover.DiscoverTab
import com.duckflix.lite.ui.screens.advancedsearch.AdvancedSearchTab
import com.duckflix.lite.ui.screens.providers.ClassicModeTab
import com.duckflix.lite.ui.theme.Dimens

/**
 * Main VOD container screen that replaces HomeScreen content.
 * Contains top navigation menu, VodTabBar, and tab content area.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VodContainerScreen(
    navController: NavController,
    vodViewModel: VodContainerViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val vodUiState by vodViewModel.uiState.collectAsState()
    val homeUiState by homeViewModel.uiState.collectAsState()
    val topNavBringIntoView = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    BackHandler {
        navController.navigateUp()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { clip = false }
            .verticalScroll(rememberScrollState())
    ) {
        // Top section with VodTabBar
        Column(
            modifier = Modifier
                .bringIntoViewRequester(topNavBringIntoView)
                .onFocusChanged { focusState ->
                    if (focusState.hasFocus) {
                        coroutineScope.launch {
                            topNavBringIntoView.bringIntoView()
                        }
                    }
                }
        ) {
            Spacer(modifier = Modifier.height(Dimens.screenPaddingVertical))

            // Logo + VodTabBar row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.edgePadding, end = Dimens.edgePadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LogoButton(
                    onClick = { navController.navigateUp() },
                    size = Dimens.tabBarHeight
                )
                VodTabBar(
                    tabs = VodTab.entries,
                    selectedTab = vodUiState.selectedTab,
                    onTabSelected = { tab -> vodViewModel.selectTab(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Content - NO horizontal padding here so LazyRows can extend edge-to-edge
        // graphicsLayer { clip = false } prevents clipping of glow effects
        Column(
            modifier = Modifier
                .padding(vertical = Dimens.spacingMedium)
                .graphicsLayer { clip = false },
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
                            onContinueWatchingResume = { item ->
                                navController.navigate(
                                    Screen.Player.createRoute(
                                        tmdbId = item.tmdbId,
                                        title = item.title,
                                        year = null,
                                        type = item.type,
                                        season = item.season,
                                        episode = item.episode,
                                        resumePosition = if (item.position > 0) item.position else null,
                                        posterUrl = item.posterUrl,
                                        logoUrl = item.logoUrl
                                    )
                                )
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
                                            posterUrl = item.posterUrl,
                                            logoUrl = item.logoUrl
                                        )
                                    )
                                }
                            },
                            onContinueWatchingDetails = { item ->
                                navController.navigate(Screen.Detail.createRoute(item.tmdbId, item.type))
                            },
                            onContinueWatchingRemove = { item ->
                                if (item.isFailed && item.jobId != null) {
                                    homeViewModel.dismissFailedDownload(item.jobId)
                                } else {
                                    homeViewModel.removeFromContinueWatching(item)
                                }
                            },
                            onWatchlistDetails = { item ->
                                navController.navigate(Screen.Detail.createRoute(item.tmdbId, item.type))
                            },
                            onWatchlistRemove = { item ->
                                homeViewModel.removeFromWatchlist(item.tmdbId, item.type)
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
