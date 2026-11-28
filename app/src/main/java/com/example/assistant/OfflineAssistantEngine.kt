package com.example.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.local.DeviceAppKnowledgeEntity
import com.example.data.local.LearnedWorkflowEntity
import com.example.data.local.MemoryFactEntity
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Genuine on-device offline intelligence engine used when internet connectivity is absent or
 * NVIDIA NIM cloud cannot be reached. Answers strictly from real device data, local Room
 * memory & learned workflows, active screen summaries, and deterministic math/utilities.
 */
object OfflineAssistantEngine {

    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun canHandleLocally(
        prompt: String,
        memories: List<MemoryFactEntity>,
        screenSummary: String?
    ): Boolean {
        val lower = prompt.trim().lowercase(Locale.US)
        if (lower.isBlank()) return false
        if (isGreetingOrIdentity(lower)) return true
        if (tryEvaluateMath(lower) != null) return true
        if (isMemoryQuery(lower)) return true
        if (isLearnedAppsQuery(lower)) return true
        if (isScreenQuery(lower) && !screenSummary.isNullOrBlank()) return true
        if (findMemoryKeywordHit(lower, memories) != null) return true
        if (isTimeOrDateQuery(lower) || isDeviceStatusQuery(lower)) return true
        return false
    }

    fun answerOffline(
        context: Context,
        prompt: String,
        memories: List<MemoryFactEntity>,
        learnedApps: List<DeviceAppKnowledgeEntity>,
        learnedWorkflows: List<LearnedWorkflowEntity>,
        screenSummary: String?
    ): Pair<String, String> {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase(Locale.US)

        // 1. Conversational greetings / identity / readiness
        if (isGreetingOrIdentity(lower)) {
            return when {
                lower.contains("how are you") || lower.contains("how's it going") || lower.contains("hows it going") ->
                    "Running smoothly on your device, Sir. All system controls, learned workflows, and saved memories remain ready." to "Local Assistant"
                lower.contains("who are you") ->
                    "I am J.A.R.V.I.S., your personal device and screen assistant, Sir." to "Local Assistant"
                lower.contains("thank") ->
                    "Always a pleasure, Sir." to "Local Assistant"
                lower.contains("what can you do") || lower.contains("how can you help") ->
                    "I can launch apps, tap screen controls, read notifications, manage timers and automations, recall your saved memories, and reason online via NVIDIA NIM, Sir." to "Local Assistant"
                else ->
                    "At your service, Sir. I can launch apps, tap screen controls, check device status, recall your saved memories, or handle tasks for you." to "Local Assistant"
            }
        }

        // 2. Deterministic arithmetic evaluator
        tryEvaluateMath(lower)?.let { result ->
            return "$result, Sir." to "Offline Calculator"
        }

        // 3. Explicit memory recall ("what do you remember", "what did I tell you about ...")
        if (isMemoryQuery(lower)) {
            if (memories.isEmpty()) {
                return "You don't have any saved memories stored locally yet, Sir." to "Offline Memory"
            }
            val queryTokens = lower.split(Regex("\\s+"))
                .filter { it.length >= 3 && it !in stopWords }
            val matching = if (queryTokens.isEmpty()) {
                memories.take(4)
            } else {
                memories.filter { fact ->
                    val s = fact.summary.lowercase(Locale.US)
                    queryTokens.any { s.contains(it) }
                }.ifEmpty { memories.take(4) }
            }
            val summary = matching.take(4).joinToString(" • ") { it.summary }
            return "From your local offline memory vault, Sir: $summary." to "Offline Memory"
        }

        // 4. Learned apps & workflows recall
        if (isLearnedAppsQuery(lower)) {
            val topApps = learnedApps.sortedByDescending { it.usageCount }.take(5).joinToString(", ") { it.appName }
            val cats = learnedApps.groupingBy { it.category }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(5)
                .joinToString(", ") { "${it.key} (${it.value})" }
            val wfCount = learnedWorkflows.size
            val msg = buildString {
                append("Operating locally, Sir. I have indexed ${learnedApps.size} apps and $wfCount workflows across your device")
                if (cats.isNotBlank()) append(" ($cats)")
                append(".")
                if (topApps.isNotBlank()) append(" Top apps: $topApps.")
            }
            return msg to "Offline Device Map"
        }

        // 5. Screen summary inspection offline
        if (isScreenQuery(lower) && !screenSummary.isNullOrBlank()) {
            return screenSummary to "Offline Screen Read"
        }

        // 6. Fallback local memory keyword match before reporting offline cloud boundary
        findMemoryKeywordHit(lower, memories)?.let { hit ->
            return "From your offline memory, Sir: ${hit.summary}." to "Offline Memory"
        }

        // 7. Time / Device status quick check
        if (isTimeOrDateQuery(lower)) {
            val formatted = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
            return "It is currently $formatted, Sir." to "Offline Clock"
        }
        if (isDeviceStatusQuery(lower)) {
            return JarvisSystemAssistant.buildSystemTelemetryReport(context) to "Offline Telemetry"
        }

        return "You're currently offline, Sir, so cloud reasoning (NVIDIA NIM) isn't reachable for \"$trimmed\". Device controls, screen tapping, sleep/unlock, saved memories, and calculations still work offline." to "Offline Mode"
    }

