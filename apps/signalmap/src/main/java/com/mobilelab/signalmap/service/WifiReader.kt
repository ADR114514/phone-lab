package com.mobilelab.signalmap.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.mobilelab.core.log.LabLog
import com.mobilelab.signalmap.data.ScanEntry
import com.mobilelab.signalmap.data.WifiMath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thin, defensive wrapper around WifiManager. All platform calls are wrapped in
 * runCatching: OEM ROMs throw SecurityException/IllegalStateException in
 * surprising places and a survey must never crash because of a radio hiccup.
 */
class WifiReader(private val context: Context) {

    private val app = context.applicationContext
    private val wifiManager: WifiManager? = app.getSystemService(WifiManager::class.java)

    data class ConnectionSnapshot(
        val connected: Boolean,
        val ssid: String,
        val bssid: String,
        val rssiDbm: Int?,
        val linkSpeedMbps: Int?,
        val txLinkMbps: Int?,
        val rxLinkMbps: Int?,
        val frequencyMhz: Int?,
        val channel: Int?,
        val wifiStandard: String,
    )

    data class ScanOutcome(
        val entries: List<ScanEntry>,
        /** "callback" = fresh scan, "cache" = throttled/fresh results unavailable, "blocked". */
        val source: String,
        val note: String,
        val rawCount: Int,
    )

    @Suppress("DEPRECATION")
    fun connection(): ConnectionSnapshot {
        val info = runCatching { wifiManager?.connectionInfo }.getOrNull()
        val networkId = info?.networkId ?: -1
        val frequency = info?.frequency?.takeIf { it > 0 }
        val connected = networkId >= 0 || wifiTransportActive()
        return ConnectionSnapshot(
            connected = connected,
            ssid = WifiMath.sanitizeSsid(info?.ssid),
            bssid = WifiMath.sanitizeBssid(info?.bssid),
            rssiDbm = if (networkId >= 0) info?.rssi?.takeIf { it <= 0 } else null,
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            txLinkMbps = info?.txLinkSpeedMbps?.takeIf { it > 0 },
            rxLinkMbps = info?.rxLinkSpeedMbps?.takeIf { it > 0 },
            frequencyMhz = frequency,
            channel = WifiMath.channelOf(frequency),
            wifiStandard = WifiMath.bandOf(frequency),
        )
    }

    private fun wifiTransportActive(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** dhcpInfo.gateway as dotted quad, or null when the platform has no lease. */
    fun gatewayIpv4(): String? {
        @Suppress("DEPRECATION")
        val dhcp = runCatching { wifiManager?.dhcpInfo }.getOrNull() ?: return null
        val gateway = dhcp.gateway
        if (gateway == 0) return null
        val ip = WifiMath.intToIpv4(gateway)
        return if (ip == "0.0.0.0") null else ip
    }

    /**
     * Requests a fresh scan and waits for the platform callback (bounded by
     * [timeoutMs]). Throttled scans fall back to the cached result list, which
     * is still real measured data; that fallback is recorded in the note.
     */
    suspend fun scan(timestampIso: String, timeoutMs: Long = 6_000L): ScanOutcome {
        val manager = wifiManager
            ?: return ScanOutcome(emptyList(), "blocked", "Wi-Fi 服务不可用，跳过邻道扫描", 0)
        if (!EnvironmentProbe.check(app).radioReadable) {
            return ScanOutcome(emptyList(), "blocked", "定位权限或定位开关缺失，未执行邻道扫描", 0)
        }

        val signal = CompletableDeferred<Unit>()
        val callback = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                signal.complete(Unit)
            }
        }
        var registered = false
        var started = false
        var timedOut = false
        try {
            registered = runCatching {
                manager.registerScanResultsCallback(ContextCompat.getMainExecutor(app), callback)
                true
            }.onFailure { LabLog.w(TAG, "registerScanResultsCallback failed: ${it.message}") }
                .getOrDefault(false)
            started = runCatching { manager.startScan() }
                .onFailure { LabLog.w(TAG, "startScan threw: ${it.message}") }
                .getOrDefault(false)
            if (!started) LabLog.w(TAG, "startScan returned false (throttled?)")
            if (registered) {
                timedOut = withTimeoutOrNull(timeoutMs) { signal.await() } == null
            }
        } finally {
            if (registered) runCatching { manager.unregisterScanResultsCallback(callback) }
        }

        // Android 12+ 读取扫描缓存需要定位权限（13+ 还需 NEARBY_WIFI_DEVICES）：
        // 在这里显式复核，权限缺失时返回空列表而不是依赖 SecurityException 兜底。
        val canReadScanResults =
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(app, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED)
        @Suppress("DEPRECATION")
        val raw: List<ScanResult> = if (canReadScanResults) {
            runCatching { manager.scanResults }
                .onFailure { LabLog.w(TAG, "getScanResults failed: ${it.message}") }
                .getOrDefault(emptyList())
        } else {
            LabLog.w(TAG, "scan results skipped: location/nearby-wifi permission missing")
            emptyList()
        }

        val entries = raw.mapNotNull { result -> toEntry(result, timestampIso) }
        val note = when {
            entries.isEmpty() && !started -> "扫描被系统节流且无缓存结果"
            entries.isEmpty() -> "扫描无结果"
            timedOut -> "扫描回调超时，使用缓存结果"
            !started -> "扫描被节流，使用缓存结果"
            else -> ""
        }
        return ScanOutcome(
            entries = entries,
            source = if (timedOut || !started) "cache" else "callback",
            note = note,
            rawCount = raw.size,
        )
    }

    private fun toEntry(result: ScanResult, timestampIso: String): ScanEntry? {
        val frequency = result.frequency
        if (frequency <= 0) return null
        val level = result.level
        if (level > 0 || level < -127) return null
        val bssid = WifiMath.sanitizeBssid(result.BSSID)
        if (bssid.isEmpty() && result.SSID.isNullOrEmpty()) return null
        return ScanEntry(
            timestampIso = timestampIso,
            bssid = bssid,
            ssid = WifiMath.sanitizeSsid(result.SSID),
            frequencyMhz = frequency,
            channel = WifiMath.channelOf(frequency),
            rssiDbm = level,
            // ScanResult on Android 12+ has no per-AP link speed: leave empty.
            linkSpeedMbps = null,
            capabilities = result.capabilities ?: "",
        )
    }

    companion object {
        private const val TAG = "WifiReader"
    }
}
