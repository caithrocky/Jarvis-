package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.DataFreshness
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.OfflineGray
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun HudGlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = LocalJarvisColors.current.hairlineGlow,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val jc = LocalJarvisColors.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = jc.glassSurface,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            jc.electricBlue.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content
            )
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun DataFreshnessBadge(freshness: DataFreshness, modifier: Modifier = Modifier) {
    val color = when (freshness) {
        DataFreshness.LIVE -> ProfitEmerald
        DataFreshness.DELAYED -> WarningAmber
        DataFreshness.UNAVAILABLE -> OfflineGray
    }
    StatusBadge(text = freshness.label, color = color, modifier = modifier)
}

@Composable
fun EmergencyStopButton(
    isStopped: Boolean,
    onToggleEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if (isStopped) WarningAmber else AlertCrimson
    OutlinedButton(
        onClick = onToggleEmergencyStop,
        modifier = modifier.testTag("emergency_stop_button"),
        border = BorderStroke(1.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = color.copy(alpha = 0.14f),
            contentColor = color
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Emergency stop trading",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isStopped) "TRADING HALTED — RESUME" else "EMERGENCY STOP",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
