package com.duckflix.lite.ui.screens.advancedsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.CollectionItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Search tab (simplified - no advanced filters)
 */
data class AdvancedSearchUiState(
    val query: String = "",
    val tvSelected: Boolean = false,
    val movieSelected: Boolean = false,
    val results: List<CollectionItem> = emptyList(),
    val totalResults: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AdvancedSearchViewModel @Inject constructor(
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdvancedSearchUiState())
    val uiState: StateFlow<AdvancedSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private val searchDebounceMs = 400L

    /**
     * Update the search query text with debounced auto-search
     */
    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        // Debounce search while typing
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(searchDebounceMs)
            search()
        }
    }

    /**
     * Toggle TV filter - auto-triggers search
     */
    fun toggleTv() {
        _uiState.value = _uiState.value.copy(
            tvSelected = !_uiState.value.tvSelected
        )
        searchImmediate()
    }

    /**
     * Toggle Movie filter - auto-triggers search
     */
    fun toggleMovie() {
        _uiState.value = _uiState.value.copy(
            movieSelected = !_uiState.value.movieSelected
        )
        searchImmediate()
    }

    /**
     * Trigger search immediately (cancels any pending debounced search)
     */
    private fun searchImmediate() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            search()
        }
    }

    /**
     * Execute search with current query and TV/Movie filter
     */
    fun search() {
        val state = _uiState.value

        // Don't search if query is empty
        if (state.query.isBlank()) {
            _uiState.value = state.copy(
                results = emptyList(),
                totalResults = 0,
                isLoading = false,
                error = null
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(
                isLoading = true,
                error = null
            )

            try {
                val type = determineMediaType(state.tvSelected, state.movieSelected)

                val searchResults = if (type == null) {
                    // No type filter - search both movies and TV in parallel
                    val movieDeferred = async { api.searchTmdb(query = state.query, type = "movie") }
                    val tvDeferred = async { api.searchTmdb(query = state.query, type = "tv") }
                    val movieResults = movieDeferred.await().results
                    val tvResults = tvDeferred.await().results
                    // Combine and interleave results (movie, tv, movie, tv...)
                    val combined = mutableListOf<com.duckflix.lite.data.remote.dto.TmdbSearchResult>()
                    val maxSize = maxOf(movieResults.size, tvResults.size)
                    for (i in 0 until maxSize) {
                        if (i < movieResults.size) combined.add(movieResults[i])
                        if (i < tvResults.size) combined.add(tvResults[i])
                    }
                    combined
                } else {
                    api.searchTmdb(query = state.query, type = type).results
                }

                // Convert TmdbSearchResult to CollectionItem for unified display
                val results = searchResults.map { result ->
                    CollectionItem(
                        id = result.id,
                        title = result.title,
                        posterPath = result.posterPath,
                        overview = result.overview,
                        voteAverage = result.voteAverage,
                        releaseDate = result.year?.let { "$it-01-01" },
                        mediaType = result.type
                    )
                }

                _uiState.value = _uiState.value.copy(
                    results = results,
                    totalResults = results.size,
                    isLoading = false
                )
            } catch (e: Exception) {
                println("[SearchViewModel] Search error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Search failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Determine the media type parameter based on toggle states.
     */
    private fun determineMediaType(tvSelected: Boolean, movieSelected: Boolean): String? {
        return when {
            tvSelected && !movieSelected -> "tv"
            movieSelected && !tvSelected -> "movie"
            else -> null // Both on or both off = show all
        }
    }
}
