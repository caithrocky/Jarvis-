package com.example.data.remote

import android.util.Xml
import com.example.domain.model.EconomicEvent
import com.example.domain.model.NewsItem
import com.example.security.SecretsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewsAndCalendarRepository(private val httpClient: OkHttpClient) {

    private data class RssSource(val name: String, val url: String)

    private val rssSources = listOf(
        RssSource("CNBC Markets", "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258"),
        RssSource("CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/"),
        RssSource("Federal Reserve", "https://www.federalreserve.gov/feeds/press_all.xml"),
        RssSource("FXStreet", "https://www.fxstreet.com/rss/news")
    )

    suspend fun fetchLiveFinancialNews(): Pair<List<NewsItem>, String?> = withContext(Dispatchers.IO) {
        val allItems = mutableListOf<NewsItem>()
        val errors = mutableListOf<String>()

        // 1. Fetch Finnhub live Forex & Crypto news if FINNHUB_API_KEY is configured
        SecretsConfig.finnhubApiKey?.let { finnhubKey ->
            for (category in listOf("forex", "crypto")) {
                runCatching {
                    val req = Request.Builder()
                        .url("https://finnhub.io/api/v1/news?category=$category&token=$finnhubKey")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val arr = JSONArray(resp.body?.string().orEmpty())
                            val dateFmt = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
                            for (i in 0 until minOf(arr.length(), 10)) {
                                val obj = arr.getJSONObject(i)
                                val headline = obj.optString("headline").trim()
                                val summary = obj.optString("summary").trim()
                                val source = obj.optString("source", "Finnhub")
                                val url = obj.optString("url", "")
                                val dtSec = obj.optLong("datetime", System.currentTimeMillis() / 1000L)
                                val tsMs = dtSec * 1000L
                                if (headline.isNotBlank()) {
                                    val combined = "$headline $summary"
                                    val assets = detectAffectedAssets(combined)
                                    allItems.add(
                                        NewsItem(
                                            id = "finnhub_${obj.optLong("id", headline.hashCode().toLong())}",
                                            title = headline,
                                            summary = summary.take(260).ifBlank { "Tap Source for full article." },
                                            source = "$source (Finnhub)",
                                            url = url,
                                            publishedAtMs = tsMs,
                                            publishedLabel = dateFmt.format(Date(tsMs)),
                                            affectedAssets = assets,
                                            marketImplication = buildMarketImplication(combined, assets),
                                            impactLevel = detectImpactLevel(combined)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }.onFailure {
                    errors.add("Finnhub $category: ${it.message ?: "Error"}")
                }
            }
        }

        // 2. Fetch public RSS/Atom financial feeds
        for (source in rssSources) {
            runCatching {
                val req = Request.Builder()
                    .url(source.url)
                    .header("User-Agent", "JARVIS-Financial-Companion/1.0")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        errors.add("${source.name}: HTTP ${resp.code}")
                    } else {
                        val xml = resp.body?.string().orEmpty()
                        if (xml.isNotBlank()) {
                            allItems.addAll(parseRssXml(xml, source.name))
                        }
                    }
                }
            }.onFailure {
                errors.add("${source.name}: ${it.message ?: "Network error"}")
            }
        }

        val sorted = allItems
            .distinctBy { it.title }
            .sortedByDescending { it.publishedAtMs }
            .take(45)

        val errMsg = if (sorted.isEmpty() && errors.isNotEmpty()) {
            "Unable to load financial news feeds (${errors.first()})."
        } else null

        sorted to errMsg
    }

    suspend fun testFinnhubConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = SecretsConfig.finnhubApiKey
            ?: return@withContext false to "FINNHUB_API_KEY not set in Secrets."
        runCatching {
            val req = Request.Builder()
                .url("https://finnhub.io/api/v1/news?category=forex&token=$key")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    true to "Connected (Finnhub Live News API)"
                } else {
                    val err = resp.body?.string()?.take(120).orEmpty()
                    false to "Finnhub HTTP ${resp.code}: $err"
                }
            }
        }.getOrElse {
            false to "Finnhub unreachable: ${it.message ?: "Network error"}"
        }
    }

    suspend fun fetchEconomicCalendar(): Pair<List<EconomicEvent>, String?> = withContext(Dispatchers.IO) {
        val finnhubKey = SecretsConfig.finnhubApiKey
            ?: return@withContext emptyList<EconomicEvent>() to
                "Economic calendar requires FINNHUB_API_KEY in AI Studio Secrets."

        runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val now = System.currentTimeMillis()
            val from = sdf.format(now - 86_400_000L)
            val to = sdf.format(now + 4 * 86_400_000L)
            val url = "https://finnhub.io/api/v1/calendar/economic?from=$from&to=$to&token=$finnhubKey"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 403) {
                    return@withContext emptyList<EconomicEvent>() to
                        "Finnhub Live News is connected; Finnhub's /calendar/economic endpoint requires a Finnhub premium plan."
                }
                if (!resp.isSuccessful) {
                    val bodyErr = resp.body?.string()?.take(140) ?: "HTTP ${resp.code}"
                    return@withContext emptyList<EconomicEvent>() to "Finnhub calendar (${resp.code}): $bodyErr"
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr = json.optJSONArray("economicCalendar")
                    ?: return@withContext emptyList<EconomicEvent>() to "No upcoming economic releases returned for this window."
                val events = mutableListOf<EconomicEvent>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    events.add(
                        EconomicEvent(
                            id = "${obj.optString("time")}_${obj.optString("event")}",
                            title = obj.optString("event", "Economic Release"),
                            currency = obj.optString("country", "GLOBAL"),
                            impact = obj.optString("impact", "medium").uppercase(Locale.US),
                            actual = obj.opt("actual")?.takeIf { it != JSONObject.NULL }?.toString(),
                            forecast = obj.opt("estimate")?.takeIf { it != JSONObject.NULL }?.toString(),
                            previous = obj.opt("prev")?.takeIf { it != JSONObject.NULL }?.toString(),
                            scheduledTimeLabel = obj.optString("time", "Scheduled")
                        )
                    )
                }
                events.take(30) to null
            }
        }.getOrElse {
            emptyList<EconomicEvent>() to "Calendar request failed: ${it.message ?: "Unknown error"}"
        }
    }

    private fun parseRssXml(xmlContent: String, sourceName: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var insideItem = false
            var title = ""
            var link = ""
            var description = ""
            var pubDate = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.lowercase(Locale.US)
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tag == "item" || tag == "entry") {
                            insideItem = true
                            title = ""
                            link = ""
                            description = ""
                            pubDate = ""
                        } else if (insideItem) {
                            when (tag) {
                                "title" -> title = parser.nextText().trim()
                                "link" -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    link = if (!href.isNullOrBlank()) href else parser.nextText().trim()
                                }
                                "description", "summary" -> description = stripHtml(parser.nextText())
                                "pubdate", "updated", "published" -> pubDate = parser.nextText().trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if ((tag == "item" || tag == "entry") && insideItem) {
                            insideItem = false
                            if (title.isNotBlank()) {
                                val parsedTime = parseRssDate(pubDate)
                                val combined = "$title $description"
                                val assets = detectAffectedAssets(combined)
                                val impact = detectImpactLevel(combined)
                                val implication = buildMarketImplication(combined, assets)
                                items.add(
                                    NewsItem(
                                        id = "${sourceName}_${title.hashCode()}",
                                        title = title,
                                        summary = description.take(260).ifBlank { "Tap source link for full report." },
                                        source = sourceName,
                                        url = link,
                                        publishedAtMs = parsedTime,
                                        publishedLabel = pubDate.ifBlank { "Recent" },
                                        affectedAssets = assets,
                                        marketImplication = implication,
                                        impactLevel = impact
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return items.take(12)
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseRssDate(raw: String): Long {
        if (raw.isBlank()) return System.currentTimeMillis()
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (fmt in formats) {
            runCatching {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                return sdf.parse(raw)?.time ?: continue
            }
        }
        return System.currentTimeMillis()
    }

    private fun detectAffectedAssets(text: String): List<String> {
        val upper = text.uppercase(Locale.US)
        val matched = mutableListOf<String>()
        if (upper.contains("FED") || upper.contains("DOLLAR") || upper.contains("USD") || upper.contains("INFLATION") || upper.contains("CPI") || upper.contains("PAYROLL")) matched.add("USD")
        if (upper.contains("ECB") || upper.contains("EURO") || upper.contains("EUR/USD")) matched.add("EUR/USD")
        if (upper.contains("BOE") || upper.contains("POUND") || upper.contains("STERLING") || upper.contains("GBP")) matched.add("GBP/USD")
        if (upper.contains("BOJ") || upper.contains("YEN") || upper.contains("JPY")) matched.add("USD/JPY")
        if (upper.contains("BITCOIN") || upper.contains("BTC")) matched.add("BTC/USDT")
        if (upper.contains("ETHEREUM") || upper.contains("ETH")) matched.add("ETH/USDT")
        if (upper.contains("SOLANA") || upper.contains("SOL")) matched.add("SOL/USDT")
        if (upper.contains("GOLD") || upper.contains("BULLION") || upper.contains("XAU")) matched.add("XAU/USD")
        if (upper.contains("OIL") || upper.contains("CRUDE") || upper.contains("OPEC")) matched.add("WTI/USD")
        if (matched.isEmpty()) matched.add("MACRO")
        return matched.distinct().take(4)
    }

    private fun detectImpactLevel(text: String): String {
        val lower = text.lowercase(Locale.US)
        return when {
            lower.contains("rate") || lower.contains("fed") || lower.contains("inflation") ||
                lower.contains("cpi") || lower.contains("payroll") || lower.contains("war") ||
                lower.contains("tariff") || lower.contains("sec") || lower.contains("etf") -> "HIGH"
            lower.contains("gdp") || lower.contains("yield") || lower.contains("oil") ||
                lower.contains("gold") || lower.contains("bitcoin") -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun buildMarketImplication(text: String, assets: List<String>): String {
        val lower = text.lowercase(Locale.US)
        val assetLabel = assets.joinToString(", ")
        return when {
            lower.contains("rate cut") || lower.contains("dovish") ->
                "Dovish policy tone tends to soften USD yields and support $assetLabel risk assets & Gold."
            lower.contains("rate hike") || lower.contains("hawkish") || lower.contains("hotter") ->
                "Hawkish tightening signals tend to lift USD yields and pressure non-yielding assets ($assetLabel)."
            lower.contains("inflow") || lower.contains("approval") || lower.contains("surge") ->
                "Positive liquidity catalyst; watch breakout volume and resistance levels on $assetLabel."
            lower.contains("geopolitical") || lower.contains("conflict") || lower.contains("sanction") ->
                "Elevated geopolitical risk often drives safe-haven flows into Gold (XAU/USD) and USD."
            else ->
                "Monitor price reaction around nearest key support/resistance levels for $assetLabel."
        }
    }
}
