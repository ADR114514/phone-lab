package com.mobilelab.signalmap.service

import android.content.Context
import com.mobilelab.core.log.LabLog
import com.mobilelab.signalmap.data.RttMath
import com.mobilelab.signalmap.data.SurveyRow
import com.mobilelab.signalmap.data.WifiMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Executes one measurement repeat exactly as specified in
 * docs/DATA_DICTIONARY.md 2.3:
 *
 *   connection info -> neighbour scan -> gateway TCP RTT -> internet TCP RTT
 *   -> HTTP TTFB/total -> optional throughput
 *
 * The pipeline performs *no* file writes: the caller appends the returned row
 * only after the whole run succeeded, and a blocked environment throws
 * [MeasurementBlockedException] before any probe starts.
 */
class MeasurementPipeline(
    private val context: Context,
    private val wifiReader: WifiReader = WifiReader(context),
) {

    suspend fun measure(
        request: MeasurementRequest,
        callback: MeasurementCallback = MeasurementCallback.NOOP,
    ): MeasurementOutcome = withContext(Dispatchers.IO) {
        val settings = request.settings
        val status: EnvironmentStatus = EnvironmentProbe.check(context)
        if (!status.measurementReady) {
            throw MeasurementBlockedException(status.blockingSummary)
        }
        val notes = mutableListOf<String>()

        // 1) associated AP -----------------------------------------------------
        callback.onStep(MeasurementStep.CONNECTION)
        val connection = wifiReader.connection()
        val rssi = connection.rssiDbm
        if (rssi == null) {
            throw MeasurementBlockedException(
                "无法读取关联 RSSI（Wi-Fi 连接、定位开关或定位权限异常），已中止，未写入任何数据"
            )
        }
        if (connection.ssid.isEmpty()) notes += "SSID 不可读"
        if (connection.bssid.isEmpty()) notes += "BSSID 不可读"
        callback.onLive(
            LiveSample(
                rssiDbm = rssi,
                message = "SSID=${connection.ssid.ifEmpty { "--" }} RSSI=$rssi dBm 频段=${connection.wifiStandard.ifEmpty { "--" }}",
            )
        )
        LabLog.i(
            TAG,
            "connection: ssid=${connection.ssid} bssid=${connection.bssid} rssi=$rssi " +
                "freq=${connection.frequencyMhz} link=${connection.linkSpeedMbps}",
        )
        ensureActive()

        // 2) neighbour scan ----------------------------------------------------
        callback.onStep(MeasurementStep.SCAN)
        val scan = wifiReader.scan(request.timestampIso)
        if (scan.note.isNotEmpty()) notes += scan.note
        LabLog.i(TAG, "scan: ${scan.entries.size}/${scan.rawCount} APs source=${scan.source}")
        callback.onLive(LiveSample(rssiDbm = rssi, message = "扫描到 ${scan.entries.size} 个邻道 AP"))
        ensureActive()

        val probe = NetworkProbe()

        // 3) gateway TCP RTT ---------------------------------------------------
        callback.onStep(MeasurementStep.GATEWAY)
        var gatewayMs: Double? = null
        val gatewayIp = wifiReader.gatewayIpv4()
        if (gatewayIp == null) {
            notes += "无 DHCP 网关信息，跳过网关 TCP RTT"
            callback.onLive(LiveSample(rssiDbm = rssi, message = "无网关信息，跳过网关 RTT"))
        } else {
            var run = probe.tcpProbe(gatewayIp, 80, settings.gatewayAttempts, NetworkProbe.GATEWAY_TIMEOUT_MS)
            if (run.allFailed) {
                val retry = probe.tcpProbe(gatewayIp, 443, settings.gatewayAttempts, NetworkProbe.GATEWAY_TIMEOUT_MS)
                if (!retry.allFailed) {
                    run = retry
                    notes += "网关 80 端口不可达，改用 443 端口"
                } else {
                    notes += "网关 $gatewayIp 不可达"
                }
            }
            gatewayMs = run.summary.avgMs
            LabLog.i(TAG, "gateway $gatewayIp: ${run.latenciesMs.size}/${run.attempts} ok, avg=$gatewayMs")
            callback.onLive(
                LiveSample(
                    rssiDbm = rssi,
                    avgLatencyMs = gatewayMs,
                    message = "网关 ${gatewayIp}：${run.latenciesMs.size}/${run.attempts} 次成功",
                )
            )
        }
        ensureActive()

        // 4) internet TCP RTT --------------------------------------------------
        callback.onStep(MeasurementStep.INTERNET)
        val rttSamples = ArrayList<Double>(settings.rttAttempts)
        val rttRun = probe.tcpProbe(
            host = settings.tcpHost,
            port = settings.tcpPort,
            attempts = settings.rttAttempts,
            timeoutMs = settings.tcpTimeoutMs,
        ) { sample ->
            if (sample != null) rttSamples += sample
            val running = RttMath.summarize(settings.rttAttempts, rttSamples)
            callback.onLive(
                LiveSample(
                    rssiDbm = rssi,
                    sampleLatencyMs = sample,
                    avgLatencyMs = running.avgMs,
                    successCount = rttSamples.size,
                    targetCount = settings.rttAttempts,
                    lossPct = if (settings.rttAttempts > 0) {
                        (settings.rttAttempts - rttSamples.size) * 100.0 / settings.rttAttempts
                    } else {
                        null
                    },
                    message = "RTT ${rttSamples.size}/${settings.rttAttempts} 次成功",
                )
            )
        }
        val rtt = rttRun.summary
        when {
            rttRun.allFailed -> notes += "互联网 RTT 全部失败（${settings.tcpHost}:${settings.tcpPort}）"
            rttRun.failures > 0 -> notes += "RTT 失败 ${rttRun.failures}/${rttRun.attempts}"
        }
        LabLog.i(
            TAG,
            "rtt ${settings.tcpHost}:${settings.tcpPort}: success=${rtt.successes}/${rtt.attempts} " +
                "avg=${rtt.avgMs} p95=${rtt.p95Ms} loss=${rtt.lossPct}",
        )
        ensureActive()

        // 5) HTTP TTFB / total -------------------------------------------------
        callback.onStep(MeasurementStep.HTTP)
        val httpRun = probe.httpProbe(settings.httpTestUrl, settings.httpAttempts) { sample ->
            callback.onLive(
                LiveSample(
                    rssiDbm = rssi,
                    avgLatencyMs = rtt.avgMs,
                    successCount = rttSamples.size,
                    targetCount = settings.rttAttempts,
                    httpTtfbMs = sample.ttfbMs,
                    message = if (sample.error != null) {
                        "HTTP 失败：${sample.error}"
                    } else {
                        "HTTP ${sample.code} TTFB=${format(sample.ttfbMs)} ms"
                    },
                )
            )
        }
        if (httpRun.successes == 0) {
            notes += "HTTP 探测全部失败" + (httpRun.dominantError?.let { "（$it）" } ?: "")
        } else if (httpRun.failures > 0) {
            notes += "HTTP 失败 ${httpRun.failures}/${httpRun.attempts}"
        }
        LabLog.i(
            TAG,
            "http: success=${httpRun.successes}/${httpRun.attempts} " +
                "ttfb=${httpRun.avgTtfbMs} total=${httpRun.avgTotalMs} dns=${RttMath.meanOf(httpRun.dnsValues)}",
        )
        ensureActive()

        // 6) optional throughput ----------------------------------------------
        var downKbps: Double? = null
        if (settings.throughputEnabled) {
            callback.onStep(MeasurementStep.THROUGHPUT)
            downKbps = probe.throughputKbps(settings.throughputUrl, settings.throughputBytes)
            if (downKbps == null) {
                notes += "吞吐测试失败（512 KB 下载不可用）"
            } else {
                LabLog.i(TAG, "throughput: $downKbps kbps")
            }
            ensureActive()
        }

        // 7) assemble the row --------------------------------------------------
        if (request.photoFile.isEmpty()) notes += "未拍照"

        val neighborEntries = scan.entries.filter { entry ->
            connection.bssid.isEmpty() || entry.bssid != connection.bssid
        }
        val strongestNeighbor = neighborEntries.maxOfOrNull { it.rssiDbm }
        val neighborChannels = WifiMath.neighborChannels(neighborEntries.mapNotNull { it.channel })

        val row = SurveyRow(
            sessionId = request.sessionId,
            pointId = request.point.id,
            pointName = request.point.name,
            pointType = request.point.type.id,
            wallMaterial = request.point.wallMaterial.id,
            obstruction = request.point.obstruction,
            distanceM = request.point.distanceM,
            floor = request.point.floor,
            photoFile = request.photoFile,
            repeatIndex = request.repeatIndex,
            timestampIso = request.timestampIso,
            ssid = connection.ssid,
            bssid = connection.bssid,
            rssiDbm = rssi,
            linkSpeedMbps = connection.linkSpeedMbps,
            txLinkMbps = connection.txLinkMbps,
            rxLinkMbps = connection.rxLinkMbps,
            frequencyMhz = connection.frequencyMhz,
            channel = connection.channel,
            wifiStandard = connection.wifiStandard,
            latencyAvgMs = rtt.avgMs,
            latencyMinMs = rtt.minMs,
            latencyMaxMs = rtt.maxMs,
            latencyMedianMs = rtt.medianMs,
            latencyP95Ms = rtt.p95Ms,
            jitterMs = rtt.jitterMs,
            lossPct = rtt.lossPct,
            tcpConnectMs = gatewayMs,
            httpTtfbMs = httpRun.avgTtfbMs,
            httpTotalMs = httpRun.avgTotalMs,
            downKbps = downKbps,
            apCount = scan.entries.size,
            strongestNeighborRssi = strongestNeighbor,
            neighborChannels = neighborChannels,
            note = notes.joinToString("; ").take(400),
        )
        LabLog.i(TAG, "row assembled for ${request.point.id} r${request.repeatIndex}: ${row.note.ifEmpty { "no notes" }}")
        MeasurementOutcome(row = row, scanEntries = scan.entries)
    }

    private fun format(value: Double?): String =
        if (value == null || !value.isFinite()) "--" else String.format(java.util.Locale.US, "%.1f", value)

    companion object {
        private const val TAG = "MeasurementPipeline"
    }
}
