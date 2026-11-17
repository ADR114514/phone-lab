package com.mobilelab.signalmap.data

/**
 * Domain model for the campus Wi-Fi blind-zone survey (课题03 / SignalMap).
 *
 * Field names and value vocabularies follow docs/DATA_DICTIONARY.md section 2
 * exactly: they are written verbatim into CSV files, so the enum ids below are
 * part of the data contract and must never be localised.
 */

enum class PointType(val id: String, val label: String) {
    CORRIDOR("corridor", "走廊"),
    CORNER("corner", "转角"),
    ELEVATOR("elevator", "电梯口"),
    STAIRWELL("stairwell", "楼梯间"),
    CLASSROOM("classroom", "教室"),
    DORM("dorm", "宿舍"),
    LAB("lab", "实验室"),
    OUTDOOR("outdoor", "室外");

    companion object {
        fun fromId(id: String?): PointType = entries.firstOrNull { it.id == id } ?: CORRIDOR
    }
}

enum class WallMaterial(val id: String, val label: String) {
    CONCRETE("concrete", "混凝土"),
    BRICK("brick", "砖墙"),
    GYPSUM("gypsum", "石膏板"),
    GLASS("glass", "玻璃"),
    METAL("metal", "金属"),
    MIXED("mixed", "混合"),
    NONE("none", "无遮挡");

    companion object {
        fun fromId(id: String?): WallMaterial = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** One of the eight survey stations. Editable by the operator, id stays fixed. */
data class SurveyPoint(
    val id: String,
    val name: String,
    val type: PointType,
    val wallMaterial: WallMaterial,
    val obstruction: String,
    val distanceM: Double?,
    val floor: Int?,
    val photoFile: String = "",
)

/** One scanned neighbour AP (row of scan_<session>_<point>_r<n>.csv). */
data class ScanEntry(
    val timestampIso: String,
    val bssid: String,
    val ssid: String,
    val frequencyMhz: Int,
    val channel: Int?,
    val rssiDbm: Int,
    /** ScanResult no longer exposes a link speed on modern Android; empty when unknown. */
    val linkSpeedMbps: Int?,
    val capabilities: String,
)

/**
 * Aggregated statistics of a latency probe run. Null statistics mean "no
 * successful sample", which the CSV writer renders as an empty field — never
 * as 0 (the analysis pipeline distinguishes "not measured" from "0 ms").
 */
data class RttSummary(
    val attempts: Int,
    val successes: Int,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val medianMs: Double?,
    val p95Ms: Double?,
    val jitterMs: Double?,
) {
    val failures: Int get() = (attempts - successes).coerceAtLeast(0)
    val lossPct: Double get() = if (attempts <= 0) 0.0 else failures * 100.0 / attempts

    companion object {
        val EMPTY = RttSummary(0, 0, null, null, null, null, null, null)
    }
}

/** One of the 24 rows of survey_<session>.csv. */
data class SurveyRow(
    val sessionId: String,
    val pointId: String,
    val pointName: String,
    val pointType: String,
    val wallMaterial: String,
    val obstruction: String,
    val distanceM: Double?,
    val floor: Int?,
    val photoFile: String,
    val repeatIndex: Int,
    val timestampIso: String,
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int?,
    val linkSpeedMbps: Int?,
    val txLinkMbps: Int?,
    val rxLinkMbps: Int?,
    val frequencyMhz: Int?,
    val channel: Int?,
    val wifiStandard: String,
    val latencyAvgMs: Double?,
    val latencyMinMs: Double?,
    val latencyMaxMs: Double?,
    val latencyMedianMs: Double?,
    val latencyP95Ms: Double?,
    val jitterMs: Double?,
    val lossPct: Double?,
    val tcpConnectMs: Double?,
    val httpTtfbMs: Double?,
    val httpTotalMs: Double?,
    val downKbps: Double?,
    val apCount: Int?,
    val strongestNeighborRssi: Int?,
    val neighborChannels: String,
    val note: String,
) {
    val repeat: Int get() = repeatIndex
}

/** Target of the next measurement: a station plus the 1-based repeat index. */
data class MeasurementTarget(val point: SurveyPoint, val repeatIndex: Int)
