package com.mobilelab.powertrace.data

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mobilelab.core.log.LabLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 真实系统指标采集。所有字段来自 Android 公开 API 或 /sys 节点；
 * 不可用的字段返回 null，并在 [RawMetrics.unavailable] 中记录原因（写入 session.log），
 * 绝不填 0 或估计值。
 */
class MetricsCollector(private val context: Context) {

    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    data class RawMetrics(
        val batteryLevelPct: Int? = null,
        val batteryVoltageMv: Int? = null,
        val batteryTempC: Double? = null,
        val currentNowUa: Int? = null,
        val chargeCounterUah: Int? = null,
        val energyCounterNwh: Long? = null,
        val thermalStatus: Int? = null,
        val cpuTempC: Double? = null,
        val cpuFreqAvgMhz: Int? = null,
        val cpuFreqMaxMhz: Int? = null,
        val batteryCycleCount: Int? = null,
        val batteryHealth: Int? = null,
        val charging: Boolean = false,
        val screenOn: Boolean = true,
        val screenBrightnessPct: Int? = null,
        val ambientLux: Double? = null,
        val netType: String = "NONE",
        val totalRxBytes: Long? = null,
        val totalTxBytes: Long? = null,
        val memAvailableMb: Int? = null,
        val memTotalMb: Int? = null,
        val fps: Double? = null,
        val foregroundPkg: String? = null,
        val unavailable: List<String> = emptyList(),
    )

    /** 采一个点；耗时通常 < 2 s（光线传感器最多等 1.2 s）。 */
    fun collect(): RawMetrics {
        val unavailable = ArrayList<String>(4)
        val battery = readBattery(unavailable)
        val cpu = readCpu(unavailable)
        val (memAvailableMb, memTotalMb) = readMemory(unavailable)
        val net = readNetwork(unavailable)
        val lux = readAmbientLux(unavailable)
        return RawMetrics(
            batteryLevelPct = battery.levelPct,
            batteryVoltageMv = battery.voltageMv,
            batteryTempC = battery.tempC,
            currentNowUa = battery.currentUa,
            chargeCounterUah = battery.chargeCounterUah,
            energyCounterNwh = battery.energyCounterNwh,
            thermalStatus = readThermalStatus(unavailable),
            cpuTempC = cpu.tempC,
            cpuFreqAvgMhz = cpu.freqAvgMhz,
            cpuFreqMaxMhz = cpu.freqMaxMhz,
            batteryCycleCount = battery.cycleCount,
            batteryHealth = battery.health,
            charging = battery.charging,
            screenOn = battery.screenOn,
            screenBrightnessPct = battery.brightnessPct,
            ambientLux = lux,
            netType = net.type,
            totalRxBytes = net.rxBytes,
            totalTxBytes = net.txBytes,
            memAvailableMb = memAvailableMb,
            memTotalMb = memTotalMb,
            fps = FpsMeter.currentFps(),
            foregroundPkg = readForegroundPackage(),
            unavailable = unavailable,
        )
    }

    private data class BatteryReading(
        val levelPct: Int?,
        val voltageMv: Int?,
        val tempC: Double?,
        val currentUa: Int?,
        val chargeCounterUah: Int?,
        val energyCounterNwh: Long?,
        val cycleCount: Int?,
        val health: Int?,
        val charging: Boolean,
        val screenOn: Boolean,
        val brightnessPct: Int?,
    )

