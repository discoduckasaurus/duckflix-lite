package com.duckflix.lite.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.CollectionItem
import com.duckflix.lite.data.remote.dto.GenreDto
import com.duckflix.lite.data.remote.dto.TrendingResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MediaTypeFilter { ALL, MOVIE, TV }

/**
 * Sort options for discover results.
 * Some options only work for movies or TV - indicated by movieOnly/tvOnly flags.
 */
enum class SortOption(
    val movieApiValue: String,
    val tvApiValue: String,
    val displayName: String,
    val movieOnly: Boolean = false,
    val tvOnly: Boolean = false
) {
    POPULARITY_DESC("popularity.desc", "popularity.desc", "Most Popular"),
    RATING_DESC("vote_average.desc", "vote_average.desc", "Highest Rated"),
    NEWEST("primary_release_date.desc", "first_air_date.desc", "Newest First"),
    OLDEST("primary_release_date.asc", "first_air_date.asc", "Oldest First"),
    TITLE_ASC("title.asc", "name.asc", "A-Z"),
    RUNTIME_SHORT("runtime.asc", "runtime.asc", "Shortest", movieOnly = true),
    RUNTIME_LONG("runtime.desc", "runtime.desc", "Longest", movieOnly = true);

    fun getApiValue(mediaType: MediaTypeFilter): String {
        return when (mediaType) {
            MediaTypeFilter.TV -> tvApiValue
            MediaTypeFilter.MOVIE -> movieApiValue
            MediaTypeFilter.ALL -> movieApiValue // Default to movie sort
        }
    }

    fun isAvailable(mediaType: MediaTypeFilter): Boolean {
        return when {
            movieOnly && mediaType == MediaTypeFilter.TV -> false
            tvOnly && mediaType == MediaTypeFilter.MOVIE -> false
            else -> true
        }
    }
}

/**
 * Represents a decade for filtering content (e.g., "2020s", "2010s")
 */
data class Decade(val startYear: Int, val displayName: String) {
    val endYear: Int get() = startYear + 9

    companion object {
        fun generateDecades(): List<Decade> = listOf(
            Decade(2020, "2020s"),
            Decade(2010, "2010s"),
            Decade(2000, "2000s"),
            Decade(1990, "1990s"),
            Decade(1980, "1980s"),
            Decade(1970, "1970s"),
            Decade(1960, "1960s"),
            Decade(1950, "1950s"),
            Decade(1940, "1940s"),
            Decade(1930, "1930s"),
            Decade(1920, "1920s"),
            Decade(1910, "1910s"),
            Decade(1900, "1900s")
        )
    }
}

