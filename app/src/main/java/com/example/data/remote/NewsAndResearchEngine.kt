package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class NewsBriefingResult(
    val topic: String,
    val headlinesSummary: String,
    val sources: List<String>,
    val webContextForAi: String
)

/**
 * Live News, Kenyan Headlines, Weather, Traffic & Market Research Engine.
 * Fetches verified headlines from Citizen Digital, Daily Nation, Standard, BBC, Reuters,
 * and live web search without fabricating facts.
 */
class NewsAndResearchEngine(private val webClient: WebKnowledgeClient) {

    suspend fun fetchBriefing(rawQuery: String): NewsBriefingResult = withContext(Dispatchers.IO) {
        val lower = rawQuery.lowercase(Locale.US)
        val searchQuery = buildFocusedQuery(rawQuery, lower)
        val result = webClient.searchInternet(searchQuery)

        val sources = result.items
            .mapNotNull { item ->
                val host = runCatching { java.net.URI(item.url).host?.removePrefix("www.") }.getOrNull()
                host?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(4)

        val isMarketQuery = lower.contains("market") || lower.contains("forex") ||
            lower.contains("gold") || lower.contains("btc") || lower.contains("bitcoin") ||
            lower.contains("stock") || lower.contains("trading") || lower.contains("crypto")

        val summary = if (!result.hasUsefulInfo) {
            "I couldn't reach live news sources for \"$rawQuery\" right now, Sir. Check your network connection or ask me to open a news site in Chrome."
        } else {
            buildString {
                result.items.take(3).forEachIndexed { idx, item ->
                    if (idx > 0) append(" ")
                    append("${item.title}: ${item.snippet.take(150)}")
                }
                if (sources.isNotEmpty()) {
                    append(" Sources: ${sources.joinToString(", ")}.")
                }
                if (isMarketQuery) {
                    append(" Note: Market conditions carry risk — tell me if you want to inspect charts or approve any action.")
                }
            }.trim()
        }

        NewsBriefingResult(
            topic = searchQuery,
            headlinesSummary = summary,
            sources = sources,
            webContextForAi = result.formatForPrompt(maxItems = 5)
        )
    }

    internal fun buildFocusedQuery(rawQuery: String, lower: String): String {
        return when {
            lower.contains("kenya") || lower.contains("nairobi") || lower.contains("mombasa") ||
                lower.contains("kisumu") || lower.contains("ruto") || lower.contains("shilling") -> {
                when {
                    lower.contains("weather") -> "Kenya Nairobi weather forecast today"
                    lower.contains("traffic") -> "Nairobi traffic update today"
                    else -> "Kenya latest news today Nation Africa Citizen Digital Standard"
                }
            }
            lower.contains("weather") -> rawQuery.trim()
            lower.contains("traffic") -> rawQuery.trim()
            lower.contains("tech news") || lower.contains("technology news") -> "latest technology AI news today"
            lower.contains("business news") || lower.contains("economy") -> "latest business economy market news today"
            lower.contains("sports news") || lower.contains("football") || lower.contains("premier league") -> "latest sports football news today"
            lower.contains("world news") -> "latest breaking world news BBC Reuters today"
            else -> rawQuery.trim()
        }
    }
}
