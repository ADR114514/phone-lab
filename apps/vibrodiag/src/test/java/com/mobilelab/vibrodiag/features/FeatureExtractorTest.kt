package com.mobilelab.vibrodiag.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Feature definitions from docs/feature_spec.md exercised on synthetic signals. */
class FeatureExtractorTest {

    private val sampleRate = 44100

    private fun sinePcm(frequency: Double, amplitude: Double, seconds: Double): ShortArray {
        val count = (sampleRate * seconds).toInt()
        return ShortArray(count) { index ->
            (amplitude * 32767.0 * sin(2.0 * PI * frequency * index / sampleRate)).toInt().toShort()
        }
    }

    @Test
    fun oneKilohertzSineProducesExpectedSpectralFeatures() {
        val pcm = sinePcm(1000.0, 0.5, 1.0)
        val features = FeatureExtractor.extractAudio(pcm, sampleRate)

        val peakHz = features.getValue("audio_peak_hz")
        assertEquals(1000.0, peakHz, 2.0)

        val bands = FeatureNames.audioBands.map { features.getValue(it) }
        val strongest = FeatureNames.audioBands[bands.indices.maxByOrNull { bands[it] }!!]
        assertEquals("band_1k_2k", strongest)
        // 1000 Hz falls 1.1 Hz above the band edge, so the Hann main lobe splits
        // between band_300_1k and band_1k_2k; together they must hold almost all energy.
        assertTrue(features.getValue("band_1k_2k") > 0.4)
        assertTrue(features.getValue("band_1k_2k") + features.getValue("band_300_1k") > 0.95)

        val centroid = features.getValue("audio_centroid_hz")
        assertEquals(1000.0, centroid, 20.0)

        val rmsDbfs = features.getValue("audio_rms_dbfs")
        assertEquals(20.0 * log10(0.5 / sqrt(2.0)), rmsDbfs, 0.2)
        val peakDbfs = features.getValue("audio_peak_dbfs")
        assertEquals(20.0 * log10(0.5), peakDbfs, 0.1)
        assertEquals(3.01, features.getValue("audio_crest_db"), 0.15)

        val expectedZcr = 2.0 * 1000.0 / sampleRate
        assertEquals(expectedZcr, features.getValue("audio_zcr"), 0.002)
        // Non-excess kurtosis of a pure sine is 1.5 and its skewness is 0.
        assertEquals(1.5, features.getValue("audio_kurtosis"), 0.05)
        assertEquals(0.0, features.getValue("audio_skewness"), 0.01)
    }

    @Test
    fun silenceIsReportedAsMinus120DbfsAndUndefinedSpectrumIsNaN() {
        val silence = ShortArray(sampleRate)
        val features = FeatureExtractor.extractAudio(silence, sampleRate)
        assertEquals(-120.0, features.getValue("audio_rms_dbfs"), 1e-9)
        assertEquals(-120.0, features.getValue("audio_peak_dbfs"), 1e-9)
        assertTrue(features.getValue("audio_peak_hz").isNaN())
        // Flatness uses ln(P + 1e-15), so a silent spectrum is flat by definition.
        assertEquals(1.0, features.getValue("audio_flatness"), 1e-9)
    }

    @Test
    fun accelFeaturesRecoverSyntheticVibrationFrequency() {
        val rate = 200.0
        val n = 3000
        val frequency = 20.0
        val x = DoubleArray(n) { 3.0 + sin(2.0 * PI * frequency * it / rate) }
        val y = DoubleArray(n)
        val z = DoubleArray(n)
        val features = FeatureExtractor.extractAccel(x, y, z, rate)

        assertEquals(frequency, features.getValue("accel_peak_hz"), 0.6)
        assertTrue(features.getValue("accel_band_10_25") > 0.85)
        assertTrue(features.getValue("accel_rms") > 0.6)
        assertTrue(features.getValue("accel_rms") < 0.8)
        assertTrue(features.getValue("accel_crest") > 1.3)
        // 50 Hz fallback sampling rate is still handled, but 100 Hz band is out of range.
        val lowRate = FeatureExtractor.extractAccel(x.copyOf(750), y.copyOf(750), z.copyOf(750), 50.0)
        assertTrue(lowRate.getValue("accel_band_50_100").isNaN())
    }

    @Test
    fun emptyAccelSeriesYieldsNaNNumericFeatures() {
        val features = FeatureExtractor.extractAccel(DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.0)
        for (name in FeatureExtractor.accelColumnNames()) {
            assertTrue("expected NaN for " + name, features.getValue(name).isNaN())
        }
    }

    @Test
    fun featureVectorKeepsContractOrderAndSkipsMissingValues() {
        val audio = FeatureExtractor.extractAudio(sinePcm(500.0, 0.3, 0.3), sampleRate)
        val merged = FeatureVector.merge(audio, emptyMap())
        assertEquals(FeatureNames.count, merged.size)
        assertEquals(FeatureNames.numeric, merged.keys.toList())
        assertFalse(merged.getValue("accel_rms").isFinite())
        val array = FeatureVector.toArray(merged)
        assertEquals(FeatureNames.count, array.size)
        assertTrue(abs(array[0] - merged.getValue("audio_rms_dbfs")) < 1e-12)
    }
}
