package com.mobilelab.signalmap

import com.mobilelab.signalmap.data.SessionIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SessionIdsTest {

    private fun epochOf(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun datePartUsesLocalCalendarDay() {
        assertEquals("20261118", SessionIds.datePart(epochOf(LocalDate.of(2026, 11, 18))))
    }

    @Test
    fun firstSessionOfDayStartsAtOne() {
        val id = SessionIds.next(epochOf(LocalDate.of(2026, 11, 18)), emptyList())
        assertEquals("WS-20261118-001", id)
    }

    @Test
    fun sequenceContinuesFromHighestExisting() {
        val day = epochOf(LocalDate.of(2026, 11, 18))
        val existing = listOf(
            "WS-20261118-001",
            "WS-20261118-007",
            "WS-20261117-004",
            "not-a-session",
        )
        assertEquals("WS-20261118-008", SessionIds.next(day, existing))
    }

    @Test
    fun sessionIdFormatValidation() {
        assertTrue(SessionIds.isSessionId("WS-20261118-001"))
        assertFalse(SessionIds.isSessionId("WS-2026-11-18-001"))
        assertFalse(SessionIds.isSessionId("PT-20261118-001"))
        assertFalse(SessionIds.isSessionId(""))
    }
}
