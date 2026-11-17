package com.mobilelab.signalmap

import com.mobilelab.core.csv.CsvBuilder
import com.mobilelab.signalmap.data.CsvCodec
import com.mobilelab.signalmap.data.CsvText
import com.mobilelab.signalmap.data.DataContract
import com.mobilelab.signalmap.data.PresetPoints
import com.mobilelab.signalmap.data.SurveyRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the file contract of DATA_DICTIONARY section 2. If one of these tests
 * fails the Python analysis pipeline would break as well.
 */
class CsvContractTest {

    @Test
    fun surveyHeaderMatchesDictionaryExactly() {
        assertEquals(35, DataContract.SURVEY_HEADER.size)
        assertEquals(
            listOf(
                "session_id", "point_id", "point_name", "point_type", "wall_material",
                "obstruction", "distance_m", "floor", "photo_file", "repeat_index",
                "timestamp_iso", "ssid", "bssid", "rssi_dbm", "link_speed_mbps",
                "tx_link_mbps", "rx_link_mbps", "frequency_mhz", "channel", "wifi_standard",
                "latency_avg_ms", "latency_min_ms", "latency_max_ms", "latency_median_ms",
                "latency_p95_ms", "jitter_ms", "loss_pct", "tcp_connect_ms", "http_ttfb_ms",
                "http_total_ms", "down_kbps", "ap_count", "strongest_neighbor_rssi",
                "neighbor_channels", "note",
            ),
            DataContract.SURVEY_HEADER,
        )
    }

    @Test
    fun pointsAndScanHeadersMatchDictionary() {
        assertEquals(
            listOf(
                "point_id", "point_name", "point_type", "wall_material", "obstruction",
                "distance_m", "floor", "photo_file",
            ),
            DataContract.POINTS_HEADER,
        )
        assertEquals(
            listOf(
                "timestamp_iso", "bssid", "ssid", "frequency_mhz", "channel", "rssi_dbm",
                "link_speed_mbps", "capabilities",
            ),
            DataContract.SCAN_HEADER,
        )
    }

    @Test
    fun rowValuesAlignWithHeader() {
        val values = CsvCodec.rowValues(TestFixtures.row(), hashBssid = false)
        assertEquals(DataContract.SURVEY_HEADER.size, values.size)
    }

