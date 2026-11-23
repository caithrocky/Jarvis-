package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.data.local.AiActivityLogEntity
import com.example.data.local.OrderAuditEntity
import com.example.ui.components.HudGlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    orderAudits: List<OrderAuditEntity>,
    aiLogs: List<AiActivityLogEntity>
) {
    val jc = LocalJarvisColors.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val fmt = remember { SimpleDateFormat("MMM dd HH:mm:ss", Locale.US) }

    val executedCount = orderAudits.count { it.status == "EXECUTED" }
    val blockedCount = orderAudits.count { it.status == "BLOCKED" }
    val failedCount = orderAudits.count { it.status == "FAILED" }
    val totalNotional = orderAudits.filter { it.status == "EXECUTED" }.sumOf { it.amountUsd }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("HISTORY & ANALYTICS", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text("Order audit trail, AI telemetry & performance summary", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
            }
        }

        // Performance Analytics Summary
        item {
            HudGlassCard {
                Text("EXECUTION & RISK ANALYTICS", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricBlock("Executed", executedCount.toString(), ProfitEmerald)
                    MetricBlock("Blocked", blockedCount.toString(), WarningAmber)
                    MetricBlock("Failed", failedCount.toString(), AlertCrimson)
                    MetricBlock("Volume", "$${"%.0f".format(totalNotional)}", ElectricBlue)
                }

                if (orderAudits.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    val cumulative = mutableListOf<Double>()
                    var running = 0.0
                    orderAudits.reversed().forEach { audit ->
                        if (audit.status == "EXECUTED") running += audit.amountUsd
                        cumulative.add(running)
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    ) {
                        val maxV = (cumulative.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
                        val step = if (cumulative.size > 1) size.width / (cumulative.size - 1) else size.width
                        val path = Path()
                        cumulative.forEachIndexed { i, v ->
                            val x = i * step
                            val y = size.height - ((v / maxV).toFloat() * (size.height * 0.8f) + size.height * 0.1f)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        if (cumulative.size == 1) {
                            drawCircle(ElectricBlue, radius = 4.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
                        } else {
                            drawPath(path, color = ElectricBlue, style = Stroke(width = 2.5.dp.toPx()))
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Order Audit (${orderAudits.size})") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("AI Activity Log (${aiLogs.size})") }
                )
            }
        }

        if (selectedTab == 0) {
            if (orderAudits.isEmpty()) {
                item {
                    HudGlassCard {
                        Text("No trading orders have been submitted yet.", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                    }
                }
            }
            items(orderAudits, key = { it.id }) { audit ->
                val statusColor = when (audit.status) {
                    "EXECUTED" -> ProfitEmerald
                    "BLOCKED" -> WarningAmber
                    else -> AlertCrimson
                }
                HudGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${audit.brokerName} • ${audit.side} ${audit.symbol}", style = MaterialTheme.typography.titleMedium)
                        StatusBadge(text = audit.status, color = statusColor)
                    }
                    Text(
                        text = "$${"%.2f".format(audit.amountUsd)} • ${audit.environment} • ${audit.operatingMode} • ${fmt.format(Date(audit.timestampMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcCyan
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(audit.resultMessage, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                }
            }
        } else {
            if (aiLogs.isEmpty()) {
                item {
                    HudGlassCard {
                        Text("No AI telemetry events recorded yet.", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                    }
                }
            }
            items(aiLogs, key = { it.id }) { log ->
                HudGlassCard(contentPadding = PaddingValues(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(log.actionType, style = MaterialTheme.typography.labelMedium, color = ElectricBlue)
                        Text(fmt.format(Date(log.timestampMs)), style = MaterialTheme.typography.labelSmall, color = jc.mutedSlate)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(log.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun MetricBlock(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalJarvisColors.current.mutedSlate)
    }
}
