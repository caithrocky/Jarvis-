package com.example.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.JarvisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class JarvisVoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        JarvisNotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as JarvisApplication
        if (intent?.action == ACTION_STOP) {
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
            "Say \"Hey JARVIS\" anytime to talk or open the mini-orb"
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
        return START_STICKY
    }

    override fun onDestroy() {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
