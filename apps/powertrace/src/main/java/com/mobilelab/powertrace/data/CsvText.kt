package com.mobilelab.powertrace.data

import com.mobilelab.core.csv.CsvBuilder
import java.io.File

/**
 * CSV 文本层：写文件复用 core 的 [CsvBuilder]（RFC4180 转义 + UTF-8 BOM + CRLF），
 * 追加写与解析在本文件实现，均为纯 JVM 代码，可被单元测试覆盖。
 *
 * 会话 CSV 采用"追加写"（崩溃可恢复），因此不能每次都用 CsvBuilder 整表重写：
 * 首次写入时写 BOM + 表头，之后每次只追加一行。
 */
object CsvText {

    /** 表头行：BOM + 转义后的列名 + CRLF。 */
    fun headerLine(header: List<String>): String =
        "\uFEFF" + header.joinToString(",") { CsvBuilder.render(it) } + "\r\n"

    /** 数据行：按 RFC4180 转义、逗号分隔、CRLF 结束。 */
    fun dataLine(values: List<Any?>): String =
        values.joinToString(",") { CsvBuilder.render(it) } + "\r\n"

    /** 整表写入（批次 CSV / 汇总 CSV 等派生文件），复用 core CsvBuilder。 */
    fun writeFull(file: File, header: List<String>, rows: List<List<Any?>>) {
        val builder = CsvBuilder(header, includeBom = true, lineEnding = "\r\n")
        for (row in rows) builder.row(row)
        builder.write(file)
    }

    /**
     * 追加一行；文件不存在或为空时先写表头。返回是否新创建了文件。
     * 使用 `appendText(..., Charsets.UTF_8)` 保证编码稳定，不损坏已有行。
     */
    fun appendRow(file: File, header: List<String>, values: List<Any?>): Boolean {
        file.parentFile?.mkdirs()
        val fresh = !file.exists() || file.length() == 0L
        if (fresh) file.writeText(headerLine(header), Charsets.UTF_8)
        file.appendText(dataLine(values), Charsets.UTF_8)
        return fresh
    }

    /** RFC4180 解析：支持引号包裹、内嵌逗号/换行、双引号转义，自动去掉 BOM。 */
    fun parse(text: String): List<List<String>> {
        val src = text.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < src.length && src[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> Unit
                    '\n' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        rows.add(row)
                        row = ArrayList()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows.filter { r -> r.isNotEmpty() && !(r.size == 1 && r[0].isBlank()) }
    }
}
