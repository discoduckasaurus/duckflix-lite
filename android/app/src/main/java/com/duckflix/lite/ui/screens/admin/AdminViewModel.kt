package com.duckflix.lite.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.squareup.moshi.JsonClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@JsonClass(generateAdapter = true)
data class AdminDashboard(
    val totalUsers: Int,
    val activeSessions: Int,
    val rdExpiringSoon: Int,
    val recentFailures: Int,
    val totalStorage: String?
)

@JsonClass(generateAdapter = true)
data class AdminUserInfo(
    val id: Int,
    val username: String,
    val isAdmin: Boolean,
    val lastLogin: String?,
    val rdExpiry: String?,
    val isRdExpiringSoon: Boolean
)

@JsonClass(generateAdapter = true)
data class AdminFailureInfo(
    val id: Int,
    val tmdbId: Int,
    val title: String,
    val username: String,
    val errorCode: String,
    val errorMessage: String,
    val timestamp: Long
)

data class AdminUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val dashboard: AdminDashboard? = null,
    val users: List<AdminUserInfo> = emptyList(),
    val failures: List<AdminFailureInfo> = emptyList()
)

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val api: DuckFlixApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = AdminUiState(isLoading = true)
            try {
                val dashboard = api.getAdminDashboard()
                val users = api.getAdminUsers()
                val failures = api.getAdminFailures(limit = 50)
                _uiState.value = AdminUiState(
                    isLoading = false,
                    dashboard = dashboard,
                    users = users,
                    failures = failures
                )
            } catch (e: Exception) {
                _uiState.value = AdminUiState(
                    isLoading = false,
                    error = "Failed: ${e.message}"
                )
            }
        }
    }

    fun resetUserPassword(userId: Int) {
        viewModelScope.launch {
            try {
                api.resetUserPassword(userId)
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Reset failed: ${e.message}")
            }
        }
    }

    fun disableUser(userId: Int) {
        viewModelScope.launch {
            try {
                api.disableUser(userId)
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Disable failed: ${e.message}")
            }
        }
    }
}
