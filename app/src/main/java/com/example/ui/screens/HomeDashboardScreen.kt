package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.JarvisUserSettings
import com.example.ui.JarvisSection
import com.example.ui.JarvisUiState
import com.example.ui.components.HudGlassCard
import com.example.ui.components.JarvisOrb
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber
import java.util.Calendar

@Composable
fun HomeDashboardScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOrbTap: () -> Unit,
    onToggleAlwaysOn: (Boolean) -> Unit,
    onToggleFloatingOrb: (Boolean) -> Unit,
    onLaunchFloatingOrbNow: () -> Unit,
    onQuickVoicePrompt: (String) -> Unit,
    onOpenWebsite: (String) -> Unit,
    onNavigate: (JarvisSection) -> Unit
) {
    val jc = LocalJarvisColors.current
    var showScreenHelperDisclosure by remember { mutableStateOf(false) }
    var websiteInput by remember { mutableStateOf("") }

    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning, Sir"
            in 12..16 -> "Good afternoon, Sir"
            else -> "Good evening, Sir"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.img_jarvis_hud_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.16f)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Greeting Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.headlineMedium,
                            color = jc.electricBlue
                        )
                        Text(
                            text = "Private AI assistant, Chrome browser & screen plugin",
                            style = MaterialTheme.typography.bodyMedium,
                            color = jc.mutedSlate
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.bluetoothAudioReady) {
                            Icon(
                                imageVector = Icons.Filled.BluetoothAudio,
                                contentDescription = "Bluetooth audio ready",
                                tint = ArcCyan,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                        IconButton(
                            onClick = { onNavigate(JarvisSection.SETTINGS) },
                            modifier = Modifier.testTag("home_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Open settings",
                                tint = ElectricBlue
                            )
                        }
                    }
                }
            }

            // Interactive Central Orb Hero Card
            item {
                HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.42f)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        JarvisOrb(
                            state = uiState.orbState,
                            audioLevel = uiState.audioLevel,
                            onClick = {
                                if (!hasMicPermission) onRequestMicPermission() else onOrbTap()
                            },
                            size = 182.dp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (uiState.partialVoiceText.isNotBlank()) {
                                "\"${uiState.partialVoiceText}\""
                            } else {
                                uiState.voiceStatusDetail
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArcCyan
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!hasMicPermission) onRequestMicPermission() else onOrbTap()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("home_talk_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Talk")
                            }
                            OutlinedButton(
                                onClick = { onNavigate(JarvisSection.JARVIS) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("home_chat_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Chat")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (!uiState.hasOverlayPermission) {
                                        onRequestOverlayPermission()
                                    } else {
                                        onLaunchFloatingOrbNow()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("home_mini_orb_button")
                            ) {
                                Icon(Icons.Filled.BubbleChart, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Mini Orb")
                            }
                        }

                        if (!hasMicPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onRequestMicPermission,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("grant_mic_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan.copy(alpha = 0.85f))
                            ) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Allow Microphone So JARVIS Can Hear You")
                            }
                        }
                    }
                }
            }

            // Open Any Website in Chrome
            item {
                HudGlassCard(borderColor = ArcCyan.copy(alpha = 0.30f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            tint = ArcCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Open Website in Chrome", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enter any link or site name — if JARVIS cannot find it online, he will ask you for the link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = jc.mutedSlate
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = websiteInput,
                            onValueChange = { websiteInput = it },
                            placeholder = { Text("e.g. tradingview.com or site name") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_website_input")
                        )
                        Button(
                            onClick = {
                                if (websiteInput.isNotBlank()) {
                                    onOpenWebsite(websiteInput)
                                    websiteInput = ""
                                }
                            },
                            enabled = websiteInput.isNotBlank(),
                            modifier = Modifier.testTag("home_open_website_button")
                        ) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open website in Chrome")
                            Spacer(Modifier.width(4.dp))
                            Text("Open")
                        }
                    }
                }
            }

            // Quick Things You Can Ask
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Try asking JARVIS",
                        style = MaterialTheme.typography.titleMedium,
                        color = ArcCyan
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val quickPrompts = listOf(
                            "Open YouTube in Chrome",
                            "Search online for gold market trend",
                            "Guide me on this screen",
                            "Fill my login credentials",
                            "Search Reddit for AI news",
                            "Open Wi-Fi settings",
                            "Set a timer for 5 minutes"
                        )
                        items(quickPrompts) { prompt ->
                            AssistChip(
                                onClick = { onQuickVoicePrompt(prompt) },
                                label = { Text(prompt, style = MaterialTheme.typography.labelLarge) }
                            )
                        }
                    }
                }
            }

            // Smart Phone Plugin (Always-on, Mini Orb, Screen Control & Step Guide)
            item {
                HudGlassCard(borderColor = ArcCyan.copy(alpha = 0.30f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Smart Phone Plugin", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                        StatusBadge(
                            text = if (uiState.isScreenHelperEnabled && uiState.hasOverlayPermission) "ACTIVE" else "SETUP",
                            color = if (uiState.isScreenHelperEnabled && uiState.hasOverlayPermission) ProfitEmerald else WarningAmber
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    // 1. Hands-Free Wake Word
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (settings.wakeWordEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                                contentDescription = null,
                                tint = if (settings.wakeWordEnabled) ProfitEmerald else jc.mutedSlate,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Hands-Free \"Hey JARVIS\"", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (settings.wakeWordEnabled) "Listening anytime in the background" else "Wake JARVIS anytime without tapping",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = jc.mutedSlate
                                )
                            }
                        }
                        Switch(
                            checked = settings.wakeWordEnabled,
                            onCheckedChange = { want ->
                                if (want && !hasMicPermission) onRequestMicPermission() else onToggleAlwaysOn(want)
                            },
                            modifier = Modifier.testTag("home_always_on_switch")
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 2. Floating Translucent Glowing Orb
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BubbleChart,
                                contentDescription = null,
                                tint = if (settings.floatingOrbEnabled && uiState.hasOverlayPermission) ArcCyan else jc.mutedSlate,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Floating Mini-Orb", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (!uiState.hasOverlayPermission) {
                                        "Tap switch to allow floating over other apps"
                                    } else if (settings.floatingOrbEnabled) {
                                        "Opens translucent mini-orb when you say \"Hey JARVIS\""
                                    } else {
                                        "Floating mini-orb is off"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = jc.mutedSlate
                                )
                            }
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

                    Spacer(Modifier.height(10.dp))

                    // 3. Screen Control & Step Guide (Accessibility)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showScreenHelperDisclosure = true }
                            .testTag("home_screen_helper_row"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccessibilityNew,
                                contentDescription = null,
                                tint = if (uiState.isScreenHelperEnabled) ProfitEmerald else WarningAmber,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Screen Control & Step Guide", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (uiState.isScreenHelperEnabled) {
                                        "Active — JARVIS can read screens, fill credentials & guide ID/OTP steps"
                                    } else {
                                        "Tap here to enable Android Screen Helper"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = jc.mutedSlate
                                )
                            }
                        }
                        StatusBadge(
                            text = if (uiState.isScreenHelperEnabled) "READY" else "SETUP",
                            color = if (uiState.isScreenHelperEnabled) ProfitEmerald else WarningAmber
                        )
                    }
                }
            }

            // Saved Logins & Private Credentials Quick Card
            item {
                HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.28f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = ProfitEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Saved Logins & Credentials", style = MaterialTheme.typography.titleMedium)
                                val profileReady = uiState.signupProfile.email.isNotBlank() ||
                                    uiState.signupProfile.fullName.isNotBlank()
                                Text(
                                    text = "${uiState.savedCredentials.size} saved logins • " +
                                        if (profileReady) "Personal info ready" else "Set up personal info",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = jc.mutedSlate
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { onNavigate(JarvisSection.VAULT) },
                            modifier = Modifier.testTag("home_open_vault_button")
                        ) {
                            Text("Open Vault")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(14.dp)) }
        }
    }

    if (showScreenHelperDisclosure) {
        AlertDialog(
            onDismissRequest = { showScreenHelperDisclosure = false },
            title = { Text("Enable JARVIS Screen Helper?") },
            text = {
                Text(
                    "Turning on JARVIS in Android Accessibility Settings lets JARVIS read visible screen elements, analyze open websites or trading apps, fill your encrypted credentials on command, guide ID photo or OTP steps, and navigate Back/Home/Settings. Passwords remain on your device and are never logged."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showScreenHelperDisclosure = false
                    onOpenAccessibilitySettings()
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
}
