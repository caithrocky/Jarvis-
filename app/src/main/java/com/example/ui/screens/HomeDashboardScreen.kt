package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.assistant.FuturisticMissionSuggestion
import com.example.assistant.JarvisHudIntelligence
import com.example.assistant.ScheduledAutomationEngine
import com.example.data.local.DeviceAppKnowledgeEntity
import com.example.data.local.JarvisUserSettings
import com.example.data.local.MemoryFactEntity
import com.example.data.local.ScheduledAutomationEntity
import com.example.domain.model.OrbState
import com.example.domain.model.TaskStage
import com.example.ui.JarvisSection
import com.example.ui.JarvisUiState
import com.example.ui.components.JarvisOrb
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.SoftTurquoise
import com.example.ui.theme.SoftViolet
import com.example.ui.theme.WarningAmber

@Composable
fun HomeDashboardScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    memories: List<MemoryFactEntity>,
    learnedApps: List<DeviceAppKnowledgeEntity>,
    scheduledAutomations: List<ScheduledAutomationEntity>,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOpenMenu: () -> Unit,
    onOrbTap: () -> Unit,
    onNavigate: (JarvisSection) -> Unit,
    onAbortAutonomous: () -> Unit,
    onCycleHudHeadline: () -> Unit,
    onTriggerSuggestion: (FuturisticMissionSuggestion) -> Unit,
    onRunAutomationNow: (ScheduledAutomationEntity) -> Unit,
    onDeleteAutomation: (Long) -> Unit
) {
    val triggerVoice = {
        if (!hasMicPermission) onRequestMicPermission() else onOrbTap()
    }

    val activeAutomations = remember(scheduledAutomations) {
        scheduledAutomations.filter { it.isEnabled }.sortedBy { it.triggerAtMs }
    }

    val spotlight = remember(
        uiState.partialVoiceText,
        uiState.orbState,
        uiState.isAiProcessing,
        uiState.isMicMuted,
        uiState.lastUserPrompt,
        uiState.aiHudHeadline,
        uiState.taskProgress.title,
        uiState.pendingConfirmationSummary,
        uiState.isOnline,
        uiState.batteryPercent,
        uiState.isCharging,
        uiState.unreadNotificationCount,
        uiState.learnedAppsCount,
        memories,
        activeAutomations,
        uiState.hudRotationTick
    ) {
        JarvisHudIntelligence.buildSpotlight(
            partialVoiceText = uiState.partialVoiceText,
            isListening = uiState.orbState == OrbState.LISTENING,
            isProcessing = uiState.isAiProcessing ||
                uiState.orbState == OrbState.PROCESSING ||
                uiState.orbState == OrbState.UNDERSTANDING,
            isSpeaking = uiState.orbState == OrbState.SPEAKING,
            isExecuting = uiState.orbState == OrbState.EXECUTING,
            isMicMuted = uiState.isMicMuted,
            lastUserPrompt = uiState.lastUserPrompt,
            aiChosenHeadline = uiState.aiHudHeadline,
            taskProgressTitle = uiState.taskProgress.title,
            pendingConfirmationSummary = uiState.pendingConfirmationSummary,
            isOnline = uiState.isOnline,
            batteryPercent = uiState.batteryPercent,
            isCharging = uiState.isCharging,
            unreadNotificationCount = uiState.unreadNotificationCount,
            learnedAppsCount = uiState.learnedAppsCount,
            memoriesCount = memories.size,
            activeAutomations = activeAutomations,
            recentMemories = memories,
            rotationTick = uiState.hudRotationTick
        )
    }

    val futuristicSuggestions = remember(
        uiState.isOnline,
        uiState.batteryPercent,
        uiState.unreadNotificationCount,
        uiState.learnedAppsCount,
        activeAutomations,
        learnedApps,
        uiState.hudRotationTick
    ) {
        JarvisHudIntelligence.buildAutogeneratedSuggestions(
            isOnline = uiState.isOnline,
            batteryPercent = uiState.batteryPercent,
            unreadNotificationCount = uiState.unreadNotificationCount,
            learnedAppsCount = uiState.learnedAppsCount,
            activeAutomations = activeAutomations,
            learnedApps = learnedApps
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF010207),
                        0.52f to Color(0xFF020714),
                        0.76f to Color(0xFF05183D),
                        0.91f to Color(0xFF0A398C),
                        1.0f to Color(0xFF1456C4)
                    )
                )
            )
    ) {
        // Subtle radial cobalt glow rising from behind the bottom voice pill
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF287EFF).copy(alpha = 0.42f),
                        Color(0xFF0D44AA).copy(alpha = 0.20f),
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height * 0.96f),
                    radius = size.width * 0.85f
                ),
                radius = size.width * 0.85f,
                center = Offset(size.width / 2f, size.height * 0.96f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar: Hamburger left, Telemetry Pill center, Settings right
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlowingGlassCircleButton(
                    onClick = onOpenMenu,
                    contentDescription = "Open all JARVIS features",
                    modifier = Modifier.testTag("home_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Compact Live Telemetry Chip
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x450A2250))
                        .border(1.dp, Color(0x456EC1FF), RoundedCornerShape(20.dp))
                        .clickable { onNavigate(JarvisSection.JARVIS) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("home_telemetry_pill")
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (uiState.isOnline) ProfitEmerald else WarningAmber)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            append(if (uiState.isOnline) "NIM ONLINE" else "OFFLINE CORE")
                            append(" • ${uiState.batteryPercent}%")
                            if (activeAutomations.isNotEmpty()) {
                                append(" • ${activeAutomations.size} AUTO")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 0.6.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = Color(0xFFD6E9FF)
                    )
                }

                GlowingGlassCircleButton(
                    onClick = { onNavigate(JarvisSection.SETTINGS) },
                    contentDescription = "Open settings",
                    modifier = Modifier.testTag("home_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Centered Orb + Dynamic AI Headline + Live Status / Scheduled Automations & Missions
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                ) {
                    JarvisOrb(
                        state = uiState.orbState,
                        audioLevel = uiState.audioLevel,
                        onClick = triggerVoice,
                        size = 192.dp,
                        executionProgress = uiState.taskProgress.fraction,
                        showStateLabel = false
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Dynamic Kicker / Eyebrow Label
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onCycleHudHeadline)
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        uiState.isMicMuted -> WarningAmber
                                        uiState.orbState == OrbState.LISTENING -> ArcCyan
                                        uiState.orbState == OrbState.EXECUTING -> SoftTurquoise
                                        else -> Color(0xFF66B7FF)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = spotlight.kicker,
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.1.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = when {
                                uiState.isMicMuted -> WarningAmber
                                uiState.orbState == OrbState.LISTENING -> ArcCyan
                                else -> Color(0xFFAAD2FF)
                            },
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Dynamic AI / Voice-Driven Headline (never stuck on static text)
                    AnimatedContent(
                        targetState = spotlight.headline,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(260)) togetherWith fadeOut(animationSpec = tween(200))
                        },
                        label = "DynamicHudHeadline"
                    ) { targetHeadline ->
                        Text(
                            text = targetHeadline,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 27.sp,
                                lineHeight = 33.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.4).sp
                            ),
                            color = if (uiState.partialVoiceText.isNotBlank()) ArcCyan else Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(onClick = onCycleHudHeadline)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("home_dynamic_headline")
                        )
                    }

                    if (spotlight.subtitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = spotlight.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB4C8E6),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Live execution / reply card when actively conversing or running an action
                    val showLiveCard = uiState.partialVoiceText.isNotBlank() ||
                        uiState.streamingReply.isNotBlank() ||
                        uiState.taskProgress.stage != TaskStage.IDLE ||
                        uiState.autonomousStepStatus.isNotBlank() ||
                        uiState.pendingConfirmationSummary != null

                    AnimatedVisibility(
                        visible = showLiveCard,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0x52081B3E))
                                .border(
                                    width = 1.dp,
                                    color = Color(0x456EC1FF),
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable { onNavigate(JarvisSection.JARVIS) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when {
                                uiState.pendingConfirmationSummary != null -> {
                                    Text(
                                        text = "Confirm: ${uiState.pendingConfirmationSummary}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                uiState.partialVoiceText.isNotBlank() -> {
                                    Text(
                                        text = "\"${uiState.partialVoiceText}\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ArcCyan,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                uiState.streamingReply.isNotBlank() -> {
                                    Text(
                                        text = uiState.streamingReply,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFEAF6FF),
                                        textAlign = TextAlign.Center,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                uiState.autonomousStepStatus.isNotBlank() || uiState.taskProgress.isBusy -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = uiState.autonomousStepStatus.ifBlank {
                                                uiState.taskProgress.detail.ifBlank { uiState.taskProgress.title }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SoftTurquoise,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        OutlinedButton(
                                            onClick = onAbortAutonomous,
                                            modifier = Modifier.testTag("home_abort_autonomous_btn")
                                        ) {
                                            Text("Abort")
                                        }
                                    }
                                    if (uiState.taskProgress.isBusy) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { uiState.taskProgress.fraction.coerceIn(0.08f, 1f) },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = SoftTurquoise
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Futuristic Timed Automations Queue + Autogenerated Mission Chips when not mid-stream
                    AnimatedVisibility(
                        visible = !showLiveCard,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (activeAutomations.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Color(0x47071A3C))
                                        .border(1.dp, Color(0x406EC1FF), RoundedCornerShape(18.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("home_automations_card"),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Filled.Schedule,
                                                contentDescription = null,
                                                tint = ArcCyan,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "TIMED AUTOMATIONS",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    letterSpacing = 0.9.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = ArcCyan
                                            )
                                        }
                                        Text(
                                            text = "${activeAutomations.size} active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SoftTurquoise
                                        )
                                    }

                                    activeAutomations.take(3).forEach { task ->
                                        val timeLabel = ScheduledAutomationEngine.formatScheduleTime(task.triggerAtMs)
                                        val countdown = ScheduledAutomationEngine.formatCountdown(task.triggerAtMs)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Color(0x33123A78))
                                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = task.title,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "$timeLabel • $countdown" + if (task.repeatDaily) " • Daily" else "",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SoftTurquoise,
                                                    maxLines = 1
                                                )
                                            }
                                            IconButton(
                                                onClick = { onRunAutomationNow(task) },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("run_automation_${task.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.PlayArrow,
                                                    contentDescription = "Run ${task.title} now",
                                                    tint = ProfitEmerald,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { onDeleteAutomation(task.id) },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("delete_automation_${task.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = "Cancel ${task.title}",
                                                    tint = Color(0xFFB4C8E6),
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (futuristicSuggestions.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    futuristicSuggestions.forEach { suggestion ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(Color(0x420A2454))
                                                .border(1.dp, Color(0x456EC1FF), RoundedCornerShape(16.dp))
                                                .clickable { onTriggerSuggestion(suggestion) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                                .testTag("suggestion_${suggestion.id}")
                                        ) {
                                            Icon(
                                                imageVector = if (suggestion.isScheduleAction) {
                                                    Icons.Filled.Schedule
                                                } else {
                                                    Icons.Filled.AutoAwesome
                                                },
                                                contentDescription = null,
                                                tint = ArcCyan,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column {
                                                Text(
                                                    text = suggestion.label,
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                                    color = Color.White,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = suggestion.badge,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = SoftTurquoise,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!hasMicPermission) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Allow microphone access for automatic voice activation",
                            style = MaterialTheme.typography.bodySmall,
                            color = SoftViolet.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onRequestMicPermission() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("grant_mic_button")
                        )
                    }
                }
            }

            // Bottom Glowing Oral Voice Pill (100% voice-only, zero typing)
            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(bottom = 18.dp)
                    .heightIn(min = 58.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0x471E62D8),
                                Color(0x5E2E7EFF),
                                Color(0x471E62D8)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0x88AED8FF),
                                Color(0x40549EFF)
                            )
                        ),
                        shape = RoundedCornerShape(32.dp)
                    )
                    .clickable(onClick = triggerVoice)
                    .padding(start = 22.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)
                    .testTag("home_talk_button")
                    .semantics {
                        contentDescription = if (uiState.orbState == OrbState.LISTENING) {
                            "Stop listening"
                        } else {
                            "Speak to JARVIS"
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            !hasMicPermission -> "Allow microphone for automatic voice..."
                            uiState.isMicMuted -> "Microphone muted — tap to resume..."
                            uiState.orbState == OrbState.LISTENING -> "Listening — speak now..."
                            uiState.orbState == OrbState.SPEAKING -> "Speaking — say stop or tap..."
                            settings.wakeWordEnabled -> "Say a command or schedule e.g. \"At 8 AM brief me\"..."
                            else -> "Voice standby..."
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        color = Color(0xE0DCEAFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    VoiceWaveformGlyph(
                        color = if (uiState.orbState == OrbState.LISTENING) ArcCyan else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlowingGlassCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0x661C58BA),
                        Color(0x470B2659)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x6692C8FF),
                        Color(0x283677E0)
                    )
                ),
                shape = CircleShape
            )
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick)
    ) {
        content()
    }
}

@Composable
private fun VoiceWaveformGlyph(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val barHeights = floatArrayOf(0.28f, 0.56f, 0.92f, 0.52f, 0.28f)
        val count = barHeights.size
        val step = size.width / (count + 1f)
        val strokeW = (size.width * 0.11f).coerceAtLeast(2.dp.toPx())
        val cy = size.height / 2f
        for (i in 0 until count) {
            val x = step * (i + 1f)
            val halfH = (size.height * barHeights[i]) / 2f
            drawLine(
                color = color,
                start = Offset(x, cy - halfH),
                end = Offset(x, cy + halfH),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }
}
