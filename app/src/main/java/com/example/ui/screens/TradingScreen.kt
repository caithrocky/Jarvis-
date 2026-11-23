package com.example.ui.screens

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import com.example.analysis.TechnicalAnalysisEngine
import com.example.data.local.JarvisUserSettings
import com.example.domain.model.BrokerType
import com.example.domain.model.RiskLimits
import com.example.domain.model.TradeProposal
import com.example.domain.model.TradingOperatingMode
import com.example.ui.JarvisUiState
import com.example.ui.components.EmergencyStopButton
import com.example.ui.components.HudGlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun TradingScreen(
    uiState: JarvisUiState,
    settings: JarvisUserSettings,
    onSelectMode: (TradingOperatingMode) -> Unit,
    onAuthorizeAutomated: (Boolean) -> Unit,
    onToggleEmergencyStop: () -> Unit,
    onUpdateRiskLimits: (RiskLimits) -> Unit,
    onApproveProposal: (TradeProposal) -> Unit,
    onRejectProposal: (String) -> Unit,
    onSubmitManualProposal: (BrokerType, String, String, Double, Double, Double, Double) -> Unit
) {
    val jc = LocalJarvisColors.current
    var maxTradeText by remember(settings.riskLimits.maxTradeAmountUsd) {
        mutableStateOf(settings.riskLimits.maxTradeAmountUsd.toString())
    }
    var maxLossText by remember(settings.riskLimits.maxDailyLossUsd) {
        mutableStateOf(settings.riskLimits.maxDailyLossUsd.toString())
    }
    var maxPosText by remember(settings.riskLimits.maxOpenPositions) {
        mutableStateOf(settings.riskLimits.maxOpenPositions.toString())
    }

    var ticketBroker by remember { mutableStateOf(BrokerType.DERIV) }
    var ticketSide by remember { mutableStateOf("BUY") }
    var ticketAmount by remember { mutableStateOf("25.0") }

    val activeQuote = uiState.quotes.find { it.symbol == uiState.selectedSymbol }
    val activePrice = activeQuote?.price?.takeIf { it > 0.0 } ?: 1.0

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
                Column(modifier = Modifier.weight(1f)) {
                    Text("TRADING COMMAND", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                    Text("Operating modes, risk guardrails & order execution", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                }
                EmergencyStopButton(
                    isStopped = settings.riskLimits.emergencyStopActive,
                    onToggleEmergencyStop = onToggleEmergencyStop
                )
            }
        }

        // Three Operating Modes
        item {
            HudGlassCard(borderColor = ElectricBlue.copy(alpha = 0.45f)) {
                Text("OPERATING MODE", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                TradingOperatingMode.entries.forEach { mode ->
                    val selected = settings.operatingMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mode_${mode.name.lowercase()}"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selected) ElectricBlue else MaterialTheme.colorScheme.onSurface
                            )
                            Text(mode.subtitle, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectMode(mode) },
                            label = { Text(if (selected) "ACTIVE" else "SELECT") }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Authorize Automated Trading", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Disabled by default. Requires explicit authorization & risk limits.",
                            style = MaterialTheme.typography.labelSmall,
                            color = WarningAmber
                        )
                    }
                    Switch(
                        checked = settings.riskLimits.automatedTradingAuthorized,
                        onCheckedChange = onAuthorizeAutomated,
                        modifier = Modifier.testTag("authorize_automated_switch")
                    )
                }
            }
        }

        // Assistant Mode Approval Queue
        if (uiState.pendingProposals.isNotEmpty()) {
            item {
                Text("PENDING CONFIRMATION (ASSISTANT MODE)", style = MaterialTheme.typography.titleMedium, color = WarningAmber)
            }
            items(uiState.pendingProposals, key = { it.id }) { prop ->
                HudGlassCard(borderColor = WarningAmber) {
                    Text("${prop.brokerType.displayName} • ${prop.side} ${prop.symbol}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Amount: $${"%.2f".format(prop.amountUsd)} | Entry: ${TechnicalAnalysisEngine.formatPrice(prop.entryPrice)} | SL: ${TechnicalAnalysisEngine.formatPrice(prop.stopLoss)} | TP: ${TechnicalAnalysisEngine.formatPrice(prop.takeProfit)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcCyan
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(prop.reasoning, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onApproveProposal(prop) },
                            colors = ButtonDefaults.buttonColors(containerColor = ProfitEmerald),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("approve_proposal_btn")
                        ) { Text("Confirm & Execute") }
                        OutlinedButton(
                            onClick = { onRejectProposal(prop.id) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Decline") }
                    }
                }
            }
        }

        // Guided Order Ticket
        item {
            HudGlassCard {
                Text("ORDER EXECUTION TICKET (${uiState.selectedSymbol})", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val availableBrokers = BrokerType.entries.filter {
                        it != BrokerType.MT_BRIDGE || com.example.security.SecretsConfig.hasMtBridge
                    }
                    availableBrokers.forEach { b ->
                        FilterChip(
                            selected = ticketBroker == b,
                            onClick = { ticketBroker = b },
                            label = { Text(b.displayName) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("BUY", "SELL").forEach { side ->
                        FilterChip(
                            selected = ticketSide == side,
                            onClick = { ticketSide = side },
                            label = { Text(side) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ticketAmount,
                    onValueChange = { ticketAmount = it },
                    label = { Text("Stake / Order Amount (USD)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val amt = ticketAmount.toDoubleOrNull() ?: 25.0
                        val sl = if (ticketSide == "BUY") activePrice * 0.99 else activePrice * 1.01
                        val tp = if (ticketSide == "BUY") activePrice * 1.02 else activePrice * 0.98
                        onSubmitManualProposal(ticketBroker, uiState.selectedSymbol, ticketSide, amt, activePrice, sl, tp)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("submit_order_ticket_btn")
                ) {
                    Text(
                        when (settings.operatingMode) {
                            TradingOperatingMode.OBSERVATION -> "Observation Mode (Execution Locked)"
                            TradingOperatingMode.ASSISTANT -> "Propose Order for Approval"
                            TradingOperatingMode.AUTOMATED -> "Execute Within Risk Guardrails"
                        }
                    )
                }
            }
        }

        // Open Positions
        item {
            Text("OPEN POSITIONS (${uiState.openPositions.size})", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
        }
        if (uiState.openPositions.isEmpty()) {
            item {
                HudGlassCard {
                    Text(
                        text = "No open positions across connected brokers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = jc.mutedSlate
                    )
                }
            }
        } else {
            items(uiState.openPositions, key = { it.id }) { pos ->
                HudGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("${pos.brokerType.displayName} • ${pos.symbol}", style = MaterialTheme.typography.titleMedium)
                            Text("${pos.side} • Vol ${pos.volume}", style = MaterialTheme.typography.labelMedium, color = jc.mutedSlate)
                        }
                        StatusBadge(
                            text = "${"%+.2f".format(pos.unrealizedPnl)} ${pos.currency}",
                            color = if (pos.unrealizedPnl >= 0.0) ProfitEmerald else AlertCrimson
                        )
                    }
                }
            }
        }

        // Risk Management Guardrails
        item {
            HudGlassCard {
                Text("RISK GUARDRAILS", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxTradeText,
                        onValueChange = { maxTradeText = it },
                        label = { Text("Max Trade ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxLossText,
                        onValueChange = { maxLossText = it },
                        label = { Text("Max Daily Loss ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxPosText,
                        onValueChange = { maxPosText = it },
                        label = { Text("Max Open") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        onUpdateRiskLimits(
                            settings.riskLimits.copy(
                                maxTradeAmountUsd = maxTradeText.toDoubleOrNull() ?: settings.riskLimits.maxTradeAmountUsd,
                                maxDailyLossUsd = maxLossText.toDoubleOrNull() ?: settings.riskLimits.maxDailyLossUsd,
                                maxOpenPositions = maxPosText.toIntOrNull() ?: settings.riskLimits.maxOpenPositions
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Risk Guardrails")
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
