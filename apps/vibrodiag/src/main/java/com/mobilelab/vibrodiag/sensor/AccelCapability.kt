package com.mobilelab.vibrodiag.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.mobilelab.vibrodiag.model.AccelMode

/** Resolved accelerometer capability and the sampling period actually requested. */
data class AccelCapability(
    val sensorName: String = "不可用",
    val vendor: String = "",
    val minDelayUs: Int = 0,
    val maxDelayUs: Int = 0,
    val maxRateHz: Double = 0.0,
    val supports200Hz: Boolean = false,
    val requestedMode: AccelMode = AccelMode.AUTO,
    val resolvedPeriodUs: Int = FALLBACK_PERIOD_US,
    val resolvedRateHz: Double = 0.0,
    val note: String = "",
) {
    companion object {
        const val HIGH_RATE_PERIOD_US = 5000
        const val FALLBACK_PERIOD_US = 20000
    }
}

/**
 * 200 Hz requires API 31+ and a sensor with minDelay <= 5000 us; devices without
 * the high-rate capability fall back to SENSOR_DELAY_GAME (~50 Hz) and the real
 * rate is measured from SensorEvent.timestamp anyway.
 */
object AccelProbe {

    /** Resolves the sampling period the recorder should request for [mode]. */
    fun resolvePeriodUs(context: Context, mode: AccelMode): Int =
        probe(context, mode).resolvedPeriodUs

    fun probe(context: Context, mode: AccelMode): AccelCapability {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return AccelCapability(requestedMode = mode, note = "无传感器服务")
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return AccelCapability(requestedMode = mode, note = "设备没有加速度计")
        val minDelay = sensor.minDelay
        val maxDelay = sensor.maxDelay
        val maxRate = if (minDelay > 0) 1_000_000.0 / minDelay else 0.0
        val supports200 = minDelay in 1..AccelCapability.HIGH_RATE_PERIOD_US
        val periodUs = when (mode) {
            AccelMode.RATE_200 ->
                if (supports200) AccelCapability.HIGH_RATE_PERIOD_US else maxOf(minDelay, AccelCapability.HIGH_RATE_PERIOD_US)
            AccelMode.RATE_50 -> AccelCapability.FALLBACK_PERIOD_US
            AccelMode.AUTO ->
                if (supports200) AccelCapability.HIGH_RATE_PERIOD_US
                else if (minDelay > 0) minDelay
                else AccelCapability.FALLBACK_PERIOD_US
        }
        val resolvedRate = if (periodUs > 0) 1_000_000.0 / periodUs else 0.0
        val note = when {
            supports200 && mode != AccelMode.RATE_50 -> "支持 200 Hz（HIGH_SAMPLING_RATE_SENSORS）"
            mode == AccelMode.RATE_50 -> "按 50 Hz 模式采集"
            else -> "不支持 200 Hz，已回退到最小可用周期 " + minDelay + " us"
        }
        return AccelCapability(
            sensorName = sensor.name,
            vendor = sensor.vendor,
            minDelayUs = minDelay,
            maxDelayUs = maxDelay,
            maxRateHz = maxRate,
            supports200Hz = supports200,
            requestedMode = mode,
            resolvedPeriodUs = periodUs,
            resolvedRateHz = resolvedRate,
            note = note,
        )
    }
}