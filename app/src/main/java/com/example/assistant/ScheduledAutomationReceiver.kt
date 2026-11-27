package com.example.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.JarvisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Executes due scheduled voice/AI automations when woken by AlarmManager.
 */
class ScheduledAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScheduledAutomationEngine.ACTION_RUN_SCHEDULED_TASK) return
        val taskId = intent.getLongExtra(ScheduledAutomationEngine.EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) return

        val app = context.applicationContext as? JarvisApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.commandCoordinator.executeScheduledAutomationById(taskId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
