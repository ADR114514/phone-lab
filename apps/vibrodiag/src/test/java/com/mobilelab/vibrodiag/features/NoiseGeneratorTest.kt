package com.mobilelab.vibrodiag.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** SNR calibration and spectrum shape of the two self-implemented noise types. */
class NoiseGeneratorTest {

    private fun tone(count: Int, amplitude: Double = 0.5): DoubleArray =
        DoubleArray(count) { amplitude * sin(2.0 * PI * 1000.0 * it / 44100.0) }

    @Test
    fun whiteNoiseIsScaledToRequestedSnr() {
        val signal = tone(44100)
        val white = NoiseGenerator.generate(NoiseType.WHITE, signal.size, 42L)
        for (target in listOf(20.0, 10.0, 5.0, 0.0, -5.0)) {
            val scaled = NoiseGenerator.scaleToSnr(signal, white, target)
            val measured = NoiseGenerator.measuredSnrDb(signal, scaled)
            assertEquals(target, measured, 0.05)
        }
    }

    @Test
    fun pinkNoiseIsScaledToRequestedSnr() {
        val signal = tone(44100)
        val pink = NoiseGenerator.generate(NoiseType.PINK, signal.size, 7L)
        for (target in listOf(20.0, 10.0, 0.0, -5.0)) {
            val scaled = NoiseGenerator.scaleToSnr(signal, pink, target)
            val measured = NoiseGenerator.measuredSnrDb(signal, scaled)
            assertEquals(target, measured, 0.05)
        }
    }

    @Test
    fun pinkNoiseCarriesMoreLowFrequencyEnergyThanWhite() {
        val count = 16384
        val white = NoiseGenerator.generate(NoiseType.WHITE, count, 3L)
        val pink = NoiseGenerator.generate(NoiseType.PINK, count, 3L)
        val whiteRatio = lowOverHighBandEnergy(white)
        val pinkRatio = lowOverHighBandEnergy(pink)
        assertTrue("pink low/high ratio should far exceed white: " + pinkRatio + " vs " + whiteRatio, pinkRatio > 4.0 * whiteRatio)
    }

    /** Mean power below 500 Hz divided by mean power above 4 kHz (Hann-windowed FFT). */
    private fun lowOverHighBandEnergy(values: DoubleArray): Double {
        val fftSize = 4096
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val window = com.mobilelab.core.signal.Fft.hannWindow(fftSize)
        val take = minOf(fftSize, values.size)
        var mean = 0.0
        for (i in 0 until take) mean += values[i]
        mean /= take
        for (i in 0 until take) re[i] = (values[i] - mean) * window[i]
        com.mobilelab.core.signal.Fft.transform(re, im)
        val binHz = 44100.0 / fftSize
        var low = 0.0
        var lowCount = 0
        var high = 0.0
        var highCount = 0
        for (k in 1 until fftSize / 2) {
            val power = (re[k] * re[k] + im[k] * im[k]) / fftSize
            val frequency = k * binHz
            if (frequency < 500.0) {
                low += power
                lowCount++
            } else if (frequency > 4000.0) {
                high += power
                highCount++
            }
        }
        return (low / lowCount) / (high / highCount)
    }

    @Test
    fun mixtureKeepsRequestedSnrAndNeverExceedsFullScale() {
        val signal = tone(22050)
        val mixed = NoiseGenerator.mix(signal, NoiseType.WHITE, 6.0, 11L)
        assertEquals(signal.size, mixed.size)
        val peak = mixed.maxOf { kotlin.math.abs(it) }
        assertTrue(peak <= 1.0000001)
        // The mixture scaling preserves the SNR of the added component.
        val noise = NoiseGenerator.scaleToSnr(signal, NoiseGenerator.generate(NoiseType.WHITE, signal.size, 11L), 6.0)
        assertEquals(6.0, NoiseGenerator.measuredSnrDb(signal, noise), 0.05)
    }

}
