package com.mobilelab.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFmtTest {

    @Test
    fun elapsedFormatting() {
        assertEquals("00:00", TimeFmt.elapsedMinSec(0))
        assertEquals("01:05", TimeFmt.elapsedMinSec(65_000))
        assertEquals("61:01", TimeFmt.elapsedMinSec(3_661_000))
    }

    @Test
    fun fileStampHasNoColons() {
        val stamp = TimeFmt.fileStamp(0)
        assertTrue(Regex("^\\d{8}_\\d{6}$").matches(stamp))
    }

    @Test
    fun isoLocalContainsOffset() {
        val iso = TimeFmt.isoLocal(0)
        assertTrue(iso.contains("T"))
        assertTrue(iso.last() == 'Z' || iso.contains("+") || iso.contains("-"))
    }
}
