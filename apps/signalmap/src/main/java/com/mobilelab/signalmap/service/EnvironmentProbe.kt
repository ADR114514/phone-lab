package com.mobilelab.signalmap.service

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.mobilelab.core.permission.Permissions
import com.mobilelab.core.permission.hasPermission
import com.mobilelab.core.permission.isLocationEnabled
import com.mobilelab.signalmap.data.WifiMath

/**
 * Snapshot of everything that can block a measurement. The Home screen renders
 * it as a red/green checklist with a repair action per row, and the pipeline
 * re-checks it before writing anything so a permission revoked mid-session can
 * never produce invented data.
 */
data class EnvironmentStatus(
    val wifiConnected: Boolean,
    val internetCapable: Boolean,
    val ssid: String,
    val rssiDbm: Int?,
    val frequencyMhz: Int?,
    val locationEnabled: Boolean,
    val locationPermission: Boolean,
    val nearbyWifiRequired: Boolean,
    val nearbyWifiGranted: Boolean,
    val cameraPermission: Boolean,
    /** True when SSID/RSSI/scan data can actually be read (permission + switch). */
    val radioReadable: Boolean,
) {
    val measurementReady: Boolean
        get() = wifiConnected && locationEnabled && locationPermission && nearbyWifiGranted

    val blockingReasons: List<String>
        get() = buildList {
            if (!wifiConnected) add("未连接 Wi-Fi：请先连接校园网后再开始测绘")
            if (!locationPermission) add("未授予定位权限：Android 12+ 读取 RSSI 与扫描结果的前提")
            if (!locationEnabled) add("系统定位开关未开启：请打开「设置 → 位置信息」")
            if (nearbyWifiRequired && !nearbyWifiGranted) {
                add("未授予「附近的 Wi-Fi 设备」权限（Android 13+ 扫描必需）")
            }
        }

    val blockingSummary: String
        get() = if (blockingReasons.isEmpty()) "环境检查通过" else blockingReasons.joinToString("；")
}

object EnvironmentProbe {

    /** Runtime permission introduced in Android 13 (API 33) for Wi-Fi scanning. */
    const val NEARBY_WIFI_DEVICES: String = "android.permission.NEARBY_WIFI_DEVICES"

    fun needsNearbyWifiPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Permissions that must be granted before a measurement may start. */
    fun measurementPermissions(): List<String> = buildList {
        add(Permissions.fineLocation)
        add(Permissions.coarseLocation)
        if (needsNearbyWifiPermission()) add(NEARBY_WIFI_DEVICES)
    }

    fun cameraPermission(): String = Permissions.camera

    fun check(context: Context): EnvironmentStatus {
        val app = context.applicationContext

        // Android 12+ only exposes RSSI / scan results with PRECISE location; a
        // coarse-only grant would silently yield empty radio data, so it does
        // not count as satisfied here.
        val locationPermission = hasPermission(app, Permissions.fineLocation)
        val locationOn = runCatching { isLocationEnabled(app) }.getOrDefault(false)

        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val wifiTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val wifiManager = app.getSystemService(WifiManager::class.java)
        // Reading connection info requires location permission AND an enabled
        // location switch on Android 12+; otherwise the platform throws or
        // returns placeholder values.
        val info = if (locationPermission && locationOn) {
            @Suppress("DEPRECATION")
            runCatching { wifiManager?.connectionInfo }.getOrNull()
        } else {
            null
        }
        val networkId = info?.networkId ?: -1
        val frequency = info?.frequency?.takeIf { it > 0 }
        val wifiConnected = wifiTransport || networkId >= 0
        val rssi = if (networkId >= 0) info?.rssi?.takeIf { it <= 0 } else null

        return EnvironmentStatus(
            wifiConnected = wifiConnected,
            internetCapable = internet,
            ssid = WifiMath.sanitizeSsid(info?.ssid),
            rssiDbm = rssi,
            frequencyMhz = frequency,
            locationEnabled = locationOn,
            locationPermission = locationPermission,
            nearbyWifiRequired = needsNearbyWifiPermission(),
            nearbyWifiGranted = !needsNearbyWifiPermission() ||
                hasPermission(app, NEARBY_WIFI_DEVICES),
            cameraPermission = hasPermission(app, Permissions.camera),
            radioReadable = locationPermission && locationOn,
        )
    }

    /** Camera is required for the photo step but the operator may skip photos. */
    fun isCameraFeatureAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
}
