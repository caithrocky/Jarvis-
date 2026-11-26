package com.example.assistant

import com.example.domain.model.AssistantUiMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeModeDecision(
    val targetMode: AssistantUiMode,
    val shouldOpenMiniOrb: Boolean,
    val shouldStartListening: Boolean
)

/**
 * Pure policy rules governing how Mode A (Full App) and Mode B (Floating Mini Orb)
 * share one conversation, microphone session, and task engine without spawning conflicting states.
 */
object AssistantModePolicy {

    fun decideOnWakeWord(
        appInForeground: Boolean,
        floatingOrbEnabled: Boolean,
        overlayPermissionGranted: Boolean
    ): WakeModeDecision {
        // Rule 2.2: Never auto-switch to Mini Orb if Full App is already open & visible.
        if (appInForeground) {
            return WakeModeDecision(
                targetMode = AssistantUiMode.FULL_APP,
                shouldOpenMiniOrb = false,
                shouldStartListening = true
            )
        }
        // Rule 2.4: When Full App is closed/backgrounded and wake-word triggers, summon Mini Orb.
        val canSpawnOrb = floatingOrbEnabled && overlayPermissionGranted
        return WakeModeDecision(
            targetMode = if (canSpawnOrb) AssistantUiMode.MINI_ORB else AssistantUiMode.FULL_APP,
            shouldOpenMiniOrb = canSpawnOrb,
            shouldStartListening = true
        )
    }

    fun isSwitchToMiniOrbPhrase(lowerText: String): Boolean {
        val t = lowerText.trim()
        return t.contains("open mini orb") ||
            t.contains("switch to mini orb") ||
            t.contains("activate floating mode") ||
            t.contains("minimize jarvis") ||
            t.contains("go to floating mode") ||
            t.contains("show your floating assistant") ||
            t.contains("move to the mini orb") ||
            t.contains("move to mini orb") ||
            t.contains("stay available in the background") ||
            t.contains("open floating orb") ||
            t.contains("show floating orb") ||
            t.contains("minimize to orb")
    }

    fun isOpenFullAppPhrase(lowerText: String): Boolean {
        val t = lowerText.trim()
        return t.contains("open full app") ||
            t.contains("open the full app") ||
            t.contains("switch to full app") ||
            t.contains("maximize jarvis") ||
            t.contains("go back to full app") ||
            t.contains("expand full app")
    }
}

sealed interface SessionUiDirective {
    data object MinimizeFullAppToBackground : SessionUiDirective
    data object BringFullAppToForeground : SessionUiDirective
}

/**
 * Shared coordinator between Mode A (Full Jarvis App) and Mode B (Floating Mini Orb).
 */
class AssistantSessionManager {

    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _activeMode = MutableStateFlow(AssistantUiMode.FULL_APP)
    val activeMode: StateFlow<AssistantUiMode> = _activeMode.asStateFlow()

    private val _stayAwakePinned = MutableStateFlow(false)
    val stayAwakePinned: StateFlow<Boolean> = _stayAwakePinned.asStateFlow()

    private val _directives = MutableSharedFlow<SessionUiDirective>(extraBufferCapacity = 8)
    val directives: SharedFlow<SessionUiDirective> = _directives.asSharedFlow()

    fun updateForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
        if (inForeground) {
            _activeMode.value = AssistantUiMode.FULL_APP
        }
    }

    fun onExplicitSwitchToMiniOrb() {
        _activeMode.value = AssistantUiMode.MINI_ORB
        _directives.tryEmit(SessionUiDirective.MinimizeFullAppToBackground)
    }

    fun onExplicitSwitchToFullApp() {
        _activeMode.value = AssistantUiMode.FULL_APP
        _stayAwakePinned.value = false
        _directives.tryEmit(SessionUiDirective.BringFullAppToForeground)
    }

    fun setStayAwakePinned(pinned: Boolean) {
        _stayAwakePinned.value = pinned
    }

    fun decideWakeTransition(
        floatingOrbEnabled: Boolean,
        overlayPermissionGranted: Boolean
    ): WakeModeDecision {
        val decision = AssistantModePolicy.decideOnWakeWord(
            appInForeground = _isAppInForeground.value,
            floatingOrbEnabled = floatingOrbEnabled,
            overlayPermissionGranted = overlayPermissionGranted
        )
        _activeMode.value = decision.targetMode
        return decision
    }
}
