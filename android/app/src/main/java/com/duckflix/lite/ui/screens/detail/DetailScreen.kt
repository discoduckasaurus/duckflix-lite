package com.duckflix.lite.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.CastDto
import com.duckflix.lite.data.remote.dto.TmdbDetailResponse
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.LoadingIndicator

@Composable
fun DetailScreen(
    onPlayClick: (Int, String, String?, String, Int?, Int?, Long?, String?) -> Unit, // Added posterUrl param
    onSearchTorrents: (Int, String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
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
                    onRetry = viewModel::loadDetails
                )
            }

            uiState.content != null -> {
                ContentDetailView(
                    content = uiState.content!!,
                    contentType = uiState.contentType,
                    zurgMatch = uiState.zurgMatch,
                    isCheckingZurg = uiState.isCheckingZurg,
                    seasons = uiState.seasons,
                    selectedSeason = uiState.selectedSeason,
                    isLoadingSeasons = uiState.isLoadingSeasons,
                    watchProgress = uiState.watchProgress,
                    isInWatchlist = uiState.isInWatchlist,
                    onPlayClick = { content, type, season, episode, resumePosition, posterUrl ->
                        onPlayClick(content.id, content.title, content.year, type, season, episode, resumePosition, posterUrl)
                    },
                    onSearchTorrents = { onSearchTorrents(it.id, it.title) },
                    onNavigateBack = onNavigateBack,
                    onSelectSeason = viewModel::selectSeason,
                    onToggleWatchlist = viewModel::toggleWatchlist
                )
            }
        }
    }
}

