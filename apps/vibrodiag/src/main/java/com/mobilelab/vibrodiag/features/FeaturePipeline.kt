package com.mobilelab.vibrodiag.features

import com.mobilelab.vibrodiag.data.AccelCsv
import java.io.File

/** Glue between the pure extractor and the files stored on disk. */
object FeaturePipeline {

    fun extractFromFiles(wavFile: File, accelCsvFile: File): Map<String, Double> {
        val wav = WavIo.read(wavFile)
        val accel = if (accelCsvFile.exists()) AccelCsv.read(accelCsvFile) else AccelCsv.emptySeries()
        val audio = FeatureExtractor.extractAudio(wav.samples, wav.sampleRate)
        val accelFeatures = FeatureExtractor.extractAccel(accel.x, accel.y, accel.z, accel.sampleRateHz)
        return FeatureVector.merge(audio, accelFeatures)
    }

    fun extractFromPcm(pcm: ShortArray, sampleRate: Int, accel: AccelCsv.Series): Map<String, Double> {
        val audio = FeatureExtractor.extractAudio(pcm, sampleRate)
        val accelFeatures = FeatureExtractor.extractAccel(accel.x, accel.y, accel.z, accel.sampleRateHz)
        return FeatureVector.merge(audio, accelFeatures)
    }
}

/** Canonical ordering helpers for the packed feature vectors used by the model. */
object FeatureVector {

    fun merge(audio: Map<String, Double>, accel: Map<String, Double>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(FeatureNames.numeric.size)
        for (name in FeatureNames.numeric) {
            out[name] = audio[name] ?: accel[name] ?: Double.NaN
        }
        return out
    }

    fun toArray(features: Map<String, Double>): DoubleArray =
        DoubleArray(FeatureNames.numeric.size) { index -> features[FeatureNames.numeric[index]] ?: Double.NaN }

    fun fromArray(values: DoubleArray): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(FeatureNames.numeric.size)
        for (index in FeatureNames.numeric.indices) {
            out[FeatureNames.numeric[index]] = if (index < values.size) values[index] else Double.NaN
        }
        return out
    }
}
