package com.duckflix.lite.ui.screens.discover

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.CollectionItem
import com.duckflix.lite.data.remote.dto.GenreDto
import com.duckflix.lite.data.remote.dto.TrendingResult
import com.duckflix.lite.ui.components.FilterDropdown
import com.duckflix.lite.ui.components.MediaCard
import com.duckflix.lite.ui.theme.Dimens
import com.duckflix.lite.ui.theme.TvOsColors
import com.duckflix.lite.ui.theme.TvOsGradientLazyRow
import com.duckflix.lite.ui.theme.rememberGlowColor
import com.duckflix.lite.ui.theme.tvOsScaleOnly

/**
 * Discover Tab - Browse trending, popular, top rated, and now playing content
 * with TV/Movie filtering and advanced filters for paginated browse.
 */
@Composable
fun DiscoverTab(
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val decades = remember { Decade.generateDecades() }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Single filter row with all controls inline
        InlineFilterRow(
            currentFilter = uiState.mediaTypeFilter,
            onFilterChange = { viewModel.setMediaTypeFilter(it) },
            availableGenres = uiState.availableGenres,
            selectedGenre = uiState.selectedGenre,
            onGenreChange = { viewModel.setSelectedGenre(it) },
            decades = decades,
            selectedDecade = uiState.selectedDecade,
            onDecadeChange = { viewModel.setSelectedDecade(it) },
            sortBy = uiState.sortBy,
            onSortChange = { viewModel.setSortBy(it) },
            hasActiveFilters = uiState.hasActiveFilters,
            onClearFilters = { viewModel.clearFilters() }
        )

        // Show either browse rows or filter results rows
        if (uiState.showFilterResults) {
            FilterResultsRows(
                uiState = uiState,
                onContentClick = onContentClick,
                onLoadMore = { viewModel.loadMoreResults() }
            )
        } else {
            BrowseContent(
                uiState = uiState,
                onContentClick = onContentClick,
                onLoadMoreTrending = viewModel::loadMoreTrending,
                onLoadMorePopular = viewModel::loadMorePopular,
                onLoadMoreTopRated = viewModel::loadMoreTopRated,
                onLoadMoreNowPlaying = viewModel::loadMoreNowPlaying
            )
        }
    }
}

@Composable
private fun InlineFilterRow(
    currentFilter: MediaTypeFilter,
    onFilterChange: (MediaTypeFilter) -> Unit,
    availableGenres: List<GenreDto>,
    selectedGenre: Int?,
    onGenreChange: (Int?) -> Unit,
    decades: List<Decade>,
    selectedDecade: Decade?,
    onDecadeChange: (Decade?) -> Unit,
    sortBy: SortOption,
    onSortChange: (SortOption) -> Unit,
    hasActiveFilters: Boolean,
    onClearFilters: () -> Unit
) {
    // Filter sort options based on media type
    val availableSortOptions = remember(currentFilter) {
        SortOption.entries.filter { it.isAvailable(currentFilter) }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = Dimens.edgePadding, vertical = 4.dp)
    ) {
        // 3-stage media type toggle (All | Movies | TV)
        item {
            MediaTypeToggle(
                currentFilter = currentFilter,
                onFilterChange = onFilterChange
            )
        }

        // Genre Dropdown
        item {
            FilterDropdown(
                label = "Genre",
                options = listOf(null) + availableGenres,
                selectedOptions = if (selectedGenre == null) {
                    listOf(null)
                } else {
                    availableGenres.filter { it.id == selectedGenre }
                },
                onSelectionChange = { selected ->
                    val genre = selected.firstOrNull()
                    onGenreChange(if (genre is GenreDto) genre.id else null)
                },
                optionLabel = { if (it == null) "All Genres" else (it as? GenreDto)?.name ?: "Unknown" },
                multiSelect = false
            )
        }

        // Decade Dropdown
        item {
            FilterDropdown(
                label = "Decade",
                options = listOf(null) + decades,
                selectedOptions = listOf(selectedDecade),
                onSelectionChange = { selected ->
                    onDecadeChange(selected.firstOrNull() as? Decade)
                },
                optionLabel = { if (it == null) "All Time" else (it as? Decade)?.displayName ?: "Unknown" },
                multiSelect = false
            )
        }

        // Sort Dropdown
        item {
            FilterDropdown(
                label = "Sort",
                options = availableSortOptions,
                selectedOptions = listOf(sortBy),
                onSelectionChange = { selected ->
                    selected.firstOrNull()?.let { onSortChange(it) }
                },
                optionLabel = { option ->
                    val sort = option as? SortOption
                    if (sort == null) "Unknown"
                    else if (sort.movieOnly) "${sort.displayName} (Movie)"
                    else sort.displayName
                },
                multiSelect = false
            )
        }

        // Clear Filters Button (only show when filters are active)
        if (hasActiveFilters) {
            item {
                ClearFiltersButton(onClick = onClearFilters)
            }
        }
    }
}

