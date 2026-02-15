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
import android.util.Log
import javax.inject.Inject

private const val TAG = "DiscoverViewModel"

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
    val trendingPage: Int = 1,
    val trendingHasMore: Boolean = false,
    val popular: List<CollectionItem> = emptyList(),
    val popularPage: Int = 1,
    val popularHasMore: Boolean = false,
    val topRated: List<CollectionItem> = emptyList(),
    val topRatedPage: Int = 1,
    val topRatedHasMore: Boolean = false,
    val nowPlaying: List<CollectionItem> = emptyList(),
    val nowPlayingPage: Int = 1,
    val nowPlayingHasMore: Boolean = false,
    val isLoadingTrending: Boolean = false,
    val isLoadingPopular: Boolean = false,
    val isLoadingTopRated: Boolean = false,
    val isLoadingNowPlaying: Boolean = false,
    val isLoadingMoreTrending: Boolean = false,
    val isLoadingMorePopular: Boolean = false,
    val isLoadingMoreTopRated: Boolean = false,
    val isLoadingMoreNowPlaying: Boolean = false,
    val trendingError: String? = null,
    val popularError: String? = null,
    val topRatedError: String? = null,
    val nowPlayingError: String? = null,

    // Filter mode - shows paginated grid results
    val filterResults: List<CollectionItem> = emptyList(),
    val filterResultsPage: Int = 1,
    val filterResultsTotalPages: Int = 1,
    val filterResultsTotal: Int = 0,
    val filterHasMore: Boolean = false,
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
        Log.d(TAG, "setSelectedDecade called with: $decade (current: ${_uiState.value.selectedDecade})")
        if (_uiState.value.selectedDecade != decade) {
            Log.d(TAG, "Decade changed! Updating state and loading results")
            _uiState.value = _uiState.value.copy(selectedDecade = decade)
            loadFilterResults(resetPage = true)
        } else {
            Log.d(TAG, "Decade unchanged, skipping")
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
            filterResultsPage = 1,
            filterHasMore = false
        )
        loadBrowseContent()
    }

    fun loadMoreResults() {
        val state = _uiState.value
        Log.d(TAG, "loadMoreResults called: page=${state.filterResultsPage}, hasMore=${state.filterHasMore}, isLoadingMore=${state.isLoadingMoreResults}")

        if (state.isLoadingMoreResults || state.isLoadingFilterResults) return
        // Use hasMore from server, fallback to size heuristic
        if (!state.filterHasMore && state.filterResults.size < state.filterResultsPage * 20) {
            Log.d(TAG, "Skipping loadMore: no more results")
            return
        }
        Log.d(TAG, "Calling loadFilterResults for page ${state.filterResultsPage + 1}")
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
                if (state.mediaTypeFilter == MediaTypeFilter.ALL) {
                    // Parallel calls for movies and TV, mirroring browse row pattern
                    val movieDeferred = async {
                        api.discover(
                            type = "movie",
                            genre = state.selectedGenre,
                            minYear = state.selectedDecade?.startYear,
                            maxYear = state.selectedDecade?.endYear,
                            sortBy = state.sortBy.getApiValue(MediaTypeFilter.MOVIE),
                            page = page
                        )
                    }
                    val tvDeferred = async {
                        api.discover(
                            type = "tv",
                            genre = state.selectedGenre,
                            minYear = state.selectedDecade?.startYear,
                            maxYear = state.selectedDecade?.endYear,
                            sortBy = state.sortBy.getApiValue(MediaTypeFilter.TV),
                            page = page
                        )
                    }

                    val movieResponse = movieDeferred.await()
                    val tvResponse = tvDeferred.await()

                    // Tag results with correct mediaType since server may not include it
                    val movieResults = movieResponse.results.map { it.copy(mediaType = "movie") }
                    val tvResults = tvResponse.results.map { it.copy(mediaType = "tv") }
                    val interleaved = interleaveCollectionResults(movieResults, tvResults)

                    val newResults = if (resetPage) interleaved else state.filterResults + interleaved

                    val combinedHasMore = movieResponse.hasMore || tvResponse.hasMore

                    _uiState.value = _uiState.value.copy(
                        filterResults = newResults,
                        filterResultsPage = page,
                        filterResultsTotalPages = maxOf(movieResponse.totalPages, tvResponse.totalPages),
                        filterResultsTotal = movieResponse.totalResults + tvResponse.totalResults,
                        filterHasMore = combinedHasMore,
                        isLoadingFilterResults = false,
                        isLoadingMoreResults = false
                    )

                    Log.d(TAG, "RESPONSE (ALL): movies=${movieResults.size}, tv=${tvResults.size}, page $page, hasMore=$combinedHasMore")
                } else {
                    val type = when (state.mediaTypeFilter) {
                        MediaTypeFilter.TV -> "tv"
                        MediaTypeFilter.MOVIE -> "movie"
                        MediaTypeFilter.ALL -> "movie"
                    }

                    val sortValue = state.sortBy.getApiValue(state.mediaTypeFilter)

                    Log.d(TAG, "API CALL: type=$type, genre=${state.selectedGenre}, minYear=${state.selectedDecade?.startYear}, maxYear=${state.selectedDecade?.endYear}, sortBy=$sortValue, page=$page")

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
                        filterHasMore = response.hasMore,
                        isLoadingFilterResults = false,
                        isLoadingMoreResults = false
                    )

                    Log.d(TAG, "RESPONSE: ${response.results.size} items, page $page/${response.totalPages}, hasMore=${response.hasMore}")
                }
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
        loadTrending(filter, resetPage = true)
        loadPopular(filter, resetPage = true)
        loadTopRated(filter, resetPage = true)
        if (filter != MediaTypeFilter.TV) {
            loadNowPlaying(resetPage = true)
        } else {
            _uiState.value = _uiState.value.copy(nowPlaying = emptyList(), nowPlayingPage = 1, nowPlayingHasMore = false)
        }
    }

    fun loadMoreTrending() {
        val state = _uiState.value
        if (state.isLoadingMoreTrending || state.isLoadingTrending) return
        if (!state.trendingHasMore && state.trending.size < state.trendingPage * 20) return
        loadTrending(state.mediaTypeFilter, resetPage = false)
    }

    fun loadMorePopular() {
        val state = _uiState.value
        if (state.isLoadingMorePopular || state.isLoadingPopular) return
        if (!state.popularHasMore && state.popular.size < state.popularPage * 20) return
        loadPopular(state.mediaTypeFilter, resetPage = false)
    }

    fun loadMoreTopRated() {
        val state = _uiState.value
        if (state.isLoadingMoreTopRated || state.isLoadingTopRated) return
        if (!state.topRatedHasMore && state.topRated.size < state.topRatedPage * 20) return
        loadTopRated(state.mediaTypeFilter, resetPage = false)
    }

    fun loadMoreNowPlaying() {
        val state = _uiState.value
        if (state.isLoadingMoreNowPlaying || state.isLoadingNowPlaying) return
        if (!state.nowPlayingHasMore && state.nowPlaying.size < state.nowPlayingPage * 20) return
        loadNowPlaying(resetPage = false)
    }

    private fun loadTrending(filter: MediaTypeFilter, resetPage: Boolean) {
        viewModelScope.launch {
            val state = _uiState.value
            val page = if (resetPage) 1 else state.trendingPage + 1

            _uiState.value = state.copy(
                isLoadingTrending = resetPage,
                isLoadingMoreTrending = !resetPage,
                trendingError = null
            )
            try {
                when (filter) {
                    MediaTypeFilter.ALL -> {
                        val movieDeferred = async { api.getTrending(mediaType = "movie", timeWindow = "week", page = page) }
                        val tvDeferred = async { api.getTrending(mediaType = "tv", timeWindow = "week", page = page) }
                        val movieResp = movieDeferred.await()
                        val tvResp = tvDeferred.await()
                        val interleaved = interleaveResults(movieResp.results, tvResp.results)
                        val newItems = if (resetPage) interleaved else state.trending + interleaved
                        _uiState.value = _uiState.value.copy(
                            trending = newItems, trendingPage = page,
                            trendingHasMore = movieResp.hasMore || tvResp.hasMore,
                            isLoadingTrending = false, isLoadingMoreTrending = false
                        )
                    }
                    else -> {
                        val mediaType = if (filter == MediaTypeFilter.TV) "tv" else "movie"
                        val resp = api.getTrending(mediaType = mediaType, timeWindow = "week", page = page)
                        val newItems = if (resetPage) resp.results else state.trending + resp.results
                        _uiState.value = _uiState.value.copy(
                            trending = newItems, trendingPage = page,
                            trendingHasMore = resp.hasMore,
                            isLoadingTrending = false, isLoadingMoreTrending = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingTrending = false, isLoadingMoreTrending = false, trendingError = e.message
                )
            }
        }
    }

    private fun loadPopular(filter: MediaTypeFilter, resetPage: Boolean) {
        viewModelScope.launch {
            val state = _uiState.value
            val page = if (resetPage) 1 else state.popularPage + 1

            _uiState.value = state.copy(
                isLoadingPopular = resetPage,
                isLoadingMorePopular = !resetPage,
                popularError = null
            )
            try {
                when (filter) {
                    MediaTypeFilter.ALL -> {
                        val movieDeferred = async { api.getPopular(type = "movie", page = page) }
                        val tvDeferred = async { api.getPopular(type = "tv", page = page) }
                        val movieResp = movieDeferred.await()
                        val tvResp = tvDeferred.await()
                        val interleaved = interleaveCollectionResults(
                            movieResp.results.map { it.copy(mediaType = "movie") },
                            tvResp.results.map { it.copy(mediaType = "tv") }
                        )
                        val newItems = if (resetPage) interleaved else state.popular + interleaved
                        _uiState.value = _uiState.value.copy(
                            popular = newItems, popularPage = page,
                            popularHasMore = movieResp.hasMore || tvResp.hasMore,
                            isLoadingPopular = false, isLoadingMorePopular = false
                        )
                    }
                    else -> {
                        val type = if (filter == MediaTypeFilter.TV) "tv" else "movie"
                        val resp = api.getPopular(type = type, page = page)
                        val newItems = if (resetPage) resp.results else state.popular + resp.results
                        _uiState.value = _uiState.value.copy(
                            popular = newItems, popularPage = page,
                            popularHasMore = resp.hasMore,
                            isLoadingPopular = false, isLoadingMorePopular = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingPopular = false, isLoadingMorePopular = false, popularError = e.message
                )
            }
        }
    }

    private fun loadTopRated(filter: MediaTypeFilter, resetPage: Boolean) {
        viewModelScope.launch {
            val state = _uiState.value
            val page = if (resetPage) 1 else state.topRatedPage + 1

            _uiState.value = state.copy(
                isLoadingTopRated = resetPage,
                isLoadingMoreTopRated = !resetPage,
                topRatedError = null
            )
            try {
                when (filter) {
                    MediaTypeFilter.ALL -> {
                        val movieDeferred = async { api.getTopRated(type = "movie", page = page) }
                        val tvDeferred = async { api.getTopRated(type = "tv", page = page) }
                        val movieResp = movieDeferred.await()
                        val tvResp = tvDeferred.await()
                        val interleaved = interleaveCollectionResults(
                            movieResp.results.map { it.copy(mediaType = "movie") },
                            tvResp.results.map { it.copy(mediaType = "tv") }
                        )
                        val newItems = if (resetPage) interleaved else state.topRated + interleaved
                        _uiState.value = _uiState.value.copy(
                            topRated = newItems, topRatedPage = page,
                            topRatedHasMore = movieResp.hasMore || tvResp.hasMore,
                            isLoadingTopRated = false, isLoadingMoreTopRated = false
                        )
                    }
                    else -> {
                        val type = if (filter == MediaTypeFilter.TV) "tv" else "movie"
                        val resp = api.getTopRated(type = type, page = page)
                        val newItems = if (resetPage) resp.results else state.topRated + resp.results
                        _uiState.value = _uiState.value.copy(
                            topRated = newItems, topRatedPage = page,
                            topRatedHasMore = resp.hasMore,
                            isLoadingTopRated = false, isLoadingMoreTopRated = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingTopRated = false, isLoadingMoreTopRated = false, topRatedError = e.message
                )
            }
        }
    }

    private fun loadNowPlaying(resetPage: Boolean) {
        viewModelScope.launch {
            val state = _uiState.value
            val page = if (resetPage) 1 else state.nowPlayingPage + 1

            _uiState.value = state.copy(
                isLoadingNowPlaying = resetPage,
                isLoadingMoreNowPlaying = !resetPage,
                nowPlayingError = null
            )
            try {
                val resp = api.getNowPlaying(page = page)
                val newItems = if (resetPage) resp.results else state.nowPlaying + resp.results
                _uiState.value = _uiState.value.copy(
                    nowPlaying = newItems, nowPlayingPage = page,
                    nowPlayingHasMore = resp.hasMore,
                    isLoadingNowPlaying = false, isLoadingMoreNowPlaying = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingNowPlaying = false, isLoadingMoreNowPlaying = false, nowPlayingError = e.message
                )
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
