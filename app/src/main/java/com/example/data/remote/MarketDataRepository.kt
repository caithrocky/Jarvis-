package com.example.data.remote

import com.example.domain.model.DataFreshness
import com.example.domain.model.MarketAssetClass
import com.example.domain.model.MarketQuote
import com.example.domain.model.OhlcCandle
import com.example.security.SecretsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

data class MarketInstrumentSpec(
    val symbol: String,
    val displayName: String,
    val assetClass: MarketAssetClass,
    val binanceSymbol: String? = null,
    val derivSymbol: String? = null,
    val frankfurterPair: Pair<String, String>? = null,
    val tradingHours: String
)

class MarketDataRepository(private val httpClient: OkHttpClient) {

    val supportedInstruments: List<MarketInstrumentSpec> = listOf(
        // Forex
        MarketInstrumentSpec("EUR/USD", "Euro / US Dollar", MarketAssetClass.FOREX, derivSymbol = "frxEURUSD", frankfurterPair = "EUR" to "USD", tradingHours = "24/5 Sun 22:00 – Fri 22:00 UTC"),
        MarketInstrumentSpec("GBP/USD", "British Pound / US Dollar", MarketAssetClass.FOREX, derivSymbol = "frxGBPUSD", frankfurterPair = "GBP" to "USD", tradingHours = "24/5 Sun 22:00 – Fri 22:00 UTC"),
        MarketInstrumentSpec("USD/JPY", "US Dollar / Japanese Yen", MarketAssetClass.FOREX, derivSymbol = "frxUSDJPY", frankfurterPair = "USD" to "JPY", tradingHours = "24/5 Sun 22:00 – Fri 22:00 UTC"),
        MarketInstrumentSpec("USD/CHF", "US Dollar / Swiss Franc", MarketAssetClass.FOREX, derivSymbol = "frxUSDCHF", frankfurterPair = "USD" to "CHF", tradingHours = "24/5 Sun 22:00 – Fri 22:00 UTC"),
        MarketInstrumentSpec("AUD/USD", "Australian Dollar / US Dollar", MarketAssetClass.FOREX, derivSymbol = "frxAUDUSD", frankfurterPair = "AUD" to "USD", tradingHours = "24/5 Sun 22:00 – Fri 22:00 UTC"),
        MarketInstrumentSpec("USD/CAD", "US Dollar / Canadian Dollar", MarketAssetClass.FOREX, derivSymbol = "frxUSDCAD", frankfurterPair = "USD" to "CAD", tradingHours = "24/5 Sun 22:00 – Fri 22:00 UTC"),
        MarketInstrumentSpec("NZD/USD", "New Zealand Dollar / US Dollar", MarketAssetClass.FOREX, derivSymbol = "frxNZDUSD", frankfurterPair = "NZD" to "USD", tradingHours = "24/5 Sun 22:00 – Fri 22:00 UTC"),
        // Crypto (Binance Spot Live)
        MarketInstrumentSpec("BTC/USDT", "Bitcoin", MarketAssetClass.CRYPTO, binanceSymbol = "BTCUSDT", derivSymbol = "cryBTCUSD", tradingHours = "24/7 Continuous"),
        MarketInstrumentSpec("ETH/USDT", "Ethereum", MarketAssetClass.CRYPTO, binanceSymbol = "ETHUSDT", derivSymbol = "cryETHUSD", tradingHours = "24/7 Continuous"),
        MarketInstrumentSpec("SOL/USDT", "Solana", MarketAssetClass.CRYPTO, binanceSymbol = "SOLUSDT", tradingHours = "24/7 Continuous"),
        MarketInstrumentSpec("BNB/USDT", "BNB", MarketAssetClass.CRYPTO, binanceSymbol = "BNBUSDT", tradingHours = "24/7 Continuous"),
        MarketInstrumentSpec("XRP/USDT", "Ripple XRP", MarketAssetClass.CRYPTO, binanceSymbol = "XRPUSDT", tradingHours = "24/7 Continuous"),
        // Commodities
        MarketInstrumentSpec("XAU/USD", "Gold Spot", MarketAssetClass.COMMODITIES, derivSymbol = "frxXAUUSD", binanceSymbol = "PAXGUSDT", tradingHours = "23/5 Mon–Fri"),
        MarketInstrumentSpec("XAG/USD", "Silver Spot", MarketAssetClass.COMMODITIES, derivSymbol = "frxXAGUSD", tradingHours = "23/5 Mon–Fri"),
        MarketInstrumentSpec("WTI/USD", "Crude Oil WTI", MarketAssetClass.COMMODITIES, derivSymbol = "frxUSOUSD", tradingHours = "23/5 Mon–Fri"),
        MarketInstrumentSpec("NGAS/USD", "Natural Gas", MarketAssetClass.COMMODITIES, derivSymbol = "frxNGASUSD", tradingHours = "23/5 Mon–Fri"),
        // Volatility & Global Indices
        MarketInstrumentSpec("R_100", "Volatility 100 Index", MarketAssetClass.INDICES, derivSymbol = "R_100", tradingHours = "24/7 Synthetic Index"),
        MarketInstrumentSpec("R_75", "Volatility 75 Index", MarketAssetClass.INDICES, derivSymbol = "R_75", tradingHours = "24/7 Synthetic Index"),
        MarketInstrumentSpec("R_25", "Volatility 25 Index", MarketAssetClass.INDICES, derivSymbol = "R_25", tradingHours = "24/7 Synthetic Index"),
        MarketInstrumentSpec("1HZ10V", "Volatility 10 (1s) Index", MarketAssetClass.INDICES, derivSymbol = "1HZ10V", tradingHours = "24/7 Synthetic Index")
    )

