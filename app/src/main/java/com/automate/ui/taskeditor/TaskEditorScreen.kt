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
            OutlinedTextField(
                value = taskName,
                onValueChange = { taskName = it },
                label = { Text("Task Name") },
                modifier = Modifier.fillMaxWidth()
            )

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
                    SimpleTimePickerInline(
                        initialHour = scheduleHour,
                        initialMinute = scheduleMinute,
                        onConfirm = { h, m ->
                            scheduleHour = h
                            scheduleMinute = m
                            showTimePicker = false
                        },
                        onDismiss = { showTimePicker = false }
                    )
                }

                Text(
                    "Runs daily at ${formatTime(scheduleHour, scheduleMinute)} (Mon-Fri)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("What this task does", fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (selectedTrigger) {
                        "GEOFENCE_ENTER" -> {
                            Text("When you arrive at the office, AutoMate will:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Open Beehive HRMS and mark your attendance automatically", style = MaterialTheme.typography.bodySmall)
                            Text("Handle any popups or GPS errors", style = MaterialTheme.typography.bodySmall)
                            Text("Ask you before marking time-out in the evening", style = MaterialTheme.typography.bodySmall)
                        }
                        "GEOFENCE_EXIT" -> {
                            Text("When your work hours are done and you leave:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("AutoMate will ask if you're leaving for the day", style = MaterialTheme.typography.bodySmall)
                            Text("Then mark your time-out in Beehive HRMS", style = MaterialTheme.typography.bodySmall)
                            Text("You can be prompted again later if you stay", style = MaterialTheme.typography.bodySmall)
                        }
                        "TIME_SCHEDULE" -> {
                            Text("At ${formatTime(scheduleHour, scheduleMinute)}, AutoMate will:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Ask if you're going to work today", style = MaterialTheme.typography.bodySmall)
                            Text("If yes: track your location for attendance", style = MaterialTheme.typography.bodySmall)
                            Text("If no: no tracking for the day", style = MaterialTheme.typography.bodySmall)
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

@Composable
fun SimpleTimePickerInline(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Time") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { if (hour < 23) hour++ }) {
                        Icon(Icons.Default.KeyboardArrowUp, "Up")
                    }
                    Text(String.format("%02d", hour), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { if (hour > 0) hour-- }) {
                        Icon(Icons.Default.KeyboardArrowDown, "Down")
                    }
                    Text("Hour", style = MaterialTheme.typography.bodySmall)
                }
                Text(":", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { if (minute < 55) minute += 5 else minute = 0 }) {
                        Icon(Icons.Default.KeyboardArrowUp, "Up")
                    }
                    Text(String.format("%02d", minute), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { if (minute > 0) minute -= 5 else minute = 55 }) {
                        Icon(Icons.Default.KeyboardArrowDown, "Down")
                    }
                    Text("Minute", style = MaterialTheme.typography.bodySmall)
                }
                Column(modifier = Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val isPM = hour >= 12
                    AssistChip(
                        onClick = { hour = if (isPM) hour - 12 else hour + 12 },
                        label = { Text(if (isPM) "PM" else "AM") }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(hour, minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
