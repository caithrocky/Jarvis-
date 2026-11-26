package com.example.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

enum class WakeIntent {
    NONE,
    CASUAL_MENTION,
    DIRECT_WAKE
}

data class WakeEvalResult(
    val intent: WakeIntent,
    val remainder: String
)

/**
 * Context-aware wake phrase detector.
 * Distinguishes direct commands/summons ("Hey Jarvis", "Jarvis, open Instagram", "Okay Jarvis")
 * from casual mid-sentence third-person mentions ("I think Jarvis we need to find a better way").
 */
object ContextualWakeDetector {

    private val directGreetingRegex = Regex(
        "^(?:.*\\b)?(hey|ok|okay|hi|hello|yo)\\s+jarvis\\b[:,.!?\\s]*(.*)$",
        RegexOption.IGNORE_CASE
    )

    private val leadNameDirectRegex = Regex(
        "^jarvis\\s*(?:,|\\.|:|\\s+)\\s*(.*)$",
        RegexOption.IGNORE_CASE
    )

    private val trailingDirectRegex = Regex(
        "^(.+?),\\s*jarvis[.!?]*$",
        RegexOption.IGNORE_CASE
    )

    private val casualThirdPersonLeads = listOf(
        "i think jarvis",
        "we think jarvis",
        "i guess jarvis",
        "maybe jarvis",
        "like jarvis",
        "about jarvis",
        "told jarvis",
        "ask jarvis if",
        "what jarvis",
        "why jarvis",
        "when jarvis",
        "because jarvis"
    )

    private val bargeInPhrases = setOf(
        "wait", "stop", "hold on", "be quiet", "quiet", "shut up",
        "cancel that", "listen to me", "stop talking", "pause", "never mind"
    )

    fun evaluate(rawText: String, inActiveConversation: Boolean = false): WakeEvalResult {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return WakeEvalResult(WakeIntent.NONE, "")
        val lower = trimmed.lowercase(Locale.US)

        if (!lower.contains("jarvis") && !lower.contains("hey service")) {
            return WakeEvalResult(WakeIntent.NONE, "")
        }

        // Legacy phrase compatibility
        if (lower.contains("hey service")) {
            val remainder = trimmed.replace(Regex("(?i)\\bhey\\s+service\\b[:,.!?\\s]*"), "").trim()
            return WakeEvalResult(WakeIntent.DIRECT_WAKE, remainder)
        }

        // Explicit greeting summon ("Hey Jarvis...", "Okay Jarvis...", "Hi Jarvis...")
        directGreetingRegex.find(trimmed)?.let { m ->
            val remainder = m.groupValues[2].trim()
            return WakeEvalResult(WakeIntent.DIRECT_WAKE, remainder)
        }

        // Standalone "Jarvis"
        if (lower.replace(Regex("[.!?,:]"), "").trim() == "jarvis") {
            return WakeEvalResult(WakeIntent.DIRECT_WAKE, "")
        }

        // Casual third-person mention while idling (unless in active conversation already)
        if (!inActiveConversation && casualThirdPersonLeads.any { lower.startsWith(it) || lower.contains(" $it ") }) {
            return WakeEvalResult(WakeIntent.CASUAL_MENTION, trimmed)
        }

        // Direct address starting with "Jarvis, ..." or "Jarvis <imperative>"
        leadNameDirectRegex.find(trimmed)?.let { m ->
            val remainder = m.groupValues[1].trim()
            val remLower = remainder.lowercase(Locale.US)
            val looksNarrative = !inActiveConversation && (
                remLower.startsWith("we need") ||
                    remLower.startsWith("was ") ||
                    remLower.startsWith("is a ") ||
                    remLower.startsWith("did ") ||
                    remLower.startsWith("used to")
                )
            return if (looksNarrative) {
                WakeEvalResult(WakeIntent.CASUAL_MENTION, trimmed)
            } else {
                WakeEvalResult(WakeIntent.DIRECT_WAKE, remainder)
            }
        }

        // Polite suffix summon: "Open Instagram, Jarvis"
        trailingDirectRegex.find(trimmed)?.let { m ->
            val prefix = m.groupValues[1].trim()
            if (prefix.isNotBlank() && !casualThirdPersonLeads.any { lower.contains(it) }) {
                return WakeEvalResult(WakeIntent.DIRECT_WAKE, prefix)
            }
        }

        // Any remaining embedded "jarvis" outside direct greetings defaults to casual mention in standby
        return WakeEvalResult(WakeIntent.CASUAL_MENTION, trimmed)
    }

