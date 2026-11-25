package com.example

import com.example.analysis.RiskValidationResult
import com.example.analysis.TechnicalAnalysisEngine
import com.example.analysis.TradingRiskEngine
import com.example.assistant.JarvisSystemAssistant
import com.example.assistant.SystemIntentKind
import com.example.domain.model.OhlcCandle
import com.example.domain.model.RiskLimits
import com.example.domain.model.TradingOperatingMode
import com.example.domain.model.formatModeSummary
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
    fun `simultaneous operating modes validate cleanly across combinations`() {
        val allThreeModes = setOf(
            TradingOperatingMode.OBSERVATION,
            TradingOperatingMode.ASSISTANT,
            TradingOperatingMode.AUTOMATED
        )
        assertEquals("All Modes Active (Obs + Asst + Auto)", allThreeModes.formatModeSummary())

        val limitsUnauth = RiskLimits(
            automatedTradingAuthorized = false,
            emergencyStopActive = false,
            maxTradeAmountUsd = 100.0,
            maxDailyLossUsd = 200.0,
            allowedAssets = setOf("EUR/USD")
        )

        // With Obs+Asst+Auto, unapproved execution still requires automatedTradingAuthorized
        assertTrue(
            TradingRiskEngine.validateOrder(
                allThreeModes, limitsUnauth, "EUR/USD", 25.0, 1.07, 1.10, 0, false
            ) is RiskValidationResult.Blocked
        )

        // Once authorized, all three modes allow both automated and manual-approved orders
        val limitsAuth = limitsUnauth.copy(automatedTradingAuthorized = true)
        assertEquals(
            RiskValidationResult.Approved,
            TradingRiskEngine.validateOrder(allThreeModes, limitsAuth, "EUR/USD", 25.0, 1.07, 1.10, 0, false)
        )
        assertEquals(
            RiskValidationResult.Approved,
            TradingRiskEngine.validateOrder(allThreeModes, limitsAuth, "EUR/USD", 25.0, 1.07, 1.10, 0, true)
        )

        // Emergency stop overrides all three simultaneous modes
        assertTrue(
            TradingRiskEngine.validateOrder(
                allThreeModes, limitsAuth.copy(emergencyStopActive = true), "EUR/USD", 25.0, 1.07, 1.10, 0, true
            ) is RiskValidationResult.Blocked
        )
    }

    @Test
    fun `system assistant parses app launch, timers, alarms, mode and role switches and ai action tags`() {
        val openYt = JarvisSystemAssistant.parseCommand("Open YouTube")
        assertEquals(SystemIntentKind.OPEN_APP, openYt?.kind)
        assertEquals("YouTube", openYt?.primaryArg)

        val alarm = JarvisSystemAssistant.parseCommand("Set an alarm for 7:30 am called Market Open")
        assertEquals(SystemIntentKind.SET_ALARM, alarm?.kind)
        assertEquals(7, alarm?.hour)
        assertEquals(30, alarm?.minute)

        val timer = JarvisSystemAssistant.parseCommand("Set a timer for 5 minutes")
        assertEquals(SystemIntentKind.SET_TIMER, timer?.kind)
        assertEquals(300, timer?.durationSeconds)

        val marketingRole = JarvisSystemAssistant.parseCommand("Focus on marketing as your choice activity")
        assertEquals(SystemIntentKind.SWITCH_ACTIVITY_MARKETING, marketingRole?.kind)

        val assistantRole = JarvisSystemAssistant.parseCommand("Switch to assistant mode")
        assertEquals(SystemIntentKind.SWITCH_ACTIVITY_ASSISTANT, assistantRole?.kind)

        val allModes = JarvisSystemAssistant.parseCommand("Run all modes at the same time")
        assertEquals(SystemIntentKind.ENABLE_ALL_MODES, allModes?.kind)

        val (cleaned, actions) = JarvisSystemAssistant.extractAndStripAiActions(
            "Opening Spotify for you now, Sir. [ACTION:OPEN_APP|Spotify]"
        )
        assertEquals("Opening Spotify for you now, Sir.", cleaned)
        assertEquals(1, actions.size)
        assertEquals(SystemIntentKind.OPEN_APP, actions.first().kind)
        assertEquals("Spotify", actions.first().primaryArg)
    }

    @Test
    fun `system assistant parses platform searches, app typing, app settings, screen navigation, and signup assist`() {
        val ytSearch = JarvisSystemAssistant.parseCommand("Search YouTube for calm piano music")
        assertEquals(SystemIntentKind.PLATFORM_SEARCH, ytSearch?.kind)
        assertEquals("youtube", ytSearch?.primaryArg)
        assertEquals("calm piano music", ytSearch?.secondaryArg)

        val redditSearch = JarvisSystemAssistant.parseCommand("Look up Android automation on Reddit")
        assertEquals(SystemIntentKind.PLATFORM_SEARCH, redditSearch?.kind)
        assertEquals("reddit", redditSearch?.primaryArg)
        assertEquals("Android automation", redditSearch?.secondaryArg)

        val appSettings = JarvisSystemAssistant.parseCommand("Open WhatsApp settings")
        assertEquals(SystemIntentKind.APP_SETTINGS, appSettings?.kind)
        assertEquals("whatsapp", appSettings?.primaryArg)

        val wifiSettings = JarvisSystemAssistant.parseCommand("Open Wi-Fi settings")
        assertEquals(SystemIntentKind.OPEN_SETTINGS, wifiSettings?.kind)
        assertEquals("wifi", wifiSettings?.primaryArg)

        val writeCmd = JarvisSystemAssistant.parseCommand("Write Hello my friend inside WhatsApp")
        assertEquals(SystemIntentKind.TYPE_TEXT, writeCmd?.kind)
        assertEquals("Hello my friend", writeCmd?.primaryArg)
        assertEquals("WhatsApp", writeCmd?.secondaryArg)

        val tapCmd = JarvisSystemAssistant.parseCommand("Tap Sign Up button")
        assertEquals(SystemIntentKind.TAP_TEXT, tapCmd?.kind)
        assertEquals("Sign Up", tapCmd?.primaryArg)

        val backCmd = JarvisSystemAssistant.parseCommand("Go back")
        assertEquals(SystemIntentKind.GLOBAL_NAV, backCmd?.kind)
        assertEquals("back", backCmd?.primaryArg)

        val orbCmd = JarvisSystemAssistant.parseCommand("Open floating orb")
        assertEquals(SystemIntentKind.FLOATING_ORB, orbCmd?.kind)
        assertEquals("open", orbCmd?.primaryArg)

        val signupCmd = JarvisSystemAssistant.parseCommand("Help me create an account on GitHub with my permission")
        assertEquals(SystemIntentKind.SIGNUP_ASSIST, signupCmd?.kind)
        assertEquals("GitHub", signupCmd?.primaryArg)
        assertEquals("permitted", signupCmd?.secondaryArg)

        val signinCmd = JarvisSystemAssistant.parseCommand("Sign me in to GitHub with my permission")
        assertEquals(SystemIntentKind.SIGNIN_ASSIST, signinCmd?.kind)
        assertEquals("GitHub", signinCmd?.primaryArg)
        assertEquals("permitted", signinCmd?.secondaryArg)

        val fillCredsCmd = JarvisSystemAssistant.parseCommand("Fill my login credentials")
        assertEquals(SystemIntentKind.SIGNIN_ASSIST, fillCredsCmd?.kind)
        assertEquals("autofill", fillCredsCmd?.primaryArg)

        val guideCmd = JarvisSystemAssistant.parseCommand("Guide me on this screen")
        assertEquals(SystemIntentKind.GUIDE_SCREEN, guideCmd?.kind)

        val chromeDirect = JarvisSystemAssistant.parseCommand("Open tradingview.com in Chrome")
        assertEquals(SystemIntentKind.OPEN_URL, chromeDirect?.kind)
        assertEquals("https://tradingview.com", chromeDirect?.primaryArg)

        val unknownSite = JarvisSystemAssistant.parseCommand("Open ExamplePortal website")
        assertEquals(SystemIntentKind.OPEN_UNKNOWN_WEBSITE, unknownSite?.kind)
        assertEquals("ExamplePortal", unknownSite?.primaryArg)

        val (cleaned, actions) = JarvisSystemAssistant.extractAndStripAiActions(
            "Typing your message and opening WhatsApp settings, Sir. [ACTION:TYPE_TEXT|On my way!|message] [ACTION:APP_SETTINGS|WhatsApp] [ACTION:GLOBAL_NAV|notifications]"
        )
        assertEquals("Typing your message and opening WhatsApp settings, Sir.", cleaned)
        assertEquals(3, actions.size)
        assertEquals(SystemIntentKind.TYPE_TEXT, actions[0].kind)
        assertEquals("On my way!", actions[0].primaryArg)
        assertEquals(SystemIntentKind.APP_SETTINGS, actions[1].kind)
        assertEquals(SystemIntentKind.GLOBAL_NAV, actions[2].kind)
    }

    @Test
    fun `system assistant parses compound chrome and sign-in commands sequentially`() {
        val chromeAndSignIn = JarvisSystemAssistant.parseCommands("open chrome and sign me in")
        assertEquals(2, chromeAndSignIn.size)
        assertEquals(SystemIntentKind.OPEN_APP, chromeAndSignIn[0].kind)
        assertEquals("Chrome", chromeAndSignIn[0].primaryArg)
        assertEquals(SystemIntentKind.SIGNIN_ASSIST, chromeAndSignIn[1].kind)
        assertEquals("autofill", chromeAndSignIn[1].primaryArg)

        val siteAndSignIn = JarvisSystemAssistant.parseCommands("open github and sign me in")
        assertEquals(2, siteAndSignIn.size)
        assertEquals(SystemIntentKind.OPEN_APP, siteAndSignIn[0].kind)
        assertEquals(SystemIntentKind.SIGNIN_ASSIST, siteAndSignIn[1].kind)
        assertEquals("github", siteAndSignIn[1].primaryArg)

        val chromeSiteSignIn = JarvisSystemAssistant.parseCommands("open chrome and sign me in to tradingview")
        assertEquals(2, chromeSiteSignIn.size)
        assertEquals(SystemIntentKind.OPEN_APP, chromeSiteSignIn[0].kind)
        assertEquals(SystemIntentKind.SIGNIN_ASSIST, chromeSiteSignIn[1].kind)
        assertEquals("tradingview", chromeSiteSignIn[1].primaryArg)

        val (stripped, tolerantActions) = JarvisSystemAssistant.extractAndStripAiActions(
            "Opening Chrome now, Sir. [action: OPEN_CHROME | Chrome] [ACTION: SIGNIN_ASSIST | autofill]"
        )
        assertEquals("Opening Chrome now, Sir.", stripped)
        assertEquals(2, tolerantActions.size)
        assertEquals(SystemIntentKind.OPEN_APP, tolerantActions[0].kind)
        assertEquals("Chrome", tolerantActions[0].primaryArg)
        assertEquals(SystemIntentKind.SIGNIN_ASSIST, tolerantActions[1].kind)
    }

    @Test
    fun `web knowledge client sanitizes public urls and parses search snippets`() {
        assertEquals("https://www.tradingview.com/chart/", JarvisSystemAssistant.extractDirectUrlOrDomain("Open www.tradingview.com/chart/ please"))
        assertNull(com.example.data.remote.WebKnowledgeClient.sanitizePublicHttpUrl("javascript:alert(1)"))
        assertNull(com.example.data.remote.WebKnowledgeClient.sanitizePublicHttpUrl("http://localhost:8080/admin"))
        assertNull(com.example.data.remote.WebKnowledgeClient.sanitizePublicHttpUrl("http://192.168.1.50/router"))
        assertEquals(
            "https://example.org/docs",
            com.example.data.remote.WebKnowledgeClient.sanitizePublicHttpUrl("https://example.org/docs")
        )

        val sampleHtml = """
            <div class="result">
              <a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.exness.com%2F">Exness Official Site</a>
              <a class="result__snippet">Global multi-asset trading platform overview.</a>
            </div>
        """.trimIndent()
        val parsed = com.example.data.remote.WebKnowledgeClient(okhttp3.OkHttpClient()).parseDuckDuckGoHtml(sampleHtml)
        assertEquals(1, parsed.size)
        assertEquals("Exness Official Site", parsed.first().title)
        assertEquals("https://www.exness.com/", parsed.first().url)
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
