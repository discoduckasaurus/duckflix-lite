package com.duckflix.lite.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.local.dao.WatchProgressDao
import com.duckflix.lite.data.local.dao.WatchlistDao
import com.duckflix.lite.data.local.entity.WatchProgressEntity
import com.duckflix.lite.data.local.entity.WatchlistEntity
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.TmdbDetailResponse
import com.duckflix.lite.data.remote.dto.TmdbSeasonResponse
import com.duckflix.lite.data.remote.dto.ZurgMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val content: TmdbDetailResponse? = null,
    val contentType: String = "movie",
    val zurgMatch: ZurgMatch? = null,
    val isCheckingZurg: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val seasons: Map<Int, TmdbSeasonResponse> = emptyMap(), // seasonNumber -> season data
    val selectedSeason: Int? = null, // currently selected season number
    val isLoadingSeasons: Boolean = false,
    val watchProgress: WatchProgressEntity? = null,
    val isInWatchlist: Boolean = false
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val api: DuckFlixApi,
    private val watchProgressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val tmdbId: Int = checkNotNull(savedStateHandle["tmdbId"])
    private val contentType: String = savedStateHandle["type"] ?: "movie"

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        loadDetails()
        loadWatchProgress()
        loadWatchlistStatus()
    }

    fun loadDetails() {
        viewModelScope.launch {
            _uiState.value = DetailUiState(isLoading = true, contentType = contentType)

            try {
                val details = api.getTmdbDetail(tmdbId, contentType)
                _uiState.value = DetailUiState(content = details, contentType = contentType, isLoading = false)

                // Check Zurg availability
                checkZurgAvailability(details)

                // For TV shows, select and load first season automatically
                if (contentType == "tv" && !details.seasons.isNullOrEmpty()) {
                    // Select first non-special season
                    val firstSeason = details.seasons.firstOrNull { it.seasonNumber > 0 }
                    if (firstSeason != null) {
                        _uiState.value = _uiState.value.copy(selectedSeason = firstSeason.seasonNumber)
                        loadSeason(firstSeason.seasonNumber)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = DetailUiState(
                    isLoading = false,
                    contentType = contentType,
                    error = "Failed to load details: ${e.message}"
                )
            }
        }
    }

    private suspend fun checkZurgAvailability(content: TmdbDetailResponse) {
        try {
            _uiState.value = _uiState.value.copy(isCheckingZurg = true)

            val zurgResult = api.searchZurg(
                title = content.title,
                year = content.year,
                type = if (contentType == "tv") "episode" else "movie",
                duration = content.runtime
            )

            _uiState.value = _uiState.value.copy(
                zurgMatch = zurgResult.match ?: zurgResult.fallback,
                isCheckingZurg = false
            )
        } catch (e: Exception) {
            // Zurg check failure is not critical, just log it
            _uiState.value = _uiState.value.copy(isCheckingZurg = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun selectSeason(seasonNumber: Int) {
        _uiState.value = _uiState.value.copy(selectedSeason = seasonNumber)

        // Load season data if not already loaded
        if (!_uiState.value.seasons.containsKey(seasonNumber)) {
            loadSeason(seasonNumber)
        }
    }

    private fun loadSeason(seasonNumber: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingSeasons = true)
                val seasonData = api.getTmdbSeason(tmdbId, seasonNumber)
                val updatedSeasons = _uiState.value.seasons + (seasonNumber to seasonData)
                _uiState.value = _uiState.value.copy(
                    seasons = updatedSeasons,
                    isLoadingSeasons = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingSeasons = false)
            }
        }
    }

    private fun loadWatchProgress() {
        viewModelScope.launch {
            try {
                val progress = watchProgressDao.getProgress(tmdbId)
                _uiState.value = _uiState.value.copy(watchProgress = progress)
            } catch (e: Exception) {
                // Progress loading failure is not critical
            }
        }
    }

    private fun loadWatchlistStatus() {
        viewModelScope.launch {
            try {
                val isInWatchlist = watchlistDao.isInWatchlist(tmdbId)
                _uiState.value = _uiState.value.copy(isInWatchlist = isInWatchlist)
            } catch (e: Exception) {
                // Watchlist status loading failure is not critical
            }
        }
    }

    fun toggleWatchlist() {
        viewModelScope.launch {
            try {
                val content = _uiState.value.content ?: return@launch

                if (_uiState.value.isInWatchlist) {
                    // Remove from watchlist
                    watchlistDao.remove(tmdbId)
                    _uiState.value = _uiState.value.copy(isInWatchlist = false)
                } else {
                    // Add to watchlist
                    val watchlistItem = WatchlistEntity(
                        tmdbId = tmdbId,
                        title = content.title,
                        type = contentType,
                        year = content.year,
                        posterUrl = content.posterUrl,
                        addedAt = System.currentTimeMillis()
                    )
                    watchlistDao.add(watchlistItem)
                    _uiState.value = _uiState.value.copy(isInWatchlist = true)
                }
            } catch (e: Exception) {
                // Watchlist toggle failure - could show error to user
            }
        }
    }
}
