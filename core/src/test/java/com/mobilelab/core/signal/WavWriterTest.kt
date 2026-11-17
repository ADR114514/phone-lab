package com.mobilelab.core.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun writesValidHeaderAndLengths() {
        val file: File = tempFolder.newFile("test.wav")
        val samples = ShortArray(1000) { (it % 100).toShort() }
        WavWriter(file, sampleRate = 44100, channels = 1, bitsPerSample = 16).use { writer ->
            writer.write(samples)
            assertEquals(1000L, writer.bytesWritten / 2)
        }
        val bytes = file.readBytes()
        assertEquals(44 + 2000, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        val dataSize = (bytes[40].toInt() and 0xFF) or
            ((bytes[41].toInt() and 0xFF) shl 8) or
            ((bytes[42].toInt() and 0xFF) shl 16) or
            ((bytes[43].toInt() and 0xFF) shl 24)
        assertEquals(2000, dataSize)
        assertTrue(bytes.size >= 44)
    }

    @Test
    fun durationIsDerivedFromBytes() {
        val file = tempFolder.newFile("d.wav")
        val writer = WavWriter(file, 8000, 1, 16)
        writer.write(ShortArray(8000))
        assertEquals(1.0, writer.durationSeconds(), 1e-9)
        writer.close()
    }
}
