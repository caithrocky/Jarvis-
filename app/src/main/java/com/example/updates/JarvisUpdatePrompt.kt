package com.example.updates

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private data class JarvisUpdate(
    val versionName: String,
    val apkUrl: String
)

@Composable
fun JarvisUpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<JarvisUpdate?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/caithrocky/Jarvis-/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "JARVIS-Android-Updater")
                    .build()
                OkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: return@use null)
                    val tag = json.optString("tag_name").removePrefix("v")
                    val assets = json.optJSONArray("assets") ?: return@use null
                    var url: String? = null
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name") == "Jarvis.apk") {
                            url = asset.optString("browser_download_url")
                            break
                        }
                    }
                    if (url.isNullOrBlank() || compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) null
                    else JarvisUpdate(tag, url!!)
                }
            }.getOrNull()
        }
    }

    val availableUpdate = update
    if (availableUpdate != null && !dismissed) {
        AlertDialog(
            onDismissRequest = { if (!busy) dismissed = true },
            title = { Text("JARVIS update available") },
            text = {
                Text(
                    message ?: if (downloadedApk == null) {
                        "Version ${availableUpdate.versionName} is available. Download it now?"
                    } else {
                        "The update is downloaded and ready to install."
                    }
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            val apk = downloadedApk
                            if (apk == null) {
                                busy = true
                                message = "Downloading update…"
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val request = Request.Builder()
                                            .url(availableUpdate.apkUrl)
                                            .header("User-Agent", "JARVIS-Android-Updater")
                                            .build()
                                        OkHttpClient().newCall(request).execute().use { response ->
                                            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                                            val body = response.body ?: error("Empty APK download")
                                            val folder = File(context.cacheDir, "updates")
                                            check(folder.mkdirs() || folder.isDirectory)
                                            val file = File(folder, "Jarvis-${availableUpdate.versionName}.apk")
                                            body.byteStream().use { input ->
                                                file.outputStream().use { output -> input.copyTo(output) }
                                            }
                                            check(file.length() > 0) { "Downloaded APK is empty" }
                                            file
                                        }
                                    }
                                }
                                busy = false
                                result.onSuccess {
                                    downloadedApk = it
                                    message = "Update downloaded. Tap Install update to continue."
                                }.onFailure {
                                    message = it.message ?: "Unable to download the update."
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
                TextButton(onClick = { dismissed = true }, enabled = !busy) {
                    Text("Later")
                }
            }
        )
    }
}

private fun compareVersions(first: String, second: String): Int {
    val a = first.split(".").map { it.toIntOrNull() ?: 0 }
    val b = second.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(i) { 0 }
        val right = b.getOrElse(i) { 0 }
        if (left != right) return left.compareTo(right)
    }
    return 0
}
