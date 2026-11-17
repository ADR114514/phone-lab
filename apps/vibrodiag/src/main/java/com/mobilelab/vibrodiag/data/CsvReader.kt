package com.mobilelab.vibrodiag.data

import java.io.File

/**
 * Tiny RFC4180 reader used for the app's own CSV files. The core library only
 * ships a writer, and the data dictionary requires quoted-field support for
 * free-form notes.
 */
class CsvTable(val header: List<String>, val rows: List<List<String>>) {

    private val columnIndex: Map<String, Int> = header.withIndex().associate { (i, name) -> name.trim() to i }

    fun column(name: String): Int = columnIndex[name] ?: -1

    fun string(row: List<String>, name: String): String {
        val index = column(name)
        return if (index >= 0 && index < row.size) row[index] else ""
    }

    fun double(row: List<String>, name: String): Double {
        val raw = string(row, name).trim()
        return raw.toDoubleOrNull() ?: Double.NaN
    }

    fun long(row: List<String>, name: String): Long {
        val raw = string(row, name).trim()
        return raw.toLongOrNull() ?: -1L
    }
}

object CsvReader {

    fun readRows(file: File): List<List<String>> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun readTable(file: File): CsvTable? {
        val rows = readRows(file)
        if (rows.isEmpty()) return null
        val body = rows.drop(1).filter { row -> row.any { it.isNotBlank() } }
        return CsvTable(rows.first(), body)
    }

    /** Parses CSV text including quotes, embedded commas/newlines and a leading BOM. */
    fun parse(text: String): List<List<String>> {
        val source = if (text.startsWith("\uFEFF")) text.substring(1) else text
        val rows = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < source.length && source[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }
                c == '\r' -> Unit
                c == '\n' -> {
                    fields.add(field.toString())
                    field.setLength(0)
                    rows.add(fields)
                    fields = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(field.toString())
            rows.add(fields)
        }
        return rows
    }
}
