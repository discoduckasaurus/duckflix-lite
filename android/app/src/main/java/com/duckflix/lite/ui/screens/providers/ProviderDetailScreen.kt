package com.duckflix.lite.ui.screens.providers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.CollectionItem
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.FocusableSearchBar
import com.duckflix.lite.ui.components.MediaCard
import com.duckflix.lite.ui.theme.Dimens
import com.duckflix.lite.ui.theme.TvOsGradientLazyRow
import com.duckflix.lite.ui.theme.rememberGlowColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Provider Detail Screen - Full-screen page for browsing content from a specific streaming provider.
 * Shows discover (mixed), movies, and TV shows sections filtered by the provider.
 */
@Composable
fun ProviderDetailScreen(
    providerId: Int,
    providerName: String,
    providerLogoUrl: String?,
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProviderDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val discoverFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }

    // Initialize ViewModel with provider info
    LaunchedEffect(providerId, providerName, providerLogoUrl) {
        viewModel.setProvider(providerId, providerName, providerLogoUrl)
    }

    // Request focus on first discover item when content loads
    LaunchedEffect(uiState.discover) {
        if (uiState.discover.isNotEmpty()) {
            delay(100)
            try {
                discoverFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {}
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row with edge padding
        HeaderRow(
            providerName = providerName,
            providerLogoUrl = providerLogoUrl,
            onNavigateBack = onNavigateBack,
            backButtonFocusRequester = backButtonFocusRequester
        )

        // Search Bar Row with edge padding
        SearchBarRow(
            query = uiState.query,
            onQueryChange = { viewModel.setQuery(it) },
            onSearch = { viewModel.search() },
            onNavigateUp = {
                try {
                    backButtonFocusRequester.requestFocus()
                } catch (_: IllegalStateException) {}
            },
            onNavigateDown = {
                try {
                    discoverFocusRequester.requestFocus()
                } catch (_: IllegalStateException) {}
            }
        )

        // Discover Section (mixed content)
        ContentSection(title = "Discover") {
            when {
                uiState.isLoadingDiscover -> LoadingRow()
                uiState.discoverError != null -> ErrorText(uiState.discoverError!!)
                uiState.discover.isNotEmpty() -> CollectionRow(
                    items = uiState.discover,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) },
                    firstItemFocusRequester = discoverFocusRequester
                )
                else -> EmptyText("No content found")
            }
        }

        // Movies Section
        ContentSection(title = "Movies") {
            when {
                uiState.isLoadingMovies -> LoadingRow()
                uiState.moviesError != null -> ErrorText(uiState.moviesError!!)
                uiState.movies.isNotEmpty() -> CollectionRow(
                    items = uiState.movies,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) }
                )
                else -> EmptyText("No movies found")
            }
        }

        // TV Shows Section
        ContentSection(title = "TV Shows") {
            when {
                uiState.isLoadingTvShows -> LoadingRow()
                uiState.tvShowsError != null -> ErrorText(uiState.tvShowsError!!)
                uiState.tvShows.isNotEmpty() -> CollectionRow(
                    items = uiState.tvShows,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) }
                )
                else -> EmptyText("No TV shows found")
            }
        }

        // Bottom spacing
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun HeaderRow(
    providerName: String,
    providerLogoUrl: String?,
    onNavigateBack: () -> Unit,
    backButtonFocusRequester: FocusRequester? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.edgePadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back Button
        FocusableButton(
            onClick = onNavigateBack,
            modifier = Modifier
                .width(100.dp)
                .height(48.dp),
            focusRequester = backButtonFocusRequester
        ) {
            Text("Back")
        }

        // Provider Logo (if available)
        if (providerLogoUrl != null) {
            Card(
                modifier = Modifier
                    .height(48.dp)
                    .width(48.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = MaterialTheme.shapes.small
            ) {
                AsyncImage(
                    model = providerLogoUrl,
                    contentDescription = "$providerName logo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Provider Name
        Text(
            text = providerName,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.edgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FocusableSearchBar(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search within provider...",
            onSearch = onSearch,
            modifier = Modifier.weight(1f),
            height = 48.dp,
            onNavigateUp = onNavigateUp,
            onNavigateDown = onNavigateDown
        )

        FocusableButton(
            onClick = onSearch,
            modifier = Modifier
                .width(100.dp)
                .height(48.dp)
        ) {
            Text("Search")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = Dimens.edgePadding)
        )
        content()
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.padding(horizontal = Dimens.edgePadding),
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
private fun ErrorText(error: String) {
    Text(
        text = "Error: $error",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Red,
        modifier = Modifier.padding(horizontal = Dimens.edgePadding, vertical = 16.dp)
    )
}

@Composable
private fun EmptyText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Gray,
        modifier = Modifier.padding(horizontal = Dimens.edgePadding, vertical = 16.dp)
    )
}

@Composable
private fun CollectionRow(
    items: List<CollectionItem>,
    onItemClick: (CollectionItem) -> Unit,
    firstItemFocusRequester: FocusRequester? = null
) {
    TvOsGradientLazyRow(
        gradientAlpha = 0.5f,
        verticalPadding = 20.dp
    ) {
        items(items.size, key = { items[it].let { item -> "${item.id}_${item.mediaType}" } }) { index ->
            val item = items[index]
            val glowColor = rememberGlowColor(index, items.size)
            val isFirstItem = index == 0

            MediaCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(128.dp)
                    .height(240.dp),
                focusRequester = if (isFirstItem) firstItemFocusRequester else null,
                borderColor = glowColor
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
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
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
