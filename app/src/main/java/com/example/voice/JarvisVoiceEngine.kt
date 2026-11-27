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
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.data.remote.NvidiaMagpieTtsClient
import com.example.data.remote.SynthesizedAudio
import com.example.domain.model.OrbState
import com.example.domain.model.TaskStage
import com.example.domain.model.VoicePersonality
import com.example.security.SecretsConfig
import com.example.security.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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
    private val magpieTtsClient: NvidiaMagpieTtsClient? = null,
    private val credentialStore: SecureCredentialStore? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var forceStandardRecognizer = true
    @Volatile
    private var earconMutedSystem = false
    @Volatile
    private var earconMutedNotif = false
    private var earconUnmuteRunnable: Runnable? = null

    private data class StreamingChunk(
        val text: String,
        val isFinal: Boolean,
        val followUpListen: Boolean
    )

    private var pendingListenRunnable: Runnable? = null

    @Volatile
    private var isRecognizerListening = false
    @Volatile
    private var lastRecognizerActivityMs = 0L
    private var watchdogJob: Job? = null
    private var standbyMonitorJob: Job? = null
    @Volatile
    private var activeStandbyAudioRecord: AudioRecord? = null
    @Volatile
    private var isPipeSessionActive = false
    @Volatile
    private var pipeAudioSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    @Volatile
    private var pipeSessionReceivedCallbacks = false
    @Volatile
    private var currentRecognizerIsPipe = false
    @Volatile
    private var activePipeReadFd: ParcelFileDescriptor? = null
    @Volatile
    private var lastSessionPartialTranscript = ""
    @Volatile
    private var lastActiveCommandEnteredAtMs = 0L
    @Volatile
    private var userFacingActive = false
    @Volatile
    private var followUpDirectTryActive = false

    @Volatile
    private var activeAudioTrack: AudioTrack? = null
    private var activeNeuralJob: Job? = null
    private var streamingSpeechChannel: Channel<StreamingChunk>? = null
    private var streamingSpeechToken: Int = -1
    private val speechGeneration = AtomicInteger(0)
    private val vad = VoiceActivityDetector()

    private var onCommandCaptured: ((String) -> Unit)? = null
    private var onWakeDetected: ((String) -> Unit)? = null
    private var onBargeInTriggered: (() -> Unit)? = null

    private var alwaysOnEnabled = false
    private var continuousSession = false
    private var useNeuralNimTts = true
    private var currentPersonality = VoicePersonality.JARVIS_CLASSIC
    private var currentSpeed = VoicePersonality.JARVIS_CLASSIC.rate
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var sessionChimePlayed = false

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _phase = MutableStateFlow(VoicePipelinePhase.STOPPED)
    val phase: StateFlow<VoicePipelinePhase> = _phase.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _bluetoothConnected = MutableStateFlow(false)
    val bluetoothConnected: StateFlow<Boolean> = _bluetoothConnected.asStateFlow()

    private val _statusDetail = MutableStateFlow("Always listening for \"Hey JARVIS\"")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    @Volatile
    private var fluToleranceEnabled = true
    @Volatile
    private var lastVerifiedOwnerAtMs = 0L

    private val _lastSpeakerState = MutableStateFlow<SpeakerMatchState?>(null)
    val lastSpeakerState: StateFlow<SpeakerMatchState?> = _lastSpeakerState.asStateFlow()

    private val _speakerConfidence = MutableStateFlow(0f)
    val speakerConfidence: StateFlow<Float> = _speakerConfidence.asStateFlow()

    private val _speakerStatusSummary = MutableStateFlow("Owner Voice ID: Not enrolled")
    val speakerStatusSummary: StateFlow<String> = _speakerStatusSummary.asStateFlow()

    init {
        refreshBluetoothState()
        refreshSpeakerEnrollmentLabel()
    }

    fun refreshSpeakerEnrollmentLabel() {
        val profile = credentialStore?.getOwnerVoiceProfile()
        _speakerStatusSummary.value = when {
            profile == null -> "Owner Voice ID: Not enrolled"
            profile.fluAdaptedCount > 0 ->
                "Enrolled (${profile.sampleCount} samples • ${profile.fluAdaptedCount} flu shifts learned)"
            else ->
                "Enrolled (${profile.sampleCount} samples • Baseline ${profile.baselinePitchHz.toInt()}Hz)"
        }
    }

    fun isOwnerVoiceEnrolled(): Boolean = credentialStore?.getOwnerVoiceProfile() != null

    fun isOwnerRecentlyVerified(maxAgeMs: Long = 180_000L): Boolean {
        if (!isOwnerVoiceEnrolled()) return true
        return System.currentTimeMillis() - lastVerifiedOwnerAtMs <= maxAgeMs
    }

    fun markOwnerVerifiedManually() {
        lastVerifiedOwnerAtMs = System.currentTimeMillis()
        _lastSpeakerState.value = SpeakerMatchState.VERIFIED_OWNER
        _speakerConfidence.value = 0.99f
    }

    fun clearOwnerVoiceProfile() {
        credentialStore?.clearOwnerVoiceProfile()
        lastVerifiedOwnerAtMs = 0L
        _lastSpeakerState.value = null
        _speakerConfidence.value = 0f
        refreshSpeakerEnrollmentLabel()
    }

    fun setFluToleranceEnabled(enabled: Boolean) {
        fluToleranceEnabled = enabled
    }

    fun setCallbacks(
        onWake: (String) -> Unit,
        onCommand: (String) -> Unit,
        onBargeIn: (() -> Unit)? = null
    ) {
        onWakeDetected = onWake
        onCommandCaptured = onCommand
        onBargeInTriggered = onBargeIn
    }

    fun setUserFacingActive(active: Boolean) {
        userFacingActive = active
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            vad.stopBargeInMonitor()
            stopContinuousStandbyMonitor()
            interruptSpeech()
            watchdogJob?.cancel()
            watchdogJob = null
            pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingListenRunnable = null
            isRecognizerListening = false
            _phase.value = VoicePipelinePhase.STOPPED
            _rmsLevel.value = 0f
            _partialTranscript.value = ""
            _statusDetail.value = "Microphone muted"
            mainHandler.post {
                closeActivePipeReadFd()
                runCatching { speechRecognizer?.cancel() }
                runCatching { speechRecognizer?.destroy() }
                speechRecognizer = null
            }
        } else {
            _statusDetail.value = if (alwaysOnEnabled) "Always listening for \"Hey JARVIS\"" else "Voice standby"
            if (alwaysOnEnabled && hasMicPermission()) {
                startAlwaysOnWakeWordLoop()
            }
        }
    }

    fun toggleMute(): Boolean {
        val next = !_isMuted.value
        setMuted(next)
        return next
    }

    fun configureVoice(personality: VoicePersonality, speed: Float, useNvidiaMagpie: Boolean = true) {
        currentPersonality = personality
        currentSpeed = speed
        // Neural Magpie TTS is strictly required; robotic Android TTS is permanently disabled.
        @Suppress("UNUSED_VARIABLE")
        val unused = useNvidiaMagpie
        useNeuralNimTts = true
    }

    fun ensureAlwaysListeningStandby() {
        if (_isMuted.value || !hasMicPermission()) return
        if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) return
        if (_phase.value == VoicePipelinePhase.STOPPED || standbyMonitorJob?.isActive != true && !isRecognizerListening) {
            startAlwaysOnWakeWordLoop()
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
        if (_isMuted.value) {
            _statusDetail.value = "Microphone muted"
            _phase.value = VoicePipelinePhase.STOPPED
            return
        }
        if (!hasMicPermission()) {
            _statusDetail.value = "Microphone permission required for wake-word detection"
            _phase.value = VoicePipelinePhase.STOPPED
            return
        }
        val alreadyWarm = alwaysOnEnabled &&
            _phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY &&
            (standbyMonitorJob?.isActive == true || isRecognizerListening)
        alwaysOnEnabled = true
        continuousSession = false
        sessionChimePlayed = false
        _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
        _statusDetail.value = "Always listening for \"Hey JARVIS\""
        startAlwaysOnWatchdog()
        if (!alreadyWarm) {
            mainHandler.post { ensureSpeechRecognizer() }
            startContinuousStandbyMonitor()
        }
    }

    fun startSingleCommandCapture(keepConversationLoop: Boolean = true, playChime: Boolean = false) {
        if (_isMuted.value) {
            _isMuted.value = false
        }
        if (!hasMicPermission()) {
            _statusDetail.value = "Microphone permission required"
            return
        }
        @Suppress("UNUSED_VARIABLE")
        val unusedPlayChime = playChime
        interruptSpeech()
        stopContinuousStandbyMonitor()
        continuousSession = keepConversationLoop
        followUpDirectTryActive = false
        _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
        lastActiveCommandEnteredAtMs = System.currentTimeMillis()
        _statusDetail.value = "Listening, Sir..."
        mainHandler.post { startDirectRecognizerSession() }
    }

    fun onCommandHandlingFinished(expectFollowUp: Boolean) {
        if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) return
        if (_isMuted.value) {
            _phase.value = VoicePipelinePhase.STOPPED
            return
        }
        followUpDirectTryActive = false
        if (alwaysOnEnabled) {
            _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
            continuousSession = expectFollowUp
            _statusDetail.value = if (expectFollowUp) {
                "Listening for follow-up, Sir..."
            } else {
                "Always listening for \"Hey JARVIS\""
            }
            startContinuousStandbyMonitor()
        } else {
            _phase.value = VoicePipelinePhase.STOPPED
            _statusDetail.value = "Voice standby"
        }
    }

    suspend fun enrollOwnerVoiceProfile(
        onPromptStep: (step: Int, phrase: String) -> Unit
    ): Result<SecureCredentialStore.OwnerVoiceProfile> = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            return@withContext Result.failure(IllegalStateException("Microphone permission required"))
        }
        val store = credentialStore
            ?: return@withContext Result.failure(IllegalStateException("Secure credential vault unavailable"))

        val phrases = listOf(
            "Hey JARVIS, recognize my voice under any condition.",
            "Open my screen controls and check today's schedule, Sir.",
            "Confirm owner identity lock and adapt smoothly to my voice."
        )

        val wasAlwaysOn = alwaysOnEnabled
        withContext(Dispatchers.Main) {
            stopAllListening()
            interruptSpeech()
        }
        Thread.sleep(220L)

        val captured = mutableListOf<VoiceSampleFeatures>()
        for ((index, phrase) in phrases.withIndex()) {
            withContext(Dispatchers.Main) {
                _statusDetail.value = "Voice ID sample ${index + 1}/3 — speak clearly..."
                onPromptStep(index + 1, phrase)
            }
            val features = capturePcmFeaturesBlocking(durationMs = 2400L)
            if (features != null) {
                captured.add(features)
            }
        }

        val profile = VoiceBiometricAnalyzer.buildInitialProfile(captured)
        return@withContext if (profile != null && captured.size >= 2) {
            store.saveOwnerVoiceProfile(profile)
            lastVerifiedOwnerAtMs = System.currentTimeMillis()
            _lastSpeakerState.value = SpeakerMatchState.VERIFIED_OWNER
            _speakerConfidence.value = 0.96f
            refreshSpeakerEnrollmentLabel()
            if (wasAlwaysOn) {
                withContext(Dispatchers.Main) { startAlwaysOnWakeWordLoop() }
            }
            Result.success(profile)
        } else {
            if (wasAlwaysOn) {
                withContext(Dispatchers.Main) { startAlwaysOnWakeWordLoop() }
            }
            Result.failure(IllegalStateException("Not enough clear speech captured (${captured.size}/3). Speak clearly in a quiet space and try again, Sir."))
        }
    }

    suspend fun verifyOwnerVoiceBurst(
        durationMs: Long = 2000L
    ): SpeakerVerificationResult = withContext(Dispatchers.IO) {
        val store = credentialStore
        val profile = store?.getOwnerVoiceProfile()
            ?: return@withContext SpeakerVerificationResult(
                state = SpeakerMatchState.VERIFIED_OWNER,
                confidence = 1f,
                formantSimilarity = 1f,
                pitchDeviationRatio = 0f,
                detailMessage = "No Voice ID enrolled yet — proceeding for Sir."
            )
        if (!hasMicPermission()) {
            return@withContext SpeakerVerificationResult(
                state = SpeakerMatchState.INSUFFICIENT_AUDIO,
                confidence = 0f,
                formantSimilarity = 0f,
                pitchDeviationRatio = 1f,
                detailMessage = "Microphone permission needed for Voice ID check, Sir."
            )
        }

        val resumeStandby = alwaysOnEnabled
        withContext(Dispatchers.Main) {
            pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            _statusDetail.value = "Verifying Sir's voiceprint..."
        }
        Thread.sleep(180L)

        val features = capturePcmFeaturesBlocking(durationMs)
        val result = VoiceBiometricAnalyzer.verifySpeaker(
            sample = features,
            profile = profile,
            fluToleranceEnabled = fluToleranceEnabled
        )

        if (features != null && (result.state == SpeakerMatchState.VERIFIED_OWNER || result.state == SpeakerMatchState.VERIFIED_VOCAL_SHIFT)) {
            val adapted = VoiceBiometricAnalyzer.adaptProfile(profile, features, result.state)
            store.saveOwnerVoiceProfile(adapted)
            lastVerifiedOwnerAtMs = System.currentTimeMillis()
            refreshSpeakerEnrollmentLabel()
        }

        _lastSpeakerState.value = result.state
        _speakerConfidence.value = result.confidence
        _statusDetail.value = result.detailMessage

        if (resumeStandby) {
            withContext(Dispatchers.Main) {
                _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
                scheduleNextListen(260L)
            }
        } else {
            withContext(Dispatchers.Main) {
                if (_phase.value == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE) {
                    scheduleNextListen(220L)
                }
            }
        }
        result
    }

    private fun capturePcmFeaturesBlocking(durationMs: Long): VoiceSampleFeatures? {
        val sampleRate = VoiceBiometricAnalyzer.SAMPLE_RATE_HZ
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val targetSamples = ((sampleRate * durationMs) / 1000L).toInt().coerceAtLeast(sampleRate)
        val pcmBuffer = ShortArray(targetSamples)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        }.getOrNull() ?: return null

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return null
        }

        return try {
            recorder.startRecording()
            var offset = 0
            val chunk = ShortArray(1024)
            val deadline = System.currentTimeMillis() + durationMs + 500L
            while (offset < targetSamples && System.currentTimeMillis() < deadline) {
                val read = recorder.read(chunk, 0, minOf(chunk.size, targetSamples - offset))
                if (read > 0) {
                    chunk.copyInto(pcmBuffer, offset, 0, read)
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = chunk[i] / 32768.0
                        sumSq += s * s
                    }
                    _rmsLevel.value = (sqrt(sumSq / read).toFloat() * 4.2f).coerceIn(0.05f, 1.0f)
                    offset += read
                } else {
                    break
                }
            }
            _rmsLevel.value = 0f
            val actual = if (offset < targetSamples) pcmBuffer.copyOf(offset) else pcmBuffer
            VoiceBiometricAnalyzer.extractFeatures(actual, sampleRate)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    fun stopAllListening() {
        alwaysOnEnabled = false
        continuousSession = false
        followUpDirectTryActive = false
        watchdogJob?.cancel()
        watchdogJob = null
        stopContinuousStandbyMonitor()
        pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingListenRunnable = null
        isRecognizerListening = false
        _phase.value = VoicePipelinePhase.STOPPED
        _rmsLevel.value = 0f
        _partialTranscript.value = ""
        _statusDetail.value = "Voice standby"
        mainHandler.post {
            closeActivePipeReadFd()
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    fun beginStreamingSpeechSession(): Int {
        val token = speechGeneration.incrementAndGet()
        activeNeuralJob?.cancel()
        streamingSpeechChannel?.close()
        stopAudioTrack()
        stopContinuousStandbyMonitor()
        pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingListenRunnable = null
        isRecognizerListening = false
        mainHandler.post {
            closeActivePipeReadFd()
            runCatching { speechRecognizer?.cancel() }
        }

        val ch = Channel<StreamingChunk>(Channel.UNLIMITED)
        streamingSpeechChannel = ch
        streamingSpeechToken = token
        _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
        _statusDetail.value = "Speaking (${currentPersonality.label})"
        startBargeInWatch(token)

        activeNeuralJob = engineScope.launch {
            var followUpAfterStream = false
            for (chunk in ch) {
                if (!isActive || speechGeneration.get() != token) break
                if (chunk.isFinal) {
                    followUpAfterStream = chunk.followUpListen
                    break
                }
                val cleanSentence = sanitizeSpeechInput(chunk.text)
                if (cleanSentence.isBlank()) continue

                if (magpieTtsClient != null && SecretsConfig.hasNvidiaNim) {
                    val synth = synthesizeWithMagpieRetry(cleanSentence, token)
                    if (!isActive || speechGeneration.get() != token) break
                    if (synth != null && synth.pcmBytes.isNotEmpty()) {
                        playPcmAudio(synth, token, followUpListen = false, completePipelineOnEnd = false)
                    } else {
                        _statusDetail.value = "Magpie voice unreachable — response shown on screen"
                    }
                } else {
                    _statusDetail.value = "NVIDIA NIM key needed for Magpie voice"
                }
            }
            if (speechGeneration.get() == token) {
                onSpeechCompleted(followUpAfterStream)
            }
        }
        return token
    }

    fun enqueueStreamingSpeechSentence(sessionToken: Int, sentence: String) {
        if (speechGeneration.get() != sessionToken || streamingSpeechToken != sessionToken) return
        val clean = sanitizeSpeechInput(sentence)
        if (clean.isBlank()) return
        streamingSpeechChannel?.trySend(StreamingChunk(clean, isFinal = false, followUpListen = false))
    }

    fun finishStreamingSpeechSession(sessionToken: Int, followUpListen: Boolean) {
        if (speechGeneration.get() != sessionToken || streamingSpeechToken != sessionToken) return
        streamingSpeechChannel?.trySend(StreamingChunk("", isFinal = true, followUpListen = followUpListen))
        streamingSpeechChannel?.close()
    }

    fun speak(text: String, followUpListen: Boolean = true) {
        val clean = sanitizeSpeechInput(text)
        if (clean.isBlank()) {
            onSpeechCompleted(followUpListen)
            return
        }

        val token = speechGeneration.incrementAndGet()
        activeNeuralJob?.cancel()
        streamingSpeechChannel?.close()
        streamingSpeechChannel = null
        stopAudioTrack()
        stopContinuousStandbyMonitor()

        pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingListenRunnable = null
        isRecognizerListening = false
        mainHandler.post {
            closeActivePipeReadFd()
            runCatching { speechRecognizer?.cancel() }
        }

        if (magpieTtsClient == null || !SecretsConfig.hasNvidiaNim) {
            _statusDetail.value = "NVIDIA NIM API key needed for JARVIS voice"
            onSpeechCompleted(followUpListen)
            return
        }

        _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
        _statusDetail.value = "Speaking (${currentPersonality.label})"
        startBargeInWatch(token)

        val chunks = splitIntoSpeechSentences(clean)
        activeNeuralJob = engineScope.launch {
            for (part in chunks) {
                if (!isActive || speechGeneration.get() != token) return@launch
                val audio = synthesizeWithMagpieRetry(part, token)
                if (!isActive || speechGeneration.get() != token) return@launch

                if (audio != null && audio.pcmBytes.isNotEmpty()) {
                    playPcmAudio(audio, token, followUpListen = false, completePipelineOnEnd = false)
                } else {
                    _statusDetail.value = "Magpie voice unreachable — response shown on screen"
                    break
                }
            }
            if (speechGeneration.get() == token) {
                onSpeechCompleted(followUpListen)
            }
        }
    }

    private suspend fun synthesizeWithMagpieRetry(sentence: String, token: Int): SynthesizedAudio? {
        val client = magpieTtsClient ?: return null
        val first = withTimeoutOrNull(12_000L) {
            client.synthesize(sentence, currentPersonality).getOrNull()
        }
        if (first != null && first.pcmBytes.isNotEmpty()) return first
        if (speechGeneration.get() != token) return null
        delay(200L)
        if (speechGeneration.get() != token) return null
        return withTimeoutOrNull(12_000L) {
            client.synthesize(sentence, currentPersonality).getOrNull()
        }
    }

    private fun splitIntoSpeechSentences(text: String): List<String> {
        if (text.length <= 260) return listOf(text)
        val rawParts = text.split(Regex("(?<=[.!?])\\s+"))
        val merged = mutableListOf<String>()
        val current = StringBuilder()
        for (part in rawParts) {
            val trimmed = part.trim()
            if (trimmed.isBlank()) continue
            if (current.length + trimmed.length > 260 && current.isNotEmpty()) {
                merged.add(current.toString().trim())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(trimmed)
        }
        if (current.isNotEmpty()) merged.add(current.toString().trim())
        return merged.ifEmpty { listOf(text) }
    }

    private fun startBargeInWatch(token: Int) {
        vad.startBargeInMonitor(
            scope = engineScope,
            hasMicPermission = hasMicPermission() && !_isMuted.value,
            isStillSpeaking = {
                speechGeneration.get() == token && _phase.value == VoicePipelinePhase.SPEAKING_RESPONSE
            },
            onSpeechBargeIn = {
                mainHandler.post {
                    if (speechGeneration.get() == token) {
                        onBargeInTriggered?.invoke()
                        interruptSpeech()
                        ensureAlwaysListeningStandby()
                    }
                }
            }
        )
    }

    private fun playPcmAudio(
        audio: SynthesizedAudio,
        token: Int,
        followUpListen: Boolean,
        completePipelineOnEnd: Boolean = true
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
            _statusDetail.value = "Audio output busy — text shown on screen"
            if (completePipelineOnEnd && speechGeneration.get() == token) {
                onSpeechCompleted(followUpListen)
            }
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
            _statusDetail.value = "Audio playback interrupted"
        }

        if (completePipelineOnEnd && speechGeneration.get() == token) {
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

    private fun onSpeechCompleted(followUpListen: Boolean) {
        vad.stopBargeInMonitor()
        _rmsLevel.value = 0f
        mainHandler.post {
            abandonAudioFocus()
            followUpDirectTryActive = false
            if (_isMuted.value) {
                _phase.value = VoicePipelinePhase.STOPPED
                _statusDetail.value = "Microphone muted"
            } else if (alwaysOnEnabled) {
                // Always stay inside silent AudioRecord standby with zero mic chime
                continuousSession = followUpListen
                _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
                _statusDetail.value = if (followUpListen) {
                    "Listening for follow-up, Sir..."
                } else {
                    "Always listening for \"Hey JARVIS\""
                }
                startContinuousStandbyMonitor()
            } else {
                _phase.value = VoicePipelinePhase.STOPPED
                _statusDetail.value = "Voice standby"
            }
        }
    }

    fun interruptSpeech() {
        speechGeneration.incrementAndGet()
        vad.stopBargeInMonitor()
        streamingSpeechChannel?.close()
        streamingSpeechChannel = null
        streamingSpeechToken = -1
        activeNeuralJob?.cancel()
        stopAudioTrack()
        mainHandler.post {
            _rmsLevel.value = 0f
            abandonAudioFocus()
            if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) {
                _phase.value = when {
                    _isMuted.value -> VoicePipelinePhase.STOPPED
                    alwaysOnEnabled -> VoicePipelinePhase.WAKE_WORD_STANDBY
                    else -> VoicePipelinePhase.STOPPED
                }
                _statusDetail.value = when {
                    _isMuted.value -> "Microphone muted"
                    alwaysOnEnabled -> "Always listening for \"Hey JARVIS\""
                    else -> "Ready"
                }
                if (alwaysOnEnabled && !_isMuted.value) {
                    startContinuousStandbyMonitor()
                }
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

    fun suggestedOrbState(
        isAiProcessing: Boolean,
        hasCriticalAlert: Boolean,
        taskStage: TaskStage = TaskStage.IDLE
    ): OrbState {
        return when {
            hasCriticalAlert -> OrbState.ALERT
            taskStage == TaskStage.UNDERSTANDING || taskStage == TaskStage.PLANNING -> OrbState.UNDERSTANDING
            taskStage == TaskStage.EXECUTING || taskStage == TaskStage.PERMISSION_CHECK || taskStage == TaskStage.VERIFYING -> OrbState.EXECUTING
            taskStage == TaskStage.COMPLETED -> OrbState.SUCCESS
            taskStage == TaskStage.FAILED -> OrbState.ERROR
            isAiProcessing -> OrbState.PROCESSING
            _phase.value == VoicePipelinePhase.SPEAKING_RESPONSE -> OrbState.SPEAKING
            _phase.value == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE ||
                _phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY -> OrbState.LISTENING
            else -> OrbState.IDLE
        }
    }

    private fun ensureSpeechRecognizer(): SpeechRecognizer {
        val existing = speechRecognizer
        if (existing != null) return existing
        return createBestSpeechRecognizer().apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isRecognizerListening = true
                    if (currentRecognizerIsPipe) pipeSessionReceivedCallbacks = true
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    lastSessionPartialTranscript = ""
                    _partialTranscript.value = ""
                }

                override fun onBeginningOfSpeech() {
                    if (currentRecognizerIsPipe) pipeSessionReceivedCallbacks = true
                    lastRecognizerActivityMs = System.currentTimeMillis()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    if (!currentRecognizerIsPipe && standbyMonitorJob?.isActive != true) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                        _rmsLevel.value = normalized
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (!currentRecognizerIsPipe) {
                        _rmsLevel.value = 0.08f
                    }
                }

                override fun onError(error: Int) {
                    val wasPipe = currentRecognizerIsPipe
                    val hadPipeCallbacks = pipeSessionReceivedCallbacks
                    val partialCandidate = lastSessionPartialTranscript.trim()

                    isRecognizerListening = false
                    currentRecognizerIsPipe = false
                    isPipeSessionActive = false
                    closeActivePipeReadFd()
                    if (!wasPipe) _rmsLevel.value = 0f
                    lastSessionPartialTranscript = ""

                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        alwaysOnEnabled = false
                        continuousSession = false
                        stopContinuousStandbyMonitor()
                        _phase.value = VoicePipelinePhase.STOPPED
                        _statusDetail.value = mapRecognizerError(error)
                        return
                    }

                    // If this phone's SpeechRecognizer rejected external audio pipe, permanently switch to direct mic handoff cleanly
                    if (wasPipe && !hadPipeCallbacks &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        pipeAudioSupported = false
                        stopContinuousStandbyMonitor()
                        recreateRecognizerAndRestartDirect(70L)
                        return
                    }

                    // Recover spoken partial transcript if recognizer ended on timeout/no-match after hearing words
                    if (partialCandidate.isNotBlank() &&
                        (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_AUDIO ||
                            error == SpeechRecognizer.ERROR_CLIENT)
                    ) {
                        handleRecognizedText(partialCandidate, isFinal = true)
                        return
                    }

                    val onDeviceError = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    if (!forceStandardRecognizer && onDeviceError) {
                        forceStandardRecognizer = true
                        recreateRecognizerAndRestartDirect(80L)
                        return
                    }

                    if (alwaysOnEnabled && !_isMuted.value && _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                        val wasFollowUpSilence = followUpDirectTryActive &&
                            (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        followUpDirectTryActive = false
                        if (wasFollowUpSilence) {
                            continuousSession = false
                        }
                        if (error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_AUDIO ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        ) {
                            runCatching { speechRecognizer?.cancel() }
                            runCatching { speechRecognizer?.destroy() }
                            speechRecognizer = null
                        }
                        // Return to continuous silent AudioRecord standby instead of looping SpeechRecognizer during silence
                        _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
                        _statusDetail.value = "Always listening for \"Hey JARVIS\""
                        startContinuousStandbyMonitor()
                    } else if (!alwaysOnEnabled) {
                        followUpDirectTryActive = false
                        continuousSession = false
                        _phase.value = VoicePipelinePhase.STOPPED
                        _statusDetail.value = mapRecognizerError(error)
                    }
                }

                override fun onResults(results: Bundle?) {
                    followUpDirectTryActive = false
                    isRecognizerListening = false
                    currentRecognizerIsPipe = false
                    isPipeSessionActive = false
                    closeActivePipeReadFd()
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    if (standbyMonitorJob?.isActive != true) _rmsLevel.value = 0f

                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                        .ifBlank { lastSessionPartialTranscript.trim() }
                    lastSessionPartialTranscript = ""
                    handleRecognizedText(text, isFinal = true)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (currentRecognizerIsPipe) pipeSessionReceivedCallbacks = true
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        lastSessionPartialTranscript = text
                        _partialTranscript.value = text
                        if (_phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY) {
                            val eval = ContextualWakeDetector.evaluate(text, inActiveConversation = false)
                            if (eval.intent == WakeIntent.DIRECT_WAKE) {
                                _statusDetail.value = "Yes, Sir..."
                            }
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }.also { speechRecognizer = it }
    }

    private fun closeActivePipeReadFd() {
        val fd = activePipeReadFd
        activePipeReadFd = null
        if (fd != null) {
            runCatching { fd.close() }
        }
    }

    private fun stopContinuousStandbyMonitor() {
        isPipeSessionActive = false
        standbyMonitorJob?.cancel()
        standbyMonitorJob = null
        activeStandbyAudioRecord?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        activeStandbyAudioRecord = null
    }

    private fun startContinuousStandbyMonitor() {
        if (!alwaysOnEnabled || _isMuted.value || !hasMicPermission()) return
        if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) return
        if (standbyMonitorJob?.isActive == true) return
        if (isRecognizerListening) return

        standbyMonitorJob = engineScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                mainHandler.post { startDirectRecognizerSession() }
                return@launch
            }

            val bufSize = maxOf(minBuf * 2, 16000)
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
            }.getOrNull()

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                mainHandler.post { startDirectRecognizerSession() }
                return@launch
            }

            activeStandbyAudioRecord = recorder
            // 960ms circular pre-roll ring buffer (16000 Hz * 2 bytes * 0.96s = 30720 bytes)
            val preRollCapacity = 30_720
            val ringBuffer = ByteArray(preRollCapacity)
            var ringWritePos = 0
            var ringFilled = 0

            val chunkShorts = 512 // 32ms per frame
            val shortBuf = ShortArray(chunkShorts)
            val byteBuf = ByteArray(chunkShorts * 2)

            var hotFrames = 0
            var pipeStream: ParcelFileDescriptor.AutoCloseOutputStream? = null
            var pipeUtteranceStartedAtMs = 0L
            var pipeLastVoiceMs = 0L
            var switchToDirectRecognizer = false

            try {
                recorder.startRecording()
                while (isActive &&
                    alwaysOnEnabled &&
                    !_isMuted.value &&
                    _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE
                ) {
                    val read = recorder.read(shortBuf, 0, chunkShorts)
                    if (read <= 0) {
                        if (isPipeSessionActive) {
                            runCatching { pipeStream?.close() }
                            pipeStream = null
                            isPipeSessionActive = false
                            pipeAudioSupported = false
                        }
                        break
                    }

                    var sumSq = 0.0
                    var peak = 0
                    val byteLen = read * 2
                    for (i in 0 until read) {
                        val sVal = shortBuf[i].toInt()
                        val absV = if (sVal < 0) -sVal else sVal
                        if (absV > peak) peak = absV
                        val norm = sVal / 32768.0
                        sumSq += norm * norm
                        byteBuf[i * 2] = (sVal and 0xFF).toByte()
                        byteBuf[i * 2 + 1] = ((sVal ushr 8) and 0xFF).toByte()
                    }
                    val rawRms = sqrt(sumSq / read).toFloat()
                    val displayRms = (rawRms * 4.2f).coerceIn(0.04f, 1.0f)

                    if (_phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                        _rmsLevel.value = displayRms
                    }

                    for (bIdx in 0 until byteLen) {
                        ringBuffer[ringWritePos] = byteBuf[bIdx]
                        ringWritePos = (ringWritePos + 1) % preRollCapacity
                        if (ringFilled < preRollCapacity) ringFilled++
                    }

                    val activePipe = pipeStream
                    if (isPipeSessionActive && activePipe != null) {
                        val writtenOk = runCatching {
                            activePipe.write(byteBuf, 0, byteLen)
                            true
                        }.getOrDefault(false)

                        val now = System.currentTimeMillis()
                        if (vad.isSpeechOverThreshold(rawRms, peak) || peak >= 750) {
                            pipeLastVoiceMs = now
                        }
                        val elapsed = now - pipeUtteranceStartedAtMs
                        val silenceGap = now - pipeLastVoiceMs
                        val shouldFinishUtterance = !writtenOk ||
                            !isPipeSessionActive ||
                            (elapsed >= 550L && silenceGap >= 1300L) ||
                            elapsed >= 11_000L

                        if (shouldFinishUtterance) {
                            runCatching { activePipe.flush() }
                            runCatching { activePipe.close() }
                            pipeStream = null
                            isPipeSessionActive = false
                            hotFrames = 0
                        }
                        continue
                    }

                    if (isRecognizerListening) {
                        break
                    }

                    vad.updateAmbientRms(rawRms)

                    if (vad.isSpeechOverThreshold(rawRms, peak)) {
                        hotFrames++
                        if (hotFrames >= 2) {
                            hotFrames = 0
                            if (pipeAudioSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val pipeStarted = runCatching {
                                    val pipe = ParcelFileDescriptor.createPipe()
                                    val readFd = pipe[0]
                                    val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                                    if (ringFilled > 0) {
                                        if (ringFilled < preRollCapacity) {
                                            out.write(ringBuffer, 0, ringFilled)
                                        } else {
                                            val firstPart = preRollCapacity - ringWritePos
                                            if (firstPart > 0) out.write(ringBuffer, ringWritePos, firstPart)
                                            if (ringWritePos > 0) out.write(ringBuffer, 0, ringWritePos)
                                        }
                                    }
                                    out.flush()
                                    pipeStream = out
                                    isPipeSessionActive = true
                                    pipeSessionReceivedCallbacks = false
                                    pipeUtteranceStartedAtMs = System.currentTimeMillis()
                                    pipeLastVoiceMs = pipeUtteranceStartedAtMs
                                    mainHandler.post { startPipeRecognizerSession(readFd) }
                                    true
                                }.getOrDefault(false)

                                if (!pipeStarted) {
                                    pipeAudioSupported = false
                                    switchToDirectRecognizer = true
                                    break
                                }
                            } else {
                                switchToDirectRecognizer = true
                                break
                            }
                        }
                    } else {
                        hotFrames = 0
                    }
                }
            } catch (_: Throwable) {
                // Recover cleanly via watchdog/handoff
            } finally {
                runCatching { pipeStream?.close() }
                isPipeSessionActive = false
                if (activeStandbyAudioRecord === recorder) {
                    activeStandbyAudioRecord = null
                }
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }

            if (switchToDirectRecognizer &&
                alwaysOnEnabled &&
                !_isMuted.value &&
                _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE
            ) {
                mainHandler.post { startDirectRecognizerSession() }
            }
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun buildRecognizerIntent(readFd: ParcelFileDescriptor? = null): Intent {
        val useOfflineOnly = !isNetworkConnected()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            if (useOfflineOnly) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2400L)
            if (readFd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            }
        }
    }

    private fun startPipeRecognizerSession(readFd: ParcelFileDescriptor) {
        if (!hasMicPermission() || _isMuted.value || _phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) {
            runCatching { readFd.close() }
            isPipeSessionActive = false
            return
        }
        if (isRecognizerListening) {
            runCatching { readFd.close() }
            isPipeSessionActive = false
            return
        }
        closeActivePipeReadFd()
        activePipeReadFd = readFd
        currentRecognizerIsPipe = true
        pipeSessionReceivedCallbacks = false
        lastSessionPartialTranscript = ""

        val recognizer = ensureSpeechRecognizer()
        val intent = buildRecognizerIntent(readFd)
        isRecognizerListening = true
        lastRecognizerActivityMs = System.currentTimeMillis()
        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            isRecognizerListening = false
            currentRecognizerIsPipe = false
            isPipeSessionActive = false
            closeActivePipeReadFd()
            pipeAudioSupported = false
            stopContinuousStandbyMonitor()
            recreateRecognizerAndRestartDirect(70L)
        }
    }

    private fun recreateRecognizerAndRestartDirect(delayMs: Long) {
        isRecognizerListening = false
        currentRecognizerIsPipe = false
        closeActivePipeReadFd()
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        scheduleNextListen(delayMs)
    }

    private fun muteSystemEarconBriefly() {
        earconUnmuteRunnable?.let { mainHandler.removeCallbacks(it) }
        if (!earconMutedSystem && !audioManager.isStreamMute(AudioManager.STREAM_SYSTEM)) {
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                earconMutedSystem = true
            }
        }
        if (!earconMutedNotif && !audioManager.isStreamMute(AudioManager.STREAM_NOTIFICATION)) {
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                earconMutedNotif = true
            }
        }
        val restore = Runnable { restoreSystemEarconIfMuted() }
        earconUnmuteRunnable = restore
        mainHandler.postDelayed(restore, 380L)
    }

    private fun restoreSystemEarconIfMuted() {
        earconUnmuteRunnable?.let { mainHandler.removeCallbacks(it) }
        earconUnmuteRunnable = null
        if (earconMutedSystem) {
            runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0) }
            earconMutedSystem = false
        }
        if (earconMutedNotif) {
            runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0) }
            earconMutedNotif = false
        }
    }

    private fun startDirectRecognizerSession() {
        if (!hasMicPermission() || _isMuted.value) return
        if (_phase.value == VoicePipelinePhase.STOPPED || _phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) return
        if (isRecognizerListening) return

        stopContinuousStandbyMonitor()
        currentRecognizerIsPipe = false
        lastSessionPartialTranscript = ""

        val recognizer = ensureSpeechRecognizer()
        val intent = buildRecognizerIntent(readFd = null)
        isRecognizerListening = true
        lastRecognizerActivityMs = System.currentTimeMillis()
        muteSystemEarconBriefly()
        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            isRecognizerListening = false
            restoreSystemEarconIfMuted()
            if (alwaysOnEnabled && !_isMuted.value) {
                _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
                startContinuousStandbyMonitor()
            } else {
                _statusDetail.value = "Speech recognizer unavailable on device"
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
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Always listening — ready when you speak"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone busy — retrying..."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition offline — retrying..."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice service busy — retrying..."
        else -> "Voice standby"
    }

    private fun handleRecognizedText(text: String, isFinal: Boolean) {
        if (!isFinal || _isMuted.value) return
        followUpDirectTryActive = false
        _partialTranscript.value = ""

        if (text.isBlank()) {
            if (alwaysOnEnabled) {
                _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
                _statusDetail.value = "Always listening for \"Hey JARVIS\""
                startContinuousStandbyMonitor()
            } else {
                continuousSession = false
                sessionChimePlayed = false
                _phase.value = VoicePipelinePhase.STOPPED
                _statusDetail.value = "Voice standby"
            }
            return
        }

        if (ContextualWakeDetector.isBargeInCommand(text) && _phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) {
            interruptSpeech()
            onBargeInTriggered?.invoke()
            startSingleCommandCapture(keepConversationLoop = true, playChime = false)
            return
        }

        if (_phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY) {
            val wakeEval = ContextualWakeDetector.evaluate(text, inActiveConversation = false)
            when (wakeEval.intent) {
                WakeIntent.CASUAL_MENTION -> {
                    if (alwaysOnEnabled) startContinuousStandbyMonitor()
                }
                WakeIntent.DIRECT_WAKE -> {
                    val remainder = wakeEval.remainder
                    onWakeDetected?.invoke(remainder)
                    if (remainder.isNotBlank()) {
                        _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                        lastActiveCommandEnteredAtMs = System.currentTimeMillis()
                        continuousSession = true
                        _statusDetail.value = "On it, Sir..."
                        onCommandCaptured?.invoke(remainder)
                    } else {
                        _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                        lastActiveCommandEnteredAtMs = System.currentTimeMillis()
                        continuousSession = true
                        speak("Yes Sir, I'm listening.", followUpListen = true)
                    }
                }
                WakeIntent.NONE -> {
                    if (userFacingActive || continuousSession) {
                        _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                        lastActiveCommandEnteredAtMs = System.currentTimeMillis()
                        continuousSession = true
                        _statusDetail.value = "On it, Sir..."
                        onCommandCaptured?.invoke(text)
                    } else if (alwaysOnEnabled) {
                        startContinuousStandbyMonitor()
                    }
                }
            }
            return
        }

        if (_phase.value == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE) {
            val eval = ContextualWakeDetector.evaluate(text, inActiveConversation = true)
            if (eval.intent == WakeIntent.CASUAL_MENTION && !userFacingActive) {
                onCommandHandlingFinished(expectFollowUp = true)
                return
            }
            val cleanedCommand = if (eval.intent == WakeIntent.DIRECT_WAKE && eval.remainder.isNotBlank()) {
                eval.remainder
            } else {
                text
            }
            lastActiveCommandEnteredAtMs = System.currentTimeMillis()
            _statusDetail.value = "On it, Sir..."
            onCommandCaptured?.invoke(cleanedCommand)
        }
    }

    internal fun matchesWakePhrase(text: String): Boolean =
        ContextualWakeDetector.evaluate(text, inActiveConversation = false).intent == WakeIntent.DIRECT_WAKE

    internal fun stripWakePhrase(text: String): String {
        val eval = ContextualWakeDetector.evaluate(text, inActiveConversation = true)
        return if (eval.intent != WakeIntent.NONE) eval.remainder else text.trim()
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
            pendingListenRunnable = null
            if ((alwaysOnEnabled || continuousSession) &&
                !_isMuted.value &&
                _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE
            ) {
                startDirectRecognizerSession()
            }
        }
        pendingListenRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun startAlwaysOnWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = engineScope.launch {
            while (isActive && alwaysOnEnabled) {
                kotlinx.coroutines.delay(1500L)
                if (!alwaysOnEnabled || _isMuted.value || !hasMicPermission()) continue
                if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) continue

                val now = System.currentTimeMillis()
                val stalledRecognizer = isRecognizerListening &&
                    (now - lastRecognizerActivityMs > 25_000L)
                val stuckActivePhase = _phase.value == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE &&
                    !isRecognizerListening &&
                    !isPipeSessionActive &&
                    (now - lastActiveCommandEnteredAtMs > 18_000L)

                when {
                    stalledRecognizer -> mainHandler.post {
                        isRecognizerListening = false
                        currentRecognizerIsPipe = false
                        closeActivePipeReadFd()
                        runCatching { speechRecognizer?.cancel() }
                        runCatching { speechRecognizer?.destroy() }
                        speechRecognizer = null
                        _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
                        startContinuousStandbyMonitor()
                    }
                    stuckActivePhase -> {
                        _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
                        _statusDetail.value = "Always listening for \"Hey JARVIS\""
                        startContinuousStandbyMonitor()
                    }
                    _phase.value == VoicePipelinePhase.WAKE_WORD_STANDBY &&
                        standbyMonitorJob?.isActive != true &&
                        !isRecognizerListening &&
                        pendingListenRunnable == null -> {
                        startContinuousStandbyMonitor()
                    }
                }
            }
        }
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
        restoreSystemEarconIfMuted()
        engineScope.cancel()
    }
}
