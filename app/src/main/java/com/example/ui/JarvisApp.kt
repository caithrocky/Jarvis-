package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.JarvisApplication
import com.example.assistant.AccessibilityHealthState
import com.example.data.local.JarvisUserSettings
import com.example.domain.model.AssistantUiMode
import com.example.ui.components.HudGlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.screens.CredentialsVaultScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.JarvisChatScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.JarvisTheme
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.SoftTurquoise
import com.example.ui.theme.SoftViolet
import com.example.ui.theme.WarningAmber
import com.example.updates.JarvisUpdatePrompt
import kotlinx.coroutines.launch

private data class DrawerNavItem(
    val section: JarvisSection,
    val label: String,
    val subtitle: String,
    val icon: ImageVector
)

@Composable
fun JarvisApp(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as JarvisApplication
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val aiLogs by viewModel.aiLogs.collectAsStateWithLifecycle()
    val scheduledAutomations by viewModel.scheduledAutomations.collectAsStateWithLifecycle()
    val learnedApps by viewModel.learnedApps.collectAsStateWithLifecycle()

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

    // Request mic permission once on launch if not granted, and automatically run always-on listening
    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            requestVoicePermissions()
        }
    }
    LaunchedEffect(hasMicPermission, settings.wakeWordEnabled) {
        if (hasMicPermission && settings.wakeWordEnabled) {
            viewModel.ensureAlwaysListeningRunning(hasMicPermission)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hasMicPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                viewModel.refreshSystemCapabilitiesState()
                viewModel.ensureAlwaysListeningRunning(hasMicPermission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openOverlayPermissionSettings = {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
        Unit
    }

    val openAccessibilitySettings = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Unit
    }

    val openNotificationAccessSettings = {
        runCatching {
            context.startActivity(com.example.assistant.NotificationVaultBridge.openAccessSettingsIntent())
        }
        Unit
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showScreenHelperDisclosure by remember { mutableStateOf(false) }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && uiState.selectedSection != JarvisSection.HOME) {
        viewModel.navigateTo(JarvisSection.HOME)
    }

    val drawerItems = remember(uiState.savedCredentials.size, uiState.unreadNotificationCount) {
        listOf(
            DrawerNavItem(JarvisSection.HOME, "Home", "Voice-first orb screen", Icons.Filled.Home),
            DrawerNavItem(JarvisSection.JARVIS, "Voice Transcript", "Conversation history & memory", Icons.Filled.AutoAwesome),
            DrawerNavItem(
                JarvisSection.VAULT,
                "Credentials Vault",
                "${uiState.savedCredentials.size} saved login(s) & personal profile",
                Icons.Filled.Lock
            ),
            DrawerNavItem(JarvisSection.HISTORY, "Activity History", "AI commands, actions & logs", Icons.Filled.History),
            DrawerNavItem(JarvisSection.SETTINGS, "Settings", "Voice, AI models, permissions & updates", Icons.Filled.Settings)
        )
    }

    JarvisTheme(highContrastHud = settings.highContrastHud) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                JarvisFeaturesDrawer(
                    uiState = uiState,
                    settings = settings,
                    hasMicPermission = hasMicPermission,
                    drawerItems = drawerItems,
                    onSelectSection = { section ->
                        scope.launch { drawerState.close() }
                        viewModel.navigateTo(section)
                    },
                    onRequestMicPermission = requestVoicePermissions,
                    onRequestOverlayPermission = openOverlayPermissionSettings,
                    onOpenScreenHelperDialog = { showScreenHelperDisclosure = true },
                    onOpenNotificationAccessSettings = openNotificationAccessSettings,
                    onToggleMicMute = viewModel::toggleMicMute,
                    onToggleAlwaysOn = viewModel::toggleAlwaysOnVoice,
                    onToggleFloatingOrb = viewModel::setFloatingOrbEnabled,
                    onLaunchFloatingOrbNow = {
                        scope.launch { drawerState.close() }
                        viewModel.launchFloatingOrbNow()
                    },
                    onSwitchToMiniOrb = {
                        scope.launch { drawerState.close() }
                        viewModel.switchToMiniOrb()
                    },
                    onQuickPrompt = { prompt ->
                        scope.launch { drawerState.close() }
                        viewModel.navigateTo(JarvisSection.JARVIS)
                        viewModel.sendUserQuery(prompt, fromVoice = true)
                    },
                    onOpenWebsite = { urlOrName ->
                        scope.launch { drawerState.close() }
                        viewModel.openWebsiteInChrome(urlOrName)
                    }
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(ObsidianBlack, MidnightNavy, ObsidianBlack))
                    )
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.navigationBarsPadding()
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        if (uiState.selectedSection == JarvisSection.HOME) {
                            HomeDashboardScreen(
                                uiState = uiState,
                                settings = settings,
                                memories = memories,
                                learnedApps = learnedApps,
                                scheduledAutomations = scheduledAutomations,
                                hasMicPermission = hasMicPermission,
                                onRequestMicPermission = requestVoicePermissions,
                                onOpenMenu = { scope.launch { drawerState.open() } },
                                onOrbTap = { viewModel.onOrbTapped(hasMicPermission) },
                                onNavigate = viewModel::navigateTo,
                                onAbortAutonomous = viewModel::abortAutonomousTasks,
                                onCycleHudHeadline = viewModel::cycleHudHeadline,
                                onTriggerSuggestion = viewModel::triggerOrScheduleSuggestion,
                                onRunAutomationNow = viewModel::runAutomationNow,
                                onDeleteAutomation = viewModel::deleteAutomation
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 880.dp)
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .statusBarsPadding()
                                    .navigationBarsPadding()
                            ) {
                                SecondaryScreenTopBar(
                                    title = when (uiState.selectedSection) {
                                        JarvisSection.JARVIS -> "Voice Transcript"
                                        JarvisSection.VAULT -> "Credentials Vault"
                                        JarvisSection.HISTORY -> "Activity History"
                                        JarvisSection.SETTINGS -> "Settings"
                                        JarvisSection.HOME -> "JARVIS"
                                    },
                                    showSettingsButton = uiState.selectedSection != JarvisSection.SETTINGS,
                                    onBack = { viewModel.navigateTo(JarvisSection.HOME) },
                                    onOpenMenu = { scope.launch { drawerState.open() } },
                                    onOpenSettings = { viewModel.navigateTo(JarvisSection.SETTINGS) }
                                )

                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    when (uiState.selectedSection) {
                                        JarvisSection.JARVIS -> JarvisChatScreen(
                                            uiState = uiState,
                                            settings = settings,
                                            messages = messages,
                                            memories = memories,
                                            hasMicPermission = hasMicPermission,
                                            onRequestMicPermission = requestVoicePermissions,
                                            onOrbTap = { viewModel.onOrbTapped(hasMicPermission) },
                                            onToggleMicMute = viewModel::toggleMicMute,
                                            onInterruptSpeech = { app.voiceEngine.interruptSpeech() },
                                            onDeleteMemory = viewModel::deleteMemory,
                                            onClearMemories = viewModel::clearAllMemories,
                                            onExportMemoriesText = viewModel::buildMemoriesExportText,
                                            onClearChat = viewModel::clearChatHistory
                                        )

                                        JarvisSection.VAULT -> CredentialsVaultScreen(
                                            uiState = uiState,
                                            onSavePersonalProfile = viewModel::saveSignupProfile,
                                            onSaveSiteCredential = viewModel::saveSiteCredential,
                                            onDeleteSiteCredential = viewModel::deleteSiteCredential,
                                            onOpenAndSignIn = viewModel::openAndSignInWithCredential,
                                            onRequestOverlayPermission = openOverlayPermissionSettings,
                                            onOpenAccessibilitySettings = openAccessibilitySettings,
                                            onLaunchFloatingOrbNow = viewModel::launchFloatingOrbNow
                                        )

                                        JarvisSection.HISTORY -> HistoryScreen(aiLogs = aiLogs)

                                        JarvisSection.SETTINGS -> SettingsScreen(
                                            uiState = uiState,
                                            settings = settings,
                                            hasMicPermission = hasMicPermission,
                                            onRequestMicPermission = requestVoicePermissions,
                                            onRequestOverlayPermission = openOverlayPermissionSettings,
                                            onOpenAccessibilitySettings = openAccessibilitySettings,
                                            onOpenNotificationAccessSettings = openNotificationAccessSettings,
                                            onToggleFloatingOrb = viewModel::setFloatingOrbEnabled,
                                            onLaunchFloatingOrbNow = viewModel::launchFloatingOrbNow,
                                            onUpdateOrbInactivityTimeout = viewModel::setOrbInactivityTimeoutSeconds,
                                            onSelectModel = viewModel::updateModelId,
                                            onSelectVoicePersonality = viewModel::updateVoicePersonality,
                                            onUpdateVoiceSpeed = viewModel::updateVoiceSpeed,
                                            onToggleAlwaysOn = viewModel::toggleAlwaysOnVoice,
                                            onToggleVoiceOutput = viewModel::setVoiceOutputEnabled,
                                            onPreviewVoice = viewModel::previewJarvisVoice,
                                            onToggleMemory = viewModel::setMemoryEnabled,
                                            onClearMemories = viewModel::clearAllMemories,
                                            onClearChat = viewModel::clearChatHistory,
                                            onToggleHighContrastHud = viewModel::setHighContrastHud,
                                            onTestNvidiaConnection = viewModel::testNvidiaNimConnection,
                                            onTriggerManualUpdateCheck = viewModel::triggerManualUpdateCheck,
                                            onToggleShowTapIndicator = viewModel::setShowTapIndicator,
                                            onToggleShowHighlightIndicator = viewModel::setShowHighlightIndicator,
                                            onToggleShowScrollIndicator = viewModel::setShowScrollIndicator,
                                            onToggleFuturisticHudMode = viewModel::setFuturisticHudMode,
                                            onToggleVoiceBiometricLock = viewModel::setVoiceBiometricLockEnabled,
                                            onToggleVoiceFluAdaptation = viewModel::setVoiceFluAdaptationEnabled,
                                            onEnrollOwnerVoice = viewModel::enrollOwnerVoice,
                                            onVerifyOwnerVoice = viewModel::verifyOwnerVoiceNow,
                                            onClearOwnerVoice = viewModel::clearOwnerVoice,
                                            onToggleAutoLearnDaily = viewModel::setAutoLearnDeviceDaily,
                                            onStudyDeviceNow = viewModel::studyDeviceNow,
                                            onToggleKeepScreenAwake = viewModel::setKeepScreenAwakeWhileActive,
                                            onToggleDeviceUnlockEnabled = viewModel::setDeviceUnlockEnabled,
                                            onSaveSafeWord = viewModel::saveDeviceUnlockSafeWord,
                                            onSaveUnlockPattern = viewModel::saveDeviceUnlockPattern,
                                            onClearUnlockCredentials = viewModel::clearDeviceUnlockCredentials,
                                            onSaveNvidiaNimKey = viewModel::saveNvidiaNimKey,
                                            onClearNvidiaNimKeyOverride = viewModel::clearNvidiaNimKeyOverride,
                                            onToggleAutoOfflineSwitch = viewModel::setAutoOfflineSwitchEnabled,
                                            onDownloadLfmModel = viewModel::downloadLfmOfflineModel,
                                            onCancelLfmDownload = viewModel::cancelLfmDownload,
                                            onDeleteLfmModel = viewModel::deleteLfmOfflineModel,
                                            onDownloadVoskModel = viewModel::downloadVoskOfflineModel,
                                            onCancelVoskDownload = viewModel::cancelVoskDownload,
                                            onDeleteVoskModel = viewModel::deleteVoskOfflineModel
                                        )

                                        JarvisSection.HOME -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showScreenHelperDisclosure) {
            AlertDialog(
                onDismissRequest = { showScreenHelperDisclosure = false },
                title = { Text("Enable JARVIS Screen Helper?") },
                text = {
                    Text(
                        "Turning on JARVIS in Android Accessibility Settings lets JARVIS read visible screen elements, analyze open websites or apps, fill your encrypted credentials on command, guide ID photo or OTP steps, and navigate Back/Home/Settings. Passwords remain on your device and are never logged."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showScreenHelperDisclosure = false
                        openAccessibilitySettings()
                    }) {
                        Text("Open Accessibility Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showScreenHelperDisclosure = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        JarvisUpdatePrompt(
            manualCheckTrigger = uiState.manualUpdateCheckTrigger,
            onCheckStatusMessage = viewModel::showStatusSnackbar
        )
    }
}

@Composable
private fun SecondaryScreenTopBar(
    title: String,
    showSettingsButton: Boolean,
    onBack: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("secondary_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Home",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.testTag("secondary_menu_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "Open JARVIS features drawer",
                    tint = ArcCyan
                )
            }
            if (showSettingsButton) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("secondary_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open Settings",
                        tint = ElectricBlue
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JarvisFeaturesDrawer(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    hasMicPermission: Boolean,
    drawerItems: List<DrawerNavItem>,
    onSelectSection: (JarvisSection) -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenScreenHelperDialog: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleAlwaysOn: (Boolean) -> Unit,
    onToggleFloatingOrb: (Boolean) -> Unit,
    onLaunchFloatingOrbNow: () -> Unit,
    onSwitchToMiniOrb: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    onOpenWebsite: (String) -> Unit
) {
    val jc = LocalJarvisColors.current

    ModalDrawerSheet(
        drawerContainerColor = Color(0xF5070E20),
        modifier = Modifier.widthIn(max = 350.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Drawer Brand & Real Telemetry Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "J.A.R.V.I.S",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = ArcCyan
                        )
                        StatusBadge(
                            text = if (uiState.activeUiMode == AssistantUiMode.MINI_ORB) "MINI ORB" else "FULL APP",
                            color = if (uiState.activeUiMode == AssistantUiMode.MINI_ORB) SoftViolet else SoftTurquoise
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${uiState.learnedAppsCount} apps • ${uiState.learnedWorkflowsCount} workflows • " +
                            "${if (uiState.voiceEnrolled) "Voice ID Locked" else "Voice ID Open"} • " +
                            "${uiState.unreadNotificationCount} alerts",
                        style = MaterialTheme.typography.bodySmall,
                        color = jc.mutedSlate
                    )
                }
                if (uiState.bluetoothAudioReady) {
                    Icon(
                        imageVector = Icons.Filled.BluetoothAudio,
                        contentDescription = "Bluetooth audio ready",
                        tint = ArcCyan
                    )
                }
            }

            HorizontalDivider(color = Color(0x2856A8FF))

            // All Navigation Destinations
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                drawerItems.forEach { item ->
                    val selected = uiState.selectedSection == item.section
                    NavigationDrawerItem(
                        selected = selected,
                        onClick = { onSelectSection(item.section) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = {
                            Column {
                                Text(item.label, fontWeight = FontWeight.Medium)
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) ArcCyan.copy(alpha = 0.85f) else jc.mutedSlate
                                )
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Color(0x2E1C64E8),
                            selectedIconColor = ArcCyan,
                            selectedTextColor = Color.White,
                            unselectedContainerColor = Color.Transparent,
                            unselectedIconColor = jc.mutedSlate,
                            unselectedTextColor = Color(0xFFD9E8FF)
                        ),
                        modifier = Modifier.testTag("nav_${item.section.route}")
                    )
                }
            }

            HorizontalDivider(color = Color(0x2856A8FF))

            // Mini Orb & Microphone Controls
            Text("Voice & Mini Orb", style = MaterialTheme.typography.labelLarge, color = ArcCyan)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!uiState.hasOverlayPermission) onRequestOverlayPermission() else onSwitchToMiniOrb()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_mini_orb_button")
                ) {
                    Icon(Icons.Filled.BubbleChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mini Orb")
                }

                OutlinedButton(
                    onClick = onToggleMicMute,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_mute_mic_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = null,
                        tint = if (uiState.isMicMuted) WarningAmber else SoftTurquoise,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (uiState.isMicMuted) "Unmute" else "Mute")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hands-Free \"Hey JARVIS\"", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text(
                        text = if (settings.wakeWordEnabled) "Listens continuously & summons orb when closed" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = jc.mutedSlate
                    )
                }
                Switch(
                    checked = settings.wakeWordEnabled,
                    onCheckedChange = { want ->
                        if (want && !hasMicPermission) onRequestMicPermission() else onToggleAlwaysOn(want)
                    },
                    modifier = Modifier.testTag("home_always_on_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating Mini Orb", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text(
                        text = if (!uiState.hasOverlayPermission) "Tap to grant overlay permission" else "Companion pod over apps",
                        style = MaterialTheme.typography.bodySmall,
                        color = jc.mutedSlate
                    )
                }
                Switch(
                    checked = settings.floatingOrbEnabled && uiState.hasOverlayPermission,
                    onCheckedChange = { want ->
                        if (want && !uiState.hasOverlayPermission) {
                            onRequestOverlayPermission()
                        } else {
                            onToggleFloatingOrb(want)
                        }
                    },
                    modifier = Modifier.testTag("home_floating_orb_switch")
                )
            }

            HorizontalDivider(color = Color(0x2856A8FF))

            // Smart Phone Plugin & System Permissions
            Text("Smart Phone Plugin", style = MaterialTheme.typography.labelLarge, color = ArcCyan)

            HudGlassCard(borderColor = ArcCyan.copy(alpha = 0.25f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenScreenHelperDialog() }
                        .testTag("home_screen_helper_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val accState = uiState.accessibilityHealthState
                    val accTint = when (accState) {
                        AccessibilityHealthState.OPERATIONAL, AccessibilityHealthState.CONNECTED -> ProfitEmerald
                        AccessibilityHealthState.CONNECTING -> ArcCyan
                        AccessibilityHealthState.MALFUNCTION_DETECTED -> MaterialTheme.colorScheme.error
                        else -> WarningAmber
                    }
                    val accBadge = when (accState) {
                        AccessibilityHealthState.OPERATIONAL -> "OPERATIONAL"
                        AccessibilityHealthState.CONNECTED -> "CONNECTED"
                        AccessibilityHealthState.CONNECTING -> "CONNECTING"
                        AccessibilityHealthState.MALFUNCTION_DETECTED -> "MALFUNCTION"
                        AccessibilityHealthState.PERMISSION_ENABLED_NOT_CONNECTED -> "REBIND"
                        AccessibilityHealthState.PERMISSION_DISABLED -> "SETUP"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Filled.AccessibilityNew,
                            contentDescription = null,
                            tint = accTint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Screen Control", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            Text(
                                text = uiState.accessibilityStatusDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = jc.mutedSlate
                            )
                        }
                    }
                    StatusBadge(
                        text = accBadge,
                        color = accTint
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNotificationAccessSettings() }
                        .testTag("home_notification_access_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = null,
                            tint = if (uiState.hasNotificationAccess) SoftTurquoise else WarningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Notification Access", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            Text(
                                text = if (uiState.hasNotificationAccess) "${uiState.unreadNotificationCount} active alerts" else "Tap to allow alerts & texts",
                                style = MaterialTheme.typography.bodySmall,
                                color = jc.mutedSlate
                            )
                        }
                    }
                    StatusBadge(
                        text = if (uiState.hasNotificationAccess) "ON" else "ENABLE",
                        color = if (uiState.hasNotificationAccess) ProfitEmerald else WarningAmber
                    )
                }
            }

            // Quick Chrome & Web Shortcuts (no typing required)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Language, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Chrome & Web Shortcuts", style = MaterialTheme.typography.labelLarge, color = ArcCyan)
            }
            val siteShortcuts = remember {
                listOf("Google" to "google.com", "YouTube" to "youtube.com", "TradingView" to "tradingview.com", "Daily Nation" to "nation.africa")
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                siteShortcuts.forEach { (label, url) ->
                    AssistChip(
                        onClick = { onOpenWebsite(url) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            // Spoken Quick Actions
            Text("Voice Quick Actions", style = MaterialTheme.typography.labelLarge, color = ArcCyan)
            val quickActions = remember {
                listOf(
                    "Read my notifications",
                    "Latest Kenyan news",
                    "Daily briefing",
                    "Install Spotify",
                    "Play music",
                    "Take a selfie",
                    "Open Reels",
                    "Study my device"
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickActions.forEach { prompt ->
                    AssistChip(
                        onClick = { onQuickPrompt(prompt) },
                        label = { Text(prompt, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            OutlinedButton(
                onClick = { onSelectSection(JarvisSection.VAULT) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_open_vault_button")
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Credentials Vault (${uiState.savedCredentials.size})")
            }

            if (uiState.hasOverlayPermission && settings.floatingOrbEnabled) {
                TextButton(
                    onClick = onLaunchFloatingOrbNow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Show Mini Orb Overlay Without Minimizing")
                }
            }
        }
    }
}
