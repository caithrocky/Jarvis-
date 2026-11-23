package com.example.analysis

import com.example.domain.model.FibonacciLevels
import com.example.domain.model.MarketDirection
import com.example.domain.model.OhlcCandle
import com.example.domain.model.TechnicalIndicators
import com.example.domain.model.TradingOpportunity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object TechnicalAnalysisEngine {

    fun calculateSma(closes: List<Double>, period: Int): Double {
        if (closes.isEmpty()) return 0.0
        val slice = closes.takeLast(min(period, closes.size))
        return slice.average()
    }

    fun calculateEmaSeries(closes: List<Double>, period: Int): List<Double> {
        if (closes.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1.0)
        val result = MutableList(closes.size) { closes.first() }
        var ema = closes.first()
        for (i in closes.indices) {
            ema = if (i == 0) closes[0] else (closes[i] * k) + (ema * (1.0 - k))
            result[i] = ema
        }
        return result
    }

    fun calculateEma(closes: List<Double>, period: Int): Double {
        return calculateEmaSeries(closes, period).lastOrNull() ?: 0.0
    }

    fun calculateRsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size <= 1) return 50.0
        val effectivePeriod = min(period, closes.size - 1)
        var gains = 0.0
        var losses = 0.0
        for (i in 1..effectivePeriod) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) gains += diff else losses -= diff
        }
        var avgGain = gains / effectivePeriod
        var avgLoss = losses / effectivePeriod
        for (i in (effectivePeriod + 1) until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            avgGain = ((avgGain * (effectivePeriod - 1)) + gain) / effectivePeriod
            avgLoss = ((avgLoss * (effectivePeriod - 1)) + loss) / effectivePeriod
        }
        if (avgLoss == 0.0) return if (avgGain == 0.0) 50.0 else 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    data class MacdResult(val macdLine: Double, val signalLine: Double, val histogram: Double)

    fun calculateMacd(closes: List<Double>): MacdResult {
        if (closes.size < 3) return MacdResult(0.0, 0.0, 0.0)
        val ema12 = calculateEmaSeries(closes, 12)
        val ema26 = calculateEmaSeries(closes, 26)
        val macdSeries = ema12.indices.map { ema12[it] - ema26[it] }
        val signalSeries = calculateEmaSeries(macdSeries, 9)
        val macdLine = macdSeries.last()
        val signalLine = signalSeries.last()
        return MacdResult(macdLine, signalLine, macdLine - signalLine)
    }

    data class BollingerResult(val upper: Double, val middle: Double, val lower: Double)

    fun calculateBollingerBands(closes: List<Double>, period: Int = 20, stdDevMultiplier: Double = 2.0): BollingerResult {
        if (closes.isEmpty()) return BollingerResult(0.0, 0.0, 0.0)
        val slice = closes.takeLast(min(period, closes.size))
        val mean = slice.average()
        val variance = slice.sumOf { (it - mean).pow(2) } / slice.size.coerceAtLeast(1)
        val stdDev = sqrt(variance)
        return BollingerResult(
            upper = mean + stdDevMultiplier * stdDev,
            middle = mean,
            lower = mean - stdDevMultiplier * stdDev
        )
    }

    fun calculateAtr(candles: List<OhlcCandle>, period: Int = 14): Double {
        if (candles.size < 2) return candles.firstOrNull()?.let { it.high - it.low } ?: 0.0
        val trs = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            val tr = max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            trs.add(tr)
        }
        return trs.takeLast(min(period, trs.size)).average()
    }

    fun calculateFibonacci(candles: List<OhlcCandle>): FibonacciLevels {
        if (candles.isEmpty()) return FibonacciLevels(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val window = candles.takeLast(min(60, candles.size))
        val swingHigh = window.maxOf { it.high }
        val swingLow = window.minOf { it.low }
        val range = swingHigh - swingLow
        return FibonacciLevels(
            swingHigh = swingHigh,
            swingLow = swingLow,
            level236 = swingHigh - range * 0.236,
            level382 = swingHigh - range * 0.382,
            level500 = swingHigh - range * 0.500,
            level618 = swingHigh - range * 0.618,
            level786 = swingHigh - range * 0.786
        )
    }

    fun detectCandlestickPatterns(candles: List<OhlcCandle>): List<String> {
        if (candles.size < 2) return emptyList()
        val patterns = mutableListOf<String>()
        val curr = candles.last()
        val prev = candles[candles.size - 2]
        val body = abs(curr.close - curr.open)
        val range = (curr.high - curr.low).coerceAtLeast(1e-9)
        val upperWick = curr.high - max(curr.open, curr.close)
        val lowerWick = min(curr.open, curr.close) - curr.low

        if (body / range < 0.12) {
            patterns.add("Doji (Indecision)")
        }
        if (lowerWick > body * 2.0 && upperWick < body * 0.6 && body > 0.0) {
            patterns.add("Hammer / Bullish Pin Bar")
        }
        if (upperWick > body * 2.0 && lowerWick < body * 0.6 && body > 0.0) {
            patterns.add("Shooting Star / Bearish Rejection")
        }
        val prevBull = prev.close > prev.open
        val currBull = curr.close > curr.open
        if (!prevBull && currBull && curr.open <= prev.close && curr.close >= prev.open) {
            patterns.add("Bullish Engulfing")
        } else if (prevBull && !currBull && curr.open >= prev.close && curr.close <= prev.open) {
            patterns.add("Bearish Engulfing")
        }
        if (candles.size >= 3) {
            val first = candles[candles.size - 3]
            val firstBody = abs(first.close - first.open)
            val prevBody = abs(prev.close - prev.open)
            if (first.close < first.open && prevBody < firstBody * 0.4 && curr.close > curr.open && curr.close > (first.open + first.close) / 2.0) {
                patterns.add("Morning Star Reversal")
            } else if (first.close > first.open && prevBody < firstBody * 0.4 && curr.close < curr.open && curr.close < (first.open + first.close) / 2.0) {
                patterns.add("Evening Star Reversal")
            }
        }
        return patterns
    }

    fun findSupportResistance(candles: List<OhlcCandle>): Pair<List<Double>, List<Double>> {
        if (candles.size < 5) {
            val low = candles.minOfOrNull { it.low } ?: 0.0
            val high = candles.maxOfOrNull { it.high } ?: 0.0
            return listOf(low) to listOf(high)
        }
        val currentPrice = candles.last().close
        val supports = mutableListOf<Double>()
        val resistances = mutableListOf<Double>()
        for (i in 2 until candles.size - 2) {
            val c = candles[i]
            val isSwingLow = c.low <= candles[i - 1].low && c.low <= candles[i - 2].low &&
                c.low <= candles[i + 1].low && c.low <= candles[i + 2].low
            val isSwingHigh = c.high >= candles[i - 1].high && c.high >= candles[i - 2].high &&
                c.high >= candles[i + 1].high && c.high >= candles[i + 2].high
            if (isSwingLow && c.low < currentPrice) supports.add(c.low)
            if (isSwingHigh && c.high > currentPrice) resistances.add(c.high)
        }
        if (supports.isEmpty()) supports.add(candles.takeLast(30).minOf { it.low })
        if (resistances.isEmpty()) resistances.add(candles.takeLast(30).maxOf { it.high })
        return supports.distinct().sortedDescending().take(3) to
            resistances.distinct().sorted().take(3)
    }

    fun analyze(
        symbol: String,
        displayName: String,
        timeframe: String,
        candles: List<OhlcCandle>,
        newsHeadline: String? = null
    ): TradingOpportunity? {
        if (candles.size < 10) return null
        val closes = candles.map { it.close }
        val lastPrice = closes.last()
        val rsi = calculateRsi(closes, 14)
        val macd = calculateMacd(closes)
        val sma20 = calculateSma(closes, 20)
        val sma50 = calculateSma(closes, 50)
        val sma200 = calculateSma(closes, 200)
        val ema9 = calculateEma(closes, 9)
        val ema21 = calculateEma(closes, 21)
        val ema50 = calculateEma(closes, 50)
        val bb = calculateBollingerBands(closes, 20, 2.0)
        val atr = calculateAtr(candles, 14).coerceAtLeast(lastPrice * 0.001)
        val fib = calculateFibonacci(candles)
        val (supports, resistances) = findSupportResistance(candles)
        val patterns = detectCandlestickPatterns(candles)

        val maCross = when {
            ema9 > ema21 && sma20 >= sma50 -> "Bullish EMA9/21 & SMA20/50 Alignment"
            ema9 < ema21 && sma20 <= sma50 -> "Bearish EMA9/21 & SMA20/50 Alignment"
            ema9 > ema21 -> "Short-Term Bullish EMA9 > EMA21 Crossover"
            ema9 < ema21 -> "Short-Term Bearish EMA9 < EMA21 Crossover"
            else -> "Neutral Moving Average Convergence"
        }

        // Weighted multi-factor score (-100 to +100)
        var score = 0
        val reasons = mutableListOf<String>()

        if (ema9 > ema21) {
            score += 20
            reasons.add("EMA(9) above EMA(21) momentum")
        } else {
            score -= 20
            reasons.add("EMA(9) below EMA(21) momentum")
        }

        if (lastPrice > sma50) {
            score += 15
            reasons.add("Price holding above SMA(50)")
        } else {
            score -= 15
            reasons.add("Price trading below SMA(50)")
        }

        when {
            rsi in 45.0..65.0 && ema9 > ema21 -> {
                score += 15
                reasons.add("RSI(14) at ${"%.1f".format(rsi)} supports bullish continuation")
            }
            rsi < 32.0 -> {
                score += 20
                reasons.add("RSI(14) oversold (${"%.1f".format(rsi)}) near support")
            }
            rsi > 70.0 -> {
                score -= 20
                reasons.add("RSI(14) overbought (${"%.1f".format(rsi)}) risking pullback")
            }
            else -> reasons.add("RSI(14) neutral at ${"%.1f".format(rsi)}")
        }

        if (macd.histogram > 0 && macd.macdLine > macd.signalLine) {
            score += 20
            reasons.add("MACD histogram positive (${"%.5f".format(macd.histogram)})")
        } else if (macd.histogram < 0 && macd.macdLine < macd.signalLine) {
            score -= 20
            reasons.add("MACD bearish crossover (${"%.5f".format(macd.histogram)})")
        }

        if (lastPrice <= bb.lower * 1.003) {
            score += 12
            reasons.add("Price testing lower Bollinger Band")
        } else if (lastPrice >= bb.upper * 0.997) {
            score -= 12
            reasons.add("Price extended at upper Bollinger Band")
        }

        patterns.forEach { pattern ->
            if (pattern.contains("Bullish") || pattern.contains("Hammer") || pattern.contains("Morning")) {
                score += 10
                reasons.add("Detected $pattern")
            } else if (pattern.contains("Bearish") || pattern.contains("Shooting") || pattern.contains("Evening")) {
                score -= 10
                reasons.add("Detected $pattern")
            }
        }

        val direction = when {
            score >= 18 -> MarketDirection.BULLISH
            score <= -18 -> MarketDirection.BEARISH
            else -> MarketDirection.NEUTRAL
        }

        val action = when (direction) {
            MarketDirection.BULLISH -> "BUY"
            MarketDirection.BEARISH -> "SELL"
            MarketDirection.NEUTRAL -> "WAIT"
        }

        val stopLoss = when (direction) {
            MarketDirection.BULLISH -> max(lastPrice - atr * 1.5, supports.firstOrNull()?.let { it - atr * 0.3 } ?: (lastPrice - atr * 1.5))
            MarketDirection.BEARISH -> min(lastPrice + atr * 1.5, resistances.firstOrNull()?.let { it + atr * 0.3 } ?: (lastPrice + atr * 1.5))
            MarketDirection.NEUTRAL -> lastPrice - atr * 1.5
        }
        val riskDist = abs(lastPrice - stopLoss).coerceAtLeast(atr * 0.8)
        val takeProfit = when (direction) {
            MarketDirection.BULLISH -> lastPrice + riskDist * 2.1
            MarketDirection.BEARISH -> lastPrice - riskDist * 2.1
            MarketDirection.NEUTRAL -> lastPrice + riskDist * 1.5
        }
        val rrRatio = ((abs(takeProfit - lastPrice) / riskDist) * 10.0).roundToInt() / 10.0

        val atrPct = (atr / lastPrice) * 100.0
        val volatilityRegime = when {
            atrPct > 1.5 -> "High Volatility (${"%.2f".format(atrPct)}% ATR)"
            atrPct > 0.45 -> "Moderate Volatility (${"%.2f".format(atrPct)}% ATR)"
            else -> "Low Volatility (${"%.2f".format(atrPct)}% ATR)"
        }

        val confidencePct = (45 + (abs(score) * 0.5).roundToInt()).coerceIn(38, 86)
        val confidenceLabel = when {
            confidencePct >= 75 -> "High Confluence"
            confidencePct >= 58 -> "Moderate Confluence"
            else -> "Low Conviction / Mixed Signals"
        }

        val invalidation = when (direction) {
            MarketDirection.BULLISH -> "Invalidated on $timeframe candle close below ${formatPrice(stopLoss)} or bearish MACD crossover below Fib 61.8% (${formatPrice(fib.level618)})."
            MarketDirection.BEARISH -> "Invalidated on $timeframe candle close above ${formatPrice(stopLoss)} or sustained breakout over resistance ${formatPrice(resistances.firstOrNull() ?: bb.upper)}."
            MarketDirection.NEUTRAL -> "Wait for a decisive $timeframe breakout above ${formatPrice(resistances.firstOrNull() ?: bb.upper)} or breakdown below ${formatPrice(supports.firstOrNull() ?: bb.lower)}."
        }

        val indicators = TechnicalIndicators(
            symbol = symbol,
            timeframe = timeframe,
            rsi14 = rsi,
            macdLine = macd.macdLine,
            macdSignal = macd.signalLine,
            macdHistogram = macd.histogram,
            sma20 = sma20,
            sma50 = sma50,
            sma200 = sma200,
            ema9 = ema9,
            ema21 = ema21,
            ema50 = ema50,
            bbUpper = bb.upper,
            bbMiddle = bb.middle,
            bbLower = bb.lower,
            atr14 = atr,
            volatilityRegime = volatilityRegime,
            fibonacci = fib,
            supportLevels = supports,
            resistanceLevels = resistances,
            candlestickPatterns = patterns,
            maCrossoverSignal = maCross,
            trendDirection = direction
        )

        return TradingOpportunity(
            id = "${symbol}_$timeframe",
            symbol = symbol,
            displayName = displayName,
            marketDirection = direction,
            suggestedAction = action,
            suggestedEntry = lastPrice,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            riskRewardRatio = rrRatio,
            timeframe = timeframe,
            technicalReasoning = reasons.joinToString("; ") + ".",
            importantNewsContext = newsHeadline ?: "Check News tab & upcoming economic releases before sizing risk.",
            confidencePercent = confidencePct,
            confidenceLabel = confidenceLabel,
            invalidationConditions = invalidation,
            indicators = indicators
        )
    }

    fun formatPrice(price: Double): String = when {
        price >= 1000.0 -> "%.2f".format(price)
        price >= 10.0 -> "%.3f".format(price)
        else -> "%.5f".format(price)
    }
}
