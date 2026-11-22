package com.mobilelab.chargeaudit.model

/** 页面路由。 */
enum class Screen { HOME, RECORD, RESULTS, SETTINGS }

/** 充电宝/手机/测试仪的铭牌与参数。 */
data class Nameplate(
    val model: String = "Huawei CP11QC",
    val ratedEnergyWh: Double = 34.49,
    val ratedOutputMah: Double = 6200.0,
    val ratedOutputV: Double = 5.0,
    val cellTypicalMah: Double = 10000.0,
    val cellRatedMah: Double = 9500.0,
    val phoneCapacityMah: Double = 5400.0,
    val phoneNominalV: Double = 3.85,
    val testerModel: String = "",
) {
    /** 铭牌反推升压效率：额定输出容量×电压 / 额定能量。 */
    val nameplateBoostEta: Double
        get() = if (ratedEnergyWh > 0) ratedOutputMah / 1000.0 * ratedOutputV / ratedEnergyWh else Double.NaN
}

/** 一个 10 min 采样点。 */
data class AuditPoint(
    val index: Int,
    val timestampMs: Long,
    val elapsedMin: Double,
    val usbVoltageV: Double,
    val usbCurrentA: Double,
    val testerWhCum: Double? = null,
    val phonePct: Int? = null,
    val phoneVoltageMv: Int? = null,
    val phoneChargeUah: Long? = null,
    val phoneTempC: Double? = null,
    val charging: Boolean = true,
    val note: String = "",
) {
    val powerW: Double get() = usbVoltageV * usbCurrentA
}

/** 计算后的效率与损耗汇总。 */
data class ChargeSummary(
    val points: Int,
    val durationH: Double,
    val avgPowerW: Double,
    val peakPowerW: Double,
    val eOutWh: Double,
    val ePhoneWh: Double,
    val phoneEnergyMethod: String,
    val etaOutput: Double,
    val etaCharge: Double,
    val etaTotal: Double,
    val equivOutputMah: Double,
    val nameplateBoostEta: Double,
    val lossBoostWh: Double,
    val lossBoostPct: Double,
    val lossChargeWh: Double,
    val lossChargePct: Double,
    val lossTotalWh: Double,
    val lossTotalPct: Double,
    val startPct: Int?,
    val endPct: Int?,
)
