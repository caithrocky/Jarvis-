package com.example.data.remote

import com.example.security.SecretsConfig
import com.example.security.SecureCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SupabaseSyncClient(
    private val httpClient: OkHttpClient,
    private val credentialStore: SecureCredentialStore
) {
    fun currentSignedInEmail(): String? =
        credentialStore.getDecrypted(SecureCredentialStore.KEY_SUPABASE_USER_EMAIL)

    suspend fun signInWithEmailPassword(email: String, password: String): Pair<Boolean, String> =
        authRequest("token?grant_type=password", email, password, isSignUp = false)

    suspend fun signUpWithEmailPassword(email: String, password: String): Pair<Boolean, String> =
        authRequest("signup", email, password, isSignUp = true)

    private suspend fun authRequest(
        path: String,
        email: String,
        password: String,
        isSignUp: Boolean
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val baseUrl = SecretsConfig.supabaseUrl
        val anonKey = SecretsConfig.supabaseAnonKey
        if (baseUrl == null || anonKey == null) {
            return@withContext false to "Configure SUPABASE_URL and SUPABASE_ANON_KEY in AI Studio Secrets."
        }
        if (!baseUrl.startsWith("https://")) {
            return@withContext false to "SUPABASE_URL must use HTTPS."
        }
        if (email.isBlank() || password.length < 6) {
            return@withContext false to "Enter a valid email and a password of at least 6 characters."
        }
        runCatching {
            val payload = JSONObject().put("email", email.trim()).put("password", password).toString()
            val req = Request.Builder()
                .url("$baseUrl/auth/v1/$path")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val jsonErr = runCatching { JSONObject(body) }.getOrNull()
                    val msg = jsonErr?.optString("error_description")?.takeIf { it.isNotBlank() }
                        ?: jsonErr?.optString("msg")?.takeIf { it.isNotBlank() }
                        ?: jsonErr?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "HTTP ${resp.code}"
                    return@withContext false to "Supabase auth failed: $msg"
                }
                val json = JSONObject(body)
                val accessToken = json.optString("access_token")
                if (accessToken.isBlank()) {
                    return@withContext if (isSignUp) {
                        true to "Verification email sent to ${email.trim()}. Confirm email, then sign in."
                    } else {
                        false to "No access token returned by Supabase."
                    }
                }
                credentialStore.putEncrypted(SecureCredentialStore.KEY_SUPABASE_ACCESS_TOKEN, accessToken)
                credentialStore.putEncrypted(SecureCredentialStore.KEY_SUPABASE_USER_EMAIL, email.trim())
                true to "Authenticated as ${email.trim()}"
            }
        }.getOrElse {
            false to "Supabase auth error: ${it.message ?: "Network failure"}"
        }
    }

    fun signOut() {
        credentialStore.remove(SecureCredentialStore.KEY_SUPABASE_ACCESS_TOKEN)
        credentialStore.remove(SecureCredentialStore.KEY_SUPABASE_USER_EMAIL)
    }

    suspend fun testHealth(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val baseUrl = SecretsConfig.supabaseUrl
        val anonKey = SecretsConfig.supabaseAnonKey
        if (baseUrl == null || anonKey == null) {
            return@withContext false to "SUPABASE_URL / SUPABASE_ANON_KEY not set in Secrets."
        }
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            return@withContext false to "Invalid SUPABASE_URL format."
        }

        // Check Auth settings endpoint first (requires valid project URL + apikey, works across all Supabase setups)
        val authCheck = runCatching {
            val req = Request.Builder()
                .url("$baseUrl/auth/v1/settings")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    true to "Connected (${SecretsConfig.supabaseHostLabel})"
                } else {
                    val err = resp.body?.string()?.take(120).orEmpty()
                    false to "Auth HTTP ${resp.code}: $err"
                }
            }
        }
        if (authCheck.getOrNull()?.first == true) {
            return@withContext authCheck.getOrThrow()
        }

        // Fallback check on PostgREST root
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/rest/v1/")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    true to "Connected via REST (${SecretsConfig.supabaseHostLabel})"
                } else {
                    false to "Supabase HTTP ${resp.code} (${authCheck.getOrNull()?.second ?: "Check SUPABASE_ANON_KEY"})"
                }
            }
        }.getOrElse {
            false to "Supabase unreachable: ${it.message ?: "Network error"}"
        }
    }

    suspend fun syncMemoryFact(category: String, summary: String): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = SecretsConfig.supabaseUrl ?: return@withContext false
        val anonKey = SecretsConfig.supabaseAnonKey ?: return@withContext false
        val token = credentialStore.getDecrypted(SecureCredentialStore.KEY_SUPABASE_ACCESS_TOKEN)
            ?: return@withContext false

        runCatching {
            val body = JSONObject().apply {
                put("category", category)
                put("summary", summary)
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/rest/v1/jarvis_memories")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Prefer", "return=minimal")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun syncOrderAudit(
        brokerName: String,
        environment: String,
        symbol: String,
        side: String,
        amountUsd: Double,
        status: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = SecretsConfig.supabaseUrl ?: return@withContext false
        val anonKey = SecretsConfig.supabaseAnonKey ?: return@withContext false
        val token = credentialStore.getDecrypted(SecureCredentialStore.KEY_SUPABASE_ACCESS_TOKEN)
            ?: return@withContext false

        runCatching {
            val body = JSONObject().apply {
                put("broker_name", brokerName)
                put("environment", environment)
                put("symbol", symbol)
                put("side", side)
                put("amount_usd", amountUsd)
                put("status", status)
                put("result_message", message)
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/rest/v1/jarvis_order_audits")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Prefer", "return=minimal")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
