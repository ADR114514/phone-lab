package com.mobilelab.vibrodiag.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.signal.WavWriter
import com.mobilelab.vibrodiag.data.AccelSample
import com.mobilelab.vibrodiag.model.AccelMode
import androidx.core.content.ContextCompat
import com.mobilelab.vibrodiag.sensor.AccelProbe
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class RecordingRequest(
    val wavFile: File,
    val durationS: Int,
    val accelMode: AccelMode,
    val sampleRate: Int = AudioProbe.SAMPLE_RATE,
)

/** Result of one audio + accelerometer acquisition. */
data class RecordingOutput(
    val wavFile: File,
    val accelSamples: List<AccelSample>,
    val baseNanos: Long,
    val startedAtMs: Long,
    val durationS: Double,
    val audioSourceLabel: String,
    val audioSampleRate: Int,
    val accelRateHz: Double,
    val accelSensorName: String,
    val requestedAccelPeriodUs: Int,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val interrupted: Boolean,
    val error: String?,
)

/**
 * Streams AudioRecord PCM16 into a WAV file on a worker thread while the
 * accelerometer is sampled on the main looper. Both are started from the same
 * SystemClock.elapsedRealtimeNanos() reference so accel t_ms is relative to the
 * audio start as required by DATA_DICTIONARY 3.2.
 *
 * The engine never throws from [start] (returns an error message instead) and
 * [finish] is idempotent so a lifecycle interruption and the UI polling loop can
 * race without corrupting the WAV header.
 */
