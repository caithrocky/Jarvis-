package com.example.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JarvisApplication
import com.example.assistant.JarvisAccessibilityService
import com.example.assistant.JarvisSystemAssistant
import com.example.data.local.AiActivityLogEntity
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.JarvisUserSettings
import com.example.data.local.MemoryFactEntity
import com.example.domain.model.ApiIntegrationStatus
import com.example.domain.model.OrbState
import com.example.domain.model.VoicePersonality
import com.example.security.SecretsConfig
import com.example.security.SecureCredentialStore
import com.example.voice.JarvisFloatingOrbService
import com.example.voice.JarvisVoiceService
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
    val isAiProcessing: Boolean = false,
    val streamingReply: String = "",
    val apiStatuses: List<ApiIntegrationStatus> = emptyList(),
    val nimReachable: Boolean? = null,
    val diagnosticResult: String? = null,
    val snackbarMessage: String? = null,
    val hasOverlayPermission: Boolean = false,
    val isScreenHelperEnabled: Boolean = false,
    val signupProfile: SecureCredentialStore.SignupProfile = SecureCredentialStore.SignupProfile(),
    val savedCredentials: List<SecureCredentialStore.SavedCredentialEntry> = emptyList(),
    val manualUpdateCheckTrigger: Int = 0,
    val hasRuntimeNvidiaKeyOverride: Boolean = false,
    val hasBuildConfigNvidiaKey: Boolean = false
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApplication
    private val localRepo = app.localRepository
    private val prefsRepo = app.preferencesRepository
    private val nimClient = app.nimClient
    private val magpieTtsClient = app.magpieTtsClient
    private val voiceEngine = app.voiceEngine
    private val coordinator = app.commandCoordinator
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
        refreshSystemCapabilitiesState()
        refreshApiStatuses()
    }

    fun refreshSystemCapabilitiesState() {
        val overlayOk = Settings.canDrawOverlays(app)
        val screenOk = JarvisAccessibilityService.isConnected.value
        val profile = credentialStore.getSignupProfile()
        val credentials = credentialStore.getSavedCredentials()
        val hasRuntimeKey = !credentialStore.getDecrypted(SecureCredentialStore.KEY_NVIDIA_NIM_API_OVERRIDE).isNullOrBlank()
        val hasBuildKey = SecretsConfig.hasBuildConfigNvidiaNim
        _uiState.update {
            it.copy(
                hasOverlayPermission = overlayOk,
                isScreenHelperEnabled = screenOk,
                signupProfile = profile,
                savedCredentials = credentials,
                hasRuntimeNvidiaKeyOverride = hasRuntimeKey,
                hasBuildConfigNvidiaKey = hasBuildKey
            )
        }
    }

    private fun observeCoordinatorState() {
        viewModelScope.launch {
            combine(
                coordinator.isProcessing,
                coordinator.streamingReply
            ) { processing, streaming -> processing to streaming }
                .collect { (processing, streaming) ->
                    _uiState.update {
                        it.copy(
                            isAiProcessing = processing,
                            streamingReply = streaming
                        )
                    }
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
                    val orb = voiceEngine.suggestedOrbState(state.isAiProcessing, hasCriticalAlert = false)
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
            settings.collect { s ->
                voiceEngine.configureVoice(s.voicePersonality, s.voiceSpeed, s.useNvidiaMagpieVoice)
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

    fun onOrbTapped(hasMicPermission: Boolean) {
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
        JarvisFloatingOrbService.start(app, expandBubble = true)
        _uiState.update { it.copy(snackbarMessage = "Floating mini-orb launched over screen.") }
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
