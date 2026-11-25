package com.example.assistant

import android.content.Context
import android.provider.Settings
import com.example.data.local.JarvisLocalRepository
import com.example.data.local.JarvisPreferencesRepository
import com.example.data.local.JarvisUserSettings
import com.example.data.remote.JarvisAiResult
import com.example.data.remote.NvidiaNimClient
import com.example.data.remote.WebKnowledgeClient
import com.example.domain.model.JarvisChoiceActivity
import com.example.voice.JarvisFloatingOrbService
import com.example.voice.JarvisVoiceEngine
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Shared command & AI execution brain used by both MainActivity/ViewModel and the
 * background Wake-Word + Floating Mini-Orb plugin so JARVIS works everywhere.
 */
class JarvisCommandCoordinator(
    private val context: Context,
    private val localRepo: JarvisLocalRepository,
    private val prefsRepo: JarvisPreferencesRepository,
    private val webClient: WebKnowledgeClient,
    private val nimClient: NvidiaNimClient,
    private val voiceEngine: JarvisVoiceEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    @Volatile
    var manualOrbPinned: Boolean = false

    @Volatile
    private var awaitingWebsiteLinkFor: String? = null

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _lastAssistantReply = MutableStateFlow("Tap the orb and tell me what you need, Sir.")
    val lastAssistantReply: StateFlow<String> = _lastAssistantReply.asStateFlow()

    private val _lastUserPrompt = MutableStateFlow("")
    val lastUserPrompt: StateFlow<String> = _lastUserPrompt.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val navigationEvents: SharedFlow<String> = _navigationEvents.asSharedFlow()

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

    fun submitQuery(
        rawPrompt: String,
        fromVoice: Boolean = false
    ) {
        val prompt = rawPrompt.trim()
        if (prompt.isBlank()) return

        _lastUserPrompt.value = prompt
        scope.launch {
            val currentSettings = prefsRepo.settingsFlow.first()
            voiceEngine.configureVoice(
                currentSettings.voicePersonality,
                currentSettings.voiceSpeed,
                currentSettings.useNvidiaMagpieVoice
            )

            localRepo.saveMessage(
                role = "user",
                content = prompt,
                modelUsed = currentSettings.selectedModelId,
                isVoice = fromVoice
            )

            // 1. If we previously asked Sir for a hard-to-find website link, open it immediately in Chrome
            val pendingSite = awaitingWebsiteLinkFor
            val suppliedUrl = JarvisSystemAssistant.extractDirectUrlOrDomain(prompt)
            if (pendingSite != null && suppliedUrl != null) {
                awaitingWebsiteLinkFor = null
                val openRes = JarvisSystemAssistant.openWebsiteInChrome(context, suppliedUrl)
                finishDirectReply(openRes.replyText, openRes.actionLabel, currentSettings, fromVoice)
                return@launch
            }

            // 2. Fast deterministic handler for Chrome links, apps, settings, screen control & credentials
            val directMatch = tryHandleDirectCommand(prompt, currentSettings, fromVoice)
            if (directMatch != null) {
                finishDirectReply(directMatch.first, directMatch.second, currentSettings, fromVoice)
                return@launch
            }

            // 3. AI Reasoning + Live Internet Search + Active Screen Context
            _isProcessing.value = true
            _streamingReply.value = ""

            val screenSummary = JarvisAccessibilityService.activeInstance()?.let { svc ->
                val lower = prompt.lowercase(Locale.US)
                if (!isAppInForeground || lower.contains("screen") || lower.contains("analy") ||
                    lower.contains("chart") || lower.contains("trade") || lower.contains("market") ||
                    lower.contains("guide") || lower.contains("sign") || lower.contains("upload")
                ) {
                    svc.readScreenSummary()
                } else null
            }

            val webResult = if (shouldLookupOnline(prompt)) {
                webClient.searchInternet(prompt)
            } else null
            val webContext = webResult?.formatForPrompt()?.takeIf { it.isNotBlank() }

            val history = localRepo.getRecentMessages(14)
            val memories = if (currentSettings.memoryEnabled) localRepo.getRecentMemories() else emptyList()
            val snapshot = NvidiaNimClient.LiveContextSnapshot(
                primaryActivity = currentSettings.primaryActivity,
                activeScreenSummary = screenSummary,
                internetSearchContext = webContext
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
                        // Safety net if NIM acknowledged an action in prose ("Opening Chrome...") without emitting [ACTION:...]
                        JarvisSystemAssistant.parseCommands(prompt)
                    }
                    val executedActionLabels = mutableListOf<String>()
                    val actionNotes = mutableListOf<String>()
                    if (webContext != null) executedActionLabels.add("Web Search")

                    for ((index, cmd) in actionsToRun.withIndex()) {
                        val hasMoreSteps = index < actionsToRun.lastIndex
                        when (cmd.kind) {
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
                            SystemIntentKind.WEB_LOOKUP -> {
                                executedActionLabels.add("Internet Lookup")
                            }
                            else -> {
                                val outcome = JarvisSystemAssistant.execute(context, cmd)
                                if (outcome.actionLabel.isNotBlank()) {
                                    executedActionLabels.add(outcome.actionLabel)
                                    localRepo.logAiActivity("SYSTEM_ACTION", "${outcome.actionLabel}: ${outcome.replyText}")
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

                    val baseReply = cleanedReply.ifBlank { "Right away, Sir." }
                    replyText = if (actionNotes.isNotEmpty()) {
                        "$baseReply\n\n${actionNotes.last()}"
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
                        "I had trouble reaching NVIDIA NIM, Sir, so I checked the internet directly: $onlineFallback"
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
                else -> {
                    val exec = JarvisSystemAssistant.execute(context, parsed)
                    if (!exec.handled) null else {
                        localRepo.logAiActivity("SYSTEM_ACTION", "${exec.actionLabel}: ${exec.replyText}")
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
