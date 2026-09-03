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

    LaunchedEffect(taskId) {
        viewModel.loadTask(taskId)
    }

    LaunchedEffect(uiState.task) {
        uiState.task?.let {
            taskName = it.name
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
                            workHours = workHours
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

            // Work Hours (for time-based tasks)
            if (selectedTrigger == "TIME_SCHEDULE") {
                Text("Work Hours", fontWeight = FontWeight.Bold)
                Slider(
                    value = workHours.toFloat(),
                    onValueChange = { workHours = it.toInt() },
                    valueRange = 4f..12f,
                    steps = 7
                )
                Text("$workHours hours", modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            // Actions Preview
            Text("Actions", fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (selectedTrigger) {
                        "GEOFENCE_ENTER" -> {
                            Text("1. Launch Beehive HRMS")
                            Text("2. Click SIGN IN")
                            Text("3. Click TIME IN")
                            Text("4. Handle popups")
                            Text("5. Click OK")
                            Text("6. Close app")
                        }
                        "GEOFENCE_EXIT" -> {
                            Text("1. Wait $workHours hours")
                            Text("2. Prompt: Keep watching?")
                            Text("3. Monitor distance (150m)")
                            Text("4. Launch Beehive HRMS")
                            Text("5. Click TIME OUT")
                            Text("6. Handle popups")
                            Text("7. Click OK")
                            Text("8. Close app")
                        }
                        else -> {
                            Text("Configure actions after saving")
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
