package com.mobilelab.powertrace

import com.mobilelab.powertrace.data.SessionSample

/** 测试用采样点构造器（6 点工况的标准序列）。 */
object TestSamples {

    fun standard(
        sampleIndex: Int = 1,
        elapsedSec: Long = (sampleIndex - 1) * 300L,
        levelPct: Int = 80,
        voltageMv: Int = 4200,
        currentUa: Int = 500_000,
        tempC: Double = 30.0,
        thermal: Int = 1,
        note: String = "",
    ): SessionSample = SessionSample(
        sampleIndex = sampleIndex,
        elapsedSec = elapsedSec,
        timestampMs = 1_730_000_000_000L + elapsedSec * 1000L,
        batteryLevelPct = levelPct,
        batteryVoltageMv = voltageMv,
        batteryTempC = tempC,
        currentNowUa = currentUa,
        chargeCounterUah = 4_000_000 - sampleIndex * 100_000,
        energyCounterNwh = 15_000_000_000L,
        thermalStatus = thermal,
        cpuTempC = 36.5,
        cpuFreqAvgMhz = 1800,
        cpuFreqMaxMhz = 2400,
        batteryCycleCount = 120,
        batteryHealth = 2,
        charging = false,
        screenOn = true,
        screenBrightnessPct = 60,
        ambientLux = 320.0,
        netType = "WIFI",
        netRxKbps = 1000.0,
        netTxKbps = 500.0,
        memAvailableMb = 3000,
        memTotalMb = 7800,
        fps = 59.9,
        foregroundPkg = "com.example.video",
        note = note,
    )

    fun series(conditionSamples: Int = 6): List<SessionSample> =
        (1..conditionSamples).map { index ->
            standard(
                sampleIndex = index,
                levelPct = 80 - index,
                tempC = 30.0 + index,
                thermal = 1 + (index / 3),
            )
        }
}
