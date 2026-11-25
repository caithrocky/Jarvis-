package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.JarvisApplication
import com.example.ui.screens.AccountsScreen
import com.example.ui.screens.AutomationScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.JarvisChatScreen
import com.example.ui.screens.MarketsScreen
import com.example.ui.screens.NewsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TradingScreen
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.JarvisTheme
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.ObsidianBlack

private data class PrimaryNavTab(
    val section: JarvisSection,
    val label: String,
    val icon: ImageVector
)

@Composable
fun JarvisApp(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as JarvisApplication

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cachedTickerQuotes by viewModel.cachedTickerQuotes.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val tasks by viewModel.monitoringTasks.collectAsStateWithLifecycle()
    val audits by viewModel.orderAudits.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val aiLogs by viewModel.aiLogs.collectAsStateWithLifecycle()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasMicPermission = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMicPermission && settings.wakeWordEnabled) {
            viewModel.toggleAlwaysOnVoice(true)
        }
    }

    val requestVoicePermissions = {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    BackHandler(enabled = uiState.selectedSection != JarvisSection.HOME) {
        viewModel.navigateTo(JarvisSection.HOME)
    }

    val primaryTabs = listOf(
        PrimaryNavTab(JarvisSection.HOME, "Home", Icons.Filled.Home),
        PrimaryNavTab(JarvisSection.JARVIS, "JARVIS", Icons.Filled.AutoAwesome),
        PrimaryNavTab(JarvisSection.MARKETS, "Markets", Icons.AutoMirrored.Filled.ShowChart),
        PrimaryNavTab(JarvisSection.TRADING, "Trading", Icons.Filled.Bolt),
        PrimaryNavTab(JarvisSection.ACCOUNTS, "Accounts", Icons.Filled.AccountBalanceWallet)
    )

    JarvisTheme(highContrastHud = settings.highContrastHud) {
        val jc = LocalJarvisColors.current

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(ObsidianBlack, MidnightNavy, ObsidianBlack))
                )
        ) {
            val useRail = maxWidth >= 680.dp

            Scaffold(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            containerColor = MidnightNavy.copy(alpha = 0.96f),
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            primaryTabs.forEach { tab ->
                                val selected = uiState.selectedSection == tab.section
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.navigateTo(tab.section) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = ElectricBlue,
                                        selectedTextColor = ElectricBlue,
                                        indicatorColor = ElectricBlue.copy(alpha = 0.16f)
                                    ),
                                    modifier = Modifier.testTag("nav_${tab.section.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(
                            containerColor = MidnightNavy.copy(alpha = 0.92f),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            primaryTabs.forEach { tab ->
                                NavigationRailItem(
                                    selected = uiState.selectedSection == tab.section,
                                    onClick = { viewModel.navigateTo(tab.section) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) }
                                )
                            }
                            NavigationRailItem(
                                selected = uiState.selectedSection == JarvisSection.SETTINGS,
                                onClick = { viewModel.navigateTo(JarvisSection.SETTINGS) },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                                label = { Text("Settings") }
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top 9-Section HUD Command Strip
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets(0, 0, 0, 0)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(JarvisSection.entries) { section ->
                                val selected = uiState.selectedSection == section
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.navigateTo(section) },
                                    label = {
                                        Text(
                                            text = section.label,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ElectricBlue.copy(alpha = 0.22f),
                                        selectedLabelColor = ElectricBlue
                                    ),
                                    modifier = Modifier.testTag("hud_tab_${section.route}")
                                )
                            }
                        }

                        // Responsive centered container for large screens
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(max = 880.dp)
                                .fillMaxWidth()
                        ) {
                            when (uiState.selectedSection) {
                                JarvisSection.HOME -> HomeDashboardScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    cachedTickerQuotes = cachedTickerQuotes,
                                    alerts = alerts,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = requestVoicePermissions,
                                    onOrbTap = { viewModel.onOrbTapped(hasMicPermission) },
                                    onToggleAlwaysOn = viewModel::toggleAlwaysOnVoice,
                                    onQuickVoicePrompt = { prompt ->
                                        viewModel.navigateTo(JarvisSection.JARVIS)
                                        viewModel.sendUserQuery(prompt, fromVoice = false)
                                    },
                                    onNavigate = viewModel::navigateTo,
                                    onRefreshMarkets = { viewModel.refreshMarkets(false) },
                                    onToggleEmergencyStop = viewModel::toggleEmergencyStop,
                                    onSelectMarket = { sym -> viewModel.selectSymbol(sym) }
                                )

                                JarvisSection.JARVIS -> JarvisChatScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    messages = messages,
                                    memories = memories,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = requestVoicePermissions,
                                    onOrbTap = { viewModel.onOrbTapped(hasMicPermission) },
                                    onInterruptSpeech = { app.voiceEngine.interruptSpeech() },
                                    onSendQuery = { q -> viewModel.sendUserQuery(q, fromVoice = false) },
                                    onAddMemory = viewModel::addManualMemory,
                                    onDeleteMemory = viewModel::deleteMemory,
                                    onClearMemories = viewModel::clearAllMemories,
                                    onClearChat = viewModel::clearChatHistory
                                )

                                JarvisSection.MARKETS -> MarketsScreen(
                                    uiState = uiState,
                                    cachedTickerQuotes = cachedTickerQuotes,
                                    onSelectAssetClass = viewModel::selectAssetClass,
                                    onSelectSymbol = viewModel::selectSymbol,
                                    onRefreshMarkets = { viewModel.refreshMarkets(false) },
                                    onAskJarvisExplain = viewModel::askJarvisAboutOpportunity,
                                    onProposeTrade = { opp, broker ->
                                        viewModel.proposeTrade(
                                            brokerType = broker,
                                            symbol = opp.symbol,
                                            side = opp.suggestedAction,
                                            amountUsd = settings.riskLimits.maxTradeAmountUsd.coerceAtMost(50.0),
                                            entryPrice = opp.suggestedEntry,
                                            stopLoss = opp.stopLoss,
                                            takeProfit = opp.takeProfit,
                                            reasoning = opp.technicalReasoning
                                        )
                                        viewModel.navigateTo(JarvisSection.TRADING)
                                    }
                                )

                                JarvisSection.TRADING -> TradingScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    onSelectMode = viewModel::setOperatingMode,
                                    onAuthorizeAutomated = viewModel::setAutomatedAuthorized,
                                    onToggleEmergencyStop = viewModel::toggleEmergencyStop,
                                    onUpdateRiskLimits = viewModel::updateRiskLimits,
                                    onApproveProposal = viewModel::approveProposal,
                                    onRejectProposal = viewModel::rejectProposal,
                                    onSubmitManualProposal = { broker, sym, side, amt, entry, sl, tp ->
                                        viewModel.proposeTrade(
                                            brokerType = broker,
                                            symbol = sym,
                                            side = side,
                                            amountUsd = amt,
                                            entryPrice = entry,
                                            stopLoss = sl,
                                            takeProfit = tp,
                                            reasoning = "Manual guided order ticket"
                                        )
                                    }
                                )

                                JarvisSection.ACCOUNTS -> AccountsScreen(
                                    uiState = uiState,
                                    derivOAuthUrl = viewModel.buildDerivOAuthUrl(),
                                    supabaseSignedInEmail = app.supabaseClient.currentSignedInEmail(),
                                    onSyncBrokers = viewModel::syncBrokers,
                                    onDisconnectDeriv = viewModel::disconnectDeriv,
                                    onSignInSupabase = viewModel::signInSupabase,
                                    onSignUpSupabase = viewModel::signUpSupabase,
                                    onSignOutSupabase = viewModel::signOutSupabase
                                )

                                JarvisSection.NEWS -> NewsScreen(
                                    uiState = uiState,
                                    onRefreshNews = viewModel::refreshNewsAndCalendar,
                                    onSummarizeWithJarvis = viewModel::summarizeNewsWithJarvis
                                )

                                JarvisSection.HISTORY -> HistoryScreen(
                                    orderAudits = audits,
                                    aiLogs = aiLogs
                                )

                                JarvisSection.AUTOMATION -> AutomationScreen(
                                    tasks = tasks,
                                    alerts = alerts,
                                    onCreateTask = viewModel::createMonitoringTask,
                                    onToggleTask = viewModel::toggleMonitoringTask,
                                    onDeleteTask = viewModel::deleteMonitoringTask,
                                    onMarkAlertsRead = viewModel::markAlertsRead,
                                    onClearAlerts = viewModel::clearAlerts
                                )

                                JarvisSection.SETTINGS -> SettingsScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = requestVoicePermissions,
                                    onSelectModel = viewModel::updateModelId,
                                    onSelectVoicePersonality = viewModel::updateVoicePersonality,
                                    onUpdateVoiceSpeed = viewModel::updateVoiceSpeed,
                                    onToggleAlwaysOn = viewModel::toggleAlwaysOnVoice,
                                    onToggleVoiceOutput = viewModel::setVoiceOutputEnabled,
                                    onToggleNvidiaMagpieVoice = viewModel::setUseNvidiaMagpieVoice,
                                    onPreviewVoice = viewModel::previewJarvisVoice,
                                    onToggleMemory = viewModel::setMemoryEnabled,
                                    onClearMemories = viewModel::clearAllMemories,
                                    onClearChat = viewModel::clearChatHistory,
                                    onToggleWatchlist = viewModel::toggleWatchlist,
                                    onUpdateNotificationPrefs = viewModel::updateNotificationPrefs,
                                    onToggleHighContrastHud = viewModel::setHighContrastHud,
                                    onToggleSupabaseSync = viewModel::setSupabaseAutoSync,
                                    onTestNvidiaConnection = viewModel::testNvidiaNimConnection,
                                    onCheckForUpdates = viewModel::checkForUpdates
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
