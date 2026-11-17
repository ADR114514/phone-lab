package com.mobilelab.core.math

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {

    @Test
    fun meanMedianPercentile() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(3.0, Stats.mean(values), 1e-9)
        assertEquals(3.0, Stats.median(values), 1e-9)
        assertEquals(4.0, Stats.percentile(values, 0.75), 1e-9)
        assertEquals(1.0, Stats.percentile(values, 0.0), 1e-9)
    }

    @Test
    fun evenMedianAveragesMiddlePair() {
        assertEquals(2.5, Stats.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
    }

    @Test
    fun stdevAndRms() {
        assertEquals(1.5811388, Stats.stdev(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 1e-6)
        assertEquals(2.0, Stats.rms(doubleArrayOf(2.0, -2.0, 2.0, -2.0)), 1e-9)
    }

    @Test
    fun emptyCollectionsAreSafe() {
        assertEquals(0.0, Stats.mean(emptyList<Double>()), 0.0)
        assertEquals(0.0, Stats.median(emptyList()), 0.0)
        assertEquals(0.0, Stats.percentile(emptyList(), 0.9), 0.0)
    }
}
