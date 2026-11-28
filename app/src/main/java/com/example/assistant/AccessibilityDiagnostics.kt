package com.example.assistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccessibilityHealthState(
    val label: String,
    val isHealthy: Boolean
) {
    PERMISSION_DISABLED("Permission Disabled", false),
    PERMISSION_ENABLED_NOT_CONNECTED("Permission Enabled (Not Connected)", false),
    CONNECTING("Service Connecting", false),
    CONNECTED("Service Connected", true),
    OPERATIONAL("Service Operational", true),
    MALFUNCTION_DETECTED("Malfunction Detected", false)
}

data class AccessibilityHealthSnapshot(
    val state: AccessibilityHealthState,
    val detail: String,
    val lastFailure: String? = null,
    val activePackage: String? = null
)

/**
 * Dedicated diagnostic telemetry, uncaught-exception reporter, and 6-state health tracker
 * for [JarvisAccessibilityService] under logcat tag `JARVIS_ACCESSIBILITY`.
 *
 * Strictly privacy-safe: never logs or persists screen text, passwords, OTPs, safe words,
 * or private message contents.
 */
object AccessibilityDiagnostics {

    const val TAG = "JARVIS_ACCESSIBILITY"
    private const val PREFS_NAME = "jarvis_accessibility_diagnostics"
    private const val KEY_MALFUNCTION = "malfunction_detected"
    private const val KEY_LAST_FAILURE = "last_failure_summary"
    private const val KEY_LAST_STACK = "last_failure_stack"
    private const val KEY_LAST_FAILURE_TS = "last_failure_ts"
    private const val MAX_RECENT_LOGS = 60

    private val lock = Any()
    private val ringLogs = ArrayDeque<String>(MAX_RECENT_LOGS)

    private val _healthState = MutableStateFlow(AccessibilityHealthState.PERMISSION_DISABLED)
    val healthState: StateFlow<AccessibilityHealthState> = _healthState.asStateFlow()

    private val _statusDetail = MutableStateFlow("Accessibility permission is disabled")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _lastFailureReason = MutableStateFlow<String?>(null)
    val lastFailureReason: StateFlow<String?> = _lastFailureReason.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<String>>(emptyList())
    val recentEvents: StateFlow<List<String>> = _recentEvents.asStateFlow()

    private val _activePackage = MutableStateFlow<String?>(null)
    val activePackage: StateFlow<String?> = _activePackage.asStateFlow()

    @Volatile
    private var uncaughtHandlerInstalled = false

    @Volatile
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var isConnectingFlag = false

    @Volatile
    private var isConnectedFlag = false

    @Volatile
    private var hasOperationalProofFlag = false

    @Volatile
    private var malfunctionFlag = false

    @Volatile
    private var persistedLoaded = false

    @Volatile
    private var lastEventLogMs = 0L

    @Volatile
    private var lastNonFatalErrorMs = 0L

    @Volatile
    private var lastReportedPackage: String? = null

    fun resolveHealthState(
        permissionEnabledInSettings: Boolean,
        isConnecting: Boolean,
        isServiceConnected: Boolean,
        hasOperationalProof: Boolean,
        malfunctionRecorded: Boolean
    ): AccessibilityHealthState {
        return when {
            !permissionEnabledInSettings -> AccessibilityHealthState.PERMISSION_DISABLED
            isServiceConnected && malfunctionRecorded -> AccessibilityHealthState.MALFUNCTION_DETECTED
            isServiceConnected && hasOperationalProof -> AccessibilityHealthState.OPERATIONAL
            isServiceConnected -> AccessibilityHealthState.CONNECTED
            isConnecting -> AccessibilityHealthState.CONNECTING
            malfunctionRecorded -> AccessibilityHealthState.MALFUNCTION_DETECTED
            else -> AccessibilityHealthState.PERMISSION_ENABLED_NOT_CONNECTED
        }
    }

    fun installUncaughtExceptionReporter(context: Context) {
        if (uncaughtHandlerInstalled) return
        val appContext = context.applicationContext ?: context
        loadPersistedState(appContext)
        synchronized(lock) {
            if (uncaughtHandlerInstalled) return
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            if (existing is JarvisUncaughtExceptionReporter) {
                uncaughtHandlerInstalled = true
                return
            }
            previousUncaughtHandler = existing
            Thread.setDefaultUncaughtExceptionHandler(
                JarvisUncaughtExceptionReporter(appContext, existing)
            )
            uncaughtHandlerInstalled = true
        }
        logInfo("UncaughtExceptionReporter installed for pid=${Process.myPid()}")
    }

