package com.mobilelab.signalmap

import com.mobilelab.signalmap.data.WifiMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiMathTest {

    @Test
    fun bandInferredFromFrequency() {
        assertEquals("2.4G", WifiMath.bandOf(2412))
        assertEquals("2.4G", WifiMath.bandOf(2472))
        assertEquals("5G", WifiMath.bandOf(5180))
        assertEquals("5G", WifiMath.bandOf(5745))
        assertEquals("6G", WifiMath.bandOf(6135))
        assertEquals("", WifiMath.bandOf(null))
        assertEquals("", WifiMath.bandOf(0))
    }

    @Test
    fun channelInferredFromFrequency() {
        assertEquals(1, WifiMath.channelOf(2412))
        assertEquals(6, WifiMath.channelOf(2437))
        assertEquals(11, WifiMath.channelOf(2462))
        assertEquals(14, WifiMath.channelOf(2484))
        assertEquals(36, WifiMath.channelOf(5180))
        assertEquals(149, WifiMath.channelOf(5745))
        assertEquals(1, WifiMath.channelOf(5955))
        assertNull(WifiMath.channelOf(100))
        assertNull(WifiMath.channelOf(null))
    }

    @Test
    fun gatewayIntConvertedToDottedQuad() {
        // 192.168.1.1 -> network byte order int 0x0101A8C0
        assertEquals("192.168.1.1", WifiMath.intToIpv4(0x0101A8C0))
        assertEquals("10.0.0.1", WifiMath.intToIpv4(0x0100000A))
    }

    @Test
    fun ssidSanitised() {
        assertEquals("CampusNet", WifiMath.sanitizeSsid("\"CampusNet\""))
        assertEquals("CampusNet", WifiMath.sanitizeSsid("  CampusNet "))
        assertEquals("", WifiMath.sanitizeSsid("<unknown ssid>"))
        assertEquals("", WifiMath.sanitizeSsid(null))
        assertEquals("", WifiMath.sanitizeSsid("\"\""))
    }

    @Test
    fun placeholderBssidFiltered() {
        assertEquals("", WifiMath.sanitizeBssid("02:00:00:00:00:00"))
        assertEquals("", WifiMath.sanitizeBssid("00:00:00:00:00:00"))
        assertEquals("aa:bb:cc:dd:ee:ff", WifiMath.sanitizeBssid(" AA:BB:CC:DD:EE:FF "))
        assertEquals("", WifiMath.sanitizeBssid(null))
    }

    @Test
    fun neighbourChannelsSortedAndDeduplicated() {
        assertEquals("1;6;11", WifiMath.neighborChannels(listOf(11, 1, 6, 1)))
        assertEquals("", WifiMath.neighborChannels(emptyList()))
    }

    @Test
    fun hostPortParsing() {
        assertEquals("223.5.5.5" to 443, WifiMath.parseHostPort("223.5.5.5:443", 443))
        assertEquals("8.8.8.8" to 443, WifiMath.parseHostPort("8.8.8.8", 443))
        assertEquals("gw.local" to 8080, WifiMath.parseHostPort(" gw.local:8080 ", 443))
        assertEquals("bad" to 443, WifiMath.parseHostPort("bad:notaport", 443))
        assertEquals("" to 443, WifiMath.parseHostPort("  ", 443))
    }
}