/**
 * 3-stage toggle: All | Movies | TV
 * Clicking cycles through the options
 */
@Composable
private fun MediaTypeToggle(
    currentFilter: MediaTypeFilter,
    onFilterChange: (MediaTypeFilter) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)

    Row(
        modifier = Modifier
            .height(48.dp)
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.03f)
            .clip(shape)
            .background(color = Color(0xFF2A2A2A), shape = shape)
            .then(if (isFocused) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    // Cycle through: ALL -> MOVIE -> TV -> ALL
                    val nextFilter = when (currentFilter) {
                        MediaTypeFilter.ALL -> MediaTypeFilter.MOVIE
                        MediaTypeFilter.MOVIE -> MediaTypeFilter.TV
                        MediaTypeFilter.TV -> MediaTypeFilter.ALL
                    }
                    onFilterChange(nextFilter)
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleSegment(
            text = "All",
            isSelected = currentFilter == MediaTypeFilter.ALL,
            isFirst = true
        )
        ToggleSegment(
            text = "Movies",
            isSelected = currentFilter == MediaTypeFilter.MOVIE
        )
        ToggleSegment(
            text = "TV",
            isSelected = currentFilter == MediaTypeFilter.TV,
            isLast = true
        )
    }
}

@Composable
private fun ToggleSegment(
    text: String,
    isSelected: Boolean,
    isFirst: Boolean = false,
    isLast: Boolean = false
) {
    val gradientBrush = Brush.linearGradient(colors = TvOsColors.gradientColors)
    val shape = when {
        isFirst -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
        isLast -> RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
        else -> RoundedCornerShape(0.dp)
    }

    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(shape)
            .then(
                if (isSelected) Modifier.background(brush = gradientBrush, shape = shape)
                else Modifier
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ClearFiltersButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .height(48.dp)
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.03f)
            .clip(shape)
            .background(color = if (isFocused) Color(0xFFCC3333) else Color(0xFF882222), shape = shape)
            .then(if (isFocused) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Clear",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}

@Composable
private fun FilterResultsRows(
    uiState: DiscoverUiState,
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    onLoadMore: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Results count - use actual count if totalResults is wrong
        if (!uiState.isLoadingFilterResults && uiState.filterResults.isNotEmpty()) {
            val displayCount = if (uiState.filterResultsTotal > 0) uiState.filterResultsTotal else uiState.filterResults.size
            Text(
                text = "$displayCount results",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = Dimens.edgePadding, vertical = 8.dp)
            )
        }

        // Loading state
        if (uiState.isLoadingFilterResults) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // Error state
        if (uiState.filterError != null) {
            Text(
                text = "Error: ${uiState.filterError}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Red,
                modifier = Modifier.padding(horizontal = Dimens.edgePadding)
            )
        }

        // Results rows
        if (!uiState.isLoadingFilterResults && uiState.filterResults.isNotEmpty()) {
            when (uiState.mediaTypeFilter) {
                MediaTypeFilter.ALL -> {
                    // Split into movies and TV shows
                    val movies = uiState.filterResults.filter { it.mediaType == "movie" }
                    val tvShows = uiState.filterResults.filter { it.mediaType == "tv" }

                    if (movies.isNotEmpty()) {
                        FilterResultSection(
                            title = "Movies",
                            items = movies,
                            onItemClick = onContentClick,
                            onEndReached = onLoadMore,
                            isLoadingMore = uiState.isLoadingMoreResults
                        )
                    }

                    if (tvShows.isNotEmpty()) {
                        FilterResultSection(
                            title = "TV Shows",
                            items = tvShows,
                            onItemClick = onContentClick,
                            onEndReached = onLoadMore,
                            isLoadingMore = uiState.isLoadingMoreResults
                        )
                    }
                }
                MediaTypeFilter.MOVIE -> {
                    FilterResultSection(
                        title = "Movies",
                        items = uiState.filterResults,
                        onItemClick = onContentClick,
                        onEndReached = onLoadMore,
                        isLoadingMore = uiState.isLoadingMoreResults
                    )
                }
                MediaTypeFilter.TV -> {
                    FilterResultSection(
                        title = "TV Shows",
                        items = uiState.filterResults,
                        onItemClick = onContentClick,
                        onEndReached = onLoadMore,
                        isLoadingMore = uiState.isLoadingMoreResults
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Empty state
        if (!uiState.isLoadingFilterResults && uiState.filterResults.isEmpty() && uiState.filterError == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No results found. Try different filters.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterResultSection(
    title: String,
    items: List<CollectionItem>,
    onItemClick: (tmdbId: Int, mediaType: String) -> Unit,
    onEndReached: () -> Unit,
    isLoadingMore: Boolean
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

        TvOsGradientLazyRow(
            gradientAlpha = 0.5f,
            verticalPadding = 20.dp,
            onEndReached = onEndReached
        ) {
            items(items.size, key = { "${it}_${items[it].id}_${items[it].mediaType}" }) { index ->
                val item = items[index]
                val glowColor = rememberGlowColor(index, items.size)
                MediaCard(
                    onClick = { onItemClick(item.id, item.mediaType) },
                    modifier = Modifier
                        .width(128.dp)
                        .height(240.dp),
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
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
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

            // Loading more indicator at end of row
            if (isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    item: CollectionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = Color.White
) {
    MediaCard(
        onClick = onClick,
        modifier = modifier
            .width(140.dp)
            .height(260.dp),
        borderColor = borderColor
    ) {
        Column {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
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

@Composable
private fun BrowseContent(
    uiState: DiscoverUiState,
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    onLoadMoreTrending: () -> Unit,
    onLoadMorePopular: () -> Unit,
    onLoadMoreTopRated: () -> Unit,
    onLoadMoreNowPlaying: () -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Trending Section
        ContentSection(title = "Trending") {
            when {
                uiState.isLoadingTrending -> LoadingRow()
                uiState.trendingError != null -> ErrorText(uiState.trendingError)
                uiState.trending.isNotEmpty() -> TrendingRow(
                    items = uiState.trending,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) },
                    onEndReached = onLoadMoreTrending
                )
            }
        }

        // Popular Section
        ContentSection(title = "Popular") {
            when {
                uiState.isLoadingPopular -> LoadingRow()
                uiState.popularError != null -> ErrorText(uiState.popularError)
                uiState.popular.isNotEmpty() -> CollectionRow(
                    items = uiState.popular,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) },
                    onEndReached = onLoadMorePopular
                )
            }
        }

        // Top Rated Section
        ContentSection(title = "Top Rated") {
            when {
                uiState.isLoadingTopRated -> LoadingRow()
                uiState.topRatedError != null -> ErrorText(uiState.topRatedError)
                uiState.topRated.isNotEmpty() -> CollectionRow(
                    items = uiState.topRated,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) },
                    onEndReached = onLoadMoreTopRated
                )
            }
        }

        // Now Playing Section (Movies only)
        if (uiState.mediaTypeFilter != MediaTypeFilter.TV) {
            ContentSection(title = "Now Playing") {
                when {
                    uiState.isLoadingNowPlaying -> LoadingRow()
                    uiState.nowPlayingError != null -> ErrorText(uiState.nowPlayingError)
                    uiState.nowPlaying.isNotEmpty() -> CollectionRow(
                        items = uiState.nowPlaying,
                        onItemClick = { item -> onContentClick(item.id, item.mediaType) },
                        onEndReached = onLoadMoreNowPlaying
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
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
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
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
    onItemClick: (TrendingResult) -> Unit,
    onEndReached: (() -> Unit)? = null
) {
    TvOsGradientLazyRow(gradientAlpha = 0.5f, verticalPadding = 20.dp, onEndReached = onEndReached) {
        items(items.size, key = { items[it].let { item -> "${it}_${item.id}_${item.mediaType}" } }) { index ->
            val item = items[index]
            val glowColor = rememberGlowColor(index, items.size)
            MediaCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(128.dp)
                    .height(240.dp),
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
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
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
    onItemClick: (CollectionItem) -> Unit,
    onEndReached: (() -> Unit)? = null
) {
    TvOsGradientLazyRow(gradientAlpha = 0.5f, verticalPadding = 20.dp, onEndReached = onEndReached) {
        items(items.size, key = { items[it].let { item -> "${it}_${item.id}_${item.mediaType}" } }) { index ->
            val item = items[index]
            val glowColor = rememberGlowColor(index, items.size)
            MediaCard(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .width(128.dp)
                    .height(240.dp),
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
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
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
