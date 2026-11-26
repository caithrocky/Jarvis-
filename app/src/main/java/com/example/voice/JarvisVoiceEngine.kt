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
import com.example.domain.model.TaskStage
import com.example.domain.model.VoicePersonality
import com.example.security.SecretsConfig
import com.example.security.SecureCredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val magpieTtsClient: NvidiaMagpieTtsClient? = null,
    private val credentialStore: SecureCredentialStore? = null
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

    private data class StreamingChunk(
        val text: String,
        val isFinal: Boolean,
        val followUpListen: Boolean
    )

    @Volatile
    private var pendingUtterance: PendingUtterance? = null
    private var pendingListenRunnable: Runnable? = null

    @Volatile
    private var isRecognizerListening = false
    @Volatile
    private var lastRecognizerActivityMs = 0L
    private var watchdogJob: Job? = null

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

    private val _statusDetail = MutableStateFlow("Tap the orb to speak or say \"Hey JARVIS\"")
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
        initTts()
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

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            vad.stopBargeInMonitor()
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
                runCatching { speechRecognizer?.cancel() }
                runCatching { speechRecognizer?.destroy() }
                speechRecognizer = null
            }
        } else {
            _statusDetail.value = if (alwaysOnEnabled) "Always listening for \"Hey JARVIS\"" else "Tap orb to speak"
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
            isRecognizerListening
        alwaysOnEnabled = true
        continuousSession = false
        sessionChimePlayed = false
        _phase.value = VoicePipelinePhase.WAKE_WORD_STANDBY
        _statusDetail.value = "Always listening for \"Hey JARVIS\""
        startAlwaysOnWatchdog()
        if (!alreadyWarm) {
            mainHandler.post { startRecognizerSession() }
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
        val enteringActiveSession = _phase.value != VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
        interruptSpeech()
        continuousSession = keepConversationLoop
        _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
        _statusDetail.value = "Listening, Sir..."
        if ((playChime || enteringActiveSession) && !sessionChimePlayed) {
            sessionChimePlayed = true
            JarvisChimePlayer.playActivationChime(engineScope)
        }
        mainHandler.post { startRecognizerSession() }
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
        watchdogJob?.cancel()
        watchdogJob = null
        pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingListenRunnable = null
        isRecognizerListening = false
        _phase.value = VoicePipelinePhase.STOPPED
        _rmsLevel.value = 0f
        _partialTranscript.value = ""
        _statusDetail.value = "Voice standby"
        mainHandler.post {
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    fun beginStreamingSpeechSession(): Int {
        val token = speechGeneration.incrementAndGet()
        activeNeuralJob?.cancel()
        streamingSpeechChannel?.close()
        pendingUtterance = null
        stopAudioTrack()
        pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingListenRunnable = null
        isRecognizerListening = false
        mainHandler.post {
            runCatching { speechRecognizer?.cancel() }
            if (tts?.isSpeaking == true) tts?.stop()
        }

        val ch = Channel<StreamingChunk>(Channel.UNLIMITED)
        streamingSpeechChannel = ch
        streamingSpeechToken = token
        _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
        _statusDetail.value = "Speaking (${currentPersonality.nimVoiceName.substringAfterLast(".")})"
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

                if (useNeuralNimTts && magpieTtsClient != null && SecretsConfig.hasNvidiaNim) {
                    val synth = magpieTtsClient.synthesize(cleanSentence, currentPersonality).getOrNull()
                    if (!isActive || speechGeneration.get() != token) break
                    if (synth != null && synth.pcmBytes.isNotEmpty()) {
                        playPcmAudio(synth, cleanSentence, token, followUpListen = false, completePipelineOnEnd = false)
                    } else {
                        speakDeviceTtsBlocking(cleanSentence, token)
                    }
                } else {
                    speakDeviceTtsBlocking(cleanSentence, token)
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
        if (clean.isBlank()) return

        val token = speechGeneration.incrementAndGet()
        activeNeuralJob?.cancel()
        streamingSpeechChannel?.close()
        streamingSpeechChannel = null
        pendingUtterance = null
        stopAudioTrack()

        if (useNeuralNimTts && magpieTtsClient != null && SecretsConfig.hasNvidiaNim) {
            pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingListenRunnable = null
            isRecognizerListening = false
            mainHandler.post { runCatching { speechRecognizer?.cancel() } }
            _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
            _statusDetail.value = "Speaking (${currentPersonality.nimVoiceName.substringAfterLast(".")})"
            startBargeInWatch(token)

            activeNeuralJob = engineScope.launch {
                val synthResult = magpieTtsClient.synthesize(clean, currentPersonality)
                if (!isActive || speechGeneration.get() != token) return@launch

                val audio = synthResult.getOrNull()
                if (audio != null && audio.pcmBytes.isNotEmpty()) {
                    playPcmAudio(audio, clean, token, followUpListen, completePipelineOnEnd = true)
                } else {
                    speakWithDeviceTts(clean, token, followUpListen)
                }
            }
            return
        }

        startBargeInWatch(token)
        speakWithDeviceTts(clean, token, followUpListen)
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
                        startSingleCommandCapture(keepConversationLoop = true, playChime = false)
                    }
                }
            }
        )
    }

    private suspend fun speakDeviceTtsBlocking(cleanText: String, token: Int) {
        if (speechGeneration.get() != token || !ttsReady) return
        val done = CompletableDeferred<Unit>()
        mainHandler.post {
            if (speechGeneration.get() != token) {
                done.complete(Unit)
                return@post
            }
            requestAudioFocus()
            _phase.value = VoicePipelinePhase.SPEAKING_RESPONSE
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
                override fun onStart(id: String?) { _rmsLevel.value = 0.68f }
                override fun onDone(id: String?) { done.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { done.complete(Unit) }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete(Unit) }
            })
            val res = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (res != TextToSpeech.SUCCESS) done.complete(Unit)
        }
        done.await()
        _rmsLevel.value = 0f
    }

    private fun playPcmAudio(
        audio: SynthesizedAudio,
        originalText: String,
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
            if (completePipelineOnEnd) {
                speakWithDeviceTts(originalText, token, followUpListen)
            }
            return
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
            pendingListenRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingListenRunnable = null
            isRecognizerListening = false
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
            if (result != TextToSpeech.SUCCESS) {
                onSpeechCompleted(followUpListen)
            }
        }
    }

    private fun onSpeechCompleted(followUpListen: Boolean) {
        vad.stopBargeInMonitor()
        _rmsLevel.value = 0f
        mainHandler.post {
            abandonAudioFocus()
            if (_isMuted.value) {
                _phase.value = VoicePipelinePhase.STOPPED
                _statusDetail.value = "Microphone muted"
            } else if (alwaysOnEnabled || (followUpListen && continuousSession)) {
                _phase.value = if (followUpListen) {
                    VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                } else {
                    VoicePipelinePhase.WAKE_WORD_STANDBY
                }
                _statusDetail.value = if (followUpListen) {
                    "Listening for follow-up, Sir..."
                } else {
                    "Always listening for \"Hey JARVIS\""
                }
                scheduleNextListen(80L)
            } else {
                _phase.value = VoicePipelinePhase.STOPPED
                _statusDetail.value = "Voice standby"
            }
        }
    }

    fun interruptSpeech() {
        speechGeneration.incrementAndGet()
        vad.stopBargeInMonitor()
        pendingUtterance = null
        streamingSpeechChannel?.close()
        streamingSpeechChannel = null
        streamingSpeechToken = -1
        activeNeuralJob?.cancel()
        stopAudioTrack()
        mainHandler.post {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
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
                    scheduleNextListen(60L)
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

    private fun ensureSpeechRecognizer(): SpeechRecognizer {
        val existing = speechRecognizer
        if (existing != null) return existing
        return createBestSpeechRecognizer().apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isRecognizerListening = true
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    _partialTranscript.value = ""
                }

                override fun onBeginningOfSpeech() {
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) {
                        interruptSpeech()
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                    vad.updateAmbientRms(normalized)
                    _rmsLevel.value = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _rmsLevel.value = 0.08f
                }

                override fun onError(error: Int) {
                    isRecognizerListening = false
                    _rmsLevel.value = 0f

                    val onDeviceError = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    if (!forceStandardRecognizer && onDeviceError) {
                        forceStandardRecognizer = true
                        recreateRecognizerAndRestart(80L)
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        alwaysOnEnabled = false
                        continuousSession = false
                        _phase.value = VoicePipelinePhase.STOPPED
                        _statusDetail.value = mapRecognizerError(error)
                        return
                    }

                    val isHardRecognizerFailure = error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_AUDIO ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    val isNetworkError = error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT

                    if (alwaysOnEnabled && !_isMuted.value && _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                        when {
                            isHardRecognizerFailure ->
                                recreateRecognizerAndRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 320L else 140L)
                            isNetworkError ->
                                scheduleNextListen(500L)
                            else ->
                                // ERROR_NO_MATCH or ERROR_SPEECH_TIMEOUT: keep same warm recognizer & restart immediately
                                scheduleNextListen(35L)
                        }
                    } else if (!alwaysOnEnabled) {
                        continuousSession = false
                        _phase.value = VoicePipelinePhase.STOPPED
                        _statusDetail.value = mapRecognizerError(error)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isRecognizerListening = false
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    _rmsLevel.value = 0f
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    handleRecognizedText(text, isFinal = true)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    lastRecognizerActivityMs = System.currentTimeMillis()
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotBlank()) {
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

    private fun recreateRecognizerAndRestart(delayMs: Long) {
        isRecognizerListening = false
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        scheduleNextListen(delayMs)
    }

    private fun startRecognizerSession() {
        if (!hasMicPermission() || _isMuted.value) return
        if (_phase.value == VoicePipelinePhase.STOPPED || _phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) return
        if (isRecognizerListening) return

        val recognizer = ensureSpeechRecognizer()
        val useOffline = !forceStandardRecognizer && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            if (useOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            // Generous continuous-listening silence windows so the mic stays open smoothly
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2600L)
        }
        isRecognizerListening = true
        lastRecognizerActivityMs = System.currentTimeMillis()
        runCatching {
            recognizer.startListening(intent)
        }.onFailure {
            isRecognizerListening = false
            if (alwaysOnEnabled && !_isMuted.value) {
                recreateRecognizerAndRestart(220L)
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
        _partialTranscript.value = ""

        if (text.isBlank()) {
            if (alwaysOnEnabled) {
                scheduleNextListen(40L)
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
            if (wakeEval.intent == WakeIntent.DIRECT_WAKE) {
                if (!sessionChimePlayed) {
                    sessionChimePlayed = true
                    JarvisChimePlayer.playActivationChime(engineScope)
                }
                val remainder = wakeEval.remainder
                onWakeDetected?.invoke(remainder)
                if (remainder.isNotBlank()) {
                    _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                    continuousSession = true
                    onCommandCaptured?.invoke(remainder)
                    if (alwaysOnEnabled && _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                        scheduleNextListen(60L)
                    }
                } else {
                    _phase.value = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE
                    continuousSession = true
                    speak("Yes Sir, I'm listening.", followUpListen = true)
                }
            } else if (alwaysOnEnabled) {
                scheduleNextListen(40L)
            }
            return
        }

        if (_phase.value == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE) {
            val eval = ContextualWakeDetector.evaluate(text, inActiveConversation = true)
            val cleanedCommand = if (eval.intent == WakeIntent.DIRECT_WAKE && eval.remainder.isNotBlank()) {
                eval.remainder
            } else {
                text
            }
            onCommandCaptured?.invoke(cleanedCommand)
            // Keep microphone continuously hot while JARVIS thinks/acts unless TTS takes the mic
            if ((alwaysOnEnabled || continuousSession) && _phase.value != VoicePipelinePhase.SPEAKING_RESPONSE) {
                scheduleNextListen(60L)
            }
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
                startRecognizerSession()
            }
        }
        pendingListenRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun startAlwaysOnWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = engineScope.launch {
            while (isActive && alwaysOnEnabled) {
                kotlinx.coroutines.delay(1400L)
                if (!alwaysOnEnabled || _isMuted.value || !hasMicPermission()) continue
                if (_phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) continue

                val stalled = isRecognizerListening &&
                    (System.currentTimeMillis() - lastRecognizerActivityMs > 45_000L)
                if (stalled) {
                    mainHandler.post { recreateRecognizerAndRestart(80L) }
                } else if (!isRecognizerListening && pendingListenRunnable == null) {
                    mainHandler.post { startRecognizerSession() }
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
        engineScope.cancel()
        tts?.shutdown()
    }
}
