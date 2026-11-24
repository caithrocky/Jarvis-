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

    val nvidiaNimApiKey: String?
        get() = cleanSecret(BuildConfig.NVIDIA_NIM_API_KEY)

    val supabaseUrl: String?
        get() = normalizeHttpsUrl(BuildConfig.SUPABASE_URL)

    val supabaseAnonKey: String?
        get() = cleanSecret(BuildConfig.SUPABASE_ANON_KEY)

    val derivAppId: String?
        get() = cleanSecret(BuildConfig.DERIV_APP_ID, stripBearerPrefix = false)

    val derivApiToken: String?
        get() = cleanSecret(BuildConfig.DERIV_API_TOKEN)

    val binanceApiKey: String?
        get() = cleanSecret(BuildConfig.BINANCE_API_KEY)

    val binanceApiSecret: String?
        get() = cleanSecret(BuildConfig.BINANCE_API_SECRET)

    val finnhubApiKey: String?
        get() = cleanSecret(BuildConfig.FINNHUB_API_KEY)

    val mtBridgeUrl: String?
        get() = cleanSecret(BuildConfig.MT_BRIDGE_URL, stripBearerPrefix = false)?.trimEnd('/')

    val mtBridgeToken: String?
        get() = cleanSecret(BuildConfig.MT_BRIDGE_TOKEN)

    val hasNvidiaNim: Boolean
        get() = nvidiaNimApiKey != null

    val hasSupabase: Boolean
        get() = supabaseUrl != null && supabaseAnonKey != null

    val hasDerivAuth: Boolean
        get() = derivApiToken != null

    val hasBinanceAuth: Boolean
        get() = binanceApiKey != null && binanceApiSecret != null

    val hasFinnhub: Boolean
        get() = finnhubApiKey != null

    val hasMtBridge: Boolean
        get() = mtBridgeUrl != null && mtBridgeToken != null

    val supabaseHostLabel: String?
        get() = supabaseUrl?.removePrefix("https://")?.removePrefix("http://")?.substringBefore("/")
}
