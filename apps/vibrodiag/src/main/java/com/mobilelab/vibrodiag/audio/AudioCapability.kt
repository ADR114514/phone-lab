package com.mobilelab.vibrodiag.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/** Resolved microphone capability of the current device. */
data class AudioCapability(
    val sampleRate: Int = AudioProbe.SAMPLE_RATE,
    val minBufferBytes: Int = 0,
    val outputSampleRateHz: Int = 0,
    val sourceLabel: String = "未知",
    val unprocessedSupported: Boolean = false,
    val micSupported: Boolean = false,
    val note: String = "",
) {
    val supports44100: Boolean get() = minBufferBytes > 0
}

/**
 * The experiment requires an unprocessed 44.1 kHz mono PCM stream. UNPROCESSED is
 * preferred (no AGC/NS/echo cancellation); MIC is the documented fallback.
 */
object AudioProbe {

    const val SAMPLE_RATE = 44100

    fun probe(context: Context): AudioCapability {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        val outputRate = runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        }.getOrDefault(0)
        val bufferBytes = bufferSizeBytes(minBuffer)
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return AudioCapability(
                sampleRate = SAMPLE_RATE,
                minBufferBytes = minBuffer,
                outputSampleRateHz = outputRate,
                sourceLabel = "待授权",
                note = "未授予麦克风权限，无法探测音频源",
            )
        }
        val unprocessed = canInitialize(context, MediaRecorder.AudioSource.UNPROCESSED, bufferBytes)
        val mic = canInitialize(context, MediaRecorder.AudioSource.MIC, bufferBytes)
        val label = when {
            unprocessed -> "UNPROCESSED"
            mic -> "MIC"
            else -> "不可用"
        }
        return AudioCapability(
            sampleRate = SAMPLE_RATE,
            minBufferBytes = minBuffer,
            outputSampleRateHz = outputRate,
            sourceLabel = label,
            unprocessedSupported = unprocessed,
            micSupported = mic,
            note = if (unprocessed) "UNPROCESSED（无 AGC/降噪）" else "回退 MIC 音频源",
        )
    }

    /**
     * Resolves the source used by the recorder: UNPROCESSED first, then MIC.
     * Returns null when neither can be initialised.
     */
    fun resolveSource(context: Context): Pair<Int, String>? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = bufferSizeBytes(minBuffer)
        if (canInitialize(context, MediaRecorder.AudioSource.UNPROCESSED, bufferBytes)) {
            return MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED"
        }
        if (canInitialize(context, MediaRecorder.AudioSource.MIC, bufferBytes)) {
            return MediaRecorder.AudioSource.MIC to "MIC"
        }
        return null
    }

    fun bufferSizeBytes(minBufferSize: Int, sampleRate: Int = SAMPLE_RATE): Int {
        val fallback = sampleRate * 2 * 2
        return if (minBufferSize > 0) maxOf(minBufferSize * 2, fallback) else fallback
    }

    private fun canInitialize(context: Context, source: Int, bufferBytes: Int): Boolean {
        // 显式复核 RECORD_AUDIO：AudioRecord 构造在无权限时会抛 SecurityException。
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        var record: AudioRecord? = null
        return try {
            record = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            record.state == AudioRecord.STATE_INITIALIZED
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { record?.release() }
        }
    }
}
