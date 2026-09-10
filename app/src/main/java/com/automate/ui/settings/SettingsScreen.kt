package com.automate.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
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

    var showWorkHoursDialog by remember { mutableStateOf(false) }
    var showMorningTimeDialog by remember { mutableStateOf(false) }
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
            Text(
                "System",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            ListItem(
                headlineContent = { Text("Accessibility Service") },
                supportingContent = {
                    Text(if (AutoMateAccessibilityService.instance != null) "Active" else "Needs activation")
                },
                leadingContent = { Icon(Icons.Default.Accessibility, null) },
                trailingContent = {
                    if (AutoMateAccessibilityService.instance == null) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: Exception) {}
                        }) { Text("Enable") }
                    }
                }
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Work Hours") },
                supportingContent = { Text("${uiState.workHours} hours") },
                leadingContent = { Icon(Icons.Default.Schedule, null) }
            )
            TextButton(
                onClick = { showWorkHoursDialog = true },
                modifier = Modifier.padding(start = 72.dp)
            ) { Text("Change") }

            HorizontalDivider()

            Text(
                "Notifications",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            ListItem(
                headlineContent = { Text("Morning Prompt") },
                supportingContent = {
                    val h = uiState.morningHour
                    val m = uiState.morningMinute
                    val period = if (h < 12) "AM" else "PM"
                    val displayH = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
                    Text("Daily at $displayH:${String.format("%02d", m)} $period, Mon-Fri")
                },
                leadingContent = { Icon(Icons.Default.Alarm, null) },
                trailingContent = {
                    Switch(
                        checked = uiState.morningPromptEnabled,
                        onCheckedChange = { viewModel.toggleMorningPrompt() }
                    )
                }
            )
            TextButton(
                onClick = { showMorningTimeDialog = true },
                modifier = Modifier.padding(start = 72.dp)
            ) { Text("Change Time") }

            HorizontalDivider()

            Text(
                "Location",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            ListItem(
                headlineContent = { Text("Geofence Radius") },
                supportingContent = { Text("${uiState.geofenceRadius.toInt()}m around office") },
                leadingContent = { Icon(Icons.Default.LocationOn, null) }
            )
            TextButton(
                onClick = { showGeofenceRadiusDialog = true },
                modifier = Modifier.padding(start = 72.dp)
            ) { Text("Change") }

            ListItem(
                headlineContent = { Text("Exit Watch Distance") },
                supportingContent = { Text("${uiState.exitWatchDistance.toInt()}m from check-in spot") },
                leadingContent = { Icon(Icons.Default.LocationOff, null) }
            )
            TextButton(
                onClick = { showExitDistanceDialog = true },
                modifier = Modifier.padding(start = 72.dp)
            ) { Text("Change") }

            HorizontalDivider()

            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            ListItem(
                headlineContent = { Text("AutoMate") },
                supportingContent = { Text("Version 2.0.0") },
                leadingContent = { Icon(Icons.Default.Info, null) }
            )
        }
    }

    if (showMorningTimeDialog) {
        SimpleTimePickerDialog(
            initialHour = uiState.morningHour,
            initialMinute = uiState.morningMinute,
            onConfirm = { hour, minute ->
                viewModel.setMorningTime(hour, minute)
                showMorningTimeDialog = false
            },
            onDismiss = { showMorningTimeDialog = false }
        )
    }

    if (showWorkHoursDialog) {
        SliderDialog(
            title = "Work Hours",
            initialValue = uiState.workHours,
            valueRange = 4f..12f,
            step = 0.5f,
            format = { "${it.toInt()} hours" },
            onConfirm = { viewModel.setWorkHours(it) },
            onDismiss = { showWorkHoursDialog = false }
        )
    }

    if (showGeofenceRadiusDialog) {
        SliderDialog(
            title = "Geofence Radius",
            initialValue = uiState.geofenceRadius,
            valueRange = 50f..500f,
            step = 25f,
            format = { "${it.toInt()}m" },
            onConfirm = { viewModel.setGeofenceRadius(it) },
            onDismiss = { showGeofenceRadiusDialog = false }
        )
    }

    if (showExitDistanceDialog) {
        SliderDialog(
            title = "Exit Watch Distance",
            initialValue = uiState.exitWatchDistance,
            valueRange = 10f..200f,
            step = 10f,
            format = { "${it.toInt()}m" },
            onConfirm = { viewModel.setExitWatchDistance(it) },
            onDismiss = { showExitDistanceDialog = false }
        )
    }
}

@Composable
fun SimpleTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Morning Prompt Time") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "When should AutoMate ask if you're going to work?",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hour selector
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { if (hour < 23) hour++ }) {
                            Icon(Icons.Default.KeyboardArrowUp, "Increase hour")
                        }
                        Text(
                            String.format("%02d", hour),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { if (hour > 0) hour-- }) {
                            Icon(Icons.Default.KeyboardArrowDown, "Decrease hour")
                        }
                        Text("Hour", style = MaterialTheme.typography.bodySmall)
                    }

                    Text(
                        ":",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    // Minute selector
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { if (minute < 55) minute += 5 else minute = 0 }) {
                            Icon(Icons.Default.KeyboardArrowUp, "Increase minute")
                        }
                        Text(
                            String.format("%02d", minute),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { if (minute > 0) minute -= 5 else minute = 55 }) {
                            Icon(Icons.Default.KeyboardArrowDown, "Decrease minute")
                        }
                        Text("Minute", style = MaterialTheme.typography.bodySmall)
                    }

                    // AM/PM toggle
                    Column(
                        modifier = Modifier.padding(start = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val isPM = hour >= 12
                        AssistChip(
                            onClick = {
                                hour = if (isPM) hour - 12 else hour + 12
                            },
                            label = { Text(if (isPM) "PM" else "AM") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour, minute) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SliderDialog(
    title: String,
    initialValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
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
                    format(sliderValue),
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
                    Text(format(valueRange.start), style = MaterialTheme.typography.bodySmall)
                    Text(format(valueRange.endInclusive), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sliderValue); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
