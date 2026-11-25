package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.JarvisUserSettings
import com.example.data.local.MemoryFactEntity
import com.example.domain.model.OrbState
import com.example.ui.JarvisUiState
import com.example.ui.components.HudGlassCard
import com.example.ui.components.JarvisOrb
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald

@Composable
fun JarvisChatScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    messages: List<ConversationMessageEntity>,
    memories: List<MemoryFactEntity>,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOrbTap: () -> Unit,
    onInterruptSpeech: () -> Unit,
    onSendQuery: (String) -> Unit,
    onAddMemory: (String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onClearMemories: () -> Unit,
    onClearChat: () -> Unit
) {
    val jc = LocalJarvisColors.current
    var inputText by remember { mutableStateOf("") }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var newMemoryText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, uiState.streamingReply) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Header + Memory / Interrupt actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("J.A.R.V.I.S ASSISTANT", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text(
                    text = "Personal Assistant & Floating Plugin • ${settings.selectedModelId.substringAfterLast("/")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = jc.mutedSlate
                )
            }
            IconButton(
                onClick = { showMemoryDialog = true },
                modifier = Modifier.testTag("open_memory_button")
            ) {
                Icon(Icons.Filled.Memory, contentDescription = "Manage conversational memory", tint = ArcCyan)
            }
            if (uiState.orbState == OrbState.SPEAKING) {
                IconButton(
                    onClick = onInterruptSpeech,
                    modifier = Modifier.testTag("interrupt_speech_button")
                ) {
                    Icon(Icons.Filled.StopCircle, contentDescription = "Interrupt JARVIS speech", tint = AlertCrimson)
                }
            }
        }

        // Compact Voice Orb Strip
        HudGlassCard(
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                JarvisOrb(
                    state = uiState.orbState,
                    audioLevel = uiState.audioLevel,
                    onClick = {
                        if (!hasMicPermission) onRequestMicPermission() else onOrbTap()
                    },
                    size = 76.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.partialVoiceText.isNotBlank()) "\"${uiState.partialVoiceText}\"" else uiState.voiceStatusDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArcCyan
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Memory: ${if (settings.memoryEnabled) "${memories.size} stored facts" else "Disabled"} • Live web search active",
                        style = MaterialTheme.typography.labelSmall,
                        color = jc.mutedSlate
                    )
                }
            }
        }

        // Conversation Transcript
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    HudGlassCard {
                        Text(
                            text = "Say \"Hey JARVIS\" or ask me anything, Sir.",
                            style = MaterialTheme.typography.titleMedium,
                            color = ArcCyan
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Try: \"Open tradingview.com in Chrome\", \"Sign me up on GitHub with my permission\", \"Guide me on this screen\", \"Analyze what is on my screen\", \"Search the internet for gold market outlook\", or \"Open Wi-Fi settings\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = jc.mutedSlate
                        )
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                val isUser = msg.role == "user"
                HudGlassCard(
                    borderColor = if (isUser) ArcCyan.copy(alpha = 0.45f) else ElectricBlue.copy(alpha = 0.25f),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isUser) if (msg.isVoice) "YOU (VOICE)" else "YOU" else "J.A.R.V.I.S",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isUser) ArcCyan else ElectricBlue
                        )
                        msg.toolSummary?.let { tools ->
                            StatusBadge(text = tools, color = ProfitEmerald)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            if (uiState.isAiProcessing && uiState.streamingReply.isNotBlank()) {
                item {
                    HudGlassCard(borderColor = ElectricBlue) {
                        Text("J.A.R.V.I.S (STREAMING)", style = MaterialTheme.typography.labelMedium, color = ElectricBlue)
                        Spacer(Modifier.height(6.dp))
                        Text(uiState.streamingReply, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask online, open Chrome site, sign in, or guide screen...") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_field")
            )
            IconButton(
                onClick = {
                    if (!hasMicPermission) onRequestMicPermission() else onOrbTap()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_mic_button")
            ) {
                Icon(Icons.Filled.Mic, contentDescription = "Speak to JARVIS", tint = ArcCyan)
            }
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendQuery(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_send_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send prompt", tint = ElectricBlue)
            }
        }
    }

    if (showMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = { Text("Conversational Memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newMemoryText,
                            onValueChange = { newMemoryText = it },
                            label = { Text("Save preference/note") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            if (newMemoryText.isNotBlank()) {
                                onAddMemory(newMemoryText)
                                newMemoryText = ""
                            }
                        }) {
                            Icon(Icons.Filled.BookmarkAdd, contentDescription = "Save memory")
                        }
                    }
                    if (memories.isEmpty()) {
                        Text("No stored memories yet.", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                    } else {
                        memories.take(6).forEach { mem ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mem.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onDeleteMemory(mem.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete memory", tint = AlertCrimson)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onClearMemories) { Text("Clear Memories") }
                        OutlinedButton(onClick = onClearChat) { Text("Clear Chat") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMemoryDialog = false }) { Text("Done") }
            }
        )
    }
}
