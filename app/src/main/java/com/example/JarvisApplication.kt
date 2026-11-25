package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.assistant.DeviceLearningEngine
import com.example.assistant.JarvisCommandCoordinator
import com.example.data.local.JarvisDatabase
import com.example.data.local.JarvisLocalRepository
import com.example.data.local.JarvisPreferencesRepository
import com.example.data.remote.NvidiaMagpieTtsClient
import com.example.data.remote.NvidiaNimClient
import com.example.data.remote.WebKnowledgeClient
import com.example.security.SecretsConfig
import com.example.security.SecureCredentialStore
import com.example.voice.JarvisNotificationHelper
import com.example.voice.JarvisVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class JarvisApplication : Application() {

    lateinit var httpClient: OkHttpClient
        private set
    lateinit var database: JarvisDatabase
        private set
    lateinit var localRepository: JarvisLocalRepository
        private set
    lateinit var preferencesRepository: JarvisPreferencesRepository
        private set
    lateinit var credentialStore: SecureCredentialStore
        private set
    lateinit var webClient: WebKnowledgeClient
        private set
    lateinit var nimClient: NvidiaNimClient
        private set
    lateinit var magpieTtsClient: NvidiaMagpieTtsClient
        private set
    lateinit var voiceEngine: JarvisVoiceEngine
        private set
    lateinit var deviceLearningEngine: DeviceLearningEngine
        private set
    lateinit var commandCoordinator: JarvisCommandCoordinator
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        JarvisNotificationHelper.ensureChannels(this)

        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        database = JarvisDatabase.getInstance(this)
        localRepository = JarvisLocalRepository(database)
        preferencesRepository = JarvisPreferencesRepository(this)
        credentialStore = SecureCredentialStore(this)
        SecretsConfig.attachRuntimeStore(credentialStore)
        webClient = WebKnowledgeClient(httpClient)
        nimClient = NvidiaNimClient(httpClient)
        magpieTtsClient = NvidiaMagpieTtsClient(httpClient)
        voiceEngine = JarvisVoiceEngine(this, magpieTtsClient, credentialStore)
        deviceLearningEngine = DeviceLearningEngine(this, localRepository, preferencesRepository)

        commandCoordinator = JarvisCommandCoordinator(
            context = this,
            localRepo = localRepository,
            prefsRepo = preferencesRepository,
            credentialStore = credentialStore,
            webClient = webClient,
            nimClient = nimClient,
            voiceEngine = voiceEngine,
            deviceLearningEngine = deviceLearningEngine
        )
        commandCoordinator.attachVoiceEngineCallbacks()

        DeviceLearningEngine.scheduleDailyStudy(this)
        appScope.launch {
            deviceLearningEngine.studyIfDailyDue()
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    commandCoordinator.onForegroundChanged(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) {
                    commandCoordinator.onForegroundChanged(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
