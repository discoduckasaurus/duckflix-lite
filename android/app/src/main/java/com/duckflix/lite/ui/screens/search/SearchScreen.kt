package com.duckflix.lite.ui.screens.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.FocusableCard
import com.duckflix.lite.ui.components.LoadingIndicator

@Composable
fun SearchScreen(
    onContentSelected: (Int, String, String) -> Unit, // tmdbId, title, type
    onNavigateBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp)
    ) {
        // Header with search input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f)
            )

            FocusableButton(
                onClick = onNavigateBack,
                modifier = Modifier.width(120.dp)
            ) {
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Search input and button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp)
                    .focusRequester(searchFocusRequester),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (uiState.query.isEmpty()) {
                        Text(
                            text = "Enter movie or TV show title...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    BasicTextField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { viewModel.search() }
                        )
                    )
                }
            }

            FocusableButton(
                onClick = { viewModel.search() },
                modifier = Modifier
                    .width(140.dp)
                    .height(72.dp),
                enabled = uiState.query.isNotBlank()
            ) {
                Text("Search")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Content area
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.error != null -> {
                ErrorScreen(
                    message = uiState.error ?: "Unknown error",
                    onRetry = {
                        viewModel.clearError()
                        if (uiState.query.isNotBlank()) {
                            viewModel.onQueryChange(uiState.query)
                        }
                    }
                )
            }

            uiState.query.isBlank() -> {
                // Show recent searches and recently watched
                RecentContent(
                    recentSearches = uiState.recentSearches,
                    recentlyWatched = uiState.recentlyWatched,
                    onContentSelected = onContentSelected
                )
            }

            uiState.movieResults.isEmpty() && uiState.tvResults.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No results found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            else -> {
                SearchResultsWithSections(
                    movieResults = uiState.movieResults,
                    tvResults = uiState.tvResults,
                    onContentSelected = { tmdbId, title, type ->
                        // Save to recent searches
                        val result = (uiState.movieResults + uiState.tvResults)
                            .find { it.id == tmdbId }
                        result?.let {
                            viewModel.saveRecentSearch(tmdbId, title, type, it.year, it.posterUrl)
                        }
                        onContentSelected(tmdbId, title, type)
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchResultsWithSections(
    movieResults: List<com.duckflix.lite.data.remote.dto.TmdbSearchResult>,
    tvResults: List<com.duckflix.lite.data.remote.dto.TmdbSearchResult>,
    onContentSelected: (Int, String, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (movieResults.isNotEmpty()) {
            item {
                Text(
                    text = "Movies",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(movieResults.chunked(5)) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    rowItems.forEach { result ->
                        SearchResultCard(
                            result = result,
                            onClick = { onContentSelected(result.id, result.title, result.type) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Fill empty spaces in the last row
                    repeat(5 - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (tvResults.isNotEmpty()) {
            item {
                Text(
                    text = "TV Shows",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(tvResults.chunked(5)) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    rowItems.forEach { result ->
                        SearchResultCard(
                            result = result,
                            onClick = { onContentSelected(result.id, result.title, result.type) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Fill empty spaces in the last row
                    repeat(5 - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: com.duckflix.lite.data.remote.dto.TmdbSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier
            .height(340.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Poster
            AsyncImage(
                model = result.posterUrl,
                contentDescription = result.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                contentScale = ContentScale.Crop
            )

            // Title and year
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                if (result.year != null) {
                    Text(
                        text = result.year,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentContent(
    recentSearches: List<com.duckflix.lite.ui.screens.search.RecentItem>,
    recentlyWatched: List<com.duckflix.lite.ui.screens.search.RecentItem>,
    onContentSelected: (Int, String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Recently Watched Section
        if (recentlyWatched.isNotEmpty()) {
            Column {
                Text(
                    text = "Recently Watched",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Simple rows of cards
                recentlyWatched.chunked(5).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rowItems.forEach { item ->
                            RecentItemCard(
                                item = item,
                                onClick = { onContentSelected(item.tmdbId, item.title, item.type) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(5 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Recent Searches Section
        if (recentSearches.isNotEmpty()) {
            Column {
                Text(
                    text = "Recent Searches",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Simple rows of cards
                recentSearches.chunked(5).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rowItems.forEach { item ->
                            RecentItemCard(
                                item = item,
                                onClick = { onContentSelected(item.tmdbId, item.title, item.type) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(5 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Empty state
        if (recentSearches.isEmpty() && recentlyWatched.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Start searching to see results here",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun RecentItemCard(
    item: com.duckflix.lite.ui.screens.search.RecentItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier.height(340.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                if (item.year != null) {
                    Text(
                        text = item.year,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
