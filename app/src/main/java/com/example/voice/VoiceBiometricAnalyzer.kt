package com.example.voice

import com.example.security.SecureCredentialStore.OwnerVoiceProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class VoiceSampleFeatures(
    val spectralBands: DoubleArray,
    val pitchHz: Double,
    val zcr: Double,
    val spectralTilt: Double,
    val rmsEnergy: Double,
    val voicedFrameRatio: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceSampleFeatures) return false
        return spectralBands.contentEquals(other.spectralBands) &&
            pitchHz == other.pitchHz &&
            zcr == other.zcr &&
            spectralTilt == other.spectralTilt &&
            rmsEnergy == other.rmsEnergy
    }

    override fun hashCode(): Int {
        var result = spectralBands.contentHashCode()
        result = 31 * result + pitchHz.hashCode()
        return result
    }
}

enum class SpeakerMatchState {
    VERIFIED_OWNER,
    VERIFIED_VOCAL_SHIFT,
    UNRECOGNIZED_SPEAKER,
    INSUFFICIENT_AUDIO
}

data class SpeakerVerificationResult(
    val state: SpeakerMatchState,
    val confidence: Float,
    val formantSimilarity: Float,
    val pitchDeviationRatio: Float,
    val detailMessage: String
)

/**
 * On-device acoustic voiceprint extractor & speaker verifier.
 * Uses pitch autocorrelation, zero-crossing breathiness, spectral tilt, and
 * 8-band vocal tract spectral ratios — separating pitch-sensitive identity from
 * pitch-invariant formant structure so the owner is still recognized with a flu or hoarse throat.
 */
object VoiceBiometricAnalyzer {

    const val SAMPLE_RATE_HZ = 16000
    private val BAND_FREQUENCIES_HZ = doubleArrayOf(140.0, 220.0, 340.0, 520.0, 820.0, 1280.0, 2000.0, 3100.0)
    private const val FRAME_SIZE = 512 // 32 ms at 16 kHz
    private const val MIN_RMS_SPEECH = 0.012
    private const val MIN_VOICED_FRAMES = 3

    fun extractFeatures(pcmSamples: ShortArray, sampleRateHz: Int = SAMPLE_RATE_HZ): VoiceSampleFeatures? {
        if (pcmSamples.size < FRAME_SIZE * 2) return null

        val bandSums = DoubleArray(BAND_FREQUENCIES_HZ.size)
        val pitches = mutableListOf<Double>()
        var totalRms = 0.0
        var totalZcr = 0.0
        var voicedFrames = 0
        var totalFrames = 0

        val hop = FRAME_SIZE / 2
        var start = 0
        while (start + FRAME_SIZE <= pcmSamples.size) {
            totalFrames++
            var sumSq = 0.0
            var zeroCrossings = 0
            var prevSign = pcmSamples[start] >= 0

            val frame = DoubleArray(FRAME_SIZE)
            for (i in 0 until FRAME_SIZE) {
                val norm = pcmSamples[start + i] / 32768.0
                frame[i] = norm
                sumSq += norm * norm
                val sign = pcmSamples[start + i] >= 0
                if (i > 0 && sign != prevSign) {
                    zeroCrossings++
                    prevSign = sign
                }
            }

            val rms = sqrt(sumSq / FRAME_SIZE)
            if (rms >= MIN_RMS_SPEECH) {
                voicedFrames++
                totalRms += rms
                totalZcr += zeroCrossings.toDouble() / FRAME_SIZE

                for (b in BAND_FREQUENCIES_HZ.indices) {
                    val mag = goertzelMagnitude(frame, BAND_FREQUENCIES_HZ[b], sampleRateHz)
                    bandSums[b] += ln(1.0 + mag * 120.0)
                }

                estimateFramePitchHz(frame, sampleRateHz)?.let { pitches.add(it) }
            }
            start += hop
        }

        if (voicedFrames < MIN_VOICED_FRAMES) return null

        val avgBands = DoubleArray(BAND_FREQUENCIES_HZ.size) { i -> bandSums[i] / voicedFrames }
        val normalizedBands = l2Normalize(avgBands)
        val pitchHz = medianOrDefault(pitches, 130.0)
        val avgZcr = totalZcr / voicedFrames
        val lowEnergy = avgBands[0] + avgBands[1] + avgBands[2]
        val highEnergy = (avgBands[4] + avgBands[5] + avgBands[6] + avgBands[7]).coerceAtLeast(1e-6)
        val tilt = (lowEnergy / (lowEnergy + highEnergy)).coerceIn(0.0, 1.0)

        return VoiceSampleFeatures(
            spectralBands = normalizedBands,
            pitchHz = pitchHz,
            zcr = avgZcr,
            spectralTilt = tilt,
            rmsEnergy = totalRms / voicedFrames,
            voicedFrameRatio = voicedFrames.toDouble() / max(1, totalFrames)
        )
    }

