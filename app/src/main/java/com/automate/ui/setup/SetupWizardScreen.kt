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
    val context = LocalContext.current
    var hasForeground by remember { mutableStateOf(false) }
    var hasBackground by remember { mutableStateOf(false) }
    var askedForeground by remember { mutableStateOf(false) }
    var askedBackground by remember { mutableStateOf(false) }

    fun refresh() {
        hasForeground = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasBackground = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    // Re-check when returning from the system permission screen.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { refresh() }

    val allGranted = hasForeground && hasBackground

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

        if (!allGranted) {
            // Background location must be requested in a SEPARATE call after the
            // foreground grant. On Android 11+ a combined request is dropped by the
            // system, which is why "Allow all the time" never stuck before.
            val buttonLabel = when {
                !hasForeground && !askedForeground -> "Grant Location Permission"
                !hasForeground -> "Grant Location Permission (Required)"
                !hasBackground && !askedBackground -> "Grant Background Access"
                else -> "Open Settings to Allow All the Time"
            }

            Button(onClick = {
                val activity = context as? android.app.Activity
                when {
                    !hasForeground -> {
                        askedForeground = true
                        activity?.requestPermissions(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            ),
                            1001
                        )
                    }
                    !hasBackground && android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.R -> {
                        // Android 10 shows a real dialog for background location.
                        askedBackground = true
                        activity?.requestPermissions(
                            arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            1002
                        )
                    }
                    else -> {
                        // Android 11+ has no background dialog: send the user to the
                        // app's permission page where "Allow all the time" lives.
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(
                                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }) {
                Text(buttonLabel)
            }

            if (hasForeground && !hasBackground) {
                Text(
                    "Background access is required so triggers work while AutoMate is closed.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
