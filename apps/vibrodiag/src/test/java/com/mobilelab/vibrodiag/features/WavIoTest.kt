package com.mobilelab.vibrodiag.features

import com.mobilelab.core.signal.WavWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** WAV container contract: 44 byte header, patched sizes, readable truncated files. */
class WavIoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun readAscii(bytes: ByteArray, offset: Int, count: Int): String =
        String(bytes, offset, count, Charsets.US_ASCII)

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun writerProducesCanonicalHeaderAndReadableSamples() {
        val file = folder.newFile("tone.wav")
        val samples = ShortArray(100) { (it * 100).toShort() }
        WavWriter(file, 44100).use { writer -> writer.write(samples) }

        assertEquals(44L + 200L, file.length())
        val bytes = file.readBytes()
        assertEquals("RIFF", readAscii(bytes, 0, 4))
        assertEquals(236, intLe(bytes, 4))
        assertEquals("WAVE", readAscii(bytes, 8, 4))
        assertEquals("fmt ", readAscii(bytes, 12, 4))
        assertEquals(16, intLe(bytes, 16))
        assertEquals(1, shortLe(bytes, 20))
        assertEquals(1, shortLe(bytes, 22))
        assertEquals(44100, intLe(bytes, 24))
        assertEquals(88200, intLe(bytes, 28))
        assertEquals(2, shortLe(bytes, 32))
        assertEquals(16, shortLe(bytes, 34))
        assertEquals("data", readAscii(bytes, 36, 4))
        assertEquals(200, intLe(bytes, 40))

        val read = WavIo.read(file)
        assertEquals(44100, read.sampleRate)
        assertEquals(1, read.channels)
        assertEquals(16, read.bitsPerSample)
        assertArrayEquals(samples, read.samples)
        assertEquals(100.0 / 44100.0, read.durationSeconds, 1e-9)
    }

    @Test
    fun zeroedDataSizeStillReadsRemainingBytes() {
        val file = folder.newFile("interrupted.wav")
        val samples = ShortArray(64) { (it - 32).toShort() }
        WavWriter(file, 44100).use { writer -> writer.write(samples) }
        // Simulate an interrupted recording: header still claims zero data bytes.
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.seek(40)
            raf.write(byteArrayOf(0, 0, 0, 0))
        }
        val read = WavIo.read(file)
        assertEquals(64, read.samples.size)
        assertEquals(samples[63].toInt(), read.samples[63].toInt())
    }

    @Test
    fun stereoFilesAreDownmixedToFirstChannel() {
        val file = folder.newFile("stereo.wav")
        val frames = ShortArray(60)
        WavWriter(file, 22050, channels = 2).use { writer -> writer.write(frames) }
        val read = WavIo.read(file)
        assertEquals(2, read.channels)
        assertEquals(30, read.samples.size)
        assertEquals(frames[0].toInt(), read.samples[0].toInt())
        assertEquals(frames[2].toInt(), read.samples[1].toInt())
    }

    @Test(expected = java.io.IOException::class)
    fun missingFileFailsLoudly() {
        WavIo.read(File(folder.root, "does-not-exist.wav"))
    }
}
