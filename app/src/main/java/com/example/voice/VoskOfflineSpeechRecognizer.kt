package com.example.voice

import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

data class VoskRecognitionStep(
    val partialText: String = "",
    val finalizedText: String = "",
    val didFinalize: Boolean = false
)

/**
 * Real offline speech recognition powered by Vosk (`com.alphacephei:vosk-android`) using
 * 16 kHz mono 16-bit PCM frames captured directly from AudioRecord (zero network dependency,
 * zero system recognizer chimes).
 */
class VoskOfflineSpeechRecognizer {

    private var loadedModel: Model? = null
    private var loadedModelPath: String? = null
    private var activeRecognizer: Recognizer? = null

    @Volatile
    private var logLevelConfigured = false

    @Synchronized
    fun prepareSession(modelDir: File): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        return runCatching {
            if (!logLevelConfigured) {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                logLevelConfigured = true
            }
            val targetPath = modelDir.absolutePath
            if (loadedModel == null || loadedModelPath != targetPath) {
                activeRecognizer?.close()
                activeRecognizer = null
                loadedModel?.close()
                loadedModel = Model(targetPath)
                loadedModelPath = targetPath
            }
            if (activeRecognizer == null) {
                activeRecognizer = Recognizer(loadedModel, SAMPLE_RATE_HZ)
            } else {
                activeRecognizer?.reset()
            }
            true
        }.getOrElse {
            release()
            false
        }
    }

    @Synchronized
    fun acceptPcmSamples(samples: ShortArray, length: Int): VoskRecognitionStep {
        val recognizer = activeRecognizer ?: return VoskRecognitionStep()
        if (length <= 0) return VoskRecognitionStep()
        return runCatching {
            val completed = recognizer.acceptWaveForm(samples, length)
            if (completed) {
                val text = parseVoskField(recognizer.result, "text")
                VoskRecognitionStep(finalizedText = text, didFinalize = text.isNotBlank())
            } else {
                val partial = parseVoskField(recognizer.partialResult, "partial")
                VoskRecognitionStep(partialText = partial, didFinalize = false)
            }
        }.getOrDefault(VoskRecognitionStep())
    }

    @Synchronized
    fun flushFinalResult(): String {
        val recognizer = activeRecognizer ?: return ""
        return runCatching {
            val text = parseVoskField(recognizer.finalResult, "text")
            recognizer.reset()
            text
        }.getOrDefault("")
    }

    @Synchronized
    fun resetSession() {
        runCatching { activeRecognizer?.reset() }
    }

    @Synchronized
    fun release() {
        runCatching { activeRecognizer?.close() }
        activeRecognizer = null
        runCatching { loadedModel?.close() }
        loadedModel = null
        loadedModelPath = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000.0f

        internal fun parseVoskField(jsonString: String?, fieldName: String): String {
            if (jsonString.isNullOrBlank()) return ""
            val pattern = Regex(""""${Regex.escape(fieldName)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
            val raw = pattern.find(jsonString)?.groupValues?.getOrNull(1) ?: return ""
            return raw
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", " ")
                .trim()
        }
    }
}
