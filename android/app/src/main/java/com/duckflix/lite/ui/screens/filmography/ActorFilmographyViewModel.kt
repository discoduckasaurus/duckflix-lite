package com.duckflix.lite.ui.screens.filmography

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.PersonCreditsResponse
import com.duckflix.lite.data.remote.dto.PersonDetailsResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActorFilmographyUiState(
    val personDetails: PersonDetailsResponse? = null,
    val filmography: PersonCreditsResponse? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ActorFilmographyViewModel @Inject constructor(
    private val api: DuckFlixApi,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val personId: Int = checkNotNull(savedStateHandle["personId"])

    private val _uiState = MutableStateFlow(ActorFilmographyUiState())
    val uiState: StateFlow<ActorFilmographyUiState> = _uiState.asStateFlow()

    init {
        loadActorData()
    }

    fun loadActorData() {
        viewModelScope.launch {
            _uiState.value = ActorFilmographyUiState(isLoading = true)

            try {
                // Load person details and filmography in parallel
                val personDetails = api.getPersonDetails(personId)
                val filmography = api.getPersonCredits(personId)

                _uiState.value = ActorFilmographyUiState(
                    personDetails = personDetails,
                    filmography = filmography,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = ActorFilmographyUiState(
                    isLoading = false,
                    error = "Failed to load actor data: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
