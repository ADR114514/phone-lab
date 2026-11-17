package com.mobilelab.signalmap

import com.mobilelab.signalmap.data.BssidHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BssidHashTest {

    @Test
    fun hashIsStable() {
        val value = BssidHash.hash("AA:BB:CC:DD:EE:FF")
        assertEquals(value, BssidHash.hash("AA:BB:CC:DD:EE:FF"))
        assertEquals("h", value.take(1))
        assertEquals(1 + BssidHash.HASH_CHARS, value.length)
        assertEquals(value, BssidHash.hash("  aa:bb:cc:dd:ee:ff "))
    }

    @Test
    fun differentBssidsProduceDifferentHashes() {
        assertNotEquals(BssidHash.hash("AA:BB:CC:DD:EE:01"), BssidHash.hash("AA:BB:CC:DD:EE:02"))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", BssidHash.hash(""))
        assertEquals("", BssidHash.hash("   "))
    }

    @Test
    fun hashedValueIsRecognisable() {
        assertTrue(BssidHash.isHashed(BssidHash.hash("11:22:33:44:55:66")))
        assertFalse(BssidHash.isHashed("11:22:33:44:55:66"))
        assertFalse(BssidHash.isHashed(""))
    }

    @Test
    fun knownDigestPrefix() {
        // sha256("aa:bb:cc:dd:ee:ff") = c1582e87c8022218... — asserts the
        // algorithm never silently changes (analysis scripts depend on it).
        assertEquals("hc1582e87c8022218", BssidHash.hash("AA:BB:CC:DD:EE:FF"))
    }
}
