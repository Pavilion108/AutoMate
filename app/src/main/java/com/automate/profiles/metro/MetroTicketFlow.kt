package com.automate.profiles.metro

import android.content.Context
import android.util.Log
import com.automate.engine.AutoMateAccessibilityService
import com.automate.engine.VariableStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetroTicketFlow @Inject constructor(
    @ApplicationContext private val context: Context,
    private val variableStore: VariableStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    companion object {
        private const val TAG = "MetroTicketFlow"
        private const val MAX_POLL_ATTEMPTS = 15
        private const val POLL_INTERVAL_MS = 1000L
    }

    fun start() {
        job?.cancel()
        job = scope.launch {
            Log.i(TAG, "Starting Metro Ticket booking flow")
            runCatching { variableStore.setVariable("metro_booking_active", "true", "BOOLEAN") }

            val service = AutoMateAccessibilityService.instance
            if (service == null) {
                Log.e(TAG, "Accessibility service not running")
                sendNotification("Error", "Accessibility service not running")
                return@launch
            }

            val botNumber = runCatching { variableStore.getStringVariable("metro_bot_number") }.getOrDefault(MetroTicketProfile.DEFAULT_BOT_NUMBER)
            val source = runCatching { variableStore.getStringVariable("metro_source_station") }.getOrDefault(MetroTicketProfile.DEFAULT_SOURCE)
            val destination = runCatching { variableStore.getStringVariable("metro_dest_station") }.getOrDefault(MetroTicketProfile.DEFAULT_DESTINATION)
            val tripType = runCatching { variableStore.getStringVariable("metro_trip_type") }.getOrDefault(MetroTicketProfile.DEFAULT_TRIP_TYPE)
            val message = runCatching { variableStore.getStringVariable("metro_initial_message") }.getOrDefault(MetroTicketProfile.DEFAULT_INITIAL_MESSAGE)

            Log.i(TAG, "Config: bot=$botNumber, from=$source, to=$destination, type=$tripType")

            try {
                val success = executeBookingFlow(service, botNumber, source, destination, tripType, message)
                if (success) {
                    Log.i(TAG, "Metro ticket booked successfully!")
                    sendNotification("Metro Ticket Booked", "Your eTicket is ready!")
                } else {
                    Log.w(TAG, "Metro ticket booking failed")
                    sendNotification("Booking Failed", "Could not complete metro ticket booking")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Metro ticket flow error", e)
                sendNotification("Booking Error", e.message ?: "Unknown error")
            } finally {
                runCatching { variableStore.setVariable("metro_booking_active", "false", "BOOLEAN") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun executeBookingFlow(
        service: AutoMateAccessibilityService,
        botNumber: String,
        source: String,
        destination: String,
        tripType: String,
        message: String
    ): Boolean {

        // === STEP 1: Open WhatsApp ===
        sendNotification("Booking Metro Ticket", "Opening WhatsApp...")
        if (!launchWhatsApp(service)) return false
        delay(1500)

        // === STEP 2: Search for bot by phone number ===
        sendNotification("Booking Metro Ticket", "Finding metro bot...")
        if (!searchBotChat(service, botNumber)) return false
        delay(1000)

        // === STEP 3: Send initial message ===
        sendNotification("Booking Metro Ticket", "Sending message to bot...")
        if (!sendInitialMessage(service, message)) return false
        delay(1500)

        // === STEP 4: Wait for bot reply and click first "Book Now" ===
        sendNotification("Booking Metro Ticket", "Waiting for bot response...")
        if (!waitForTextAndClick(service, "Book Now", "Book now", "BOOK NOW")) return false
        delay(2000)

        // === STEP 5: Bot sends new message with "Book Now" link — click it ===
        sendNotification("Booking Metro Ticket", "Opening booking form...")
        if (!waitForTextAndClick(service, "Book Now", "Book now", "BOOK NOW")) return false
        delay(3000)

        // === STEP 6: WebView opens — fill source station ===
        sendNotification("Booking Metro Ticket", "Filling station details...")
        if (!fillStationField(service, "source", source)) return false
        delay(1000)

        // === STEP 7: Fill destination station ===
        if (!fillStationField(service, "destination", destination)) return false
        delay(1000)

        // === STEP 8: Select trip type (Single/Return) ===
        if (!selectTripType(service, tripType)) return false
        delay(1000)

        // === STEP 9: Click Proceed ===
        sendNotification("Booking Metro Ticket", "Proceeding to payment...")
        if (!waitForTextAndClick(service, "Proceed", "PROCEED")) return false
        delay(2000)

        // === STEP 10: Click Pay Online ===
        if (!waitForTextAndClick(service, "Pay Online", "Pay online", "PAY ONLINE")) return false
        delay(1500)

        // === STEP 11: Click UPI Payments ===
        if (!waitForTextAndClick(service, "UPI", "UPI Payment", "UPI Payments")) return false
        delay(1500)

        // === STEP 12: Click Continue ===
        if (!waitForTextAndClick(service, "Continue", "CONTINUE")) return false
        delay(2000)

        // === STEP 13: Wait for "Pay now" and click ===
        sendNotification("Booking Metro Ticket", "Waiting for payment option...")
        if (!waitForTextAndClick(service, "Pay now", "Pay Now", "PAY NOW")) return false
        delay(2000)

        // === STEP 14: Find "Pay with GooglePay" / "Select payment method" and navigate to UPI Lite ===
        sendNotification("Booking Metro Ticket", "Selecting UPI Lite payment...")
        if (!selectPaymentMethod(service)) return false
        delay(3000)

        // === STEP 15: GPay opens — notify user to complete payment ===
        sendNotification("Booking Metro Ticket", "GPay opened — complete your payment, then come back to AutoMate")
        waitForUserPayment(service)
        delay(2000)

        // === STEP 16: GPay auto-closes, back in WhatsApp — find "View eTicket" ===
        sendNotification("Booking Metro Ticket", "Fetching eTicket...")
        if (!waitForTextAndClick(service, "View eTicket", "View ticket", "eTicket", "View")) return false
        delay(2000)

        sendNotification("Metro Ticket Booked", "Your eTicket is ready! Tap to view.")
        return true
    }

    // === STEP 15 helper: Wait for user to complete GPay payment ===
    private suspend fun waitForUserPayment(service: AutoMateAccessibilityService) {
        // Poll until we're back in WhatsApp (GPay closed)
        for (i in 1..60) { // up to 60 seconds
            val screenText = service.getScreenText()
            // Check if we're back in WhatsApp
            if (screenText.contains("WhatsApp", ignoreCase = true) ||
                screenText.contains("View eTicket", ignoreCase = true) ||
                screenText.contains("eTicket", ignoreCase = true) ||
                screenText.contains("View ticket", ignoreCase = true)) {
                Log.i(TAG, "Back in WhatsApp after payment")
                return
            }
            // Still in GPay or payment screen
            if (i % 5 == 0) {
                sendNotification("Waiting for payment", "Complete payment in GPay (${i}s)...")
            }
            delay(1000)
        }
        Log.w(TAG, "Timeout waiting for user payment, continuing anyway")
    }

    // === STEP 1: Launch WhatsApp ===
    private suspend fun launchWhatsApp(service: AutoMateAccessibilityService): Boolean {
        try {
            // Force stop for clean state
            Runtime.getRuntime().exec(arrayOf("am", "force-stop", MetroTicketProfile.WHATSAPP_PACKAGE)).waitFor()
            delay(500)

            service.performGlobalHome()
            delay(500)

            Runtime.getRuntime().exec(arrayOf(
                "am", "start", "-n", "${MetroTicketProfile.WHATSAPP_PACKAGE}/.Main"
            )).waitFor()
            Log.i(TAG, "WhatsApp launched")
            delay(1500)

            // Verify WhatsApp is open
            for (i in 1..5) {
                val screenText = service.getScreenText()
                if (screenText.contains("WhatsApp", ignoreCase = true) ||
                    screenText.contains("Chats", ignoreCase = true) ||
                    screenText.contains("Search", ignoreCase = true)) {
                    Log.i(TAG, "WhatsApp confirmed open")
                    return true
                }
                delay(1000)
            }

            Log.w(TAG, "WhatsApp not detected after launch")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp", e)
            return false
        }
    }

    // === STEP 2: Search for bot chat by phone number ===
    private suspend fun searchBotChat(service: AutoMateAccessibilityService, botNumber: String): Boolean {
        // Tap search icon
        val searchNode = service.findNodeByText("Search")
            ?: service.findNodeByText("Search…")
            ?: service.findNodeByTextContentDescription("Search")
        if (searchNode != null) {
            service.performClick(searchNode)
            delay(1000)
        } else {
            // Try finding the search icon (magnifying glass)
            Log.w(TAG, "Search text not found, trying icon")
            val searchIcon = service.findNodeByTextContentDescription("Search")
            if (searchIcon != null) {
                service.performClick(searchIcon)
                delay(1000)
            } else {
                Log.w(TAG, "Cannot find search in WhatsApp")
                return false
            }
        }

        // Type the phone number
        val searchField = service.findNodeByText("Search…")
            ?: service.findNodeByText("Search")
            ?: service.findNodeByTextContentDescription("Search")
        if (searchField != null) {
            service.setText(searchField, botNumber)
            delay(2000)
        } else {
            // Fallback: type directly
            delay(500)
        }

        // Find and tap the chat result that contains the number
        val chatResult = service.findNodeByText(botNumber)
            ?: service.findNodeByText(botNumber.removePrefix("+"))
            ?: service.findNodeByText(botNumber.removePrefix("+91"))
        if (chatResult != null) {
            service.performClick(chatResult)
            delay(1500)
            Log.i(TAG, "Bot chat opened")
            return true
        }

        Log.w(TAG, "Bot chat not found for number: $botNumber")
        return false
    }

    // === STEP 3: Send initial message ===
    private suspend fun sendInitialMessage(service: AutoMateAccessibilityService, message: String): Boolean {
        // Find message input field
        val messageField = service.findNodeByText("Type a message")
            ?: service.findNodeByText("Message")
            ?: service.findNodeByText("message")
            ?: service.findNodeByTextContentDescription("Type a message")

        if (messageField != null) {
            service.setText(messageField, message)
            delay(500)

            // Find and tap send button
            val sendButton = service.findNodeByTextContentDescription("Send")
                ?: service.findNodeByText("Send")
            if (sendButton != null) {
                service.performClick(sendButton)
                delay(1000)
                Log.i(TAG, "Message sent: $message")
                return true
            } else {
                // Try the send icon (paper plane) — often has no text, try coordinates
                Log.w(TAG, "Send button not found by text, trying back button approach")
                // Press enter key as fallback
                service.performGlobalBack()
                delay(500)
            }
        }

        Log.w(TAG, "Could not send message")
        return false
    }

    // === STEP 4-5: Wait for text and click ===
    private suspend fun waitForTextAndClick(
        service: AutoMateAccessibilityService,
        vararg targets: String
    ): Boolean {
        for (attempt in 1..MAX_POLL_ATTEMPTS) {
            val screenText = service.getScreenText()

            for (target in targets) {
                if (screenText.contains(target, ignoreCase = true)) {
                    val node = service.findNodeByText(target)
                        ?: service.findNodeByTextContentDescription(target)
                    if (node != null) {
                        Log.i(TAG, "Found '$target' at attempt $attempt, clicking...")
                        service.performClick(node)
                        delay(1000)
                        return true
                    }
                }
            }

            Log.d(TAG, "Poll $attempt/$MAX_POLL_ATTEMPTS - waiting for: ${targets.joinToString()}")
            delay(POLL_INTERVAL_MS)
        }

        Log.w(TAG, "Text not found after $MAX_POLL_ATTEMPTS attempts: ${targets.joinToString()}")
        return false
    }

    // === STEP 6-7: Fill station field ===
    private suspend fun fillStationField(
        service: AutoMateAccessibilityService,
        fieldType: String,
        stationName: String
    ): Boolean {
        // Look for field labels — these could be "Source Station", "Destination", "From", "To", etc.
        val fieldLabels = when (fieldType) {
            "source" -> listOf("Source", "From", "Boarding", "source", "from")
            "destination" -> listOf("Destination", "To", "Alighting", "destination", "to")
            else -> listOf(fieldType)
        }

        for (label in fieldLabels) {
            // Try finding the label and clicking the next input field
            val labelNode = service.findNodeByText(label)
            if (labelNode != null) {
                // Click on or near the label to focus the input field
                service.performClick(labelNode)
                delay(500)

                // Now type the station name
                // The focused field should accept text input
                val screenText = service.getScreenText()
                if (screenText.contains(label, ignoreCase = true)) {
                    // Find the input field (usually an EditText)
                    val inputField = service.findNodeByText(label)
                    if (inputField != null) {
                        service.setText(inputField, stationName)
                        delay(1000)

                        // If dropdown appears, select the first match
                        val matchNode = service.findNodeByText(stationName)
                        if (matchNode != null && matchNode != inputField) {
                            service.performClick(matchNode)
                            delay(500)
                        }
                        Log.i(TAG, "Filled $fieldType: $stationName")
                        return true
                    }
                }
            }
        }

        // Fallback: try to find any editable text field and type
        Log.w(TAG, "Could not find $fieldType field by label, trying generic approach")
        val screenText = service.getScreenText()
        Log.d(TAG, "Screen text: ${screenText.take(500)}")
        return false
    }

    // === STEP 8: Select trip type ===
    private suspend fun selectTripType(service: AutoMateAccessibilityService, tripType: String): Boolean {
        val node = service.findNodeByText(tripType)
            ?: service.findNodeByText(tripType.lowercase())
            ?: service.findNodeByText(tripType.uppercase())
        if (node != null) {
            service.performClick(node)
            delay(500)
            Log.i(TAG, "Selected trip type: $tripType")
            return true
        }

        Log.w(TAG, "Trip type not found: $tripType")
        return false
    }

    // === STEP 14: Select payment method (UPI Lite) ===
    private suspend fun selectPaymentMethod(service: AutoMateAccessibilityService): Boolean {
        // First, look for "Select payment method" or "Pay with GooglePay"
        var screenText = service.getScreenText()

        // If "Pay with GooglePay" is visible, click it first
        if (screenText.contains("GooglePay", ignoreCase = true) ||
            screenText.contains("Google Pay", ignoreCase = true)) {
            val gpayNode = service.findNodeByText("Pay with GooglePay")
                ?: service.findNodeByText("GooglePay")
                ?: service.findNodeByText("Google Pay")
                ?: service.findNodeByTextContentDescription("GooglePay")
            if (gpayNode != null) {
                service.performClick(gpayNode)
                delay(2000)
            }
        }

        // Now look for "Select payment method"
        screenText = service.getScreenText()
        if (screenText.contains("Select payment method", ignoreCase = true) ||
            screenText.contains("Payment method", ignoreCase = true)) {

            // Look for UPI Lite directly
            val upiLiteNode = service.findNodeByText("UPI Lite")
                ?: service.findNodeByText("upi lite")
            if (upiLiteNode != null) {
                service.performClick(upiLiteNode)
                delay(1000)
                Log.i(TAG, "Selected UPI Lite directly")
                return true
            }

            // If not visible, tap below "Select payment method" or look for expand icon
            val selectMethodNode = service.findNodeByText("Select payment method")
                ?: service.findNodeByText("Payment method")
            if (selectMethodNode != null) {
                // Tap below the text (expand the dropdown)
                val bounds = android.graphics.Rect()
                selectMethodNode.getBoundsInScreen(bounds)
                // Tap below the center of the text
                service.tapAtCoordinates(bounds.centerX(), bounds.bottom + 50)
                delay(1000)

                // Now look for UPI Lite
                val upiLiteNode2 = service.findNodeByText("UPI Lite")
                    ?: service.findNodeByText("upi lite")
                if (upiLiteNode2 != null) {
                    service.performClick(upiLiteNode2)
                    delay(1000)
                    Log.i(TAG, "Selected UPI Lite from dropdown")
                    return true
                }

                // Try typing "v" to scroll to UPI Lite (as user suggested)
                val searchField = service.findNodeByText("Search")
                    ?: service.findNodeByText("Search…")
                if (searchField != null) {
                    service.setText(searchField, "v")
                    delay(500)
                    val upiLiteNode3 = service.findNodeByText("UPI Lite")
                    if (upiLiteNode3 != null) {
                        service.performClick(upiLiteNode3)
                        delay(1000)
                        Log.i(TAG, "Selected UPI Lite after search")
                        return true
                    }
                }
            }
        }

        // Fallback: just look for UPI Lite anywhere
        val fallbackNode = service.findNodeByText("UPI Lite")
        if (fallbackNode != null) {
            service.performClick(fallbackNode)
            delay(1000)
            Log.i(TAG, "Selected UPI Lite (fallback)")
            return true
        }

        Log.w(TAG, "Could not find UPI Lite payment method")
        return false
    }

    // === STEP 15: Perform GPay payment ===
    private suspend fun performGPayPayment(service: AutoMateAccessibilityService): Boolean {
        // Wait for GPay to open
        for (i in 1..10) {
            val screenText = service.getScreenText()
            if (screenText.contains("Pay", ignoreCase = true) &&
                (screenText.contains("₹", ignoreCase = true) || screenText.contains("Rs", ignoreCase = true))) {
                break
            }
            delay(1000)
        }

        // Find and click "Pay" button
        for (attempt in 1..10) {
            val screenText = service.getScreenText()
            Log.i(TAG, "GPay screen (attempt $attempt): ${screenText.take(200)}")

            // Look for the Pay button — it usually shows "Pay ₹XX" or just "Pay"
            val payNode = service.findNodeByText("Pay")
                ?: service.findNodeByText("PAY")
                ?: service.findNodeByTextContentDescription("Pay")
            if (payNode != null) {
                // Make sure it's a button, not just text
                val bounds = android.graphics.Rect()
                payNode.getBoundsInScreen(bounds)
                // Click it
                service.performClick(payNode)
                delay(1000)
                Log.i(TAG, "Clicked Pay in GPay")
                return true
            }

            delay(1000)
        }

        Log.w(TAG, "Could not find Pay button in GPay")
        return false
    }

    private fun sendNotification(title: String, text: String) {
        try {
            val intent = android.content.Intent(context, com.automate.MainActivity::class.java)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = android.app.Notification.Builder(context, com.automate.AutoMateApp.CHANNEL_TASK_STATUS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(android.app.Notification.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send notification: $title - $text", e)
        }
    }
}
