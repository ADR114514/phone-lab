package com.mobilelab.core.csv

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * RFC 4180 style CSV builder shared by every experiment module.
 *
 * The builder is deliberately dependency free and pure JVM code so that the
 * serialisation format can be unit tested without an Android device.
 */
class CsvBuilder(
    private val header: List<String>,
    private val includeBom: Boolean = true,
    private val lineEnding: String = "\r\n",
) {
    private val rows = mutableListOf<List<String>>()

    val rowCount: Int get() = rows.size

    fun row(values: List<Any?>): CsvBuilder {
        require(values.size == header.size) {
            "Row has ${values.size} fields but header has ${header.size}"
        }
        rows += values.map { render(it) }
        return this
    }

    fun row(vararg values: Any?): CsvBuilder = row(values.toList())

    fun build(): String {
        val sb = StringBuilder(256 + rows.size * header.size * 12)
        if (includeBom) sb.append('\uFEFF')
        sb.append(header.joinToString(",") { render(it) }).append(lineEnding)
        for (r in rows) {
            sb.append(r.joinToString(",")).append(lineEnding)
        }
        return sb.toString()
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(build(), StandardCharsets.UTF_8)
    }

    companion object {
        /** Escapes one CSV field: quote when it contains comma, quote, CR or LF. */
        fun render(value: Any?): String = when (value) {
            null -> ""
            is Double -> formatDouble(value)
            is Float -> formatDouble(value.toDouble())
            is String -> escape(value)
            else -> escape(value.toString())
        }

        fun formatDouble(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return ""
            return if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) {
                v.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.4f", v)
            }
        }

        fun escape(raw: String): String {
            val needsQuote = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
            if (!needsQuote) return raw
            return "\"" + raw.replace("\"", "\"\"") + "\""
        }
    }
}
