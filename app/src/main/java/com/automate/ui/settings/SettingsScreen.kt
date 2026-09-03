package com.automate.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.automate.engine.AutoMateAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // System Settings
            Text(
                "System",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            // Accessibility Service
            ListItem(
                headlineContent = { Text("Accessibility Service") },
                supportingContent = {
                    Text(if (AutoMateAccessibilityService.instance != null) "Enabled" else "Disabled")
                },
                leadingContent = {
                    Icon(Icons.Default.Accessibility, null)
                },
                trailingContent = {
                    if (AutoMateAccessibilityService.instance == null) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text("Enable")
                        }
                    }
                }
            )

            HorizontalDivider()

            // Work Hours
            ListItem(
                headlineContent = { Text("Work Hours") },
                supportingContent = { Text("${uiState.workHours} hours") },
                leadingContent = {
                    Icon(Icons.Default.Schedule, null)
                }
            )

            HorizontalDivider()

            // Notification Settings
            Text(
                "Notifications",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            ListItem(
                headlineContent = { Text("Morning Prompt") },
                supportingContent = { Text("Daily reminder at 7:30 AM") },
                leadingContent = {
                    Icon(Icons.Default.Alarm, null)
                },
                trailingContent = {
                    Switch(
                        checked = uiState.morningPromptEnabled,
                        onCheckedChange = { viewModel.toggleMorningPrompt() }
                    )
                }
            )

            HorizontalDivider()

            // Location Settings
            Text(
                "Location",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            ListItem(
                headlineContent = { Text("Geofence Radius") },
                supportingContent = { Text("${uiState.geofenceRadius.toInt()}m") },
                leadingContent = {
                    Icon(Icons.Default.LocationOn, null)
                }
            )

            ListItem(
                headlineContent = { Text("Exit Watch Distance") },
                supportingContent = { Text("${uiState.exitWatchDistance.toInt()}m") },
                leadingContent = {
                    Icon(Icons.Default.LocationOff, null)
                }
            )

            HorizontalDivider()

            // About
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            ListItem(
                headlineContent = { Text("AutoMate") },
                supportingContent = { Text("Version 1.0.0") },
                leadingContent = {
                    Icon(Icons.Default.Info, null)
                }
            )

            ListItem(
                headlineContent = { Text("Open Source") },
                supportingContent = { Text("View source on GitHub") },
                leadingContent = {
                    Icon(Icons.Default.Code, null)
                }
            )
        }
    }
}
