package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

data class WebSearchSnippet(
    val title: String,
    val snippet: String,
    val url: String
)

data class WebSearchResult(
    val query: String,
    val items: List<WebSearchSnippet>,
    val officialUrl: String? = null,
    val sourceNote: String = ""
) {
    val hasUsefulInfo: Boolean get() = items.isNotEmpty() || !officialUrl.isNullOrBlank()

    fun formatForPrompt(maxItems: Int = 5): String {
        if (!hasUsefulInfo) return ""
        return buildString {
            officialUrl?.let { appendLine("Detected Official Link: $it") }
            items.take(maxItems).forEachIndexed { idx, item ->
                appendLine("${idx + 1}. ${item.title}: ${item.snippet} (${item.url})")
            }
        }.trim()
    }

    fun formatForUserReply(): String {
        if (!hasUsefulInfo) return ""
        val first = items.firstOrNull()
        return buildString {
            if (first != null) {
                append(first.snippet.take(360))
                if (first.url.isNotBlank()) {
                    append(" (Source: ${first.title})")
                }
            } else if (!officialUrl.isNullOrBlank()) {
                append("Found link: $officialUrl")
            }
        }.trim()
    }
}

class WebKnowledgeClient(private val httpClient: OkHttpClient) {

    suspend fun searchInternet(rawQuery: String): WebSearchResult = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return@withContext WebSearchResult(query = "", items = emptyList())
        }

        val snippets = mutableListOf<WebSearchSnippet>()
        var officialUrl: String? = null

        // 1. DuckDuckGo Instant Answer API (fast structured summaries & official sites)
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val abstractText = json.optString("AbstractText").trim()
                    val abstractUrl = sanitizePublicHttpUrl(json.optString("AbstractURL"))
                    val heading = json.optString("Heading").trim().ifBlank { query }
                    if (abstractText.isNotBlank() && abstractUrl != null) {
                        snippets.add(WebSearchSnippet(heading, abstractText, abstractUrl))
                    }

                    val results = json.optJSONArray("Results")
                    if (results != null) {
                        for (i in 0 until minOf(results.length(), 3)) {
                            val obj = results.optJSONObject(i) ?: continue
                            val firstUrl = sanitizePublicHttpUrl(obj.optString("FirstURL"))
                            val text = stripHtml(obj.optString("Text"))
                            if (firstUrl != null) {
                                if (officialUrl == null) officialUrl = firstUrl
                                if (text.isNotBlank()) {
                                    snippets.add(WebSearchSnippet(text.take(60), text, firstUrl))
                                }
                            }
                        }
                    }

                    val related = json.optJSONArray("RelatedTopics")
                    if (related != null) {
                        for (i in 0 until minOf(related.length(), 4)) {
                            val topic = related.optJSONObject(i) ?: continue
                            val topicText = stripHtml(topic.optString("Text"))
                            val topicUrl = sanitizePublicHttpUrl(topic.optString("FirstURL"))
                            if (topicText.isNotBlank() && topicUrl != null) {
                                snippets.add(WebSearchSnippet(topicText.substringBefore(" - ").take(64), topicText, topicUrl))
                            }
                        }
                    }
                }
            }
        }

        // 2. DuckDuckGo HTML Search fallback for current events, websites, live topics
        if (snippets.size < 3 || officialUrl == null) {
            runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val req = Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=$encoded")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = resp.body?.string().orEmpty()
                        val parsed = parseDuckDuckGoHtml(html)
                        for (item in parsed) {
                            if (officialUrl == null && isLikelyHomepage(item.url, query)) {
                                officialUrl = item.url
                            }
                            if (snippets.none { it.url == item.url }) {
                                snippets.add(item)
                            }
                            if (snippets.size >= 6) break
                        }
                        if (officialUrl == null) {
                            officialUrl = parsed.firstOrNull()?.url
                        }
                    }
                }
            }
        }

        // 3. Wikipedia Search API for encyclopedic & platform facts
        if (snippets.size < 2) {
            runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val req = Request.Builder()
                    .url("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=3&utf8=&format=json")
                    .header("User-Agent", USER_AGENT)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val searchArr = json.optJSONObject("query")?.optJSONArray("search")
                        if (searchArr != null) {
                            for (i in 0 until searchArr.length()) {
                                val item = searchArr.optJSONObject(i) ?: continue
                                val title = item.optString("title").trim()
                                val snippet = stripHtml(item.optString("snippet"))
                                if (title.isNotBlank() && snippet.isNotBlank()) {
                                    val wikiUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(" ", "_"), "UTF-8")}"
                                    snippets.add(WebSearchSnippet(title, snippet, wikiUrl))
                                }
                            }
                        }
                    }
                }
            }
        }

        WebSearchResult(
            query = query,
            items = snippets.distinctBy { it.url }.take(6),
            officialUrl = officialUrl,
            sourceNote = if (snippets.isNotEmpty()) "Live Web Search" else "No results returned"
        )
    }

    suspend fun findWebsiteUrl(siteName: String): String? {
        val clean = siteName.trim()
        if (clean.isBlank()) return null
        val result = searchInternet("$clean official website")
        return sanitizePublicHttpUrl(result.officialUrl)
            ?: result.items.firstOrNull { isLikelyHomepage(it.url, clean) }?.url
            ?: result.items.firstOrNull()?.url?.takeIf { !it.contains("wikipedia.org") }
    }

    internal fun parseDuckDuckGoHtml(html: String): List<WebSearchSnippet> {
        if (html.isBlank()) return emptyList()
        val linkRegex = Regex(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRegex = Regex(
            "<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>|<div[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</div>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val links = linkRegex.findAll(html).toList()
        val texts = snippetRegex.findAll(html).map { m ->
            stripHtml(m.groupValues[1].ifBlank { m.groupValues[2] })
        }.toList()

        val items = mutableListOf<WebSearchSnippet>()
        links.forEachIndexed { idx, match ->
            val rawHref = unescapeHtml(match.groupValues[1])
            val resolvedUrl = unwrapDuckDuckGoRedirect(rawHref)
            val safeUrl = sanitizePublicHttpUrl(resolvedUrl)
            val title = stripHtml(match.groupValues[2])
            val snippet = texts.getOrElse(idx) { title }
            if (safeUrl != null && title.isNotBlank() && !safeUrl.contains("duckduckgo.com")) {
                items.add(WebSearchSnippet(title = title, snippet = snippet.ifBlank { title }, url = safeUrl))
            }
        }
        return items
    }

    private fun unwrapDuckDuckGoRedirect(rawHref: String): String {
        val normalized = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
        if (!normalized.contains("uddg=")) return normalized
        return runCatching {
            val uri = URI(normalized)
            val query = uri.rawQuery.orEmpty()
            val uddgParam = query.split("&").firstOrNull { it.startsWith("uddg=") }
                ?.removePrefix("uddg=")
            if (!uddgParam.isNullOrBlank()) {
                java.net.URLDecoder.decode(uddgParam, "UTF-8")
            } else normalized
        }.getOrDefault(normalized)
    }

    private fun isLikelyHomepage(url: String, siteQuery: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        val compact = siteQuery.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (compact.length < 3) return false
        return host.contains(compact) && !host.contains("wikipedia.org") && !host.contains("reddit.com")
    }

    private fun stripHtml(raw: String): String {
        return unescapeHtml(raw.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }

    companion object {
        private const val USER_AGENT = "JARVIS-AI-Assistant/1.0 (Android)"

        fun sanitizePublicHttpUrl(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            val withScheme = when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                else -> return null
            }
            return runCatching {
                val uri = URI(withScheme)
                val scheme = uri.scheme?.lowercase() ?: return null
                if (scheme != "https" && scheme != "http") return null
                val host = uri.host?.lowercase()?.trim() ?: return null
                if (host.isBlank() ||
                    host == "localhost" ||
                    host.endsWith(".local") ||
                    host.startsWith("127.") ||
                    host.startsWith("10.") ||
                    host.startsWith("192.168.") ||
                    host.startsWith("169.254.") ||
                    host == "::1" ||
                    host == "[::1]" ||
                    Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(host)
                ) {
                    return null
                }
                uri.toString()
            }.getOrNull()
        }
    }
}
