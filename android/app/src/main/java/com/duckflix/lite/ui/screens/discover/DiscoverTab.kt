package com.duckflix.lite.ui.screens.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import com.duckflix.lite.ui.components.FilterToggleButton
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Filter Row with TV/Movie toggles and Advanced Filters button
        FilterRow(
            currentFilter = uiState.mediaTypeFilter,
            onFilterChange = { viewModel.setMediaTypeFilter(it) },
            advancedFiltersExpanded = uiState.advancedFiltersExpanded,
            onToggleAdvancedFilters = { viewModel.toggleAdvancedFilters() },
            hasActiveFilters = uiState.hasActiveFilters,
            onClearFilters = { viewModel.clearFilters() }
        )

        // Advanced Filter Row (collapsible)
        AnimatedVisibility(
            visible = uiState.advancedFiltersExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            AdvancedFilterRow(
                uiState = uiState,
                decades = decades,
                onGenreChange = { viewModel.setSelectedGenre(it) },
                onDecadeChange = { viewModel.setSelectedDecade(it) },
                onSortChange = { viewModel.setSortBy(it) }
            )
        }

        // Show either browse rows or filter results grid
        if (uiState.showFilterResults) {
            FilterResultsGrid(
                uiState = uiState,
                onContentClick = onContentClick,
                onLoadMore = { viewModel.loadMoreResults() }
            )
        } else {
            BrowseContent(
                uiState = uiState,
                onContentClick = onContentClick
            )
        }
    }
}

@Composable
private fun FilterRow(
    currentFilter: MediaTypeFilter,
    onFilterChange: (MediaTypeFilter) -> Unit,
    advancedFiltersExpanded: Boolean,
    onToggleAdvancedFilters: () -> Unit,
    hasActiveFilters: Boolean,
    onClearFilters: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = Dimens.edgePadding, vertical = 4.dp)
    ) {
        // TV toggle
        item {
            FilterToggleButton(
                label = "TV",
                isSelected = currentFilter == MediaTypeFilter.TV,
                onToggle = {
                    val newFilter = when (currentFilter) {
                        MediaTypeFilter.ALL -> MediaTypeFilter.TV
                        MediaTypeFilter.TV -> MediaTypeFilter.ALL
                        MediaTypeFilter.MOVIE -> MediaTypeFilter.ALL
                    }
                    onFilterChange(newFilter)
                }
            )
        }

        // Movie toggle
        item {
            FilterToggleButton(
                label = "Movie",
                isSelected = currentFilter == MediaTypeFilter.MOVIE,
                onToggle = {
                    val newFilter = when (currentFilter) {
                        MediaTypeFilter.ALL -> MediaTypeFilter.MOVIE
                        MediaTypeFilter.MOVIE -> MediaTypeFilter.ALL
                        MediaTypeFilter.TV -> MediaTypeFilter.ALL
                    }
                    onFilterChange(newFilter)
                }
            )
        }

        // Advanced Filters Button
        item {
            AdvancedFiltersButton(
                isExpanded = advancedFiltersExpanded,
                hasActiveFilters = hasActiveFilters,
                onClick = onToggleAdvancedFilters
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

@Composable
private fun AdvancedFiltersButton(
    isExpanded: Boolean,
    hasActiveFilters: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)

    val gradientBrush = Brush.linearGradient(colors = TvOsColors.gradientColors)

    val backgroundModifier = when {
        isFocused -> Modifier.background(brush = gradientBrush, shape = shape)
        isExpanded || hasActiveFilters -> Modifier.background(brush = gradientBrush, shape = shape)
        else -> Modifier.background(color = Color(0xFF3A3A3A), shape = shape)
    }

    val borderModifier = if (isFocused) {
        Modifier.border(2.dp, gradientBrush, shape)
    } else Modifier

    val textColor = when {
        isFocused || isExpanded || hasActiveFilters -> Color.White
        else -> Color.White.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.03f)
            .clip(shape)
            .then(backgroundModifier)
            .then(borderModifier)
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
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Filters",
                style = MaterialTheme.typography.labelLarge,
                color = textColor
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse filters" else "Expand filters",
                tint = textColor,
                modifier = Modifier.size(18.dp)
            )
        }
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

    val gradientBrush = Brush.linearGradient(colors = TvOsColors.gradientColors)

    Box(
        modifier = modifier
            .height(48.dp)
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.03f)
            .clip(shape)
            .background(color = if (isFocused) Color(0xFFCC3333) else Color(0xFF882222), shape = shape)
            .then(if (isFocused) Modifier.border(2.dp, gradientBrush, shape) else Modifier)
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
private fun AdvancedFilterRow(
    uiState: DiscoverUiState,
    decades: List<Decade>,
    onGenreChange: (Int?) -> Unit,
    onDecadeChange: (Decade?) -> Unit,
    onSortChange: (SortOption) -> Unit
) {
    // Filter sort options based on media type
    val availableSortOptions = remember(uiState.mediaTypeFilter) {
        SortOption.entries.filter { it.isAvailable(uiState.mediaTypeFilter) }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = Dimens.edgePadding, vertical = 4.dp)
    ) {
        // Genre Dropdown (single-select, with "All" option)
        item {
            FilterDropdown(
                label = "Genre",
                options = listOf(null) + uiState.availableGenres,
                selectedOptions = if (uiState.selectedGenre == null) {
                    listOf(null)
                } else {
                    uiState.availableGenres.filter { it.id == uiState.selectedGenre }
                },
                onSelectionChange = { selected ->
                    val genre = selected.firstOrNull()
                    onGenreChange(if (genre is GenreDto) genre.id else null)
                },
                optionLabel = { if (it == null) "All Genres" else (it as GenreDto).name },
                multiSelect = false
            )
        }

        // Decade Dropdown (single-select, with "All" option)
        item {
            FilterDropdown(
                label = "Decade",
                options = listOf(null) + decades,
                selectedOptions = listOf(uiState.selectedDecade),
                onSelectionChange = { selected ->
                    onDecadeChange(selected.firstOrNull() as? Decade)
                },
                optionLabel = { if (it == null) "All Time" else (it as Decade).displayName },
                multiSelect = false
            )
        }

        // Sort Dropdown
        item {
            FilterDropdown(
                label = "Sort",
                options = availableSortOptions,
                selectedOptions = listOf(uiState.sortBy),
                onSelectionChange = { selected ->
                    selected.firstOrNull()?.let { onSortChange(it) }
                },
                optionLabel = { option ->
                    val sort = option as SortOption
                    if (sort.movieOnly) "${sort.displayName} (Movie)" else sort.displayName
                },
                multiSelect = false
            )
        }
    }
}

