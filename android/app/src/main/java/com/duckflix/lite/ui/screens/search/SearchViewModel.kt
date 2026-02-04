package com.duckflix.lite.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.local.dao.RecentSearchDao
import com.duckflix.lite.data.local.dao.WatchProgressDao
import com.duckflix.lite.data.local.entity.RecentSearchEntity
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.TmdbSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecentItem(
    val tmdbId: Int,
    val title: String,
    val type: String,
    val year: String?,
    val posterUrl: String?,
    val voteAverage: Double? = null
)

data class SearchUiState(
    val query: String = "",
    val topResults: List<TmdbSearchResult> = emptyList(), // Mixed movies + TV by relevance
    val movieResults: List<TmdbSearchResult> = emptyList(),
    val tvResults: List<TmdbSearchResult> = emptyList(),
    val recentSearches: List<RecentItem> = emptyList(),
    val recentlyWatched: List<RecentItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val api: DuckFlixApi,
    private val recentSearchDao: RecentSearchDao,
    private val watchProgressDao: WatchProgressDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        loadRecentData()
    }

    private fun loadRecentData() {
        viewModelScope.launch {
            // Load recent searches
            launch {
                recentSearchDao.getRecentSearches().collect { searches ->
                    _uiState.value = _uiState.value.copy(
                        recentSearches = searches.map {
                            RecentItem(it.tmdbId, it.title, it.type, it.year, it.posterUrl, it.voteAverage)
                        }
                    )
                }
            }

            // Load recently watched
            launch {
                watchProgressDao.getRecentlyWatched().collect { watched ->
                    _uiState.value = _uiState.value.copy(
                        recentlyWatched = watched.map {
                            RecentItem(it.tmdbId, it.title, it.type, it.year, it.posterUrl)
                        }
                    )
                }
            }
        }
    }

    fun saveRecentSearch(tmdbId: Int, title: String, type: String, year: String?, posterUrl: String?, voteAverage: Double? = null) {
        viewModelScope.launch {
            recentSearchDao.saveSearch(
                RecentSearchEntity(
                    tmdbId = tmdbId,
                    title = title,
                    type = type,
                    year = year,
                    posterUrl = posterUrl,
                    searchedAt = System.currentTimeMillis(),
                    voteAverage = voteAverage
                )
            )
        }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun search() {
        val query = _uiState.value.query
        if (query.isNotBlank()) {
            performSearch(query)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun performSearch(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // Use multi-search to get both movies and TV in one call
                val multiResponse = api.searchTmdb(query = query, type = "multi")

                // Server already sorted by relevance and added relevanceScore
                val allResults = multiResponse.results

                // Separate into movies and TV (keeping relevance order)
                val moviesWithType = allResults.filter { it.type == "movie" }
                val tvWithType = allResults.filter { it.type == "tv" }

                // Top results - already sorted by relevance from server
                val topResults = allResults.take(10)

                _uiState.value = _uiState.value.copy(
                    topResults = topResults,
                    movieResults = moviesWithType,
                    tvResults = tvWithType,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Search failed: ${e.message}"
                )
            }
        }
    }
}
