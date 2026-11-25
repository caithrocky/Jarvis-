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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.JarvisApplication
import com.example.ui.screens.CredentialsVaultScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.JarvisChatScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.JarvisTheme
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.ObsidianBlack
import com.example.updates.JarvisUpdatePrompt

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
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemCapabilitiesState()
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

    BackHandler(enabled = uiState.selectedSection != JarvisSection.HOME) {
        viewModel.navigateTo(JarvisSection.HOME)
    }

    val primaryTabs = remember {
        listOf(
            PrimaryNavTab(JarvisSection.HOME, "Home", Icons.Filled.Home),
            PrimaryNavTab(JarvisSection.JARVIS, "JARVIS", Icons.Filled.AutoAwesome),
            PrimaryNavTab(JarvisSection.VAULT, "Credentials", Icons.Filled.Lock),
            PrimaryNavTab(JarvisSection.HISTORY, "Activity", Icons.Filled.History),
            PrimaryNavTab(JarvisSection.SETTINGS, "Settings", Icons.Filled.Settings)
        )
    }

    JarvisTheme(highContrastHud = settings.highContrastHud) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(ObsidianBlack, MidnightNavy, ObsidianBlack))
                )
        ) {
            val useRail = maxWidth >= 680.dp

            Scaffold(
                containerColor = Color.Transparent,
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
                                    label = { Text(tab.label, maxLines = 1) },
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
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag("rail_${tab.section.route}")
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 880.dp)
                                .fillMaxWidth()
                                .fillMaxHeight()
                        ) {
                            when (uiState.selectedSection) {
                                JarvisSection.HOME -> HomeDashboardScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = requestVoicePermissions,
                                    onRequestOverlayPermission = openOverlayPermissionSettings,
                                    onOpenAccessibilitySettings = openAccessibilitySettings,
                                    onOrbTap = { viewModel.onOrbTapped(hasMicPermission) },
                                    onToggleAlwaysOn = viewModel::toggleAlwaysOnVoice,
                                    onToggleFloatingOrb = viewModel::setFloatingOrbEnabled,
                                    onLaunchFloatingOrbNow = viewModel::launchFloatingOrbNow,
                                    onQuickVoicePrompt = { prompt ->
                                        viewModel.navigateTo(JarvisSection.JARVIS)
                                        viewModel.sendUserQuery(prompt, fromVoice = false)
                                    },
                                    onOpenWebsite = viewModel::openWebsiteInChrome,
                                    onNavigate = viewModel::navigateTo
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

                                JarvisSection.HISTORY -> HistoryScreen(
                                    aiLogs = aiLogs
                                )

                                JarvisSection.SETTINGS -> SettingsScreen(
                                    uiState = uiState,
                                    settings = settings,
                                    hasMicPermission = hasMicPermission,
                                    onRequestMicPermission = requestVoicePermissions,
                                    onRequestOverlayPermission = openOverlayPermissionSettings,
                                    onOpenAccessibilitySettings = openAccessibilitySettings,
                                    onToggleFloatingOrb = viewModel::setFloatingOrbEnabled,
                                    onLaunchFloatingOrbNow = viewModel::launchFloatingOrbNow,
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
                                    onToggleHighContrastHud = viewModel::setHighContrastHud,
                                    onTestNvidiaConnection = viewModel::testNvidiaNimConnection,
                                    onTriggerManualUpdateCheck = viewModel::triggerManualUpdateCheck,
                                    onSaveNvidiaKeyOverride = viewModel::saveNvidiaNimKeyOverride,
                                    onClearNvidiaKeyOverride = viewModel::clearNvidiaNimKeyOverride
                                )
                            }
                        }
                    }
                }
            }
        }
        JarvisUpdatePrompt(
            manualCheckTrigger = uiState.manualUpdateCheckTrigger,
            onCheckStatusMessage = viewModel::showStatusSnackbar
        )
    }
}
