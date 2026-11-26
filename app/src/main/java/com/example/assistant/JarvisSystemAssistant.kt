package com.example.assistant

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import com.example.data.remote.WebKnowledgeClient
import com.example.security.SecureCredentialStore
import com.example.voice.JarvisFloatingOrbService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SystemIntentKind {
    OPEN_APP,
    CALL_DIAL,
    SEND_SMS,
    SEND_EMAIL,
    SET_ALARM,
    SET_TIMER,
    ADD_CALENDAR_EVENT,
    WEB_SEARCH,
    WEB_LOOKUP,
    PLATFORM_SEARCH,
    OPEN_URL,
    OPEN_UNKNOWN_WEBSITE,
    OPEN_MAPS,
    PLAY_YOUTUBE,
    OPEN_CAMERA,
    OPEN_VIDEO_CAMERA,
    MEDIA_CONTROL,
    OPEN_SETTINGS,
    APP_SETTINGS,
    TYPE_TEXT,
    TAP_TEXT,
    SCROLL_SCREEN,
    GLOBAL_NAV,
    READ_SCREEN,
    GUIDE_SCREEN,
    SIGNUP_ASSIST,
    SIGNIN_ASSIST,
    FLOATING_ORB,
    DEVICE_STATUS,
    TIME_DATE,
    SWITCH_ACTIVITY_MARKETING,
    SWITCH_ACTIVITY_ASSISTANT,
    ENABLE_ALL_MODES,
    NAVIGATE_SECTION,
    TEXT_CONTACT,
    SOCIAL_ACTION,
    STUDY_DEVICE,
    VISUAL_FEEDBACK_TOGGLE,
    CLICK_NODE_INDEX,
    SEARCH_IN_APP,
    VERIFY_VOICE,
    DAILY_BRIEFING,
    AUTONOMOUS_TASK,
    SWITCH_TO_MINI_ORB,
    OPEN_FULL_APP,
    ORB_CONTROL,
    MUTE_MIC,
    INSTALL_APP,
    READ_NOTIFICATIONS,
    MEDIA_PLAY_SEARCH,
    NEWS_RESEARCH
}

data class ParsedSystemCommand(
    val kind: SystemIntentKind,
    val primaryArg: String = "",
    val secondaryArg: String = "",
    val hour: Int? = null,
    val minute: Int? = null,
    val durationSeconds: Int? = null
)

data class InstalledAppSummary(
    val appName: String,
    val packageName: String
)

data class SystemExecutionResult(
    val handled: Boolean,
    val replyText: String,
    val actionLabel: String,
    val launchedTarget: Boolean = false
)

object JarvisSystemAssistant {

    private val actionTagRegex = Regex(
        "\\[\\s*ACTION\\s*:\\s*([A-Za-z_]+)(?:\\s*\\|\\s*([^\\]]*))?\\s*\\]",
        RegexOption.IGNORE_CASE
    )

    private val deviceSettingKeywords = setOf(
        "wifi", "wi-fi", "bluetooth", "display", "brightness", "sound", "volume",
        "battery", "location", "gps", "assistant", "security", "privacy",
        "accessibility", "overlay", "storage", "apps", "all apps", "notifications",
        "date", "time", "language", "developer", "network", "mobile data", "internet",
        "general", "system", "device", "nfc", "vpn", "cast", "hotspot", "tethering",
        "accounts", "keyboard", "about", "about phone", "dark theme", "night display"
    )

    private val platformKeys = setOf(
        "youtube", "yt", "google", "reddit", "x", "twitter", "tiktok", "instagram",
        "github", "wikipedia", "wiki", "amazon", "play store", "google play",
        "binance", "tradingview", "duckduckgo", "facebook", "linkedin", "snapchat", "threads"
    )

    private val messagingPlatformNames = mapOf(
        "whatsapp" to "WhatsApp",
        "whats app" to "WhatsApp",
        "snapchat" to "Snapchat",
        "snap" to "Snapchat",
        "instagram" to "Instagram",
        "insta" to "Instagram",
        "ig" to "Instagram",
        "telegram" to "Telegram",
        "messenger" to "Messenger",
        "facebook messenger" to "Messenger",
        "signal" to "Signal",
        "discord" to "Discord",
        "sms" to "Messages",
        "messages" to "Messages"
    )

    private val socialPlatformNames = setOf(
        "instagram", "insta", "ig", "tiktok", "tik tok", "x", "twitter", "reddit",
        "youtube", "yt", "facebook", "fb", "linkedin", "snapchat", "snap", "threads"
    )

    val websiteDirectory = mapOf(
        "google" to "https://www.google.com",
        "youtube" to "https://www.youtube.com",
        "gmail" to "https://mail.google.com",
        "reddit" to "https://www.reddit.com",
        "github" to "https://github.com",
        "instagram" to "https://www.instagram.com",
        "x" to "https://x.com",
        "twitter" to "https://x.com",
        "tiktok" to "https://www.tiktok.com",
        "facebook" to "https://www.facebook.com",
        "linkedin" to "https://www.linkedin.com",
        "wikipedia" to "https://www.wikipedia.org",
        "amazon" to "https://www.amazon.com",
        "chatgpt" to "https://chatgpt.com",
        "openai" to "https://platform.openai.com",
        "claude" to "https://claude.ai",
        "perplexity" to "https://www.perplexity.ai",
        "tradingview" to "https://www.tradingview.com",
        "deriv" to "https://app.deriv.com",
        "binance" to "https://www.binance.com",
        "coinbase" to "https://www.coinbase.com",
        "bybit" to "https://www.bybit.com",
        "exness" to "https://www.exness.com",
        "xm" to "https://www.xm.com",
        "pocket option" to "https://pocketoption.com",
        "quotex" to "https://qxbroker.com",
        "olymp trade" to "https://olymptrade.com",
        "investing" to "https://www.investing.com",
        "yahoo finance" to "https://finance.yahoo.com",
        "netflix" to "https://www.netflix.com",
        "spotify" to "https://open.spotify.com",
        "canva" to "https://www.canva.com"
    )

    private val signupUrls = mapOf(
        "reddit" to "https://www.reddit.com/register/",
        "github" to "https://github.com/signup",
        "instagram" to "https://www.instagram.com/accounts/emailsignup/",
        "x" to "https://x.com/i/flow/signup",
        "twitter" to "https://x.com/i/flow/signup",
        "tiktok" to "https://www.tiktok.com/signup",
        "facebook" to "https://www.facebook.com/r.php",
        "linkedin" to "https://www.linkedin.com/signup",
        "tradingview" to "https://www.tradingview.com/accounts/signin/#signup",
        "binance" to "https://accounts.binance.com/en/register",
        "deriv" to "https://deriv.com/signup/",
        "exness" to "https://my.exness.com/accounts/sign-up",
        "amazon" to "https://www.amazon.com/ap/register",
        "google" to "https://accounts.google.com/signup"
    )

    private val signinUrls = mapOf(
        "reddit" to "https://www.reddit.com/login/",
        "github" to "https://github.com/login",
        "instagram" to "https://www.instagram.com/accounts/login/",
        "x" to "https://x.com/i/flow/login",
        "twitter" to "https://x.com/i/flow/login",
        "facebook" to "https://www.facebook.com/login/",
        "linkedin" to "https://www.linkedin.com/login",
        "tradingview" to "https://www.tradingview.com/accounts/signin/",
        "binance" to "https://accounts.binance.com/en/login",
        "deriv" to "https://oauth.deriv.com/oauth2/authorize?app_id=16929",
        "exness" to "https://my.exness.com/accounts/sign-in",
        "google" to "https://accounts.google.com/"
    )

