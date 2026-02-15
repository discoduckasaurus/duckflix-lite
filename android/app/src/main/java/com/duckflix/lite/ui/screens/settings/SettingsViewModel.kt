package com.duckflix.lite.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.BuildConfig
import com.duckflix.lite.data.local.dao.AutoPlaySettingsDao
import com.duckflix.lite.data.local.entity.AutoPlaySettingsEntity
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
    val logoutMessage: String? = null,
    val autoplaySeriesDefault: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val autoPlaySettingsDao: AutoPlaySettingsDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadUserInfo()
        loadAutoPlaySettings()
    }

    private fun loadUserInfo() {
        viewModelScope.launch {
            val user = authRepository.getCurrentUser()
            _uiState.value = _uiState.value.copy(
                username = user?.username,
                rdExpiryDate = user?.rdExpiryDate,
                serverUrl = "https://lite.duckflix.tv",
                appVersion = BuildConfig.VERSION_NAME
            )
        }
    }

    private fun loadAutoPlaySettings() {
        viewModelScope.launch {
            val settings = autoPlaySettingsDao.getSettingsOnce()
            _uiState.value = _uiState.value.copy(
                autoplaySeriesDefault = settings?.autoplaySeriesDefault ?: true
            )
        }
    }

    fun toggleAutoplaySeriesDefault() {
        viewModelScope.launch {
            val newValue = !_uiState.value.autoplaySeriesDefault
            _uiState.value = _uiState.value.copy(autoplaySeriesDefault = newValue)

            // Save to database
            val currentSettings = autoPlaySettingsDao.getSettingsOnce() ?: AutoPlaySettingsEntity()
            autoPlaySettingsDao.saveSettings(
                currentSettings.copy(autoplaySeriesDefault = newValue)
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
