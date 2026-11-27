package com.example.data.remote

import com.example.domain.model.VoicePersonality
import com.example.security.SecretsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SynthesizedAudio(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int
)

/**
 * Client for NVIDIA NIM Magpie-TTS Multilingual (nvidia/magpie-tts-multilingual)
 * hosted on NVIDIA Cloud Functions (NVCF).
 */
class NvidiaMagpieTtsClient(private val httpClient: OkHttpClient) {

    suspend fun synthesize(
        text: String,
        personality: VoicePersonality
    ): Result<SynthesizedAudio> = withContext(Dispatchers.IO) {
        val key = SecretsConfig.nvidiaNimApiKey
            ?: return@withContext Result.failure(IllegalStateException("NVIDIA_NIM_API_KEY not configured"))

        val cleanText = sanitizeForSpeech(text).take(600)
        if (cleanText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("No speakable text"))
        }

        runCatching {
            val primaryVoice = personality.nimVoiceName
            val firstTry = executeSynthesizeCall(key, cleanText, primaryVoice)
            if (firstTry.isSuccess) return@runCatching firstTry.getOrThrow()

            // If an .Emotion suffix (4+ parts) was rejected by a container revision, retry base speaker name
            val segments = primaryVoice.split('.')
            if (segments.size > 3) {
                val baseVoice = segments.take(3).joinToString(".")
                val baseTry = executeSynthesizeCall(key, cleanText, baseVoice)
                if (baseTry.isSuccess) return@runCatching baseTry.getOrThrow()
            }
            firstTry.getOrThrow()
        }
    }

    private fun executeSynthesizeCall(key: String, cleanText: String, voiceName: String): Result<SynthesizedAudio> = runCatching {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", cleanText)
            .addFormDataPart("language", "en-US")
            .addFormDataPart("voice", voiceName)
            .addFormDataPart("encoding", "LINEAR_PCM")
            .addFormDataPart("sample_rate_hz", DEFAULT_SAMPLE_RATE.toString())
            .build()

        val request = Request.Builder()
            .url(MAGPIE_NVCF_SYNTHESIZE_URL)
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val rawBytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val errPreview = rawBytes.decodeToString().take(200)
                error("Magpie TTS HTTP ${resp.code}: $errPreview")
            }
            if (rawBytes.isEmpty()) {
                error("Magpie TTS returned empty audio")
            }
            parseWavOrRawPcm(rawBytes)
        }
    }

    suspend fun testTtsConnection(personality: VoicePersonality): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = SecretsConfig.nvidiaNimApiKey
            ?: return@withContext false to "NVIDIA_NIM_API_KEY missing"
        synthesize("Systems online, Sir.", personality).fold(
            onSuccess = { true to "Magpie TTS active (${personality.label})" },
            onFailure = { false to (it.message ?: "Magpie TTS error") }
        )
    }

    internal fun parseWavOrRawPcm(bytes: ByteArray): SynthesizedAudio {
        // Check RIFF / WAVE container header
        if (bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
        ) {
            var sampleRate = DEFAULT_SAMPLE_RATE
            var channels = 1
            var bitsPerSample = 16
            var offset = 12
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            while (offset + 8 <= bytes.size) {
                val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = buf.getInt(offset + 4).coerceAtLeast(0)
                val dataStart = offset + 8
                when (chunkId) {
                    "fmt " -> if (dataStart + 16 <= bytes.size) {
                        channels = buf.getShort(dataStart + 2).toInt().coerceIn(1, 2)
                        sampleRate = buf.getInt(dataStart + 4).coerceIn(8000, 96000)
                        bitsPerSample = buf.getShort(dataStart + 14).toInt().coerceIn(8, 32)
                    }
                    "data" -> {
                        val end = (dataStart + chunkSize).coerceAtMost(bytes.size)
                        val pcm = bytes.copyOfRange(dataStart, end)
                        return SynthesizedAudio(pcm, sampleRate, channels, bitsPerSample)
                    }
                }
                val paddedSize = chunkSize + (chunkSize and 1)
                offset = dataStart + paddedSize
            }
        }
        return SynthesizedAudio(bytes, DEFAULT_SAMPLE_RATE, 1, 16)
    }

    internal fun sanitizeForSpeech(raw: String): String {
        return raw
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`~#>]"), "")
            .replace(Regex("\\s*[•|]\\s*"), ", ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        const val MAGPIE_MODEL_ID = "nvidia/magpie-tts-multilingual"
        const val MAGPIE_NVCF_FUNCTION_ID = "877104f7-e885-42b9-8de8-f6e4c6303969"
        const val MAGPIE_NVCF_SYNTHESIZE_URL =
            "https://$MAGPIE_NVCF_FUNCTION_ID.invocation.api.nvcf.nvidia.com/v1/audio/synthesize"
        private const val DEFAULT_SAMPLE_RATE = 44100
    }
}
