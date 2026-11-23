package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AiModelOption
import com.example.domain.model.RiskLimits
import com.example.domain.model.TradingOperatingMode
import com.example.domain.model.VoicePersonality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.jarvisDataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_preferences")

data class JarvisUserSettings(
    val selectedModelId: String = AiModelOption.catalog.first().id,
    val voicePersonality: VoicePersonality = VoicePersonality.JARVIS_CLASSIC,
    val voiceSpeed: Float = 1.02f,
    val wakeWordEnabled: Boolean = false,
    val voiceOutputEnabled: Boolean = true,
    val useNvidiaMagpieVoice: Boolean = true,
    val memoryEnabled: Boolean = true,
    val operatingMode: TradingOperatingMode = TradingOperatingMode.OBSERVATION,
    val riskLimits: RiskLimits = RiskLimits(),
    val watchlistSymbols: Set<String> = setOf("EUR/USD", "GBP/USD", "XAU/USD", "BTC/USDT", "ETH/USDT", "SOL/USDT"),
    val notifyMarketMoves: Boolean = true,
    val notifyOpportunities: Boolean = true,
    val notifyNews: Boolean = true,
    val notifyTrades: Boolean = true,
    val highContrastHud: Boolean = false,
    val supabaseAutoSync: Boolean = false,
    val preferredBroker: String = "DERIV"
)

class JarvisPreferencesRepository(private val context: Context) {

    private object Keys {
        val MODEL_ID = stringPreferencesKey("selected_model_id")
        val VOICE_PERSONALITY = stringPreferencesKey("voice_personality")
        val VOICE_SPEED = floatPreferencesKey("voice_speed")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val VOICE_OUTPUT_ENABLED = booleanPreferencesKey("voice_output_enabled")
        val USE_NVIDIA_MAGPIE_VOICE = booleanPreferencesKey("use_nvidia_magpie_voice")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val OPERATING_MODE = stringPreferencesKey("operating_mode")
        val AUTO_AUTHORIZED = booleanPreferencesKey("auto_trading_authorized")
        val EMERGENCY_STOP = booleanPreferencesKey("emergency_stop_active")
        val MAX_TRADE_USD = doublePreferencesKey("max_trade_amount_usd")
        val MAX_DAILY_LOSS_USD = doublePreferencesKey("max_daily_loss_usd")
        val CURRENT_DAILY_LOSS_USD = doublePreferencesKey("current_daily_loss_usd")
        val MAX_OPEN_POSITIONS = intPreferencesKey("max_open_positions")
        val RISK_PER_TRADE_PCT = doublePreferencesKey("risk_per_trade_pct")
        val REQUIRE_SL = booleanPreferencesKey("require_stop_loss")
        val REQUIRE_TP = booleanPreferencesKey("require_take_profit")
        val SESSION_FILTER = booleanPreferencesKey("session_filter_enabled")
        val ALLOWED_ASSETS = stringSetPreferencesKey("allowed_assets")
        val WATCHLIST = stringSetPreferencesKey("watchlist_symbols")
        val NOTIFY_MOVES = booleanPreferencesKey("notify_moves")
        val NOTIFY_OPP = booleanPreferencesKey("notify_opportunities")
        val NOTIFY_NEWS = booleanPreferencesKey("notify_news")
        val NOTIFY_TRADES = booleanPreferencesKey("notify_trades")
        val HIGH_CONTRAST_HUD = booleanPreferencesKey("high_contrast_hud")
        val SUPABASE_AUTO_SYNC = booleanPreferencesKey("supabase_auto_sync")
        val PREFERRED_BROKER = stringPreferencesKey("preferred_broker")
    }

    val settingsFlow: Flow<JarvisUserSettings> = context.jarvisDataStore.data.map { prefs ->
        val mode = prefs[Keys.OPERATING_MODE]?.let { name ->
            TradingOperatingMode.entries.find { it.name == name }
        } ?: TradingOperatingMode.OBSERVATION

        val personality = prefs[Keys.VOICE_PERSONALITY]?.let { name ->
            VoicePersonality.entries.find { it.name == name }
        } ?: VoicePersonality.JARVIS_CLASSIC

        val defaultRisk = RiskLimits()
        JarvisUserSettings(
            selectedModelId = AiModelOption.normalizeModelId(prefs[Keys.MODEL_ID]),
            voicePersonality = personality,
            voiceSpeed = prefs[Keys.VOICE_SPEED] ?: personality.rate,
            wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: false,
            voiceOutputEnabled = prefs[Keys.VOICE_OUTPUT_ENABLED] ?: true,
            useNvidiaMagpieVoice = prefs[Keys.USE_NVIDIA_MAGPIE_VOICE] ?: true,
            memoryEnabled = prefs[Keys.MEMORY_ENABLED] ?: true,
            operatingMode = mode,
            riskLimits = RiskLimits(
                automatedTradingAuthorized = prefs[Keys.AUTO_AUTHORIZED] ?: false,
                emergencyStopActive = prefs[Keys.EMERGENCY_STOP] ?: false,
                maxTradeAmountUsd = prefs[Keys.MAX_TRADE_USD] ?: defaultRisk.maxTradeAmountUsd,
                maxDailyLossUsd = prefs[Keys.MAX_DAILY_LOSS_USD] ?: defaultRisk.maxDailyLossUsd,
                currentDailyLossUsd = prefs[Keys.CURRENT_DAILY_LOSS_USD] ?: 0.0,
                maxOpenPositions = prefs[Keys.MAX_OPEN_POSITIONS] ?: defaultRisk.maxOpenPositions,
                riskPerTradePercent = prefs[Keys.RISK_PER_TRADE_PCT] ?: defaultRisk.riskPerTradePercent,
                requireStopLoss = prefs[Keys.REQUIRE_SL] ?: true,
                requireTakeProfit = prefs[Keys.REQUIRE_TP] ?: true,
                sessionFilterEnabled = prefs[Keys.SESSION_FILTER] ?: false,
                allowedAssets = prefs[Keys.ALLOWED_ASSETS] ?: defaultRisk.allowedAssets
            ),
            watchlistSymbols = prefs[Keys.WATCHLIST] ?: setOf("EUR/USD", "GBP/USD", "XAU/USD", "BTC/USDT", "ETH/USDT", "SOL/USDT"),
            notifyMarketMoves = prefs[Keys.NOTIFY_MOVES] ?: true,
            notifyOpportunities = prefs[Keys.NOTIFY_OPP] ?: true,
            notifyNews = prefs[Keys.NOTIFY_NEWS] ?: true,
            notifyTrades = prefs[Keys.NOTIFY_TRADES] ?: true,
            highContrastHud = prefs[Keys.HIGH_CONTRAST_HUD] ?: false,
            supabaseAutoSync = prefs[Keys.SUPABASE_AUTO_SYNC] ?: false,
            preferredBroker = prefs[Keys.PREFERRED_BROKER] ?: "DERIV"
        )
    }

