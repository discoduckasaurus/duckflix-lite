package com.duckflix.lite.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.WatchProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for Classic Mode (provider-based browsing)
 */
data class ClassicModeUiState(
    val providers: List<WatchProvider> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for Classic Mode - loads streaming providers for provider-based browsing.
 * Uses the watch providers endpoint to fetch available streaming services.
 */
@HiltViewModel
class ClassicModeViewModel @Inject constructor(
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClassicModeUiState())
    val uiState: StateFlow<ClassicModeUiState> = _uiState.asStateFlow()

    init {
        loadProviders()
    }

    /**
     * Load streaming providers from the API
     */
    private fun loadProviders() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null
            )
            try {
                val response = api.getWatchProviders(type = "movie", region = "US")
                // Sort by display priority (lower = higher priority) if available
                val sortedProviders = response.providers.sortedBy { it.displayPriority ?: Int.MAX_VALUE }
                println("[ClassicModeViewModel] Providers loaded: ${sortedProviders.size} items")
                _uiState.value = _uiState.value.copy(
                    providers = sortedProviders,
                    isLoading = false
                )
            } catch (e: Exception) {
                println("[ClassicModeViewModel] Error loading providers: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load providers"
                )
            }
        }
    }

    /**
     * Refresh the providers list
     */
    fun refresh() {
        loadProviders()
    }
}
