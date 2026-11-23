package com.example.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.analysis.TechnicalAnalysisEngine
import com.example.data.local.AlertNotificationEntity
import com.example.data.local.JarvisUserSettings
import com.example.domain.model.MarketQuote
import com.example.ui.JarvisSection
import com.example.ui.JarvisUiState
import com.example.ui.components.EmergencyStopButton
import com.example.ui.components.HudGlassCard
import com.example.ui.components.JarvisOrb
import com.example.ui.components.LiveMarketTickerRibbon
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun HomeDashboardScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    cachedTickerQuotes: List<MarketQuote>,
    alerts: List<AlertNotificationEntity>,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onOrbTap: () -> Unit,
    onToggleAlwaysOn: (Boolean) -> Unit,
    onQuickVoicePrompt: (String) -> Unit,
    onNavigate: (JarvisSection) -> Unit,
    onRefreshMarkets: () -> Unit,
    onToggleEmergencyStop: () -> Unit,
    onSelectMarket: (String) -> Unit
) {
    val jc = LocalJarvisColors.current
    val tickerQuotes = cachedTickerQuotes.ifEmpty {
        uiState.quotes.filter { settings.watchlistSymbols.contains(it.symbol) }
            .ifEmpty { uiState.quotes.take(6) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.img_jarvis_hud_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.24f)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Telemetry Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "JARVIS COMMAND",
                            style = MaterialTheme.typography.headlineMedium,
                            color = jc.electricBlue
                        )
                        Text(
                            text = "Personal Financial Intelligence",
                            style = MaterialTheme.typography.bodyMedium,
                            color = jc.mutedSlate
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (uiState.bluetoothAudioReady) {
                            Icon(
                                imageVector = Icons.Filled.BluetoothAudio,
                                contentDescription = "Bluetooth audio available",
                                tint = ArcCyan,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                        IconButton(
                            onClick = onRefreshMarkets,
                            modifier = Modifier.testTag("home_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh markets",
                                tint = ElectricBlue
                            )
                        }
                    }
                }
            }

            // Central AI Orb & Voice Activation Card
            item {
                HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.45f)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        JarvisOrb(
                            state = uiState.orbState,
                            audioLevel = uiState.audioLevel,
                            onClick = {
                                if (!hasMicPermission) onRequestMicPermission() else onOrbTap()
                            }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (uiState.partialVoiceText.isNotBlank()) {
                                "\"${uiState.partialVoiceText}\""
                            } else {
                                uiState.voiceStatusDetail
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArcCyan
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (settings.wakeWordEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                                    contentDescription = "Wake word microphone",
                                    tint = if (settings.wakeWordEnabled) ProfitEmerald else jc.mutedSlate,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Always-On \"Hey JARVIS\"",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = if (settings.wakeWordEnabled) "Foreground mic listener active" else "Tap to enable background wake-word",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = jc.mutedSlate
                                    )
                                }
                            }
                            Switch(
                                checked = settings.wakeWordEnabled,
                                onCheckedChange = { wantEnable ->
                                    if (wantEnable && !hasMicPermission) {
                                        onRequestMicPermission()
                                    } else {
                                        onToggleAlwaysOn(wantEnable)
                                    }
                                },
                                modifier = Modifier.testTag("home_always_on_switch")
                            )
                        }

                        if (!hasMicPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onRequestMicPermission,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("grant_mic_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                            ) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Enable Voice & Microphone Access")
                            }
                        }
                    }
                }
            }

            // Quick Spoken/AI Briefing Commands
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val quickPrompts = listOf(
                        "What's happening in the markets?",
                        "Analyze EUR/USD",
                        "Check my Deriv account",
                        "Find trading opportunities",
                        "What about Gold?"
                    )
                    items(quickPrompts) { prompt ->
                        AssistChip(
                            onClick = { onQuickVoicePrompt(prompt) },
                            label = { Text(prompt, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }

            // Operating Mode + Account Balance + Emergency Stop
            item {
                HudGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = ElectricBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = settings.operatingMode.title.uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ElectricBlue
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            val connectedAccs = uiState.accounts.filter { it.isConnected }
                            val totalSummary = if (connectedAccs.isEmpty()) {
                                "No live/demo broker linked"
                            } else {
                                connectedAccs.joinToString(" • ") { a ->
                                    "${a.brokerType.displayName} (${a.environment.label}): ${a.currency} ${"%.2f".format(a.balance ?: 0.0)}"
                                }
                            }
                            Text(
                                text = totalSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = jc.mutedSlate
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(
                            text = "${uiState.openPositions.size} OPEN",
                            color = if (uiState.openPositions.isNotEmpty()) ArcCyan else jc.mutedSlate
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmergencyStopButton(
                            isStopped = settings.riskLimits.emergencyStopActive,
                            onToggleEmergencyStop = onToggleEmergencyStop
                        )
                        AssistChip(
                            onClick = { onNavigate(JarvisSection.ACCOUNTS) },
                            label = { Text("Accounts") }
                        )
                    }
                }
            }

            // Live Market Ticker Ribbon (Room-cached & live synced)
            item {
                LiveMarketTickerRibbon(
                    quotes = tickerQuotes,
                    selectedSymbol = uiState.selectedSymbol,
                    isRefreshing = uiState.isLoadingMarkets,
                    onSelectTicker = { quote ->
                        onSelectMarket(quote.symbol)
                        onNavigate(JarvisSection.MARKETS)
                    },
                    onRefresh = onRefreshMarkets
                )
            }

            // Top AI Opportunities
            if (uiState.opportunities.isNotEmpty()) {
                item {
                    Text(
                        text = "ACTIVE TECHNICAL SIGNALS",
                        style = MaterialTheme.typography.titleMedium,
                        color = ArcCyan
                    )
                }
                items(uiState.opportunities.take(3), key = { it.id }) { opp ->
                    HudGlassCard(
                        modifier = Modifier.clickable {
                            onSelectMarket(opp.symbol)
                            onNavigate(JarvisSection.MARKETS)
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = null,
                                    tint = ElectricBlue
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("${opp.symbol} (${opp.timeframe})", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "Entry ${TechnicalAnalysisEngine.formatPrice(opp.suggestedEntry)} • R:R 1:${opp.riskRewardRatio}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = jc.mutedSlate
                                    )
                                }
                            }
                            val badgeColor = when (opp.suggestedAction) {
                                "BUY" -> ProfitEmerald
                                "SELL" -> AlertCrimson
                                else -> WarningAmber
                            }
                            StatusBadge(text = "${opp.suggestedAction} ${opp.confidencePercent}%", color = badgeColor)
                        }
                    }
                }
            }

            // Recent Alerts
            if (alerts.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RECENT ALERTS",
                            style = MaterialTheme.typography.titleMedium,
                            color = ArcCyan
                        )
                        Text(
                            text = "Automation",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricBlue,
                            modifier = Modifier
                                .clickable { onNavigate(JarvisSection.AUTOMATION) }
                                .padding(8.dp)
                        )
                    }
                }
                items(alerts.take(3), key = { it.id }) { alert ->
                    HudGlassCard(contentPadding = PaddingValues(12.dp)) {
                        Text(alert.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = alert.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = jc.mutedSlate,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

