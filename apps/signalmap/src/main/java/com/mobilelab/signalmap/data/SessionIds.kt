package com.mobilelab.signalmap.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Session identifiers use the dictionary format WS-<yyyyMMdd>-<序号三位>, where
 * the sequence number is per calendar day and continues after the highest
 * session already present on the device.
 */
object SessionIds {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    fun datePart(epochMs: Long): String =
        dateFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun next(epochMs: Long, existingIds: List<String>): String {
        val date = datePart(epochMs)
        val prefix = "WS-" + date + "-"
        val maxSequence = existingIds
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
            .maxOrNull() ?: 0
        return prefix + String.format(Locale.US, "%03d", maxSequence + 1)
    }

    fun isSessionId(value: String): Boolean =
        Regex("^WS-\\d{8}-\\d{3}$").matches(value)
}
