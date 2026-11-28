package com.example.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

enum class ModelInstallStage {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
    ERROR
}

data class OfflineModelItemState(
    val stage: ModelInstallStage = ModelInstallStage.NOT_INSTALLED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressFraction: Float = 0f,
    val installedSizeBytes: Long = 0L,
    val errorMessage: String? = null
)

data class OfflineModelsUiState(
    val lfmModel: OfflineModelItemState = OfflineModelItemState(),
    val voskSttModel: OfflineModelItemState = OfflineModelItemState()
)

/**
 * Downloads, validates, extracts, and manages genuine on-device models inside app-private storage:
 * 1. Liquid AI LFM2.5-1.2B-Instruct (GGUF Q4_K_M) for offline LLM reasoning.
 * 2. Vosk Small English (vosk-model-small-en-us-0.15) for offline speech recognition.
 */
class OfflineModelManager(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val rootDir: File by lazy {
        File(context.filesDir, "offline_models").apply { mkdirs() }
    }

    private val lfmDir: File get() = File(rootDir, "lfm2.5-1.2b")
    private val lfmFinalFile: File get() = File(lfmDir, LFM_FILENAME)
    private val lfmPartFile: File get() = File(lfmDir, "$LFM_FILENAME.part")

    private val voskRootDir: File get() = File(rootDir, "vosk-en-us")
    private val voskModelDir: File get() = File(voskRootDir, "model")
    private val voskZipPartFile: File get() = File(voskRootDir, "$VOSK_ZIP_FILENAME.part")
    private val voskReadyMarker: File get() = File(voskModelDir, ".ready")

    private var lfmDownloadJob: Job? = null
    private var voskDownloadJob: Job? = null

    private val _state = MutableStateFlow(OfflineModelsUiState())
    val state: StateFlow<OfflineModelsUiState> = _state.asStateFlow()

    init {
        refreshInstalledStates()
    }

    fun refreshInstalledStates() {
        val lfmReady = isLfmFileValid(lfmFinalFile)
        val lfmPartBytes = lfmPartFile.takeIf { it.exists() }?.length() ?: 0L
        val voskReady = isVoskDirValid(voskModelDir)

        _state.update { current ->
            val nextLfm = if (lfmDownloadJob?.isActive == true) {
                current.lfmModel
            } else if (lfmReady) {
                OfflineModelItemState(
                    stage = ModelInstallStage.INSTALLED,
                    downloadedBytes = lfmFinalFile.length(),
                    totalBytes = lfmFinalFile.length(),
                    progressFraction = 1f,
                    installedSizeBytes = lfmFinalFile.length()
                )
            } else {
                OfflineModelItemState(
                    stage = ModelInstallStage.NOT_INSTALLED,
                    downloadedBytes = lfmPartBytes,
                    totalBytes = LFM_APPROX_BYTES,
                    progressFraction = if (lfmPartBytes > 0L) (lfmPartBytes.toFloat() / LFM_APPROX_BYTES).coerceIn(0f, 0.99f) else 0f
                )
            }

            val nextVosk = if (voskDownloadJob?.isActive == true) {
                current.voskSttModel
            } else if (voskReady) {
                val totalDirBytes = directorySizeBytes(voskModelDir)
                OfflineModelItemState(
                    stage = ModelInstallStage.INSTALLED,
                    downloadedBytes = totalDirBytes,
                    totalBytes = totalDirBytes,
                    progressFraction = 1f,
                    installedSizeBytes = totalDirBytes
                )
            } else {
                val partLen = voskZipPartFile.takeIf { it.exists() }?.length() ?: 0L
                OfflineModelItemState(
                    stage = ModelInstallStage.NOT_INSTALLED,
                    downloadedBytes = partLen,
                    totalBytes = VOSK_APPROX_BYTES,
                    progressFraction = if (partLen > 0L) (partLen.toFloat() / VOSK_APPROX_BYTES).coerceIn(0f, 0.99f) else 0f
                )
            }

            OfflineModelsUiState(lfmModel = nextLfm, voskSttModel = nextVosk)
        }
    }

    fun getLfmModelPathIfReady(): String? =
        lfmFinalFile.takeIf { isLfmFileValid(it) }?.absolutePath

    fun getVoskModelDirIfReady(): File? =
        voskModelDir.takeIf { isVoskDirValid(it) }

    fun isLfmModelInstalled(): Boolean = getLfmModelPathIfReady() != null
    fun isVoskModelInstalled(): Boolean = getVoskModelDirIfReady() != null

    fun downloadLfmModel() {
        if (lfmDownloadJob?.isActive == true) return
        if (!OfflineAssistantEngine.isNetworkConnected(context)) {
            _state.update {
                it.copy(
                    lfmModel = it.lfmModel.copy(
                        stage = ModelInstallStage.ERROR,
                        errorMessage = "Connect to Wi-Fi or mobile data to download LFM2.5 1.2B."
                    )
                )
            }
            return
        }

        lfmDownloadJob = scope.launch {
            try {
                lfmDir.mkdirs()
                streamFileWithResume(
                    url = LFM_MODEL_URL,
                    partFile = lfmPartFile,
                    approxTotalBytes = LFM_APPROX_BYTES
                ) { downloaded, total ->
                    val frac = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 0.99f) else 0f
                    _state.update {
                        it.copy(
                            lfmModel = OfflineModelItemState(
                                stage = ModelInstallStage.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                progressFraction = frac
                            )
                        )
                    }
                }

                if (!isLfmFileValid(lfmPartFile)) {
                    throw IllegalStateException("Downloaded file did not pass GGUF integrity check.")
                }
                if (lfmFinalFile.exists()) lfmFinalFile.delete()
                if (!lfmPartFile.renameTo(lfmFinalFile)) {
                    lfmPartFile.copyTo(lfmFinalFile, overwrite = true)
                    lfmPartFile.delete()
                }
                val finalBytes = lfmFinalFile.length()
                _state.update {
                    it.copy(
                        lfmModel = OfflineModelItemState(
                            stage = ModelInstallStage.INSTALLED,
                            downloadedBytes = finalBytes,
                            totalBytes = finalBytes,
                            progressFraction = 1f,
                            installedSizeBytes = finalBytes
                        )
                    )
                }
            } catch (_: CancellationException) {
                refreshInstalledStates()
            } catch (ex: Throwable) {
                _state.update {
                    it.copy(
                        lfmModel = OfflineModelItemState(
                            stage = ModelInstallStage.ERROR,
                            downloadedBytes = lfmPartFile.takeIf { f -> f.exists() }?.length() ?: 0L,
                            totalBytes = LFM_APPROX_BYTES,
                            errorMessage = ex.message ?: "LFM2.5 download failed"
                        )
                    )
                }
            }
        }
    }

    fun cancelLfmDownload() {
        lfmDownloadJob?.cancel()
        lfmDownloadJob = null
        refreshInstalledStates()
    }

    fun deleteLfmModel() {
        lfmDownloadJob?.cancel()
        lfmDownloadJob = null
        runCatching { lfmFinalFile.delete() }
        runCatching { lfmPartFile.delete() }
        refreshInstalledStates()
    }

    fun downloadVoskSttModel() {
        if (voskDownloadJob?.isActive == true) return
        if (!OfflineAssistantEngine.isNetworkConnected(context)) {
            _state.update {
                it.copy(
                    voskSttModel = it.voskSttModel.copy(
                        stage = ModelInstallStage.ERROR,
                        errorMessage = "Connect to Wi-Fi or mobile data to download offline speech recognition."
                    )
                )
            }
            return
        }

        voskDownloadJob = scope.launch {
            try {
                voskRootDir.mkdirs()
                streamFileWithResume(
                    url = VOSK_MODEL_ZIP_URL,
                    partFile = voskZipPartFile,
                    approxTotalBytes = VOSK_APPROX_BYTES
                ) { downloaded, total ->
                    val frac = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 0.90f) else 0f
                    _state.update {
                        it.copy(
                            voskSttModel = OfflineModelItemState(
                                stage = ModelInstallStage.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                progressFraction = frac
                            )
                        )
                    }
                }

                _state.update {
                    it.copy(
                        voskSttModel = it.voskSttModel.copy(
                            stage = ModelInstallStage.EXTRACTING,
                            progressFraction = 0.94f
                        )
                    )
                }

                val stagingDir = File(voskRootDir, "model_staging")
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()
                extractZipSafely(voskZipPartFile, stagingDir, stripTopFolder = true)
                if (!isVoskStructurePresent(stagingDir)) {
                    throw IllegalStateException("Extracted Vosk package is missing acoustic/config files.")
                }

                if (voskModelDir.exists()) voskModelDir.deleteRecursively()
                if (!stagingDir.renameTo(voskModelDir)) {
                    stagingDir.copyRecursively(voskModelDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }
                voskReadyMarker.writeText("ok")
                runCatching { voskZipPartFile.delete() }

                val totalBytes = directorySizeBytes(voskModelDir)
                _state.update {
                    it.copy(
                        voskSttModel = OfflineModelItemState(
                            stage = ModelInstallStage.INSTALLED,
                            downloadedBytes = totalBytes,
                            totalBytes = totalBytes,
                            progressFraction = 1f,
                            installedSizeBytes = totalBytes
                        )
                    )
                }
            } catch (_: CancellationException) {
                refreshInstalledStates()
            } catch (ex: Throwable) {
                _state.update {
                    it.copy(
                        voskSttModel = OfflineModelItemState(
                            stage = ModelInstallStage.ERROR,
                            downloadedBytes = voskZipPartFile.takeIf { f -> f.exists() }?.length() ?: 0L,
                            totalBytes = VOSK_APPROX_BYTES,
                            errorMessage = ex.message ?: "Offline speech model download failed"
                        )
                    )
                }
            }
        }
    }

    fun cancelVoskDownload() {
        voskDownloadJob?.cancel()
        voskDownloadJob = null
        refreshInstalledStates()
    }

    fun deleteVoskSttModel() {
        voskDownloadJob?.cancel()
        voskDownloadJob = null
        runCatching { voskModelDir.deleteRecursively() }
        runCatching { voskZipPartFile.delete() }
        refreshInstalledStates()
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.streamFileWithResume(
        url: String,
        partFile: File,
        approxTotalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val existingBytes = partFile.takeIf { it.exists() }?.length() ?: 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "JarvisAndroid/1.0")
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        val downloadClient = httpClient.newBuilder()
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        downloadClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 416 && existingBytes > 0L) {
                // Local .part size matched or exceeded remote length — reset and verify
                partFile.delete()
                streamFileWithResume(url, partFile, approxTotalBytes, onProgress)
                return
            }
            if (!resp.isSuccessful && resp.code != 206) {
                throw IllegalStateException("Download failed (HTTP ${resp.code})")
            }

            val append = resp.code == 206 && existingBytes > 0L
            val baseOffset = if (append) existingBytes else 0L
            val contentLen = resp.body?.contentLength()?.takeIf { it > 0L }
            val totalExpected = when {
                append && contentLen != null -> baseOffset + contentLen
                contentLen != null -> contentLen
                else -> approxTotalBytes
            }

            val input = resp.body?.byteStream()
                ?: throw IllegalStateException("Empty response body")

            var downloaded = baseOffset
            onProgress(downloaded, totalExpected)

            FileOutputStream(partFile, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var lastEmitMs = 0L
                while (isActive) {
                    val read = input.read(buf)
                    if (read == -1) break
                    out.write(buf, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= 180L) {
                        lastEmitMs = now
                        onProgress(downloaded, totalExpected)
                    }
                }
                out.flush()
            }
            if (!isActive) {
                throw CancellationException("Download cancelled")
            }
            onProgress(downloaded, maxOf(downloaded, totalExpected))
        }
    }

    private fun isLfmFileValid(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_LFM_BYTES) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(4)
                fis.read(magic) == 4 &&
                    magic[0] == 'G'.code.toByte() &&
                    magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun isVoskDirValid(dir: File): Boolean =
        dir.exists() && dir.isDirectory && voskReadyMarker.exists() && isVoskStructurePresent(dir)

    private fun isVoskStructurePresent(dir: File): Boolean =
        File(dir, "am/final.mdl").exists() ||
            File(dir, "conf/model.conf").exists() ||
            File(dir, "final.mdl").exists()

    private fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        dir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum
    }

    companion object {
        const val LFM_MODEL_DISPLAY_NAME = "Liquid AI LFM2.5 1.2B Instruct (Q4_K_M)"
        const val LFM_MODEL_URL =
            "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/LFM2.5-1.2B-Instruct-Q4_K_M.gguf"
        private const val LFM_FILENAME = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf"
        private const val LFM_APPROX_BYTES = 731_000_000L
        private const val MIN_VALID_LFM_BYTES = 250_000_000L

        const val VOSK_MODEL_DISPLAY_NAME = "Vosk Small English Offline STT"
        const val VOSK_MODEL_ZIP_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val VOSK_ZIP_FILENAME = "vosk-model-small-en-us-0.15.zip"
        private const val VOSK_APPROX_BYTES = 41_200_000L

        internal fun extractZipSafely(
            zipFile: File,
            destDir: File,
            stripTopFolder: Boolean = true
        ) {
            val destCanonical = destDir.canonicalPath
            val destPrefix = destCanonical + File.separator
            val buf = ByteArray(32 * 1024)

            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val rawName = entry.name.replace('\\', '/')
                    val normalizedRel = if (stripTopFolder && rawName.contains('/')) {
                        rawName.substringAfter('/')
                    } else {
                        rawName
                    }.trimStart('/')

                    if (normalizedRel.isNotBlank()) {
                        val outFile = File(destDir, normalizedRel)
                        val outCanonical = outFile.canonicalPath
                        if (outCanonical != destCanonical && !outCanonical.startsWith(destPrefix)) {
                            throw SecurityException("Blocked unsafe zip entry: $rawName")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                while (true) {
                                    val count = zis.read(buf)
                                    if (count == -1) break
                                    fos.write(buf, 0, count)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        fun formatMb(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 100.0) {
                "${mb.toInt()} MB"
            } else {
                String.format(java.util.Locale.US, "%.1f MB", mb)
            }
        }
    }
}
