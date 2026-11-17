package com.mobilelab.signalmap

import com.mobilelab.signalmap.data.RepeatGate
import com.mobilelab.signalmap.data.RttMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RttMathTest {

    @Test
    fun allAttemptsSucceed_computesFullStatistics() {
        val samples = (1..20).map { it.toDouble() }
        val summary = RttMath.summarize(attempts = 20, latenciesMs = samples)

        assertEquals(20, summary.attempts)
        assertEquals(20, summary.successes)
        assertEquals(0.0, summary.lossPct, 1e-9)
        assertEquals(1.0, summary.minMs!!, 1e-9)
        assertEquals(20.0, summary.maxMs!!, 1e-9)
        assertEquals(10.5, summary.avgMs!!, 1e-9)
        assertEquals(10.5, summary.medianMs!!, 1e-9)
        // numpy-compatible linear interpolation: rank = 0.95 * 19 = 18.05
        assertEquals(19.05, summary.p95Ms!!, 1e-9)
        assertTrue(summary.jitterMs!! > 0.0)
    }

    @Test
    fun partialFailures_keepStatisticsAndReportLoss() {
        // 15 of 20 attempts succeeded -> 25 % loss, statistics over the 15.
        val samples = listOf(
            10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0,
            18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0,
        )
        val summary = RttMath.summarize(attempts = 20, latenciesMs = samples)

        assertEquals(20, summary.attempts)
        assertEquals(15, summary.successes)
        assertEquals(5, summary.failures)
        assertEquals(25.0, summary.lossPct, 1e-9)
        assertEquals(10.0, summary.minMs!!, 1e-9)
        assertEquals(24.0, summary.maxMs!!, 1e-9)
        assertEquals(17.0, summary.avgMs!!, 1e-9)
        assertEquals(17.0, summary.medianMs!!, 1e-9)
    }

    @Test
    fun allAttemptsFail_yieldsEmptyStatisticsAndFullLoss() {
        val summary = RttMath.summarize(attempts = 20, latenciesMs = emptyList())

        assertEquals(20, summary.attempts)
        assertEquals(0, summary.successes)
        assertEquals(20, summary.failures)
        assertEquals(100.0, summary.lossPct, 1e-9)
        assertNull(summary.minMs)
        assertNull(summary.avgMs)
        assertNull(summary.maxMs)
        assertNull(summary.medianMs)
        assertNull(summary.p95Ms)
        assertNull(summary.jitterMs)
    }

    @Test
    fun singleSuccessfulSample_hasZeroJitterButRealAverage() {
        val summary = RttMath.summarize(attempts = 5, latenciesMs = listOf(42.0))
        assertEquals(42.0, summary.avgMs!!, 1e-9)
        assertEquals(42.0, summary.medianMs!!, 1e-9)
        assertEquals(0.0, summary.jitterMs!!, 1e-9)
        assertEquals(80.0, summary.lossPct, 1e-9)
    }

    @Test
    fun invalidSamplesAreIgnored() {
        val summary = RttMath.summarize(
            attempts = 3,
            latenciesMs = listOf(Double.NaN, -5.0, 10.0),
        )
        assertEquals(1, summary.successes)
        assertEquals(2, summary.failures)
        assertEquals(10.0, summary.avgMs!!, 1e-9)
    }

    @Test
    fun repeatGate_enforcesThirtySecondInterval() {
        val last = 1_000_000L
        assertEquals(30_000L, RepeatGate.remainingMs(last, last, 30))
        assertEquals(20_000L, RepeatGate.remainingMs(last, last + 10_000, 30))
        assertEquals(0L, RepeatGate.remainingMs(last, last + 30_000, 30))
        assertEquals(0L, RepeatGate.remainingMs(null, last, 30))
        assertFalse(RepeatGate.allowed(last, last + 29_999, 30))
        assertTrue(RepeatGate.allowed(last, last + 30_001, 30))
    }
}
