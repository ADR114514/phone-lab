package com.mobilelab.powertrace.data

import kotlin.math.abs

/**
 * 功耗/流量/能量的纯计算，全部可在 JVM 单元测试中直接验证（不依赖 Android）。
 */
object PowerMath {

    /**
     * `power_w = voltage_mv × |current_ua| / 1e9`。
     *
     * 厂商对 BATTERY_PROPERTY_CURRENT_NOW 的符号约定不一致（放电有正有负），
     * 这里统一取绝对值；符号约定在 session.log 中记录一次。
     */
    fun powerW(voltageMv: Int?, currentNowUa: Int?): Double? {
        if (voltageMv == null || currentNowUa == null) return null
        if (voltageMv <= 0) return null
        return voltageMv.toDouble() * abs(currentNowUa.toLong()) / 1_000_000_000.0
    }

    /**
     * 由电荷计差分估算平均功率（W）。
     *
     * 很多机型（如 OnePlus/ColorOS）的 BATTERY_PROPERTY_CURRENT_NOW 只有 µA 级噪声/不可用，
     * 此时用 charge_counter_uah 差分更可靠：1 mAh @ 3.85 V ≈ 3.85 mWh，
     * 5 min 窗口分辨率约 0.046 W，足以区分待机/视频/导航/游戏四档。
     *
     * 公式推导：ΔQ(C) = ΔµAh × 3.6e-3；P = ΔQ × V / dt = ΔµAh × V_mV × 3.6e-3 × 1e-3 / (dt_ms × 1e-3)。
     * 化简为：P = ΔµAh × V_mV × 3.6 / (dt_ms × 1000)。
     *
     * @param deltaUah 放电期间的电荷差（前一次读数 − 本次读数，>0）
     * @param voltageMv 电池电压（mV）
     * @param dtMs 两次读数的时间差（ms）
     */
    fun powerWFromCharge(deltaUah: Long, voltageMv: Int, dtMs: Long): Double? {
        if (deltaUah <= 0L || dtMs <= 0L) return null
        if (voltageMv <= 0) return null
        val watts = deltaUah.toDouble() * voltageMv * 3.6 / (dtMs.toDouble() * 1000.0)
        return if (watts.isFinite() && watts >= 0.0) watts else null
    }

    /**
     * current_now_ua 是否足以作为功率主口径。
     * 实测 OnePlus 12 待机仅 -26 µA、真实会话 -93~+521 µA，量级明显异常；
     * 低于 2 mA 时视为不可用，改用电荷计差分。
     */
    fun isCurrentPlausible(currentNowUa: Int?): Boolean =
        currentNowUa != null && abs(currentNowUa.toLong()) >= MIN_PLAUSIBLE_CURRENT_UA

    const val MIN_PLAUSIBLE_CURRENT_UA = 2_000L

    /**
     * 能量积分（Wh）。
     *
     * 矩形积分：第 i 个采样点的功率代表其后 [intervalSec] 秒的区间，
     * 6 个点 × 300 s = 1800 s = 30 min，正好覆盖一个工况。
     * 空采样点跳过；无有效点时返回 null（而不是 0，避免伪造成"零功耗"）。
     */
    fun energyWh(powers: List<Double?>, intervalSec: Long = SamplingConfig.INTERVAL_SEC): Double? {
        if (powers.none { it != null && it.isFinite() }) return null
        var acc = 0.0
        for (p in powers) {
            if (p != null && p.isFinite()) acc += p * intervalSec / 3600.0
        }
        return acc
    }

    /** 采样区间内的平均速率：kbps = Δbytes × 8 / 1000 / Δs。 */
    fun kbps(deltaBytes: Long?, elapsedMs: Long): Double? {
        if (deltaBytes == null || deltaBytes < 0L) return null
        if (elapsedMs <= 0L) return null
        return deltaBytes.toDouble() * 8.0 / 1000.0 / (elapsedMs / 1000.0)
    }

    /** 由逐样本平均速率换算成区间总流量（MB，1 MB = 1048576 bytes）。 */
    fun megabytesFromKbps(ratesKbps: List<Double?>, intervalSec: Long = SamplingConfig.INTERVAL_SEC): Double? {
        if (ratesKbps.none { it != null && it.isFinite() }) return null
        var bytes = 0.0
        for (rate in ratesKbps) {
            if (rate != null && rate.isFinite()) {
                bytes += rate * 1000.0 / 8.0 * intervalSec
            }
        }
        return bytes / 1024.0 / 1024.0
    }

    /** 平均值（忽略空值）；无有效值返回 null。 */
    fun avg(values: List<Double?>): Double? {
        val valid = values.filterNotNull().filter { it.isFinite() }
        if (valid.isEmpty()) return null
        return valid.sum() / valid.size
    }

    /** 最大值（忽略空值）；无有效值返回 null。 */
    fun max(values: List<Double?>): Double? {
        val valid = values.filterNotNull().filter { it.isFinite() }
        return valid.maxOrNull()
    }

    fun avgInt(values: List<Int?>): Int? {
        val valid = values.filterNotNull()
        if (valid.isEmpty()) return null
        return Math.round(valid.sum().toDouble() / valid.size).toInt()
    }

    fun maxInt(values: List<Int?>): Int? = values.filterNotNull().maxOrNull()

    /** 温度/电量等整数差分，任一为空返回 null。 */
    fun diffInt(start: Int?, end: Int?): Int? =
        if (start == null || end == null) null else end - start

    fun diffDouble(start: Double?, end: Double?): Double? =
        if (start == null || end == null) null else end - start

    /**
     * thermal 上升事件数：相邻采样点中 thermal_status 抬升的次数
     * （用于衡量发热累积速率，数据字典 1.3 的 thermal_rise_events）。
     */
    fun thermalRiseEvents(values: List<Int?>): Int? {
        val valid = values.filterNotNull()
        if (valid.size < 2) return null
        var count = 0
        for (i in 1 until valid.size) {
            if (valid[i] > valid[i - 1]) count++
        }
        return count
    }
}
