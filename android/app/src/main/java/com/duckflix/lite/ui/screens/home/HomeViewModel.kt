package com.duckflix.lite.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.local.dao.WatchProgressDao
import com.duckflix.lite.data.local.dao.WatchlistDao
import com.duckflix.lite.data.local.entity.WatchProgressEntity
import com.duckflix.lite.data.local.entity.WatchlistEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val continueWatching: List<WatchProgressEntity> = emptyList(),
    val watchlist: List<WatchlistEntity> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val watchlistDao: WatchlistDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                watchProgressDao.getContinueWatching(),
                watchlistDao.getAll()
            ) { continueWatching, watchlist ->
                HomeUiState(
                    continueWatching = continueWatching,
                    watchlist = watchlist
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun removeFromWatchlist(tmdbId: Int) {
        viewModelScope.launch {
            watchlistDao.remove(tmdbId)
        }
    }
}
