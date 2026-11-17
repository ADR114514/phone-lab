package com.mobilelab.signalmap

import com.mobilelab.signalmap.data.CsvText
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTextTest {

    @Test
    fun parsesBomAndCrlf() {
        val table = CsvText.parse("\uFEFFa,b,c\r\n1,2,3\r\n")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), table)
    }

    @Test
    fun parsesQuotedFieldsWithCommasAndEscapedQuotes() {
        val table = CsvText.parse("a,\"b,c\",\"d\"\"e\"\r\n")
        assertEquals(listOf(listOf("a", "b,c", "d\"e")), table)
    }

    @Test
    fun trailingNewlineDoesNotCreateEmptyRow() {
        assertEquals(1, CsvText.parse("a,b\r\n").size)
        assertEquals(1, CsvText.parse("a,b\n").size)
    }

    @Test
    fun lastRowWithoutNewlineIsKept() {
        val table = CsvText.parse("a,b\r\nc,d")
        assertEquals(2, table.size)
        assertEquals(listOf("c", "d"), table[1])
    }

    @Test
    fun emptyFieldAtLineEndPreserved() {
        val table = CsvText.parse("a,\r\n")
        assertEquals(listOf("a", ""), table.first())
    }

    @Test
    fun emptyInputYieldsNoRows() {
        assertEquals(0, CsvText.parse("").size)
    }
}
