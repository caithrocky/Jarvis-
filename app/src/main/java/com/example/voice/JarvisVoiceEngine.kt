package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.example.data.remote.NvidiaMagpieTtsClient
import com.example.data.remote.SynthesizedAudio
import com.example.domain.model.OrbState
import com.example.domain.model.VoicePersonality
import com.example.security.SecretsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

enum class VoicePipelinePhase {
    STOPPED,
    WAKE_WORD_STANDBY,
    ACTIVE_COMMAND_CAPTURE,
    SPEAKING_RESPONSE
}

class JarvisVoiceEngine(
    private val context: Context,
    private val magpieTtsClient: NvidiaMagpieTtsClient? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile
    private var activeAudioTrack: AudioTrack? = null
    private var activeNeuralJob: Job? = null
    private val speechGeneration = AtomicInteger(0)

    private var onCommandCaptured: ((String) -> Unit)? = null
    private var onWakeDetected: (() -> Unit)? = null

    private var alwaysOnEnabled = false
    private var continuousSession = false
    private var useNeuralNimTts = true
    private var currentPersonality = VoicePersonality.JARVIS_CLASSIC
    private var currentSpeed = VoicePersonality.JARVIS_CLASSIC.rate
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _phase = MutableStateFlow(VoicePipelinePhase.STOPPED)
    val phase: StateFlow<VoicePipelinePhase> = _phase.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _bluetoothConnected = MutableStateFlow(false)
    val bluetoothConnected: StateFlow<Boolean> = _bluetoothConnected.asStateFlow()

    private val _statusDetail = MutableStateFlow("Microphone standby")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    init {
        initTts()
        refreshBluetoothState()
    }

    fun setCallbacks(
        onWake: () -> Unit,
        onCommand: (String) -> Unit
    ) {
        onWakeDetected = onWake
        onCommandCaptured = onCommand
    }

    fun configureVoice(personality: VoicePersonality, speed: Float, useNvidiaMagpie: Boolean = useNeuralNimTts) {
        currentPersonality = personality
        currentSpeed = speed
        useNeuralNimTts = useNvidiaMagpie
        tts?.setPitch(personality.pitch)
        tts?.setSpeechRate(speed)
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun refreshBluetoothState() {
        val btActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoAvailableOffCall
        }
        _bluetoothConnected.value = btActive
    }

    fun startAlwaysOnWakeWordLoop() {
        if (!hasMicPermission()) {
            _statusDetail.value = "Microphone permission required for wake-word detection"
            _phase.value = VoicePipelinePhase.STOPPED
            return
        }
        alwaysOnEnabled = true
        continuousSession = false
        _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
        _statusDetail.value = "Listening locally for \"Hey JARVIS\""
        mainHandler.post { startRecognizerSession() }
    }

    fun startSingleCommandCapture() {
        if (!hasMicPermission()) {
            _statusDetail.value = "Microphone permission required"
            return
        }
        interruptSpeech()
        continuousSession = true
        _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
        _statusDetail.value = "Listening to your request, Sir..."
        mainHandler.post { startRecognizerSession() }
    }

    fun stopAllListening() {
        alwaysOnEnabled = false
        continuousSession = false
        _phase.value = VoicePipelinePhase.STOPPED
        _rmsLevel.value = 0f
        _partialTranscript.value = ""
        _statusDetail.value = "Always-on listening paused"
        mainHandler.post {
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    fun speak(text: String, followUpListen: Boolean = true) {
        if (text.isBlank()) return
        val token = speechGeneration.incrementAndGet()
        activeNeuralJob?.cancel()
        stopAudioTrack()

        if (useNeuralNimTts && magpieTtsClient != null && SecretsConfig.hasNvidiaNim) {
            mainHandler.post { runCatching { speechRecognizer?.cancel() } }
            _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
            _statusDetail.value = "JARVIS speaking (${currentPersonality.nimVoiceName.substringAfterLast(".")})"

            activeNeuralJob = engineScope.launch {
                val synthResult = magpieTtsClient.synthesize(text, currentPersonality)
                if (!isActive || speechGeneration.get() != token) return@launch

                val audio = synthResult.getOrNull()
                if (audio != null && audio.pcmBytes.isNotEmpty()) {
                    playPcmAudio(audio, token, followUpListen)
                } else {
                    // Graceful fallback to on-device British/English TTS
                    speakWithDeviceTts(text, token, followUpListen)
                }
            }
            return
        }

        speakWithDeviceTts(text, token, followUpListen)
    }

    private fun playPcmAudio(audio: SynthesizedAudio, token: Int, followUpListen: Boolean) {
        mainHandler.post { requestAudioFocus() }

        val channelMask = if (audio.channels >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val encoding = if (audio.bitsPerSample == 8) {
            AudioFormat.ENCODING_PCM_8BIT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val minBuf = AudioTrack.getMinBufferSize(audio.sampleRateHz, channelMask, encoding)
            .coerceAtLeast(4096)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(audio.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null) {
            speakWithDeviceTts("At your service, Sir.", token, followUpListen)
            return
        }

        activeAudioTrack = track
        runCatching {
            track.play()
            val frameSize = 2048
            var offset = 0
            while (offset < audio.pcmBytes.size && speechGeneration.get() == token) {
                val len = minOf(frameSize, audio.pcmBytes.size - offset)
                val written = track.write(audio.pcmBytes, offset, len)
                if (written <= 0) break
                _rmsLevel.value = computePcmRms(audio.pcmBytes, offset, len)
                offset += written
            }
            if (speechGeneration.get() == token) {
                track.stop()
            }
        }
        runCatching { track.release() }
        if (activeAudioTrack === track) activeAudioTrack = null

        if (speechGeneration.get() == token) {
            onSpeechCompleted(followUpListen)
        }
    }

    private fun computePcmRms(bytes: ByteArray, offset: Int, length: Int): Float {
        if (length < 2) return 0.2f
        val samples = length / 2
        var sumSq = 0.0
        val bb = ByteBuffer.wrap(bytes, offset, samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val s = bb.short / 32768.0
            sumSq += s * s
        }
        val rms = sqrt(sumSq / samples.coerceAtLeast(1)).toFloat()
        return (rms * 3.4f).coerceIn(0.12f, 1.0f)
    }

    private fun speakWithDeviceTts(text: String, token: Int, followUpListen: Boolean) {
        if (speechGeneration.get() != token) return
        if (!ttsReady) {
            onSpeechCompleted(followUpListen)
            return
        }
        val spokenText = magpieTtsClient?.sanitizeForSpeech(text) ?: text
        mainHandler.post {
            if (speechGeneration.get() != token) return@post
            runCatching { speechRecognizer?.cancel() }
            requestAudioFocus()
            _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
            _statusDetail.value = "JARVIS speaking (tap or speak to interrupt)"
            tts?.setPitch(currentPersonality.pitch)
            tts?.setSpeechRate(currentSpeed)
            val utteranceId = UUID.randomUUID().toString()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    _rmsLevel.value = 0.72f
                }

                override fun onDone(id: String?) {
                    if (speechGeneration.get() == token) {
                        onSpeechCompleted(followUpListen)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    _rmsLevel.value = 0f
                    abandonAudioFocus()
                    _phase.value = if (alwaysOnEnabled) VoicePipelinePhase.WAKE_WORD_STANDBY else VoicePipelinePhase.STOPPED
                }
            })
            tts?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private fun onSpeechCompleted(followUpListen: Boolean) {
        _rmsLevel.value = 0f
        mainHandler.post {
            abandonAudioFocus()
            if (alwaysOnEnabled) {
                _phase.value = if (followUpListen) VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE else VoicePipelinePhase.WAKE_WORD_STANDBY
                _statusDetail.value = if (followUpListen) "Follow-up listening, Sir..." else "Listening locally for \"Hey JARVIS\""
                scheduleNextListen(250L)
            } else {
                _phase.value = VoicePipelinePhase.STOPPED
                _statusDetail.value = "Ready"
            }
        }
    }

    fun interruptSpeech() {
        speechGeneration.incrementAndGet()
        activeNeuralJob?.cancel()
        stopAudioTrack()
        mainHandler.post {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
            _rmsLevel.value = 0f
            abandonAudioFocus()
            if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) {
                _phase.value = if (alwaysOnEnabled) VoicePipelinePhase.WAKE_WORD_STANDBY else VoicePipelinePhase.STOPPED
                _statusDetail.value = "Ready"
            }
        }
    }

    private fun stopAudioTrack() {
        val track = activeAudioTrack
        activeAudioTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    fun suggestedOrbState(isAiProcessing: Boolean, hasCriticalAlert: Boolean): OrbState {
        return when {
            hasCriticalAlert -> OrbState.ALERT
            isAiProcessing -> OrbState.PROCESSING
            _phase.value == VoicePipelinePhase.SPEAKING_RESPONSE -> OrbState.SPEAKING
            _phase.value == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE ||
                _phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY -> OrbState.LISTENING
            else -> OrbState.IDLE
        }
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Prefer British English (en-GB) for JARVIS fallback if installed on device
                val ukResult = tts?.setLanguage(Locale.UK)
                if (ukResult == TextToSpeech.LANG_MISSING_DATA || ukResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                } else {
                    val preferredUkVoice = runCatching {
                        tts?.voices?.firstOrNull { voice ->
                            voice.locale.language == "en" &&
                                voice.locale.country == "GB" &&
                                !voice.isNetworkConnectionRequired &&
                                voice.name.contains("male", ignoreCase = true)
                        } ?: tts?.voices?.firstOrNull { voice ->
                            voice.locale.language == "en" &&
                                voice.locale.country == "GB" &&
                                !voice.isNetworkConnectionRequired
                        }
                    }.getOrNull()
                    if (preferredUkVoice != null) {
                        runCatching { tts?.voice = preferredUkVoice }
                    }
                }
                tts?.setPitch(currentPersonality.pitch)
                tts?.setSpeechRate(currentSpeed)
                ttsReady = true
            }
        }
    }

    private fun startRecognizerSession() {
        if (!hasMicPermission()) return
        if (_phase.value == VoicePipelinePhase.STOPPED) return

        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = createBestSpeechRecognizer().apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _partialTranscript.value = ""
                }

                override fun onBeginningOfSpeech() {
                    if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) {
                        interruptSpeech()
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                    _rmsLevel.value = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _rmsLevel.value = 0.1f
                }

                override fun onError(error: Int) {
                    _rmsLevel.value = 0f
                    if (alwaysOnEnabled && _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                        val backoffMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 450L
                        scheduleNextListen(backoffMs)
                    } else if (!alwaysOnEnabled) {
                        _phase.value = VoicePipelinePhase.STOPPED
                        _statusDetail.value = "Tap orb or speak wake word"
                    }
                }

                override fun onResults(results: Bundle?) {
                    _rmsLevel.value = 0f
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    handleRecognizedText(text, isFinal = true)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        _partialTranscript.value = text
                        if (_phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY && matchesWakePhrase(text)) {
                            _statusDetail.value = "Wake phrase recognized!"
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        }
        runCatching { speechRecognizer?.startListening(intent) }
    }

    private fun createBestSpeechRecognizer(): SpeechRecognizer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun handleRecognizedText(text: String, isFinal: Boolean) {
        if (!isFinal) return
        _partialTranscript.value = ""

        if (text.isBlank()) {
            if (alwaysOnEnabled) scheduleNextListen(350L) else _phase.value = VoicePipelinePhase.STOPPED
            return
        }

        if (_phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY) {
            if (matchesWakePhrase(text)) {
                val remainder = stripWakePhrase(text)
                if (remainder.isNotBlank()) {
                    _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                    onCommandCaptured?.invoke(remainder)
                } else {
                    _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                    onWakeDetected?.invoke()
                    speak("At your service, Sir. What would you like me to analyze?", followUpListen = true)
                }
            } else if (alwaysOnEnabled) {
                scheduleNextListen(300L)
            }
            return
        }

        if (_phase.value == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE) {
            continuousSession = false
            onCommandCaptured?.invoke(text)
        }
    }

    private fun matchesWakePhrase(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return lower.contains("hey jarvis") ||
            lower.contains("ok jarvis") ||
            lower.startsWith("jarvis") ||
            lower.contains("hey service")
    }

    private fun stripWakePhrase(text: String): String {
        return text
            .replace(Regex("(?i)\\b(hey|ok)\\s+(jarvis|service)\\b[:,]?\\s*"), "")
            .replace(Regex("(?i)^jarvis[:,]?\\s*"), "")
            .trim()
    }

    private fun scheduleNextListen(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (alwaysOnEnabled && _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                startRecognizerSession()
            }
        }, delayMs)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    fun release() {
        stopAllListening()
        interruptSpeech()
        engineScope.cancel()
        tts?.shutdown()
    }
}
