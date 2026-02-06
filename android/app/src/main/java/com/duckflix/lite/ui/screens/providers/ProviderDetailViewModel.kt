package com.duckflix.lite.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.CollectionItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderDetailUiState(
    val providerId: Int = 0,
    val providerName: String = "",
    val providerLogoUrl: String? = null,
    val query: String = "",
    val discover: List<CollectionItem> = emptyList(),
    val movies: List<CollectionItem> = emptyList(),
    val tvShows: List<CollectionItem> = emptyList(),
    val isLoadingDiscover: Boolean = false,
    val isLoadingMovies: Boolean = false,
    val isLoadingTvShows: Boolean = false,
    val discoverError: String? = null,
    val moviesError: String? = null,
    val tvShowsError: String? = null
)

@HiltViewModel
class ProviderDetailViewModel @Inject constructor(
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderDetailUiState())
    val uiState: StateFlow<ProviderDetailUiState> = _uiState.asStateFlow()

    /**
     * Initialize the ViewModel with provider information.
     * Call this when the screen is first displayed.
     */
    fun setProvider(providerId: Int, name: String, logoUrl: String?) {
        if (_uiState.value.providerId != providerId) {
            _uiState.value = _uiState.value.copy(
                providerId = providerId,
                providerName = name,
                providerLogoUrl = logoUrl,
                query = "",
                discover = emptyList(),
                movies = emptyList(),
                tvShows = emptyList()
            )
            loadContent()
        }
    }

    /**
     * Update the search query for filtering within this provider.
     */
    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    /**
     * Search within this provider using the current query.
     * Uses TMDB search endpoint, then we display results.
     * Note: TMDB search doesn't filter by provider, so this is a text search
     * but results are shown in the provider context.
     */
    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) {
            // If query is empty, reload all content
            loadContent()
            return
        }

        val providerId = _uiState.value.providerId
        if (providerId == 0) return

        // Search for content using the query
        searchContent(query)
    }

    /**
     * Search for content using TMDB search, loading both movies and TV shows.
     */
    private fun searchContent(query: String) {
        viewModelScope.launch {
            // Set loading state for all sections
            _uiState.value = _uiState.value.copy(
                isLoadingDiscover = true,
                isLoadingMovies = true,
                isLoadingTvShows = true,
                discoverError = null,
                moviesError = null,
                tvShowsError = null
            )

            try {
                // Search both movies and TV in parallel
                val movieDeferred = async {
                    api.searchTmdb(query = query, type = "movie")
                }
                val tvDeferred = async {
                    api.searchTmdb(query = query, type = "tv")
                }

                val movieResults = movieDeferred.await().results.map { result ->
                    CollectionItem(
                        id = result.id,
                        title = result.title,
                        posterPath = result.posterPath,
                        overview = result.overview,
                        voteAverage = result.voteAverage,
                        releaseDate = null,
                        year = result.year,
                        mediaType = result.type // Use actual type from result
                    )
                }
                val tvResults = tvDeferred.await().results.map { result ->
                    CollectionItem(
                        id = result.id,
                        title = result.title,
                        posterPath = result.posterPath,
                        overview = result.overview,
                        voteAverage = result.voteAverage,
                        releaseDate = null,
                        year = result.year,
                        mediaType = result.type // Use actual type from result
                    )
                }

                // Interleave for discover, keep separate for movies/tv sections
                val interleaved = interleaveResults(movieResults, tvResults)

                println("[ProviderDetailViewModel] Search completed: ${movieResults.size} movies, ${tvResults.size} TV shows")
                _uiState.value = _uiState.value.copy(
                    discover = interleaved,
                    movies = movieResults,
                    tvShows = tvResults,
                    isLoadingDiscover = false,
                    isLoadingMovies = false,
                    isLoadingTvShows = false
                )
            } catch (e: Exception) {
                println("[ProviderDetailViewModel] Search error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingDiscover = false,
                    isLoadingMovies = false,
                    isLoadingTvShows = false,
                    discoverError = e.message,
                    moviesError = e.message,
                    tvShowsError = e.message
                )
            }
        }
    }

    /**
     * Load all content sections for this provider.
     * Fetches discover (mixed), movies, and TV shows in parallel.
     */
    fun loadContent() {
        val providerId = _uiState.value.providerId
        if (providerId == 0) return

        loadDiscover(providerId)
        loadMovies(providerId)
        loadTvShows(providerId)
    }

    private fun loadDiscover(providerId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingDiscover = true,
                discoverError = null
            )
            try {
                // Load both movie and TV content in parallel for mixed discover
                val movieDeferred = async {
                    api.discover(type = "movie", watchProvider = providerId)
                }
                val tvDeferred = async {
                    api.discover(type = "tv", watchProvider = providerId)
                }
                val movieResults = movieDeferred.await().results
                val tvResults = tvDeferred.await().results

                // Interleave results for variety
                val interleaved = interleaveResults(movieResults, tvResults)

                println("[ProviderDetailViewModel] Discover loaded: ${interleaved.size} items for provider $providerId")
                _uiState.value = _uiState.value.copy(
                    discover = interleaved,
                    isLoadingDiscover = false
                )
            } catch (e: Exception) {
                println("[ProviderDetailViewModel] Discover error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingDiscover = false,
                    discoverError = e.message
                )
            }
        }
    }

    private fun loadMovies(providerId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingMovies = true,
                moviesError = null
            )
            try {
                val results = api.discover(type = "movie", watchProvider = providerId).results
                println("[ProviderDetailViewModel] Movies loaded: ${results.size} items for provider $providerId")
                _uiState.value = _uiState.value.copy(
                    movies = results,
                    isLoadingMovies = false
                )
            } catch (e: Exception) {
                println("[ProviderDetailViewModel] Movies error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingMovies = false,
                    moviesError = e.message
                )
            }
        }
    }

    private fun loadTvShows(providerId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingTvShows = true,
                tvShowsError = null
            )
            try {
                val results = api.discover(type = "tv", watchProvider = providerId).results
                println("[ProviderDetailViewModel] TV Shows loaded: ${results.size} items for provider $providerId")
                _uiState.value = _uiState.value.copy(
                    tvShows = results,
                    isLoadingTvShows = false
                )
            } catch (e: Exception) {
                println("[ProviderDetailViewModel] TV Shows error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingTvShows = false,
                    tvShowsError = e.message
                )
            }
        }
    }

    /**
     * Interleave two lists of CollectionItem for variety when showing mixed content
     */
    private fun interleaveResults(
        list1: List<CollectionItem>,
        list2: List<CollectionItem>
    ): List<CollectionItem> {
        val result = mutableListOf<CollectionItem>()
        val maxSize = maxOf(list1.size, list2.size)
        for (i in 0 until maxSize) {
            if (i < list1.size) result.add(list1[i])
            if (i < list2.size) result.add(list2[i])
        }
        return result
    }

    /**
     * Refresh all content sections
     */
    fun refresh() {
        loadContent()
    }
}
