package com.mobilelab.vibrodiag.data

import com.mobilelab.core.json.JsonObject
import com.mobilelab.vibrodiag.features.FeatureExtractor
import com.mobilelab.vibrodiag.features.FeatureNames
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.ml.LoocvResult
import com.mobilelab.vibrodiag.ml.ModelSpec
import com.mobilelab.vibrodiag.ml.NoiseEvaluationResult
import com.mobilelab.vibrodiag.ml.NoiseEvaluator
import com.mobilelab.vibrodiag.model.Conditions
import java.io.File

/**
 * Builds evaluation_<sessionId>.json (DATA_DICTIONARY 3.6) with the full model
 * parameter block required by docs/feature_spec.md section 3 so a run can be
 * reproduced from the report alone.
 */
object EvaluationReport {

    fun build(
        sessionId: String,
        generatedAtMs: Long,
        trainingSamples: Int,
        loocv: LoocvResult?,
        blindRows: List<BlindCsvRow>,
        noise: NoiseEvaluationResult?,
        conditionSamples: Map<String, Int>,
        notes: String,
    ): String {
        val labels = Conditions.ids
        val training = JsonObject()
            .put("samples", trainingSamples)
            .put("features", FeatureNames.count)
            .put("model", ModelSpec.DESCRIPTOR)
        if (loocv != null) {
            training.put("loocv_accuracy", loocv.accuracy)
            training.put("loocv_correct", loocv.correct)
            training.put("loocv_total", loocv.total)
            val matrix = loocv.confusionMatrix()
            val confusion = JsonObject()
            for (i in labels.indices) confusion.put(labels[i], matrix[i])
            training.put("confusion_matrix", confusion)
            val perCondition = JsonObject()
            for ((label, accuracy) in loocv.perConditionAccuracy()) perCondition.put(label, accuracy)
            training.put("per_condition_accuracy", perCondition)
        }

        val blind = JsonObject()
        if (blindRows.isEmpty()) {
            blind.put("trials", 0).put("correct", 0).put("accuracy", 0.0)
        } else {
            val correct = blindRows.count { it.correct }
            blind.put("trials", blindRows.size)
                .put("correct", correct)
                .put("accuracy", correct.toDouble() / blindRows.size)
        }

        val noiseArray = ArrayList<JsonObject>()
        if (noise != null) {
            for (snrDb in noise.snrLevels) {
                val entry = JsonObject().put("snr_db", snrDb)
                var hasValue = false
                for (noiseType in noise.noiseTypes) {
                    val accuracy = noise.accuracy(noiseType, snrDb)
                    if (accuracy.isFinite()) {
                        entry.put(noiseType.id, accuracy)
                        hasValue = true
                    }
                }
                if (hasValue) noiseArray.add(entry)
            }
        }

        val model = JsonObject()
            .put("classifier", "knn")
            .put("k", ModelSpec.K)
            .put("distance", "euclidean")
            .put("standardization", ModelSpec.STANDARDIZATION)
            .put("audio_sample_rate", FeatureExtractor.AUDIO_SAMPLE_RATE)
            .put("audio_fft_size", FeatureExtractor.AUDIO_FFT_SIZE)
            .put("audio_hop", FeatureExtractor.AUDIO_HOP)
            .put("audio_window", "hann")
            .put("accel_fft_size", FeatureExtractor.ACCEL_FFT_SIZE)
            .put("accel_window", "hann")
            .put("accel_bands_hz", listOf("0-5", "5-10", "10-25", "25-50", "50-100"))
            .put("audio_bands_hz", listOf("0-100", "100-300", "300-1k", "1k-2k", "2k-5k", "5k-10k", "10k-20k"))
            .put("feature_count", FeatureNames.count)
            .put("feature_names", FeatureNames.numeric)
            .put("noise_snr_db", NoiseEvaluator.DEFAULT_SNR_LEVELS)
            .put("noise_types", NoiseEvaluator.DEFAULT_NOISE_TYPES.map { it.id })

        val conditions = Conditions.all.map { condition ->
            JsonObject()
                .put("id", condition.id)
                .put("label", condition.label)
                .put("samples", conditionSamples[condition.id] ?: 0)
        }

        val root = JsonObject()
            .put("schema", "phonelab.vibro.eval/1")
            .put("session_id", sessionId)
            .put("generated_at_ms", generatedAtMs)
            .put("training", training)
            .put("blind_test", blind)
            .put("noise", noiseArray)
            .put("model", model)
            .put("conditions", conditions)
            .put("notes", notes)
        return root.toJson()
    }

    fun write(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    /** Convenience for the UI: white/pink accuracy per SNR for the line chart. */
    fun curve(noise: NoiseEvaluationResult?, noiseType: NoiseType): List<Pair<Int, Float>> {
        if (noise == null) return emptyList()
        return noise.snrLevels.mapNotNull { snrDb ->
            val accuracy = noise.accuracy(noiseType, snrDb)
            if (accuracy.isFinite()) snrDb to accuracy.toFloat() else null
        }
    }
}
