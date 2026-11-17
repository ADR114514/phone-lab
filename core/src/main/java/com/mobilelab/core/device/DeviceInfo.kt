package com.mobilelab.core.device

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.mobilelab.core.json.JsonObject
import java.lang.reflect.Method
import java.util.Locale

data class DeviceSnapshot(
    val model: String,
    val manufacturer: String,
    val brand: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val socManufacturer: String,
    val socModel: String,
    val abi: String,
    val supportedAbis: String,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val batteryCapacityMah: Int,
    val locale: String,
    val timeZone: String,
)

fun collectDeviceSnapshot(context: Context): DeviceSnapshot {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    val refresh = runCatching { wm.defaultDisplay.refreshRate }.getOrDefault(0f)
    return DeviceSnapshot(
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        device = Build.DEVICE,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else "",
        socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
        supportedAbis = Build.SUPPORTED_ABIS.joinToString(";"),
        screenWidthPx = metrics.widthPixels,
        screenHeightPx = metrics.heightPixels,
        densityDpi = metrics.densityDpi,
        refreshRateHz = refresh,
        batteryCapacityMah = readBatteryCapacityMah(context),
        locale = Locale.getDefault().toLanguageTag(),
        timeZone = java.util.TimeZone.getDefault().id,
    )
}

/**
 * Reads the design capacity through the hidden PowerProfile API. Devices that
 * hide it return 0 and the analysis falls back to measured charge deltas.
 */
private fun readBatteryCapacityMah(context: Context): Int {
    return runCatching {
        val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
        val profile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
        val method: Method = powerProfileClass.getMethod("getBatteryCapacity")
        val value = method.invoke(profile) as Double
        if (value.isFinite() && value > 0) value.toInt() else 0
    }.getOrDefault(0)
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun deviceManifestJson(
    appLabel: String,
    appVersion: String,
    projectCode: String,
    snapshot: DeviceSnapshot,
    extras: Map<String, Any?> = emptyMap(),
): String {
    val root = JsonObject()
        .put("schema", "phonelab.manifest/1")
        .put("project", projectCode)
        .put("app", appLabel)
        .put("appVersion", appVersion)
        .put("generatedAt", System.currentTimeMillis())
        .put("device", JsonObject()
            .put("model", snapshot.model)
            .put("manufacturer", snapshot.manufacturer)
            .put("brand", snapshot.brand)
            .put("device", snapshot.device)
            .put("androidRelease", snapshot.androidRelease)
            .put("sdkInt", snapshot.sdkInt)
            .put("socManufacturer", snapshot.socManufacturer)
            .put("socModel", snapshot.socModel)
            .put("abi", snapshot.abi)
            .put("supportedAbis", snapshot.supportedAbis)
            .put("screenWidthPx", snapshot.screenWidthPx)
            .put("screenHeightPx", snapshot.screenHeightPx)
            .put("densityDpi", snapshot.densityDpi)
            .put("refreshRateHz", snapshot.refreshRateHz)
            .put("batteryCapacityMah", snapshot.batteryCapacityMah)
            .put("locale", snapshot.locale)
            .put("timeZone", snapshot.timeZone))
    extras.forEach { (k, v) -> root.put(k, v) }
    return root.toJson()
}
