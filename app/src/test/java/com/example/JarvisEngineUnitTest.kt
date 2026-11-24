package com.example

import com.example.analysis.RiskValidationResult
import com.example.analysis.TechnicalAnalysisEngine
import com.example.analysis.TradingRiskEngine
import com.example.domain.model.OhlcCandle
import com.example.domain.model.RiskLimits
import com.example.domain.model.TradingOperatingMode
import com.example.security.SecretsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisEngineUnitTest {

    @Test
    fun `technical analysis calculates indicators and 12-point opportunity`() {
        val candles = (1..40).map { i ->
            val base = 1.0800 + i * 0.0008
            OhlcCandle(
                timestampMs = i * 3600_000L,
                open = base - 0.0003,
                high = base + 0.0009,
                low = base - 0.0006,
                close = base + 0.0005,
                volume = 1200.0 + i * 10
            )
        }
        val opp = TechnicalAnalysisEngine.analyze("EUR/USD", "Euro / US Dollar", "1h", candles)
        assertNotNull(opp)
        assertTrue(opp!!.indicators.rsi14 in 0.0..100.0)
        assertTrue(opp.indicators.bbUpper >= opp.indicators.bbMiddle)
        assertTrue(opp.indicators.bbMiddle >= opp.indicators.bbLower)
        assertTrue(opp.riskRewardRatio > 0.0)
        assertTrue(opp.invalidationConditions.isNotBlank())
    }

    @Test
    fun `risk engine enforces modes, authorization, emergency stop, and daily loss`() {
        val baseLimits = RiskLimits(
            automatedTradingAuthorized = false,
            emergencyStopActive = false,
            maxTradeAmountUsd = 100.0,
            maxDailyLossUsd = 200.0,
            currentDailyLossUsd = 0.0,
            maxOpenPositions = 2,
            requireStopLoss = true,
            requireTakeProfit = true,
            allowedAssets = setOf("EUR/USD", "BTC/USDT")
        )

        // Observation blocks everything
        val obs = TradingRiskEngine.validateOrder(
            TradingOperatingMode.OBSERVATION, baseLimits, "EUR/USD", 50.0, 1.07, 1.10, 0, true
        )
        assertTrue(obs is RiskValidationResult.Blocked)

        // Assistant requires explicit approval
        val assistUnapproved = TradingRiskEngine.validateOrder(
            TradingOperatingMode.ASSISTANT, baseLimits, "EUR/USD", 50.0, 1.07, 1.10, 0, false
        )
        assertTrue(assistUnapproved is RiskValidationResult.Blocked)

        val assistApproved = TradingRiskEngine.validateOrder(
            TradingOperatingMode.ASSISTANT, baseLimits, "EUR/USD", 50.0, 1.07, 1.10, 0, true
        )
        assertEquals(RiskValidationResult.Approved, assistApproved)

        // Automated blocked until explicitly authorized
        val autoUnauth = TradingRiskEngine.validateOrder(
            TradingOperatingMode.AUTOMATED, baseLimits, "EUR/USD", 50.0, 1.07, 1.10, 0, false
        )
        assertTrue(autoUnauth is RiskValidationResult.Blocked)

        // Daily loss triggers auto-suspend
        val lossBreach = TradingRiskEngine.validateOrder(
            TradingOperatingMode.AUTOMATED,
            baseLimits.copy(automatedTradingAuthorized = true, currentDailyLossUsd = 200.0),
            "EUR/USD", 50.0, 1.07, 1.10, 0, false
        )
        assertTrue(lossBreach is RiskValidationResult.Blocked && lossBreach.triggerAutoSuspend)

        // Emergency stop overrides even approved assistant trades
        val emergencyBlocked = TradingRiskEngine.validateOrder(
            TradingOperatingMode.ASSISTANT,
            baseLimits.copy(emergencyStopActive = true),
            "EUR/USD", 50.0, 1.07, 1.10, 0, true
        )
        assertTrue(emergencyBlocked is RiskValidationResult.Blocked)
    }

    @Test
    fun `secrets config normalizes bearer tokens, quotes, and supabase urls`() {
        assertEquals("tok_xyz", SecretsConfig.cleanSecret("  'Bearer tok_xyz\n' "))
        assertNull(SecretsConfig.cleanSecret("MY_SUPABASE_ANON_KEY"))
        assertNull(SecretsConfig.cleanSecret("your_token"))
        assertEquals("https://abc.supabase.co", SecretsConfig.normalizeHttpsUrl("abc.supabase.co/rest/v1/"))
        assertEquals("https://abc.supabase.co", SecretsConfig.normalizeHttpsUrl("abc"))
    }
}