    fun coroutineExceptionHandler(component: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            recordNonFatalError("Coroutine($component)", throwable)
        }
    }

    fun isServiceEnabledInSettings(context: Context): Boolean {
        return runCatching {
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!enabled) return@runCatching false
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val expectedComponent = ComponentName(context, JarvisAccessibilityService::class.java)
            val colonSplitter = enabledServices.split(':')
            colonSplitter.any { entry ->
                val parsed = ComponentName.unflattenFromString(entry.trim())
                parsed != null &&
                    parsed.packageName == expectedComponent.packageName &&
                    parsed.className == expectedComponent.className
            }
        }.getOrDefault(false)
    }

    fun onServiceCreate(context: Context) {
        val appContext = context.applicationContext ?: context
        loadPersistedState(appContext)
        isConnectingFlag = true
        hasOperationalProofFlag = false
        logInfo("Service created (onCreate) pid=${Process.myPid()}")
        refreshAndEvaluate(appContext, JarvisAccessibilityService.activeInstance())
    }

    fun onServiceConnecting(context: Context) {
        val appContext = context.applicationContext ?: context
        loadPersistedState(appContext)
        isConnectingFlag = true
        logInfo("System binding -> onServiceConnected() starting")
        refreshAndEvaluate(appContext, JarvisAccessibilityService.activeInstance())
    }

    fun onServiceConnected(context: Context, serviceInfo: AccessibilityServiceInfo?) {
        val appContext = context.applicationContext ?: context
        loadPersistedState(appContext)
        isConnectingFlag = false
        isConnectedFlag = true
        val caps = serviceInfo?.capabilities ?: 0
        val canRetrieve = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
        val canGestures = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        val flags = serviceInfo?.flags ?: 0
        logInfo(
            "onServiceConnected() bound successfully: canRetrieveWindow=$canRetrieve " +
                "canPerformGestures=$canGestures flags=0x${flags.toString(16)}"
        )
        // Clear prior crash flag once Android has cleanly re-bound onServiceConnected()
        clearPersistedMalfunction(appContext)
        refreshAndEvaluate(appContext, JarvisAccessibilityService.activeInstance())
    }

    fun onAccessibilityEvent(eventType: Int, packageName: CharSequence?) {
        val pkg = packageName?.toString()?.trim().orEmpty()
        if (pkg.isNotBlank() && pkg != lastReportedPackage) {
            lastReportedPackage = pkg
            _activePackage.value = pkg
            logInfo("Active foreground package changed: $pkg (event=${AccessibilityEvent.eventTypeToString(eventType)})")
        } else {
            val now = System.currentTimeMillis()
            if (now - lastEventLogMs >= 5000L) {
                lastEventLogMs = now
                logInfo("Accessibility event=${AccessibilityEvent.eventTypeToString(eventType)} pkg=${pkg.ifBlank { "unknown" }}")
            }
        }
    }

    fun onWindowCheckResult(context: Context, success: Boolean, detail: String) {
        if (success) {
            if (!hasOperationalProofFlag) {
                hasOperationalProofFlag = true
                logInfo("Window inspection operational: $detail")
            }
            clearPersistedMalfunction(context.applicationContext ?: context)
        } else {
            logWarn("Window retrieval failed: $detail")
        }
        refreshAndEvaluate(context.applicationContext ?: context, JarvisAccessibilityService.activeInstance())
    }

    fun onGestureResult(
        context: Context,
        actionName: String,
        accepted: Boolean,
        completed: Boolean? = null,
        reason: String = ""
    ) {
        val msg = buildString {
            append("Gesture[$actionName] accepted=$accepted")
            if (completed != null) append(" completed=$completed")
            if (reason.isNotBlank()) append(" ($reason)")
        }
        if (accepted && completed != false) {
            logInfo(msg)
            if (completed == true && !hasOperationalProofFlag) {
                hasOperationalProofFlag = true
                refreshAndEvaluate(context.applicationContext ?: context, JarvisAccessibilityService.activeInstance())
            }
        } else {
            logWarn(msg)
        }
    }

    fun onServiceInterrupt() {
        logWarn("onInterrupt() called by Android system")
    }

    fun onServiceUnbind(context: Context) {
        val appContext = context.applicationContext ?: context
        isConnectingFlag = false
        isConnectedFlag = false
        hasOperationalProofFlag = false
        val stillEnabled = isServiceEnabledInSettings(appContext)
        if (stillEnabled) {
            val reason = "Service unbound while permission remains enabled in Android Settings"
            recordMalfunction(appContext, reason, stack = null)
            logWarn("onUnbind(): $reason")
        } else {
            clearPersistedMalfunction(appContext)
            logInfo("onUnbind(): permission disabled in Settings; clean unbind")
        }
        refreshAndEvaluate(appContext, null)
    }

    fun onServiceDestroy(context: Context) {
        val appContext = context.applicationContext ?: context
        isConnectingFlag = false
        isConnectedFlag = false
        hasOperationalProofFlag = false
        logInfo("Service destroyed (onDestroy)")
        refreshAndEvaluate(appContext, null)
    }

    fun recordNonFatalError(operation: String, throwable: Throwable, context: Context? = null) {
        val safeMsg = sanitizeFailureSummary(throwable)
        val now = System.currentTimeMillis()
        if (now - lastNonFatalErrorMs >= 400L) {
            lastNonFatalErrorMs = now
            logError("Failure in [$operation]: $safeMsg", throwable)
        }
        if (context != null) {
            val appContext = context.applicationContext ?: context
            if (throwable is SecurityException || throwable is IllegalStateException) {
                _lastFailureReason.value = "$operation: $safeMsg"
            }
            refreshAndEvaluate(appContext, JarvisAccessibilityService.activeInstance())
        } else {
            _lastFailureReason.value = "$operation: $safeMsg"
        }
    }

    fun markMalfunction(context: Context, operation: String, throwable: Throwable) {
        val appContext = context.applicationContext ?: context
        val safeSummary = "$operation: ${sanitizeFailureSummary(throwable)}"
        val stackSummary = throwable.stackTraceToString().lineSequence().take(12).joinToString("\n")
        recordMalfunction(appContext, safeSummary, stackSummary)
        logError("MALFUNCTION_DETECTED [$operation]: $safeSummary", throwable)
        refreshAndEvaluate(appContext, JarvisAccessibilityService.activeInstance())
    }

    fun refreshAndEvaluate(
        context: Context,
        activeService: JarvisAccessibilityService? = JarvisAccessibilityService.activeInstance()
    ): AccessibilityHealthSnapshot {
        val appContext = context.applicationContext ?: context
        loadPersistedState(appContext)
        val permEnabled = isServiceEnabledInSettings(appContext)
        val actuallyConnected = activeService != null && JarvisAccessibilityService.isConnected.value
        isConnectedFlag = actuallyConnected
        if (actuallyConnected) {
            isConnectingFlag = false
        }

        val state = resolveHealthState(
            permissionEnabledInSettings = permEnabled,
            isConnecting = isConnectingFlag,
            isServiceConnected = actuallyConnected,
            hasOperationalProof = hasOperationalProofFlag,
            malfunctionRecorded = malfunctionFlag
        )

        val lastFail = _lastFailureReason.value
        val detail = when (state) {
            AccessibilityHealthState.PERMISSION_DISABLED ->
                "Permission disabled in Android Accessibility Settings"
            AccessibilityHealthState.PERMISSION_ENABLED_NOT_CONNECTED ->
                "Permission enabled, waiting for Android to bind JarvisAccessibilityService"
            AccessibilityHealthState.CONNECTING ->
                "Binding to Android accessibility framework..."
            AccessibilityHealthState.CONNECTED ->
                "Connected to Android Accessibility; verifying active window operations"
            AccessibilityHealthState.OPERATIONAL ->
                "Operational: window inspection, node actions & gestures verified"
            AccessibilityHealthState.MALFUNCTION_DETECTED ->
                lastFail?.let { "Malfunction detected: $it" }
                    ?: "Service disconnected unexpectedly while enabled in Settings"
        }

        _healthState.value = state
        _statusDetail.value = detail
        return AccessibilityHealthSnapshot(
            state = state,
            detail = detail,
            lastFailure = lastFail,
            activePackage = _activePackage.value
        )
    }

    fun exportDiagnosticsReport(context: Context): String {
        val snapshot = refreshAndEvaluate(context)
        val persistedStack = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_STACK, null)
        }.getOrNull()
        return buildString {
            appendLine("Health State: ${snapshot.state.label}")
            appendLine("Detail: ${snapshot.detail}")
            appendLine("Active Package: ${snapshot.activePackage ?: "none"}")
            if (!snapshot.lastFailure.isNullOrBlank()) {
                appendLine("Last Failure: ${snapshot.lastFailure}")
            }
            if (!persistedStack.isNullOrBlank()) {
                appendLine("Last Crash Trace (top frames):")
                appendLine(persistedStack)
            }
            val recent = _recentEvents.value
            if (recent.isNotEmpty()) {
                appendLine("Recent $TAG Events (${recent.size}):")
                recent.takeLast(12).forEach { appendLine("  $it") }
            }
        }.trim()
    }

    internal fun resetForTesting() {
        isConnectingFlag = false
        isConnectedFlag = false
        hasOperationalProofFlag = false
        malfunctionFlag = false
        persistedLoaded = true
        lastReportedPackage = null
        _lastFailureReason.value = null
        _activePackage.value = null
        _healthState.value = AccessibilityHealthState.PERMISSION_DISABLED
        _statusDetail.value = "Accessibility permission is disabled"
        synchronized(lock) {
            ringLogs.clear()
            _recentEvents.value = emptyList()
        }
    }

    private fun recordMalfunction(context: Context, summary: String, stack: String?) {
        malfunctionFlag = true
        _lastFailureReason.value = summary
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MALFUNCTION, true)
                .putString(KEY_LAST_FAILURE, summary)
                .putString(KEY_LAST_STACK, stack)
                .putLong(KEY_LAST_FAILURE_TS, System.currentTimeMillis())
                .commit()
        }
    }

    private fun clearPersistedMalfunction(context: Context) {
        if (!malfunctionFlag && _lastFailureReason.value == null) return
        malfunctionFlag = false
        _lastFailureReason.value = null
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MALFUNCTION, false)
                .remove(KEY_LAST_FAILURE)
                .remove(KEY_LAST_STACK)
                .apply()
        }
    }

    private fun loadPersistedState(context: Context) {
        if (persistedLoaded) return
        synchronized(lock) {
            if (persistedLoaded) return
            runCatching {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                malfunctionFlag = prefs.getBoolean(KEY_MALFUNCTION, false)
                _lastFailureReason.value = prefs.getString(KEY_LAST_FAILURE, null)
            }
            persistedLoaded = true
        }
    }

    private fun sanitizeFailureSummary(throwable: Throwable): String {
        val cls = throwable.javaClass.simpleName.ifBlank { "Throwable" }
        // Keep only technical exception class and bounded first line, scrubbing quoted user strings
        val rawMsg = throwable.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.replace(Regex("\"[^\"]{1,120}\""), "\"<redacted>\"")
            ?.take(140)
            .orEmpty()
        return if (rawMsg.isNotBlank()) "$cls: $rawMsg" else cls
    }

    private fun appendRingLog(level: String, message: String) {
        val entry = "[$level] $message"
        synchronized(lock) {
            if (ringLogs.size >= MAX_RECENT_LOGS) {
                ringLogs.removeFirst()
            }
            ringLogs.addLast(entry)
            _recentEvents.value = ringLogs.toList()
        }
    }

    private fun logInfo(message: String) {
        appendRingLog("I", message)
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarn(message: String) {
        appendRingLog("W", message)
        runCatching { Log.w(TAG, message) }
    }

    private fun logError(message: String, throwable: Throwable) {
        appendRingLog("E", "$message (${throwable.javaClass.simpleName})")
        runCatching { Log.e(TAG, message, throwable) }
    }

    private class JarvisUncaughtExceptionReporter(
        private val appContext: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                val summary = "Uncaught ${throwable.javaClass.simpleName} on ${thread.name}: " +
                    (throwable.message?.take(120) ?: "no message")
                val trace = throwable.stackTraceToString().lineSequence().take(18).joinToString("\n")
                runCatching { Log.e(TAG, "UNCAUGHT_EXCEPTION: $summary", throwable) }
                runCatching {
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_MALFUNCTION, true)
                        .putString(KEY_LAST_FAILURE, summary)
                        .putString(KEY_LAST_STACK, trace)
                        .putLong(KEY_LAST_FAILURE_TS, System.currentTimeMillis())
                        .commit()
                }
            } catch (_: Throwable) {
                // Never mask or replace Android's crash dispatch
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