class RecordingEngine(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val captureLock = Any()
    private val finishMutex = Mutex()

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var writer: WavWriter? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var samplesRead = 0L
    @Volatile private var sumSquares = 0.0
    @Volatile private var peakAbs = 0.0
    @Volatile private var chunkRmsDbfs = SILENCE_DBFS
    @Volatile private var latestAccelMagnitude = 0.0
    @Volatile private var errorMessage: String? = null
    @Volatile private var sourceLabel = "MIC"
    @Volatile private var accelSensorName = ""
    @Volatile private var accelPeriodUs = AccelProbe.resolvePeriodUs(context, AccelMode.AUTO)
    @Volatile private var cachedOutput: RecordingOutput? = null

    private var request: RecordingRequest? = null
    private val accelSamples = ArrayList<AccelSample>()
    private var accelListener: SensorEventListener? = null
    private var accelSensor: Sensor? = null
    private var accelSampleCap = 0

    private val waveformRing = FloatArray(WAVEFORM_RING_SIZE)
    private var waveformWriteIndex = 0
    private var waveformTotal = 0L

    fun isRecording(): Boolean = running

    fun elapsedSeconds(): Double {
        val req = request ?: return 0.0
        return samplesRead / req.sampleRate.toDouble()
    }

    fun liveRmsDbfs(): Double = chunkRmsDbfs

    fun livePeakDbfs(): Double = if (peakAbs > 0.0) 20.0 * log10(peakAbs) else SILENCE_DBFS

    fun latestAccelMagnitudeG(): Double = latestAccelMagnitude

    fun accelSampleCount(): Int = synchronized(captureLock) { accelSamples.size }

    fun audioSource(): String = sourceLabel

    fun accelSensor(): String = accelSensorName

    fun requestedAccelRateHz(): Double = if (accelPeriodUs > 0) 1_000_000.0 / accelPeriodUs else 0.0

    /** Starts audio + accel capture; returns null on success or a Chinese error message. */
    fun start(request: RecordingRequest): String? {
        if (running) return "已有录音正在进行"
        // 显式复核 RECORD_AUDIO：未授权时直接给出可操作的错误，而不是依赖构造异常。
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "未授予录音权限：请在系统设置中允许麦克风后重试"
        }
        this.request = request
        cachedOutput = null
        stopRequested = false
        errorMessage = null
        samplesRead = 0L
        sumSquares = 0.0
        peakAbs = 0.0
        chunkRmsDbfs = SILENCE_DBFS
        latestAccelMagnitude = 0.0
        waveformWriteIndex = 0
        waveformTotal = 0L
        synchronized(captureLock) { accelSamples.clear() }
        accelSampleCap = (request.durationS + 5) * 500

        val source = AudioProbe.resolveSource(context)
            ?: return "无法访问麦克风：请授予 RECORD_AUDIO 权限并确认设备未被占用"
        sourceLabel = source.second
        val minBuffer = AudioRecord.getMinBufferSize(
            request.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = AudioProbe.bufferSizeBytes(minBuffer, request.sampleRate)
        val record = try {
            AudioRecord(
                source.first,
                request.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            LabLog.e(TAG, "AudioRecord creation failed", t)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            return "无法创建 AudioRecord（设备不支持或麦克风被其他应用占用）"
        }
        val wav = try {
            WavWriter(request.wavFile, request.sampleRate)
        } catch (t: Throwable) {
            LabLog.e(TAG, "WavWriter creation failed", t)
            runCatching { record.release() }
            return "无法创建 WAV 文件：" + (t.message ?: t.javaClass.simpleName)
        }
        audioRecord = record
        writer = wav
        try {
            record.startRecording()
        } catch (t: Throwable) {
            LabLog.e(TAG, "startRecording failed", t)
            runCatching { record.release() }
            runCatching { wav.close() }
            audioRecord = null
            writer = null
            return "麦克风启动失败：" + (t.message ?: t.javaClass.simpleName)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.release() }
            runCatching { wav.close() }
            audioRecord = null
            writer = null
            return "麦克风未进入录音状态"
        }
        startedAtMs = System.currentTimeMillis()
        baseNanos = SystemClock.elapsedRealtimeNanos()
        targetSamples = request.durationS.toLong() * request.sampleRate
        val sensorStarted = startAccelSensor(request.accelMode)
        if (!sensorStarted) {
            errorMessage = "加速度计不可用，本组振动特征将为空"
            LabLog.w(TAG, "accelerometer unavailable")
        }
        running = true
        readerThread = Thread({ readLoop(request) }, "vibrodiag-audio").also { it.start() }
        LabLog.i(
            TAG,
            "recording started source=" + sourceLabel + " rate=" + request.sampleRate +
                " duration=" + request.durationS + "s accel=" + accelSensorName +
                " periodUs=" + accelPeriodUs,
        )
        return null
    }

    /** Stops capture, waits for the writer to patch the RIFF header and returns the result. */
    suspend fun finish(): RecordingOutput = finishMutex.withLock {
        cachedOutput?.let { return it }
        stopRequested = true
        val thread = readerThread
        thread?.join(4000)
        if (thread != null && thread.isAlive) {
            runCatching { audioRecord?.stop() }
            thread.join(2000)
        }
        stopAccelSensor()
        runCatching { writer?.close() }
        val output = buildOutput()
        cachedOutput = output
        readerThread = null
        running = false
        LabLog.i(
            TAG,
            "recording finished duration=" + String.format(Locale.US, "%.2f", output.durationS) +
                "s interrupted=" + output.interrupted + " accelSamples=" + output.accelSamples.size +
                " error=" + output.error,
        )
        output
    }

    /** Last resort cleanup for Activity.onDestroy; prefers an explicit finish() call. */
    fun release() {
        stopRequested = true
        stopAccelSensor()
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { writer?.close() }
        audioRecord = null
        writer = null
        running = false
    }

    private fun readLoop(request: RecordingRequest) {
        val record = audioRecord ?: return
        val out = writer ?: return
        val bufferSize = maxOf(request.sampleRate / 10, 1024)
        val buffer = ShortArray(bufferSize)
        try {
            while (!stopRequested && samplesRead < targetSamples) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, read)
                    var chunkSum = 0.0
                    var chunkPeak = 0.0
                    for (i in 0 until read) {
                        val value = buffer[i] / 32768.0
                        chunkSum += value * value
                        val magnitude = abs(value)
                        if (magnitude > chunkPeak) chunkPeak = magnitude
                        appendWaveform(value.toFloat())
                    }
                    synchronized(captureLock) {
                        sumSquares += chunkSum
                        if (chunkPeak > peakAbs) peakAbs = chunkPeak
                        samplesRead += read
                    }
                    val chunkRms = sqrt(chunkSum / read)
                    chunkRmsDbfs = if (chunkRms > 0.0) 20.0 * log10(chunkRms) else SILENCE_DBFS
                } else if (read < 0) {
                    errorMessage = "AudioRecord.read 返回错误码 " + read
                    LabLog.w(TAG, errorMessage ?: "read failed")
                    break
                } else {
                    Thread.sleep(5L)
                }
            }
        } catch (t: Throwable) {
            // Stop may abort a blocking read; only report unexpected failures.
            if (!stopRequested) {
                errorMessage = "录音异常：" + t.javaClass.simpleName + (t.message?.let { ": " + it } ?: "")
                LabLog.e(TAG, "audio loop failed", t)
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { out.close() }
            audioRecord = null
            writer = null
            running = false
        }
    }

    private fun startAccelSensor(mode: AccelMode): Boolean {
        val manager = sensorManager ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val capability = AccelProbe.probe(context, mode)
        accelSensor = sensor
        accelSensorName = sensor.name
        accelPeriodUs = capability.resolvedPeriodUs
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                if (event.values.size < 3) return
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                latestAccelMagnitude = sqrt(x * x + y * y + z * z) / 9.80665
                synchronized(captureLock) {
                    if (accelSamples.size < accelSampleCap) {
                        accelSamples.add(AccelSample(event.timestamp, x, y, z))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        accelListener = listener
        return manager.registerListener(listener, sensor, capability.resolvedPeriodUs)
    }

    private fun stopAccelSensor() {
        val listener = accelListener ?: return
        val sensor = accelSensor
        if (sensor != null) runCatching { sensorManager?.unregisterListener(listener, sensor) }
        accelListener = null
        accelSensor = null
    }

    private fun buildOutput(): RecordingOutput {
        val req = request ?: RecordingRequest(File(""), 0, AccelMode.AUTO)
        val accelSnapshot: List<AccelSample>
        val samples: Long
        val squares: Double
        val peak: Double
        synchronized(captureLock) {
            accelSnapshot = accelSamples.toList()
            samples = samplesRead
            squares = sumSquares
            peak = peakAbs
        }
        val rate = measuredAccelRate(accelSnapshot)
        val duration = samples / req.sampleRate.toDouble()
        val rms = if (samples > 0) sqrt(squares / samples) else 0.0
        val rmsDbfs = if (rms > 0.0) 20.0 * log10(rms) else SILENCE_DBFS
        val peakDbfs = if (peak > 0.0) 20.0 * log10(peak) else SILENCE_DBFS
        val interrupted = duration < req.durationS - 0.2
        return RecordingOutput(
            wavFile = req.wavFile,
            accelSamples = accelSnapshot,
            baseNanos = baseNanos,
            startedAtMs = startedAtMs,
            durationS = duration,
            audioSourceLabel = sourceLabel,
            audioSampleRate = req.sampleRate,
            accelRateHz = rate,
            accelSensorName = accelSensorName,
            requestedAccelPeriodUs = accelPeriodUs,
            rmsDbfs = rmsDbfs,
            peakDbfs = peakDbfs,
            interrupted = interrupted,
            error = errorMessage,
        )
    }

    private fun measuredAccelRate(samples: List<AccelSample>): Double {
        if (samples.size < 2) return requestedAccelRateHz()
        val first = samples.first().tNs
        val last = samples.last().tNs
        if (last <= first) return requestedAccelRateHz()
        return (samples.size - 1) * 1e9 / (last - first).toDouble()
    }

    private fun appendWaveform(value: Float) {
        waveformRing[waveformWriteIndex] = value
        waveformWriteIndex = (waveformWriteIndex + 1) % waveformRing.size
        waveformTotal++
    }

    /** Last second of audio as [points] signed samples (bucket centres), for the live chart. */
    fun waveform(points: Int = 240): List<Float> {
        val requested = points.coerceIn(16, 512)
        val total = waveformTotal
        if (total <= 0L) return List(requested) { 0f }
        val available = minOf(total, waveformRing.size.toLong()).toInt()
        val out = ArrayList<Float>(requested)
        for (p in 0 until requested) {
            val offset = ((p + 0.5) * available / requested).toInt().coerceIn(0, available - 1)
            val index = ((total - available + offset) % waveformRing.size).toInt()
            out.add(waveformRing[index])
        }
        return out
    }

    private var startedAtMs = 0L
    private var baseNanos = 0L
    private var targetSamples = 0L

    companion object {
        private const val TAG = "RecordingEngine"
        private const val SILENCE_DBFS = -120.0
        private const val WAVEFORM_RING_SIZE = 44100
    }
}
