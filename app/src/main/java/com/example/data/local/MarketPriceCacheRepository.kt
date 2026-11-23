package com.example.data.local

import com.example.data.remote.MarketInstrumentSpec
import com.example.domain.model.DataFreshness
import com.example.domain.model.MarketAssetClass
import com.example.domain.model.MarketQuote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MarketPriceCacheRepository(
    private val marketCacheDao: MarketCacheDao
) {
    val configuredSymbols: Flow<List<ConfiguredSymbolEntity>> =
        marketCacheDao.observeConfiguredSymbols()

    val cachedConfiguredQuotes: Flow<List<MarketQuote>> =
        marketCacheDao.observeConfiguredCachedQuotes().map { rows ->
            val now = System.currentTimeMillis()
            rows.map { it.toDomainModel(now) }
        }

    val allCachedQuotes: Flow<List<MarketQuote>> =
        marketCacheDao.observeAllCachedQuotes().map { rows ->
            val now = System.currentTimeMillis()
            rows.map { it.toDomainModel(now) }
        }

    suspend fun getCachedSnapshot(): List<MarketQuote> {
        val now = System.currentTimeMillis()
        return marketCacheDao.getAllCachedQuotesSnapshot().map { it.toDomainModel(now) }
    }

    suspend fun getCachedQuote(symbol: String): MarketQuote? {
        val now = System.currentTimeMillis()
        return marketCacheDao.getCachedQuoteBySymbol(symbol)?.toDomainModel(now)
    }

    suspend fun ensureConfiguredSymbolsSeeded(
        instruments: List<MarketInstrumentSpec>,
        enabledSymbols: Set<String>
    ) {
        val existing = marketCacheDao.getAllConfiguredSymbolsSnapshot()
        if (existing.isEmpty()) {
            val entities = instruments.mapIndexed { idx, spec ->
                ConfiguredSymbolEntity(
                    symbol = spec.symbol,
                    displayName = spec.displayName,
                    assetClass = spec.assetClass.name,
                    isEnabled = enabledSymbols.contains(spec.symbol),
                    sortOrder = idx
                )
            }
            marketCacheDao.upsertConfiguredSymbols(entities)
        }
    }

    suspend fun syncConfiguredSymbols(
        enabledSymbols: Set<String>,
        instruments: List<MarketInstrumentSpec>
    ) {
        val existingBySym = marketCacheDao.getAllConfiguredSymbolsSnapshot().associateBy { it.symbol }
        val updated = instruments.mapIndexed { idx, spec ->
            val prev = existingBySym[spec.symbol]
            ConfiguredSymbolEntity(
                symbol = spec.symbol,
                displayName = spec.displayName,
                assetClass = spec.assetClass.name,
                isEnabled = enabledSymbols.contains(spec.symbol),
                sortOrder = prev?.sortOrder ?: idx,
                addedAtMs = prev?.addedAtMs ?: System.currentTimeMillis()
            )
        }
        marketCacheDao.upsertConfiguredSymbols(updated)
    }

    suspend fun setSymbolEnabled(symbol: String, enabled: Boolean) {
        marketCacheDao.setSymbolEnabled(symbol, enabled)
    }

    /**
     * Persists only quotes that have a real positive market price so cached records
     * remain reliable during temporary offline intervals.
     */
    suspend fun cacheQuotes(quotes: List<MarketQuote>) {
        val validEntities = quotes
            .filter { it.price > 0.0 && it.freshness != DataFreshness.UNAVAILABLE }
            .map { q ->
                CachedMarketQuoteEntity(
                    symbol = q.symbol,
                    displayName = q.displayName,
                    assetClass = q.assetClass.name,
                    price = q.price,
                    previousPrice = q.previousPrice,
                    change = q.change,
                    changePercent = q.changePercent,
                    high24h = q.high24h,
                    low24h = q.low24h,
                    volume = q.volume,
                    volatilityLabel = q.volatilityLabel,
                    supportLevel = q.supportLevel,
                    resistanceLevel = q.resistanceLevel,
                    marketSession = q.marketSession,
                    tradingHours = q.tradingHours,
                    freshness = q.freshness.name,
                    sourceName = q.sourceName,
                    updatedAtMs = q.timestampMs
                )
            }
        if (validEntities.isNotEmpty()) {
            marketCacheDao.upsertQuotes(validEntities)
        }
    }

    suspend fun clearCache() {
        marketCacheDao.clearQuotes()
    }

    private fun CachedMarketQuoteEntity.toDomainModel(nowMs: Long): MarketQuote {
        val parsedClass = MarketAssetClass.entries.find { it.name == assetClass } ?: MarketAssetClass.FOREX
        val storedFreshness = DataFreshness.entries.find { it.name == freshness } ?: DataFreshness.DELAYED
        val ageMs = (nowMs - updatedAtMs).coerceAtLeast(0L)
        val effectiveFreshness = if (ageMs > STALE_THRESHOLD_MS && storedFreshness == DataFreshness.LIVE) {
            DataFreshness.DELAYED
        } else {
            storedFreshness
        }
        val effectiveSource = if (ageMs > STALE_THRESHOLD_MS) {
            "$sourceName (Cached)"
        } else {
            sourceName
        }
        return MarketQuote(
            symbol = symbol,
            displayName = displayName,
            assetClass = parsedClass,
            price = price,
            previousPrice = previousPrice,
            change = change,
            changePercent = changePercent,
            high24h = high24h,
            low24h = low24h,
            volume = volume,
            volatilityLabel = volatilityLabel,
            supportLevel = supportLevel,
            resistanceLevel = resistanceLevel,
            marketSession = marketSession,
            tradingHours = tradingHours,
            freshness = effectiveFreshness,
            sourceName = effectiveSource,
            timestampMs = updatedAtMs
        )
    }

    companion object {
        private const val STALE_THRESHOLD_MS = 120_000L // 2 minutes
    }
}
