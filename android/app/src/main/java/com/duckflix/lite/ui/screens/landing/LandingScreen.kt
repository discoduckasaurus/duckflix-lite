package com.duckflix.lite.ui.screens.landing

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.duckflix.lite.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckflix.lite.ui.components.ExitConfirmationDialog
import com.duckflix.lite.ui.theme.TvOsColors
import com.duckflix.lite.ui.theme.tvOsScaleOnly

/**
 * Main landing screen after login.
 * Shows two primary navigation buttons (On Demand, Live TV) and a settings button.
 */
@Composable
fun LandingScreen(
    onNavigateToVod: () -> Unit,
    onNavigateToLiveTv: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }
    val primaryFocusRequester = remember { FocusRequester() }

    BackHandler {
        showExitDialog = true
    }

    if (showExitDialog) {
        ExitConfirmationDialog(
            onConfirm = {
                (context as? Activity)?.finish()
            },
            onDismiss = {
                showExitDialog = false
            }
        )
    }

    // Request focus on first button when screen loads
    LaunchedEffect(Unit) {
        try {
            primaryFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Large duck logo at top center
        Image(
            painter = painterResource(id = R.drawable.dfl_logo),
            contentDescription = "DuckFlix Logo",
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Main buttons row
        Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // On Demand button
            LandingButton(
                title = "On Demand",
                subtitle = "Movies & TV Shows",
                onClick = onNavigateToVod,
                focusRequester = primaryFocusRequester,
                modifier = Modifier.size(width = 280.dp, height = 180.dp)
            )

            // Live TV button
            LandingButton(
                title = "Live TV",
                subtitle = "Watch Now",
                onClick = onNavigateToLiveTv,
                modifier = Modifier.size(width = 280.dp, height = 180.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Settings button (smaller, circular)
        SettingsButton(
            onClick = onNavigateToSettings
        )

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LandingButton(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)

    val gradientBrush = Brush.linearGradient(
        colors = TvOsColors.gradientColors
    )

    // Grey when not focused, gradient when focused
    val backgroundModifier = if (isFocused) {
        Modifier.background(brush = gradientBrush, shape = shape)
    } else {
        Modifier.background(color = Color(0xFF3A3A3A), shape = shape)
    }

    val borderModifier = if (isFocused) {
        Modifier.border(3.dp, gradientBrush, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.05f)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .then(backgroundModifier)
            .then(borderModifier)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                fontSize = 16.sp,
                color = if (isFocused) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SettingsButton(
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val gradientBrush = Brush.linearGradient(
        colors = TvOsColors.gradientColors
    )

    // Grey when not focused, gradient when focused
    val backgroundModifier = if (isFocused) {
        Modifier.background(brush = gradientBrush, shape = CircleShape)
    } else {
        Modifier.background(color = Color(0xFF3A3A3A), shape = CircleShape)
    }

    val borderModifier = if (isFocused) {
        Modifier.border(2.dp, gradientBrush, CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(64.dp)
            .tvOsScaleOnly(isFocused = isFocused, focusedScale = 1.08f)
            .clip(CircleShape)
            .then(backgroundModifier)
            .then(borderModifier)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Settings",
            tint = if (isFocused) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(32.dp)
        )
    }
}
