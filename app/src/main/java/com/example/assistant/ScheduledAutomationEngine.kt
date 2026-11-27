package com.example.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.ScheduledAutomationEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ParsedScheduledTaskSpec(
    val title: String,
    val commandPrompt: String,
    val triggerAtMs: Long,
    val repeatDaily: Boolean
)

/**
 * Real timed-automation scheduler & natural-language time parser.
 * Uses Play-Policy compliant AlarmManager.setAndAllowWhileIdle backed by in-process due checks
 * and BootRecoveryReceiver rescheduling so voice-scheduled tasks execute reliably at their target time.
 */
object ScheduledAutomationEngine {

    const val ACTION_RUN_SCHEDULED_TASK = "com.example.assistant.ACTION_RUN_SCHEDULED_TASK"
    const val EXTRA_TASK_ID = "extra_task_id"

    fun scheduleAlarm(context: Context, task: ScheduledAutomationEntity) {
        if (!task.isEnabled || task.id <= 0L) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPendingIntent(context, task.id) ?: return
        val fireAt = maxOf(task.triggerAtMs, System.currentTimeMillis() + 3_000L)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
        }
    }

    fun cancelAlarm(context: Context, taskId: Long) {
        if (taskId <= 0L) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ScheduledAutomationReceiver::class.java).apply {
            action = ACTION_RUN_SCHEDULED_TASK
        }
        val pi = PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pi != null) {
            runCatching { alarmManager.cancel(pi) }
            runCatching { pi.cancel() }
        }
    }

    private fun buildPendingIntent(context: Context, taskId: Long): PendingIntent? {
        val intent = Intent(context, ScheduledAutomationReceiver::class.java).apply {
            action = ACTION_RUN_SCHEDULED_TASK
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Parses natural-language scheduled commands such as:
     * - "At 7:30 am open WhatsApp and say good morning"
     * - "Every day at 8 am give me my daily briefing"
     * - "In 15 minutes remind me to check my emails"
     * - "Schedule study installed apps at 9:00 pm"
     * - "At 11 pm make the device sleep"
     */
    fun parseScheduleCommand(
        rawPrompt: String,
        nowMs: Long = System.currentTimeMillis()
    ): ParsedScheduledTaskSpec? {
        val trimmed = rawPrompt.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.US)

        // Do not intercept plain alarm/timer clock app intents unless paired with an automation/action/reminder
        val looksLikePlainClockAlarm = (lower.startsWith("set an alarm") || lower.startsWith("set alarm") ||
            lower.startsWith("wake me up") || lower.startsWith("set a timer") || lower.startsWith("set timer")) &&
            !lower.contains("automate") && !lower.contains("schedule") && !lower.contains("run ")
        if (looksLikePlainClockAlarm) return null

        val repeatDaily = Regex("""\b(every day|everyday|daily|every morning|every evening|every night)\b""")
            .containsMatchIn(lower)

        // Case 1: Relative delay — "in 15 minutes ...", "after 2 hours ..."
        val relativeRegex = Regex(
            """\b(?:in|after)\s+(\d{1,3})\s*(second|seconds|sec|secs|minute|minutes|min|mins|hour|hours|hr|hrs)\b""",
            RegexOption.IGNORE_CASE
        )
        val relMatch = relativeRegex.find(trimmed)
        if (relMatch != null && (
                lower.contains("remind") ||
                lower.contains("schedule") ||
                lower.contains("automate") ||
                lower.contains("open ") ||
                lower.contains("check ") ||
                lower.contains("run ") ||
                lower.contains("send ") ||
                lower.contains("sleep") ||
                lower.contains("lock ") ||
                lower.contains("brief")
            )
        ) {
            val amount = relMatch.groupValues[1].toLongOrNull()?.coerceAtLeast(1L) ?: return null
            val unit = relMatch.groupValues[2].lowercase(Locale.US)
            val delayMs = when {
                unit.startsWith("h") -> amount * 3_600_000L
                unit.startsWith("m") -> amount * 60_000L
                else -> amount * 1_000L
            }
            val cleanedTask = cleanupTaskPhrase(trimmed.removeRange(relMatch.range))
            if (cleanedTask.isBlank()) return null
            val title = buildConciseTitle(cleanedTask)
            return ParsedScheduledTaskSpec(
                title = title,
                commandPrompt = cleanedTask,
                triggerAtMs = nowMs + delayMs,
                repeatDaily = repeatDaily
            )
        }

        // Case 2: Clock time — "at 7:30 am", "at 21:00", "at 8 pm", "at 7 tomorrow"
        val atTimeRegex = Regex(
            """\b(?:at\s+|for\s+|every\s+(?:morning|evening|night|day)\s+at\s+)(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?\b""",
            RegexOption.IGNORE_CASE
        )
        val timeMatch = atTimeRegex.find(trimmed) ?: return null
        val rawHour = timeMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = timeMatch.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
        val meridiem = timeMatch.groupValues[3].replace(".", "").lowercase(Locale.US)

        if (rawHour !in 0..23) return null
        if (meridiem.isEmpty() && timeMatch.groupValues[2].isBlank() &&
            !lower.contains("schedule") && !lower.contains("every") && !lower.contains("remind") &&
            !lower.contains("tomorrow") && !lower.contains("morning") && !lower.contains("night") &&
            !lower.contains("evening") && !lower.contains("automate")
        ) {
            return null
        }

        val hour24 = when {
            meridiem == "pm" && rawHour in 1..11 -> rawHour + 12
            meridiem == "am" && rawHour == 12 -> 0
            meridiem.isEmpty() && rawHour in 1..11 && (lower.contains("tonight") || lower.contains("evening") || lower.contains("night")) -> rawHour + 12
            else -> rawHour.coerceIn(0, 23)
        }

        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (lower.contains("tomorrow") && timeInMillis <= nowMs) {
                add(Calendar.DAY_OF_YEAR, 1)
            } else if (timeInMillis <= nowMs) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val strippedTime = trimmed
            .removeRange(timeMatch.range)
            .replace(Regex("""\b(every day|everyday|daily|every morning|every evening|every night|tomorrow|today)\b""", RegexOption.IGNORE_CASE), "")
        val cleanedTask = cleanupTaskPhrase(strippedTime)
        if (cleanedTask.isBlank()) return null

        return ParsedScheduledTaskSpec(
            title = buildConciseTitle(cleanedTask),
            commandPrompt = cleanedTask,
            triggerAtMs = cal.timeInMillis,
            repeatDaily = repeatDaily
        )
    }

    fun parseAiScheduleTag(
        whenSpec: String,
        repeatArg: String,
        titleArg: String,
        commandArg: String,
        nowMs: Long = System.currentTimeMillis()
    ): ParsedScheduledTaskSpec? {
        val command = commandArg.trim().ifBlank { titleArg.trim() }
        if (command.isBlank()) return null
        val repeatDaily = repeatArg.trim().equals("true", ignoreCase = true) ||
            repeatArg.trim().equals("daily", ignoreCase = true)
        val whenClean = whenSpec.trim().lowercase(Locale.US)

        val triggerAtMs = when {
            whenClean.toLongOrNull() != null && whenClean.toLong() > 1_000_000_000_000L -> {
                whenClean.toLong()
            }
            Regex("""^\d+\s*m$""").matches(whenClean) -> {
                val mins = whenClean.removeSuffix("m").trim().toLongOrNull()?.coerceAtLeast(1L) ?: 10L
                nowMs + mins * 60_000L
            }
            Regex("""^\d+\s*h$""").matches(whenClean) -> {
                val hrs = whenClean.removeSuffix("h").trim().toLongOrNull()?.coerceAtLeast(1L) ?: 1L
                nowMs + hrs * 3_600_000L
            }
            Regex("""^\d{1,2}:\d{2}$""").matches(whenClean) -> {
                val parts = whenClean.split(":")
                val h = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: 8
                val m = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: 0
                Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
            }
            else -> nowMs + 15 * 60_000L
        }

        val title = titleArg.trim().ifBlank { buildConciseTitle(command) }
        return ParsedScheduledTaskSpec(
            title = title,
            commandPrompt = command,
            triggerAtMs = triggerAtMs,
            repeatDaily = repeatDaily
        )
    }

    private fun cleanupTaskPhrase(raw: String): String {
        var text = raw.trim()
        val prefixes = listOf(
            "jarvis ",
            "hey jarvis ",
            "please ",
            "schedule an automation to ",
            "schedule automation to ",
            "schedule a task to ",
            "schedule task to ",
            "schedule to ",
            "schedule ",
            "automate ",
            "remind me to ",
            "remind me ",
            "make sure to ",
            "can you ",
            "you should ",
            "i want you to ",
            "task:",
            "to "
        )
        var changed = true
        while (changed) {
            changed = false
            val low = text.lowercase(Locale.US)
            for (prefix in prefixes) {
                if (low.startsWith(prefix)) {
                    text = text.drop(prefix.length).trimStart(',', ':', '-', ' ')
                    changed = true
                    break
                }
            }
        }
        return text.trim().trimEnd(',', '.', ';')
    }

    fun buildConciseTitle(commandPrompt: String): String {
        val clean = commandPrompt
            .replace(Regex("""^(open|launch|check|run|give me|do|tell me|read)\s+my\s+""", RegexOption.IGNORE_CASE), "$1 ")
            .trim()
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        val joined = words.take(5).joinToString(" ")
        val truncated = if (joined.length > 34) joined.take(31).trimEnd() + "..." else joined
        return truncated.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
        }
    }

    fun formatScheduleTime(triggerAtMs: Long): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(triggerAtMs))
    }

    fun formatCountdown(triggerAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffMs = triggerAtMs - nowMs
        if (diffMs <= 60_000L) return "Due now"
        val totalMinutes = (diffMs / 60_000L).toInt()
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return when {
            hours >= 24 -> "${hours / 24}d ${hours % 24}h"
            hours > 0 && mins > 0 -> "In ${hours}h ${mins}m"
            hours > 0 -> "In ${hours}h"
            else -> "In ${mins}m"
        }
    }
}
