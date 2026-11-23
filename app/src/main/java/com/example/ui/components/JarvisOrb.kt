package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.OrbState
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcBlue
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.OfflineGray
import com.example.ui.theme.WarningAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun JarvisOrb(
    state: OrbState,
    audioLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 210.dp
) {
    val transition = rememberInfiniteTransition(label = "jarvis_orb_anim")
    val slowRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart),
        label = "slow_rot"
    )
    val fastRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "fast_rot"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "wave"
    )

    val primaryColor = when (state) {
        OrbState.IDLE -> ElectricBlue
        OrbState.LISTENING -> ArcCyan
        OrbState.PROCESSING -> ArcBlue
        OrbState.SPEAKING -> ElectricBlue
        OrbState.ALERT -> AlertCrimson
        OrbState.OFFLINE -> OfflineGray
    }
    val secondaryColor = when (state) {
        OrbState.ALERT -> WarningAmber
        OrbState.OFFLINE -> Color(0xFF334155)
        else -> ArcCyan
    }

    val orbCd = stringResource(R.string.orb_content_description)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .testTag("jarvis_orb")
                .semantics { contentDescription = "$orbCd: ${state.label}" }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = primaryColor),
                    onClick = onClick
                )
        ) {
            Canvas(modifier = Modifier.size(size)) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val maxR = this.size.minDimension / 2f
                val levelBoost = audioLevel.coerceIn(0f, 1f)

                // Ambient radial glow
                val glowAlpha = when (state) {
                    OrbState.OFFLINE -> 0.12f
                    OrbState.LISTENING, OrbState.SPEAKING -> 0.28f + levelBoost * 0.22f
                    OrbState.ALERT -> 0.25f + pulse * 0.22f
                    else -> 0.22f + pulse * 0.08f
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primaryColor.copy(alpha = glowAlpha), Color.Transparent),
                        center = center,
                        radius = maxR
                    ),
                    radius = maxR,
                    center = center
                )

                // Outer segmented energy ring
                val outerR = maxR * 0.84f
                val outerAngle = if (state == OrbState.PROCESSING) fastRotation else slowRotation
                for (i in 0 until 4) {
                    val start = outerAngle + i * 90f
                    drawArc(
                        color = primaryColor.copy(alpha = if (state == OrbState.OFFLINE) 0.3f else 0.78f),
                        startAngle = start,
                        sweepAngle = if (state == OrbState.PROCESSING) 52f else 66f,
                        useCenter = false,
                        topLeft = Offset(center.x - outerR, center.y - outerR),
                        size = Size(outerR * 2f, outerR * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Counter-rotating inner telemetry ring
                val midR = maxR * 0.66f
                val midAngle = if (state == OrbState.PROCESSING) -fastRotation * 1.3f else -slowRotation * 0.8f
                for (i in 0 until 6) {
                    drawArc(
                        color = secondaryColor.copy(alpha = if (state == OrbState.OFFLINE) 0.2f else 0.65f),
                        startAngle = midAngle + i * 60f,
                        sweepAngle = 32f,
                        useCenter = false,
                        topLeft = Offset(center.x - midR, center.y - midR),
                        size = Size(midR * 2f, midR * 2f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // State-specific dynamic elements
                when (state) {
                    OrbState.LISTENING -> {
                        val rippleR = maxR * (0.55f + pulse * 0.35f + levelBoost * 0.08f)
                        drawCircle(
                            color = ArcCyan.copy(alpha = (1f - pulse) * 0.55f),
                            radius = rippleR,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    OrbState.PROCESSING -> {
                        val orbitR = maxR * 0.75f
                        for (i in 0 until 8) {
                            val rad = (fastRotation * 1.5f + i * 45f) * PI.toFloat() / 180f
                            val px = center.x + cos(rad) * orbitR
                            val py = center.y + sin(rad) * orbitR
                            drawCircle(
                                color = ArcCyan,
                                radius = 3.dp.toPx(),
                                center = Offset(px, py)
                            )
                        }
                    }

                    OrbState.SPEAKING -> {
                        val barCount = 24
                        val baseR = maxR * 0.48f
                        for (i in 0 until barCount) {
                            val angleRad = (i * (360f / barCount)) * PI.toFloat() / 180f
                            val wave = (sin(wavePhase + i * 0.7f) + 1f) * 0.5f
                            val barLen = maxR * (0.06f + (0.08f + levelBoost * 0.14f) * wave)
                            val start = Offset(
                                center.x + cos(angleRad) * baseR,
                                center.y + sin(angleRad) * baseR
                            )
                            val end = Offset(
                                center.x + cos(angleRad) * (baseR + barLen),
                                center.y + sin(angleRad) * (baseR + barLen)
                            )
                            drawLine(
                                color = ArcCyan.copy(alpha = 0.9f),
                                start = start,
                                end = end,
                                strokeWidth = 2.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    OrbState.ALERT -> {
                        val alertR = maxR * (0.6f + pulse * 0.34f)
                        drawCircle(
                            color = AlertCrimson.copy(alpha = 0.7f * (1f - pulse * 0.5f)),
                            radius = alertR,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }

                    else -> Unit
                }

                // Luminous Core
                val coreR = maxR * (0.34f + if (state == OrbState.LISTENING || state == OrbState.SPEAKING) levelBoost * 0.06f else pulse * 0.02f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (state == OrbState.OFFLINE) 0.25f else 0.9f),
                            primaryColor.copy(alpha = if (state == OrbState.OFFLINE) 0.2f else 0.75f),
                            primaryColor.copy(alpha = 0.08f)
                        ),
                        center = center,
                        radius = coreR
                    ),
                    radius = coreR,
                    center = center
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "J.A.R.V.I.S // ${state.label}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = primaryColor
        )
    }
}
