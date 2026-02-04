package com.duckflix.lite.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duckflix.lite.ui.components.ErrorScreen
import com.duckflix.lite.ui.components.FocusableButton
import com.duckflix.lite.ui.components.FocusableCard
import com.duckflix.lite.ui.components.LoadingIndicator
import java.text.SimpleDateFormat
import java.util.*

enum class AdminTab { DASHBOARD, USERS, FAILURES }

@Composable
fun AdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: AdminViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(AdminTab.DASHBOARD) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Admin Dashboard",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White
            )
            FocusableButton(onClick = onNavigateBack) {
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Tab buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            AdminTab.values().forEach { tab ->
                FocusableButton(
                    onClick = { selectedTab = tab },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = tab.name,
                        color = if (selectedTab == tab) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Tab content
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            uiState.error != null -> {
                ErrorScreen(
                    message = uiState.error ?: "Unknown error",
                    onRetry = viewModel::loadData
                )
            }
            else -> {
                when (selectedTab) {
                    AdminTab.DASHBOARD -> DashboardTab(uiState)
                    AdminTab.USERS -> UsersTab(uiState, viewModel)
                    AdminTab.FAILURES -> FailuresTab(uiState)
                }
            }
        }
    }
}

@Composable
private fun DashboardTab(uiState: AdminUiState) {
    val dashboard = uiState.dashboard ?: return

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatCard(
                    title = "Total Users",
                    value = dashboard.totalUsers.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Active Sessions",
                    value = dashboard.activeSessions.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatCard(
                    title = "RD Expiring Soon",
                    value = dashboard.rdExpiringSoon.toString(),
                    color = if (dashboard.rdExpiringSoon > 0) Color(0xFFFF9800) else Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Recent Failures",
                    value = dashboard.recentFailures.toString(),
                    color = if (dashboard.recentFailures > 0) Color(0xFFF44336) else Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (dashboard.totalStorage != null) {
            item {
                StatCard(
                    title = "Total Storage",
                    value = dashboard.totalStorage,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(120.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                color = color
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun UsersTab(uiState: AdminUiState, viewModel: AdminViewModel) {
    var selectedUser by remember { mutableStateOf<AdminUserInfo?>(null) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(uiState.users) { user ->
            UserCard(
                user = user,
                onClick = { selectedUser = user }
            )
        }
    }

    // User detail dialog
    selectedUser?.let { user ->
        UserDetailDialog(
            user = user,
            onDismiss = { selectedUser = null },
            onResetPassword = {
                viewModel.resetUserPassword(user.id)
                selectedUser = null
            },
            onDisableUser = {
                viewModel.disableUser(user.id)
                selectedUser = null
            }
        )
    }
}

@Composable
private fun UserCard(
    user: AdminUserInfo,
    onClick: () -> Unit
) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = user.username,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    if (user.isAdmin) {
                        Text(
                            text = "ADMIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last login: ${user.lastLogin ?: "Never"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (user.isRdExpiringSoon) {
                    Text(
                        text = "⚠ Expiring Soon",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
                user.rdExpiry?.let {
                    Text(
                        text = "RD: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun UserDetailDialog(
    user: AdminUserInfo,
    onDismiss: () -> Unit,
    onResetPassword: () -> Unit,
    onDisableUser: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = user.username)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("User ID: ${user.id}")
                Text("Admin: ${if (user.isAdmin) "Yes" else "No"}")
                Text("Last Login: ${user.lastLogin ?: "Never"}")
                user.rdExpiry?.let {
                    Text("RD Expiry: $it")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusableButton(onClick = onResetPassword) {
                    Text("Reset Password")
                }
                if (!user.isAdmin) {
                    FocusableButton(onClick = onDisableUser) {
                        Text("Disable")
                    }
                }
            }
        },
        dismissButton = {
            FocusableButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun FailuresTab(uiState: AdminUiState) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(uiState.failures) { failure ->
            FailureCard(failure)
        }

        if (uiState.failures.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No recent failures",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FailureCard(failure: AdminFailureInfo) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = failure.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = formatTimestamp(failure.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "User: ${failure.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = "Code: ${failure.errorCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336)
                )
            }

            Text(
                text = failure.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
