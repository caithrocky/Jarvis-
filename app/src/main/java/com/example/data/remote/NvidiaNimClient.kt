package com.example.data.remote

import com.example.data.local.ConversationMessageEntity
import com.example.data.local.MemoryFactEntity
import com.example.domain.model.AiModelOption
import com.example.domain.model.BrokerAccountInfo
import com.example.domain.model.JarvisChoiceActivity
import com.example.domain.model.MarketQuote
import com.example.domain.model.NewsItem
import com.example.domain.model.OpenPosition
import com.example.domain.model.TradingOperatingMode
import com.example.domain.model.TradingOpportunity
import com.example.domain.model.formatModeSummary
import com.example.security.SecretsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

sealed class JarvisAiResult {
    data class Success(
        val replyText: String,
        val modelUsed: String,
        val toolCallsExecuted: List<String> = emptyList(),
        val proposedTradeJson: JSONObject? = null,
        val suggestedMemoryFact: String? = null,
        val triggeredEmergencyStop: Boolean = false
    ) : JarvisAiResult()

    data class ConfigurationRequired(val message: String) : JarvisAiResult()
    data class Error(val message: String) : JarvisAiResult()
}

class NvidiaNimClient(private val httpClient: OkHttpClient) {

    data class LiveContextSnapshot(
        val quotes: List<MarketQuote> = emptyList(),
        val opportunities: List<TradingOpportunity> = emptyList(),
        val accounts: List<BrokerAccountInfo> = emptyList(),
        val positions: List<OpenPosition> = emptyList(),
        val recentNews: List<NewsItem> = emptyList(),
        val operatingMode: TradingOperatingMode = TradingOperatingMode.ASSISTANT,
        val emergencyStopActive: Boolean = false,
        val activeModes: Set<TradingOperatingMode> = setOf(operatingMode),
        val primaryActivity: JarvisChoiceActivity = JarvisChoiceActivity.ASSISTANT,
        val activeScreenSummary: String? = null,
        val internetSearchContext: String? = null
    )

