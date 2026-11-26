package com.example

import com.example.voice.SpeakerMatchState
import com.example.voice.VoiceBiometricAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceBiometricAnalyzerTest {

    private fun synthesizeVoicePcm(
        fundamentalHz: Double,
        fundamentalAmp: Double,
        formants: List<Pair<Double, Double>>,
        durationMs: Int = 1200,
        breathNoiseAmp: Double = 0.0
    ): ShortArray {
        val sr = VoiceBiometricAnalyzer.SAMPLE_RATE_HZ
        val count = (sr * durationMs) / 1000
        var seed = 42L
        return ShortArray(count) { i ->
            val t = i.toDouble() / sr
            var v = fundamentalAmp * sin(2.0 * PI * fundamentalHz * t)
            for ((freq, amp) in formants) {
                v += amp * sin(2.0 * PI * freq * t)
            }
            if (breathNoiseAmp > 0.0) {
                seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
                val noise = ((seed.toDouble() / 0xFFFFFFFFL) * 2.0 - 1.0) * breathNoiseAmp
                v += noise
            }
            (v.coerceIn(-0.98, 0.98) * 16000.0).toInt().toShort()
        }
    }

    @Test
    fun `voice biometric analyzer verifies healthy owner, adapts during flu shift, and rejects other speakers`() {
        val ownerFormants = listOf(
            340.0 to 0.22,
            820.0 to 0.28,
            1280.0 to 0.20,
            2000.0 to 0.16
        )

        // Enroll 3 healthy samples of the owner around ~140 Hz
        val enrollSamples = listOf(138.0, 140.0, 142.0).map { f0 ->
            val pcm = synthesizeVoicePcm(
                fundamentalHz = f0,
                fundamentalAmp = 0.65,
                formants = ownerFormants
            )
            val feat = VoiceBiometricAnalyzer.extractFeatures(pcm)
            assertNotNull("Expected valid features for enrollment sample", feat)
            feat!!
        }

        val profile = VoiceBiometricAnalyzer.buildInitialProfile(enrollSamples)
        assertNotNull(profile)
        val enrolledProfile = profile!!
        assertEquals(3, enrolledProfile.sampleCount)

        // 1. Normal healthy verification
        val healthyPcm = synthesizeVoicePcm(
            fundamentalHz = 141.0,
            fundamentalAmp = 0.64,
            formants = ownerFormants
        )
        val healthyResult = VoiceBiometricAnalyzer.verifySpeaker(
            sample = VoiceBiometricAnalyzer.extractFeatures(healthyPcm),
            profile = enrolledProfile,
            fluToleranceEnabled = true
        )
        assertEquals(SpeakerMatchState.VERIFIED_OWNER, healthyResult.state)
        assertTrue(healthyResult.confidence >= 0.78f)

        // 2. Owner with flu / hoarse throat (~31% lower fundamental pitch + slight breathy noise, preserved vocal-tract formants)
        val fluPcm = synthesizeVoicePcm(
            fundamentalHz = 96.0,
            fundamentalAmp = 0.68,
            formants = ownerFormants,
            breathNoiseAmp = 0.04
        )
        val fluFeatures = VoiceBiometricAnalyzer.extractFeatures(fluPcm)!!
        val fluResult = VoiceBiometricAnalyzer.verifySpeaker(
            sample = fluFeatures,
            profile = enrolledProfile,
            fluToleranceEnabled = true
        )
        assertEquals(
            "Expected vocal shift verification, got ${fluResult.state} (conf=${fluResult.confidence}, formant=${fluResult.formantSimilarity}, pitchDiff=${fluResult.pitchDeviationRatio})",
            SpeakerMatchState.VERIFIED_VOCAL_SHIFT,
            fluResult.state
        )
        assertTrue(fluResult.formantSimilarity >= 0.87f)

        // When flu adaptation is disabled, heavy pitch drop should NOT match
        val strictResult = VoiceBiometricAnalyzer.verifySpeaker(
            sample = fluFeatures,
            profile = enrolledProfile,
            fluToleranceEnabled = false
        )
        assertEquals(SpeakerMatchState.UNRECOGNIZED_SPEAKER, strictResult.state)

        // Adapt baseline smoothly while keeping immutable anchorBands intact
        val adapted = VoiceBiometricAnalyzer.adaptProfile(enrolledProfile, fluFeatures, fluResult.state)
        assertEquals(4, adapted.sampleCount)
        assertEquals(1, adapted.fluAdaptedCount)
        assertTrue(enrolledProfile.anchorBands.contentEquals(adapted.anchorBands))

        // 3. Unrecognized different speaker (completely different pitch & vocal tract formants)
        val impostorPcm = synthesizeVoicePcm(
            fundamentalHz = 235.0,
            fundamentalAmp = 0.65,
            formants = listOf(
                220.0 to 0.35,
                520.0 to 0.40,
                3100.0 to 0.38
            )
        )
        val impostorResult = VoiceBiometricAnalyzer.verifySpeaker(
            sample = VoiceBiometricAnalyzer.extractFeatures(impostorPcm),
            profile = adapted,
            fluToleranceEnabled = true
        )
        assertEquals(SpeakerMatchState.UNRECOGNIZED_SPEAKER, impostorResult.state)
    }
}
