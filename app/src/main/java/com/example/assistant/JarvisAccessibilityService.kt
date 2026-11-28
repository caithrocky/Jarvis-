package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.PowerManager
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.JarvisApplication
import com.example.security.SecureCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class ScreenActionResult(
    val success: Boolean,
    val message: String,
    val targetRect: Rect? = null
)

data class ScreenFlowGuidance(
    val stepKind: String,
    val summary: String,
    val spokenGuidance: String
)

data class IndexedUiNode(
    val index: Int,
    val label: String,
    val role: String,
    val resourceId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean
)

data class ContactCandidateMatch(
    val index: Int,
    val displayName: String,
    val subText: String,
    val bounds: Rect
)

class JarvisAccessibilityService : AccessibilityService() {

    private var overlayManager: JarvisVisualOverlayManager? = null
    private val serviceScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            AccessibilityDiagnostics.coroutineExceptionHandler("JarvisAccessibilityService")
    )

    @Volatile
    private var lastIndexedNodes: List<IndexedUiNode> = emptyList()

    @Volatile
    private var lastEventProbeMs: Long = 0L

    @Volatile
    private var continuousScrollJob: Job? = null

    override fun onCreate() {
        try {
            super.onCreate()
            AccessibilityDiagnostics.installUncaughtExceptionReporter(applicationContext ?: this)
            AccessibilityDiagnostics.onServiceCreate(applicationContext ?: this)
        } catch (t: Throwable) {
            AccessibilityDiagnostics.markMalfunction(applicationContext ?: this, "onCreate", t)
        }
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            val appCtx = applicationContext ?: this
            AccessibilityDiagnostics.installUncaughtExceptionReporter(appCtx)
            AccessibilityDiagnostics.onServiceConnecting(appCtx)

            instance = this
            _isConnected.value = true

            runCatching {
                overlayManager?.detach()
                overlayManager = JarvisVisualOverlayManager(this).apply {
                    showTapEnabled = desiredShowTap
                    showHighlightEnabled = desiredShowHighlight
                    showScrollEnabled = desiredShowScroll
                }
            }.onFailure { err ->
                AccessibilityDiagnostics.recordNonFatalError("overlayManager.init", err, appCtx)
            }

            // Ensure declared retrieval/gesture flags stay applied safely on all OEM builds
            val info = runCatching { serviceInfo }.onFailure { err ->
                AccessibilityDiagnostics.recordNonFatalError("readServiceInfo", err, appCtx)
            }.getOrNull()

            AccessibilityDiagnostics.onServiceConnected(appCtx, info)
            runHealthProbe()

            // Load only visual indicator preferences cleanly; voice foreground service is managed separately
            serviceScope.launch(Dispatchers.IO) {
                runCatching {
                    val app = applicationContext as? JarvisApplication ?: return@launch
                    val settings = app.preferencesRepository.settingsFlow.first()
                    withContext(Dispatchers.Main.immediate) {
                        applyVisualFeedbackSettings(
                            showTap = settings.showTapIndicator,
                            showHighlight = settings.showHighlightIndicator,
                            showScroll = settings.showScrollIndicator
                        )
                    }
                }.onFailure { err ->
                    AccessibilityDiagnostics.recordNonFatalError("loadVisualPrefs", err, appCtx)
                }
            }
        } catch (t: Throwable) {
            AccessibilityDiagnostics.markMalfunction(applicationContext ?: this, "onServiceConnected", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val eventPkg = runCatching { event.packageName?.toString()?.trim().orEmpty() }.getOrDefault("")
            if (eventPkg.isNotBlank() && eventPkg != packageName) {
                _activePackageName.value = eventPkg
            }
            AccessibilityDiagnostics.onAccessibilityEvent(event.eventType, eventPkg)

            val health = AccessibilityDiagnostics.healthState.value
            if (health != AccessibilityHealthState.OPERATIONAL) {
                val now = System.currentTimeMillis()
                if (now - lastEventProbeMs >= 1500L) {
                    lastEventProbeMs = now
                    val hasWindow = runCatching { rootInActiveWindow != null || !windows.isNullOrEmpty() }
                        .getOrDefault(false)
                    if (hasWindow) {
                        AccessibilityDiagnostics.onWindowCheckResult(
                            applicationContext ?: this,
                            success = true,
                            detail = "Active event window verified (${eventPkg.ifBlank { "system" }})"
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError(
                "onAccessibilityEvent",
                t,
                applicationContext ?: this
            )
        }
    }

    override fun onInterrupt() {
        try {
            stopFreeRoamScroll()
            AccessibilityDiagnostics.onServiceInterrupt()
            runCatching { overlayManager?.clearOverlays() }
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("onInterrupt", t, applicationContext ?: this)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            stopFreeRoamScroll()
            runCatching {
                overlayManager?.detach()
                overlayManager = null
            }.onFailure { err ->
                AccessibilityDiagnostics.recordNonFatalError("onUnbind.detachOverlay", err)
            }
            lastIndexedNodes = emptyList()
            if (instance === this) {
                instance = null
            }
            _isConnected.value = false
            AccessibilityDiagnostics.onServiceUnbind(applicationContext ?: this)
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("onUnbind", t, applicationContext ?: this)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            stopFreeRoamScroll()
            runCatching {
                overlayManager?.detach()
                overlayManager = null
            }.onFailure { err ->
                AccessibilityDiagnostics.recordNonFatalError("onDestroy.detachOverlay", err)
            }
            lastIndexedNodes = emptyList()
            runCatching { serviceScope.cancel() }
            if (instance === this) {
                instance = null
            }
            _isConnected.value = false
            AccessibilityDiagnostics.onServiceDestroy(applicationContext ?: this)
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("onDestroy", t)
        }
        super.onDestroy()
    }

    /**
     * Verifies real accessibility capability by probing serviceInfo flags and non-throwing
     * window content availability, updating [AccessibilityDiagnostics] to OPERATIONAL only
     * when live window access succeeds.
     */
    fun runHealthProbe(): Boolean {
        val appCtx = applicationContext ?: this
        return try {
            val info = runCatching { serviceInfo }.getOrNull()
            val caps = info?.capabilities ?: 0
            val canRetrieve = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
            val rootAvailable = runCatching { rootInActiveWindow != null }.getOrDefault(false)
            val winCount = runCatching { windows?.size ?: 0 }.getOrDefault(0)
            if (canRetrieve && (rootAvailable || winCount > 0)) {
                AccessibilityDiagnostics.onWindowCheckResult(
                    appCtx,
                    success = true,
                    detail = "Probe OK (root=$rootAvailable, windows=$winCount)"
                )
                true
            } else {
                AccessibilityDiagnostics.onWindowCheckResult(
                    appCtx,
                    success = false,
                    detail = "Probe awaiting active window (canRetrieve=$canRetrieve, root=$rootAvailable, windows=$winCount)"
                )
                false
            }
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("runHealthProbe", t, appCtx)
            false
        }
    }

    fun applyVisualFeedbackSettings(showTap: Boolean, showHighlight: Boolean, showScroll: Boolean) {
        desiredShowTap = showTap
        desiredShowHighlight = showHighlight
        desiredShowScroll = showScroll
        runCatching {
            overlayManager?.apply {
                showTapEnabled = showTap
                showHighlightEnabled = showHighlight
                showScrollEnabled = showScroll
                if (!showTap && !showHighlight && !showScroll) {
                    clearOverlays()
                }
            }
        }.onFailure { err ->
            AccessibilityDiagnostics.recordNonFatalError("applyVisualFeedbackSettings", err, applicationContext ?: this)
        }
    }

    fun showHighlightsOnScreen(targets: List<HighlightTarget>, durationMs: Long = 4000L) {
        runCatching {
            overlayManager?.showHighlightBoxes(targets, durationMs)
        }.onFailure { err ->
            AccessibilityDiagnostics.recordNonFatalError("showHighlightsOnScreen", err)
        }
    }

    fun clearVisualOverlays() {
        stopFreeRoamScroll()
        runCatching {
            overlayManager?.clearOverlays()
        }.onFailure { err ->
            AccessibilityDiagnostics.recordNonFatalError("clearVisualOverlays", err)
        }
    }

    fun stopFreeRoamScroll() {
        continuousScrollJob?.cancel()
        continuousScrollJob = null
    }

    private fun activeAppRootWindow(): AccessibilityNodeInfo? {
        val appCtx = applicationContext ?: this
        val currentRoot = try {
            rootInActiveWindow
        } catch (e: DeadObjectException) {
            AccessibilityDiagnostics.recordNonFatalError("rootInActiveWindow", e, appCtx)
            null
        } catch (e: IllegalStateException) {
            AccessibilityDiagnostics.recordNonFatalError("rootInActiveWindow", e, appCtx)
            null
        } catch (e: SecurityException) {
            AccessibilityDiagnostics.recordNonFatalError("rootInActiveWindow", e, appCtx)
            null
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("rootInActiveWindow", t, appCtx)
            null
        }

        val currentPkg = runCatching { currentRoot?.packageName?.toString() }.getOrNull()
        if (currentRoot != null && currentPkg != packageName) {
            AccessibilityDiagnostics.onWindowCheckResult(
                appCtx,
                success = true,
                detail = "rootInActiveWindow (${currentPkg ?: "unknown"})"
            )
            return currentRoot
        }

        val appWindowRoot = try {
            windows
                ?.filter {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        runCatching { it.root?.packageName?.toString() }.getOrNull() != packageName
                }
                ?.maxByOrNull { it.layer }
                ?.root
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("windows.activeApp", t, appCtx)
            null
        }

        val chosen = appWindowRoot ?: currentRoot
        if (chosen != null) {
            val pkg = runCatching { chosen.packageName?.toString() }.getOrNull() ?: "unknown"
            AccessibilityDiagnostics.onWindowCheckResult(appCtx, success = true, detail = "window ($pkg)")
        } else {
            AccessibilityDiagnostics.onWindowCheckResult(
                appCtx,
                success = false,
                detail = "Both rootInActiveWindow and TYPE_APPLICATION windows returned null"
            )
        }
        return chosen
    }

    private fun allInteractiveRoots(
        includeInputMethod: Boolean = false,
        includeSystem: Boolean = false
    ): List<AccessibilityNodeInfo> {
        val appCtx = applicationContext ?: this
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows
                ?.sortedByDescending { it.layer }
                ?.forEach { win ->
                    val root = runCatching { win.root }.getOrNull() ?: return@forEach
                    val pkg = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
                    if (pkg == packageName) return@forEach
                    val allowedType = when (win.type) {
                        AccessibilityWindowInfo.TYPE_APPLICATION -> true
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> includeInputMethod
                        AccessibilityWindowInfo.TYPE_SYSTEM -> includeSystem
                        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> false
                        else -> false
                    }
                    if (allowedType) roots.add(root)
                }
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("allInteractiveRoots", t, appCtx)
        }
        if (roots.isEmpty()) {
            activeAppRootWindow()?.let { roots.add(it) }
        }
        return roots
    }

    private fun inputMethodWindowRoot(): AccessibilityNodeInfo? {
        return try {
            windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.maxByOrNull { it.layer }
                ?.root
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("inputMethodWindowRoot", t, applicationContext ?: this)
            null
        }
    }

    fun activeForegroundPackage(): String? =
        runCatching { activeAppRootWindow()?.packageName?.toString() }.getOrNull() ?: _activePackageName.value

    fun hasFormFieldsOnScreen(): Boolean {
        return runSafeOp("hasFormFieldsOnScreen", default = false) {
            val root = activeAppRootWindow() ?: return@runSafeOp false
            if (safePackageName(root) == packageName) return@runSafeOp false
            var found = false
            traverseNodes(root) { node ->
                if (!found && safeIsEditable(node) && safeIsVisible(node) && !isBrowserAddressBar(node)) {
                    found = true
                }
            }
            found
        }
    }

    fun readScreenSummary(): String {
        return runSafeOp("readScreenSummary", default = "Unable to inspect the active screen safely right now, Sir.") {
            val root = activeAppRootWindow()
                ?: return@runSafeOp "No active screen window is currently readable, Sir."
            val pkg = safePackageName(root)
            val appLabel = pkg.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    val info = packageManager.getApplicationInfo(it, 0)
                    packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(it)
            } ?: "Current screen"

            val visibleTexts = mutableListOf<String>()
            val clickableLabels = mutableListOf<String>()
            val editableHints = mutableListOf<String>()

            traverseNodes(root) { node ->
                val rawText = safeText(node)
                val desc = safeContentDesc(node)
                val hint = safeHintText(node)

                if (safeIsEditable(node)) {
                    val fieldLabel = hint.ifBlank { desc }.ifBlank {
                        safeResName(node).replace('_', ' ')
                    }.ifBlank { "text input" }
                    editableHints.add(fieldLabel)
                } else {
                    val label = if (safeIsPassword(node)) "" else rawText.ifBlank { desc }
                    if (label.isNotBlank()) {
                        if (safeIsClickable(node) && clickableLabels.size < 14 && label.length <= 50) {
                            clickableLabels.add(label)
                        } else if (visibleTexts.size < 18 && label.length in 2..110) {
                            visibleTexts.add(label)
                        }
                    }
                }
            }

            val sb = StringBuilder("Active screen: $appLabel ($pkg).")
            if (editableHints.isNotEmpty()) {
                sb.append(" Input fields: ${editableHints.distinct().take(6).joinToString(", ")}.")
            }
            if (clickableLabels.isNotEmpty()) {
                sb.append(" Buttons/links: ${clickableLabels.distinct().take(12).joinToString(", ")}.")
            }
            if (visibleTexts.isNotEmpty()) {
                sb.append(" Visible text: ${visibleTexts.distinct().take(12).joinToString(" | ")}.")
            }
            sb.toString()
        }
    }

    /**
     * Reads a numbered UI element map with exact coordinates for autonomous reasoning and target highlighting.
     */
    fun readRichScreenMap(maxNodes: Int = 36): Pair<String, List<IndexedUiNode>> {
        return runSafeOp("readRichScreenMap", default = "Screen read unavailable." to emptyList()) {
            val root = activeAppRootWindow()
                ?: return@runSafeOp "No active screen window." to emptyList()
            val pkg = safePackageName(root)
            val appLabel = pkg.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
                }.getOrDefault(it)
            } ?: "Current App"

            val nodes = mutableListOf<IndexedUiNode>()
            val seenKeys = mutableSetOf<String>()

            traverseNodes(root) { node ->
                if (nodes.size >= maxNodes || !safeIsVisible(node)) return@traverseNodes
                val clickable = safeIsClickable(node)
                val editable = safeIsEditable(node)
                val scrollable = safeIsScrollable(node)
                val rawText = if (safeIsPassword(node)) "" else safeText(node)
                val desc = safeContentDesc(node)
                val interesting = clickable || editable || scrollable || rawText.isNotBlank() || desc.isNotBlank()
                if (!interesting) return@traverseNodes

                val rect = safeBounds(node)
                if (rect.width() <= 6 || rect.height() <= 6) return@traverseNodes

                val hint = safeHintText(node)
                val resId = safeResName(node)
                val label = rawText.ifBlank { desc }.ifBlank { hint }.ifBlank { resId.replace('_', ' ') }.trim()
                if (label.isBlank() && !editable && !clickable) return@traverseNodes

                val dedupKey = "${label.lowercase(Locale.US)}|${rect.centerX() / 24}|${rect.centerY() / 24}|$editable"
                if (!seenKeys.add(dedupKey)) return@traverseNodes

                val role = when {
                    editable -> "input"
                    clickable -> "button"
                    scrollable -> "list"
                    else -> "text"
                }
                nodes.add(
                    IndexedUiNode(
                        index = nodes.size + 1,
                        label = label.ifBlank { role },
                        role = role,
                        resourceId = resId,
                        bounds = rect,
                        isClickable = clickable,
                        isEditable = editable,
                        isScrollable = scrollable
                    )
                )
            }

            lastIndexedNodes = nodes
            val formatted = buildString {
                appendLine("Active App: $appLabel ($pkg)")
                nodes.forEach { n ->
                    val flags = buildList {
                        if (n.isEditable) add("editable")
                        if (n.isClickable) add("clickable")
                        if (n.isScrollable) add("scrollable")
                    }.joinToString(",")
                    appendLine("[#${n.index}] ${n.role}: \"${n.label.take(48)}\" ($flags) id=${n.resourceId}")
                }
            }
            formatted to nodes
        }
    }

    fun clickIndexedNode(index: Int): ScreenActionResult {
        return runSafeAction("clickIndexedNode") {
            val node = lastIndexedNodes.firstOrNull { it.index == index }
                ?: return@runSafeAction ScreenActionResult(false, "Control #$index is no longer visible, Sir.")
            tapBoundsCenter(node.bounds, node.label)
        }
    }

    fun tapBoundsCenter(rect: Rect, label: String = ""): ScreenActionResult {
        return runSafeAction("tapBoundsCenter") {
            if (rect.width() <= 0 || rect.height() <= 0) {
                return@runSafeAction ScreenActionResult(false, "Target bounds are invalid, Sir.")
            }
            val cx = rect.centerX().toFloat().coerceAtLeast(1f)
            val cy = rect.centerY().toFloat().coerceAtLeast(1f)

            runCatching {
                overlayManager?.showHighlightBoxes(
                    listOf(HighlightTarget(rect = rect, label = label, accentColor = Color.rgb(0, 229, 255))),
                    durationMs = 1500L
                )
                overlayManager?.showTapRipple(cx, cy, label)
            }

            // First prefer semantic accessibility node click if a matching clickable node is current
            val root = activeAppRootWindow()
            if (root != null) {
                var clickedNode = false
                traverseNodes(root) { node ->
                    if (!clickedNode && safeIsClickable(node) && safeIsVisible(node)) {
                        val nr = safeBounds(node)
                        if (absDiff(nr.centerX(), rect.centerX()) <= 24 && absDiff(nr.centerY(), rect.centerY()) <= 24) {
                            clickedNode = safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
                }
                if (clickedNode) {
                    AccessibilityDiagnostics.onWindowCheckResult(
                        applicationContext ?: this,
                        success = true,
                        detail = "Semantic ACTION_CLICK succeeded"
                    )
                    return@runSafeAction ScreenActionResult(true, "Tapped \"${label.ifBlank { "item" }}\", Sir.", rect)
                }
            }

            val path = Path().apply { moveTo(cx, cy) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = safeDispatchGesture(gesture, "tapBoundsCenter")
            if (ok) {
                ScreenActionResult(true, "Tapped \"${label.ifBlank { "item" }}\", Sir.", rect)
            } else {
                ScreenActionResult(false, "Unable to tap \"${label.ifBlank { "item" }}\" at ($cx, $cy), Sir.")
            }
        }
    }

    fun hasMessageComposeBox(): Boolean {
        return runSafeOp("hasMessageComposeBox", default = false) {
            val root = activeAppRootWindow() ?: return@runSafeOp false
            val dm = resources.displayMetrics
            var found = false
            traverseNodes(root) { node ->
                if (!found && safeIsEditable(node) && safeIsVisible(node) && !isBrowserAddressBar(node)) {
                    val sig = nodeSignature(node)
                    val r = safeBounds(node)
                    val looksLikeSearch = sig.contains("search") || sig.contains("find") || sig.contains("meta ai")
                    val looksLikeCompose = sig.contains("message") || sig.contains("chat") || sig.contains("entry") ||
                        sig.contains("reply") || r.top > dm.heightPixels * 0.45f
                    if (!looksLikeSearch && looksLikeCompose) {
                        found = true
                    }
                }
            }
            found
        }
    }

    /**
     * Prepares messaging/social apps before searching for a contact (e.g., opens Instagram DMs,
     * switches Snapchat to Chat tab, or exits a previously open chat on WhatsApp/Telegram).
     */
    fun navigateMessagingAppToSearchArea(platform: String): Boolean {
        return runSafeOp("navigateMessagingAppToSearchArea", default = false) {
            val root = activeAppRootWindow() ?: return@runSafeOp false
            val pkgLower = safePackageName(root).lowercase(Locale.US)
            val platLower = platform.lowercase(Locale.US)

            val composeOpen = hasMessageComposeBox()
            val hasOpenSearchEdit = run {
                var found = false
                traverseNodes(root) { n ->
                    if (!found && safeIsEditable(n) && safeIsVisible(n) && nodeSignature(n).contains("search")) {
                        found = true
                    }
                }
                found
            }
            if (hasOpenSearchEdit) return@runSafeOp false

            if (platLower.contains("instagram") || pkgLower.contains("instagram")) {
                val dmIcon = findClickableNodeByKeywords(
                    root,
                    listOf("action_bar_inbox_button", "direct_tab", "direct", "messenger", "messages", "inbox")
                )
                if (dmIcon != null && !composeOpen) {
                    val r = safeBounds(dmIcon)
                    if (r.top < resources.displayMetrics.heightPixels * 0.22f) {
                        tapBoundsCenter(r, "Instagram DMs")
                        return@runSafeOp true
                    }
                }
            }

            if (platLower.contains("snapchat") || pkgLower.contains("snapchat")) {
                val chatTab = findClickableNodeByKeywords(
                    root,
                    listOf("chat_tab", "chat", "messages", "friends")
                )
                if (chatTab != null && !composeOpen) {
                    val r = safeBounds(chatTab)
                    if (r.bottom > resources.displayMetrics.heightPixels * 0.78f) {
                        tapBoundsCenter(r, "Snapchat Chat")
                        return@runSafeOp true
                    }
                }
            }

            if (composeOpen) {
                runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
                return@runSafeOp true
            }
            false
        }
    }

    /**
     * Locates an in-app Search bar or Search icon button, opens it if needed, and enters the query.
     * Strictly avoids tapping Meta AI / AI Assistant buttons on WhatsApp/Instagram.
     */
    fun findAndTapSearchAndType(queryText: String): ScreenActionResult {
        return runSafeAction("findAndTapSearchAndType") {
            val root = activeAppRootWindow()
                ?: return@runSafeAction ScreenActionResult(false, "I cannot see the active app window right now, Sir.")

            val editables = mutableListOf<AccessibilityNodeInfo>()
            val dedicatedSearchTriggers = mutableListOf<AccessibilityNodeInfo>()
            val newChatTriggers = mutableListOf<AccessibilityNodeInfo>()
            val fallbackSearchTriggers = mutableListOf<AccessibilityNodeInfo>()

            traverseNodes(root) { node ->
                if (!safeIsVisible(node)) return@traverseNodes
                val sig = nodeSignature(node)
                val isMetaAiOnly = (sig.contains("meta ai") || sig.contains("meta_ai") || sig.contains("ai studio") || sig.contains("ask meta")) &&
                    !sig.contains("search")
                if (isMetaAiOnly) return@traverseNodes

                if (safeIsEditable(node) && !isBrowserAddressBar(node)) {
                    editables.add(node)
                } else if (safeIsClickable(node) || safeText(node).isNotBlank() || safeContentDesc(node).isNotBlank()) {
                    when {
                        sig.contains("menuitem_search") ||
                            sig.contains("search_button") ||
                            sig.contains("search_btn") ||
                            sig.contains("chat_search") ||
                            sig.contains("search_bar") ||
                            sig.contains("action_search") ||
                            sig.contains("search_src_text") -> dedicatedSearchTriggers.add(node)
                        sig.contains("new chat") || sig.contains("new_chat") || sig.contains("start chat") || sig.contains("fab") ->
                            newChatTriggers.add(node)
                        sig.contains("search") && !sig.contains("meta ai") && !sig.contains("ask meta") ->
                            dedicatedSearchTriggers.add(node)
                        sig.contains("search") ->
                            fallbackSearchTriggers.add(node)
                    }
                }
            }

            val activeSearchEdit = editables.firstOrNull {
                val s = nodeSignature(it)
                (s.contains("search") || s.contains("filter") || s.contains("find") || s.contains("to")) &&
                    !s.contains("meta ai")
            }
            if (activeSearchEdit != null) {
                val rect = safeBounds(activeSearchEdit)
                runCatching {
                    overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = "Search")), 2000L)
                    overlayManager?.showTapRipple(rect.centerX().toFloat(), rect.centerY().toFloat(), "Search")
                }
                val ok = writeTextIntoNode(activeSearchEdit, queryText)
                return@runSafeAction ScreenActionResult(
                    ok,
                    if (ok) "Searching for $queryText, Sir." else "Could not type into search box.",
                    rect
                )
            }

            val pureTrigger = dedicatedSearchTriggers.firstOrNull { findClickableAncestorBounds(it) != null || safeIsClickable(it) }
            if (pureTrigger != null) {
                val rect = findClickableAncestorBounds(pureTrigger) ?: safeBounds(pureTrigger)
                tapBoundsCenter(rect, "Search")
                return@runSafeAction ScreenActionResult(false, "Opened search bar, waiting to type query.", rect)
            }

            val newChatTrigger = newChatTriggers.firstOrNull { findClickableAncestorBounds(it) != null || safeIsClickable(it) }
            if (newChatTrigger != null) {
                val rect = findClickableAncestorBounds(newChatTrigger) ?: safeBounds(newChatTrigger)
                tapBoundsCenter(rect, "New Chat")
                return@runSafeAction ScreenActionResult(false, "Opened New Chat contact picker, waiting for search.", rect)
            }

            val compoundBar = fallbackSearchTriggers.firstOrNull { findClickableAncestorBounds(it) != null || safeIsClickable(it) }
            if (compoundBar != null) {
                val fullRect = findClickableAncestorBounds(compoundBar) ?: safeBounds(compoundBar)
                val searchTapRect = Rect(
                    (fullRect.left + fullRect.width() * 0.52f).toInt(),
                    fullRect.top,
                    fullRect.right,
                    fullRect.bottom
                )
                tapBoundsCenter(searchTapRect, "Search")
                return@runSafeAction ScreenActionResult(false, "Opened search bar, waiting to type query.", searchTapRect)
            }

            val topEdit = editables.firstOrNull { node ->
                val r = safeBounds(node)
                r.top < resources.displayMetrics.heightPixels * 0.45f
            }
            if (topEdit != null) {
                val rect = safeBounds(topEdit)
                val ok = writeTextIntoNode(topEdit, queryText)
                return@runSafeAction ScreenActionResult(
                    ok,
                    if (ok) "Entered $queryText into search field, Sir." else "Failed typing $queryText.",
                    rect
                )
            }

            ScreenActionResult(false, "No search bar visible on this screen yet, Sir.")
        }
    }

    /**
     * Scans active screen rows matching the requested contact/user name for cross-platform messaging.
     */
    fun findContactCandidates(contactQuery: String): List<ContactCandidateMatch> {
        return runSafeOp("findContactCandidates", default = emptyList()) {
            val root = activeAppRootWindow() ?: return@runSafeOp emptyList()
            val queryLower = contactQuery.trim().lowercase(Locale.US)
            val tokens = queryLower.split(Regex("\\s+")).filter { it.length >= 2 }
            if (tokens.isEmpty()) return@runSafeOp emptyList()

            val scoredMatches = mutableListOf<Pair<Int, ContactCandidateMatch>>()
            val seenRows = mutableSetOf<Int>()
            val dm = resources.displayMetrics
            val topHeaderCutoff = (dm.heightPixels * 0.12f).toInt()

            traverseNodes(root) { node ->
                if (!safeIsVisible(node) || safeIsEditable(node)) return@traverseNodes
                val rawText = safeText(node)
                val desc = safeContentDesc(node)
                val candidateLabel = rawText.ifBlank { desc }
                if (candidateLabel.isBlank() || candidateLabel.length > 70) return@traverseNodes

                val lower = candidateLabel.lowercase(Locale.US)
                val focused = runCatching { node.isFocused }.getOrDefault(false)
                if (lower == "search" ||
                    lower == "new chat" ||
                    lower == "chats" ||
                    lower == "contacts" ||
                    lower.contains("ask meta ai") ||
                    (lower == queryLower && focused)
                ) {
                    return@traverseNodes
                }

                val exact = lower == queryLower
                val starts = lower.startsWith(queryLower)
                val allTokens = tokens.all { lower.contains(it) } || lower.contains(queryLower)
                if (!allTokens) return@traverseNodes

                val rect = safeBounds(node)
                if (rect.top < topHeaderCutoff && isInsideSearchArea(node)) return@traverseNodes
                if (rect.width() <= 10 || rect.height() <= 10) return@traverseNodes

                val clickableRow = findClickableAncestorBounds(node, maxDepth = 7)
                    ?: if (safeIsClickable(node)) rect else return@traverseNodes
                val rowBucket = clickableRow.centerY() / 44
                if (!seenRows.add(rowBucket)) return@traverseNodes

                val score = when {
                    exact -> 0
                    starts -> 1
                    else -> 2
                }
                scoredMatches.add(
                    score to ContactCandidateMatch(
                        index = 0,
                        displayName = candidateLabel,
                        subText = "",
                        bounds = clickableRow
                    )
                )
            }

            scoredMatches
                .sortedWith(compareBy<Pair<Int, ContactCandidateMatch>> { it.first }.thenBy { it.second.bounds.top })
                .take(6)
                .mapIndexed { idx, (_, m) -> m.copy(index = idx + 1) }
        }
    }

    fun findMessageComposeAndType(messageText: String): ScreenActionResult {
        return runSafeAction("findMessageComposeAndType") {
            val root = activeAppRootWindow()
                ?: return@runSafeAction ScreenActionResult(false, "No active chat window visible, Sir.")

            val dm = resources.displayMetrics
            val editables = mutableListOf<AccessibilityNodeInfo>()
            traverseNodes(root) { node ->
                if (safeIsEditable(node) && safeIsVisible(node) && !isBrowserAddressBar(node)) {
                    editables.add(node)
                }
            }

            val composeNode = editables.firstOrNull { node ->
                val sig = nodeSignature(node)
                !sig.contains("search") && (
                    sig.contains("message") || sig.contains("chat") || sig.contains("reply") ||
                        sig.contains("write") || sig.contains("entry") || sig.contains("caption")
                    )
            } ?: editables.lastOrNull { node ->
                val r = safeBounds(node)
                r.top > dm.heightPixels * 0.42f && !nodeSignature(node).contains("search")
            } ?: editables.lastOrNull()
                ?: return@runSafeAction ScreenActionResult(false, "No message box is open on this screen yet, Sir.")

            val rect = safeBounds(composeNode)
            runCatching {
                overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = "Message")), 2500L)
                overlayManager?.showTapRipple(rect.centerX().toFloat(), rect.centerY().toFloat(), "Compose")
            }
            val ok = writeTextIntoNode(composeNode, messageText)
            if (ok) {
                ScreenActionResult(true, "Typed message into chat box, Sir.", rect)
            } else {
                ScreenActionResult(false, "Couldn't write directly into this chat box, Sir.", rect)
            }
        }
    }

    fun findSendButtonBounds(): Rect? {
        return runSafeOp("findSendButtonBounds", default = null) {
            val root = activeAppRootWindow() ?: return@runSafeOp null
            val dm = resources.displayMetrics
            var bestRect: Rect? = null
            traverseNodes(root) { node ->
                if (safeIsClickable(node) && safeIsVisible(node)) {
                    val sig = nodeSignature(node)
                    val isSendSig = (sig.contains("send") || sig.contains("send_btn") || sig.contains("send_button")) &&
                        !sig.contains("send a chat") &&
                        !sig.contains("send photo") &&
                        !sig.contains("send video")
                    if (isSendSig) {
                        val r = safeBounds(node)
                        if (r.width() > 8 && r.height() > 8 && r.top > dm.heightPixels * 0.35f) {
                            if (bestRect == null || r.right > bestRect!!.right) {
                                bestRect = r
                            }
                        }
                    }
                }
            }
            bestRect
        }
    }

    fun highlightSendButton(): Rect? {
        val rect = findSendButtonBounds() ?: return null
        runCatching {
            overlayManager?.showHighlightBoxes(
                listOf(HighlightTarget(rect, badge = "CONFIRM", label = "Send", accentColor = Color.rgb(16, 185, 129))),
                durationMs = 8000L
            )
        }
        return rect
    }

    fun tapSendButton(): ScreenActionResult {
        val rect = findSendButtonBounds()
            ?: return clickNodeByText("Send")
        return tapBoundsCenter(rect, "Send")
    }

    /**
     * Universal social media navigation & engagement across TikTok, Instagram, X, Reddit, YouTube, Snapchat, Facebook, LinkedIn.
     */
    fun performSocialAction(action: String, targetArg: String = ""): ScreenActionResult {
        return runSafeAction("performSocialAction") {
            val lower = action.lowercase(Locale.US).trim()
            val root = activeAppRootWindow()
                ?: return@runSafeAction ScreenActionResult(false, "Open a social app screen first, Sir.")

            when (lower) {
                "like", "upvote", "love" -> {
                    val keywords = listOf("like", "heart", "love", "upvote", "thumbs up")
                    val nodeRect = findClickableByKeywords(root, keywords)
                    if (nodeRect != null) {
                        tapBoundsCenter(nodeRect, "Like")
                    } else {
                        val dm = resources.displayMetrics
                        val cx = dm.widthPixels * 0.50f
                        val cy = dm.heightPixels * 0.48f
                        runCatching { overlayManager?.showTapRipple(cx, cy, "Double-Tap Like") }
                        val p1 = Path().apply { moveTo(cx, cy) }
                        val p2 = Path().apply { moveTo(cx + 4f, cy - 2f) }
                        val gesture = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(p1, 0L, 45L))
                            .addStroke(GestureDescription.StrokeDescription(p2, 110L, 45L))
                            .build()
                        val ok = safeDispatchGesture(gesture, "social_double_tap_like")
                        ScreenActionResult(ok, if (ok) "Liked the current post with a double-tap, Sir." else "Unable to like this post, Sir.")
                    }
                }
                "repost", "retweet", "boost" -> {
                    val rect = findClickableByKeywords(root, listOf("repost", "retweet", "echo", "remix", "reshare"))
                    if (rect != null) tapBoundsCenter(rect, "Repost")
                    else ScreenActionResult(false, "I couldn't spot a Repost button on this screen, Sir. Ask me to read screen or share instead.")
                }
                "share" -> {
                    val rect = findClickableByKeywords(root, listOf("share", "send to", "forward"))
                    if (rect != null) tapBoundsCenter(rect, "Share")
                    else ScreenActionResult(false, "No Share button is visible right now, Sir.")
                }
                "save", "bookmark" -> {
                    val rect = findClickableByKeywords(root, listOf("save", "bookmark", "favorite", "add to favorites"))
                    if (rect != null) tapBoundsCenter(rect, "Save")
                    else ScreenActionResult(false, "No Save or Bookmark button is visible right now, Sir.")
                }
                "follow", "subscribe" -> {
                    val rect = findClickableByKeywords(root, listOf("follow", "subscribe"))
                    if (rect != null) tapBoundsCenter(rect, "Follow")
                    else ScreenActionResult(false, "No Follow or Subscribe button is visible right now, Sir.")
                }
                "comment", "comments" -> {
                    val rect = findClickableByKeywords(root, listOf("comment", "comments", "reply", "add a comment"))
                    if (rect != null) tapBoundsCenter(rect, "Comments")
                    else ScreenActionResult(false, "I couldn't find the Comments button on this screen, Sir.")
                }
                "next", "scroll_down", "scroll_next" -> scrollScreen(forward = true)
                "previous", "scroll_up" -> scrollScreen(forward = false)
                "swipe_left" -> swipeHorizontal(toLeft = true)
                "swipe_right" -> swipeHorizontal(toLeft = false)
                "dms", "messages", "inbox" -> {
                    val rect = findClickableByKeywords(root, listOf("messages", "direct", "chat", "chats", "inbox", "messenger"))
                    if (rect != null) tapBoundsCenter(rect, "Messages")
                    else ScreenActionResult(false, "I couldn't see a Direct Messages icon on this screen, Sir.")
                }
                "profile" -> {
                    val rect = findClickableByKeywords(root, listOf("profile", "account", "me"))
                    if (rect != null) tapBoundsCenter(rect, "Profile")
                    else ScreenActionResult(false, "No Profile tab found on this screen, Sir.")
                }
                "home_feed", "feed" -> {
                    val rect = findClickableByKeywords(root, listOf("home", "for you", "feed"))
                    if (rect != null) tapBoundsCenter(rect, "Home")
                    else ScreenActionResult(false, "No Home feed tab found on this screen, Sir.")
                }
                "reels" -> {
                    val rect = findClickableByKeywords(root, listOf("reels", "shorts", "video"))
                    if (rect != null) tapBoundsCenter(rect, "Reels")
                    else ScreenActionResult(false, "No Reels tab found on this screen, Sir.")
                }
                else -> {
                    if (targetArg.isNotBlank()) clickNodeByText(targetArg) else clickNodeByText(action)
                }
            }
        }
    }

    private fun findClickableNodeByKeywords(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        return runSafeOp("findClickableNodeByKeywords", default = null) {
            val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
            traverseNodes(root) { node ->
                if (!safeIsVisible(node)) return@traverseNodes
                val sig = nodeSignature(node)
                val matchedIndex = keywords.indexOfFirst { kw -> sig.contains(kw) }
                if (matchedIndex >= 0) {
                    candidates.add(node to matchedIndex)
                }
            }
            val best = candidates.minByOrNull { it.second }?.first ?: return@runSafeOp null
            var cur: AccessibilityNodeInfo? = best
            var depth = 0
            while (cur != null && depth < 6) {
                if (safeIsClickable(cur)) return@runSafeOp cur
                cur = safeParent(cur)
                depth++
            }
            best
        }
    }

    private fun findClickableByKeywords(root: AccessibilityNodeInfo, keywords: List<String>): Rect? {
        val best = findClickableNodeByKeywords(root, keywords) ?: return null
        return findClickableAncestorBounds(best) ?: safeBounds(best)
    }

    private fun findClickableAncestorBounds(node: AccessibilityNodeInfo, maxDepth: Int = 5): Rect? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < maxDepth) {
            if (safeIsClickable(cur)) {
                val r = safeBounds(cur)
                if (r.width() > 8 && r.height() > 8) return r
            }
            cur = safeParent(cur)
            depth++
        }
        return null
    }

    private fun isInsideSearchArea(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (nodeSignature(cur).contains("search")) return true
            cur = safeParent(cur)
            depth++
        }
        return false
    }

    fun analyzeCurrentFlowStep(): ScreenFlowGuidance {
        val summary = readScreenSummary()
        val lower = summary.lowercase(Locale.US)

        return when {
            lower.contains("passport") || lower.contains("national id") || lower.contains("driver") ||
                lower.contains("front of id") || lower.contains("back of id") || lower.contains("selfie") ||
                lower.contains("verify identity") || lower.contains("kyc") ||
                (lower.contains("upload") && (lower.contains("id") || lower.contains("document") || lower.contains("photo"))) ||
                lower.contains("take photo") || lower.contains("take a photo") -> {
                ScreenFlowGuidance(
                    stepKind = "ID_UPLOAD",
                    summary = summary,
                    spokenGuidance = "Step guidance, Sir: This screen is asking for identity verification or a document photo upload. Place your ID on a flat, well-lit surface with zero glare and keep all four corners inside the frame—or position your face in bright light for a selfie. Tap the Upload or Take Photo button (or tell me \"Open camera\" or \"Tap upload\"), review the preview clearly, and tell me when you are ready for the next step."
                )
            }
            lower.contains("verification code") || lower.contains("otp") || lower.contains("2fa") ||
                lower.contains("authenticator") || lower.contains("enter code") || lower.contains("check your email") -> {
                ScreenFlowGuidance(
                    stepKind = "OTP_VERIFICATION",
                    summary = summary,
                    spokenGuidance = "Step guidance, Sir: This page is asking for a verification code or two-factor confirmation. Check your email or SMS inbox for the latest code, or tell me the code and I can type it into the verification box for you."
                )
            }
            lower.contains("captcha") || lower.contains("not a robot") || lower.contains("human verification") -> {
                ScreenFlowGuidance(
                    stepKind = "CAPTCHA",
                    summary = summary,
                    spokenGuidance = "Step guidance, Sir: There is a human verification or CAPTCHA challenge on screen. Please complete that check manually, then tap Continue or tell me to proceed."
                )
            }
            lower.contains("input fields:") && (
                lower.contains("password") || lower.contains("email") || lower.contains("sign in") ||
                    lower.contains("log in") || lower.contains("sign up") || lower.contains("register") ||
                    lower.contains("create account")
                ) -> {
                val isSignUp = lower.contains("sign up") || lower.contains("register") || lower.contains("create account")
                val modeWord = if (isSignUp) "sign-up" else "sign-in"
                ScreenFlowGuidance(
                    stepKind = if (isSignUp) "SIGNUP_FORM" else "SIGNIN_FORM",
                    summary = summary,
                    spokenGuidance = "Step guidance, Sir: I see a $modeWord form on this page. Say \"Fill my credentials\" and I will populate the matching fields from your encrypted vault, then pause so you can verify everything before submitting."
                )
            }
            else -> {
                ScreenFlowGuidance(
                    stepKind = "GENERAL_SCREEN",
                    summary = summary,
                    spokenGuidance = summary
                )
            }
        }
    }

    fun typeIntoActiveOrMatchingField(text: String, fieldHint: String? = null): ScreenActionResult {
        return runSafeAction("typeIntoActiveOrMatchingField") {
            val root = activeAppRootWindow()
                ?: return@runSafeAction ScreenActionResult(false, "I cannot read the active screen right now, Sir.")

            val editables = mutableListOf<AccessibilityNodeInfo>()
            traverseNodes(root) { node ->
                if (safeIsEditable(node) && safeIsVisible(node)) {
                    editables.add(node)
                }
            }

            val hintLower = fieldHint?.trim()?.lowercase(Locale.US).orEmpty()
            val focusedInput = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                .getOrNull()?.takeIf { safeIsEditable(it) }

            val targetNode = when {
                hintLower.isNotBlank() -> {
                    editables.firstOrNull { nodeMatchesHint(it, hintLower) }
                        ?: focusedInput
                        ?: editables.firstOrNull()
                }
                else -> {
                    focusedInput
                        ?: editables.firstOrNull { runCatching { it.isFocused }.getOrDefault(false) }
                        ?: editables.firstOrNull()
                }
            } ?: return@runSafeAction ScreenActionResult(
                false,
                "No editable text box is currently open on screen, Sir. Tap a text field first or open a search bar."
            )

            val rect = safeBounds(targetNode)
            runCatching {
                overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = "Input")), 1800L)
                overlayManager?.showTapRipple(rect.centerX().toFloat(), rect.centerY().toFloat(), "Type")
            }

            val setOk = writeTextIntoNode(targetNode, text)
            if (setOk) {
                ScreenActionResult(true, "Entered the requested text into the active field, Sir.", rect)
            } else {
                ScreenActionResult(
                    false,
                    "I copied the text to your clipboard, Sir, as this input box restricts direct filling.",
                    rect
                )
            }
        }
    }

    fun fillFormFromVault(
        profile: SecureCredentialStore.SignupProfile,
        savedCredential: SecureCredentialStore.SavedCredentialEntry?,
        generatedPassword: String?,
        isSignInHint: Boolean
    ): ScreenActionResult {
        return runSafeAction("fillFormFromVault") {
            val root = activeAppRootWindow()
                ?: return@runSafeAction ScreenActionResult(false, "Open the website or app login/signup page first, Sir.")
            if (safePackageName(root) == packageName) {
                return@runSafeAction ScreenActionResult(
                    false,
                    "Open the website or app login page first, Sir, so I don't fill JARVIS's own screen."
                )
            }

            val editables = mutableListOf<AccessibilityNodeInfo>()
            traverseNodes(root) { node ->
                if (safeIsEditable(node) && safeIsVisible(node) && !isBrowserAddressBar(node)) {
                    editables.add(node)
                }
            }
            if (editables.isEmpty()) {
                return@runSafeAction ScreenActionResult(
                    false,
                    "I don't see any login or signup input boxes on this screen yet, Sir. Open a sign-in page (not just a new tab) or tap into the form and ask me again."
                )
            }

            val effectiveEmail = savedCredential?.usernameOrEmail?.takeIf { it.contains("@") }
                ?: profile.email.ifBlank { savedCredential?.usernameOrEmail.orEmpty() }
            val effectiveUser = savedCredential?.usernameOrEmail?.takeIf { !it.contains("@") }
                ?: profile.username.ifBlank { savedCredential?.usernameOrEmail.orEmpty() }
            val effectivePassword = savedCredential?.password?.takeIf { it.isNotEmpty() }
                ?: profile.defaultPassword.takeIf { it.isNotEmpty() }
                ?: if (!isSignInHint) generatedPassword else null

            val nameParts = profile.fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val firstName = nameParts.firstOrNull().orEmpty()
            val lastName = if (nameParts.size > 1) nameParts.drop(1).joinToString(" ") else ""

            val filled = mutableListOf<String>()
            val highlightedTargets = mutableListOf<HighlightTarget>()

            for (node in editables) {
                val sig = nodeSignature(node)
                val adjSig = if (sig.isBlank()) adjacentLabelSignature(node) else "$sig ${adjacentLabelSignature(node)}"
                val inputType = runCatching { node.inputType }.getOrDefault(0)
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                val inputClass = inputType and InputType.TYPE_MASK_CLASS
                val isPasswordByType = safeIsPassword(node) ||
                    (inputClass == InputType.TYPE_CLASS_TEXT && (
                        variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        ))
                val isEmailByType = inputClass == InputType.TYPE_CLASS_TEXT && (
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                    )
                val isPhoneByType = inputClass == InputType.TYPE_CLASS_PHONE

                val pair: Pair<String, String>? = when {
                    (isEmailByType || adjSig.contains("email") || adjSig.contains("e-mail")) && effectiveEmail.isNotBlank() ->
                        "Email" to effectiveEmail
                    isPasswordByType || adjSig.contains("password") || adjSig.contains("passcode") ->
                        if (!effectivePassword.isNullOrBlank()) "Password" to effectivePassword else null
                    (adjSig.contains("user") || adjSig.contains("handle") || adjSig.contains("nick") || adjSig.contains("login")) && effectiveUser.isNotBlank() ->
                        "Username/Login" to effectiveUser
                    (adjSig.contains("first") && adjSig.contains("name")) && firstName.isNotBlank() ->
                        "First Name" to firstName
                    ((adjSig.contains("last") && adjSig.contains("name")) || adjSig.contains("surname")) && lastName.isNotBlank() ->
                        "Last Name" to lastName
                    (adjSig.contains("name") && !adjSig.contains("user")) && profile.fullName.isNotBlank() ->
                        "Full Name" to profile.fullName
                    (isPhoneByType || adjSig.contains("phone") || adjSig.contains("mobile") || adjSig.contains("tel")) && profile.phoneNumber.isNotBlank() ->
                        "Phone Number" to profile.phoneNumber
                    (adjSig.contains("birth") || adjSig.contains("dob")) && profile.dateOfBirth.isNotBlank() ->
                        "Date of Birth" to profile.dateOfBirth
                    adjSig.contains("country") && profile.country.isNotBlank() ->
                        "Country" to profile.country
                    (adjSig.contains("address") || adjSig.contains("street")) && profile.addressLine.isNotBlank() ->
                        "Address" to profile.addressLine
                    adjSig.contains("city") && profile.city.isNotBlank() ->
                        "City" to profile.city
                    (adjSig.contains("postal") || adjSig.contains("zip")) && profile.postalCode.isNotBlank() ->
                        "Postal Code" to profile.postalCode
                    editables.size <= 2 && !isPasswordByType && (effectiveEmail.isNotBlank() || effectiveUser.isNotBlank()) ->
                        "Login" to (savedCredential?.usernameOrEmail?.ifBlank { effectiveEmail } ?: effectiveEmail.ifBlank { effectiveUser })
                    else -> null
                }

                if (pair != null && writeTextIntoNode(node, pair.second)) {
                    filled.add(pair.first)
                    val rect = safeBounds(node)
                    highlightedTargets.add(HighlightTarget(rect, label = pair.first, accentColor = Color.rgb(16, 185, 129)))
                }
            }

            if (highlightedTargets.isNotEmpty()) {
                runCatching { overlayManager?.showHighlightBoxes(highlightedTargets, 2600L) }
            }

            val flowCheck = analyzeCurrentFlowStep()
            val extraStepTip = if (flowCheck.stepKind == "ID_UPLOAD" || flowCheck.stepKind == "CAPTCHA") {
                " Note: ${flowCheck.spokenGuidance}"
            } else {
                ""
            }

            if (filled.isNotEmpty()) {
                val credSource = savedCredential?.let { " for ${it.serviceName}" }.orEmpty()
                ScreenActionResult(
                    true,
                    "With your permission, I filled ${filled.distinct().joinToString(", ")}$credSource on this page, Sir. Please review, complete any CAPTCHA or verification, and tap Submit when ready.$extraStepTip"
                )
            } else {
                ScreenActionResult(
                    false,
                    "I found ${editables.size} field(s), but none matched your saved details yet, Sir. Add your info under the Credentials tab or tell me specifically what to enter."
                )
            }
        }
    }

    private fun writeTextIntoNode(targetNode: AccessibilityNodeInfo, text: String): Boolean {
        return runSafeOp("writeTextIntoNode", default = false) {
            safePerformAction(targetNode, AccessibilityNodeInfo.ACTION_FOCUS)
            safePerformAction(targetNode, AccessibilityNodeInfo.ACTION_CLICK)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (safePerformAction(targetNode, AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return@runSafeOp true
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("JARVIS Input", text))
            safePerformAction(targetNode, AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    fun clickNodeByText(targetText: String): ScreenActionResult {
        return runSafeAction("clickNodeByText") {
            val roots = allInteractiveRoots(includeInputMethod = true, includeSystem = true)
            if (roots.isEmpty()) {
                return@runSafeAction ScreenActionResult(false, "I cannot read the active screen right now, Sir.")
            }
            val clean = targetText.trim()
            if (clean.isBlank()) return@runSafeAction ScreenActionResult(false, "Tell me which button, tab, or key to tap, Sir.")

            val strippedVariant = clean
                .replace(Regex("\\s+(?:button|tab|key)$", RegexOption.IGNORE_CASE), "")
                .trim()
            val queries = listOf(clean, strippedVariant).filter { it.isNotBlank() }.distinct()

            data class ScoredCandidate(val score: Int, val node: AccessibilityNodeInfo)
            val scored = mutableListOf<ScoredCandidate>()

            for (root in roots) {
                traverseNodes(root) { node ->
                    if (!safeIsVisible(node)) return@traverseNodes
                    val rawText = if (safeIsPassword(node)) "" else safeText(node)
                    val desc = safeContentDesc(node)
                    val hint = safeHintText(node)
                    val resName = safeResName(node).replace('_', ' ').trim()

                    val fields = listOf(rawText, desc, hint, resName).filter { it.isNotBlank() }
                    if (fields.isEmpty()) return@traverseNodes

                    var bestMatchScore = Int.MAX_VALUE
                    for (q in queries) {
                        val ql = q.lowercase(Locale.US)
                        for ((idx, f) in fields.withIndex()) {
                            val fl = f.lowercase(Locale.US)
                            val basePenalty = if (idx == 3) 4 else 0
                            val s = when {
                                fl == ql -> 0 + basePenalty
                                fl.startsWith(ql) -> 1 + basePenalty
                                Regex("\\b${Regex.escape(ql)}\\b").containsMatchIn(fl) -> 2 + basePenalty
                                fl.contains(ql) -> 3 + basePenalty
                                else -> Int.MAX_VALUE
                            }
                            if (s < bestMatchScore) bestMatchScore = s
                        }
                    }
                    if (bestMatchScore != Int.MAX_VALUE) {
                        val hasClickable = safeIsClickable(node) || findClickableAncestorBounds(node, maxDepth = 5) != null
                        val clickPenalty = if (hasClickable) 0 else 10
                        scored.add(ScoredCandidate(bestMatchScore + clickPenalty, node))
                    }
                }
            }

            val ordered = scored.sortedBy { it.score }
            for (entry in ordered) {
                val candidate = entry.node
                var current: AccessibilityNodeInfo? = candidate
                var depth = 0
                while (current != null && depth < 5) {
                    if (safeIsClickable(current)) {
                        val rect = safeBounds(current)
                        if (rect.width() > 4 && rect.height() > 4) {
                            runCatching {
                                overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = clean)), 1500L)
                                overlayManager?.showTapRipple(rect.centerX().toFloat(), rect.centerY().toFloat(), clean)
                            }
                            if (safePerformAction(current, AccessibilityNodeInfo.ACTION_CLICK)) {
                                return@runSafeAction ScreenActionResult(true, "Tapped \"$clean\", Sir.", rect)
                            }
                            return@runSafeAction tapBoundsCenter(rect, clean)
                        }
                    }
                    current = safeParent(current)
                    depth++
                }
                val bounds = safeBounds(candidate)
                if (bounds.width() > 6 && bounds.height() > 6) {
                    return@runSafeAction tapBoundsCenter(bounds, clean)
                }
            }
            ScreenActionResult(false, "I could not find \"$clean\" on this screen, Sir.")
        }
    }

    fun pressKeyboardKey(keyQuery: String): ScreenActionResult {
        return runSafeAction("pressKeyboardKey") {
            val clean = keyQuery.trim()
            val lower = clean.lowercase(Locale.US)
            if (lower.isBlank()) return@runSafeAction ScreenActionResult(false, "Tell me which key to press, Sir.")

            val isImeActionKey = lower in setOf("enter", "return", "go", "search", "send", "next", "done", "submit")
            val imeRoot = inputMethodWindowRoot()

            val aliases: List<String> = when (lower) {
                "enter", "return" -> listOf("enter", "done", "go", "search", "send", "next", "return")
                "backspace", "delete", "del", "erase" -> listOf("delete", "backspace", "del", "erase")
                "space", "spacebar", "space bar" -> listOf("space", "spacebar", "english")
                "shift", "caps", "caps lock" -> listOf("shift", "caps")
                else -> listOf(lower)
            }

            if (imeRoot != null) {
                var matchedNode: AccessibilityNodeInfo? = null
                var bestScore = Int.MAX_VALUE
                traverseNodes(imeRoot) { node ->
                    if (!safeIsVisible(node)) return@traverseNodes
                    val sig = nodeSignature(node)
                    val t = safeText(node).lowercase(Locale.US)
                    val d = safeContentDesc(node).lowercase(Locale.US)
                    aliases.forEachIndexed { idx, alias ->
                        val score = when {
                            t == alias || d == alias -> idx * 10
                            t.startsWith(alias) || d.startsWith(alias) -> idx * 10 + 2
                            sig.contains(alias) -> idx * 10 + 5
                            else -> Int.MAX_VALUE
                        }
                        if (score < bestScore) {
                            val rect = safeBounds(node)
                            if (rect.width() > 4 && rect.height() > 4) {
                                bestScore = score
                                matchedNode = node
                            }
                        }
                    }
                }
                val found = matchedNode
                if (found != null) {
                    val rect = safeBounds(found)
                    return@runSafeAction tapBoundsCenter(rect, "$clean key")
                }
            }

            if (isImeActionKey && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appRoot = activeAppRootWindow()
                val focused = runCatching { appRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                if (focused != null && safePerformAction(focused, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    return@runSafeAction ScreenActionResult(true, "Pressed $clean, Sir.")
                }
            }

            if (imeRoot == null) {
                ScreenActionResult(false, "No on-screen keyboard is currently open to press \"$clean\", Sir.")
            } else {
                ScreenActionResult(false, "I couldn't find the \"$clean\" key on the visible keyboard, Sir.")
            }
        }
    }

    fun scrollScreen(forward: Boolean): ScreenActionResult {
        return runSafeAction("scrollScreen") {
            val root = activeAppRootWindow()
                ?: return@runSafeAction ScreenActionResult(false, "No screen is active to scroll, Sir.")

            runCatching {
                overlayManager?.showScrollChevron(
                    if (forward) ScrollOverlayDirection.DOWN else ScrollOverlayDirection.UP
                )
            }

            val scrollables = mutableListOf<AccessibilityNodeInfo>()
            traverseNodes(root) { node ->
                if (safeIsScrollable(node) && safeIsVisible(node)) {
                    scrollables.add(node)
                }
            }
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            for (node in scrollables) {
                if (safePerformAction(node, action)) {
                    AccessibilityDiagnostics.onWindowCheckResult(
                        applicationContext ?: this,
                        success = true,
                        detail = "Semantic scroll succeeded"
                    )
                    return@runSafeAction ScreenActionResult(true, "Scrolled ${if (forward) "down" else "up"}, Sir.")
                }
            }

            val dm = resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val startY = if (forward) dm.heightPixels * 0.74f else dm.heightPixels * 0.28f
            val endY = if (forward) dm.heightPixels * 0.24f else dm.heightPixels * 0.76f
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
            val dispatched = safeDispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                if (forward) "scroll_down" else "scroll_up"
            )
            if (dispatched) {
                ScreenActionResult(true, "Swiped ${if (forward) "down" else "up"}, Sir.")
            } else {
                ScreenActionResult(false, "Unable to scroll this screen, Sir.")
            }
        }
    }

    fun swipeHorizontal(toLeft: Boolean): ScreenActionResult {
        return runSafeAction("swipeHorizontal") {
            runCatching {
                overlayManager?.showScrollChevron(
                    if (toLeft) ScrollOverlayDirection.LEFT else ScrollOverlayDirection.RIGHT
                )
            }
            val dm = resources.displayMetrics
            val cy = dm.heightPixels * 0.50f
            val startX = if (toLeft) dm.widthPixels * 0.82f else dm.widthPixels * 0.18f
            val endX = if (toLeft) dm.widthPixels * 0.18f else dm.widthPixels * 0.82f
            val path = Path().apply {
                moveTo(startX, cy)
                lineTo(endX, cy)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
            val ok = safeDispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                if (toLeft) "swipe_left" else "swipe_right"
            )
            ScreenActionResult(ok, if (ok) "Swiped ${if (toLeft) "left" else "right"}, Sir." else "Unable to swipe horizontally, Sir.")
        }
    }

    /**
     * Free-roam vertical, horizontal, and diagonal scrolling with adjustable distance, speed,
     * jump-to-top/bottom, and optional continuous auto-scroll loop until "stop scrolling".
     */
    fun scrollFreeRoam(
        direction: String,
        distanceFraction: Float = 0.55f,
        durationMs: Long = 320L,
        continuous: Boolean = false
    ): ScreenActionResult {
        val dir = direction.lowercase(Locale.US).trim().replace('-', '_').replace(' ', '_')
        if (dir == "stop" || dir == "pause" || dir == "halt") {
            val wasRunning = continuousScrollJob?.isActive == true
            stopFreeRoamScroll()
            return ScreenActionResult(
                true,
                if (wasRunning) "Stopped free-roam scrolling, Sir." else "Scrolling is already stopped, Sir."
            )
        }

        if (dir == "top" || dir == "bottom") {
            stopFreeRoamScroll()
            val forward = dir == "bottom"
            continuousScrollJob = serviceScope.launch {
                repeat(4) {
                    if (!isActive) return@launch
                    dispatchFreeRoamStroke(if (forward) "down" else "up", 0.72f, 210L)
                    delay(320L)
                }
            }
            return ScreenActionResult(true, "Scrolling to the $dir of this page, Sir.")
        }

        if (continuous) {
            stopFreeRoamScroll()
            val firstOk = dispatchFreeRoamStroke(dir, distanceFraction, durationMs)
            if (!firstOk) {
                return ScreenActionResult(false, "Unable to start free-roam $dir scroll here, Sir.")
            }
            continuousScrollJob = serviceScope.launch {
                var steps = 0
                while (isActive && steps < 24) {
                    delay((durationMs + 420L).coerceIn(520L, 1400L))
                    if (!isActive) break
                    val ok = dispatchFreeRoamStroke(dir, distanceFraction, durationMs)
                    if (!ok) break
                    steps++
                }
            }
            return ScreenActionResult(
                true,
                "Continuously scrolling ${dir.replace('_', ' ')}, Sir. Say \"stop scrolling\" anytime."
            )
        }

        val ok = dispatchFreeRoamStroke(dir, distanceFraction, durationMs)
        return if (ok) {
            ScreenActionResult(true, "Scrolled ${dir.replace('_', ' ')}, Sir.")
        } else {
            ScreenActionResult(false, "Unable to scroll ${dir.replace('_', ' ')} on this screen, Sir.")
        }
    }

    private fun dispatchFreeRoamStroke(
        dir: String,
        distanceFraction: Float,
        durationMs: Long
    ): Boolean {
        return runSafeOp("dispatchFreeRoamStroke", default = false) {
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat().coerceAtLeast(100f)
            val h = dm.heightPixels.toFloat().coerceAtLeast(100f)
            val cx = w * 0.50f
            val cy = h * 0.50f
            val frac = distanceFraction.coerceIn(0.18f, 0.78f)
            // Finger drag vector: to view further down -> drag finger up; to view right -> drag finger left.
            val (dxNorm, dyNorm, overlayDir) = when (dir) {
                "down" -> Triple(0f, -1f, ScrollOverlayDirection.DOWN)
                "up" -> Triple(0f, 1f, ScrollOverlayDirection.UP)
                "right" -> Triple(-1f, 0f, ScrollOverlayDirection.RIGHT)
                "left" -> Triple(1f, 0f, ScrollOverlayDirection.LEFT)
                "down_right" -> Triple(-0.75f, -0.75f, ScrollOverlayDirection.DOWN_RIGHT)
                "down_left" -> Triple(0.75f, -0.75f, ScrollOverlayDirection.DOWN_LEFT)
                "up_right" -> Triple(-0.75f, 0.75f, ScrollOverlayDirection.UP_RIGHT)
                "up_left" -> Triple(0.75f, 0.75f, ScrollOverlayDirection.UP_LEFT)
                else -> Triple(0f, -1f, ScrollOverlayDirection.DOWN)
            }

            // Keep horizontal start/end inside [0.14w .. 0.86w] so Android edge-back gesture never triggers
            val halfSpanX = (w * 0.36f * (frac / 0.55f)).coerceAtMost(w * 0.36f)
            val halfSpanY = (h * 0.28f * (frac / 0.55f)).coerceAtMost(h * 0.32f)

            val startX = (cx - dxNorm * halfSpanX).coerceIn(w * 0.14f, w * 0.86f)
            val endX = (cx + dxNorm * halfSpanX).coerceIn(w * 0.14f, w * 0.86f)
            val startY = (cy - dyNorm * halfSpanY).coerceIn(h * 0.18f, h * 0.84f)
            val endY = (cy + dyNorm * halfSpanY).coerceIn(h * 0.18f, h * 0.84f)

            runCatching { overlayManager?.showScrollChevron(overlayDir) }

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(140L, 1200L))
            safeDispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), "free_roam_$dir")
        }
    }

    /**
     * Multi-touch pinch-to-zoom (2 simultaneous fingers moving apart or together).
     */
    fun performPinchZoom(zoomIn: Boolean, intensity: Float = 1.0f): ScreenActionResult {
        return runSafeAction("performPinchZoom") {
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat().coerceAtLeast(100f)
            val h = dm.heightPixels.toFloat().coerceAtLeast(100f)
            val cx = w * 0.50f
            val cy = h * 0.50f
            val scale = intensity.coerceIn(0.6f, 1.4f)
            val nearX = w * 0.08f
            val nearY = h * 0.06f
            val farX = (w * 0.30f * scale).coerceAtMost(w * 0.38f)
            val farY = (h * 0.19f * scale).coerceAtMost(h * 0.28f)

            val f1StartX = if (zoomIn) cx - nearX else cx - farX
            val f1StartY = if (zoomIn) cy - nearY else cy - farY
            val f1EndX = if (zoomIn) cx - farX else cx - nearX
            val f1EndY = if (zoomIn) cy - farY else cy - nearY

            val f2StartX = if (zoomIn) cx + nearX else cx + farX
            val f2StartY = if (zoomIn) cy + nearY else cy + farY
            val f2EndX = if (zoomIn) cx + farX else cx + nearX
            val f2EndY = if (zoomIn) cy + farY else cy + nearY

            runCatching {
                overlayManager?.showMultiTouchRipples(
                    listOf(f1StartX to f1StartY, f2StartX to f2StartY),
                    if (zoomIn) "Pinch Out (Zoom In)" else "Pinch In (Zoom Out)"
                )
            }

            val p1 = Path().apply {
                moveTo(f1StartX, f1StartY)
                lineTo(f1EndX, f1EndY)
            }
            val p2 = Path().apply {
                moveTo(f2StartX, f2StartY)
                lineTo(f2EndX, f2EndY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p1, 0L, 380L))
                .addStroke(GestureDescription.StrokeDescription(p2, 0L, 380L))
                .build()
            val ok = safeDispatchGesture(gesture, if (zoomIn) "pinch_zoom_in" else "pinch_zoom_out")
            ScreenActionResult(
                ok,
                if (ok) {
                    if (zoomIn) "Zoomed in with multi-touch spread, Sir." else "Zoomed out with multi-touch pinch, Sir."
                } else {
                    "Android could not dispatch the multi-touch zoom gesture here, Sir."
                }
            )
        }
    }

    /**
     * Two-finger parallel scroll in any vertical or horizontal direction.
     */
    fun performTwoFingerScroll(direction: String, distanceFraction: Float = 0.50f): ScreenActionResult {
        return runSafeAction("performTwoFingerScroll") {
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat().coerceAtLeast(100f)
            val h = dm.heightPixels.toFloat().coerceAtLeast(100f)
            val cx = w * 0.50f
            val cy = h * 0.50f
            val sep = 72f
            val dir = direction.lowercase(Locale.US).trim()
            val (dx, dy) = when (dir) {
                "up" -> 0f to h * 0.26f * distanceFraction.coerceIn(0.3f, 1.2f)
                "left" -> w * 0.30f * distanceFraction.coerceIn(0.3f, 1.2f) to 0f
                "right" -> -w * 0.30f * distanceFraction.coerceIn(0.3f, 1.2f) to 0f
                else -> 0f to -h * 0.26f * distanceFraction.coerceIn(0.3f, 1.2f)
            }
            val horizontal = dir == "left" || dir == "right"
            val p1Start = if (horizontal) cx - dx to cy - sep else cx - sep to cy - dy
            val p1End = if (horizontal) cx + dx to cy - sep else cx - sep to cy + dy
            val p2Start = if (horizontal) cx - dx to cy + sep else cx + sep to cy - dy
            val p2End = if (horizontal) cx + dx to cy + sep else cx + sep to cy + dy

            runCatching {
                overlayManager?.showMultiTouchRipples(
                    listOf(p1Start, p2Start),
                    "2-Finger Scroll ${dir.replaceFirstChar { it.uppercase() }}"
                )
            }

            val path1 = Path().apply { moveTo(p1Start.first, p1Start.second); lineTo(p1End.first, p1End.second) }
            val path2 = Path().apply { moveTo(p2Start.first, p2Start.second); lineTo(p2End.first, p2End.second) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path1, 0L, 340L))
                .addStroke(GestureDescription.StrokeDescription(path2, 0L, 340L))
                .build()
            val ok = safeDispatchGesture(gesture, "two_finger_scroll_$dir")
            ScreenActionResult(
                ok,
                if (ok) "Executed two-finger $dir scroll, Sir." else "Could not perform two-finger $dir scroll, Sir."
            )
        }
    }

    /**
     * Multi-finger simultaneous tap (2 to 4 fingers) around screen center.
     */
    fun performMultiFingerTap(fingerCount: Int = 2): ScreenActionResult {
        return runSafeAction("performMultiFingerTap") {
            val count = fingerCount.coerceIn(2, 4)
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat().coerceAtLeast(100f)
            val h = dm.heightPixels.toFloat().coerceAtLeast(100f)
            val spacing = 95f
            val totalWidth = (count - 1) * spacing
            val startX = (w * 0.50f - totalWidth / 2f).coerceAtLeast(w * 0.14f)
            val cy = h * 0.50f
            val points = (0 until count).map { idx -> (startX + idx * spacing).coerceAtMost(w * 0.86f) to cy }

            runCatching { overlayManager?.showMultiTouchRipples(points, "$count-Finger Tap") }

            val builder = GestureDescription.Builder()
            points.forEach { (px, py) ->
                val p = Path().apply { moveTo(px, py) }
                builder.addStroke(GestureDescription.StrokeDescription(p, 0L, 65L))
            }
            val ok = safeDispatchGesture(builder.build(), "multi_finger_tap_$count")
            ScreenActionResult(
                ok,
                if (ok) "Performed $count-finger multi-touch tap, Sir." else "Multi-finger tap failed on this screen, Sir."
            )
        }
    }

    /**
     * Closes an open Android Settings screen or Quick Panel cleanly.
     */
    suspend fun closeCurrentSettingsScreen(settingLabel: String = ""): ScreenActionResult {
        return try {
            val activePkg = activeForegroundPackage().orEmpty().lowercase(Locale.US)
            val summaryLower = readScreenSummary().lowercase(Locale.US)
            val isSettingsVisible = activePkg.contains("settings") ||
                activePkg.contains("systemui") ||
                summaryLower.contains("wi-fi") ||
                summaryLower.contains("bluetooth") ||
                summaryLower.contains("settings")

            if (!isSettingsVisible) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return ScreenActionResult(
                    true,
                    "Navigated back to close ${settingLabel.ifBlank { "that" }} screen, Sir."
                )
            }

            // Try explicit Done / Close / Cancel button if this is a floating Android Settings Panel
            val dismissBtn = allInteractiveRoots(includeInputMethod = false, includeSystem = true)
                .firstNotNullOfOrNull { root ->
                    findClickableNodeByKeywords(root, listOf("done", "close", "dismiss", "navigate up"))
                }
            if (dismissBtn != null) {
                val rect = findClickableAncestorBounds(dismissBtn) ?: safeBounds(dismissBtn)
                if (rect.width() > 6 && rect.height() > 6) {
                    tapBoundsCenter(rect, "Close Settings")
                    delay(260L)
                }
            }

            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(280L)
            val afterPkg = activeForegroundPackage().orEmpty().lowercase(Locale.US)
            if (afterPkg.contains("settings")) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            val labelText = if (settingLabel.isNotBlank() && settingLabel != "general") "$settingLabel settings" else "settings"
            ScreenActionResult(true, "Closed $labelText, Sir.")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("closeCurrentSettingsScreen", t, applicationContext ?: this)
            ScreenActionResult(false, "Unable to close settings cleanly right now, Sir.")
        }
    }

    /**
     * Finds and sets a matching Settings toggle / Switch / CheckBox on the visible Settings or Quick Settings screen.
     */
    fun toggleOnScreenSettingSwitch(keywords: List<String>, desiredOn: Boolean?): ScreenActionResult {
        return runSafeAction("toggleOnScreenSettingSwitch") {
            val roots = allInteractiveRoots(includeInputMethod = false, includeSystem = true)
            for (root in roots) {
                var matchedNode: AccessibilityNodeInfo? = null
                traverseNodes(root) { node ->
                    if (matchedNode != null || !safeIsVisible(node)) return@traverseNodes
                    val sig = nodeSignature(node)
                    val isCheckable = runCatching { node.isCheckable }.getOrDefault(false)
                    if (isCheckable && keywords.any { sig.contains(it) }) {
                        matchedNode = node
                        return@traverseNodes
                    }
                    // Also check a clickable row containing the keyword plus a checkable switch child
                    if (safeIsClickable(node) && keywords.any { sig.contains(it) }) {
                        val childSwitch = findCheckableChild(node)
                        if (childSwitch != null) {
                            matchedNode = childSwitch
                        } else if (sig.contains("tile") || sig.contains("wi-fi") || sig.contains("bluetooth")) {
                            matchedNode = node
                        }
                    }
                }
                val target = matchedNode
                if (target != null) {
                    val isCheckable = runCatching { target.isCheckable }.getOrDefault(false)
                    val currentlyChecked = runCatching { target.isChecked }.getOrDefault(false)
                    if (isCheckable && desiredOn != null && currentlyChecked == desiredOn) {
                        return@runSafeAction ScreenActionResult(
                            true,
                            "${keywords.first().replaceFirstChar { it.uppercase() }} is already ${if (desiredOn) "enabled" else "disabled"}, Sir."
                        )
                    }
                    val rect = findClickableAncestorBounds(target) ?: safeBounds(target)
                    if (rect.width() > 6 && rect.height() > 6) {
                        return@runSafeAction tapBoundsCenter(rect, keywords.first())
                    }
                    if (safePerformAction(target, AccessibilityNodeInfo.ACTION_CLICK)) {
                        return@runSafeAction ScreenActionResult(true, "Updated ${keywords.first()} setting, Sir.")
                    }
                }
            }
            ScreenActionResult(false, "Could not locate the ${keywords.first()} switch on screen yet, Sir.")
        }
    }

    private fun findCheckableChild(parent: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 3) return null
        val count = runCatching { parent.childCount.coerceIn(0, 12) }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { parent.getChild(i) }.getOrNull() ?: continue
            if (runCatching { child.isCheckable && child.isVisibleToUser }.getOrDefault(false)) {
                return child
            }
            findCheckableChild(child, depth + 1)?.let { return it }
        }
        return null
    }

    fun doubleTapBoundsCenter(rect: Rect, label: String = ""): ScreenActionResult {
        return runSafeAction("doubleTapBoundsCenter") {
            val cx = rect.centerX().toFloat().coerceAtLeast(1f)
            val cy = rect.centerY().toFloat().coerceAtLeast(1f)
            runCatching { overlayManager?.showTapRipple(cx, cy, label.ifBlank { "Double-Tap" }) }
            val p1 = Path().apply { moveTo(cx, cy) }
            val p2 = Path().apply { moveTo(cx + 2f, cy + 2f) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p1, 0L, 45L))
                .addStroke(GestureDescription.StrokeDescription(p2, 110L, 45L))
                .build()
            val ok = safeDispatchGesture(gesture, "double_tap")
            ScreenActionResult(ok, if (ok) "Double-tapped ${label.ifBlank { "target" }}, Sir." else "Could not double-tap target, Sir.", rect)
        }
    }

    fun longPressBoundsCenter(rect: Rect, label: String = ""): ScreenActionResult {
        return runSafeAction("longPressBoundsCenter") {
            val cx = rect.centerX().toFloat().coerceAtLeast(1f)
            val cy = rect.centerY().toFloat().coerceAtLeast(1f)
            runCatching {
                overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = label.ifBlank { "Hold" })), 1500L)
            }
            val path = Path().apply { moveTo(cx, cy) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 620L)
            val ok = safeDispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), "long_press")
            ScreenActionResult(ok, if (ok) "Long-pressed ${label.ifBlank { "item" }}, Sir." else "Could not long-press item, Sir.", rect)
        }
    }

    fun verifyScreenChanged(previousSummary: String, expectedKeywords: List<String> = emptyList()): Boolean {
        return runSafeOp("verifyScreenChanged", default = false) {
            val current = readScreenSummary()
            val lower = current.lowercase(Locale.US)
            if (expectedKeywords.isNotEmpty() && expectedKeywords.any { lower.contains(it.lowercase(Locale.US)) }) {
                return@runSafeOp true
            }
            current != previousSummary
        }
    }

    fun performGlobalNavigation(actionName: String): ScreenActionResult {
        return runSafeAction("performGlobalNavigation") {
            val (globalCode, label) = when (actionName.lowercase(Locale.US)) {
                "back" -> GLOBAL_ACTION_BACK to "Navigating back"
                "home" -> GLOBAL_ACTION_HOME to "Returning to Home screen"
                "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS to "Opening Recent Apps"
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS to "Opening Notifications shade"
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS to "Opening Quick Settings"
                "power_menu" -> GLOBAL_ACTION_POWER_DIALOG to "Opening Power Menu"
                "all_apps", "app_drawer" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS to "Opening App Drawer"
                    } else {
                        return@runSafeAction ScreenActionResult(false, "App drawer global action requires Android 12 or higher, Sir.")
                    }
                }
                "dismiss_notifications" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE to "Closing Notifications shade"
                    } else {
                        GLOBAL_ACTION_BACK to "Closing shade"
                    }
                }
                "lock", "lock_screen" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        GLOBAL_ACTION_LOCK_SCREEN to "Locking screen"
                    } else {
                        return@runSafeAction ScreenActionResult(false, "Screen lock requires Android 9 or higher, Sir.")
                    }
                }
                "screenshot" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        GLOBAL_ACTION_TAKE_SCREENSHOT to "Taking a screenshot"
                    } else {
                        return@runSafeAction ScreenActionResult(false, "Screenshot command requires Android 9 or higher, Sir.")
                    }
                }
                else -> return@runSafeAction ScreenActionResult(false, "Unknown navigation command: $actionName")
            }

            val ok = performGlobalAction(globalCode)
            if (ok) {
                ScreenActionResult(true, "$label, Sir.")
            } else {
                ScreenActionResult(false, "Android blocked the $actionName action on this screen, Sir.")
            }
        }
    }

    @Suppress("DEPRECATION")
    fun wakeScreenBriefly(timeoutMs: Long = 15_000L) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "JarvisAI::WakeScreenUnlock"
            )
            wakeLock.acquire(timeoutMs)
        }.onFailure { err ->
            AccessibilityDiagnostics.recordNonFatalError("wakeScreenBriefly", err, applicationContext ?: this)
        }
    }

    suspend fun performPowerAction(action: String): ScreenActionResult {
        return try {
            val normalized = action.lowercase(Locale.US).trim()
            if (normalized == "sleep" || normalized == "lock" || normalized == "lock_screen" || normalized == "screen_off") {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    if (ok) {
                        ScreenActionResult(true, "Putting your device to sleep now, Sir.")
                    } else {
                        ScreenActionResult(false, "Android blocked locking the screen from here, Sir.")
                    }
                } else {
                    ScreenActionResult(false, "Locking/sleeping the screen programmatically requires Android 9 or higher, Sir.")
                }
            }

            val isRestart = normalized == "restart" || normalized == "reboot"
            val isShutdown = normalized == "shutdown" || normalized == "power_off" || normalized == "shut_down"
            if (!isRestart && !isShutdown) {
                return ScreenActionResult(false, "Unknown power command: $action")
            }

            val menuOpened = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            if (!menuOpened) {
                return ScreenActionResult(false, "Android blocked opening the Power Menu on this screen, Sir.")
            }

            delay(800L)
            val keywords = if (isRestart) {
                listOf("restart", "reboot")
            } else {
                listOf("power off", "shut down", "shutdown", "turn off")
            }
            val actionLabel = if (isRestart) "Restart" else "Power off"
            val firstTap = findAndClickSystemDialogButton(keywords)
            if (firstTap) {
                delay(450L)
                findAndClickSystemDialogButton(keywords)
                return ScreenActionResult(
                    true,
                    "Opened the power menu and selected $actionLabel, Sir — confirm on screen if your device prompts once more."
                )
            }

            ScreenActionResult(
                true,
                "I opened Android's Power Menu, Sir — tap $actionLabel on screen since Android doesn't allow apps to trigger silent kernel $normalized without tapping."
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("performPowerAction", t, applicationContext ?: this)
            ScreenActionResult(false, "Unable to complete power action cleanly, Sir.")
        }
    }

    private fun findAndClickSystemDialogButton(keywords: List<String>): Boolean {
        val roots = allInteractiveRoots(includeInputMethod = false, includeSystem = true)
        for (root in roots) {
            val match = findClickableNodeByKeywords(root, keywords)
            if (match != null) {
                val rect = findClickableAncestorBounds(match) ?: safeBounds(match)
                if (rect.width() > 6 && rect.height() > 6) {
                    val res = tapBoundsCenter(rect, keywords.first())
                    if (res.success) return true
                }
            }
        }
        return false
    }

    suspend fun performPatternUnlock(patternDots: List<Int>): ScreenActionResult {
        return try {
            if (patternDots.size < 4 || patternDots.any { it !in 1..9 }) {
                return ScreenActionResult(false, "Saved unlock pattern is invalid (needs at least 4 dots), Sir.")
            }

            wakeScreenBriefly(16_000L)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                ?: return ScreenActionResult(false, "Cannot access system lock state on this device, Sir.")

            if (!km.isKeyguardLocked) {
                return ScreenActionResult(true, "Your device is already unlocked, Sir.")
            }

            if (findLockPatternBoundsInWindows() == null) {
                val dm = resources.displayMetrics
                val cx = dm.widthPixels * 0.50f
                val swipePath = Path().apply {
                    moveTo(cx, dm.heightPixels * 0.84f)
                    lineTo(cx, dm.heightPixels * 0.24f)
                }
                dispatchGestureSuspend(
                    GestureDescription.StrokeDescription(swipePath, 0L, 260L),
                    "keyguard_swipe_up"
                )
                delay(700L)
                if (!km.isKeyguardLocked) {
                    return ScreenActionResult(true, "Device unlocked, Sir.")
                }
            }

            val gridBounds = findLockPatternBoundsInWindows() ?: estimateStandardPatternGridBounds()
            val cellWidth = gridBounds.width() / 3f
            val cellHeight = gridBounds.height() / 3f

            fun centerForDot(dot: Int): Pair<Float, Float> {
                val idx = dot - 1
                val col = idx % 3
                val row = idx / 3
                val x = (gridBounds.left + (col + 0.5f) * cellWidth).coerceAtLeast(1f)
                val y = (gridBounds.top + (row + 0.5f) * cellHeight).coerceAtLeast(1f)
                return x to y
            }

            val path = Path()
            val firstPoint = centerForDot(patternDots.first())
            path.moveTo(firstPoint.first, firstPoint.second)
            for (i in 1 until patternDots.size) {
                val pt = centerForDot(patternDots[i])
                path.lineTo(pt.first, pt.second)
            }

            val durationMs = (patternDots.size * 155L).coerceIn(550L, 1400L)
            val dispatched = dispatchGestureSuspend(
                GestureDescription.StrokeDescription(path, 0L, durationMs),
                "pattern_unlock"
            )
            if (!dispatched) {
                return ScreenActionResult(
                    false,
                    "Android rejected the unlock gesture on the secure lock screen, Sir."
                )
            }

            delay(800L)
            if (!km.isKeyguardLocked) {
                ScreenActionResult(true, "Device unlocked, Sir. Welcome back.")
            } else {
                ScreenActionResult(
                    false,
                    "I drew your pattern across the lock grid, Sir, but Android still reports the screen locked (either your device hides lock-screen pattern coordinates from accessibility services or it requires manual entry)."
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("performPatternUnlock", t, applicationContext ?: this)
            ScreenActionResult(false, "Unable to complete pattern unlock safely on this lock screen, Sir.")
        }
    }

    private fun findLockPatternBoundsInWindows(): Rect? {
        val sysRoots = allInteractiveRoots(includeInputMethod = false, includeSystem = true)
        val dm = resources.displayMetrics
        for (root in sysRoots) {
            var foundRect: Rect? = null
            traverseNodes(root) { node ->
                if (foundRect != null || !safeIsVisible(node)) return@traverseNodes
                val resId = runCatching { node.viewIdResourceName?.lowercase(Locale.US) }.getOrNull().orEmpty()
                val cls = runCatching { node.className?.toString()?.lowercase(Locale.US) }.getOrNull().orEmpty()
                if (resId.contains("lockpattern") || resId.contains("lock_pattern") || cls.contains("lockpatternview")) {
                    val r = safeBounds(node)
                    if (r.width() >= dm.widthPixels * 0.35f && r.height() >= dm.widthPixels * 0.35f) {
                        foundRect = r
                    }
                }
            }
            if (foundRect != null) return foundRect
        }
        return null
    }

    private fun estimateStandardPatternGridBounds(): Rect {
        val dm = resources.displayMetrics
        val side = minOf(dm.widthPixels * 0.66f, dm.heightPixels * 0.34f).toInt()
        val left = (dm.widthPixels - side) / 2
        val centerY = (dm.heightPixels * 0.64f).toInt()
        val top = (centerY - side / 2).coerceIn(0, (dm.heightPixels - side).coerceAtLeast(0))
        return Rect(left, top, left + side, top + side)
    }

    private fun safeDispatchGesture(gesture: GestureDescription, actionName: String): Boolean {
        val appCtx = applicationContext ?: this
        return try {
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        AccessibilityDiagnostics.onGestureResult(
                            appCtx,
                            actionName = actionName,
                            accepted = true,
                            completed = true
                        )
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        AccessibilityDiagnostics.onGestureResult(
                            appCtx,
                            actionName = actionName,
                            accepted = true,
                            completed = false,
                            reason = "cancelled by system"
                        )
                    }
                },
                null
            )
            if (!accepted) {
                AccessibilityDiagnostics.onGestureResult(
                    appCtx,
                    actionName = actionName,
                    accepted = false,
                    completed = false,
                    reason = "dispatchGesture returned false"
                )
            }
            accepted
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError("dispatchGesture[$actionName]", t, appCtx)
            false
        }
    }

    private suspend fun dispatchGestureSuspend(
        stroke: GestureDescription.StrokeDescription,
        actionName: String = "suspend_gesture"
    ): Boolean {
        val appCtx = applicationContext ?: this
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            try {
                val accepted = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            AccessibilityDiagnostics.onGestureResult(appCtx, actionName, accepted = true, completed = true)
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            AccessibilityDiagnostics.onGestureResult(
                                appCtx,
                                actionName,
                                accepted = true,
                                completed = false,
                                reason = "cancelled"
                            )
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!accepted && cont.isActive) {
                    AccessibilityDiagnostics.onGestureResult(
                        appCtx,
                        actionName,
                        accepted = false,
                        completed = false,
                        reason = "rejected synchronously"
                    )
                    cont.resume(false)
                }
            } catch (t: Throwable) {
                AccessibilityDiagnostics.recordNonFatalError("dispatchGestureSuspend[$actionName]", t, appCtx)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /**
     * Bounded iterative tree traversal with per-node exception isolation so stale nodes,
     * recycled nodes, Deep WebViews/Compose trees, or DeadObjectException can never crash the service.
     */
    private fun traverseNodes(root: AccessibilityNodeInfo?, visitor: (AccessibilityNodeInfo) -> Unit) {
        if (root == null) return
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_TRAVERSAL_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            try {
                visitor(node)
            } catch (t: Throwable) {
                AccessibilityDiagnostics.recordNonFatalError("traverseVisitor", t)
            }

            if (depth >= MAX_TRAVERSAL_DEPTH) continue
            val count = try {
                node.childCount.coerceIn(0, MAX_CHILDREN_PER_NODE)
            } catch (_: Throwable) {
                0
            }
            for (i in 0 until count) {
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                }
                if (child != null) {
                    queue.addLast(child to (depth + 1))
                }
            }
        }
    }

    private inline fun <T> runSafeOp(opName: String, default: T, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError(opName, t, applicationContext ?: this)
            default
        }
    }

    private inline fun runSafeAction(opName: String, block: () -> ScreenActionResult): ScreenActionResult {
        return try {
            block()
        } catch (t: Throwable) {
            AccessibilityDiagnostics.recordNonFatalError(opName, t, applicationContext ?: this)
            ScreenActionResult(false, "Android screen hierarchy changed during $opName, Sir — please try again.")
        }
    }

    private fun safePackageName(node: AccessibilityNodeInfo): String =
        runCatching { node.packageName?.toString().orEmpty() }.getOrDefault("")

    private fun safeText(node: AccessibilityNodeInfo): String =
        runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")

    private fun safeContentDesc(node: AccessibilityNodeInfo): String =
        runCatching { node.contentDescription?.toString()?.trim().orEmpty() }.getOrDefault("")

    private fun safeHintText(node: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { node.hintText?.toString()?.trim().orEmpty() }.getOrDefault("")
        } else ""

    private fun safeResName(node: AccessibilityNodeInfo): String =
        runCatching { node.viewIdResourceName?.substringAfterLast("/").orEmpty() }.getOrDefault("")

    private fun safeIsClickable(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isClickable }.getOrDefault(false)

    private fun safeIsEditable(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isEditable }.getOrDefault(false)

    private fun safeIsScrollable(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isScrollable }.getOrDefault(false)

    private fun safeIsVisible(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isVisibleToUser }.getOrDefault(false)

    private fun safeIsPassword(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isPassword }.getOrDefault(false)

    private fun safeParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        runCatching { node.parent }.getOrNull()

    private fun safeBounds(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return rect
    }

    private fun safePerformAction(
        node: AccessibilityNodeInfo,
        action: Int,
        args: Bundle? = null
    ): Boolean {
        return runCatching {
            if (args != null) node.performAction(action, args) else node.performAction(action)
        }.onFailure { err ->
            AccessibilityDiagnostics.recordNonFatalError("performAction($action)", err)
        }.getOrDefault(false)
    }

    private fun isBrowserAddressBar(node: AccessibilityNodeInfo): Boolean {
        val resId = safeResName(node).lowercase(Locale.US)
        val hint = safeHintText(node).lowercase(Locale.US)
        return resId.contains("url_bar") ||
            resId.contains("omnibox") ||
            resId.contains("location_bar") ||
            resId.contains("search_box_text") ||
            resId.contains("toolbar_search") ||
            hint.contains("search or type web address") ||
            hint.contains("search or type url")
    }

    private fun adjacentLabelSignature(node: AccessibilityNodeInfo): String {
        val parent = safeParent(node) ?: return ""
        val sb = StringBuilder()
        val count = runCatching { minOf(parent.childCount, 8) }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { parent.getChild(i) }.getOrNull() ?: continue
            if (!safeIsPassword(child) && child != node) {
                safeText(child).takeIf { it.isNotBlank() && it.length <= 40 }?.let { sb.append(it).append(' ') }
                safeContentDesc(child).takeIf { it.isNotBlank() && it.length <= 40 }?.let { sb.append(it).append(' ') }
            }
        }
        return sb.toString().lowercase(Locale.US)
    }

    private fun nodeMatchesHint(node: AccessibilityNodeInfo, hintLower: String): Boolean {
        return nodeSignature(node).contains(hintLower)
    }

    private fun nodeSignature(node: AccessibilityNodeInfo): String {
        val hint = safeHintText(node)
        val text = if (safeIsPassword(node)) "" else safeText(node)
        val desc = safeContentDesc(node)
        val resId = safeResName(node)
        return "$hint $text $desc $resId".lowercase(Locale.US)
    }

    private fun absDiff(a: Int, b: Int): Int = if (a >= b) a - b else b - a

    companion object {
        private const val MAX_TRAVERSAL_DEPTH = 30
        private const val MAX_TRAVERSAL_NODES = 450
        private const val MAX_CHILDREN_PER_NODE = 64

        @Volatile
        private var instance: JarvisAccessibilityService? = null

        @Volatile
        private var desiredShowTap: Boolean = true
        @Volatile
        private var desiredShowHighlight: Boolean = true
        @Volatile
        private var desiredShowScroll: Boolean = true

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activePackageName = MutableStateFlow<String?>(null)
        val activePackageName: StateFlow<String?> = _activePackageName.asStateFlow()

        fun activeInstance(): JarvisAccessibilityService? = instance
    }
}