    fun isBargeInCommand(rawText: String): Boolean {
        val clean = rawText.lowercase(Locale.US)
            .replace(Regex("[.!?,]+$"), "")
            .trim()
        return clean in bargeInPhrases ||
            clean.startsWith("wait jarvis") ||
            clean.startsWith("stop jarvis") ||
            clean.startsWith("hold on jarvis") ||
            clean == "cancel"
    }
}

/**
 * Chime playback is disabled per user preference so the assistant never emits microphone noises.
 */
object JarvisChimePlayer {
    @Suppress("UNUSED_PARAMETER")
    fun playActivationChime(scope: CoroutineScope) {
        // Intentionally silent: user denied microphone activation chimes/noises.
    }
}

/**
 * Adaptive Voice Activity & Hardware-AEC Barge-In Detector.
 * Uses VOICE_COMMUNICATION + hardware AcousticEchoCanceler when available so
 * owner speech interrupts JARVIS cleanly without speaker feedback self-triggering.
 */
class VoiceActivityDetector {

    private var bargeInJob: Job? = null
    @Volatile
    private var adaptiveNoiseFloor = 0.007f

    fun updateAmbientRms(rms: Float) {
        if (rms < adaptiveNoiseFloor * 1.8f) {
            adaptiveNoiseFloor = (adaptiveNoiseFloor * 0.92f + rms * 0.08f).coerceIn(0.003f, 0.035f)
        }
    }

    fun isSpeechOverThreshold(rms: Float, peak: Int = 1000): Boolean {
        val threshold = (adaptiveNoiseFloor * 2.6f).coerceIn(0.018f, 0.095f)
        return rms >= threshold && peak >= 850
    }

    fun startBargeInMonitor(
        scope: CoroutineScope,
        hasMicPermission: Boolean,
        isStillSpeaking: () -> Boolean,
        onSpeechBargeIn: () -> Unit
    ) {
        stopBargeInMonitor()
        if (!hasMicPermission) return

        bargeInJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@launch

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull() ?: return@launch

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                return@launch
            }

            var aec: AcousticEchoCanceler? = null
            if (AcousticEchoCanceler.isAvailable()) {
                aec = runCatching {
                    AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                        enabled = true
                    }
                }.getOrNull()
            }

            // Require strong sustained near-field speech so TTS speaker output never self-interrupts
            val bargeInThreshold = if (aec?.enabled == true) 0.26f else 0.42f
            val buf = ShortArray(640) // 40ms frames
            var hotFrames = 0
            val warmupUntil = System.currentTimeMillis() + 850L

            try {
                recorder.startRecording()
                while (isActive && isStillSpeaking()) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read <= 0) break
                    if (System.currentTimeMillis() < warmupUntil) continue

                    var sumSq = 0.0
                    var peak = 0
                    for (i in 0 until read) {
                        val s = buf[i] / 32768.0
                        sumSq += s * s
                        val absV = abs(buf[i].toInt())
                        if (absV > peak) peak = absV
                    }
                    val rms = sqrt(sumSq / read).toFloat()
                    if (rms >= bargeInThreshold && peak > 4400) {
                        hotFrames++
                        if (hotFrames >= 7) { // ~280ms sustained loud speech
                            onSpeechBargeIn()
                            break
                        }
                    } else {
                        hotFrames = (hotFrames - 1).coerceAtLeast(0)
                    }
                }
            } catch (_: Throwable) {
                // Gracefully degrade if audio hardware is busy
            } finally {
                runCatching { aec?.release() }
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    fun stopBargeInMonitor() {
        bargeInJob?.cancel()
        bargeInJob = null
    }
}
