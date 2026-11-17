package com.mobilelab.signalmap

import com.mobilelab.signalmap.data.PresetPoints
import com.mobilelab.signalmap.data.SurveyProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyProgressTest {

    private val points = PresetPoints.defaults()

    @Test
    fun emptyProgress_pointsToFirstStationFirstRepeat() {
        val target = SurveyProgress.EMPTY.nextTarget(points)!!
        assertEquals("P01", target.point.id)
        assertEquals(1, target.repeatIndex)
        assertEquals(0, SurveyProgress.EMPTY.totalCompleted())
    }

    @Test
    fun registeringRepeatsAdvancesThroughStations() {
        var progress = SurveyProgress.EMPTY
        progress = progress.register("P01", 1, 1000)
        // Next missing repeat of the same station is repeat 2.
        assertEquals(2, progress.nextTarget(points)!!.repeatIndex)
        progress = progress.register("P01", 2, 2000)
        progress = progress.register("P01", 3, 3000)
        assertEquals(3, progress.repeats("P01"))
        assertEquals("P02", progress.nextTarget(points)!!.point.id)
        assertEquals(1, progress.nextTarget(points)!!.repeatIndex)
        assertEquals(3, progress.totalCompleted())
    }

    @Test
    fun duplicateRegistrationDoesNotDoubleCount() {
        val progress = SurveyProgress.EMPTY
            .register("P01", 1, 1000)
            .register("P01", 1, 2000)
        assertEquals(1, progress.repeats("P01"))
        assertEquals(1, progress.totalCompleted())
    }

    @Test
    fun outOfOrderRepeatsFillFirstMissingIndex() {
        val progress = SurveyProgress.EMPTY.register("P01", 3, 1000)
        assertEquals(1, progress.nextRepeatIndex("P01"))
        assertEquals(1, progress.repeats("P01"))
        // Unknown ids are simply "not started", so the first missing repeat is 1.
        assertEquals(1, progress.nextRepeatIndex("P99"))
    }

    @Test
    fun allTwentyFourRepeatsCompleteSession() {
        var progress = SurveyProgress.EMPTY
        points.forEach { point ->
            (1..3).forEach { repeat ->
                progress = progress.register(point.id, repeat, 1000L * repeat)
            }
        }
        assertEquals(24, progress.totalCompleted())
        assertTrue(progress.allDone(points))
        assertNull(progress.nextTarget(points))
        assertTrue(progress.isPointDone("P08"))
    }

    @Test
    fun reconcileRebuildsProgressFromSurvivingRows() {
        val rows = listOf(
            TestFixtures.row(pointId = "P01", repeatIndex = 1),
            TestFixtures.row(pointId = "P01", repeatIndex = 2),
            TestFixtures.row(pointId = "P02", repeatIndex = 1),
        )
        val progress = SurveyProgress.EMPTY.reconciledWith(rows, fallbackTimestampMs = 777)

        assertEquals(2, progress.repeats("P01"))
        assertEquals(1, progress.repeats("P02"))
        assertEquals(3, progress.totalCompleted())
        assertEquals(777L, progress.lastCompletedAt("P01"))
        // P01 rows 1+2 survived but repeat 3 is still missing.
        assertEquals("P01", progress.nextTarget(points)!!.point.id)
        assertEquals(3, progress.nextTarget(points)!!.repeatIndex)
    }

    @Test
    fun reconcileKeepsKnownTimestamps() {
        val existing = SurveyProgress.EMPTY.register("P01", 1, 5000)
        val merged = existing.reconciledWith(
            listOf(TestFixtures.row(pointId = "P01", repeatIndex = 1)),
            fallbackTimestampMs = 999,
        )
        assertEquals(5000L, merged.lastCompletedAt("P01"))
    }

    @Test
    fun storageRoundTrip() {
        val progress = SurveyProgress.EMPTY
            .register("P01", 1, 111)
            .register("P01", 2, 222)
            .register("P03", 1, 333)
        val restored = SurveyProgress.fromStorage(
            repeats = progress.toStorage(),
            timestamps = progress.lastTimestampsToStorage(),
        )
        assertEquals(progress.completed, restored.completed)
        assertEquals(progress.lastCompletedAtMs, restored.lastCompletedAtMs)
        assertEquals(2, restored.repeats("P01"))
        assertEquals(222L, restored.lastCompletedAt("P01"))
    }

    @Test
    fun storageToleratesGarbage() {
        val restored = SurveyProgress.fromStorage("P01=1,,x|bad|P02=", "P01=12|broken")
        assertEquals(1, restored.repeats("P01"))
        assertEquals(0, restored.repeats("P02"))
        assertEquals(12L, restored.lastCompletedAt("P01"))
    }
}
