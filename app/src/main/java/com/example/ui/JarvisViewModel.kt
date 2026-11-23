package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JarvisApplication
import com.example.analysis.RiskValidationResult
import com.example.analysis.TechnicalAnalysisEngine
import com.example.analysis.TradingRiskEngine
import com.example.data.local.AiActivityLogEntity
import com.example.data.local.AlertNotificationEntity
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.JarvisUserSettings
import com.example.data.local.MemoryFactEntity
import com.example.data.local.MonitoringTaskEntity
import com.example.data.local.OrderAuditEntity
import com.example.data.remote.JarvisAiResult
import com.example.data.remote.NvidiaNimClient
import com.example.data.remote.OrderExecutionResult
import com.example.domain.model.ApiIntegrationStatus
import com.example.domain.model.BrokerAccountInfo
import com.example.domain.model.BrokerType
import com.example.domain.model.DataFreshness
import com.example.domain.model.EconomicEvent
import com.example.domain.model.MarketAssetClass
import com.example.domain.model.MarketQuote
import com.example.domain.model.NewsItem
import com.example.domain.model.OhlcCandle
import com.example.domain.model.OpenPosition
import com.example.domain.model.OrbState
import com.example.domain.model.RiskLimits
import com.example.domain.model.TradeProposal
import com.example.domain.model.TradingOperatingMode
import com.example.domain.model.TradingOpportunity
import com.example.domain.model.VoicePersonality
import com.example.security.SecretsConfig
import com.example.voice.JarvisVoiceService
import com.example.voice.VoicePipelinePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

