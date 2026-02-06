package com.duckflix.lite.ui.screens.providers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duckflix.lite.data.remote.dto.WatchProvider
import com.duckflix.lite.ui.components.LoadingIndicator
import com.duckflix.lite.ui.components.ProviderTile

/**
 * Classic Mode Tab - Provider-based browsing interface.
 * Displays streaming providers in a 3-column grid (like episode layout).
 * Logo centered in each tile.
 *
 * Grid spacing: 10dp (matches episode grid)
 */
@Composable
fun ClassicModeTab(
    onProviderClick: (WatchProvider) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ClassicModeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Optional title
        Text(
            text = "Browse by Provider",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        when {
            uiState.isLoading -> {
                // Show loading indicator centered
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.error != null -> {
                // Show error message
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Error: ${uiState.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Red
                    )
                }
            }

            uiState.providers.isEmpty() -> {
                // Show empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No providers available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                // Provider grid - 3 columns like episode layout
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    uiState.providers.chunked(3).forEach { rowProviders ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowProviders.forEach { provider ->
                                Box(modifier = Modifier.weight(1f)) {
                                    ProviderTile(
                                        provider = provider,
                                        onClick = { onProviderClick(provider) }
                                    )
                                }
                            }
                            // Fill remaining space for incomplete last row
                            repeat(3 - rowProviders.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