@Composable
private fun ContentDetailView(
    content: TmdbDetailResponse,
    contentType: String,
    zurgMatch: com.duckflix.lite.data.remote.dto.ZurgMatch?,
    isCheckingZurg: Boolean,
    seasons: Map<Int, com.duckflix.lite.data.remote.dto.TmdbSeasonResponse>,
    selectedSeason: Int?,
    isLoadingSeasons: Boolean,
    watchProgress: com.duckflix.lite.data.local.entity.WatchProgressEntity?,
    isInWatchlist: Boolean,
    onPlayClick: (TmdbDetailResponse, String, Int?, Int?, Long?, String?) -> Unit,
    onSearchTorrents: (TmdbDetailResponse) -> Unit,
    onNavigateBack: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleWatchlist: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop image with gradient
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = content.backdropUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.3f),
                                Color.Black.copy(alpha = 0.7f),
                                Color.Black
                            )
                        )
                    )
            )
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 64.dp, vertical = 48.dp)
        ) {
            // Back button and Watchlist toggle
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FocusableButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.width(120.dp)
                ) {
                    Text("Back")
                }

                FocusableButton(
                    onClick = onToggleWatchlist,
                    modifier = Modifier.width(180.dp)
                ) {
                    Text(if (isInWatchlist) "♥ In Watchlist" else "♡ Add to Watchlist")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Main content area
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Poster
                AsyncImage(
                    model = content.posterUrl,
                    contentDescription = content.title,
                    modifier = Modifier
                        .width(300.dp)
                        .height(450.dp)
                )

                // Info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title
                    Text(
                        text = content.title,
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White
                    )

                    // Meta info
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        content.year?.let { year ->
                            Text(
                                text = year,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        if (content.runtime != null) {
                            Text(
                                text = "${content.runtime} min",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        if (content.voteAverage != null) {
                            Text(
                                text = "★ ${String.format("%.1f", content.voteAverage)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFFFD700)
                            )
                        }
                    }

                    // Genres
                    if (content.genreText.isNotEmpty()) {
                        Text(
                            text = content.genreText,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action buttons
                    when {
                        isCheckingZurg -> {
                            Row(
                                modifier = Modifier.height(56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LoadingIndicator()
                                Text(
                                    text = "Searching for content...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }

                        contentType == "movie" && watchProgress != null && !watchProgress.isCompleted -> {
                            // Movie with watch progress - show Resume and Restart
                            val progressPercent = ((watchProgress.position.toFloat() / watchProgress.duration) * 100).toInt()
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FocusableButton(
                                        onClick = { onPlayClick(content, contentType, null, null, watchProgress.position, content.posterUrl) },
                                        modifier = Modifier.width(200.dp).height(56.dp)
                                    ) {
                                        Text("▶ Resume ($progressPercent%)", style = MaterialTheme.typography.titleMedium)
                                    }
                                    FocusableButton(
                                        onClick = { onPlayClick(content, contentType, null, null, 0L, content.posterUrl) },
                                        modifier = Modifier.width(150.dp).height(56.dp)
                                    ) {
                                        Text("⟲ Restart", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                if (zurgMatch != null) {
                                    Text(
                                        text = "Available in library",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF00FF00)
                                    )
                                }
                            }
                        }

                        zurgMatch != null && contentType == "movie" -> {
                            // Movie without progress - show Play
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FocusableButton(
                                    onClick = { onPlayClick(content, contentType, null, null, null, content.posterUrl) },
                                    modifier = Modifier.width(200.dp).height(56.dp)
                                ) {
                                    Text("▶ Play", style = MaterialTheme.typography.titleLarge)
                                }
                                Text(
                                    text = "Available in library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF00FF00)
                                )
                            }
                        }

                        contentType == "tv" -> {
                            // For TV shows, show season/episode count
                            // TODO: Show resume info for TV shows with watch progress
                            Text(
                                text = if (content.numberOfSeasons != null) {
                                    "${content.numberOfSeasons} Season${if (content.numberOfSeasons > 1) "s" else ""}"
                                } else {
                                    "Select an episode to play"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }

                        else -> {
                            // Movie not in library
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FocusableButton(
                                    onClick = { onPlayClick(content, contentType, null, null, null, content.posterUrl) },
                                    modifier = Modifier.width(200.dp).height(56.dp)
                                ) {
                                    Text("▶ Play", style = MaterialTheme.typography.titleLarge)
                                }
                                Text(
                                    text = "Not in library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Overview
                    if (content.overview != null) {
                        Text(
                            text = "Overview",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = content.overview,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // Seasons (for TV shows)
            if (contentType == "tv" && !content.seasons.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                SeasonsSection(
                    seasons = content.seasons.sortedBy { it.seasonNumber },
                    loadedSeasons = seasons,
                    selectedSeason = selectedSeason,
                    isLoadingSeasons = isLoadingSeasons,
                    onSelectSeason = onSelectSeason,
                    onEpisodeClick = { season, episode, resumePos, posterUrl ->
                        onPlayClick(content, contentType, season, episode, resumePos, posterUrl)
                    }
                )
            }

            // Cast
            if (!content.cast.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "Cast",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))
                CastRow(cast = content.cast)
            }
        }
    }
}

@Composable
private fun SeasonsSection(
    seasons: List<com.duckflix.lite.data.remote.dto.SeasonInfoDto>,
    loadedSeasons: Map<Int, com.duckflix.lite.data.remote.dto.TmdbSeasonResponse>,
    selectedSeason: Int?,
    isLoadingSeasons: Boolean,
    onSelectSeason: (Int) -> Unit,
    onEpisodeClick: (Int, Int, Long?, String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Season dropdown selector
        Text(
            text = "Select Season",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )

        Box {
            FocusableButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.width(300.dp).height(56.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentSeason = seasons.find { it.seasonNumber == selectedSeason }
                    Text(
                        text = currentSeason?.name ?: "Select a season",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = if (expanded) "▲" else "▼",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(300.dp)
            ) {
                seasons.forEach { season ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${season.name} (${season.episodeCount ?: 0} episodes)",
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        onClick = {
                            onSelectSeason(season.seasonNumber)
                            expanded = false
                        }
                    )
                }
            }
        }

        // Show selected season info and episodes
        if (selectedSeason != null) {
            val loadedSeason = loadedSeasons[selectedSeason]
            val seasonInfo = seasons.find { it.seasonNumber == selectedSeason }

            // Season overview
            if (loadedSeason?.overview != null && loadedSeason.overview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = loadedSeason.overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Episodes grid
            if (isLoadingSeasons && loadedSeason == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            } else if (loadedSeason != null && !loadedSeason.episodes.isNullOrEmpty()) {
                EpisodeGrid(
                    episodes = loadedSeason.episodes,
                    seasonNumber = selectedSeason,
                    onEpisodeClick = onEpisodeClick
                )
            }
        }
    }
}

@Composable
private fun EpisodeGrid(
    episodes: List<com.duckflix.lite.data.remote.dto.EpisodeDto>,
    seasonNumber: Int,
    onEpisodeClick: (Int, Int, Long?, String?) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        episodes.chunked(2).forEach { rowEpisodes ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowEpisodes.forEach { episode ->
                    Box(modifier = Modifier.weight(1f)) {
                        EpisodeCard(
                            episode = episode,
                            onEpisodeClick = { resumePos, posterUrl ->
                                onEpisodeClick(seasonNumber, episode.episodeNumber, resumePos, posterUrl)
                            }
                        )
                    }
                }
                // Fill remaining space if odd number of episodes in last row
                if (rowEpisodes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: com.duckflix.lite.data.remote.dto.EpisodeDto,
    onEpisodeClick: (Long?, String?) -> Unit
) {
    FocusableButton(
        onClick = { onEpisodeClick(null, episode.stillUrl) },
        modifier = Modifier.fillMaxWidth().height(220.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Episode still/thumbnail
            if (episode.stillUrl != null) {
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = episode.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No Image",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Episode info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "E${episode.episodeNumber}: ${episode.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1
                )
                if (episode.runtime != null) {
                    Text(
                        text = "${episode.runtime} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                if (episode.overview != null) {
                    Text(
                        text = episode.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun CastRow(cast: List<CastDto>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(cast) { member ->
            Column(
                modifier = Modifier.width(120.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = member.profileUrl,
                    contentDescription = member.name,
                    modifier = Modifier
                        .size(120.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 2
                )
                if (member.character != null) {
                    Text(
                        text = member.character,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
