package com.example.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import com.example.MainActivity
import com.example.data.local.JarvisLocalRepository
import com.example.data.local.JarvisPreferencesRepository
import com.example.data.local.JarvisUserSettings
import com.example.data.local.ScheduledAutomationEntity
import com.example.data.remote.JarvisAiResult
import com.example.data.remote.NewsAndResearchEngine
import com.example.data.remote.NvidiaNimClient
import com.example.data.remote.WebKnowledgeClient
import com.example.domain.model.JarvisChoiceActivity
import com.example.domain.model.TaskExecutionProgress
import com.example.domain.model.TaskStage
import com.example.security.SecureCredentialStore
import com.example.voice.JarvisFloatingOrbService
import com.example.voice.JarvisVoiceEngine
import com.example.voice.SpeakerMatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

private enum class MessageStage {
    AWAITING_CONTACT_CHOICE,
    AWAITING_MESSAGE_TEXT,
    AWAITING_SEND_CONFIRMATION
}

private data class PendingMessageSession(
    val platform: String,
    val contactQuery: String,
    val selectedContactName: String = "",
    val draftMessage: String = "",
    val stage: MessageStage,
    val candidates: List<ContactCandidateMatch> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis()
)

private data class PendingUnlockSession(
    val createdAtMs: Long = System.currentTimeMillis(),
    val attemptsRemaining: Int = 3
)

private data class PendingInstallSession(
    val appQuery: String,
    val capabilityLabel: String,
    val candidates: List<DiscoveredAppCandidate>,
    val createdAtMs: Long = System.currentTimeMillis()
)

private data class DirectCommandOutcome(
    val reply: String,
    val label: String,
    val success: Boolean = true
)

/**
 * Shared command, messaging, autonomous UI agent & AI execution brain used by both
 * MainActivity/ViewModel (Mode A) and the voice-first Floating Mini-Orb plugin (Mode B).
 */
