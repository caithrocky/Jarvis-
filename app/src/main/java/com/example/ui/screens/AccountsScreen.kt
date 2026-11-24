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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.model.AccountEnvironment
import com.example.domain.model.BrokerType
import com.example.security.SecretsConfig
import com.example.ui.JarvisUiState
import com.example.ui.components.HudGlassCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.OfflineGray
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun AccountsScreen(
    uiState: JarvisUiState,
    derivOAuthUrl: String?,
    supabaseSignedInEmail: String?,
    onSyncBrokers: () -> Unit,
    onDisconnectDeriv: () -> Unit,
    onSignInSupabase: (String, String) -> Unit,
    onSignUpSupabase: (String, String) -> Unit,
    onSignOutSupabase: () -> Unit
) {
    val context = LocalContext.current
    val jc = LocalJarvisColors.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
                    Text("CONNECTED ACCOUNTS", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                    Text("Demo & Live separation • Zero password collection", style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                }
                IconButton(onClick = onSyncBrokers, modifier = Modifier.testTag("sync_brokers_btn")) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Sync trading accounts", tint = ElectricBlue)
                }
            }
        }

        // Broker Cards
        items(uiState.accounts, key = { it.id }) { acc ->
            val envColor = if (acc.environment == AccountEnvironment.DEMO) ArcCyan else WarningAmber
            val statusColor = if (acc.isConnected) ProfitEmerald else OfflineGray

            HudGlassCard(borderColor = if (acc.isConnected) ProfitEmerald.copy(alpha = 0.45f) else jc.hairlineGlow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(acc.brokerType.displayName, style = MaterialTheme.typography.titleLarge)
                        Text("Account: ${acc.accountCode}", style = MaterialTheme.typography.labelMedium, color = jc.mutedSlate)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val isOptionalMt = acc.brokerType == BrokerType.MT_BRIDGE && !acc.isConnected && !SecretsConfig.hasMtBridge
                        if (!isOptionalMt) {
                            StatusBadge(text = acc.environment.label, color = envColor)
                        }
                        StatusBadge(
                            text = when {
                                acc.isConnected -> "CONNECTED"
                                isOptionalMt -> "OPTIONAL (LATER)"
                                else -> "DISCONNECTED"
                            },
                            color = statusColor
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (acc.balance != null) {
                    Text(
                        text = "${acc.currency} ${"%.2f".format(acc.balance)}",
                        style = MaterialTheme.typography.headlineLarge,
                        color = ElectricBlue
                    )
                    acc.availableBalance?.let { avail ->
                        Text(
                            text = "Available: ${acc.currency} ${"%.2f".format(avail)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = jc.mutedSlate
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(acc.statusMessage, style = MaterialTheme.typography.bodyMedium, color = jc.mutedSlate)
                if (acc.permissions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Permissions: ${acc.permissions.joinToString(" • ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcCyan
                    )
                }

                if (acc.brokerType == BrokerType.DERIV) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (derivOAuthUrl != null) {
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(derivOAuthUrl)))
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("deriv_oauth_button")
                            ) {
                                Icon(Icons.Filled.Link, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Deriv OAuth")
                            }
                        }
                        if (acc.isConnected) {
                            OutlinedButton(
                                onClick = onDisconnectDeriv,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }
        }

        // Supabase Cloud Backend Auth Card
        item {
            HudGlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudDone, contentDescription = null, tint = ElectricBlue)
                        Spacer(Modifier.width(8.dp))
                        Text("SUPABASE CLOUD BACKEND", style = MaterialTheme.typography.titleMedium)
                    }
                    StatusBadge(
                        text = when {
                            supabaseSignedInEmail != null -> "AUTHENTICATED"
                            uiState.supabaseReachable == true -> "CONNECTED"
                            uiState.supabaseReachable == false && SecretsConfig.hasSupabase -> "ERROR"
                            SecretsConfig.hasSupabase -> "READY"
                            else -> "UNCONFIGURED"
                        },
                        color = when {
                            supabaseSignedInEmail != null || uiState.supabaseReachable == true -> ProfitEmerald
                            uiState.supabaseReachable == false && SecretsConfig.hasSupabase -> WarningAmber
                            else -> jc.mutedSlate
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                uiState.supabaseHealthDetail?.let { health ->
                    Text(
                        text = health,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.supabaseReachable == true) ArcCyan else WarningAmber
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (!SecretsConfig.hasSupabase) {
                    Text(
                        text = "Configure SUPABASE_URL and SUPABASE_ANON_KEY in AI Studio Secrets to enable PostgreSQL RLS sync and Edge Function proxying.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = jc.mutedSlate
                    )
                } else if (supabaseSignedInEmail != null) {
                    Text("Signed in as $supabaseSignedInEmail", style = MaterialTheme.typography.bodyMedium, color = ProfitEmerald)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onSignOutSupabase) { Text("Sign Out") }
                } else {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Supabase Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSignInSupabase(email, password) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Sign In") }
                        OutlinedButton(
                            onClick = { onSignUpSupabase(email, password) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Create Account") }
                    }
                }
            }
        }

        if (uiState.brokerTradeHistory.isNotEmpty()) {
            item {
                HudGlassCard {
                    Text("RECENT BROKER TRANSACTIONS", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                    Spacer(Modifier.height(6.dp))
                    uiState.brokerTradeHistory.forEach { item ->
                        Text("• $item", style = MaterialTheme.typography.labelMedium, color = jc.mutedSlate)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
