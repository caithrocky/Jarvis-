package com.example.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import java.util.Locale

/**
 * Real Android Media, Audio Volume, Music/Video Platform & Camera Controller.
 */
object MediaAndCameraController {

    fun controlPlayback(context: Context, action: String): SystemExecutionResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return SystemExecutionResult(true, "Audio service is unavailable, Sir.", "Media Control")

        val lower = action.lowercase(Locale.US).trim()
        when (lower) {
            "volume_up" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return SystemExecutionResult(true, "Raised media volume, Sir.", "Volume Up", true)
            }
            "volume_down" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return SystemExecutionResult(true, "Lowered media volume, Sir.", "Volume Down", true)
            }
            "mute_media" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                return SystemExecutionResult(true, "Muted media audio, Sir.", "Media Mute", true)
            }
        }

        val keyCode = when (lower) {
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

        val reply = when (lower) {
            "next", "skip" -> "Skipping to the next track, Sir."
            "previous", "prev" -> "Returning to the previous track, Sir."
            "stop" -> "Stopping media playback, Sir."
            "pause" -> "Pausing playback, Sir."
            "play" -> "Resuming playback, Sir."
            else -> if (audio.isMusicActive) "Pausing media playback, Sir." else "Playing media, Sir."
        }
        return SystemExecutionResult(true, reply, "Media Control", launchedTarget = true)
    }

    fun playMediaSearch(context: Context, query: String, preferredApp: String = ""): SystemExecutionResult {
        val cleanQuery = query.trim()
        val platformLower = preferredApp.lowercase(Locale.US).trim()
        if (platformLower.contains("spotify")) {
            val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(cleanQuery)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(spotifyIntent); true }.getOrDefault(false)) {
                return SystemExecutionResult(true, "Searching Spotify for \"$cleanQuery\", Sir.", "Spotify", true)
            }
            return JarvisSystemAssistant.openWebsiteInChrome(
                context,
                "https://open.spotify.com/search/${Uri.encode(cleanQuery)}"
            )
        }
        if (platformLower.contains("netflix")) {
            return JarvisSystemAssistant.openWebsiteInChrome(
                context,
                "https://www.netflix.com/search?q=${Uri.encode(cleanQuery)}"
            )
        }
        // Default: YouTube search/playback
        val ytUrl = "https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}"
        return JarvisSystemAssistant.openWebsiteInChrome(context, ytUrl).copy(
            replyText = "Opening \"$cleanQuery\" on YouTube in Chrome, Sir.",
            actionLabel = "YouTube Media"
        )
    }

    fun launchCamera(context: Context, mode: String): SystemExecutionResult {
        val lower = mode.lowercase(Locale.US).trim()
        val intent = when (lower) {
            "video" -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            "selfie", "front" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
            else -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val ok = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        return if (ok) {
            val msg = when (lower) {
                "selfie", "front" -> "Opening your front camera for a selfie, Sir."
                "video" -> "Opening video camera mode now, Sir."
                else -> "Opening the camera now, Sir."
            }
            SystemExecutionResult(true, msg, "Camera", launchedTarget = true)
        } else {
            SystemExecutionResult(false, "No camera app responded on this device, Sir.", "Camera Error", launchedTarget = false)
        }
    }
}
