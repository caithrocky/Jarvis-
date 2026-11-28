package com.example.assistant

import android.os.Build
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.MemoryFactEntity
import com.example.data.remote.JarvisAiResult
import com.example.data.remote.NvidiaNimClient
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Genuine on-device LLM inference engine running Liquid AI LFM2.5-1.2B-Instruct (GGUF Q4_K_M)
 * via llama.cpp (`dev.ffmpegkit-maintained:llama-android`).
 */
class LocalLfmLlmEngine(
    private val modelManager: OfflineModelManager
) {
    private val mutex = Mutex()

    @Volatile
    private var loadedModel: LlamaModel? = null
    @Volatile
    private var loadedPath: String? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _lastTokensPerSecond = MutableStateFlow(0f)
    val lastTokensPerSecond: StateFlow<Float> = _lastTokensPerSecond.asStateFlow()

    fun isArm64Supported(): Boolean =
        Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }

    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadedModel?.let { model ->
                runCatching { Llama.releaseModel(model) }
            }
            loadedModel = null
            loadedPath = null
            _isModelLoaded.value = false
        }
    }

    suspend fun generateReply(
        userPrompt: String,
        history: List<ConversationMessageEntity>,
        memories: List<MemoryFactEntity>,
        snapshot: NvidiaNimClient.LiveContextSnapshot,
        maxTokens: Int = 280
    ): JarvisAiResult = withContext(Dispatchers.IO) {
        val modelPath = modelManager.getLfmModelPathIfReady()
            ?: return@withContext JarvisAiResult.ConfigurationRequired(
                "LFM2.5 1.2B offline model isn't downloaded yet. Open JARVIS Settings → Offline AI Engine to download it."
            )

        if (!isArm64Supported()) {
            return@withContext JarvisAiResult.Error(
                "LFM2.5 local native runtime requires a 64-bit ARM (arm64-v8a) device."
            )
        }

        mutex.withLock {
            _isGenerating.value = true
            try {
                val model = ensureLoadedLocked(modelPath)
                val systemPrompt = buildCompactSystemPrompt(memories, snapshot)
                val promptWithHistory = formatPromptWithHistory(userPrompt, history)

                val result = Llama.complete(
                    model = model,
                    prompt = promptWithHistory,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens.coerceIn(64, 512)
                )
                _lastTokensPerSecond.value = result.tokensPerSecond

                val reply = cleanLfmOutput(result.text)
                if (reply.isBlank()) {
                    return@withContext JarvisAiResult.Error("LFM2.5 1.2B returned an empty response.")
                }

                JarvisAiResult.Success(
                    replyText = reply,
                    modelUsed = "LFM2.5-1.2B-Instruct (Offline)",
                    toolCallsExecuted = listOf("On-Device LFM2.5")
                )
            } catch (ex: Throwable) {
                if (loadedPath != null && !java.io.File(modelPath).exists()) {
                    loadedModel = null
                    loadedPath = null
                    _isModelLoaded.value = false
                }
                JarvisAiResult.Error("LFM2.5 offline error: ${ex.message ?: "Inference failed"}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private suspend fun ensureLoadedLocked(modelPath: String): LlamaModel {
        val existing = loadedModel
        if (existing != null && existing.isLoaded && loadedPath == modelPath) {
            return existing
        }
        existing?.let { runCatching { Llama.releaseModel(it) } }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
        val config = LlamaConfig(
            contextSize = 2048,
            threads = threads,
            gpuLayers = 0,
            temperature = 0.5f,
            topP = 0.9f,
            topK = 40,
            seed = -1
        )
        val newModel = Llama.loadModel(modelPath, config)
        loadedModel = newModel
        loadedPath = modelPath
        _isModelLoaded.value = true
        return newModel
    }

    companion object {
        internal fun buildCompactSystemPrompt(
            memories: List<MemoryFactEntity>,
            snapshot: NvidiaNimClient.LiveContextSnapshot
        ): String = buildString {
            appendLine("You are JARVIS, an offline on-device AI assistant running Liquid LFM2.5-1.2B on Sir's phone.")
            appendLine("Address the user naturally as Sir. Reply cleanly and concisely in spoken English without markdown or asterisks.")
            appendLine("Infer implied user intent naturally (for example \"lock it\" -> DEVICE_POWER|sleep, \"close wifi settings\" -> DEVICE_SETTING|wifi|close). If an Android device or UI action is required, append matching tags at the very end:")
            appendLine("[ACTION:OPEN_APP|AppName], [ACTION:DELEGATE_CAPABILITY|alarm/timer/trading/marketing/entertainment/messaging|goal], [ACTION:DEVICE_SETTING|setting|open/close/on/off/toggle], [ACTION:TAP_TEXT|label], [ACTION:CLICK_INDEX|n], [ACTION:TYPE_TEXT|text|field], [ACTION:FREE_SCROLL|up/down/left/right/top/bottom/stop|single/continuous], [ACTION:MULTI_TOUCH|pinch_out/pinch_in/multi_finger_tap], [ACTION:VISUAL_FEEDBACK|tap/highlight/scroll|on/off], [ACTION:GLOBAL_NAV|back/home/notifications], [ACTION:DEVICE_POWER|sleep/shutdown/restart], [ACTION:UNLOCK_DEVICE], [ACTION:SET_ALARM|HH:MM|Label], [ACTION:SET_TIMER|seconds|Label], [ACTION:SCHEDULE_TASK|HH:MM or 15m|true/false|Title|Command], [ACTION:MEDIA_CONTROL|play_pause/next/previous], [ACTION:READ_SCREEN].")
            appendLine("Append [HUD_TITLE:2-5 word headline] at the very end when helpful.")
            snapshot.deviceKnowledgeSummary?.takeIf { it.isNotBlank() }?.let {
                appendLine("Device Context: ${it.take(360)}")
            }
            snapshot.indexedScreenMap?.takeIf { it.isNotBlank() }?.let {
                appendLine("Visible Screen Map:\n${it.take(700)}")
            } ?: snapshot.activeScreenSummary?.takeIf { it.isNotBlank() }?.let {
                appendLine("Active Screen: ${it.take(350)}")
            }
            if (memories.isNotEmpty()) {
                appendLine("Memories: " + memories.take(6).joinToString("; ") { it.summary })
            }
        }.trim()

        internal fun formatPromptWithHistory(
            userPrompt: String,
            history: List<ConversationMessageEntity>
        ): String {
            val trimmed = userPrompt.trim()
            val recent = history
                .takeLast(6)
                .filter { it.content.isNotBlank() }
                .let { list ->
                    if (list.isNotEmpty() && list.last().role == "user" && list.last().content.trim() == trimmed) {
                        list.dropLast(1)
                    } else list
                }
            if (recent.isEmpty()) return trimmed
            return buildString {
                appendLine("Recent conversation:")
                recent.takeLast(4).forEach { msg ->
                    val speaker = if (msg.role == "user") "Sir" else "JARVIS"
                    appendLine("$speaker: ${msg.content.trim().take(220)}")
                }
                append("Sir: ")
                append(trimmed)
            }
        }

        internal fun cleanLfmOutput(raw: String): String {
            return raw
                .replace(Regex("<\\|im_end\\|>|<\\|endoftext\\|>|<\\|end\\|>"), "")
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .replace(Regex("(?s)<think>.*$"), "")
                .replace(Regex("^(?:JARVIS|Assistant)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
        }
    }
}
