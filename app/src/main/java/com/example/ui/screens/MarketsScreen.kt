package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.analysis.TechnicalAnalysisEngine
import com.example.domain.model.BrokerType
import com.example.domain.model.DataFreshness
import com.example.domain.model.MarketAssetClass
import com.example.domain.model.MarketQuote
import com.example.domain.model.TradingOpportunity
import com.example.ui.JarvisUiState
import com.example.ui.components.DataFreshnessBadge
import com.example.ui.components.HudGlassCard
import com.example.ui.components.LiveMarketTickerRibbon
import com.example.ui.components.MarketChartCanvas
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun MarketsScreen(
    uiState: JarvisUiState,
    cachedTickerQuotes: List<MarketQuote>,
    onSelectAssetClass: (MarketAssetClass) -> Unit,
    onSelectSymbol: (String, String) -> Unit,
    onRefreshMarkets: () -> Unit,
    onAskJarvisExplain: (TradingOpportunity) -> Unit,
    onProposeTrade: (TradingOpportunity, BrokerType) -> Unit
) {
    val jc = LocalJarvisColors.current
    val filteredQuotes = uiState.quotes.filter { it.assetClass == uiState.selectedAssetClass }
    val selectedQuote = uiState.quotes.find { it.symbol == uiState.selectedSymbol }
    val activeOpportunity = uiState.opportunities.find {
        it.symbol == uiState.selectedSymbol && it.timeframe == uiState.selectedTimeframe
    } ?: uiState.opportunities.firstOrNull { it.symbol == uiState.selectedSymbol }

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
                    Text("MARKETS & ANALYSIS", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                    Text("Live prices, multi-timeframe technicals & AI setups", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                }
                IconButton(onClick = onRefreshMarkets, modifier = Modifier.testTag("markets_refresh_btn")) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh market quotes", tint = ElectricBlue)
                }
            }
        }

        // Live Configured Market Tickers
        item {
            LiveMarketTickerRibbon(
                quotes = cachedTickerQuotes,
                selectedSymbol = uiState.selectedSymbol,
                isRefreshing = uiState.isLoadingMarkets,
                onSelectTicker = { q -> onSelectSymbol(q.symbol, uiState.selectedTimeframe) },
                onRefresh = onRefreshMarkets
            )
        }

        // Asset Class Tabs
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MarketAssetClass.entries) { assetClass ->
                    FilterChip(
                        selected = uiState.selectedAssetClass == assetClass,
                        onClick = { onSelectAssetClass(assetClass) },
                        label = { Text(assetClass.label) }
                    )
                }
            }
        }

        // Selected Asset Chart & Timeframes
        item {
            HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.4f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${uiState.selectedSymbol} • ${selectedQuote?.displayName ?: ""}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        val priceLabel = if (selectedQuote != null && selectedQuote.price > 0.0) {
                            "${TechnicalAnalysisEngine.formatPrice(selectedQuote.price)} (${"%+.2f".format(selectedQuote.changePercent)}%)"
                        } else {
                            "Waiting for live quote..."
                        }
                        Text(
                            text = priceLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = if ((selectedQuote?.changePercent ?: 0.0) >= 0.0) ProfitEmerald else AlertCrimson
                        )
                    }
                    selectedQuote?.let { DataFreshnessBadge(it.freshness) }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("15m", "1h", "4h", "1d").forEach { tf ->
                        FilterChip(
                            selected = uiState.selectedTimeframe == tf,
                            onClick = { onSelectSymbol(uiState.selectedSymbol, tf) },
                            label = { Text(tf) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                MarketChartCanvas(
                    candles = uiState.selectedCandles,
                    indicators = activeOpportunity?.indicators
                )
                selectedQuote?.let { q ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Session: ${q.marketSession} | Hours: ${q.tradingHours} | Source: ${q.sourceName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = jc.mutedSlate
                    )
                }
            }
        }

        // 12-Point AI Trading Opportunity Analysis
        if (activeOpportunity != null) {
            item {
                val opp = activeOpportunity
                val ind = opp.indicators
                val actionColor = when (opp.suggestedAction) {
                    "BUY" -> ProfitEmerald
                    "SELL" -> AlertCrimson
                    else -> WarningAmber
                }
                HudGlassCard(borderColor = actionColor.copy(alpha = 0.55f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("12-POINT TECHNICAL SETUP", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                        StatusBadge(text = "${opp.suggestedAction} • ${opp.confidencePercent}%", color = actionColor)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "1. Asset: ${opp.symbol} (${opp.displayName})\n" +
                            "2. Direction: ${opp.marketDirection.label}   3. Suggestion: ${opp.suggestedAction}\n" +
                            "4. Entry: ${TechnicalAnalysisEngine.formatPrice(opp.suggestedEntry)}   5. Stop-Loss: ${TechnicalAnalysisEngine.formatPrice(opp.stopLoss)}\n" +
                            "6. Take-Profit: ${TechnicalAnalysisEngine.formatPrice(opp.takeProfit)}   7. R:R Ratio: 1:${opp.riskRewardRatio}\n" +
                            "8. Timeframe: ${opp.timeframe}   9. Confidence: ${opp.confidencePercent}% (${opp.confidenceLabel})",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Indicators: RSI(14)=${"%.1f".format(ind.rsi14)} | MACD Hist=${"%.5f".format(ind.macdHistogram)} | SMA20/50=${TechnicalAnalysisEngine.formatPrice(ind.sma20)}/${TechnicalAnalysisEngine.formatPrice(ind.sma50)} | BB=${TechnicalAnalysisEngine.formatPrice(ind.bbLower)}–${TechnicalAnalysisEngine.formatPrice(ind.bbUpper)} | Fib 61.8%=${TechnicalAnalysisEngine.formatPrice(ind.fibonacci.level618)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcCyan
                    )
                    if (ind.candlestickPatterns.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Candlestick Patterns: ${ind.candlestickPatterns.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = WarningAmber
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("10. Technical Reasoning: ${opp.technicalReasoning}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("11. News Context: ${opp.importantNewsContext}", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                    Spacer(Modifier.height(4.dp))
                    Text("12. Invalidation: ${opp.invalidationConditions}", style = MaterialTheme.typography.bodyMedium, color = WarningAmber)
                    Spacer(Modifier.height(6.dp))
                    Text(opp.disclaimer, style = MaterialTheme.typography.labelSmall, color = jc.mutedSlate)

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onAskJarvisExplain(opp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ask_jarvis_explain_btn")
                        ) {
                            Icon(Icons.Filled.Psychology, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Explain")
                        }
                        if (opp.suggestedAction == "BUY" || opp.suggestedAction == "SELL") {
                            Button(
                                onClick = {
                                    val broker = if (opp.symbol.endsWith("USDT")) BrokerType.BINANCE else BrokerType.DERIV
                                    onProposeTrade(opp, broker)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("propose_setup_trade_btn")
                            ) {
                                Text("Queue Trade")
                            }
                        }
                    }
                }
            }
        }

        // Instrument List
        item {
            Text("${uiState.selectedAssetClass.label.uppercase()} INSTRUMENTS", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
        }

        items(filteredQuotes, key = { it.symbol }) { q ->
            val pctColor = when {
                q.freshness == DataFreshness.UNAVAILABLE -> jc.mutedSlate
                q.changePercent >= 0.0 -> ProfitEmerald
                else -> AlertCrimson
            }
            HudGlassCard(
                modifier = Modifier.clickable { onSelectSymbol(q.symbol, uiState.selectedTimeframe) },
                contentPadding = PaddingValues(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            DataFreshnessBadge(q.freshness)
                        }
                        Text(q.displayName, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                        Text(
                            text = "${q.volatilityLabel} • ${q.sourceName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = jc.mutedSlate
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (q.price > 0.0) TechnicalAnalysisEngine.formatPrice(q.price) else "N/A",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (q.price > 0.0) "${"%+.2f".format(q.changePercent)}%" else "Unavailable",
                            style = MaterialTheme.typography.labelMedium,
                            color = pctColor
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
