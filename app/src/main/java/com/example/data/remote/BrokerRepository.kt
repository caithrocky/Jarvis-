package com.example.data.remote

import android.net.Uri
import com.example.domain.model.AccountEnvironment
import com.example.domain.model.BrokerAccountInfo
import com.example.domain.model.BrokerType
import com.example.domain.model.OpenPosition
import com.example.security.SecretsConfig
import com.example.security.SecureCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

data class BrokerSyncResult(
    val accounts: List<BrokerAccountInfo>,
    val positions: List<OpenPosition>,
    val tradeHistorySummary: List<String>
)

sealed class OrderExecutionResult {
    data class Executed(val orderId: String, val message: String) : OrderExecutionResult()
    data class Failed(val errorMessage: String) : OrderExecutionResult()
}

class BrokerRepository(
    private val httpClient: OkHttpClient,
    private val credentialStore: SecureCredentialStore
) {

    @Volatile
    private var lastDerivCurrency: String = "USD"

    @Volatile
    private var activeBinanceBaseUrl: String = BINANCE_MAINNET_HOSTS.first()

    fun buildDerivOAuthUrl(): String? {
        val appId = SecretsConfig.derivAppId ?: return null
        return "https://oauth.deriv.com/oauth2/authorize?app_id=$appId&l=EN&brand=deriv"
    }

    /**
     * Parses Deriv OAuth2 redirect params: jarvis://deriv-oauth?acct1=VRTC...&token1=...&cur1=USD
     */
    fun handleDerivOAuthCallback(uri: Uri): Boolean {
        val token1 = uri.getQueryParameter("token1") ?: return false
        val acct1 = uri.getQueryParameter("acct1") ?: "DERIV"
        val cur1 = uri.getQueryParameter("cur1") ?: "USD"
        lastDerivCurrency = cur1
        credentialStore.putEncrypted(SecureCredentialStore.KEY_DERIV_OAUTH_TOKEN, token1)
        credentialStore.putEncrypted(SecureCredentialStore.KEY_DERIV_OAUTH_ACCOUNT, acct1)
        credentialStore.putEncrypted(SecureCredentialStore.KEY_DERIV_OAUTH_CURRENCY, cur1)
        return true
    }

    fun disconnectDerivOAuth() {
        credentialStore.remove(SecureCredentialStore.KEY_DERIV_OAUTH_TOKEN)
        credentialStore.remove(SecureCredentialStore.KEY_DERIV_OAUTH_ACCOUNT)
        credentialStore.remove(SecureCredentialStore.KEY_DERIV_OAUTH_CURRENCY)
    }

    suspend fun syncAllBrokers(spotPricesUsd: Map<String, Double> = emptyMap()): BrokerSyncResult =
        withContext(Dispatchers.IO) {
            val accounts = mutableListOf<BrokerAccountInfo>()
            val positions = mutableListOf<OpenPosition>()
            val history = mutableListOf<String>()

            // 1. Deriv Account (OAuth token or Secrets BuildConfig token)
            val derivToken = credentialStore.getDecrypted(SecureCredentialStore.KEY_DERIV_OAUTH_TOKEN)
                ?: SecretsConfig.derivApiToken
            if (derivToken != null) {
                val derivData = fetchDerivAccountData(derivToken)
                accounts.addAll(derivData.accounts)
                positions.addAll(derivData.positions)
                history.addAll(derivData.tradeHistorySummary)
            } else {
                val oauthReady = SecretsConfig.derivAppId != null
                accounts.add(
                    BrokerAccountInfo(
                        id = "deriv_unconnected",
                        brokerType = BrokerType.DERIV,
                        accountCode = "Not Connected",
                        environment = AccountEnvironment.DEMO,
                        currency = "USD",
                        balance = null,
                        availableBalance = null,
                        permissions = listOf("Read", "Trade (OAuth / Token)"),
                        isConnected = false,
                        statusMessage = if (oauthReady) {
                            "App ID loaded — tap 'Deriv OAuth' below or configure DERIV_API_TOKEN in Secrets."
                        } else {
                            "Configure DERIV_APP_ID / DERIV_API_TOKEN in AI Studio Secrets."
                        },
                        lastSyncMs = null
                    )
                )
            }

            // 2. Binance Spot Account (Mainnet with clock-sync & mirror fallback + Testnet detection)
            val binanceKey = SecretsConfig.binanceApiKey
            val binanceSecret = SecretsConfig.binanceApiSecret
            if (binanceKey != null && binanceSecret != null) {
                val binanceData = fetchBinanceAccountData(binanceKey, binanceSecret, spotPricesUsd)
                accounts.addAll(binanceData.accounts)
                positions.addAll(binanceData.positions)
                history.addAll(binanceData.tradeHistorySummary)
            } else {
                accounts.add(
                    BrokerAccountInfo(
                        id = "binance_unconnected",
                        brokerType = BrokerType.BINANCE,
                        accountCode = "Not Connected",
                        environment = AccountEnvironment.LIVE,
                        currency = "USDT",
                        balance = null,
                        availableBalance = null,
                        permissions = listOf("Read", "Spot Trade (Withdrawal Forbidden)"),
                        isConnected = false,
                        statusMessage = "Configure BINANCE_API_KEY & BINANCE_API_SECRET in AI Studio Secrets.",
                        lastSyncMs = null
                    )
                )
            }

            // 3. MetaTrader 4 / 5 Bridge (Only shown when configured by the user)
            val mtUrl = SecretsConfig.mtBridgeUrl
            val mtToken = SecretsConfig.mtBridgeToken
            if (mtUrl != null && mtToken != null) {
                val mtData = fetchMetaTraderBridgeData(mtUrl, mtToken)
                accounts.addAll(mtData.accounts)
                positions.addAll(mtData.positions)
                history.addAll(mtData.tradeHistorySummary)
            }

            BrokerSyncResult(accounts, positions, history)
        }

    suspend fun executeOrder(
        brokerType: BrokerType,
        symbol: String,
        side: String,
        amountUsd: Double
    ): OrderExecutionResult = withContext(Dispatchers.IO) {
        when (brokerType) {
            BrokerType.DERIV -> executeDerivOrder(symbol, side, amountUsd)
            BrokerType.BINANCE -> executeBinanceSpotOrder(symbol, side, amountUsd)
            BrokerType.MT_BRIDGE -> executeMtBridgeOrder(symbol, side, amountUsd)
        }
    }

    private suspend fun fetchDerivAccountData(token: String): BrokerSyncResult {
        val appId = SecretsConfig.derivAppId ?: "1089"
        val url = "wss://ws.derivws.com/websockets/v3?app_id=$appId"
        val accounts = mutableListOf<BrokerAccountInfo>()
        val positions = mutableListOf<OpenPosition>()
        val history = mutableListOf<String>()

        val wsResult = withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { cont ->
                var completed = false
                var authorized = false

                fun finish() {
                    if (!completed) {
                        completed = true
                        if (cont.isActive) {
                            cont.resume(BrokerSyncResult(accounts.toList(), positions.toList(), history.toList()))
                        }
                    }
                }

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(JSONObject().put("authorize", token).toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val json = JSONObject(text)
                            val msgType = json.optString("msg_type")
                            val errorObj = json.optJSONObject("error")
                            if (errorObj != null) {
                                // Only mark disconnected if authorize itself failed;
                                // follow-up portfolio/profit_table scope restrictions shouldn't invalidate auth.
                                if (!authorized) {
                                    accounts.clear()
                                    accounts.add(
                                        BrokerAccountInfo(
                                            id = "deriv_err",
                                            brokerType = BrokerType.DERIV,
                                            accountCode = "Auth Error",
                                            environment = AccountEnvironment.DEMO,
                                            currency = "USD",
                                            balance = null,
                                            availableBalance = null,
                                            permissions = emptyList(),
                                            isConnected = false,
                                            statusMessage = errorObj.optString("message", "Deriv authorization failed"),
                                            lastSyncMs = System.currentTimeMillis()
                                        )
                                    )
                                    webSocket.close(1000, "Error")
                                    finish()
                                } else if (msgType == "portfolio") {
                                    webSocket.send(JSONObject().put("profit_table", 1).put("limit", 10).toString())
                                } else {
                                    webSocket.close(1000, "Synced")
                                    finish()
                                }
                                return
                            }

                            when (msgType) {
                                "authorize" -> {
                                    authorized = true
                                    val auth = json.getJSONObject("authorize")
                                    val loginId = auth.optString("loginid", "DERIV")
                                    val isVirtual = auth.optInt("is_virtual", 1) == 1 || loginId.startsWith("VRTC")
                                    val currency = auth.optString("currency", "USD").ifBlank { "USD" }
                                    lastDerivCurrency = currency
                                    val balance = auth.optDouble("balance", 0.0)
                                    val scopesArr = auth.optJSONArray("scopes")
                                    val scopes = if (scopesArr != null) {
                                        (0 until scopesArr.length()).map { scopesArr.getString(it) }
                                    } else listOf("read")

                                    val linkedArr = auth.optJSONArray("account_list")
                                    val linkedCount = linkedArr?.length() ?: 1
                                    val statusLabel = if (linkedCount > 1) {
                                        "Authorized via Deriv WebSocket ($linkedCount linked accounts)"
                                    } else {
                                        "Authorized via Deriv WebSocket"
                                    }

                                    accounts.add(
                                        BrokerAccountInfo(
                                            id = "deriv_$loginId",
                                            brokerType = BrokerType.DERIV,
                                            accountCode = loginId,
                                            environment = if (isVirtual) AccountEnvironment.DEMO else AccountEnvironment.LIVE,
                                            currency = currency,
                                            balance = balance,
                                            availableBalance = balance,
                                            permissions = scopes,
                                            isConnected = true,
                                            statusMessage = statusLabel,
                                            lastSyncMs = System.currentTimeMillis()
                                        )
                                    )
                                    webSocket.send(JSONObject().put("portfolio", 1).toString())
                                }

                                "portfolio" -> {
                                    val contracts = json.optJSONObject("portfolio")?.optJSONArray("contracts")
                                    if (contracts != null) {
                                        for (i in 0 until contracts.length()) {
                                            val c = contracts.getJSONObject(i)
                                            val buyPrice = c.optDouble("buy_price", 0.0)
                                            val payout = c.optDouble("payout", buyPrice)
                                            positions.add(
                                                OpenPosition(
                                                    id = c.optLong("contract_id").toString(),
                                                    brokerType = BrokerType.DERIV,
                                                    symbol = c.optString("symbol", "DERIV"),
                                                    side = c.optString("contract_type", "CALL"),
                                                    volume = buyPrice,
                                                    entryPrice = buyPrice,
                                                    currentPrice = payout,
                                                    unrealizedPnl = 0.0,
                                                    currency = c.optString("currency", lastDerivCurrency),
                                                    stopLoss = null,
                                                    takeProfit = null,
                                                    openedAtMs = c.optLong("date_start", 0L) * 1000L
                                                )
                                            )
                                        }
                                    }
                                    webSocket.send(
                                        JSONObject().put("profit_table", 1).put("limit", 10).toString()
                                    )
                                }

                                "profit_table" -> {
                                    val txns = json.optJSONObject("profit_table")?.optJSONArray("transactions")
                                    if (txns != null) {
                                        for (i in 0 until txns.length()) {
                                            val t = txns.getJSONObject(i)
                                            val buy = t.optDouble("buy_price", 0.0)
                                            val sell = t.optDouble("sell_price", 0.0)
                                            history.add("Deriv ${t.optString("shortcode")}: PnL ${"%+.2f".format(sell - buy)}")
                                        }
                                    }
                                    webSocket.close(1000, "Synced")
                                    finish()
                                }
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!authorized) {
                            accounts.add(
                                BrokerAccountInfo(
                                    id = "deriv_net_err",
                                    brokerType = BrokerType.DERIV,
                                    accountCode = "Connection Failed",
                                    environment = AccountEnvironment.DEMO,
                                    currency = "USD",
                                    balance = null,
                                    availableBalance = null,
                                    permissions = emptyList(),
                                    isConnected = false,
                                    statusMessage = "Deriv WebSocket error: ${t.message ?: "Network timeout"}",
                                    lastSyncMs = System.currentTimeMillis()
                                )
                            )
                        }
                        finish()
                    }
                }
                val ws = httpClient.newWebSocket(Request.Builder().url(url).build(), listener)
                cont.invokeOnCancellation { ws.cancel() }
            }
        }

        if (wsResult != null) return wsResult
        if (accounts.any { it.isConnected }) {
            return BrokerSyncResult(accounts.toList(), positions.toList(), history.toList())
        }
        return BrokerSyncResult(
            accounts = listOf(
                BrokerAccountInfo(
                    id = "deriv_timeout",
                    brokerType = BrokerType.DERIV,
                    accountCode = "Timeout",
                    environment = AccountEnvironment.DEMO,
                    currency = "USD",
                    balance = null,
                    availableBalance = null,
                    permissions = emptyList(),
                    isConnected = false,
                    statusMessage = "Deriv WebSocket timed out (app_id=$appId).",
                    lastSyncMs = System.currentTimeMillis()
                )
            ),
            positions = emptyList(),
            tradeHistorySummary = emptyList()
        )
    }

    private fun fetchBinanceAccountData(
        apiKey: String,
        apiSecret: String,
        spotPricesUsd: Map<String, Double>
    ): BrokerSyncResult {
        val candidates = BINANCE_MAINNET_HOSTS.map { it to AccountEnvironment.LIVE } +
            listOf(BINANCE_TESTNET_HOST to AccountEnvironment.DEMO)

        var lastError = "Network error"
        for ((baseUrl, env) in candidates) {
            val attempt = queryBinanceAccountOnHost(baseUrl, env, apiKey, apiSecret, spotPricesUsd)
            when (attempt) {
                is BinanceHostAttempt.Success -> {
                    activeBinanceBaseUrl = baseUrl
                    return attempt.result
                }
                is BinanceHostAttempt.RetryNextHost -> {
                    lastError = attempt.reason
                    continue
                }
                is BinanceHostAttempt.Fatal -> {
                    return BrokerSyncResult(
                        accounts = listOf(
                            BrokerAccountInfo(
                                id = "binance_err",
                                brokerType = BrokerType.BINANCE,
                                accountCode = "Auth Error",
                                environment = env,
                                currency = "USDT",
                                balance = null,
                                availableBalance = null,
                                permissions = emptyList(),
                                isConnected = false,
                                statusMessage = attempt.reason,
                                lastSyncMs = System.currentTimeMillis()
                            )
                        ),
                        positions = emptyList(),
                        tradeHistorySummary = emptyList()
                    )
                }
            }
        }

        return BrokerSyncResult(
            accounts = listOf(
                BrokerAccountInfo(
                    id = "binance_fail",
                    brokerType = BrokerType.BINANCE,
                    accountCode = "Unreachable",
                    environment = AccountEnvironment.LIVE,
                    currency = "USDT",
                    balance = null,
                    availableBalance = null,
                    permissions = emptyList(),
                    isConnected = false,
                    statusMessage = "Binance connection error: $lastError",
                    lastSyncMs = System.currentTimeMillis()
                )
            ),
            positions = emptyList(),
            tradeHistorySummary = emptyList()
        )
    }

    private sealed class BinanceHostAttempt {
        data class Success(val result: BrokerSyncResult) : BinanceHostAttempt()
        data class RetryNextHost(val reason: String) : BinanceHostAttempt()
        data class Fatal(val reason: String) : BinanceHostAttempt()
    }

    private fun fetchBinanceServerTimestamp(baseUrl: String): Long {
        val localNow = System.currentTimeMillis()
        return runCatching {
            val req = Request.Builder().url("$baseUrl/api/v3/time").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching localNow
                val serverTime = JSONObject(resp.body?.string().orEmpty()).optLong("serverTime", 0L)
                if (serverTime > 0L) serverTime else localNow
            }
        }.getOrDefault(localNow)
    }

    private fun queryBinanceAccountOnHost(
        baseUrl: String,
        env: AccountEnvironment,
        apiKey: String,
        apiSecret: String,
        spotPricesUsd: Map<String, Double>
    ): BinanceHostAttempt {
        return runCatching {
            val timestamp = fetchBinanceServerTimestamp(baseUrl)
            val query = "timestamp=$timestamp&recvWindow=50000"
            val signature = hmacSha256(apiSecret, query)
            val url = "$baseUrl/api/v3/account?$query&signature=$signature"
            val req = Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val code = runCatching { JSONObject(rawBody).optInt("code", 0) }.getOrDefault(0)
                    val msg = runCatching { JSONObject(rawBody).optString("msg") }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"

                    // HTTP 451 (geo restriction on emulator IP) or -2015 on mainnet -> try mirrors / testnet
                    if (resp.code == 451 || resp.code >= 500 || code == -2015 || resp.code == 401) {
                        val suffix = if (resp.code == 451) " (HTTP 451 region-restricted IP)" else " ($msg)"
                        return BinanceHostAttempt.RetryNextHost("Binance ${env.label}$suffix")
                    }
                    return BinanceHostAttempt.Fatal("Binance API error: $msg")
                }

                val json = JSONObject(rawBody)
                val canTrade = json.optBoolean("canTrade", false)
                val balances = json.optJSONArray("balances") ?: JSONArray()
                var estimatedTotalUsd = 0.0
                var usdtFree = 0.0
                val nonZeroAssets = mutableListOf<String>()

                for (i in 0 until balances.length()) {
                    val b = balances.getJSONObject(i)
                    val asset = b.getString("asset")
                    val free = b.getString("free").toDoubleOrNull() ?: 0.0
                    val locked = b.getString("locked").toDoubleOrNull() ?: 0.0
                    val total = free + locked
                    if (total > 0.0001) {
                        val unitUsd = when (asset) {
                            "USDT", "USDC", "FDUSD", "BUSD", "TUSD" -> 1.0
                            else -> spotPricesUsd["$asset/USDT"] ?: 0.0
                        }
                        estimatedTotalUsd += total * unitUsd
                        if (asset == "USDT") usdtFree = free
                        nonZeroAssets.add("$asset: ${"%.4f".format(total)}")
                    }
                }

                val perms = buildList {
                    add("Read")
                    if (canTrade) add("Spot Trade")
                }

                val envPrefix = if (env == AccountEnvironment.DEMO) "Spot Testnet" else "Spot"
                BinanceHostAttempt.Success(
                    BrokerSyncResult(
                        accounts = listOf(
                            BrokerAccountInfo(
                                id = "binance_${env.name.lowercase()}",
                                brokerType = BrokerType.BINANCE,
                                accountCode = "$envPrefix-${apiKey.take(6)}***",
                                environment = env,
                                currency = "USDT",
                                balance = estimatedTotalUsd,
                                availableBalance = usdtFree,
                                permissions = perms,
                                isConnected = true,
                                statusMessage = if (nonZeroAssets.isEmpty()) {
                                    "Connected to $envPrefix (0.00 USDT)"
                                } else {
                                    "$envPrefix Holdings: ${nonZeroAssets.take(4).joinToString(" • ")}"
                                },
                                lastSyncMs = System.currentTimeMillis()
                            )
                        ),
                        positions = emptyList(),
                        tradeHistorySummary = nonZeroAssets.map { "Binance $envPrefix Holding: $it" }
                    )
                )
            }
        }.getOrElse { ex ->
            BinanceHostAttempt.RetryNextHost(ex.message ?: "Network error")
        }
    }

    private fun fetchMetaTraderBridgeData(baseUrl: String, token: String): BrokerSyncResult {
        if (!baseUrl.startsWith("https://")) {
            return BrokerSyncResult(
                accounts = listOf(
                    BrokerAccountInfo(
                        id = "mt_insecure",
                        brokerType = BrokerType.MT_BRIDGE,
                        accountCode = "Blocked URL",
                        environment = AccountEnvironment.DEMO,
                        currency = "USD",
                        balance = null,
                        availableBalance = null,
                        permissions = emptyList(),
                        isConnected = false,
                        statusMessage = "MT_BRIDGE_URL must use HTTPS for credential safety.",
                        lastSyncMs = System.currentTimeMillis()
                    )
                ),
                positions = emptyList(),
                tradeHistorySummary = emptyList()
            )
        }
        return runCatching {
            val req = Request.Builder()
                .url("$baseUrl/account")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return BrokerSyncResult(
                        accounts = listOf(
                            BrokerAccountInfo(
                                id = "mt_err",
                                brokerType = BrokerType.MT_BRIDGE,
                                accountCode = "Bridge HTTP ${resp.code}",
                                environment = AccountEnvironment.DEMO,
                                currency = "USD",
                                balance = null,
                                availableBalance = null,
                                permissions = emptyList(),
                                isConnected = false,
                                statusMessage = "MT4/MT5 Bridge returned HTTP ${resp.code}",
                                lastSyncMs = System.currentTimeMillis()
                            )
                        ),
                        positions = emptyList(),
                        tradeHistorySummary = emptyList()
                    )
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val isDemo = json.optBoolean("isDemo", true)
                val bal = json.optDouble("balance", 0.0)
                val eq = json.optDouble("equity", bal)
                val curr = json.optString("currency", "USD")
                val login = json.optString("login", "MT-BRIDGE")
                BrokerSyncResult(
                    accounts = listOf(
                        BrokerAccountInfo(
                            id = "mt_$login",
                            brokerType = BrokerType.MT_BRIDGE,
                            accountCode = login,
                            environment = if (isDemo) AccountEnvironment.DEMO else AccountEnvironment.LIVE,
                            currency = curr,
                            balance = bal,
                            availableBalance = eq,
                            permissions = listOf("Read", "Bridge Execution"),
                            isConnected = true,
                            statusMessage = "Connected via HTTPS Bridge",
                            lastSyncMs = System.currentTimeMillis()
                        )
                    ),
                    positions = emptyList(),
                    tradeHistorySummary = emptyList()
                )
            }
        }.getOrElse { ex ->
            BrokerSyncResult(
                accounts = listOf(
                    BrokerAccountInfo(
                        id = "mt_fail",
                        brokerType = BrokerType.MT_BRIDGE,
                        accountCode = "Unreachable",
                        environment = AccountEnvironment.DEMO,
                        currency = "USD",
                        balance = null,
                        availableBalance = null,
                        permissions = emptyList(),
                        isConnected = false,
                        statusMessage = "MT4/MT5 Bridge unreachable: ${ex.message ?: "Network error"}",
                        lastSyncMs = System.currentTimeMillis()
                    )
                ),
                positions = emptyList(),
                tradeHistorySummary = emptyList()
            )
        }
    }

    private suspend fun executeDerivOrder(symbol: String, side: String, amountUsd: Double): OrderExecutionResult {
        val token = credentialStore.getDecrypted(SecureCredentialStore.KEY_DERIV_OAUTH_TOKEN)
            ?: SecretsConfig.derivApiToken
            ?: return OrderExecutionResult.Failed("Deriv account not authorized. Connect via OAuth or DERIV_API_TOKEN first.")

        val derivSymbol = mapToDerivSymbol(symbol)
        val contractType = if (side.equals("BUY", ignoreCase = true)) "CALL" else "PUT"
        val appId = SecretsConfig.derivAppId ?: "1089"
        val url = "wss://ws.derivws.com/websockets/v3?app_id=$appId"

        return withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine { cont ->
                var finished = false
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(JSONObject().put("authorize", token).toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val json = JSONObject(text)
                            val err = json.optJSONObject("error")
                            if (err != null) {
                                if (!finished) {
                                    finished = true
                                    webSocket.close(1000, "Err")
                                    if (cont.isActive) cont.resume(OrderExecutionResult.Failed(err.optString("message", "Deriv order rejected")))
                                }
                                return
                            }
                            when (json.optString("msg_type")) {
                                "authorize" -> {
                                    val accountCurrency = json.optJSONObject("authorize")
                                        ?.optString("currency", lastDerivCurrency)
                                        ?.ifBlank { lastDerivCurrency }
                                        ?: lastDerivCurrency
                                    lastDerivCurrency = accountCurrency
                                    val buyReq = JSONObject().apply {
                                        put("buy", 1)
                                        put("price", amountUsd)
                                        put("parameters", JSONObject().apply {
                                            put("amount", amountUsd)
                                            put("basis", "stake")
                                            put("contract_type", contractType)
                                            put("currency", accountCurrency)
                                            put("duration", 5)
                                            put("duration_unit", "m")
                                            put("symbol", derivSymbol)
                                        })
                                    }
                                    webSocket.send(buyReq.toString())
                                }
                                "buy" -> {
                                    val buyObj = json.getJSONObject("buy")
                                    val contractId = buyObj.optLong("contract_id").toString()
                                    val buyPrice = buyObj.optDouble("buy_price", amountUsd)
                                    if (!finished) {
                                        finished = true
                                        webSocket.close(1000, "Bought")
                                        if (cont.isActive) cont.resume(
                                            OrderExecutionResult.Executed(
                                                orderId = contractId,
                                                message = "Deriv contract #$contractId opened ($contractType $derivSymbol for $buyPrice $lastDerivCurrency)"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!finished) {
                            finished = true
                            if (cont.isActive) cont.resume(OrderExecutionResult.Failed("Deriv network failure: ${t.message}"))
                        }
                    }
                }
                val ws = httpClient.newWebSocket(Request.Builder().url(url).build(), listener)
                cont.invokeOnCancellation { ws.cancel() }
            }
        } ?: OrderExecutionResult.Failed("Deriv order execution timed out.")
    }

    private fun executeBinanceSpotOrder(symbol: String, side: String, amountUsd: Double): OrderExecutionResult {
        val apiKey = SecretsConfig.binanceApiKey
        val apiSecret = SecretsConfig.binanceApiSecret
        if (apiKey == null || apiSecret == null) {
            return OrderExecutionResult.Failed("Binance API credentials not configured in AI Studio Secrets.")
        }
        val binanceSym = symbol.replace("/", "")
        if (!binanceSym.endsWith("USDT")) {
            return OrderExecutionResult.Failed("Binance Spot supports USDT pairs (e.g. BTC/USDT, ETH/USDT, SOL/USDT).")
        }
        return runCatching {
            val timestamp = fetchBinanceServerTimestamp(activeBinanceBaseUrl)
            val cleanSide = if (side.equals("BUY", ignoreCase = true)) "BUY" else "SELL"
            val params = "symbol=$binanceSym&side=$cleanSide&type=MARKET&quoteOrderQty=${"%.2f".format(amountUsd)}&recvWindow=50000&timestamp=$timestamp"
            val sig = hmacSha256(apiSecret, params)
            val url = "$activeBinanceBaseUrl/api/v3/order?$params&signature=$sig"
            val req = Request.Builder()
                .url(url)
                .header("X-MBX-APIKEY", apiKey)
                .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(body).optString("msg") }.getOrNull() ?: "HTTP ${resp.code}"
                    return OrderExecutionResult.Failed("Binance rejected order: $msg")
                }
                val json = JSONObject(body)
                val orderId = json.optLong("orderId").toString()
                OrderExecutionResult.Executed(
                    orderId = orderId,
                    message = "Binance Spot $cleanSide $binanceSym ($${"%.2f".format(amountUsd)}) filled (#$orderId)"
                )
            }
        }.getOrElse {
            OrderExecutionResult.Failed("Binance order failed: ${it.message ?: "Network error"}")
        }
    }

    private fun executeMtBridgeOrder(symbol: String, side: String, amountUsd: Double): OrderExecutionResult {
        val baseUrl = SecretsConfig.mtBridgeUrl
        val token = SecretsConfig.mtBridgeToken
        if (baseUrl == null || token == null || !baseUrl.startsWith("https://")) {
            return OrderExecutionResult.Failed("MT4/MT5 Bridge is not configured.")
        }
        return runCatching {
            val body = JSONObject().apply {
                put("symbol", symbol.replace("/", ""))
                put("side", side.uppercase())
                put("amountUsd", amountUsd)
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/orders")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return OrderExecutionResult.Failed("MT Bridge HTTP ${resp.code}")
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                val id = json.optString("orderId", "MT-${System.currentTimeMillis()}")
                OrderExecutionResult.Executed(id, "MT4/MT5 Bridge order #$id dispatched.")
            }
        }.getOrElse {
            OrderExecutionResult.Failed("MT Bridge error: ${it.message ?: "Network failure"}")
        }
    }

    private fun mapToDerivSymbol(symbol: String): String = when (symbol.uppercase()) {
        "EUR/USD" -> "frxEURUSD"
        "GBP/USD" -> "frxGBPUSD"
        "USD/JPY" -> "frxUSDJPY"
        "USD/CHF" -> "frxUSDCHF"
        "AUD/USD" -> "frxAUDUSD"
        "USD/CAD" -> "frxUSDCAD"
        "NZD/USD" -> "frxNZDUSD"
        "XAU/USD" -> "frxXAUUSD"
        "XAG/USD" -> "frxXAGUSD"
        "BTC/USDT" -> "cryBTCUSD"
        "ETH/USDT" -> "cryETHUSD"
        else -> symbol
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val BINANCE_MAINNET_HOSTS = listOf(
            "https://api.binance.com",
            "https://api1.binance.com",
            "https://api3.binance.com",
            "https://api4.binance.com"
        )
        private const val BINANCE_TESTNET_HOST = "https://testnet.binance.vision"
    }
}
