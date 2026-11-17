package com.mobilelab.core.signal

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming 16-bit PCM WAV writer. The RIFF/data sizes are patched on close so
 * a crash mid-recording still leaves a readable (truncated) file in most cases.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) : Closeable {

    private val raf: RandomAccessFile
    private var dataBytes: Long = 0
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        writeHeader()
    }

    private fun writeHeader() {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(0)
        raf.seek(0)
        raf.write(buf.array())
        raf.seek(44)
    }

    fun write(samples: ShortArray, count: Int = samples.size) {
        if (closed) return
        require(count <= samples.size)
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    fun write(pcm: ByteArray) {
        if (closed) return
        raf.write(pcm)
        dataBytes += pcm.size
    }

    val bytesWritten: Long get() = dataBytes

    fun durationSeconds(): Double = dataBytes.toDouble() / (sampleRate * channels * bitsPerSample / 8)

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            raf.seek(4)
            raf.write(le32((36 + dataBytes).toInt()))
            raf.seek(40)
            raf.write(le32(dataBytes.toInt()))
        }
        runCatching { raf.close() }
    }

    private fun le32(value: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (value and 0xFF).toByte()
        b[1] = ((value shr 8) and 0xFF).toByte()
        b[2] = ((value shr 16) and 0xFF).toByte()
        b[3] = ((value shr 24) and 0xFF).toByte()
        return b
    }
}
