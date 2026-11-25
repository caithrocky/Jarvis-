package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleFloatingOrb: (Boolean) -> Unit,
    onLaunchFloatingOrbNow: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectVoicePersonality: (VoicePersonality) -> Unit,
    onUpdateVoiceSpeed: (Float) -> Unit,
    onToggleAlwaysOn: (Boolean) -> Unit,
    onToggleVoiceOutput: (Boolean) -> Unit,
    onToggleNvidiaMagpieVoice: (Boolean) -> Unit,
    onPreviewVoice: () -> Unit,
    onToggleMemory: (Boolean) -> Unit,
    onClearMemories: () -> Unit,
    onClearChat: () -> Unit,
    onToggleHighContrastHud: (Boolean) -> Unit,
    onTestNvidiaConnection: () -> Unit,
    onTriggerManualUpdateCheck: () -> Unit,
    onSaveNvidiaKeyOverride: (String) -> Unit,
    onClearNvidiaKeyOverride: () -> Unit
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

    var nvidiaKeyInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("SYSTEM SETTINGS", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text(
                    "Floating plugin, NVIDIA NIM AI & voice, privacy & GitHub updates",
                    style = MaterialTheme.typography.bodyMedium,
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
                        "Opens a small glowing translucent orb over any app on \"Hey JARVIS\""
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
                        Text(if (uiState.isScreenHelperEnabled) "Screen Helper: ON" else "Enable Screen Helper")
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

        // NVIDIA NIM Intelligence Model & Key Fallback
        item {
            HudGlassCard {
                Text("NVIDIA NIM INTELLIGENCE MODEL", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
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
                Button(
                    onClick = onTestNvidiaConnection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_nvidia_nim_btn")
                ) { Text("Test NVIDIA NIM & Magpie Voice") }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        uiState.hasBuildConfigNvidiaKey -> "Build Key Status: Embedded at build time."
                        uiState.hasRuntimeNvidiaKeyOverride -> "Build Key Missing — using encrypted key saved on this device."
                        else -> "Build Key Missing. Save your nvapi- key below to keep NVIDIA NIM & Magpie voice working across updates:"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.hasBuildConfigNvidiaKey || uiState.hasRuntimeNvidiaKeyOverride) ProfitEmerald else WarningAmber
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = nvidiaKeyInput,
                    onValueChange = { nvidiaKeyInput = it },
                    label = { Text("Encrypted NVIDIA NIM Key Fallback (nvapi-...)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nvidia_key_override_input")
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveNvidiaKeyOverride(nvidiaKeyInput)
                            nvidiaKeyInput = ""
                        },
                        enabled = nvidiaKeyInput.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_nvidia_key_btn")
                    ) {
                        Text("Save Encrypted Key")
                    }
                    if (uiState.hasRuntimeNvidiaKeyOverride) {
                        OutlinedButton(
                            onClick = onClearNvidiaKeyOverride,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("clear_nvidia_key_btn")
                        ) {
                            Text("Clear Saved Key")
                        }
                    }
                }
                uiState.diagnosticResult?.let { diag ->
                    Spacer(Modifier.height(6.dp))
                    Text(diag, style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                }
            }
        }

        // Voice & Always-On Activation
        item {
            HudGlassCard {
                Text("VOICE & ALWAYS-ON ACTIVATION", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Always-On \"Hey JARVIS\" Listener",
                    subtitle = "Runs foreground microphone service with persistent indicator",
                    checked = settings.wakeWordEnabled,
                    onCheckedChange = { want ->
                        if (want && !hasMicPermission) onRequestMicPermission() else onToggleAlwaysOn(want)
                    }
                )
                SettingToggleRow(
                    title = "Spoken TTS Responses",
                    subtitle = "Read JARVIS answers aloud with interruption support",
                    checked = settings.voiceOutputEnabled,
                    onCheckedChange = onToggleVoiceOutput
                )
                SettingToggleRow(
                    title = "NVIDIA Magpie Neural Voice (NIM)",
                    subtitle = "Uses nvidia/magpie-tts-multilingual instead of robotic device TTS",
                    checked = settings.useNvidiaMagpieVoice,
                    onCheckedChange = onToggleNvidiaMagpieVoice
                )
                Spacer(Modifier.height(8.dp))
                Text("Voice Personality", style = MaterialTheme.typography.labelLarge)
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
    onCheckedChange: (Boolean) -> Unit
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Spacer(Modifier.height(6.dp))
}
