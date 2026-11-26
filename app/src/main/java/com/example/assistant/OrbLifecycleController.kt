package com.example.assistant

import com.example.domain.model.MiniOrbVisualState
import com.example.domain.model.TaskExecutionProgress
import com.example.domain.model.TaskStage
import com.example.voice.VoicePipelinePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pure state-mapping helper so Mini Orb visual states strictly mirror genuine assistant state.
 */
object MiniOrbStateMapper {

    fun resolve(
        isMuted: Boolean,
        voicePhase: VoicePipelinePhase,
        isAiProcessing: Boolean,
        taskProgress: TaskExecutionProgress,
        wakeFlashActive: Boolean,
        terminalFlash: MiniOrbVisualState?
    ): MiniOrbVisualState {
        if (isMuted) return MiniOrbVisualState.MUTED
        if (wakeFlashActive && voicePhase != VoicePipelinePhase.SPEAKING_RESPONSE) {
            return MiniOrbVisualState.WAKE_DETECTED
        }
        if (terminalFlash == MiniOrbVisualState.SUCCESS || terminalFlash == MiniOrbVisualState.ERROR) {
            return terminalFlash
        }
        return when (taskProgress.stage) {
            TaskStage.UNDERSTANDING, TaskStage.PLANNING -> MiniOrbVisualState.UNDERSTANDING
            TaskStage.EXECUTING, TaskStage.PERMISSION_CHECK, TaskStage.VERIFYING -> MiniOrbVisualState.EXECUTING
            TaskStage.COMPLETED -> MiniOrbVisualState.SUCCESS
            TaskStage.FAILED -> MiniOrbVisualState.ERROR
            else -> when {
                voicePhase == VoicePipelinePhase.SPEAKING_RESPONSE -> MiniOrbVisualState.SPEAKING
                isAiProcessing -> MiniOrbVisualState.UNDERSTANDING
                voicePhase == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE -> MiniOrbVisualState.LISTENING
                voicePhase == VoicePipelinePhase.WAKE_WORD_STANDBY -> MiniOrbVisualState.STANDBY
                else -> MiniOrbVisualState.STANDBY
            }
        }
    }
}

/**
 * Manages Mini Orb transient state flashes (WAKE_DETECTED, SUCCESS, ERROR), expansion state,
 * and automatic inactivity collapse while keeping hands-free wake detection intact.
 */
class OrbLifecycleController(private val scope: CoroutineScope) {

    private val _isOrbExpanded = MutableStateFlow(false)
    val isOrbExpanded: StateFlow<Boolean> = _isOrbExpanded.asStateFlow()

    private val _wakeFlashActive = MutableStateFlow(false)
    val wakeFlashActive: StateFlow<Boolean> = _wakeFlashActive.asStateFlow()

    private val _terminalFlash = MutableStateFlow<MiniOrbVisualState?>(null)
    val terminalFlash: StateFlow<MiniOrbVisualState?> = _terminalFlash.asStateFlow()

    private val _inactivityTimeoutSeconds = MutableStateFlow(45)
    val inactivityTimeoutSeconds: StateFlow<Int> = _inactivityTimeoutSeconds.asStateFlow()

    private var inactivityJob: Job? = null
    private var pulseJob: Job? = null
    private var stayAwakePinned = false
    private var onAutoSleepCallback: (() -> Unit)? = null

    fun setOnAutoSleep(callback: () -> Unit) {
        onAutoSleepCallback = callback
    }

    fun setInactivityTimeoutSeconds(seconds: Int) {
        _inactivityTimeoutSeconds.value = seconds.coerceIn(15, 300)
    }

    fun setStayAwakePinned(pinned: Boolean) {
        stayAwakePinned = pinned
        if (pinned) {
            inactivityJob?.cancel()
        } else {
            touchActivity()
        }
    }

    fun setExpanded(expanded: Boolean) {
        _isOrbExpanded.value = expanded
        if (expanded) {
            touchActivity()
        } else {
            inactivityJob?.cancel()
        }
    }

    fun triggerWakePulse() {
        pulseJob?.cancel()
        _wakeFlashActive.value = true
        pulseJob = scope.launch {
            delay(1100L)
            _wakeFlashActive.value = false
        }
        touchActivity()
    }

    fun triggerSuccessPulse() {
        pulseJob?.cancel()
        _terminalFlash.value = MiniOrbVisualState.SUCCESS
        pulseJob = scope.launch {
            delay(1600L)
            _terminalFlash.value = null
        }
        touchActivity()
    }

    fun triggerErrorPulse() {
        pulseJob?.cancel()
        _terminalFlash.value = MiniOrbVisualState.ERROR
        pulseJob = scope.launch {
            delay(2000L)
            _terminalFlash.value = null
        }
        touchActivity()
    }

    fun touchActivity() {
        inactivityJob?.cancel()
        if (stayAwakePinned || !_isOrbExpanded.value) return
        val timeoutMs = _inactivityTimeoutSeconds.value * 1000L
        inactivityJob = scope.launch {
            delay(timeoutMs)
            if (!stayAwakePinned) {
                _isOrbExpanded.value = false
                onAutoSleepCallback?.invoke()
            }
        }
    }

    fun onInteractionBusy(busy: Boolean) {
        if (busy) {
            inactivityJob?.cancel()
        } else {
            touchActivity()
        }
    }
}
