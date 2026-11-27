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

        // Compound messaging / search / social flows carry platform context cleanly
        val waCompoundMsg = JarvisSystemAssistant.parseCommands("open WhatsApp and text Alex hey")
        assertEquals(1, waCompoundMsg.size)
        assertEquals(SystemIntentKind.TEXT_CONTACT, waCompoundMsg[0].kind)
        assertEquals("WhatsApp", waCompoundMsg[0].primaryArg)
        assertEquals("Alex|hey", waCompoundMsg[0].secondaryArg)

        val waCompoundNoMsg = JarvisSystemAssistant.parseCommands("open WhatsApp and text Sarah")
        assertEquals(1, waCompoundNoMsg.size)
        assertEquals(SystemIntentKind.TEXT_CONTACT, waCompoundNoMsg[0].kind)
        assertEquals("WhatsApp", waCompoundNoMsg[0].primaryArg)
        assertEquals("Sarah|", waCompoundNoMsg[0].secondaryArg)

        val waCalledSaying = JarvisSystemAssistant.parseCommands("open WhatsApp and message someone called John saying I'm on my way")
        assertEquals(1, waCalledSaying.size)
        assertEquals(SystemIntentKind.TEXT_CONTACT, waCalledSaying[0].kind)
        assertEquals("WhatsApp", waCalledSaying[0].primaryArg)
        assertEquals("John|I'm on my way", waCalledSaying[0].secondaryArg)

        val igCompoundDm = JarvisSystemAssistant.parseCommands("open Instagram and DM Maya hey")
        assertEquals(1, igCompoundDm.size)
        assertEquals(SystemIntentKind.TEXT_CONTACT, igCompoundDm[0].kind)
        assertEquals("Instagram", igCompoundDm[0].primaryArg)
        assertEquals("Maya|hey", igCompoundDm[0].secondaryArg)

        val chromeSearch = JarvisSystemAssistant.parseCommands("open Chrome and search bitcoin price")
        assertEquals(1, chromeSearch.size)
        assertEquals(SystemIntentKind.WEB_SEARCH, chromeSearch[0].kind)
        assertEquals("bitcoin price", chromeSearch[0].primaryArg)

        val igLike = JarvisSystemAssistant.parseCommands("open Instagram and like this post")
        assertEquals(1, igLike.size)
        assertEquals(SystemIntentKind.SOCIAL_ACTION, igLike[0].kind)
        assertEquals("like", igLike[0].primaryArg)
        assertEquals("Instagram", igLike[0].secondaryArg)
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

    @Test
    fun `voice catalog exposes seven distinct Magpie neural voices`() {
        val voices = com.example.domain.model.VoicePersonality.entries
        assertEquals(7, voices.size)
        assertEquals(7, voices.map { it.nimVoiceName }.distinct().size)
        assertEquals(7, voices.map { it.label }.distinct().size)
        voices.forEach { voice ->
            assertTrue(voice.label.isNotBlank())
            assertTrue(voice.nimVoiceName.startsWith("Magpie-Multilingual.EN-US."))
        }
    }

    @Test
    fun `system assistant parses keyboard key presses, tabs, power sleep shutdown restart, and unlock commands`() {
        val enterKey = JarvisSystemAssistant.parseCommand("Press enter")
        assertEquals(SystemIntentKind.PRESS_KEY, enterKey?.kind)
        assertEquals("enter", enterKey?.primaryArg?.lowercase())

        val backspaceKey = JarvisSystemAssistant.parseCommand("Tap backspace key")
        assertEquals(SystemIntentKind.PRESS_KEY, backspaceKey?.kind)
        assertEquals("backspace", backspaceKey?.primaryArg?.lowercase())

        val tabCmd = JarvisSystemAssistant.parseCommand("Switch to Chats tab")
        assertEquals(SystemIntentKind.TAP_TEXT, tabCmd?.kind)
        assertEquals("Chats", tabCmd?.primaryArg)

        val tapTabCmd = JarvisSystemAssistant.parseCommand("Tap Updates tab")
        assertEquals(SystemIntentKind.TAP_TEXT, tapTabCmd?.kind)
        assertEquals("Updates", tapTabCmd?.primaryArg)

        val sleepDevice = JarvisSystemAssistant.parseCommand("Make the device sleep")
        assertEquals(SystemIntentKind.DEVICE_POWER, sleepDevice?.kind)
        assertEquals("sleep", sleepDevice?.primaryArg)

        val lockScreen = JarvisSystemAssistant.parseCommand("Lock my screen")
        assertEquals(SystemIntentKind.DEVICE_POWER, lockScreen?.kind)
        assertEquals("sleep", lockScreen?.primaryArg)

        val shutdownDevice = JarvisSystemAssistant.parseCommand("Shut down phone")
        assertEquals(SystemIntentKind.DEVICE_POWER, shutdownDevice?.kind)
        assertEquals("shutdown", shutdownDevice?.primaryArg)

        val restartDevice = JarvisSystemAssistant.parseCommand("Restart the phone")
        assertEquals(SystemIntentKind.DEVICE_POWER, restartDevice?.kind)
        assertEquals("restart", restartDevice?.primaryArg)

        val unlockDevice = JarvisSystemAssistant.parseCommand("Open the device")
        assertEquals(SystemIntentKind.UNLOCK_DEVICE, unlockDevice?.kind)

        val unlockPhone = JarvisSystemAssistant.parseCommand("Unlock my phone")
        assertEquals(SystemIntentKind.UNLOCK_DEVICE, unlockPhone?.kind)

        val (stripped, actions) = JarvisSystemAssistant.extractAndStripAiActions(
            "Right away, Sir. [ACTION:PRESS_KEY|enter] [ACTION:DEVICE_POWER|restart] [ACTION:UNLOCK_DEVICE]"
        )
        assertEquals("Right away, Sir.", stripped)
        assertEquals(3, actions.size)
        assertEquals(SystemIntentKind.PRESS_KEY, actions[0].kind)
        assertEquals("enter", actions[0].primaryArg)
        assertEquals(SystemIntentKind.DEVICE_POWER, actions[1].kind)
        assertEquals("restart", actions[1].primaryArg)
        assertEquals(SystemIntentKind.UNLOCK_DEVICE, actions[2].kind)
    }

    @Test
    fun `offline assistant engine evaluates math deterministically without internet`() {
        assertEquals("25 * 4 = 100", com.example.assistant.OfflineAssistantEngine.tryEvaluateMath("what is 25 times 4"))
        assertEquals("15% of 200 is 30", com.example.assistant.OfflineAssistantEngine.tryEvaluateMath("calculate 15% of 200"))
        assertEquals("Division by zero is undefined", com.example.assistant.OfflineAssistantEngine.tryEvaluateMath("10 / 0"))
        assertNull(com.example.assistant.OfflineAssistantEngine.tryEvaluateMath("explain quantum entanglement"))
    }

    @Test
    fun `scheduled automation parser handles timed tasks, list, cancel, and HUD_TITLE tags`() {
        val now = 1_760_000_000_000L
        val relativeTask = com.example.assistant.ScheduledAutomationEngine.parseScheduleCommand(
            "In 15 minutes remind me to check my emails",
            nowMs = now
        )
        assertNotNull(relativeTask)
        assertEquals(now + 15 * 60_000L, relativeTask!!.triggerAtMs)
        assertTrue(relativeTask.commandPrompt.contains("check my emails", ignoreCase = true))

        val dailyBrief = JarvisSystemAssistant.parseCommand("Every morning at 8:00 AM give me my daily briefing")
        assertEquals(SystemIntentKind.SCHEDULE_TASK, dailyBrief?.kind)
        assertTrue(dailyBrief?.primaryArg?.contains("|true|") == true)

        val listCmd = JarvisSystemAssistant.parseCommand("List my automations")
        assertEquals(SystemIntentKind.LIST_AUTOMATIONS, listCmd?.kind)

        val cancelCmd = JarvisSystemAssistant.parseCommand("Cancel automation daily briefing")
        assertEquals(SystemIntentKind.CANCEL_AUTOMATION, cancelCmd?.kind)
        assertEquals("daily briefing", cancelCmd?.primaryArg)

        val (hudClean, hudTitle) = JarvisSystemAssistant.extractHudTitleAndClean(
            "Queued your morning routine, Sir. [HUD_TITLE:Morning Brief Armed] [ACTION:SCHEDULE_TASK|07:30|true|Morning Brief|Daily briefing]"
        )
        assertEquals("Morning Brief Armed", hudTitle)
        val (fullyClean, actions) = JarvisSystemAssistant.extractAndStripAiActions(hudClean)
        assertEquals("Queued your morning routine, Sir.", fullyClean)
        assertEquals(1, actions.size)
        assertEquals(SystemIntentKind.SCHEDULE_TASK, actions.first().kind)
    }

    @Test
    fun `hud intelligence dynamically switches headlines and generates real mission suggestions`() {
        val voiceSpot = com.example.assistant.JarvisHudIntelligence.buildSpotlight(
            partialVoiceText = "open whatsapp and text alex",
            isListening = true,
            isProcessing = false,
            isSpeaking = false,
            isExecuting = false,
            isMicMuted = false,
            lastUserPrompt = "",
            aiChosenHeadline = null,
            taskProgressTitle = "",
            pendingConfirmationSummary = null,
            isOnline = true,
            batteryPercent = 82,
            isCharging = false,
            unreadNotificationCount = 0,
            learnedAppsCount = 12,
            memoriesCount = 3,
            activeAutomations = emptyList(),
            recentMemories = emptyList(),
            rotationTick = 0
        )
        assertEquals("VOICE STREAM ACTIVE", voiceSpot.kicker)
        assertTrue(voiceSpot.headline.contains("Open whatsapp", ignoreCase = true))

        val aiSpot = com.example.assistant.JarvisHudIntelligence.buildSpotlight(
            partialVoiceText = "",
            isListening = false,
            isProcessing = false,
            isSpeaking = false,
            isExecuting = false,
            isMicMuted = false,
            lastUserPrompt = "Check market status",
            aiChosenHeadline = "Live Market Radar",
            taskProgressTitle = "",
            pendingConfirmationSummary = null,
            isOnline = true,
            batteryPercent = 82,
            isCharging = false,
            unreadNotificationCount = 2,
            learnedAppsCount = 12,
            memoriesCount = 3,
            activeAutomations = emptyList(),
            recentMemories = emptyList(),
            rotationTick = 0
        )
        assertEquals("ACTIVE MISSION FOCUS", aiSpot.kicker)
        assertTrue(aiSpot.headline.contains("Live Market Radar"))

        val suggestions = com.example.assistant.JarvisHudIntelligence.buildAutogeneratedSuggestions(
            isOnline = true,
            batteryPercent = 18,
            unreadNotificationCount = 3,
            learnedAppsCount = 0,
            activeAutomations = emptyList(),
            learnedApps = emptyList()
        )
        assertTrue(suggestions.any { it.id == "triage_alerts" })
        assertTrue(suggestions.any { it.id == "schedule_morning_brief" })
    }
}
