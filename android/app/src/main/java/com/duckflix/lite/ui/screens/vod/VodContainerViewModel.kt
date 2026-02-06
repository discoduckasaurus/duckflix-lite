package com.duckflix.lite.ui.screens.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.local.dao.UserDao
import com.duckflix.lite.data.repository.AuthRepository
import com.duckflix.lite.ui.components.VodTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VodContainerUiState(
    val selectedTab: VodTab = VodTab.MY_STUFF,
    val isAdmin: Boolean = false,
    val isLoggingOut: Boolean = false
)

@HiltViewModel
class VodContainerViewModel @Inject constructor(
    private val userDao: UserDao,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VodContainerUiState())
    val uiState: StateFlow<VodContainerUiState> = _uiState.asStateFlow()

    init {
        loadUserAdminStatus()
    }

    fun selectTab(tab: VodTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    private fun loadUserAdminStatus() {
        viewModelScope.launch {
            try {
                val user = userDao.getUserById(1)
                _uiState.value = _uiState.value.copy(isAdmin = user?.isAdmin == true)
            } catch (e: Exception) {
                // Non-critical, fail silently
            }
        }
    }

    fun logout(onLogoutComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingOut = true)
            val result = authRepository.logout()

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isLoggingOut = false)
                onLogoutComplete()
            } else {
                _uiState.value = _uiState.value.copy(isLoggingOut = false)
            }
        }
    }
}
