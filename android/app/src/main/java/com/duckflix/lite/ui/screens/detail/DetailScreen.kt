package com.duckflix.lite.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.CastDto
import com.duckflix.lite.data.remote.dto.TmdbDetailResponse
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.LoadingIndicator
import com.duckflix.lite.ui.components.MediaCard

@Composable
fun DetailScreen(
    onPlayClick: (Int, String, String?, String, Int?, Int?, Long?, String?, String?, String?, Boolean) -> Unit, // tmdbId, title, year, type, season, episode, resumePosition, posterUrl, logoUrl, originalLanguage, isRandom
    onSearchTorrents: (Int, String) -> Unit,
    onNavigateBack: () -> Unit,
    onActorClick: (Int) -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Handle one-time events from ViewModel (toast, navigation)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is DetailEvent.NavigateBack -> {
                    onNavigateBack()
                }
            }
        }
    }

    BackHandler {
        if (uiState.selectedSeason != null) {
            viewModel.resetToSeriesView()
        } else {
            onNavigateBack()
        }
    }

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
                // Create a focus requester for the primary action (Play button or Season dropdown)
                val primaryActionFocusRequester = remember { FocusRequester() }

                // Request focus on the primary action when content loads
                LaunchedEffect(uiState.content, uiState.isCheckingZurg) {
                    // Wait for zurg check to complete before focusing
                    if (!uiState.isCheckingZurg) {
                        try {
                            primaryActionFocusRequester.requestFocus()
                        } catch (_: IllegalStateException) {
                            // FocusRequester not yet attached, ignore
                        }
                    }
                }

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
                    isLoadingRandomEpisode = uiState.isLoadingRandomEpisode,
                    randomEpisodeError = uiState.randomEpisodeError,
                    primaryActionFocusRequester = primaryActionFocusRequester,
                    onPlayClick = { content, type, season, episode, resumePosition, posterUrl, logoUrl ->
                        println("[LOGO-DEBUG-DETAIL] Preparing to play: ${content.title}")
                        println("[LOGO-DEBUG-DETAIL] logoUrl from content: ${content.logoUrl}")
                        println("[LOGO-DEBUG-DETAIL] logoUrl parameter: $logoUrl")
                        println("[LOGO-DEBUG-DETAIL] posterUrl: $posterUrl")
                        onPlayClick(content.id, content.title, content.year, type, season, episode, resumePosition, posterUrl, logoUrl, content.originalLanguage, false)
                    },
                    onSearchTorrents = { onSearchTorrents(it.id, it.title) },
                    onNavigateBack = onNavigateBack,
                    onSelectSeason = viewModel::selectSeason,
                    onToggleWatchlist = viewModel::toggleWatchlist,
                    onPlayRandomEpisode = {
                        viewModel.playRandomEpisode { season, episode ->
                            onPlayClick(uiState.content!!.id, uiState.content!!.title, uiState.content!!.year, "tv", season, episode, null, uiState.content!!.posterUrl, uiState.content!!.logoUrl, uiState.content!!.originalLanguage, true)
                        }
                    },
                    onActorClick = onActorClick
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
    isLoadingRandomEpisode: Boolean,
    randomEpisodeError: String?,
    primaryActionFocusRequester: FocusRequester,
    onPlayClick: (TmdbDetailResponse, String, Int?, Int?, Long?, String?, String?) -> Unit, // Added logoUrl param (originalLanguage extracted from TmdbDetailResponse)
    onSearchTorrents: (TmdbDetailResponse) -> Unit,
    onNavigateBack: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleWatchlist: () -> Unit,
    onPlayRandomEpisode: () -> Unit,
    onActorClick: (Int) -> Unit
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
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
        ) {
            // Back button and Watchlist toggle
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FocusableButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.width(100.dp)
                ) {
                    Text("Back")
                }

                FocusableButton(
                    onClick = onToggleWatchlist,
                    modifier = Modifier.width(140.dp)
                ) {
                    Text("+ Watchlist")
                }
            }

            Spacer(modifier = Modifier.height(11.dp))

            // Main content area
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Poster
                AsyncImage(
                    model = content.posterUrl,
                    contentDescription = content.title,
                    modifier = Modifier
                        .width(180.dp)
                        .height(270.dp)
                )

                // Info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Title
                    Text(
                        text = content.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White
                    )

                    // --- Combined meta info line with | delimiters and gold star ---
                    val metaText = buildAnnotatedString {
                        val defaultStyle = SpanStyle(color = Color.White.copy(alpha = 0.8f))
                        val goldStyle = SpanStyle(color = Color(0xFFFFD700))
                        var needsSeparator = false

                        fun appendSeparator() {
                            if (needsSeparator) {
                                withStyle(defaultStyle) { append(" | ") }
                            }
                            needsSeparator = true
                        }

                        content.year?.let {
                            appendSeparator()
                            withStyle(defaultStyle) { append(it) }
                        }
                        content.runtime?.let {
                            appendSeparator()
                            withStyle(defaultStyle) { append("$it min") }
                        }
                        content.voteAverage?.let {
                            appendSeparator()
                            withStyle(goldStyle) { append("\u2605") }
                            withStyle(defaultStyle) { append(" ${String.format("%.1f", it)}") }
                        }
                        if (content.genreText.isNotEmpty()) {
                            appendSeparator()
                            withStyle(defaultStyle) { append(content.genreText) }
                        }
                        if (contentType == "tv" && content.numberOfSeasons != null) {
                            appendSeparator()
                            withStyle(defaultStyle) {
                                append("${content.numberOfSeasons} Season${if (content.numberOfSeasons > 1) "s" else ""}")
                            }
                        }
                    }
                    if (metaText.isNotEmpty()) {
                        Text(
                            text = metaText,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(1.dp))

                    // Action buttons
                    when {
                        isCheckingZurg -> {
                            Row(
                                modifier = Modifier.height(48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LoadingIndicator()
                                Text(
                                    text = "Searching for content...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }

                        contentType == "movie" && watchProgress != null && !watchProgress.isCompleted -> {
                            // Movie with watch progress - show Resume and Restart
                            val progressPercent = ((watchProgress.position.toFloat() / watchProgress.duration) * 100).toInt()
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FocusableButton(
                                        onClick = { onPlayClick(content, contentType, null, null, watchProgress.position, content.posterUrl, content.logoUrl) },
                                        modifier = Modifier.width(140.dp).height(48.dp),
                                        focusRequester = primaryActionFocusRequester
                                    ) {
                                        Text("\u25B6 Resume ($progressPercent%)", style = MaterialTheme.typography.bodyLarge)
                                    }
                                    FocusableButton(
                                        onClick = { onPlayClick(content, contentType, null, null, 0L, content.posterUrl, content.logoUrl) },
                                        modifier = Modifier.width(120.dp).height(48.dp)
                                    ) {
                                        Text("\u27F2 Restart", style = MaterialTheme.typography.bodyLarge)
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
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                FocusableButton(
                                    onClick = { onPlayClick(content, contentType, null, null, null, content.posterUrl, content.logoUrl) },
                                    modifier = Modifier.width(140.dp).height(48.dp),
                                    focusRequester = primaryActionFocusRequester
                                ) {
                                    Text("\u25B6 Play", style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    text = "Available in library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF00FF00)
                                )
                            }
                        }

                        contentType == "tv" -> {
                            // TV shows: season count is now in the meta line above;
                            // no separate text needed here
                        }

                        else -> {
                            // Movie not in library
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                FocusableButton(
                                    onClick = { onPlayClick(content, contentType, null, null, null, content.posterUrl, content.logoUrl) },
                                    modifier = Modifier.width(140.dp).height(48.dp),
                                    focusRequester = primaryActionFocusRequester
                                ) {
                                    Text("\u25B6 Play", style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    text = "Not in library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Overview
                    if (content.overview != null) {
                        Text(
                            text = "Overview",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = content.overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }

                    // Season controls (dropdown + random button) - stays in info column
                    if (contentType == "tv" && !content.seasons.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        SeasonControls(
                            seasons = content.seasons.sortedBy { it.seasonNumber },
                            selectedSeason = selectedSeason,
                            isLoadingRandomEpisode = isLoadingRandomEpisode,
                            randomEpisodeError = randomEpisodeError,
                            focusRequester = primaryActionFocusRequester,
                            onSelectSeason = onSelectSeason,
                            onPlayRandomEpisode = onPlayRandomEpisode
                        )
                    }
                }
            }

            // Episode grid - full width below poster/info row
            if (contentType == "tv" && selectedSeason != null) {
                Spacer(modifier = Modifier.height(18.dp))
                SeasonEpisodes(
                    loadedSeasons = seasons,
                    selectedSeason = selectedSeason,
                    isLoadingSeasons = isLoadingSeasons,
                    seriesPosterUrl = content.posterUrl,
                    onEpisodeClick = { season, episode, resumePos, posterUrl ->
                        onPlayClick(content, contentType, season, episode, resumePos, posterUrl, content.logoUrl)
                    }
                )
            }

            // Cast
            if (!content.cast.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = "Cast",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                CastRow(cast = content.cast, onActorClick = onActorClick)
            }
        }
    }
}

/**
 * Season controls (dropdown + random button) - displayed in info column
 */
@Composable
private fun SeasonControls(
    seasons: List<com.duckflix.lite.data.remote.dto.SeasonInfoDto>,
    selectedSeason: Int?,
    isLoadingRandomEpisode: Boolean,
    randomEpisodeError: String?,
    focusRequester: FocusRequester,
    onSelectSeason: (Int) -> Unit,
    onPlayRandomEpisode: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Season dropdown selector and random button
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                FocusableButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.width(260.dp).height(48.dp),
                    focusRequester = focusRequester
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val currentSeason = seasons.find { it.seasonNumber == selectedSeason }
                        Text(
                            text = currentSeason?.name ?: "Select Season",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        Text(
                            text = if (expanded) "\u25B2" else "\u25BC",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                    }
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(260.dp)
                ) {
                    seasons.forEach { season ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${season.name} (${season.episodeCount ?: 0} episodes)",
                                    style = MaterialTheme.typography.bodyLarge
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

            // Random episode button with tooltip
            if (!isLoadingRandomEpisode) {
                Box {
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()

                    FocusableButton(
                        onClick = onPlayRandomEpisode,
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp),
                        interactionSource = interactionSource
                    ) {
                        Text(
                            text = "🎰",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                    }

                    // Tooltip on focus
                    if (isFocused) {
                        Surface(
                            modifier = Modifier
                                .offset(y = (-62).dp)
                                .zIndex(10f),
                            color = Color.Black.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Play Random Episode",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
        }

        // Random episode error
        randomEpisodeError?.let { errorMsg ->
            Text(
                text = errorMsg,
                color = Color.Red,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Season episodes (overview + grid) - displayed full width below poster/info
 */
@Composable
private fun SeasonEpisodes(
    loadedSeasons: Map<Int, com.duckflix.lite.data.remote.dto.TmdbSeasonResponse>,
    selectedSeason: Int,
    isLoadingSeasons: Boolean,
    seriesPosterUrl: String?,
    onEpisodeClick: (Int, Int, Long?, String?) -> Unit
) {
    val loadedSeason = loadedSeasons[selectedSeason]

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Season overview
        if (loadedSeason?.overview != null && loadedSeason.overview.isNotEmpty()) {
            Text(
                text = loadedSeason.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }

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
                seriesPosterUrl = seriesPosterUrl,
                onEpisodeClick = onEpisodeClick
            )
        }
    }
}

@Composable
private fun EpisodeGrid(
    episodes: List<com.duckflix.lite.data.remote.dto.EpisodeDto>,
    seasonNumber: Int,
    seriesPosterUrl: String?, // Series poster for Continue Watching
    onEpisodeClick: (Int, Int, Long?, String?) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        episodes.chunked(3).forEach { rowEpisodes ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowEpisodes.forEach { episode ->
                    Box(modifier = Modifier.weight(1f)) {
                        EpisodeCard(
                            episode = episode,
                            seriesPosterUrl = seriesPosterUrl, // Pass series poster
                            onEpisodeClick = { resumePos, posterUrl ->
                                onEpisodeClick(seasonNumber, episode.episodeNumber, resumePos, posterUrl)
                            }
                        )
                    }
                }
                // Fill remaining space for incomplete last row
                repeat(3 - rowEpisodes.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCard(
    episode: com.duckflix.lite.data.remote.dto.EpisodeDto,
    seriesPosterUrl: String?, // Series poster for Continue Watching (not episode still)
    onEpisodeClick: (Long?, String?) -> Unit
) {
    // --- Task 5: Track focus state for marquee scrolling ---
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    MediaCard(
        onClick = { onEpisodeClick(null, seriesPosterUrl) }, // Use series poster, not episode still
        modifier = Modifier
            .fillMaxWidth()
            .height(252.dp), // --- Task 4: Increased from 240dp to 252dp (5% increase) ---
        interactionSource = interactionSource
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Episode still/thumbnail with 16:9 aspect ratio and 6dp top rounded corners
            if (episode.stillUrl != null) {
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = episode.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "E${episode.episodeNumber}",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Grey overlay info area at bottom
            // --- Task 4: Increased info area from 80dp to 88dp for proportional scaling ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "E${episode.episodeNumber}: ${episode.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (episode.overview != null && episode.overview.isNotBlank()) {
                    Text(
                        text = episode.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 7, // --- Task 4: Increased from 5 to 7 for taller card ---
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                        // --- Task 5: Marquee scroll when focused and text is truncated ---
                        modifier = if (isFocused) {
                            Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CastRow(cast: List<CastDto>, onActorClick: (Int) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(cast) { member ->
            MediaCard(
                onClick = { onActorClick(member.id) },
                modifier = Modifier.width(120.dp)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = member.profileUrl,
                        contentDescription = member.name,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (member.character != null) {
                        Text(
                            text = member.character,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