    fun buildInitialProfile(samples: List<VoiceSampleFeatures>): OwnerVoiceProfile? {
        if (samples.isEmpty()) return null
        val bandCount = BAND_FREQUENCIES_HZ.size
        val meanBands = DoubleArray(bandCount)
        var sumPitch = 0.0
        var sumZcr = 0.0
        var sumTilt = 0.0

        samples.forEach { sample ->
            for (i in 0 until bandCount) {
                meanBands[i] += sample.spectralBands.getOrElse(i) { 0.0 }
            }
            sumPitch += sample.pitchHz
            sumZcr += sample.zcr
            sumTilt += sample.spectralTilt
        }

        val count = samples.size.toDouble()
        for (i in 0 until bandCount) {
            meanBands[i] /= count
        }
        val normBands = l2Normalize(meanBands)
        val avgPitch = sumPitch / count

        return OwnerVoiceProfile(
            anchorBands = normBands,
            adaptiveBands = normBands.copyOf(),
            baselinePitchHz = avgPitch,
            adaptivePitchHz = avgPitch,
            baselineZcr = sumZcr / count,
            baselineSpectralTilt = sumTilt / count,
            sampleCount = samples.size,
            fluAdaptedCount = 0,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    fun verifySpeaker(
        sample: VoiceSampleFeatures?,
        profile: OwnerVoiceProfile,
        fluToleranceEnabled: Boolean = true
    ): SpeakerVerificationResult {
        if (sample == null || sample.rmsEnergy < MIN_RMS_SPEECH * 0.8) {
            return SpeakerVerificationResult(
                state = SpeakerMatchState.INSUFFICIENT_AUDIO,
                confidence = 0f,
                formantSimilarity = 0f,
                pitchDeviationRatio = 1f,
                detailMessage = "Not enough clear speech detected — please speak closer to the mic, Sir."
            )
        }

        val anchorSim = cosineSimilarity(sample.spectralBands, profile.anchorBands)
        val adaptiveSim = cosineSimilarity(sample.spectralBands, profile.adaptiveBands)
        val bestBandSim = max(anchorSim, adaptiveSim)

        // Vocal tract / formant region (bands 2..7, ~340 Hz to 3100 Hz) resists F0 vocal-cord pitch drops from a flu/cold
        val formantAnchorSim = cosineSimilarityRange(sample.spectralBands, profile.anchorBands, 2, 7)
        val formantAdaptiveSim = cosineSimilarityRange(sample.spectralBands, profile.adaptiveBands, 2, 7)
        val bestFormantSim = max(formantAnchorSim, formantAdaptiveSim)

        val pitchRef = profile.adaptivePitchHz.coerceAtLeast(65.0)
        val anchorPitchDiff = abs(sample.pitchHz - profile.baselinePitchHz) / profile.baselinePitchHz.coerceAtLeast(65.0)
        val adaptivePitchDiff = abs(sample.pitchHz - pitchRef) / pitchRef
        val minPitchDiff = minOf(anchorPitchDiff, adaptivePitchDiff)
        val zcrDiff = abs(sample.zcr - profile.baselineZcr)
        val tiltDiff = abs(sample.spectralTilt - profile.baselineSpectralTilt)

        val pitchScore = (1.0 - minPitchDiff * 1.4).coerceIn(0.0, 1.0)
        val combinedConfidence = (
            bestBandSim * 0.45 +
                bestFormantSim * 0.35 +
                pitchScore * 0.12 +
                (1.0 - (tiltDiff * 1.8).coerceIn(0.0, 1.0)) * 0.08
            ).toFloat().coerceIn(0f, 1f)

        // Normal healthy owner match
        if (bestBandSim >= 0.90 && bestFormantSim >= 0.89 && minPitchDiff <= 0.24) {
            return SpeakerVerificationResult(
                state = SpeakerMatchState.VERIFIED_OWNER,
                confidence = combinedConfidence.coerceAtLeast(0.84f),
                formantSimilarity = bestFormantSim.toFloat(),
                pitchDeviationRatio = minPitchDiff.toFloat(),
                detailMessage = "Owner voiceprint verified (${(combinedConfidence * 100).toInt()}%), Sir."
            )
        }

        // Flu / hoarse throat resilience: pitch dropped/strained or ZCR breathier, yet upper vocal-tract formants & core envelope match strongly
        val fluPlausible = fluToleranceEnabled &&
            anchorSim >= 0.78 &&
            bestFormantSim >= 0.87 &&
            minPitchDiff in 0.12..0.46 &&
            (zcrDiff <= 0.14 || tiltDiff <= 0.26)

        if (fluPlausible) {
            return SpeakerVerificationResult(
                state = SpeakerMatchState.VERIFIED_VOCAL_SHIFT,
                confidence = combinedConfidence.coerceIn(0.72f, 0.91f),
                formantSimilarity = bestFormantSim.toFloat(),
                pitchDeviationRatio = minPitchDiff.toFloat(),
                detailMessage = "Recognized your voice with vocal/flu shift (${(bestFormantSim * 100).toInt()}% vocal-tract match) and adapted your baseline, Sir."
            )
        }

        return SpeakerVerificationResult(
            state = SpeakerMatchState.UNRECOGNIZED_SPEAKER,
            confidence = combinedConfidence,
            formantSimilarity = bestFormantSim.toFloat(),
            pitchDeviationRatio = minPitchDiff.toFloat(),
            detailMessage = "Voice did not match the enrolled owner voiceprint (${(combinedConfidence * 100).toInt()}%). Sensitive commands remain locked under Sir."
        )
    }

    /**
     * Smoothly updates adaptive baseline when verified, preserving the immutable anchorBands
     * so temporary illness adapts smoothly without drifting away from the owner's core voiceprint.
     */
    fun adaptProfile(
        profile: OwnerVoiceProfile,
        sample: VoiceSampleFeatures,
        matchState: SpeakerMatchState
    ): OwnerVoiceProfile {
        if (matchState != SpeakerMatchState.VERIFIED_OWNER && matchState != SpeakerMatchState.VERIFIED_VOCAL_SHIFT) {
            return profile
        }
        val alpha = if (matchState == SpeakerMatchState.VERIFIED_VOCAL_SHIFT) 0.16 else 0.12
        val updatedBands = DoubleArray(profile.adaptiveBands.size) { i ->
            val prev = profile.adaptiveBands[i]
            val incoming = sample.spectralBands.getOrElse(i) { prev }
            (1.0 - alpha) * prev + alpha * incoming
        }
        // Clamp drift from anchor so adaptive profile stays bounded within owner territory
        val normAdaptive = l2Normalize(updatedBands)
        val anchorDistance = cosineSimilarity(normAdaptive, profile.anchorBands)
        val safeBands = if (anchorDistance < 0.80) {
            l2Normalize(DoubleArray(normAdaptive.size) { i -> 0.55 * normAdaptive[i] + 0.45 * profile.anchorBands[i] })
        } else {
            normAdaptive
        }

        val updatedPitch = (1.0 - alpha) * profile.adaptivePitchHz + alpha * sample.pitchHz
        return profile.copy(
            adaptiveBands = safeBands,
            adaptivePitchHz = updatedPitch.coerceIn(65.0, 340.0),
            sampleCount = profile.sampleCount + 1,
            fluAdaptedCount = profile.fluAdaptedCount + if (matchState == SpeakerMatchState.VERIFIED_VOCAL_SHIFT) 1 else 0,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    private fun goertzelMagnitude(frame: DoubleArray, targetHz: Double, sampleRateHz: Int): Double {
        val k = ((0.5 + (frame.size * targetHz) / sampleRateHz)).toInt()
        val omega = (2.0 * PI * k) / frame.size
        val coeff = 2.0 * cos(omega)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (i in frame.indices) {
            // Hann window reduces spectral leakage across adjacent vocal formants
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (frame.size - 1)))
            q0 = coeff * q1 - q2 + frame[i] * window
            q2 = q1
            q1 = q0
        }
        val power = q1 * q1 + q2 * q2 - q1 * q2 * coeff
        return sqrt(max(0.0, power)) / frame.size
    }

    private fun estimateFramePitchHz(frame: DoubleArray, sampleRateHz: Int): Double? {
        val minLag = (sampleRateHz / 340).coerceAtLeast(16)
        val maxLag = (sampleRateHz / 65).coerceAtMost(frame.size / 2)
        if (maxLag <= minLag) return null

        var zeroLag = 0.0
        for (i in frame.indices) zeroLag += frame[i] * frame[i]
        if (zeroLag <= 1e-6) return null

        var bestLag = -1
        var bestCorr = 0.0
        for (lag in minLag..maxLag) {
            var corr = 0.0
            for (i in 0 until frame.size - lag) {
                corr += frame[i] * frame[i + lag]
            }
            val normalized = corr / zeroLag
            if (normalized > bestCorr) {
                bestCorr = normalized
                bestLag = lag
            }
        }
        return if (bestLag > 0 && bestCorr >= 0.35) {
            sampleRateHz.toDouble() / bestLag
        } else null
    }

    private fun l2Normalize(vec: DoubleArray): DoubleArray {
        var normSq = 0.0
        for (v in vec) normSq += v * v
        val mag = sqrt(normSq).coerceAtLeast(1e-9)
        return DoubleArray(vec.size) { i -> vec[i] / mag }
    }

    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var dot = 0.0
        var magA = 0.0
        var magB = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        val denom = sqrt(magA * magB).coerceAtLeast(1e-9)
        return (dot / denom).coerceIn(-1.0, 1.0)
    }

    private fun cosineSimilarityRange(a: DoubleArray, b: DoubleArray, startIdx: Int, endIdxInclusive: Int): Double {
        val s = startIdx.coerceAtLeast(0)
        val e = minOf(endIdxInclusive, minOf(a.size, b.size) - 1)
        if (e < s) return 0.0
        var dot = 0.0
        var magA = 0.0
        var magB = 0.0
        for (i in s..e) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        val denom = sqrt(magA * magB).coerceAtLeast(1e-9)
        return (dot / denom).coerceIn(-1.0, 1.0)
    }

    private fun medianOrDefault(values: List<Double>, fallback: Double): Double {
        if (values.isEmpty()) return fallback
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
