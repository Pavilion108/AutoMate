package com.automate.ui.taskeditor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    taskId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TaskEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var taskName by remember { mutableStateOf("") }
    var selectedTrigger by remember { mutableStateOf("GEOFENCE_ENTER") }
    var workHours by remember { mutableIntStateOf(8) }
    var scheduleHour by remember { mutableIntStateOf(7) }
    var scheduleMinute by remember { mutableIntStateOf(30) }

    LaunchedEffect(taskId) {
        viewModel.loadTask(taskId)
    }

    LaunchedEffect(uiState.task) {
        uiState.task?.let { task ->
            taskName = task.name
            selectedTrigger = task.trigger.type.name
            workHours = 8
            if (task.trigger.type == com.automate.domain.model.TriggerType.TIME_SCHEDULE) {
                scheduleHour = task.trigger.hour
                scheduleMinute = task.trigger.minute
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (taskId == -1L) "New Task" else "Edit Task") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.saveTask(
                            name = taskName,
                            triggerType = selectedTrigger,
                            workHours = workHours,
                            scheduleHour = scheduleHour,
                            scheduleMinute = scheduleMinute
                        )
                        onNavigateBack()
                    }) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Task Name
            OutlinedTextField(
                value = taskName,
                onValueChange = { taskName = it },
                label = { Text("Task Name") },
                modifier = Modifier.fillMaxWidth()
            )

            // Trigger Type
            Text("Trigger", fontWeight = FontWeight.Bold)
            var expanded by remember { mutableStateOf(false) }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = getTriggerDisplayName(selectedTrigger),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    listOf(
                        "GEOFENCE_ENTER" to "Enter Location",
                        "GEOFENCE_EXIT" to "Exit Location",
                        "TIME_SCHEDULE" to "Time Schedule",
                        "MANUAL" to "Manual"
                    ).forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                selectedTrigger = value
                                expanded = false
                            }
                        )
                    }
                }
            }

            // Work Hours (for exit/distance tasks)
            if (selectedTrigger == "GEOFENCE_EXIT") {
                Text("Work Hours Before Prompt", fontWeight = FontWeight.Bold)
                Slider(
                    value = workHours.toFloat(),
                    onValueChange = { workHours = it.toInt() },
                    valueRange = 4f..12f,
                    steps = 7
                )
                Text("$workHours hours", modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            // Schedule Time (for time-based tasks)
            if (selectedTrigger == "TIME_SCHEDULE") {
                Text("Schedule Time", fontWeight = FontWeight.Bold)
                var showTimePicker by remember { mutableStateOf(false) }

                OutlinedCard(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                formatTime(scheduleHour, scheduleMinute),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("Edit", color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (showTimePicker) {
                    val timePickerState = rememberTimePickerState(
                        initialHour = scheduleHour,
                        initialMinute = scheduleMinute,
                        is24Hour = false
                    )
                    AlertDialog(
                        onDismissRequest = { showTimePicker = false },
                        title = { Text("Schedule Time") },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "When should this task trigger?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                                TimePicker(
                                    state = timePickerState,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scheduleHour = timePickerState.hour
                                scheduleMinute = timePickerState.minute
                                showTimePicker = false
                            }) { Text("Set") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                        }
                    )
                }

                Text(
                    "Runs daily at ${formatTime(scheduleHour, scheduleMinute)} (Mon-Fri)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions Preview — shows exactly what this task will do
            Text("Actions", fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (selectedTrigger) {
                        "GEOFENCE_ENTER" -> {
                            Text("1. Launch Beehive HRMS", style = MaterialTheme.typography.bodySmall)
                            Text("2. Wait for app to load", style = MaterialTheme.typography.bodySmall)
                            Text("3. Click SIGN IN", style = MaterialTheme.typography.bodySmall)
                            Text("4. Click TIME IN", style = MaterialTheme.typography.bodySmall)
                            Text("5. Handle popups / location errors", style = MaterialTheme.typography.bodySmall)
                            Text("6. Save GPS location", style = MaterialTheme.typography.bodySmall)
                            Text("7. Schedule time-out prompt at 7h", style = MaterialTheme.typography.bodySmall)
                            Text("8. Close app", style = MaterialTheme.typography.bodySmall)
                        }
                        "GEOFENCE_EXIT" -> {
                            Text("1. Wait for work hours ($workHours h)", style = MaterialTheme.typography.bodySmall)
                            Text("2. Ask: 'About to leave?'", style = MaterialTheme.typography.bodySmall)
                            Text("3. If yes: watch GPS for exit", style = MaterialTheme.typography.bodySmall)
                            Text("4. If no: ask again at 8.5h", style = MaterialTheme.typography.bodySmall)
                            Text("5. On exit: Launch Beehive HRMS", style = MaterialTheme.typography.bodySmall)
                            Text("6. Click SIGN IN if needed", style = MaterialTheme.typography.bodySmall)
                            Text("7. Click TIME OUT", style = MaterialTheme.typography.bodySmall)
                            Text("8. Handle popups", style = MaterialTheme.typography.bodySmall)
                            Text("9. Close app, disable geofences", style = MaterialTheme.typography.bodySmall)
                        }
                        "TIME_SCHEDULE" -> {
                            Text("1. Alarm fires at ${formatTime(scheduleHour, scheduleMinute)}", style = MaterialTheme.typography.bodySmall)
                            Text("2. Show morning prompt notification", style = MaterialTheme.typography.bodySmall)
                            Text("3. User taps 'Yes, going!' or 'No, staying home'", style = MaterialTheme.typography.bodySmall)
                            Text("4. If yes: arm geofences, start GPS", style = MaterialTheme.typography.bodySmall)
                            Text("5. If no: disable everything for the day", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> {
                            Text("Configure actions after saving", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun getTriggerDisplayName(type: String): String {
    return when (type) {
        "GEOFENCE_ENTER" -> "Enter Location"
        "GEOFENCE_EXIT" -> "Exit Location"
        "TIME_SCHEDULE" -> "Time Schedule"
        "MANUAL" -> "Manual"
        else -> type
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    val period = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$displayHour:${String.format("%02d", minute)} $period"
}