data class DiscoverUiState(
    // Filter state
    val mediaTypeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    val advancedFiltersExpanded: Boolean = false,
    val selectedGenre: Int? = null,
    val selectedDecade: Decade? = null,
    val sortBy: SortOption = SortOption.POPULARITY_DESC,
    val availableGenres: List<GenreDto> = emptyList(),
    val isLoadingGenres: Boolean = false,

    // Browse mode (no filters active) - shows category rows
    val trending: List<TrendingResult> = emptyList(),
    val popular: List<CollectionItem> = emptyList(),
    val topRated: List<CollectionItem> = emptyList(),
    val nowPlaying: List<CollectionItem> = emptyList(),
    val isLoadingTrending: Boolean = false,
    val isLoadingPopular: Boolean = false,
    val isLoadingTopRated: Boolean = false,
    val isLoadingNowPlaying: Boolean = false,
    val trendingError: String? = null,
    val popularError: String? = null,
    val topRatedError: String? = null,
    val nowPlayingError: String? = null,

    // Filter mode - shows paginated grid results
    val filterResults: List<CollectionItem> = emptyList(),
    val filterResultsPage: Int = 1,
    val filterResultsTotalPages: Int = 1,
    val filterResultsTotal: Int = 0,
    val isLoadingFilterResults: Boolean = false,
    val isLoadingMoreResults: Boolean = false,
    val filterError: String? = null
) {
    /**
     * True when any filter beyond just media type is active
     */
    val hasActiveFilters: Boolean
        get() = selectedGenre != null || selectedDecade != null || sortBy != SortOption.POPULARITY_DESC

    /**
     * True when we should show the filter results grid instead of browse rows
     */
    val showFilterResults: Boolean
        get() = hasActiveFilters
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var filterJob: Job? = null

    init {
        loadGenres()
        loadBrowseContent()
    }

    private fun loadGenres() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingGenres = true)
            try {
                val movieGenresDeferred = async { api.getGenres(type = "movie") }
                val tvGenresDeferred = async { api.getGenres(type = "tv") }

                val movieGenres = movieGenresDeferred.await().genres
                val tvGenres = tvGenresDeferred.await().genres

                val combinedGenres = (movieGenres + tvGenres)
                    .distinctBy { it.id }
                    .sortedBy { it.name }

                println("[DiscoverViewModel] Loaded ${combinedGenres.size} genres: ${combinedGenres.map { "${it.name}(${it.id})" }.joinToString(", ")}")
                _uiState.value = _uiState.value.copy(
                    availableGenres = combinedGenres,
                    isLoadingGenres = false
                )
            } catch (e: Exception) {
                println("[DiscoverViewModel] Error loading genres: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoadingGenres = false)
            }
        }
    }

    fun setMediaTypeFilter(filter: MediaTypeFilter) {
        if (_uiState.value.mediaTypeFilter != filter) {
            // Reset sort if current sort is not available for new filter
            val currentSort = _uiState.value.sortBy
            val newSort = if (!currentSort.isAvailable(filter)) SortOption.POPULARITY_DESC else currentSort

            _uiState.value = _uiState.value.copy(
                mediaTypeFilter = filter,
                sortBy = newSort
            )

            if (_uiState.value.hasActiveFilters) {
                loadFilterResults(resetPage = true)
            } else {
                loadBrowseContent()
            }
        }
    }

    fun toggleAdvancedFilters() {
        _uiState.value = _uiState.value.copy(
            advancedFiltersExpanded = !_uiState.value.advancedFiltersExpanded
        )
    }

    fun setSelectedGenre(genreId: Int?) {
        if (_uiState.value.selectedGenre != genreId) {
            _uiState.value = _uiState.value.copy(selectedGenre = genreId)
            loadFilterResults(resetPage = true)
        }
    }

    fun setSelectedDecade(decade: Decade?) {
        if (_uiState.value.selectedDecade != decade) {
            _uiState.value = _uiState.value.copy(selectedDecade = decade)
            loadFilterResults(resetPage = true)
        }
    }

    fun setSortBy(sort: SortOption) {
        if (_uiState.value.sortBy != sort) {
            _uiState.value = _uiState.value.copy(sortBy = sort)
            loadFilterResults(resetPage = true)
        }
    }

    fun clearFilters() {
        _uiState.value = _uiState.value.copy(
            selectedGenre = null,
            selectedDecade = null,
            sortBy = SortOption.POPULARITY_DESC,
            filterResults = emptyList(),
            filterResultsPage = 1
        )
        loadBrowseContent()
    }

    fun loadMoreResults() {
        val state = _uiState.value
        if (state.isLoadingMoreResults || state.filterResultsPage >= state.filterResultsTotalPages) {
            return
        }
        loadFilterResults(resetPage = false)
    }

    private fun loadFilterResults(resetPage: Boolean) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val state = _uiState.value
            val page = if (resetPage) 1 else state.filterResultsPage + 1

            _uiState.value = state.copy(
                isLoadingFilterResults = resetPage,
                isLoadingMoreResults = !resetPage,
                filterError = null
            )

            try {
                val type = when (state.mediaTypeFilter) {
                    MediaTypeFilter.TV -> "tv"
                    MediaTypeFilter.MOVIE -> "movie"
                    MediaTypeFilter.ALL -> null
                }

                val sortValue = state.sortBy.getApiValue(state.mediaTypeFilter)

                println("[DiscoverViewModel] Calling discover: type=$type, genre=${state.selectedGenre}, minYear=${state.selectedDecade?.startYear}, maxYear=${state.selectedDecade?.endYear}, sortBy=$sortValue, page=$page")

                val response = api.discover(
                    type = type,
                    genre = state.selectedGenre,
                    minYear = state.selectedDecade?.startYear,
                    maxYear = state.selectedDecade?.endYear,
                    sortBy = sortValue,
                    page = page
                )

                val newResults = if (resetPage) {
                    response.results
                } else {
                    state.filterResults + response.results
                }

                _uiState.value = _uiState.value.copy(
                    filterResults = newResults,
                    filterResultsPage = page,
                    filterResultsTotalPages = response.totalPages,
                    filterResultsTotal = response.totalResults,
                    isLoadingFilterResults = false,
                    isLoadingMoreResults = false
                )

                println("[DiscoverViewModel] Filter results: ${response.results.size} items, page $page/${response.totalPages}")
            } catch (e: Exception) {
                println("[DiscoverViewModel] Filter error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoadingFilterResults = false,
                    isLoadingMoreResults = false,
                    filterError = e.message
                )
            }
        }
    }

    private fun loadBrowseContent() {
        val filter = _uiState.value.mediaTypeFilter
        loadTrending(filter)
        loadPopular(filter)
        loadTopRated(filter)
        if (filter != MediaTypeFilter.TV) {
            loadNowPlaying()
        } else {
            _uiState.value = _uiState.value.copy(nowPlaying = emptyList())
        }
    }

    private fun loadTrending(filter: MediaTypeFilter) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingTrending = true,
                trendingError = null
            )
            try {
                val results = when (filter) {
                    MediaTypeFilter.ALL -> {
                        val movieDeferred = async { api.getTrending(mediaType = "movie", timeWindow = "week") }
                        val tvDeferred = async { api.getTrending(mediaType = "tv", timeWindow = "week") }
                        interleaveResults(movieDeferred.await().results, tvDeferred.await().results)
                    }
                    MediaTypeFilter.MOVIE -> api.getTrending(mediaType = "movie", timeWindow = "week").results
                    MediaTypeFilter.TV -> api.getTrending(mediaType = "tv", timeWindow = "week").results
                }
                _uiState.value = _uiState.value.copy(trending = results, isLoadingTrending = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingTrending = false, trendingError = e.message)
            }
        }
    }

    private fun loadPopular(filter: MediaTypeFilter) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPopular = true, popularError = null)
            try {
                val results = when (filter) {
                    MediaTypeFilter.ALL -> {
                        val movieDeferred = async { api.getPopular(type = "movie") }
                        val tvDeferred = async { api.getPopular(type = "tv") }
                        interleaveCollectionResults(movieDeferred.await().results, tvDeferred.await().results)
                    }
                    MediaTypeFilter.MOVIE -> api.getPopular(type = "movie").results
                    MediaTypeFilter.TV -> api.getPopular(type = "tv").results
                }
                _uiState.value = _uiState.value.copy(popular = results, isLoadingPopular = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingPopular = false, popularError = e.message)
            }
        }
    }

    private fun loadTopRated(filter: MediaTypeFilter) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingTopRated = true, topRatedError = null)
            try {
                val results = when (filter) {
                    MediaTypeFilter.ALL -> {
                        val movieDeferred = async { api.getTopRated(type = "movie") }
                        val tvDeferred = async { api.getTopRated(type = "tv") }
                        interleaveCollectionResults(movieDeferred.await().results, tvDeferred.await().results)
                    }
                    MediaTypeFilter.MOVIE -> api.getTopRated(type = "movie").results
                    MediaTypeFilter.TV -> api.getTopRated(type = "tv").results
                }
                _uiState.value = _uiState.value.copy(topRated = results, isLoadingTopRated = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingTopRated = false, topRatedError = e.message)
            }
        }
    }

    private fun loadNowPlaying() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingNowPlaying = true, nowPlayingError = null)
            try {
                val results = api.getNowPlaying().results
                _uiState.value = _uiState.value.copy(nowPlaying = results, isLoadingNowPlaying = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingNowPlaying = false, nowPlayingError = e.message)
            }
        }
    }

    private fun interleaveResults(list1: List<TrendingResult>, list2: List<TrendingResult>): List<TrendingResult> {
        val result = mutableListOf<TrendingResult>()
        val maxSize = maxOf(list1.size, list2.size)
        for (i in 0 until maxSize) {
            if (i < list1.size) result.add(list1[i])
            if (i < list2.size) result.add(list2[i])
        }
        return result
    }

    private fun interleaveCollectionResults(list1: List<CollectionItem>, list2: List<CollectionItem>): List<CollectionItem> {
        val result = mutableListOf<CollectionItem>()
        val maxSize = maxOf(list1.size, list2.size)
        for (i in 0 until maxSize) {
            if (i < list1.size) result.add(list1[i])
            if (i < list2.size) result.add(list2[i])
        }
        return result
    }

    fun refresh() {
        if (_uiState.value.hasActiveFilters) {
            loadFilterResults(resetPage = true)
        } else {
            loadBrowseContent()
        }
    }
}
