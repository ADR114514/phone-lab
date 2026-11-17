package com.mobilelab.powertrace

import com.mobilelab.powertrace.data.CampaignEngine
import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.CsvSchema
import com.mobilelab.powertrace.data.CsvText
import com.mobilelab.powertrace.data.Fmt
import com.mobilelab.powertrace.data.SummaryCalculator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数据字典列名/顺序/单位/行数（验收项：24 行、列名与顺序完全一致）。 */
class CsvSchemaTest {

    @Test
    fun sessionHeader_matchesDataDictionaryExactly() {
        val expected = listOf(
            "session_id", "condition_id", "condition_label", "sample_index", "elapsed_sec",
            "timestamp_iso", "timestamp_utc", "battery_level_pct", "battery_voltage_mv", "battery_temp_c",
            "current_now_ua", "charge_counter_uah", "energy_counter_nwh", "power_w", "thermal_status",
            "cpu_temp_c", "cpu_freq_mhz_avg", "cpu_freq_mhz_max", "battery_cycle_count", "battery_health",
            "charging", "screen_on", "screen_brightness_pct", "ambient_lux", "net_type",
            "net_rx_kbps", "net_tx_kbps", "mem_available_mb", "mem_total_mb", "fps",
            "foreground_pkg", "note",
        )
        assertEquals(expected, CsvSchema.SESSION_HEADER)
        assertEquals(32, CsvSchema.SESSION_HEADER.size)
    }

    @Test
    fun campaignHeader_isCampaignIdBlockPlusSessionWithoutSessionId() {
        assertEquals(33, CsvSchema.CAMPAIGN_HEADER.size)
        assertEquals(listOf("campaign_id", "block"), CsvSchema.CAMPAIGN_HEADER.take(2))
        assertEquals(CsvSchema.SESSION_HEADER.drop(1), CsvSchema.CAMPAIGN_HEADER.drop(2))
    }

    @Test
    fun summaryAndRawHeaders_matchSpec() {
        assertEquals(
            listOf(
                "condition_id", "condition_label", "duration_min", "start_level_pct", "end_level_pct",
                "level_drop_pct", "avg_power_w", "peak_power_w", "energy_wh", "avg_current_ma",
                "temp_start_c", "temp_end_c", "temp_rise_c", "thermal_max", "thermal_rise_events",
                "avg_cpu_freq_mhz", "avg_screen_brightness_pct", "net_rx_mb", "net_tx_mb", "notes",
            ),
            CsvSchema.SUMMARY_HEADER,
        )
        assertEquals(13, CsvSchema.RAW_HEADER.size)
        assertEquals("elapsed_sec", CsvSchema.RAW_HEADER.first())
        assertEquals("net_tx_kbps", CsvSchema.RAW_HEADER.last())
    }

    @Test
    fun sessionRow_usesDictionaryNumberFormatsAndEmptyForNull() {
        val sample = TestSamples.standard(currentUa = 500_000, voltageMv = 4200, tempC = 31.25)
        val row = CsvSchema.sessionRow("PT-20261118-001", ConditionId.VIDEO, sample)
        assertEquals(32, row.size)
        assertEquals("PT-20261118-001", row[0])
        assertEquals("VIDEO", row[1])
        assertEquals("短视频", row[2])
        assertEquals(1, row[3])
        assertEquals(0L, row[4])
        // 温度一位小数
        assertEquals("31.3", row[9])
        // 功率三位小数：4200 mV × 500000 µA / 1e9 = 2.1 W
        assertEquals("2.100", row[13])
        // 布尔 1/0
        assertEquals(0, row[20])
        assertEquals(1, row[21])
        // 空值必须是空字符串，不能是 NaN/null/N/A
        val emptyRow = CsvSchema.sessionRow("PT-1", ConditionId.IDLE, TestSamples.standard().copy(
            batteryTempC = null, currentNowUa = null, ambientLux = null, fps = null,
            netRxKbps = null, foregroundPkg = null, energyCounterNwh = null, batteryCycleCount = null,
        ))
        assertNull(emptyRow[9])
        assertNull(emptyRow[13])
        assertNull(emptyRow[23])
        assertNull(emptyRow[29])
        // foreground_pkg 无授权时写空字符串（不是 null 占位）
        assertEquals("", emptyRow[30])
        // 序列化后这些位置必须是空字段（不是 NaN / null / N/A）
        val rendered = CsvText.dataLine(emptyRow).trimEnd('\r', '\n').split(",")
        assertEquals(CsvSchema.SESSION_HEADER.size, rendered.size)
        assertEquals("", rendered[9])
        assertEquals("", rendered[13])
        assertEquals("", rendered[23])
        assertEquals("", rendered[29])
        assertEquals("", rendered[30])
        assertTrue(row[24] is String)
    }

