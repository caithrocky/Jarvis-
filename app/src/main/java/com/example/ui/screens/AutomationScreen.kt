package com.example.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.AlertNotificationEntity
import com.example.data.local.MonitoringTaskEntity
import com.example.ui.components.HudGlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun AutomationScreen(
    tasks: List<MonitoringTaskEntity>,
    alerts: List<AlertNotificationEntity>,
    onCreateTask: (String, String, String, Double) -> Unit,
    onToggleTask: (Long, Boolean) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onMarkAlertsRead: () -> Unit,
    onClearAlerts: () -> Unit
) {
    val jc = LocalJarvisColors.current
    var symbol by remember { mutableStateOf("BTC/USDT") }
    var ruleType by remember { mutableStateOf("PCT_MOVE") }
    var threshold by remember { mutableStateOf("3.0") }

    val quickSymbols = listOf("EUR/USD", "XAU/USD", "BTC/USDT", "ETH/USDT", "SOL/USDT")
    val rules = listOf(
        "PCT_MOVE" to "% Move",
        "PRICE_ABOVE" to "Above",
        "PRICE_BELOW" to "Below",
        "MORNING_BRIEF" to "Daily Brief"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("AUTONOMOUS MONITORING", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text("Scheduled background surveillance & alert dispatch", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
            }
        }

        // Create Monitoring Task Card
        item {
            HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.4f)) {
                Text("NEW SURVEILLANCE RULE", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    quickSymbols.forEach { sym ->
                        FilterChip(
                            selected = symbol == sym,
                            onClick = { symbol = sym },
                            label = { Text(sym.substringBefore("/")) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rules.forEach { (code, label) ->
                        FilterChip(
                            selected = ruleType == code,
                            onClick = { ruleType = code },
                            label = { Text(label) }
                        )
                    }
                }
                if (ruleType != "MORNING_BRIEF") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = threshold,
                        onValueChange = { threshold = it },
                        label = {
                            Text(if (ruleType == "PCT_MOVE") "Movement Threshold (%)" else "Target Price")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val valNum = threshold.toDoubleOrNull() ?: 3.0
                        val title = when (ruleType) {
                            "PCT_MOVE" -> "Alert when $symbol moves $valNum%"
                            "PRICE_ABOVE" -> "Alert when $symbol rises above $valNum"
                            "PRICE_BELOW" -> "Alert when $symbol drops below $valNum"
                            else -> "Morning market briefing ($symbol & majors)"
                        }
                        onCreateTask(title, symbol, ruleType, valNum)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_monitor_task_btn")
                ) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                    Text("  Activate Monitoring Rule")
                }
            }
        }

        item {
            Text("ACTIVE & PAUSED TASKS (${tasks.size})", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
        }
        if (tasks.isEmpty()) {
            item {
                HudGlassCard {
                    Text(
                        text = "No autonomous monitors configured. Add a rule above or ask JARVIS to watch an asset.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = jc.mutedSlate
                    )
                }
            }
        }
        items(tasks, key = { it.id }) { task ->
            HudGlassCard(contentPadding = PaddingValues(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text("${task.symbol} • ${task.ruleType}", style = MaterialTheme.typography.labelSmall, color = ArcCyan)
                    }
                    Switch(
                        checked = task.isActive,
                        onCheckedChange = { onToggleTask(task.id, it) }
                    )
                    IconButton(onClick = { onDeleteTask(task.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete monitoring task", tint = AlertCrimson)
                    }
                }
            }
        }

        // Notification Center
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ALERT LOG (${alerts.size})", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMarkAlertsRead) { Text("Mark Read") }
                    OutlinedButton(onClick = onClearAlerts) { Text("Clear") }
                }
            }
        }
        items(alerts, key = { it.id }) { alert ->
            val sevColor = when (alert.severity) {
                "CRITICAL" -> AlertCrimson
                "WARNING" -> WarningAmber
                else -> ProfitEmerald
            }
            HudGlassCard(contentPadding = PaddingValues(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(alert.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusBadge(text = alert.category, color = sevColor)
                }
                Spacer(Modifier.height(4.dp))
                Text(alert.message, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
