package com.duckflix.lite.ui.screens.settings

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duckflix.lite.ui.components.FocusableCard
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.theme.Dimens
import com.duckflix.lite.ui.theme.UiScale
import com.duckflix.lite.ui.theme.UiScalePreferences
import com.duckflix.lite.ui.theme.scaled
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onLogoutSuccess: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp.scaled())
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.sectionSpacing)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayLarge
        )

        // User Info Section
        SettingsSection(title = "Account") {
            FocusableCard(
                onClick = { },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Username",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = uiState.username ?: "Not logged in",
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (uiState.rdExpiryDate != null) {
                FocusableCard(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Real-Debrid Expiry",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.rdExpiryDate ?: "Unknown",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            FocusableButton(
                onClick = {
                    viewModel.logout(onLogoutComplete = onLogoutSuccess)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoggingOut
            ) {
                Text(if (uiState.isLoggingOut) "Logging out..." else "Logout")
            }
        }

        // Display Settings
        SettingsSection(title = "Display") {
            UiScaleSelector()
        }

        // Server Settings
        SettingsSection(title = "Server") {
            FocusableCard(
                onClick = { },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Server URL",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = uiState.serverUrl,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // App Info
        SettingsSection(title = "About") {
            FocusableCard(
                onClick = { },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Version",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = uiState.appVersion,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    uiState.logoutMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            delay(100)
            viewModel.clearLogoutMessage()
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun UiScaleSelector() {
    val context = LocalContext.current
    var currentScale by remember { mutableStateOf(UiScalePreferences.getUiScale(context)) }
    var showRestartHint by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "UI Scale",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Adjust the size of text, buttons, and posters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UiScale.entries.forEach { scale ->
                UiScaleChip(
                    scale = scale,
                    isSelected = scale == currentScale,
                    onClick = {
                        currentScale = scale
                        UiScalePreferences.setUiScale(context, scale)
                        showRestartHint = true
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (showRestartHint) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Restart app to apply changes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )

                FocusableButton(
                    onClick = {
                        // Restart the activity
                        val activity = context as? Activity
                        activity?.let {
                            val intent = it.intent
                            it.finish()
                            it.startActivity(intent)
                        }
                    }
                ) {
                    Text("Restart Now")
                }
            }
        }
    }
}

@Composable
private fun UiScaleChip(
    scale: UiScale,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }

    val borderColor = when {
        isFocused -> MaterialTheme.colorScheme.primary
        isSelected -> Color.Transparent
        else -> Color.White.copy(alpha = 0.3f)
    }

    val borderWidth = if (isFocused) 3.dp else 1.dp

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .background(backgroundColor, shape)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = scale.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
                maxLines = 1
            )
            Text(
                text = "${(scale.factor * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.5f)
            )
        }
    }
}
