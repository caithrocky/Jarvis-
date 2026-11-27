package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.example.ui.theme.WarningAmber

@Composable
fun JarvisChatScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    messages: List<ConversationMessageEntity>,
    memories: List<MemoryFactEntity>,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOrbTap: () -> Unit,
    onToggleMicMute: () -> Unit,
    onInterruptSpeech: () -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onClearMemories: () -> Unit,
    onExportMemoriesText: () -> String,
    onClearChat: () -> Unit
) {
    val context = LocalContext.current
    val jc = LocalJarvisColors.current
    var showMemoryDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, uiState.streamingReply) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val triggerVoice = {
        if (!hasMicPermission) onRequestMicPermission() else onOrbTap()
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
                Text("J.A.R.V.I.S VOICE", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text(
                    text = "Oral Conversation • ${settings.selectedModelId.substringAfterLast("/")}",
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
                    onClick = triggerVoice,
                    size = 76.dp,
                    executionProgress = uiState.taskProgress.fraction,
                    showStateLabel = false
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.partialVoiceText.isNotBlank()) "\"${uiState.partialVoiceText}\"" else uiState.voiceStatusDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ArcCyan
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Memory: ${if (settings.memoryEnabled) "${memories.size} stored facts" else "Disabled"} • Live voice & web active",
                        style = MaterialTheme.typography.labelSmall,
                        color = jc.mutedSlate
                    )
                }
            }
        }

        // Spoken Conversation Transcript
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
                            text = "Always listening — say \"Hey JARVIS\" or speak naturally, Sir.",
                            style = MaterialTheme.typography.titleMedium,
                            color = ArcCyan
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Speak naturally: \"Open Chrome\", \"Latest Kenyan news\", \"Check my notifications\", \"Install Spotify\", \"Switch to Mini Orb\", or \"Guide me on this screen\".",
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
                            text = if (isUser) "YOU (VOICE)" else "J.A.R.V.I.S",
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
                        Text("J.A.R.V.I.S (SPEAKING)", style = MaterialTheme.typography.labelMedium, color = ElectricBlue)
                        Spacer(Modifier.height(6.dp))
                        Text(uiState.streamingReply, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // Pure Oral Voice Control Dock (zero typing)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0x4D1E62D8),
                                Color(0x662E7EFF)
                            )
                        )
                    )
                    .border(1.dp, Color(0x708ECAFF), RoundedCornerShape(28.dp))
                    .clickable(onClick = triggerVoice)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("chat_mic_button"),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            !hasMicPermission -> "Allow microphone for automatic voice"
                            uiState.isMicMuted -> "Microphone muted — tap to resume"
                            uiState.orbState == OrbState.LISTENING -> "Listening — speak naturally"
                            uiState.orbState == OrbState.SPEAKING -> "Speaking — tap to interrupt"
                            settings.wakeWordEnabled -> "Always listening — say \"Hey Jarvis\""
                            else -> "Voice standby"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    Icon(
                        imageVector = if (uiState.orbState == OrbState.LISTENING) Icons.Filled.GraphicEq else Icons.Filled.Mic,
                        contentDescription = "Speak to JARVIS",
                        tint = if (uiState.orbState == OrbState.LISTENING) ArcCyan else Color.White
                    )
                }
            }

            IconButton(
                onClick = onToggleMicMute,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0x3314264A))
                    .border(1.dp, Color(0x446EC1FF), RoundedCornerShape(26.dp))
                    .testTag("chat_mute_button")
            ) {
                Icon(
                    imageVector = if (uiState.isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (uiState.isMicMuted) "Unmute microphone" else "Mute microphone",
                    tint = if (uiState.isMicMuted) WarningAmber else ArcCyan
                )
            }
        }
    }

    if (showMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Long-Term Memory")
                    IconButton(
                        onClick = {
                            val exportPayload = onExportMemoriesText()
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "JARVIS Long-Term Memory Export")
                                putExtra(Intent.EXTRA_TEXT, exportPayload)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, "Export JARVIS Memories"))
                            }
                        },
                        modifier = Modifier.testTag("export_memories_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Export memories", tint = ArcCyan)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Tell JARVIS \"remember that...\" anytime in conversation to store facts here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = jc.mutedSlate
                    )
                    if (memories.isEmpty()) {
                        Text("No stored memories yet.", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                    } else {
                        memories.take(8).forEach { mem ->
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
