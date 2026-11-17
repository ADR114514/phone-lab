package com.mobilelab.core.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    private val sampleRate = 44100
    private val fftSize = 4096

    private fun sine(freq: Double, seconds: Double = 1.0, amplitude: Double = 0.5): DoubleArray {
        val n = (sampleRate * seconds).toInt()
        return DoubleArray(n) { i -> amplitude * sin(2.0 * PI * freq * i / sampleRate) }
    }

    @Test
    fun detectsPeakOfPureSine() {
        val spectrum = Fft.powerSpectrum(sine(1000.0), fftSize)
        val peak = Fft.interpolatedPeak(spectrum, sampleRate, fftSize)
        assertEquals(1000.0, peak, 3.0)
    }

    @Test
    fun bandEnergyConcentratesOnToneBand() {
        val spectrum = Fft.powerSpectrum(sine(1000.0), fftSize)
        val inBand = Fft.bandEnergy(spectrum, sampleRate, fftSize, 900.0, 1100.0)
        val outOfBand = Fft.bandEnergy(spectrum, sampleRate, fftSize, 5000.0, 8000.0)
        assertTrue(inBand > outOfBand * 100)
    }

    @Test
    fun hannWindowStartsAndEndsAtZero() {
        val window = Fft.hannWindow(16)
        assertEquals(0.0, window.first(), 1e-12)
        assertEquals(0.0, window.last(), 1e-12)
        assertEquals(1.0, window[8], 0.02)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwoLength() {
        Fft.transform(DoubleArray(3), DoubleArray(3))
    }

    @Test
    fun flatnessIsHigherForWhiteNoiseThanForTone() {
        val random = java.util.Random(42)
        val noise = DoubleArray(sampleRate) { (random.nextDouble() - 0.5) * 0.5 }
        val noiseFlatness = Fft.spectralFlatness(Fft.powerSpectrum(noise, fftSize))
        val toneFlatness = Fft.spectralFlatness(Fft.powerSpectrum(sine(1000.0), fftSize))
        assertTrue(noiseFlatness > toneFlatness)
    }
}
