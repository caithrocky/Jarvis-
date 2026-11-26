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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.SoftTurquoise
import com.example.ui.theme.SoftViolet
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
    size: Dp = 200.dp,
    executionProgress: Float = 0.55f,
    showStateLabel: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "jarvis_orb_anim")
    val slowRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart),
        label = "slow_rot"
    )
    val mediumRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "med_rot"
    )
    val fastRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "fast_rot"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "wave"
    )

    val primaryColor = when (state) {
        OrbState.IDLE -> Color(0xFF3B9EFF)
        OrbState.LISTENING -> ArcCyan
        OrbState.UNDERSTANDING -> SoftViolet
        OrbState.PROCESSING -> Color(0xFF54B6FF)
        OrbState.SPEAKING -> Color(0xFF60C8FF)
        OrbState.EXECUTING -> SoftTurquoise
        OrbState.SUCCESS -> ProfitEmerald
        OrbState.ERROR -> WarningAmber
        OrbState.ALERT -> AlertCrimson
        OrbState.OFFLINE -> OfflineGray
    }
    val secondaryColor = when (state) {
        OrbState.ERROR -> AlertCrimson
        OrbState.ALERT -> WarningAmber
        OrbState.SUCCESS -> ArcCyan
        OrbState.OFFLINE -> Color(0xFF283548)
        OrbState.PROCESSING, OrbState.UNDERSTANDING -> SoftViolet
        else -> Color(0xFF1A5CFF)
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
                val sphereR = maxR * (0.68f + if (state == OrbState.LISTENING || state == OrbState.SPEAKING) levelBoost * 0.045f else pulse * 0.015f)

                // Deep atmospheric radial halo around the sphere
                val auraAlpha = when (state) {
                    OrbState.OFFLINE -> 0.12f
                    OrbState.LISTENING, OrbState.SPEAKING -> 0.36f + levelBoost * 0.20f
                    OrbState.SUCCESS -> 0.34f
                    OrbState.ERROR, OrbState.ALERT -> 0.28f + pulse * 0.14f
                    else -> 0.24f + pulse * 0.08f
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = auraAlpha),
                            secondaryColor.copy(alpha = auraAlpha * 0.45f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxR
                    ),
                    radius = maxR,
                    center = center
                )

                // Dark obsidian-sapphire interior sphere body
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0A2458).copy(alpha = if (state == OrbState.OFFLINE) 0.45f else 0.92f),
                            Color(0xFF040D24),
                            Color(0xFF01040D)
                        ),
                        center = Offset(center.x - sphereR * 0.24f, center.y - sphereR * 0.26f),
                        radius = sphereR * 1.18f
                    ),
                    radius = sphereR,
                    center = center
                )

                // Swirling translucent fluid ribbons clipped cleanly inside the sphere
                val sphereClip = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            center.x - sphereR,
                            center.y - sphereR,
                            center.x + sphereR,
                            center.y + sphereR
                        )
                    )
                }
                val rotDeg = when (state) {
                    OrbState.PROCESSING -> fastRotation
                    OrbState.UNDERSTANDING, OrbState.EXECUTING, OrbState.SPEAKING -> mediumRotation
                    else -> slowRotation
                }
                val rotRad = rotDeg * (PI.toFloat() / 180f)

                clipPath(sphereClip) {
                    // Flowing luminous fluid ribbon 1
                    val ribbon1 = Path().apply {
                        moveTo(center.x - sphereR * 1.05f, center.y + sphereR * 0.12f)
                        cubicTo(
                            center.x - sphereR * 0.30f + cos(rotRad) * sphereR * 0.25f,
                            center.y + sphereR * 0.72f + sin(wavePhase) * sphereR * 0.12f,
                            center.x + sphereR * 0.25f,
                            center.y - sphereR * 0.35f + cos(wavePhase) * sphereR * 0.14f,
                            center.x + sphereR * 1.02f,
                            center.y - sphereR * 0.10f
                        )
                        cubicTo(
                            center.x + sphereR * 0.55f,
                            center.y + sphereR * 0.48f,
                            center.x - sphereR * 0.15f,
                            center.y + sphereR * 0.92f,
                            center.x - sphereR * 1.05f,
                            center.y + sphereR * 0.12f
                        )
                        close()
                    }
                    drawPath(
                        path = ribbon1,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                secondaryColor.copy(alpha = 0.24f),
                                primaryColor.copy(alpha = 0.62f),
                                Color(0xFFBDE8FF).copy(alpha = 0.88f)
                            ),
                            start = Offset(center.x - sphereR, center.y + sphereR * 0.4f),
                            end = Offset(center.x + sphereR, center.y - sphereR * 0.2f)
                        )
                    )

                    // Flowing luminous ribbon 2 (upper veil)
                    val ribbon2 = Path().apply {
                        moveTo(center.x - sphereR * 0.95f, center.y - sphereR * 0.30f)
                        cubicTo(
                            center.x - sphereR * 0.10f,
                            center.y - sphereR * 0.88f + sin(rotRad) * sphereR * 0.10f,
                            center.x + sphereR * 0.45f,
                            center.y - sphereR * 0.48f,
                            center.x + sphereR * 0.96f,
                            center.y + sphereR * 0.22f
                        )
                        cubicTo(
                            center.x + sphereR * 0.48f,
                            center.y - sphereR * 0.25f,
                            center.x - sphereR * 0.22f,
                            center.y - sphereR * 0.62f,
                            center.x - sphereR * 0.95f,
                            center.y - sphereR * 0.30f
                        )
                        close()
                    }
                    drawPath(
                        path = ribbon2,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.18f),
                                Color(0xFF7AD3FF).copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0.62f)
                            ),
                            start = Offset(center.x - sphereR, center.y - sphereR),
                            end = Offset(center.x + sphereR, center.y)
                        )
                    )

                    // Bright right-edge crescent caustic glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (state == OrbState.OFFLINE) 0.15f else 0.90f),
                                primaryColor.copy(alpha = 0.65f),
                                Color.Transparent
                            ),
                            center = Offset(center.x + sphereR * 0.72f, center.y + sphereR * 0.10f),
                            radius = sphereR * 0.62f
                        ),
                        radius = sphereR * 0.62f,
                        center = Offset(center.x + sphereR * 0.72f, center.y + sphereR * 0.10f)
                    )
                }

                // Crisp luminous glass rim
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (state == OrbState.OFFLINE) 0.18f else 0.88f),
                            primaryColor.copy(alpha = 0.58f),
                            secondaryColor.copy(alpha = 0.22f),
                            primaryColor.copy(alpha = 0.48f),
                            Color.White.copy(alpha = if (state == OrbState.OFFLINE) 0.18f else 0.88f)
                        ),
                        center = center
                    ),
                    radius = sphereR,
                    center = center,
                    style = Stroke(width = 1.9.dp.toPx())
                )

                // Active state reactive outer ring overlays (subtle & purposeful)
                when (state) {
                    OrbState.LISTENING -> {
                        val rippleR = sphereR + maxR * (0.06f + pulse * 0.14f + levelBoost * 0.08f)
                        drawCircle(
                            color = ArcCyan.copy(alpha = (1f - pulse) * 0.52f),
                            radius = rippleR,
                            center = center,
                            style = Stroke(width = 1.8.dp.toPx())
                        )
                    }

                    OrbState.SPEAKING -> {
                        val barCount = 24
                        val baseR = sphereR + maxR * 0.05f
                        for (i in 0 until barCount) {
                            val angleRad = (i * (360f / barCount)) * PI.toFloat() / 180f
                            val wave = (sin(wavePhase + i * 0.7f) + 1f) * 0.5f
                            val barLen = maxR * (0.03f + (0.05f + levelBoost * 0.12f) * wave)
                            val start = Offset(center.x + cos(angleRad) * baseR, center.y + sin(angleRad) * baseR)
                            val end = Offset(center.x + cos(angleRad) * (baseR + barLen), center.y + sin(angleRad) * (baseR + barLen))
                            drawLine(
                                color = Color(0xFF8BE2FF).copy(alpha = 0.82f),
                                start = start,
                                end = end,
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    OrbState.UNDERSTANDING, OrbState.PROCESSING -> {
                        val orbitR = sphereR + maxR * 0.09f
                        for (i in 0 until 3) {
                            drawArc(
                                color = primaryColor.copy(alpha = 0.75f),
                                startAngle = rotDeg + i * 120f,
                                sweepAngle = 48f,
                                useCenter = false,
                                topLeft = Offset(center.x - orbitR, center.y - orbitR),
                                size = Size(orbitR * 2f, orbitR * 2f),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }

                    OrbState.EXECUTING -> {
                        val progR = sphereR + maxR * 0.08f
                        drawArc(
                            color = SoftTurquoise,
                            startAngle = -90f + mediumRotation * 0.5f,
                            sweepAngle = executionProgress.coerceIn(0.15f, 0.95f) * 360f,
                            useCenter = false,
                            topLeft = Offset(center.x - progR, center.y - progR),
                            size = Size(progR * 2f, progR * 2f),
                            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    OrbState.SUCCESS -> {
                        drawCircle(
                            color = ProfitEmerald.copy(alpha = 0.72f),
                            radius = sphereR + maxR * 0.07f,
                            center = center,
                            style = Stroke(width = 2.4.dp.toPx())
                        )
                    }

                    OrbState.ERROR, OrbState.ALERT -> {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.60f + pulse * 0.22f),
                            radius = sphereR + maxR * 0.07f,
                            center = center,
                            style = Stroke(width = 2.2.dp.toPx())
                        )
                    }

                    OrbState.IDLE, OrbState.OFFLINE -> Unit
                }
            }
        }

        if (showStateLabel) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "J.A.R.V.I.S // ${state.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = primaryColor
            )
        }
    }
}
