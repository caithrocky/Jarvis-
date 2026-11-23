package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.analysis.TechnicalAnalysisEngine
import com.example.domain.model.DataFreshness
import com.example.domain.model.MarketQuote
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.LocalJarvisColors
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

/**
 * Compact live market ticker ribbon with pulsing telemetry indicator,
 * clickable ticker pills, and Room-cached timestamp fallback.
 */
@Composable
fun LiveMarketTickerRibbon(
    quotes: List<MarketQuote>,
    selectedSymbol: String?,
    isRefreshing: Boolean,
    onSelectTicker: (MarketQuote) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val jc = LocalJarvisColors.current
    val pulseTransition = rememberInfiniteTransition(label = "ticker_live_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasLive = quotes.any { it.freshness == DataFreshness.LIVE }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(if (hasLive) pulseAlpha else 0.5f)
                        .clip(CircleShape)
                        .background(if (hasLive) ProfitEmerald else WarningAmber)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "LIVE MARKET TICKERS",
                    style = MaterialTheme.typography.titleMedium,
                    color = ArcCyan
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(
                    text = "${quotes.size} WATCHED",
                    color = ElectricBlue
                )
            }

            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("ticker_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh live market tickers",
                    tint = ElectricBlue
                )
            }
        }

        if (isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(bottom = 6.dp),
                color = ElectricBlue,
                trackColor = ElectricBlue.copy(alpha = 0.15f)
            )
        }

        if (quotes.isEmpty()) {
            HudGlassCard(contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Storage,
                        contentDescription = null,
                        tint = jc.mutedSlate,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "No configured ticker symbols cached yet. Tap refresh or enable symbols in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = jc.mutedSlate
                    )
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.testTag("live_market_ticker_list")
            ) {
                items(quotes, key = { it.symbol }) { quote ->
                    LiveMarketTickerCard(
                        quote = quote,
                        isSelected = quote.symbol == selectedSymbol,
                        onClick = { onSelectTicker(quote) }
                    )
                }
            }
        }
    }
}

@Composable
fun LiveMarketTickerCard(
    quote: MarketQuote,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val jc = LocalJarvisColors.current

    // Detect price tick changes to animate border highlight briefly
    var previousSeenPrice by remember(quote.symbol) { mutableDoubleStateOf(quote.price) }
    val tickDelta = quote.price - previousSeenPrice
    LaunchedEffect(quote.price) {
        previousSeenPrice = quote.price
    }

    val moveColor = when {
        quote.freshness == DataFreshness.UNAVAILABLE || quote.price <= 0.0 -> jc.mutedSlate
        quote.changePercent >= 0.0 -> ProfitEmerald
        else -> AlertCrimson
    }

    val targetBorderColor = when {
        tickDelta > 0.0 -> ProfitEmerald.copy(alpha = 0.7f)
        tickDelta < 0.0 -> AlertCrimson.copy(alpha = 0.7f)
        isSelected -> ElectricBlue
        else -> jc.hairlineGlow
    }
    val animatedBorder by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(400),
        label = "ticker_border_flash"
    )

    Surface(
        modifier = modifier
            .width(196.dp)
            .minimumInteractiveComponentSize()
            .testTag("ticker_card_${quote.symbol}")
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) jc.glassElevated else jc.glassSurface,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, animatedBorder),
        tonalElevation = if (isSelected) 4.dp else 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = quote.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) ElectricBlue else MaterialTheme.colorScheme.onSurface
                )
                DataFreshnessBadge(freshness = quote.freshness)
            }

            Text(
                text = quote.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = jc.mutedSlate,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = if (quote.price > 0.0) TechnicalAnalysisEngine.formatPrice(quote.price) else "N/A",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (quote.changePercent >= 0.0) {
                            Icons.AutoMirrored.Filled.TrendingUp
                        } else {
                            Icons.AutoMirrored.Filled.TrendingDown
                        },
                        contentDescription = if (quote.changePercent >= 0.0) "Price up" else "Price down",
                        tint = moveColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (quote.price > 0.0) "${"%+.2f".format(quote.changePercent)}%" else "--",
                        style = MaterialTheme.typography.labelMedium,
                        color = moveColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 24h Range indicator bar
            if (quote.high24h > quote.low24h && quote.price > 0.0) {
                val rangeFraction = ((quote.price - quote.low24h) / (quote.high24h - quote.low24h))
                    .toFloat()
                    .coerceIn(0.04f, 1f)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(rangeFraction)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(moveColor.copy(alpha = 0.85f))
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "${formatRelativeAge(quote.timestampMs)} • ${quote.sourceName}",
                style = MaterialTheme.typography.labelSmall,
                color = jc.mutedSlate,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatRelativeAge(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Pending"
    val diffSec = ((System.currentTimeMillis() - timestampMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 10L -> "Just now"
        diffSec < 60L -> "${diffSec}s ago"
        diffSec < 3600L -> "${diffSec / 60L}m ago"
        else -> "${diffSec / 3600L}h ago"
    }
}