    private fun isGreetingOrIdentity(lower: String): Boolean {
        val clean = lower.replace(Regex("[!?.,:;]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean in exactGreetings) return true
        val words = clean.split(" ").filter { it.isNotBlank() }
        if (words.size <= 4 && Regex("^(hi|hello|hey|yo|good morning|good afternoon|good evening|good night)\\b").containsMatchIn(clean)) {
            return true
        }
        return false
    }

    private fun isMemoryQuery(lower: String): Boolean =
        lower.contains("remember") || lower.contains("memory") || lower.contains("memories") ||
            lower.contains("what did i") || lower.contains("saved about")

    private fun isLearnedAppsQuery(lower: String): Boolean =
        lower.contains("learned") || lower.contains("which apps") || lower.contains("what apps") || lower.contains("workflows")

    private fun isScreenQuery(lower: String): Boolean =
        lower.contains("screen") || lower.contains("page") || lower.contains("visible") || lower.contains("what is here")

    private fun isTimeOrDateQuery(lower: String): Boolean =
        lower.contains("time") || lower.contains("date")

    private fun isDeviceStatusQuery(lower: String): Boolean =
        lower.contains("battery") || lower.contains("status") || lower.contains("network") ||
            lower.contains("offline") || lower.contains("online")

    private fun findMemoryKeywordHit(lower: String, memories: List<MemoryFactEntity>): MemoryFactEntity? {
        val promptKeywords = lower.split(Regex("\\s+")).filter { it.length >= 4 && it !in stopWords }
        if (promptKeywords.isEmpty()) return null
        return memories.firstOrNull { fact ->
            val s = fact.summary.lowercase(Locale.US)
            promptKeywords.any { s.contains(it) }
        }
    }

    private val exactGreetings = setOf(
        "hi", "hello", "hey", "yo", "sup",
        "good morning", "good afternoon", "good evening", "good night",
        "how are you", "who are you", "what's up", "whats up",
        "how is it going", "hows it going", "are you there", "are you listening",
        "what can you do", "how can you help", "thank you", "thanks", "test"
    )

    internal fun tryEvaluateMath(lower: String): String? {
        val cleaned = lower
            .replace("what is", "")
            .replace("calculate", "")
            .replace("compute", "")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("times", "*")
            .replace("multiplied by", "*")
            .replace("divided by", "/")
            .replace("x", "*")
            .trim()

        // Percentage form: "15% of 240"
        val pctMatch = Regex("""(-?\d+(?:\.\d+)?)\s*%\s*of\s*(-?\d+(?:\.\d+)?)""").find(cleaned)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toDoubleOrNull() ?: return null
            val base = pctMatch.groupValues[2].toDoubleOrNull() ?: return null
            return "${pctMatch.groupValues[1]}% of ${pctMatch.groupValues[2]} is ${formatNumber((pct / 100.0) * base)}"
        }

        val binaryMatch = Regex("""(-?\d+(?:\.\d+)?)\s*([+\-*/])\s*(-?\d+(?:\.\d+)?)""").find(cleaned) ?: return null
        val left = binaryMatch.groupValues[1].toDoubleOrNull() ?: return null
        val op = binaryMatch.groupValues[2]
        val right = binaryMatch.groupValues[3].toDoubleOrNull() ?: return null
        val res = when (op) {
            "+" -> left + right
            "-" -> left - right
            "*" -> left * right
            "/" -> if (abs(right) < 1e-12) return "Division by zero is undefined" else left / right
            else -> return null
        }
        return "${formatNumber(left)} $op ${formatNumber(right)} = ${formatNumber(res)}"
    }

    private fun formatNumber(value: Double): String {
        return if (abs(value - value.toLong().toDouble()) < 1e-9) {
            value.toLong().toString()
        } else {
            DecimalFormat("#.####").format(value)
        }
    }

    private val stopWords = setOf(
        "what", "when", "where", "which", "about", "remember", "memory", "memories",
        "jarvis", "please", "could", "would", "should", "tell", "have", "that", "this", "with", "from"
    )
}
