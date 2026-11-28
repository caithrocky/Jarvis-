package com.example.security

import com.example.BuildConfig

object SecretsConfig {
    internal fun cleanSecret(raw: String?, stripBearerPrefix: Boolean = true): String? {
        if (raw.isNullOrBlank()) return null
        var trimmed = raw.trim().trim('"', '\'').replace("\r", "").replace("\n", "").trim()
        if (stripBearerPrefix && trimmed.startsWith("Bearer ", ignoreCase = true)) {
            trimmed = trimmed.substring(7).trim()
        }
        val upper = trimmed.uppercase()
        if (trimmed.isBlank() ||
            upper.startsWith("MY_") ||
            upper.startsWith("UNSET_") ||
            upper.startsWith("YOUR_") ||
            upper == "NULL"
        ) {
            return null
        }
        return trimmed
    }

    internal fun normalizeHttpsUrl(raw: String?): String? {
        val cleaned = cleanSecret(raw, stripBearerPrefix = false) ?: return null
        val withScheme = when {
            cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("http://", ignoreCase = true) -> cleaned
            cleaned.contains(".") -> "https://$cleaned"
            else -> "https://$cleaned.supabase.co"
        }
        return withScheme
            .trimEnd('/')
            .removeSuffix("/rest/v1")
            .removeSuffix("/auth/v1")
            .trimEnd('/')
    }

    @Volatile
    private var runtimeCredentialStore: SecureCredentialStore? = null

    fun attachRuntimeStore(store: SecureCredentialStore) {
        runtimeCredentialStore = store
    }

    val nvidiaNimApiKey: String?
        get() = cleanSecret(BuildConfig.NVIDIA_NIM_API_KEY)
            ?: cleanSecret(runtimeCredentialStore?.getDecrypted(SecureCredentialStore.KEY_NVIDIA_NIM_API_OVERRIDE))

    val hasBuildConfigNvidiaNim: Boolean
        get() = cleanSecret(BuildConfig.NVIDIA_NIM_API_KEY) != null

    val supabaseUrl: String? = null
    val supabaseAnonKey: String? = null
    val derivAppId: String? = null
    val derivApiToken: String? = null
    val binanceApiKey: String? = null
    val binanceApiSecret: String? = null
    val finnhubApiKey: String? = null
    val mtBridgeUrl: String? = null
    val mtBridgeToken: String? = null

    val hasNvidiaNim: Boolean
        get() = nvidiaNimApiKey != null

    val hasSupabase: Boolean = false
    val hasDerivAuth: Boolean = false
    val hasBinanceAuth: Boolean = false
    val hasFinnhub: Boolean = false
    val hasMtBridge: Boolean = false
    val supabaseHostLabel: String? = null
}