enum class JarvisSection(val route: String, val label: String) {
    HOME("home", "Home"),
    JARVIS("jarvis", "JARVIS"),
    MARKETS("markets", "Markets"),
    TRADING("trading", "Trading"),
    ACCOUNTS("accounts", "Accounts"),
    NEWS("news", "News"),
    HISTORY("history", "History"),
    AUTOMATION("automation", "Automation"),
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
    val quotes: List<MarketQuote> = emptyList(),
    val isLoadingMarkets: Boolean = false,
    val marketError: String? = null,
    val selectedAssetClass: MarketAssetClass = MarketAssetClass.FOREX,
    val selectedSymbol: String = "EUR/USD",
    val selectedTimeframe: String = "1h",
    val selectedCandles: List<OhlcCandle> = emptyList(),
    val opportunities: List<TradingOpportunity> = emptyList(),
    val accounts: List<BrokerAccountInfo> = emptyList(),
    val openPositions: List<OpenPosition> = emptyList(),
    val brokerTradeHistory: List<String> = emptyList(),
    val isSyncingBrokers: Boolean = false,
    val pendingProposals: List<TradeProposal> = emptyList(),
    val newsItems: List<NewsItem> = emptyList(),
    val newsError: String? = null,
    val economicEvents: List<EconomicEvent> = emptyList(),
    val calendarNotice: String? = null,
    val isLoadingNews: Boolean = false,
    val apiStatuses: List<ApiIntegrationStatus> = emptyList(),
    val diagnosticResult: String? = null,
    val snackbarMessage: String? = null
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApplication
    private val localRepo = app.localRepository
    private val marketCacheRepo = app.marketCacheRepository
    private val prefsRepo = app.preferencesRepository
    private val marketRepo = app.marketRepository
    private val newsRepo = app.newsRepository
    private val nimClient = app.nimClient
    private val magpieTtsClient = app.magpieTtsClient
    private val brokerRepo = app.brokerRepository
    private val supabaseClient = app.supabaseClient
    private val voiceEngine = app.voiceEngine

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    val settings: StateFlow<JarvisUserSettings> = prefsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), JarvisUserSettings())

    val cachedTickerQuotes: StateFlow<List<MarketQuote>> = marketCacheRepo.cachedConfiguredQuotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messages: StateFlow<List<ConversationMessageEntity>> = localRepo.messages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryFactEntity>> = localRepo.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val monitoringTasks: StateFlow<List<MonitoringTaskEntity>> = localRepo.monitoringTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val orderAudits: StateFlow<List<OrderAuditEntity>> = localRepo.orderAudits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val alerts: StateFlow<List<AlertNotificationEntity>> = localRepo.alerts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aiLogs: StateFlow<List<AiActivityLogEntity>> = localRepo.aiLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        setupVoiceCallbacks()
        observeVoiceTelemetry()
        hydrateMarketCache()
        refreshApiStatuses()
        refreshMarkets()
        refreshNewsAndCalendar()
        syncBrokers()
        startLiveMarketPolling()
    }

    private fun hydrateMarketCache() {
        viewModelScope.launch {
            marketCacheRepo.ensureConfiguredSymbolsSeeded(
                instruments = marketRepo.supportedInstruments,
                enabledSymbols = settings.value.watchlistSymbols
            )
            val cached = marketCacheRepo.getCachedSnapshot()
            if (cached.isNotEmpty() && _uiState.value.quotes.isEmpty()) {
                _uiState.update { it.copy(quotes = cached) }
            }
        }
    }

    private fun setupVoiceCallbacks() {
        voiceEngine.setCallbacks(
            onWake = {
                viewModelScope.launch {
                    localRepo.logAiActivity("WAKE_WORD", "Activated via \"Hey JARVIS\"")
                }
            },
            onCommand = { spokenText ->
                sendUserQuery(spokenText, fromVoice = true)
            }
        )
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
            }.collect { (phase, rms, partial, detail, bt) ->
                _uiState.update { state ->
                    val hasAlert = state.quotes.isNotEmpty() &&
                        state.quotes.all { it.freshness == DataFreshness.UNAVAILABLE }
                    val orb = when {
                        settings.value.riskLimits.emergencyStopActive -> OrbState.ALERT
                        hasAlert && phase == VoicePipelinePhase.STOPPED -> OrbState.OFFLINE
                        else -> voiceEngine.suggestedOrbState(state.isAiProcessing, hasCriticalAlert = false)
                    }
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

    private fun startLiveMarketPolling() {
        viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                refreshMarkets(silent = true)
            }
        }
    }

    fun navigateTo(section: JarvisSection) {
        _uiState.update { it.copy(selectedSection = section) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
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
                localRepo.logAiActivity("VOICE_SERVICE", "Always-on Hey JARVIS foreground service enabled")
            } else {
                JarvisVoiceService.stop(app)
                voiceEngine.stopAllListening()
                localRepo.logAiActivity("VOICE_SERVICE", "Always-on Hey JARVIS foreground service stopped")
            }
        }
    }

    fun selectAssetClass(assetClass: MarketAssetClass) {
        val firstMatch = _uiState.value.quotes.firstOrNull { it.assetClass == assetClass }?.symbol
            ?: marketRepo.supportedInstruments.firstOrNull { it.assetClass == assetClass }?.symbol
            ?: _uiState.value.selectedSymbol
        _uiState.update { it.copy(selectedAssetClass = assetClass) }
        selectSymbol(firstMatch)
    }

    fun selectSymbol(symbol: String, timeframe: String = _uiState.value.selectedTimeframe) {
        _uiState.update { it.copy(selectedSymbol = symbol, selectedTimeframe = timeframe) }
        viewModelScope.launch {
            val candles = marketRepo.fetchCandles(symbol, timeframe)
            _uiState.update { state ->
                val opp = TechnicalAnalysisEngine.analyze(
                    symbol = symbol,
                    displayName = state.quotes.find { it.symbol == symbol }?.displayName ?: symbol,
                    timeframe = timeframe,
                    candles = candles,
                    newsHeadline = state.newsItems.firstOrNull { it.affectedAssets.any { a -> symbol.contains(a) } }?.title
                )
                val updatedOpps = if (opp != null) {
                    (listOf(opp) + state.opportunities.filterNot { it.symbol == symbol && it.timeframe == timeframe }).take(8)
                } else state.opportunities
                state.copy(selectedCandles = candles, opportunities = updatedOpps)
            }
        }
    }

    fun refreshMarkets(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _uiState.update { it.copy(isLoadingMarkets = true, marketError = null) }
            runCatching {
                val quotes = marketRepo.fetchAllMarketQuotes()
                marketCacheRepo.cacheQuotes(quotes)
                val cachedFallback = marketCacheRepo.getCachedSnapshot().associateBy { it.symbol }
                val mergedQuotes = quotes.map { live ->
                    if (live.price > 0.0) live else cachedFallback[live.symbol] ?: live
                }
                val activeSym = _uiState.value.selectedSymbol
                val tf = _uiState.value.selectedTimeframe
                val candles = marketRepo.fetchCandles(activeSym, tf)
                val keySymbols = listOf(activeSym, "EUR/USD", "XAU/USD", "BTC/USDT").distinct()
                val opps = mutableListOf<TradingOpportunity>()
                for (sym in keySymbols) {
                    val symCandles = if (sym == activeSym) candles else marketRepo.fetchCandles(sym, tf, 60)
                    val q = mergedQuotes.find { it.symbol == sym }
                    TechnicalAnalysisEngine.analyze(
                        symbol = sym,
                        displayName = q?.displayName ?: sym,
                        timeframe = tf,
                        candles = symCandles,
                        newsHeadline = _uiState.value.newsItems.firstOrNull()?.title
                    )?.let { opps.add(it) }
                }

                val anyLive = mergedQuotes.any { it.freshness != DataFreshness.UNAVAILABLE }
                _uiState.update {
                    it.copy(
                        quotes = mergedQuotes,
                        selectedCandles = candles,
                        opportunities = if (opps.isNotEmpty()) opps else it.opportunities,
                        isLoadingMarkets = false,
                        marketError = if (anyLive) null else "Market feeds unreachable. Check internet connectivity."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingMarkets = false,
                        marketError = "Market refresh failed: ${err.message}"
                    )
                }
            }
        }
    }

    fun refreshNewsAndCalendar() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingNews = true) }
            val (news, newsErr) = newsRepo.fetchLiveFinancialNews()
            val (events, calNotice) = newsRepo.fetchEconomicCalendar()
            _uiState.update {
                it.copy(
                    newsItems = news,
                    newsError = newsErr,
                    economicEvents = events,
                    calendarNotice = calNotice,
                    isLoadingNews = false
                )
            }
        }
    }

    fun syncBrokers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingBrokers = true) }
            val spotPrices = _uiState.value.quotes
                .filter { it.price > 0.0 }
                .associate { it.symbol to it.price }
            val result = brokerRepo.syncAllBrokers(spotPrices)
            _uiState.update {
                it.copy(
                    accounts = result.accounts,
                    openPositions = result.positions,
                    brokerTradeHistory = result.tradeHistorySummary,
                    isSyncingBrokers = false
                )
            }
            refreshApiStatuses()
        }
    }

    fun buildDerivOAuthUrl(): String? = brokerRepo.buildDerivOAuthUrl()

    fun handleDeepLink(uri: Uri) {
        if (uri.scheme == "jarvis" && uri.host == "deriv-oauth") {
            val ok = brokerRepo.handleDerivOAuthCallback(uri)
            if (ok) {
                _uiState.update { it.copy(snackbarMessage = "Deriv OAuth authorization saved securely.") }
                viewModelScope.launch {
                    localRepo.logAiActivity("BROKER_OAUTH", "Deriv account authorized via OAuth2 redirect")
                }
                syncBrokers()
            } else {
                _uiState.update { it.copy(snackbarMessage = "Deriv OAuth callback missing token.") }
            }
        }
    }

    fun disconnectDeriv() {
        brokerRepo.disconnectDerivOAuth()
        syncBrokers()
        _uiState.update { it.copy(snackbarMessage = "Deriv OAuth session disconnected.") }
    }

    fun askJarvisAboutOpportunity(opportunity: TradingOpportunity) {
        navigateTo(JarvisSection.JARVIS)
        val prompt = "Explain your ${opportunity.suggestedAction} analysis on ${opportunity.symbol} (${opportunity.timeframe}) at entry ${TechnicalAnalysisEngine.formatPrice(opportunity.suggestedEntry)}, stop-loss ${TechnicalAnalysisEngine.formatPrice(opportunity.stopLoss)}, take-profit ${TechnicalAnalysisEngine.formatPrice(opportunity.takeProfit)}, RSI ${"%.1f".format(opportunity.indicators.rsi14)}."
        sendUserQuery(prompt, fromVoice = false)
    }

    fun summarizeNewsWithJarvis(newsItem: NewsItem) {
        navigateTo(JarvisSection.JARVIS)
        val prompt = "Summarize the market impact of this headline from ${newsItem.source}: \"${newsItem.title}\". Affected assets: ${newsItem.affectedAssets.joinToString(", ")}."
        sendUserQuery(prompt, fromVoice = false)
    }

    fun sendUserQuery(rawPrompt: String, fromVoice: Boolean = false) {
        val prompt = rawPrompt.trim()
        if (prompt.isBlank()) return
        val currentSettings = settings.value

        viewModelScope.launch {
            localRepo.saveMessage(
                role = "user",
                content = prompt,
                modelUsed = currentSettings.selectedModelId,
                isVoice = fromVoice
            )

            // Handle immediate deterministic safety/action intents locally
            val localCommandReply = tryHandleDirectCommand(prompt, currentSettings)
            if (localCommandReply != null) {
                localRepo.saveMessage(
                    role = "assistant",
                    content = localCommandReply,
                    modelUsed = "JARVIS Command Router",
                    isVoice = fromVoice,
                    toolSummary = "Direct Action"
                )
                if (currentSettings.voiceOutputEnabled && (fromVoice || currentSettings.wakeWordEnabled)) {
                    voiceEngine.speak(localCommandReply, followUpListen = fromVoice)
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isAiProcessing = true,
                    orbState = OrbState.PROCESSING,
                    streamingReply = ""
                )
            }

            val history = localRepo.getRecentMessages(14)
            val memories = if (currentSettings.memoryEnabled) localRepo.getRecentMemories() else emptyList()
            val snapshot = NvidiaNimClient.LiveContextSnapshot(
                quotes = _uiState.value.quotes,
                opportunities = _uiState.value.opportunities,
                accounts = _uiState.value.accounts,
                positions = _uiState.value.openPositions,
                recentNews = _uiState.value.newsItems,
                operatingMode = currentSettings.operatingMode,
                emergencyStopActive = currentSettings.riskLimits.emergencyStopActive
            )

            val result = nimClient.chatWithStreaming(
                userPrompt = prompt,
                modelId = currentSettings.selectedModelId,
                history = history,
                memories = memories,
                snapshot = snapshot,
                onPartialToken = { partial ->
                    _uiState.update { it.copy(streamingReply = partial) }
                }
            )

            val replyText: String
            val toolSummary: String?
            when (result) {
                is JarvisAiResult.Success -> {
                    replyText = result.replyText
                    toolSummary = result.toolCallsExecuted.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    if (currentSettings.memoryEnabled && result.suggestedMemoryFact != null) {
                        localRepo.saveMemory("preference", result.suggestedMemoryFact)
                        if (currentSettings.supabaseAutoSync) {
                            supabaseClient.syncMemoryFact("preference", result.suggestedMemoryFact)
                        }
                    }
                    localRepo.logAiActivity("AI_RESPONSE", "NVIDIA NIM (${result.modelUsed}) answered query")
                }
                is JarvisAiResult.ConfigurationRequired -> {
                    replyText = buildLocalFallbackBrief(prompt, result.message)
                    toolSummary = "Local Telemetry Fallback"
                }
                is JarvisAiResult.Error -> {
                    replyText = "${result.message}\n\n${buildLocalFallbackBrief(prompt, "")}"
                    toolSummary = "NIM Error"
                }
            }

            localRepo.saveMessage(
                role = "assistant",
                content = replyText,
                modelUsed = currentSettings.selectedModelId,
                isVoice = fromVoice,
                toolSummary = toolSummary
            )

            _uiState.update {
                it.copy(
                    isAiProcessing = false,
                    streamingReply = ""
                )
            }

            if (currentSettings.voiceOutputEnabled && (fromVoice || currentSettings.wakeWordEnabled)) {
                voiceEngine.speak(replyText.take(450), followUpListen = fromVoice)
            }
        }
    }

    private suspend fun tryHandleDirectCommand(prompt: String, currentSettings: JarvisUserSettings): String? {
        val lower = prompt.lowercase(Locale.US)
        return when {
            lower.contains("stop all automated trading") || lower.contains("emergency stop") -> {
                prefsRepo.setEmergencyStop(true)
                localRepo.logAlert("TRADE", "Emergency Stop Activated", "Automated trading halted by user command.", "CRITICAL", null)
                localRepo.logAiActivity("EMERGENCY_STOP", "All automated trading halted")
                "Emergency Stop engaged, Sir. Automated trading is now suspended and operating mode has reverted to Observation Mode."
            }

            lower.contains("monitor ") && (lower.contains("move") || lower.contains("notify") || lower.contains("%")) -> {
                val symbol = marketRepo.supportedInstruments.find {
                    lower.contains(it.displayName.lowercase()) || lower.contains(it.symbol.lowercase())
                }?.symbol ?: "BTC/USDT"
                val pctMatch = Regex("(\\d+(\\.\\d+)?)\\s*%").find(lower)?.groupValues?.get(1)?.toDoubleOrNull() ?: 3.0
                localRepo.addMonitoringTask(
                    MonitoringTaskEntity(
                        title = "Alert when $symbol moves $pctMatch%",
                        symbol = symbol,
                        ruleType = "PCT_MOVE",
                        thresholdValue = pctMatch,
                        isActive = true
                    )
                )
                localRepo.logAiActivity("MONITOR_CREATED", "Created $pctMatch% move watch on $symbol")
                "Monitoring task activated, Sir: I will alert you whenever $symbol moves more than $pctMatch%."
            }

            else -> null
        }
    }

    private fun buildLocalFallbackBrief(prompt: String, configNote: String): String {
        val lower = prompt.lowercase(Locale.US)
        val sb = StringBuilder()
        if (configNote.isNotBlank()) {
            sb.appendLine(configNote)
            sb.appendLine()
        }
        when {
            lower.contains("balance") || lower.contains("deriv") || lower.contains("account") || lower.contains("position") -> {
                val accs = _uiState.value.accounts
                if (accs.isEmpty()) {
                    sb.appendLine("No broker telemetry is loaded yet, Sir.")
                } else {
                    sb.appendLine("Here is your connected broker telemetry, Sir:")
                    accs.forEach { a ->
                        val bal = a.balance?.let { "${a.currency} ${"%.2f".format(it)}" } ?: a.statusMessage
                        sb.appendLine("• ${a.brokerType.displayName} (${a.environment.label}): $bal")
                    }
                }
                sb.appendLine("Open positions: ${_uiState.value.openPositions.size}")
            }
            lower.contains("opportunit") || lower.contains("analy") -> {
                val opps = _uiState.value.opportunities
                if (opps.isEmpty()) {
                    sb.appendLine("No live technical setups are computed yet, Sir. Please refresh Markets first.")
                } else {
                    sb.appendLine("Here are the top active setups, Sir:")
                    opps.take(3).forEach { o ->
                        sb.appendLine("• ${o.symbol} (${o.timeframe}): ${o.suggestedAction} @ ${TechnicalAnalysisEngine.formatPrice(o.suggestedEntry)} | SL ${TechnicalAnalysisEngine.formatPrice(o.stopLoss)} | TP ${TechnicalAnalysisEngine.formatPrice(o.takeProfit)} (${o.confidencePercent}% ${o.confidenceLabel})")
                    }
                }
            }
            else -> {
                val live = _uiState.value.quotes.filter { it.price > 0.0 }.take(4)
                if (live.isNotEmpty()) {
                    sb.appendLine("Here is the live market snapshot, Sir:")
                    live.forEach { q ->
                        sb.appendLine("• ${q.symbol}: ${TechnicalAnalysisEngine.formatPrice(q.price)} (${"%+.2f".format(q.changePercent)}%) [${q.freshness.label}]")
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    fun setOperatingMode(mode: TradingOperatingMode) {
        viewModelScope.launch {
            if (mode == TradingOperatingMode.AUTOMATED && !settings.value.riskLimits.automatedTradingAuthorized) {
                _uiState.update {
                    it.copy(snackbarMessage = "Enable 'Authorize Automated Trading' in Risk Controls first.")
                }
                return@launch
            }
            if (settings.value.riskLimits.emergencyStopActive && mode != TradingOperatingMode.OBSERVATION) {
                _uiState.update {
                    it.copy(snackbarMessage = "Clear Emergency Stop before enabling Assistant or Automated mode.")
                }
                return@launch
            }
            prefsRepo.setOperatingMode(mode)
            localRepo.logAiActivity("MODE_CHANGE", "Switched to ${mode.title}")
        }
    }

    fun setAutomatedAuthorized(authorized: Boolean) {
        viewModelScope.launch {
            prefsRepo.setAutomatedAuthorized(authorized)
            localRepo.logAiActivity("RISK_AUTH", "Automated trading authorization set to $authorized")
        }
    }

    fun toggleEmergencyStop() {
        viewModelScope.launch {
            val next = !settings.value.riskLimits.emergencyStopActive
            prefsRepo.setEmergencyStop(next)
            val title = if (next) "Emergency Stop Activated" else "Emergency Stop Cleared"
            localRepo.logAlert("TRADE", title, if (next) "All trading halted." else "Observation mode restored.", if (next) "CRITICAL" else "INFO", null)
            localRepo.logAiActivity("EMERGENCY_STOP", title)
        }
    }

    fun updateRiskLimits(limits: RiskLimits) {
        viewModelScope.launch {
            prefsRepo.updateRiskLimits(limits)
            _uiState.update { it.copy(snackbarMessage = "Risk guardrails updated.") }
        }
    }

    fun proposeTrade(
        brokerType: BrokerType,
        symbol: String,
        side: String,
        amountUsd: Double,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double,
        reasoning: String
    ) {
        val currentSettings = settings.value
        val mode = currentSettings.operatingMode
        if (mode == TradingOperatingMode.OBSERVATION) {
            _uiState.update {
                it.copy(snackbarMessage = "Observation Mode is active. Switch to Assistant Mode to propose or approve trades.")
            }
            return
        }

        if (mode == TradingOperatingMode.ASSISTANT) {
            val proposal = TradeProposal(
                id = UUID.randomUUID().toString(),
                brokerType = brokerType,
                symbol = symbol,
                side = side,
                amountUsd = amountUsd,
                entryPrice = entryPrice,
                stopLoss = stopLoss,
                takeProfit = takeProfit,
                reasoning = reasoning,
                createdAtMs = System.currentTimeMillis()
            )
            _uiState.update {
                it.copy(
                    pendingProposals = listOf(proposal) + it.pendingProposals,
                    snackbarMessage = "Trade proposal queued for explicit confirmation."
                )
            }
            return
        }

        // Automated mode executes immediately if risk guardrails pass
        executeTradeInternal(brokerType, symbol, side, amountUsd, entryPrice, stopLoss, takeProfit, explicitApproval = false)
    }

    fun approveProposal(proposal: TradeProposal) {
        _uiState.update { it.copy(pendingProposals = it.pendingProposals.filterNot { p -> p.id == proposal.id }) }
        executeTradeInternal(
            brokerType = proposal.brokerType,
            symbol = proposal.symbol,
            side = proposal.side,
            amountUsd = proposal.amountUsd,
            entryPrice = proposal.entryPrice,
            stopLoss = proposal.stopLoss,
            takeProfit = proposal.takeProfit,
            explicitApproval = true
        )
    }

    fun rejectProposal(proposalId: String) {
        _uiState.update { it.copy(pendingProposals = it.pendingProposals.filterNot { p -> p.id == proposalId }) }
        viewModelScope.launch {
            localRepo.logAiActivity("TRADE_REJECTED", "User declined trade proposal $proposalId")
        }
    }

    private fun executeTradeInternal(
        brokerType: BrokerType,
        symbol: String,
        side: String,
        amountUsd: Double,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double,
        explicitApproval: Boolean
    ) {
        val currentSettings = settings.value
        val validation = TradingRiskEngine.validateOrder(
            mode = currentSettings.operatingMode,
            riskLimits = currentSettings.riskLimits,
            symbol = symbol,
            amountUsd = amountUsd,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            openPositionsCount = _uiState.value.openPositions.size,
            explicitUserApproval = explicitApproval
        )

        viewModelScope.launch {
            when (validation) {
                is RiskValidationResult.Blocked -> {
                    if (validation.triggerAutoSuspend) {
                        prefsRepo.setAutomatedAuthorized(false)
                    }
                    localRepo.logOrderAudit(
                        OrderAuditEntity(
                            brokerName = brokerType.displayName,
                            environment = "GUARDRAIL",
                            symbol = symbol,
                            side = side,
                            amountUsd = amountUsd,
                            entryPrice = entryPrice,
                            stopLoss = stopLoss,
                            takeProfit = takeProfit,
                            operatingMode = currentSettings.operatingMode.title,
                            status = "BLOCKED",
                            resultMessage = validation.reason
                        )
                    )
                    _uiState.update { it.copy(snackbarMessage = validation.reason) }
                }

                RiskValidationResult.Approved -> {
                    val result = brokerRepo.executeOrder(brokerType, symbol, side, amountUsd)
                    val matchedAcc = _uiState.value.accounts.firstOrNull { it.brokerType == brokerType }
                    val envLabel = matchedAcc?.environment?.label ?: "UNCONFIGURED"
                    when (result) {
                        is OrderExecutionResult.Executed -> {
                            localRepo.logOrderAudit(
                                OrderAuditEntity(
                                    brokerName = brokerType.displayName,
                                    environment = envLabel,
                                    symbol = symbol,
                                    side = side,
                                    amountUsd = amountUsd,
                                    entryPrice = entryPrice,
                                    stopLoss = stopLoss,
                                    takeProfit = takeProfit,
                                    operatingMode = currentSettings.operatingMode.title,
                                    status = "EXECUTED",
                                    brokerOrderId = result.orderId,
                                    resultMessage = result.message
                                )
                            )
                            if (currentSettings.supabaseAutoSync) {
                                supabaseClient.syncOrderAudit(
                                    brokerType.displayName, envLabel, symbol, side, amountUsd, "EXECUTED", result.message
                                )
                            }
                            localRepo.logAlert("TRADE", "Order Executed: $symbol", result.message, "INFO", symbol)
                            _uiState.update { it.copy(snackbarMessage = result.message) }
                            syncBrokers()
                        }
                        is OrderExecutionResult.Failed -> {
                            localRepo.logOrderAudit(
                                OrderAuditEntity(
                                    brokerName = brokerType.displayName,
                                    environment = envLabel,
                                    symbol = symbol,
                                    side = side,
                                    amountUsd = amountUsd,
                                    entryPrice = entryPrice,
                                    stopLoss = stopLoss,
                                    takeProfit = takeProfit,
                                    operatingMode = currentSettings.operatingMode.title,
                                    status = "FAILED",
                                    resultMessage = result.errorMessage
                                )
                            )
                            if (currentSettings.supabaseAutoSync) {
                                supabaseClient.syncOrderAudit(
                                    brokerType.displayName, envLabel, symbol, side, amountUsd, "FAILED", result.errorMessage
                                )
                            }
                            localRepo.logAlert("BROKER", "Order Error: $symbol", result.errorMessage, "WARNING", symbol)
                            _uiState.update { it.copy(snackbarMessage = result.errorMessage) }
                        }
                    }
                }
            }
        }
    }

    // Memory & Monitoring Management
    fun addManualMemory(summary: String) {
        if (summary.isBlank()) return
        viewModelScope.launch {
            localRepo.saveMemory("preference", summary.trim())
        }
    }

    fun deleteMemory(id: Long) = viewModelScope.launch { localRepo.deleteMemory(id) }
    fun clearAllMemories() = viewModelScope.launch { localRepo.clearAllMemories() }
    fun clearChatHistory() = viewModelScope.launch { localRepo.clearConversationHistory() }

    fun createMonitoringTask(title: String, symbol: String, ruleType: String, threshold: Double) {
        viewModelScope.launch {
            localRepo.addMonitoringTask(
                MonitoringTaskEntity(
                    title = title,
                    symbol = symbol,
                    ruleType = ruleType,
                    thresholdValue = threshold,
                    isActive = true
                )
            )
            _uiState.update { it.copy(snackbarMessage = "Monitoring task scheduled.") }
        }
    }

    fun toggleMonitoringTask(id: Long, active: Boolean) =
        viewModelScope.launch { localRepo.toggleTaskActive(id, active) }

    fun deleteMonitoringTask(id: Long) =
        viewModelScope.launch { localRepo.deleteMonitoringTask(id) }

    fun markAlertsRead() = viewModelScope.launch { localRepo.markAllAlertsRead() }
    fun clearAlerts() = viewModelScope.launch { localRepo.clearAlerts() }

    // Settings updates
    fun updateModelId(id: String) = viewModelScope.launch { prefsRepo.updateModelId(id) }
    fun updateVoicePersonality(p: VoicePersonality) = viewModelScope.launch { prefsRepo.updateVoicePersonality(p) }
    fun updateVoiceSpeed(speed: Float) = viewModelScope.launch { prefsRepo.updateVoiceSpeed(speed) }
    fun setVoiceOutputEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setVoiceOutputEnabled(enabled) }
    fun setUseNvidiaMagpieVoice(enabled: Boolean) = viewModelScope.launch { prefsRepo.setUseNvidiaMagpieVoice(enabled) }
    fun previewJarvisVoice() {
        voiceEngine.speak(
            "Good day, Sir. NVIDIA Magpie neural speech and live financial telemetry are standing by at your command.",
            followUpListen = false
        )
    }
    fun setMemoryEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setMemoryEnabled(enabled) }
    fun toggleWatchlist(symbol: String) = viewModelScope.launch {
        prefsRepo.toggleWatchlistSymbol(symbol)
        val current = settings.value.watchlistSymbols
        val updated = if (current.contains(symbol)) current - symbol else current + symbol
        marketCacheRepo.syncConfiguredSymbols(updated, marketRepo.supportedInstruments)
    }
    fun setHighContrastHud(enabled: Boolean) = viewModelScope.launch { prefsRepo.setHighContrastHud(enabled) }
    fun setSupabaseAutoSync(enabled: Boolean) = viewModelScope.launch { prefsRepo.setSupabaseAutoSync(enabled) }
    fun updateNotificationPrefs(moves: Boolean, opp: Boolean, news: Boolean, trades: Boolean) =
        viewModelScope.launch { prefsRepo.updateNotificationPrefs(moves, opp, news, trades) }

    fun signInSupabase(email: String, password: String) {
        viewModelScope.launch {
            val (ok, msg) = supabaseClient.signInWithEmailPassword(email, password)
            _uiState.update { it.copy(snackbarMessage = msg) }
            if (ok) refreshApiStatuses()
        }
    }

    fun signUpSupabase(email: String, password: String) {
        viewModelScope.launch {
            val (ok, msg) = supabaseClient.signUpWithEmailPassword(email, password)
            _uiState.update { it.copy(snackbarMessage = msg) }
            if (ok) refreshApiStatuses()
        }
    }

    fun signOutSupabase() {
        supabaseClient.signOut()
        refreshApiStatuses()
        _uiState.update { it.copy(snackbarMessage = "Signed out of Supabase.") }
    }

    fun testNvidiaNimConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(diagnosticResult = "Testing NVIDIA NIM LLM, Magpie TTS & Supabase endpoints...") }
            val (nimOk, nimMsg) = nimClient.testConnection(settings.value.selectedModelId)
            val (ttsOk, ttsMsg) = magpieTtsClient.testTtsConnection(settings.value.voicePersonality)
            val (supOk, supMsg) = supabaseClient.testHealth()
            _uiState.update {
                it.copy(
                    diagnosticResult = "NVIDIA NIM: ${if (nimOk) "OK" else "FAIL"} ($nimMsg)\nMagpie TTS: ${if (ttsOk) "OK" else "FAIL"} ($ttsMsg)\nSupabase: ${if (supOk) "OK" else "FAIL"} ($supMsg)"
                )
            }
            refreshApiStatuses()
        }
    }

    fun refreshApiStatuses() {
        val statuses = listOf(
            ApiIntegrationStatus(
                id = "nvidia_nim",
                name = "NVIDIA NIM Intelligence",
                configured = SecretsConfig.hasNvidiaNim || SecretsConfig.hasSupabase,
                reachable = null,
                statusText = if (SecretsConfig.hasNvidiaNim) "API Key Loaded (BuildConfig)" else if (SecretsConfig.hasSupabase) "Proxied via Supabase Edge Function" else "Key Not Configured",
                obtainGuide = "Generate a key at build.nvidia.com and configure NVIDIA_NIM_API_KEY in AI Studio Secrets.",
                secretKeys = listOf("NVIDIA_NIM_API_KEY")
            ),
            ApiIntegrationStatus(
                id = "supabase",
                name = "Supabase Backend & Auth",
                configured = SecretsConfig.hasSupabase,
                reachable = supabaseClient.currentSignedInEmail() != null,
                statusText = supabaseClient.currentSignedInEmail()?.let { "Signed in: $it" }
                    ?: SecretsConfig.supabaseHostLabel?.let { "Configured ($it — Sign in on Accounts tab)" }
                    ?: "Not Configured",
                obtainGuide = "Create a Supabase project at supabase.com and add SUPABASE_URL + SUPABASE_ANON_KEY in AI Studio Secrets.",
                secretKeys = listOf("SUPABASE_URL", "SUPABASE_ANON_KEY")
            ),
            ApiIntegrationStatus(
                id = "deriv",
                name = "Deriv Broker (OAuth / Token)",
                configured = SecretsConfig.hasDerivAuth || SecretsConfig.derivAppId != null,
                reachable = _uiState.value.accounts.any { it.brokerType == BrokerType.DERIV && it.isConnected },
                statusText = if (_uiState.value.accounts.any { it.brokerType == BrokerType.DERIV && it.isConnected }) "Authorized & Synced"
                else if (SecretsConfig.hasDerivAuth) "Token Loaded (Syncing)"
                else if (SecretsConfig.derivAppId != null) "App ID Configured (Tap Deriv OAuth)"
                else "Public Market Feed Only",
                obtainGuide = "Register an app at developers.deriv.com (redirect URI: jarvis://deriv-oauth) and add DERIV_APP_ID / DERIV_API_TOKEN in Secrets.",
                secretKeys = listOf("DERIV_APP_ID", "DERIV_API_TOKEN")
            ),
            ApiIntegrationStatus(
                id = "binance",
                name = "Binance Spot API",
                configured = SecretsConfig.hasBinanceAuth,
                reachable = _uiState.value.accounts.any { it.brokerType == BrokerType.BINANCE && it.isConnected },
                statusText = if (_uiState.value.accounts.any { it.brokerType == BrokerType.BINANCE && it.isConnected }) "Authorized (Spot/Read)"
                else if (SecretsConfig.hasBinanceAuth) "Credentials Loaded"
                else "Public Market Feed Only",
                obtainGuide = "Create a Read + Spot Trade key (disable Withdrawals) in Binance API Management and add BINANCE_API_KEY + BINANCE_API_SECRET in Secrets.",
                secretKeys = listOf("BINANCE_API_KEY", "BINANCE_API_SECRET")
            ),
            ApiIntegrationStatus(
                id = "finnhub",
                name = "Finnhub News & Economic Calendar",
                configured = SecretsConfig.hasFinnhub,
                reachable = null,
                statusText = if (SecretsConfig.hasFinnhub) "Key Loaded (Live News & Calendar Active)" else "Optional (RSS News Active)",
                obtainGuide = "Obtain a free API key at finnhub.io and add FINNHUB_API_KEY in AI Studio Secrets.",
                secretKeys = listOf("FINNHUB_API_KEY")
            ),
            ApiIntegrationStatus(
                id = "mt_bridge",
                name = "MetaTrader 4 / 5 HTTPS Bridge (Optional)",
                configured = SecretsConfig.hasMtBridge,
                reachable = _uiState.value.accounts.any { it.brokerType == BrokerType.MT_BRIDGE && it.isConnected },
                statusText = if (SecretsConfig.hasMtBridge) "Bridge URL Configured" else "Optional — Deferred (Add later)",
                obtainGuide = "Optional: Deploy an authorized MetaApi or self-hosted HTTPS MT4/MT5 REST bridge and add MT_BRIDGE_URL + MT_BRIDGE_TOKEN in Secrets when ready.",
                secretKeys = listOf("MT_BRIDGE_URL", "MT_BRIDGE_TOKEN")
            )
        )
        _uiState.update { it.copy(apiStatuses = statuses) }
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
