package com.example.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JarvisApplication
import com.example.assistant.JarvisAccessibilityService
import com.example.assistant.JarvisSystemAssistant
import com.example.assistant.NotificationVaultBridge
import com.example.data.local.AiActivityLogEntity
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.JarvisUserSettings
import com.example.data.local.MemoryFactEntity
import com.example.domain.model.ApiIntegrationStatus
import com.example.domain.model.AssistantUiMode
import com.example.domain.model.OrbState
import com.example.domain.model.TaskExecutionProgress
import com.example.domain.model.TaskStage
import com.example.domain.model.VoicePersonality
import com.example.security.SecretsConfig
import com.example.security.SecureCredentialStore
import com.example.voice.JarvisFloatingOrbService
import com.example.voice.JarvisVoiceService
import com.example.voice.SpeakerMatchState
import com.example.voice.VoicePipelinePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class JarvisSection(val route: String, val label: String) {
    HOME("home", "Home"),
    JARVIS("jarvis", "JARVIS"),
    VAULT("vault", "Credentials"),
    HISTORY("history", "Activity"),
    SETTINGS("settings", "Settings")
}

data class JarvisUiState(
    val selectedSection: JarvisSection = JarvisSection.HOME,
    val orbState: OrbState = OrbState.IDLE,
    val audioLevel: Float = 0f,
    val voiceStatusDetail: String = "Standby",
    val partialVoiceText: String = "",
    val bluetoothAudioReady: Boolean = false,
    val isMicMuted: Boolean = false,
    val isAiProcessing: Boolean = false,
    val streamingReply: String = "",
    val apiStatuses: List<ApiIntegrationStatus> = emptyList(),
    val nimReachable: Boolean? = null,
    val diagnosticResult: String? = null,
    val snackbarMessage: String? = null,
    val hasOverlayPermission: Boolean = false,
    val isScreenHelperEnabled: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val unreadNotificationCount: Int = 0,
    val activeUiMode: AssistantUiMode = AssistantUiMode.FULL_APP,
    val taskProgress: TaskExecutionProgress = TaskExecutionProgress(),
    val signupProfile: SecureCredentialStore.SignupProfile = SecureCredentialStore.SignupProfile(),
    val savedCredentials: List<SecureCredentialStore.SavedCredentialEntry> = emptyList(),
    val manualUpdateCheckTrigger: Int = 0,
    val hasRuntimeNvidiaKeyOverride: Boolean = false,
    val hasBuildConfigNvidiaKey: Boolean = false,
    val learnedAppsCount: Int = 0,
    val learnedWorkflowsCount: Int = 0,
    val ownerHabitsCount: Int = 0,
    val voiceEnrolled: Boolean = false,
    val speakerStatusSummary: String = "Owner Voice ID: Not enrolled",
    val lastSpeakerState: SpeakerMatchState? = null,
    val speakerConfidence: Float = 0f,
    val voiceEnrollmentProgress: String? = null,
    val autonomousStepStatus: String = "",
    val pendingConfirmationSummary: String? = null
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApplication
    private val localRepo = app.localRepository
    private val prefsRepo = app.preferencesRepository
    private val nimClient = app.nimClient
    private val magpieTtsClient = app.magpieTtsClient
    private val voiceEngine = app.voiceEngine
    private val coordinator = app.commandCoordinator
    private val deviceLearningEngine = app.deviceLearningEngine
    private val credentialStore = app.credentialStore

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    val settings: StateFlow<JarvisUserSettings> = prefsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), JarvisUserSettings())

    val messages: StateFlow<List<ConversationMessageEntity>> = localRepo.messages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryFactEntity>> = localRepo.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aiLogs: StateFlow<List<AiActivityLogEntity>> = localRepo.aiLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        coordinator.attachVoiceEngineCallbacks()
        observeVoiceTelemetry()
        observeCoordinatorState()
        observeLearningAndBiometrics()
        refreshSystemCapabilitiesState()
        refreshApiStatuses()
    }

    fun refreshSystemCapabilitiesState() {
        val overlayOk = Settings.canDrawOverlays(app)
        val screenOk = JarvisAccessibilityService.isConnected.value
        val notifOk = NotificationVaultBridge.isAccessGranted(app)
        val profile = credentialStore.getSignupProfile()
        val credentials = credentialStore.getSavedCredentials()
        val hasRuntimeKey = !credentialStore.getDecrypted(SecureCredentialStore.KEY_NVIDIA_NIM_API_OVERRIDE).isNullOrBlank()
        val hasBuildKey = SecretsConfig.hasBuildConfigNvidiaNim
        val voiceEnrolled = voiceEngine.isOwnerVoiceEnrolled()
        _uiState.update {
            it.copy(
                hasOverlayPermission = overlayOk,
                isScreenHelperEnabled = screenOk,
                hasNotificationAccess = notifOk,
                signupProfile = profile,
                savedCredentials = credentials,
                hasRuntimeNvidiaKeyOverride = hasRuntimeKey,
                hasBuildConfigNvidiaKey = hasBuildKey,
                voiceEnrolled = voiceEnrolled
            )
        }
    }

    private fun observeCoordinatorState() {
        viewModelScope.launch {
            combine(
                coordinator.isProcessing,
                coordinator.streamingReply,
                coordinator.autonomousStepStatus,
                coordinator.pendingConfirmationSummary,
                coordinator.taskProgress
            ) { processing, streaming, stepStatus, pendingConfirm, taskProg ->
                Tuple5(processing, streaming, stepStatus, pendingConfirm, taskProg)
            }.collect { (processing, streaming, stepStatus, pendingConfirm, taskProg) ->
                _uiState.update { state ->
                    val orb = voiceEngine.suggestedOrbState(
                        isAiProcessing = processing,
                        hasCriticalAlert = false,
                        taskStage = taskProg.stage
                    )
                    state.copy(
                        isAiProcessing = processing,
                        streamingReply = streaming,
                        autonomousStepStatus = stepStatus,
                        pendingConfirmationSummary = pendingConfirm,
                        taskProgress = taskProg,
                        orbState = orb
                    )
                }
            }
        }
        viewModelScope.launch {
            coordinator.sessionManager.activeMode.collect { mode ->
                _uiState.update { it.copy(activeUiMode = mode) }
            }
        }
        viewModelScope.launch {
            NotificationVaultBridge.notifications.collect { list ->
                _uiState.update { it.copy(unreadNotificationCount = list.size) }
            }
        }
        viewModelScope.launch {
            coordinator.navigationEvents.collect { navTarget ->
                val section = when (navTarget) {
                    "home", "dashboard" -> JarvisSection.HOME
                    "jarvis", "chat" -> JarvisSection.JARVIS
                    "vault", "credentials", "plugin" -> JarvisSection.VAULT
                    "history", "activity" -> JarvisSection.HISTORY
                    "settings" -> JarvisSection.SETTINGS
                    else -> JarvisSection.HOME
                }
                navigateTo(section)
            }
        }
        viewModelScope.launch {
            JarvisAccessibilityService.isConnected.collect { connected ->
                _uiState.update { it.copy(isScreenHelperEnabled = connected) }
            }
        }
    }

    private fun observeLearningAndBiometrics() {
        viewModelScope.launch {
            combine(
                localRepo.learnedApps,
                localRepo.learnedWorkflows,
                localRepo.ownerHabits
            ) { apps, workflows, habits ->
                Triple(apps.size, workflows.size, habits.size)
            }.collect { (appsCount, wfCount, habitsCount) ->
                _uiState.update {
                    it.copy(
                        learnedAppsCount = appsCount,
                        learnedWorkflowsCount = wfCount,
                        ownerHabitsCount = habitsCount
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                voiceEngine.speakerStatusSummary,
                voiceEngine.lastSpeakerState,
                voiceEngine.speakerConfidence
            ) { summary, matchState, confidence ->
                Triple(summary, matchState, confidence)
            }.collect { (summary, matchState, confidence) ->
                _uiState.update {
                    it.copy(
                        voiceEnrolled = voiceEngine.isOwnerVoiceEnrolled(),
                        speakerStatusSummary = summary,
                        lastSpeakerState = matchState,
                        speakerConfidence = confidence
                    )
                }
            }
        }
    }

    private fun observeVoiceTelemetry() {
        viewModelScope.launch {
            combine(
                voiceEngine.phase,
                voiceEngine.rmsLevel,
                voiceEngine.partialTranscript,
                voiceEngine.statusDetail,
                voiceEngine.bluetoothConnected
            ) { phase, rms, partial, detail, bt ->
                Tuple5(phase, rms, partial, detail, bt)
            }.collect { (_, rms, partial, detail, bt) ->
                _uiState.update { state ->
                    val orb = voiceEngine.suggestedOrbState(
                        isAiProcessing = state.isAiProcessing,
                        hasCriticalAlert = false,
                        taskStage = state.taskProgress.stage
                    )
                    state.copy(
                        orbState = orb,
                        audioLevel = rms,
                        partialVoiceText = partial,
                        voiceStatusDetail = detail,
                        bluetoothAudioReady = bt
                    )
                }
            }
        }

        viewModelScope.launch {
            voiceEngine.isMuted.collect { muted ->
                _uiState.update { it.copy(isMicMuted = muted) }
            }
        }

        viewModelScope.launch {
            settings.collect { s ->
                voiceEngine.configureVoice(s.voicePersonality, s.voiceSpeed, s.useNvidiaMagpieVoice)
                voiceEngine.setFluToleranceEnabled(s.voiceFluAdaptationEnabled)
            }
        }
    }

    fun navigateTo(section: JarvisSection) {
        _uiState.update { it.copy(selectedSection = section) }
    }

    fun onAssistTriggered() {
        navigateTo(JarvisSection.JARVIS)
        _uiState.update { it.copy(snackbarMessage = "JARVIS System Assistant ready, Sir.") }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun showStatusSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun triggerManualUpdateCheck() {
        _uiState.update {
            it.copy(
                manualUpdateCheckTrigger = it.manualUpdateCheckTrigger + 1,
                snackbarMessage = "Checking GitHub for the latest JARVIS release..."
            )
        }
    }

    fun saveNvidiaNimKeyOverride(rawKey: String) {
        val cleaned = SecretsConfig.cleanSecret(rawKey)
        if (cleaned.isNullOrBlank()) {
            _uiState.update {
                it.copy(snackbarMessage = "Enter a valid NVIDIA NIM API key (for example starting with nvapi-).")
            }
            return
        }
        credentialStore.putEncrypted(SecureCredentialStore.KEY_NVIDIA_NIM_API_OVERRIDE, cleaned)
        _uiState.update {
            it.copy(
                hasRuntimeNvidiaKeyOverride = true,
                snackbarMessage = "Encrypted NVIDIA NIM key saved on device (persists across app updates)."
            )
        }
        refreshApiStatuses()
        testNvidiaNimConnection()
    }

    fun clearNvidiaNimKeyOverride() {
        credentialStore.remove(SecureCredentialStore.KEY_NVIDIA_NIM_API_OVERRIDE)
        _uiState.update {
            it.copy(
                hasRuntimeNvidiaKeyOverride = false,
                nimReachable = null,
                snackbarMessage = "Device-stored NVIDIA NIM key cleared."
            )
        }
        refreshApiStatuses()
    }

    fun toggleMicMute() {
        val mutedNow = voiceEngine.toggleMute()
        _uiState.update {
            it.copy(
                isMicMuted = mutedNow,
                snackbarMessage = if (mutedNow) "Microphone muted, Sir." else "Microphone unmuted, Sir."
            )
        }
    }

    fun switchToMiniOrb() {
        if (!Settings.canDrawOverlays(app)) {
            _uiState.update { it.copy(snackbarMessage = "Grant 'Display over other apps' to switch to Mini Orb.") }
            return
        }
        coordinator.submitQuery("Switch to Mini Orb", fromVoice = false)
    }

    fun onOrbTapped(hasMicPermission: Boolean) {
        if (voiceEngine.isMuted.value) {
            voiceEngine.setMuted(false)
        }
        val currentPhase = voiceEngine.phase.value
        if (currentPhase == VoicePipelinePhase.SPEAKING_RESPONSE) {
            voiceEngine.interruptSpeech()
            return
        }
        if (!hasMicPermission) {
            _uiState.update {
                it.copy(snackbarMessage = "Grant Microphone permission to talk with JARVIS.")
            }
            return
        }
        if (currentPhase == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE) {
            if (settings.value.wakeWordEnabled) {
                voiceEngine.startAlwaysOnWakeWordLoop()
            } else {
                voiceEngine.stopAllListening()
            }
        } else {
            voiceEngine.startSingleCommandCapture()
        }
    }

    fun toggleAlwaysOnVoice(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setWakeWordEnabled(enabled)
            if (enabled) {
                JarvisVoiceService.start(app)
                localRepo.logAiActivity("VOICE_SERVICE", "Always-on Hey JARVIS listener enabled")
            } else {
                JarvisVoiceService.stop(app)
                voiceEngine.stopAllListening()
                localRepo.logAiActivity("VOICE_SERVICE", "Always-on Hey JARVIS listener stopped")
            }
        }
    }

    fun ensureAlwaysListeningRunning(hasMicPermission: Boolean) {
        if (!hasMicPermission) return
        if (!settings.value.wakeWordEnabled) return
        if (voiceEngine.isMuted.value) return
        if (voiceEngine.phase.value == VoicePipelinePhase.STOPPED) {
            JarvisVoiceService.start(app)
            voiceEngine.startAlwaysOnWakeWordLoop()
        }
    }

    fun sendUserQuery(rawPrompt: String, fromVoice: Boolean = false) {
        coordinator.submitQuery(rawPrompt, fromVoice = fromVoice)
    }

    fun openWebsiteInChrome(urlOrSite: String) {
        val trimmed = urlOrSite.trim()
        if (trimmed.isBlank()) return
        val resolved = JarvisSystemAssistant.extractDirectUrlOrDomain(trimmed)
            ?: JarvisSystemAssistant.websiteDirectory[trimmed.lowercase()]
        if (resolved != null) {
            val outcome = JarvisSystemAssistant.openWebsiteInChrome(app, resolved)
            _uiState.update { it.copy(snackbarMessage = outcome.replyText) }
        } else {
            navigateTo(JarvisSection.JARVIS)
            coordinator.submitQuery("Open $trimmed website in Chrome", fromVoice = false)
        }
    }

    // Memory Management
    fun addManualMemory(summary: String) {
        if (summary.isBlank()) return
        viewModelScope.launch {
            localRepo.saveMemory("preference", summary.trim())
        }
    }

    fun updateMemory(id: Long, summary: String) {
        if (summary.isBlank()) return
        viewModelScope.launch {
            localRepo.updateMemory(id, summary.trim())
            _uiState.update { it.copy(snackbarMessage = "Memory updated, Sir.") }
        }
    }

    fun buildMemoriesExportText(): String {
        val list = memories.value
        if (list.isEmpty()) return "J.A.R.V.I.S Memory Vault is currently empty."
        return buildString {
            appendLine("J.A.R.V.I.S Long-Term Memory Export")
            appendLine("====================================")
            list.forEachIndexed { idx, fact ->
                appendLine("${idx + 1}. [${fact.category}] ${fact.summary}")
            }
        }
    }

    fun deleteMemory(id: Long) = viewModelScope.launch { localRepo.deleteMemory(id) }
    fun clearAllMemories() = viewModelScope.launch { localRepo.clearAllMemories() }
    fun clearChatHistory() = viewModelScope.launch { localRepo.clearConversationHistory() }

    // Settings updates
    fun updateModelId(id: String) = viewModelScope.launch { prefsRepo.updateModelId(id) }
    fun updateVoicePersonality(p: VoicePersonality) = viewModelScope.launch { prefsRepo.updateVoicePersonality(p) }
    fun updateVoiceSpeed(speed: Float) = viewModelScope.launch { prefsRepo.updateVoiceSpeed(speed) }
    fun setVoiceOutputEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setVoiceOutputEnabled(enabled) }
    fun setUseNvidiaMagpieVoice(enabled: Boolean) = viewModelScope.launch { prefsRepo.setUseNvidiaMagpieVoice(enabled) }
    fun setMemoryEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setMemoryEnabled(enabled) }
    fun setHighContrastHud(enabled: Boolean) = viewModelScope.launch { prefsRepo.setHighContrastHud(enabled) }
    fun setOrbInactivityTimeoutSeconds(seconds: Int) = viewModelScope.launch {
        prefsRepo.setOrbInactivityTimeoutSeconds(seconds)
    }

    fun setFloatingOrbEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setFloatingOrbEnabled(enabled)
        if (!enabled) {
            coordinator.manualOrbPinned = false
            JarvisFloatingOrbService.stop(app)
        }
    }

    fun launchFloatingOrbNow() {
        if (!Settings.canDrawOverlays(app)) {
            _uiState.update { it.copy(snackbarMessage = "Grant 'Display over other apps' to launch the floating orb.") }
            return
        }
        coordinator.manualOrbPinned = true
        JarvisFloatingOrbService.start(
            app,
            expandBubble = true,
            startListening = voiceEngine.hasMicPermission()
        )
        _uiState.update { it.copy(snackbarMessage = "Voice-first floating orb launched over screen.") }
    }

    fun setShowTapIndicator(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setShowTapIndicator(enabled)
    }

    fun setShowHighlightIndicator(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setShowHighlightIndicator(enabled)
    }

    fun setShowScrollIndicator(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setShowScrollIndicator(enabled)
    }

    fun setVoiceBiometricLockEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setVoiceBiometricLockEnabled(enabled)
    }

    fun setVoiceFluAdaptationEnabled(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setVoiceFluAdaptationEnabled(enabled)
        voiceEngine.setFluToleranceEnabled(enabled)
    }

    fun setAutoLearnDeviceDaily(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setAutoLearnDeviceDaily(enabled)
    }

    fun setFuturisticHudMode(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setFuturisticHudMode(enabled)
    }

    fun enrollOwnerVoice() {
        if (!voiceEngine.hasMicPermission()) {
            _uiState.update { it.copy(snackbarMessage = "Microphone permission is required to enroll your Voice ID, Sir.") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(voiceEnrollmentProgress = "Preparing 3-phrase Voice ID capture...")
            }
            val result = voiceEngine.enrollOwnerVoiceProfile { step, phrase ->
                _uiState.update {
                    it.copy(voiceEnrollmentProgress = "Sample $step/3 — Say: \"$phrase\"")
                }
            }
            result.fold(
                onSuccess = { profile ->
                    localRepo.logAiActivity(
                        "VOICE_ID",
                        "Owner Voice ID enrolled (${profile.sampleCount} samples, baseline ${profile.baselinePitchHz.toInt()}Hz)"
                    )
                    _uiState.update {
                        it.copy(
                            voiceEnrolled = true,
                            voiceEnrollmentProgress = null,
                            snackbarMessage = "Owner Voice ID locked to you, Sir (with flu/vocal-shift adaptation active)."
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            voiceEnrollmentProgress = null,
                            snackbarMessage = err.message ?: "Voice enrollment failed — please try again, Sir."
                        )
                    }
                }
            )
        }
    }

    fun verifyOwnerVoiceNow() {
        if (!voiceEngine.hasMicPermission()) {
            _uiState.update { it.copy(snackbarMessage = "Grant Microphone permission to verify your voice, Sir.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(voiceEnrollmentProgress = "Speak a short sentence now to verify...") }
            val result = voiceEngine.verifyOwnerVoiceBurst(durationMs = 2200L)
            _uiState.update {
                it.copy(
                    voiceEnrollmentProgress = null,
                    snackbarMessage = result.detailMessage
                )
            }
        }
    }

    fun clearOwnerVoice() {
        voiceEngine.clearOwnerVoiceProfile()
        _uiState.update {
            it.copy(
                voiceEnrolled = false,
                voiceEnrollmentProgress = null,
                snackbarMessage = "Owner Voice ID cleared from device vault."
            )
        }
    }

    fun studyDeviceNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(snackbarMessage = "Studying installed apps, screen controls, and daily habits...") }
            val report = deviceLearningEngine.studyDeviceNow(force = true)
            _uiState.update { it.copy(snackbarMessage = report.summaryMessage) }
        }
    }

    fun abortAutonomousTasks() {
        coordinator.abortAllPendingActions()
        _uiState.update { it.copy(snackbarMessage = "All active JARVIS screen actions aborted, Sir.") }
    }

    fun saveSignupProfile(profile: SecureCredentialStore.SignupProfile) {
        credentialStore.saveSignupProfile(profile)
        _uiState.update {
            it.copy(
                signupProfile = profile,
                snackbarMessage = "Personal profile encrypted & saved locally on device."
            )
        }
    }

    fun saveSiteCredential(
        serviceName: String,
        websiteOrApp: String,
        usernameOrEmail: String,
        password: String,
        notes: String
    ) {
        if (serviceName.isBlank() || usernameOrEmail.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(snackbarMessage = "Enter Service Name, Login/Email, and Password.") }
            return
        }
        val updated = credentialStore.upsertSavedCredential(
            SecureCredentialStore.SavedCredentialEntry(
                serviceName = serviceName,
                websiteOrApp = websiteOrApp,
                usernameOrEmail = usernameOrEmail,
                password = password,
                notes = notes
            )
        )
        _uiState.update {
            it.copy(
                savedCredentials = updated,
                snackbarMessage = "Encrypted login for ${serviceName.trim()} saved."
            )
        }
    }

    fun deleteSiteCredential(id: String) {
        val updated = credentialStore.deleteSavedCredential(id)
        _uiState.update {
            it.copy(
                savedCredentials = updated,
                snackbarMessage = "Saved login removed from vault."
            )
        }
    }

    fun openAndSignInWithCredential(entry: SecureCredentialStore.SavedCredentialEntry) {
        val target = entry.websiteOrApp.ifBlank { entry.serviceName }
        coordinator.submitQuery("Sign me in to $target with my permission", fromVoice = false)
    }

    fun previewJarvisVoice() {
        if (voiceEngine.isMediaVolumeZero()) {
            _uiState.update { it.copy(snackbarMessage = "Your device media volume is at 0 — turn volume up to hear JARVIS.") }
        }
        voiceEngine.speak(
            "Yes Sir, my voice systems are online and ready to help whenever you call.",
            followUpListen = false
        )
    }

    fun testNvidiaNimConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(diagnosticResult = "Testing NVIDIA NIM AI & Magpie Neural Voice...") }
            val (nimOk, nimMsg) = nimClient.testConnection(settings.value.selectedModelId)
            val (ttsOk, ttsMsg) = magpieTtsClient.testTtsConnection(settings.value.voicePersonality)

            _uiState.update {
                it.copy(
                    nimReachable = nimOk,
                    diagnosticResult = buildString {
                        appendLine("NVIDIA NIM AI: ${if (nimOk) "OK" else "FAIL"} ($nimMsg)")
                        append("NVIDIA Magpie Voice: ${if (ttsOk) "OK" else "FAIL"} ($ttsMsg)")
                    }
                )
            }
            refreshApiStatuses()
        }
    }

    fun refreshApiStatuses() {
        val state = _uiState.value
        val statuses = listOf(
            ApiIntegrationStatus(
                id = "nvidia_nim",
                name = "NVIDIA NIM AI & Magpie Voice",
                configured = SecretsConfig.hasNvidiaNim,
                reachable = state.nimReachable,
                statusText = when {
                    SecretsConfig.hasBuildConfigNvidiaNim -> "API Key Loaded (BuildConfig)"
                    SecretsConfig.hasNvidiaNim -> "API Key Loaded (Encrypted Device Vault)"
                    else -> "Key Not Configured"
                },
                obtainGuide = "Generate a key at build.nvidia.com and save it in AI Studio Secrets or the Encrypted Device Fallback above.",
                secretKeys = listOf("NVIDIA_NIM_API_KEY")
            )
        )
        _uiState.update { it.copy(apiStatuses = statuses) }
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
