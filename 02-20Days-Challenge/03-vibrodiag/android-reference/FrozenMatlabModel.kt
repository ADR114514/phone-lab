package com.mobilelab.vibrodiag.ml

import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.MalformedJsonException
import com.mobilelab.vibrodiag.features.FeatureNames
import com.mobilelab.vibrodiag.model.Conditions
import java.io.EOFException
import java.io.InputStream
import java.util.Collections
import kotlin.math.exp

/** Fixed RBF-ridge inference; does not train or depend on Android APIs. */
class FrozenMatlabModel private constructor(
    val modelId: String,
    val trainingSession: String,
    val gamma: Double,
    val lambda: Double,
    conditions: List<String>,
    private val retained: IntArray,
    private val fill: DoubleArray,
    private val mean: DoubleArray,
    private val std: DoubleArray,
    private val trainZ: Array<DoubleArray>,
    private val alpha: Array<DoubleArray>,
) {
    /** The condition order is also the score order and the tie breaker. */
    val conditions: List<String> = Collections.unmodifiableList(conditions)
    val trainingSamples: Int get() = trainZ.size

    /**
     * Returns [Prediction] for consumers shared with kNN. Here confidence is the raw
     * winning score minus the runner-up score, not a calibrated probability or a
     * percentage. Neighbour labels and distances do not apply and are empty.
     */
    fun predict(features: DoubleArray): Prediction {
        val scores = predictScores(features)
        var winner = 0
        for (i in 1 until scores.size) {
            if (scores[i] > scores[winner]) winner = i
        }
        val runnerUp = scores.indices.filter { it != winner }.maxOf { scores[it] }
        return Prediction(conditions[winner], scores[winner] - runnerUp, emptyList(), emptyList())
    }

    /**
     * Raw RBF-ridge scores in [conditions] order; not calibrated probabilities.
     * Input is in [FeatureNames.numeric] order; NaN denotes a missing measurement.
     * Rejects wrong-length input, infinity, and rows with no usable retained feature.
     */
    fun predictScores(features: DoubleArray): DoubleArray {
        require(features.size == RAW_FEATURES) { "Expected $RAW_FEATURES model features" }
        require(features.none { it.isInfinite() }) { "Model features cannot contain infinity" }
        require(retained.any { features[it].isFinite() }) { "No usable retained model feature" }

        val z = DoubleArray(retained.size) { j ->
            val raw = features[retained[j]]
            val value = if (raw.isNaN()) fill[j] else raw
            val standardized = (value - mean[j]) / std[j]
            require(standardized.isFinite()) { "Standardized model feature $j is not finite" }
            // The frozen CUDA predictor converts its standardized queries to single.
            val single = standardized.toFloat()
            require(single.isFinite()) { "Standardized model feature $j is out of range" }
            single.toDouble()
        }
        val scores = DoubleArray(conditions.size)
        for (i in trainZ.indices) {
            var squaredDistance = 0.0
            for (j in z.indices) {
                val diff = z[j] - trainZ[i][j]
                squaredDistance += diff * diff
            }
            // This direct form avoids the cancellation in ||z||² + ||trainZ||² - 2(z·trainZ).
            val kernel = exp(-gamma * squaredDistance)
            for (c in scores.indices) scores[c] += kernel * alpha[i][c]
        }
        require(scores.all { it.isFinite() }) { "Frozen model produced a non-finite score" }
        return scores
    }

    companion object {
        const val ASSET_FILENAME = "matlab_vibro_model.json"
        const val MODEL_ID = "matlab-rbf-ridge-VD-20261122-002-v1"
        const val TRAINING_SESSION = "VD-20261122-002"
        private const val SCHEMA = "phonelab.vibro.matlab/1"
        private const val RAW_FEATURES = 32
        private const val KEPT_FEATURES = 31
        private const val TRAINING_ROWS = 75
        private const val CONDITION_COUNT = 5
        private val fields = setOf(
            "schema", "model_id", "training_session", "feature_names", "keep", "fill", "mean", "std",
            "gamma", "lambda", "conditions", "train_z", "alpha",
        )

        /** Reads the frozen model asset as UTF-8. The caller owns and closes [input]. */
        fun load(input: InputStream): FrozenMatlabModel {
            val values = readFields(input)
            fun field(key: String): JsonElement = values.getValue(key)

            require(field("schema").text("schema") == SCHEMA) { "Unsupported frozen model schema" }
            val modelId = field("model_id").text("model_id")
            require(modelId == MODEL_ID) { "Unexpected frozen model ID: $modelId" }
            val trainingSession = field("training_session").text("training_session")
            require(trainingSession == TRAINING_SESSION) { "Unexpected frozen model training session: $trainingSession" }
            val featureNames = field("feature_names").texts("feature_names", RAW_FEATURES)
            require(featureNames == FeatureNames.numeric) { "Frozen model feature order does not match the App" }
            val conditions = field("conditions").texts("conditions", CONDITION_COUNT)
            require(conditions == Conditions.ids) { "Frozen model condition order does not match the App" }

            val keep = field("keep").entries("keep", RAW_FEATURES)
            val retained = keep.indices.filter { j ->
                val flag = keep[j]
                require(flag.isJsonPrimitive && flag.asJsonPrimitive.isBoolean) { "keep[$j] must be a boolean" }
                flag.asBoolean
            }.toIntArray()
            require(retained.size == KEPT_FEATURES) { "Expected $KEPT_FEATURES retained model features" }

            val fill = field("fill").numbers("fill", KEPT_FEATURES)
            val mean = field("mean").numbers("mean", KEPT_FEATURES)
            val std = field("std").numbers("std", KEPT_FEATURES)
            require(std.all { it > 0.0 }) { "Frozen model std must be positive" }
            val gamma = field("gamma").number("gamma")
            val lambda = field("lambda").number("lambda")
            require(gamma > 0.0 && lambda > 0.0) { "Frozen model gamma and lambda must be positive" }
            val trainZ = field("train_z").matrix("train_z", TRAINING_ROWS, KEPT_FEATURES)
            val alpha = field("alpha").matrix("alpha", TRAINING_ROWS, CONDITION_COUNT)
            return FrozenMatlabModel(modelId, trainingSession, gamma, lambda, conditions, retained, fill, mean, std, trainZ, alpha)
        }

        private fun readFields(input: InputStream): Map<String, JsonElement> {
            try {
                val reader = JsonReader(input.reader(Charsets.UTF_8)).apply { strictness = Strictness.STRICT }
                require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Frozen model must be a JSON object" }
                reader.beginObject()
                val values = mutableMapOf<String, JsonElement>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name in fields && name !in values) { "Unknown or duplicate frozen model field: $name" }
                    values[name] = JsonParser.parseReader(reader)
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected text after frozen model object" }
                require(values.keys == fields) { "Missing frozen model fields: ${fields - values.keys}" }
                return values
            } catch (e: JsonParseException) {
                throw IllegalArgumentException("Invalid frozen model JSON", e)
            } catch (e: MalformedJsonException) {
                throw IllegalArgumentException("Invalid frozen model JSON", e)
            } catch (e: EOFException) {
                throw IllegalArgumentException("Truncated frozen model JSON", e)
            }
        }

        private fun JsonElement.text(path: String): String {
            require(isJsonPrimitive && asJsonPrimitive.isString) { "$path must be a string" }
            return asString
        }

        private fun JsonElement.number(path: String): Double {
            require(isJsonPrimitive && asJsonPrimitive.isNumber) { "$path must be a number" }
            return asDouble.also { require(it.isFinite()) { "$path must be finite" } }
        }

        private fun JsonElement.entries(path: String, size: Int): List<JsonElement> {
            require(isJsonArray && asJsonArray.size() == size) { "$path must have $size entries" }
            return asJsonArray.asList()
        }

        private fun JsonElement.texts(path: String, size: Int): List<String> =
            entries(path, size).mapIndexed { j, entry -> entry.text("$path[$j]") }

        private fun JsonElement.numbers(path: String, size: Int): DoubleArray {
            val entries = entries(path, size)
            return DoubleArray(size) { j -> entries[j].number("$path[$j]") }
        }

        private fun JsonElement.matrix(path: String, rows: Int, columns: Int): Array<DoubleArray> {
            val entries = entries(path, rows)
            return Array(rows) { i -> entries[i].numbers("$path[$i]", columns) }
        }
    }
}
