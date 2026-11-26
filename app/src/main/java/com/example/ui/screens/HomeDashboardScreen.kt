package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.local.JarvisUserSettings
import com.example.domain.model.OrbState
import com.example.domain.model.TaskStage
import com.example.ui.JarvisSection
import com.example.ui.JarvisUiState
import com.example.ui.components.JarvisOrb
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.SoftTurquoise
import com.example.ui.theme.SoftViolet
import com.example.ui.theme.WarningAmber

@Composable
fun HomeDashboardScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOpenMenu: () -> Unit,
    onOrbTap: () -> Unit,
    onNavigate: (JarvisSection) -> Unit,
    onAbortAutonomous: () -> Unit
) {
    val triggerVoice = {
        if (!hasMicPermission) onRequestMicPermission() else onOrbTap()
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
            // Top bar: Hamburger button (all other features) on left, Settings button on right
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

            // Centered Orb + Greeting + Headline
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.widthIn(max = 520.dp)
                ) {
                    JarvisOrb(
                        state = uiState.orbState,
                        audioLevel = uiState.audioLevel,
                        onClick = triggerVoice,
                        size = 212.dp,
                        executionProgress = uiState.taskProgress.fraction,
                        showStateLabel = false
                    )

                    Spacer(modifier = Modifier.height(22.dp))

                    Text(
                        text = when {
                            uiState.isMicMuted -> "Hello sir • Mic muted"
                            uiState.orbState == OrbState.LISTENING -> "Listening..."
                            uiState.orbState == OrbState.UNDERSTANDING ||
                                uiState.orbState == OrbState.PROCESSING -> "Thinking..."
                            uiState.orbState == OrbState.SPEAKING -> "Speaking..."
                            uiState.orbState == OrbState.EXECUTING -> "On it, sir..."
                            settings.wakeWordEnabled && hasMicPermission -> "Always listening • Hello sir"
                            else -> "Hello sir"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.3.sp
                        ),
                        color = when {
                            uiState.isMicMuted -> WarningAmber
                            uiState.orbState == OrbState.LISTENING -> ArcCyan
                            else -> Color(0xFFB4C4DC)
                        },
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "How can I help\nyou today?",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = 31.sp,
                            lineHeight = 38.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.4).sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    // Subtle live status strip ONLY when voice/task/reply is actively happening
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
                                .padding(top = 18.dp)
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

                    if (!hasMicPermission) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Tap the orb or voice button to allow microphone access",
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
                            uiState.orbState == OrbState.SPEAKING -> "Speaking — tap to interrupt..."
                            settings.wakeWordEnabled -> "Always listening — say \"Hey Jarvis\"..."
                            else -> "Voice standby — tap to listen..."
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
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
