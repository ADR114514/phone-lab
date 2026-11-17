package com.mobilelab.signalmap

import com.mobilelab.signalmap.data.ScanEntry
import com.mobilelab.signalmap.data.SurveyPoint
import com.mobilelab.signalmap.data.SurveyRow

/** Shared builders so the contract tests assert on realistic rows. */
object TestFixtures {

    fun point(id: String, name: String = "点位 $id") = SurveyPoint(
        id = id,
        name = name,
        type = com.mobilelab.signalmap.data.PointType.CORRIDOR,
        wallMaterial = com.mobilelab.signalmap.data.WallMaterial.CONCRETE,
        obstruction = "两侧承重墙, 转角 90°",
        distanceM = 12.5,
        floor = 2,
        photoFile = "photos/${id}_20261118_101500.jpg",
    )

    fun row(
        pointId: String = "P01",
        repeatIndex: Int = 1,
        sessionId: String = "WS-20261118-001",
    ) = SurveyRow(
        sessionId = sessionId,
        pointId = pointId,
        pointName = "教学楼 2F 走廊中段（基准点）",
        pointType = "corridor",
        wallMaterial = "none",
        obstruction = "走廊两侧开放，无承重墙遮挡（基准点）",
        distanceM = 8.0,
        floor = 2,
        photoFile = "photos/${pointId}_20261118_101500.jpg",
        repeatIndex = repeatIndex,
        timestampIso = "2026-11-18T10:15:00.000+08:00",
        ssid = "CampusNet",
        bssid = "AA:BB:CC:DD:EE:FF",
        rssiDbm = -58,
        linkSpeedMbps = 866,
        txLinkMbps = 800,
        rxLinkMbps = 700,
        frequencyMhz = 5180,
        channel = 36,
        wifiStandard = "5G",
        latencyAvgMs = 12.34,
        latencyMinMs = 8.12,
        latencyMaxMs = 30.5,
        latencyMedianMs = 11.2,
        latencyP95Ms = 28.4,
        jitterMs = 2.5,
        lossPct = 5.0,
        tcpConnectMs = 1.5,
        httpTtfbMs = 120.4,
        httpTotalMs = 145.8,
        downKbps = null,
        apCount = 12,
        strongestNeighborRssi = -42,
        neighborChannels = "1;6;11;36",
        note = "未拍照",
    )

    fun scanEntry(bssid: String = "AA:BB:CC:DD:EE:FF") = ScanEntry(
        timestampIso = "2026-11-18T10:15:00.000+08:00",
        bssid = bssid,
        ssid = "Neighbour, \"lab\"",
        frequencyMhz = 2437,
        channel = 6,
        rssiDbm = -71,
        linkSpeedMbps = null,
        capabilities = "[WPA2-PSK-CCMP][ESS]",
    )
}
