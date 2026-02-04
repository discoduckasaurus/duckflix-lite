package com.duckflix.lite.ui.screens.filmography

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.duckflix.lite.data.remote.dto.PersonCreditItem
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.LoadingIndicator
import com.duckflix.lite.ui.components.MediaCard

@Composable
fun ActorFilmographyScreen(
    onNavigateBack: () -> Unit,
    onContentClick: (Int, String) -> Unit, // (tmdbId, mediaType) -> navigate to detail
    viewModel: ActorFilmographyViewModel = hiltViewModel()
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
                    onRetry = viewModel::loadActorData
                )
            }

            uiState.personDetails != null && uiState.filmography != null -> {
                ActorFilmographyContent(
                    personDetails = uiState.personDetails!!,
                    filmography = uiState.filmography!!,
                    onNavigateBack = onNavigateBack,
                    onContentClick = onContentClick
                )
            }
        }
    }
}

@Composable
private fun ActorFilmographyContent(
    personDetails: com.duckflix.lite.data.remote.dto.PersonDetailsResponse,
    filmography: com.duckflix.lite.data.remote.dto.PersonCreditsResponse,
    onNavigateBack: () -> Unit,
    onContentClick: (Int, String) -> Unit
) {
    var bioExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Back button
        FocusableButton(
            onClick = onNavigateBack,
            modifier = Modifier.width(100.dp)
        ) {
            Text("Back")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Header Section - Actor Info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Profile photo (circular)
            AsyncImage(
                model = personDetails.profileUrl,
                contentDescription = personDetails.name,
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            // Actor details
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Name
                Text(
                    text = personDetails.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )

                // Known for
                personDetails.knownForDepartment?.let {
                    Text(
                        text = "Known for: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Birthday
                personDetails.birthday?.let {
                    Text(
                        text = "Born: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                // Place of birth
                personDetails.placeOfBirth?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Biography section with expand/collapse
        personDetails.biography?.takeIf { it.isNotBlank() }?.let { bio ->
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (bioExpanded) bio else bio.take(200) + if (bio.length > 200) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = if (bioExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )

            if (bio.length > 200) {
                Spacer(modifier = Modifier.height(8.dp))
                FocusableButton(
                    onClick = { bioExpanded = !bioExpanded },
                    modifier = Modifier.width(140.dp).height(40.dp)
                ) {
                    Text(if (bioExpanded) "Show Less" else "Read More")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Filmography title with deduplicated count
        val uniqueTitlesCount = filmography.results.distinctBy { it.id }.size
        Text(
            text = "Filmography ($uniqueTitlesCount titles)",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Filmography grid
        if (filmography.results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No filmography found for this actor",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        } else {
            FilmographyGrid(
                credits = filmography.results,
                onContentClick = onContentClick
            )
        }
    }
}

@Composable
private fun FilmographyGrid(
    credits: List<PersonCreditItem>,
    onContentClick: (Int, String) -> Unit
) {
    // Deduplicate by ID and combine character names
    val deduplicatedCredits = credits.groupBy { it.id }.map { (_, items) ->
        val first = items.first()
        val characters = items.mapNotNull { it.character }.distinct()
        val combinedCharacter = when {
            characters.isEmpty() -> null
            characters.size == 1 -> characters.first()
            else -> "Multiple Roles"
        }

        // Create a modified credit with combined character info
        first.copy(character = combinedCharacter)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        deduplicatedCredits.chunked(4).forEach { rowCredits ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowCredits.forEach { credit ->
                    Box(modifier = Modifier.weight(1f)) {
                        FilmographyCard(
                            credit = credit,
                            onClick = { onContentClick(credit.id, credit.mediaType) }
                        )
                    }
                }
                // Fill remaining space for incomplete last row
                repeat(4 - rowCredits.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FilmographyCard(
    credit: PersonCreditItem,
    onClick: () -> Unit
) {
    MediaCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Poster image
            Box(modifier = Modifier.weight(1f)) {
                if (credit.posterUrl != null) {
                    AsyncImage(
                        model = credit.posterUrl,
                        contentDescription = credit.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = credit.title,
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                // Media type badge (MOVIE or TV)
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    color = if (credit.mediaType == "movie") Color(0xFF0066CC) else Color(0xFF00AA00),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = credit.mediaType.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Info section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Title and year
                Text(
                    text = credit.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Year and rating
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    credit.year?.let {
                        Text(
                            text = it.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    credit.voteAverage?.let { rating ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "\u2605",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFD700)
                            )
                            Text(
                                text = String.format("%.1f", rating),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Character name
                credit.character?.let {
                    Text(
                        text = "as $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
