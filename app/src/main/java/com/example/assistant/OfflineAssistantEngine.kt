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

        // 1. Conversational greetings / identity
        if (lower in setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening", "how are you", "who are you")) {
            return when {
                lower.contains("how are you") ->
                    "Running smoothly offline on your device, Sir. All system controls, learned workflows, and saved memories remain ready." to "Offline Mode"
                lower.contains("who are you") ->
                    "I am J.A.R.V.I.S., currently operating in offline on-device mode, Sir." to "Offline Mode"
                else ->
                    "At your service offline, Sir. I can launch apps, tap screen controls, check device status, recall your saved memories, or calculate offline." to "Offline Mode"
            }
        }

        // 2. Deterministic arithmetic evaluator
        tryEvaluateMath(lower)?.let { result ->
            return "$result, Sir." to "Offline Calculator"
        }

        // 3. Explicit memory recall ("what do you remember", "what did I tell you about ...")
        if (lower.contains("remember") || lower.contains("memory") || lower.contains("memories") ||
            lower.contains("what did i") || lower.contains("saved about")
        ) {
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
        if (lower.contains("learned") || lower.contains("which apps") || lower.contains("what apps") || lower.contains("workflows")) {
            val topApps = learnedApps.sortedByDescending { it.usageCount }.take(5).joinToString(", ") { it.appName }
            val wfCount = learnedWorkflows.size
            val msg = buildString {
                append("Operating offline, Sir. I have indexed ${learnedApps.size} apps and $wfCount workflows locally.")
                if (topApps.isNotBlank()) append(" Top apps: $topApps.")
            }
            return msg to "Offline Device Map"
        }

        // 5. Screen summary inspection offline
        if ((lower.contains("screen") || lower.contains("page") || lower.contains("visible") || lower.contains("what is here")) &&
            !screenSummary.isNullOrBlank()
        ) {
            return screenSummary to "Offline Screen Read"
        }

        // 6. Fallback local memory keyword match before reporting offline cloud boundary
        val promptKeywords = lower.split(Regex("\\s+")).filter { it.length >= 4 && it !in stopWords }
        if (promptKeywords.isNotEmpty()) {
            val hit = memories.firstOrNull { fact ->
                val s = fact.summary.lowercase(Locale.US)
                promptKeywords.any { s.contains(it) }
            }
            if (hit != null) {
                return "From your offline memory, Sir: ${hit.summary}." to "Offline Memory"
            }
        }

        // 7. Time / Device status quick check
        if (lower.contains("time") || lower.contains("date")) {
            val formatted = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
            return "It is currently $formatted, Sir (offline mode)." to "Offline Clock"
        }
        if (lower.contains("battery") || lower.contains("status") || lower.contains("network") || lower.contains("offline") || lower.contains("online")) {
            return JarvisSystemAssistant.buildSystemTelemetryReport(context) to "Offline Telemetry"
        }

        return "You're currently offline, Sir, so cloud reasoning (NVIDIA NIM) isn't reachable for \"$trimmed\". Device controls, screen tapping, sleep/unlock, saved memories, and calculations still work offline." to "Offline Mode"
    }

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
