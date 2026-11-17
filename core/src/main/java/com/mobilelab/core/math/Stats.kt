package com.mobilelab.core.math

/** Small statistics helpers shared by the latency survey and feature extractor. */
object Stats {

    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun mean(values: DoubleArray): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun stdev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        var acc = 0.0
        for (v in values) {
            val d = v - m
            acc += d * d
        }
        return kotlin.math.sqrt(acc / (values.size - 1))
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = (p.coerceIn(0.0, 1.0)) * (sorted.size - 1)
        val low = kotlin.math.floor(rank).toInt()
        val high = kotlin.math.ceil(rank).toInt()
        if (low == high) return sorted[low]
        val frac = rank - low
        return sorted[low] * (1 - frac) + sorted[high] * frac
    }

    fun rms(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var acc = 0.0
        for (v in values) acc += v * v
        return kotlin.math.sqrt(acc / values.size)
    }

    fun min(values: List<Double>): Double = values.minOrNull() ?: 0.0

    fun max(values: List<Double>): Double = values.maxOrNull() ?: 0.0
}
