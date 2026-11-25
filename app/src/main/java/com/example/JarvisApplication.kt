package com.example

import android.app.Application
import com.example.data.local.JarvisDatabase
import com.example.data.local.JarvisLocalRepository
import com.example.data.local.JarvisPreferencesRepository
import com.example.data.local.MarketPriceCacheRepository
import com.example.data.remote.BrokerRepository
import com.example.data.remote.MarketDataRepository
import com.example.data.remote.NewsAndCalendarRepository
import com.example.data.remote.NvidiaMagpieTtsClient
import com.example.data.remote.NvidiaNimClient
import com.example.data.remote.SupabaseSyncClient
import com.example.security.SecureCredentialStore
import com.example.voice.JarvisNotificationHelper
import com.example.voice.JarvisVoiceEngine
import com.example.voice.MarketMonitorWorker
import com.example.update.UpdateCheckWorker
import com.example.update.UpdateRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class JarvisApplication : Application() {

    lateinit var httpClient: OkHttpClient
        private set
    lateinit var database: JarvisDatabase
        private set
    lateinit var localRepository: JarvisLocalRepository
        private set
    lateinit var marketCacheRepository: MarketPriceCacheRepository
        private set
    lateinit var preferencesRepository: JarvisPreferencesRepository
        private set
    lateinit var credentialStore: SecureCredentialStore
        private set
    lateinit var marketRepository: MarketDataRepository
        private set
    lateinit var newsRepository: NewsAndCalendarRepository
        private set
    lateinit var nimClient: NvidiaNimClient
        private set
    lateinit var magpieTtsClient: NvidiaMagpieTtsClient
        private set
    lateinit var brokerRepository: BrokerRepository
        private set
    lateinit var supabaseClient: SupabaseSyncClient
        private set
    lateinit var voiceEngine: JarvisVoiceEngine
        private set
    lateinit var updateRepository: UpdateRepository
        private set

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
        marketCacheRepository = MarketPriceCacheRepository(database.marketCacheDao())
        preferencesRepository = JarvisPreferencesRepository(this)
        credentialStore = SecureCredentialStore(this)
        marketRepository = MarketDataRepository(httpClient)
        newsRepository = NewsAndCalendarRepository(httpClient)
        nimClient = NvidiaNimClient(httpClient)
        magpieTtsClient = NvidiaMagpieTtsClient(httpClient)
        brokerRepository = BrokerRepository(httpClient, credentialStore)
        supabaseClient = SupabaseSyncClient(httpClient, credentialStore)
        voiceEngine = JarvisVoiceEngine(this, magpieTtsClient)
        updateRepository = UpdateRepository(this, httpClient)

        MarketMonitorWorker.schedulePeriodic(this)
        UpdateCheckWorker.schedulePeriodic(this)
        UpdateCheckWorker.runOnce(this)
    }
}
