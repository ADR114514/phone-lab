package com.mobilelab.vibrodiag.ml

import com.mobilelab.vibrodiag.data.StoredSample
import com.mobilelab.vibrodiag.features.FeatureExtractor
import com.mobilelab.vibrodiag.features.FeatureVector
import com.mobilelab.vibrodiag.features.NoiseGenerator
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.features.WavIo

/** One aggregated (noise type, SNR, condition) cell of the robustness table. */
data class NoiseRow(
    val noiseType: NoiseType,
    val snrDb: Int,
    val conditionId: String,
    val trials: Int,
    val correct: Int,
) {
    val accuracy: Double get() = if (trials > 0) correct.toDouble() / trials else 0.0
}

data class NoiseEvaluationResult(
    val rows: List<NoiseRow>,
    val snrLevels: List<Int>,
    val noiseTypes: List<NoiseType>,
) {
    /** Weighted accuracy over all conditions for one noise type / SNR pair. */
    fun accuracy(noiseType: NoiseType, snrDb: Int): Double {
        val matching = rows.filter { it.noiseType == noiseType && it.snrDb == snrDb }
        val trials = matching.sumOf { it.trials }
        val correct = matching.sumOf { it.correct }
        return if (trials > 0) correct.toDouble() / trials else Double.NaN
    }

    fun cell(noiseType: NoiseType, snrDb: Int, conditionId: String): NoiseRow? =
        rows.firstOrNull { it.noiseType == noiseType && it.snrDb == snrDb && it.conditionId == conditionId }
}

/**
 * Re-extracts audio features from every clean recording after mixing in white or
 * pink noise at the requested SNR. The accelerometer part of the feature vector
 * is unchanged because the noise is purely acoustic. The classifier is the one
 * trained on clean features, so the output measures the robustness of the
 * acoustic branch alone.
 */
object NoiseEvaluator {

    val DEFAULT_SNR_LEVELS: List<Int> = listOf(20, 10, 5, 0, -5)
    val DEFAULT_NOISE_TYPES: List<NoiseType> = listOf(NoiseType.WHITE, NoiseType.PINK)

    fun evaluate(
        samples: List<StoredSample>,
        classifier: KnnClassifier,
        conditionIds: List<String>,
        snrLevels: List<Int> = DEFAULT_SNR_LEVELS,
        noiseTypes: List<NoiseType> = DEFAULT_NOISE_TYPES,
        seedBase: Long = 20261118L,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): NoiseEvaluationResult {
        val counts = LinkedHashMap<String, IntArray>()
        for (index in samples.indices) {
            if (isCancelled()) break
            val sample = samples[index]
            val wav = runCatching { WavIo.read(sample.paths.audio) }.getOrNull() ?: continue
            if (wav.samples.isEmpty()) continue
            val signal = DoubleArray(wav.samples.size) { wav.samples[it] / 32768.0 }
            var stop = false
            for (noiseType in noiseTypes) {
                if (stop) break
                for (snrDb in snrLevels) {
                    if (isCancelled()) {
                        stop = true
                        break
                    }
                    val seed = seedBase + index * 7919L + snrDb * 104729L + noiseType.ordinal
                    val mixed = NoiseGenerator.mix(signal, noiseType, snrDb.toDouble(), seed)
                    val audio = FeatureExtractor.extractAudioNormalized(mixed, wav.sampleRate)
                    val merged = FeatureVector.merge(audio, sample.features)
                    val prediction = classifier.predict(FeatureVector.toArray(merged)) ?: continue
                    val key = key(noiseType, snrDb, sample.conditionId)
                    val entry = counts.getOrPut(key) { IntArray(2) }
                    entry[0] += 1
                    if (prediction.label == sample.conditionId) entry[1] += 1
                }
            }
            onProgress((index + 1).toFloat() / samples.size)
        }
        val rows = ArrayList<NoiseRow>()
        for (snrDb in snrLevels) {
            for (noiseType in noiseTypes) {
                for (conditionId in conditionIds) {
                    val entry = counts[key(noiseType, snrDb, conditionId)] ?: continue
                    if (entry[0] <= 0) continue
                    rows.add(NoiseRow(noiseType, snrDb, conditionId, entry[0], entry[1]))
                }
            }
        }
        return NoiseEvaluationResult(rows, snrLevels, noiseTypes)
    }

    private fun key(noiseType: NoiseType, snrDb: Int, conditionId: String): String =
        noiseType.id + "|" + snrDb + "|" + conditionId
}
