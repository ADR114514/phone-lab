package com.mobilelab.vibrodiag.features

import com.mobilelab.core.signal.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Verifies the FFT wrapper and Hann window used by the cross-language contract. */
class FftPeakTest {

    @Test
    fun hannWindowMatchesDefinition() {
        val window = Fft.hannWindow(8)
        assertEquals(0.0, window[0], 1e-12)
        // With an even size the maximum is 0.95048 at both centre samples (N-1 denominator).
        assertEquals(0.950484434, window[3], 1e-9)
        assertEquals(0.950484434, window[4], 1e-9)
        assertEquals(0.1882550991, window[1], 1e-9)
        assertEquals(0.6112604670, window[2], 1e-9)
        for (i in window.indices) {
            assertEquals(window[i], window[window.size - 1 - i], 1e-12)
            assertTrue(window[i] >= -1e-12)
            assertTrue(window[i] <= 1.0 + 1e-12)
        }
        assertEquals((window.size - 1) / 2.0, window.sum(), 1e-9)
    }

    @Test
    fun radix2FftOnPureSinePeaksAtOneKilohertz() {
        val sampleRate = 44100
        val samples = DoubleArray(4096) { sin(2.0 * PI * 1000.0 * it / sampleRate) }
        val power = Fft.powerSpectrum(samples, 4096)
        val peakHz = Fft.interpolatedPeak(power, sampleRate, 4096)
        assertEquals(1000.0, peakHz, 2.0)
        val centroid = Fft.centroid(power, sampleRate, 4096)
        assertEquals(1000.0, centroid, 5.0)
        assertEquals(4096 / 2 + 1, power.size)
    }

    @Test
    fun powerSpectrumOfSineHasExpectedPeakMagnitude() {
        val sampleRate = 44100
        val amplitude = 0.5
        val samples = DoubleArray(4096) { amplitude * sin(2.0 * PI * 1000.0 * it / sampleRate) }
        val power = Fft.powerSpectrum(samples, 4096)
        val peak = power.maxOrNull() ?: 0.0
        // |X|^2 / N for a Hann-windowed sine: (A*N/4)^2 / N = A^2*N/16.
        val expected = amplitude * amplitude * 4096.0 / 16.0
        assertEquals(expected, peak, expected * 0.05)
    }
}
