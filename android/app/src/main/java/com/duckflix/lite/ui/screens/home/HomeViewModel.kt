package com.duckflix.lite.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.local.dao.WatchProgressDao
import com.duckflix.lite.data.local.dao.WatchlistDao
import com.duckflix.lite.data.local.entity.WatchlistEntity
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.ContinueWatchingItem
import com.duckflix.lite.data.remote.dto.RecommendationItem
import com.duckflix.lite.data.remote.dto.TrendingResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val watchlist: List<WatchlistEntity> = emptyList(),
    val recommendations: List<RecommendationItem> = emptyList(),
    val recommendationsPage: Int = 1,
    val recommendationsHasMore: Boolean = false,
    val trendingMovies: List<TrendingResult> = emptyList(),
    val trendingMoviesPage: Int = 1,
    val trendingMoviesHasMore: Boolean = false,
    val trendingTV: List<TrendingResult> = emptyList(),
    val trendingTVPage: Int = 1,
    val trendingTVHasMore: Boolean = false,
    val isLoadingRecommendations: Boolean = false,
    val isLoadingTrendingMovies: Boolean = false,
    val isLoadingTrendingTV: Boolean = false,
    val isLoadingMoreRecommendations: Boolean = false,
    val isLoadingMoreTrendingMovies: Boolean = false,
    val isLoadingMoreTrendingTV: Boolean = false,
    val recommendationsError: String? = null,
    val trendingMoviesError: String? = null,
    val trendingTVError: String? = null,
    val isAdmin: Boolean = false,
    val activeDownloads: Int = 0,
    val failedDownloads: Int = 0
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao,
    private val userDao: com.duckflix.lite.data.local.dao.UserDao,
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        loadLocalData()
        loadUserAdminStatus()
        fetchWatchlistFromServer() // Fetch from server first
        loadContinueWatching() // Load from server
        loadRecommendations()
        loadTrendingMovies()
        loadTrendingTV()
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    private fun loadLocalData() {
        viewModelScope.launch {
            // Load watchlist from local DB
            watchlistDao.getAll().collect { watchlist ->
                _uiState.value = _uiState.value.copy(watchlist = watchlist)
            }
        }
    }

    fun loadContinueWatching() {
        viewModelScope.launch {
            try {
                val response = api.getContinueWatching()
                _uiState.value = _uiState.value.copy(
                    continueWatching = response.continueWatching,
                    activeDownloads = response.activeDownloads,
                    failedDownloads = response.failedDownloads
                )

                // Start polling if there are active or failed downloads
                startPollingIfNeeded()
            } catch (e: Exception) {
                println("[HomeViewModel] Failed to load Continue Watching: ${e.message}")
                // Fall back to empty list on error
                _uiState.value = _uiState.value.copy(continueWatching = emptyList())
            }
        }
    }

    private fun startPollingIfNeeded() {
        val hasActiveDownloads = _uiState.value.activeDownloads > 0
        val hasFailedDownloads = _uiState.value.failedDownloads > 0

        // Start polling if there are active or failed downloads and not already polling
        if ((hasActiveDownloads || hasFailedDownloads) && pollingJob == null) {
            pollingJob = viewModelScope.launch {
                while (isActive) {
                    delay(5000) // Poll every 5 seconds

                    try {
                        val response = api.getContinueWatching()
                        _uiState.value = _uiState.value.copy(
                            continueWatching = response.continueWatching,
                            activeDownloads = response.activeDownloads,
                            failedDownloads = response.failedDownloads
                        )

                        // Stop polling if no more active or failed downloads
                        if (response.activeDownloads == 0 && response.failedDownloads == 0) {
                            pollingJob?.cancel()
                            pollingJob = null
                            break
                        }
                    } catch (e: Exception) {
                        println("[HomeViewModel] Polling error: ${e.message}")
                        // Continue polling even on error
                    }
                }
            }
        }
    }

    fun dismissFailedDownload(jobId: String) {
        viewModelScope.launch {
            try {
                api.dismissFailedDownload(jobId)
                // Refresh Continue Watching
                loadContinueWatching()
            } catch (e: Exception) {
                println("[HomeViewModel] Failed to dismiss: ${e.message}")
            }
        }
    }

    fun retryFailedDownload(item: ContinueWatchingItem, onNavigateToPlayer: () -> Unit) {
        viewModelScope.launch {
            try {
                // Dismiss the failed download first
                if (item.jobId != null) {
                    api.dismissFailedDownload(item.jobId)
                }
                // Navigate to player to trigger new download
                onNavigateToPlayer()
            } catch (e: Exception) {
                println("[HomeViewModel] Failed to retry: ${e.message}")
            }
        }
    }

    fun removeFromContinueWatching(item: ContinueWatchingItem) {
        viewModelScope.launch {
            try {
                api.deleteWatchProgress(item.tmdbId, item.type)
                // Refresh Continue Watching
                loadContinueWatching()
            } catch (e: Exception) {
                println("[HomeViewModel] Failed to remove from continue watching: ${e.message}")
            }
        }
    }

    private fun loadUserAdminStatus() {
        viewModelScope.launch {
            try {
                // Assuming user ID 1 or retrieve from auth
                val user = userDao.getUserById(1)
                _uiState.value = _uiState.value.copy(isAdmin = user?.isAdmin == true)
            } catch (e: Exception) {
                // Non-critical, fail silently
            }
        }
    }

    fun loadMoreRecommendations() {
        val state = _uiState.value
        if (state.isLoadingMoreRecommendations || state.isLoadingRecommendations) return
        if (!state.recommendationsHasMore && state.recommendations.size < state.recommendationsPage * 20) return
        loadRecommendations(resetPage = false)
    }

    fun loadMoreTrendingMovies() {
        val state = _uiState.value
        if (state.isLoadingMoreTrendingMovies || state.isLoadingTrendingMovies) return
        if (!state.trendingMoviesHasMore && state.trendingMovies.size < state.trendingMoviesPage * 20) return
        loadTrendingMovies(resetPage = false)
    }

    fun loadMoreTrendingTV() {
        val state = _uiState.value
        if (state.isLoadingMoreTrendingTV || state.isLoadingTrendingTV) return
        if (!state.trendingTVHasMore && state.trendingTV.size < state.trendingTVPage * 20) return
        loadTrendingTV(resetPage = false)
    }

    private fun loadRecommendations(resetPage: Boolean = true) {
        viewModelScope.launch {
            val state = _uiState.value
            val page = if (resetPage) 1 else state.recommendationsPage + 1

            _uiState.value = state.copy(
                isLoadingRecommendations = resetPage,
                isLoadingMoreRecommendations = !resetPage,
                recommendationsError = null
            )
            try {
                val response = api.getRecommendations(limit = 20, page = page)
                val newItems = if (resetPage) response.recommendations else state.recommendations + response.recommendations
                _uiState.value = _uiState.value.copy(
                    recommendations = newItems,
                    recommendationsPage = page,
                    recommendationsHasMore = response.hasMore,
                    isLoadingRecommendations = false,
                    isLoadingMoreRecommendations = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingRecommendations = false,
                    isLoadingMoreRecommendations = false,
                    recommendationsError = e.message
                )
            }
        }
    }

    private fun loadTrendingMovies(resetPage: Boolean = true) {
        viewModelScope.launch {
            val state = _uiState.value
            val page = if (resetPage) 1 else state.trendingMoviesPage + 1

            _uiState.value = state.copy(
                isLoadingTrendingMovies = resetPage,
                isLoadingMoreTrendingMovies = !resetPage,
                trendingMoviesError = null
            )
            try {
                val response = api.getTrending(mediaType = "movie", timeWindow = "week", page = page)
                val newItems = if (resetPage) response.results else state.trendingMovies + response.results
                _uiState.value = _uiState.value.copy(
                    trendingMovies = newItems,
                    trendingMoviesPage = page,
                    trendingMoviesHasMore = response.hasMore,
                    isLoadingTrendingMovies = false,
                    isLoadingMoreTrendingMovies = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingTrendingMovies = false,
                    isLoadingMoreTrendingMovies = false,
                    trendingMoviesError = e.message
                )
            }
        }
    }

    private fun loadTrendingTV(resetPage: Boolean = true) {
        viewModelScope.launch {
            val state = _uiState.value
            val page = if (resetPage) 1 else state.trendingTVPage + 1

            _uiState.value = state.copy(
                isLoadingTrendingTV = resetPage,
                isLoadingMoreTrendingTV = !resetPage,
                trendingTVError = null
            )
            try {
                val response = api.getTrending(mediaType = "tv", timeWindow = "week", page = page)
                val newItems = if (resetPage) response.results else state.trendingTV + response.results
                _uiState.value = _uiState.value.copy(
                    trendingTV = newItems,
                    trendingTVPage = page,
                    trendingTVHasMore = response.hasMore,
                    isLoadingTrendingTV = false,
                    isLoadingMoreTrendingTV = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingTrendingTV = false,
                    isLoadingMoreTrendingTV = false,
                    trendingTVError = e.message
                )
            }
        }
    }

    fun removeFromWatchlist(tmdbId: Int, type: String) {
        viewModelScope.launch {
            try {
                // Remove from server first
                api.removeFromWatchlist(tmdbId, type)
                println("[HomeViewModel] Removed from server watchlist: $tmdbId ($type)")
            } catch (e: Exception) {
                println("[HomeViewModel] Failed to remove from server watchlist: ${e.message}")
                // Continue to remove locally even if server fails
            }
            // Remove from local database
            watchlistDao.remove(tmdbId)
        }
    }

    private fun fetchWatchlistFromServer() {
        viewModelScope.launch {
            try {
                println("[HomeViewModel] Fetching watchlist from server...")
                val response = api.getWatchlist()
                println("[HomeViewModel] Server watchlist: ${response.watchlist.size} items")

                // Convert server items to local entities and save
                response.watchlist.forEach { item ->
                    // Parse ISO date string to timestamp
                    val addedAtTimestamp = try {
                        item.addedAt?.let {
                            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                .parse(it)?.time
                        } ?: System.currentTimeMillis()
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    }

                    val entity = WatchlistEntity(
                        tmdbId = item.tmdbId,
                        title = item.title,
                        type = item.type,
                        year = item.releaseDate,
                        posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                        addedAt = addedAtTimestamp,
                        voteAverage = item.voteAverage
                    )
                    watchlistDao.add(entity)
                }
                println("[HomeViewModel] Watchlist synced to local DB")
            } catch (e: Exception) {
                println("[HomeViewModel] Failed to fetch watchlist from server: ${e.message}")
                // Fall back to local data which is already being loaded
            }
        }
    }

    fun refresh() {
        loadContinueWatching()
        loadRecommendations()
        loadTrendingMovies()
        loadTrendingTV()
    }

    private fun syncExistingDataToServer() {
        viewModelScope.launch {
            try {
                // Sync all watchlist items
                watchlistDao.getAll().collect { watchlist ->
                    watchlist.forEach { item ->
                        try {
                            val syncRequest = com.duckflix.lite.data.remote.dto.WatchlistSyncRequest(
                                tmdbId = item.tmdbId,
                                type = item.type,
                                title = item.title,
                                posterPath = item.posterUrl?.substringAfter("w500"),
                                releaseDate = item.year,
                                voteAverage = null
                            )
                            api.addToWatchlist(syncRequest)
                        } catch (e: Exception) {
                            println("[WARN] Failed to sync watchlist item ${item.title}: ${e.message}")
                        }
                    }
                    return@collect // Only sync once, don't keep collecting
                }

                // Sync all continue watching items
                watchProgressDao.getContinueWatching().collect { continueWatching ->
                    continueWatching.forEach { item ->
                        try {
                            val syncRequest = com.duckflix.lite.data.remote.dto.WatchProgressSyncRequest(
                                tmdbId = item.tmdbId,
                                type = item.type,
                                title = item.title,
                                posterPath = item.posterUrl?.substringAfter("w500"),
                                releaseDate = item.year,
                                position = item.position,
                                duration = item.duration,
                                season = item.season,
                                episode = item.episode
                            )
                            api.syncWatchProgress(syncRequest)
                        } catch (e: Exception) {
                            println("[WARN] Failed to sync watch progress for ${item.title}: ${e.message}")
                        }
                    }
                    return@collect // Only sync once
                }
                println("[HomeViewModel] Initial sync complete")
            } catch (e: Exception) {
                println("[ERROR] Failed to sync existing data: ${e.message}")
            }
        }
    }
}
