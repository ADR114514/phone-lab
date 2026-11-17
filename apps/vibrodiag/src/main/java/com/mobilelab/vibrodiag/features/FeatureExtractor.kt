package com.mobilelab.vibrodiag.features

import com.mobilelab.core.signal.Fft
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure Kotlin acoustic/vibration feature extractor.
 *
 * The implementation follows docs/feature_spec.md literally; the Python pipeline
 * in analysis/vibro/features.py must mirror the conventions below.
 *
 * Audio: x = pcm / 32768, frames start at 0, 2048, 4096 ... while start < N
 * (trailing frame zero padded to 4096), per-frame mean removal, Hann(4096)
 * window, P_k = |X_k|^2 / 4096 averaged over frames, k = 0..2048. Peak, centroid
 * and flatness ignore DC (k >= 1). Band ratios use the 20 Hz - 20 kHz energy.
 * audio_peak_mag = sqrt(max P).
 *
 * Accel: m(t) = sqrt(x^2 + y^2 + z^2), linear detrend + mean removal, then
 * fftSize = min(4096, nextPow2(N)), hop = fftSize / 2, Hann window, the same
 * |X|^2 / N power definition. A band whose lower edge is above Nyquist is NaN.
 *
 * Undefined values are NaN and serialise as an empty CSV field.
 */
object FeatureExtractor {

    const val AUDIO_FFT_SIZE = 4096
    const val AUDIO_HOP = 2048
    const val AUDIO_SAMPLE_RATE = 44100
    const val ACCEL_FFT_SIZE = 4096
    const val SILENCE_DBFS = -120.0

    private const val EPS = 1e-15

    private val audioWindow: DoubleArray by lazy { Fft.hannWindow(AUDIO_FFT_SIZE) }

    fun extractAudio(pcm: ShortArray, sampleRate: Int = AUDIO_SAMPLE_RATE): Map<String, Double> {
        val normalized = DoubleArray(pcm.size) { pcm[it] / 32768.0 }
        return extractAudioNormalized(normalized, sampleRate)
    }

    fun extractAudioNormalized(x: DoubleArray, sampleRate: Int): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(FeatureNames.audioTime.size + FeatureNames.audioSpectrum.size + FeatureNames.audioBands.size)
        val n = x.size
        var sumSq = 0.0
        var peak = 0.0
        for (v in x) {
            sumSq += v * v
            val a = abs(v)
            if (a > peak) peak = a
        }
        val rms = if (n > 0) sqrt(sumSq / n) else 0.0
        val rmsDbfs = if (rms > 0.0) 20.0 * log10(rms) else SILENCE_DBFS
        val peakDbfs = if (peak > 0.0) 20.0 * log10(peak) else SILENCE_DBFS
        out["audio_rms_dbfs"] = rmsDbfs
        out["audio_peak_dbfs"] = peakDbfs
        out["audio_crest_db"] = peakDbfs - rmsDbfs
        out["audio_zcr"] = zeroCrossingRate(x)
        val moments = standardizedMoments(x)
        out["audio_skewness"] = moments.first
        out["audio_kurtosis"] = moments.second

        val power = averagePowerSpectrum(x, AUDIO_FFT_SIZE, AUDIO_HOP, audioWindow)
        val binHz = sampleRate.toDouble() / AUDIO_FFT_SIZE
        val bandDenominator = bandEnergy(power, binHz, 20.0, 20000.0)
        val peakIndex = strongestBin(power, 1)
        val peakPower = if (peakIndex >= 0) power[peakIndex] else 0.0
        val peakHz = if (peakIndex >= 0 && peakPower > 0.0) refinePeak(power, peakIndex, binHz) else Double.NaN

