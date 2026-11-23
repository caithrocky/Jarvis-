package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.analysis.TechnicalAnalysisEngine
import com.example.domain.model.OhlcCandle
import com.example.domain.model.TechnicalIndicators
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.MutedCyanSlate
import com.example.ui.theme.ProfitEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun MarketChartCanvas(
    candles: List<OhlcCandle>,
    indicators: TechnicalIndicators?,
    modifier: Modifier = Modifier
) {
    if (candles.size < 2) {
        Text(
            text = "Loading historical price candles...",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedCyanSlate,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    val visible = candles.takeLast(56)
    val closes = visible.map { it.close }
    val ema9Series = TechnicalAnalysisEngine.calculateEmaSeries(closes, 9)
    val ema21Series = TechnicalAnalysisEngine.calculateEmaSeries(closes, 21)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "EMA9",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricBlue,
                modifier = Modifier.padding(end = 10.dp)
            )
            Text(
                text = "EMA21",
                style = MaterialTheme.typography.labelSmall,
                color = WarningAmber,
                modifier = Modifier.padding(end = 10.dp)
            )
            Text(
                text = "S/R & Fib 61.8%",
                style = MaterialTheme.typography.labelSmall,
                color = ArcCyan
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        ) {
            val w = size.width
            val h = size.height
            val minLow = visible.minOf { it.low }
            val maxHigh = visible.maxOf { it.high }
            val span = (maxHigh - minLow).coerceAtLeast(1e-6)

            fun yForPrice(price: Double): Float {
                val norm = ((price - minLow) / span).toFloat().coerceIn(0f, 1f)
                return h - (norm * (h * 0.88f) + h * 0.06f)
            }

            // Grid lines
            for (i in 1..4) {
                val gy = h * (i / 5f)
                drawLine(
                    color = Color.White.copy(alpha = 0.06f),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Support, Resistance & Fibonacci 61.8% dashed overlays
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            indicators?.let { ind ->
                ind.supportLevels.firstOrNull()?.let { sup ->
                    if (sup in minLow..maxHigh) {
                        val sy = yForPrice(sup)
                        drawLine(ProfitEmerald.copy(alpha = 0.55f), Offset(0f, sy), Offset(w, sy), 1.2.dp.toPx(), pathEffect = dash)
                    }
                }
                ind.resistanceLevels.firstOrNull()?.let { res ->
                    if (res in minLow..maxHigh) {
                        val ry = yForPrice(res)
                        drawLine(AlertCrimson.copy(alpha = 0.55f), Offset(0f, ry), Offset(w, ry), 1.2.dp.toPx(), pathEffect = dash)
                    }
                }
                val fib618 = ind.fibonacci.level618
                if (fib618 in minLow..maxHigh) {
                    val fy = yForPrice(fib618)
                    drawLine(ArcCyan.copy(alpha = 0.45f), Offset(0f, fy), Offset(w, fy), 1.dp.toPx(), pathEffect = dash)
                }
            }

            // Candlesticks
            val step = w / visible.size.toFloat()
            val bodyWidth = (step * 0.58f).coerceAtLeast(3f)

            visible.forEachIndexed { idx, c ->
                val cx = idx * step + step / 2f
                val bull = c.close >= c.open
                val candleColor = if (bull) ProfitEmerald else AlertCrimson
                val yHigh = yForPrice(c.high)
                val yLow = yForPrice(c.low)
                val yOpen = yForPrice(c.open)
                val yClose = yForPrice(c.close)

                drawLine(
                    color = candleColor,
                    start = Offset(cx, yHigh),
                    end = Offset(cx, yLow),
                    strokeWidth = 1.4.dp.toPx()
                )
                val top = minOf(yOpen, yClose)
                val bodyH = kotlin.math.abs(yClose - yOpen).coerceAtLeast(2f)
                drawRect(
                    color = candleColor,
                    topLeft = Offset(cx - bodyWidth / 2f, top),
                    size = Size(bodyWidth, bodyH)
                )
            }

            // EMA9 & EMA21 curves
            fun drawCurve(series: List<Double>, color: Color) {
                if (series.size < 2) return
                val path = Path()
                series.forEachIndexed { idx, v ->
                    val x = idx * step + step / 2f
                    val y = yForPrice(v)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = color, style = Stroke(width = 1.8.dp.toPx()))
            }
            drawCurve(ema9Series, ElectricBlue)
            drawCurve(ema21Series, WarningAmber)
        }
    }
}
