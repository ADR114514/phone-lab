package com.mobilelab.signalmap

import com.mobilelab.signalmap.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Defaults must reproduce the measurement protocol of DATA_DICTIONARY 2.3. */
class AppSettingsTest {

    @Test
    fun defaultsMatchDictionaryProtocol() {
        val settings = AppSettings()
        assertEquals(AppSettings.DEFAULT_HTTP_URL, settings.httpTestUrl)
        assertEquals("223.5.5.5:443", settings.tcpTarget)
        assertEquals("223.5.5.5", settings.tcpHost)
        assertEquals(443, settings.tcpPort)
        assertEquals(20, settings.rttAttempts)
        assertEquals(5, settings.httpAttempts)
        assertEquals(5, settings.gatewayAttempts)
        assertEquals(1000, settings.tcpTimeoutMs)
        assertEquals(512 * 1024, settings.throughputBytes)
        assertFalse(settings.throughputEnabled)
        assertTrue(settings.hashBssidOnExport)
        assertEquals(3, AppSettings.REPEATS_PER_POINT)
        assertEquals(30, AppSettings.REPEAT_INTERVAL_SEC)
    }

    @Test
    fun sanitizeClampsOutOfRangeValues() {
        val wild = AppSettings(
            httpTestUrl = "   ",
            tcpTarget = "",
            rttAttempts = 0,
            httpAttempts = 999,
            gatewayAttempts = -3,
            tcpTimeoutMs = 1,
            throughputBytes = 1,
        ).sanitized()

        assertEquals(AppSettings.DEFAULT_HTTP_URL, wild.httpTestUrl)
        assertEquals(AppSettings.DEFAULT_TCP_TARGET, wild.tcpTarget)
        assertEquals(1, wild.rttAttempts)
        assertEquals(50, wild.httpAttempts)
        assertEquals(1, wild.gatewayAttempts)
        assertEquals(100, wild.tcpTimeoutMs)
        assertEquals(64 * 1024, wild.throughputBytes)
    }

    @Test
    fun customTargetParsedIntoHostAndPort() {
        val settings = AppSettings(tcpTarget = "10.1.2.3:8443").sanitized()
        assertEquals("10.1.2.3", settings.tcpHost)
        assertEquals(8443, settings.tcpPort)
    }
}
