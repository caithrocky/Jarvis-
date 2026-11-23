package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.NewsItem
import com.example.ui.JarvisUiState
import com.example.ui.components.HudGlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun NewsScreen(
    uiState: JarvisUiState,
    onRefreshNews: () -> Unit,
    onSummarizeWithJarvis: (NewsItem) -> Unit
) {
    val context = LocalContext.current
    val jc = LocalJarvisColors.current
    var showCalendar by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("FINANCIAL NEWS & MACRO", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                    Text("Live RSS feeds & economic calendar", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                }
                IconButton(onClick = onRefreshNews, modifier = Modifier.testTag("refresh_news_btn")) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh news and calendar", tint = ElectricBlue)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showCalendar,
                    onClick = { showCalendar = false },
                    label = { Text("Live News (${uiState.newsItems.size})") }
                )
                FilterChip(
                    selected = showCalendar,
                    onClick = { showCalendar = true },
                    label = { Text("Economic Calendar (${uiState.economicEvents.size})") }
                )
            }
        }

        if (!showCalendar) {
            uiState.newsError?.let { err ->
                item {
                    HudGlassCard(borderColor = WarningAmber) {
                        Text(err, style = MaterialTheme.typography.bodyMedium, color = WarningAmber)
                    }
                }
            }

            if (uiState.newsItems.isEmpty() && uiState.newsError == null) {
                item {
                    HudGlassCard {
                        Text(
                            text = if (uiState.isLoadingNews) "Loading live financial headlines..." else "No news articles loaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = jc.mutedSlate
                        )
                    }
                }
            }

            items(uiState.newsItems, key = { it.id }) { item ->
                val impactColor = when (item.impactLevel) {
                    "HIGH" -> AlertCrimson
                    "MEDIUM" -> WarningAmber
                    else -> ProfitEmerald
                }
                HudGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${item.source} • ${item.publishedLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ArcCyan,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(text = "${item.impactLevel} IMPACT", color = impactColor)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(item.summary, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Assets: ${item.affectedAssets.joinToString(", ")} — ${item.marketImplication}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricBlue
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onSummarizeWithJarvis(item) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Ask JARVIS")
                        }
                        if (item.url.startsWith("https://") || item.url.startsWith("http://")) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Source")
                            }
                        }
                    }
                }
            }
        } else {
            uiState.calendarNotice?.let { notice ->
                item {
                    HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.45f)) {
                        Text(notice, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                    }
                }
            }
            items(uiState.economicEvents, key = { it.id }) { ev ->
                val color = when (ev.impact) {
                    "HIGH" -> AlertCrimson
                    "MEDIUM" -> WarningAmber
                    else -> ProfitEmerald
                }
                HudGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${ev.currency} • ${ev.title}", style = MaterialTheme.typography.titleMedium)
                            Text(ev.scheduledTimeLabel, style = MaterialTheme.typography.labelSmall, color = jc.mutedSlate)
                        }
                        StatusBadge(text = ev.impact, color = color)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Actual: ${ev.actual ?: "--"} | Forecast: ${ev.forecast ?: "--"} | Previous: ${ev.previous ?: "--"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcCyan
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
