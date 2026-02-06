package com.duckflix.lite.ui.screens.advancedsearch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.CollectionItem
import com.duckflix.lite.data.remote.dto.GenreDto
import com.duckflix.lite.ui.components.FilterDropdown
import com.duckflix.lite.ui.components.FilterSlider
import com.duckflix.lite.ui.components.FilterToggleButton
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.FocusableSearchBar
import com.duckflix.lite.ui.components.LoadingIndicator
import com.duckflix.lite.ui.components.MediaCard

/**
 * Advanced Search Tab - Browse and filter content with multiple criteria.
 * Supports text search, genre/year/rating/runtime filters, and sorting.
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
    val filterRowFocusRequester = remember { FocusRequester() }
    val resultsFocusRequester = remember { FocusRequester() }

    // Year options: 2026 down to 1970
    val yearOptions = remember { (2026 downTo 1970).toList() }

    // Note: verticalScroll removed - parent VodContainerScreen handles scrolling
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Search Bar Row
        SearchBarRow(
            query = uiState.query,
            onQueryChange = viewModel::setQuery,
            onSearch = viewModel::search,
            searchFocusRequester = searchFocusRequester,
            onNavigateDown = {
                focusManager.clearFocus()
                try {
                    filterRowFocusRequester.requestFocus()
                } catch (_: IllegalStateException) {
                    // FocusRequester not attached yet
                }
            }
        )

        // Filter Row (horizontally scrollable)
        FilterRow(
            uiState = uiState,
            yearOptions = yearOptions,
            onToggleTv = viewModel::toggleTv,
            onToggleMovie = viewModel::toggleMovie,
            onGenresChange = viewModel::setSelectedGenres,
            onYearChange = viewModel::setSelectedYear,
            onRatingChange = viewModel::setRatingRange,
            onRuntimeChange = { range -> viewModel.setRuntimeRange(range) },
            onSortChange = viewModel::setSortBy,
            filterRowFocusRequester = filterRowFocusRequester
        )

        // Results Count
        if (!uiState.isLoading && uiState.results.isNotEmpty()) {
            Text(
                text = "${uiState.totalResults} results",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
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
                resultsFocusRequester = resultsFocusRequester
            )
        }

        // Empty state
        if (!uiState.isLoading && uiState.results.isEmpty() && uiState.error == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No results found. Try adjusting your filters.",
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
    searchFocusRequester: FocusRequester,
    onNavigateDown: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Search Input - click to activate
        FocusableSearchBar(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search movies and TV shows...",
            onSearch = onSearch,
            modifier = Modifier.weight(1f),
            height = 56.dp,
            focusRequester = searchFocusRequester,
            onNavigateDown = onNavigateDown
        )

        // Search Button
        FocusableButton(
            onClick = onSearch,
            modifier = Modifier
                .width(120.dp)
                .height(56.dp)
        ) {
            Text("Search")
        }
    }
}

@Composable
private fun FilterRow(
    uiState: AdvancedSearchUiState,
    yearOptions: List<Int>,
    onToggleTv: () -> Unit,
    onToggleMovie: () -> Unit,
    onGenresChange: (List<Int>) -> Unit,
    onYearChange: (Int?) -> Unit,
    onRatingChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onRuntimeChange: (IntRange) -> Unit,
    onSortChange: (String) -> Unit,
    filterRowFocusRequester: FocusRequester
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        // TV Toggle
        item {
            FilterToggleButton(
                label = "TV",
                isSelected = uiState.tvSelected,
                onToggle = onToggleTv,
                modifier = Modifier.focusRequester(filterRowFocusRequester)
            )
        }

        // Movie Toggle
        item {
            FilterToggleButton(
                label = "Movie",
                isSelected = uiState.movieSelected,
                onToggle = onToggleMovie
            )
        }

        // Genre Dropdown (multi-select)
        item {
            FilterDropdown(
                label = "Genre",
                options = uiState.availableGenres,
                selectedOptions = uiState.availableGenres.filter {
                    uiState.selectedGenres.contains(it.id)
                },
                onSelectionChange = { selectedGenres ->
                    onGenresChange(selectedGenres.map { it.id })
                },
                optionLabel = { it.name },
                multiSelect = true
            )
        }

        // Year Dropdown (single-select)
        item {
            FilterDropdown(
                label = "Year",
                options = yearOptions,
                selectedOptions = uiState.selectedYear?.let { listOf(it) } ?: emptyList(),
                onSelectionChange = { selected ->
                    onYearChange(selected.firstOrNull())
                },
                optionLabel = { it.toString() },
                multiSelect = false
            )
        }

        // Rating Slider
        item {
            RatingFilterChip(
                ratingRange = uiState.ratingRange,
                onRatingChange = onRatingChange
            )
        }

        // Runtime Slider
        item {
            RuntimeFilterChip(
                runtimeRange = uiState.runtimeRange,
                onRuntimeChange = onRuntimeChange
            )
        }

        // Sort Dropdown
        item {
            FilterDropdown(
                label = "Sort",
                options = SortOption.entries.toList(),
                selectedOptions = SortOption.entries.filter { it.apiValue == uiState.sortBy },
                onSelectionChange = { selected ->
                    selected.firstOrNull()?.let { onSortChange(it.apiValue) }
                },
                optionLabel = { it.displayName },
                multiSelect = false
            )
        }
    }
}

/**
 * Chip-style rating filter that expands to show a slider when clicked
 */
