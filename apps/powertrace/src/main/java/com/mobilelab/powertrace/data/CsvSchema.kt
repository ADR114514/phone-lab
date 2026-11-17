package com.mobilelab.powertrace.data

import com.mobilelab.core.time.TimeFmt
import java.time.Instant

/**
 * 数据字典 1.1 / 1.2 / 1.3 / 1.4 的列序与行构造。
 *
 * 这里是整个 App 与 analysis/power 的接缝：列名、顺序、单位、空值约定
 * 一旦改动必须同步修改 docs/DATA_DICTIONARY.md。单元测试 [CsvSchema] 会逐列校验。
 */
object CsvSchema {

    /** 1.1 会话 CSV：32 列。 */
    val SESSION_HEADER: List<String> = listOf(
        "session_id",
        "condition_id",
        "condition_label",
        "sample_index",
        "elapsed_sec",
        "timestamp_iso",
        "timestamp_utc",
        "battery_level_pct",
        "battery_voltage_mv",
        "battery_temp_c",
        "current_now_ua",
        "charge_counter_uah",
        "energy_counter_nwh",
        "power_w",
        "thermal_status",
        "cpu_temp_c",
        "cpu_freq_mhz_avg",
        "cpu_freq_mhz_max",
        "battery_cycle_count",
        "battery_health",
        "charging",
        "screen_on",
        "screen_brightness_pct",
        "ambient_lux",
        "net_type",
        "net_rx_kbps",
        "net_tx_kbps",
        "mem_available_mb",
        "mem_total_mb",
        "fps",
        "foreground_pkg",
        "note",
    )

    /** 1.2 批次 CSV：campaign_id + block + 1.1（去掉 session_id） = 33 列。 */
    val CAMPAIGN_HEADER: List<String> =
        listOf("campaign_id", "block") + SESSION_HEADER.drop(1)

    /** 1.3 工况汇总 CSV：21 列。 */
    val SUMMARY_HEADER: List<String> = listOf(
        "condition_id",
        "condition_label",
        "duration_min",
        "start_level_pct",
        "end_level_pct",
        "level_drop_pct",
        "avg_power_w",
        "peak_power_w",
        "energy_wh",
        "avg_current_ma",
        "temp_start_c",
        "temp_end_c",
        "temp_rise_c",
        "thermal_max",
        "thermal_rise_events",
        "avg_cpu_freq_mhz",
        "avg_screen_brightness_pct",
        "net_rx_mb",
        "net_tx_mb",
        "notes",
    )

    /** 1.4 原始高频序列 CSV：13 列。 */
    val RAW_HEADER: List<String> = listOf(
        "elapsed_sec",
        "timestamp_iso",
        "battery_level_pct",
        "current_now_ua",
        "voltage_mv",
        "battery_temp_c",
        "thermal_status",
        "cpu_temp_c",
        "cpu_freq_mhz_avg",
        "screen_on",
        "fps",
        "net_rx_kbps",
        "net_tx_kbps",
    )

    /** 会话 CSV 数据行。空值写空字段，布尔写 1/0。 */
    fun sessionRow(sessionId: String, condition: ConditionId, sample: SessionSample): List<Any?> = listOf(
        sessionId,
        condition.id,
        condition.label,
        sample.sampleIndex,
        sample.elapsedSec,
        TimeFmt.isoLocal(sample.timestampMs),
        TimeFmt.isoUtc(sample.timestampMs),
        Fmt.int(sample.batteryLevelPct),
        Fmt.int(sample.batteryVoltageMv),
        Fmt.dec1(sample.batteryTempC),
        Fmt.int(sample.currentNowUa),
        Fmt.int(sample.chargeCounterUah),
        Fmt.long(sample.energyCounterNwh),
        Fmt.dec3(sample.powerW),
        Fmt.int(sample.thermalStatus),
        Fmt.dec1(sample.cpuTempC),
        Fmt.int(sample.cpuFreqAvgMhz),
        Fmt.int(sample.cpuFreqMaxMhz),
        Fmt.int(sample.batteryCycleCount),
        Fmt.int(sample.batteryHealth),
        if (sample.charging) 1 else 0,
        if (sample.screenOn) 1 else 0,
        Fmt.int(sample.screenBrightnessPct),
        Fmt.dec1(sample.ambientLux),
        sample.netType,
        Fmt.dec1(sample.netRxKbps),
        Fmt.dec1(sample.netTxKbps),
        Fmt.int(sample.memAvailableMb),
        Fmt.int(sample.memTotalMb),
        Fmt.dec1(sample.fps),
        sample.foregroundPkg ?: "",
        sample.note,
    )

    /** 批次 CSV 数据行：campaign_id + block + 会话行去掉 session_id。 */
    fun campaignRow(
        campaignId: String,
        block: Char,
        condition: ConditionId,
        sample: SessionSample,
    ): List<Any?> = listOf(campaignId, block.toString()) + sessionRow("", condition, sample).drop(1)