    fun currentMarketSessionLabel(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val day = cal.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || (day == Calendar.SUNDAY && hour < 22)) {
            return "Weekend (Crypto & 24/7 Indices Active)"
        }
        return when (hour) {
            in 0..6 -> "Sydney / Tokyo Session"
            in 7..11 -> "London Session"
            in 12..16 -> "London + New York Overlap"
            in 17..21 -> "New York Session"
            else -> "Sydney Open / Rollover"
        }
    }

    suspend fun fetchAllMarketQuotes(): List<MarketQuote> = withContext(Dispatchers.IO) {
        val session = currentMarketSessionLabel()
        val binanceMap = fetchBinance24hrTickers()
        val derivCandlesMap = fetchDerivSnapshotBatch(
            supportedInstruments.mapNotNull { it.derivSymbol }.distinct()
        )
        val frankfurterRates = if (derivCandlesMap.isEmpty()) fetchFrankfurterUsdRates() else emptyMap()

        supportedInstruments.map { spec ->
            val binance = spec.binanceSymbol?.let { binanceMap[it] }
            val derivCandles = spec.derivSymbol?.let { derivCandlesMap[it] }

            when {
                // Prefer Binance live 24hr stats for Crypto spot pairs
                spec.assetClass == MarketAssetClass.CRYPTO && binance != null -> {
                    MarketQuote(
                        symbol = spec.symbol,
                        displayName = spec.displayName,
                        assetClass = spec.assetClass,
                        price = binance.lastPrice,
                        previousPrice = binance.openPrice,
                        change = binance.priceChange,
                        changePercent = binance.priceChangePct,
                        high24h = binance.highPrice,
                        low24h = binance.lowPrice,
                        volume = binance.quoteVolume,
                        volatilityLabel = classifyVolatility(binance.highPrice, binance.lowPrice, binance.lastPrice),
                        supportLevel = binance.lowPrice,
                        resistanceLevel = binance.highPrice,
                        marketSession = "24/7 Global Spot",
                        tradingHours = spec.tradingHours,
                        freshness = DataFreshness.LIVE,
                        sourceName = "Binance Public Spot",
                        timestampMs = System.currentTimeMillis()
                    )
                }

                // Prefer Deriv live WebSocket candles for Forex, Commodities & Volatility Indices
                derivCandles != null && derivCandles.isNotEmpty() -> {
                    val last = derivCandles.last()
                    val first = derivCandles.first()
                    val prevClose = if (derivCandles.size > 1) derivCandles[derivCandles.size - 2].close else first.open
                    val change = last.close - first.open
                    val pct = if (first.open > 0.0) (change / first.open) * 100.0 else 0.0
                    val high = derivCandles.maxOf { it.high }
                    val low = derivCandles.minOf { it.low }
                    MarketQuote(
                        symbol = spec.symbol,
                        displayName = spec.displayName,
                        assetClass = spec.assetClass,
                        price = last.close,
                        previousPrice = prevClose,
                        change = change,
                        changePercent = pct,
                        high24h = high,
                        low24h = low,
                        volume = null,
                        volatilityLabel = classifyVolatility(high, low, last.close),
                        supportLevel = low,
                        resistanceLevel = high,
                        marketSession = if (spec.assetClass == MarketAssetClass.INDICES) "24/7 Synthetic" else session,
                        tradingHours = spec.tradingHours,
                        freshness = DataFreshness.LIVE,
                        sourceName = "Deriv WebSocket Feed",
                        timestampMs = System.currentTimeMillis()
                    )
                }

                // Fallback to Binance PAXG for Gold if Deriv is unreachable
                spec.binanceSymbol != null && binance != null -> {
                    MarketQuote(
                        symbol = spec.symbol,
                        displayName = "${spec.displayName} (Spot Proxy)",
                        assetClass = spec.assetClass,
                        price = binance.lastPrice,
                        previousPrice = binance.openPrice,
                        change = binance.priceChange,
                        changePercent = binance.priceChangePct,
                        high24h = binance.highPrice,
                        low24h = binance.lowPrice,
                        volume = binance.quoteVolume,
                        volatilityLabel = classifyVolatility(binance.highPrice, binance.lowPrice, binance.lastPrice),
                        supportLevel = binance.lowPrice,
                        resistanceLevel = binance.highPrice,
                        marketSession = session,
                        tradingHours = spec.tradingHours,
                        freshness = DataFreshness.LIVE,
                        sourceName = "Binance Spot (PAXG Proxy)",
                        timestampMs = System.currentTimeMillis()
                    )
                }

                // Fallback to Frankfurter ECB reference rates for Forex if WebSocket failed
                spec.frankfurterPair != null && frankfurterRates.isNotEmpty() -> {
                    val price = computePairFromUsdRates(spec.frankfurterPair, frankfurterRates)
                    if (price != null) {
                        MarketQuote(
                            symbol = spec.symbol,
                            displayName = spec.displayName,
                            assetClass = spec.assetClass,
                            price = price,
                            previousPrice = price,
                            change = 0.0,
                            changePercent = 0.0,
                            high24h = price,
                            low24h = price,
                            volume = null,
                            volatilityLabel = "ECB Daily Reference",
                            supportLevel = null,
                            resistanceLevel = null,
                            marketSession = session,
                            tradingHours = spec.tradingHours,
                            freshness = DataFreshness.DELAYED,
                            sourceName = "Frankfurter ECB Reference",
                            timestampMs = System.currentTimeMillis()
                        )
                    } else unavailableQuote(spec, session)
                }

                else -> unavailableQuote(spec, session)
            }
        }
    }

    suspend fun fetchCandles(symbol: String, timeframe: String, limit: Int = 90): List<OhlcCandle> =
        withContext(Dispatchers.IO) {
            val spec = supportedInstruments.find { it.symbol.equals(symbol, ignoreCase = true) }
                ?: return@withContext emptyList()

            if (spec.assetClass == MarketAssetClass.CRYPTO && spec.binanceSymbol != null) {
                val binanceCandles = fetchBinanceKlines(spec.binanceSymbol, timeframe, limit)
                if (binanceCandles.isNotEmpty()) return@withContext binanceCandles
            }

            if (spec.derivSymbol != null) {
                val granularity = when (timeframe) {
                    "15m" -> 900
                    "1h" -> 3600
                    "4h" -> 14400
                    "1d" -> 86400
                    else -> 3600
                }
                val derivCandles = fetchDerivCandles(spec.derivSymbol, granularity, limit)
                if (derivCandles.isNotEmpty()) return@withContext derivCandles
            }

            if (spec.binanceSymbol != null) {
                return@withContext fetchBinanceKlines(spec.binanceSymbol, timeframe, limit)
            }

            emptyList()
        }

    private fun unavailableQuote(spec: MarketInstrumentSpec, session: String): MarketQuote =
        MarketQuote(
            symbol = spec.symbol,
            displayName = spec.displayName,
            assetClass = spec.assetClass,
            price = 0.0,
            previousPrice = 0.0,
            change = 0.0,
            changePercent = 0.0,
            high24h = 0.0,
            low24h = 0.0,
            volume = null,
            volatilityLabel = "Feed Unavailable",
            supportLevel = null,
            resistanceLevel = null,
            marketSession = session,
            tradingHours = spec.tradingHours,
            freshness = DataFreshness.UNAVAILABLE,
            sourceName = "No Live Feed Connected",
            timestampMs = System.currentTimeMillis()
        )

    private fun classifyVolatility(high: Double, low: Double, last: Double): String {
        if (last <= 0.0) return "Unknown"
        val rangePct = ((high - low) / last) * 100.0
        return when {
            rangePct >= 4.0 -> "High (${"%.2f".format(rangePct)}% 24h)"
            rangePct >= 1.2 -> "Moderate (${"%.2f".format(rangePct)}% 24h)"
            else -> "Low (${"%.2f".format(rangePct)}% 24h)"
        }
    }

    private data class BinanceTicker24h(
        val lastPrice: Double,
        val openPrice: Double,
        val priceChange: Double,
        val priceChangePct: Double,
        val highPrice: Double,
        val lowPrice: Double,
        val quoteVolume: Double
    )

    private fun fetchBinance24hrTickers(): Map<String, BinanceTicker24h> = runCatching {
        val symbols = supportedInstruments.mapNotNull { it.binanceSymbol }.distinct()
        if (symbols.isEmpty()) return emptyMap()
        val jsonArrayParam = JSONArray(symbols).toString()
        val url = "https://api.binance.com/api/v3/ticker/24hr?symbols=$jsonArrayParam"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            val body = resp.body?.string() ?: return emptyMap()
            val arr = JSONArray(body)
            val result = mutableMapOf<String, BinanceTicker24h>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sym = obj.getString("symbol")
                result[sym] = BinanceTicker24h(
                    lastPrice = obj.getString("lastPrice").toDoubleOrNull() ?: 0.0,
                    openPrice = obj.getString("openPrice").toDoubleOrNull() ?: 0.0,
                    priceChange = obj.getString("priceChange").toDoubleOrNull() ?: 0.0,
                    priceChangePct = obj.getString("priceChangePercent").toDoubleOrNull() ?: 0.0,
                    highPrice = obj.getString("highPrice").toDoubleOrNull() ?: 0.0,
                    lowPrice = obj.getString("lowPrice").toDoubleOrNull() ?: 0.0,
                    quoteVolume = obj.getString("quoteVolume").toDoubleOrNull() ?: 0.0
                )
            }
            result
        }
    }.getOrDefault(emptyMap())

    private fun fetchBinanceKlines(symbol: String, interval: String, limit: Int): List<OhlcCandle> = runCatching {
        val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            val list = ArrayList<OhlcCandle>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.getJSONArray(i)
                list.add(
                    OhlcCandle(
                        timestampMs = item.getLong(0),
                        open = item.getString(1).toDouble(),
                        high = item.getString(2).toDouble(),
                        low = item.getString(3).toDouble(),
                        close = item.getString(4).toDouble(),
                        volume = item.getString(5).toDouble()
                    )
                )
            }
            list
        }
    }.getOrDefault(emptyList())

    private suspend fun fetchDerivSnapshotBatch(derivSymbols: List<String>): Map<String, List<OhlcCandle>> =
        coroutineScope {
            if (derivSymbols.isEmpty()) return@coroutineScope emptyMap()
            val appId = SecretsConfig.derivAppId ?: "1089"
            val wsClient = httpClient.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
            val url = "wss://ws.derivws.com/websockets/v3?app_id=$appId"

            withTimeoutOrNull(9000L) {
                suspendCancellableCoroutine { cont ->
                    val results = mutableMapOf<String, List<OhlcCandle>>()
                    var pending = derivSymbols.size
                    val lock = Any()
                    var finished = false

                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            derivSymbols.forEachIndexed { idx, sym ->
                                val req = JSONObject().apply {
                                    put("ticks_history", sym)
                                    put("adjust_start_time", 1)
                                    put("count", 24)
                                    put("end", "latest")
                                    put("granularity", 3600)
                                    put("style", "candles")
                                    put("req_id", idx + 1)
                                }
                                webSocket.send(req.toString())
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            runCatching {
                                val json = JSONObject(text)
                                val reqId = json.optInt("req_id", -1)
                                val sym = derivSymbols.getOrNull(reqId - 1)
                                val candlesArr = json.optJSONArray("candles")
                                if (sym != null && candlesArr != null) {
                                    val candles = mutableListOf<OhlcCandle>()
                                    for (i in 0 until candlesArr.length()) {
                                        val c = candlesArr.getJSONObject(i)
                                        candles.add(
                                            OhlcCandle(
                                                timestampMs = c.getLong("epoch") * 1000L,
                                                open = c.getDouble("open"),
                                                high = c.getDouble("high"),
                                                low = c.getDouble("low"),
                                                close = c.getDouble("close")
                                            )
                                        )
                                    }
                                    synchronized(lock) { results[sym] = candles }
                                }
                            }
                            synchronized(lock) {
                                pending--
                                if (pending <= 0 && !finished) {
                                    finished = true
                                    webSocket.close(1000, "Done")
                                    if (cont.isActive) cont.resume(results.toMap())
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            synchronized(lock) {
                                if (!finished) {
                                    finished = true
                                    if (cont.isActive) cont.resume(results.toMap())
                                }
                            }
                        }
                    }

                    val ws = wsClient.newWebSocket(Request.Builder().url(url).build(), listener)
                    cont.invokeOnCancellation { ws.cancel() }
                }
            } ?: emptyMap()
        }

    private suspend fun fetchDerivCandles(derivSymbol: String, granularity: Int, count: Int): List<OhlcCandle> {
        val appId = SecretsConfig.derivAppId ?: "1089"
        val url = "wss://ws.derivws.com/websockets/v3?app_id=$appId"
        return withTimeoutOrNull(7000L) {
            suspendCancellableCoroutine { cont ->
                var done = false
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val req = JSONObject().apply {
                            put("ticks_history", derivSymbol)
                            put("adjust_start_time", 1)
                            put("count", count)
                            put("end", "latest")
                            put("granularity", granularity)
                            put("style", "candles")
                        }
                        webSocket.send(req.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val list = runCatching {
                            val json = JSONObject(text)
                            val arr = json.optJSONArray("candles") ?: return@runCatching emptyList()
                            (0 until arr.length()).map { i ->
                                val c = arr.getJSONObject(i)
                                OhlcCandle(
                                    timestampMs = c.getLong("epoch") * 1000L,
                                    open = c.getDouble("open"),
                                    high = c.getDouble("high"),
                                    low = c.getDouble("low"),
                                    close = c.getDouble("close")
                                )
                            }
                        }.getOrDefault(emptyList())
                        if (!done) {
                            done = true
                            webSocket.close(1000, "Done")
                            if (cont.isActive) cont.resume(list)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!done) {
                            done = true
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    }
                }
                val ws = httpClient.newWebSocket(Request.Builder().url(url).build(), listener)
                cont.invokeOnCancellation { ws.cancel() }
            }
        } ?: emptyList()
    }

    private fun fetchFrankfurterUsdRates(): Map<String, Double> = runCatching {
        val request = Request.Builder().url("https://api.frankfurter.app/latest?from=USD").get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            val body = resp.body?.string() ?: return emptyMap()
            val ratesObj = JSONObject(body).getJSONObject("rates")
            val map = mutableMapOf("USD" to 1.0)
            val keys = ratesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = ratesObj.getDouble(k)
            }
            map
        }
    }.getOrDefault(emptyMap())

    private fun computePairFromUsdRates(pair: Pair<String, String>, usdRates: Map<String, Double>): Double? {
        val base = usdRates[pair.first] ?: return null
        val quote = usdRates[pair.second] ?: return null
        if (base == 0.0) return null
        return quote / base
    }
}
