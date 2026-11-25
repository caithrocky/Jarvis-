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
    @Volatile
    private var ttsReady = false
    @Volatile
    private var ttsInitFailed = false
    @Volatile
    private var forceStandardRecognizer = false

    private data class PendingUtterance(
        val text: String,
        val token: Int,
        val followUpListen: Boolean
    )

    @Volatile
    private var pendingUtterance: PendingUtterance? = null
    private var pendingListenRunnable: Runnable? = null

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

    private val _statusDetail = MutableStateFlow("Tap the orb to speak or say \"Hey JARVIS\"")
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
        val personalityChanged = currentPersonality != personality
        currentPersonality = personality
        currentSpeed = speed
        useNeuralNimTts = useNvidiaMagpie
        tts?.setPitch(personality.pitch)
        tts?.setSpeechRate(speed)
        if (personalityChanged && ttsReady) {
            mainHandler.post { applyPreferredDeviceVoice() }
        }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isMediaVolumeZero(): Boolean {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return current <= 0
    }

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
        _statusDetail.value = "Listening for \"Hey JARVIS\""
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
        _statusDetail.value = "Listening, Sir..."
        mainHandler.post { startRecognizerSession() }
    }

    fun stopAllListening() {
        alwaysOnEnabled = false
        continuousSession = false
        pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingListenRunnable = null
        _phase.value = VoicePipelinePhase.STOPPED
        _rmsLevel.value = 0f
        _partialTranscript.value = ""
        _statusDetail.value = "Tap the orb to speak"
        mainHandler.post {
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    fun speak(text: String, followUpListen: Boolean = true) {
        val clean = sanitizeSpeechInput(text)
        if (clean.isBlank()) return

        val token = speechGeneration.incrementAndGet()
        activeNeuralJob?.cancel()
        pendingUtterance = null
        stopAudioTrack()

        if (useNeuralNimTts && magpieTtsClient != null && SecretsConfig.hasNvidiaNim) {
            mainHandler.post { runCatching { speechRecognizer?.cancel() } }
            _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
            _statusDetail.value = "Speaking (${currentPersonality.nimVoiceName.substringAfterLast(".")})"

            activeNeuralJob = engineScope.launch {
                val synthResult = magpieTtsClient.synthesize(clean, currentPersonality)
                if (!isActive || speechGeneration.get() != token) return@launch

                val audio = synthResult.getOrNull()
                if (audio != null && audio.pcmBytes.isNotEmpty()) {
                    playPcmAudio(audio, clean, token, followUpListen)
                } else {
                    // Fallback to device TTS with the exact reply text
                    speakWithDeviceTts(clean, token, followUpListen)
                }
            }
            return
        }

        speakWithDeviceTts(clean, token, followUpListen)
    }

    private fun playPcmAudio(
        audio: SynthesizedAudio,
        originalText: String,
        token: Int,
        followUpListen: Boolean
    ) {
        requestAudioFocus()

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
        val bytesPerSample = (audio.bitsPerSample / 8).coerceAtLeast(1)
        val frameBytes = (audio.channels.coerceAtLeast(1) * bytesPerSample).coerceAtLeast(1)
        val totalFrames = (audio.pcmBytes.size / frameBytes).coerceAtLeast(1)
        val minBuf = AudioTrack.getMinBufferSize(audio.sampleRateHz, channelMask, encoding)
            .coerceAtLeast(4096)

        val useStatic = audio.pcmBytes.size <= 1_500_000
        val track = runCatching {
            val bufSize = if (useStatic) audio.pcmBytes.size.coerceAtLeast(minBuf) else minBuf
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
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(if (useStatic) AudioTrack.MODE_STATIC else AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null) {
            speakWithDeviceTts(originalText, token, followUpListen)
            return
        }

        activeAudioTrack = track
        var playbackFailed = false
        runCatching {
            if (useStatic) {
                var writtenTotal = 0
                while (writtenTotal < audio.pcmBytes.size && speechGeneration.get() == token) {
                    val w = track.write(audio.pcmBytes, writtenTotal, audio.pcmBytes.size - writtenTotal)
                    if (w <= 0) break
                    writtenTotal += w
                }
                if (writtenTotal <= 0) {
                    playbackFailed = true
                } else {
                    track.play()
                    val startedAt = System.currentTimeMillis()
                    val expectedDurationMs = (totalFrames * 1000L / audio.sampleRateHz.coerceAtLeast(8000)) + 250L
                    while (speechGeneration.get() == token) {
                        val playedFrames = runCatching { track.playbackHeadPosition }.getOrDefault(totalFrames)
                        if (playedFrames >= totalFrames - 32) break
                        if (System.currentTimeMillis() - startedAt > expectedDurationMs + 1200L) break

                        val byteOffset = (playedFrames * frameBytes).coerceIn(0, (audio.pcmBytes.size - 2).coerceAtLeast(0))
                        val winLen = minOf(1024, audio.pcmBytes.size - byteOffset)
                        if (winLen >= 2) {
                            _rmsLevel.value = computePcmRms(audio.pcmBytes, byteOffset, winLen)
                        }
                        Thread.sleep(40L)
                    }
                }
            } else {
                track.play()
                val chunk = 2048
                var offset = 0
                while (offset < audio.pcmBytes.size && speechGeneration.get() == token) {
                    val len = minOf(chunk, audio.pcmBytes.size - offset)
                    val written = track.write(audio.pcmBytes, offset, len)
                    if (written <= 0) break
                    _rmsLevel.value = computePcmRms(audio.pcmBytes, offset, len)
                    offset += written
                }
                // Wait for remaining queued frames to finish playing out of speaker
                val startDrain = System.currentTimeMillis()
                while (speechGeneration.get() == token) {
                    val head = runCatching { track.playbackHeadPosition }.getOrDefault(totalFrames)
                    if (head >= totalFrames - 64) break
                    if (System.currentTimeMillis() - startDrain > 2500L) break
                    Thread.sleep(40L)
                }
            }
            if (speechGeneration.get() == token) {
                runCatching { track.stop() }
            }
        }.onFailure {
            playbackFailed = true
        }

        runCatching { track.release() }
        if (activeAudioTrack === track) activeAudioTrack = null

        if (playbackFailed && speechGeneration.get() == token) {
            speakWithDeviceTts(originalText, token, followUpListen)
            return
        }

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
        return (rms * 3.4f).coerceIn(0.14f, 1.0f)
    }

    private fun speakWithDeviceTts(text: String, token: Int, followUpListen: Boolean) {
        if (speechGeneration.get() != token) return
        if (!ttsReady) {
            if (ttsInitFailed || tts == null) {
                pendingUtterance = PendingUtterance(text, token, followUpListen)
                ttsInitFailed = false
                mainHandler.post { initTts() }
            } else {
                pendingUtterance = PendingUtterance(text, token, followUpListen)
            }
            _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
            _statusDetail.value = "Preparing voice engine..."
            return
        }

        val spokenText = sanitizeSpeechInput(text)
        mainHandler.post {
            if (speechGeneration.get() != token) return@post
            runCatching { speechRecognizer?.cancel() }
            requestAudioFocus()
            _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
            _statusDetail.value = if (isMediaVolumeZero()) {
                "Speaking (Device media volume is 0)"
            } else {
                "JARVIS speaking (tap orb to interrupt)"
            }
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
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
                    if (speechGeneration.get() == token) {
                        onSpeechCompleted(followUpListen)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (speechGeneration.get() == token) {
                        onSpeechCompleted(followUpListen)
                    }
                }
            })
            val result = tts?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                onSpeechCompleted(followUpListen)
            }
        }
    }

    private fun onSpeechCompleted(followUpListen: Boolean) {
        _rmsLevel.value = 0f
        mainHandler.post {
            abandonAudioFocus()
            if (alwaysOnEnabled || (followUpListen && continuousSession)) {
                _phase.value = if (followUpListen) {
                    VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                } else {
                    VoicePipelinePhase.WAKE_WORD_STANDBY
                }
                _statusDetail.value = if (followUpListen) {
                    "Listening for follow-up, Sir..."
                } else {
                    "Listening for \"Hey JARVIS\""
                }
                scheduleNextListen(260L)
            } else {
                _phase.value = VoicePipelinePhase.STOPPED
                _statusDetail.value = "Tap orb to speak"
            }
        }
    }

    fun interruptSpeech() {
        speechGeneration.incrementAndGet()
        pendingUtterance = null
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
                _statusDetail.value = if (alwaysOnEnabled) "Listening for \"Hey JARVIS\"" else "Ready"
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
        runCatching { tts?.shutdown() }
        ttsReady = false
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                applyPreferredDeviceVoice()
                tts?.setPitch(currentPersonality.pitch)
                tts?.setSpeechRate(currentSpeed)
                ttsReady = true
                ttsInitFailed = false

                val queued = pendingUtterance
                pendingUtterance = null
                if (queued != null && speechGeneration.get() == queued.token) {
                    speakWithDeviceTts(queued.text, queued.token, queued.followUpListen)
                }
            } else {
                ttsReady = false
                ttsInitFailed = true
                val queued = pendingUtterance
                pendingUtterance = null
                if (queued != null && speechGeneration.get() == queued.token) {
                    _statusDetail.value = "Device speech engine unavailable"
                    onSpeechCompleted(queued.followUpListen)
                }
            }
        }
    }

    private fun applyPreferredDeviceVoice() {
        val engine = tts ?: return
        val wantFemale = currentPersonality == VoicePersonality.CALM_MENTOR
        val targetLocale = if (wantFemale) Locale.US else Locale.UK
        val langResult = engine.setLanguage(targetLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            val usResult = engine.setLanguage(Locale.US)
            if (usResult == TextToSpeech.LANG_MISSING_DATA || usResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
        }
        val matchingVoice = runCatching {
            val voices = engine.voices.orEmpty().filter { it.locale.language == "en" }
            val offline = voices.filter { !it.isNetworkConnectionRequired }.ifEmpty { voices }
            if (wantFemale) {
                offline.firstOrNull {
                    it.name.contains("female", ignoreCase = true) ||
                        it.name.contains("aria", ignoreCase = true) ||
                        it.name.contains("sfg", ignoreCase = true) ||
                        it.name.contains("tpf", ignoreCase = true)
                }
            } else {
                offline.firstOrNull {
                    it.locale.country == "GB" && it.name.contains("male", ignoreCase = true)
                } ?: offline.firstOrNull { it.locale.country == "GB" }
            }
        }.getOrNull()
        if (matchingVoice != null) {
            runCatching { engine.voice = matchingVoice }
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

                    // If on-device STT failed with missing model / server error, fall back to standard STT automatically
                    val onDeviceError = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_CLIENT
                    if (!forceStandardRecognizer && onDeviceError) {
                        forceStandardRecognizer = true
                        scheduleNextListen(220L)
                        return
                    }

                    if (alwaysOnEnabled && _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                        val backoffMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 420L
                        scheduleNextListen(backoffMs)
                    } else if (!alwaysOnEnabled) {
                        continuousSession = false
                        _phase.value = VoicePipelinePhase.STOPPED
                        _statusDetail.value = mapRecognizerError(error)
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
                            _statusDetail.value = "Yes, Sir..."
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val useOffline = !forceStandardRecognizer && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            if (useOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        }
        runCatching {
            speechRecognizer?.startListening(intent)
        }.onFailure {
            _statusDetail.value = "Speech recognizer unavailable on device"
            if (!alwaysOnEnabled) {
                _phase.value = VoicePipelinePhase.STOPPED
            }
        }
    }

    private fun createBestSpeechRecognizer(): SpeechRecognizer {
        return if (!forceStandardRecognizer &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun mapRecognizerError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — tap orb to speak"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone busy — tap orb to retry"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition offline — tap orb to retry"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice service busy — tap orb to retry"
        else -> "Tap orb to speak"
    }

    private fun handleRecognizedText(text: String, isFinal: Boolean) {
        if (!isFinal) return
        _partialTranscript.value = ""

        if (text.isBlank()) {
            if (alwaysOnEnabled) {
                scheduleNextListen(350L)
            } else {
                continuousSession = false
                _phase.value = VoicePipelinePhase.STOPPED
                _statusDetail.value = "Tap orb to speak"
            }
            return
        }

        if (_phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY) {
            if (matchesWakePhrase(text)) {
                val remainder = stripWakePhrase(text)
                onWakeDetected?.invoke()
                if (remainder.isNotBlank()) {
                    _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                    continuousSession = true
                    onCommandCaptured?.invoke(remainder)
                } else {
                    _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                    continuousSession = true
                    speak("Yes Sir, I'm listening. How can I help?", followUpListen = true)
                }
            } else if (alwaysOnEnabled) {
                scheduleNextListen(300L)
            }
            return
        }

        if (_phase.value == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE) {
            onCommandCaptured?.invoke(text)
        }
    }

    internal fun matchesWakePhrase(text: String): Boolean {
        val lower = text.lowercase(Locale.US).trim()
        return lower.contains("hey jarvis") ||
            lower.contains("ok jarvis") ||
            lower.contains("hi jarvis") ||
            lower.startsWith("jarvis") ||
            lower.contains("hey service")
    }

    internal fun stripWakePhrase(text: String): String {
        return text
            .replace(Regex("(?i)\\b(hey|ok|hi)\\s+(jarvis|service)\\b[:,]?\\s*"), "")
            .replace(Regex("(?i)^jarvis[:,]?\\s*"), "")
            .trim()
    }

    internal fun sanitizeSpeechInput(raw: String): String {
        return magpieTtsClient?.sanitizeForSpeech(raw) ?: raw
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`~#>]"), "")
            .replace(Regex("\\s*[•|]\\s*"), ", ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun scheduleNextListen(delayMs: Long) {
        pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if ((alwaysOnEnabled || continuousSession) && _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                startRecognizerSession()
            }
        }
        pendingListenRunnable = r
        mainHandler.postDelayed(r, delayMs)
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
