package com.example

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import com.example.assistant.AccessibilityDiagnostics
import com.example.assistant.AppDiscoveryEngine
import com.example.assistant.DeviceLearningEngine
import com.example.assistant.JarvisCommandCoordinator
import com.example.assistant.LocalLfmLlmEngine
import com.example.assistant.OfflineModelManager
import com.example.data.local.JarvisDatabase
import com.example.data.local.JarvisLocalRepository
import com.example.data.local.JarvisPreferencesRepository
import com.example.data.remote.NewsAndResearchEngine
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
    lateinit var appDiscoveryEngine: AppDiscoveryEngine
        private set
    lateinit var newsEngine: NewsAndResearchEngine
        private set
    lateinit var nimClient: NvidiaNimClient
        private set
    lateinit var magpieTtsClient: NvidiaMagpieTtsClient
        private set
    lateinit var offlineModelManager: OfflineModelManager
        private set
    lateinit var localLfmEngine: LocalLfmLlmEngine
        private set
    lateinit var voiceEngine: JarvisVoiceEngine
        private set
    lateinit var deviceLearningEngine: DeviceLearningEngine
        private set
    lateinit var commandCoordinator: JarvisCommandCoordinator
        private set

    private val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            AccessibilityDiagnostics.coroutineExceptionHandler("JarvisApplication")
    )
    private var startedActivityCount = 0

    private val packageWatcherReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pkg = intent.data?.schemeSpecificPart?.trim().orEmpty()
            if (pkg.isBlank() || pkg == packageName) return
            appScope.launch {
                runCatching {
                    when (action) {
                        Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> {
                            val reason = if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                                "app_update"
                            } else {
                                "new_install"
                            }
                            deviceLearningEngine.learnSingleApp(pkg, triggerReason = reason)
                        }
                        Intent.ACTION_PACKAGE_REMOVED -> {
                            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                                deviceLearningEngine.onPackageRemoved(pkg)
                            }
                        }
                    }
                }.onFailure { err ->
                    AccessibilityDiagnostics.recordNonFatalError("packageWatcher[$action]", err, this@JarvisApplication)
                }
            }
        }
    }

    override fun onCreate() {
        AccessibilityDiagnostics.installUncaughtExceptionReporter(this)
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
        appDiscoveryEngine = AppDiscoveryEngine(httpClient, webClient)
        newsEngine = NewsAndResearchEngine(webClient)
        nimClient = NvidiaNimClient(httpClient)
        magpieTtsClient = NvidiaMagpieTtsClient(httpClient)
        offlineModelManager = OfflineModelManager(this, httpClient)
        localLfmEngine = LocalLfmLlmEngine(offlineModelManager)
        voiceEngine = JarvisVoiceEngine(this, magpieTtsClient, credentialStore, offlineModelManager)
        deviceLearningEngine = DeviceLearningEngine(this, localRepository, preferencesRepository, webClient)

        commandCoordinator = JarvisCommandCoordinator(
            context = this,
            localRepo = localRepository,
            prefsRepo = preferencesRepository,
            credentialStore = credentialStore,
            webClient = webClient,
            nimClient = nimClient,
            voiceEngine = voiceEngine,
            deviceLearningEngine = deviceLearningEngine,
            appDiscoveryEngine = appDiscoveryEngine,
            newsEngine = newsEngine,
            offlineModelManager = offlineModelManager,
            localLfmEngine = localLfmEngine
        )
        commandCoordinator.attachVoiceEngineCallbacks()

        registerPackageInstallWatcher()

        runCatching { DeviceLearningEngine.scheduleDailyStudy(this) }
            .onFailure { AccessibilityDiagnostics.recordNonFatalError("scheduleDailyStudy", it, this) }
        appScope.launch {
            runCatching { deviceLearningEngine.studyIfDailyDue() }
                .onFailure { AccessibilityDiagnostics.recordNonFatalError("studyIfDailyDue", it, this@JarvisApplication) }
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

    private fun registerPackageInstallWatcher() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(packageWatcherReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(packageWatcherReceiver, filter)
            }
        }.onFailure { err ->
            AccessibilityDiagnostics.recordNonFatalError("registerPackageInstallWatcher", err, this)
        }
    }
}
