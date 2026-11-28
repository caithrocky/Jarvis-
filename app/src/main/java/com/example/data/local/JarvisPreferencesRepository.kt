package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AiModelOption
import com.example.domain.model.JarvisChoiceActivity
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
    val wakeWordEnabled: Boolean = true,
    val voiceOutputEnabled: Boolean = true,
    val useNvidiaMagpieVoice: Boolean = true,
    val memoryEnabled: Boolean = true,
    val activeOperatingModes: Set<TradingOperatingMode> = setOf(
        TradingOperatingMode.OBSERVATION,
        TradingOperatingMode.ASSISTANT
    ),
    val primaryActivity: JarvisChoiceActivity = JarvisChoiceActivity.ASSISTANT,
    val riskLimits: RiskLimits = RiskLimits(),
    val watchlistSymbols: Set<String> = setOf("EUR/USD", "GBP/USD", "XAU/USD", "BTC/USDT", "ETH/USDT", "SOL/USDT"),
    val notifyMarketMoves: Boolean = true,
    val notifyOpportunities: Boolean = true,
    val notifyNews: Boolean = true,
    val notifyTrades: Boolean = true,
    val highContrastHud: Boolean = false,
    val supabaseAutoSync: Boolean = false,
    val preferredBroker: String = "DERIV",
    val floatingOrbEnabled: Boolean = true,
    val showTapIndicator: Boolean = true,
    val showHighlightIndicator: Boolean = true,
    val showScrollIndicator: Boolean = true,
    val voiceBiometricLockEnabled: Boolean = true,
    val voiceFluAdaptationEnabled: Boolean = true,
    val autoLearnDeviceDaily: Boolean = true,
    val futuristicHudMode: Boolean = true,
    val lastDailyLearnMs: Long = 0L,
    val orbInactivityTimeoutSeconds: Int = 45,
    val keepScreenAwakeWhileActive: Boolean = true,
    val deviceUnlockEnabled: Boolean = false,
    val autoOfflineSwitchEnabled: Boolean = true
) {
    val operatingMode: TradingOperatingMode
        get() = when {
            TradingOperatingMode.AUTOMATED in activeOperatingModes -> TradingOperatingMode.AUTOMATED
            TradingOperatingMode.ASSISTANT in activeOperatingModes -> TradingOperatingMode.ASSISTANT
            else -> TradingOperatingMode.OBSERVATION
        }
}

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
        val ACTIVE_OPERATING_MODES = stringSetPreferencesKey("active_operating_modes")
        val PRIMARY_ACTIVITY = stringPreferencesKey("primary_activity")
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
        val FLOATING_ORB_ENABLED = booleanPreferencesKey("floating_orb_enabled")
        val SHOW_TAP_INDICATOR = booleanPreferencesKey("show_tap_indicator")
        val SHOW_HIGHLIGHT_INDICATOR = booleanPreferencesKey("show_highlight_indicator")
        val SHOW_SCROLL_INDICATOR = booleanPreferencesKey("show_scroll_indicator")
        val VOICE_BIOMETRIC_LOCK = booleanPreferencesKey("voice_biometric_lock_enabled")
        val VOICE_FLU_ADAPTATION = booleanPreferencesKey("voice_flu_adaptation_enabled")
        val AUTO_LEARN_DAILY = booleanPreferencesKey("auto_learn_device_daily")
        val FUTURISTIC_HUD_MODE = booleanPreferencesKey("futuristic_hud_mode")
        val LAST_DAILY_LEARN_MS = longPreferencesKey("last_daily_learn_ms")
        val ORB_INACTIVITY_SECONDS = intPreferencesKey("orb_inactivity_seconds")
        val KEEP_SCREEN_AWAKE = booleanPreferencesKey("keep_screen_awake_while_active")
        val DEVICE_UNLOCK_ENABLED = booleanPreferencesKey("device_unlock_enabled")
        val AUTO_OFFLINE_SWITCH_ENABLED = booleanPreferencesKey("auto_offline_switch_enabled")
    }

    val settingsFlow: Flow<JarvisUserSettings> = context.jarvisDataStore.data.map { prefs ->
        val autoAuthorized = prefs[Keys.AUTO_AUTHORIZED] ?: false
        val emergencyStopped = prefs[Keys.EMERGENCY_STOP] ?: false

        val parsedModes = prefs[Keys.ACTIVE_OPERATING_MODES]
            ?.mapNotNull { name -> TradingOperatingMode.entries.find { it.name == name } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: prefs[Keys.OPERATING_MODE]
                ?.let { name -> TradingOperatingMode.entries.find { it.name == name } }
                ?.let { setOf(it) }
            ?: setOf(TradingOperatingMode.OBSERVATION, TradingOperatingMode.ASSISTANT)

        val effectiveModes = parsedModes
            .filterNot {
                it == TradingOperatingMode.AUTOMATED && (!autoAuthorized || emergencyStopped)
            }
            .toSet()
            .ifEmpty { setOf(TradingOperatingMode.OBSERVATION) }

        val primaryActivity = prefs[Keys.PRIMARY_ACTIVITY]?.let { name ->
            JarvisChoiceActivity.entries.find { it.name == name }
        } ?: JarvisChoiceActivity.ASSISTANT

        val personality = prefs[Keys.VOICE_PERSONALITY]?.let { name ->
            VoicePersonality.entries.find { it.name == name }
        } ?: VoicePersonality.JARVIS_CLASSIC

        val defaultRisk = RiskLimits()
        JarvisUserSettings(
            selectedModelId = AiModelOption.normalizeModelId(prefs[Keys.MODEL_ID]),
            voicePersonality = personality,
            voiceSpeed = prefs[Keys.VOICE_SPEED] ?: personality.rate,
            wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: true,
            voiceOutputEnabled = prefs[Keys.VOICE_OUTPUT_ENABLED] ?: true,
            useNvidiaMagpieVoice = prefs[Keys.USE_NVIDIA_MAGPIE_VOICE] ?: true,
            memoryEnabled = prefs[Keys.MEMORY_ENABLED] ?: true,
            activeOperatingModes = effectiveModes,
            primaryActivity = primaryActivity,
            riskLimits = RiskLimits(
                automatedTradingAuthorized = autoAuthorized,
                emergencyStopActive = emergencyStopped,
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
            preferredBroker = prefs[Keys.PREFERRED_BROKER] ?: "DERIV",
            floatingOrbEnabled = prefs[Keys.FLOATING_ORB_ENABLED] ?: true,
            showTapIndicator = prefs[Keys.SHOW_TAP_INDICATOR] ?: true,
            showHighlightIndicator = prefs[Keys.SHOW_HIGHLIGHT_INDICATOR] ?: true,
            showScrollIndicator = prefs[Keys.SHOW_SCROLL_INDICATOR] ?: true,
            voiceBiometricLockEnabled = prefs[Keys.VOICE_BIOMETRIC_LOCK] ?: true,
            voiceFluAdaptationEnabled = prefs[Keys.VOICE_FLU_ADAPTATION] ?: true,
            autoLearnDeviceDaily = prefs[Keys.AUTO_LEARN_DAILY] ?: true,
            futuristicHudMode = prefs[Keys.FUTURISTIC_HUD_MODE] ?: true,
            lastDailyLearnMs = prefs[Keys.LAST_DAILY_LEARN_MS] ?: 0L,
            orbInactivityTimeoutSeconds = (prefs[Keys.ORB_INACTIVITY_SECONDS] ?: 45).coerceIn(15, 300),
            keepScreenAwakeWhileActive = prefs[Keys.KEEP_SCREEN_AWAKE] ?: true,
            deviceUnlockEnabled = prefs[Keys.DEVICE_UNLOCK_ENABLED] ?: false,
            autoOfflineSwitchEnabled = prefs[Keys.AUTO_OFFLINE_SWITCH_ENABLED] ?: true
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

    suspend fun setPrimaryActivity(activity: JarvisChoiceActivity) {
        context.jarvisDataStore.edit { it[Keys.PRIMARY_ACTIVITY] = activity.name }
    }

    suspend fun setOperatingMode(mode: TradingOperatingMode) {
        context.jarvisDataStore.edit { prefs ->
            val resolved = if (mode == TradingOperatingMode.AUTOMATED && prefs[Keys.AUTO_AUTHORIZED] != true) {
                TradingOperatingMode.ASSISTANT
            } else {
                mode
            }
            prefs[Keys.OPERATING_MODE] = resolved.name
            prefs[Keys.ACTIVE_OPERATING_MODES] = setOf(resolved.name)
        }
    }

    suspend fun toggleOperatingMode(mode: TradingOperatingMode) {
        context.jarvisDataStore.edit { prefs ->
            val current = prefs[Keys.ACTIVE_OPERATING_MODES]
                ?.mapNotNull { name -> TradingOperatingMode.entries.find { it.name == name } }
                ?.toMutableSet()
                ?: prefs[Keys.OPERATING_MODE]
                    ?.let { name -> TradingOperatingMode.entries.find { it.name == name } }
                    ?.let { mutableSetOf(it) }
                ?: mutableSetOf(TradingOperatingMode.OBSERVATION, TradingOperatingMode.ASSISTANT)

            if (mode in current) {
                if (current.size > 1) {
                    current.remove(mode)
                }
            } else {
                if (mode == TradingOperatingMode.AUTOMATED && prefs[Keys.AUTO_AUTHORIZED] != true) {
                    return@edit
                }
                if (prefs[Keys.EMERGENCY_STOP] == true && mode != TradingOperatingMode.OBSERVATION) {
                    return@edit
                }
                current.add(mode)
            }
            val names = current.map { it.name }.toSet().ifEmpty { setOf(TradingOperatingMode.OBSERVATION.name) }
            prefs[Keys.ACTIVE_OPERATING_MODES] = names
            prefs[Keys.OPERATING_MODE] = when {
                TradingOperatingMode.AUTOMATED.name in names -> TradingOperatingMode.AUTOMATED.name
                TradingOperatingMode.ASSISTANT.name in names -> TradingOperatingMode.ASSISTANT.name
                else -> TradingOperatingMode.OBSERVATION.name
            }
        }
    }

    suspend fun enableAllOperatingModes(authorizeAutomatedIfRequested: Boolean = false) {
        context.jarvisDataStore.edit { prefs ->
            if (prefs[Keys.EMERGENCY_STOP] == true) {
                prefs[Keys.ACTIVE_OPERATING_MODES] = setOf(TradingOperatingMode.OBSERVATION.name)
                prefs[Keys.OPERATING_MODE] = TradingOperatingMode.OBSERVATION.name
                return@edit
            }
            if (authorizeAutomatedIfRequested) {
                prefs[Keys.AUTO_AUTHORIZED] = true
            }
            val autoAllowed = prefs[Keys.AUTO_AUTHORIZED] == true
            val modes = buildSet {
                add(TradingOperatingMode.OBSERVATION.name)
                add(TradingOperatingMode.ASSISTANT.name)
                if (autoAllowed) add(TradingOperatingMode.AUTOMATED.name)
            }
            prefs[Keys.ACTIVE_OPERATING_MODES] = modes
            prefs[Keys.OPERATING_MODE] = if (autoAllowed) {
                TradingOperatingMode.AUTOMATED.name
            } else {
                TradingOperatingMode.ASSISTANT.name
            }
        }
    }

    suspend fun setAutomatedAuthorized(authorized: Boolean) {
        context.jarvisDataStore.edit { prefs ->
            prefs[Keys.AUTO_AUTHORIZED] = authorized
            val current = prefs[Keys.ACTIVE_OPERATING_MODES]?.toMutableSet()
                ?: mutableSetOf(prefs[Keys.OPERATING_MODE] ?: TradingOperatingMode.OBSERVATION.name)
            if (!authorized) {
                current.remove(TradingOperatingMode.AUTOMATED.name)
                if (current.isEmpty()) current.add(TradingOperatingMode.OBSERVATION.name)
                prefs[Keys.ACTIVE_OPERATING_MODES] = current
                if (prefs[Keys.OPERATING_MODE] == TradingOperatingMode.AUTOMATED.name) {
                    prefs[Keys.OPERATING_MODE] = current.first()
                }
            }
        }
    }

    suspend fun setEmergencyStop(active: Boolean) {
        context.jarvisDataStore.edit { prefs ->
            prefs[Keys.EMERGENCY_STOP] = active
            if (active) {
                prefs[Keys.AUTO_AUTHORIZED] = false
                prefs[Keys.OPERATING_MODE] = TradingOperatingMode.OBSERVATION.name
                prefs[Keys.ACTIVE_OPERATING_MODES] = setOf(TradingOperatingMode.OBSERVATION.name)
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

    suspend fun setFloatingOrbEnabled(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.FLOATING_ORB_ENABLED] = enabled }
    }

    suspend fun setShowTapIndicator(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.SHOW_TAP_INDICATOR] = enabled }
    }

    suspend fun setShowHighlightIndicator(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.SHOW_HIGHLIGHT_INDICATOR] = enabled }
    }

    suspend fun setShowScrollIndicator(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.SHOW_SCROLL_INDICATOR] = enabled }
    }

    suspend fun setVoiceBiometricLockEnabled(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.VOICE_BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setVoiceFluAdaptationEnabled(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.VOICE_FLU_ADAPTATION] = enabled }
    }

    suspend fun setAutoLearnDeviceDaily(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.AUTO_LEARN_DAILY] = enabled }
    }

    suspend fun setFuturisticHudMode(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.FUTURISTIC_HUD_MODE] = enabled }
    }

    suspend fun markDailyLearnCompleted(timestampMs: Long = System.currentTimeMillis()) {
        context.jarvisDataStore.edit { it[Keys.LAST_DAILY_LEARN_MS] = timestampMs }
    }

    suspend fun setOrbInactivityTimeoutSeconds(seconds: Int) {
        context.jarvisDataStore.edit { it[Keys.ORB_INACTIVITY_SECONDS] = seconds.coerceIn(15, 300) }
    }

    suspend fun setKeepScreenAwakeWhileActive(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.KEEP_SCREEN_AWAKE] = enabled }
    }

    suspend fun setDeviceUnlockEnabled(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.DEVICE_UNLOCK_ENABLED] = enabled }
    }

    suspend fun setAutoOfflineSwitchEnabled(enabled: Boolean) {
        context.jarvisDataStore.edit { it[Keys.AUTO_OFFLINE_SWITCH_ENABLED] = enabled }
    }
}
