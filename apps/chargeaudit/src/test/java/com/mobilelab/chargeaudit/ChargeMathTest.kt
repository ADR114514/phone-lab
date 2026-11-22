package com.mobilelab.chargeaudit

import com.mobilelab.chargeaudit.math.ChargeMath
import com.mobilelab.chargeaudit.model.AuditPoint
import com.mobilelab.chargeaudit.model.Nameplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeMathTest {

    private fun point(index: Int, minute: Double, voltage: Double = 5.0, current: Double = 2.0, pct: Int = 20 + index * 5) =
        AuditPoint(
            index = index,
            timestampMs = 1_000_000L + index * 600_000L,
            elapsedMin = minute,
            usbVoltageV = voltage,
            usbCurrentA = current,
            phonePct = pct,
            phoneVoltageMv = 4000,
            charging = true,
        )

    @Test
    fun trapezoidEnergy_matchesConstantPower() {
        val points = listOf(point(0, 0.0), point(1, 60.0), point(2, 120.0))
        assertEquals(20.0, ChargeMath.trapezoidEnergyWh(points), 1e-9)
    }

    @Test
    fun phoneEnergyFromPct_usesCapacity() {
        val points = listOf(point(0, 0.0, pct = 20), point(1, 60.0, pct = 40), point(2, 120.0, pct = 90))
        val (energy, method) = ChargeMath.phoneEnergyWh(points, 5400.0, 3.85)
        assertEquals(0.70 * 5.4 * 3.85, energy, 1e-6)
        assertEquals("battery_pct_x_capacity", method)
    }

    @Test
    fun summarize_computesThreeEfficiencies() {
        val plate = Nameplate(ratedEnergyWh = 34.49, ratedOutputMah = 6200.0, ratedOutputV = 5.0)
        val points = listOf(point(0, 0.0, pct = 20), point(1, 60.0, pct = 40), point(2, 120.0, pct = 90))
        val summary = ChargeMath.summarize(points, plate)!!
        assertEquals(20.0, summary.eOutWh, 1e-9)
        assertTrue(summary.etaTotal > 0.0)
        assertTrue(summary.equivOutputMah > 3900.0)
    }
}
