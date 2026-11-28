package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.example.assistant.AccessibilityHealthState
import com.example.assistant.ModelInstallStage
import com.example.assistant.OfflineModelItemState
import com.example.assistant.OfflineModelManager
import com.example.data.local.JarvisUserSettings
import com.example.domain.model.AiModelOption
import com.example.domain.model.VoicePersonality
import com.example.ui.JarvisUiState
import com.example.ui.components.HudGlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber
import kotlin.math.hypot

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onToggleFloatingOrb: (Boolean) -> Unit,
    onLaunchFloatingOrbNow: () -> Unit,
    onUpdateOrbInactivityTimeout: (Int) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectVoicePersonality: (VoicePersonality) -> Unit,
    onUpdateVoiceSpeed: (Float) -> Unit,
    onToggleAlwaysOn: (Boolean) -> Unit,
    onToggleVoiceOutput: (Boolean) -> Unit,
    onPreviewVoice: () -> Unit,
    onToggleMemory: (Boolean) -> Unit,
    onClearMemories: () -> Unit,
    onClearChat: () -> Unit,
    onToggleHighContrastHud: (Boolean) -> Unit,
    onTestNvidiaConnection: () -> Unit,
    onTriggerManualUpdateCheck: () -> Unit,
    onToggleShowTapIndicator: (Boolean) -> Unit,
    onToggleShowHighlightIndicator: (Boolean) -> Unit,
    onToggleShowScrollIndicator: (Boolean) -> Unit,
    onToggleFuturisticHudMode: (Boolean) -> Unit,
    onToggleVoiceBiometricLock: (Boolean) -> Unit,
    onToggleVoiceFluAdaptation: (Boolean) -> Unit,
    onEnrollOwnerVoice: () -> Unit,
    onVerifyOwnerVoice: () -> Unit,
    onClearOwnerVoice: () -> Unit,
    onToggleAutoLearnDaily: (Boolean) -> Unit,
    onStudyDeviceNow: () -> Unit,
    onToggleKeepScreenAwake: (Boolean) -> Unit,
    onToggleDeviceUnlockEnabled: (Boolean) -> Unit,
    onSaveSafeWord: (String) -> Unit,
    onSaveUnlockPattern: (List<Int>) -> Unit,
    onClearUnlockCredentials: () -> Unit,
    onSaveNvidiaNimKey: (String) -> Unit,
    onClearNvidiaNimKeyOverride: () -> Unit,
    onToggleAutoOfflineSwitch: (Boolean) -> Unit,
    onDownloadLfmModel: () -> Unit,
    onCancelLfmDownload: () -> Unit,
    onDeleteLfmModel: () -> Unit,
    onDownloadVoskModel: () -> Unit,
    onCancelVoskDownload: () -> Unit,
    onDeleteVoskModel: () -> Unit
) {
    val context = LocalContext.current
    val jc = LocalJarvisColors.current
    var showScreenDisclosure by remember { mutableStateOf(false) }

    val (installedVersionName, installedVersionCode) = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            (info.versionName ?: "1.0") to PackageInfoCompat.getLongVersionCode(info)
        }.getOrDefault("1.0" to 1L)
    }
    val isBatteryUnrestricted = remember(uiState) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("SYSTEM SETTINGS", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text(
                    "Permissions health, companion Mini Orb, NVIDIA NIM AI & voice, GitHub sync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = jc.mutedSlate
                )
            }
        }

        // Permissions & Background Health Dashboard
        item {
            HudGlassCard(borderColor = ArcCyan.copy(alpha = 0.36f)) {
                Text("PERMISSIONS & BACKGROUND HEALTH", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                PermissionHealthRow(
                    label = "Microphone (Voice & Wake Word)",
                    granted = hasMicPermission,
                    actionLabel = if (hasMicPermission) "Granted" else "Allow",
                    onAction = onRequestMicPermission
                )
                PermissionHealthRow(
                    label = "Display Over Other Apps (Mini Orb)",
                    granted = uiState.hasOverlayPermission,
                    actionLabel = if (uiState.hasOverlayPermission) "Allowed" else "Allow",
                    onAction = onRequestOverlayPermission
                )
                val accState = uiState.accessibilityHealthState
                val accColor = when (accState) {
                    AccessibilityHealthState.OPERATIONAL, AccessibilityHealthState.CONNECTED -> ProfitEmerald
                    AccessibilityHealthState.CONNECTING -> ArcCyan
                    AccessibilityHealthState.MALFUNCTION_DETECTED -> MaterialTheme.colorScheme.error
                    else -> WarningAmber
                }
                val accActionLabel = when (accState) {
                    AccessibilityHealthState.OPERATIONAL -> "Operational"
                    AccessibilityHealthState.CONNECTED -> "Connected"
                    AccessibilityHealthState.CONNECTING -> "Binding"
                    AccessibilityHealthState.MALFUNCTION_DETECTED -> "Repair"
                    AccessibilityHealthState.PERMISSION_ENABLED_NOT_CONNECTED -> "Rebind"
                    AccessibilityHealthState.PERMISSION_DISABLED -> "Enable"
                }
                PermissionHealthRow(
                    label = "Screen Helper (UI & Social Automation)",
                    granted = uiState.isScreenHelperEnabled,
                    actionLabel = accActionLabel,
                    onAction = { showScreenDisclosure = true },
                    statusText = uiState.accessibilityStatusDetail,
                    statusColor = accColor
                )
                PermissionHealthRow(
                    label = "Notification Access (Missed Calls & Texts)",
                    granted = uiState.hasNotificationAccess,
                    actionLabel = if (uiState.hasNotificationAccess) "Connected" else "Enable",
                    onAction = onOpenNotificationAccessSettings
                )
                PermissionHealthRow(
                    label = "Built-In Device Plugin (Closed-App Wake & Orb)",
                    granted = isBatteryUnrestricted && uiState.isScreenHelperEnabled && uiState.hasOverlayPermission,
                    actionLabel = if (isBatteryUnrestricted) "Unrestricted" else "Install",
                    onAction = {
                        val launched = runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                            true
                        }.getOrDefault(false)
                        if (!launched) {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    },
                    testTag = "install_device_plugin_btn"
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Built-In Plugin & Boot Auto-Recovery: Keeps \"Hey JARVIS\" and Mini Orb auto-launch active even when JARVIS is closed from Recents or after a reboot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = jc.mutedSlate
                )
            }
        }

        // App Updates & GitHub Release Sync
        item {
            HudGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("APP UPDATES & GITHUB SYNC", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                    StatusBadge(
                        text = "v$installedVersionName ($installedVersionCode)",
                        color = ElectricBlue
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Installed build: v$installedVersionName (code $installedVersionCode). GitHub Actions APKs use repository Secrets to bundle your NVIDIA NIM key and release signature.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = jc.mutedSlate
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onTriggerManualUpdateCheck,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("check_updates_now_btn")
                ) {
                    Text("Check GitHub for Update Now")
                }
            }
        }

        // Floating Mini-Orb & Screen Helper Plugin
        item {
            HudGlassCard {
                Text("FLOATING MINI-ORB & SCREEN PLUGIN", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Floating Mini-Orb on Wake",
                    subtitle = if (uiState.hasOverlayPermission) {
                        "Auto-opens glowing companion Mini Orb when Full App is closed and you say \"Hey JARVIS\""
                    } else {
                        "Grant 'Display over other apps' permission to float over screens"
                    },
                    checked = settings.floatingOrbEnabled && uiState.hasOverlayPermission,
                    onCheckedChange = { want ->
                        if (want && !uiState.hasOverlayPermission) {
                            onRequestOverlayPermission()
                        } else {
                            onToggleFloatingOrb(want)
                        }
                    }
                )
                Text(
                    text = "Mini Orb Auto-Sleep Timeout: ${settings.orbInactivityTimeoutSeconds}s",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = settings.orbInactivityTimeoutSeconds.toFloat(),
                    onValueChange = { onUpdateOrbInactivityTimeout(it.toInt()) },
                    valueRange = 15f..300f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!uiState.hasOverlayPermission) onRequestOverlayPermission() else onLaunchFloatingOrbNow()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_launch_orb_btn")
                    ) {
                        Text(if (uiState.hasOverlayPermission) "Open Mini-Orb Now" else "Allow Floating Orb")
                    }
                    OutlinedButton(
                        onClick = { showScreenDisclosure = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_accessibility_btn")
                    ) {
                        val btnText = when (uiState.accessibilityHealthState) {
                            AccessibilityHealthState.OPERATIONAL -> "Helper: Operational"
                            AccessibilityHealthState.CONNECTED -> "Helper: Connected"
                            AccessibilityHealthState.CONNECTING -> "Helper: Binding"
                            AccessibilityHealthState.MALFUNCTION_DETECTED -> "Repair Helper"
                            AccessibilityHealthState.PERMISSION_ENABLED_NOT_CONNECTED -> "Rebind Helper"
                            AccessibilityHealthState.PERMISSION_DISABLED -> "Enable Screen Helper"
                        }
                        Text(btnText)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("default_assistant_settings_btn")
                ) {
                    Text("Set JARVIS as Default Digital Assistant")
                }
            }
        }

        // NVIDIA NIM Intelligence Model (in-app editable Keystore override + Build/GitHub Secrets)
        item {
            var nimKeyDraft by remember { mutableStateOf("") }
            var showNimKey by remember { mutableStateOf(false) }

            HudGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "NVIDIA NIM INTELLIGENCE MODEL",
                        style = MaterialTheme.typography.titleMedium,
                        color = ArcCyan,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(
                        text = uiState.nvidiaNimKeySource.uppercase(),
                        color = if (uiState.hasBuildConfigNvidiaKey || uiState.hasRuntimeNvidiaKeyOverride) {
                            ProfitEmerald
                        } else {
                            WarningAmber
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                AiModelOption.catalog.forEach { opt ->
                    val selected = settings.selectedModelId == opt.id
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(opt.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(opt.roleDescription, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectModel(opt.id) },
                            label = { Text(if (selected) "ACTIVE" else "USE") }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "API Key Source: ${uiState.nvidiaNimKeySource}. Keys saved here are encrypted in Android Keystore and persist across GitHub APK updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = jc.mutedSlate
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = nimKeyDraft,
                    onValueChange = { nimKeyDraft = it },
                    label = { Text("Enter or Update NVIDIA NIM API Key") },
                    placeholder = { Text("nvapi-...") },
                    singleLine = true,
                    visualTransformation = if (showNimKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showNimKey = !showNimKey }) {
                            Icon(
                                imageVector = if (showNimKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showNimKey) "Hide API key" else "Show API key"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nvidia_nim_key_input")
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveNvidiaNimKey(nimKeyDraft)
                            if (nimKeyDraft.trim().length >= 12) {
                                nimKeyDraft = ""
                            }
                        },
                        enabled = nimKeyDraft.trim().isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_nvidia_nim_key_btn")
                    ) {
                        Text("Save Key")
                    }
                    if (uiState.hasRuntimeNvidiaKeyOverride) {
                        OutlinedButton(
                            onClick = {
                                nimKeyDraft = ""
                                onClearNvidiaNimKeyOverride()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("clear_nvidia_nim_key_btn")
                        ) {
                            Text(if (uiState.hasBuildConfigNvidiaKey) "Use Build Key" else "Clear Key")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onTestNvidiaConnection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_nvidia_nim_btn")
                ) { Text("Test NVIDIA NIM & Magpie Voice") }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (uiState.hasBuildConfigNvidiaKey || uiState.hasRuntimeNvidiaKeyOverride) {
                        "NVIDIA NIM & Magpie Voice Status: Key Active (${uiState.nvidiaNimKeySource})"
                    } else {
                        "No NVIDIA NIM Key yet — paste your nvapi-... key above, or add NVIDIA_NIM_API_KEY in GitHub Repo Actions Secrets for release APKs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.hasBuildConfigNvidiaKey || uiState.hasRuntimeNvidiaKeyOverride) ProfitEmerald else WarningAmber
                )
                uiState.diagnosticResult?.let { diag ->
                    Spacer(Modifier.height(6.dp))
                    Text(diag, style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                }
            }
        }

        // Offline AI (LFM2.5 1.2B) & Offline Speech Recognition (Vosk)
        item {
            val lfmItem = uiState.offlineModelsState.lfmModel
            val voskItem = uiState.offlineModelsState.voskSttModel
            val activeEngineBadge = when {
                uiState.isOnline -> "ONLINE: NIM"
                settings.autoOfflineSwitchEnabled && uiState.isLfmOfflineReady -> "AUTO-OFFLINE: LFM2.5"
                else -> "OFFLINE: CORE"
            }
            val activeEngineColor = when {
                uiState.isOnline -> ElectricBlue
                settings.autoOfflineSwitchEnabled && uiState.isLfmOfflineReady -> ProfitEmerald
                else -> WarningAmber
            }

            HudGlassCard(borderColor = ProfitEmerald.copy(alpha = 0.35f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "OFFLINE AI & OFFLINE VOICE",
                        style = MaterialTheme.typography.titleMedium,
                        color = ArcCyan,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(text = activeEngineBadge, color = activeEngineColor)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Automatically switches from NVIDIA NIM to local Liquid AI LFM2.5 1.2B + Vosk offline speech recognition whenever your device loses network connectivity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = jc.mutedSlate
                )
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Auto-Switch to LFM2.5 1.2B Offline",
                    subtitle = "Use downloaded on-device LFM2.5 1.2B whenever internet is offline or cloud NIM is unavailable",
                    checked = settings.autoOfflineSwitchEnabled,
                    onCheckedChange = onToggleAutoOfflineSwitch,
                    testTag = "toggle_auto_offline_switch"
                )

                OfflineModelRow(
                    title = "LFM2.5 1.2B Instruct (GGUF Q4_K_M)",
                    subtitle = "On-device AI brain (~731 MB, arm64) • Resumable download",
                    item = lfmItem,
                    downloadButtonTag = "download_lfm_model_btn",
                    cancelButtonTag = "cancel_lfm_model_btn",
                    deleteButtonTag = "delete_lfm_model_btn",
                    onDownload = onDownloadLfmModel,
                    onCancel = onCancelLfmDownload,
                    onDelete = onDeleteLfmModel
                )
                Spacer(Modifier.height(10.dp))
                OfflineModelRow(
                    title = "Vosk Offline Speech Recognition (EN-US)",
                    subtitle = "Hands-free standby & command voice intake offline (~41 MB)",
                    item = voskItem,
                    downloadButtonTag = "download_vosk_model_btn",
                    cancelButtonTag = "cancel_vosk_model_btn",
                    deleteButtonTag = "delete_vosk_model_btn",
                    onDownload = onDownloadVoskModel,
                    onCancel = onCancelVoskDownload,
                    onDelete = onDeleteVoskModel
                )
            }
        }

        // Voice & Always-On Activation
        item {
            HudGlassCard {
                Text("VOICE & ALWAYS-ON ACTIVATION", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Always-On \"Hey JARVIS\" Listener",
                    subtitle = "Runs foreground microphone service with sleep wake-lock even when screen is off",
                    checked = settings.wakeWordEnabled,
                    onCheckedChange = { want ->
                        if (want && !hasMicPermission) onRequestMicPermission() else onToggleAlwaysOn(want)
                    }
                )
                SettingToggleRow(
                    title = "Keep Screen Awake While Active",
                    subtitle = "Prevents phone auto-sleep while JARVIS is active unless you say \"Make the device sleep\"",
                    checked = settings.keepScreenAwakeWhileActive,
                    onCheckedChange = onToggleKeepScreenAwake,
                    testTag = "toggle_keep_screen_awake"
                )
                SettingToggleRow(
                    title = "Spoken Neural Responses",
                    subtitle = "Speaks with NVIDIA Magpie Neural Voice (zero robotic TTS)",
                    checked = settings.voiceOutputEnabled,
                    onCheckedChange = onToggleVoiceOutput
                )
                Spacer(Modifier.height(8.dp))
                Text("NVIDIA Magpie Voice (${VoicePersonality.entries.size} Voices)", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoicePersonality.entries.forEach { vp ->
                        FilterChip(
                            selected = settings.voicePersonality == vp,
                            onClick = { onSelectVoicePersonality(vp) },
                            label = { Text(vp.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Voice Speed: ${"%.2f".format(settings.voiceSpeed)}x", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settings.voiceSpeed,
                    onValueChange = onUpdateVoiceSpeed,
                    valueRange = 0.75f..1.45f
                )
                Button(
                    onClick = onPreviewVoice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preview_jarvis_voice_btn")
                ) {
                    Text("Preview JARVIS Voice (\"Sir\")")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Battery Optimization Settings (Unrestricted Listening)")
                }
            }
        }

        // Owner Voiceprint & Flu Adaptation ("Under Me")
        item {
            HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.35f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("OWNER VOICE ID & FLU ADAPTATION", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                    StatusBadge(
                        text = if (uiState.voiceEnrolled) "ENROLLED" else "OPEN",
                        color = if (uiState.voiceEnrolled) ProfitEmerald else WarningAmber
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = uiState.speakerStatusSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricBlue
                )
                uiState.voiceEnrollmentProgress?.let { progressMsg ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = progressMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ProfitEmerald
                    )
                }
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Owner-Only Voice Lock (\"Under Me\")",
                    subtitle = "Restricts sensitive actions (logins, sending messages, screen control) strictly to your voiceprint",
                    checked = settings.voiceBiometricLockEnabled,
                    onCheckedChange = onToggleVoiceBiometricLock,
                    testTag = "toggle_voice_biometric_lock"
                )
                SettingToggleRow(
                    title = "Flu & Vocal-Strain Adaptation",
                    subtitle = "Matches your upper-formant vocal tract signature smoothly even if illness, cold, or hoarseness alters pitch",
                    checked = settings.voiceFluAdaptationEnabled,
                    onCheckedChange = onToggleVoiceFluAdaptation,
                    testTag = "toggle_voice_flu_adaptation"
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!hasMicPermission) onRequestMicPermission() else onEnrollOwnerVoice()
                        },
                        enabled = uiState.voiceEnrollmentProgress == null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("enroll_owner_voice_btn")
                    ) {
                        Text(if (uiState.voiceEnrolled) "Re-Enroll Voice ID" else "Enroll Voice ID (3x)")
                    }
                    OutlinedButton(
                        onClick = {
                            if (!hasMicPermission) onRequestMicPermission() else onVerifyOwnerVoice()
                        },
                        enabled = uiState.voiceEnrolled && uiState.voiceEnrollmentProgress == null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("verify_owner_voice_btn")
                    ) {
                        Text("Check Voice")
                    }
                }
                if (uiState.voiceEnrolled) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onClearOwnerVoice,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("clear_owner_voice_btn")
                    ) {
                        Text("Clear Saved Owner Voiceprint")
                    }
                }
            }
        }

        // Voice Device Unlock (Safe Word + 3x3 Pattern Grid)
        item {
            var safeWordDraft by remember { mutableStateOf("") }
            var showSafeWord by remember { mutableStateOf(false) }
            val draftPattern = remember { mutableStateListOf<Int>() }
            val unlockReady = settings.deviceUnlockEnabled &&
                uiState.hasDeviceSafeWord &&
                uiState.deviceUnlockPatternLength >= 4

            HudGlassCard(borderColor = ArcCyan.copy(alpha = 0.4f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("VOICE DEVICE UNLOCK & SAFE WORD", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                    StatusBadge(
                        text = if (unlockReady) "READY" else "SETUP",
                        color = if (unlockReady) ProfitEmerald else WarningAmber
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "When your phone is asleep, say \"Open the device\" or \"Unlock my phone\". JARVIS wakes the screen, asks for your Safe Word, and if confirmed draws your saved pattern.",
                    style = MaterialTheme.typography.bodySmall,
                    color = jc.mutedSlate
                )
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Enable Voice Unlock From Sleep",
                    subtitle = "Requires Screen Helper + Safe Word + 3x3 Pattern saved in Keystore",
                    checked = settings.deviceUnlockEnabled,
                    onCheckedChange = onToggleDeviceUnlockEnabled,
                    testTag = "toggle_device_unlock"
                )
                Text(
                    text = "Safe Word: ${if (uiState.hasDeviceSafeWord) "Saved in Keystore" else "Not set yet"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.hasDeviceSafeWord) ProfitEmerald else WarningAmber
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = safeWordDraft,
                    onValueChange = { safeWordDraft = it },
                    label = { Text("Set or Update Safe Word") },
                    placeholder = { Text("e.g. open sesame or falcon") },
                    singleLine = true,
                    visualTransformation = if (showSafeWord) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showSafeWord = !showSafeWord }) {
                            Icon(
                                imageVector = if (showSafeWord) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showSafeWord) "Hide safe word" else "Show safe word"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("safe_word_input")
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        onSaveSafeWord(safeWordDraft)
                        if (safeWordDraft.trim().length >= 2) {
                            safeWordDraft = ""
                        }
                    },
                    enabled = safeWordDraft.trim().length >= 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_safe_word_btn")
                ) {
                    Text("Save Safe Word")
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Device Unlock Pattern (3x3 Grid): " + if (uiState.deviceUnlockPatternLength >= 4) {
                        "Saved (${uiState.deviceUnlockPatternLength} dots)"
                    } else {
                        "Not set yet"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.deviceUnlockPatternLength >= 4) ProfitEmerald else WarningAmber
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Swipe or tap at least 4 dots in order (Draft: ${
                        if (draftPattern.isEmpty()) "none" else draftPattern.joinToString(" -> ")
                    }):",
                    style = MaterialTheme.typography.bodySmall,
                    color = jc.mutedSlate
                )
                Spacer(Modifier.height(8.dp))
                PatternLockInputGrid(
                    selectedDots = draftPattern,
                    onStrokeStart = { firstDot ->
                        draftPattern.clear()
                        draftPattern.add(firstDot)
                    },
                    onStrokeAddDot = { dot ->
                        if (dot !in draftPattern) {
                            val last = draftPattern.lastOrNull()
                            if (last != null) {
                                intermediatePatternDot(last, dot)?.let { mid ->
                                    if (mid !in draftPattern) draftPattern.add(mid)
                                }
                            }
                            draftPattern.add(dot)
                        }
                    },
                    onTapDot = { dot ->
                        if (dot !in draftPattern) {
                            val last = draftPattern.lastOrNull()
                            if (last != null) {
                                intermediatePatternDot(last, dot)?.let { mid ->
                                    if (mid !in draftPattern) draftPattern.add(mid)
                                }
                            }
                            draftPattern.add(dot)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .testTag("pattern_lock_grid")
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { draftPattern.clear() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("clear_pattern_draft_btn")
                    ) {
                        Text("Clear Grid")
                    }
                    Button(
                        onClick = {
                            onSaveUnlockPattern(draftPattern.toList())
                            draftPattern.clear()
                        },
                        enabled = draftPattern.size >= 4,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_unlock_pattern_btn")
                    ) {
                        Text("Save Pattern")
                    }
                }
                if (uiState.hasDeviceSafeWord || uiState.deviceUnlockPatternLength > 0) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            draftPattern.clear()
                            onClearUnlockCredentials()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wipe_unlock_credentials_btn")
                    ) {
                        Text("Clear Saved Safe Word & Pattern")
                    }
                }
            }
        }

        // Visual Screen Feedback & Futuristic HUD
        item {
            HudGlassCard(borderColor = ArcCyan.copy(alpha = 0.35f)) {
                Text("VISUAL ACTION OVERLAYS & HUD", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Show Tap & Multi-Touch",
                    subtitle = "Display glowing cyan tap circles & multi-finger touch rings (voice: \"Show taps\")",
                    checked = settings.showTapIndicator,
                    onCheckedChange = onToggleShowTapIndicator,
                    testTag = "toggle_show_taps"
                )
                SettingToggleRow(
                    title = "Highlight Screen Targets",
                    subtitle = "Draw glowing borders & #1/#2/#3 badges around targets JARVIS picks",
                    checked = settings.showHighlightIndicator,
                    onCheckedChange = onToggleShowHighlightIndicator,
                    testTag = "toggle_show_highlights"
                )
                SettingToggleRow(
                    title = "Show Scrolling",
                    subtitle = "Render animated directional & diagonal chevrons during free-roam scrolling (voice: \"Show scrolling\")",
                    checked = settings.showScrollIndicator,
                    onCheckedChange = onToggleShowScrollIndicator,
                    testTag = "toggle_show_scrolls"
                )
                SettingToggleRow(
                    title = "Futuristic Holographic Telemetry",
                    subtitle = "Show live Voice ID, Device Learning & Autonomous HUD strip on Home",
                    checked = settings.futuristicHudMode,
                    onCheckedChange = onToggleFuturisticHudMode,
                    testTag = "toggle_futuristic_hud"
                )
            }
        }

        // Daily Self-Learning Studio
        item {
            HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.35f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AUTONOMOUS APP & DEVICE LEARNING", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                    StatusBadge(
                        text = "${uiState.learnedAppsCount} APPS",
                        color = ProfitEmerald
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Studied ${uiState.learnedAppsCount} installed & system apps • ${uiState.learnedWorkflowsCount} screen workflows • ${uiState.ownerHabitsCount} habits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = jc.mutedSlate
                )
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Auto-Learn New & Existing Apps",
                    subtitle = "Automatically inspects all apps, newly installed apps & researches commands online",
                    checked = settings.autoLearnDeviceDaily,
                    onCheckedChange = onToggleAutoLearnDaily,
                    testTag = "toggle_auto_learn_daily"
                )
                Button(
                    onClick = onStudyDeviceNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("study_device_now_btn")
                ) {
                    Text("Inspect & Learn All Apps Now")
                }
            }
        }

        // Memory & Display Controls
        item {
            HudGlassCard {
                Text("MEMORY & DISPLAY CONTROLS", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Persistent Conversational Memory",
                    subtitle = "Remember preferences & follow-up context locally on device",
                    checked = settings.memoryEnabled,
                    onCheckedChange = onToggleMemory
                )
                SettingToggleRow(
                    title = "High-Contrast Obsidian HUD",
                    subtitle = "Pure black OLED contrast & brighter glow borders",
                    checked = settings.highContrastHud,
                    onCheckedChange = onToggleHighContrastHud
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClearMemories, modifier = Modifier.weight(1f)) { Text("Clear Memories") }
                    OutlinedButton(onClick = onClearChat, modifier = Modifier.weight(1f)) { Text("Clear History") }
                }
            }
        }

        // API Connection Status (NVIDIA NIM)
        item {
            Text("API CONNECTION STATUS", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
        }
        items(uiState.apiStatuses, key = { it.id }) { api ->
            HudGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(api.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusBadge(
                        text = when {
                            api.reachable == true -> "CONNECTED"
                            api.configured && api.reachable == false -> "CHECK ERROR"
                            api.configured -> "CONFIGURED"
                            else -> "MISSING KEY"
                        },
                        color = when {
                            api.reachable == true || (api.configured && api.reachable != false) -> ProfitEmerald
                            else -> WarningAmber
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(api.statusText, style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                Spacer(Modifier.height(4.dp))
                Text(api.obtainGuide, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                Text(
                    text = "Secrets: ${api.secretKeys.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricBlue
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showScreenDisclosure) {
        AlertDialog(
            onDismissRequest = { showScreenDisclosure = false },
            title = { Text("Enable JARVIS Screen Helper?") },
            text = {
                Text(
                    "Enabling Accessibility lets JARVIS read visible screen items, analyze open websites or apps, fill saved credentials when permitted, guide ID/OTP steps, tap buttons, scroll, and perform Back/Home navigation."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showScreenDisclosure = false
                    onOpenAccessibilitySettings()
                }) { Text("Open Accessibility Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showScreenDisclosure = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalJarvisColors.current.mutedSlate)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PermissionHealthRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String? = null,
    statusText: String? = null,
    statusColor: Color? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = statusText ?: if (granted) "Ready" else "Setup recommended",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor ?: if (granted) ProfitEmerald else WarningAmber
            )
        }
        OutlinedButton(
            onClick = onAction,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        ) {
            Text(actionLabel)
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun OfflineModelRow(
    title: String,
    subtitle: String,
    item: OfflineModelItemState,
    downloadButtonTag: String,
    cancelButtonTag: String,
    deleteButtonTag: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val jc = LocalJarvisColors.current
    val stageBadgeText = when (item.stage) {
        ModelInstallStage.INSTALLED -> "INSTALLED"
        ModelInstallStage.DOWNLOADING -> "DOWNLOADING"
        ModelInstallStage.EXTRACTING -> "EXTRACTING"
        ModelInstallStage.ERROR -> "ERROR"
        ModelInstallStage.NOT_INSTALLED -> "NOT INSTALLED"
    }
    val stageBadgeColor = when (item.stage) {
        ModelInstallStage.INSTALLED -> ProfitEmerald
        ModelInstallStage.DOWNLOADING, ModelInstallStage.EXTRACTING -> ArcCyan
        ModelInstallStage.ERROR -> MaterialTheme.colorScheme.error
        ModelInstallStage.NOT_INSTALLED -> WarningAmber
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x440A1833))
            .border(1.dp, ArcCyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusBadge(text = stageBadgeText, color = stageBadgeColor)
        }
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = jc.mutedSlate)

        when (item.stage) {
            ModelInstallStage.DOWNLOADING, ModelInstallStage.EXTRACTING -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { item.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (item.stage == ModelInstallStage.EXTRACTING) {
                        "Extracting package..."
                    } else {
                        val pct = (item.progressFraction * 100).toInt()
                        "${OfflineModelManager.formatMb(item.downloadedBytes)} / ${OfflineModelManager.formatMb(item.totalBytes)} ($pct%)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = ArcCyan
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(cancelButtonTag)
                ) {
                    Text("Pause / Cancel Download")
                }
            }

            ModelInstallStage.INSTALLED -> {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ready on device (${OfflineModelManager.formatMb(item.installedSizeBytes)})",
                        style = MaterialTheme.typography.labelMedium,
                        color = ProfitEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag(deleteButtonTag)
                    ) {
                        Text("Remove")
                    }
                }
            }

            ModelInstallStage.ERROR, ModelInstallStage.NOT_INSTALLED -> {
                item.errorMessage?.let { err ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (item.downloadedBytes > 0L && item.totalBytes > 0L) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Partial download saved: ${OfflineModelManager.formatMb(item.downloadedBytes)} / ${OfflineModelManager.formatMb(item.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = jc.mutedSlate
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(downloadButtonTag)
                ) {
                    Text(
                        when {
                            item.stage == ModelInstallStage.ERROR && item.downloadedBytes > 0L -> "Resume Download"
                            item.downloadedBytes > 0L -> "Resume Download"
                            else -> "Download Model"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PatternLockInputGrid(
    selectedDots: List<Int>,
    onStrokeStart: (Int) -> Unit,
    onStrokeAddDot: (Int) -> Unit,
    onTapDot: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var activeDragPos by remember { mutableStateOf<Offset?>(null) }

    fun dotCenter(dot: Int, size: IntSize): Offset {
        val idx = dot - 1
        val col = idx % 3
        val row = idx / 3
        return Offset(
            x = (col + 0.5f) * (size.width / 3f),
            y = (row + 0.5f) * (size.height / 3f)
        )
    }

    fun hitTestDot(pos: Offset, size: IntSize): Int? {
        if (size.width <= 0 || size.height <= 0) return null
        val hitRadius = minOf(size.width, size.height) / 7.5f
        for (dot in 1..9) {
            val c = dotCenter(dot, size)
            if (hypot((pos.x - c.x).toDouble(), (pos.y - c.y).toDouble()) <= hitRadius) {
                return dot
            }
        }
        return null
    }

    Box(
        modifier = modifier
            .size(216.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x66081229))
            .border(1.dp, ArcCyan.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val initialHit = hitTestDot(down.position, boxSize)
                    var movedAcrossMultiple = false
                    var lastDragDot = initialHit
                    var strokeStarted = false
                    activeDragPos = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            activeDragPos = null
                            if (!movedAcrossMultiple && initialHit != null) {
                                onTapDot(initialHit)
                            }
                            break
                        }
                        activeDragPos = change.position
                        val hit = hitTestDot(change.position, boxSize)
                        val distanceFromStart = hypot(
                            (change.position.x - down.position.x).toDouble(),
                            (change.position.y - down.position.y).toDouble()
                        )
                        if (distanceFromStart > 14.0) {
                            movedAcrossMultiple = true
                            if (!strokeStarted && initialHit != null) {
                                strokeStarted = true
                                onStrokeStart(initialHit)
                            }
                        }
                        if (movedAcrossMultiple && hit != null && hit != lastDragDot) {
                            if (!strokeStarted) {
                                strokeStarted = true
                                onStrokeStart(hit)
                            } else {
                                onStrokeAddDot(hit)
                            }
                            lastDragDot = hit
                        }
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
            val strokeColor = ArcCyan.copy(alpha = 0.85f)
            if (selectedDots.size >= 2) {
                for (i in 0 until selectedDots.lastIndex) {
                    drawLine(
                        color = strokeColor,
                        start = dotCenter(selectedDots[i], canvasSize),
                        end = dotCenter(selectedDots[i + 1], canvasSize),
                        strokeWidth = 7f,
                        cap = StrokeCap.Round
                    )
                }
            }
            val dragPos = activeDragPos
            val lastDot = selectedDots.lastOrNull()
            if (dragPos != null && lastDot != null) {
                drawLine(
                    color = ElectricBlue.copy(alpha = 0.55f),
                    start = dotCenter(lastDot, canvasSize),
                    end = dragPos,
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
            }

            for (dot in 1..9) {
                val center = dotCenter(dot, canvasSize)
                val isSelected = dot in selectedDots
                drawCircle(
                    color = if (isSelected) ArcCyan.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                    radius = if (isSelected) 24f else 18f,
                    center = center
                )
                drawCircle(
                    color = if (isSelected) ProfitEmerald else ArcCyan.copy(alpha = 0.65f),
                    radius = if (isSelected) 11f else 7f,
                    center = center
                )
            }
        }
    }
}

private fun intermediatePatternDot(a: Int, b: Int): Int? {
    val ra = (a - 1) / 3
    val ca = (a - 1) % 3
    val rb = (b - 1) / 3
    val cb = (b - 1) % 3
    val dRow = rb - ra
    val dCol = cb - ca
    return if (dRow % 2 == 0 && dCol % 2 == 0) {
        val midRow = (ra + rb) / 2
        val midCol = (ca + cb) / 2
        midRow * 3 + midCol + 1
    } else {
        null
    }
}
