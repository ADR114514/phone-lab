package com.mobilelab.core.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radix-2 complex FFT plus the spectral descriptors used by the vibration /
 * acoustic analyser. Pure Kotlin so it can be validated in JVM unit tests.
 */
object Fft {

    /** In-place iterative radix-2 FFT. Length must be a power of two. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "re/im length mismatch" }
        require(n > 1 && n and (n - 1) == 0) { "FFT length must be a power of two: $n" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun hannWindow(size: Int): DoubleArray {
        require(size > 1) { "window too small" }
        return DoubleArray(size) { i -> 0.5 * (1.0 - cos(2.0 * PI * i / (size - 1))) }
    }

    fun powerSpectrum(samples: DoubleArray, fftSize: Int = 4096, removeDc: Boolean = true): DoubleArray {
        val n = fftSize
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val window = hannWindow(minOf(n, samples.size))
        val copy = minOf(n, samples.size)
        var mean = 0.0
        if (removeDc) {
            for (i in 0 until copy) mean += samples[i]
            mean /= copy.coerceAtLeast(1)
        }
        for (i in 0 until copy) re[i] = (samples[i] - mean) * window[i]
        transform(re, im)
        val half = n / 2 + 1
        return DoubleArray(half) { k -> (re[k] * re[k] + im[k] * im[k]) / n }
    }

    /** Amplitude spectrum (RMS-scaled), useful for absolute level comparisons. */
    fun amplitudeSpectrum(samples: DoubleArray, fftSize: Int = 4096, removeDc: Boolean = true): DoubleArray {
        val p = powerSpectrum(samples, fftSize, removeDc)
        return DoubleArray(p.size) { k -> sqrt(p[k]) * 2.0 }
    }

    fun bandEnergy(power: DoubleArray, sampleRate: Int, fftSize: Int, lowHz: Double, highHz: Double): Double {
        val binHz = sampleRate.toDouble() / fftSize
        var sum = 0.0
        for (k in power.indices) {
            val f = k * binHz
            if (f >= lowHz && f < highHz) sum += power[k]
        }
        return sum
    }

    fun centroid(power: DoubleArray, sampleRate: Int, fftSize: Int): Double {
        val binHz = sampleRate.toDouble() / fftSize
        var num = 0.0
        var den = 0.0
        for (k in power.indices) {
            num += k * binHz * power[k]
            den += power[k]
        }
        return if (den <= 1e-12) 0.0 else num / den
    }

    fun spectralFlatness(power: DoubleArray): Double {
        var logSum = 0.0
        var sum = 0.0
        var count = 0
        for (i in 1 until power.size) {
            val p = power[i] + 1e-15
            logSum += ln(p)
            sum += p
            count++
        }
        if (count == 0 || sum <= 0.0) return 0.0
        val geo = kotlin.math.exp(logSum / count)
        val arith = sum / count
        return (geo / arith).coerceIn(0.0, 1.0)
    }

    fun rolloff(power: DoubleArray, sampleRate: Int, fftSize: Int, fraction: Double = 0.85): Double {
        val total = power.sum()
        if (total <= 1e-12) return 0.0
        var acc = 0.0
        val threshold = total * fraction
        for (k in power.indices) {
            acc += power[k]
            if (acc >= threshold) return k * (sampleRate.toDouble() / fftSize)
        }
        return (power.size - 1) * (sampleRate.toDouble() / fftSize)
    }

    fun peakFrequency(power: DoubleArray, sampleRate: Int, fftSize: Int, minHz: Double = 0.0): Double {
        val binHz = sampleRate.toDouble() / fftSize
        var best = -1
        var bestValue = -1.0
        for (k in power.indices) {
            val f = k * binHz
            if (f < minHz) continue
            if (power[k] > bestValue) {
                bestValue = power[k]
                best = k
            }
        }
        return if (best < 0) 0.0 else best * binHz
    }

    /** Quadratic interpolation around the peak bin for sub-bin resolution. */
    fun interpolatedPeak(power: DoubleArray, sampleRate: Int, fftSize: Int, minHz: Double = 0.0): Double {
        val binHz = sampleRate.toDouble() / fftSize
        var best = -1
        var bestValue = -1.0
        for (k in power.indices) {
            val f = k * binHz
            if (f < minHz) continue
            if (power[k] > bestValue) {
                bestValue = power[k]
                best = k
            }
        }
        if (best <= 0 || best >= power.size - 1) return if (best < 0) 0.0 else best * binHz
        val a = power[best - 1]
        val b = power[best]
        val c = power[best + 1]
        val denom = a - 2 * b + c
        val delta = if (kotlin.math.abs(denom) < 1e-15) 0.0 else 0.5 * (a - c) / denom
        return (best + delta) * binHz
    }

    fun magnitude(re: Double, im: Double): Double = hypot(re, im)
}
