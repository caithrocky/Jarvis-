package com.example.assistant

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DeviceStudyReport(
    val totalAppsIndexed: Int,
    val messagingApps: List<String>,
    val socialApps: List<String>,
    val summaryMessage: String
)

/**
 * Self-learning engine that studies installed device apps/programs, live UI layouts,
 * and owner habits every day so JARVIS improves autonomously over time.
 */
class DeviceLearningEngine(
    private val context: Context,
    private val localRepo: JarvisLocalRepository,
    private val prefsRepo: JarvisPreferencesRepository
) {
    suspend fun studyDeviceNow(force: Boolean = false): DeviceStudyReport = withContext(Dispatchers.IO) {
        val installed = JarvisSystemAssistant.listInstalledLauncherApps(context)
        val existingMap = localRepo.getAllDeviceAppsSnapshot().associateBy { it.packageName }
        val now = System.currentTimeMillis()

        val studiedEntities = installed.map { app ->
            val prev = existingMap[app.packageName]
            val category = classifyApp(app.appName, app.packageName)
            DeviceAppKnowledgeEntity(
                packageName = app.packageName,
                appName = app.appName,
                category = category,
                discoveredScreensSummary = prev?.discoveredScreensSummary.orEmpty(),
                usageCount = prev?.usageCount ?: 0,
                lastStudiedMs = now
            )
        }

        if (studiedEntities.isNotEmpty()) {
            localRepo.upsertDeviceApps(studiedEntities)
        }

        // Study active foreground app buttons/fields if Accessibility Screen Helper is enabled
        JarvisAccessibilityService.activeInstance()?.let { svc ->
            val fgPkg = svc.activeForegroundPackage()
            if (!fgPkg.isNullOrBlank() && fgPkg != context.packageName) {
                val matched = studiedEntities.firstOrNull { it.packageName == fgPkg }
                val summary = svc.readScreenSummary().take(280)
                if (matched != null && summary.isNotBlank()) {
                    localRepo.upsertDeviceApp(
                        matched.copy(
                            discoveredScreensSummary = summary,
                            lastStudiedMs = now
                        )
                    )
                }
            }
        }

        // Distill high-level habit insights from recent activity & history
        distillDailyOwnerHabits(studiedEntities)
        prefsRepo.markDailyLearnCompleted(now)

        val messaging = studiedEntities.filter { it.category == "Messaging" }.map { it.appName }
        val social = studiedEntities.filter { it.category == "Social" }.map { it.appName }

        localRepo.logAiActivity(
            "DAILY_STUDY",
            "Indexed ${studiedEntities.size} device apps (${messaging.size} messaging, ${social.size} social)"
        )

        val msg = "Device study complete, Sir: I indexed ${studiedEntities.size} installed apps " +
            "(Messaging: ${messaging.take(4).joinToString(", ").ifBlank { "System SMS" }}; " +
            "Social: ${social.take(4).joinToString(", ").ifBlank { "Browser" }}) and updated your personal habit profile."

        DeviceStudyReport(
            totalAppsIndexed = studiedEntities.size,
            messagingApps = messaging,
            socialApps = social,
            summaryMessage = msg
        )
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
                discoveredScreensSummary = screenSummary.ifBlank { matched.discoveredScreensSummary }.take(280),
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

    suspend fun buildCompactKnowledgeContext(): String = withContext(Dispatchers.IO) {
        val apps = localRepo.getAllDeviceAppsSnapshot()
        val workflows = localRepo.getTopWorkflows()
        val habits = localRepo.getTopHabits()

        buildString {
            if (apps.isNotEmpty()) {
                val topApps = apps.sortedByDescending { it.usageCount }.take(18)
                    .joinToString(", ") { "${it.appName}[${it.category}]" }
                appendLine("Installed Apps Studied (${apps.size} total): $topApps")
            }
            if (habits.isNotEmpty()) {
                appendLine("Learned Owner Habits:")
                habits.take(6).forEach { appendLine("- ${it.summary}") }
            }
            if (workflows.isNotEmpty()) {
                appendLine("Learned App Workflows:")
                workflows.take(5).forEach {
                    appendLine("- ${it.targetPackageOrSite}: \"${it.goalPhrase}\" -> ${it.stepsSummary}")
                }
            }
        }.trim()
    }

    private suspend fun distillDailyOwnerHabits(apps: List<DeviceAppKnowledgeEntity>) {
        val msgApps = apps.filter { it.category == "Messaging" }.take(3).joinToString(", ") { it.appName }
        if (msgApps.isNotBlank()) {
            localRepo.upsertOwnerHabit(
                OwnerHabitEntity(
                    habitKey = "device_messaging_stack",
                    category = "Device Stack",
                    summary = "Installed messaging platforms on device: $msgApps",
                    confidenceScore = 0.9f
                )
            )
        }
        val socialApps = apps.filter { it.category == "Social" }.take(4).joinToString(", ") { it.appName }
        if (socialApps.isNotBlank()) {
            localRepo.upsertOwnerHabit(
                OwnerHabitEntity(
                    habitKey = "device_social_stack",
                    category = "Device Stack",
                    summary = "Installed social platforms on device: $socialApps",
                    confidenceScore = 0.9f
                )
            )
        }
    }

    private fun classifyApp(appName: String, pkg: String): String {
        val sig = "$appName $pkg".lowercase(Locale.US)
        return when {
            sig.contains("whatsapp") || sig.contains("snapchat") || sig.contains("telegram") ||
                sig.contains("messenger") || sig.contains("signal") || sig.contains("discord") ||
                sig.contains("messaging") || sig.contains("sms") -> "Messaging"
            sig.contains("instagram") || sig.contains("tiktok") || sig.contains("musically") ||
                sig.contains("twitter") || sig.contains("x.com") || sig.contains("reddit") ||
                sig.contains("facebook") || sig.contains("linkedin") || sig.contains("threads") ||
                sig.contains("youtube") -> "Social"
            sig.contains("chrome") || sig.contains("browser") || sig.contains("firefox") || sig.contains("opera") -> "Browser"
            sig.contains("trading") || sig.contains("deriv") || sig.contains("binance") ||
                sig.contains("metatrader") || sig.contains("bank") || sig.contains("wallet") ||
                sig.contains("pay") -> "Finance"
            sig.contains("spotify") || sig.contains("music") || sig.contains("netflix") ||
                sig.contains("camera") || sig.contains("photos") || sig.contains("gallery") -> "Media"
            sig.contains("settings") || sig.contains("clock") || sig.contains("dialer") ||
                sig.contains("contacts") || sig.contains("files") -> "System"
            else -> "App"
        }
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
