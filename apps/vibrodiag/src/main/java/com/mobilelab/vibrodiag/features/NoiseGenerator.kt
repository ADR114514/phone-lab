package com.mobilelab.vibrodiag.features

import java.util.Random
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Noise generators used by the robustness evaluation (feature_spec section 3). */
enum class NoiseType(val id: String, val label: String) {
    WHITE("white", "白噪声"),
    PINK("pink", "粉红噪声");

    companion object {
        fun fromId(id: String): NoiseType? = values().firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}

object NoiseGenerator {

    fun generate(type: NoiseType, count: Int, seed: Long): DoubleArray = when (type) {
        NoiseType.WHITE -> white(count, seed)
        NoiseType.PINK -> pink(count, seed)
    }

    /** Gaussian white noise, unit variance. */
    fun white(count: Int, seed: Long): DoubleArray {
        val random = Random(seed)
        return DoubleArray(count) { random.nextGaussian() }
    }

    /**
     * Pink noise: white noise through a 1/f filter (Paul Kellet's 7-pole
     * approximation), then normalised to unit RMS so the SNR calibration below
     * is independent of the filter gain.
     */
    fun pink(count: Int, seed: Long): DoubleArray {
        val random = Random(seed)
        val out = DoubleArray(count)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0
        for (i in 0 until count) {
            val white = random.nextGaussian()
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            out[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926
        }
        val scale = rms(out)
        if (scale > 0.0) {
            for (i in out.indices) out[i] /= scale
        }
        return out
    }

    /** Scales noise so that signal/noise power equals snrDb relative to signal. */
    fun scaleToSnr(signal: DoubleArray, noise: DoubleArray, snrDb: Double): DoubleArray {
        val signalRms = rms(signal)
        val noiseRms = rms(noise)
        if (signalRms <= 0.0 || noiseRms <= 0.0) return DoubleArray(noise.size)
        val scale = signalRms / (noiseRms * 10.0.pow(snrDb / 20.0))
        return DoubleArray(noise.size) { noise[it] * scale }
    }

    /**
     * Returns signal + noise at the requested SNR. The whole mixture is scaled
     * down when its peak exceeds full scale: this keeps the SNR exactly as
     * requested (and therefore comparable with the Python pipeline) instead of
     * clipping the loudest samples.
     */
    fun mix(signal: DoubleArray, type: NoiseType, snrDb: Double, seed: Long): DoubleArray {
        val noise = generate(type, signal.size, seed)
        val scaled = scaleToSnr(signal, noise, snrDb)
        val out = DoubleArray(signal.size) { signal[it] + scaled[it] }
        var peak = 0.0
        for (v in out) {
            val a = if (v < 0.0) -v else v
            if (a > peak) peak = a
        }
        if (peak > 1.0) {
            for (i in out.indices) out[i] /= peak
        }
        return out
    }

    fun measuredSnrDb(signal: DoubleArray, noise: DoubleArray): Double {
        var signalPower = 0.0
        var noisePower = 0.0
        val n = minOf(signal.size, noise.size)
        if (n == 0) return Double.NaN
        for (i in 0 until n) {
            signalPower += signal[i] * signal[i]
            noisePower += noise[i] * noise[i]
        }
        if (noisePower <= 0.0 || signalPower <= 0.0) return Double.NaN
        return 10.0 * log10(signalPower / noisePower)
    }

    fun rms(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += v * v
        return sqrt(sum / values.size)
    }
}