class JarvisCommandCoordinator(
    private val context: Context,
    private val localRepo: JarvisLocalRepository,
    private val prefsRepo: JarvisPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val webClient: WebKnowledgeClient,
    private val nimClient: NvidiaNimClient,
    private val voiceEngine: JarvisVoiceEngine,
    private val deviceLearningEngine: DeviceLearningEngine,
    private val appDiscoveryEngine: AppDiscoveryEngine,
    private val newsEngine: NewsAndResearchEngine,
    private val offlineModelManager: OfflineModelManager? = null,
    private val localLfmEngine: LocalLfmLlmEngine? = null
) {
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            AccessibilityDiagnostics.coroutineExceptionHandler("JarvisCommandCoordinator")
    )

    val sessionManager = AssistantSessionManager()
    val orbLifecycle = OrbLifecycleController(scope)

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    @Volatile
    var manualOrbPinned: Boolean = false

    @Volatile
    private var awaitingWebsiteLinkFor: String? = null

    @Volatile
    private var pendingMessageSession: PendingMessageSession? = null

    @Volatile
    private var pendingUnlockSession: PendingUnlockSession? = null

    @Volatile
    private var pendingInstallSession: PendingInstallSession? = null

    @Volatile
    private var abortRequested: Boolean = false

    private var taskResetJob: Job? = null

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _suppressScreenAwakeTemporarily = MutableStateFlow(false)
    val suppressScreenAwakeTemporarily: StateFlow<Boolean> = _suppressScreenAwakeTemporarily.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _lastAssistantReply = MutableStateFlow("Always listening for you, Sir — speak naturally anytime.")
    val lastAssistantReply: StateFlow<String> = _lastAssistantReply.asStateFlow()

    private val _lastUserPrompt = MutableStateFlow("")
    val lastUserPrompt: StateFlow<String> = _lastUserPrompt.asStateFlow()

    private val _aiHudHeadline = MutableStateFlow<String?>(null)
    val aiHudHeadline: StateFlow<String?> = _aiHudHeadline.asStateFlow()

    private val _autonomousStepStatus = MutableStateFlow("")
    val autonomousStepStatus: StateFlow<String> = _autonomousStepStatus.asStateFlow()

    private val _pendingConfirmationSummary = MutableStateFlow<String?>(null)
    val pendingConfirmationSummary: StateFlow<String?> = _pendingConfirmationSummary.asStateFlow()

    private val _taskProgress = MutableStateFlow(TaskExecutionProgress())
    val taskProgress: StateFlow<TaskExecutionProgress> = _taskProgress.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val navigationEvents: SharedFlow<String> = _navigationEvents.asSharedFlow()

    init {
        scope.launch {
            prefsRepo.settingsFlow.collectLatest { settings ->
                voiceEngine.setFluToleranceEnabled(settings.voiceFluAdaptationEnabled)
                orbLifecycle.setInactivityTimeoutSeconds(settings.orbInactivityTimeoutSeconds)
                JarvisAccessibilityService.activeInstance()?.applyVisualFeedbackSettings(
                    showTap = settings.showTapIndicator,
                    showHighlight = settings.showHighlightIndicator,
                    showScroll = settings.showScrollIndicator
                )
            }
        }
        startScheduledAutomationWatcher()
    }

    fun onForegroundChanged(inForeground: Boolean) {
        isAppInForeground = inForeground
        sessionManager.updateForeground(inForeground)
        voiceEngine.setUserFacingActive(inForeground || manualOrbPinned)
        scope.launch {
            val settings = prefsRepo.settingsFlow.first()
            if (!inForeground) {
                val shouldShowOrb = (settings.floatingOrbEnabled && settings.wakeWordEnabled) || manualOrbPinned
                if (shouldShowOrb && Settings.canDrawOverlays(context)) {
                    JarvisFloatingOrbService.start(context, expandBubble = false)
                }
            } else if (!manualOrbPinned) {
                JarvisFloatingOrbService.stop(context)
            }
        }
    }

    fun attachVoiceEngineCallbacks() {
        voiceEngine.setCallbacks(
            onWake = { remainder ->
                scope.launch {
                    localRepo.logAiActivity("WAKE_WORD", "Activated via contextual \"Hey JARVIS\"")
                    orbLifecycle.triggerWakePulse()
                    val settings = prefsRepo.settingsFlow.first()
                    val decision = sessionManager.decideWakeTransition(
                        floatingOrbEnabled = settings.floatingOrbEnabled,
                        overlayPermissionGranted = Settings.canDrawOverlays(context)
                    )
                    // Rule 2.2 / 2.4: Never launch Mini Orb if Full App is open; launch it expanded when closed.
                    // Note: JarvisVoiceEngine speaks "Yes Sir, I'm listening." with followUpListen = true on bare wake;
                    // passing startListening = false avoids interrupting that greeting mid-sentence.
                    if (decision.shouldOpenMiniOrb) {
                        JarvisFloatingOrbService.start(
                            context,
                            expandBubble = true,
                            startListening = false
                        )
                    }
                }
            },
            onCommand = { spokenText ->
                submitQuery(spokenText, fromVoice = true)
            },
            onBargeIn = {
                orbLifecycle.touchActivity()
                updateTaskProgress(TaskStage.RECOGNIZING, "Interrupted", "Listening to you, Sir...")
            }
        )
    }

    fun abortAllPendingActions() {
        abortRequested = true
        pendingMessageSession = null
        pendingUnlockSession = null
        pendingInstallSession = null
        awaitingWebsiteLinkFor = null
        _pendingConfirmationSummary.value = null
        _autonomousStepStatus.value = ""
        _isProcessing.value = false
        JarvisAccessibilityService.activeInstance()?.clearVisualOverlays()
        voiceEngine.interruptSpeech()
        updateTaskProgress(TaskStage.CANCELLED, "Stopped", "Standing down, Sir", autoClear = true)
        _lastAssistantReply.value = "Standing down right away, Sir."
    }

    private fun updateTaskProgress(
        stage: TaskStage,
        title: String,
        detail: String,
        stepIndex: Int = 0,
        totalSteps: Int = 0,
        autoClear: Boolean = false
    ) {
        taskResetJob?.cancel()
        _taskProgress.value = TaskExecutionProgress(
            stage = stage,
            title = title,
            detail = detail,
            stepIndex = stepIndex,
            totalSteps = totalSteps
        )
        if (stage == TaskStage.COMPLETED) {
            orbLifecycle.triggerSuccessPulse()
        } else if (stage == TaskStage.FAILED) {
            orbLifecycle.triggerErrorPulse()
        }
        if (autoClear) {
            taskResetJob = scope.launch {
                delay(1800L)
                _taskProgress.value = TaskExecutionProgress()
            }
        }
    }

    fun submitQuery(
        rawPrompt: String,
        fromVoice: Boolean = false
    ) {
        val prompt = rawPrompt.trim()
        if (prompt.isBlank()) return
        val lower = prompt.lowercase(Locale.US)
        orbLifecycle.touchActivity()

        if (lower == "stop" || lower == "abort" || lower == "stop jarvis" || lower == "cancel action") {
            abortAllPendingActions()
            scope.launch {
                val settings = prefsRepo.settingsFlow.first()
                finishDirectReply("Stopped everything right away, Sir.", "Owner Abort", settings, fromVoice, followUpListen = false)
            }
            return
        }

        val normalizedWrapUp = lower.replace(Regex("[!?.,]+$"), "").trim()
        if (pendingMessageSession == null && pendingUnlockSession == null && pendingInstallSession == null && awaitingWebsiteLinkFor == null && normalizedWrapUp in setOf(
                "that's all", "thats all", "that will be all", "nothing else",
                "thank you jarvis", "thanks jarvis", "bye jarvis", "goodbye jarvis",
                "stop listening", "go to standby", "you can sleep"
            )
        ) {
            scope.launch {
                val settings = prefsRepo.settingsFlow.first()
                finishDirectReply(
                    "Anytime, Sir. I'll stay quietly on standby—just say my name when you need me.",
                    "Standby",
                    settings,
                    fromVoice,
                    followUpListen = false
                )
            }
            return
        }

        abortRequested = false
        _lastUserPrompt.value = prompt
        _aiHudHeadline.value = JarvisHudIntelligence.summarizePromptAsHeadline(prompt).takeIf { it.isNotBlank() }
        updateTaskProgress(TaskStage.UNDERSTANDING, "Analyzing Request", prompt.take(48))

        scope.launch {
            val currentSettings = prefsRepo.settingsFlow.first()
            voiceEngine.configureVoice(
                currentSettings.voicePersonality,
                currentSettings.voiceSpeed,
                currentSettings.useNvidiaMagpieVoice
            )
            JarvisAccessibilityService.activeInstance()?.applyVisualFeedbackSettings(
                showTap = currentSettings.showTapIndicator,
                showHighlight = currentSettings.showHighlightIndicator,
                showScroll = currentSettings.showScrollIndicator
            )

            localRepo.saveMessage(
                role = "user",
                content = prompt,
                modelUsed = currentSettings.selectedModelId,
                isVoice = fromVoice
            )

            // 0. Active interactive messaging session (disambiguation / message entry / send confirm)
            val activeMsgSession = pendingMessageSession
            if (activeMsgSession != null && System.currentTimeMillis() - activeMsgSession.createdAtMs < 180_000L) {
                val handledReply = handlePendingMessageSessionTurn(prompt, activeMsgSession, currentSettings, fromVoice)
                if (handledReply != null) {
                    updateTaskProgress(TaskStage.COMPLETED, handledReply.second, handledReply.first.take(64), autoClear = true)
                    finishDirectReply(handledReply.first, handledReply.second, currentSettings, fromVoice)
                    return@launch
                }
            } else {
                pendingMessageSession = null
                if (pendingUnlockSession == null) {
                    _pendingConfirmationSummary.value = null
                }
            }

            // 0b. Active Safe Word Unlock verification session
            val activeUnlockSession = pendingUnlockSession
            if (activeUnlockSession != null && System.currentTimeMillis() - activeUnlockSession.createdAtMs < 90_000L) {
                val unlockOutcome = handlePendingUnlockTurn(prompt, activeUnlockSession, currentSettings, fromVoice)
                if (unlockOutcome != null) {
                    updateTaskProgress(
                        if (unlockOutcome.success) TaskStage.COMPLETED else TaskStage.FAILED,
                        unlockOutcome.label,
                        unlockOutcome.reply.take(64),
                        autoClear = true
                    )
                    finishDirectReply(unlockOutcome.reply, unlockOutcome.label, currentSettings, fromVoice)
                    return@launch
                }
            } else if (pendingUnlockSession != null) {
                pendingUnlockSession = null
                _pendingConfirmationSummary.value = null
            }

            // 0c. Pending app installation confirmation (e.g. missing Clock / Trading / Marketing app)
            val activeInstallSession = pendingInstallSession
            if (activeInstallSession != null && System.currentTimeMillis() - activeInstallSession.createdAtMs < 120_000L) {
                val installOutcome = handlePendingInstallTurn(prompt, activeInstallSession)
                if (installOutcome != null) {
                    updateTaskProgress(
                        if (installOutcome.success) TaskStage.COMPLETED else TaskStage.FAILED,
                        installOutcome.label,
                        installOutcome.reply.take(64),
                        autoClear = true
                    )
                    finishDirectReply(installOutcome.reply, installOutcome.label, currentSettings, fromVoice)
                    return@launch
                }
            } else if (pendingInstallSession != null) {
                pendingInstallSession = null
                _pendingConfirmationSummary.value = null
            }

            // 1. User supplied a previously requested direct URL
            val pendingSite = awaitingWebsiteLinkFor
            val suppliedUrl = JarvisSystemAssistant.extractDirectUrlOrDomain(prompt)
            if (pendingSite != null && suppliedUrl != null) {
                awaitingWebsiteLinkFor = null
                updateTaskProgress(TaskStage.EXECUTING, "Opening Chrome", suppliedUrl)
                val openRes = JarvisSystemAssistant.openWebsiteInChrome(context, suppliedUrl)
                updateTaskProgress(
                    if (openRes.launchedTarget) TaskStage.COMPLETED else TaskStage.FAILED,
                    openRes.actionLabel,
                    suppliedUrl,
                    autoClear = true
                )
                finishDirectReply(openRes.replyText, openRes.actionLabel, currentSettings, fromVoice)
                return@launch
            }

            // 2. Deterministic direct command routing (mode switch, apps, Play Store, notifications, news, messaging, social, settings)
            val directMatch = tryHandleDirectCommand(prompt, currentSettings, fromVoice)
            if (directMatch != null) {
                updateTaskProgress(
                    if (directMatch.success) TaskStage.COMPLETED else TaskStage.FAILED,
                    directMatch.label,
                    directMatch.reply.take(64),
                    autoClear = true
                )
                finishDirectReply(directMatch.reply, directMatch.label, currentSettings, fromVoice)
                return@launch
            }

            // 3. Autonomous AI Reasoning + Indexed Screen Map + Self-Learned Knowledge + Web Search + Streaming TTS
            _isProcessing.value = true
            _streamingReply.value = ""
            updateTaskProgress(TaskStage.PLANNING, "Thinking", "Consulting AI & live context...")

            val svc = JarvisAccessibilityService.activeInstance()
            val (indexedMap, _) = svc?.readRichScreenMap(maxNodes = 32) ?: ("" to emptyList())
            val screenSummary = svc?.readScreenSummary()
            val learnedContext = deviceLearningEngine.buildCompactKnowledgeContext(prompt)

            val isOnline = OfflineAssistantEngine.isNetworkConnected(context)
            val lfmInstalled = offlineModelManager?.isLfmModelInstalled() == true
            val canUseLfmOffline = currentSettings.autoOfflineSwitchEnabled && lfmInstalled && localLfmEngine != null
            val webResult = if (isOnline && shouldLookupOnline(prompt)) {
                webClient.searchInternet(prompt)
            } else null
            val webContext = webResult?.formatForPrompt()?.takeIf { it.isNotBlank() }

            val history = localRepo.getRecentMessages(14)
            val memories = if (currentSettings.memoryEnabled) localRepo.getRecentMemories() else emptyList()

            if (!isOnline && (!canUseLfmOffline || OfflineAssistantEngine.canHandleLocally(prompt, memories, screenSummary))) {
                val (baseOfflineReply, offlineTag) = OfflineAssistantEngine.answerOffline(
                    context = context,
                    prompt = prompt,
                    memories = memories,
                    learnedApps = localRepo.getAllDeviceAppsSnapshot(),
                    learnedWorkflows = localRepo.getTopWorkflows(),
                    screenSummary = screenSummary
                )
                val offlineReply = if (offlineTag == "Offline Mode" && !lfmInstalled) {
                    "$baseOfflineReply Download LFM2.5 1.2B in Settings → Offline AI Engine to unlock full offline intelligence."
                } else {
                    baseOfflineReply
                }
                localRepo.logAiActivity("OFFLINE_RESPONSE", "$offlineTag answered query offline")
                _lastAssistantReply.value = offlineReply
                localRepo.saveMessage(
                    role = "assistant",
                    content = offlineReply,
                    modelUsed = "JARVIS On-Device Offline Engine",
                    isVoice = fromVoice,
                    toolSummary = offlineTag
                )
                _isProcessing.value = false
                _streamingReply.value = ""
                updateTaskProgress(TaskStage.COMPLETED, offlineTag, offlineReply.take(64), autoClear = true)
                voiceEngine.onCommandHandlingFinished(expectFollowUp = fromVoice)
                return@launch
            }

            val snapshot = NvidiaNimClient.LiveContextSnapshot(
                primaryActivity = currentSettings.primaryActivity,
                activeScreenSummary = screenSummary,
                internetSearchContext = webContext,
                deviceKnowledgeSummary = learnedContext,
                indexedScreenMap = indexedMap.takeIf { it.isNotBlank() }
            )

            // Prepare sentence-by-sentence streaming voice pipeline for non-task conversational replies;
            // device-task prompts must execute & verify real results BEFORE JARVIS speaks confirmation.
            val looksLikeTask = looksLikeDeviceTaskRequest(prompt)
            val allowStreamingVoice = currentSettings.voiceOutputEnabled && !voiceEngine.isMuted.value
            val allowStreamingProse = isOnline && allowStreamingVoice && !looksLikeTask
            var streamSpeechToken = -1
            var streamedSentenceCursor = 0
            var sawBracketInStream = false

            val primaryResult: JarvisAiResult = if (!isOnline && canUseLfmOffline) {
                updateTaskProgress(TaskStage.PLANNING, "LFM2.5 1.2B Offline", "Thinking on device (offline auto-switch)...")
                _streamingReply.value = "Running LFM2.5 1.2B offline..."
                localLfmEngine!!.generateReply(
                    userPrompt = prompt,
                    history = history,
                    memories = memories,
                    snapshot = snapshot
                )
            } else {
                nimClient.chatWithStreaming(
                    userPrompt = prompt,
                    modelId = currentSettings.selectedModelId,
                    history = history,
                    memories = memories,
                    snapshot = snapshot,
                    onPartialToken = { partial ->
                        val (visiblePartial, _) = JarvisSystemAssistant.extractAndStripAiActions(partial)
                        _streamingReply.value = visiblePartial

                        if (allowStreamingProse && !sawBracketInStream) {
                            if (partial.contains('[')) {
                                sawBracketInStream = true
                            } else if (visiblePartial.length > streamedSentenceCursor) {
                                val unseen = visiblePartial.substring(streamedSentenceCursor)
                                val sentenceBreak = findSentenceBoundary(unseen)
                                if (sentenceBreak > 0) {
                                    val sentence = unseen.substring(0, sentenceBreak).trim()
                                    streamedSentenceCursor += sentenceBreak
                                    if (sentence.length >= 6) {
                                        if (streamSpeechToken == -1) {
                                            streamSpeechToken = voiceEngine.beginStreamingSpeechSession()
                                        }
                                        voiceEngine.enqueueStreamingSpeechSentence(streamSpeechToken, sentence)
                                    }
                                }
                            }
                        }
                    }
                )
            }

            // Automatic seamless fallback to LFM2.5 1.2B if NIM fails/unconfigured (when not handled by simple local shortcut)
            val result: JarvisAiResult = when {
                primaryResult is JarvisAiResult.Success -> primaryResult
                canUseLfmOffline && !OfflineAssistantEngine.canHandleLocally(prompt, memories, screenSummary) -> {
                    updateTaskProgress(TaskStage.PLANNING, "LFM2.5 1.2B Fallback", "Switching to on-device LFM2.5 1.2B...")
                    val lfmAttempt = localLfmEngine!!.generateReply(
                        userPrompt = prompt,
                        history = history,
                        memories = memories,
                        snapshot = snapshot
                    )
                    if (lfmAttempt is JarvisAiResult.Success) lfmAttempt else primaryResult
                }
                else -> primaryResult
            }

            val replyText: String
            val toolSummary: String?
            var usedActionOverrideNote = false
            var taskFailed = false

            when (result) {
                is JarvisAiResult.Success -> {
                    val (hudStrippedReply, aiSelectedTitle) = JarvisSystemAssistant.extractHudTitleAndClean(result.replyText)
                    if (!aiSelectedTitle.isNullOrBlank()) {
                        _aiHudHeadline.value = aiSelectedTitle
                    }
                    val (cleanedReply, parsedAiActions) = JarvisSystemAssistant.extractAndStripAiActions(hudStrippedReply)
                    val actionsToRun = if (parsedAiActions.isNotEmpty()) {
                        parsedAiActions
                    } else {
                        JarvisSystemAssistant.parseCommands(prompt)
                    }
                    val executedActionLabels = mutableListOf<String>()
                    val actionNotes = mutableListOf<String>()
                    if (webContext != null) executedActionLabels.add("Web Search")

                    if (actionsToRun.isEmpty() && looksLikeTask) {
                        if (svc != null) {
                            updateTaskProgress(TaskStage.EXECUTING, "Autonomous Agent", prompt.take(44))
                            val (autoOk, autoOutcome) = runAutonomousUiTask(prompt, currentSettings)
                            executedActionLabels.add("Autonomous UI Agent")
                            actionNotes.add(autoOutcome)
                            if (!autoOk) taskFailed = true
                        } else {
                            taskFailed = true
                            executedActionLabels.add("Screen Helper Needed")
                            actionNotes.add(
                                "I couldn't run that action automatically yet, Sir — enable JARVIS Screen Helper in Accessibility Settings or name the exact app or contact step for me."
                            )
                        }
                    } else if (actionsToRun.isNotEmpty()) {
                        updateTaskProgress(
                            TaskStage.EXECUTING,
                            "Executing Actions",
                            "${actionsToRun.size} step(s) queued",
                            stepIndex = 0,
                            totalSteps = actionsToRun.size
                        )
                    }

                    for ((index, cmd) in actionsToRun.withIndex()) {
                        if (abortRequested) break
                        updateTaskProgress(
                            TaskStage.EXECUTING,
                            cmd.kind.name.replace('_', ' '),
                            cmd.primaryArg.ifBlank { prompt.take(36) },
                            stepIndex = index + 1,
                            totalSteps = actionsToRun.size
                        )
                        val hasMoreSteps = index < actionsToRun.lastIndex
                        when (cmd.kind) {
                            SystemIntentKind.SWITCH_TO_MINI_ORB -> {
                                val res = handleSwitchToMiniOrb()
                                executedActionLabels.add(res.second)
                                actionNotes.add(res.first)
                            }
                            SystemIntentKind.OPEN_FULL_APP -> {
                                val res = handleOpenFullApp()
                                executedActionLabels.add(res.second)
                                actionNotes.add(res.first)
                            }
                            SystemIntentKind.ORB_CONTROL -> {
                                val res = handleOrbControl(cmd.primaryArg)
                                executedActionLabels.add(res.second)
                                actionNotes.add(res.first)
                            }
                            SystemIntentKind.MUTE_MIC -> {
                                val mute = cmd.primaryArg == "mute"
                                voiceEngine.setMuted(mute)
                                executedActionLabels.add("Mic Control")
                                actionNotes.add(if (mute) "Microphone muted, Sir." else "Microphone unmuted, Sir.")
                            }
                            SystemIntentKind.INSTALL_APP -> {
                                val res = handleAppInstallDiscovery(cmd.primaryArg)
                                executedActionLabels.add(res.second)
                                actionNotes.add(res.first)
                            }
                            SystemIntentKind.READ_NOTIFICATIONS -> {
                                val res = handleReadNotifications(prompt)
                                executedActionLabels.add(res.second)
                                actionNotes.add(res.first)
                            }
                            SystemIntentKind.NEWS_RESEARCH -> {
                                val briefing = newsEngine.fetchBriefing(cmd.primaryArg.ifBlank { prompt })
                                executedActionLabels.add("News Research")
                                actionNotes.add(briefing.headlinesSummary)
                            }
                            SystemIntentKind.TEXT_CONTACT -> {
                                val res = executeCrossPlatformTextFlow(cmd, currentSettings, fromVoice)
                                executedActionLabels.add(res.second)
                                actionNotes.add(res.first)
                            }
                            SystemIntentKind.SOCIAL_ACTION -> {
                                val res = executeSocialNavigationFlow(cmd)
                                executedActionLabels.add(res.second)
                                if (res.first.isNotBlank()) actionNotes.add(res.first)
                            }
                            SystemIntentKind.AUTONOMOUS_TASK -> {
                                val (autoOk, autoOutcome) = runAutonomousUiTask(cmd.primaryArg.ifBlank { prompt }, currentSettings)
                                executedActionLabels.add("Autonomous UI Agent")
                                actionNotes.add(autoOutcome)
                                if (!autoOk) taskFailed = true
                            }
                            SystemIntentKind.STUDY_DEVICE -> {
                                val report = deviceLearningEngine.studyDeviceNow(force = true)
                                executedActionLabels.add("Device Study")
                                actionNotes.add(report.summaryMessage)
                            }
                            SystemIntentKind.VERIFY_VOICE -> {
                                val vRes = voiceEngine.verifyOwnerVoiceBurst()
                                executedActionLabels.add("Voice ID")
                                actionNotes.add(vRes.detailMessage)
                            }
                            SystemIntentKind.OPEN_UNKNOWN_WEBSITE -> {
                                val found = webClient.findWebsiteUrl(cmd.primaryArg)
                                if (found != null) {
                                    val openRes = JarvisSystemAssistant.openWebsiteInChrome(context, found)
                                    executedActionLabels.add(openRes.actionLabel)
                                    localRepo.logAiActivity("CHROME_OPEN", "Resolved ${cmd.primaryArg} -> $found")
                                    if (!openRes.launchedTarget) {
                                        taskFailed = true
                                        actionNotes.add(openRes.replyText)
                                    } else if (hasMoreSteps) {
                                        waitForAppForeground(svc, "chrome", 3600L)
                                    }
                                } else {
                                    awaitingWebsiteLinkFor = cmd.primaryArg
                                    executedActionLabels.add("Ask Link")
                                    actionNotes.add("I couldn't find the exact website address for \"${cmd.primaryArg}\", Sir. Tell me the domain and I'll open it in Chrome.")
                                }
                            }
                            SystemIntentKind.WEB_LOOKUP -> executedActionLabels.add("Internet Lookup")
                            SystemIntentKind.DEVICE_POWER -> {
                                val powerRes = handleDevicePowerAction(cmd.primaryArg)
                                executedActionLabels.add(powerRes.label)
                                actionNotes.add(powerRes.reply)
                                if (!powerRes.success) taskFailed = true
                            }
                            SystemIntentKind.UNLOCK_DEVICE -> {
                                val unlockRes = initiateDeviceUnlockFlow(currentSettings)
                                executedActionLabels.add(unlockRes.label)
                                actionNotes.add(unlockRes.reply)
                                if (!unlockRes.success) taskFailed = true
                            }
                            SystemIntentKind.SCHEDULE_TASK -> {
                                val schedRes = handleScheduleTask(cmd)
                                executedActionLabels.add(schedRes.label)
                                actionNotes.add(schedRes.reply)
                                if (!schedRes.success) taskFailed = true
                            }
                            SystemIntentKind.LIST_AUTOMATIONS -> {
                                val listRes = handleListAutomations()
                                executedActionLabels.add(listRes.label)
                                actionNotes.add(listRes.reply)
                            }
                            SystemIntentKind.CANCEL_AUTOMATION -> {
                                val cancelRes = handleCancelAutomation(cmd.primaryArg)
                                executedActionLabels.add(cancelRes.label)
                                actionNotes.add(cancelRes.reply)
                                if (!cancelRes.success) taskFailed = true
                            }
                            SystemIntentKind.VISUAL_FEEDBACK_TOGGLE -> {
                                val visRes = applyVisualFeedbackToggle(cmd, currentSettings)
                                executedActionLabels.add(visRes.label)
                                actionNotes.add(visRes.reply)
                            }
                            SystemIntentKind.DEVICE_SETTING_CONTROL -> {
                                val setRes = handleDeviceSettingControl(cmd.primaryArg, cmd.secondaryArg)
                                executedActionLabels.add(setRes.label)
                                actionNotes.add(setRes.reply)
                                if (!setRes.success) taskFailed = true
                            }
                            SystemIntentKind.DELEGATE_CAPABILITY -> {
                                val delRes = handleDelegateCapability(cmd.primaryArg, cmd.secondaryArg, currentSettings)
                                executedActionLabels.add(delRes.label)
                                actionNotes.add(delRes.reply)
                                if (!delRes.success) taskFailed = true
                            }
                            else -> {
                                if ((cmd.kind == SystemIntentKind.SIGNIN_ASSIST || cmd.kind == SystemIntentKind.SIGNUP_ASSIST) &&
                                    !verifyOwnerLockIfNeeded(currentSettings, fromVoice, "credential form filling")
                                ) {
                                    taskFailed = true
                                    actionNotes.add("Voiceprint verification required to fill saved credentials under Sir's lock.")
                                } else {
                                    val beforePkg = svc?.activeForegroundPackage().orEmpty()
                                    val outcome = JarvisSystemAssistant.execute(context, cmd)
                                    if (outcome.actionLabel.isNotBlank()) {
                                        executedActionLabels.add(outcome.actionLabel)
                                        localRepo.logAiActivity("SYSTEM_ACTION", "${outcome.actionLabel}: ${outcome.replyText}")
                                    }
                                    if (cmd.kind == SystemIntentKind.OPEN_APP && outcome.launchedTarget) {
                                        deviceLearningEngine.recordAppInteraction(cmd.primaryArg)
                                    }
                                    if (!outcome.launchedTarget && cmd.kind in setOf(
                                            SystemIntentKind.SET_ALARM,
                                            SystemIntentKind.SET_TIMER,
                                            SystemIntentKind.ADD_CALENDAR_EVENT
                                        )
                                    ) {
                                        val capName = when (cmd.kind) {
                                            SystemIntentKind.SET_ALARM -> "clock alarm"
                                            SystemIntentKind.SET_TIMER -> "timer clock"
                                            else -> "calendar"
                                        }
                                        val offer = offerAppDownload(capName, capName)
                                        executedActionLabels.add(offer.label)
                                        actionNotes.add(offer.reply)
                                    } else {
                                        val isActionableKind = cmd.kind in setOf(
                                            SystemIntentKind.OPEN_APP,
                                            SystemIntentKind.OPEN_URL,
                                            SystemIntentKind.PLATFORM_SEARCH,
                                            SystemIntentKind.WEB_SEARCH,
                                            SystemIntentKind.SIGNIN_ASSIST,
                                            SystemIntentKind.SIGNUP_ASSIST,
                                            SystemIntentKind.TAP_TEXT,
                                            SystemIntentKind.TYPE_TEXT,
                                            SystemIntentKind.PRESS_KEY,
                                            SystemIntentKind.CLICK_NODE_INDEX,
                                            SystemIntentKind.SEARCH_IN_APP,
                                            SystemIntentKind.GLOBAL_NAV,
                                            SystemIntentKind.SCROLL_SCREEN,
                                            SystemIntentKind.FREE_ROAM_SCROLL,
                                            SystemIntentKind.MULTI_TOUCH,
                                            SystemIntentKind.APP_SETTINGS,
                                            SystemIntentKind.OPEN_SETTINGS,
                                            SystemIntentKind.MEDIA_PLAY_SEARCH,
                                            SystemIntentKind.OPEN_CAMERA,
                                            SystemIntentKind.OPEN_VIDEO_CAMERA,
                                            SystemIntentKind.OPEN_MAPS,
                                            SystemIntentKind.CALL_DIAL,
                                            SystemIntentKind.SEND_SMS,
                                            SystemIntentKind.SEND_EMAIL,
                                            SystemIntentKind.SET_ALARM,
                                            SystemIntentKind.SET_TIMER
                                        )
                                        if (outcome.launchedTarget &&
                                            cmd.kind in setOf(SystemIntentKind.OPEN_APP, SystemIntentKind.OPEN_URL, SystemIntentKind.PLATFORM_SEARCH, SystemIntentKind.WEB_SEARCH)
                                        ) {
                                            val waitTarget = if (cmd.kind == SystemIntentKind.OPEN_APP) cmd.primaryArg else "chrome"
                                            val maxWait = if (hasMoreSteps) 3800L else 2400L
                                            val entered = waitForAppForeground(svc, waitTarget, maxWait, beforePkg)
                                            if (hasMoreSteps) {
                                                delay(320L)
                                            }
                                            if (svc != null && !entered && cmd.kind == SystemIntentKind.OPEN_APP && !waitTarget.equals("jarvis", ignoreCase = true)) {
                                                taskFailed = true
                                                actionNotes.add("I tried opening ${cmd.primaryArg}, Sir, but Android hasn't brought it onto the screen yet (check if your screen is locked or allow Background Pop-up permission for JARVIS).")
                                            }
                                        } else if (isActionableKind && !outcome.launchedTarget && outcome.replyText.isNotBlank()) {
                                            taskFailed = true
                                            actionNotes.add(outcome.replyText)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val baseReply = cleanedReply.ifBlank { "Right away, Sir." }
                    if (actionNotes.isNotEmpty()) {
                        usedActionOverrideNote = true
                        replyText = actionNotes.last()
                    } else {
                        replyText = baseReply
                    }

                    // Flush remaining prose into streaming speech session if no action override replaced it
                    if (allowStreamingProse && streamSpeechToken != -1 && !usedActionOverrideNote && !taskFailed) {
                        val remaining = cleanedReply.drop(streamedSentenceCursor.coerceIn(0, cleanedReply.length)).trim()
                        if (remaining.isNotBlank()) {
                            voiceEngine.enqueueStreamingSpeechSentence(streamSpeechToken, remaining)
                        }
                        voiceEngine.finishStreamingSpeechSession(streamSpeechToken, followUpListen = fromVoice)
                    }

                    val combinedTools = (result.toolCallsExecuted + executedActionLabels).distinct()
                    toolSummary = combinedTools.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    if (currentSettings.memoryEnabled && result.suggestedMemoryFact != null) {
                        localRepo.saveMemory("preference", result.suggestedMemoryFact)
                    }
                    val enginePrefix = if (result.modelUsed.contains("LFM2.5", ignoreCase = true)) {
                        result.modelUsed
                    } else {
                        "NVIDIA NIM (${result.modelUsed})"
                    }
                    localRepo.logAiActivity("AI_RESPONSE", "$enginePrefix answered query")
                }
                is JarvisAiResult.ConfigurationRequired -> {
                    val onlineFallback = webResult?.formatForUserReply().orEmpty()
                    if (OfflineAssistantEngine.canHandleLocally(prompt, memories, screenSummary)) {
                        val (offReply, offTag) = OfflineAssistantEngine.answerOffline(
                            context = context,
                            prompt = prompt,
                            memories = memories,
                            learnedApps = localRepo.getAllDeviceAppsSnapshot(),
                            learnedWorkflows = localRepo.getTopWorkflows(),
                            screenSummary = screenSummary
                        )
                        replyText = "$offReply (Enter your NVIDIA NIM key in Settings → NVIDIA NIM Model for cloud reasoning & voice.)"
                        toolSummary = "$offTag • NIM Setup Needed"
                    } else if (onlineFallback.isNotBlank()) {
                        replyText = "From the internet, Sir: $onlineFallback"
                        toolSummary = "Internet Search"
                    } else {
                        taskFailed = true
                        replyText = result.message
                        toolSummary = "Setup Needed"
                    }
                }
                is JarvisAiResult.Error -> {
                    val onlineFallback = webResult?.formatForUserReply().orEmpty()
                    if (onlineFallback.isNotBlank()) {
                        replyText = "I had trouble reaching NVIDIA NIM, Sir, so I pulled this straight from the web: $onlineFallback"
                        toolSummary = "Internet Fallback"
                    } else if (!OfflineAssistantEngine.isNetworkConnected(context) ||
                        OfflineAssistantEngine.canHandleLocally(prompt, memories, screenSummary)
                    ) {
                        val (offReply, offTag) = OfflineAssistantEngine.answerOffline(
                            context = context,
                            prompt = prompt,
                            memories = memories,
                            learnedApps = localRepo.getAllDeviceAppsSnapshot(),
                            learnedWorkflows = localRepo.getTopWorkflows(),
                            screenSummary = screenSummary
                        )
                        replyText = offReply
                        toolSummary = offTag
                    } else {
                        taskFailed = true
                        replyText = result.message
                        toolSummary = "NIM Error"
                    }
                }
            }

            _lastAssistantReply.value = replyText
            val usedModelLabel = (result as? JarvisAiResult.Success)?.modelUsed ?: currentSettings.selectedModelId
            localRepo.saveMessage(
                role = "assistant",
                content = replyText,
                modelUsed = usedModelLabel,
                isVoice = fromVoice,
                toolSummary = toolSummary
            )

            _isProcessing.value = false
            _streamingReply.value = ""
            updateTaskProgress(
                stage = if (taskFailed) TaskStage.FAILED else TaskStage.COMPLETED,
                title = toolSummary ?: "Complete",
                detail = replyText.take(64),
                autoClear = true
            )

            if (allowStreamingVoice) {
                val handledByStream = streamSpeechToken != -1 && !usedActionOverrideNote && !taskFailed
                if (!handledByStream) {
                    if (streamSpeechToken != -1) {
                        voiceEngine.interruptSpeech()
                    }
                    voiceEngine.speak(replyText.take(520), followUpListen = fromVoice)
                }
            } else {
                voiceEngine.onCommandHandlingFinished(expectFollowUp = fromVoice)
            }
        }
    }

    private fun findSentenceBoundary(text: String): Int {
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '.' || ch == '!' || ch == '?') {
                val next = text.getOrNull(i + 1)
                if (next == ' ' || next == '\n') {
                    return i + 2
                }
            }
        }
        return -1
    }

    private fun handleSwitchToMiniOrb(): Pair<String, String> {
        if (!Settings.canDrawOverlays(context)) {
            return "To switch into the floating Mini Orb, allow 'Display over other apps' on Home or in Settings first, Sir." to "Overlay Permission Needed"
        }
        manualOrbPinned = true
        JarvisFloatingOrbService.start(context, expandBubble = true, startListening = false)
        sessionManager.onExplicitSwitchToMiniOrb()
        localRepo.logAiActivitySyncSafe("MODE_SWITCH", "Switched to Floating Mini Orb")
        return "Switching to Mini Orb right now, Sir — I'm floating right beside you." to "Mini Orb Active"
    }

    private fun handleOpenFullApp(): Pair<String, String> {
        manualOrbPinned = false
        orbLifecycle.setStayAwakePinned(false)
        sessionManager.onExplicitSwitchToFullApp()
        JarvisFloatingOrbService.stop(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(intent) }
        return "Opening the full JARVIS application now, Sir." to "Full App"
    }

    private fun handleOrbControl(modeArg: String): Pair<String, String> {
        return if (modeArg == "stay_here") {
            orbLifecycle.setStayAwakePinned(true)
            sessionManager.setStayAwakePinned(true)
            "Got it, Sir — I'll stay awake and keep listening right here." to "Orb Pinned"
        } else {
            manualOrbPinned = false
            orbLifecycle.setStayAwakePinned(false)
            sessionManager.setStayAwakePinned(false)
            JarvisFloatingOrbService.stop(context)
            "Going to quiet standby, Sir. Just say \"Hey JARVIS\" whenever you need me." to "Orb Standby"
        }
    }

    private suspend fun handleAppInstallDiscovery(appQuery: String): Pair<String, String> {
        _autonomousStepStatus.value = "Searching Google Play for $appQuery..."
        val discovery = appDiscoveryEngine.discoverApp(appQuery)
        _autonomousStepStatus.value = ""
        val first = discovery.candidates.firstOrNull()
        if (first != null && !first.packageId.isNullOrBlank()) {
            val openRes = appDiscoveryEngine.openInstallCandidate(context, first)
            localRepo.logAiActivity("APP_DISCOVERY", "Opened Play Store for ${first.title} (${first.packageId})")
            return "${discovery.statusMessage} ${openRes.replyText}".trim() to "Play Store"
        }
        return discovery.statusMessage to "App Discovery"
    }

    private fun handleReadNotifications(prompt: String): Pair<String, String> {
        if (!NotificationVaultBridge.isAccessGranted(context)) {
            runCatching { context.startActivity(NotificationVaultBridge.openAccessSettingsIntent()) }
            return "To read your notifications and missed calls, enable JARVIS Notification Access in the settings screen I just opened, Sir." to "Notification Access"
        }
        val summary = NotificationVaultBridge.buildSummary(prompt.lowercase(Locale.US))
        return summary to "Notifications"
    }

    private fun JarvisLocalRepository.logAiActivitySyncSafe(type: String, detail: String) {
        scope.launch { logAiActivity(type, detail) }
    }

    private suspend fun verifyOwnerLockIfNeeded(
        settings: JarvisUserSettings,
        fromVoice: Boolean,
        actionDescription: String
    ): Boolean {
        if (!fromVoice || !settings.voiceBiometricLockEnabled || !voiceEngine.isOwnerVoiceEnrolled()) {
            return true
        }
        if (voiceEngine.isOwnerRecentlyVerified()) {
            return true
        }
        updateTaskProgress(TaskStage.PERMISSION_CHECK, "Voice ID Check", "Verifying owner voice for $actionDescription")
        _autonomousStepStatus.value = "Verifying Sir's voiceprint for $actionDescription..."
        voiceEngine.speak("Quick voice check for $actionDescription, Sir — speak briefly now.", followUpListen = false)
        delay(1700L)
        val res = voiceEngine.verifyOwnerVoiceBurst(durationMs = 2000L)
        _autonomousStepStatus.value = ""
        return res.state == SpeakerMatchState.VERIFIED_OWNER || res.state == SpeakerMatchState.VERIFIED_VOCAL_SHIFT
    }

    private suspend fun waitForAppForeground(
        svc: JarvisAccessibilityService?,
        appOrPlatform: String,
        maxWaitMs: Long = 4000L,
        previousForegroundPackage: String = ""
    ): Boolean {
        if (svc == null) {
            delay(minOf(1300L, maxWaitMs))
            return false
        }
        val lower = appOrPlatform.lowercase(Locale.US).trim()
        val beforeLower = previousForegroundPackage.lowercase(Locale.US).trim()
        val expectedHints = when {
            lower.contains("whatsapp") -> listOf("whatsapp")
            lower.contains("instagram") -> listOf("instagram")
            lower.contains("snapchat") -> listOf("snapchat")
            lower.contains("telegram") -> listOf("telegram")
            lower.contains("messenger") -> listOf("orca", "messenger")
            lower.contains("chrome") -> listOf("chrome", "browser")
            lower == "x" || lower.contains("twitter") -> listOf("twitter", "com.x.")
            lower.contains("tiktok") -> listOf("tiktok", "musically")
            lower.contains("youtube") -> listOf("youtube")
            lower.contains("spotify") -> listOf("spotify")
            lower.contains("discord") -> listOf("discord")
            lower.contains("reddit") -> listOf("reddit")
            lower.contains("facebook") -> listOf("facebook", "katana")
            lower.contains("message") || lower == "sms" -> listOf("messag", "mms")
            lower.contains("phone") || lower.contains("dialer") -> listOf("dialer", "telecom", "incallui", "contacts")
            lower.contains("gmail") || lower == "mail" -> listOf("gmail", ".gm")
            lower.contains("note") -> listOf("note", "keep")
            lower.contains("camera") -> listOf("cam")
            else -> listOf(lower.replace(Regex("[^a-z0-9]"), "")).filter { it.length >= 2 }
        }
        if (expectedHints.isEmpty()) {
            delay(950L)
            return true
        }
        val ownPkg = context.packageName.lowercase(Locale.US)
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline && !abortRequested) {
            val fg = svc.activeForegroundPackage().orEmpty().lowercase(Locale.US)
            if (fg.isNotBlank() && fg != ownPkg) {
                if (expectedHints.any { fg.contains(it) }) {
                    return true
                }
                if (beforeLower.isNotBlank() && fg != beforeLower && !fg.contains("launcher") && !fg.contains("systemui")) {
                    return true
                }
            }
            delay(220L)
        }
        return false
    }

    private suspend fun executeCrossPlatformTextFlow(
        cmd: ParsedSystemCommand,
        settings: JarvisUserSettings,
        fromVoice: Boolean
    ): Pair<String, String> {
        val svc = JarvisAccessibilityService.activeInstance()
            ?: return "Turn on JARVIS Screen Helper on Home or Settings first, Sir, so I can search contacts and type messages across WhatsApp, Snapchat, and Instagram." to "Screen Helper Needed"

        val parts = cmd.secondaryArg.split("|", limit = 2)
        val contact = parts.getOrElse(0) { "" }.trim()
        val messageText = parts.getOrElse(1) { "" }.trim()
        val platform = cmd.primaryArg.ifBlank { "WhatsApp" }

        if (contact.isBlank()) {
            return "Tell me who to message on $platform, Sir." to "Messaging"
        }

        if (!verifyOwnerLockIfNeeded(settings, fromVoice, "messaging on $platform")) {
            return "Owner voiceprint didn't verify, Sir, so I paused messaging under your security lock." to "Voice Lock"
        }

        updateTaskProgress(TaskStage.EXECUTING, "Messaging ($platform)", "Finding $contact")
        _autonomousStepStatus.value = "Opening $platform & finding $contact..."
        deviceLearningEngine.recordCommandHabit(contact, SystemIntentKind.TEXT_CONTACT)

        val launchOutcome = JarvisSystemAssistant.execute(context, ParsedSystemCommand(SystemIntentKind.OPEN_APP, platform))
        if (!launchOutcome.launchedTarget) {
            _autonomousStepStatus.value = ""
            return launchOutcome.replyText to "App Lookup"
        }
        deviceLearningEngine.recordAppInteraction(platform)

        val enteredForeground = waitForAppForeground(svc, platform, maxWaitMs = 4000L)
        if (!enteredForeground) {
            _autonomousStepStatus.value = ""
            return "I triggered $platform, Sir, but Android hasn't brought $platform onto the screen yet (check Screen Helper or if the screen is locked)." to "$platform Launch Blocked"
        }
        delay(380L)

        // Prepare Inbox / DM tab or back out of a previously open chat before searching
        if (svc.navigateMessagingAppToSearchArea(platform)) {
            delay(650L)
        }

        var searchTyped = false
        var lastSearchResult = ScreenActionResult(false, "")
        for (attempt in 1..3) {
            if (abortRequested) break
            lastSearchResult = svc.findAndTapSearchAndType(contact)
            if (lastSearchResult.success) {
                searchTyped = true
                break
            }
            delay(520L)
        }

        // Poll contact results dynamically so slower devices finish rendering rows
        var candidates: List<ContactCandidateMatch> = emptyList()
        if (searchTyped) {
            for (poll in 1..6) {
                if (abortRequested) break
                delay(380L)
                candidates = svc.findContactCandidates(contact)
                if (candidates.isNotEmpty()) break
            }
        }
        _autonomousStepStatus.value = ""

        if (candidates.size >= 2) {
            val highlights = candidates.mapIndexed { idx, match ->
                HighlightTarget(
                    rect = match.bounds,
                    badge = "#${idx + 1}",
                    label = match.displayName.take(18),
                    accentColor = Color.rgb(0, 229, 255)
                )
            }
            svc.showHighlightsOnScreen(highlights, durationMs = 15_000L)
            pendingMessageSession = PendingMessageSession(
                platform = platform,
                contactQuery = contact,
                draftMessage = messageText,
                stage = MessageStage.AWAITING_CONTACT_CHOICE,
                candidates = candidates
            )
            val optionSummary = candidates.take(4).mapIndexed { i, c -> "${i + 1}: ${c.displayName}" }.joinToString(", ")
            _pendingConfirmationSummary.value = "Pick #1–#${candidates.size} on $platform ($optionSummary)"
            return "I found ${candidates.size} people matching $contact on $platform, Sir — highlighted 1 to ${candidates.size} on your screen ($optionSummary). Which one should I open?" to "$platform Disambiguation"
        }

        if (candidates.size == 1) {
            val chosen = candidates.first()
            svc.tapBoundsCenter(chosen.bounds, chosen.displayName)
            for (poll in 1..5) {
                if (abortRequested) break
                delay(320L)
                if (svc.hasMessageComposeBox()) break
            }
            return stageMessageAndAskSendConfirmation(svc, platform, chosen.displayName, messageText)
        }

        if (svc.hasMessageComposeBox()) {
            return stageMessageAndAskSendConfirmation(svc, platform, contact, messageText)
        }

        return if (searchTyped) {
            pendingMessageSession = PendingMessageSession(
                platform = platform,
                contactQuery = contact,
                draftMessage = messageText,
                stage = MessageStage.AWAITING_CONTACT_CHOICE,
                candidates = emptyList()
            )
            "I opened $platform and searched for \"$contact\", Sir, but didn't get a clear clickable result row yet. Tap the chat you want or tell me what's visible and I'll type your message." to "$platform Search"
        } else {
            "I opened $platform, Sir, but couldn't open the contact search automatically (check if $platform has an app lock or tap the chat manually). ${lastSearchResult.message}".trim() to "$platform Search"
        }
    }

    private fun stageMessageAndAskSendConfirmation(
        svc: JarvisAccessibilityService,
        platform: String,
        contactName: String,
        messageText: String
    ): Pair<String, String> {
        if (messageText.isBlank()) {
            pendingMessageSession = PendingMessageSession(
                platform = platform,
                contactQuery = contactName,
                selectedContactName = contactName,
                draftMessage = "",
                stage = MessageStage.AWAITING_MESSAGE_TEXT
            )
            _pendingConfirmationSummary.value = "Dictate message for $contactName on $platform"
            return "Opened $contactName's chat on $platform, Sir. What would you like me to type?" to "$platform Ready"
        }

        val typeResult = svc.findMessageComposeAndType(messageText)
        if (!typeResult.success) {
            pendingMessageSession = PendingMessageSession(
                platform = platform,
                contactQuery = contactName,
                selectedContactName = contactName,
                draftMessage = messageText,
                stage = MessageStage.AWAITING_MESSAGE_TEXT
            )
            return "Opened $contactName on $platform, Sir, but couldn't tap the chat input yet. Tap inside the message box and tell me \"Type $messageText\"." to "$platform Draft"
        }

        svc.highlightSendButton()
        pendingMessageSession = PendingMessageSession(
            platform = platform,
            contactQuery = contactName,
            selectedContactName = contactName,
            draftMessage = messageText,
            stage = MessageStage.AWAITING_SEND_CONFIRMATION
        )
        _pendingConfirmationSummary.value = "Send \"$messageText\" to $contactName on $platform?"
        return "I've typed \"$messageText\" to $contactName on $platform and highlighted the Send button, Sir. Should I send it now?" to "Confirm Send"
    }

    private suspend fun handlePendingMessageSessionTurn(
        prompt: String,
        session: PendingMessageSession,
        settings: JarvisUserSettings,
        fromVoice: Boolean
    ): Pair<String, String>? {
        val lower = prompt.lowercase(Locale.US).trim().replace(Regex("[!?.,]+$"), "")
        val svc = JarvisAccessibilityService.activeInstance()

        if (lower in setOf("no", "cancel", "don't send", "do not send", "stop", "never mind", "nevermind")) {
            pendingMessageSession = null
            _pendingConfirmationSummary.value = null
            svc?.clearVisualOverlays()
            return "Understood, Sir — cancelled without sending anything." to "Cancelled"
        }

        return when (session.stage) {
            MessageStage.AWAITING_CONTACT_CHOICE -> {
                val chosenIndex = parseChoiceNumber(lower)
                val chosen = when {
                    chosenIndex != null && chosenIndex in 1..session.candidates.size ->
                        session.candidates[chosenIndex - 1]
                    session.candidates.isNotEmpty() ->
                        session.candidates.firstOrNull { c -> lower.contains(c.displayName.lowercase(Locale.US)) }
                    else -> null
                }

                if (chosen != null && svc != null) {
                    svc.clearVisualOverlays()
                    svc.tapBoundsCenter(chosen.bounds, chosen.displayName)
                    delay(950L)
                    stageMessageAndAskSendConfirmation(svc, session.platform, chosen.displayName, session.draftMessage)
                } else {
                    pendingMessageSession = null
                    _pendingConfirmationSummary.value = null
                    null
                }
            }
            MessageStage.AWAITING_MESSAGE_TEXT -> {
                if (svc == null) return "Turn on Screen Helper first, Sir." to "Screen Helper Needed"
                stageMessageAndAskSendConfirmation(
                    svc,
                    session.platform,
                    session.selectedContactName.ifBlank { session.contactQuery },
                    prompt
                )
            }
            MessageStage.AWAITING_SEND_CONFIRMATION -> {
                val isConfirm = lower in setOf(
                    "yes", "yeah", "yep", "confirm", "send", "send it", "go ahead", "do it", "yes send it", "yes sir"
                ) || lower.startsWith("yes ") || lower.contains("send it")
                if (!isConfirm) {
                    pendingMessageSession = null
                    _pendingConfirmationSummary.value = null
                    return null
                }

                if (!verifyOwnerLockIfNeeded(settings, fromVoice, "sending message on ${session.platform}")) {
                    return "Owner voiceprint verification failed, Sir — message left unsent under your lock." to "Voice Lock"
                }

                pendingMessageSession = null
                _pendingConfirmationSummary.value = null
                svc?.clearVisualOverlays()

                val tapOutcome = svc?.tapSendButton()
                    ?: return "Screen Helper isn't connected right now, Sir." to "Screen Helper Needed"
                localRepo.logAiActivity(
                    "MESSAGE_SENT",
                    "Confirmed & sent on ${session.platform} to ${session.selectedContactName.ifBlank { session.contactQuery }}"
                )
                if (tapOutcome.success) {
                    "Sent to ${session.selectedContactName.ifBlank { session.contactQuery }} on ${session.platform}, Sir." to "Message Sent"
                } else {
                    "I tried tapping Send on ${session.platform}, Sir — please double-check the highlighted button on screen." to "Check Send"
                }
            }
        }
    }

    private fun parseChoiceNumber(lower: String): Int? {
        return when {
            lower.contains("first") || lower == "1" || lower.contains("number 1") || lower.contains("one") -> 1
            lower.contains("second") || lower == "2" || lower.contains("number 2") || lower.contains("two") -> 2
            lower.contains("third") || lower == "3" || lower.contains("number 3") || lower.contains("three") -> 3
            lower.contains("fourth") || lower == "4" || lower.contains("number 4") || lower.contains("four") -> 4
            lower.contains("fifth") || lower == "5" || lower.contains("number 5") || lower.contains("five") -> 5
            else -> Regex("\\b([1-6])\\b").find(lower)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private suspend fun executeSocialNavigationFlow(cmd: ParsedSystemCommand): Pair<String, String> {
        val svc = JarvisAccessibilityService.activeInstance()
            ?: return "Enable JARVIS Screen Helper on Home or Settings first, Sir, so I can control social media feeds." to "Screen Helper Needed"

        val platform = cmd.secondaryArg.trim()
        if (platform.isNotBlank()) {
            val fgPkg = svc.activeForegroundPackage().orEmpty().lowercase(Locale.US)
            val platformLower = platform.lowercase(Locale.US)
            val alreadyInPlatform = fgPkg.contains(platformLower) ||
                (platformLower == "x" && fgPkg.contains("twitter")) ||
                (platformLower == "tiktok" && fgPkg.contains("musically"))
            if (!alreadyInPlatform) {
                val openRes = JarvisSystemAssistant.execute(context, ParsedSystemCommand(SystemIntentKind.OPEN_APP, platform))
                if (!openRes.launchedTarget) return openRes.replyText to "App Lookup"
                deviceLearningEngine.recordAppInteraction(platform)
                waitForAppForeground(svc, platform, 3600L)
                delay(320L)
            }
        }

        deviceLearningEngine.recordCommandHabit(cmd.primaryArg, SystemIntentKind.SOCIAL_ACTION)
        val result = svc.performSocialAction(cmd.primaryArg, cmd.secondaryArg)
        return result.message to "Social Control"
    }

    private suspend fun runAutonomousUiTask(goal: String, settings: JarvisUserSettings): Pair<Boolean, String> {
        val svc = JarvisAccessibilityService.activeInstance()
            ?: return false to "Turn on JARVIS Screen Helper first, Sir, so I can inspect the screen and complete that task automatically."

        val maxSteps = 12
        val stepLog = mutableListOf<String>()
        var lastPkg = svc.activeForegroundPackage().orEmpty()
        var lastActionSignature = ""
        var repeatedActionCount = 0

        for (step in 1..maxSteps) {
            if (abortRequested) {
                _autonomousStepStatus.value = ""
                return false to "Paused autonomous steps at your command, Sir."
            }

            updateTaskProgress(TaskStage.EXECUTING, "Autonomous Agent", "Studying screen (step $step/$maxSteps)", step, maxSteps)
            _autonomousStepStatus.value = "Autonomous step $step/$maxSteps: studying screen..."
            val (screenMap, _) = svc.readRichScreenMap(maxNodes = 36)
            lastPkg = svc.activeForegroundPackage().orEmpty()

            val promptForAgent = """
                Goal requested by Sir: "$goal"
                Steps taken so far: ${stepLog.ifEmpty { listOf("none") }.joinToString(" -> ")}
                Look at Live Indexed UI Elements carefully and output ONLY ONE next concrete action tag to advance the goal, plus a 1-sentence human explanation:
                - Use [ACTION:OPEN_APP|AppName] to launch any installed app, or [ACTION:OPEN_CHROME|https://...] to open a webpage.
                - Use [ACTION:CLICK_INDEX|n] to tap an exact element number from Live Indexed UI Elements, or [ACTION:TAP_TEXT|button label].
                - Use [ACTION:SEARCH_IN_APP|query] or [ACTION:TYPE_TEXT|text|field] to enter text.
                - Use [ACTION:FREE_SCROLL|up/down/left/right/top/bottom|single] or [ACTION:MULTI_TOUCH|pinch_out/pinch_in] or [ACTION:GLOBAL_NAV|back] to navigate.
                - Use [ACTION:DEVICE_SETTING|setting|open/close/on/off] or [ACTION:SOCIAL_ACTION|like/repost/dms/reels|platform].
                - If the goal is now complete or needs Sir's confirmation before submitting/sending/buying, do NOT emit an action tag and clearly explain what's ready.
            """.trimIndent()

            val snapshot = NvidiaNimClient.LiveContextSnapshot(
                primaryActivity = settings.primaryActivity,
                indexedScreenMap = screenMap,
                deviceKnowledgeSummary = deviceLearningEngine.buildCompactKnowledgeContext(goal)
            )
            val isOnline = OfflineAssistantEngine.isNetworkConnected(context)
            val canUseLfmOffline = settings.autoOfflineSwitchEnabled &&
                offlineModelManager?.isLfmModelInstalled() == true &&
                localLfmEngine != null
            val res = if (!isOnline && canUseLfmOffline) {
                localLfmEngine!!.generateReply(
                    userPrompt = promptForAgent,
                    history = emptyList(),
                    memories = emptyList(),
                    snapshot = snapshot,
                    maxTokens = 160
                )
            } else {
                val nimRes = nimClient.chatWithStreaming(
                    userPrompt = promptForAgent,
                    modelId = settings.selectedModelId,
                    history = emptyList(),
                    memories = emptyList(),
                    snapshot = snapshot,
                    onPartialToken = {}
                )
                if (nimRes !is JarvisAiResult.Success && canUseLfmOffline) {
                    localLfmEngine!!.generateReply(
                        userPrompt = promptForAgent,
                        history = emptyList(),
                        memories = emptyList(),
                        snapshot = snapshot,
                        maxTokens = 160
                    )
                } else {
                    nimRes
                }
            }

            when (res) {
                is JarvisAiResult.Success -> {
                    val (cleanMsg, tags) = JarvisSystemAssistant.extractAndStripAiActions(res.replyText)
                    if (tags.isEmpty()) {
                        _autonomousStepStatus.value = ""
                        if (stepLog.isEmpty()) {
                            return false to "I inspected the screen, Sir, but couldn't find an automatic UI step to execute \"$goal\"."
                        }
                        if (lastPkg.isNotBlank()) {
                            localRepo.saveLearnedWorkflow(lastPkg, goal, stepLog.joinToString(" -> "))
                        }
                        return true to cleanMsg.ifBlank { "Executed ${stepLog.size} screen steps for \"$goal\", Sir." }
                    }
                    val nextCmd = tags.first()
                    val sig = "$lastPkg|${nextCmd.kind}|${nextCmd.primaryArg}"
                    if (sig == lastActionSignature) {
                        repeatedActionCount++
                        if (repeatedActionCount >= 2) {
                            _autonomousStepStatus.value = ""
                            return false to "I reached a screen loop trying ${nextCmd.kind.name.lowercase()} (${nextCmd.primaryArg}) repeatedly, Sir. Tell me what you'd like me to do instead."
                        }
                    } else {
                        lastActionSignature = sig
                        repeatedActionCount = 0
                    }

                    val beforePkg = svc.activeForegroundPackage().orEmpty()
                    val (stepOk, stepLabel) = when (nextCmd.kind) {
                        SystemIntentKind.DEVICE_SETTING_CONTROL -> {
                            val r = handleDeviceSettingControl(nextCmd.primaryArg, nextCmd.secondaryArg)
                            r.success to r.label
                        }
                        SystemIntentKind.VISUAL_FEEDBACK_TOGGLE -> {
                            val r = applyVisualFeedbackToggle(nextCmd, settings)
                            r.success to r.label
                        }
                        else -> {
                            val exec = JarvisSystemAssistant.execute(context, nextCmd)
                            exec.launchedTarget to exec.actionLabel
                        }
                    }
                    if (stepOk) {
                        stepLog.add("${nextCmd.kind}:${nextCmd.primaryArg}")
                    }
                    _autonomousStepStatus.value = "Step $step/$maxSteps: $stepLabel"
                    if (nextCmd.kind == SystemIntentKind.OPEN_APP && stepOk) {
                        waitForAppForeground(svc, nextCmd.primaryArg, 3600L, beforePkg)
                        delay(300L)
                    } else {
                        delay(850L)
                    }
                }
                else -> {
                    _autonomousStepStatus.value = ""
                    return false to "I hit a connection snag while planning the next screen step, Sir."
                }
            }
        }

        _autonomousStepStatus.value = ""
        if (stepLog.isNotEmpty() && lastPkg.isNotBlank()) {
            localRepo.saveLearnedWorkflow(lastPkg, goal, stepLog.joinToString(" -> "))
        }
        return stepLog.isNotEmpty() to if (stepLog.isNotEmpty()) {
            "I ran ${stepLog.size} steps on your screen (${stepLog.lastOrNull().orEmpty()}), Sir — let me know if you'd like me to keep going."
        } else {
            "I couldn't complete any verified UI step for \"$goal\", Sir."
        }
    }

    private suspend fun finishDirectReply(
        reply: String,
        toolLabel: String,
        currentSettings: JarvisUserSettings,
        fromVoice: Boolean,
        followUpListen: Boolean = fromVoice
    ) {
        _lastAssistantReply.value = reply
        localRepo.saveMessage(
            role = "assistant",
            content = reply,
            modelUsed = "JARVIS System & Plugin Router",
            isVoice = fromVoice,
            toolSummary = toolLabel
        )
        if (currentSettings.voiceOutputEnabled && !voiceEngine.isMuted.value) {
            voiceEngine.speak(reply, followUpListen = followUpListen)
        } else {
            voiceEngine.onCommandHandlingFinished(expectFollowUp = followUpListen)
        }
    }

    private suspend fun tryHandleDirectCommand(
        prompt: String,
        currentSettings: JarvisUserSettings,
        fromVoice: Boolean
    ): DirectCommandOutcome? {
        val commands = JarvisSystemAssistant.parseCommands(prompt)
        if (commands.isEmpty()) return null

        val replies = mutableListOf<String>()
        val labels = mutableListOf<String>()
        var allSucceeded = true

        for ((index, parsed) in commands.withIndex()) {
            if (abortRequested) break
            updateTaskProgress(
                stage = TaskStage.EXECUTING,
                title = parsed.kind.name.replace('_', ' '),
                detail = parsed.primaryArg.ifBlank { prompt.take(36) },
                stepIndex = index + 1,
                totalSteps = commands.size
            )
            val hasMoreSteps = index < commands.lastIndex
            val stepResult: DirectCommandOutcome? = when (parsed.kind) {
                SystemIntentKind.SWITCH_ACTIVITY_ASSISTANT -> {
                    prefsRepo.setPrimaryActivity(JarvisChoiceActivity.ASSISTANT)
                    localRepo.logAiActivity("ACTIVITY_SWITCH", "System Assistant active")
                    DirectCommandOutcome("Right away, Sir. Ready as your personal assistant and floating screen plugin.", "System Assistant")
                }
                SystemIntentKind.SWITCH_ACTIVITY_MARKETING -> {
                    DirectCommandOutcome("Certainly, Sir. Just tell me which trading app or website to open in Chrome, or ask me to check market info online and analyze your screen.", "Assistant & Web")
                }
                SystemIntentKind.NAVIGATE_SECTION -> {
                    _navigationEvents.tryEmit(parsed.primaryArg)
                    DirectCommandOutcome("Opening ${parsed.primaryArg.replaceFirstChar { it.uppercase() }}, Sir.", "Navigation")
                }
                SystemIntentKind.SWITCH_TO_MINI_ORB -> {
                    val res = handleSwitchToMiniOrb()
                    DirectCommandOutcome(res.first, res.second, res.second != "Overlay Permission Needed")
                }
                SystemIntentKind.OPEN_FULL_APP -> {
                    val res = handleOpenFullApp()
                    DirectCommandOutcome(res.first, res.second)
                }
                SystemIntentKind.ORB_CONTROL -> {
                    val res = handleOrbControl(parsed.primaryArg)
                    DirectCommandOutcome(res.first, res.second)
                }
                SystemIntentKind.MUTE_MIC -> {
                    val mute = parsed.primaryArg == "mute"
                    voiceEngine.setMuted(mute)
                    DirectCommandOutcome(
                        if (mute) "Microphone muted, Sir." else "Microphone unmuted — listening again, Sir.",
                        "Mic Control"
                    )
                }
                SystemIntentKind.FLOATING_ORB -> {
                    val res = if (parsed.primaryArg == "close") {
                        handleOrbControl("sleep")
                    } else {
                        handleSwitchToMiniOrb()
                    }
                    DirectCommandOutcome(res.first, res.second, res.second != "Overlay Permission Needed")
                }
                SystemIntentKind.INSTALL_APP -> {
                    val res = handleAppInstallDiscovery(parsed.primaryArg)
                    DirectCommandOutcome(res.first, res.second, res.second == "Play Store")
                }
                SystemIntentKind.READ_NOTIFICATIONS -> {
                    val res = handleReadNotifications(prompt)
                    DirectCommandOutcome(res.first, res.second, res.second != "Notification Access")
                }
                SystemIntentKind.NEWS_RESEARCH -> {
                    _isProcessing.value = true
                    val briefing = newsEngine.fetchBriefing(parsed.primaryArg)
                    _isProcessing.value = false
                    localRepo.logAiActivity("NEWS_RESEARCH", "Fetched ${briefing.topic}")
                    DirectCommandOutcome(briefing.headlinesSummary, "Live Briefing")
                }
                SystemIntentKind.TEXT_CONTACT -> {
                    val res = executeCrossPlatformTextFlow(parsed, currentSettings, fromVoice)
                    val ok = !res.second.contains("Needed", ignoreCase = true) && !res.second.contains("Blocked", ignoreCase = true)
                    DirectCommandOutcome(res.first, res.second, ok)
                }
                SystemIntentKind.SOCIAL_ACTION -> {
                    val res = executeSocialNavigationFlow(parsed)
                    val ok = !res.second.contains("Needed", ignoreCase = true)
                    DirectCommandOutcome(res.first, res.second, ok)
                }
                SystemIntentKind.STUDY_DEVICE -> {
                    _autonomousStepStatus.value = "Studying installed apps & patterns..."
                    val report = deviceLearningEngine.studyDeviceNow(force = true)
                    _autonomousStepStatus.value = ""
                    DirectCommandOutcome(report.summaryMessage, "Device Study")
                }
                SystemIntentKind.VERIFY_VOICE -> {
                    val vRes = voiceEngine.verifyOwnerVoiceBurst()
                    localRepo.logAiActivity("VOICE_ID", vRes.detailMessage)
                    DirectCommandOutcome(vRes.detailMessage, "Voice ID")
                }
                SystemIntentKind.VISUAL_FEEDBACK_TOGGLE -> applyVisualFeedbackToggle(parsed, currentSettings)
                SystemIntentKind.DAILY_BRIEFING -> {
                    val apps = localRepo.getAllDeviceAppsSnapshot()
                    val habits = localRepo.getTopHabits()
                    val telemetry = JarvisSystemAssistant.buildSystemTelemetryReport(context)
                    val learnedBrief = if (apps.isNotEmpty()) {
                        " I've indexed ${apps.size} device apps and ${habits.size} learned habits for you today."
                    } else ""
                    DirectCommandOutcome("$telemetry$learnedBrief How would you like to start, Sir?", "Daily Briefing")
                }
                SystemIntentKind.OPEN_UNKNOWN_WEBSITE -> {
                    _isProcessing.value = true
                    val foundUrl = webClient.findWebsiteUrl(parsed.primaryArg)
                    _isProcessing.value = false
                    if (foundUrl != null) {
                        awaitingWebsiteLinkFor = null
                        val openRes = JarvisSystemAssistant.openWebsiteInChrome(context, foundUrl)
                        localRepo.logAiActivity("CHROME_OPEN", "Found & opened ${parsed.primaryArg} -> $foundUrl")
                        if (hasMoreSteps && openRes.launchedTarget) {
                            waitForAppForeground(JarvisAccessibilityService.activeInstance(), "chrome", 3600L)
                            delay(300L)
                        }
                        DirectCommandOutcome(openRes.replyText, "Chrome Lookup", openRes.launchedTarget)
                    } else {
                        awaitingWebsiteLinkFor = parsed.primaryArg
                        DirectCommandOutcome(
                            "I couldn't find the exact website address for \"${parsed.primaryArg}\" online, Sir. Please tell me or paste the link (for example site.com), and I will open it in Chrome right away.",
                            "Link Needed",
                            false
                        )
                    }
                }
                SystemIntentKind.GUIDE_SCREEN -> {
                    val svc = JarvisAccessibilityService.activeInstance()
                    if (svc == null) {
                        DirectCommandOutcome("Turn on Screen Helper on Home or Settings first, Sir, so I can read the screen and guide you step by step (like ID uploads, forms, or trading screens).", "Screen Helper Needed", false)
                    } else {
                        val guidance = svc.analyzeCurrentFlowStep()
                        if (guidance.stepKind == "GENERAL_SCREEN" && commands.size == 1 && !prompt.lowercase(Locale.US).contains("next step")) {
                            null
                        } else {
                            localRepo.logAiActivity("STEP_GUIDE", "${guidance.stepKind}: guided user on screen")
                            DirectCommandOutcome(guidance.spokenGuidance, "Step Guide")
                        }
                    }
                }
                SystemIntentKind.SIGNIN_ASSIST, SystemIntentKind.SIGNUP_ASSIST -> {
                    if (!verifyOwnerLockIfNeeded(currentSettings, fromVoice, "filling saved credentials")) {
                        DirectCommandOutcome("Voiceprint verification didn't match Sir's enrolled voice, so credential autofill stays locked.", "Voice Lock", false)
                    } else {
                        val exec = JarvisSystemAssistant.execute(context, parsed)
                        if (!exec.handled) null else {
                            localRepo.logAiActivity("SYSTEM_ACTION", "${exec.actionLabel}: ${exec.replyText}")
                            DirectCommandOutcome(exec.replyText, exec.actionLabel, exec.launchedTarget)
                        }
                    }
                }
                SystemIntentKind.DEVICE_POWER -> handleDevicePowerAction(parsed.primaryArg)
                SystemIntentKind.UNLOCK_DEVICE -> initiateDeviceUnlockFlow(currentSettings)
                SystemIntentKind.SCHEDULE_TASK -> handleScheduleTask(parsed)
                SystemIntentKind.LIST_AUTOMATIONS -> handleListAutomations()
                SystemIntentKind.CANCEL_AUTOMATION -> handleCancelAutomation(parsed.primaryArg)
                SystemIntentKind.DEVICE_SETTING_CONTROL -> handleDeviceSettingControl(parsed.primaryArg, parsed.secondaryArg)
                SystemIntentKind.DELEGATE_CAPABILITY -> handleDelegateCapability(parsed.primaryArg, parsed.secondaryArg, currentSettings)
                else -> {
                    val svc = JarvisAccessibilityService.activeInstance()
                    val beforePkg = svc?.activeForegroundPackage().orEmpty()
                    val exec = JarvisSystemAssistant.execute(context, parsed)
                    if (!exec.handled) null else {
                        localRepo.logAiActivity("SYSTEM_ACTION", "${exec.actionLabel}: ${exec.replyText}")
                        if (parsed.kind == SystemIntentKind.OPEN_APP && exec.launchedTarget) {
                            deviceLearningEngine.recordAppInteraction(parsed.primaryArg)
                        }
                        if (!exec.launchedTarget && parsed.kind in setOf(
                                SystemIntentKind.SET_ALARM,
                                SystemIntentKind.SET_TIMER,
                                SystemIntentKind.ADD_CALENDAR_EVENT
                            )
                        ) {
                            val capName = when (parsed.kind) {
                                SystemIntentKind.SET_ALARM -> "clock alarm"
                                SystemIntentKind.SET_TIMER -> "timer clock"
                                else -> "calendar"
                            }
                            offerAppDownload(capName, capName)
                        } else {
                            var verifiedSuccess = exec.launchedTarget || parsed.kind in setOf(
                                SystemIntentKind.DEVICE_STATUS,
                                SystemIntentKind.TIME_DATE,
                                SystemIntentKind.READ_SCREEN,
                                SystemIntentKind.GUIDE_SCREEN
                            )
                            var replyForStep = exec.replyText
                            var labelForStep = exec.actionLabel
                            if (exec.launchedTarget &&
                                parsed.kind in setOf(SystemIntentKind.OPEN_APP, SystemIntentKind.OPEN_URL, SystemIntentKind.PLATFORM_SEARCH, SystemIntentKind.WEB_SEARCH)
                            ) {
                                val waitTarget = if (parsed.kind == SystemIntentKind.OPEN_APP) parsed.primaryArg else "chrome"
                                val entered = waitForAppForeground(svc, waitTarget, if (hasMoreSteps) 3800L else 2400L, beforePkg)
                                if (hasMoreSteps) {
                                    delay(320L)
                                }
                                if (svc != null && !entered && parsed.kind == SystemIntentKind.OPEN_APP && !waitTarget.equals("jarvis", ignoreCase = true)) {
                                    verifiedSuccess = false
                                    replyForStep = "I tried opening ${parsed.primaryArg}, Sir, but Android hasn't brought it onto the screen yet (check if your screen is locked or allow Background Pop-up permission for JARVIS)."
                                    labelForStep = "${parsed.primaryArg} Launch Blocked"
                                }
                            }
                            DirectCommandOutcome(replyForStep, labelForStep, verifiedSuccess)
                        }
                    }
                }
            }

            if (stepResult != null) {
                if (stepResult.reply.isNotBlank()) replies.add(stepResult.reply)
                if (stepResult.label.isNotBlank()) labels.add(stepResult.label)
                if (!stepResult.success) allSucceeded = false
            } else if (commands.size == 1) {
                return null
            }
        }

        if (replies.isEmpty()) return null
        return DirectCommandOutcome(replies.joinToString(" "), labels.distinct().joinToString(", "), allSucceeded)
    }

    private fun looksLikeDeviceTaskRequest(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.US).trim()
        val wordCount = lower.split(Regex("\\s+")).size
        if (wordCount < 2) return false
        val startsWithQuestion = lower.startsWith("what ") || lower.startsWith("why ") ||
            lower.startsWith("when ") || lower.startsWith("who ") || lower.startsWith("where ") ||
            lower.startsWith("how does") || lower.startsWith("how do ") || lower.startsWith("explain ") ||
            lower.startsWith("tell me about")
        if (startsWithQuestion && !lower.contains("open ") && !lower.contains("send ") && !lower.contains("tap ")) {
            return false
        }
        return Regex(
            """\b(open|launch|start|close|shut|exit|send|text|message|dm|reply|call|dial|tap|click|press|scroll|pan|swipe|zoom|pinch|type|write|search in|play|pause|stop|mute|unmute|turn on|turn off|enable|disable|toggle|install|download|alarm|timer|trade|trading|marketing|sign me in|sign in|log in|create an account|fill my|check my notifications|read my notifications|go back|go home|take a selfie|take a photo|unlock|shut down|shutdown|restart|reboot|sleep)\b"""
        ).containsMatchIn(lower)
    }

    @Suppress("DEPRECATION")
    private suspend fun initiateDeviceUnlockFlow(settings: JarvisUserSettings): DirectCommandOutcome {
        val svc = JarvisAccessibilityService.activeInstance()
        svc?.wakeScreenBriefly(18_000L) ?: runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "JarvisAI::UnlockWake"
            )?.acquire(18_000L)
        }

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null && !km.isKeyguardLocked) {
            return DirectCommandOutcome("Your device is already unlocked, Sir.", "Already Unlocked", true)
        }
        if (svc == null) {
            return DirectCommandOutcome(
                "Turn on JARVIS Screen Helper in Accessibility Settings first, Sir, so I can unlock the screen for you.",
                "Screen Helper Needed",
                false
            )
        }
        if (!settings.deviceUnlockEnabled) {
            return DirectCommandOutcome(
                "Voice device unlock is turned off, Sir. Enable it and save your Safe Word and pattern in JARVIS Settings first.",
                "Unlock Disabled",
                false
            )
        }
        if (!credentialStore.hasDeviceSafeWord() || !credentialStore.hasDeviceUnlockPattern()) {
            return DirectCommandOutcome(
                "Please set both your Safe Word and 3x3 unlock pattern inside JARVIS Settings first, Sir.",
                "Unlock Setup Needed",
                false
            )
        }

        pendingUnlockSession = PendingUnlockSession()
        _pendingConfirmationSummary.value = "Say your Safe Word to unlock device"
        return DirectCommandOutcome(
            "Waking the screen now, Sir. What is your safe word?",
            "Safe Word Required",
            true
        )
    }

    private suspend fun handlePendingUnlockTurn(
        prompt: String,
        session: PendingUnlockSession,
        settings: JarvisUserSettings,
        fromVoice: Boolean
    ): DirectCommandOutcome? {
        val lower = prompt.lowercase(Locale.US).trim().replace(Regex("[!?.,]+$"), "")
        if (lower in setOf("cancel", "stop", "never mind", "nevermind", "abort")) {
            pendingUnlockSession = null
            _pendingConfirmationSummary.value = null
            return DirectCommandOutcome("Unlock cancelled, Sir.", "Unlock Cancelled", true)
        }

        if (!credentialStore.verifySafeWord(prompt)) {
            val remaining = session.attemptsRemaining - 1
            return if (remaining <= 0) {
                pendingUnlockSession = null
                _pendingConfirmationSummary.value = null
                DirectCommandOutcome(
                    "Safe word did not match after 3 attempts, Sir. Keeping your device locked.",
                    "Safe Word Denied",
                    false
                )
            } else {
                pendingUnlockSession = session.copy(attemptsRemaining = remaining)
                DirectCommandOutcome(
                    "That safe word didn't match, Sir — $remaining attempt${if (remaining == 1) "" else "s"} left. Please repeat your safe word.",
                    "Safe Word Retry",
                    false
                )
            }
        }

        if (!verifyOwnerLockIfNeeded(settings, fromVoice, "unlocking the device")) {
            pendingUnlockSession = null
            _pendingConfirmationSummary.value = null
            return DirectCommandOutcome(
                "Safe word matched, Sir, but your enrolled Voiceprint did not verify under Voice Lock.",
                "Voice Lock",
                false
            )
        }

        pendingUnlockSession = null
        _pendingConfirmationSummary.value = null

        val svc = JarvisAccessibilityService.activeInstance()
            ?: return DirectCommandOutcome("Safe word confirmed, Sir, but Screen Helper is disconnected.", "Screen Helper Needed", false)
        val dots = credentialStore.getDeviceUnlockPattern()
        if (dots.size < 4) {
            return DirectCommandOutcome("Safe word confirmed, Sir, but no valid pattern is saved in Settings.", "Pattern Needed", false)
        }

        val outcome = svc.performPatternUnlock(dots)
        localRepo.logAiActivity("DEVICE_UNLOCK", if (outcome.success) "Unlocked device via Safe Word" else outcome.message)
        return DirectCommandOutcome(
            outcome.message,
            if (outcome.success) "Device Unlocked" else "Unlock Check",
            outcome.success
        )
    }

    private suspend fun handleDevicePowerAction(mode: String): DirectCommandOutcome {
        val svc = JarvisAccessibilityService.activeInstance()
            ?: return DirectCommandOutcome(
                "Turn on JARVIS Screen Helper in Settings first, Sir, so I can put the device to sleep or open the power menu.",
                "Screen Helper Needed",
                false
            )

        return if (mode.equals("sleep", ignoreCase = true)) {
            _suppressScreenAwakeTemporarily.value = true
            delay(220L)
            val res = svc.performPowerAction("sleep")
            scope.launch {
                delay(6500L)
                _suppressScreenAwakeTemporarily.value = false
            }
            localRepo.logAiActivity("DEVICE_POWER", res.message)
            DirectCommandOutcome(res.message, "Device Sleep", res.success)
        } else {
            val res = svc.performPowerAction(mode)
            localRepo.logAiActivity("DEVICE_POWER", res.message)
            DirectCommandOutcome(
                res.message,
                if (mode.equals("restart", ignoreCase = true)) "Restart Menu" else "Shutdown Menu",
                res.success
            )
        }
    }

    private suspend fun applyVisualFeedbackToggle(
        cmd: ParsedSystemCommand,
        currentSettings: JarvisUserSettings
    ): DirectCommandOutcome {
        val enabled = cmd.secondaryArg == "true"
        var newTap = currentSettings.showTapIndicator
        var newHighlight = currentSettings.showHighlightIndicator
        var newScroll = currentSettings.showScrollIndicator
        when (cmd.primaryArg) {
            "tap" -> {
                newTap = enabled
                prefsRepo.setShowTapIndicator(enabled)
            }
            "highlight" -> {
                newHighlight = enabled
                prefsRepo.setShowHighlightIndicator(enabled)
            }
            "scroll" -> {
                newScroll = enabled
                prefsRepo.setShowScrollIndicator(enabled)
            }
        }
        JarvisAccessibilityService.activeInstance()?.applyVisualFeedbackSettings(
            showTap = newTap,
            showHighlight = newHighlight,
            showScroll = newScroll
        )
        return DirectCommandOutcome(
            "Visual ${cmd.primaryArg} indicator turned ${if (enabled) "on" else "off"}, Sir.",
            "Visual HUD",
            true
        )
    }

    private suspend fun handleDeviceSettingControl(setting: String, mode: String): DirectCommandOutcome {
        val svc = JarvisAccessibilityService.activeInstance()
        val labelName = setting.replaceFirstChar { it.uppercase() }
        if (mode == "close") {
            if (svc == null) {
                return DirectCommandOutcome(
                    "Enable JARVIS Screen Helper on Home or Settings first, Sir, so I can close $setting settings.",
                    "Screen Helper Needed",
                    false
                )
            }
            val closed = svc.closeCurrentSettingsScreen(setting)
            localRepo.logAiActivity("SETTING_CLOSED", "Closed $setting settings")
            return DirectCommandOutcome(closed.message, "$labelName Closed", closed.success)
        }

        if (mode == "open") {
            val openRes = JarvisSystemAssistant.openSettingPanelForModification(context, setting)
            return DirectCommandOutcome(openRes.replyText, openRes.actionLabel, openRes.launchedTarget)
        }

        // Handle explicit sound mute/unmute directly via AudioManager if asked
        if (setting == "sound") {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio != null) {
                if (mode == "off") {
                    audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                    return DirectCommandOutcome("Muted ringer volume, Sir.", "Sound Off", true)
                } else if (mode == "on") {
                    audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                    return DirectCommandOutcome("Restored ringer volume, Sir.", "Sound On", true)
                }
            }
        }

        // Open the Android quick panel / setting page and flip its live switch via Accessibility
        val panelRes = JarvisSystemAssistant.openSettingPanelForModification(context, setting)
        if (!panelRes.launchedTarget) {
            return DirectCommandOutcome("Couldn't open $labelName settings on this device, Sir.", "Setting Error", false)
        }
        if (svc == null) {
            return DirectCommandOutcome(
                "Opened $labelName settings, Sir — enable Screen Helper if you want me to tap the toggle switch automatically.",
                "$labelName Opened",
                true
            )
        }

        val desiredOn = when (mode) {
            "on" -> true
            "off" -> false
            else -> null
        }
        val keywords = JarvisSystemAssistant.resolveSettingKeywords(setting)
        delay(850L)
        var toggleRes = svc.toggleOnScreenSettingSwitch(keywords, desiredOn)
        if (!toggleRes.success) {
            delay(650L)
            toggleRes = svc.toggleOnScreenSettingSwitch(keywords, desiredOn)
        }
        localRepo.logAiActivity("SETTING_CONTROL", "$labelName ($mode): ${toggleRes.message}")
        return if (toggleRes.success) {
            DirectCommandOutcome(toggleRes.message, "$labelName Updated", true)
        } else {
            DirectCommandOutcome(
                "Opened $labelName settings for you, Sir, so you can toggle it directly if Android locked the switch.",
                "$labelName Panel",
                true
            )
        }
    }

    private suspend fun handleDelegateCapability(
        capability: String,
        originalGoal: String,
        currentSettings: JarvisUserSettings
    ): DirectCommandOutcome {
        val capLower = capability.lowercase(Locale.US).trim()
        val svc = JarvisAccessibilityService.activeInstance()

        // 1. System utility capability delegation (alarm, timer, calendar)
        if (capLower in setOf("alarm", "timer", "calendar")) {
            if (!JarvisSystemAssistant.hasInstalledCapabilityHandler(context, capLower)) {
                val query = when (capLower) {
                    "alarm" -> "clock alarm app"
                    "timer" -> "timer clock app"
                    else -> "calendar app"
                }
                return offerAppDownload(query, capLower)
            }
            val openRes = JarvisSystemAssistant.openCapabilityHandler(context, capLower, originalGoal)
            if (!openRes.launchedTarget) {
                return offerAppDownload("$capLower app", capLower)
            }
            localRepo.logAiActivity("CAPABILITY_DELEGATION", "Delegated $capLower -> ${openRes.actionLabel}")
            return DirectCommandOutcome(openRes.replyText, openRes.actionLabel, true)
        }

        // 2. Installed specialized software search (Trading, Marketing, Entertainment, Messaging, Social, Finance)
        val searchQueries = when (capLower) {
            "trading" -> listOf("Trading", "TradingView", "MetaTrader", "MT5", "Binance", "Deriv", "Finance")
            "marketing" -> listOf("Marketing", "Canva", "Meta Business", "Meta Ads", "Instagram", "CapCut")
            "entertainment" -> listOf("Entertainment", "Netflix", "YouTube", "Spotify", "YT Music", "Twitch", "TikTok")
            "messaging" -> listOf("Messaging", "WhatsApp", "Telegram", "Messages", "Snapchat", "Instagram")
            else -> listOf(capability)
        }
        var matchedApp = searchQueries.firstNotNullOfOrNull { q ->
            deviceLearningEngine.findBestInstalledAppByCategoryOrQuery(q)
        }
        if (matchedApp == null && capLower == "entertainment") {
            matchedApp = deviceLearningEngine.findBestInstalledAppByCategoryOrQuery("Social")
        }

        if (matchedApp != null) {
            val openOutcome = JarvisSystemAssistant.execute(
                context,
                ParsedSystemCommand(SystemIntentKind.OPEN_APP, matchedApp.appName)
            )
            if (openOutcome.launchedTarget) {
                deviceLearningEngine.recordAppInteraction(matchedApp.appName)
                val entered = waitForAppForeground(svc, matchedApp.appName, 3000L)
                val genericOpenOnly = Regex(
                    "^(?:please\\s+)?(?:open|launch|start)\\s+.+$",
                    RegexOption.IGNORE_CASE
                ).matches(originalGoal.trim())
                if (entered && !genericOpenOnly && svc != null && originalGoal.split(" ").size >= 5) {
                    delay(450L)
                    val (autoOk, autoOutcome) = runAutonomousUiTask(originalGoal, currentSettings)
                    return DirectCommandOutcome(autoOutcome, matchedApp.appName, autoOk)
                }
                localRepo.logAiActivity("CAPABILITY_DELEGATION", "Opened ${matchedApp.appName} [$capLower]")
                return DirectCommandOutcome(
                    "Opening ${matchedApp.appName} for $capLower, Sir — tell me what you'd like me to tap or look up inside.",
                    matchedApp.appName,
                    true
                )
            }
        }

        // 3. No suitable software installed -> search Play Store and ASK user whether to download!
        val playSearchQuery = when (capLower) {
            "trading" -> "TradingView"
            "marketing" -> "Canva marketing design"
            "entertainment" -> "YouTube"
            else -> "$capLower app"
        }
        return offerAppDownload(playSearchQuery, capLower)
    }

    private suspend fun offerAppDownload(appQuery: String, capabilityLabel: String): DirectCommandOutcome {
        _autonomousStepStatus.value = "Checking Google Play for $appQuery..."
        val discovery = appDiscoveryEngine.discoverApp(appQuery)
        _autonomousStepStatus.value = ""
        pendingInstallSession = PendingInstallSession(
            appQuery = appQuery,
            capabilityLabel = capabilityLabel,
            candidates = discovery.candidates
        )
        val top = discovery.candidates.firstOrNull()
        val label = top?.title ?: appQuery
        _pendingConfirmationSummary.value = "Download $label from Google Play?"
        val msg = if (top != null) {
            "I don't see an installed $capabilityLabel app on your phone, Sir — I found $label on ${top.sourceName}. Would you like me to open it to download?"
        } else {
            "I couldn't find an installed $capabilityLabel app on your phone, Sir. Should I search Google Play for $appQuery so you can download one?"
        }
        return DirectCommandOutcome(msg, "Install Offered", true)
    }

    private fun handlePendingInstallTurn(
        prompt: String,
        session: PendingInstallSession
    ): DirectCommandOutcome? {
        val lower = prompt.lowercase(Locale.US).trim().replace(Regex("[!?.,]+$"), "")
        if (lower in setOf("no", "nope", "cancel", "don't", "do not", "never mind", "nevermind", "later")) {
            pendingInstallSession = null
            _pendingConfirmationSummary.value = null
            return DirectCommandOutcome("Understood, Sir — I won't open Google Play right now.", "Install Declined", true)
        }
        val isYes = lower in setOf(
            "yes", "yeah", "yep", "sure", "ok", "okay", "please", "download", "download it",
            "install", "install it", "go ahead", "open play store", "yes please", "do it"
        ) || lower.startsWith("yes ")
        if (!isYes) {
            pendingInstallSession = null
            _pendingConfirmationSummary.value = null
            return null
        }

        pendingInstallSession = null
        _pendingConfirmationSummary.value = null
        val chosen = session.candidates.firstOrNull()
        val res = if (chosen != null) {
            appDiscoveryEngine.openInstallCandidate(context, chosen)
        } else {
            appDiscoveryEngine.openPlayStoreSearch(context, session.appQuery)
        }
        return DirectCommandOutcome(res.replyText, res.actionLabel, res.launchedTarget)
    }

    private fun shouldLookupOnline(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.US)
        if (lower.startsWith("hi") || lower.startsWith("hello") || lower == "thanks" || lower == "thank you") {
            return false
        }
        return lower.contains("search") ||
            lower.contains("internet") ||
            lower.contains("online") ||
            lower.contains("web") ||
            lower.contains("latest") ||
            lower.contains("news") ||
            lower.contains("price") ||
            lower.contains("market") ||
            lower.contains("trade") ||
            lower.contains("trading") ||
            lower.contains("best ") ||
            lower.contains("weather") ||
            lower.contains("who ") ||
            lower.contains("what is") ||
            lower.contains("what's") ||
            lower.contains("when ") ||
            lower.contains("where ") ||
            lower.contains("how to") ||
            lower.contains("how do i") ||
            lower.contains("app for") ||
            lower.contains("website") ||
            lower.contains("link")
    }

    private fun startScheduledAutomationWatcher() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                localRepo.getActiveScheduledAutomations().forEach { task ->
                    ScheduledAutomationEngine.scheduleAlarm(context, task)
                }
            }
            while (true) {
                delay(15_000L)
                runCatching {
                    val now = System.currentTimeMillis()
                    val active = localRepo.getActiveScheduledAutomations()
                    active.filter { it.triggerAtMs <= now }.forEach { dueTask ->
                        executeScheduledAutomationById(dueTask.id)
                    }
                }
            }
        }
    }

    suspend fun scheduleManualAutomation(
        title: String,
        commandPrompt: String,
        triggerAtMs: Long,
        repeatDaily: Boolean,
        source: String = "AI"
    ): ScheduledAutomationEntity {
        val entity = ScheduledAutomationEntity(
            title = title,
            commandPrompt = commandPrompt,
            triggerAtMs = triggerAtMs,
            repeatDaily = repeatDaily,
            isEnabled = true,
            source = source
        )
        val newId = localRepo.upsertScheduledAutomation(entity)
        val saved = entity.copy(id = newId)
        ScheduledAutomationEngine.scheduleAlarm(context, saved)
        localRepo.logAiActivity(
            "AUTOMATION_QUEUED",
            "${saved.title} for ${ScheduledAutomationEngine.formatScheduleTime(saved.triggerAtMs)}${if (repeatDaily) " (daily)" else ""}"
        )
        return saved
    }

    fun toggleScheduledAutomation(id: Long, enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            localRepo.setScheduledAutomationEnabled(id, enabled)
            val task = localRepo.getScheduledAutomationById(id) ?: return@launch
            if (enabled) {
                val now = System.currentTimeMillis()
                val adjustedTrigger = if (task.triggerAtMs <= now) now + 24 * 3_600_000L else task.triggerAtMs
                val updated = task.copy(triggerAtMs = adjustedTrigger, isEnabled = true)
                localRepo.upsertScheduledAutomation(updated)
                ScheduledAutomationEngine.scheduleAlarm(context, updated)
            } else {
                ScheduledAutomationEngine.cancelAlarm(context, id)
            }
        }
    }

    fun deleteScheduledAutomation(id: Long) {
        scope.launch(Dispatchers.IO) {
            ScheduledAutomationEngine.cancelAlarm(context, id)
            localRepo.deleteScheduledAutomation(id)
        }
    }

    suspend fun executeScheduledAutomationById(taskId: Long) {
        val task = localRepo.getScheduledAutomationById(taskId) ?: return
        if (!task.isEnabled) return
        val now = System.currentTimeMillis()
        val nextTriggerMs = if (task.repeatDaily) {
            var candidate = task.triggerAtMs + 24 * 3_600_000L
            while (candidate <= now + 60_000L) {
                candidate += 24 * 3_600_000L
            }
            candidate
        } else {
            task.triggerAtMs
        }
        val stayEnabled = task.repeatDaily
        localRepo.recordScheduledAutomationRun(
            id = task.id,
            nextTriggerMs = nextTriggerMs,
            enabled = stayEnabled,
            runAtMs = now,
            outcome = "Triggered"
        )
        if (stayEnabled) {
            ScheduledAutomationEngine.scheduleAlarm(context, task.copy(triggerAtMs = nextTriggerMs, isEnabled = true))
        } else {
            ScheduledAutomationEngine.cancelAlarm(context, task.id)
        }

        localRepo.logAiActivity("AUTOMATION_RUN", "Running scheduled task: ${task.title}")
        scope.launch {
            _aiHudHeadline.value = task.title
            submitQuery(task.commandPrompt, fromVoice = false)
        }
    }

    fun triggerOrScheduleSuggestion(suggestion: FuturisticMissionSuggestion) {
        if (!suggestion.isScheduleAction) {
            _aiHudHeadline.value = suggestion.label
            submitQuery(suggestion.commandPrompt, fromVoice = false)
            return
        }
        scope.launch {
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, suggestion.suggestedHour24)
                set(Calendar.MINUTE, suggestion.suggestedMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }
            val saved = scheduleManualAutomation(
                title = suggestion.label,
                commandPrompt = suggestion.commandPrompt,
                triggerAtMs = cal.timeInMillis,
                repeatDaily = suggestion.repeatDaily,
                source = "AI"
            )
            _aiHudHeadline.value = "Queued: ${saved.title}"
            val settings = prefsRepo.settingsFlow.first()
            finishDirectReply(
                "Scheduled \"${saved.title}\" for ${ScheduledAutomationEngine.formatScheduleTime(saved.triggerAtMs)}, Sir.",
                "Automation Queued",
                settings,
                fromVoice = false,
                followUpListen = false
            )
        }
    }

    private suspend fun handleScheduleTask(cmd: ParsedSystemCommand): DirectCommandOutcome {
        val parts = cmd.primaryArg.split("|", limit = 3)
        val triggerAtMs = parts.getOrNull(0)?.toLongOrNull() ?: (System.currentTimeMillis() + 10 * 60_000L)
        val repeatDaily = parts.getOrNull(1)?.equals("true", ignoreCase = true) == true
        val title = parts.getOrNull(2).orEmpty().ifBlank { "Timed Task" }
        val commandPrompt = cmd.secondaryArg.ifBlank { title }

        val saved = scheduleManualAutomation(
            title = title,
            commandPrompt = commandPrompt,
            triggerAtMs = triggerAtMs,
            repeatDaily = repeatDaily,
            source = "USER"
        )
        _aiHudHeadline.value = "Scheduled: ${saved.title}"
        val timeText = ScheduledAutomationEngine.formatScheduleTime(saved.triggerAtMs)
        val countdown = ScheduledAutomationEngine.formatCountdown(saved.triggerAtMs)
        val repeatTag = if (saved.repeatDaily) " every day" else ""
        return DirectCommandOutcome(
            "Done, Sir — scheduled \"${saved.title}\" for $timeText$repeatTag ($countdown).",
            "Task Scheduled",
            true
        )
    }

    private suspend fun handleListAutomations(): DirectCommandOutcome {
        val active = localRepo.getActiveScheduledAutomations()
        if (active.isEmpty()) {
            _aiHudHeadline.value = "No Queued Automations"
            return DirectCommandOutcome(
                "You don't have any scheduled automations right now, Sir. Tell me a time and task—for example, \"At 7:30 AM give me my daily briefing\"—and I'll queue it.",
                "Automations Empty",
                true
            )
        }
        val summary = active.take(5).joinToString("; ") { task ->
            val time = ScheduledAutomationEngine.formatScheduleTime(task.triggerAtMs)
            val countdown = ScheduledAutomationEngine.formatCountdown(task.triggerAtMs)
            val daily = if (task.repeatDaily) " (daily)" else ""
            "${task.title} at $time$daily ($countdown)"
        }
        _aiHudHeadline.value = "${active.size} Automation${if (active.size == 1) "" else "s"} Active"
        return DirectCommandOutcome(
            "You have ${active.size} active automation${if (active.size == 1) "" else "s"}, Sir: $summary.",
            "Automations",
            true
        )
    }

    private suspend fun handleCancelAutomation(query: String): DirectCommandOutcome {
        val active = localRepo.getActiveScheduledAutomations()
        if (active.isEmpty()) {
            return DirectCommandOutcome("There are no active scheduled automations to cancel, Sir.", "No Automations", false)
        }
        val q = query.trim().lowercase(Locale.US)
        if (q == "all" || q == "everything") {
            active.forEach {
                ScheduledAutomationEngine.cancelAlarm(context, it.id)
                localRepo.deleteScheduledAutomation(it.id)
            }
            _aiHudHeadline.value = "Cleared Automations"
            return DirectCommandOutcome("Cleared all ${active.size} scheduled automations, Sir.", "Automations Cleared", true)
        }
        val target = if (q.isBlank()) {
            active.first()
        } else {
            active.firstOrNull {
                it.title.lowercase(Locale.US).contains(q) ||
                    it.commandPrompt.lowercase(Locale.US).contains(q) ||
                    ScheduledAutomationEngine.formatScheduleTime(it.triggerAtMs).lowercase(Locale.US).contains(q)
            }
        }
        if (target == null) {
            return DirectCommandOutcome("I couldn't find a scheduled automation matching \"$query\", Sir.", "Not Found", false)
        }
        ScheduledAutomationEngine.cancelAlarm(context, target.id)
        localRepo.deleteScheduledAutomation(target.id)
        _aiHudHeadline.value = "Cancelled ${target.title}"
        return DirectCommandOutcome("Cancelled your scheduled automation \"${target.title}\", Sir.", "Automation Cancelled", true)
    }
}
