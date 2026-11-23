package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.JarvisDatabase
import com.example.data.local.MarketPriceCacheRepository
import com.example.data.remote.MarketInstrumentSpec
import com.example.domain.model.DataFreshness
import com.example.domain.model.MarketAssetClass
import com.example.domain.model.MarketQuote
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarketCacheRepositoryTest {

    private lateinit var db: JarvisDatabase
    private lateinit var repo: MarketPriceCacheRepository

    private val sampleInstruments = listOf(
        MarketInstrumentSpec("EUR/USD", "Euro / US Dollar", MarketAssetClass.FOREX, tradingHours = "24/5"),
        MarketInstrumentSpec("BTC/USDT", "Bitcoin", MarketAssetClass.CRYPTO, tradingHours = "24/7"),
        MarketInstrumentSpec("XAU/USD", "Gold Spot", MarketAssetClass.COMMODITIES, tradingHours = "23/5")
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = MarketPriceCacheRepository(db.marketCacheDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `seeded configured symbols filter and order cached market quotes`() = runBlocking {
        repo.ensureConfiguredSymbolsSeeded(
            instruments = sampleInstruments,
            enabledSymbols = setOf("EUR/USD", "XAU/USD")
        )

        val now = System.currentTimeMillis()
        repo.cacheQuotes(
            listOf(
                sampleQuote("XAU/USD", 2742.50, now),
                sampleQuote("BTC/USDT", 92400.00, now),
                sampleQuote("EUR/USD", 1.0842, now)
            )
        )

        val configuredQuotes = repo.cachedConfiguredQuotes.first()
        assertEquals(listOf("EUR/USD", "XAU/USD"), configuredQuotes.map { it.symbol })

        // Enable BTC/USDT dynamically and verify order matches instrument list
        repo.syncConfiguredSymbols(
            enabledSymbols = setOf("EUR/USD", "BTC/USDT", "XAU/USD"),
            instruments = sampleInstruments
        )
        val allEnabledQuotes = repo.cachedConfiguredQuotes.first()
        assertEquals(listOf("EUR/USD", "BTC/USDT", "XAU/USD"), allEnabledQuotes.map { it.symbol })
    }

    @Test
    fun `invalid or unavailable quotes do not overwrite valid cached prices`() = runBlocking {
        repo.ensureConfiguredSymbolsSeeded(sampleInstruments, setOf("BTC/USDT"))
        val now = System.currentTimeMillis()

        repo.cacheQuotes(listOf(sampleQuote("BTC/USDT", 91500.0, now)))
        repo.cacheQuotes(
            listOf(
                sampleQuote(
                    symbol = "BTC/USDT",
                    price = 0.0,
                    timestampMs = now + 5_000L,
                    freshness = DataFreshness.UNAVAILABLE
                )
            )
        )

        val cached = repo.getCachedQuote("BTC/USDT")
        assertNotNull(cached)
        assertEquals(91500.0, cached!!.price, 0.001)
        assertEquals(DataFreshness.LIVE, cached.freshness)
    }

    @Test
    fun `stale cached quotes downgrade freshness to delayed and tag source`() = runBlocking {
        repo.ensureConfiguredSymbolsSeeded(sampleInstruments, setOf("EUR/USD"))
        val staleTimestamp = System.currentTimeMillis() - 180_000L // 3 minutes ago

        repo.cacheQuotes(
            listOf(
                sampleQuote(
                    symbol = "EUR/USD",
                    price = 1.0855,
                    timestampMs = staleTimestamp,
                    freshness = DataFreshness.LIVE,
                    sourceName = "Deriv Live"
                )
            )
        )

        val cached = repo.getCachedQuote("EUR/USD")
        assertNotNull(cached)
        assertEquals(DataFreshness.DELAYED, cached!!.freshness)
        assertTrue(cached.sourceName.endsWith("(Cached)"))
    }

    private fun sampleQuote(
        symbol: String,
        price: Double,
        timestampMs: Long,
        freshness: DataFreshness = DataFreshness.LIVE,
        sourceName: String = "Binance Spot"
    ) = MarketQuote(
        symbol = symbol,
        displayName = symbol,
        assetClass = MarketAssetClass.CRYPTO,
        price = price,
        previousPrice = price * 0.995,
        change = price * 0.005,
        changePercent = 0.5,
        high24h = price * 1.01,
        low24h = price * 0.99,
        volume = 12345.0,
        volatilityLabel = "Normal",
        supportLevel = price * 0.985,
        resistanceLevel = price * 1.015,
        marketSession = "24/7",
        tradingHours = "24/7",
        freshness = freshness,
        sourceName = sourceName,
        timestampMs = timestampMs
    )
}
