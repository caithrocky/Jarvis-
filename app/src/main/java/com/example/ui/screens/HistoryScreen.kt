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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.local.AiActivityLogEntity
import com.example.ui.components.HudGlassCard
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    aiLogs: List<AiActivityLogEntity>
) {
    val jc = LocalJarvisColors.current
    val fmt = remember { SimpleDateFormat("MMM dd HH:mm:ss", Locale.US) }

    val webAndChromeCount = aiLogs.count {
        it.actionType.contains("URL", ignoreCase = true) ||
            it.actionType.contains("SEARCH", ignoreCase = true) ||
            it.actionType.contains("WEB", ignoreCase = true)
    }
    val pluginAndScreenCount = aiLogs.count {
        it.actionType.contains("SIGN", ignoreCase = true) ||
            it.actionType.contains("GUIDE", ignoreCase = true) ||
            it.actionType.contains("SCREEN", ignoreCase = true) ||
            it.actionType.contains("TYPE", ignoreCase = true) ||
            it.actionType.contains("ORB", ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("JARVIS & PLUGIN ACTIVITY", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text(
                    "Recent Chrome navigation, web lookups, screen guidance & voice actions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = jc.mutedSlate
                )
            }
        }

        item {
            HudGlassCard {
                Text("ACTIVITY SUMMARY", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricBlock("Total Events", aiLogs.size.toString(), ElectricBlue)
                    MetricBlock("Chrome & Web", webAndChromeCount.toString(), ArcCyan)
                    MetricBlock("Screen Plugin", pluginAndScreenCount.toString(), ProfitEmerald)
                }
            }
        }

        if (aiLogs.isEmpty()) {
            item {
                HudGlassCard {
                    Text(
                        text = "No JARVIS activity yet. Ask JARVIS to open a website in Chrome, look something up online, or guide you through a screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = jc.mutedSlate
                    )
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
                Spacer(Modifier.height(4.dp))
                Text(log.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun MetricBlock(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalJarvisColors.current.mutedSlate)
    }
}
