package com.automate.engine

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import com.automate.domain.model.Action
import com.automate.domain.model.ActionType
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

class AutoMateAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val eventListeners = CopyOnWriteArrayList<EventListener>()

    var isRunning = false
        private set

    var currentRootNode: AccessibilityNodeInfo?
        get() = rootInActiveWindow
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEYEvents
            notificationTimeout = 100
            packageNames = null // Listen to all apps
        }

        instance = this
        Log.i(TAG, "AutoMate Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        eventListeners.forEach { it.onEvent(event) }
    }

    override fun onInterrupt() {
        Log.i(TAG, "AutoMate Accessibility Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        instance = null
        scope.cancel()
        Log.i(TAG, "AutoMate Accessibility Service unbound")
        return super.onUnbind(intent)
    }

    fun addEventListener(listener: EventListener) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: EventListener) {
        eventListeners.remove(listener)
    }

    // === UI Element Finding ===

    fun findNodeByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeByTextRecursive(root, text, exact)
    }

    private fun findNodeByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val match = if (exact) nodeText == text else nodeText.contains(text, ignoreCase = true)
        if (match) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTextRecursive(child, text, exact)
            if (result != null) return result
        }
        return null
    }

    fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        return nodes.firstOrNull()
    }

    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text, results)
        return results
    }

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = node.text?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true)) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByTextRecursive(child, text, results)
        }
    }

    fun findClickableNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findClickableNodesRecursive(root, results)
        return results
    }

    private fun findClickableNodesRecursive(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isClickable) results.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableNodesRecursive(child, results)
        }
    }

    fun findNodeByTextContentDescription(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findByContentDescription(root, text)
    }

    private fun findByContentDescription(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains(text, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByContentDescription(child, text)
            if (result != null) return result
        }
        return null
    }

    // === Actions ===

    fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        return false
    }

    fun performLongClick(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performScrollForward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun performScrollBackward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun tapAtCoordinates(x: Int, y: Int) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 500) {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun performGlobalBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performGlobalHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performGlobalRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    // === Smart Element Finding with Fallback ===

    fun smartFindElement(action: Action): AccessibilityNodeInfo? {
        // Strategy 1: Find by resource ID
        if (action.targetId.isNotEmpty()) {
            val node = findNodeById(action.targetId)
            if (node != null) return node
        }

        // Strategy 2: Find by text
        if (action.target.isNotEmpty()) {
            val node = findNodeByText(action.target)
            if (node != null) return node
        }

        // Strategy 3: Find by content description
        if (action.target.isNotEmpty()) {
            val node = findNodeByTextContentDescription(action.target)
            if (node != null) return node
        }

        return null
    }

    // === Wait for Element ===

    suspend fun waitForElement(
        target: String,
        targetId: String = "",
        timeoutMs: Long = 10000,
        pollIntervalMs: Long = 500
    ): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val action = Action(type = ActionType.CLICK_ELEMENT, target = target, targetId = targetId)
            val node = smartFindElement(action)
            if (node != null) return node
            delay(pollIntervalMs)
        }
        return null
    }

    // === Execute Action ===

    suspend fun executeAction(action: Action): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                when (action.type) {
                    ActionType.CLICK_ELEMENT -> {
                        val node = smartFindElement(action)
                        if (node != null) {
                            performClick(node)
                        } else {
                            Log.w(TAG, "Element not found: ${action.target}")
                            false
                        }
                    }

                    ActionType.CLICK_COORDINATES -> {
                        val coords = action.coordinates
                        if (coords != null) {
                            tapAtCoordinates(coords.first, coords.second)
                            true
                        } else {
                            false
                        }
                    }

                    ActionType.TYPE_TEXT -> {
                        val node = smartFindElement(action)
                        if (node != null) {
                            setText(node, action.text)
                        } else {
                            false
                        }
                    }

                    ActionType.WAIT -> {
                        delay(action.seconds * 1000L)
                        true
                    }

                    ActionType.SWIPE -> {
                        val start = action.swipeStart
                        val end = action.swipeEnd
                        if (start != null && end != null) {
                            performSwipe(start.first, start.second, end.first, end.second, action.swipeDurationMs)
                            true
                        } else {
                            false
                        }
                    }

                    ActionType.GLOBAL_ACTION -> {
                        when (action.globalActionType) {
                            "back" -> { performGlobalBack(); true }
                            "home" -> { performGlobalHome(); true }
                            "recents" -> { performGlobalRecents(); true }
                            else -> false
                        }
                    }

                    ActionType.LAUNCH_APP,
                    ActionType.KILL_APP,
                    ActionType.SHOW_NOTIFICATION,
                    ActionType.SHOW_DIALOG,
                    ActionType.SET_VARIABLE,
                    ActionType.CHECK_VARIABLE,
                    ActionType.REFRESH_LOCATION,
                    ActionType.POPUP_HANDLER,
                    ActionType.SCHEDULE_TIME_OUT -> {
                        // These are handled by ActionExecutor, not the accessibility service
                        true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing action: ${action.type}", e)
                false
            }
        }
    }

    // === Popup Detection ===

    fun detectPopup(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        // Common popup indicators
        val popupTexts = listOf(
            "OK", "Ok", "ok", "CANCEL", "Cancel",
            "ALLOW", "Allow", "DENY", "Deny",
            "CLOSE", "Close", "GOT IT", "Got it",
            "DISMISS", "Dismiss", "YES", "NO",
            "Location", "location", "GPS", "gps",
            "Update", "update", "Error", "error",
            "Permission", "permission"
        )

        for (text in popupTexts) {
            val node = findNodeByTextRecursive(root, text, false)
            if (node != null && node.isClickable) return node
        }

        return null
    }

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        extractText(root, sb)
        return sb.toString()
    }

    private fun extractText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            sb.appendLine(text)
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) {
            sb.appendLine(desc)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractText(child, sb)
        }
    }

    interface EventListener {
        fun onEvent(event: AccessibilityEvent)
    }

    companion object {
        private const val TAG = "AutoMateAccessibility"
        var instance: AutoMateAccessibilityService? = null
            private set

        fun requireInstance(): AutoMateAccessibilityService {
            return instance ?: throw IllegalStateException(
                "Accessibility Service not running. Enable it in Settings > Accessibility > AutoMate."
            )
        }
    }
}
