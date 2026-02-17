package com.duckflix.lite.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.BuildConfig
import com.duckflix.lite.data.local.dao.AutoPlaySettingsDao
import com.duckflix.lite.data.local.dao.SubtitlePreferencesDao
import com.duckflix.lite.data.local.entity.AutoPlaySettingsEntity
import com.duckflix.lite.data.local.entity.SubtitlePreferencesEntity
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
    val autoplaySeriesDefault: Boolean = true,
    // Subtitle style preferences
    val subtitleSize: Int = 1,          // 0=Small, 1=Medium, 2=Large
    val subtitleColor: Int = 0,         // 0=White, 1=Yellow, 2=Green, 3=Cyan
    val subtitleBackground: Int = 0,    // 0=None, 1=Black, 2=Semi-transparent
    val subtitleEdge: Int = 1           // 0=None, 1=Drop shadow, 2=Outline
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val autoPlaySettingsDao: AutoPlaySettingsDao,
    private val subtitlePreferencesDao: SubtitlePreferencesDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadUserInfo()
        loadAutoPlaySettings()
        loadSubtitlePreferences()
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

    private fun loadSubtitlePreferences() {
        viewModelScope.launch {
            val prefs = subtitlePreferencesDao.getSettings() ?: return@launch
            _uiState.value = _uiState.value.copy(
                subtitleSize = prefs.subtitleSize,
                subtitleColor = prefs.subtitleColor,
                subtitleBackground = prefs.subtitleBackground,
                subtitleEdge = prefs.subtitleEdge
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

    fun setSubtitleSize(size: Int) {
        _uiState.value = _uiState.value.copy(subtitleSize = size)
        saveSubtitlePreferences()
    }

    fun setSubtitleColor(color: Int) {
        _uiState.value = _uiState.value.copy(subtitleColor = color)
        saveSubtitlePreferences()
    }

    fun setSubtitleBackground(background: Int) {
        _uiState.value = _uiState.value.copy(subtitleBackground = background)
        saveSubtitlePreferences()
    }

    fun setSubtitleEdge(edge: Int) {
        _uiState.value = _uiState.value.copy(subtitleEdge = edge)
        saveSubtitlePreferences()
    }

    private fun saveSubtitlePreferences() {
        viewModelScope.launch {
            val current = subtitlePreferencesDao.getSettings() ?: SubtitlePreferencesEntity()
            subtitlePreferencesDao.saveSettings(
                current.copy(
                    subtitleSize = _uiState.value.subtitleSize,
                    subtitleColor = _uiState.value.subtitleColor,
                    subtitleBackground = _uiState.value.subtitleBackground,
                    subtitleEdge = _uiState.value.subtitleEdge
                )
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
