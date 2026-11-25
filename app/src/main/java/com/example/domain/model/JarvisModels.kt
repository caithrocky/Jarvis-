package com.example.domain.model

enum class OrbState(val label: String) {
    IDLE("STANDBY"),
    LISTENING("LISTENING"),
    PROCESSING("ANALYZING"),
    SPEAKING("SPEAKING"),
    ALERT("ALERT"),
    OFFLINE("OFFLINE")
}

enum class MarketAssetClass(val label: String) {
    FOREX("Forex"),
    CRYPTO("Crypto"),
    COMMODITIES("Commodities"),
    INDICES("Indices")
}

enum class DataFreshness(val label: String) {
    LIVE("LIVE"),
    DELAYED("DELAYED"),
    UNAVAILABLE("OFFLINE")
}

enum class MarketDirection(val label: String) {
    BULLISH("BULLISH"),
    BEARISH("BEARISH"),
    NEUTRAL("CONSOLIDATION")
}

data class MarketQuote(
    val symbol: String,
    val displayName: String,
    val assetClass: MarketAssetClass,
    val price: Double,
    val previousPrice: Double,
    val change: Double,
    val changePercent: Double,
    val high24h: Double,
    val low24h: Double,
    val volume: Double?,
    val volatilityLabel: String,
    val supportLevel: Double?,
    val resistanceLevel: Double?,
    val marketSession: String,
    val tradingHours: String,
    val freshness: DataFreshness,
    val sourceName: String,
    val timestampMs: Long
) {
    val direction: MarketDirection
        get() = when {
            changePercent > 0.05 -> MarketDirection.BULLISH
            changePercent < -0.05 -> MarketDirection.BEARISH
            else -> MarketDirection.NEUTRAL
        }
}

