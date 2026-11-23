package com.example.voice

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.JarvisApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MarketMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? JarvisApplication ?: return Result.success()
        val settings = app.preferencesRepository.settingsFlow.first()
        val tasks = app.localRepository.getActiveMonitoringTasks()
        if (tasks.isEmpty()) return Result.success()

        val quotes = runCatching { app.marketRepository.fetchAllMarketQuotes() }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()

        for (task in tasks) {
            val quote = quotes.find { it.symbol.equals(task.symbol, ignoreCase = true) }
            when (task.ruleType) {
                "PCT_MOVE" -> {
                    if (quote != null && quote.price > 0.0) {
                        val baseline = task.lastBaselinePrice ?: quote.price
                        val pctDelta = if (baseline > 0.0) ((quote.price - baseline) / baseline) * 100.0 else 0.0
                        if (abs(pctDelta) >= task.thresholdValue) {
                            val title = "${quote.symbol} moved ${"%+.2f".format(pctDelta)}%"
                            val msg = "Price reached ${"%.4f".format(quote.price)} (threshold ${task.thresholdValue}%)."
                            app.localRepository.logAlert("MARKET", title, msg, "WARNING", quote.symbol)
                            if (settings.notifyMarketMoves) {
                                JarvisNotificationHelper.postMarketAlert(applicationContext, title, msg)
                            }
                            app.localRepository.updateTaskState(task.id, quote.price, now)
                        } else if (task.lastBaselinePrice == null) {
                            app.localRepository.updateTaskState(task.id, quote.price, null)
                        }
                    }
                }
                "PRICE_ABOVE" -> {
                    if (quote != null && quote.price >= task.thresholdValue) {
                        val cooldownPassed = (now - (task.lastTriggeredMs ?: 0L)) > 3_600_000L
                        if (cooldownPassed) {
                            val title = "${quote.symbol} crossed above ${task.thresholdValue}"
                            val msg = "Live price ${"%.4f".format(quote.price)} via ${quote.sourceName}."
                            app.localRepository.logAlert("MARKET", title, msg, "INFO", quote.symbol)
                            if (settings.notifyMarketMoves) {
                                JarvisNotificationHelper.postMarketAlert(applicationContext, title, msg)
                            }
                            app.localRepository.updateTaskState(task.id, quote.price, now)
                        }
                    }
                }
                "PRICE_BELOW" -> {
                    if (quote != null && quote.price in 0.000001..task.thresholdValue) {
                        val cooldownPassed = (now - (task.lastTriggeredMs ?: 0L)) > 3_600_000L
                        if (cooldownPassed) {
                            val title = "${quote.symbol} dropped below ${task.thresholdValue}"
                            val msg = "Live price ${"%.4f".format(quote.price)} via ${quote.sourceName}."
                            app.localRepository.logAlert("MARKET", title, msg, "WARNING", quote.symbol)
                            if (settings.notifyMarketMoves) {
                                JarvisNotificationHelper.postMarketAlert(applicationContext, title, msg)
                            }
                            app.localRepository.updateTaskState(task.id, quote.price, now)
                        }
                    }
                }
                "MORNING_BRIEF" -> {
                    val cooldownPassed = (now - (task.lastTriggeredMs ?: 0L)) > 20 * 3_600_000L
                    if (cooldownPassed && quotes.isNotEmpty()) {
                        val summary = quotes.filter { it.price > 0.0 }.take(3).joinToString(" | ") {
                            "${it.symbol}: ${"%.4f".format(it.price)} (${"%+.2f".format(it.changePercent)}%)"
                        }
                        if (summary.isNotBlank()) {
                            app.localRepository.logAlert("MARKET", "JARVIS Market Briefing", summary, "INFO", null)
                            if (settings.notifyMarketMoves) {
                                JarvisNotificationHelper.postMarketAlert(applicationContext, "JARVIS Market Briefing", summary)
                            }
                            app.localRepository.updateTaskState(task.id, null, now)
                        }
                    }
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "jarvis_autonomous_market_monitor"

        fun schedulePeriodic(context: Context) {
            runCatching {
                if (!WorkManager.isInitialized()) {
                    WorkManager.initialize(
                        context.applicationContext,
                        androidx.work.Configuration.Builder().build()
                    )
                }
                val request = PeriodicWorkRequestBuilder<MarketMonitorWorker>(15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}
