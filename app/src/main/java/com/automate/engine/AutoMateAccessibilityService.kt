package com.automate.engine

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
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

    val currentRootNode: AccessibilityNodeInfo?
        get() = rootInActiveWindow

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            packageNames = null // Listen to all apps
        }

        instance = this
        KeepAliveService.start(this)
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
        Log.w(TAG, "AutoMate Accessibility Service unbound! Requesting rebind...")
        KeepAliveService.rebindAccessibility(this)
        return true // Return true to receive onRebind
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        isRunning = true
        instance = this
        Log.i(TAG, "AutoMate Accessibility Service rebound")
    }

    fun addEventListener(listener: EventListener) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: EventListener) {
        eventListeners.remove(listener)
    }

    // === UI Element Finding (scoped to package) ===

    fun findNodeByText(text: String, exact: Boolean = false, packageName: String? = null): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (packageName != null && root.packageName?.toString() != packageName) {
            return null
        }
        return findNodeByTextRecursive(root, text, exact)
    }

    fun findNodeByTextInApp(text: String, packageName: String, exact: Boolean = false): AccessibilityNodeInfo? {
        // Check if the target app is in the foreground
        val root = rootInActiveWindow ?: return null
        val rootPkg = root.packageName?.toString() ?: ""
        // NativeScript apps: root might show as the WebView package, check window list
        if (rootPkg != packageName) {
            // Try to find the window for the target package
            val windows = windows
            for (i in 0 until windows.size) {
                val windowRoot = windows[i].root ?: continue
                if (windowRoot.packageName?.toString() == packageName) {
                    return findNodeByTextRecursive(windowRoot, text, exact)
                }
            }
            return null
        }
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

    /**
     * Finds the best actionable node for [text].
     *
     * A plain text match is not enough: Beehive's tree contains labels and stubs such as
     * "HOME" with bounds [0,0][0,0] and zero-height rows. Clicking those silently does
     * nothing, which used to burn every retry and then relaunch the app. So we require the
     * node to be visible, non-degenerate, and prefer a clickable ancestor.
     */
    fun findActionableNode(
        text: String,
        packageName: String? = null,
        exact: Boolean = true,
        excludeContaining: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (packageName != null && root.packageName?.toString() != packageName) {
            return null
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionable(root, text, exact, excludeContaining, candidates, 0)

        if (candidates.isEmpty()) return null

        // Prefer: clickable > enabled > on-screen > larger area (more likely the real control)
        return candidates.maxByOrNull { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            var score = 0L
            if (node.isClickable) score += 1_000_000_000L
            if (node.isEnabled) score += 100_000_000L
            score += r.width().toLong() * r.height()
        }
    }

    private fun collectActionable(
        node: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
        excludeContaining: List<String>,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 40) return

        val nodeText = node.text?.toString() ?: ""
        val match = if (exact) {
            nodeText.trim().equals(text, ignoreCase = true)
        } else {
            nodeText.contains(text, ignoreCase = true)
        }

        if (match && !excludeContaining.any { nodeText.contains(it, ignoreCase = true) }) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val visible = node.isVisibleToUser && r.width() > 0 && r.height() > 0
            if (visible) {
                out.add(node)
                // A clickable ancestor is the real tap target; stop descending.
                if (node.isClickable) return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectActionable(child, text, exact, excludeContaining, out, depth + 1)
            }
        }
    }

    /** True when the node is on screen with a usable tap area. */
    fun isTappable(node: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return node.isVisibleToUser && r.width() > 0 && r.height() > 0
    }

    /** Clicks a node, walking up to a clickable ancestor, then falling back to a tap. */
    fun clickNodeRobustly(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 5) {
            if (current.isVisibleToUser && current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            current = current.parent
            hops++
        }
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            tapAtCoordinates(r.centerX(), r.centerY())
            return true
        }
        return false
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

        /**
         * Two distinct states matter and must not be conflated:
         *  - ENABLED: the user (or watchdog) turned the service on in the OS
         *  - BOUND:   the framework has called onServiceConnected() and we can drive the UI
         * Callers that need to tap the screen should check [isBound], not [isEnabled].
         */
        fun isEnabled(context: Context): Boolean {
            val serviceComponent = ComponentName(context, AutoMateAccessibilityService::class.java)

            val enabledServices = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (_: SecurityException) {
                null
            } ?: ""

            // Exact match per entry. A plain contains() would report a false positive for
            // sibling components such as "...AutoMateAccessibilityServiceExtra".
            if (enabledServices.split(':').any { entry ->
                    entry.equals(serviceComponent.flattenToShortString(), ignoreCase = true) ||
                        entry.equals(serviceComponent.flattenToString(), ignoreCase = true)
                }
            ) return true

            return try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager
                am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
                ).any { info ->
                    info.resolveInfo?.serviceInfo?.let { si ->
                        si.packageName == context.packageName &&
                            si.name == serviceComponent.className
                    } ?: false
                }
            } catch (_: Exception) {
                false
            }
        }

        /** True once the OS has actually bound us, meaning UI automation can run. */
        val isBound: Boolean get() = instance != null
    }
}
