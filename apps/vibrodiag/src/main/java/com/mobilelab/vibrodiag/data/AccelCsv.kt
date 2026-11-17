package com.mobilelab.vibrodiag.data

import com.mobilelab.core.csv.CsvBuilder
import java.io.File
import kotlin.math.sqrt

/** One accelerometer reading as delivered by SensorEvent.timestamp. */
data class AccelSample(val tNs: Long, val x: Double, val y: Double, val z: Double)

/**
 * Reader/writer for the accel CSV defined by DATA_DICTIONARY 3.2:
 * t_ns, t_ms, x, y, z, mag_g, sample_rate_hz. Units are m/s^2, t_ms is relative
 * to the audio recording start and sample_rate_hz is the measured rate.
 */
object AccelCsv {

    const val GRAVITY = 9.80665
    const val SAMPLE_RATE_COLUMN = "sample_rate_hz"

    val header: List<String> = listOf("t_ns", "t_ms", "x", "y", "z", "mag_g", "sample_rate_hz")

    data class Series(
        val tNs: LongArray,
        val tMs: DoubleArray,
        val x: DoubleArray,
        val y: DoubleArray,
        val z: DoubleArray,
        val sampleRateHz: Double,
    ) {
        val size: Int get() = x.size
    }

    fun emptySeries(): Series = Series(LongArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.0)

    fun write(file: File, samples: List<AccelSample>, baseNanos: Long, sampleRateHz: Double) {
        val builder = CsvBuilder(header)
        for (sample in samples) {
            val tMs = (sample.tNs - baseNanos) / 1_000_000.0
            val mag = sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z) / GRAVITY
            builder.row(sample.tNs, maxOf(0.0, tMs), sample.x, sample.y, sample.z, mag, sampleRateHz)
        }
        builder.write(file)
    }

    fun read(file: File): Series {
        val table = CsvReader.readTable(file) ?: return emptySeries()
        if (table.rows.isEmpty()) return emptySeries()
        val n = table.rows.size
        val tNs = LongArray(n)
        val tMs = DoubleArray(n)
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val z = DoubleArray(n)
        var firstNs = Long.MAX_VALUE
        var lastNs = Long.MIN_VALUE
        for (i in 0 until n) {
            val row = table.rows[i]
            tNs[i] = table.long(row, "t_ns")
            tMs[i] = table.double(row, "t_ms")
            x[i] = table.double(row, "x")
            y[i] = table.double(row, "y")
            z[i] = table.double(row, "z")
            if (tNs[i] > 0L) {
                if (tNs[i] < firstNs) firstNs = tNs[i]
                if (tNs[i] > lastNs) lastNs = tNs[i]
            }
        }
        val fromColumn = table.rows.firstNotNullOfOrNull { row ->
            table.double(row, SAMPLE_RATE_COLUMN).takeIf { it.isFinite() && it > 0.0 }
        }
        val derived = if (lastNs > firstNs) (n - 1) * 1e9 / (lastNs - firstNs).toDouble() else 0.0
        val rate = fromColumn ?: derived
        return Series(tNs, tMs, x, y, z, rate)
    }
}
