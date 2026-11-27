package com.example.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.JarvisApplication
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? JarvisApplication
                DeviceLearningEngine.scheduleDailyStudy(context.applicationContext)

                app?.localRepository?.getActiveScheduledAutomations()?.forEach { task ->
                    ScheduledAutomationEngine.scheduleAlarm(context.applicationContext, task)
                }

                val wakeEnabled = app?.preferencesRepository?.settingsFlow?.first()?.wakeWordEnabled == true
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (wakeEnabled && hasMic) {
                    runCatching { JarvisVoiceService.start(context.applicationContext) }
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
