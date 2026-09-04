package com.automate.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

    var showWorkHoursDialog by remember { mutableStateOf(false) }
    var showGeofenceRadiusDialog by remember { mutableStateOf(false) }
    var showExitDistanceDialog by remember { mutableStateOf(false) }

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

            ListItem(
                headlineContent = { Text("Accessibility Service") },
                supportingContent = {
                    Text(if (AutoMateAccessibilityService.instance != null) "Enabled" else "Disabled")
                },
                leadingContent = { Icon(Icons.Default.Accessibility, null) },
                trailingContent = {
                    if (AutoMateAccessibilityService.instance == null) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("Enable") }
                    }
                }
            )

            HorizontalDivider()

            // Work Hours
            ListItem(
                headlineContent = { Text("Work Hours") },
                supportingContent = { Text("${uiState.workHours} hours (${formatHoursAndMinutes(uiState.workHours.toLong())})") },
                leadingContent = { Icon(Icons.Default.Schedule, null) },
                modifier = Modifier.run {
                    this
                }
            )
            TextButton(
                onClick = { showWorkHoursDialog = true },
                modifier = Modifier.padding(start = 72.dp)
            ) { Text("Edit") }

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
                leadingContent = { Icon(Icons.Default.Alarm, null) },
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
                leadingContent = { Icon(Icons.Default.LocationOn, null) }
            )
            TextButton(
                onClick = { showGeofenceRadiusDialog = true },
                modifier = Modifier.padding(start = 72.dp)
            ) { Text("Edit") }

            ListItem(
                headlineContent = { Text("Exit Watch Distance") },
                supportingContent = { Text("${uiState.exitWatchDistance.toInt()}m from office") },
                leadingContent = { Icon(Icons.Default.LocationOff, null) }
            )
            TextButton(
                onClick = { showExitDistanceDialog = true },
                modifier = Modifier.padding(start = 72.dp)
            ) { Text("Edit") }

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
                leadingContent = { Icon(Icons.Default.Info, null) }
            )
        }
    }

    // Work Hours Dialog
    if (showWorkHoursDialog) {
        NumberInputDialog(
            title = "Work Hours",
            initialValue = uiState.workHours,
            valueRange = 4f..12f,
            step = 0.5f,
            unit = "hours",
            onConfirm = { viewModel.setWorkHours(it) },
            onDismiss = { showWorkHoursDialog = false }
        )
    }

    // Geofence Radius Dialog
    if (showGeofenceRadiusDialog) {
        NumberInputDialog(
            title = "Geofence Radius",
            initialValue = uiState.geofenceRadius,
            valueRange = 50f..500f,
            step = 25f,
            unit = "meters",
            onConfirm = { viewModel.setGeofenceRadius(it) },
            onDismiss = { showGeofenceRadiusDialog = false }
        )
    }

    // Exit Distance Dialog
    if (showExitDistanceDialog) {
        NumberInputDialog(
            title = "Exit Watch Distance",
            initialValue = uiState.exitWatchDistance,
            valueRange = 10f..200f,
            step = 10f,
            unit = "meters from office",
            onConfirm = { viewModel.setExitWatchDistance(it) },
            onDismiss = { showExitDistanceDialog = false }
        )
    }
}

private fun formatHoursAndMinutes(hours: Long): String {
    val h = hours
    val m = ((hours - h) * 60).toInt()
    return if (m > 0) "${h}h ${m}m" else "${h}h"
}

@Composable
fun NumberInputDialog(
    title: String,
    initialValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "${sliderValue.toInt()} $unit",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = valueRange,
                    steps = ((valueRange.endInclusive - valueRange.start) / step - 1).toInt(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${valueRange.start.toInt()} $unit", style = MaterialTheme.typography.bodySmall)
                    Text("${valueRange.endInclusive.toInt()} $unit", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(sliderValue)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
