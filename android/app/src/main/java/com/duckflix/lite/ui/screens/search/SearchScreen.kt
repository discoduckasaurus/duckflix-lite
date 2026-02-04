package com.duckflix.lite.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.LoadingIndicator
import com.duckflix.lite.ui.components.MediaCard

@Composable
fun SearchScreen(
    onContentSelected: (Int, String, String) -> Unit, // tmdbId, title, type
    onNavigateBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
    }

    BackHandler {
        if (uiState.query.isNotEmpty()) {
            viewModel.onQueryChange("")
        } else {
            onNavigateBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 24.dp)
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
                modifier = Modifier
                    .width(120.dp)
                    .focusRequester(backButtonFocusRequester)
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
                    .height(72.dp),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        Key.DirectionUp -> {
                                            focusManager.clearFocus()
                                            try {
                                                backButtonFocusRequester.requestFocus()
                                            } catch (_: IllegalStateException) {
                                                // FocusRequester not attached yet
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            focusManager.clearFocus()
                                            try {
                                                contentFocusRequester.requestFocus()
                                            } catch (_: IllegalStateException) {
                                                // FocusRequester not attached yet
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            },
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
                    onContentSelected = onContentSelected,
                    contentFocusRequester = contentFocusRequester
                )
            }

            uiState.topResults.isEmpty() && uiState.movieResults.isEmpty() && uiState.tvResults.isEmpty() -> {
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
                    topResults = uiState.topResults,
                    movieResults = uiState.movieResults,
                    tvResults = uiState.tvResults,
                    onContentSelected = { tmdbId, title, type ->
                        // Save to recent searches
                        val result = (uiState.topResults + uiState.movieResults + uiState.tvResults)
                            .find { it.id == tmdbId }
                        result?.let {
                            viewModel.saveRecentSearch(tmdbId, title, type, it.year, it.posterUrl, it.voteAverage)
                        }
                        onContentSelected(tmdbId, title, type)
                    },
                    contentFocusRequester = contentFocusRequester
                )
            }
        }
    }
}

@Composable
private fun SearchResultsWithSections(
    topResults: List<com.duckflix.lite.data.remote.dto.TmdbSearchResult>,
    movieResults: List<com.duckflix.lite.data.remote.dto.TmdbSearchResult>,
    tvResults: List<com.duckflix.lite.data.remote.dto.TmdbSearchResult>,
    onContentSelected: (Int, String, String) -> Unit,
    contentFocusRequester: FocusRequester = remember { FocusRequester() }
) {
    // Determine which row is first to assign the contentFocusRequester
    val firstSection = when {
        topResults.isNotEmpty() -> "top"
        movieResults.isNotEmpty() -> "movies"
        tvResults.isNotEmpty() -> "tv"
        else -> null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Top Results - Mixed movies and TV by relevance
        if (topResults.isNotEmpty()) {
            item {
                Text(
                    text = "Top Results",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                HorizontalScrollableRow(
                    results = topResults,
                    onContentSelected = onContentSelected,
                    firstItemFocusRequester = if (firstSection == "top") contentFocusRequester else null
                )
            }
        }

        // Movies - Horizontal scrollable
        if (movieResults.isNotEmpty()) {
            item {
                Text(
                    text = "Movies",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                HorizontalScrollableRow(
                    results = movieResults,
                    onContentSelected = onContentSelected,
                    firstItemFocusRequester = if (firstSection == "movies") contentFocusRequester else null
                )
            }
        }

        // TV Shows - Horizontal scrollable
        if (tvResults.isNotEmpty()) {
            item {
                Text(
                    text = "TV Shows",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                HorizontalScrollableRow(
                    results = tvResults,
                    onContentSelected = onContentSelected,
                    firstItemFocusRequester = if (firstSection == "tv") contentFocusRequester else null
                )
            }
        }
    }
}

@Composable
private fun HorizontalScrollableRow(
    results: List<com.duckflix.lite.data.remote.dto.TmdbSearchResult>,
    onContentSelected: (Int, String, String) -> Unit,
    firstItemFocusRequester: FocusRequester? = null
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 0.dp)
    ) {
        items(results.size) { index ->
            val result = results[index]
            SearchResultCard(
                result = result,
                onClick = { onContentSelected(result.id, result.title, result.type) },
                modifier = Modifier.width(128.dp),
                focusRequester = if (index == 0) firstItemFocusRequester else null
            )
        }
    }
}

@Composable
private fun SearchResultCard(
    result: com.duckflix.lite.data.remote.dto.TmdbSearchResult,
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
                model = result.posterUrl,
                contentDescription = result.title,
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
                    text = result.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.voteAverage != null && result.voteAverage > 0) {
                    Text(
                        text = "★ ${String.format("%.1f", result.voteAverage)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD700).copy(alpha = 0.9f)
                    )
                }
                if (result.year != null) {
                    Text(
                        text = result.year,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
    onContentSelected: (Int, String, String) -> Unit,
    contentFocusRequester: FocusRequester = remember { FocusRequester() }
) {
    // Track whether the focus requester has been assigned to the first card
    var firstItemAssigned = false

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
                recentlyWatched.chunked(5).forEachIndexed { rowIndex, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rowItems.forEachIndexed { itemIndex, item ->
                            val isFirstItem = !firstItemAssigned && rowIndex == 0 && itemIndex == 0
                            if (isFirstItem) firstItemAssigned = true
                            RecentItemCard(
                                item = item,
                                onClick = { onContentSelected(item.tmdbId, item.title, item.type) },
                                modifier = Modifier.weight(1f),
                                focusRequester = if (isFirstItem) contentFocusRequester else null
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
                recentSearches.chunked(5).forEachIndexed { rowIndex, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rowItems.forEachIndexed { itemIndex, item ->
                            val isFirstItem = !firstItemAssigned && rowIndex == 0 && itemIndex == 0
                            if (isFirstItem) firstItemAssigned = true
                            RecentItemCard(
                                item = item,
                                onClick = { onContentSelected(item.tmdbId, item.title, item.type) },
                                modifier = Modifier.weight(1f),
                                focusRequester = if (isFirstItem) contentFocusRequester else null
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.voteAverage != null && item.voteAverage > 0) {
                    Text(
                        text = "\u2605 ${String.format("%.1f", item.voteAverage)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD700).copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}
