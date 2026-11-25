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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    onToggleWatchlist: (String) -> Unit,
    onUpdateNotificationPrefs: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onToggleHighContrastHud: (Boolean) -> Unit,
    onToggleSupabaseSync: (Boolean) -> Unit,
    onTestNvidiaConnection: () -> Unit,
    onCheckForUpdates: () -> Unit
) {
    val context = LocalContext.current
    val jc = LocalJarvisColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("SYSTEM SETTINGS", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text("NVIDIA NIM models, voice engine, privacy & API diagnostics", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
            }
        }

        item {
            HudGlassCard {
                Text("AUTOMATIC UPDATES", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(6.dp))
                Text(uiState.updateStatus, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCheckForUpdates,
                    enabled = !uiState.isCheckingForUpdates,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.isCheckingForUpdates) "CHECKING..." else "Check & Download Update")
                }
            }
        }

        // NVIDIA NIM Model Selection
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
                ) { Text("Test All Connected APIs (NIM, TTS, Supabase, Brokers, Finnhub)") }
                uiState.diagnosticResult?.let { diag ->
                    Spacer(Modifier.height(6.dp))
                    Text(diag, style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                }
            }
        }

        // Voice & Wake-Word Settings
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

        // Memory & Privacy Controls
        item {
            HudGlassCard {
                Text("MEMORY & PRIVACY CONTROLS", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Persistent Conversational Memory",
                    subtitle = "Remember preferences & follow-up context locally",
                    checked = settings.memoryEnabled,
                    onCheckedChange = onToggleMemory
                )
                SettingToggleRow(
                    title = "Supabase Cloud Memory Sync",
                    subtitle = "Sync preferences when authenticated in Accounts",
                    checked = settings.supabaseAutoSync,
                    onCheckedChange = onToggleSupabaseSync
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

        // Watchlist & Notifications
        item {
            HudGlassCard {
                Text("WATCHLIST & NOTIFICATIONS", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("EUR/USD", "GBP/USD", "USD/JPY", "XAU/USD", "BTC/USDT", "ETH/USDT", "SOL/USDT", "R_100").forEach { sym ->
                        FilterChip(
                            selected = settings.watchlistSymbols.contains(sym),
                            onClick = { onToggleWatchlist(sym) },
                            label = { Text(sym) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingToggleRow(
                    title = "Market Movement Alerts",
                    subtitle = "Threshold breakouts & 24h volatility alerts",
                    checked = settings.notifyMarketMoves,
                    onCheckedChange = {
                        onUpdateNotificationPrefs(it, settings.notifyOpportunities, settings.notifyNews, settings.notifyTrades)
                    }
                )
                SettingToggleRow(
                    title = "Trade Execution & Error Alerts",
                    subtitle = "Confirmations, blocks, and broker disconnects",
                    checked = settings.notifyTrades,
                    onCheckedChange = {
                        onUpdateNotificationPrefs(settings.notifyMarketMoves, settings.notifyOpportunities, settings.notifyNews, it)
                    }
                )
            }
        }

        // API Connection Status & Secrets Guide
        item {
            Text("API CONNECTION STATUS (AI STUDIO SECRETS)", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
        }
        items(uiState.apiStatuses, key = { it.id }) { api ->
            HudGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(api.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    val isOptionalDeferred = api.id == "finnhub" && !api.configured
                    StatusBadge(
                        text = when {
                            api.reachable == true -> "CONNECTED"
                            api.configured && api.reachable == false -> "CHECK ERROR"
                            api.configured -> "CONFIGURED"
                            isOptionalDeferred -> "OPTIONAL"
                            else -> "MISSING KEY"
                        },
                        color = when {
                            api.reachable == true || api.configured && api.reachable != false -> ProfitEmerald
                            isOptionalDeferred -> ArcCyan
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
