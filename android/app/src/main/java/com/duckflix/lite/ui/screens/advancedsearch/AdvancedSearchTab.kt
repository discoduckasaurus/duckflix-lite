package com.duckflix.lite.ui.screens.advancedsearch

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.CollectionItem
import com.duckflix.lite.ui.components.FilterToggleButton
import com.duckflix.lite.ui.components.FocusableSearchBar
import com.duckflix.lite.ui.components.LoadingIndicator
import com.duckflix.lite.ui.components.MediaCard
import com.duckflix.lite.ui.theme.Dimens
import com.duckflix.lite.ui.theme.TvOsGradientLazyRow
import com.duckflix.lite.ui.theme.rememberGlowColor

/**
 * Advanced Search Tab - Browse and filter content with multiple criteria.
 * Features inline TV/Movie toggles next to search bar and collapsible advanced filters.
 */
@Composable
fun AdvancedSearchTab(
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AdvancedSearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val resultsFocusRequester = remember { FocusRequester() }

    // Note: verticalScroll removed - parent VodContainerScreen handles scrolling
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Search Bar Row with inline toggles (simplified - no advanced filters)
        SearchBarRow(
            query = uiState.query,
            onQueryChange = viewModel::setQuery,
            onSearch = viewModel::search,
            tvSelected = uiState.tvSelected,
            movieSelected = uiState.movieSelected,
            onToggleTv = viewModel::toggleTv,
            onToggleMovie = viewModel::toggleMovie,
            searchFocusRequester = searchFocusRequester,
            onNavigateDown = {
                focusManager.clearFocus()
                try {
                    resultsFocusRequester.requestFocus()
                } catch (_: IllegalStateException) {
                    // FocusRequester not attached yet
                }
            }
        )

        // Results Count
        if (!uiState.isLoading && uiState.results.isNotEmpty()) {
            Text(
                text = "${uiState.totalResults} results",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = Dimens.edgePadding)
            )
        }

        // Error state
        if (uiState.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = uiState.error ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Loading state
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }

        // Results Grid
        if (!uiState.isLoading && uiState.results.isNotEmpty()) {
            ResultsGrid(
                results = uiState.results,
                onContentClick = onContentClick,
                resultsFocusRequester = resultsFocusRequester,
                onEndReached = viewModel::loadMore
            )
        }

        // Empty state - show prompt to search
        if (!uiState.isLoading && uiState.results.isEmpty() && uiState.error == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uiState.query.isBlank()) "Enter a search term to find content" else "No results found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        // Bottom spacing
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    tvSelected: Boolean,
    movieSelected: Boolean,
    onToggleTv: () -> Unit,
    onToggleMovie: () -> Unit,
    searchFocusRequester: FocusRequester,
    onNavigateDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.edgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Search Input
        FocusableSearchBar(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search...",
            onSearch = onSearch,
            modifier = Modifier.weight(1f),
            height = 48.dp,
            focusRequester = searchFocusRequester,
            onNavigateDown = onNavigateDown
        )

        // TV Toggle (inline)
        FilterToggleButton(
            label = "TV",
            isSelected = tvSelected,
            onToggle = onToggleTv
        )

        // Movie Toggle (inline)
        FilterToggleButton(
            label = "Movie",
            isSelected = movieSelected,
            onToggle = onToggleMovie
        )
    }
}

@Composable
private fun ResultsGrid(
    results: List<CollectionItem>,
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    resultsFocusRequester: FocusRequester,
    onEndReached: (() -> Unit)? = null
) {
    // Display results in a horizontal carousel with gradient glow (matches My Stuff style)
    TvOsGradientLazyRow(
        verticalPadding = 20.dp,
        onEndReached = onEndReached
    ) {
        items(results.size, key = { results[it].let { item -> "${it}_${item.id}_${item.mediaType}" } }) { index ->
            val item = results[index]
            val glowColor = rememberGlowColor(index, results.size)
            ResultCard(
                item = item,
                onClick = { onContentClick(item.id, item.mediaType) },
                focusRequester = if (index == 0) resultsFocusRequester else null,
                borderColor = glowColor
            )
        }
    }
}

@Composable
private fun ResultCard(
    item: CollectionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    borderColor: Color = Color.White
) {
    MediaCard(
        onClick = onClick,
        modifier = modifier
            .width(128.dp)
            .height(240.dp),
        focusRequester = focusRequester,
        borderColor = borderColor
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
