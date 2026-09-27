package com.automate.engine

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.app.KeyguardManager
import android.app.ActivityManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
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
        // Deliberately does not start KeepAliveService. The accessibility service is
        // always bound by the OS, so starting GPS from here would run location tracking
        // on every non-office day. The service is started by TaskRunner.armForToday() and
        // stopped again on disarm.
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
    /**
     * The root node to inspect for [packageName].
     *
     * Reading only `rootInActiveWindow` is what made the automation look "blind": a
     * notification shade, a runtime permission dialog or any system window taking focus
     * made every lookup return null and the flow give up even though the target app was
     * fully rendered behind it. Scanning the window list for the target package keeps the
     * flow working whenever the app is visible at all.
     */
    fun rootForPackage(packageName: String): AccessibilityNodeInfo? {
        try {
            windows.forEach { w ->
                val root = w.root ?: return@forEach
                if (root.packageName?.toString() == packageName) return root
            }
        } catch (_: Exception) {
        }
        val active = rootInActiveWindow
        return if (active?.packageName?.toString() == packageName) active else null
    }

    fun findActionableNode(
        text: String,
        packageName: String? = null,
        exact: Boolean = true,
        excludeContaining: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val root: AccessibilityNodeInfo = if (packageName != null) {
            rootForPackage(packageName) ?: return null
        } else {
            rootInActiveWindow ?: return null
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
            score += r.width().toLong() * r.height().toLong()
            score
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

    private var stealthOverlay: View? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Attaches a 1x1 fully transparent overlay on the main thread and returns once it is
     * actually attached.
     *
     * Android 10+ refuses background activity starts ("Abort background activity starts")
     * unless the calling app is visible. A window the app actually owns satisfies that
     * visibility check, so attaching this around startActivity() is what makes the launch
     * stick. MIUI rejects the launch even for a bound AccessibilityService, and a
     * full-screen intent is only honoured while the screen is idle, so neither alone works.
     *
     * WindowManager.addView must run on a thread with a prepared Looper; callers are on a
     * coroutine dispatcher, so this hops to the main thread and waits for the result.
     */
    private fun attachStealthOverlay(): Boolean {
        if (stealthOverlay != null) return true
        val wm = try {
            getSystemService(WindowManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return false

        val latch = java.util.concurrent.CountDownLatch(1)
        var attached = false
        mainHandler.post {
            try {
                val view = View(this)
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply { alpha = 0.01f }
                wm.addView(view, params)
                stealthOverlay = view
                attached = true
            } catch (e: Exception) {
                Log.w(TAG, "stealth overlay attach failed: ${e.message}")
            }
            latch.countDown()
        }
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return attached
    }

    private fun detachStealthOverlay() {
        val view = stealthOverlay ?: return
        stealthOverlay = null
        mainHandler.post {
            try {
                getSystemService(WindowManager::class.java)?.removeView(view)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Keeps the overlay until [packageName] really owns the screen, then removes it.
     *
     * Tearing the window down on a fixed 1.5s timer raced the activity transition and
     * repeatedly wedged MIUI's SystemUI with the notification shade stuck open. Holding
     * the window until the switch has actually happened removes that race.
     */
    fun releaseStealthOverlayWhenSettled(packageName: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPackageLive(packageName)) break
            Thread.sleep(300)
        }
        mainHandler.postDelayed({ detachStealthOverlay() }, 500)
    }

    /**
     * Wakes the screen and clears a non-secure keyguard.
     *
     * A geofence fires while the phone is in a pocket, so the screen is asleep and the
     * keyguard is up. In that state `rootInActiveWindow` is the lock screen, every
     * accessibility read comes back empty and no target app can be brought forward —
     * the automation silently fails. A secure (PIN/pattern) lockscreen cannot be
     * dismissed this way by design; the caller gets false and reports it.
     */
    /**
     * Turns the screen on and gets a non-secure keyguard out of the way.
     *
     * A geofence fires while the phone is in a pocket, so the screen is asleep and the
     * keyguard is up. In that state `rootInActiveWindow` is the lock screen, every
     * accessibility read comes back empty and no target app can be brought forward.
     *
     * Accessibility has no wake/unlock global action, and `requestDismissKeyguard()`
     * requires an Activity, so this launches a transparent [WakeActivity] that carries
     * the platform flags and finishes immediately. It is started with the same transient
     * overlay used for normal launches, so the background-activity-start check passes.
     * A PIN/pattern lockscreen cannot be cleared this way, by design.
     */
    fun wakeAndUnlock(): Boolean {
        val overlaid = attachStealthOverlay()
        var started = false
        try {
            val intent = Intent(this, WakeActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            startActivity(intent)
            started = true
        } catch (e: Exception) {
            Log.w(TAG, "wakeAndUnlock failed to start WakeActivity: ${e.message}")
        }
        Log.i(TAG, "wakeAndUnlock started=$started overlay=$overlaid")
        mainHandler.postDelayed({ detachStealthOverlay() }, 3000)
        return started
    }

    /**
     * Dismisses a system overlay that is covering the target app.
     *
     * The MIUI notification shade can end up focused over a fully rendered app, which
     * makes every accessibility read come back empty. Pushing it away is far better than
     * declaring the target app missing.
     */
    fun dismissCoveringOverlay(): Boolean {
        val covering = try {
            val active = rootInActiveWindow?.packageName?.toString()
            active == "com.android.systemui"
        } catch (_: Exception) {
            false
        }
        if (!covering) return false
        return try {
            val done = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            Log.i(TAG, "dismissCoveringOverlay shade=$done")
            done
        } catch (e: Exception) {
            Log.w(TAG, "dismissCoveringOverlay failed: ${e.message}")
            false
        }
    }

    /**
     * Starts an app from the accessibility service context.
     *
     * Android 10+ blocks background activity launches: an app that is not in the
     * foreground gets "Abort background activity starts from <uid>" and the target app
     * never appears, which is exactly the reported "app closes and nothing opens"
     * symptom. An AccessibilityService is one of the few components exempt from that
     * restriction when acting on the user's behalf, so launching from here works.
     */
    fun launchPackage(packageName: String, activityClass: String? = null): Boolean {
        val pm = packageManager

        val intent = if (activityClass != null) {
            Intent().setComponent(ComponentName(packageName, activityClass))
        } else {
            pm.getLaunchIntentForPackage(packageName)
        } ?: run {
            Log.w(TAG, "No launch intent for $packageName")
            return false
        }

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        return try {
            // Own a window first so the system treats us as visible and allows the start.
            val overlaid = attachStealthOverlay()
            startActivity(intent)
            Log.i(TAG, "launchPackage($packageName) overlay=$overlaid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchPackage($packageName) failed", e)
            detachStealthOverlay()
            false
        }
    }

    /**
     * True when [packageName] owns the focused window. Screen text alone is not enough:
     * MIUI's launcher exposes plenty of text, so without this check "Beehive not detected"
     * would poll a home screen and eventually give up.
     */
    /**
     * True when the display is off or the keyguard is up, i.e. when accessibility reads
     * are meaningless.
     *
     * Checking only `isInteractive` is not enough: the display can be lit while the
     * lockscreen is still showing, which is exactly the state a geofence arrival leaves
     * behind. In that state every node lookup returns the lock screen and the flow stalls
     * with no error, so both conditions have to be covered.
     */
    fun needsWakeOrUnlock(): Boolean = try {
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        val displayOff = pm == null || !pm.isInteractive
        val locked = km?.isKeyguardLocked == true
        displayOff || locked
    } catch (e: Exception) {
        true
    }

    fun isPackageForeground(packageName: String): Boolean {        val root = rootInActiveWindow
        if (root?.packageName?.toString() == packageName) return true
        return try {
            windows.any { it.root?.packageName?.toString() == packageName }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Window lists lag behind a cold start, so a freshly launched app can own the screen
     * for seconds before accessibility reports it. [ActivityManager]'s importance for the
     * target process is an independent signal and covers that window. The package name is
     * logged because a mismatch here means we are tapping blind.
     */
    fun isPackageLive(packageName: String): Boolean {
        if (isPackageForeground(packageName)) return true
        return try {
            val am = getSystemService(ActivityManager::class.java) ?: return false
            val targetUid = packageManager.getApplicationInfo(packageName, 0).uid
            am.runningAppProcesses?.any {
                it.uid == targetUid &&
                    (it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                        it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE)
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "isPackageLive($packageName) failed: ${e.message}")
            false
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

    /** Screen text belonging to [packageName], ignoring any system window in front. */
    fun getScreenTextFor(packageName: String): String {
        val root = rootForPackage(packageName) ?: return ""
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

        /**
         * Whether [packageName] currently owns a window, checked without needing a bound
         * service instance. Used while waiting for a full-screen intent to land.
         */
        fun isPackageForegroundStatic(packageName: String): Boolean {
            val svc = instance ?: return false
            return svc.isPackageLive(packageName)
        }

        /** Window package names currently visible, for diagnostics. */
        fun visiblePackages(): List<String> {
            val svc = instance ?: return emptyList()
            val names = mutableListOf<String>()
            try {
                svc.rootInActiveWindow?.packageName?.toString()?.let { names.add("active:$it") }
                svc.windows.forEach { w ->
                    w.root?.packageName?.toString()?.let { names.add(it) }
                }
            } catch (_: Exception) {
            }
            return names.distinct()
        }
    }
}
