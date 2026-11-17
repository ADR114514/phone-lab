package com.mobilelab.powertrace

import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.SummaryCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工况汇总 1.3 的计算口径。 */
class SummaryCalculatorTest {

    private fun samples() = (1..6).map { index ->
        TestSamples.standard(
            sampleIndex = index,
            levelPct = 80 - index,
            tempC = 30.0 + index,
            thermal = 1 + (index / 3),
        )
    }

    @Test
    fun summarizesPowerEnergyDropAndTemperature() {
        val summary = SummaryCalculator.summarize(
            condition = ConditionId.VIDEO,
            block = 'B',
            samples = samples(),
            durationMin = 30.0,
        )
        assertEquals(6, summary.sampleCount)
        assertEquals(2.1, summary.avgPowerW!!, 1e-9)
        assertEquals(2.1, summary.peakPowerW!!, 1e-9)
        // 6 × 2.1 W × 300 s / 3600 = 1.05 Wh
        assertEquals(1.05, summary.energyWh!!, 1e-9)
        assertEquals(79, summary.startLevelPct)
        assertEquals(74, summary.endLevelPct)
        assertEquals(5, summary.levelDropPct)
        // TestSamples: temp = 30 + index（index 1..6）→ 31..36
        assertEquals(31.0, summary.tempStartC!!, 1e-9)
        assertEquals(36.0, summary.tempEndC!!, 1e-9)
        assertEquals(5.0, summary.tempRiseC!!, 1e-9)
        assertEquals(3, summary.thermalMax)
        // TestSamples thermal = 1 + index/3 → 1,1,2,2,2,3，相邻抬升 2 次
        assertEquals(2, summary.thermalRiseEvents)
        assertEquals(1800, summary.avgCpuFreqMhz)
        assertEquals(60, summary.avgScreenBrightnessPct)
        // 500 mA
        assertEquals(500.0, summary.avgCurrentMa!!, 1e-9)
        // 1000 kbps × 6 × 300 s
        assertEquals(214.576, summary.netRxMb!!, 0.01)
        assertEquals(107.288, summary.netTxMb!!, 0.01)
    }

    @Test
    fun summaryRowHasTwentyColumnsPerDataDictionary() {
        val summary = SummaryCalculator.summarize(ConditionId.IDLE, 'A', samples(), 30.0)
        val row = summary.toRow()
        assertEquals(20, row.size)
        assertEquals("IDLE", row[0])
        assertEquals("待机", row[1])
        assertEquals("30.0", row[2])
        assertEquals("2.100", row[6])
        assertEquals("1.050", row[8])
    }

    @Test
    fun partialConditionAddsAutoNote() {
        val summary = SummaryCalculator.summarize(
            condition = ConditionId.GAME,
            block = 'D',
            samples = samples().take(3),
            durationMin = 10.0,
            extraNotes = "游戏 60fps",
        )
        assertTrue(summary.notes.contains("仅采到 3/6 点"))
        assertTrue(summary.notes.contains("游戏 60fps"))
    }

    @Test
    fun emptySamplesDoNotFakeValues() {
        val summary = SummaryCalculator.summarize(ConditionId.NAVCAM, 'C', emptyList(), 0.0)
        assertEquals(0, summary.sampleCount)
        assertEquals(null, summary.avgPowerW)
        assertEquals(null, summary.energyWh)
        assertEquals(null, summary.levelDropPct)
        assertEquals("0.0", summary.toRow()[2])
    }
}
