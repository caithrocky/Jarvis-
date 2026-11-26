package com.example

import com.example.assistant.AppDiscoveryEngine
import com.example.assistant.AssistantModePolicy
import com.example.assistant.MiniOrbStateMapper
import com.example.data.remote.NewsAndResearchEngine
import com.example.data.remote.WebKnowledgeClient
import com.example.domain.model.AssistantUiMode
import com.example.domain.model.MiniOrbVisualState
import com.example.domain.model.TaskExecutionProgress
import com.example.domain.model.TaskStage
import com.example.voice.ContextualWakeDetector
import com.example.voice.VoicePipelinePhase
import com.example.voice.WakeIntent
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantModeBehaviorTest {

    @Test
    fun `wake word inside open full app never spawns mini orb`() {
        val decision = AssistantModePolicy.decideOnWakeWord(
            appInForeground = true,
            floatingOrbEnabled = true,
            overlayPermissionGranted = true
        )
        assertEquals(AssistantUiMode.FULL_APP, decision.targetMode)
        assertFalse(decision.shouldOpenMiniOrb)
        assertTrue(decision.shouldStartListening)
    }

    @Test
    fun `wake word while app is closed spawns mini orb when enabled and overlay granted`() {
        val enabledDecision = AssistantModePolicy.decideOnWakeWord(
            appInForeground = false,
            floatingOrbEnabled = true,
            overlayPermissionGranted = true
        )
        assertEquals(AssistantUiMode.MINI_ORB, enabledDecision.targetMode)
        assertTrue(enabledDecision.shouldOpenMiniOrb)
        assertTrue(enabledDecision.shouldStartListening)

        val noOverlayDecision = AssistantModePolicy.decideOnWakeWord(
            appInForeground = false,
            floatingOrbEnabled = true,
            overlayPermissionGranted = false
        )
        assertEquals(AssistantUiMode.FULL_APP, noOverlayDecision.targetMode)
        assertFalse(noOverlayDecision.shouldOpenMiniOrb)

        val disabledDecision = AssistantModePolicy.decideOnWakeWord(
            appInForeground = false,
            floatingOrbEnabled = false,
            overlayPermissionGranted = true
        )
        assertEquals(AssistantUiMode.FULL_APP, disabledDecision.targetMode)
        assertFalse(disabledDecision.shouldOpenMiniOrb)
    }

    @Test
    fun `mode switch phrase detectors match natural user phrasing`() {
        assertTrue(AssistantModePolicy.isSwitchToMiniOrbPhrase("switch to mini orb"))
        assertTrue(AssistantModePolicy.isSwitchToMiniOrbPhrase("open mini orb"))
        assertTrue(AssistantModePolicy.isSwitchToMiniOrbPhrase("activate floating mode"))
        assertTrue(AssistantModePolicy.isSwitchToMiniOrbPhrase("minimize jarvis"))
        assertFalse(AssistantModePolicy.isSwitchToMiniOrbPhrase("hey jarvis what time is it"))

        assertTrue(AssistantModePolicy.isOpenFullAppPhrase("open full app"))
        assertTrue(AssistantModePolicy.isOpenFullAppPhrase("switch to full app"))
        assertTrue(AssistantModePolicy.isOpenFullAppPhrase("maximize jarvis"))
        assertFalse(AssistantModePolicy.isOpenFullAppPhrase("open spotify"))
    }

    @Test
    fun `contextual wake detector separates direct summons from casual mentions and barge-in`() {
        val hey = ContextualWakeDetector.evaluate("Hey Jarvis")
        assertEquals(WakeIntent.DIRECT_WAKE, hey.intent)
        assertEquals("", hey.remainder)

        val okCmd = ContextualWakeDetector.evaluate("Okay Jarvis, what time is it")
        assertEquals(WakeIntent.DIRECT_WAKE, okCmd.intent)
        assertEquals("what time is it", okCmd.remainder)

        val leadCmd = ContextualWakeDetector.evaluate("Jarvis, open Instagram")
        assertEquals(WakeIntent.DIRECT_WAKE, leadCmd.intent)
        assertEquals("open Instagram", leadCmd.remainder)

        val suffixCmd = ContextualWakeDetector.evaluate("Open Instagram, Jarvis")
        assertEquals(WakeIntent.DIRECT_WAKE, suffixCmd.intent)
        assertEquals("Open Instagram", suffixCmd.remainder)

        val casual = ContextualWakeDetector.evaluate("I think Jarvis we need to find a better way to do this")
        assertEquals(WakeIntent.CASUAL_MENTION, casual.intent)

        assertTrue(ContextualWakeDetector.isBargeInCommand("Wait"))
        assertTrue(ContextualWakeDetector.isBargeInCommand("Stop"))
        assertTrue(ContextualWakeDetector.isBargeInCommand("Hold on"))
        assertTrue(ContextualWakeDetector.isBargeInCommand("Cancel that"))
        assertFalse(ContextualWakeDetector.isBargeInCommand("What is the weather in Nairobi"))
    }

    @Test
    fun `mini orb state mapper resolves all nine real visual states deterministically`() {
        val idleProgress = TaskExecutionProgress()

        assertEquals(
            MiniOrbVisualState.MUTED,
            MiniOrbStateMapper.resolve(
                isMuted = true,
                voicePhase = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE,
                isAiProcessing = true,
                taskProgress = TaskExecutionProgress(stage = TaskStage.EXECUTING),
                wakeFlashActive = true,
                terminalFlash = MiniOrbVisualState.SUCCESS
            )
        )

        assertEquals(
            MiniOrbVisualState.WAKE_DETECTED,
            MiniOrbStateMapper.resolve(
                isMuted = false,
                voicePhase = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE,
                isAiProcessing = false,
                taskProgress = idleProgress,
                wakeFlashActive = true,
                terminalFlash = null
            )
        )

        assertEquals(
            MiniOrbVisualState.LISTENING,
            MiniOrbStateMapper.resolve(
                isMuted = false,
                voicePhase = VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE,
                isAiProcessing = false,
                taskProgress = idleProgress,
                wakeFlashActive = false,
                terminalFlash = null
            )
        )

        assertEquals(
            MiniOrbVisualState.UNDERSTANDING,
            MiniOrbStateMapper.resolve(
                isMuted = false,
                voicePhase = VoicePipelinePhase.WAKE_WORD_STANDBY,
                isAiProcessing = true,
                taskProgress = idleProgress,
                wakeFlashActive = false,
                terminalFlash = null
            )
        )

        assertEquals(
            MiniOrbVisualState.SPEAKING,
            MiniOrbStateMapper.resolve(
                isMuted = false,
                voicePhase = VoicePipelinePhase.SPEAKING_RESPONSE,
                isAiProcessing = false,
                taskProgress = idleProgress,
                wakeFlashActive = false,
                terminalFlash = null
            )
        )

        assertEquals(
            MiniOrbVisualState.EXECUTING,
            MiniOrbStateMapper.resolve(
                isMuted = false,
                voicePhase = VoicePipelinePhase.WAKE_WORD_STANDBY,
                isAiProcessing = false,
                taskProgress = TaskExecutionProgress(stage = TaskStage.EXECUTING, stepIndex = 1, totalSteps = 3),
                wakeFlashActive = false,
                terminalFlash = null
            )
        )

        assertEquals(
            MiniOrbVisualState.SUCCESS,
            MiniOrbStateMapper.resolve(
                isMuted = false,
                voicePhase = VoicePipelinePhase.WAKE_WORD_STANDBY,
                isAiProcessing = false,
                taskProgress = idleProgress,
                wakeFlashActive = false,
                terminalFlash = MiniOrbVisualState.SUCCESS
            )
        )

        assertEquals(
            MiniOrbVisualState.ERROR,
            MiniOrbStateMapper.resolve(
                isMuted = false,
                voicePhase = VoicePipelinePhase.WAKE_WORD_STANDBY,
                isAiProcessing = false,
                taskProgress = idleProgress,
                wakeFlashActive = false,
                terminalFlash = MiniOrbVisualState.ERROR
            )
        )

        assertEquals(
            MiniOrbVisualState.STANDBY,
            MiniOrbStateMapper.resolve(
                isMuted = false,
                voicePhase = VoicePipelinePhase.WAKE_WORD_STANDBY,
                isAiProcessing = false,
                taskProgress = idleProgress,
                wakeFlashActive = false,
                terminalFlash = null
            )
        )
    }

    @Test
    fun `news research query builder targets Kenyan outlets and app discovery parses play packages cleanly`() {
        val newsEngine = NewsAndResearchEngine(WebKnowledgeClient(OkHttpClient()))

        val kenyaQuery = newsEngine.buildFocusedQuery("Latest Kenyan news", "latest kenyan news")
        assertTrue(kenyaQuery.contains("Nation Africa"))
        assertTrue(kenyaQuery.contains("Citizen Digital"))
        assertTrue(kenyaQuery.contains("Standard"))

        val nairobiWeather = newsEngine.buildFocusedQuery("Weather in Nairobi", "weather in nairobi")
        assertTrue(nairobiWeather.contains("Nairobi weather"))

        val worldNews = newsEngine.buildFocusedQuery("World news", "world news")
        assertTrue(worldNews.contains("BBC"))

        val discovery = AppDiscoveryEngine(OkHttpClient(), WebKnowledgeClient(OkHttpClient()))
        val samplePlayHtml = """
            <a href="/store/apps/details?id=com.google.android.gms">GMS</a>
            <a href="/store/apps/details?id=com.spotify.music">Spotify</a>
            <a href="/store/apps/details?id=com.spotify.lite">Spotify Lite</a>
        """.trimIndent()
        val parsed = discovery.parsePlayStorePackages(samplePlayHtml, "Spotify")
        assertEquals(2, parsed.size)
        assertEquals("com.spotify.music", parsed[0].packageId)
        assertEquals("com.spotify.lite", parsed[1].packageId)
        assertTrue(parsed.none { it.packageId?.startsWith("com.google.android.gms") == true })
    }
}
