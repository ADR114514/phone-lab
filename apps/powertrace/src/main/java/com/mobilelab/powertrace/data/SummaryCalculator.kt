package com.mobilelab.powertrace.data

/**
 * 工况汇总（数据字典 1.3）的纯计算。
 *
 * 口径说明：
 *  - avg_power_w / peak_power_w 取 6 个采样点 power_w 的均值与最大值；
 *  - energy_wh 用矩形积分：Σ power_w × 300 s / 3600（6 点覆盖 1800 s）；
 *  - level_drop_pct = start - end（放电为正）；
 *  - temp_rise_c = temp_end - temp_start；
 *  - thermal_rise_events = thermal_status 相邻点抬升次数；
 *  - net_rx_mb / net_tx_mb = Σ 平均 kbps × 300 s 换算成 MB。
 */
data class ConditionSummary(
    val condition: ConditionId,
    val block: Char,
    val durationMin: Double,
    val startLevelPct: Int?,
    val endLevelPct: Int?,
    val levelDropPct: Int?,
    val avgPowerW: Double?,
    val peakPowerW: Double?,
    val energyWh: Double?,
    val avgCurrentMa: Double?,
    val tempStartC: Double?,
    val tempEndC: Double?,
    val tempRiseC: Double?,
    val thermalMax: Int?,
    val thermalRiseEvents: Int?,
    val avgCpuFreqMhz: Int?,
    val avgScreenBrightnessPct: Int?,
    val netRxMb: Double?,
    val netTxMb: Double?,
    val notes: String,
    val sampleCount: Int,
    val samples: List<SessionSample> = emptyList(),
) {
    fun toRow(): List<Any?> = listOf(
        condition.id,
        condition.label,
        Fmt.dec1(durationMin),
        Fmt.int(startLevelPct),
        Fmt.int(endLevelPct),
        Fmt.int(levelDropPct),
        Fmt.dec3(avgPowerW),
        Fmt.dec3(peakPowerW),
        Fmt.dec3(energyWh),
        Fmt.dec1(avgCurrentMa),
        Fmt.dec1(tempStartC),
        Fmt.dec1(tempEndC),
        Fmt.dec1(tempRiseC),
        Fmt.int(thermalMax),
        Fmt.int(thermalRiseEvents),
        Fmt.int(avgCpuFreqMhz),
        Fmt.int(avgScreenBrightnessPct),
        Fmt.dec2(netRxMb),
        Fmt.dec2(netTxMb),
        notes,
    )
}

object SummaryCalculator {

    /** 由真实落盘的 6 行采样点计算一个工况的汇总；调用方负责给出实际时长。 */
    fun summarize(
        condition: ConditionId,
        block: Char,
        samples: List<SessionSample>,
        durationMin: Double,
        extraNotes: String = "",
    ): ConditionSummary {
        val ordered = samples.sortedBy { it.sampleIndex }
        val powers = ordered.map { it.powerW }
        val currentsMa = ordered.map { s -> s.currentNowUa?.let { Math.abs(it.toLong()) / 1000.0 } }
        val levels = ordered.map { it.batteryLevelPct }
        val temps = ordered.map { it.batteryTempC }
        val thermals = ordered.map { it.thermalStatus }
        val cpuFreqs = ordered.map { it.cpuFreqAvgMhz }
        val brightness = ordered.map { it.screenBrightnessPct }
        val rx = ordered.map { it.netRxKbps }
        val tx = ordered.map { it.netTxKbps }

        val autoNotes = buildList {
            if (ordered.isNotEmpty() && ordered.size < SamplingConfig.SAMPLES_PER_CONDITION) {
                add("仅采到 ${ordered.size}/6 点")
            }
            val late = ordered.count { it.note.contains("late_sample") }
            if (late > 0) add("迟到样本 ${late} 个")
            val charging = ordered.count { it.charging }
            if (charging > 0) add("采集期间充电 ${charging} 点（异常）")
            val missingPower = powers.count { it == null }
            if (missingPower > 0 && missingPower == ordered.size) add("功率字段不可用（机型未提供电流）")
        }
        val note = (listOf(extraNotes) + autoNotes).filter { it.isNotBlank() }.joinToString("; ")

        val startLevel = levels.firstOrNull()
        val endLevel = levels.lastOrNull()
        // 掉电 = 起点 - 终点（放电为正）；与 diff(end-start) 方向相反，单独计算。
        val levelDrop = if (startLevel != null && endLevel != null) startLevel - endLevel else null

        return ConditionSummary(
            condition = condition,
            block = block,
            durationMin = durationMin,
            startLevelPct = startLevel,
            endLevelPct = endLevel,
            levelDropPct = levelDrop,
            avgPowerW = PowerMath.avg(powers),
            peakPowerW = PowerMath.max(powers),
            energyWh = PowerMath.energyWh(powers),
            avgCurrentMa = PowerMath.avg(currentsMa),
            tempStartC = temps.firstOrNull(),
            tempEndC = temps.lastOrNull(),
            tempRiseC = PowerMath.diffDouble(temps.firstOrNull(), temps.lastOrNull()),
            thermalMax = PowerMath.maxInt(thermals),
            thermalRiseEvents = PowerMath.thermalRiseEvents(thermals),
            avgCpuFreqMhz = PowerMath.avgInt(cpuFreqs),
            avgScreenBrightnessPct = PowerMath.avgInt(brightness),
            netRxMb = PowerMath.megabytesFromKbps(rx),
            netTxMb = PowerMath.megabytesFromKbps(tx),
            notes = note,
            sampleCount = ordered.size,
            samples = ordered,
        )
    }
}
