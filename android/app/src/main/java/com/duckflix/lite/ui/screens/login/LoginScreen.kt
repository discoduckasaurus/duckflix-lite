package com.duckflix.lite.ui.screens.login

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Dismiss keyboard whenever we're in a loading/logged-in state
    LaunchedEffect(uiState.isCheckingAuth, uiState.isLoggedIn, uiState.isMeasuringBandwidth) {
        if (uiState.isCheckingAuth || uiState.isLoggedIn || uiState.isMeasuringBandwidth) {
            keyboardController?.hide()
        }
    }

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            onLoginSuccess()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Show loading screen if checking auth, already logged in, or measuring bandwidth
        if (uiState.isCheckingAuth || uiState.isLoggedIn || uiState.isMeasuringBandwidth) {
            Card(
                modifier = Modifier
                    .width(400.dp)
                    .padding(32.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "DuckFlix Lite",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = when {
                            uiState.isMeasuringBandwidth -> "Testing connection speed..."
                            uiState.isCheckingAuth -> "Loading..."
                            else -> "Logging in..."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            // Show login form only if not logged in
            Card(
                modifier = Modifier
                    .width(400.dp)
                    .padding(32.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "DuckFlix Lite",
                        style = MaterialTheme.typography.headlineLarge
                    )

                    OutlinedTextField(
                        value = uiState.username,
                        onValueChange = viewModel::onUsernameChange,
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.error != null) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = viewModel::login,
                        enabled = !uiState.isLoading && uiState.username.isNotBlank() && uiState.password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Login")
                        }
                    }
                }
            }
        }
    }
}
