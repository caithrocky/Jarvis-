package com.example.analysis

import com.example.domain.model.RiskLimits
import com.example.domain.model.TradingOperatingMode

sealed class RiskValidationResult {
    data object Approved : RiskValidationResult()
    data class Blocked(val reason: String, val triggerAutoSuspend: Boolean = false) : RiskValidationResult()
}

object TradingRiskEngine {

    fun validateOrder(
        mode: TradingOperatingMode,
        riskLimits: RiskLimits,
        symbol: String,
        amountUsd: Double,
        stopLoss: Double?,
        takeProfit: Double?,
        openPositionsCount: Int,
        explicitUserApproval: Boolean
    ): RiskValidationResult {
        if (riskLimits.emergencyStopActive) {
            return RiskValidationResult.Blocked("Emergency Stop is active. All order execution is halted.")
        }

        if (mode == TradingOperatingMode.OBSERVATION) {
            return RiskValidationResult.Blocked("Observation Mode active: switch to Assistant or Automated Mode to execute trades.")
        }

        if (mode == TradingOperatingMode.ASSISTANT && !explicitUserApproval) {
            return RiskValidationResult.Blocked("Assistant Mode requires explicit user confirmation before executing.")
        }

        if (mode == TradingOperatingMode.AUTOMATED && !riskLimits.automatedTradingAuthorized) {
            return RiskValidationResult.Blocked("Automated Trading is disabled by default and must be explicitly authorized.")
        }

        if (riskLimits.currentDailyLossUsd >= riskLimits.maxDailyLossUsd) {
            return RiskValidationResult.Blocked(
                reason = "Daily loss limit reached ($${"%.2f".format(riskLimits.currentDailyLossUsd)} / $${"%.2f".format(riskLimits.maxDailyLossUsd)}). Automated trading suspended.",
                triggerAutoSuspend = mode == TradingOperatingMode.AUTOMATED
            )
        }

        if (amountUsd <= 0.0) {
            return RiskValidationResult.Blocked("Trade amount must be greater than $0.00.")
        }

        if (amountUsd > riskLimits.maxTradeAmountUsd) {
            return RiskValidationResult.Blocked(
                "Order amount $${"%.2f".format(amountUsd)} exceeds max trade limit $${"%.2f".format(riskLimits.maxTradeAmountUsd)}."
            )
        }

        if (openPositionsCount >= riskLimits.maxOpenPositions) {
            return RiskValidationResult.Blocked(
                "Open position limit reached ($openPositionsCount / ${riskLimits.maxOpenPositions})."
            )
        }

        if (riskLimits.requireStopLoss && (stopLoss == null || stopLoss <= 0.0)) {
            return RiskValidationResult.Blocked("Risk policy requires a valid Stop-Loss on every trade.")
        }

        if (riskLimits.requireTakeProfit && (takeProfit == null || takeProfit <= 0.0)) {
            return RiskValidationResult.Blocked("Risk policy requires a valid Take-Profit target on every trade.")
        }

        if (riskLimits.allowedAssets.isNotEmpty() && !riskLimits.allowedAssets.contains(symbol)) {
            return RiskValidationResult.Blocked("$symbol is not in your authorized trading asset whitelist.")
        }

        return RiskValidationResult.Approved
    }
}
