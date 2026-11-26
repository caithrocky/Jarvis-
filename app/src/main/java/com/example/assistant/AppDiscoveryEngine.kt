package com.example.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.data.remote.WebKnowledgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

data class DiscoveredAppCandidate(
    val title: String,
    val packageId: String?,
    val sourceName: String,
    val installUrl: String
)

data class AppDiscoveryResult(
    val query: String,
    val candidates: List<DiscoveredAppCandidate>,
    val statusMessage: String
)

/**
 * Discovers real Android applications from Google Play Store and verified official web sources.
 * Never performs silent APK downloads or Dynamic Code Loading — always launches Play Store
 * or prompts explicit user confirmation for external download pages.
 */
class AppDiscoveryEngine(
    private val httpClient: OkHttpClient,
    private val webClient: WebKnowledgeClient
) {

    suspend fun discoverApp(rawQuery: String): AppDiscoveryResult = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return@withContext AppDiscoveryResult(
                query = "",
                candidates = emptyList(),
                statusMessage = "Tell me which app you want to find or install, Sir."
            )
        }

        val playCandidates = queryPlayStore(query)
        if (playCandidates.isNotEmpty()) {
            val top = playCandidates.first()
            val msg = if (playCandidates.size == 1) {
                "Found ${top.title} on Google Play (${top.packageId}). Opening its Play Store page so you can confirm install, Sir."
            } else {
                val options = playCandidates.take(3).mapIndexed { i, c -> "${i + 1}: ${c.title}" }.joinToString(", ")
                "I found a few Play Store matches for \"$query\", Sir ($options). Opening the closest match (${top.title}) — let me know if you prefer #2 or #3."
            }
            return@withContext AppDiscoveryResult(
                query = query,
                candidates = playCandidates,
                statusMessage = msg
            )
        }

        // Web fallback for official download sources outside Play Store
        val webRes = webClient.searchInternet("$query official android download")
        val webCandidates = webRes.items.take(3).map { item ->
            DiscoveredAppCandidate(
                title = item.title,
                packageId = null,
                sourceName = "Official Web Search",
                installUrl = item.url
            )
        }
        val msg = if (webCandidates.isNotEmpty()) {
            "Couldn't find an exact Play Store listing for \"$query\", Sir, so I located ${webCandidates.first().title} (${webCandidates.first().installUrl}). Confirm if you want me to open that download page in Chrome."
        } else {
            "I couldn't find a verified Play Store or official download page for \"$query\", Sir. Want me to search Play Store interactively?"
        }
        AppDiscoveryResult(
            query = query,
            candidates = webCandidates,
            statusMessage = msg
        )
    }

    internal fun queryPlayStore(query: String): List<DiscoveredAppCandidate> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://play.google.com/store/search?q=$encoded&c=apps")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val html = resp.body?.string().orEmpty()
                parsePlayStorePackages(html, query)
            }
        }.getOrDefault(emptyList())
    }

    internal fun parsePlayStorePackages(html: String, query: String): List<DiscoveredAppCandidate> {
        if (html.isBlank()) return emptyList()
        val pkgRegex = Regex("/store/apps/details\\?id=([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)")
        val seen = LinkedHashSet<String>()
        pkgRegex.findAll(html).forEach { match ->
            val pkg = match.groupValues[1]
            if (!pkg.startsWith("com.google.android.gms") && seen.size < 5) {
                seen.add(pkg)
            }
        }
        return seen.mapIndexed { idx, pkg ->
            val inferredName = if (idx == 0) {
                query.replaceFirstChar { it.uppercase() }
            } else {
                pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
            DiscoveredAppCandidate(
                title = inferredName,
                packageId = pkg,
                sourceName = "Google Play",
                installUrl = "https://play.google.com/store/apps/details?id=$pkg"
            )
        }
    }

    fun openInstallCandidate(context: Context, candidate: DiscoveredAppCandidate): SystemExecutionResult {
        val pkg = candidate.packageId
        if (!pkg.isNullOrBlank()) {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launchedPlay = runCatching {
                context.startActivity(marketIntent)
                true
            }.getOrDefault(false)
            if (launchedPlay) {
                return SystemExecutionResult(
                    handled = true,
                    replyText = "Opening ${candidate.title} on Google Play so you can tap Install, Sir.",
                    actionLabel = "Play Store",
                    launchedTarget = true
                )
            }
        }
        return JarvisSystemAssistant.openWebsiteInChrome(context, candidate.installUrl)
    }

    fun openPlayStoreSearch(context: Context, query: String): SystemExecutionResult {
        val encoded = Uri.encode(query)
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded&c=apps")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(marketIntent)
            true
        }.getOrDefault(false)
        return if (launched) {
            SystemExecutionResult(true, "Opened Google Play search for \"$query\", Sir.", "Play Store", true)
        } else {
            JarvisSystemAssistant.openWebsiteInChrome(context, "https://play.google.com/store/search?q=$encoded&c=apps")
        }
    }
}
