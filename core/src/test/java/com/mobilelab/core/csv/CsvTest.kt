package com.mobilelab.core.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTest {

    @Test
    fun headerAndRowsAreSerialisedWithCrlf() {
        val csv = CsvBuilder(listOf("a", "b"))
            .row(1, "x")
            .row(2, "y")
            .build()
        val lines = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(listOf("a,b", "1,x", "2,y"), lines)
    }

    @Test
    fun fieldsWithCommaQuoteAndNewlineAreEscaped() {
        val csv = CsvBuilder(listOf("f"), includeBom = false)
            .row("a,b")
            .row("say \"hi\"")
            .row("line1\nline2")
            .build()
        assertTrue(csv.contains("\"a,b\""))
        assertTrue(csv.contains("\"say \"\"hi\"\"\""))
        assertTrue(csv.contains("\"line1\nline2\""))
    }

    @Test
    fun doublesAreRoundedAndIntegralValuesHaveNoDecimalPoint() {
        assertEquals("3", CsvBuilder.render(3.0))
        assertEquals("3.1416", CsvBuilder.render(3.14159265))
        assertEquals("", CsvBuilder.render(Double.NaN))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedRowSizeFails() {
        CsvBuilder(listOf("a", "b")).row(1)
    }

    @Test
    fun rowCountTracksAppends() {
        val builder = CsvBuilder(listOf("a"))
        assertEquals(0, builder.rowCount)
        builder.row(1).row(2)
        assertEquals(2, builder.rowCount)
    }
}
