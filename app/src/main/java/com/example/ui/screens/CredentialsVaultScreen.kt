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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.assistant.AccessibilityHealthState
import com.example.security.SecureCredentialStore
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
fun CredentialsVaultScreen(
    uiState: JarvisUiState,
    onSavePersonalProfile: (SecureCredentialStore.SignupProfile) -> Unit,
    onSaveSiteCredential: (serviceName: String, websiteOrApp: String, usernameOrEmail: String, password: String, notes: String) -> Unit,
    onDeleteSiteCredential: (String) -> Unit,
    onOpenAndSignIn: (SecureCredentialStore.SavedCredentialEntry) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onLaunchFloatingOrbNow: () -> Unit
) {
    val jc = LocalJarvisColors.current
    val p = uiState.signupProfile

    var fullName by remember(p.fullName) { mutableStateOf(p.fullName) }
    var username by remember(p.username) { mutableStateOf(p.username) }
    var email by remember(p.email) { mutableStateOf(p.email) }
    var phone by remember(p.phoneNumber) { mutableStateOf(p.phoneNumber) }
    var dob by remember(p.dateOfBirth) { mutableStateOf(p.dateOfBirth) }
    var country by remember(p.country) { mutableStateOf(p.country) }
    var address by remember(p.addressLine) { mutableStateOf(p.addressLine) }
    var city by remember(p.city) { mutableStateOf(p.city) }
    var postal by remember(p.postalCode) { mutableStateOf(p.postalCode) }
    var defaultPassword by remember(p.defaultPassword) { mutableStateOf(p.defaultPassword) }
    var allowFill by remember(p.allowAssistedFill) { mutableStateOf(p.allowAssistedFill) }
    var showDefaultPass by remember { mutableStateOf(false) }

    var siteName by remember { mutableStateOf("") }
    var siteUrlOrApp by remember { mutableStateOf("") }
    var siteLogin by remember { mutableStateOf("") }
    var sitePass by remember { mutableStateOf("") }
    var siteNotes by remember { mutableStateOf("") }
    var revealMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("PRIVATE CREDENTIALS & PLUGIN", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
                Text(
                    "Encrypted on your phone with Android Keystore. JARVIS uses this locally to sign you in/up and guide verification steps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = jc.mutedSlate
                )
            }
        }

        // Plugin Quick Status Bar
        item {
            HudGlassCard(borderColor = ArcCyan.copy(alpha = 0.35f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = ProfitEmerald)
                        Spacer(Modifier.width(8.dp))
                        Text("On-Device AES-256 Encrypted", style = MaterialTheme.typography.titleMedium)
                    }
                    StatusBadge(
                        text = when {
                            uiState.isScreenHelperEnabled && uiState.hasOverlayPermission -> "PLUGIN READY"
                            uiState.accessibilityHealthState == AccessibilityHealthState.MALFUNCTION_DETECTED -> "REPAIR"
                            else -> "SETUP"
                        },
                        color = when {
                            uiState.isScreenHelperEnabled && uiState.hasOverlayPermission -> ProfitEmerald
                            uiState.accessibilityHealthState == AccessibilityHealthState.MALFUNCTION_DETECTED ->
                                MaterialTheme.colorScheme.error
                            else -> WarningAmber
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!uiState.hasOverlayPermission) onRequestOverlayPermission() else onLaunchFloatingOrbNow()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vault_launch_orb_btn")
                    ) {
                        Text(if (uiState.hasOverlayPermission) "Open Mini-Orb" else "Allow Mini-Orb")
                    }
                    OutlinedButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vault_screen_helper_btn")
                    ) {
                        val helperText = when (uiState.accessibilityHealthState) {
                            AccessibilityHealthState.OPERATIONAL -> "Helper: Operational"
                            AccessibilityHealthState.CONNECTED -> "Helper: Connected"
                            AccessibilityHealthState.MALFUNCTION_DETECTED -> "Repair Helper"
                            AccessibilityHealthState.PERMISSION_ENABLED_NOT_CONNECTED -> "Rebind Helper"
                            else -> "Enable Helper"
                        }
                        Text(helperText)
                    }
                }
            }
        }

        // Add Website / App Login Credential
        item {
            HudGlassCard {
                Text("SAVE WEBSITE OR APP LOGIN", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = siteName,
                    onValueChange = { siteName = it },
                    label = { Text("Website or App Name (e.g. GitHub, Exness)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cred_service_input")
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = siteUrlOrApp,
                    onValueChange = { siteUrlOrApp = it },
                    label = { Text("Website Link or URL (optional, e.g. github.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cred_url_input")
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = siteLogin,
                    onValueChange = { siteLogin = it },
                    label = { Text("Email or Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cred_login_input")
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = sitePass,
                    onValueChange = { sitePass = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cred_password_input")
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = siteNotes,
                    onValueChange = { siteNotes = it },
                    label = { Text("Optional Step Notes (e.g. 2FA app / ID check)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        onSaveSiteCredential(siteName, siteUrlOrApp, siteLogin, sitePass, siteNotes)
                        siteName = ""
                        siteUrlOrApp = ""
                        siteLogin = ""
                        sitePass = ""
                        siteNotes = ""
                    },
                    modifier = Modifier.fillMaxWidth().testTag("save_site_cred_btn")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save Encrypted Login")
                }
            }
        }

        // Saved Website & App Logins List
        if (uiState.savedCredentials.isNotEmpty()) {
            item {
                Text("SAVED LOGINS (${uiState.savedCredentials.size})", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
            }
            items(uiState.savedCredentials, key = { it.id }) { entry ->
                val revealed = revealMap[entry.id] == true
                HudGlassCard(contentPadding = PaddingValues(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.serviceName, style = MaterialTheme.typography.titleMedium)
                            if (entry.websiteOrApp.isNotBlank()) {
                                Text(entry.websiteOrApp, style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                            }
                            Text("User: ${entry.usernameOrEmail}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "Password: ${if (revealed) entry.password else "••••••••"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = jc.mutedSlate
                            )
                            if (entry.notes.isNotBlank()) {
                                Text("Note: ${entry.notes}", style = MaterialTheme.typography.labelSmall, color = ElectricBlue)
                            }
                        }
                        Row {
                            IconButton(onClick = {
                                revealMap = revealMap.toMutableMap().apply { put(entry.id, !revealed) }
                            }) {
                                Icon(
                                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle password visibility"
                                )
                            }
                            IconButton(onClick = { onOpenAndSignIn(entry) }) {
                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "Open and sign in", tint = ProfitEmerald)
                            }
                            IconButton(onClick = { onDeleteSiteCredential(entry.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete saved login", tint = AlertCrimson)
                            }
                        }
                    }
                }
            }
        }

        // Full Personal Sign-Up Details
        item {
            HudGlassCard {
                Text("MY PERSONAL INFO (FOR SIGN-UPS)", style = MaterialTheme.typography.titleMedium, color = ArcCyan)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Stored encrypted on your phone only. JARVIS uses this to auto-fill registration forms when permitted and pauses before ID photos, CAPTCHAs, or final submission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = jc.mutedSlate
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text("Full Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Preferred Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = dob, onValueChange = { dob = it }, label = { Text("Date of Birth (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = country, onValueChange = { country = it }, label = { Text("Country") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Street Address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = postal, onValueChange = { postal = it }, label = { Text("Postal / ZIP") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = defaultPassword,
                    onValueChange = { defaultPassword = it },
                    label = { Text("Default Sign-Up Password (optional)") },
                    visualTransformation = if (showDefaultPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showDefaultPass = !showDefaultPass }) {
                            Icon(
                                imageVector = if (showDefaultPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Show or hide default password"
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow Assisted Form Fill", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Lets JARVIS populate forms cleanly and guide ID/OTP steps",
                            style = MaterialTheme.typography.bodySmall,
                            color = jc.mutedSlate
                        )
                    }
                    Switch(checked = allowFill, onCheckedChange = { allowFill = it })
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        onSavePersonalProfile(
                            SecureCredentialStore.SignupProfile(
                                fullName = fullName,
                                username = username,
                                email = email,
                                phoneNumber = phone,
                                dateOfBirth = dob,
                                country = country,
                                addressLine = address,
                                city = city,
                                postalCode = postal,
                                defaultPassword = defaultPassword,
                                allowAssistedFill = allowFill
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("save_personal_profile_btn")
                ) {
                    Text("Save Personal Info")
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}
