package com.duckflix.lite.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.bandwidth.BandwidthTester
import com.duckflix.lite.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isMeasuringBandwidth: Boolean = false,
    val bandwidthMbps: Double? = null,
    val error: String? = null,
    val isCheckingAuth: Boolean = true  // Start with checking auth status
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val bandwidthTester: BandwidthTester
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Check if already logged in
        viewModelScope.launch {
            authRepository.isLoggedIn().collect { isLoggedIn ->
                if (isLoggedIn) {
                    _uiState.update { it.copy(isLoggedIn = true, isCheckingAuth = false) }
                } else {
                    _uiState.update { it.copy(isCheckingAuth = false) }
                }
            }
        }
    }

    fun onUsernameChange(username: String) {
        _uiState.update { it.copy(username = username, error = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun login() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = authRepository.login(
                username = _uiState.value.username,
                password = _uiState.value.password
            )

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    // Perform bandwidth test after successful login
                    performBandwidthTest()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Login failed"
                        )
                    }
                }
            )
        }
    }

    fun loginWithCredentials(username: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, username = username, password = password) }

            val result = authRepository.login(
                username = username,
                password = password
            )

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    // Perform bandwidth test after successful login
                    performBandwidthTest()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Login failed"
                        )
                    }
                }
            )
        }
    }

    private fun performBandwidthTest() {
        viewModelScope.launch {
            // Check if bandwidth test is needed (no measurement or stale)
            val needsTest = bandwidthTester.needsTest()

            if (needsTest) {
                _uiState.update { it.copy(isMeasuringBandwidth = true) }

                // Perform streaming bandwidth test and report to server
                val result = bandwidthTester.performTestAndReport(trigger = "startup")

                _uiState.update {
                    it.copy(
                        isMeasuringBandwidth = false,
                        isLoggedIn = true,
                        bandwidthMbps = result?.recorded
                    )
                }
            } else {
                // Bandwidth measurement exists and is fresh, skip test
                println("[Login] Bandwidth measurement is fresh, skipping test")
                _uiState.update { it.copy(isLoggedIn = true) }
            }
        }
    }
}
