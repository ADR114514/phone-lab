package com.mobilelab.signalmap.ui

import com.mobilelab.signalmap.data.SurveyRow
import java.util.Locale

/** Formatting helpers shared by the result table and the metric tiles. */
fun Double?.fmt(decimals: Int = 1): String {
    if (this == null || !isFinite()) return "--"
    return String.format(Locale.US, "%.${decimals}f", this)
}

fun Int?.fmt(): String = this?.toString() ?: "--"

fun Long.mmss(): String {
    val total = (this / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

/** Row aggregate used by the per-point bar charts on the result screen. */
data class PointAggregate(
    val pointId: String,
    val pointName: String,
    val rows: Int,
    val avgRssi: Double?,
    val avgLatency: Double?,
    val avgLossPct: Double?,
    val avgTtfb: Double?,
    val avgApCount: Double?,
)

fun aggregateByPoint(rows: List<SurveyRow>): List<PointAggregate> =
    rows.groupBy { it.pointId }.toSortedMap().map { (pointId, group) ->
        fun meanOf(selector: (SurveyRow) -> Double?): Double? {
            val values = group.mapNotNull(selector)
            return if (values.isEmpty()) null else values.sum() / values.size
        }
        PointAggregate(
            pointId = pointId,
            pointName = group.firstOrNull()?.pointName.orEmpty(),
            rows = group.size,
            avgRssi = meanOf { it.rssiDbm?.toDouble() },
            avgLatency = meanOf { it.latencyAvgMs },
            avgLossPct = meanOf { it.lossPct },
            avgTtfb = meanOf { it.httpTtfbMs },
            avgApCount = meanOf { it.apCount?.toDouble() },
        )
    }
