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
    val posterUrl: String?
)

data class SearchUiState(
    val query: String = "",
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
        seedTestData()
        loadRecentData()
    }

    private fun seedTestData() {
        viewModelScope.launch {
            // Check if we already have data
            val existingWatched = watchProgressDao.getProgress(1855) // BSG TMDB ID
            if (existingWatched == null) {
                // Insert test titles
                val now = System.currentTimeMillis()

                // Battlestar Galactica (2004)
                watchProgressDao.saveProgress(
                    com.duckflix.lite.data.local.entity.WatchProgressEntity(
                        tmdbId = 1855,
                        title = "Battlestar Galactica",
                        type = "tv",
                        year = "2004",
                        posterUrl = "https://image.tmdb.org/t/p/w500/99PJSbcO2LeM10uOGWeFihNu7f5.jpg",
                        position = 300000, // 5 minutes in
                        duration = 2700000, // 45 minutes
                        lastWatchedAt = now - 86400000, // 1 day ago
                        isCompleted = false
                    )
                )

                // Mean Girls (2004)
                watchProgressDao.saveProgress(
                    com.duckflix.lite.data.local.entity.WatchProgressEntity(
                        tmdbId = 10625,
                        title = "Mean Girls",
                        type = "movie",
                        year = "2004",
                        posterUrl = "https://image.tmdb.org/t/p/w500/fXm3YKXAEjx7d2tIWDg9TfRZtsU.jpg",
                        position = 1200000, // 20 minutes in
                        duration = 5820000, // 97 minutes
                        lastWatchedAt = now - 172800000, // 2 days ago
                        isCompleted = false
                    )
                )

                // American Dad
                watchProgressDao.saveProgress(
                    com.duckflix.lite.data.local.entity.WatchProgressEntity(
                        tmdbId = 1433,
                        title = "American Dad!",
                        type = "tv",
                        year = "2005",
                        posterUrl = "https://image.tmdb.org/t/p/w500/xnFFz3etm1vftF0ns8RMHA1XdqU.jpg",
                        position = 600000, // 10 minutes in
                        duration = 1260000, // 21 minutes
                        lastWatchedAt = now, // Just now
                        isCompleted = false
                    )
                )
            }
        }
    }

    private fun loadRecentData() {
        viewModelScope.launch {
            // Load recent searches
            launch {
                recentSearchDao.getRecentSearches().collect { searches ->
                    _uiState.value = _uiState.value.copy(
                        recentSearches = searches.map {
                            RecentItem(it.tmdbId, it.title, it.type, it.year, it.posterUrl)
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

    fun saveRecentSearch(tmdbId: Int, title: String, type: String, year: String?, posterUrl: String?) {
        viewModelScope.launch {
            recentSearchDao.saveSearch(
                RecentSearchEntity(
                    tmdbId = tmdbId,
                    title = title,
                    type = type,
                    year = year,
                    posterUrl = posterUrl,
                    searchedAt = System.currentTimeMillis()
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
                // Search both movies and TV shows in parallel
                val movieDeferred = async {
                    api.searchTmdb(query = query, type = "movie")
                }
                val tvDeferred = async {
                    api.searchTmdb(query = query, type = "tv")
                }

                val movieResponse = movieDeferred.await()
                val tvResponse = tvDeferred.await()

                // Set the type on each result
                val moviesWithType = movieResponse.results.map { it.copy(type = "movie") }
                val tvWithType = tvResponse.results.map { it.copy(type = "tv") }

                _uiState.value = _uiState.value.copy(
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