    private val knownAppAliases: Map<String, List<String>> = mapOf(
        "youtube" to listOf("com.google.android.youtube"),
        "yt" to listOf("com.google.android.youtube"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "chrome" to listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome"),
        "google chrome" to listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome"),
        "browser" to listOf("com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.android.browser"),
        "gmail" to listOf("com.google.android.gm"),
        "mail" to listOf("com.google.android.gm"),
        "email" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"),
        "google maps" to listOf("com.google.android.apps.maps"),
        "spotify" to listOf("com.spotify.music"),
        "telegram" to listOf("org.telegram.messenger"),
        "instagram" to listOf("com.instagram.android"),
        "insta" to listOf("com.instagram.android"),
        "ig" to listOf("com.instagram.android"),
        "snapchat" to listOf("com.snapchat.android"),
        "snap" to listOf("com.snapchat.android"),
        "messenger" to listOf("com.facebook.orca"),
        "discord" to listOf("com.discord"),
        "signal" to listOf("org.thoughtcrime.securesms"),
        "threads" to listOf("com.instagram.barcelona"),
        "pinterest" to listOf("com.pinterest"),
        "twitch" to listOf("tv.twitch.android.app"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "facebook" to listOf("com.facebook.katana"),
        "x" to listOf("com.twitter.android"),
        "twitter" to listOf("com.twitter.android"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "reddit" to listOf("com.reddit.frontpage"),
        "binance" to listOf("com.binance.dev"),
        "deriv" to listOf("com.deriv.app", "com.deriv.trader"),
        "metatrader 5" to listOf("net.metaquotes.metatrader5"),
        "mt5" to listOf("net.metaquotes.metatrader5"),
        "metatrader 4" to listOf("net.metaquotes.metatrader4"),
        "mt4" to listOf("net.metaquotes.metatrader4"),
        "metatrader" to listOf("net.metaquotes.metatrader5", "net.metaquotes.metatrader4"),
        "tradingview" to listOf("com.tradingview.tradingviewapp"),
        "play store" to listOf("com.android.vending"),
        "google play" to listOf("com.android.vending"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator"),
        "calendar" to listOf("com.google.android.calendar", "com.android.calendar"),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage"),
        "contacts" to listOf("com.google.android.contacts", "com.android.contacts"),
        "messages" to listOf("com.google.android.apps.messaging", "com.android.messaging"),
        "phone" to listOf("com.google.android.dialer", "com.android.dialer"),
        "dialer" to listOf("com.google.android.dialer", "com.android.dialer"),
        "photos" to listOf("com.google.android.apps.photos"),
        "gallery" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "files" to listOf("com.google.android.documentsui", "com.google.android.apps.nbu.files"),
        "notes" to listOf("com.google.android.keep"),
        "keep" to listOf("com.google.android.keep"),
        "drive" to listOf("com.google.android.apps.docs"),
        "settings" to listOf("com.android.settings")
    )

    private val internalSectionKeywords = setOf(
        "home", "dashboard", "jarvis", "chat", "credentials", "vault", "plugin", "history", "activity"
    )

    fun extractDirectUrlOrDomain(raw: String): String? {
        val urlRegex = Regex(
            "(https?://[^\\s]+|(?:www\\.)?[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*\\.(?:com|org|net|io|ai|co|dev|app|xyz|me|uk|us|ca|eu|ng|za|ke|info|biz|tv|gg)(?:/[^\\s]*)?)",
            RegexOption.IGNORE_CASE
        )
        val found = urlRegex.find(raw.trim())?.value?.trim()?.trimEnd('.', ',', ';', ')', ']') ?: return null
        val candidate = if (found.startsWith("http://", ignoreCase = true) || found.startsWith("https://", ignoreCase = true)) {
            found
        } else {
            "https://$found"
        }
        return WebKnowledgeClient.sanitizePublicHttpUrl(candidate)
    }

    private val compoundActionLeadRegex = Regex(
        "^(?:please\\s+)?(?:open|launch|start|run|go\\s+to|visit|sign|log|fill|autofill|auto-fill|enter|use|type|write|search|find|look\\s+up|tap|click|press|scroll|swipe|guide|read|set|enable|turn|show|close|hide|minimize|like|love|repost|retweet|share|save|bookmark|follow|subscribe|next|text|message|dm|send\\s+(?:a\\s+)?(?:text|message|dm)|study|check|verify)\\b",
        RegexOption.IGNORE_CASE
    )

    private val browserTargetNames = setOf("chrome", "google chrome", "browser", "internet")

    fun parseCommands(rawPrompt: String): List<ParsedSystemCommand> {
        val trimmed = rawPrompt.trim()
        if (trimmed.isBlank()) return emptyList()

        val separatorRegex = Regex(
            "(?:\\s*,\\s*then\\s+|\\s+and\\s+then\\s+|\\s+then\\s+|\\s*;\\s*|\\s*,\\s+and\\s+|\\s+and\\s+|\\s*&\\s*)",
            RegexOption.IGNORE_CASE
        )
        val rawSegments = trimmed.split(separatorRegex).map { it.trim() }.filter { it.isNotBlank() }
        if (rawSegments.size in 2..4 && rawSegments.all { compoundActionLeadRegex.containsMatchIn(it) }) {
            var activeMessagingPlatform: String? = null
            var activeSocialPlatform: String? = null
            var activeBrowser = false
            var activeOpenedApp: String? = null
            val parsedList = mutableListOf<ParsedSystemCommand>()

            for (seg in rawSegments) {
                val segLower = seg.lowercase(Locale.US)
                val parsed = parseCommandWithContext(
                    seg = seg,
                    segLower = segLower,
                    inheritedMessagingPlatform = activeMessagingPlatform,
                    inheritedSocialPlatform = activeSocialPlatform,
                    inheritedBrowser = activeBrowser,
                    inheritedApp = activeOpenedApp
                ) ?: break

                when (parsed.kind) {
                    SystemIntentKind.OPEN_APP -> {
                        val appLower = parsed.primaryArg.lowercase(Locale.US).trim()
                        activeOpenedApp = parsed.primaryArg
                        if (appLower in browserTargetNames) {
                            activeBrowser = true
                        }
                        messagingPlatformNames[appLower]?.let { activeMessagingPlatform = it }
                        if (appLower in socialPlatformNames) {
                            activeSocialPlatform = canonicalSocialPlatformName(appLower)
                        }
                    }
                    else -> Unit
                }
                parsedList.add(parsed)
            }

            if (parsedList.size == rawSegments.size) {
                // Propagate context & collapse redundant prefix OPEN_APP steps when the next action opens the target directly
                val refined = mutableListOf<ParsedSystemCommand>()
                var pendingSiteTarget: String? = null
                for (cmd in parsedList) {
                    when (cmd.kind) {
                        SystemIntentKind.OPEN_APP -> {
                            val argLower = cmd.primaryArg.lowercase(Locale.US)
                            if (argLower !in browserTargetNames) {
                                pendingSiteTarget = cmd.primaryArg
                            }
                            refined.add(cmd)
                        }
                        SystemIntentKind.SIGNIN_ASSIST, SystemIntentKind.SIGNUP_ASSIST -> {
                            val targetArg = cmd.primaryArg.lowercase(Locale.US)
                            val carried = if ((targetArg.isBlank() || targetArg == "autofill") && pendingSiteTarget != null) {
                                pendingSiteTarget
                            } else cmd.primaryArg
                            refined.add(cmd.copy(primaryArg = carried))
                        }
                        SystemIntentKind.TEXT_CONTACT -> {
                            val prev = refined.lastOrNull()
                            if (prev?.kind == SystemIntentKind.OPEN_APP) {
                                val prevPlat = messagingPlatformNames[prev.primaryArg.lowercase(Locale.US)]
                                    ?: prev.primaryArg
                                if (prevPlat.equals(cmd.primaryArg, ignoreCase = true)) {
                                    refined.removeAt(refined.lastIndex)
                                }
                            }
                            refined.add(cmd)
                        }
                        SystemIntentKind.OPEN_URL, SystemIntentKind.OPEN_UNKNOWN_WEBSITE, SystemIntentKind.WEB_SEARCH -> {
                            val prev = refined.lastOrNull()
                            if (prev?.kind == SystemIntentKind.OPEN_APP &&
                                prev.primaryArg.lowercase(Locale.US) in browserTargetNames
                            ) {
                                refined.removeAt(refined.lastIndex)
                            }
                            refined.add(cmd)
                        }
                        SystemIntentKind.SOCIAL_ACTION -> {
                            val prev = refined.lastOrNull()
                            if (prev?.kind == SystemIntentKind.OPEN_APP && cmd.secondaryArg.isBlank()) {
                                val socialName = canonicalSocialPlatformName(prev.primaryArg.lowercase(Locale.US))
                                refined.removeAt(refined.lastIndex)
                                refined.add(cmd.copy(secondaryArg = socialName))
                            } else {
                                refined.add(cmd)
                            }
                        }
                        else -> refined.add(cmd)
                    }
                }
                return refined
            }
        }

        return listOfNotNull(parseCommand(trimmed))
    }

    private fun parseCommandWithContext(
        seg: String,
        segLower: String,
        inheritedMessagingPlatform: String?,
        inheritedSocialPlatform: String?,
        inheritedBrowser: Boolean,
        inheritedApp: String?
    ): ParsedSystemCommand? {
        parseCommand(seg)?.let { direct ->
            if (direct.kind == SystemIntentKind.SOCIAL_ACTION && direct.secondaryArg.isBlank() && !inheritedSocialPlatform.isNullOrBlank()) {
                return direct.copy(secondaryArg = inheritedSocialPlatform)
            }
            return direct
        }

        if (inheritedMessagingPlatform != null) {
            parseTextContactCommand(seg, defaultPlatform = inheritedMessagingPlatform)?.let { return it }
        }

        // Handle "search [for] <query>" right after "open <app/chrome>"
        val genericSearch = Regex(
            "^(?:please\\s+)?(?:search(?:\\s+for)?|look\\s+up|find)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(seg)?.groupValues?.get(1)?.trim()
        if (!genericSearch.isNullOrBlank()) {
            val appLower = inheritedApp?.lowercase(Locale.US).orEmpty()
            return when {
                inheritedBrowser -> ParsedSystemCommand(SystemIntentKind.WEB_SEARCH, genericSearch)
                appLower in platformKeys -> ParsedSystemCommand(SystemIntentKind.PLATFORM_SEARCH, appLower, genericSearch)
                !inheritedApp.isNullOrBlank() -> ParsedSystemCommand(SystemIntentKind.SEARCH_IN_APP, genericSearch)
                else -> ParsedSystemCommand(SystemIntentKind.WEB_SEARCH, genericSearch)
            }
        }

        // Handle "scroll <app>" right after "open <app>"
        if (segLower.startsWith("scroll ") && inheritedSocialPlatform != null) {
            val dir = if (segLower.contains("up")) "scroll_up" else "scroll_down"
            return ParsedSystemCommand(SystemIntentKind.SOCIAL_ACTION, dir, inheritedSocialPlatform)
        }

        return null
    }

    private fun canonicalSocialPlatformName(lowerName: String): String = when (lowerName.trim()) {
        "insta", "ig", "instagram" -> "Instagram"
        "tiktok", "tik tok" -> "TikTok"
        "youtube", "yt" -> "YouTube"
        "snap", "snapchat" -> "Snapchat"
        "fb", "facebook" -> "Facebook"
        "twitter", "x" -> "X"
        "linkedin" -> "LinkedIn"
        else -> lowerName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    fun parseCommand(rawPrompt: String): ParsedSystemCommand? {
        val trimmed = rawPrompt.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.US)
            .replace(Regex("[!?.,]+$"), "")
            .trim()

        // 1. Choice activity switching & legacy mode phrase compatibility
        if (
            lower.contains("focus on market") ||
            lower.contains("focus on marketing") ||
            lower.contains("marketing as your choice") ||
            lower.contains("marketing your choice") ||
            lower.contains("marketing mode") ||
            lower.contains("market focus") ||
            lower.contains("markets focus") ||
            lower.contains("prioritize market") ||
            lower.contains("prioritize marketing") ||
            lower.contains("switch to marketing") ||
            lower.contains("switch to market mode")
        ) {
            return ParsedSystemCommand(SystemIntentKind.SWITCH_ACTIVITY_MARKETING)
        }

        if (
            lower.contains("be an assistant") ||
            lower.contains("be a system assistant") ||
            lower.contains("switch to assistant") ||
            lower.contains("system assistant mode") ||
            lower.contains("general assistant") ||
            lower.contains("stop market focus") ||
            lower.contains("stop marketing focus")
        ) {
            return ParsedSystemCommand(SystemIntentKind.SWITCH_ACTIVITY_ASSISTANT)
        }

        if (
            lower.contains("run all modes") ||
            lower.contains("enable all modes") ||
            lower.contains("all modes at the same time") ||
            lower.contains("all modes simultaneously") ||
            lower.contains("activate all modes")
        ) {
            return ParsedSystemCommand(SystemIntentKind.ENABLE_ALL_MODES)
        }

        // 2. Mode Switching (Full App <-> Mini Orb) & Orb Lifecycle Commands
        if (
            lower.contains("close the orb") || lower.contains("close orb") ||
            lower.contains("go to sleep") || lower.contains("close floating orb") ||
            lower.contains("hide floating orb") || lower.contains("turn off floating orb") ||
            lower.contains("close mini orb") || lower.contains("dismiss orb")
        ) {
            return ParsedSystemCommand(SystemIntentKind.ORB_CONTROL, "sleep")
        }
        if (lower == "stay here" || lower == "keep listening" || lower == "stay awake" || lower == "stay open") {
            return ParsedSystemCommand(SystemIntentKind.ORB_CONTROL, "stay_here")
        }
        if (AssistantModePolicy.isOpenFullAppPhrase(lower)) {
            return ParsedSystemCommand(SystemIntentKind.OPEN_FULL_APP)
        }
        if (AssistantModePolicy.isSwitchToMiniOrbPhrase(lower) || lower.contains("floating orb") || lower.contains("mini orb")) {
            return ParsedSystemCommand(SystemIntentKind.SWITCH_TO_MINI_ORB)
        }

        // Mute / Unmute microphone
        if (lower == "mute" || lower == "mute mic" || lower == "mute microphone" || lower == "mute yourself") {
            return ParsedSystemCommand(SystemIntentKind.MUTE_MIC, "mute")
        }
        if (lower == "unmute" || lower == "unmute mic" || lower == "unmute microphone" || lower == "listen again") {
            return ParsedSystemCommand(SystemIntentKind.MUTE_MIC, "unmute")
        }

        // Notifications / Missed calls / "Who texted me?"
        if (
            lower.contains("read my notifications") || lower.contains("check notifications") ||
            lower.contains("check my notifications") ||
            lower.contains("any notifications") || lower.contains("what notifications") ||
            lower.contains("missed call") || lower.contains("who texted me") ||
            lower.contains("who messaged me") || lower.contains("unread messages")
        ) {
            return ParsedSystemCommand(SystemIntentKind.READ_NOTIFICATIONS, trimmed)
        }

        // App Discovery & Install: "download Spotify", "install TradingView", "find ... on Play Store"
        val installMatch = Regex(
            "^(?:please\\s+)?(?:download|install|get)\\s+(?:the\\s+)?(.+?)(?:\\s+app|\\s+on\\s+play\\s+store|\\s+from\\s+play\\s+store)?$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (installMatch != null && !lower.contains("update")) {
            val targetApp = installMatch.groupValues[1].trim()
            if (targetApp.isNotBlank()) {
                return ParsedSystemCommand(SystemIntentKind.INSTALL_APP, targetApp)
            }
        }

        // Kenyan & Global News / Weather / Traffic / Market Research
        if (
            lower.contains("kenyan news") || lower.contains("kenya news") || lower.contains("news in kenya") ||
            lower.contains("daily nation") || lower.contains("citizen tv") || lower.contains("citizen digital") ||
            lower.contains("latest news") || lower.contains("world news") || lower.contains("tech news") ||
            lower.contains("business news") || lower.contains("sports news") ||
            lower.contains("weather today") || lower.contains("weather in ") || lower.contains("what's the weather") ||
            lower.contains("traffic update") || lower.contains("traffic in ")
        ) {
            return ParsedSystemCommand(SystemIntentKind.NEWS_RESEARCH, trimmed)
        }

        // 3. Step-by-step screen guidance & reading
        if (
            lower.contains("guide me") ||
            lower.contains("what should i do on this screen") ||
            lower.contains("what should i do here") ||
            lower.contains("help with this step") ||
            lower.contains("next step on screen") ||
            lower == "next step"
        ) {
            return ParsedSystemCommand(SystemIntentKind.GUIDE_SCREEN)
        }

        when {
            lower == "go back" || lower == "navigate back" || lower == "press back" ->
                return ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, "back")
            lower == "go home" || lower == "go to phone home" || lower == "home screen" || lower == "exit to home" ->
                return ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, "home")
            lower == "open recents" || lower == "recent apps" || lower == "show recent apps" || lower == "switch apps" ->
                return ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, "recents")
            lower == "open notifications" || lower == "show notifications" || lower == "pull down notifications" ->
                return ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, "notifications")
            lower == "open quick settings" || lower == "show quick settings" || lower == "control center" ->
                return ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, "quick_settings")
            lower == "lock screen" || lower == "lock my screen" || lower == "lock phone" ->
                return ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, "lock_screen")
            lower == "take a screenshot" || lower == "take screenshot" || lower == "capture screen" ->
                return ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, "screenshot")
            lower == "open app drawer" || lower == "show all apps" || lower == "all apps" ->
                return ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, "all_apps")
            lower.contains("read my screen") || lower.contains("what's on my screen") ||
                lower.contains("what is on my screen") || lower.contains("scan this screen") ->
                return ParsedSystemCommand(SystemIntentKind.READ_SCREEN)
            lower == "scroll down" || lower == "swipe down" || lower == "page down" ->
                return ParsedSystemCommand(SystemIntentKind.SCROLL_SCREEN, "down")
            lower == "scroll up" || lower == "swipe up" || lower == "page up" ->
                return ParsedSystemCommand(SystemIntentKind.SCROLL_SCREEN, "up")
            lower == "swipe left" -> return ParsedSystemCommand(SystemIntentKind.SOCIAL_ACTION, "swipe_left")
            lower == "swipe right" -> return ParsedSystemCommand(SystemIntentKind.SOCIAL_ACTION, "swipe_right")
        }

        // 3b. Self-Learning, Voice Check, Daily Briefing & Visual Feedback Controls
        if (
            lower.contains("study my device") || lower.contains("study this device") ||
            lower.contains("learn my device") || lower.contains("learn my apps") ||
            lower.contains("scan my device") || lower.contains("scan my phone") ||
            lower.contains("study this app")
        ) {
            return ParsedSystemCommand(SystemIntentKind.STUDY_DEVICE, trimmed)
        }

        if (
            lower.contains("check my voice") || lower.contains("verify my voice") ||
            lower.contains("test my voice") || lower.contains("who is speaking") ||
            lower.contains("voice id check") || lower.contains("i have a flu") ||
            lower.contains("i have the flu") || lower.contains("my voice sounds sick")
        ) {
            return ParsedSystemCommand(SystemIntentKind.VERIFY_VOICE, trimmed)
        }

        if (
            lower.contains("daily briefing") || lower.contains("morning briefing") ||
            lower == "brief me" || lower.contains("what have you learned today")
        ) {
            return ParsedSystemCommand(SystemIntentKind.DAILY_BRIEFING)
        }

        when {
            lower == "show taps" || lower == "turn on taps" || lower == "enable tap indicator" ->
                return ParsedSystemCommand(SystemIntentKind.VISUAL_FEEDBACK_TOGGLE, "tap", "true")
            lower == "hide taps" || lower == "turn off taps" || lower == "disable tap indicator" ->
                return ParsedSystemCommand(SystemIntentKind.VISUAL_FEEDBACK_TOGGLE, "tap", "false")
            lower == "show highlights" || lower == "enable highlights" || lower == "highlight elements" ->
                return ParsedSystemCommand(SystemIntentKind.VISUAL_FEEDBACK_TOGGLE, "highlight", "true")
            lower == "hide highlights" || lower == "disable highlights" ->
                return ParsedSystemCommand(SystemIntentKind.VISUAL_FEEDBACK_TOGGLE, "highlight", "false")
            lower == "show scroll" || lower == "show scrolling" || lower == "show scroll indicator" ->
                return ParsedSystemCommand(SystemIntentKind.VISUAL_FEEDBACK_TOGGLE, "scroll", "true")
            lower == "hide scroll" || lower == "hide scroll indicator" ->
                return ParsedSystemCommand(SystemIntentKind.VISUAL_FEEDBACK_TOGGLE, "scroll", "false")
        }

        // 3c. Cross-Platform Targeted Messaging: "Text Alex hey on WhatsApp" / "Message Sarah on Snapchat saying ..."
        parseTextContactCommand(trimmed)?.let { return it }

        // 3d. Universal Social Media Navigation & Actions: "like", "repost", "scroll TikTok", "open comments", etc.
        parseSocialActionCommand(trimmed, lower)?.let { return it }

        // 4. Type / Write text inside active app
        val writeMatch = Regex(
            "^(?:please\\s+)?(?:type|write|enter|input)\\s+[\"']?(.+?)[\"']?(?:\\s+(?:in|into|inside)\\s+(?:the\\s+)?(.+?)(?:\\s+(?:box|field|app))?)?$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (writeMatch != null) {
            val payloadText = writeMatch.groupValues[1].trim().trim('"', '\'')
            val targetField = writeMatch.groupValues[2].trim()
            if (payloadText.isNotBlank()) {
                return ParsedSystemCommand(SystemIntentKind.TYPE_TEXT, payloadText, targetField)
            }
        }

        // 5. Tap / Click button on screen
        val tapMatch = Regex(
            "^(?:please\\s+)?(?:tap|click|press)\\s+(?:on\\s+)?(?:the\\s+)?[\"']?(.+?)[\"']?(?:\\s+button)?$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (tapMatch != null) {
            val target = tapMatch.groupValues[1].trim()
            if (target.isNotBlank() && !target.equals("orb", ignoreCase = true)) {
                return ParsedSystemCommand(SystemIntentKind.TAP_TEXT, target)
            }
        }

        // 6. Assisted Account Creation / Sign-In / Credential Filling
        val hasPermPhrase = lower.contains("with my permission") ||
            lower.contains("i give permission") ||
            lower.contains("you have permission") ||
            lower.contains("permission granted")

        val signinMatch = Regex(
            "^(?:please\\s+)?(?:help\\s+(?:me\\s+)?)?(?:sign\\s+me\\s+in|log\\s+me\\s+in|sign\\s+in|log\\s+in|login)(?:\\s+(?:to|into|on|at)\\s+(.+?))?(?:\\s+(?:with my permission|please))?$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (signinMatch != null) {
            val targetSite = signinMatch.groupValues[1].trim().ifBlank { "autofill" }
            return ParsedSystemCommand(
                SystemIntentKind.SIGNIN_ASSIST,
                targetSite,
                if (hasPermPhrase) "permitted" else ""
            )
        }

        val fillCredsMatch = Regex(
            "^(?:please\\s+)?(?:fill|autofill|auto-fill|enter|use)\\s+(?:my\\s+)?(?:saved\\s+)?(?:login\\s+credentials|login\\s+details|login\\s+info|account\\s+details|credentials|login|password|details|info)(?:\\s+(?:for|on|in)\\s+(.+))?$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (fillCredsMatch != null) {
            val targetSite = fillCredsMatch.groupValues[1].trim().ifBlank { "autofill" }
            return ParsedSystemCommand(
                SystemIntentKind.SIGNIN_ASSIST,
                targetSite,
                if (hasPermPhrase) "permitted" else ""
            )
        }

        val fillMatch = Regex(
            "^(?:please\\s+)?(?:fill|autofill|auto-fill|complete)\\s+(?:my\\s+)?(?:signup|sign up|registration|account|form)(?:\\s+details|\\s+fields)?(?:\\s+on\\s+(.+))?$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (fillMatch != null) {
            val site = fillMatch.groupValues[1].trim().ifBlank { "autofill" }
            return ParsedSystemCommand(
                SystemIntentKind.SIGNUP_ASSIST,
                site,
                if (hasPermPhrase) "permitted" else ""
            )
        }

        val signupMatch = Regex(
            "(?:help\\s+(?:me\\s+)?)?(?:create|make|register|open|sign\\s+(?:me\\s+)?up)\\s+(?:for\\s+|an?\\s+|my\\s+)?(?:new\\s+)?(?:account\\s+)?(?:(?:on|for|at|inside)\\s+)?([A-Za-z0-9._-]+)?",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (signupMatch != null && (lower.contains("account") || lower.contains("sign up") || lower.contains("sign me up") || lower.contains("register"))) {
            val extracted = Regex(
                "(?:on|for|at|inside)\\s+([A-Za-z0-9._-]+)",
                RegexOption.IGNORE_CASE
            ).find(trimmed)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "autofill" }
            return ParsedSystemCommand(
                SystemIntentKind.SIGNUP_ASSIST,
                extracted,
                if (hasPermPhrase) "permitted" else ""
            )
        }

        // 7. Time & Date / Device Status
        if (
            lower == "what time is it" || lower == "what's the time" || lower == "tell me the time" ||
            lower == "current time" || lower == "what's the date" || lower == "what day is it" || lower == "today's date"
        ) {
            return ParsedSystemCommand(SystemIntentKind.TIME_DATE)
        }
        if (
            lower.contains("battery level") || lower.contains("check battery") ||
            lower.contains("device status") || lower.contains("system status") ||
            lower.contains("phone status") || lower == "device & battery"
        ) {
            return ParsedSystemCommand(SystemIntentKind.DEVICE_STATUS)
        }

        // 8. Device Settings & Specific App Settings
        val specificAppSettingsMatch = Regex(
            "^(?:please\\s+)?(?:open|go\\s+to|show|manage)\\s+(?:the\\s+)?(?:app\\s+)?settings\\s+(?:for|of)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(lower)
        if (specificAppSettingsMatch != null) {
            val rawTarget = specificAppSettingsMatch.groupValues[1].trim()
            val normalizedTarget = if (rawTarget == "wi-fi") "wifi" else rawTarget
            return if (rawTarget in deviceSettingKeywords) {
                ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, normalizedTarget)
            } else {
                ParsedSystemCommand(SystemIntentKind.APP_SETTINGS, rawTarget)
            }
        }

        val namedSettingsMatch = Regex(
            "^(?:please\\s+)?(?:open|go\\s+to|show)\\s+(?:the\\s+)?(.+?)\\s+(?:app\\s+)?settings$",
            RegexOption.IGNORE_CASE
        ).find(lower)
        if (namedSettingsMatch != null) {
            val target = namedSettingsMatch.groupValues[1].trim()
            val normalizedTarget = if (target == "wi-fi") "wifi" else target
            return when {
                target == "jarvis" || target == "this app" -> ParsedSystemCommand(SystemIntentKind.NAVIGATE_SECTION, "settings")
                target in deviceSettingKeywords -> ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, normalizedTarget)
                else -> ParsedSystemCommand(SystemIntentKind.APP_SETTINGS, target)
            }
        }

        when {
            lower == "open wifi" || lower == "wifi" -> return ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, "wifi")
            lower == "open bluetooth" || lower == "bluetooth" -> return ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, "bluetooth")
            lower.contains("default assistant") -> return ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, "assistant")
            lower.contains("accessibility settings") -> return ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, "accessibility")
            lower.contains("overlay permission") -> return ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, "overlay")
        }

        // 9. Camera / Media / Alarm / Timer / Calendar / Call / SMS / Email
        if (lower.contains("selfie") || lower.contains("front camera")) {
            return ParsedSystemCommand(SystemIntentKind.OPEN_CAMERA, "selfie")
        }
        if (lower == "take a photo" || lower == "take a picture" || lower == "open camera" || lower == "launch camera") {
            return ParsedSystemCommand(SystemIntentKind.OPEN_CAMERA, "rear")
        }
        if (lower == "record a video" || lower == "open video camera" || lower == "take a video") {
            return ParsedSystemCommand(SystemIntentKind.OPEN_VIDEO_CAMERA)
        }
        when {
            lower == "play music" || lower == "pause music" || lower == "play pause" || lower == "resume music" || lower == "pause media" ->
                return ParsedSystemCommand(SystemIntentKind.MEDIA_CONTROL, if (lower.contains("pause")) "pause" else "play_pause")
            lower == "next song" || lower == "next track" || lower == "skip track" || lower == "skip song" ->
                return ParsedSystemCommand(SystemIntentKind.MEDIA_CONTROL, "next")
            lower == "previous song" || lower == "previous track" ->
                return ParsedSystemCommand(SystemIntentKind.MEDIA_CONTROL, "previous")
            lower == "stop music" || lower == "stop media" ->
                return ParsedSystemCommand(SystemIntentKind.MEDIA_CONTROL, "stop")
            lower == "volume up" || lower == "turn up the volume" || lower == "louder" ->
                return ParsedSystemCommand(SystemIntentKind.MEDIA_CONTROL, "volume_up")
            lower == "volume down" || lower == "turn down the volume" || lower == "quieter" ->
                return ParsedSystemCommand(SystemIntentKind.MEDIA_CONTROL, "volume_down")
            lower == "mute volume" || lower == "mute media" ->
                return ParsedSystemCommand(SystemIntentKind.MEDIA_CONTROL, "mute_media")
        }
        val playMediaMatch = Regex(
            "^(?:please\\s+)?play\\s+(.+?)(?:\\s+on\\s+(spotify|youtube|netflix))?$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (playMediaMatch != null) {
            val mediaQuery = playMediaMatch.groupValues[1].trim()
            val mediaApp = playMediaMatch.groupValues[2].trim()
            if (mediaQuery.isNotBlank() && mediaQuery.lowercase(Locale.US) !in setOf("music", "song", "media", "video")) {
                return ParsedSystemCommand(SystemIntentKind.MEDIA_PLAY_SEARCH, mediaQuery, mediaApp)
            }
        }

        val alarmMatch = Regex("(?:set\\s+(?:an\\s+)?alarm\\s+(?:for|at)|wake\\s+me\\s+up\\s+at)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?(?:\\s+(?:called|labeled|for)\\s+(.+))?").find(lower)
        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[1].toIntOrNull() ?: 7
            val minute = alarmMatch.groupValues[2].toIntOrNull() ?: 0
            val amPm = alarmMatch.groupValues[3]
            if (amPm == "pm" && hour in 1..11) hour += 12
            if (amPm == "am" && hour == 12) hour = 0
            val label = alarmMatch.groupValues[4].ifBlank { "JARVIS Alarm" }
            return ParsedSystemCommand(SystemIntentKind.SET_ALARM, label, hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59))
        }

        val timerMatch = Regex("(?:set\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?|timer\\s+(?:for\\s+)?)(\\d+)\\s*(second|seconds|sec|secs|minute|minutes|min|mins|hour|hours|hr|hrs)(?:\\s+(?:for|called)\\s+(.+))?").find(lower)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toIntOrNull() ?: 1
            val unit = timerMatch.groupValues[2]
            val seconds = when {
                unit.startsWith("h") -> amount * 3600
                unit.startsWith("m") -> amount * 60
                else -> amount
            }.coerceAtLeast(1)
            val label = timerMatch.groupValues[3].ifBlank { "JARVIS Timer" }
            return ParsedSystemCommand(SystemIntentKind.SET_TIMER, label, durationSeconds = seconds)
        }

        val calMatch = Regex("(?:add|create|schedule)\\s+(?:a\\s+)?(?:calendar\\s+)?(?:event|meeting|reminder)\\s+(?:called\\s+|titled\\s+)?(.+)").find(trimmed)
        if (calMatch != null) {
            return ParsedSystemCommand(SystemIntentKind.ADD_CALENDAR_EVENT, calMatch.groupValues[1].trim())
        }

        val callMatch = Regex("(?:call|dial|phone)\\s+([+\\d][\\d\\s()-]{2,})").find(trimmed)
        if (callMatch != null) {
            return ParsedSystemCommand(SystemIntentKind.CALL_DIAL, callMatch.groupValues[1].trim())
        }

        val smsMatch = Regex("(?:send\\s+(?:an?\\s+)?(?:sms|text)|text|message)\\s+([+\\d][\\d\\s()-]{2,})(?:\\s+(?:saying|that|:)\\s+(.+))?", RegexOption.IGNORE_CASE).find(trimmed)
        if (smsMatch != null) {
            return ParsedSystemCommand(SystemIntentKind.SEND_SMS, smsMatch.groupValues[1].trim(), smsMatch.groupValues[2].trim())
        }

        val emailMatch = Regex("(?:send\\s+(?:an?\\s+)?email\\s+to|email)\\s+([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})(?:\\s+(?:about|subject)\\s+(.+))?", RegexOption.IGNORE_CASE).find(trimmed)
        if (emailMatch != null) {
            return ParsedSystemCommand(SystemIntentKind.SEND_EMAIL, emailMatch.groupValues[1].trim(), emailMatch.groupValues[2].trim())
        }

        // 10. Platform-Targeted Search: "search YouTube for ...", "look up ... on Reddit"
        val platformFirstMatch = Regex(
            "(?:search|look\\s+up|find)\\s+(?:on\\s+)?(youtube|yt|google|reddit|x|twitter|tiktok|instagram|github|wikipedia|wiki|amazon|play\\s+store|google\\s+play|binance|tradingview|duckduckgo|facebook|linkedin)\\s+(?:for\\s+|about\\s+)?(.+)",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (platformFirstMatch != null) {
            val platform = platformFirstMatch.groupValues[1].trim().lowercase(Locale.US)
            val query = platformFirstMatch.groupValues[2].trim()
            if (query.isNotBlank()) {
                return ParsedSystemCommand(SystemIntentKind.PLATFORM_SEARCH, platform, query)
            }
        }

        val topicFirstPlatformMatch = Regex(
            "(?:search|look\\s+up|find|play)\\s+(.+?)\\s+(?:on|in)\\s+(youtube|yt|google|reddit|x|twitter|tiktok|instagram|github|wikipedia|wiki|amazon|play\\s+store|google\\s+play|binance|tradingview|duckduckgo|facebook|linkedin)$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (topicFirstPlatformMatch != null) {
            val query = topicFirstPlatformMatch.groupValues[1].trim()
            val platform = topicFirstPlatformMatch.groupValues[2].trim().lowercase(Locale.US)
            if (query.isNotBlank() && query.lowercase(Locale.US) !in platformKeys) {
                return ParsedSystemCommand(SystemIntentKind.PLATFORM_SEARCH, platform, query)
            }
        }

        // 11. Play on YouTube fallback & Maps
        val ytMatch = Regex("(?:play|find\\s+on\\s+youtube)\\s+(.+?)(?:\\s+on\\s+youtube)?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (ytMatch != null && lower.contains("youtube")) {
            val q = ytMatch.groupValues[1].trim()
            if (q.isNotBlank() && !q.equals("youtube", ignoreCase = true)) {
                return ParsedSystemCommand(SystemIntentKind.PLATFORM_SEARCH, "youtube", q)
            }
        }

        val mapsMatch = Regex("(?:navigate\\s+to|directions\\s+to|open\\s+maps\\s+for|find\\s+on\\s+maps)\\s+(.+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (mapsMatch != null) {
            return ParsedSystemCommand(SystemIntentKind.OPEN_MAPS, mapsMatch.groupValues[1].trim())
        }

        // 12. Chrome / Website Opening (explicit domain, "in Chrome", "open website X", or known site directory)
        val directUrl = extractDirectUrlOrDomain(trimmed)
        if (directUrl != null && (
                lower.startsWith("http") ||
                    lower.startsWith("www.") ||
                    lower.contains("open ") ||
                    lower.contains("go to ") ||
                    lower.contains("visit ") ||
                    lower.contains("chrome") ||
                    lower.contains("website") ||
                    !trimmed.contains(' ')
                )
        ) {
            return ParsedSystemCommand(SystemIntentKind.OPEN_URL, directUrl)
        }

        val chromeSiteMatch = Regex(
            "^(?:please\\s+)?(?:open|go\\s+to|visit|launch)\\s+(?:the\\s+)?(?:website\\s+|site\\s+|chrome\\s+(?:to|at|for)\\s+)?(.+?)(?:\\s+(?:in|on)\\s+chrome|\\s+website|\\s+site)?$",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        val explicitChromeOrWeb = lower.contains("chrome") || lower.contains("website") || lower.contains("web page") || lower.startsWith("visit ") || lower.startsWith("go to ")
        val looksCompound = Regex("\\s+(?:and|then)\\s+(?:sign|log|fill|autofill|search|find|guide|type|write|tap|click|set|open|go)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(lower)
        if (chromeSiteMatch != null && explicitChromeOrWeb && !looksCompound) {
            val siteName = chromeSiteMatch.groupValues[1].trim()
            val siteLower = siteName.lowercase(Locale.US)
            if (siteLower == "chrome" || siteLower == "google chrome") {
                return ParsedSystemCommand(SystemIntentKind.OPEN_APP, "Chrome")
            }
            val knownUrl = websiteDirectory[siteLower]
            return if (knownUrl != null) {
                ParsedSystemCommand(SystemIntentKind.OPEN_URL, knownUrl)
            } else {
                ParsedSystemCommand(SystemIntentKind.OPEN_UNKNOWN_WEBSITE, siteName)
            }
        }

        val searchMatch = Regex("(?:search\\s+(?:the\\s+web|online)\\s+for|look\\s+up)\\s+(.+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (searchMatch != null) {
            return ParsedSystemCommand(SystemIntentKind.WEB_SEARCH, searchMatch.groupValues[1].trim())
        }

        // 13. Open / Launch installed app OR navigate internal JARVIS tab
        val openMatch = Regex("^(?:please\\s+|hey\\s+jarvis[,\\s]+)?(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE).find(trimmed)
        val hasTrailingActionClause = Regex(
            "\\s+(?:and|then)\\s+(?:text|message|dm|search|find|look|sign|log|fill|scroll|like|repost|share|tap|click|type|write)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)
        if (openMatch != null && !hasTrailingActionClause) {
            val target = openMatch.groupValues[1].trim()
            val targetLower = target.lowercase(Locale.US)
            if (targetLower in internalSectionKeywords) {
                return ParsedSystemCommand(SystemIntentKind.NAVIGATE_SECTION, targetLower)
            }
            if (targetLower == "settings" || targetLower == "jarvis settings") {
                return if (targetLower.contains("jarvis")) {
                    ParsedSystemCommand(SystemIntentKind.NAVIGATE_SECTION, "settings")
                } else {
                    ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, "general")
                }
            }
            if (targetLower in deviceSettingKeywords) {
                return ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, targetLower)
            }
            return ParsedSystemCommand(SystemIntentKind.OPEN_APP, target)
        }

        val navMatch = Regex("^(?:go\\s+to|show|switch\\s+to)\\s+(?:the\\s+)?(home|dashboard|jarvis|chat|credentials|vault|plugin|history|activity|settings)(?:\\s+screen|\\s+tab)?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (navMatch != null) {
            return ParsedSystemCommand(SystemIntentKind.NAVIGATE_SECTION, navMatch.groupValues[1].lowercase(Locale.US))
        }

        return null
    }

    internal fun parseTextContactCommand(
        trimmed: String,
        defaultPlatform: String? = null
    ): ParsedSystemCommand? {
        val platformPattern = "(whatsapp|whats\\s+app|snapchat|snap|instagram|insta|ig|telegram|messenger|facebook\\s+messenger|signal|discord|sms|messages)"
        // Pattern A: "text Alex hey how are you on WhatsApp" or "text Alex on WhatsApp saying hey"
        val afterPlatformRegex = Regex(
            "^(?:please\\s+)?(?:text|message|dm|send\\s+(?:a\\s+)?(?:message|text|dm)\\s+to)\\s+(.+?)\\s+(?:on|via|using|in)\\s+$platformPattern(?:\\s+(?:saying|that|with\\s+message|:)\\s+(.+))?$",
            RegexOption.IGNORE_CASE
        )
        afterPlatformRegex.find(trimmed)?.let { m ->
            val targetAndMaybeMsg = m.groupValues[1].trim()
            val rawPlatform = m.groupValues[2].trim().lowercase(Locale.US)
            val trailingMsg = m.groupValues[3].trim().trim('"', '\'')
            val platform = messagingPlatformNames[rawPlatform] ?: rawPlatform
            val (contact, msg) = splitContactAndInlineMessage(targetAndMaybeMsg, trailingMsg)
            if (contact.isNotBlank()) {
                return ParsedSystemCommand(SystemIntentKind.TEXT_CONTACT, platform, "$contact|$msg")
            }
        }

        // Pattern B: "on WhatsApp text Alex saying hey"
        val leadPlatformRegex = Regex(
            "^(?:please\\s+)?(?:on|via|in)\\s+$platformPattern\\s+(?:text|message|dm|send\\s+(?:a\\s+)?(?:message|text|dm)\\s+to)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        leadPlatformRegex.find(trimmed)?.let { m ->
            val rawPlatform = m.groupValues[1].trim().lowercase(Locale.US)
            val rest = m.groupValues[2].trim()
            val platform = messagingPlatformNames[rawPlatform] ?: rawPlatform
            val (contact, msg) = splitContactAndInlineMessage(rest, "")
            if (contact.isNotBlank()) {
                return ParsedSystemCommand(SystemIntentKind.TEXT_CONTACT, platform, "$contact|$msg")
            }
        }

        // Pattern C: "text <Name> saying <Message>" (explicit message without platform)
        val genericContactSaying = Regex(
            "^(?:please\\s+)?(?:text|message|dm|send\\s+(?:a\\s+)?(?:message|text|dm)\\s+to)\\s+([A-Za-z][A-Za-z0-9._' -]{1,35})\\s+(?:saying|that)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        genericContactSaying.find(trimmed)?.let { m ->
            val contact = cleanContactLead(m.groupValues[1])
            val msg = m.groupValues[2].trim().trim('"', '\'')
            if (contact.isNotBlank() && msg.isNotBlank()) {
                return ParsedSystemCommand(
                    SystemIntentKind.TEXT_CONTACT,
                    defaultPlatform ?: "Messages",
                    "$contact|$msg"
                )
            }
        }

        // Pattern D: Context-inherited messaging segment (e.g., second half of "open WhatsApp and text Alex [hey]")
        if (!defaultPlatform.isNullOrBlank()) {
            val bareTextMatch = Regex(
                "^(?:please\\s+)?(?:text|message|dm|send\\s+(?:a\\s+)?(?:message|text|dm)\\s+to)\\s+(.+)$",
                RegexOption.IGNORE_CASE
            ).find(trimmed)
            if (bareTextMatch != null) {
                val (contact, msg) = splitContactAndInlineMessage(bareTextMatch.groupValues[1].trim(), "")
                if (contact.isNotBlank()) {
                    return ParsedSystemCommand(SystemIntentKind.TEXT_CONTACT, defaultPlatform, "$contact|$msg")
                }
            }
        }

        return null
    }

    private fun cleanContactLead(raw: String): String {
        return raw.trim()
            .replace(Regex("^(?:someone\\s+(?:named|called)\\s+|a\\s+(?:person|contact|friend)\\s+(?:named|called)\\s+|contact\\s+)", RegexOption.IGNORE_CASE), "")
            .trim('"', '\'', ' ')
    }

    private val messageStarterHints = setOf(
        "hey", "hi", "hello", "yo", "sup", "good", "how", "what", "where", "when", "why",
        "please", "call", "come", "ok", "okay", "thanks", "sorry", "meet", "bro", "i'm", "im"
    )

    private fun splitContactAndInlineMessage(targetAndMaybeMsg: String, trailingMsg: String): Pair<String, String> {
        val cleanedTarget = cleanContactLead(targetAndMaybeMsg)
        if (trailingMsg.isNotBlank()) {
            return cleanedTarget to trailingMsg
        }
        val explicitSaying = cleanedTarget.split(Regex("\\s+(?:saying|that|:)\\s+", RegexOption.IGNORE_CASE), limit = 2)
        if (explicitSaying.size == 2) {
            return cleanContactLead(explicitSaying[0]) to explicitSaying[1].trim().trim('"', '\'')
        }
        val quoteIdx = cleanedTarget.indexOfAny(charArrayOf('"', '\''))
        if (quoteIdx > 0) {
            return cleanContactLead(cleanedTarget.substring(0, quoteIdx)) to
                cleanedTarget.substring(quoteIdx).trim().trim('"', '\'')
        }
        val words = cleanedTarget.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.size >= 3 -> words.first() to words.drop(1).joinToString(" ")
            words.size == 2 && words[1].lowercase(Locale.US) in messageStarterHints -> words[0] to words[1]
            else -> cleanedTarget to ""
        }
    }

    internal fun parseSocialActionCommand(trimmed: String, lower: String): ParsedSystemCommand? {
        val detectedPlatform = socialPlatformNames.firstOrNull { p ->
            Regex("\\b(?:on|in)\\s+$p\\b|\\b$p\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }.orEmpty()

        val canonicalPlatform = when (detectedPlatform) {
            "insta", "ig", "instagram" -> "Instagram"
            "tiktok", "tik tok" -> "TikTok"
            "youtube", "yt" -> "YouTube"
            "snap", "snapchat" -> "Snapchat"
            "fb", "facebook" -> "Facebook"
            "twitter" -> "X"
            "linkedin" -> "LinkedIn"
            else -> detectedPlatform.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }

        val action = when {
            Regex("^(?:please\\s+)?(?:like|love|upvote|double[- ]tap\\s+like)(?:\\s+(?:this|the)\\s+(?:post|video|reel|photo|tweet|clip))?(?:\\s+(?:on|in)\\s+.+)?$", RegexOption.IGNORE_CASE).matches(lower) -> "like"
            Regex("^(?:please\\s+)?(?:repost|retweet|reshare|boost)(?:\\s+(?:this|the)?\\s*(?:post|tweet|video|reel)?)?(?:\\s+(?:on|in)\\s+.+)?$", RegexOption.IGNORE_CASE).matches(lower) -> "repost"
            Regex("^(?:please\\s+)?(?:share)(?:\\s+(?:this|the)\\s+(?:post|video|reel|tweet|photo))?(?:\\s+(?:on|in)\\s+.+)?$", RegexOption.IGNORE_CASE).matches(lower) -> "share"
            Regex("^(?:please\\s+)?(?:save|bookmark)(?:\\s+(?:this|the)\\s+(?:post|video|reel|photo))?(?:\\s+(?:on|in)\\s+.+)?$", RegexOption.IGNORE_CASE).matches(lower) -> "save"
            Regex("^(?:please\\s+)?(?:follow|subscribe)(?:\\s+(?:this\\s+(?:creator|user|channel|page)|them|back))?(?:\\s+(?:on|in)\\s+.+)?$", RegexOption.IGNORE_CASE).matches(lower) -> "follow"
            Regex("^(?:please\\s+)?(?:open\\s+(?:the\\s+)?comments?|show\\s+comments?|view\\s+comments?)(?:\\s+(?:on|in)\\s+.+)?$", RegexOption.IGNORE_CASE).matches(lower) -> "comment"
            Regex("^(?:please\\s+)?(?:next\\s+(?:post|video|reel|short|clip|tiktok|snap)|scroll\\s+(?:tiktok|instagram|reels|shorts|x|twitter|reddit|feed|down))?$", RegexOption.IGNORE_CASE).matches(lower) &&
                (lower.contains("next ") || lower.startsWith("scroll ")) -> "scroll_down"
            Regex("^(?:please\\s+)?(?:previous\\s+(?:post|video|reel)|scroll\\s+up)$", RegexOption.IGNORE_CASE).matches(lower) -> "scroll_up"
            Regex("^(?:please\\s+)?(?:open\\s+(?:my\\s+)?(?:dms|direct\\s+messages|inbox))(?:\\s+(?:on|in)\\s+.+)?$", RegexOption.IGNORE_CASE).matches(lower) -> "dms"
            Regex("^(?:please\\s+)?open\\s+(?:my\\s+)?profile(?:\\s+(?:on|in)\\s+.+)?$", RegexOption.IGNORE_CASE).matches(lower) -> "profile"
            Regex("^(?:please\\s+)?(?:open|show|watch)\\s+(?:instagram\\s+)?reels$", RegexOption.IGNORE_CASE).matches(lower) -> "reels"
            else -> null
        } ?: return null

        val effectivePlatform = if (action == "reels" && canonicalPlatform.isBlank()) "Instagram" else canonicalPlatform
        return ParsedSystemCommand(SystemIntentKind.SOCIAL_ACTION, action, effectivePlatform)
    }

    fun extractAndStripAiActions(rawReply: String): Pair<String, List<ParsedSystemCommand>> {
        val commands = mutableListOf<ParsedSystemCommand>()
        actionTagRegex.findAll(rawReply).forEach { match ->
            val name = match.groupValues[1].trim().uppercase(Locale.US)
            val argRaw = match.groupValues[2]
            val parts = if (argRaw.isEmpty()) emptyList() else argRaw.split("|").map { it.trim() }
            when (name) {
                "OPEN_APP" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.OPEN_APP, it))
                }
                "APP_SETTINGS" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.APP_SETTINGS, it))
                }
                "PLATFORM_SEARCH" -> {
                    val platform = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "google"
                    val query = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    if (query != null) {
                        commands.add(ParsedSystemCommand(SystemIntentKind.PLATFORM_SEARCH, platform, query))
                    }
                }
                "TYPE_TEXT" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.TYPE_TEXT, it, parts.getOrElse(1) { "" }))
                }
                "TAP_TEXT" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.TAP_TEXT, it))
                }
                "SCROLL" -> commands.add(
                    ParsedSystemCommand(SystemIntentKind.SCROLL_SCREEN, parts.firstOrNull()?.ifBlank { "down" } ?: "down")
                )
                "GLOBAL_NAV" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.GLOBAL_NAV, it))
                }
                "READ_SCREEN" -> commands.add(ParsedSystemCommand(SystemIntentKind.READ_SCREEN))
                "GUIDE_SCREEN" -> commands.add(ParsedSystemCommand(SystemIntentKind.GUIDE_SCREEN))
                "SIGNUP_ASSIST" -> commands.add(
                    ParsedSystemCommand(
                        SystemIntentKind.SIGNUP_ASSIST,
                        parts.firstOrNull().orEmpty(),
                        parts.getOrElse(1) { "" }
                    )
                )
                "SIGNIN_ASSIST", "FILL_CREDENTIALS" -> commands.add(
                    ParsedSystemCommand(
                        SystemIntentKind.SIGNIN_ASSIST,
                        parts.firstOrNull().orEmpty(),
                        parts.getOrElse(1) { "" }
                    )
                )
                "FLOATING_ORB" -> commands.add(
                    ParsedSystemCommand(SystemIntentKind.FLOATING_ORB, parts.firstOrNull()?.ifBlank { "open" } ?: "open")
                )
                "CALL" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.CALL_DIAL, it))
                }
                "SMS" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.SEND_SMS, it, parts.getOrElse(1) { "" }))
                }
                "EMAIL" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.SEND_EMAIL, it, parts.getOrElse(1) { "" }))
                }
                "SET_ALARM" -> {
                    val hm = parts.firstOrNull().orEmpty().split(":")
                    val h = hm.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
                    val m = hm.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    commands.add(ParsedSystemCommand(SystemIntentKind.SET_ALARM, parts.getOrElse(1) { "JARVIS Alarm" }, hour = h, minute = m))
                }
                "SET_TIMER" -> {
                    val secs = parts.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 60
                    commands.add(ParsedSystemCommand(SystemIntentKind.SET_TIMER, parts.getOrElse(1) { "JARVIS Timer" }, durationSeconds = secs))
                }
                "WEB_SEARCH" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.WEB_SEARCH, it))
                }
                "WEB_LOOKUP" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.WEB_LOOKUP, it))
                }
                "OPEN_URL", "OPEN_CHROME" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { rawTarget ->
                    val targetLower = rawTarget.lowercase(Locale.US)
                    if (targetLower == "chrome" || targetLower == "google chrome") {
                        commands.add(ParsedSystemCommand(SystemIntentKind.OPEN_APP, "Chrome"))
                    } else {
                        val resolved = extractDirectUrlOrDomain(rawTarget) ?: websiteDirectory[targetLower]
                        if (resolved != null) {
                            commands.add(ParsedSystemCommand(SystemIntentKind.OPEN_URL, resolved))
                        } else {
                            commands.add(ParsedSystemCommand(SystemIntentKind.OPEN_UNKNOWN_WEBSITE, rawTarget))
                        }
                    }
                }
                "MAPS" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.OPEN_MAPS, it))
                }
                "YOUTUBE" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    commands.add(ParsedSystemCommand(SystemIntentKind.PLATFORM_SEARCH, "youtube", it))
                }
                "OPEN_SETTINGS" -> commands.add(ParsedSystemCommand(SystemIntentKind.OPEN_SETTINGS, parts.firstOrNull().orEmpty()))
                "CAMERA" -> commands.add(
                    ParsedSystemCommand(SystemIntentKind.OPEN_CAMERA, parts.firstOrNull()?.ifBlank { "rear" } ?: "rear")
                )
                "MEDIA_CONTROL" -> commands.add(
                    ParsedSystemCommand(SystemIntentKind.MEDIA_CONTROL, parts.firstOrNull()?.ifBlank { "play_pause" } ?: "play_pause")
                )
                "MEDIA_PLAY" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { query ->
                    commands.add(ParsedSystemCommand(SystemIntentKind.MEDIA_PLAY_SEARCH, query, parts.getOrElse(1) { "" }))
                }
                "TEXT_CONTACT" -> {
                    val platform = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "WhatsApp"
                    val contact = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: ""
                    val msg = parts.getOrElse(2) { "" }
                    if (contact.isNotBlank()) {
                        commands.add(ParsedSystemCommand(SystemIntentKind.TEXT_CONTACT, platform, "$contact|$msg"))
                    }
                }
                "SOCIAL_ACTION" -> {
                    val action = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "like"
                    val platform = parts.getOrElse(1) { "" }
                    commands.add(ParsedSystemCommand(SystemIntentKind.SOCIAL_ACTION, action, platform))
                }
                "CLICK_INDEX" -> parts.firstOrNull()?.toIntOrNull()?.let { idx ->
                    commands.add(ParsedSystemCommand(SystemIntentKind.CLICK_NODE_INDEX, idx.toString()))
                }
                "SEARCH_IN_APP" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { q ->
                    commands.add(ParsedSystemCommand(SystemIntentKind.SEARCH_IN_APP, q))
                }
                "STUDY_DEVICE" -> commands.add(ParsedSystemCommand(SystemIntentKind.STUDY_DEVICE))
                "VERIFY_VOICE" -> commands.add(ParsedSystemCommand(SystemIntentKind.VERIFY_VOICE))
                "AUTONOMOUS_TASK" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { goal ->
                    commands.add(ParsedSystemCommand(SystemIntentKind.AUTONOMOUS_TASK, goal))
                }
                "MINI_ORB", "SWITCH_TO_MINI_ORB" -> {
                    val modeArg = parts.firstOrNull()?.lowercase(Locale.US).orEmpty()
                    if (modeArg == "close" || modeArg == "sleep") {
                        commands.add(ParsedSystemCommand(SystemIntentKind.ORB_CONTROL, "sleep"))
                    } else {
                        commands.add(ParsedSystemCommand(SystemIntentKind.SWITCH_TO_MINI_ORB))
                    }
                }
                "OPEN_FULL_APP", "FULL_APP" -> commands.add(ParsedSystemCommand(SystemIntentKind.OPEN_FULL_APP))
                "INSTALL_APP" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { app ->
                    commands.add(ParsedSystemCommand(SystemIntentKind.INSTALL_APP, app))
                }
                "READ_NOTIFICATIONS" -> commands.add(ParsedSystemCommand(SystemIntentKind.READ_NOTIFICATIONS))
                "NEWS_RESEARCH" -> parts.firstOrNull()?.takeIf { it.isNotBlank() }?.let { topic ->
                    commands.add(ParsedSystemCommand(SystemIntentKind.NEWS_RESEARCH, topic))
                }
            }
        }
        val cleaned = rawReply.replace(actionTagRegex, "").replace(Regex("\\n{3,}"), "\n\n").trim()
        return cleaned to commands
    }

    fun listInstalledLauncherApps(context: Context): List<InstalledAppSummary> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = info.loadLabel(pm).toString().trim()
                if (label.isBlank()) return@mapNotNull null
                InstalledAppSummary(appName = label, packageName = pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase(Locale.US) }
    }

    fun buildPlatformSearchUrl(platform: String, query: String): Pair<String, String> {
        val encoded = Uri.encode(query)
        return when (platform.lowercase(Locale.US).trim()) {
            "youtube", "yt" -> "YouTube" to "https://www.youtube.com/results?search_query=$encoded"
            "reddit" -> "Reddit" to "https://www.reddit.com/search/?q=$encoded"
            "x", "twitter" -> "X" to "https://x.com/search?q=$encoded"
            "tiktok" -> "TikTok" to "https://www.tiktok.com/search?q=$encoded"
            "instagram" -> "Instagram" to "https://www.instagram.com/explore/search/keyword/?q=$encoded"
            "github" -> "GitHub" to "https://github.com/search?q=$encoded"
            "wikipedia", "wiki" -> "Wikipedia" to "https://en.wikipedia.org/wiki/Special:Search?search=$encoded"
            "amazon" -> "Amazon" to "https://www.amazon.com/s?k=$encoded"
            "play store", "google play" -> "Play Store" to "https://play.google.com/store/search?q=$encoded&c=apps"
            "binance" -> "Binance" to "https://www.binance.com/en/markets/overview?q=$encoded"
            "tradingview" -> "TradingView" to "https://www.tradingview.com/search/?query=$encoded"
            "duckduckgo" -> "DuckDuckGo" to "https://duckduckgo.com/?q=$encoded"
            "facebook" -> "Facebook" to "https://www.facebook.com/search/top/?q=$encoded"
            "linkedin" -> "LinkedIn" to "https://www.linkedin.com/search/results/all/?keywords=$encoded"
            else -> "Google" to "https://www.google.com/search?q=$encoded"
        }
    }

    fun openWebsiteInChrome(context: Context, rawUrl: String, showFloatingOrb: Boolean = true): SystemExecutionResult {
        val safeUrl = WebKnowledgeClient.sanitizePublicHttpUrl(rawUrl)
            ?: return SystemExecutionResult(
                handled = true,
                replyText = "That web link wasn't a valid public http/https URL, Sir. Please share the exact website link (for example example.com).",
                actionLabel = "Link Needed",
                launchedTarget = false
            )

        if (showFloatingOrb && Settings.canDrawOverlays(context)) {
            JarvisFloatingOrbService.start(context, expandBubble = false)
        }

        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            setPackage("com.android.chrome")
        }
        val chromeTry = launchIntent(
            context = context,
            intent = chromeIntent,
            successReply = "Opening $safeUrl in Google Chrome, Sir. Tap my glowing orb anytime if you'd like me to read, analyze, or fill details on the page.",
            failureReply = "",
            actionLabel = "Chrome Browser"
        )
        if (chromeTry.launchedTarget) return chromeTry

        return launchIntent(
            context = context,
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)),
            successReply = "Opening $safeUrl in your browser, Sir.",
            failureReply = "No web browser was found on this device to open $safeUrl, Sir.",
            actionLabel = "Open URL"
        )
    }

    fun execute(context: Context, command: ParsedSystemCommand): SystemExecutionResult {
        return when (command.kind) {
            SystemIntentKind.OPEN_APP -> launchInstalledApp(context, command.primaryArg)
            SystemIntentKind.APP_SETTINGS -> openSpecificAppSettings(context, command.primaryArg)
            SystemIntentKind.CALL_DIAL -> launchIntent(
                context = context,
                intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(command.primaryArg)}")),
                successReply = "Opening your dialer with ${command.primaryArg}, Sir.",
                failureReply = "I could not open a phone dialer on this device, Sir.",
                actionLabel = "System Dialer"
            )
            SystemIntentKind.SEND_SMS -> {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(command.primaryArg)}")).apply {
                    if (command.secondaryArg.isNotBlank()) putExtra("sms_body", command.secondaryArg)
                }
                launchIntent(
                    context = context,
                    intent = intent,
                    successReply = "Opening your messaging app for ${command.primaryArg}, Sir.",
                    failureReply = "No SMS messaging app was found on this device, Sir.",
                    actionLabel = "System SMS"
                )
            }
            SystemIntentKind.SEND_EMAIL -> {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(command.primaryArg)}")).apply {
                    if (command.secondaryArg.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, command.secondaryArg)
                }
                launchIntent(
                    context = context,
                    intent = intent,
                    successReply = "Composing an email to ${command.primaryArg}, Sir.",
                    failureReply = "No email app was found on this device, Sir.",
                    actionLabel = "System Email"
                )
            }
            SystemIntentKind.SET_ALARM -> {
                val h = command.hour ?: 7
                val m = command.minute ?: 0
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, h)
                    putExtra(AlarmClock.EXTRA_MINUTES, m)
                    putExtra(AlarmClock.EXTRA_MESSAGE, command.primaryArg.ifBlank { "JARVIS Alarm" })
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                }
                launchIntent(
                    context = context,
                    intent = intent,
                    successReply = "Setting an alarm for ${"%02d:%02d".format(h, m)}, Sir.",
                    failureReply = "No Clock alarm handler is available on this device, Sir.",
                    actionLabel = "System Alarm"
                )
            }
            SystemIntentKind.SET_TIMER -> {
                val secs = command.durationSeconds ?: 60
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    putExtra(AlarmClock.EXTRA_MESSAGE, command.primaryArg.ifBlank { "JARVIS Timer" })
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                }
                launchIntent(
                    context = context,
                    intent = intent,
                    successReply = "Setting a timer for $secs seconds, Sir.",
                    failureReply = "No Clock timer handler is available on this device, Sir.",
                    actionLabel = "System Timer"
                )
            }
            SystemIntentKind.ADD_CALENDAR_EVENT -> {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, command.primaryArg)
                }
                launchIntent(
                    context = context,
                    intent = intent,
                    successReply = "Opening your calendar to schedule \"${command.primaryArg}\", Sir.",
                    failureReply = "No Calendar app was found on this device, Sir.",
                    actionLabel = "System Calendar"
                )
            }
            SystemIntentKind.WEB_SEARCH -> {
                val query = command.primaryArg
                val url = "https://www.google.com/search?q=${Uri.encode(query)}"
                val chromeResult = openWebsiteInChrome(context, url)
                if (chromeResult.launchedTarget) {
                    chromeResult.copy(replyText = "Searching online in Chrome for \"$query\", Sir.", actionLabel = "Chrome Search")
                } else {
                    val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, query) }
                    launchIntent(
                        context = context,
                        intent = searchIntent,
                        successReply = "Searching the web for \"$query\", Sir.",
                        failureReply = "No web browser is available to search \"$query\", Sir.",
                        actionLabel = "Web Search"
                    )
                }
            }
            SystemIntentKind.PLATFORM_SEARCH -> {
                val (label, url) = buildPlatformSearchUrl(command.primaryArg, command.secondaryArg)
                val res = openWebsiteInChrome(context, url)
                if (res.launchedTarget) {
                    res.copy(
                        replyText = "Searching $label in Chrome for \"${command.secondaryArg}\", Sir.",
                        actionLabel = "$label Search"
                    )
                } else res
            }
            SystemIntentKind.OPEN_URL -> openWebsiteInChrome(context, command.primaryArg)
            SystemIntentKind.OPEN_MAPS -> launchIntent(
                context = context,
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(command.primaryArg)}")),
                successReply = "Launching Maps for \"${command.primaryArg}\", Sir.",
                failureReply = "No navigation or maps app was found on this device, Sir.",
                actionLabel = "Maps Navigation"
            )
            SystemIntentKind.PLAY_YOUTUBE -> openWebsiteInChrome(
                context,
                "https://www.youtube.com/results?search_query=${Uri.encode(command.primaryArg)}"
            )
            SystemIntentKind.OPEN_CAMERA -> MediaAndCameraController.launchCamera(context, command.primaryArg.ifBlank { "rear" })
            SystemIntentKind.OPEN_VIDEO_CAMERA -> MediaAndCameraController.launchCamera(context, "video")
            SystemIntentKind.MEDIA_CONTROL -> MediaAndCameraController.controlPlayback(context, command.primaryArg)
            SystemIntentKind.MEDIA_PLAY_SEARCH -> MediaAndCameraController.playMediaSearch(context, command.primaryArg, command.secondaryArg)
            SystemIntentKind.OPEN_SETTINGS -> openSystemSettings(context, command.primaryArg)
            SystemIntentKind.TYPE_TEXT -> {
                val svc = JarvisAccessibilityService.activeInstance()
                if (svc == null) {
                    SystemExecutionResult(
                        handled = true,
                        replyText = "Screen Helper isn't turned on yet, Sir. Enable Screen Helper on Home or Settings so I can write directly inside other apps.",
                        actionLabel = "Screen Helper Needed"
                    )
                } else {
                    val outcome = svc.typeIntoActiveOrMatchingField(command.primaryArg, command.secondaryArg)
                    SystemExecutionResult(true, outcome.message, "App Typing", outcome.success)
                }
            }
            SystemIntentKind.TAP_TEXT -> {
                val svc = JarvisAccessibilityService.activeInstance()
                if (svc == null) {
                    SystemExecutionResult(
                        handled = true,
                        replyText = "Turn on Screen Helper in Home or Settings first, Sir, so I can tap buttons for you.",
                        actionLabel = "Screen Helper Needed"
                    )
                } else {
                    val outcome = svc.clickNodeByText(command.primaryArg)
                    SystemExecutionResult(true, outcome.message, "Screen Tap", outcome.success)
                }
            }
            SystemIntentKind.SCROLL_SCREEN -> {
                val svc = JarvisAccessibilityService.activeInstance()
                if (svc == null) {
                    SystemExecutionResult(
                        handled = true,
                        replyText = "Enable Screen Helper on Home or Settings first, Sir, so I can scroll your screen.",
                        actionLabel = "Screen Helper Needed"
                    )
                } else {
                    val forward = !command.primaryArg.equals("up", ignoreCase = true)
                    val outcome = svc.scrollScreen(forward)
                    SystemExecutionResult(true, outcome.message, "Screen Scroll", outcome.success)
                }
            }
            SystemIntentKind.CLICK_NODE_INDEX -> {
                val svc = JarvisAccessibilityService.activeInstance()
                val idx = command.primaryArg.toIntOrNull() ?: -1
                if (svc == null || idx <= 0) {
                    SystemExecutionResult(true, "Turn on Screen Helper first to click element #$idx, Sir.", "Screen Helper Needed")
                } else {
                    val outcome = svc.clickIndexedNode(idx)
                    SystemExecutionResult(true, outcome.message, "Smart Tap", outcome.success)
                }
            }
            SystemIntentKind.SEARCH_IN_APP -> {
                val svc = JarvisAccessibilityService.activeInstance()
                if (svc == null) {
                    SystemExecutionResult(true, "Turn on Screen Helper first so I can search inside this app, Sir.", "Screen Helper Needed")
                } else {
                    val outcome = svc.findAndTapSearchAndType(command.primaryArg)
                    SystemExecutionResult(true, outcome.message, "In-App Search", outcome.success)
                }
            }
            SystemIntentKind.SOCIAL_ACTION -> {
                val svc = JarvisAccessibilityService.activeInstance()
                if (svc == null) {
                    SystemExecutionResult(
                        handled = true,
                        replyText = "Turn on Screen Helper on Home or Settings first, Sir, so I can control social apps for you.",
                        actionLabel = "Screen Helper Needed"
                    )
                } else {
                    val outcome = svc.performSocialAction(command.primaryArg, command.secondaryArg)
                    SystemExecutionResult(true, outcome.message, "Social Navigation", outcome.success)
                }
            }
            SystemIntentKind.GLOBAL_NAV -> {
                val svc = JarvisAccessibilityService.activeInstance()
                if (svc == null) {
                    SystemExecutionResult(
                        handled = true,
                        replyText = "Enable Screen Helper on Home or Settings first, Sir, so I can navigate system screens like Back, Home, and Notifications.",
                        actionLabel = "Screen Helper Needed"
                    )
                } else {
                    val outcome = svc.performGlobalNavigation(command.primaryArg)
                    SystemExecutionResult(true, outcome.message, "System Navigation", outcome.success)
                }
            }
            SystemIntentKind.READ_SCREEN -> {
                val svc = JarvisAccessibilityService.activeInstance()
                if (svc == null) {
                    SystemExecutionResult(
                        handled = true,
                        replyText = "Turn on Screen Helper in Home or Settings first, Sir, so I can read what's on your screen.",
                        actionLabel = "Screen Helper Needed"
                    )
                } else {
                    SystemExecutionResult(true, svc.readScreenSummary(), "Screen Reader", true)
                }
            }
            SystemIntentKind.GUIDE_SCREEN -> {
                val svc = JarvisAccessibilityService.activeInstance()
                if (svc == null) {
                    SystemExecutionResult(
                        handled = true,
                        replyText = "Turn on Screen Helper in Home or Settings first, Sir, so I can inspect the page and guide you step by step (like ID uploads, forms, or verifications).",
                        actionLabel = "Screen Helper Needed"
                    )
                } else {
                    val guide = svc.analyzeCurrentFlowStep()
                    SystemExecutionResult(true, guide.spokenGuidance, "Step Guidance", true)
                }
            }
            SystemIntentKind.SIGNUP_ASSIST -> handleCredentialAssist(context, command.primaryArg, command.secondaryArg, isSignIn = false)
            SystemIntentKind.SIGNIN_ASSIST -> handleCredentialAssist(context, command.primaryArg, command.secondaryArg, isSignIn = true)
            SystemIntentKind.FLOATING_ORB -> handleFloatingOrbCommand(context, command.primaryArg)
            SystemIntentKind.DEVICE_STATUS -> SystemExecutionResult(
                handled = true,
                replyText = buildSystemTelemetryReport(context),
                actionLabel = "Device Telemetry"
            )
            SystemIntentKind.TIME_DATE -> {
                val formatted = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
                SystemExecutionResult(
                    handled = true,
                    replyText = "It is currently $formatted, Sir.",
                    actionLabel = "Clock"
                )
            }
            else -> SystemExecutionResult(handled = false, replyText = "", actionLabel = "")
        }
    }

    private fun handleCredentialAssist(
        context: Context,
        target: String,
        permissionArg: String,
        isSignIn: Boolean
    ): SystemExecutionResult {
        val store = SecureCredentialStore(context)
        val profile = store.getSignupProfile()
        val svc = JarvisAccessibilityService.activeInstance()
        val activeScreenHint = svc?.readScreenSummary().orEmpty()
        val hasRealFormOnScreen = svc?.hasFormFieldsOnScreen() == true
        val savedList = store.getSavedCredentials()
        val matchedCred = store.findCredentialForTarget(target, activeScreenHint)
            ?: if (!hasRealFormOnScreen && (target.isBlank() || target.equals("autofill", ignoreCase = true)) && savedList.size == 1) {
                savedList.first()
            } else null

        val permitted = profile.allowAssistedFill || permissionArg == "permitted" || matchedCred != null || savedList.isNotEmpty()

        if (!permitted) {
            if (Settings.canDrawOverlays(context)) {
                JarvisFloatingOrbService.start(context, expandBubble = true)
            }
            return SystemExecutionResult(
                handled = true,
                replyText = "Before I fill an account form, please save your info or login under the Credentials tab and turn on Assisted Form Fill, or say \"with my permission\", Sir.",
                actionLabel = "Permission Needed"
            )
        }

        val targetLower = target.lowercase(Locale.US).trim()
        val explicitTargetName = if (targetLower.isNotBlank() && targetLower != "autofill") targetLower else null
        val urlToOpen = when {
            explicitTargetName != null -> {
                matchedCred?.websiteOrApp?.let { extractDirectUrlOrDomain(it) }
                    ?: if (isSignIn) signinUrls[explicitTargetName] else signupUrls[explicitTargetName]
                    ?: websiteDirectory[explicitTargetName]
                    ?: extractDirectUrlOrDomain(target)
            }
            !hasRealFormOnScreen && matchedCred != null -> {
                val siteKey = matchedCred.serviceName.lowercase(Locale.US).trim()
                extractDirectUrlOrDomain(matchedCred.websiteOrApp)
                    ?: if (isSignIn) signinUrls[siteKey] else signupUrls[siteKey]
                    ?: websiteDirectory[siteKey]
            }
            else -> null
        }

        // Open the target site in Chrome when no login/signup input form is on screen yet
        if (urlToOpen != null && !hasRealFormOnScreen) {
            if (Settings.canDrawOverlays(context)) {
                JarvisFloatingOrbService.start(context, expandBubble = true)
            }
            val verb = if (isSignIn) "sign-in" else "sign-up"
            val labelTarget = matchedCred?.serviceName ?: target.ifBlank { "account" }
            return openWebsiteInChrome(context, urlToOpen).copy(
                replyText = "Opening $labelTarget $verb page in Chrome, Sir. Once the form loads, tap Fill Login on my floating orb (or say \"Guide me\") and I will enter your saved credentials and walk you through each step.",
                actionLabel = if (isSignIn) "Sign-In Assist" else "Sign-Up Assist"
            )
        }

        if (svc == null) {
            if (Settings.canDrawOverlays(context)) {
                JarvisFloatingOrbService.start(context, expandBubble = true)
            }
            return SystemExecutionResult(
                handled = true,
                replyText = "Turn on JARVIS Screen Helper in Home or Android Accessibility Settings so I can detect and fill login fields directly on screen, Sir.",
                actionLabel = "Screen Helper Needed"
            )
        }

        if (!hasRealFormOnScreen) {
            if (Settings.canDrawOverlays(context)) {
                JarvisFloatingOrbService.start(context, expandBubble = true)
            }
            val savedNames = savedList.take(4).joinToString(", ") { it.serviceName }
            val msg = if (savedList.isNotEmpty()) {
                "Which website should I sign you in to, Sir? Your saved logins: $savedNames. Tell me the site name, or open the sign-in page and tap Fill Login on my floating orb."
            } else {
                "Navigate to a website's sign-in page in Chrome (and make sure your login is saved under JARVIS's Credentials tab), then tap Fill Login on my floating orb or tell me which website to open, Sir."
            }
            return SystemExecutionResult(
                handled = true,
                replyText = msg,
                actionLabel = if (isSignIn) "Sign-In Ready" else "Sign-Up Ready"
            )
        }

        val generatedPass = if (!isSignIn) store.generateStrongPassword() else null
        val outcome = svc.fillFormFromVault(
            profile = profile,
            savedCredential = matchedCred,
            generatedPassword = generatedPass,
            isSignInHint = isSignIn
        )
        return SystemExecutionResult(true, outcome.message, if (isSignIn) "Sign-In Fill" else "Sign-Up Fill", outcome.success)
    }

    private fun handleFloatingOrbCommand(context: Context, mode: String): SystemExecutionResult {
        if (mode == "close") {
            JarvisFloatingOrbService.stop(context)
            return SystemExecutionResult(true, "Minimizing the floating mini-orb, Sir.", "Floating Orb", true)
        }
        return if (!Settings.canDrawOverlays(context)) {
            SystemExecutionResult(
                handled = true,
                replyText = "To show the glowing mini-orb over other apps, allow 'Display over other apps' on the Home screen or in Settings, Sir.",
                actionLabel = "Overlay Permission Needed",
                launchedTarget = false
            )
        } else {
            JarvisFloatingOrbService.start(context, expandBubble = true)
            SystemExecutionResult(true, "Floating mini-orb is now active over your screen, Sir.", "Floating Orb", true)
        }
    }

    private fun openSpecificAppSettings(context: Context, appQuery: String): SystemExecutionResult {
        val clean = appQuery.trim()
        val queryLower = clean.lowercase(Locale.US)
        val installed = listInstalledLauncherApps(context)

        val matchedPkg = installed.firstOrNull { it.appName.equals(clean, ignoreCase = true) }?.packageName
            ?: installed.firstOrNull { it.appName.lowercase(Locale.US).contains(queryLower) }?.packageName
            ?: knownAppAliases[queryLower]?.firstOrNull()

        if (matchedPkg != null) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", matchedPkg, null)
            )
            return launchIntent(
                context = context,
                intent = intent,
                successReply = "Opening Android app settings for $clean, Sir.",
                failureReply = "Unable to open app settings for $clean, Sir.",
                actionLabel = "App Settings"
            )
        }

        return launchIntent(
            context = context,
            intent = Intent(Settings.ACTION_APPLICATION_SETTINGS),
            successReply = "I couldn't pinpoint \"$clean\", so I opened the Apps Settings list for you, Sir.",
            failureReply = "Unable to open Android Apps Settings, Sir.",
            actionLabel = "Apps Settings"
        )
    }

    private fun launchInstalledApp(context: Context, rawQuery: String): SystemExecutionResult {
        val cleanQuery = rawQuery.trim()
        val queryLower = cleanQuery.lowercase(Locale.US)
        val pm = context.packageManager

        if (queryLower == "settings" || queryLower == "system settings") {
            return openSystemSettings(context, "general")
        }

        val installedApps = listInstalledLauncherApps(context)

        // 1. Exact label match
        val exactMatch = installedApps.firstOrNull { it.appName.equals(cleanQuery, ignoreCase = true) }
        if (exactMatch != null) {
            pm.getLaunchIntentForPackage(exactMatch.packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                if (Settings.canDrawOverlays(context)) {
                    JarvisFloatingOrbService.start(context, expandBubble = false)
                }
                return launchIntent(
                    context = context,
                    intent = intent,
                    successReply = "Opening ${exactMatch.appName}, Sir.",
                    failureReply = "I located ${exactMatch.appName}, but Android prevented opening it from the background. Turn on Screen Helper or Display Over Other Apps.",
                    actionLabel = "App Launch"
                )
            }
        }

        // 2. Curated package aliases BEFORE fuzzy substring match (so "X" opens X/Twitter, not Netflix/Exness)
        val aliasPackages = knownAppAliases[queryLower].orEmpty()
        for (pkg in aliasPackages) {
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg).takeIf {
                    it.resolveActivity(pm) != null
                }
                ?: if (queryLower in browserTargetNames) {
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).setPackage(pkg).takeIf {
                        it.resolveActivity(pm) != null
                    }
                } else null

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                if (Settings.canDrawOverlays(context)) {
                    JarvisFloatingOrbService.start(context, expandBubble = false)
                }
                val result = launchIntent(
                    context = context,
                    intent = intent,
                    successReply = "Opening $cleanQuery, Sir.",
                    failureReply = "",
                    actionLabel = "App Launch"
                )
                if (result.launchedTarget) return result
            }
        }

        // 3. Fuzzy installed app label match
        val fuzzyMatch = installedApps.firstOrNull { it.appName.lowercase(Locale.US).startsWith(queryLower) }
            ?: installedApps.firstOrNull { queryLower.length >= 3 && it.appName.lowercase(Locale.US).contains(queryLower) }
            ?: installedApps.firstOrNull { it.appName.length >= 3 && queryLower.contains(it.appName.lowercase(Locale.US)) }

        if (fuzzyMatch != null) {
            pm.getLaunchIntentForPackage(fuzzyMatch.packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                if (Settings.canDrawOverlays(context)) {
                    JarvisFloatingOrbService.start(context, expandBubble = false)
                }
                return launchIntent(
                    context = context,
                    intent = intent,
                    successReply = "Opening ${fuzzyMatch.appName}, Sir.",
                    failureReply = "I located ${fuzzyMatch.appName}, but Android prevented launching it from background.",
                    actionLabel = "App Launch"
                )
            }
        }

        // If app isn't installed locally, check curated website directory & open in Chrome cleanly!
        websiteDirectory[queryLower]?.let { webUrl ->
            return openWebsiteInChrome(context, webUrl)
        }

        val category = when (queryLower) {
            "chrome", "google chrome", "browser", "internet" -> Intent.CATEGORY_APP_BROWSER
            "calculator" -> Intent.CATEGORY_APP_CALCULATOR
            "calendar" -> Intent.CATEGORY_APP_CALENDAR
            "contacts" -> Intent.CATEGORY_APP_CONTACTS
            "email", "mail", "gmail" -> Intent.CATEGORY_APP_EMAIL
            "gallery", "photos" -> Intent.CATEGORY_APP_GALLERY
            "maps", "navigation" -> Intent.CATEGORY_APP_MAPS
            "messages", "sms", "text" -> Intent.CATEGORY_APP_MESSAGING
            "music", "audio" -> Intent.CATEGORY_APP_MUSIC
            else -> null
        }
        if (category != null) {
            val catIntent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val result = launchIntent(
                context = context,
                intent = catIntent,
                successReply = "Opening $cleanQuery, Sir.",
                failureReply = "",
                actionLabel = "App Launch"
            )
            if (result.launchedTarget) return result
        }

        val sample = installedApps.take(5).joinToString(", ") { it.appName }
        val hint = if (sample.isNotBlank()) " Installed apps include: $sample." else ""
        return SystemExecutionResult(
            handled = true,
            replyText = "I could not find \"$cleanQuery\" installed as an app, Sir.$hint If you want me to open its website in Chrome, say \"Open $cleanQuery website\" or tell me its link.",
            actionLabel = "App Lookup",
            launchedTarget = false
        )
    }

    private fun openSystemSettings(context: Context, target: String): SystemExecutionResult {
        val lower = target.lowercase(Locale.US).trim()
        val intent = when (lower) {
            "wifi", "wi-fi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "network", "mobile data", "internet" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "display", "brightness" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound", "volume" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "location", "gps" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "privacy" -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "apps", "all apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            "date", "time" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
            "cast" -> Intent(Settings.ACTION_CAST_SETTINGS)
            "vpn" -> Intent(Settings.ACTION_VPN_SETTINGS)
            "accounts" -> Intent(Settings.ACTION_SYNC_SETTINGS)
            "keyboard" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "about", "about phone" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            "hotspot", "tethering" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "dark theme", "night display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        return launchIntent(
            context = context,
            intent = intent,
            successReply = "Opening ${target.ifBlank { "system" }} settings, Sir.",
            failureReply = "Unable to open ${target.ifBlank { "system" }} settings on this device, Sir.",
            actionLabel = "System Settings"
        )
    }

    fun buildSystemTelemetryReport(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val isCharging = bm?.isCharging == true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val networkLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi Connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Data Connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet Connected"
            else -> "Online"
        }

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mediaVol = audio?.let {
            val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = it.getStreamVolume(AudioManager.STREAM_MUSIC)
            "${(cur * 100) / max}%"
        } ?: "Unknown"

        val screenHelper = if (JarvisAccessibilityService.isConnected.value) "Active" else "Off"
        val timeStr = SimpleDateFormat("h:mm a, EEE MMM d", Locale.getDefault()).format(Date())
        val batText = batteryPct?.let { "$it%${if (isCharging) " (Charging)" else ""}" } ?: "Unavailable"
        return "System telemetry, Sir: Battery $batText, Network $networkLabel, Media Volume $mediaVol, Screen Helper $screenHelper, Local Time $timeStr."
    }

    private fun launchIntent(
        context: Context,
        intent: Intent,
        successReply: String,
        failureReply: String,
        actionLabel: String
    ): SystemExecutionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
        val canResolve = runCatching { intent.resolveActivity(pm) != null }.getOrDefault(false)
        if (!canResolve) {
            return SystemExecutionResult(
                handled = failureReply.isNotBlank(),
                replyText = failureReply,
                actionLabel = actionLabel,
                launchedTarget = false
            )
        }

        val accInstance = JarvisAccessibilityService.activeInstance()
        // AccessibilityService or an active Activity bypasses Android 10-16 BAL restrictions directly
        val starterContext = when {
            context is android.app.Activity -> context
            accInstance != null -> accInstance
            else -> context
        }

        if (starterContext === accInstance || starterContext is android.app.Activity) {
            val directOk = runCatching {
                starterContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (directOk) {
                return SystemExecutionResult(
                    handled = true,
                    replyText = successReply,
                    actionLabel = actionLabel,
                    launchedTarget = true
                )
            }
        }

        // On Android 14+ (API 34+) without AccessibilityService, PendingIntent + BAL opt-in honors SYSTEM_ALERT_WINDOW exemption
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Settings.canDrawOverlays(context)) {
            val piSent = runCatching {
                val opts = ActivityOptions.makeBasic().apply {
                    @Suppress("DEPRECATION")
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    )
                }
                val reqCode = (System.currentTimeMillis() and 0xFFFF).toInt()
                val pi = PendingIntent.getActivity(
                    context,
                    reqCode,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                pi.send(context, 0, null, null, null, null, opts.toBundle())
                true
            }.getOrDefault(false)
            if (piSent) {
                return SystemExecutionResult(
                    handled = true,
                    replyText = successReply,
                    actionLabel = actionLabel,
                    launchedTarget = true
                )
            }
        }

        return runCatching {
            context.startActivity(intent)
            SystemExecutionResult(
                handled = true,
                replyText = successReply,
                actionLabel = actionLabel,
                launchedTarget = true
            )
        }.getOrElse {
            SystemExecutionResult(
                handled = failureReply.isNotBlank(),
                replyText = failureReply,
                actionLabel = actionLabel,
                launchedTarget = false
            )
        }
    }
}