@Composable
private fun RatingFilterChip(
    ratingRange: ClosedFloatingPointRange<Float>,
    onRatingChange: (ClosedFloatingPointRange<Float>) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val hasFilter = ratingRange.start > 0f || ratingRange.endInclusive < 10f
    val displayText = if (hasFilter) {
        "${String.format("%.1f", ratingRange.start)} - ${String.format("%.1f", ratingRange.endInclusive)}"
    } else {
        "Rating"
    }

    Column {
        FilterToggleButton(
            label = displayText,
            isSelected = hasFilter,
            onToggle = { expanded = !expanded }
        )

        if (expanded) {
            Surface(
                modifier = Modifier
                    .width(280.dp)
                    .padding(top = 8.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 8.dp
            ) {
                FilterSlider(
                    label = "Rating",
                    value = ratingRange,
                    valueRange = 0f..10f,
                    onValueChange = onRatingChange,
                    steps = 19, // 0.5 increments
                    valueFormatter = { String.format("%.1f", it) },
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

/**
 * Chip-style runtime filter that expands to show a slider when clicked
 */
@Composable
private fun RuntimeFilterChip(
    runtimeRange: IntRange,
    onRuntimeChange: (IntRange) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val hasFilter = runtimeRange.first > 0 || runtimeRange.last < 300
    val displayText = if (hasFilter) {
        "${runtimeRange.first} - ${runtimeRange.last} min"
    } else {
        "Runtime"
    }

    Column {
        FilterToggleButton(
            label = displayText,
            isSelected = hasFilter,
            onToggle = { expanded = !expanded }
        )

        if (expanded) {
            Surface(
                modifier = Modifier
                    .width(280.dp)
                    .padding(top = 8.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 8.dp
            ) {
                FilterSlider(
                    label = "Runtime",
                    value = runtimeRange.first.toFloat()..runtimeRange.last.toFloat(),
                    valueRange = 0f..300f,
                    onValueChange = { range ->
                        onRuntimeChange(range.start.toInt()..range.endInclusive.toInt())
                    },
                    steps = 9, // 30 minute increments
                    valueFormatter = { "${it.toInt()} min" },
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultsGrid(
    results: List<CollectionItem>,
    onContentClick: (tmdbId: Int, mediaType: String) -> Unit,
    resultsFocusRequester: FocusRequester
) {
    // Display results in a horizontal LazyRow carousel (matches My Stuff style)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(results, key = { "${it.id}_${it.mediaType}" }) { item ->
            ResultCard(
                item = item,
                onClick = { onContentClick(item.id, item.mediaType) },
                focusRequester = if (results.indexOf(item) == 0) resultsFocusRequester else null
            )
        }
    }
}

@Composable
private fun ResultCard(
    item: CollectionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    MediaCard(
        onClick = onClick,
        modifier = modifier
            .width(128.dp)
            .height(240.dp),
        focusRequester = focusRequester
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
