package com.duckflix.lite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val isLoggedIn = authRepository.isLoggedIn()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    init {
        // Auto-login for testing (debug builds only)
        if (BuildConfig.DEBUG) {
            viewModelScope.launch {
                val token = authRepository.getAuthToken()
                if (token.isNullOrEmpty()) {
                    println("[DEBUG] Auto-login: No token found, attempting login with test credentials")
                    val result = authRepository.login("admin", "q")
                    if (result.isSuccess) {
                        println("[INFO] Auto-login successful")
                    } else {
                        println("[ERROR] Auto-login failed: ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    println("[DEBUG] Auto-login: Token already exists")
                }
            }
        }
    }
}
