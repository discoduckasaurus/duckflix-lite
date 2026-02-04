package com.duckflix.lite.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.BuildConfig
import com.duckflix.lite.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val username: String? = null,
    val rdExpiryDate: String? = null,
    val serverUrl: String = "https://lite.duckflix.tv",
    val appVersion: String = "1.0.0",
    val isLoggingOut: Boolean = false,
    val logoutMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadUserInfo()
    }

    private fun loadUserInfo() {
        viewModelScope.launch {
            // TODO: Load user info from repository
            _uiState.value = SettingsUiState(
                username = "admin", // TODO: Get from user session
                rdExpiryDate = null,
                serverUrl = "https://lite.duckflix.tv",
                appVersion = "1.0.0"
            )
        }
    }

    fun logout(onLogoutComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingOut = true)
            val result = authRepository.logout()

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoggingOut = false,
                    logoutMessage = "Logged out successfully"
                )
                onLogoutComplete()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoggingOut = false,
                    logoutMessage = "Logout failed: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun clearLogoutMessage() {
        _uiState.value = _uiState.value.copy(logoutMessage = null)
    }
}