    private fun readBattery(unavailable: MutableList<String>): BatteryReading {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (intent == null) unavailable += "battery_sticky_intent"
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPct = if (level >= 0 && scale > 0) (level * 100.0 / scale).roundToInt().coerceIn(0, 100) else null
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.takeIf { it >= 0 }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
        val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }
        } else {
            null
        }
        if (cycleCount == null) unavailable += "battery_cycle_count(<14_or_unsupported)"
        if (tempC == null) unavailable += "battery_temp"

        val current = readLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { abs(it) < 5_000_000L }?.toInt()
        if (current == null) unavailable += "current_now_unsupported"
        val chargeCounter = readLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toInt()
        if (chargeCounter == null) unavailable += "charge_counter_unsupported"
        // nWh 会超过 Int 上限，保留 Long；只做合理区间校验（0 < x < 1e13）。
        val energyCounter = readLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            ?.takeIf { it in 1L..10_000_000_000_000L }
        if (energyCounter == null) unavailable += "energy_counter_unsupported"

        return BatteryReading(
            levelPct = levelPct,
            voltageMv = voltage,
            tempC = tempC,
            currentUa = current,
            chargeCounterUah = chargeCounter,
            energyCounterNwh = energyCounter,
            cycleCount = cycleCount,
            health = health,
            charging = charging,
            screenOn = SystemAccess.isScreenOn(context),
            brightnessPct = readBrightnessPct(),
        )
    }

    private fun readLongProperty(property: Int): Long? = runCatching {
        val value = batteryManager.getLongProperty(property)
        if (value == Long.MIN_VALUE || value == Int.MIN_VALUE.toLong()) null else value
    }.getOrNull()

    private fun readBrightnessPct(): Int? = runCatching {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        if (raw in 0..255) (raw * 100.0 / 255.0).roundToInt().coerceIn(0, 100) else null
    }.getOrNull()

    private fun readThermalStatus(unavailable: MutableList<String>): Int? = runCatching {
        powerManager.currentThermalStatus
    }.onFailure { unavailable += "thermal_status" }.getOrNull()

    private data class CpuReading(val tempC: Double?, val freqAvgMhz: Int?, val freqMaxMhz: Int?)

    private fun readCpu(unavailable: MutableList<String>): CpuReading {
        val zones = readThermalZones()
        val cpuZones = zones.filter { (type, _) ->
            val t = type.lowercase()
            listOf("cpu", "soc", "tsens", "core", "cluster", "ap_").any { t.contains(it) }
        }
        val picked = if (cpuZones.isNotEmpty()) cpuZones.map { it.second } else zones
            .filterNot { (type, _) ->
                val t = type.lowercase()
                listOf("battery", "charger", "usb", "chg", "skin", "quiet").any { t.contains(it) }
            }
            .map { it.second }
        val tempC = picked.filter { it in 5.0..125.0 }.maxOrNull()
        if (tempC == null) unavailable += "cpu_temp(thermal_zone_unreadable)"

        val freqs = readCpuFrequenciesMhz()
        if (freqs.isEmpty()) unavailable += "cpu_freq"
        return CpuReading(
            tempC = tempC,
            freqAvgMhz = if (freqs.isEmpty()) null else freqs.sum().toDouble().div(freqs.size).roundToInt(),
            freqMaxMhz = freqs.maxOrNull(),
        )
    }

    /** 读取所有 thermal zone（type, 摄氏度）。节点不可读时静默跳过。 */
    private fun readThermalZones(): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        var misses = 0
        for (index in 0 until 40) {
            val dir = "/sys/class/thermal/thermal_zone$index"
            val type = readSysText("$dir/type")
            if (type == null) {
                misses++
                if (misses >= 3) break
                continue
            }
            misses = 0
            val raw = readSysText("$dir/temp")?.toDoubleOrNull() ?: continue
            val celsius = if (raw > 1_000.0) raw / 1000.0 else raw
            if (celsius.isFinite()) out += type to celsius
        }
        return out
    }

    private fun readCpuFrequenciesMhz(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 32)
        val out = ArrayList<Int>()
        for (index in 0 until cores) {
            val khz = readSysText("/sys/devices/system/cpu/cpu$index/cpufreq/scaling_cur_freq")
                ?.toLongOrNull() ?: continue
            if (khz <= 0L) continue
            out += (khz / 1000).toInt()
        }
        return out
    }

    private fun readSysText(path: String): String? = runCatching {
        val file = File(path)
        if (!file.canRead()) return null
        file.readText().trim().ifEmpty { null }
    }.getOrNull()

    private fun readMemory(unavailable: MutableList<String>): Pair<Int?, Int?> {
        return runCatching {
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            val avail: Int? = (info.availMem / 1_048_576L).toInt()
            val total: Int? = (info.totalMem / 1_048_576L).toInt()
            avail to total
        }.onFailure { unavailable += "meminfo" }.getOrDefault(null to null)
    }

    private data class NetReading(val type: String, val rxBytes: Long?, val txBytes: Long?)

    private fun readNetwork(unavailable: MutableList<String>): NetReading {
        val type = runCatching {
            val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            when {
                caps == null -> "NONE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "NONE".also { unavailable += "net_type_other_transport" }
            }
        }.onFailure { unavailable += "net_type" }.getOrDefault("NONE")

        val rx = TrafficStats.getTotalRxBytes().takeIf { it >= 0L }
        val tx = TrafficStats.getTotalTxBytes().takeIf { it >= 0L }
        if (rx == null || tx == null) unavailable += "traffic_stats_unsupported"
        return NetReading(type = type, rxBytes = rx, txBytes = tx)
    }

    /** 光线传感器读一次；无传感器/超时返回 null。 */
    private fun readAmbientLux(unavailable: MutableList<String>): Double? {
        val sensor = runCatching { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }.getOrNull()
        if (sensor == null) {
            unavailable += "ambient_lux(no_sensor)"
            return null
        }
        val latch = CountDownLatch(1)
        var lux: Double? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) lux = event.values[0].toDouble()
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = runCatching {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        if (!registered) {
            unavailable += "ambient_lux(register_failed)"
            return null
        }
        try {
            latch.await(SamplingConfig.LUX_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { sensorManager.unregisterListener(listener) }
        }
        val value = lux
        if (value == null) unavailable += "ambient_lux(timeout)"
        return value
    }

    /** 最近 10 s 内最后一次 ACTIVITY_RESUMED 的包名；未授权使用情况访问时返回 null。 */
    private fun readForegroundPackage(): String? {
        val manager = usageStats ?: return null
        if (!SystemAccess.hasUsageStats(context)) return null
        return runCatching {
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - SamplingConfig.FOREGROUND_WINDOW_MS, now) ?: return null
            val event = UsageEvents.Event()
            var pkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    pkg = event.packageName
                }
            }
            pkg
        }.onFailure { LabLog.w(TAG, "readForegroundPackage failed: ${it.message}") }.getOrNull()
    }

    private companion object {
        const val TAG = "PowerTraceMetrics"
    }
}