        out["audio_centroid_hz"] = centroid(power, binHz, 1)
        out["audio_rolloff85_hz"] = rolloff(power, binHz, 0.85)
        out["audio_flatness"] = flatness(power, 1)
        out["audio_peak_hz"] = peakHz
        out["audio_peak_mag"] = if (peakIndex >= 0) sqrt(peakPower) else Double.NaN
        out["audio_harmonic_ratio"] = harmonicRatio(power, binHz, peakHz)
        out["audio_hnr_db"] = hnrDb(power, binHz, peakHz)

        out["band_0_100"] = bandRatio(power, binHz, 0.0, 100.0, bandDenominator)
        out["band_100_300"] = bandRatio(power, binHz, 100.0, 300.0, bandDenominator)
        out["band_300_1k"] = bandRatio(power, binHz, 300.0, 1000.0, bandDenominator)
        out["band_1k_2k"] = bandRatio(power, binHz, 1000.0, 2000.0, bandDenominator)
        out["band_2k_5k"] = bandRatio(power, binHz, 2000.0, 5000.0, bandDenominator)
        out["band_5k_10k"] = bandRatio(power, binHz, 5000.0, 10000.0, bandDenominator)
        out["band_10k_20k"] = bandRatio(power, binHz, 10000.0, 20000.0, bandDenominator)
        return out
    }

    fun extractAccel(x: DoubleArray, y: DoubleArray, z: DoubleArray, sampleRateHz: Double): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(FeatureNames.accelTime.size + FeatureNames.accelSpectrum.size + FeatureNames.accelBands.size)
        val n = min(x.size, min(y.size, z.size))
        if (n < 8 || !(sampleRateHz > 0.0) || !sampleRateHz.isFinite()) {
            for (name in accelColumnNames()) out[name] = Double.NaN
            return out
        }
        val magnitude = DoubleArray(n) { sqrt(x[it] * x[it] + y[it] * y[it] + z[it] * z[it]) }
        val a = detrend(magnitude, sampleRateHz)
        var sumSq = 0.0
        var peak = 0.0
        for (v in a) {
            sumSq += v * v
            val av = abs(v)
            if (av > peak) peak = av
        }
        val rms = sqrt(sumSq / n)
        out["accel_rms"] = rms
        out["accel_peak"] = peak
        out["accel_crest"] = if (rms > 0.0) peak / rms else Double.NaN
        out["accel_zcr"] = zeroCrossingRate(a)

        val fftSize = accelFftSize(n)
        val power = averagePowerSpectrum(a, fftSize, fftSize / 2, Fft.hannWindow(fftSize))
        val binHz = sampleRateHz / fftSize
        val totalEnergy = power.sum()
        val peakIndex = strongestBin(power, 1)
        val peakPower = if (peakIndex >= 0) power[peakIndex] else 0.0
        out["accel_peak_hz"] = if (peakIndex >= 0 && peakPower > 0.0) refinePeak(power, peakIndex, binHz) else Double.NaN
        out["accel_centroid_hz"] = centroid(power, binHz, 1)
        out["accel_flatness"] = flatness(power, 1)
        out["accel_band_0_5"] = accelBandRatio(power, binHz, totalEnergy, 0.0, 5.0, sampleRateHz)
        out["accel_band_5_10"] = accelBandRatio(power, binHz, totalEnergy, 5.0, 10.0, sampleRateHz)
        out["accel_band_10_25"] = accelBandRatio(power, binHz, totalEnergy, 10.0, 25.0, sampleRateHz)
        out["accel_band_25_50"] = accelBandRatio(power, binHz, totalEnergy, 25.0, 50.0, sampleRateHz)
        out["accel_band_50_100"] = accelBandRatio(power, binHz, totalEnergy, 50.0, 100.0, sampleRateHz)
        return out
    }

    fun accelColumnNames(): List<String> = FeatureNames.accelTime + FeatureNames.accelSpectrum + FeatureNames.accelBands

    /** Smallest power of two >= n, capped at ACCEL_FFT_SIZE (and at least 2). */
    fun accelFftSize(n: Int): Int {
        if (n >= ACCEL_FFT_SIZE) return ACCEL_FFT_SIZE
        var size = 2
        while (size < n) size = size shl 1
        return min(size, ACCEL_FFT_SIZE)
    }

    private fun strongestBin(power: DoubleArray, minBin: Int): Int {
        var best = -1
        var bestValue = 0.0
        for (k in minBin until power.size) {
            if (power[k] > bestValue) {
                bestValue = power[k]
                best = k
            }
        }
        return best
    }

    private fun refinePeak(power: DoubleArray, index: Int, binHz: Double): Double {
        if (index <= 0 || index >= power.size - 1) return index * binHz
        val a = power[index - 1]
        val b = power[index]
        val c = power[index + 1]
        val denominator = a - 2.0 * b + c
        val delta = if (abs(denominator) < 1e-15) 0.0 else 0.5 * (a - c) / denominator
        return (index + delta) * binHz
    }

    private fun centroid(power: DoubleArray, binHz: Double, minBin: Int): Double {
        var numerator = 0.0
        var denominator = 0.0
        for (k in minBin until power.size) {
            numerator += k * binHz * power[k]
            denominator += power[k]
        }
        return if (denominator > 0.0) numerator / denominator else Double.NaN
    }

    private fun rolloff(power: DoubleArray, binHz: Double, fraction: Double): Double {
        var total = 0.0
        for (p in power) total += p
        if (total <= 0.0) return Double.NaN
        val threshold = total * fraction
        var accumulated = 0.0
        for (k in power.indices) {
            accumulated += power[k]
            if (accumulated >= threshold) return k * binHz
        }
        return (power.size - 1) * binHz
    }

    private fun flatness(power: DoubleArray, minBin: Int): Double {
        var logSum = 0.0
        var sum = 0.0
        var count = 0
        for (k in minBin until power.size) {
            val value = power[k] + EPS
            logSum += ln(value)
            sum += value
            count++
        }
        if (count == 0 || sum <= 0.0) return Double.NaN
        val geometric = exp(logSum / count)
        val arithmetic = sum / count
        if (arithmetic <= 0.0) return Double.NaN
        return (geometric / arithmetic).coerceIn(0.0, 1.0)
    }

    private fun harmonicEnergy(power: DoubleArray, binHz: Double, fundamentalHz: Double): Double? {
        if (!fundamentalHz.isFinite() || fundamentalHz <= 0.0) return null
        val used = BooleanArray(power.size)
        var energy = 0.0
        for (harmonic in 2..6) {
            val center = (harmonic * fundamentalHz / binHz).roundToInt()
            for (delta in -2..2) {
                val k = center + delta
                if (k in 1 until power.size && !used[k]) {
                    used[k] = true
                    energy += power[k]
                }
            }
        }
        return energy
    }

    private fun harmonicRatio(power: DoubleArray, binHz: Double, fundamentalHz: Double): Double {
        val harmonic = harmonicEnergy(power, binHz, fundamentalHz) ?: return Double.NaN
        var total = 0.0
        for (k in 1 until power.size) total += power[k]
        if (total <= 0.0) return Double.NaN
        return (harmonic / total).coerceIn(0.0, 1.0)
    }

    private fun hnrDb(power: DoubleArray, binHz: Double, fundamentalHz: Double): Double {
        val harmonic = harmonicEnergy(power, binHz, fundamentalHz) ?: return Double.NaN
        var total = 0.0
        for (k in 1 until power.size) total += power[k]
        if (total <= 0.0) return Double.NaN
        val noise = (total - harmonic).coerceAtLeast(0.0)
        if (harmonic <= 0.0) return Double.NaN
        return 10.0 * log10(harmonic / (noise + EPS))
    }

    private fun bandEnergy(power: DoubleArray, binHz: Double, lowHz: Double, highHz: Double): Double {
        var sum = 0.0
        for (k in power.indices) {
            val frequency = k * binHz
            if (frequency >= lowHz && frequency < highHz) sum += power[k]
        }
        return sum
    }

    private fun bandRatio(power: DoubleArray, binHz: Double, lowHz: Double, highHz: Double, denominator: Double): Double {
        if (denominator <= 0.0) return Double.NaN
        return bandEnergy(power, binHz, lowHz, highHz) / denominator
    }

    private fun accelBandRatio(
        power: DoubleArray,
        binHz: Double,
        totalEnergy: Double,
        lowHz: Double,
        highHz: Double,
        sampleRateHz: Double,
    ): Double {
        val nyquist = sampleRateHz / 2.0
        // feature_spec 4.6: a band is undefined when its lower edge is at/above
        // Nyquist or its upper edge lies above Nyquist.
        if (lowHz >= nyquist || highHz > nyquist) return Double.NaN
        if (totalEnergy <= 0.0) return Double.NaN
        return bandEnergy(power, binHz, lowHz, highHz) / totalEnergy
    }

    private fun zeroCrossingRate(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        var changes = 0
        for (i in 1 until values.size) {
            if ((values[i] < 0.0) != (values[i - 1] < 0.0)) changes++
        }
        return changes.toDouble() / values.size
    }

    /** Returns (skewness, kurtosis) of the standardised moments (kurtosis not excess). */
    private fun standardizedMoments(values: DoubleArray): Pair<Double, Double> {
        val n = values.size
        if (n < 2) return Pair(Double.NaN, Double.NaN)
        var mean = 0.0
        for (v in values) mean += v
        mean /= n
        var m2 = 0.0
        var m3 = 0.0
        var m4 = 0.0
        for (v in values) {
            val d = v - mean
            val d2 = d * d
            m2 += d2
            m3 += d2 * d
            m4 += d2 * d2
        }
        m2 /= n
        m3 /= n
        m4 /= n
        if (m2 <= 1e-30) return Pair(Double.NaN, Double.NaN)
        val sigma = sqrt(m2)
        val skewness = m3 / (sigma * sigma * sigma)
        val kurtosis = m4 / (m2 * m2)
        return Pair(skewness, kurtosis)
    }

    private fun averagePowerSpectrum(x: DoubleArray, fftSize: Int, hop: Int, window: DoubleArray): DoubleArray {
        val half = fftSize / 2 + 1
        val accumulator = DoubleArray(half)
        if (x.isEmpty()) return accumulator
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        var frames = 0
        var start = 0
        while (start < x.size) {
            val length = min(fftSize, x.size - start)
            var mean = 0.0
            for (i in 0 until length) mean += x[start + i]
            mean /= length
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until length) re[i] = (x[start + i] - mean) * window[i]
            Fft.transform(re, im)
            for (k in 0 until half) {
                accumulator[k] += (re[k] * re[k] + im[k] * im[k]) / fftSize
            }
            frames++
            start += hop
        }
        if (frames > 1) {
            for (k in 0 until half) accumulator[k] /= frames
        }
        return accumulator
    }

    /** Least squares linear detrend followed by mean removal. */
    private fun detrend(values: DoubleArray, sampleRateHz: Double): DoubleArray {
        val n = values.size
        val out = DoubleArray(n)
        if (n == 0) return out
        var sumTime = 0.0
        var sumValue = 0.0
        for (i in 0 until n) {
            sumTime += i / sampleRateHz
            sumValue += values[i]
        }
        val meanTime = sumTime / n
        val meanValue = sumValue / n
        var covariance = 0.0
        var variance = 0.0
        for (i in 0 until n) {
            val centredTime = i / sampleRateHz - meanTime
            covariance += centredTime * (values[i] - meanValue)
            variance += centredTime * centredTime
        }
        val slope = if (variance > 0.0) covariance / variance else 0.0
        val intercept = meanValue - slope * meanTime
        var residualMean = 0.0
        for (i in 0 until n) {
            out[i] = values[i] - (intercept + slope * (i / sampleRateHz))
            residualMean += out[i]
        }
        residualMean /= n
        for (i in 0 until n) out[i] -= residualMean
        return out
    }
}
