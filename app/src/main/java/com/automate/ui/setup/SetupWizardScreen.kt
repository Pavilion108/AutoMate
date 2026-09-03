package com.automate.ui.setup

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.automate.engine.AutoMateAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onSetupComplete: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Setup AutoMate") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            when (step) {
                0 -> WelcomeStep(onNext = { step = 1 })
                1 -> AccessibilityStep(
                    isEnabled = AutoMateAccessibilityService.instance != null,
                    onEnable = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onNext = { step = 2 }
                )
                2 -> LocationStep(onNext = { step = 3 })
                3 -> WorkHoursStep(onComplete = onSetupComplete)
            }

            // Progress indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(8.dp)
                            .then(
                                if (index == step) Modifier.then(
                                    Modifier.padding(0.dp)
                                ) else Modifier
                            )
                    )
                    Surface(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(8.dp),
                        shape = MaterialTheme.shapes.small,
                        color = if (index <= step)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ) {}
                }
            }
        }
    }
}

@Composable
fun WelcomeStep(onNext: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Autorenew,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "Welcome to AutoMate",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "AutoMate automates tasks in other apps on your phone.\n\n" +
            "It can automatically check you in at work, send messages, " +
            "and perform many other tasks based on your location and schedule.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Button(onClick = onNext) {
            Text("Get Started")
        }
    }
}

@Composable
fun AccessibilityStep(isEnabled: Boolean, onEnable: () -> Unit, onNext: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Accessibility,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "Enable Accessibility Service",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "AutoMate needs Accessibility Service to interact with other apps on your behalf.\n\n" +
            "This service reads screen content to find buttons and fields, " +
            "then performs taps and text input when triggered.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        if (isEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Accessibility Service is enabled", color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Button(onClick = onEnable) {
                Text("Open Accessibility Settings")
            }
        }

        if (isEnabled) {
            Button(onClick = onNext) {
                Text("Continue")
            }
        }
    }
}

@Composable
fun LocationStep(onNext: () -> Unit) {
    var hasLocationPermission by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        // Check location permission
        hasLocationPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "Location Access",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "AutoMate uses your location to detect when you arrive at or leave work.\n\n" +
            "Location data is only used for geofence triggers and is never shared.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        if (!hasLocationPermission) {
            Button(onClick = {
                // Request location permission
                val activity = context as? android.app.Activity
                activity?.requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ),
                    1001
                )
            }) {
                Text("Grant Location Permission")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Location permission granted", color = MaterialTheme.colorScheme.primary)
            }
        }

        Button(onClick = onNext) {
            Text("Continue")
        }
    }
}

@Composable
fun WorkHoursStep(onComplete: () -> Unit) {
    var workHours by remember { mutableIntStateOf(8) }
    var officeName by remember { mutableStateOf("Office") }
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "Work Hours",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "How many hours do you typically work per day?",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        // Work hours slider
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$workHours hours",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = workHours.toFloat(),
                onValueChange = { workHours = it.toInt() },
                valueRange = 4f..12f,
                steps = 7,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Text(
                "Time-out will be suggested ${workHours} hours after time-in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            // Save work hours preference
            val prefs = context.getSharedPreferences("automate_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("work_hours", workHours).apply()
            onComplete()
        }) {
            Text("Complete Setup")
        }
    }
}
