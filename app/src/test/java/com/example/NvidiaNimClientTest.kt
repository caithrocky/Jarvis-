package com.example

import com.example.data.local.ConversationMessageEntity
import com.example.data.remote.NvidiaMagpieTtsClient
import com.example.data.remote.NvidiaNimClient
import com.example.domain.model.AiModelOption
import com.example.domain.model.TradingOperatingMode
import com.example.domain.model.VoicePersonality
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NvidiaNimClientTest {

    private val client = NvidiaNimClient(OkHttpClient())
    private val ttsClient = NvidiaMagpieTtsClient(OkHttpClient())

    @Test
    fun `default and unprefixed nemotron 3_5 model IDs normalize cleanly`() {
        assertEquals("nvidia/nemotron-3.5-super-vl-120b-a12b", AiModelOption.catalog.first().id)
        assertEquals(
            "nvidia/nemotron-3.5-super-vl-120b-a12b",
            AiModelOption.normalizeModelId("nemotron-3.5-super-vl-120b-a12b")
        )
        assertEquals(
            "nvidia/nemotron-3.5-super-vl-120b-a12b",
            AiModelOption.normalizeModelId("nvidia/llama-3.1-nemotron-70b-instruct")
        )
        assertEquals(
            "nvidia/nemotron-3.5-super-vl-120b-a12b",
            AiModelOption.normalizeModelId(null)
        )
        assertEquals(
            "nvidia/nemotron-mini-4b-instruct",
            AiModelOption.normalizeModelId("nvidia/nemotron-mini-4b-instruct")
        )
    }

    @Test
    fun `sse parser ignores null content chunks and strips think blocks`() {
        val reasoningOnlyChunk =
            """{"choices":[{"delta":{"content":null,"reasoning_content":"analyzing market"}}]}"""
        assertNull(client.extractTokenFromSseChunk(reasoningOnlyChunk))

        val contentChunk =
            """{"choices":[{"delta":{"content":"EUR/USD holds near 1.0850."}}]}"""
        assertEquals("EUR/USD holds near 1.0850.", client.extractTokenFromSseChunk(contentChunk))

        val stripped = client.stripThinkingBlocks("<think>internal reasoning</think>\nGold is bullish.")
        assertEquals("Gold is bullish.", stripped)

        val unclosedStripped = client.stripThinkingBlocks("Hold steady.<think>incomplete")
        assertEquals("Hold steady.", unclosedStripped)
    }

    @Test
    fun `buildMessagesArray deduplicates trailing user prompt, enforces alternation, and instructs Sir persona`() {
        val snapshot = NvidiaNimClient.LiveContextSnapshot(
            quotes = emptyList(),
            opportunities = emptyList(),
            accounts = emptyList(),
            positions = emptyList(),
            recentNews = emptyList(),
            operatingMode = TradingOperatingMode.OBSERVATION,
            emergencyStopActive = false
        )
        val history = listOf(
            ConversationMessageEntity(role = "assistant", content = "Leading orphan reply", modelUsed = "m", isVoice = false),
            ConversationMessageEntity(role = "user", content = "Hi JARVIS", modelUsed = "m", isVoice = false),
            ConversationMessageEntity(role = "assistant", content = "Good morning.", modelUsed = "m", isVoice = false),
            ConversationMessageEntity(role = "user", content = "What about Gold?", modelUsed = "m", isVoice = false)
        )

        val messages = client.buildMessagesArray(
            userPrompt = "What about Gold?",
            history = history,
            memories = emptyList(),
            snapshot = snapshot
        )

        // Expected strict sequence: system, user("Hi JARVIS"), assistant("Good morning."), user("What about Gold?")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("\"Sir\""))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Hi JARVIS", messages.getJSONObject(1).getString("content"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Good morning.", messages.getJSONObject(2).getString("content"))
        assertEquals("user", messages.getJSONObject(3).getString("role"))
        assertEquals("What about Gold?", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun `magpie tts parses wav container and maps voice personalities`() {
        assertEquals("Magpie-Multilingual.EN-US.Leo", VoicePersonality.JARVIS_CLASSIC.nimVoiceName)
        assertEquals("Magpie-Multilingual.EN-US.Jason", VoicePersonality.ANALYST_CRISP.nimVoiceName)
        assertEquals("Magpie-Multilingual.EN-US.Aria", VoicePersonality.CALM_MENTOR.nimVoiceName)

        assertEquals(
            "Gold holds near 2740, Sir, Support intact.",
            ttsClient.sanitizeForSpeech("**Gold** holds near 2740, Sir • Support intact. https://example.com")
        )

        val pcmPayload = byteArrayOf(10, 20, 30, 40)
        val wav = ByteBuffer.allocate(44 + pcmPayload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmPayload.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(22050)
            putInt(44100)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmPayload.size)
            put(pcmPayload)
        }.array()

        val parsed = ttsClient.parseWavOrRawPcm(wav)
        assertEquals(22050, parsed.sampleRateHz)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertArrayEquals(pcmPayload, parsed.pcmBytes)
    }
}
