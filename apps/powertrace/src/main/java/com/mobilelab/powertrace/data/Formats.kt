package com.mobilelab.powertrace.data

import java.util.Locale

/**
 * CSV 数值格式约定（数据字典"通用约定"）：
 * 电流 µA 整数、电压 mV 整数、温度一位小数、功率三位小数；无数据写空字段。
 *
 * 返回 String? 而不是 Double：空值交给 CsvBuilder.render 输出空字段，
 * 避免出现 NaN / null / N/A 这类 Python 侧需要额外清洗的值。
 */
object Fmt {

    fun dec1(v: Double?): String? =
        v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) }

    fun dec2(v: Double?): String? =
        v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it) }

    fun dec3(v: Double?): String? =
        v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.3f", it) }

    fun int(v: Int?): String? = v?.toString()

    fun long(v: Long?): String? = v?.toString()

    fun intOrNull(raw: String?): Int? = raw?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    fun longOrNull(raw: String?): Long? = raw?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()

    fun doubleOrNull(raw: String?): Double? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
}
