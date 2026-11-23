package com.example.security

import com.example.BuildConfig

object SecretsConfig {
    private fun cleanSecret(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().trim('"', '\'')
        if (trimmed.isBlank() || trimmed.startsWith("MY_") || trimmed.startsWith("UNSET_") || trimmed == "null") {
            return null
        }
        return trimmed
    }

    val nvidiaNimApiKey: String?
        get() = cleanSecret(BuildConfig.NVIDIA_NIM_API_KEY)

    val supabaseUrl: String?
        get() = cleanSecret(BuildConfig.SUPABASE_URL)?.trimEnd('/')

    val supabaseAnonKey: String?
        get() = cleanSecret(BuildConfig.SUPABASE_ANON_KEY)

    val derivAppId: String?
        get() = cleanSecret(BuildConfig.DERIV_APP_ID)

    val derivApiToken: String?
        get() = cleanSecret(BuildConfig.DERIV_API_TOKEN)

    val binanceApiKey: String?
        get() = cleanSecret(BuildConfig.BINANCE_API_KEY)

    val binanceApiSecret: String?
        get() = cleanSecret(BuildConfig.BINANCE_API_SECRET)

    val finnhubApiKey: String?
        get() = cleanSecret(BuildConfig.FINNHUB_API_KEY)

    val mtBridgeUrl: String?
        get() = cleanSecret(BuildConfig.MT_BRIDGE_URL)?.trimEnd('/')

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
