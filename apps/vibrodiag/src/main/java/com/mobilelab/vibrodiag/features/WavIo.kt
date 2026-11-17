package com.mobilelab.vibrodiag.features

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Minimal WAV reader for the 16 bit PCM files produced by core.signal.WavWriter.
 * Pure JVM so the feature pipeline can be unit tested without an emulator.
 *
 * If the data chunk size is still zero (recording interrupted before the header
 * was patched) the remaining bytes are used, which keeps a truncated but valid
 * WAV readable instead of failing the whole extraction.
 */
object WavIo {

    data class WavData(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val samples: ShortArray,
    ) {
        val durationSeconds: Double
            get() = if (sampleRate > 0 && channels > 0) samples.size.toDouble() / channels / sampleRate else 0.0
    }

    fun read(file: File): WavData {
        if (!file.exists()) throw IOException("WAV file not found: " + file.absolutePath)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44L) throw IOException("WAV too short: " + file.absolutePath)
            val riff = readAscii(raf, 4)
            if (riff != "RIFF") throw IOException("Not a RIFF file: " + file.absolutePath)
            raf.readLittleEndianInt()
            if (readAscii(raf, 4) != "WAVE") throw IOException("Not a WAVE file: " + file.absolutePath)
            var sampleRate = 44100
            var channels = 1
            var bits = 16
            var dataOffset = -1L
            var declaredDataBytes = -1L
            while (raf.filePointer + 8 <= raf.length()) {
                val chunkId = readAscii(raf, 4)
                val chunkSize = raf.readLittleEndianInt().toLong() and 0xFFFFFFFFL
                when (chunkId) {
                    "fmt " -> {
                        val start = raf.filePointer
                        if (raf.length() - start < 16) throw IOException("Truncated fmt chunk")
                        raf.readLittleEndianShort()
                        channels = raf.readLittleEndianShort()
                        sampleRate = raf.readLittleEndianInt()
                        raf.readLittleEndianInt()
                        raf.readLittleEndianShort()
                        bits = raf.readLittleEndianShort()
                        raf.seek(start + chunkSize)
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        val remaining = raf.length() - dataOffset
                        declaredDataBytes = if (chunkSize > 0L) minOf(chunkSize, remaining) else remaining
                        raf.seek(dataOffset + declaredDataBytes)
                    }
                    else -> raf.seek(minOf(raf.filePointer + chunkSize, raf.length()))
                }
                if (raf.filePointer >= raf.length()) break
            }
            if (dataOffset < 0L) throw IOException("No data chunk: " + file.absolutePath)
            if (bits != 16) throw IOException("Only 16 bit PCM is supported, got " + bits)
            if (channels <= 0) throw IOException("Invalid channel count " + channels)
            val byteCount = minOf(declaredDataBytes, raf.length() - dataOffset).coerceAtLeast(0L)
            val frameCount = (byteCount / (2L * channels)).toInt()
            val samples = ShortArray(frameCount)
            raf.seek(dataOffset)
            val bytes = ByteArray(frameCount * 2 * channels)
            raf.readFully(bytes)
            var out = 0
            if (channels == 1) {
                for (i in 0 until frameCount) {
                    samples[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
            } else {
                var src = 0
                while (out < frameCount) {
                    samples[out] = ((bytes[src].toInt() and 0xFF) or (bytes[src + 1].toInt() shl 8)).toShort()
                    src += 2 * channels
                    out++
                }
            }
            return WavData(sampleRate, channels, bits, samples)
        }
    }

    private fun readAscii(raf: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readLittleEndianShort(): Int {
        val b = ByteArray(2)
        readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun RandomAccessFile.readLittleEndianInt(): Int {
        val b = ByteArray(4)
        readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }
}
