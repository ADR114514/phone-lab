package com.mobilelab.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Timestamp helpers. All on-device timestamps are milliseconds since epoch. */
object TimeFmt {

    private val isoLocal = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val isoBasic = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    fun isoLocal(epochMs: Long): String =
        isoLocal.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun isoUtc(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

    fun fileStamp(epochMs: Long): String =
        isoBasic.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun clock(epochMs: Long): String =
        clockFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun elapsedMinSec(elapsedMs: Long): String {
        val total = (elapsedMs / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    fun durationShort(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        else -> String.format(Locale.US, "%dmin%02ds", ms / 60_000, (ms / 1000) % 60)
    }

    fun zoneId(): String = ZoneId.systemDefault().id
}