    /** 原始序列数据行。 */
    fun rawRow(sample: RawSample): List<Any?> = listOf(
        sample.elapsedSec,
        TimeFmt.isoLocal(sample.timestampMs),
        Fmt.int(sample.batteryLevelPct),
        Fmt.int(sample.currentNowUa),
        Fmt.int(sample.voltageMv),
        Fmt.dec1(sample.batteryTempC),
        Fmt.int(sample.thermalStatus),
        Fmt.dec1(sample.cpuTempC),
        Fmt.int(sample.cpuFreqAvgMhz),
        if (sample.screenOn) 1 else 0,
        Fmt.dec1(sample.fps),
        Fmt.dec1(sample.netRxKbps),
        Fmt.dec1(sample.netTxKbps),
    )

    /**
     * 解析会话 CSV（结果页与派生 CSV 都从真实落盘文件读取，保证可溯源）。
     * 按表头名取列，容忍未来列顺序调整。
     */
    fun parseSessionCsv(text: String): List<SessionSample> {
        val rows = CsvText.parse(text)
        if (rows.size < 2) return emptyList()
        val header = rows[0]
        fun idx(name: String): Int = header.indexOf(name)
        val iTimestampUtc = idx("timestamp_utc")
        val iTimestampIso = idx("timestamp_iso")
        val iIndex = idx("sample_index")
        val iElapsed = idx("elapsed_sec")
        if (iIndex < 0 || iElapsed < 0) return emptyList()

        fun cell(row: List<String>, index: Int): String? =
            if (index in row.indices) row[index] else null

        return rows.drop(1).mapNotNull { row ->
            val ms: Long = when {
                iTimestampUtc >= 0 -> runCatching {
                    Instant.parse(cell(row, iTimestampUtc)!!.trim()).toEpochMilli()
                }.getOrNull()
                else -> null
            } ?: when {
                iTimestampIso >= 0 -> runCatching {
                    java.time.OffsetDateTime.parse(cell(row, iTimestampIso)!!.trim()).toInstant().toEpochMilli()
                }.getOrNull()
                else -> null
            } ?: 0L
            val sampleIndex = Fmt.intOrNull(cell(row, iIndex)) ?: return@mapNotNull null
            SessionSample(
                sampleIndex = sampleIndex,
                elapsedSec = Fmt.longOrNull(cell(row, iElapsed)) ?: SamplingConfig.elapsedSecForSample(sampleIndex),
                timestampMs = ms,
                batteryLevelPct = Fmt.intOrNull(cell(row, idx("battery_level_pct"))),
                batteryVoltageMv = Fmt.intOrNull(cell(row, idx("battery_voltage_mv"))),
                batteryTempC = Fmt.doubleOrNull(cell(row, idx("battery_temp_c"))),
                currentNowUa = Fmt.intOrNull(cell(row, idx("current_now_ua"))),
                chargeCounterUah = Fmt.intOrNull(cell(row, idx("charge_counter_uah"))),
                energyCounterNwh = Fmt.longOrNull(cell(row, idx("energy_counter_nwh"))),
                thermalStatus = Fmt.intOrNull(cell(row, idx("thermal_status"))),
                cpuTempC = Fmt.doubleOrNull(cell(row, idx("cpu_temp_c"))),
                cpuFreqAvgMhz = Fmt.intOrNull(cell(row, idx("cpu_freq_mhz_avg"))),
                cpuFreqMaxMhz = Fmt.intOrNull(cell(row, idx("cpu_freq_mhz_max"))),
                batteryCycleCount = Fmt.intOrNull(cell(row, idx("battery_cycle_count"))),
                batteryHealth = Fmt.intOrNull(cell(row, idx("battery_health"))),
                charging = Fmt.intOrNull(cell(row, idx("charging"))) == 1,
                screenOn = Fmt.intOrNull(cell(row, idx("screen_on"))) == 1,
                screenBrightnessPct = Fmt.intOrNull(cell(row, idx("screen_brightness_pct"))),
                ambientLux = Fmt.doubleOrNull(cell(row, idx("ambient_lux"))),
                netType = cell(row, idx("net_type"))?.trim().orEmpty().ifEmpty { "NONE" },
                netRxKbps = Fmt.doubleOrNull(cell(row, idx("net_rx_kbps"))),
                netTxKbps = Fmt.doubleOrNull(cell(row, idx("net_tx_kbps"))),
                memAvailableMb = Fmt.intOrNull(cell(row, idx("mem_available_mb"))),
                memTotalMb = Fmt.intOrNull(cell(row, idx("mem_total_mb"))),
                fps = Fmt.doubleOrNull(cell(row, idx("fps"))),
                foregroundPkg = cell(row, idx("foreground_pkg"))?.trim()?.ifEmpty { null },
                note = cell(row, idx("note"))?.trim().orEmpty(),
            )
        }
    }
}
