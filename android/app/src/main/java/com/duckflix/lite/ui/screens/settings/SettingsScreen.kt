package com.duckflix.lite.ui.screens.settings

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.duckflix.lite.ui.components.FocusableCard
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.LogoButton
import com.duckflix.lite.ui.theme.Dimens
import com.duckflix.lite.ui.theme.UiScale
import com.duckflix.lite.ui.theme.UiScalePreferences
import com.duckflix.lite.ui.theme.getGlowColorForPosition
import com.duckflix.lite.ui.theme.scaled
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onLogoutSuccess: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val headerBringIntoView = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 0.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.sectionSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo at top center
        LogoButton(
            onClick = onNavigateBack,
            size = 80.dp
        )

        // Header section - brought into view when first focusable element is focused
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(headerBringIntoView)
                .onFocusChanged { focusState ->
                    if (focusState.hasFocus) {
                        coroutineScope.launch {
                            headerBringIntoView.bringIntoView()
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(Dimens.sectionSpacing)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displayLarge
            )

            // User Info Section
            SettingsSection(title = "Account") {
                SettingsInfoCard {
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
                    SettingsInfoCard {
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
        }

        // Playback Settings
        SettingsSection(title = "Playback") {
            SettingsToggleCard(
                title = "Autoplay for Series",
                description = "Automatically play next episode when one ends",
                isChecked = uiState.autoplaySeriesDefault,
                onToggle = { viewModel.toggleAutoplaySeriesDefault() }
            )
        }

        // Subtitle Settings
        SettingsSection(title = "Subtitles") {
            SettingsChipRow(
                title = "Size",
                options = listOf("Small", "Medium", "Large"),
                selectedIndex = uiState.subtitleSize,
                onSelect = { viewModel.setSubtitleSize(it) }
            )
            SettingsChipRow(
                title = "Color",
                options = listOf("White", "Yellow", "Green", "Cyan"),
                selectedIndex = uiState.subtitleColor,
                onSelect = { viewModel.setSubtitleColor(it) }
            )
            SettingsChipRow(
                title = "Background",
                options = listOf("None", "Black", "Semi-transparent"),
                selectedIndex = uiState.subtitleBackground,
                onSelect = { viewModel.setSubtitleBackground(it) }
            )
            SettingsChipRow(
                title = "Edge Style",
                options = listOf("None", "Drop Shadow", "Outline"),
                selectedIndex = uiState.subtitleEdge,
                onSelect = { viewModel.setSubtitleEdge(it) }
            )
        }

        // Display Settings
        SettingsSection(title = "Display") {
            UiScaleSelector()
        }

        // Server Settings
        SettingsSection(title = "Server") {
            SettingsInfoCard {
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
            SettingsInfoCard {
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

        // Bottom spacer to ensure last section scrolls fully into view
        Spacer(modifier = Modifier.height(48.dp))
    }

    uiState.logoutMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            delay(100)
            viewModel.clearLogoutMessage()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { focusState ->
                if (focusState.hasFocus) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            },
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

/**
 * Focusable info card for settings - focus border but no zoom effect
 */
@Composable
private fun SettingsInfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .focusable(interactionSource = interactionSource),
        border = if (isFocused) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/**
 * Focusable toggle card for settings with on/off switch
 */
@Composable
private fun SettingsToggleCard(
    title: String,
    description: String,
    isChecked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle
            ),
        border = if (isFocused) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isChecked,
                onCheckedChange = null, // Handled by card click
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
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
            UiScale.entries.forEachIndexed { index, scale ->
                UiScaleChip(
                    scale = scale,
                    isSelected = scale == currentScale,
                    onClick = {
                        currentScale = scale
                        UiScalePreferences.setUiScale(context, scale)
                        showRestartHint = true
                    },
                    modifier = Modifier.weight(1f),
                    index = index,
                    totalItems = UiScale.entries.size
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
    modifier: Modifier = Modifier,
    index: Int = 0,
    totalItems: Int = 1
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    val gradientBrush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = com.duckflix.lite.ui.theme.TvOsColors.gradientColors
    )

    // Grey when not focused and not selected, gradient when focused or selected
    val backgroundModifier = when {
        isFocused -> Modifier.background(brush = gradientBrush, shape = shape)
        isSelected -> Modifier.background(brush = gradientBrush, shape = shape)
        else -> Modifier.background(color = Color(0xFF3A3A3A), shape = shape)
    }

    val borderModifier = if (isFocused) {
        Modifier.border(2.dp, gradientBrush, shape)
    } else {
        Modifier
    }

    // Use each scale's fontFactor to preview what text will look like
    val baseFontSize = MaterialTheme.typography.labelLarge.fontSize
    val previewFontSize = (baseFontSize.value * scale.fontFactor).sp

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .then(backgroundModifier)
            .then(borderModifier)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = scale.displayName,
            fontSize = previewFontSize,
            color = if (isFocused || isSelected) Color.White else Color.White.copy(alpha = 0.7f),
            maxLines = 1
        )
    }
}

/**
 * Reusable chip row for settings options (subtitle size, color, etc.)
 * Uses same visual pattern as UiScaleChip.
 */
@Composable
private fun SettingsChipRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEachIndexed { index, label ->
                SettingsChip(
                    label = label,
                    isSelected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SettingsChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    val gradientBrush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = com.duckflix.lite.ui.theme.TvOsColors.gradientColors
    )

    val backgroundModifier = when {
        isFocused -> Modifier.background(brush = gradientBrush, shape = shape)
        isSelected -> Modifier.background(brush = gradientBrush, shape = shape)
        else -> Modifier.background(color = Color(0xFF3A3A3A), shape = shape)
    }

    val borderModifier = if (isFocused) {
        Modifier.border(2.dp, gradientBrush, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .then(backgroundModifier)
            .then(borderModifier)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused || isSelected) Color.White else Color.White.copy(alpha = 0.7f),
            maxLines = 1
        )
    }
}
