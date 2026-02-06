package com.duckflix.lite.ui.screens.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.CollectionItem
import com.duckflix.lite.data.remote.dto.TrendingResult
import com.duckflix.lite.ui.components.FilterToggleButton
import com.duckflix.lite.ui.components.MediaCard

/**
 * Discover Tab - Browse trending, popular, top rated, and now playing content
 * with TV/Movie filtering support.
 */
@Composable
fun DiscoverTab(
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Note: verticalScroll removed - parent VodContainerScreen handles scrolling
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Filter Row
        FilterRow(
            currentFilter = uiState.mediaTypeFilter,
            onFilterChange = { viewModel.setMediaTypeFilter(it) }
        )

        // Trending Section
        ContentSection(title = "Trending") {
            when {
                uiState.isLoadingTrending -> LoadingRow()
                uiState.trendingError != null -> ErrorText(uiState.trendingError!!)
                uiState.trending.isNotEmpty() -> TrendingRow(
                    items = uiState.trending,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) }
                )
            }
        }

        // Popular Section
        ContentSection(title = "Popular") {
            when {
                uiState.isLoadingPopular -> LoadingRow()
                uiState.popularError != null -> ErrorText(uiState.popularError!!)
                uiState.popular.isNotEmpty() -> CollectionRow(
                    items = uiState.popular,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) }
                )
            }
        }

        // Top Rated Section
        ContentSection(title = "Top Rated") {
            when {
                uiState.isLoadingTopRated -> LoadingRow()
                uiState.topRatedError != null -> ErrorText(uiState.topRatedError!!)
                uiState.topRated.isNotEmpty() -> CollectionRow(
                    items = uiState.topRated,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) }
                )
            }
        }

        // Now Playing Section (Movies only - hidden when TV filter is active)
        if (uiState.mediaTypeFilter != MediaTypeFilter.TV) {
            ContentSection(title = "Now Playing") {
                when {
                    uiState.isLoadingNowPlaying -> LoadingRow()
                    uiState.nowPlayingError != null -> ErrorText(uiState.nowPlayingError!!)
                    uiState.nowPlaying.isNotEmpty() -> CollectionRow(
                        items = uiState.nowPlaying,
                        onItemClick = { item -> onContentClick(item.id, item.mediaType) }
                    )
                }
            }
        }

        // Bottom spacing
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun FilterRow(
    currentFilter: MediaTypeFilter,
    onFilterChange: (MediaTypeFilter) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // TV toggle
        FilterToggleButton(
            label = "TV",
            isSelected = currentFilter == MediaTypeFilter.TV,
            onToggle = {
                val newFilter = when (currentFilter) {
                    MediaTypeFilter.ALL -> MediaTypeFilter.TV
                    MediaTypeFilter.TV -> MediaTypeFilter.ALL
                    MediaTypeFilter.MOVIE -> MediaTypeFilter.ALL // Both on = ALL
                }
                onFilterChange(newFilter)
            }
        )

        // Movie toggle
        FilterToggleButton(
            label = "Movie",
            isSelected = currentFilter == MediaTypeFilter.MOVIE,
            onToggle = {
                val newFilter = when (currentFilter) {
                    MediaTypeFilter.ALL -> MediaTypeFilter.MOVIE
                    MediaTypeFilter.MOVIE -> MediaTypeFilter.ALL
                    MediaTypeFilter.TV -> MediaTypeFilter.ALL // Both on = ALL
                }
                onFilterChange(newFilter)
            }
        )
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
private fun ErrorText(error: String) {
    Text(
        text = "Error: $error",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Red,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun TrendingRow(
    items: List<TrendingResult>,
    onItemClick: (TrendingResult) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { "${it.id}_${it.mediaType}" }) { item ->
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

@Composable
private fun CollectionRow(
    items: List<CollectionItem>,
    onItemClick: (CollectionItem) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { "${it.id}_${it.mediaType}" }) { item ->
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
