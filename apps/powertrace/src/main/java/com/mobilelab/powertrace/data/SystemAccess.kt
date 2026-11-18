package com.mobilelab.powertrace.data

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mobilelab.core.permission.canScheduleExactAlarms
import com.mobilelab.core.permission.hasPermission
import com.mobilelab.core.permission.isIgnoringBatteryOptimizations
import com.mobilelab.core.permission.requestExactAlarmPermission
import com.mobilelab.core.permission.requestIgnoreBatteryOptimizations

/**
 * 权限/特殊授权的统一状态查询与跳转。
 *
 * 设计原则（spec）：权限被拒绝时降级并提示，不崩溃、不伪造数据 ——
 * 例如没有"使用情况访问"时 foreground_pkg 写空、没有精确闹钟时退化为不精确闹钟。
 */
object SystemAccess {

    data class Snapshot(
        val notifications: Boolean,
        val exactAlarm: Boolean,
        val batteryUnrestricted: Boolean,
        val usageStats: Boolean,
        val charging: Boolean?,
        val autoBrightness: Boolean,
        val screenOn: Boolean,
        val locationEnabled: Boolean,
        val network: String,
    ) {
        /** 影响测量质量但可继续采集的项。 */
        val warnings: List<String>
            get() = buildList {
                if (!notifications) add("未授予通知权限：前台服务进度不可见")
                if (!exactAlarm) add("未授予精确闹钟：采样误差可能超过 ±90 s")
                if (!batteryUnrestricted) add("未豁免电池优化：Doze 可能延迟采样")
                if (!usageStats) add("未授予使用情况访问：foreground_pkg 将写空")
                if (charging == true) add("设备正在充电：采集期间应为放电状态")
                if (autoBrightness) add("屏幕为自动亮度：亮度会在采集期间变化")
            }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    fun hasExactAlarm(context: Context): Boolean = runCatching { canScheduleExactAlarms(context) }.getOrDefault(false)

    fun hasBatteryExemption(context: Context): Boolean =
        runCatching { isIgnoringBatteryOptimizations(context) }.getOrDefault(false)

    /**
     * "使用情况访问"是 appop 授权（不是 runtime 权限）。部分机型在授权后
     * checkOp 仍返回 MODE_DEFAULT，因此再用一次真实查询兜底。
     */
    fun hasUsageStats(context: Context): Boolean {
        val checkOp = runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        if (checkOp) return true
        return runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 3_600_000L, now) ?: return false
            events.hasNextEvent()
        }.getOrDefault(false)
    }

    fun isCharging(context: Context): Boolean? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
    }.getOrNull()

    fun isAutoBrightness(context: Context): Boolean = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    fun isScreenOn(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive
    }.getOrDefault(true)

    /** 当前网络的粗略分类（导航工况需要蜂窝/WiFi 在线）。 */
    fun networkLabel(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "无网络"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
            else -> "其他"
        }
    }.getOrDefault("未知")

    /** 定位开关（导航拍摄工况需要，Android 12 起由用户开关控制）。 */
    fun locationEnabled(context: Context): Boolean =
        runCatching { com.mobilelab.core.permission.isLocationEnabled(context) }.getOrDefault(false)

    fun snapshot(context: Context): Snapshot = Snapshot(
        notifications = hasNotificationPermission(context),
        exactAlarm = hasExactAlarm(context),
        batteryUnrestricted = hasBatteryExemption(context),
        usageStats = hasUsageStats(context),
        charging = isCharging(context),
        autoBrightness = isAutoBrightness(context),
        screenOn = isScreenOn(context),
        locationEnabled = locationEnabled(context),
        network = networkLabel(context),
    )

    private fun start(context: Context, intent: Intent) {
        runCatching {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun openAppDetails(context: Context) = start(
        context,
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null)),
    )

    fun openNotificationSettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openUsageAccessSettings(context: Context) =
        start(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun requestExactAlarmAccess(context: Context): Boolean =
        runCatching { requestExactAlarmPermission(context) }.getOrDefault(false)

    fun requestBatteryExemption(context: Context): Boolean =
        runCatching { requestIgnoreBatteryOptimizations(context) }.getOrDefault(false)

    fun openBatteryOptimizationSettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** App 是否处于前台（用于 fps/使用情况日志，不参与 CSV 正确性）。 */
    fun appForeground(context: Context): Boolean = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 30_000L, now) ?: return false
        val e = android.app.usage.UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        last == context.packageName
    }.getOrDefault(false)

    /** 运行时权限列表（minSdk 31：仅通知需要运行时申请）。 */
    fun runtimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun grantedRuntimePermissions(context: Context): Boolean =
        runtimePermissions().all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
}
