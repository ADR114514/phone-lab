package com.mobilelab.signalmap.service

import com.mobilelab.signalmap.data.AppSettings
import com.mobilelab.signalmap.data.ScanEntry
import com.mobilelab.signalmap.data.SurveyPoint
import com.mobilelab.signalmap.data.SurveyRow

/** Ordered steps rendered on the measurement screen while a repeat runs. */
enum class MeasurementStep(val label: String, val detail: String) {
    CONNECTION("读取连接信息", "SSID / RSSI / 链路速率 / 频段 / BSSID"),
    SCAN("扫描邻道", "startScan + getScanResults 邻道 AP 明细"),
    GATEWAY("网关 TCP RTT", "DHCP 网关 TCP 建连 5 次"),
    INTERNET("互联网 TCP RTT", "TCP 建连，默认 20 次，单次超时 1000 ms"),
    HTTP("HTTP TTFB / 总耗时", "GET 测试 URL，5 次，含 DNS 解析"),
    THROUGHPUT("吞吐测试", "512 KB 下载（设置中开启后执行）"),
    WRITE("写入 CSV", "追加 survey 行 + scan 明细 + manifest/points"),
}

data class MeasurementRequest(
    val point: SurveyPoint,
    val repeatIndex: Int,
    val sessionId: String,
    val settings: AppSettings,
    /** Measurement start time, also written to timestamp_iso. */
    val timestampIso: String,
    /** Relative photo path already captured for this point ("" when skipped). */
    val photoFile: String,
)

/** Live values streamed to the measuring screen (never persisted). */
data class LiveSample(
    val rssiDbm: Int? = null,
    val sampleLatencyMs: Double? = null,
    val avgLatencyMs: Double? = null,
    val successCount: Int = 0,
    val targetCount: Int = 0,
    val lossPct: Double? = null,
    val httpTtfbMs: Double? = null,
    val message: String = "",
)

interface MeasurementCallback {
    fun onStep(step: MeasurementStep)
    fun onLive(sample: LiveSample)
    fun onLog(message: String)

    companion object {
        val NOOP: MeasurementCallback = object : MeasurementCallback {
            override fun onStep(step: MeasurementStep) = Unit
            override fun onLive(sample: LiveSample) = Unit
            override fun onLog(message: String) = Unit
        }
    }
}

data class MeasurementOutcome(
    val row: SurveyRow,
    val scanEntries: List<ScanEntry>,
)

/**
 * Thrown when the survey is not allowed to run (no Wi-Fi, location off,
 * permission revoked...). The pipeline throws *before* opening any file, so a
 * blocked run can never leave a fabricated row behind.
 */
class MeasurementBlockedException(message: String) : IllegalStateException(message)
