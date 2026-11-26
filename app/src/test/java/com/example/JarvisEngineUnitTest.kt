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
        assertEquals(SystemIntentKind.SWITCH_TO_MINI_ORB, orbCmd?.kind)

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

    @Test
    fun `system assistant parses cross-platform messaging, social navigation, visual overlays, learning, and voice lock intents`() {
        val waText = JarvisSystemAssistant.parseCommand("Text Alex hey how are you on WhatsApp")
        assertEquals(SystemIntentKind.TEXT_CONTACT, waText?.kind)
        assertEquals("WhatsApp", waText?.primaryArg)
        assertEquals("Alex|hey how are you", waText?.secondaryArg)

        val snapText = JarvisSystemAssistant.parseCommand("Message Sarah on Snapchat saying meet me at 5")
        assertEquals(SystemIntentKind.TEXT_CONTACT, snapText?.kind)
        assertEquals("Snapchat", snapText?.primaryArg)
        assertEquals("Sarah|meet me at 5", snapText?.secondaryArg)

        val igDm = JarvisSystemAssistant.parseCommand("DM John on Instagram saying thanks")
        assertEquals(SystemIntentKind.TEXT_CONTACT, igDm?.kind)
        assertEquals("Instagram", igDm?.primaryArg)
        assertEquals("John|thanks", igDm?.secondaryArg)

        val likePost = JarvisSystemAssistant.parseCommand("Like this post")
        assertEquals(SystemIntentKind.SOCIAL_ACTION, likePost?.kind)
        assertEquals("like", likePost?.primaryArg)

        val repostX = JarvisSystemAssistant.parseCommand("Repost on X")
        assertEquals(SystemIntentKind.SOCIAL_ACTION, repostX?.kind)
        assertEquals("repost", repostX?.primaryArg)
        assertEquals("X", repostX?.secondaryArg)

        val scrollTikTok = JarvisSystemAssistant.parseCommand("Scroll TikTok")
        assertEquals(SystemIntentKind.SOCIAL_ACTION, scrollTikTok?.kind)
        assertEquals("scroll_down", scrollTikTok?.primaryArg)
        assertEquals("TikTok", scrollTikTok?.secondaryArg)

        val nextVideo = JarvisSystemAssistant.parseCommand("Next video")
        assertEquals(SystemIntentKind.SOCIAL_ACTION, nextVideo?.kind)
        assertEquals("scroll_down", nextVideo?.primaryArg)

        val igDms = JarvisSystemAssistant.parseCommand("Open my DMs on Instagram")
        assertEquals(SystemIntentKind.SOCIAL_ACTION, igDms?.kind)
        assertEquals("dms", igDms?.primaryArg)
        assertEquals("Instagram", igDms?.secondaryArg)

        val studyDevice = JarvisSystemAssistant.parseCommand("Study my device")
        assertEquals(SystemIntentKind.STUDY_DEVICE, studyDevice?.kind)

        val showTaps = JarvisSystemAssistant.parseCommand("Show taps")
        assertEquals(SystemIntentKind.VISUAL_FEEDBACK_TOGGLE, showTaps?.kind)
        assertEquals("tap", showTaps?.primaryArg)
        assertEquals("true", showTaps?.secondaryArg)

        val hideHighlights = JarvisSystemAssistant.parseCommand("Hide highlights")
        assertEquals(SystemIntentKind.VISUAL_FEEDBACK_TOGGLE, hideHighlights?.kind)
        assertEquals("highlight", hideHighlights?.primaryArg)
        assertEquals("false", hideHighlights?.secondaryArg)

        val checkVoice = JarvisSystemAssistant.parseCommand("Check my voice")
        assertEquals(SystemIntentKind.VERIFY_VOICE, checkVoice?.kind)

        val briefing = JarvisSystemAssistant.parseCommand("Daily briefing")
        assertEquals(SystemIntentKind.DAILY_BRIEFING, briefing?.kind)

        val (stripped, richActions) = JarvisSystemAssistant.extractAndStripAiActions(
            "Let me handle that now, Sir. [ACTION:TEXT_CONTACT|WhatsApp|Alex|Hey] [ACTION:SOCIAL_ACTION|like|instagram] [ACTION:CLICK_INDEX|3]"
        )
        assertEquals("Let me handle that now, Sir.", stripped)
        assertEquals(3, richActions.size)
        assertEquals(SystemIntentKind.TEXT_CONTACT, richActions[0].kind)
        assertEquals("WhatsApp", richActions[0].primaryArg)
        assertEquals("Alex|Hey", richActions[0].secondaryArg)
        assertEquals(SystemIntentKind.SOCIAL_ACTION, richActions[1].kind)
        assertEquals(SystemIntentKind.CLICK_NODE_INDEX, richActions[2].kind)
        assertEquals("3", richActions[2].primaryArg)

        val snapshot = com.example.data.remote.NvidiaNimClient.LiveContextSnapshot(
            activeScreenSummary = "com.instagram.android",
            deviceKnowledgeSummary = "18 apps studied",
            indexedScreenMap = "[#1] Like (clickable)"
        )
        assertEquals("18 apps studied", snapshot.deviceKnowledgeSummary)
        assertEquals("[#1] Like (clickable)", snapshot.indexedScreenMap)
    }

    @Test
    fun `system assistant parses mini orb lifecycle, mute, app install, notifications, media, news, camera, and action tags`() {
        val toMini = JarvisSystemAssistant.parseCommand("Switch to Mini Orb")
        assertEquals(SystemIntentKind.SWITCH_TO_MINI_ORB, toMini?.kind)

        val toFull = JarvisSystemAssistant.parseCommand("Open full app")
        assertEquals(SystemIntentKind.OPEN_FULL_APP, toFull?.kind)

        val stayHere = JarvisSystemAssistant.parseCommand("Stay here")
        assertEquals(SystemIntentKind.ORB_CONTROL, stayHere?.kind)
        assertEquals("stay_here", stayHere?.primaryArg)

        val sleepOrb = JarvisSystemAssistant.parseCommand("Close the orb")
        assertEquals(SystemIntentKind.ORB_CONTROL, sleepOrb?.kind)
        assertEquals("sleep", sleepOrb?.primaryArg)

        val muteCmd = JarvisSystemAssistant.parseCommand("Mute mic")
        assertEquals(SystemIntentKind.MUTE_MIC, muteCmd?.kind)
        assertEquals("mute", muteCmd?.primaryArg)

        val unmuteCmd = JarvisSystemAssistant.parseCommand("Unmute")
        assertEquals(SystemIntentKind.MUTE_MIC, unmuteCmd?.kind)
        assertEquals("unmute", unmuteCmd?.primaryArg)

        val installCmd = JarvisSystemAssistant.parseCommand("Install Spotify")
        assertEquals(SystemIntentKind.INSTALL_APP, installCmd?.kind)
        assertEquals("Spotify", installCmd?.primaryArg)

        val notifCmd = JarvisSystemAssistant.parseCommand("Check my notifications")
        assertEquals(SystemIntentKind.READ_NOTIFICATIONS, notifCmd?.kind)

        val missedCallsCmd = JarvisSystemAssistant.parseCommand("Any missed calls")
        assertEquals(SystemIntentKind.READ_NOTIFICATIONS, missedCallsCmd?.kind)

        val playSpotify = JarvisSystemAssistant.parseCommand("Play jazz on Spotify")
        assertEquals(SystemIntentKind.MEDIA_PLAY_SEARCH, playSpotify?.kind)
        assertEquals("jazz", playSpotify?.primaryArg)
        assertEquals("Spotify", playSpotify?.secondaryArg)

        val newsCmd = JarvisSystemAssistant.parseCommand("Latest Kenyan news")
        assertEquals(SystemIntentKind.NEWS_RESEARCH, newsCmd?.kind)

        val selfieCmd = JarvisSystemAssistant.parseCommand("Take a selfie")
        assertEquals(SystemIntentKind.OPEN_CAMERA, selfieCmd?.kind)
        assertEquals("selfie", selfieCmd?.primaryArg)

        val reelsCmd = JarvisSystemAssistant.parseCommand("Open Reels")
        assertEquals(SystemIntentKind.SOCIAL_ACTION, reelsCmd?.kind)
        assertEquals("reels", reelsCmd?.primaryArg)

        val (stripped, actions) = JarvisSystemAssistant.extractAndStripAiActions(
            "Handling it now, Sir. [ACTION:FLOATING_ORB|open] [ACTION:MINI_ORB|open] [ACTION:INSTALL_APP|Spotify] [ACTION:READ_NOTIFICATIONS] [ACTION:NEWS_RESEARCH|Kenya] [ACTION:CAMERA|selfie]"
        )
        assertEquals("Handling it now, Sir.", stripped)
        assertEquals(6, actions.size)
        assertEquals(SystemIntentKind.FLOATING_ORB, actions[0].kind)
        assertEquals(SystemIntentKind.SWITCH_TO_MINI_ORB, actions[1].kind)
        assertEquals(SystemIntentKind.INSTALL_APP, actions[2].kind)
        assertEquals("Spotify", actions[2].primaryArg)
        assertEquals(SystemIntentKind.READ_NOTIFICATIONS, actions[3].kind)
        assertEquals(SystemIntentKind.NEWS_RESEARCH, actions[4].kind)
        assertEquals("Kenya", actions[4].primaryArg)
        assertEquals(SystemIntentKind.OPEN_CAMERA, actions[5].kind)
        assertEquals("selfie", actions[5].primaryArg)
    }
}
