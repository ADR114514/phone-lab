package com.mobilelab.chargeaudit.math

import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.ChargeSummary
import com.mobilelab.chargeaudit.model.Nameplate
import kotlin.math.abs
import kotlin.math.max

/** 纯计算逻辑：可在 JVM 单测里直接验证（不依赖 Android）。 */
object ChargeMath {

    fun powerW(voltageV: Double, currentA: Double): Double = voltageV * currentA

    /** 梯形积分：输入按时间排序。 */
    fun trapezoidEnergyWh(points: List<AuditPoint>): Double {
        if (points.size < 2) return Double.NaN
        var total = 0.0
        for (index in 1 until points.size) {
            val dtH = (points[index].elapsedMin - points[index - 1].elapsedMin) / 60.0
            if (!dtH.isFinite() || dtH <= 0.0) continue
            total += (points[index - 1].powerW + points[index].powerW) / 2.0 * dtH
        }
        return total
    }

    /** 手机电池获得能量：优先电荷计差分，否则电量百分比 × 容量。 */
    fun phoneEnergyWh(points: List<AuditPoint>, capacityMah: Double, nominalV: Double): Pair<Double, String> {
        val withCounter = points.filter { it.phoneChargeUah != null && it.phoneVoltageMv != null }
        if (withCounter.size >= 2) {
            val first = withCounter.first()
            val last = withCounter.last()
            val deltaUah = (first.phoneChargeUah ?: 0L) - (last.phoneChargeUah ?: 0L)
            val voltageMv = withCounter.mapNotNull { it.phoneVoltageMv }.average()
            if (deltaUah > 0 && voltageMv > 0) {
                return deltaUah.toDouble() * voltageMv / 1e9 to "charge_counter"
            }
        }
        val pcts = points.mapNotNull { it.phonePct }
        if (pcts.size >= 2 && pcts.last() > pcts.first()) {
            val delta = (pcts.last() - pcts.first()) / 100.0
            return delta * capacityMah / 1000.0 * nominalV to "battery_pct_x_capacity"
        }
        return Double.NaN to "unavailable"
    }

    fun summarize(points: List<AuditPoint>, nameplate: Nameplate): ChargeSummary? {
        if (points.size < 2) return null
        val sorted = points.sortedBy { it.elapsedMin }
        val eOut = trapezoidEnergyWh(sorted)
        val (ePhone, method) = phoneEnergyWh(sorted, nameplate.phoneCapacityMah, nameplate.phoneNominalV)
        val durationH = max(0.0, (sorted.last().elapsedMin - sorted.first().elapsedMin) / 60.0)
        val powers = sorted.map { it.powerW }.filter { it.isFinite() }
        val avg = if (powers.isEmpty()) Double.NaN else powers.average()
        val peak = if (powers.isEmpty()) Double.NaN else powers.max()
        val rated = nameplate.ratedEnergyWh
        val etaOutput = if (rated > 0) eOut / rated else Double.NaN
        val etaCharge = if (eOut > 0 && ePhone.isFinite()) ePhone / eOut else Double.NaN
        val etaTotal = if (rated > 0 && ePhone.isFinite()) ePhone / rated else Double.NaN
        val equivMah = if (nameplate.ratedOutputV > 0) eOut / nameplate.ratedOutputV * 1000.0 else Double.NaN
        val lossBoost = if (rated > 0 && eOut.isFinite()) max(0.0, rated - eOut) else Double.NaN
        val lossCharge = if (eOut.isFinite() && ePhone.isFinite()) max(0.0, eOut - ePhone) else Double.NaN
        val lossTotal = if (rated > 0 && ePhone.isFinite()) max(0.0, rated - ePhone) else Double.NaN
        return ChargeSummary(
            points = sorted.size,
            durationH = durationH,
            avgPowerW = avg,
            peakPowerW = peak,
            eOutWh = eOut,
            ePhoneWh = ePhone,
            phoneEnergyMethod = method,
            etaOutput = etaOutput,
            etaCharge = etaCharge,
            etaTotal = etaTotal,
            equivOutputMah = equivMah,
            nameplateBoostEta = nameplate.nameplateBoostEta,
            lossBoostWh = lossBoost,
            lossBoostPct = if (rated > 0) lossBoost / rated * 100.0 else Double.NaN,
            lossChargeWh = lossCharge,
            lossChargePct = if (rated > 0 && lossCharge.isFinite()) lossCharge / rated * 100.0 else Double.NaN,
            lossTotalWh = lossTotal,
            lossTotalPct = if (rated > 0 && lossTotal.isFinite()) lossTotal / rated * 100.0 else Double.NaN,
            startPct = sorted.firstOrNull { it.phonePct != null }?.phonePct,
            endPct = sorted.lastOrNull { it.phonePct != null }?.phonePct,
        )
    }

    fun fmt(value: Double?, digits: Int = 2, unit: String = ""): String {
        if (value == null || !value.isFinite()) return "--"
        return when (digits) {
            0 -> "%.0f".format(java.util.Locale.US, value) + unit
            1 -> "%.1f".format(java.util.Locale.US, value) + unit
            3 -> "%.3f".format(java.util.Locale.US, value) + unit
            else -> "%.2f".format(java.util.Locale.US, value) + unit
        }
    }
}