@Composable
private fun FilterResultsGrid(
    uiState: DiscoverUiState,
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    onLoadMore: () -> Unit
) {
    val gridState = rememberLazyGridState()

    // Detect when we're near the end to load more
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 6 && !uiState.isLoadingMoreResults
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && uiState.filterResults.isNotEmpty()) {
            onLoadMore()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Results count
        if (!uiState.isLoadingFilterResults && uiState.filterResults.isNotEmpty()) {
            Text(
                text = "${uiState.filterResultsTotal} results",
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
                modifier = Modifier.padding(16.dp)
            )
        }

        // Results grid
        if (!uiState.isLoadingFilterResults && uiState.filterResults.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = Dimens.edgePadding,
                    end = Dimens.edgePadding,
                    bottom = 32.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = uiState.filterResults,
                    key = { "${it.id}_${it.mediaType}" }
                ) { item ->
                    ResultCard(
                        item = item,
                        onClick = { onContentClick(item.id, item.mediaType) }
                    )
                }

                // Loading more indicator
                if (uiState.isLoadingMoreResults) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
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

@Composable
private fun ResultCard(
    item: CollectionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MediaCard(
        onClick = onClick,
        modifier = modifier
            .width(140.dp)
            .height(260.dp)
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
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Trending Section
        ContentSection(title = "Trending") {
            when {
                uiState.isLoadingTrending -> LoadingRow()
                uiState.trendingError != null -> ErrorText(uiState.trendingError)
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
                uiState.popularError != null -> ErrorText(uiState.popularError)
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
                uiState.topRatedError != null -> ErrorText(uiState.topRatedError)
                uiState.topRated.isNotEmpty() -> CollectionRow(
                    items = uiState.topRated,
                    onItemClick = { item -> onContentClick(item.id, item.mediaType) }
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
                        onItemClick = { item -> onContentClick(item.id, item.mediaType) }
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
    onItemClick: (TrendingResult) -> Unit
) {
    TvOsGradientLazyRow(gradientAlpha = 0.5f, verticalPadding = 20.dp) {
        items(items.size, key = { items[it].let { item -> "${item.id}_${item.mediaType}" } }) { index ->
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
    onItemClick: (CollectionItem) -> Unit
) {
    TvOsGradientLazyRow(gradientAlpha = 0.5f, verticalPadding = 20.dp) {
        items(items.size, key = { items[it].let { item -> "${item.id}_${item.mediaType}" } }) { index ->
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
