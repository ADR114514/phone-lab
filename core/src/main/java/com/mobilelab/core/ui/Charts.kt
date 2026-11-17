package com.mobilelab.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mobilelab.core.ui.theme.LabColors
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Dependency-free line chart. Values are plotted left-to-right; missing
 * samples are dropped by the caller. The chart intentionally has no text so it
 * stays cheap to recompose during 1 Hz monitoring screens.
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = LabColors.Primary,
    height: androidx.compose.ui.unit.Dp = 140.dp,
    minValue: Float? = null,
    maxValue: Float? = null,
    showDots: Boolean = true,
) {
    val safeValues = values.filter { it.isFinite() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(LabColors.SurfaceHigh),
    ) {
        if (safeValues.isEmpty()) {
            Text(
                text = "等待数据",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Canvas(Modifier.fillMaxWidth().height(height).padding(10.dp)) {
                val w = size.width
                val h = size.height
                val lo = minValue ?: (safeValues.minOrNull() ?: 0f)
                val hi = maxValue ?: (safeValues.maxOrNull() ?: 1f)
                val span = if (hi - lo < 1e-6f) 1f else hi - lo

                // Grid
                val gridColor = LabColors.Grid
                for (i in 0..3) {
                    val y = h * i / 3f
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }

                val stepX = if (safeValues.size > 1) w / (safeValues.size - 1) else w
                val points = safeValues.mapIndexed { index, v ->
                    val norm = ((v - lo) / span).coerceIn(0f, 1f)
                    Offset(index * stepX, h - norm * h)
                }

                // Area fill
                val area = Path().apply {
                    moveTo(points.first().x, h)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, h)
                    close()
                }
                drawPath(area, color.copy(alpha = 0.12f))

                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(line, color, style = Stroke(width = 2.2f))
                if (showDots) {
                    points.forEach { drawCircle(color, radius = 3f, center = it) }
                }
                drawCircle(color, radius = 5f, center = points.last())
            }
        }
    }
}

/** Compact min/avg/max annotation used under a chart. */
@Composable
fun ChartLegend(min: Float?, avg: Float?, max: Float?, unit: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem("MIN", min, unit)
        LegendItem("AVG", avg, unit)
        LegendItem("MAX", max, unit)
    }
}

@Composable
private fun LegendItem(label: String, value: Float?, unit: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        Text(
            text = if (value == null) "--" else String.format(Locale.US, "%.1f", value) + " " + unit,
            style = MaterialTheme.typography.labelMedium,
            color = LabColors.TextPrimary,
        )
    }
}

/** Simple vertical bar series (used for per-point / per-condition summaries). */
@Composable
fun BarSeries(
    items: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    color: Color = LabColors.Primary,
    height: androidx.compose.ui.unit.Dp = 120.dp,
    unit: String = "",
    highlightIndex: Int = -1,
) {
    if (items.isEmpty()) {
        EmptyState("暂无数据", "完成一次采集后这里会显示对比图")
        return
    }
    val maxValue = max(items.maxOf { it.second }, 1e-6f)
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { index, (label, value) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f", value),
                    style = MaterialTheme.typography.labelSmall,
                    color = LabColors.TextSecondary,
                )
                Spacer(Modifier.height(2.dp))
                val fraction = (value / maxValue).coerceIn(0.02f, 1f)
                Box(
                    Modifier
                        .width(18.dp)
                        .height((height.value * 0.7f * fraction).dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(
                            if (index == highlightIndex) color
                            else color.copy(alpha = 0.55f)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = LabColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
    if (unit.isNotEmpty()) {
        Text("单位：$unit", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
    }
}
