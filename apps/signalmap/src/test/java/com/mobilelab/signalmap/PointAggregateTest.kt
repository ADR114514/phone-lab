package com.mobilelab.signalmap

import com.mobilelab.signalmap.ui.aggregateByPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Result-screen aggregation: per point means over the rows of that station. */
class PointAggregateTest {

    @Test
    fun averagesGroupedPerPoint() {
        val rows = listOf(
            TestFixtures.row(pointId = "P01", repeatIndex = 1).copy(rssiDbm = -50, latencyAvgMs = 10.0, lossPct = 0.0),
            TestFixtures.row(pointId = "P01", repeatIndex = 2).copy(rssiDbm = -60, latencyAvgMs = 30.0, lossPct = 10.0),
            TestFixtures.row(pointId = "P02", repeatIndex = 1).copy(rssiDbm = null, latencyAvgMs = null, lossPct = 100.0),
        )
        val aggregates = aggregateByPoint(rows)
        assertEquals(2, aggregates.size)

        val p01 = aggregates.first { it.pointId == "P01" }
        assertEquals(2, p01.rows)
        assertEquals(-55.0, p01.avgRssi!!, 1e-9)
        assertEquals(20.0, p01.avgLatency!!, 1e-9)
        assertEquals(5.0, p01.avgLossPct!!, 1e-9)
        assertEquals("P01", aggregates[0].pointId)
        assertEquals("P02", aggregates[1].pointId)

        val p02 = aggregates.first { it.pointId == "P02" }
        assertEquals(1, p02.rows)
        assertNull(p02.avgRssi)
        assertNull(p02.avgLatency)
        assertEquals(100.0, p02.avgLossPct!!, 1e-9)
    }

    @Test
    fun emptyRowsProduceEmptyAggregates() {
        assertEquals(0, aggregateByPoint(emptyList()).size)
    }
}
