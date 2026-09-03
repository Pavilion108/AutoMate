package com.automate.ui.dashboard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.automate.engine.AutoMateAccessibilityService
import com.automate.ui.theme.StatusGreen
import com.automate.ui.theme.StatusRed
import com.automate.ui.theme.StatusYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToTaskEditor: (Long) -> Unit,
    onNavigateToGeofenceManager: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoMate") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToTaskEditor(-1) }) {
                Icon(Icons.Default.Add, "Add Task")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status Card
            item {
                StatusCard(
                    isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                    isGeofenceEnabled = uiState.isGeofenceEnabled,
                    isArmed = uiState.isArmed,
                    onToggleAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }

            // Quick Actions
            item {
                Text(
                    "Quick Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.PlayArrow,
                        label = "Time In",
                        onClick = { viewModel.startTimeIn() }
                    )
                    QuickActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Stop,
                        label = "Time Out",
                        onClick = { viewModel.performTimeOut() }
                    )
                    QuickActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.LocationOn,
                        label = "Locations",
                        onClick = onNavigateToGeofenceManager
                    )
                }
            }

            // Tasks Section
            item {
                Text(
                    "Tasks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (uiState.tasks.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            "No tasks configured. Tap + to create one.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            items(uiState.tasks) { task ->
                TaskCard(
                    name = task.name,
                    isEnabled = task.isEnabled,
                    triggerDescription = task.triggerDescription,
                    lastRun = task.lastRunDescription,
                    onClick = { onNavigateToTaskEditor(task.id) },
                    onToggle = { viewModel.toggleTask(task.id) }
                )
            }

            // Geofence Locations
            item {
                Text(
                    "Locations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(uiState.locations) { location ->
                LocationCard(
                    name = location.name,
                    radius = location.radiusMeters,
                    isActive = location.isActive
                )
            }

            // Spacer for FAB
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun StatusCard(
    isAccessibilityEnabled: Boolean,
    isGeofenceEnabled: Boolean,
    isArmed: Boolean,
    onToggleAccessibility: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "System Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            StatusRow(
                label = "Accessibility Service",
                isEnabled = isAccessibilityEnabled,
                onClick = onToggleAccessibility
            )
            StatusRow(
                label = "Location Tracking",
                isEnabled = isGeofenceEnabled
            )
            StatusRow(
                label = "Armed for Check-in",
                isEnabled = isArmed
            )
        }
    }
}

@Composable
fun StatusRow(label: String, isEnabled: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.padding(vertical = 4.dp) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (isEnabled) StatusGreen else StatusRed,
                modifier = Modifier.size(20.dp)
            )
            if (onClick != null && !isEnabled) {
                TextButton(onClick = onClick) {
                    Text("Enable")
                }
            }
        }
    }
}

@Composable
fun QuickActionButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
fun TaskCard(
    name: String,
    isEnabled: Boolean,
    triggerDescription: String,
    lastRun: String?,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold)
                Text(
                    triggerDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lastRun != null) {
                    Text(
                        "Last run: $lastRun",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = isEnabled, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
fun LocationCard(name: String, radius: Float, isActive: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(name, fontWeight = FontWeight.Bold)
                Text(
                    "Radius: ${radius.toInt()}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.LocationOff,
                contentDescription = null,
                tint = if (isActive) StatusGreen else StatusRed
            )
        }
    }
}