    @Test
    fun eightPointsThreeRepeatsProduceTwentyFourRows() {
        val points = PresetPoints.defaults()
        val sessionId = "WS-20261118-001"
        val rows = points.flatMap { point ->
            (1..3).map { repeat ->
                TestFixtures.row(pointId = point.id, repeatIndex = repeat, sessionId = sessionId)
            }
        }
        assertEquals(8, points.size)
        assertEquals(24, rows.size)

        val csv = CsvCodec.surveyCsv(rows)
        val parsed = CsvCodec.parseSurvey(csv)
        assertEquals(DataContract.TOTAL_ROWS, parsed.size)

        // Every station has exactly repeat 1..3.
        points.forEach { point ->
            val repeats = parsed.filter { it.pointId == point.id }.map { it.repeatIndex }.sorted()
            assertEquals(listOf(1, 2, 3), repeats)
        }
        assertEquals(sessionId, parsed.first().sessionId)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("session_id,point_id,point_name"))
        val dataLines = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotBlank() }
        assertEquals(25, dataLines.size) // header + 24 rows
    }

    @Test
    fun rowRoundTripPreservesAllFields() {
        val original = TestFixtures.row()
        val parsed = CsvCodec.parseSurvey(CsvCodec.surveyCsv(listOf(original))).single()
        assertEquals(original, parsed)
    }

    @Test
    fun nullableFieldsBecomeEmptyAndParseBackToNull() {
        val row = TestFixtures.row().copy(
            distanceM = null,
            floor = null,
            txLinkMbps = null,
            rxLinkMbps = null,
            downKbps = null,
            latencyAvgMs = null,
            latencyMinMs = null,
            latencyMaxMs = null,
            latencyMedianMs = null,
            latencyP95Ms = null,
            jitterMs = null,
            httpTtfbMs = null,
            httpTotalMs = null,
            strongestNeighborRssi = null,
        )
        val csv = CsvCodec.surveyCsv(listOf(row))
        assertFalse(csv.contains("NaN"))
        assertFalse(csv.contains("null"))
        val parsed = CsvCodec.parseSurvey(csv).single()
        assertNull(parsed.distanceM)
        assertNull(parsed.floor)
        assertNull(parsed.downKbps)
        assertNull(parsed.latencyAvgMs)
        assertNull(parsed.strongestNeighborRssi)
        // loss_pct is always a real measured value, even for a fully failed run.
        assertEquals(5.0, parsed.lossPct!!, 1e-9)
    }

    @Test
    fun csvQuotesFieldsWithCommasAndQuotes() {
        val row = TestFixtures.row().copy(
            obstruction = "两侧承重墙, 转角 90°",
            note = "异常：\"扫描节流\"",
        )
        val csv = CsvCodec.surveyCsv(listOf(row))
        assertTrue(csv.contains("\"两侧承重墙, 转角 90°\""))
        val parsed = CsvCodec.parseSurvey(csv).single()
        assertEquals("两侧承重墙, 转角 90°", parsed.obstruction)
        assertEquals("异常：\"扫描节流\"", parsed.note)
    }

    @Test
    fun hashedExportReplacesBssidButKeepsStructure() {
        val row = TestFixtures.row()
        val plain = CsvCodec.surveyCsv(listOf(row), hashBssid = false)
        val hashed = CsvCodec.surveyCsv(listOf(row), hashBssid = true)
        assertTrue(plain.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(hashed.contains("AA:BB:CC:DD:EE:FF"))
        val parsed = CsvCodec.parseSurvey(hashed).single()
        assertTrue(parsed.bssid.startsWith("h"))
        assertEquals(1 + 16, parsed.bssid.length)
        // Points/rows structure untouched.
        assertEquals(row.pointId, parsed.pointId)
        assertEquals(row.repeatIndex, parsed.repeatIndex)
        assertEquals(row.rssiDbm, parsed.rssiDbm)
    }

    @Test
    fun pointsCsvHasEightRows() {
        val csv = CsvCodec.pointsCsv(PresetPoints.defaults())
        val table = CsvText.parse(csv)
        assertEquals(9, table.size) // header + 8
        assertEquals("point_id", table.first().first())
        assertEquals("P01", table[1][0])
        assertEquals("P08", table[8][0])
    }

    @Test
    fun scanCsvMatchesHeaderAndLeavesUnknownLinkSpeedEmpty() {
        val csv = CsvCodec.scanCsv(listOf(TestFixtures.scanEntry()))
        val table = CsvText.parse(csv)
        assertEquals(2, table.size)
        assertEquals(DataContract.SCAN_HEADER, table.first())
        val row = table[1]
        assertEquals("AA:BB:CC:DD:EE:FF", row[1])
        assertEquals("Neighbour, \"lab\"", row[2])
        assertEquals("", row[6]) // link speed has no ScanResult source on Android 12+
    }

    @Test
    fun surveyDataLineHasNoBomAndNoHeader() {
        val line = CsvCodec.surveyDataLine(TestFixtures.row())
        assertFalse(line.startsWith("\uFEFF"))
        assertFalse(line.contains("session_id"))
        assertTrue(line.endsWith("\r\n"))
        val header = CsvCodec.surveyHeaderLine()
        assertTrue(header.startsWith("\uFEFF"))
        assertEquals(DataContract.SURVEY_HEADER.joinToString(",") + "\r\n", header.removePrefix("\uFEFF"))
    }

    @Test
    fun noUnexpectedColumnsAreAddedForRowWithSpecialNote() {
        val rows = (1..24).map { index ->
            TestFixtures.row(
                pointId = "P0" + ((index - 1) / 3 + 1),
                repeatIndex = ((index - 1) % 3) + 1,
            )
        }
        val csv = CsvCodec.surveyCsv(rows)
        val header = CsvText.parse(csv).first()
        assertEquals(DataContract.SURVEY_HEADER, header)
        val builderHeaderSize = CsvBuilder(DataContract.SURVEY_HEADER).build()
        assertEquals(DataContract.SURVEY_HEADER.joinToString(",") + "\r\n", builderHeaderSize.removePrefix("\uFEFF"))
    }

    @Test
    fun parseSurvivesTruncatedLastRow() {
        val full = CsvCodec.surveyCsv(listOf(TestFixtures.row())).removeSuffix("\r\n")
        val parsed: List<SurveyRow> = CsvCodec.parseSurvey(full)
        assertEquals(1, parsed.size)
        assertEquals("P01", parsed.single().pointId)
    }
}
