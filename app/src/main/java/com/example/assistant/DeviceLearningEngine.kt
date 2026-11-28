package com.example.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.JarvisApplication
import com.example.data.local.DeviceAppKnowledgeEntity
import com.example.data.local.JarvisLocalRepository
import com.example.data.local.JarvisPreferencesRepository
import com.example.data.local.OwnerHabitEntity
import com.example.data.remote.WebKnowledgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DeviceStudyReport(
    val totalAppsIndexed: Int,
    val messagingApps: List<String>,
    val socialApps: List<String>,
    val newlyResearchedCount: Int = 0,
    val summaryMessage: String
)

/**
 * Autonomous whole-device & online self-learning engine:
 * - Indexes ALL launcher apps and core system apps/utilities across Entertainment, Marketing,
 *   Online Trading, Messaging, Social, Finance, Browser, Productivity, and System categories.
 * - Immediately inspects and researches newly installed/updated apps online via [WebKnowledgeClient].
 * - Stores concise capability profiles & workflows locally so JARVIS executes commands cleanly.
 */
class DeviceLearningEngine(
    private val context: Context,
    private val localRepo: JarvisLocalRepository,
    private val prefsRepo: JarvisPreferencesRepository,
    private val webClient: WebKnowledgeClient? = null
) {
    suspend fun studyDeviceNow(force: Boolean = false): DeviceStudyReport = withContext(Dispatchers.IO) {
        val launcherApps = JarvisSystemAssistant.listInstalledLauncherApps(context)
        val systemHandlers = inspectSystemCapabilityHandlers(context)
        val combinedApps = (launcherApps + systemHandlers).distinctBy { it.packageName }

        val existingMap = localRepo.getAllDeviceAppsSnapshot().associateBy { it.packageName }
        val now = System.currentTimeMillis()

        val initialEntities = combinedApps.map { app ->
            val prev = existingMap[app.packageName]
            val category = classifyApp(app.appName, app.packageName)
            val fallbackSummary = defaultCapabilitySummary(app.appName, app.packageName, category)
            val keptSummary = prev?.discoveredScreensSummary?.takeIf { it.isNotBlank() } ?: fallbackSummary
            DeviceAppKnowledgeEntity(
                packageName = app.packageName,
                appName = app.appName,
                category = category,
                discoveredScreensSummary = keptSummary,
                usageCount = prev?.usageCount ?: 0,
                lastStudiedMs = now
            )
        }

        if (initialEntities.isNotEmpty()) {
            localRepo.upsertDeviceApps(initialEntities)
        }

        // Prune uninstalled packages if we have launcher visibility
        val currentPkgSet = combinedApps.map { it.packageName }.toSet()
        existingMap.keys.filter { it !in currentPkgSet }.forEach { removedPkg ->
            runCatching { localRepo.deleteDeviceApp(removedPkg) }
        }

        // Research unlearned or newly discovered apps online when network is connected
        var researchedCount = 0
        if (webClient != null && OfflineAssistantEngine.isNetworkConnected(context)) {
            val candidates = initialEntities
                .filter { entity ->
                    val prevSummary = existingMap[entity.packageName]?.discoveredScreensSummary.orEmpty()
                    force && !prevSummary.startsWith("[Online]") ||
                        (!prevSummary.startsWith("[Online]") && entity.discoveredScreensSummary.length < 60) ||
                        existingMap[entity.packageName] == null
                }
                .take(if (force) 14 else 8)

            if (candidates.isNotEmpty()) {
                val gate = Semaphore(3)
                val updated = coroutineScope {
                    candidates.map { entity ->
                        async {
                            gate.withPermit {
                                val enriched = researchAppOnline(entity)
                                if (enriched.discoveredScreensSummary != entity.discoveredScreensSummary) {
                                    researchedCount++
                                }
                                enriched
                            }
                        }
                    }.awaitAll()
                }
                localRepo.upsertDeviceApps(updated)
            }
        }

        // Study active foreground app buttons/fields if Accessibility Screen Helper is enabled
        JarvisAccessibilityService.activeInstance()?.let { svc ->
            val fgPkg = svc.activeForegroundPackage()
            if (!fgPkg.isNullOrBlank() && fgPkg != context.packageName) {
                val matched = localRepo.getAllDeviceAppsSnapshot().firstOrNull { it.packageName == fgPkg }
                val screenText = svc.readScreenSummary().take(240)
                if (matched != null && screenText.isNotBlank()) {
                    val combinedSummary = if (matched.discoveredScreensSummary.startsWith("[Online]")) {
                        "${matched.discoveredScreensSummary.take(200)} | UI: ${screenText.take(120)}"
                    } else {
                        screenText
                    }
                    localRepo.upsertDeviceApp(
                        matched.copy(
                            discoveredScreensSummary = combinedSummary.take(360),
                            lastStudiedMs = now
                        )
                    )
                }
            }
        }

        val finalSnapshot = localRepo.getAllDeviceAppsSnapshot()
        distillDailyOwnerHabits(finalSnapshot)
        prefsRepo.markDailyLearnCompleted(now)

        val messaging = finalSnapshot.filter { it.category == "Messaging" }.map { it.appName }
        val social = finalSnapshot.filter { it.category == "Social" }.map { it.appName }
        val trading = finalSnapshot.filter { it.category == "Trading" || it.category == "Finance" }.map { it.appName }
        val marketing = finalSnapshot.filter { it.category == "Marketing" }.map { it.appName }
        val entertainment = finalSnapshot.filter { it.category == "Entertainment" }.map { it.appName }

        localRepo.logAiActivity(
            "DAILY_STUDY",
            "Indexed ${finalSnapshot.size} apps (Online researched: $researchedCount | Trading: ${trading.size}, Marketing: ${marketing.size}, Entertainment: ${entertainment.size})"
        )

        val msg = buildString {
            append("Full device study complete, Sir: I indexed ${finalSnapshot.size} apps including system tools")
            if (researchedCount > 0) append(" and learned $researchedCount online")
            append(". ")
            if (messaging.isNotEmpty()) append("Messaging: ${messaging.take(3).joinToString(", ")}. ")
            if (trading.isNotEmpty()) append("Trading: ${trading.take(3).joinToString(", ")}. ")
            if (marketing.isNotEmpty()) append("Marketing: ${marketing.take(3).joinToString(", ")}. ")
            if (entertainment.isNotEmpty()) append("Entertainment: ${entertainment.take(3).joinToString(", ")}.")
        }.trim()

        DeviceStudyReport(
            totalAppsIndexed = finalSnapshot.size,
            messagingApps = messaging,
            socialApps = social,
            newlyResearchedCount = researchedCount,
            summaryMessage = msg
        )
    }

    /**
     * Triggered automatically when Android installs or updates a package on the device.
     * Inspects app metadata & system capabilities, researches its commands online, and stores it.
     */
    suspend fun learnSingleApp(packageName: String, triggerReason: String = "new_install"): DeviceAppKnowledgeEntity? =
        withContext(Dispatchers.IO) {
            val cleanPkg = packageName.trim()
            if (cleanPkg.isBlank() || cleanPkg == context.packageName) return@withContext null

            val pm = context.packageManager
            val appName = runCatching {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(cleanPkg, PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(cleanPkg, 0)
                }
                pm.getApplicationLabel(appInfo).toString().trim()
            }.getOrNull().takeIf { !it.isNullOrBlank() } ?: cleanPkg.substringAfterLast('.')

            val category = classifyApp(appName, cleanPkg)
            val prev = localRepo.getAllDeviceAppsSnapshot().firstOrNull { it.packageName == cleanPkg }
            val baseEntity = DeviceAppKnowledgeEntity(
                packageName = cleanPkg,
                appName = appName,
                category = category,
                discoveredScreensSummary = defaultCapabilitySummary(appName, cleanPkg, category),
                usageCount = prev?.usageCount ?: 0,
                lastStudiedMs = System.currentTimeMillis()
            )

            val enriched = if (webClient != null && OfflineAssistantEngine.isNetworkConnected(context)) {
                researchAppOnline(baseEntity)
            } else {
                baseEntity
            }

            localRepo.upsertDeviceApp(enriched)
            localRepo.saveLearnedWorkflow(
                targetPackageOrSite = cleanPkg,
                goalPhrase = "Use $appName ($category)",
                stepsSummary = enriched.discoveredScreensSummary.take(200)
            )
            localRepo.logAiActivity(
                "AUTO_LEARN_APP",
                "[$triggerReason] Learned $appName [$category]: ${enriched.discoveredScreensSummary.take(120)}"
            )
            enriched
        }

    suspend fun onPackageRemoved(packageName: String) = withContext(Dispatchers.IO) {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return@withContext
        runCatching { localRepo.deleteDeviceApp(cleanPkg) }
        localRepo.logAiActivity("APP_REMOVED", "Removed uninstalled app index: $cleanPkg")
    }

    private suspend fun researchAppOnline(entity: DeviceAppKnowledgeEntity): DeviceAppKnowledgeEntity {
        val client = webClient ?: return entity
        return runCatching {
            val query = "\"${entity.appName}\" android app features commands guide"
            val result = client.searchInternet(query)
            val combinedSnippets = result.items.take(2)
                .map { "${it.title}: ${it.snippet}" }
                .joinToString(" | ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (combinedSnippets.isNotBlank()) {
                val baseHint = defaultCapabilitySummary(entity.appName, entity.packageName, entity.category)
                entity.copy(
                    discoveredScreensSummary = "[Online] $combinedSnippets | $baseHint".take(360),
                    lastStudiedMs = System.currentTimeMillis()
                )
            } else {
                entity
            }
        }.getOrDefault(entity)
    }

    suspend fun studyIfDailyDue() {
        val settings = prefsRepo.settingsFlow.first()
        if (!settings.autoLearnDeviceDaily) return
        val elapsed = System.currentTimeMillis() - settings.lastDailyLearnMs
        if (elapsed >= TimeUnit.HOURS.toMillis(24) || localRepo.getAllDeviceAppsSnapshot().isEmpty()) {
            studyDeviceNow(force = false)
        }
    }

    suspend fun recordAppInteraction(appQuery: String, screenSummary: String = "") = withContext(Dispatchers.IO) {
        val q = appQuery.trim().lowercase(Locale.US)
        if (q.isBlank()) return@withContext
        val apps = localRepo.getAllDeviceAppsSnapshot()
        val matched = apps.firstOrNull {
            it.appName.lowercase(Locale.US) == q ||
                it.packageName.lowercase(Locale.US) == q ||
                it.appName.lowercase(Locale.US).contains(q)
        } ?: return@withContext

        localRepo.upsertDeviceApp(
            matched.copy(
                usageCount = matched.usageCount + 1,
                discoveredScreensSummary = screenSummary.ifBlank { matched.discoveredScreensSummary }.take(360),
                lastStudiedMs = System.currentTimeMillis()
            )
        )
        localRepo.upsertOwnerHabit(
            OwnerHabitEntity(
                habitKey = "frequent_app_${matched.category.lowercase(Locale.US)}",
                category = "App Preference",
                summary = "Sir frequently uses ${matched.appName} (${matched.category})",
                confidenceScore = 0.78f
            )
        )
    }

    suspend fun recordCommandHabit(prompt: String, intentKind: SystemIntentKind) = withContext(Dispatchers.IO) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeBucket = when (hour) {
            in 5..11 -> "morning"
            in 12..17 -> "afternoon"
            else -> "evening"
        }
        val habit = when (intentKind) {
            SystemIntentKind.TEXT_CONTACT, SystemIntentKind.SEND_SMS -> OwnerHabitEntity(
                habitKey = "comm_style_$timeBucket",
                category = "Communication",
                summary = "Sir often sends messages via voice in the $timeBucket",
                confidenceScore = 0.75f
            )
            SystemIntentKind.SOCIAL_ACTION -> OwnerHabitEntity(
                habitKey = "social_nav",
                category = "Social Media",
                summary = "Sir navigates and engages social feeds hands-free",
                confidenceScore = 0.80f
            )
            SystemIntentKind.OPEN_URL, SystemIntentKind.PLATFORM_SEARCH -> OwnerHabitEntity(
                habitKey = "web_research",
                category = "Web & Research",
                summary = "Sir uses Chrome and live internet search for rapid research",
                confidenceScore = 0.78f
            )
            else -> null
        }
        if (habit != null) {
            localRepo.upsertOwnerHabit(habit)
        }
    }

    suspend fun findBestInstalledAppByCategoryOrQuery(query: String): DeviceAppKnowledgeEntity? =
        withContext(Dispatchers.IO) {
            val apps = localRepo.getAllDeviceAppsSnapshot()
            val qLower = query.trim().lowercase(Locale.US)
            if (qLower.isBlank()) return@withContext null
            apps.firstOrNull { it.appName.equals(query.trim(), ignoreCase = true) }
                ?: apps.firstOrNull { it.category.equals(query.trim(), ignoreCase = true) }
                ?: apps.firstOrNull {
                    it.appName.lowercase(Locale.US).contains(qLower) ||
                        it.discoveredScreensSummary.lowercase(Locale.US).contains(qLower)
                }
        }

    suspend fun buildCompactKnowledgeContext(queryHint: String = ""): String = withContext(Dispatchers.IO) {
        val apps = localRepo.getAllDeviceAppsSnapshot()
        val workflows = localRepo.getTopWorkflows()
        val habits = localRepo.getTopHabits()
        val hintTokens = queryHint.lowercase(Locale.US)
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }

        buildString {
            if (apps.isNotEmpty()) {
                val categoryCounts = apps.groupingBy { it.category }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}:${it.value}" }
                appendLine("Installed Device Apps (${apps.size} total | $categoryCounts):")

                val relevantApps = if (hintTokens.isEmpty()) {
                    emptyList()
                } else {
                    apps.filter { app ->
                        val sig = "${app.appName} ${app.category} ${app.discoveredScreensSummary}".lowercase(Locale.US)
                        hintTokens.any { sig.contains(it) }
                    }.take(6)
                }
                val topApps = (relevantApps + apps.sortedByDescending { it.usageCount })
                    .distinctBy { it.packageName }
                    .take(18)

                topApps.forEach { app ->
                    val desc = app.discoveredScreensSummary.take(110).ifBlank { app.category }
                    appendLine("- ${app.appName} [${app.category}]: $desc")
                }
            }
            if (habits.isNotEmpty()) {
                appendLine("Learned Owner Habits:")
                habits.take(5).forEach { appendLine("- ${it.summary}") }
            }
            if (workflows.isNotEmpty()) {
                appendLine("Learned App Workflows:")
                workflows.take(5).forEach {
                    appendLine("- ${it.targetPackageOrSite}: \"${it.goalPhrase}\" -> ${it.stepsSummary.take(90)}")
                }
            }
        }.trim()
    }

    private suspend fun distillDailyOwnerHabits(apps: List<DeviceAppKnowledgeEntity>) {
        val categoryStacks = listOf(
            "Messaging" to "device_messaging_stack",
            "Social" to "device_social_stack",
            "Trading" to "device_trading_stack",
            "Marketing" to "device_marketing_stack",
            "Entertainment" to "device_entertainment_stack"
        )
        categoryStacks.forEach { (cat, key) ->
            val matched = apps.filter { it.category == cat }.take(4).joinToString(", ") { it.appName }
            if (matched.isNotBlank()) {
                localRepo.upsertOwnerHabit(
                    OwnerHabitEntity(
                        habitKey = key,
                        category = "Device Stack",
                        summary = "Installed $cat apps: $matched",
                        confidenceScore = 0.9f
                    )
                )
            }
        }
    }

    private fun inspectSystemCapabilityHandlers(context: Context): List<InstalledAppSummary> {
        val pm = context.packageManager
        val probeIntents = listOf(
            Intent(AlarmClock.ACTION_SET_ALARM),
            Intent(AlarmClock.ACTION_SET_TIMER),
            Intent(Settings.ACTION_SETTINGS),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_WEB_SEARCH)
        )
        val found = mutableListOf<InstalledAppSummary>()
        probeIntents.forEach { intent ->
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            resolved.forEach { info ->
                val pkg = info.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName) return@forEach
                val label = info.loadLabel(pm).toString().trim().ifBlank { pkg.substringAfterLast('.') }
                found.add(InstalledAppSummary(label, pkg))
            }
        }
        return found.distinctBy { it.packageName }
    }

    companion object {
        private const val WORK_NAME = "jarvis_daily_device_learning"

        fun scheduleDailyStudy(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyDeviceStudyWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        internal fun classifyApp(appName: String, pkg: String): String {
            val sig = "$appName $pkg".lowercase(Locale.US)
            return when {
                sig.contains("whatsapp") || sig.contains("snapchat") || sig.contains("telegram") ||
                    sig.contains("messenger") || sig.contains("signal") || sig.contains("discord") ||
                    sig.contains("viber") || sig.contains("slack") || sig.contains("teams") ||
                    sig.contains("messaging") || sig.contains("sms") -> "Messaging"

                sig.contains("trading") || sig.contains("deriv") || sig.contains("binance") ||
                    sig.contains("metatrader") || sig.contains("coinbase") || sig.contains("bybit") ||
                    sig.contains("exness") || sig.contains("okx") || sig.contains("kraken") ||
                    sig.contains("etoro") || sig.contains("robinhood") || sig.contains("quotex") ||
                    sig.contains("olymptrade") || sig.contains("pocketoption") || sig.contains("forex") ||
                    sig.contains("investing") || sig.contains("bloomberg") -> "Trading"

                sig.contains("canva") || sig.contains("capcut") || sig.contains("picsart") ||
                    sig.contains("mailchimp") || sig.contains("hubspot") || sig.contains("hootsuite") ||
                    sig.contains("buffer") || sig.contains("shopify") || sig.contains("ads") ||
                    sig.contains("analytics") || sig.contains("business suite") || sig.contains("marketing") ||
                    sig.contains("lightroom") || sig.contains("inshot") -> "Marketing"

                sig.contains("instagram") || sig.contains("tiktok") || sig.contains("musically") ||
                    sig.contains("twitter") || sig.contains("x.com") || sig.contains("reddit") ||
                    sig.contains("facebook") || sig.contains("linkedin") || sig.contains("threads") ||
                    sig.contains("pinterest") -> "Social"

                sig.contains("youtube") || sig.contains("netflix") || sig.contains("spotify") ||
                    sig.contains("twitch") || sig.contains("prime video") || sig.contains("disney") ||
                    sig.contains("hulu") || sig.contains("music") || sig.contains("podcast") ||
                    sig.contains("audible") || sig.contains("vlc") || sig.contains("mxplayer") ||
                    sig.contains("game") || sig.contains("roblox") || sig.contains("pubg") -> "Entertainment"

                sig.contains("chrome") || sig.contains("browser") || sig.contains("firefox") ||
                    sig.contains("opera") || sig.contains("brave") || sig.contains("tor") ||
                    sig.contains("edge") -> "Browser"

                sig.contains("bank") || sig.contains("wallet") || sig.contains("paypal") ||
                    sig.contains("mpesa") || sig.contains("revolut") || sig.contains("wise") ||
                    sig.contains("cashapp") || sig.contains("venmo") -> "Finance"

                sig.contains("wps") || sig.contains("office") || sig.contains("docs") ||
                    sig.contains("sheets") || sig.contains("outlook") || sig.contains("gmail") ||
                    sig.contains("notion") || sig.contains("trello") || sig.contains("keep") ||
                    sig.contains("notes") || sig.contains("drive") || sig.contains("scanner") -> "Productivity"

                sig.contains("settings") || sig.contains("clock") || sig.contains("deskclock") ||
                    sig.contains("dialer") || sig.contains("contacts") || sig.contains("files") ||
                    sig.contains("documentsui") || sig.contains("calendar") || sig.contains("calculator") ||
                    sig.contains("camera") || sig.contains("photos") || sig.contains("gallery") -> "System"

                else -> "Utility"
            }
        }

        internal fun defaultCapabilitySummary(appName: String, pkg: String, category: String): String {
            val sig = "$appName $pkg".lowercase(Locale.US)
            return when {
                sig.contains("clock") || sig.contains("alarm") ->
                    "System Clock: set alarms, countdown timers, stopwatch, world clock."
                sig.contains("settings") ->
                    "Android System Settings: Wi-Fi, Bluetooth, display, battery, sound, accessibility panels."
                sig.contains("calendar") ->
                    "Calendar: create events, reminders, check meetings."
                sig.contains("camera") ->
                    "Camera: capture photo, front selfie, record video."
                sig.contains("dialer") || sig.contains("phone") ->
                    "Phone Dialer: place calls, recent call log."
                sig.contains("calculator") ->
                    "Calculator: arithmetic & scientific calculations."
                category == "Messaging" ->
                    "$appName messaging: open chats, search contacts, dictate & confirm messages."
                category == "Trading" ->
                    "$appName online trading: live charts, watchlists, order book & market analysis."
                category == "Marketing" ->
                    "$appName marketing & creative studio: templates, designs, campaigns, reels & analytics."
                category == "Entertainment" ->
                    "$appName entertainment & media: stream video/music, search titles, playback & scroll."
                category == "Social" ->
                    "$appName social feed: scroll posts, like, comment, repost, DMs & search creators."
                category == "Productivity" ->
                    "$appName productivity: compose docs/emails, scan, organize files."
                category == "Browser" ->
                    "$appName browser: open URLs, web search, fill forms with permission."
                else -> "$appName [$category]: launch and automate visible controls via Screen Helper."
            }
        }
    }
}

class DailyDeviceStudyWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? JarvisApplication ?: return Result.success()
        app.deviceLearningEngine.studyDeviceNow(force = false)
        return Result.success()
    }
}