    @Test
    fun campaignRow_keepsBlockAndDropsSessionId() {
        val row = CsvSchema.campaignRow("PC-20261118-001", 'C', ConditionId.NAVCAM, TestSamples.standard())
        assertEquals(33, row.size)
        assertEquals("PC-20261118-001", row[0])
        assertEquals("C", row[1])
        assertEquals("NAVCAM", row[2])
    }

    @Test
    fun appendRow_writesBomHeaderAndCrlfThenParsesBack() {
        val dir = File(System.getProperty("java.io.tmpdir"), "powertrace-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val file = File(dir, "session_VIDEO.csv")
            val samples = TestSamples.series()
            samples.forEach { sample ->
                CsvText.appendRow(
                    file,
                    CsvSchema.SESSION_HEADER,
                    CsvSchema.sessionRow(
                        "PT-20261118-001",
                        ConditionId.VIDEO,
                        sample.copy(note = "含,逗号" + '"' + "引号"),
                    ),
                )
            }
            val text = file.readText()
            assertTrue(text.startsWith("\uFEFF"))
            assertTrue(text.contains("\r\n"))
            val lines = text.split("\r\n").filter { it.isNotBlank() }
            // 1 行表头 + 6 行数据
            assertEquals(7, lines.size)
            // 往返解析：字段与单位不丢
            val parsed = CsvSchema.parseSessionCsv(text)
            assertEquals(6, parsed.size)
            assertEquals(1, parsed.first().sampleIndex)
            assertEquals(2.1, parsed.first().powerW!!, 1e-9)
            assertEquals("含,逗号" + '"' + "引号", parsed.first().note)
            assertEquals(1_730_000_000_000L, parsed.first().timestampMs)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun campaignCsv_fullCampaignHasExactly24Rows() {
        val dir = File(System.getProperty("java.io.tmpdir"), "powertrace-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val state = CampaignEngine.create(
                campaignId = "PC-20261118-001",
                sessionId = "PT-20261118-001",
                dirPath = dir.absolutePath,
                notes = "测试批次",
                order = ConditionId.defaultOrder,
                nowMs = 0L,
            )
            val rows = ArrayList<List<Any?>>()
            state.conditions.forEach { progress ->
                TestSamples.series().forEach { sample ->
                    rows += CsvSchema.campaignRow(state.campaignId, progress.block, progress.condition, sample)
                }
            }
            assertEquals(24, rows.size)
            val file = File(dir, "campaign_PC-20261118-001.csv")
            CsvText.writeFull(file, CsvSchema.CAMPAIGN_HEADER, rows)
            val parsed = CsvText.parse(file.readText())
            // 1 行表头 + 24 行数据
            assertEquals(25, parsed.size)
            assertEquals(CsvSchema.CAMPAIGN_HEADER, parsed.first())
            val blocks = parsed.drop(1).map { it[1] }.distinct()
            assertEquals(listOf("A", "B", "C", "D"), blocks)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun summaryCsv_has20ColumnsAndFourRowsForFullCampaign() {
        val dir = File(System.getProperty("java.io.tmpdir"), "powertrace-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val rows = ConditionId.defaultOrder.map { condition ->
                SummaryCalculator.summarize(
                    condition = condition,
                    block = ConditionId.blockLetter(ConditionId.defaultOrder.indexOf(condition)),
                    samples = TestSamples.series(),
                    durationMin = 30.0,
                ).toRow()
            }
            assertEquals(4, rows.size)
            val file = File(dir, "campaign_summary_PC-20261118-001.csv")
            CsvText.writeFull(file, CsvSchema.SUMMARY_HEADER, rows)
            val parsed = CsvText.parse(file.readText())
            assertEquals(5, parsed.size)
            assertEquals(CsvSchema.SUMMARY_HEADER, parsed.first())
            assertEquals(20, parsed.first().size)
            assertEquals(listOf("IDLE", "VIDEO", "NAVCAM", "GAME"), parsed.drop(1).map { it[0] })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fmt_keepsDictionaryDecimals() {
        assertEquals("31.3", Fmt.dec1(31.25))
        assertEquals("2.100", Fmt.dec3(2.1))
        assertEquals("1000.0", Fmt.dec1(1000.0))
        assertEquals("", Fmt.dec1(Double.NaN) ?: "")
        assertNull(Fmt.dec3(Double.POSITIVE_INFINITY))
    }
}
