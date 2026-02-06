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
    val trendingMovies: List<TrendingResult> = emptyList(),
    val trendingTV: List<TrendingResult> = emptyList(),
    val isLoadingRecommendations: Boolean = false,
    val isLoadingTrendingMovies: Boolean = false,
    val isLoadingTrendingTV: Boolean = false,
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

    private fun loadRecommendations() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingRecommendations = true,
                recommendationsError = null
            )
            try {
                println("[HomeViewModel] Loading recommendations...")
                val response = api.getRecommendations(limit = 20)
                println("[HomeViewModel] Recommendations loaded: ${response.recommendations.size} items")
                _uiState.value = _uiState.value.copy(
                    recommendations = response.recommendations,
                    isLoadingRecommendations = false
                )
            } catch (e: Exception) {
                println("[HomeViewModel] Recommendations error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingRecommendations = false,
                    recommendationsError = e.message
                )
            }
        }
    }

    private fun loadTrendingMovies() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingTrendingMovies = true,
                trendingMoviesError = null
            )
            try {
                println("[HomeViewModel] Loading trending movies...")
                val response = api.getTrending(mediaType = "movie", timeWindow = "week")
                println("[HomeViewModel] Trending movies loaded: ${response.results.size} items")
                _uiState.value = _uiState.value.copy(
                    trendingMovies = response.results,
                    isLoadingTrendingMovies = false
                )
            } catch (e: Exception) {
                println("[HomeViewModel] Trending movies error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingTrendingMovies = false,
                    trendingMoviesError = e.message
                )
            }
        }
    }

    private fun loadTrendingTV() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingTrendingTV = true,
                trendingTVError = null
            )
            try {
                println("[HomeViewModel] Loading trending TV...")
                val response = api.getTrending(mediaType = "tv", timeWindow = "week")
                println("[HomeViewModel] Trending TV loaded: ${response.results.size} items")
                _uiState.value = _uiState.value.copy(
                    trendingTV = response.results,
                    isLoadingTrendingTV = false
                )
            } catch (e: Exception) {
                println("[HomeViewModel] Trending TV error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoadingTrendingTV = false,
                    trendingTVError = e.message
                )
            }
        }
    }

    fun removeFromWatchlist(tmdbId: Int) {
        viewModelScope.launch {
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
