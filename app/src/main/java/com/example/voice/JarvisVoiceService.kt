package com.example.voice

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.example.JarvisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class JarvisVoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sleepAudioWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        JarvisNotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as JarvisApplication
        if (intent?.action == ACTION_STOP) {
            releaseSleepWakeLock()
            serviceScope.launch {
                app.preferencesRepository.setWakeWordEnabled(false)
            }
            app.voiceEngine.stopAllListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = JarvisNotificationHelper.buildVoiceForegroundNotification(
            this,
            "Say \"Hey JARVIS\" anytime — active even on sleep"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                JarvisNotificationHelper.VOICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(JarvisNotificationHelper.VOICE_NOTIFICATION_ID, notification)
        }

        app.commandCoordinator.attachVoiceEngineCallbacks()
        app.voiceEngine.startAlwaysOnWakeWordLoop()
        syncSleepWakeLock(app)

        // Keep CPU audio loop alive while sleep-listening unless explicitly muted
        serviceScope.launch {
            app.voiceEngine.isMuted.collectLatest { muted ->
                if (muted) releaseSleepWakeLock() else acquireSleepWakeLock()
            }
        }

        // Ensure Mini Orb overlay daemon stands ready in collapsed standby when app is closed & permission granted
        serviceScope.launch {
            val s = runCatching { app.preferencesRepository.settingsFlow.first() }.getOrNull()
            if (s != null &&
                s.wakeWordEnabled &&
                s.floatingOrbEnabled &&
                !app.commandCoordinator.isAppInForeground &&
                Settings.canDrawOverlays(this@JarvisVoiceService)
            ) {
                runCatching {
                    JarvisFloatingOrbService.start(this@JarvisVoiceService, expandBubble = false)
                }
            }
        }
        return START_STICKY
    }

    private fun syncSleepWakeLock(app: JarvisApplication) {
        if (!app.voiceEngine.isMuted.value && app.voiceEngine.hasMicPermission()) {
            acquireSleepWakeLock()
        } else {
            releaseSleepWakeLock()
        }
    }

    private fun acquireSleepWakeLock() {
        val current = sleepAudioWakeLock
        if (current != null && current.isHeld) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JarvisAI::SleepAudioWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            sleepAudioWakeLock = lock
        }
    }

    private fun releaseSleepWakeLock() {
        runCatching {
            sleepAudioWakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }
        sleepAudioWakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val app = application as? JarvisApplication ?: return
        if (!app.voiceEngine.hasMicPermission() || app.voiceEngine.isMuted.value) return

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val restartIntent = Intent(applicationContext, BootRecoveryReceiver::class.java).apply {
            action = BootRecoveryReceiver.ACTION_RESTART_LISTENER
        }
        val pi = PendingIntent.getBroadcast(
            applicationContext,
            9402,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarmManager?.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 700L,
            pi
        )
    }

    override fun onDestroy() {
        releaseSleepWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.voice.ACTION_START"
        const val ACTION_STOP = "com.example.voice.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
