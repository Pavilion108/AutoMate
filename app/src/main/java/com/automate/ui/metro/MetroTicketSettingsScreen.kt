package com.automate.ui.metro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroTicketSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBooking: () -> Unit,
    viewModel: MetroTicketSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Metro Ticket") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            // Bot Number
            Text("Metro Bot Number", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = uiState.botNumber,
                onValueChange = { viewModel.setBotNumber(it) },
                label = { Text("Phone number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                "The WhatsApp bot number for metro ticket booking",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Source Station
            Text("Source Station", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = uiState.sourceStation,
                onValueChange = { viewModel.setSourceStation(it) },
                label = { Text("Station name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Destination Station
            Text("Destination Station", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = uiState.destinationStation,
                onValueChange = { viewModel.setDestinationStation(it) },
                label = { Text("Station name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()

            // Trip Type
            Text("Trip Type", fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.tripType == "Single",
                    onClick = { viewModel.setTripType("Single") },
                    label = { Text("Single") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = uiState.tripType == "Return",
                    onClick = { viewModel.setTripType("Return") },
                    label = { Text("Return") },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider()

            // Initial Message
            Text("Initial Message", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = uiState.initialMessage,
                onValueChange = { viewModel.setInitialMessage(it) },
                label = { Text("Message to send") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status
            if (uiState.isBookingActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Booking in progress...")
                    }
                }
            }

            // Book Now Button
            Button(
                onClick = onNavigateToBooking,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isBookingActive
            ) {
                Text("Book Metro Ticket", style = MaterialTheme.typography.titleMedium)
            }

            Text(
                "This will open WhatsApp and book a ${uiState.tripType.lowercase()} ticket from ${uiState.sourceStation} to ${uiState.destinationStation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
