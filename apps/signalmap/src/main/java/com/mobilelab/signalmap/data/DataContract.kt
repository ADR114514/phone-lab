package com.mobilelab.signalmap.data

import com.mobilelab.core.csv.CsvBuilder

/**
 * Single source of truth for the SignalMap file layout. Every column name,
 * order and unit mirrors docs/DATA_DICTIONARY.md section 2. Changing anything
 * here changes the data contract for analysis/wifi, so the lists are kept as
 * explicit literals instead of reflection.
 */
object DataContract {
    const val APP_TAG = "signalmap"
    const val PROJECT_CODE = "03-wifi-survey"
    const val APP_LABEL = "SignalMap"
    const val SCHEMA_MANIFEST = "phonelab.manifest/1"

    /** survey_<sessionId>.csv — 35 columns, exactly 8 points x 3 repeats = 24 rows. */
    val SURVEY_HEADER: List<String> = listOf(
        "session_id", "point_id", "point_name", "point_type", "wall_material",
        "obstruction", "distance_m", "floor", "photo_file", "repeat_index",
        "timestamp_iso", "ssid", "bssid", "rssi_dbm", "link_speed_mbps",
        "tx_link_mbps", "rx_link_mbps", "frequency_mhz", "channel", "wifi_standard",
        "latency_avg_ms", "latency_min_ms", "latency_max_ms", "latency_median_ms",
        "latency_p95_ms", "jitter_ms", "loss_pct", "tcp_connect_ms", "http_ttfb_ms",
        "http_total_ms", "down_kbps", "ap_count", "strongest_neighbor_rssi",
        "neighbor_channels", "note",
    )

    /** points.csv — station catalogue written next to the survey table. */
    val POINTS_HEADER: List<String> = listOf(
        "point_id", "point_name", "point_type", "wall_material", "obstruction",
        "distance_m", "floor", "photo_file",
    )

    /** scan_<sessionId>_<pointId>_r<repeat>.csv — neighbour AP detail. */
    val SCAN_HEADER: List<String> = listOf(
        "timestamp_iso", "bssid", "ssid", "frequency_mhz", "channel", "rssi_dbm",
        "link_speed_mbps", "capabilities",
    )

    const val POINTS_PER_SESSION = 8
    const val REPEATS_PER_POINT = 3
    const val TOTAL_ROWS = POINTS_PER_SESSION * REPEATS_PER_POINT
}

/**
 * RFC 4180 reader used to reload an in-progress survey after a crash and to
 * rewrite the table on export. Tolerates the UTF-8 BOM, CRLF and a missing
 * trailing newline.
 */
object CsvText {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        var sawAny = false
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    ch == '"' -> inQuotes = false
                    else -> field.append(ch)
                }
                ch == '"' -> {
                    inQuotes = true
                    sawAny = true
                }
                ch == ',' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    sawAny = true
                }
                ch == '\r' -> {
                    // swallow; the following \n terminates the row
                }
                ch == '\n' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    if (sawAny || row.size > 1) rows.add(row)
                    row = mutableListOf()
                    sawAny = false
                }
                else -> {
                    field.append(ch)
                    sawAny = true
                }
            }
            i++
        }
        if (sawAny || field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}

/** Serialises / parses the survey artefacts. Pure JVM so contract tests run offline. */
object CsvCodec {

    fun rowValues(row: SurveyRow, hashBssid: Boolean): List<Any?> = listOf(
        row.sessionId,
        row.pointId,
        row.pointName,
        row.pointType,
        row.wallMaterial,
        row.obstruction,
        row.distanceM,
        row.floor,
        row.photoFile,
        row.repeatIndex,
        row.timestampIso,
        row.ssid,
        bssidCell(row.bssid, hashBssid),
        row.rssiDbm,
        row.linkSpeedMbps,
        row.txLinkMbps,
        row.rxLinkMbps,
        row.frequencyMhz,
        row.channel,
        row.wifiStandard,
        row.latencyAvgMs,
        row.latencyMinMs,
        row.latencyMaxMs,
        row.latencyMedianMs,
        row.latencyP95Ms,
        row.jitterMs,
        row.lossPct,
        row.tcpConnectMs,
        row.httpTtfbMs,
        row.httpTotalMs,
        row.downKbps,
        row.apCount,
        row.strongestNeighborRssi,
        row.neighborChannels,
        row.note,
    )

