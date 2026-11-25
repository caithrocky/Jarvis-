package com.example.assistant

import android.content.Context
import android.graphics.Color
import android.provider.Settings
import com.example.data.local.JarvisLocalRepository
import com.example.data.local.JarvisPreferencesRepository
import com.example.data.local.JarvisUserSettings
import com.example.data.remote.JarvisAiResult
import com.example.data.remote.NvidiaNimClient
import com.example.data.remote.WebKnowledgeClient
import com.example.domain.model.JarvisChoiceActivity
import com.example.security.SecureCredentialStore
import com.example.voice.JarvisFloatingOrbService
import com.example.voice.JarvisVoiceEngine
import com.example.voice.SpeakerMatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * Shared command, messaging, autonomous UI agent & AI execution brain used by both
 * MainActivity/ViewModel and the voice-first Floating Mini-Orb plugin.
 */
class JarvisCommandCoordinator(
    private val context: Context,
    private val localRepo: JarvisLocalRepository,
    private val prefsRepo: JarvisPreferencesRepository,
    private val credentialStore: SecureCredentialStore,
    private val webClient: WebKnowledgeClient,
    private val nimClient: NvidiaNimClient,
    private val voiceEngine: JarvisVoiceEngine,
    private val deviceLearningEngine: DeviceLearningEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
    private var abortRequested: Boolean = false

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _lastAssistantReply = MutableStateFlow("Tap the orb to speak, Sir — always listening under your command.")
    val lastAssistantReply: StateFlow<String> = _lastAssistantReply.asStateFlow()

    private val _lastUserPrompt = MutableStateFlow("")
    val lastUserPrompt: StateFlow<String> = _lastUserPrompt.asStateFlow()

    private val _autonomousStepStatus = MutableStateFlow("")
    val autonomousStepStatus: StateFlow<String> = _autonomousStepStatus.asStateFlow()

    private val _pendingConfirmationSummary = MutableStateFlow<String?>(null)
    val pendingConfirmationSummary: StateFlow<String?> = _pendingConfirmationSummary.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val navigationEvents: SharedFlow<String> = _navigationEvents.asSharedFlow()

    init {
        scope.launch {
            prefsRepo.settingsFlow.collectLatest { settings ->
                voiceEngine.setFluToleranceEnabled(settings.voiceFluAdaptationEnabled)
                JarvisAccessibilityService.activeInstance()?.applyVisualFeedbackSettings(
                    showTap = settings.showTapIndicator,
                    showHighlight = settings.showHighlightIndicator,
                    showScroll = settings.showScrollIndicator
                )
            }
        }
    }

    fun onForegroundChanged(inForeground: Boolean) {
        isAppInForeground = inForeground
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
            onWake = {
                scope.launch {
                    localRepo.logAiActivity("WAKE_WORD", "Activated via \"Hey JARVIS\"")
                    val settings = prefsRepo.settingsFlow.first()
                    if (!isAppInForeground && settings.floatingOrbEnabled && Settings.canDrawOverlays(context)) {
                        JarvisFloatingOrbService.start(context, expandBubble = true)
                    }
                }
            },
            onCommand = { spokenText ->
                submitQuery(spokenText, fromVoice = true)
            }
        )
    }

    fun abortAllPendingActions() {
        abortRequested = true
        pendingMessageSession = null
        awaitingWebsiteLinkFor = null
        _pendingConfirmationSummary.value = null
        _autonomousStepStatus.value = ""
        _isProcessing.value = false
        JarvisAccessibilityService.activeInstance()?.clearVisualOverlays()
        voiceEngine.interruptSpeech()
        _lastAssistantReply.value = "Standing down right away, Sir."
    }

    fun submitQuery(
        rawPrompt: String,
        fromVoice: Boolean = false
    ) {
        val prompt = rawPrompt.trim()
        if (prompt.isBlank()) return
        val lower = prompt.lowercase(Locale.US)

        if (lower == "stop" || lower == "abort" || lower == "stop jarvis" || lower == "cancel action") {
            abortAllPendingActions()
            scope.launch {
                val settings = prefsRepo.settingsFlow.first()
                finishDirectReply("Stopped everything right away, Sir.", "Owner Abort", settings, fromVoice)
            }
            return
        }

        abortRequested = false
        _lastUserPrompt.value = prompt
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

            // 0. Check if we are in an active interactive messaging session (disambiguation / message entry / send confirm)
            val activeMsgSession = pendingMessageSession
            if (activeMsgSession != null && System.currentTimeMillis() - activeMsgSession.createdAtMs < 180_000L) {
                val handledReply = handlePendingMessageSessionTurn(prompt, activeMsgSession, currentSettings, fromVoice)
                if (handledReply != null) {
                    finishDirectReply(handledReply.first, handledReply.second, currentSettings, fromVoice)
                    return@launch
                }
            } else {
                pendingMessageSession = null
                _pendingConfirmationSummary.value = null
            }

            // 1. If we previously asked Sir for a hard-to-find website link, open it immediately in Chrome
            val pendingSite = awaitingWebsiteLinkFor
            val suppliedUrl = JarvisSystemAssistant.extractDirectUrlOrDomain(prompt)
            if (pendingSite != null && suppliedUrl != null) {
                awaitingWebsiteLinkFor = null
                val openRes = JarvisSystemAssistant.openWebsiteInChrome(context, suppliedUrl)
                finishDirectReply(openRes.replyText, openRes.actionLabel, currentSettings, fromVoice)
                return@launch
            }

            // 2. Fast deterministic handler for Messaging, Social, Apps, Settings, Voice ID, Learning & Credentials
            val directMatch = tryHandleDirectCommand(prompt, currentSettings, fromVoice)
            if (directMatch != null) {
                finishDirectReply(directMatch.first, directMatch.second, currentSettings, fromVoice)
                return@launch
            }

            // 3. Autonomous AI Reasoning + Indexed Live Screen Map + Self-Learned Knowledge + Web Search
            _isProcessing.value = true
            _streamingReply.value = ""

            val svc = JarvisAccessibilityService.activeInstance()
            val (indexedMap, _) = svc?.readRichScreenMap(maxNodes = 32) ?: ("" to emptyList())
            val screenSummary = svc?.readScreenSummary()
            val learnedContext = deviceLearningEngine.buildCompactKnowledgeContext()

            val webResult = if (shouldLookupOnline(prompt)) {
                webClient.searchInternet(prompt)
            } else null
            val webContext = webResult?.formatForPrompt()?.takeIf { it.isNotBlank() }

            val history = localRepo.getRecentMessages(14)
            val memories = if (currentSettings.memoryEnabled) localRepo.getRecentMemories() else emptyList()
            val snapshot = NvidiaNimClient.LiveContextSnapshot(
                primaryActivity = currentSettings.primaryActivity,
                activeScreenSummary = screenSummary,
                internetSearchContext = webContext,
                deviceKnowledgeSummary = learnedContext,
                indexedScreenMap = indexedMap.takeIf { it.isNotBlank() }
            )

            val result = nimClient.chatWithStreaming(
                userPrompt = prompt,
                modelId = currentSettings.selectedModelId,
                history = history,
                memories = memories,
                snapshot = snapshot,
                onPartialToken = { partial ->
                    val (visiblePartial, _) = JarvisSystemAssistant.extractAndStripAiActions(partial)
                    _streamingReply.value = visiblePartial
                }
            )

            val replyText: String
            val toolSummary: String?
            when (result) {
                is JarvisAiResult.Success -> {
                    val (cleanedReply, parsedAiActions) = JarvisSystemAssistant.extractAndStripAiActions(result.replyText)
                    val actionsToRun = if (parsedAiActions.isNotEmpty()) {
                        parsedAiActions
                    } else {
                        JarvisSystemAssistant.parseCommands(prompt)
                    }
                    val executedActionLabels = mutableListOf<String>()
                    val actionNotes = mutableListOf<String>()
                    if (webContext != null) executedActionLabels.add("Web Search")

                    for ((index, cmd) in actionsToRun.withIndex()) {
                        if (abortRequested) break
                        val hasMoreSteps = index < actionsToRun.lastIndex
                        when (cmd.kind) {
                            SystemIntentKind.TEXT_CONTACT -> {
                                val res = executeCrossPlatformTextFlow(cmd, currentSettings, fromVoice)
                                executedActionLabels.add(res.second)
                                actionNotes.add(res.first)
                            }
                            SystemIntentKind.SOCIAL_ACTION -> {
                                val res = executeSocialNavigationFlow(cmd)
                                executedActionLabels.add(res.second)
                                if (!res.first.isBlank()) actionNotes.add(res.first)
                            }
                            SystemIntentKind.AUTONOMOUS_TASK -> {
                                val autoOutcome = runAutonomousUiTask(cmd.primaryArg.ifBlank { prompt }, currentSettings)
                                executedActionLabels.add("Autonomous UI Agent")
                                actionNotes.add(autoOutcome)
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
                                    if (hasMoreSteps && openRes.launchedTarget) delay(1300L)
                                } else {
                                    awaitingWebsiteLinkFor = cmd.primaryArg
                                    executedActionLabels.add("Ask Link")
                                }
                            }
                            SystemIntentKind.WEB_LOOKUP -> executedActionLabels.add("Internet Lookup")
                            else -> {
                                if ((cmd.kind == SystemIntentKind.SIGNIN_ASSIST || cmd.kind == SystemIntentKind.SIGNUP_ASSIST) &&
                                    !verifyOwnerLockIfNeeded(currentSettings, fromVoice, "credential form filling")
                                ) {
                                    actionNotes.add("Voiceprint verification required to fill saved credentials under Sir's lock.")
                                } else {
                                    val outcome = JarvisSystemAssistant.execute(context, cmd)
                                    if (outcome.actionLabel.isNotBlank()) {
                                        executedActionLabels.add(outcome.actionLabel)
                                        localRepo.logAiActivity("SYSTEM_ACTION", "${outcome.actionLabel}: ${outcome.replyText}")
                                    }
                                    if (cmd.kind == SystemIntentKind.OPEN_APP && outcome.launchedTarget) {
                                        deviceLearningEngine.recordAppInteraction(cmd.primaryArg)
                                    }
                                    if (hasMoreSteps && outcome.launchedTarget &&
                                        cmd.kind in setOf(SystemIntentKind.OPEN_APP, SystemIntentKind.OPEN_URL, SystemIntentKind.PLATFORM_SEARCH)
                                    ) {
                                        delay(1300L)
                                    } else if (!outcome.launchedTarget && outcome.replyText.isNotBlank() &&
                                        (cmd.kind == SystemIntentKind.SIGNIN_ASSIST || cmd.kind == SystemIntentKind.SIGNUP_ASSIST || cmd.kind == SystemIntentKind.OPEN_APP)
                                    ) {
                                        actionNotes.add(outcome.replyText)
                                    }
                                }
                            }
                        }
                    }

                    val baseReply = cleanedReply.ifBlank { "Right away, Sir." }
                    replyText = if (actionNotes.isNotEmpty()) {
                        actionNotes.last()
                    } else {
                        baseReply
                    }
                    val combinedTools = (result.toolCallsExecuted + executedActionLabels).distinct()
                    toolSummary = combinedTools.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    if (currentSettings.memoryEnabled && result.suggestedMemoryFact != null) {
                        localRepo.saveMemory("preference", result.suggestedMemoryFact)
                    }
                    localRepo.logAiActivity("AI_RESPONSE", "NVIDIA NIM (${result.modelUsed}) answered query")
                }
                is JarvisAiResult.ConfigurationRequired -> {
                    val onlineFallback = webResult?.formatForUserReply().orEmpty()
                    replyText = if (onlineFallback.isNotBlank()) {
                        "From the internet, Sir: $onlineFallback"
                    } else {
                        result.message
                    }
                    toolSummary = if (onlineFallback.isNotBlank()) "Internet Search" else "Setup Needed"
                }
                is JarvisAiResult.Error -> {
                    val onlineFallback = webResult?.formatForUserReply().orEmpty()
                    replyText = if (onlineFallback.isNotBlank()) {
                        "I had trouble reaching NVIDIA NIM, Sir, so I pulled this straight from the web: $onlineFallback"
                    } else {
                        result.message
                    }
                    toolSummary = if (onlineFallback.isNotBlank()) "Internet Fallback" else "NIM Error"
                }
            }

            _lastAssistantReply.value = replyText
            localRepo.saveMessage(
                role = "assistant",
                content = replyText,
                modelUsed = currentSettings.selectedModelId,
                isVoice = fromVoice,
                toolSummary = toolSummary
            )

            _isProcessing.value = false
            _streamingReply.value = ""

            if (currentSettings.voiceOutputEnabled) {
                voiceEngine.speak(replyText.take(520), followUpListen = fromVoice)
            }
        }
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
        _autonomousStepStatus.value = "Verifying Sir's voiceprint for $actionDescription..."
        voiceEngine.speak("Quick voice check for $actionDescription, Sir — speak briefly now.", followUpListen = false)
        delay(1700L)
        val res = voiceEngine.verifyOwnerVoiceBurst(durationMs = 2000L)
        _autonomousStepStatus.value = ""
        return res.state == SpeakerMatchState.VERIFIED_OWNER || res.state == SpeakerMatchState.VERIFIED_VOCAL_SHIFT
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

        _autonomousStepStatus.value = "Opening $platform & finding $contact..."
        deviceLearningEngine.recordCommandHabit(contact, SystemIntentKind.TEXT_CONTACT)

        val launchOutcome = JarvisSystemAssistant.execute(context, ParsedSystemCommand(SystemIntentKind.OPEN_APP, platform))
        if (!launchOutcome.launchedTarget) {
            _autonomousStepStatus.value = ""
            return launchOutcome.replyText to "App Lookup"
        }
        deviceLearningEngine.recordAppInteraction(platform)
        delay(1450L)

        // Attempt search inside the opened messaging app
        val firstSearch = svc.findAndTapSearchAndType(contact)
        if (!firstSearch.success) {
            delay(750L)
            svc.findAndTapSearchAndType(contact)
        }
        delay(1250L)

        val candidates = svc.findContactCandidates(contact)
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
            svc.showHighlightsOnScreen(highlights, durationMs = 12_000L)
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
            delay(950L)
            return stageMessageAndAskSendConfirmation(svc, platform, chosen.displayName, messageText)
        }

        // If already inside a chat or no separate row matched, see if a compose box is already open
        val screenText = svc.readScreenSummary().lowercase(Locale.US)
        return if (screenText.contains("input fields:") && (screenText.contains("message") || screenText.contains("chat"))) {
            stageMessageAndAskSendConfirmation(svc, platform, contact, messageText)
        } else {
            pendingMessageSession = PendingMessageSession(
                platform = platform,
                contactQuery = contact,
                draftMessage = messageText,
                stage = MessageStage.AWAITING_CONTACT_CHOICE,
                candidates = emptyList()
            )
            "I opened $platform and searched for \"$contact\", Sir, but didn't get a clear clickable result row yet. Tap the chat you want or tell me what's visible and I'll type your message." to "$platform Search"
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
                    // Not a choice answer — clear pending session and handle as a normal command
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
                    // User issued another command instead
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
                delay(1400L)
            }
        }

        deviceLearningEngine.recordCommandHabit(cmd.primaryArg, SystemIntentKind.SOCIAL_ACTION)
        val result = svc.performSocialAction(cmd.primaryArg, cmd.secondaryArg)
        return result.message to "Social Control"
    }

    /**
     * Autonomous multi-step UI Screen Agent for unfamiliar requests inside apps or websites.
     */
    private suspend fun runAutonomousUiTask(goal: String, settings: JarvisUserSettings): String {
        val svc = JarvisAccessibilityService.activeInstance()
            ?: return "Turn on JARVIS Screen Helper first, Sir, so I can inspect the screen and complete that task automatically."

        val stepLog = mutableListOf<String>()
        var lastPkg = svc.activeForegroundPackage().orEmpty()

        for (step in 1..6) {
            if (abortRequested) {
                _autonomousStepStatus.value = ""
                return "Paused autonomous steps at your command, Sir."
            }

            _autonomousStepStatus.value = "Autonomous step $step/6: studying screen..."
            val (screenMap, _) = svc.readRichScreenMap(maxNodes = 36)
            lastPkg = svc.activeForegroundPackage().orEmpty()

            val promptForAgent = """
                Goal requested by Sir: "$goal"
                Steps taken so far: ${stepLog.ifEmpty { listOf("none") }.joinToString(" -> ")}
                Look at Live Indexed UI Elements carefully and output ONLY ONE next concrete action tag to advance the goal, plus a 1-sentence human explanation:
                - Use [ACTION:CLICK_INDEX|n] to tap an exact element number from Live Indexed UI Elements.
                - Use [ACTION:SEARCH_IN_APP|query] or [ACTION:TYPE_TEXT|text|field] to enter text.
                - Use [ACTION:SCROLL|down] or [ACTION:SCROLL|up] to scroll if not visible yet.
                - If the goal is now complete or needs Sir's confirmation before submitting/sending/buying, do NOT emit an action tag and clearly explain what's ready.
            """.trimIndent()

            val snapshot = NvidiaNimClient.LiveContextSnapshot(
                primaryActivity = settings.primaryActivity,
                indexedScreenMap = screenMap,
                deviceKnowledgeSummary = deviceLearningEngine.buildCompactKnowledgeContext()
            )
            val res = nimClient.chatWithStreaming(
                userPrompt = promptForAgent,
                modelId = settings.selectedModelId,
                history = emptyList(),
                memories = emptyList(),
                snapshot = snapshot,
                onPartialToken = {}
            )

            when (res) {
                is JarvisAiResult.Success -> {
                    val (cleanMsg, tags) = JarvisSystemAssistant.extractAndStripAiActions(res.replyText)
                    if (tags.isEmpty()) {
                        _autonomousStepStatus.value = ""
                        if (stepLog.isNotEmpty() && lastPkg.isNotBlank()) {
                            localRepo.saveLearnedWorkflow(lastPkg, goal, stepLog.joinToString(" -> "))
                        }
                        return cleanMsg.ifBlank { "Task completed on screen, Sir." }
                    }
                    val nextCmd = tags.first()
                    val exec = JarvisSystemAssistant.execute(context, nextCmd)
                    stepLog.add("${nextCmd.kind}:${nextCmd.primaryArg}")
                    _autonomousStepStatus.value = "Step $step/6: ${exec.actionLabel}"
                    delay(950L)
                }
                else -> {
                    _autonomousStepStatus.value = ""
                    return "I hit a connection snag while planning the next screen step, Sir."
                }
            }
        }

        _autonomousStepStatus.value = ""
        if (stepLog.isNotEmpty() && lastPkg.isNotBlank()) {
            localRepo.saveLearnedWorkflow(lastPkg, goal, stepLog.joinToString(" -> "))
        }
        return "I ran ${stepLog.size} steps on your screen (${stepLog.lastOrNull().orEmpty()}), Sir — let me know if you'd like me to keep going."
    }

    private suspend fun finishDirectReply(
        reply: String,
        toolLabel: String,
        currentSettings: JarvisUserSettings,
        fromVoice: Boolean
    ) {
        _lastAssistantReply.value = reply
        localRepo.saveMessage(
            role = "assistant",
            content = reply,
            modelUsed = "JARVIS System & Plugin Router",
            isVoice = fromVoice,
            toolSummary = toolLabel
        )
        if (currentSettings.voiceOutputEnabled) {
            voiceEngine.speak(reply, followUpListen = fromVoice)
        }
    }

    private suspend fun tryHandleDirectCommand(
        prompt: String,
        currentSettings: JarvisUserSettings,
        fromVoice: Boolean
    ): Pair<String, String>? {
        val commands = JarvisSystemAssistant.parseCommands(prompt)
        if (commands.isEmpty()) return null

        val replies = mutableListOf<String>()
        val labels = mutableListOf<String>()

        for ((index, parsed) in commands.withIndex()) {
            if (abortRequested) break
            val hasMoreSteps = index < commands.lastIndex
            val stepResult: Pair<String, String>? = when (parsed.kind) {
                SystemIntentKind.SWITCH_ACTIVITY_ASSISTANT -> {
                    prefsRepo.setPrimaryActivity(JarvisChoiceActivity.ASSISTANT)
                    localRepo.logAiActivity("ACTIVITY_SWITCH", "System Assistant active")
                    "Right away, Sir. Ready as your personal assistant and floating screen plugin." to "System Assistant"
                }
                SystemIntentKind.SWITCH_ACTIVITY_MARKETING -> {
                    "Certainly, Sir. Just tell me which trading app or website to open in Chrome, or ask me to check market info online and analyze your screen." to "Assistant & Web"
                }
                SystemIntentKind.NAVIGATE_SECTION -> {
                    _navigationEvents.tryEmit(parsed.primaryArg)
                    "Opening ${parsed.primaryArg.replaceFirstChar { it.uppercase() }}, Sir." to "Navigation"
                }
                SystemIntentKind.FLOATING_ORB -> {
                    manualOrbPinned = parsed.primaryArg != "close"
                    val exec = JarvisSystemAssistant.execute(context, parsed)
                    localRepo.logAiActivity("FLOATING_ORB", exec.replyText)
                    exec.replyText to exec.actionLabel
                }
                SystemIntentKind.TEXT_CONTACT -> {
                    executeCrossPlatformTextFlow(parsed, currentSettings, fromVoice)
                }
                SystemIntentKind.SOCIAL_ACTION -> {
                    executeSocialNavigationFlow(parsed)
                }
                SystemIntentKind.STUDY_DEVICE -> {
                    _autonomousStepStatus.value = "Studying installed apps & patterns..."
                    val report = deviceLearningEngine.studyDeviceNow(force = true)
                    _autonomousStepStatus.value = ""
                    report.summaryMessage to "Device Study"
                }
                SystemIntentKind.VERIFY_VOICE -> {
                    val vRes = voiceEngine.verifyOwnerVoiceBurst()
                    localRepo.logAiActivity("VOICE_ID", vRes.detailMessage)
                    vRes.detailMessage to "Voice ID"
                }
                SystemIntentKind.VISUAL_FEEDBACK_TOGGLE -> {
                    val enabled = parsed.secondaryArg == "true"
                    when (parsed.primaryArg) {
                        "tap" -> prefsRepo.setShowTapIndicator(enabled)
                        "highlight" -> prefsRepo.setShowHighlightIndicator(enabled)
                        "scroll" -> prefsRepo.setShowScrollIndicator(enabled)
                    }
                    "Visual ${parsed.primaryArg} indicator turned ${if (enabled) "on" else "off"}, Sir." to "Visual HUD"
                }
                SystemIntentKind.DAILY_BRIEFING -> {
                    val apps = localRepo.getAllDeviceAppsSnapshot()
                    val habits = localRepo.getTopHabits()
                    val telemetry = JarvisSystemAssistant.buildSystemTelemetryReport(context)
                    val learnedBrief = if (apps.isNotEmpty()) {
                        " I've indexed ${apps.size} device apps and ${habits.size} learned habits for you today."
                    } else ""
                    "$telemetry$learnedBrief How would you like to start, Sir?" to "Daily Briefing"
                }
                SystemIntentKind.OPEN_UNKNOWN_WEBSITE -> {
                    _isProcessing.value = true
                    val foundUrl = webClient.findWebsiteUrl(parsed.primaryArg)
                    _isProcessing.value = false
                    if (foundUrl != null) {
                        awaitingWebsiteLinkFor = null
                        val openRes = JarvisSystemAssistant.openWebsiteInChrome(context, foundUrl)
                        localRepo.logAiActivity("CHROME_OPEN", "Found & opened ${parsed.primaryArg} -> $foundUrl")
                        if (hasMoreSteps && openRes.launchedTarget) delay(1300L)
                        openRes.replyText to "Chrome Lookup"
                    } else {
                        awaitingWebsiteLinkFor = parsed.primaryArg
                        "I couldn't find the exact website address for \"${parsed.primaryArg}\" online, Sir. Please tell me or paste the link (for example site.com), and I will open it in Chrome right away." to "Link Needed"
                    }
                }
                SystemIntentKind.GUIDE_SCREEN -> {
                    val svc = JarvisAccessibilityService.activeInstance()
                    if (svc == null) {
                        "Turn on Screen Helper on Home or in Settings first, Sir, so I can read the screen and guide you step by step (like ID uploads, forms, or trading screens)." to "Screen Helper Needed"
                    } else {
                        val guidance = svc.analyzeCurrentFlowStep()
                        if (guidance.stepKind == "GENERAL_SCREEN" && commands.size == 1 && !prompt.lowercase(Locale.US).contains("next step")) {
                            null
                        } else {
                            localRepo.logAiActivity("STEP_GUIDE", "${guidance.stepKind}: guided user on screen")
                            guidance.spokenGuidance to "Step Guide"
                        }
                    }
                }
                SystemIntentKind.SIGNIN_ASSIST, SystemIntentKind.SIGNUP_ASSIST -> {
                    if (!verifyOwnerLockIfNeeded(currentSettings, fromVoice, "filling saved credentials")) {
                        "Voiceprint verification didn't match Sir's enrolled voice, so credential autofill stays locked." to "Voice Lock"
                    } else {
                        val exec = JarvisSystemAssistant.execute(context, parsed)
                        if (!exec.handled) null else {
                            localRepo.logAiActivity("SYSTEM_ACTION", "${exec.actionLabel}: ${exec.replyText}")
                            exec.replyText to exec.actionLabel
                        }
                    }
                }
                else -> {
                    val exec = JarvisSystemAssistant.execute(context, parsed)
                    if (!exec.handled) null else {
                        localRepo.logAiActivity("SYSTEM_ACTION", "${exec.actionLabel}: ${exec.replyText}")
                        if (parsed.kind == SystemIntentKind.OPEN_APP && exec.launchedTarget) {
                            deviceLearningEngine.recordAppInteraction(parsed.primaryArg)
                        }
                        if (hasMoreSteps && exec.launchedTarget &&
                            parsed.kind in setOf(SystemIntentKind.OPEN_APP, SystemIntentKind.OPEN_URL, SystemIntentKind.PLATFORM_SEARCH)
                        ) {
                            delay(1300L)
                        }
                        exec.replyText to exec.actionLabel
                    }
                }
            }

            if (stepResult != null) {
                if (stepResult.first.isNotBlank()) replies.add(stepResult.first)
                if (stepResult.second.isNotBlank()) labels.add(stepResult.second)
            } else if (commands.size == 1) {
                return null
            }
        }

        if (replies.isEmpty()) return null
        return replies.joinToString(" ") to labels.distinct().joinToString(", ")
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
            lower.contains("website") ||
            lower.contains("link")
    }
}
