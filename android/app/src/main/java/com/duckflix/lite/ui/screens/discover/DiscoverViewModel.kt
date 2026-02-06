package com.duckflix.lite.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.CollectionItem
import com.duckflix.lite.data.remote.dto.TrendingResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MediaTypeFilter { ALL, MOVIE, TV }

data class DiscoverUiState(
    val mediaTypeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    val trending: List<TrendingResult> = emptyList(),
    val popular: List<CollectionItem> = emptyList(),
    val topRated: List<CollectionItem> = emptyList(),
    val nowPlaying: List<CollectionItem> = emptyList(), // movies only
    val isLoadingTrending: Boolean = false,
    val isLoadingPopular: Boolean = false,
    val isLoadingTopRated: Boolean = false,
    val isLoadingNowPlaying: Boolean = false,
    val trendingError: String? = null,
    val popularError: String? = null,
    val topRatedError: String? = null,
    val nowPlayingError: String? = null
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    init {
        loadContent()
    }

    fun setMediaTypeFilter(filter: MediaTypeFilter) {
        if (_uiState.value.mediaTypeFilter != filter) {
            _uiState.value = _uiState.value.copy(mediaTypeFilter = filter)
            loadContent()
        }
    }

    fun loadContent() {
        val filter = _uiState.value.mediaTypeFilter
        loadTrending(filter)
        loadPopular(filter)
        loadTopRated(filter)
        // Now Playing is movies only - load when filter is ALL or MOVIE
        if (filter != MediaTypeFilter.TV) {
            loadNowPlaying()
        } else {
            // Clear now playing when TV filter is active
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
                        // Load both movie and TV trending in parallel
                        val movieDeferred = async { api.getTrending(mediaType = "movie", timeWindow = "week") }
                        val tvDeferred = async { api.getTrending(mediaType = "tv", timeWindow = "week") }
                        val movieResults = movieDeferred.await().results
                        val tvResults = tvDeferred.await().results
                        // Interleave results for variety
                        interleaveResults(movieResults, tvResults)
                    }
                    MediaTypeFilter.MOVIE -> {
                        api.getTrending(mediaType = "movie", timeWindow = "week").results
                    }
                    MediaTypeFilter.TV -> {
                        api.getTrending(mediaType = "tv", timeWindow = "week").results
                    }
                }
                println("[DiscoverViewModel] Trending loaded: ${results.size} items")
                _uiState.value = _uiState.value.copy(
                    trending = results,
                    isLoadingTrending = false
                )
            } catch (e: Exception) {
                println("[DiscoverViewModel] Trending error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingTrending = false,
                    trendingError = e.message
                )
            }
        }
    }

    private fun loadPopular(filter: MediaTypeFilter) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingPopular = true,
                popularError = null
            )
            try {
                val results = when (filter) {
                    MediaTypeFilter.ALL -> {
                        // Load both movie and TV popular in parallel
                        val movieDeferred = async { api.getPopular(type = "movie") }
                        val tvDeferred = async { api.getPopular(type = "tv") }
                        val movieResults = movieDeferred.await().results
                        val tvResults = tvDeferred.await().results
                        // Interleave results for variety
                        interleaveCollectionResults(movieResults, tvResults)
                    }
                    MediaTypeFilter.MOVIE -> {
                        api.getPopular(type = "movie").results
                    }
                    MediaTypeFilter.TV -> {
                        api.getPopular(type = "tv").results
                    }
                }
                println("[DiscoverViewModel] Popular loaded: ${results.size} items")
                _uiState.value = _uiState.value.copy(
                    popular = results,
                    isLoadingPopular = false
                )
            } catch (e: Exception) {
                println("[DiscoverViewModel] Popular error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingPopular = false,
                    popularError = e.message
                )
            }
        }
    }

    private fun loadTopRated(filter: MediaTypeFilter) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingTopRated = true,
                topRatedError = null
            )
            try {
                val results = when (filter) {
                    MediaTypeFilter.ALL -> {
                        // Load both movie and TV top rated in parallel
                        val movieDeferred = async { api.getTopRated(type = "movie") }
                        val tvDeferred = async { api.getTopRated(type = "tv") }
                        val movieResults = movieDeferred.await().results
                        val tvResults = tvDeferred.await().results
                        // Interleave results for variety
                        interleaveCollectionResults(movieResults, tvResults)
                    }
                    MediaTypeFilter.MOVIE -> {
                        api.getTopRated(type = "movie").results
                    }
                    MediaTypeFilter.TV -> {
                        api.getTopRated(type = "tv").results
                    }
                }
                println("[DiscoverViewModel] Top Rated loaded: ${results.size} items")
                _uiState.value = _uiState.value.copy(
                    topRated = results,
                    isLoadingTopRated = false
                )
            } catch (e: Exception) {
                println("[DiscoverViewModel] Top Rated error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingTopRated = false,
                    topRatedError = e.message
                )
            }
        }
    }

    private fun loadNowPlaying() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingNowPlaying = true,
                nowPlayingError = null
            )
            try {
                val results = api.getNowPlaying().results
                println("[DiscoverViewModel] Now Playing loaded: ${results.size} items")
                _uiState.value = _uiState.value.copy(
                    nowPlaying = results,
                    isLoadingNowPlaying = false
                )
            } catch (e: Exception) {
                println("[DiscoverViewModel] Now Playing error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingNowPlaying = false,
                    nowPlayingError = e.message
                )
            }
        }
    }

    /**
     * Interleave two lists of TrendingResult for variety when showing ALL content
     */
    private fun interleaveResults(list1: List<TrendingResult>, list2: List<TrendingResult>): List<TrendingResult> {
        val result = mutableListOf<TrendingResult>()
        val maxSize = maxOf(list1.size, list2.size)
        for (i in 0 until maxSize) {
            if (i < list1.size) result.add(list1[i])
            if (i < list2.size) result.add(list2[i])
        }
        return result
    }

    /**
     * Interleave two lists of CollectionItem for variety when showing ALL content
     */
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
        loadContent()
    }
}