    fun bssidCell(raw: String, hashBssid: Boolean): String =
        if (hashBssid && raw.isNotEmpty()) BssidHash.hash(raw) else raw

    /** One appended data line (no header/BOM) for the crash-safe append write. */
    fun surveyDataLine(row: SurveyRow, hashBssid: Boolean = false): String =
        rowValues(row, hashBssid).joinToString(",") { CsvBuilder.render(it) } + "\r\n"

    fun surveyHeaderLine(): String =
        "\uFEFF" + DataContract.SURVEY_HEADER.joinToString(",") + "\r\n"

    fun surveyCsv(rows: List<SurveyRow>, hashBssid: Boolean = false): String {
        val sb = StringBuilder(surveyHeaderLine())
        rows.forEach { sb.append(surveyDataLine(it, hashBssid)) }
        return sb.toString()
    }

    fun pointsCsv(points: List<SurveyPoint>): String {
        val builder = CsvBuilder(DataContract.POINTS_HEADER)
        points.forEach { p ->
            builder.row(
                p.id, p.name, p.type.id, p.wallMaterial.id, p.obstruction,
                p.distanceM, p.floor, p.photoFile,
            )
        }
        return builder.build()
    }

    fun scanCsv(entries: List<ScanEntry>, hashBssid: Boolean = false): String {
        val builder = CsvBuilder(DataContract.SCAN_HEADER)
        entries.forEach { e ->
            builder.row(
                e.timestampIso,
                if (hashBssid && e.bssid.isNotEmpty()) BssidHash.hash(e.bssid) else e.bssid,
                e.ssid,
                e.frequencyMhz,
                e.channel,
                e.rssiDbm,
                e.linkSpeedMbps,
                e.capabilities,
            )
        }
        return builder.build()
    }

    fun parseSurvey(text: String): List<SurveyRow> {
        val table = CsvText.parse(text)
        if (table.isEmpty()) return emptyList()
        val header = table.first().map { it.trim() }
        fun index(name: String): Int = header.indexOf(name)
        val dataRows = table.drop(1).filter { cells -> cells.any { it.isNotBlank() } }
        return dataRows.mapNotNull { cells ->
            fun cell(name: String): String {
                val idx = index(name)
                return if (idx in cells.indices) cells[idx] else ""
            }
            fun str(name: String): String = cell(name)
            fun dbl(name: String): Double? = cell(name).trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            fun int(name: String): Int? = cell(name).trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            SurveyRow(
                sessionId = str("session_id"),
                pointId = str("point_id"),
                pointName = str("point_name"),
                pointType = str("point_type"),
                wallMaterial = str("wall_material"),
                obstruction = str("obstruction"),
                distanceM = dbl("distance_m"),
                floor = int("floor"),
                photoFile = str("photo_file"),
                repeatIndex = int("repeat_index") ?: 0,
                timestampIso = str("timestamp_iso"),
                ssid = str("ssid"),
                bssid = str("bssid"),
                rssiDbm = int("rssi_dbm"),
                linkSpeedMbps = int("link_speed_mbps"),
                txLinkMbps = int("tx_link_mbps"),
                rxLinkMbps = int("rx_link_mbps"),
                frequencyMhz = int("frequency_mhz"),
                channel = int("channel"),
                wifiStandard = str("wifi_standard"),
                latencyAvgMs = dbl("latency_avg_ms"),
                latencyMinMs = dbl("latency_min_ms"),
                latencyMaxMs = dbl("latency_max_ms"),
                latencyMedianMs = dbl("latency_median_ms"),
                latencyP95Ms = dbl("latency_p95_ms"),
                jitterMs = dbl("jitter_ms"),
                lossPct = dbl("loss_pct"),
                tcpConnectMs = dbl("tcp_connect_ms"),
                httpTtfbMs = dbl("http_ttfb_ms"),
                httpTotalMs = dbl("http_total_ms"),
                downKbps = dbl("down_kbps"),
                apCount = int("ap_count"),
                strongestNeighborRssi = int("strongest_neighbor_rssi"),
                neighborChannels = str("neighbor_channels"),
                note = str("note"),
            )
        }
    }
}
