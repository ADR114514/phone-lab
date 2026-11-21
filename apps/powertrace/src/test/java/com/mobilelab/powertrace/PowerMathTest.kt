package com.mobilelab.powertrace

import com.mobilelab.powertrace.data.PowerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 功率 / 能量 / 字节速率的核心计算（spec 验收项之一）。 */
class PowerMathTest {

    @Test
    fun powerW_usesAbsoluteCurrentAndMilliVolts() {
        // 4200 mV × 500000 µA / 1e9 = 2.1 W
        assertEquals(2.1, PowerMath.powerW(4200, 500_000)!!, 1e-9)
        // 厂商电流符号相反的机型应得到同样的功率
        assertEquals(2.1, PowerMath.powerW(4200, -500_000)!!, 1e-9)
        // 1 mA 的极小电流
        assertEquals(0.0042, PowerMath.powerW(4200, 1_000)!!, 1e-9)
    }

    @Test
    fun powerW_returnsNullWhenInputUnavailable() {
        assertNull(PowerMath.powerW(null, 500_000))
        assertNull(PowerMath.powerW(4200, null))
        assertNull(PowerMath.powerW(0, 500_000))
    }

    @Test
    fun energyWh_rectangularIntegrationOverSixFiveMinuteBuckets() {
        // 6 点恒定 2.1 W = 2.1 × 6 × 300 s / 3600 = 1.05 Wh
        val constant = List(6) { 2.1 }
        assertEquals(1.05, PowerMath.energyWh(constant)!!, 1e-9)
        // 空值跳过（只剩 5 个有效区间）；全空返回 null 而不是伪造 0
        assertEquals(0.875, PowerMath.energyWh(listOf(2.1, null, 2.1, 2.1, 2.1, 2.1))!!, 1e-9)
        assertNull(PowerMath.energyWh(listOf(null, null)))
    }

    @Test
    fun kbps_isDeltaBytesOverElapsedSeconds() {
        // 300 s 内 37_500_000 B = 1000 kbit/s
        assertEquals(1000.0, PowerMath.kbps(37_500_000L, 300_000L)!!, 1e-9)
        assertNull(PowerMath.kbps(null, 300_000L))
        assertNull(PowerMath.kbps(1234L, 0L))
        assertNull(PowerMath.kbps(-5L, 1000L))
    }

    @Test
    fun megabytesFromKbps_convertsToBinaryMegabytes() {
        // 1000 kbps × 300 s = 37.5 MB(decimal) = 35.7621 MiB，6 点 = 214.57 MiB
        val mb = PowerMath.megabytesFromKbps(List(6) { 1000.0 })!!
        assertEquals(214.576, mb, 0.01)
        assertNull(PowerMath.megabytesFromKbps(listOf(null, null)))
    }

    @Test
    fun thermalRiseEvents_countsOnlyIncreases() {
        assertEquals(2, PowerMath.thermalRiseEvents(listOf(1, 1, 2, 3, 3, 3)))
        assertEquals(0, PowerMath.thermalRiseEvents(listOf(3, 3, 3)))
        assertNull(PowerMath.thermalRiseEvents(listOf(2)))
    }

    @Test
    fun aggregates_ignoreNulls() {
        assertEquals(2.0, PowerMath.avg(listOf(1.0, null, 3.0))!!, 1e-9)
        assertEquals(3.0, PowerMath.max(listOf(1.0, null, 3.0))!!, 1e-9)
        assertEquals(1800, PowerMath.avgInt(listOf(1600, 2000, null)))
        assertEquals(2400, PowerMath.maxInt(listOf(1600, 2400, null)))
        assertNull(PowerMath.avg(emptyList()))
    }
    @Test
    fun powerWFromCharge_usesMicroAmpHourDelta() {
        // 10 mAh @ 3.85 V over 300 s = 0.462 W
        assertEquals(0.462, PowerMath.powerWFromCharge(10_000L, 3850, 300_000L)!!, 1e-6)
        // 1 mAh over 300 s = 0.0462 W（电荷计 1 mAh 量化下的最小分辨率）
        assertEquals(0.0462, PowerMath.powerWFromCharge(1_000L, 3850, 300_000L)!!, 1e-6)
        assertNull(PowerMath.powerWFromCharge(0L, 3850, 300_000L))
        assertNull(PowerMath.powerWFromCharge(-1_000L, 3850, 300_000L))
        assertNull(PowerMath.powerWFromCharge(1_000L, 0, 300_000L))
        assertNull(PowerMath.powerWFromCharge(1_000L, 3850, 0L))
    }

    @Test
    fun isCurrentPlausible_rejectsMicroAmpNoise() {
        assertFalse(PowerMath.isCurrentPlausible(-26))
        assertFalse(PowerMath.isCurrentPlausible(1_999))
        assertTrue(PowerMath.isCurrentPlausible(2_000))
        assertTrue(PowerMath.isCurrentPlausible(-450_000))
        assertFalse(PowerMath.isCurrentPlausible(null))
    }
}