data class OhlcCandle(
    val timestampMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

data class FibonacciLevels(
    val swingHigh: Double,
    val swingLow: Double,
    val level236: Double,
    val level382: Double,
    val level500: Double,
    val level618: Double,
    val level786: Double
)

data class TechnicalIndicators(
    val symbol: String,
    val timeframe: String,
    val rsi14: Double,
    val macdLine: Double,
    val macdSignal: Double,
    val macdHistogram: Double,
    val sma20: Double,
    val sma50: Double,
    val sma200: Double,
    val ema9: Double,
    val ema21: Double,
    val ema50: Double,
    val bbUpper: Double,
    val bbMiddle: Double,
    val bbLower: Double,
    val atr14: Double,
    val volatilityRegime: String,
    val fibonacci: FibonacciLevels,
    val supportLevels: List<Double>,
    val resistanceLevels: List<Double>,
    val candlestickPatterns: List<String>,
    val maCrossoverSignal: String,
    val trendDirection: MarketDirection
)

data class TradingOpportunity(
    val id: String,
    val symbol: String,
    val displayName: String,
    val marketDirection: MarketDirection,
    val suggestedAction: String, // "BUY", "SELL", or "HOLD / WAIT"
    val suggestedEntry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val riskRewardRatio: Double,
    val timeframe: String,
    val technicalReasoning: String,
    val importantNewsContext: String,
    val confidencePercent: Int,
    val confidenceLabel: String,
    val invalidationConditions: String,
    val indicators: TechnicalIndicators,
    val disclaimer: String = "Analytical probability only — not financial advice or a guaranteed outcome."
)

enum class JarvisChoiceActivity(val title: String, val subtitle: String) {
    ASSISTANT(
        title = "System Assistant",
        subtitle = "Default all-purpose assistant: opens apps, device controls, reminders & answers."
    ),
    MARKETING(
        title = "Markets & Strategy Focus",
        subtitle = "Prioritizes markets, trading opportunities & marketing strategy when instructed."
    )
}

enum class TradingOperatingMode(val title: String, val subtitle: String) {
    OBSERVATION(
        title = "Observation Mode",
        subtitle = "Monitors markets & alerts continuously. Can run alongside other modes."
    ),
    ASSISTANT(
        title = "Assistant Mode",
        subtitle = "Proposes setups for explicit approval & handles personal/system assistant tasks."
    ),
    AUTOMATED(
        title = "Automated Mode",
        subtitle = "Executes pre-authorized strategies strictly inside risk guardrails."
    )
}

fun Set<TradingOperatingMode>.formatModeSummary(): String {
    if (isEmpty()) return TradingOperatingMode.OBSERVATION.title
    if (size == TradingOperatingMode.entries.size) return "All Modes Active (Obs + Asst + Auto)"
    return TradingOperatingMode.entries.filter { it in this }.joinToString(" + ") {
        when (it) {
            TradingOperatingMode.OBSERVATION -> "Observation"
            TradingOperatingMode.ASSISTANT -> "Assistant"
            TradingOperatingMode.AUTOMATED -> "Automated"
        }
    }
}

data class RiskLimits(
    val automatedTradingAuthorized: Boolean = false,
    val emergencyStopActive: Boolean = false,
    val maxTradeAmountUsd: Double = 100.0,
    val maxDailyLossUsd: Double = 250.0,
    val currentDailyLossUsd: Double = 0.0,
    val maxOpenPositions: Int = 3,
    val riskPerTradePercent: Double = 1.0,
    val requireStopLoss: Boolean = true,
    val requireTakeProfit: Boolean = true,
    val sessionFilterEnabled: Boolean = false,
    val allowedSessionLabel: String = "London + NY (07:00-21:00 UTC)",
    val allowedAssets: Set<String> = setOf("EUR/USD", "GBP/USD", "XAU/USD", "BTC/USDT", "ETH/USDT", "SOL/USDT")
)

enum class BrokerType(val displayName: String) {
    DERIV("Deriv"),
    BINANCE("Binance Spot"),
    MT_BRIDGE("MetaTrader 4/5 Bridge")
}

enum class AccountEnvironment(val label: String) {
    DEMO("DEMO"),
    LIVE("LIVE")
}

data class BrokerAccountInfo(
    val id: String,
    val brokerType: BrokerType,
    val accountCode: String,
    val environment: AccountEnvironment,
    val currency: String,
    val balance: Double?,
    val availableBalance: Double?,
    val permissions: List<String>,
    val isConnected: Boolean,
    val statusMessage: String,
    val lastSyncMs: Long?
)

data class OpenPosition(
    val id: String,
    val brokerType: BrokerType,
    val symbol: String,
    val side: String,
    val volume: Double,
    val entryPrice: Double,
    val currentPrice: Double,
    val unrealizedPnl: Double,
    val currency: String,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val openedAtMs: Long
)

data class TradeProposal(
    val id: String,
    val brokerType: BrokerType,
    val symbol: String,
    val side: String,
    val amountUsd: Double,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val reasoning: String,
    val createdAtMs: Long
)

data class NewsItem(
    val id: String,
    val title: String,
    val summary: String,
    val source: String,
    val url: String,
    val publishedAtMs: Long,
    val publishedLabel: String,
    val affectedAssets: List<String>,
    val marketImplication: String,
    val impactLevel: String
)

data class EconomicEvent(
    val id: String,
    val title: String,
    val currency: String,
    val impact: String,
    val actual: String?,
    val forecast: String?,
    val previous: String?,
    val scheduledTimeLabel: String
)

data class AiModelOption(
    val id: String,
    val displayName: String,
    val roleDescription: String
) {
    companion object {
        val catalog = listOf(
            AiModelOption(
                id = "nvidia/nemotron-3.5-super-vl-120b-a12b",
                displayName = "Nemotron 3.5 Super VL 120B",
                roleDescription = "Primary reasoning, multimodal & financial intelligence"
            ),
            AiModelOption(
                id = "nvidia/llama-3.3-nemotron-super-49b-v1",
                displayName = "Nemotron Super 49B v1",
                roleDescription = "Multi-step quantitative & strategy evaluation"
            ),
            AiModelOption(
                id = "nvidia/nemotron-mini-4b-instruct",
                displayName = "Nemotron Mini 4B",
                roleDescription = "Ultra-low-latency voice dialogue & quick summaries"
            ),
            AiModelOption(
                id = "meta/llama-3.1-70b-instruct",
                displayName = "Llama 3.1 70B (NIM)",
                roleDescription = "Compatible NVIDIA NIM general reasoning fallback"
            )
        )

        const val DEFAULT_MODEL_ID = "nvidia/nemotron-3.5-super-vl-120b-a12b"

        fun normalizeModelId(rawId: String?): String {
            val trimmed = rawId?.trim().orEmpty()
            return when {
                trimmed.isEmpty() -> DEFAULT_MODEL_ID
                trimmed == "nemotron-3.5-super-vl-120b-a12b" -> DEFAULT_MODEL_ID
                trimmed == "nvidia/llama-3.1-nemotron-70b-instruct" -> DEFAULT_MODEL_ID
                else -> trimmed
            }
        }
    }
}

enum class VoicePersonality(
    val label: String,
    val pitch: Float,
    val rate: Float,
    val nimVoiceName: String
) {
    JARVIS_CLASSIC("JARVIS Classic (Leo)", 0.88f, 1.02f, "Magpie-Multilingual.EN-US.Leo"),
    ANALYST_CRISP("Quant Brief (Jason)", 0.94f, 1.12f, "Magpie-Multilingual.EN-US.Jason"),
    CALM_MENTOR("Executive (Aria)", 0.96f, 0.96f, "Magpie-Multilingual.EN-US.Aria")
}

data class ApiIntegrationStatus(
    val id: String,
    val name: String,
    val configured: Boolean,
    val reachable: Boolean?,
    val statusText: String,
    val obtainGuide: String,
    val secretKeys: List<String>
)
