package com.duckflix.lite.ui.screens.advancedsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.CollectionItem
import com.duckflix.lite.data.remote.dto.GenreDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sort options for advanced search results
 */
enum class SortOption(val apiValue: String, val displayName: String) {
    POPULARITY_DESC("popularity.desc", "Most Popular"),
    RATING_DESC("vote_average.desc", "Highest Rated"),
    RELEASE_DATE_DESC("release_date.desc", "Newest First"),
    TITLE_ASC("title.asc", "A-Z")
}

/**
 * UI state for the Advanced Search tab
 */
data class AdvancedSearchUiState(
    val query: String = "",
    val tvSelected: Boolean = false,
    val movieSelected: Boolean = false,
    val selectedGenres: List<Int> = emptyList(),
    val selectedYear: Int? = null,
    val ratingRange: ClosedFloatingPointRange<Float> = 0f..10f,
    val runtimeRange: IntRange = 0..300,
    val sortBy: String = SortOption.POPULARITY_DESC.apiValue,
    val availableGenres: List<GenreDto> = emptyList(),
    val results: List<CollectionItem> = emptyList(),
    val totalResults: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingGenres: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AdvancedSearchViewModel @Inject constructor(
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdvancedSearchUiState())
    val uiState: StateFlow<AdvancedSearchUiState> = _uiState.asStateFlow()

    init {
        loadGenres()
        // Execute default search to pre-populate results
        search()
    }

    /**
     * Load genres for both movie and TV, combined and deduplicated
     */
    private fun loadGenres() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingGenres = true)
            try {
                // Load movie and TV genres in parallel
                val movieGenresDeferred = async { api.getGenres(type = "movie") }
                val tvGenresDeferred = async { api.getGenres(type = "tv") }

                val movieGenres = movieGenresDeferred.await().genres
                val tvGenres = tvGenresDeferred.await().genres

                // Combine and deduplicate by ID
                val combinedGenres = (movieGenres + tvGenres)
                    .distinctBy { it.id }
                    .sortedBy { it.name }

                println("[AdvancedSearchViewModel] Loaded ${combinedGenres.size} genres")
                _uiState.value = _uiState.value.copy(
                    availableGenres = combinedGenres,
                    isLoadingGenres = false
                )
            } catch (e: Exception) {
                println("[AdvancedSearchViewModel] Error loading genres: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingGenres = false,
                    error = "Failed to load genres: ${e.message}"
                )
            }
        }
    }

    /**
     * Update the search query text
     */
    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    /**
     * Toggle TV filter
     */
    fun toggleTv() {
        _uiState.value = _uiState.value.copy(
            tvSelected = !_uiState.value.tvSelected
        )
    }

    /**
     * Toggle Movie filter
     */
    fun toggleMovie() {
        _uiState.value = _uiState.value.copy(
            movieSelected = !_uiState.value.movieSelected
        )
    }

    /**
     * Update selected genres
     */
    fun setSelectedGenres(genres: List<Int>) {
        _uiState.value = _uiState.value.copy(selectedGenres = genres)
    }

    /**
     * Update selected year filter
     */
    fun setSelectedYear(year: Int?) {
        _uiState.value = _uiState.value.copy(selectedYear = year)
    }

    /**
     * Update rating range filter
     */
    fun setRatingRange(range: ClosedFloatingPointRange<Float>) {
        _uiState.value = _uiState.value.copy(ratingRange = range)
    }

    /**
     * Update runtime range filter (in minutes)
     */
    fun setRuntimeRange(range: IntRange) {
        _uiState.value = _uiState.value.copy(runtimeRange = range)
    }

    /**
     * Update sort option
     */
    fun setSortBy(sort: String) {
        _uiState.value = _uiState.value.copy(sortBy = sort)
    }

    /**
     * Clear any error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Execute search with current filters.
     * - If query is non-empty: uses searchTmdb (basic search, filters not supported by API)
     * - If query is empty: uses discover with all filter parameters
     */
    fun search() {
        val state = _uiState.value

        viewModelScope.launch {
            _uiState.value = state.copy(
                isLoading = true,
                error = null
            )

            try {
                val results: List<CollectionItem>
                val totalResults: Int

                if (state.query.isNotBlank()) {
                    // Query-based search using searchTmdb
                    // Note: searchTmdb doesn't support advanced filters
                    val type = determineMediaType(state.tvSelected, state.movieSelected)
                    val response = api.searchTmdb(
                        query = state.query,
                        type = type ?: "movie" // Default to movie if no type specified
                    )
                    // Convert TmdbSearchResult to CollectionItem for unified display
                    results = response.results.map { result ->
                        CollectionItem(
                            id = result.id,
                            title = result.title,
                            posterPath = result.posterPath,
                            overview = result.overview,
                            voteAverage = result.voteAverage,
                            releaseDate = result.year?.let { "$it-01-01" }, // Approximate date
                            mediaType = result.type
                        )
                    }
                    totalResults = results.size
                    println("[AdvancedSearchViewModel] Search query '${state.query}' returned ${results.size} results")
                } else {
                    // Discover-based search with filters
                    val type = determineMediaType(state.tvSelected, state.movieSelected)

                    // Only send filter params if they differ from defaults
                    val minRating = if (state.ratingRange.start > 0f) state.ratingRange.start else null
                    val maxRating = if (state.ratingRange.endInclusive < 10f) state.ratingRange.endInclusive else null
                    val minRuntime = if (state.runtimeRange.first > 0) state.runtimeRange.first else null
                    val maxRuntime = if (state.runtimeRange.last < 300) state.runtimeRange.last else null

                    // For genre, use first selected genre (API takes single genre)
                    val genreId = state.selectedGenres.firstOrNull()

                    val response = api.discover(
                        type = type,
                        genre = genreId,
                        year = state.selectedYear,
                        minRating = minRating,
                        maxRating = maxRating,
                        minRuntime = minRuntime,
                        maxRuntime = maxRuntime,
                        sortBy = state.sortBy
                    )
                    results = response.results
                    totalResults = response.totalResults
                    println("[AdvancedSearchViewModel] Discover returned ${results.size} results (total: $totalResults)")
                }

                _uiState.value = _uiState.value.copy(
                    results = results,
                    totalResults = totalResults,
                    isLoading = false
                )
            } catch (e: Exception) {
                println("[AdvancedSearchViewModel] Search error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Search failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Determine the media type parameter based on toggle states.
     * - Both off or both on = null (all content)
     * - TV only = "tv"
     * - Movie only = "movie"
     */
    private fun determineMediaType(tvSelected: Boolean, movieSelected: Boolean): String? {
        return when {
            tvSelected && !movieSelected -> "tv"
            movieSelected && !tvSelected -> "movie"
            else -> null // Both on or both off = show all
        }
    }
}
