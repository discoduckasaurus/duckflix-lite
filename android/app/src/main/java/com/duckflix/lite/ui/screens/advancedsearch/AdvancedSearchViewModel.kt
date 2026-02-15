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
    val page: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
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
     * Execute search with current query and TV/Movie filter (resets to page 1)
     */
    fun search() {
        performSearch(resetPage = true)
    }

    /**
     * Load more search results (next page)
     */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || state.isLoading || state.query.isBlank()) return
        if (!state.hasMore && state.results.size < state.page * 20) return
        performSearch(resetPage = false)
    }

    private fun performSearch(resetPage: Boolean) {
        val state = _uiState.value

        if (state.query.isBlank()) {
            _uiState.value = state.copy(
                results = emptyList(),
                totalResults = 0,
                page = 1,
                hasMore = false,
                isLoading = false,
                error = null
            )
            return
        }

        viewModelScope.launch {
            val page = if (resetPage) 1 else state.page + 1

            _uiState.value = state.copy(
                isLoading = resetPage,
                isLoadingMore = !resetPage,
                error = null
            )

            try {
                val type = determineMediaType(state.tvSelected, state.movieSelected)

                val searchResults: List<com.duckflix.lite.data.remote.dto.TmdbSearchResult>
                val combinedHasMore: Boolean

                if (type == null) {
                    val movieDeferred = async { api.searchTmdb(query = state.query, type = "movie", page = page) }
                    val tvDeferred = async { api.searchTmdb(query = state.query, type = "tv", page = page) }
                    val movieResp = movieDeferred.await()
                    val tvResp = tvDeferred.await()
                    val combined = mutableListOf<com.duckflix.lite.data.remote.dto.TmdbSearchResult>()
                    val maxSize = maxOf(movieResp.results.size, tvResp.results.size)
                    for (i in 0 until maxSize) {
                        if (i < movieResp.results.size) combined.add(movieResp.results[i])
                        if (i < tvResp.results.size) combined.add(tvResp.results[i])
                    }
                    searchResults = combined
                    combinedHasMore = movieResp.hasMore || tvResp.hasMore
                } else {
                    val resp = api.searchTmdb(query = state.query, type = type, page = page)
                    searchResults = resp.results
                    combinedHasMore = resp.hasMore
                }

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

                val newResults = if (resetPage) results else state.results + results

                _uiState.value = _uiState.value.copy(
                    results = newResults,
                    totalResults = newResults.size,
                    page = page,
                    hasMore = combinedHasMore,
                    isLoading = false,
                    isLoadingMore = false
                )
            } catch (e: Exception) {
                println("[SearchViewModel] Search error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
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