    suspend fun updateModelId(modelId: String) {
        context.jarvisDataStore.edit { it[Keys.MODEL_ID] = AiModelOption.normalizeModelId(modelId) }
    }

    suspend fun updateVoicePersonality(personality: VoicePersonality) {
        context.jarvisDataStore.edit {
            it[Keys.VOICE_PERSONALITY] = personality.name
            it[Keys.VOICE_SPEED] = personality.rate
        }
    }

    suspend fun updateVoiceSpeed(speed: Float) {
        context.jarvisDataStore.edit { it[Keys.VOICE_SPEED] = speed.coerceIn(0.7f, 1.5f) }
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled }
    }

    suspend fun setVoiceOutputEnabled(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.VOICE_OUTPUT_ENABLED] = enabled }
    }

    suspend fun setUseNvidiaMagpieVoice(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.USE_NVIDIA_MAGPIE_VOICE] = enabled }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.MEMORY_ENABLED] = enabled }
    }

    suspend fun setOperatingMode(mode: TradingOperatingMode) {
        context.jarvisDataStore.edit {
            it[Keys.OPERATING_MODE] = mode.name
            if (mode == TradingOperatingMode.AUTOMATED && it[Keys.AUTO_AUTHORIZED] != true) {
                // Automated trading remains disabled until explicitly authorized
                it[Keys.OPERATING_MODE] = TradingOperatingMode.ASSISTANT.name
            }
        }
    }

    suspend fun setAutomatedAuthorized(authorized: Boolean) {
        context.jarvisDataStore.edit {
            it[Keys.AUTO_AUTHORIZED] = authorized
            if (!authorized && it[Keys.OPERATING_MODE] == TradingOperatingMode.AUTOMATED.name) {
                it[Keys.OPERATING_MODE] = TradingOperatingMode.OBSERVATION.name
            }
        }
    }

    suspend fun setEmergencyStop(active: Boolean) {
        context.jarvisDataStore.edit {
            it[Keys.EMERGENCY_STOP] = active
            if (active) {
                it[Keys.AUTO_AUTHORIZED] = false
                it[Keys.OPERATING_MODE] = TradingOperatingMode.OBSERVATION.name
            }
        }
    }

    suspend fun updateRiskLimits(limits: RiskLimits) {
        context.jarvisDataStore.edit {
            it[Keys.MAX_TRADE_USD] = limits.maxTradeAmountUsd.coerceAtLeast(1.0)
            it[Keys.MAX_DAILY_LOSS_USD] = limits.maxDailyLossUsd.coerceAtLeast(5.0)
            it[Keys.MAX_OPEN_POSITIONS] = limits.maxOpenPositions.coerceIn(1, 20)
            it[Keys.RISK_PER_TRADE_PCT] = limits.riskPerTradePercent.coerceIn(0.1, 10.0)
            it[Keys.REQUIRE_SL] = limits.requireStopLoss
            it[Keys.REQUIRE_TP] = limits.requireTakeProfit
            it[Keys.SESSION_FILTER] = limits.sessionFilterEnabled
            it[Keys.ALLOWED_ASSETS] = limits.allowedAssets
        }
    }

    suspend fun toggleWatchlistSymbol(symbol: String) {
        context.jarvisDataStore.edit { prefs ->
            val current = prefs[Keys.WATCHLIST] ?: setOf("EUR/USD", "GBP/USD", "XAU/USD", "BTC/USDT", "ETH/USDT", "SOL/USDT")
            prefs[Keys.WATCHLIST] = if (current.contains(symbol)) current - symbol else current + symbol
        }
    }

    suspend fun updateNotificationPrefs(moves: Boolean, opp: Boolean, news: Boolean, trades: Boolean) {
        context.jarvisDataStore.edit {
            it[Keys.NOTIFY_MOVES] = moves
            it[Keys.NOTIFY_OPP] = opp
            it[Keys.NOTIFY_NEWS] = news
            it[Keys.NOTIFY_TRADES] = trades
        }
    }

    suspend fun setHighContrastHud(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.HIGH_CONTRAST_HUD] = enabled }
    }

    suspend fun setSupabaseAutoSync(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.SUPABASE_AUTO_SYNC] = enabled }
    }

    suspend fun setPreferredBroker(broker: String) {
        context.jarvisDataStore.edit { it[Keys.PREFERRED_BROKER] = broker }
    }
}
