package com.example.update

import android.content.Context
import android.content.pm.PackageManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkFile: File
)

class UpdateRepository(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    companion object {
        const val OWNER = "caithrocky"
        const val REPOSITORY = "Jarvis-"
        private const val API_URL =
            "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"
    }

    fun checkAndDownload(): UpdateInfo? {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Jarvis-Android")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            val release = JSONObject(body)
            val tagName = release.optString("tag_name").trim()
            val latestVersion = tagName.removePrefix("v").removePrefix("V")
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "0"

            if (!isNewerVersion(latestVersion, currentVersion)) return null

            val apkAsset = release.optJSONArray("assets")?.let { assets ->
                findApkAsset(assets)
            } ?: return null

            val downloadUrl = apkAsset.optString("browser_download_url")
            if (downloadUrl.isBlank()) return null

            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "jarvis-$latestVersion.apk")
            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "Jarvis-Android")
                .build()

            httpClient.newCall(downloadRequest).execute().use { downloadResponse ->
                if (!downloadResponse.isSuccessful) return null
                val source = downloadResponse.body?.byteStream() ?: return null
                val tempFile = File(apkFile.parentFile, "${apkFile.name}.part")
                FileOutputStream(tempFile).use { output ->
                    source.use { input -> input.copyTo(output) }
                }
                if (tempFile.length() <= 0) return null
                if (apkFile.exists()) apkFile.delete()
                if (!tempFile.renameTo(apkFile)) return null
            }

            return UpdateInfo(
                versionName = latestVersion,
                releaseNotes = release.optString("body").ifBlank { "A new Jarvis update is available." },
                apkUrl = downloadUrl,
                apkFile = apkFile
            )
        }
    }

    fun downloadedUpdate(): File? =
        File(context.cacheDir, "updates").listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

    private fun findApkAsset(assets: JSONArray): JSONObject? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) return asset
        }
        return null
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun numericParts(value: String): List<Int> =
            Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()

        val latestParts = numericParts(latest)
        val currentParts = numericParts(current)
        val length = maxOf(latestParts.size, currentParts.size)
        for (index in 0 until length) {
            val latestPart = latestParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (latestPart != currentPart) return latestPart > currentPart
        }
        return false
    }
}
