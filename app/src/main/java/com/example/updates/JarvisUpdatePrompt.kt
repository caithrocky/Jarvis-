package com.example.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val PREFS = "jarvis_updater"
private const val KEY_DISMISSED = "dismissed_version"
private const val KEY_LAST_CHECK = "last_check_ms"
private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
private const val RELEASE_URL = "https://api.github.com/repos/caithrocky/Jarvis-/releases/latest"
private const val APK_ASSET = "Jarvis.apk"
private const val SHA_ASSET = "Jarvis.apk.sha256"
private const val USER_AGENT = "JARVIS-Android-Updater"

private data class JarvisUpdate(
    val versionName: String,
    val apkUrl: String,
    val sha256Url: String?,
    val notes: String
)

@Composable
fun JarvisUpdatePrompt(forceCheck: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { newClient() }
    val installedVersion = remember { installedVersionName(context) }
    var update by remember { mutableStateOf<JarvisUpdate?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    fun dismissForNow() {
        update?.let { prefs(context).edit().putString(KEY_DISMISSED, it.versionName).apply() }
        dismissed = true
    }

    LaunchedEffect(forceCheck) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!forceCheck && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatestRelease(client) } }
        result.onSuccess { latest ->
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (latest != null && compareVersions(latest.versionName, installedVersion) > 0) {
                val dismissedBefore = prefs.getString(KEY_DISMISSED, null) == latest.versionName
                if (forceCheck || !dismissedBefore) {
                    update = latest
                    dismissed = false
                }
            }
        }
    }

    val available = update
    if (available != null && !dismissed) {
        AlertDialog(
            onDismissRequest = { if (!busy) dismissForNow() },
            title = { Text("JARVIS update available") },
            text = {
                Column {
                    Text("Version ${available.versionName} is ready (you have $installedVersion).")
                    if (available.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("What's new", fontWeight = FontWeight.Bold)
                        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                            Text(available.notes)
                        }
                    }
                    if (busy) {
                        Spacer(Modifier.height(12.dp))
                        val p = progress
                        if (p != null) {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text("${(p * 100).toInt()}%")
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            val apk = downloadedApk
                            if (apk == null) {
                                busy = true
                                progress = null
                                message = "Downloading update…"
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        downloadAndVerify(context, client, available) { done, total ->
                                            progress = if (total > 0) done.toFloat() / total.toFloat() else null
                                        }
                                    }
                                }
                                busy = false
                                progress = null
                                result.onSuccess {
                                    downloadedApk = it
                                    message = "Download verified (SHA-256 matches). Tap Install update to continue."
                                }.onFailure { t ->
                                    message = if (t is IOException) {
                                        "Download interrupted. Check your connection and tap Download update to try again."
                                    } else {
                                        t.message ?: "Unable to download the update."
                                    }
                                }
                            } else if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                !context.packageManager.canRequestPackageInstalls()
                            ) {
                                message = "Allow JARVIS to install updates, then return here and tap Install update."
                                val settingsIntent = Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(settingsIntent)
                            } else {
                                runCatching {
                                    val apkUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        apk
                                    )
                                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(installIntent)
                                    message = "Android's installer is open. Approve it to finish updating."
                                }.onFailure {
                                    message = it.message ?: "Could not open Android's installer."
                                }
                            }
                        }
                    }
                ) {
                    Text(if (busy) "Please wait…" else if (downloadedApk == null) "Download update" else "Install update")
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissForNow() }, enabled = !busy) {
                    Text("Later")
                }
            }
        )
    }
}

private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private fun installedVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "0"

private fun newClient(): OkHttpClient = OkHttpClient.Builder()
    .followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private fun fetchLatestRelease(client: OkHttpClient): JarvisUpdate? {
    val request = Request.Builder()
        .url(RELEASE_URL)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", USER_AGENT)
        .build()
    return client.newCall(request).execute().use { response ->
        if (response.code == 404) return@use null
        check(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
        val json = JSONObject(response.body?.string() ?: return@use null)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@use null
        val tag = json.optString("tag_name").removePrefix("v")
        if (tag.isBlank()) return@use null
        val assets = json.optJSONArray("assets") ?: return@use null
        var apkUrl: String? = null
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            when (asset.optString("name")) {
                APK_ASSET -> apkUrl = asset.optString("browser_download_url")
                SHA_ASSET -> shaUrl = asset.optString("browser_download_url")
            }
        }
        val url = apkUrl
        if (url.isNullOrBlank() || !url.startsWith("https://")) return@use null
        val notes = if (json.isNull("body")) "" else json.optString("body").trim().take(1500)
        JarvisUpdate(tag, url, shaUrl?.takeIf { it.startsWith("https://") }, notes)
    }
}

private fun fetchExpectedSha(client: OkHttpClient, url: String): String {
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    return client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "Checksum download failed: HTTP ${response.code}" }
        check(response.request.url.isHttps) { "Checksum was redirected to a non-HTTPS address." }
        val text = response.body?.string().orEmpty()
        val token = text.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
        check(Regex("[0-9a-f]{64}").matches(token)) { "The checksum file is not valid." }
        token
    }
}

private fun downloadAndVerify(
    context: Context,
    client: OkHttpClient,
    update: JarvisUpdate,
    onProgress: (Long, Long) -> Unit
): File {
    val shaUrl = update.sha256Url
        ?: error("This release has no checksum file, so the update was refused.")
    val expected = fetchExpectedSha(client, shaUrl)
    val folder = File(context.cacheDir, "updates")
    check(folder.mkdirs() || folder.isDirectory) { "Could not create the download folder." }
    folder.listFiles()?.forEach { it.delete() }
    val part = File(folder, "Jarvis-${update.versionName}.apk.part")
    val apk = File(folder, "Jarvis-${update.versionName}.apk")
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
            check(response.request.url.isHttps) { "Download was redirected to a non-HTTPS address." }
            val body = response.body ?: error("Empty APK download")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0) check(done == total) { "Download was incomplete." }
        }
        check(part.length() > 0) { "Downloaded APK is empty." }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "Checksum mismatch: the download is corrupted or has been tampered with."
        }
        check(part.renameTo(apk)) { "Could not save the verified update." }
        return apk
    } catch (t: Throwable) {
        part.delete()
        apk.delete()
        throw t
    }
}

private fun compareVersions(first: String, second: String): Int {
    fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
    val a = parts(first)
    val b = parts(second)
    for (i in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(i) { 0 }
        val right = b.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
    }
    return 0
}