    suspend fun testConnection(modelId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val resolvedModel = AiModelOption.normalizeModelId(modelId)
        val key = SecretsConfig.nvidiaNimApiKey
        val supabaseUrl = SecretsConfig.supabaseUrl
        val supabaseKey = SecretsConfig.supabaseAnonKey
        if (key == null && (supabaseUrl == null || supabaseKey == null)) {
            return@withContext false to "NVIDIA_NIM_API_KEY not configured in AI Studio Secrets."
        }
        runCatching {
            val endpoint = resolveEndpoint(key, supabaseUrl, supabaseKey)
            val payload = buildChatPayload(
                modelId = resolvedModel,
                messagesArray = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK.")),
                maxTokens = 64,
                stream = false,
                includeNemotronTemplateKwargs = resolvedModel.contains("nemotron-3.5", ignoreCase = true)
            )
            executeRequest(endpoint, payload, key, supabaseKey, acceptSse = false).use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    true to "Connected to NVIDIA NIM ($resolvedModel)"
                } else if (resp.code in listOf(400, 422) && payload.has("chat_template_kwargs")) {
                    // Retry without chat_template_kwargs in case a proxy rejects custom kwargs
                    val fallbackPayload = buildChatPayload(
                        modelId = resolvedModel,
                        messagesArray = payload.getJSONArray("messages"),
                        maxTokens = 64,
                        stream = false,
                        includeNemotronTemplateKwargs = false
                    )
                    executeRequest(endpoint, fallbackPayload, key, supabaseKey, acceptSse = false).use { retryResp ->
                        val retryBody = retryResp.body?.string().orEmpty()
                        if (retryResp.isSuccessful) {
                            true to "Connected to NVIDIA NIM ($resolvedModel)"
                        } else {
                            false to "NVIDIA NIM HTTP ${retryResp.code}: ${extractErrorMessage(retryBody)}"
                        }
                    }
                } else {
                    false to "NVIDIA NIM HTTP ${resp.code}: ${extractErrorMessage(rawBody)}"
                }
            }
        }.getOrElse {
            false to "Connection failed: ${it.message ?: "Network error"}"
        }
    }

    suspend fun chatWithStreaming(
        userPrompt: String,
        modelId: String,
        history: List<ConversationMessageEntity>,
        memories: List<MemoryFactEntity>,
        snapshot: LiveContextSnapshot,
        onPartialToken: (String) -> Unit
    ): JarvisAiResult = withContext(Dispatchers.IO) {
        val resolvedModel = AiModelOption.normalizeModelId(modelId)
        val nimKey = SecretsConfig.nvidiaNimApiKey
        val supabaseUrl = SecretsConfig.supabaseUrl
        val supabaseKey = SecretsConfig.supabaseAnonKey

        if (nimKey == null && (supabaseUrl == null || supabaseKey == null)) {
            return@withContext JarvisAiResult.ConfigurationRequired(
                "NVIDIA NIM API key is not configured yet. Add NVIDIA_NIM_API_KEY (from build.nvidia.com) in AI Studio Secrets, or configure SUPABASE_URL + SUPABASE_ANON_KEY to proxy requests server-side."
            )
        }

        val endpoint = resolveEndpoint(nimKey, supabaseUrl, supabaseKey)
        val messagesArray = buildMessagesArray(userPrompt, history, memories, snapshot)

        runCatching {
            val useTemplateKwargs = resolvedModel.contains("nemotron-3.5", ignoreCase = true)
            val primaryPayload = buildChatPayload(
                modelId = resolvedModel,
                messagesArray = messagesArray,
                maxTokens = 2048,
                stream = true,
                includeNemotronTemplateKwargs = useTemplateKwargs
            )

            val firstAttempt = executeStreamingOrJsonCall(
                endpoint = endpoint,
                payload = primaryPayload,
                nimKey = nimKey,
                supabaseKey = supabaseKey,
                onPartialToken = onPartialToken
            )

            // Retry cleanly without custom kwargs or non-streaming if needed
            val resolvedText = when {
                firstAttempt.httpCode in listOf(400, 422) && useTemplateKwargs -> {
                    val plainPayload = buildChatPayload(
                        modelId = resolvedModel,
                        messagesArray = messagesArray,
                        maxTokens = 2048,
                        stream = true,
                        includeNemotronTemplateKwargs = false
                    )
                    executeStreamingOrJsonCall(
                        endpoint = endpoint,
                        payload = plainPayload,
                        nimKey = nimKey,
                        supabaseKey = supabaseKey,
                        onPartialToken = onPartialToken
                    )
                }
                firstAttempt.isSuccess && firstAttempt.replyText.isBlank() -> {
                    val nonStreamPayload = buildChatPayload(
                        modelId = resolvedModel,
                        messagesArray = messagesArray,
                        maxTokens = 2048,
                        stream = false,
                        includeNemotronTemplateKwargs = false
                    )
                    executeStreamingOrJsonCall(
                        endpoint = endpoint,
                        payload = nonStreamPayload,
                        nimKey = nimKey,
                        supabaseKey = supabaseKey,
                        onPartialToken = onPartialToken
                    )
                }
                else -> firstAttempt
            }

            if (!resolvedText.isSuccess) {
                return@withContext JarvisAiResult.Error(
                    "NVIDIA NIM error (${resolvedText.httpCode}): ${resolvedText.errorMessage}"
                )
            }

            val finalReply = stripThinkingBlocks(resolvedText.replyText).trim()
            if (finalReply.isEmpty()) {
                return@withContext JarvisAiResult.Error(
                    "NVIDIA NIM returned an empty response for $resolvedModel."
                )
            }

            val extractedMemory = extractPreferenceHint(userPrompt)
            JarvisAiResult.Success(
                replyText = finalReply,
                modelUsed = resolvedModel,
                toolCallsExecuted = detectReferencedTools(userPrompt),
                suggestedMemoryFact = extractedMemory
            )
        }.getOrElse { ex ->
            JarvisAiResult.Error("NVIDIA NIM request failed: ${ex.message ?: "Network timeout"}")
        }
    }

    private data class CallOutcome(
        val isSuccess: Boolean,
        val httpCode: Int,
        val replyText: String = "",
        val errorMessage: String = ""
    )

    private fun executeStreamingOrJsonCall(
        endpoint: String,
        payload: JSONObject,
        nimKey: String?,
        supabaseKey: String?,
        onPartialToken: (String) -> Unit
    ): CallOutcome {
        val wantStream = payload.optBoolean("stream", false)
        executeRequest(endpoint, payload, nimKey, supabaseKey, acceptSse = wantStream).use { resp ->
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                return CallOutcome(
                    isSuccess = false,
                    httpCode = resp.code,
                    errorMessage = extractErrorMessage(errBody, "HTTP ${resp.code}")
                )
            }

            val bodyStream = resp.body?.byteStream()
                ?: return CallOutcome(isSuccess = false, httpCode = resp.code, errorMessage = "Empty response stream")

            // Handle plain JSON responses even when streaming was requested (e.g., edge proxies)
            if (!wantStream || (contentType.contains("application/json") && !contentType.contains("text/event-stream"))) {
                val jsonText = bodyStream.bufferedReader().use { it.readText() }
                val extracted = extractContentFromNonStreamJson(jsonText)
                val visible = stripThinkingBlocks(extracted)
                if (visible.isNotBlank()) onPartialToken(visible)
                return CallOutcome(isSuccess = true, httpCode = resp.code, replyText = extracted)
            }

            val fullText = StringBuilder()
            val rawNonSseFallback = StringBuilder()
            var sawSseData = false

            BufferedReader(InputStreamReader(bodyStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.startsWith("data:")) {
                        sawSseData = true
                        val data = trimmedLine.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val token = extractTokenFromSseChunk(data)
                        if (!token.isNullOrEmpty()) {
                            fullText.append(token)
                            val visible = stripThinkingBlocks(fullText.toString())
                            if (visible.isNotBlank()) {
                                onPartialToken(visible)
                            }
                        }
                    } else if (trimmedLine.isNotEmpty() && !sawSseData) {
                        rawNonSseFallback.appendLine(trimmedLine)
                    }
                    line = reader.readLine()
                }
            }

            val finalRaw = if (fullText.isNotEmpty()) {
                fullText.toString()
            } else if (!sawSseData && rawNonSseFallback.isNotEmpty()) {
                extractContentFromNonStreamJson(rawNonSseFallback.toString()).also { parsed ->
                    val visible = stripThinkingBlocks(parsed)
                    if (visible.isNotBlank()) onPartialToken(visible)
                }
            } else {
                ""
            }

            return CallOutcome(isSuccess = true, httpCode = resp.code, replyText = finalRaw)
        }
    }

    private fun resolveEndpoint(nimKey: String?, supabaseUrl: String?, supabaseKey: String?): String {
        return if (nimKey == null && supabaseUrl != null && supabaseKey != null) {
            "$supabaseUrl/functions/v1/jarvis-chat"
        } else {
            "https://integrate.api.nvidia.com/v1/chat/completions"
        }
    }

    private fun buildChatPayload(
        modelId: String,
        messagesArray: JSONArray,
        maxTokens: Int,
        stream: Boolean,
        includeNemotronTemplateKwargs: Boolean
    ): JSONObject {
        return JSONObject().apply {
            put("model", modelId)
            put("messages", messagesArray)
            put("temperature", 0.35)
            put("top_p", 0.9)
            put("max_tokens", maxTokens)
            put("stream", stream)
            if (includeNemotronTemplateKwargs) {
                put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            }
        }
    }

    private fun executeRequest(
        endpoint: String,
        payload: JSONObject,
        nimKey: String?,
        supabaseKey: String?,
        acceptSse: Boolean
    ): okhttp3.Response {
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", if (acceptSse) "text/event-stream" else "application/json")

        if (nimKey != null) {
            reqBuilder.header("Authorization", "Bearer $nimKey")
        } else if (supabaseKey != null) {
            reqBuilder.header("apikey", supabaseKey)
            reqBuilder.header("Authorization", "Bearer $supabaseKey")
        }
        return httpClient.newCall(reqBuilder.build()).execute()
    }

    internal fun extractTokenFromSseChunk(dataJson: String): String? {
        return runCatching {
            val chunk = JSONObject(dataJson)
            val choices = chunk.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")
            val msg = choice.optJSONObject("message")
            delta?.optNonNullString("content")
                ?: msg?.optNonNullString("content")
        }.getOrNull()
    }

    internal fun extractContentFromNonStreamJson(jsonText: String): String {
        return runCatching {
            val root = JSONObject(jsonText)
            val choices = root.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val choice = choices.getJSONObject(0)
            val msg = choice.optJSONObject("message")
            val delta = choice.optJSONObject("delta")
            msg?.optNonNullString("content")
                ?: delta?.optNonNullString("content")
                ?: choice.optNonNullString("text")
                ?: ""
        }.getOrDefault("")
    }

    internal fun stripThinkingBlocks(raw: String): String {
        return raw
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)<think>.*$"), "")
            .trim()
    }

    private fun JSONObject.optNonNullString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "")
        return value.takeIf { it.isNotEmpty() }
    }

    private fun extractErrorMessage(rawBody: String, fallback: String = "Request rejected"): String {
        if (rawBody.isBlank()) return fallback
        return runCatching {
            val obj = JSONObject(rawBody)
            val errObj = obj.optJSONObject("error")
            errObj?.optNonNullString("message")
                ?: obj.optNonNullString("detail")
                ?: obj.optNonNullString("message")
                ?: rawBody.take(240)
        }.getOrElse { rawBody.take(240) }
    }

    internal fun buildMessagesArray(
        userPrompt: String,
        history: List<ConversationMessageEntity>,
        memories: List<MemoryFactEntity>,
        snapshot: LiveContextSnapshot
    ): JSONArray {
        val systemPrompt = buildString {
            appendLine("You are JARVIS, a warm, razor-sharp, composed personal & system AI assistant with an on-screen floating plugin for your owner.")
            appendLine("Persona rules: Always address your owner respectfully and naturally as \"Sir\" (e.g., \"Certainly, Sir.\", \"Right away, Sir.\", \"Allow me to guide you through that, Sir.\"). Keep explanations friendly, clear, and conversational—tailored for natural spoken playback. Avoid markdown clutter such as asterisks or huge tables.")
            appendLine("Core capabilities:")
            appendLine("- System & App Control: Open any app on the phone, open any website in Google Chrome (if the URL is hard to find, politely ask Sir to share the exact link), type inside apps, tap buttons, scroll, manage device & app settings, set alarms/timers.")
            appendLine("- Assisted Sign-Up, Sign-In & Verification Guidance: When Sir asks to sign up or sign in, you can open the app/site in Chrome, fill saved vault credentials with permission, and guide Sir step by step (including ID card/passport/selfie photo uploads, OTP verification codes, and CAPTCHAs—always pausing before sensitive submissions or ID capture).")
            appendLine("- Online Research & Open-App Market Analysis: You have no built-in broker accounts; instead, if Sir asks for live information you don't know (general knowledge, news, tutorials, or financial markets/trading ideas), rely on the Live Internet Search Results below and/or analyze whatever trading app or website is visible in Active Screen Context to suggest the best opportunity or next step.")
            appendLine("Android System & Screen Action Tags: Whenever Sir asks to perform device, app, browser, login, or screen actions, include ALL matching tags at the very end of your reply (the app strips and executes them in order, e.g. [ACTION:OPEN_APP|Chrome] [ACTION:SIGNIN_ASSIST|autofill]):")
            appendLine("  [ACTION:OPEN_CHROME|siteOrUrl], [ACTION:OPEN_APP|AppName], [ACTION:APP_SETTINGS|AppName], [ACTION:PLATFORM_SEARCH|youtube/reddit/google/x/tiktok/github/wikipedia/amazon/tradingview|query], [ACTION:WEB_LOOKUP|query], [ACTION:TYPE_TEXT|text to type|optional field hint], [ACTION:TAP_TEXT|button label], [ACTION:SCROLL|down|up], [ACTION:GLOBAL_NAV|back|home|recents|notifications|quick_settings|lock_screen|screenshot], [ACTION:READ_SCREEN], [ACTION:GUIDE_SCREEN], [ACTION:SIGNIN_ASSIST|siteOrAutofill], [ACTION:SIGNUP_ASSIST|siteOrAutofill], [ACTION:FLOATING_ORB|open|close], [ACTION:SET_ALARM|HH:MM|Label], [ACTION:SET_TIMER|seconds|Label], [ACTION:WEB_SEARCH|query], [ACTION:MAPS|destination], [ACTION:CALL|phoneNumber], [ACTION:OPEN_SETTINGS|wifi|bluetooth|display|sound|battery|location|security|privacy|accessibility|overlay|storage|apps|notifications|assistant|general], [ACTION:CAMERA|photo]")
            appendLine("Strict truth rules: NEVER claim you already opened an app/website or filled credentials unless you included the corresponding [ACTION:...] tag(s). NEVER invent live prices, URLs, or facts when unverified. If Live Internet Search Results don't answer something, say so honestly or ask Sir for the exact website link.")
            appendLine()
            snapshot.activeScreenSummary?.takeIf { it.isNotBlank() }?.let { screenInfo ->
                appendLine("Active Screen Context (currently visible on Sir's phone):")
                appendLine(screenInfo)
                appendLine()
            }
            snapshot.internetSearchContext?.takeIf { it.isNotBlank() }?.let { webInfo ->
                appendLine("Live Internet Search Results (use these up-to-date facts):")
                appendLine(webInfo)
                appendLine()
            }
            if (memories.isNotEmpty()) {
                appendLine("Persistent User Memories:")
                memories.take(10).forEach { appendLine("- [${it.category}] ${it.summary}") }
                appendLine()
            }
        }

        // Normalize history: avoid duplicate trailing userPrompt (already inserted into Room before query)
        // and guarantee strict user/assistant alternation required by NIM chat templates.
        val trimmedPrompt = userPrompt.trim()
        val rawHistory = history
            .takeLast(14)
            .filter { it.content.isNotBlank() }
            .let { list ->
                if (list.isNotEmpty() && list.last().role == "user" && list.last().content.trim() == trimmedPrompt) {
                    list.dropLast(1)
                } else {
                    list
                }
            }

        val normalizedTurns = mutableListOf<Pair<String, String>>()
        for (msg in rawHistory) {
            val role = if (msg.role == "user") "user" else "assistant"
            val content = msg.content.trim()
            if (normalizedTurns.isEmpty() && role == "assistant") {
                continue // First conversational turn after system must be user
            }
            if (normalizedTurns.isNotEmpty() && normalizedTurns.last().first == role) {
                val prev = normalizedTurns.removeAt(normalizedTurns.lastIndex)
                normalizedTurns.add(role to "${prev.second}\n\n$content")
            } else {
                normalizedTurns.add(role to content)
            }
        }

        // Ensure final message appended is a distinct user turn
        if (normalizedTurns.isNotEmpty() && normalizedTurns.last().first == "user") {
            val prev = normalizedTurns.removeAt(normalizedTurns.lastIndex)
            normalizedTurns.add("user" to "${prev.second}\n\n$trimmedPrompt")
        } else {
            normalizedTurns.add("user" to trimmedPrompt)
        }

        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
        normalizedTurns.forEach { (role, content) ->
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        return arr
    }

    private fun extractPreferenceHint(prompt: String): String? {
        val lower = prompt.lowercase()
        return when {
            lower.contains("i prefer") || lower.contains("my favorite") || lower.contains("always risk") || lower.contains("remember that") ->
                prompt.take(140)
            else -> null
        }
    }

    private fun detectReferencedTools(prompt: String): List<String> {
        val lower = prompt.lowercase()
        val tools = mutableListOf<String>()
        if (lower.contains("chrome") || lower.contains("website") || lower.contains("http")) tools.add("chrome_browser")
        if (lower.contains("search") || lower.contains("internet") || lower.contains("online") || lower.contains("market") || lower.contains("news") || lower.contains("price")) tools.add("internet_search")
        if (lower.contains("screen") || lower.contains("guide") || lower.contains("sign") || lower.contains("login")) tools.add("screen_plugin")
        return tools
    }
}
