package com.example.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.JarvisApplication
import com.example.assistant.AccessibilityDiagnostics
import com.example.assistant.DeviceLearningEngine
import com.example.assistant.ScheduledAutomationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores hands-free wake-word service, scheduled automations, and daily device learning cleanly
 * after reboot or app update whenever configured by Sir.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != ACTION_RESTART_LISTENER
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(
            Dispatchers.IO + AccessibilityDiagnostics.coroutineExceptionHandler("BootRecoveryReceiver")
        ).launch {
            try {
                val app = context.applicationContext as? JarvisApplication
                runCatching { DeviceLearningEngine.scheduleDailyStudy(context.applicationContext) }

                runCatching {
                    app?.localRepository?.getActiveScheduledAutomations()?.forEach { task ->
                        ScheduledAutomationEngine.scheduleAlarm(context.applicationContext, task)
                    }
                }

                val wakeEnabled = runCatching {
                    app?.preferencesRepository?.settingsFlow?.first()?.wakeWordEnabled == true
                }.getOrDefault(false)
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                // Android 14+ (API 34+) prohibits starting a microphone foreground service from BOOT_COMPLETED
                if (wakeEnabled && hasMic && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching { JarvisVoiceService.start(context.applicationContext) }
                        .onFailure { AccessibilityDiagnostics.recordNonFatalError("BootRecovery.startVoice", it) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESTART_LISTENER = "com.example.voice.ACTION_RESTART_LISTENER"
    }
}
