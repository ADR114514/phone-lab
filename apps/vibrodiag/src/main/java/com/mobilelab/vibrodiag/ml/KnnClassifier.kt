package com.mobilelab.vibrodiag.ml

import kotlin.math.sqrt

/**
 * Per column z-score standardiser. Missing values (NaN) are replaced by the
 * training mean (z = 0), which keeps a partially failed sensor feature from
 * poisoning the distance function.
 */
class Standardizer private constructor(
    private val means: DoubleArray,
    private val stds: DoubleArray,
) {
    val dimension: Int get() = means.size

    fun transform(features: DoubleArray): DoubleArray {
        val out = DoubleArray(means.size)
        for (j in means.indices) {
            val raw = if (j < features.size) features[j] else Double.NaN
            val value = if (raw.isFinite()) raw else means[j]
            out[j] = if (stds[j] > 0.0) (value - means[j]) / stds[j] else 0.0
        }
        return out
    }

    companion object {
        fun fit(rows: List<DoubleArray>, dimension: Int): Standardizer {
            val means = DoubleArray(dimension)
            val stds = DoubleArray(dimension)
            for (j in 0 until dimension) {
                var sum = 0.0
                var count = 0
                for (row in rows) {
                    val value = row.getOrNull(j) ?: Double.NaN
                    if (value.isFinite()) {
                        sum += value
                        count++
                    }
                }
                val mean = if (count > 0) sum / count else 0.0
                var squareSum = 0.0
                for (row in rows) {
                    val value = row.getOrNull(j) ?: Double.NaN
                    if (value.isFinite()) {
                        val delta = value - mean
                        squareSum += delta * delta
                    }
                }
                val std = if (count > 1) sqrt(squareSum / count) else 0.0
                means[j] = mean
                stds[j] = if (std > 1e-12) std else 0.0
            }
            return Standardizer(means, stds)
        }
    }
}

/**
 * k-nearest-neighbour classifier (k = 3 by default, Euclidean distance in the
 * z-scored feature space). Confidence is the distance-weighted share of the
 * winning class among the neighbours; exact vote ties go to the class of the
 * closest neighbour, as required by feature_spec section 3.
 */
class KnnClassifier(val k: Int = ModelSpec.K) {

    private var points: List<DoubleArray> = emptyList()
    private var labels: List<String> = emptyList()
    private var standardizer: Standardizer? = null
    private var dimension = 0

    val isFitted: Boolean get() = points.isNotEmpty()
    val trainingSamples: Int get() = points.size
    val featureCount: Int get() = dimension

    fun fit(items: List<LabeledFeatures>) {
        require(items.isNotEmpty()) { "kNN needs at least one training sample" }
        dimension = items.first().features.size
        val raw = items.map { it.features }
        val fitted = Standardizer.fit(raw, dimension)
        standardizer = fitted
        points = raw.map { fitted.transform(it) }
        labels = items.map { it.label }
    }

    fun predict(features: DoubleArray): Prediction? {
        val fitted = standardizer ?: return null
        if (points.isEmpty()) return null
        val query = fitted.transform(features)
        val neighbourCount = minOf(k, points.size)
        val ordered = points.indices.sortedBy { distance(query, points[it]) }
        val nearest = ordered.take(neighbourCount)
        val distances = nearest.map { distance(query, points[it]) }
        val weights = distances.map { 1.0 / (it + 1e-6) }
        val votes = LinkedHashMap<String, Double>()
        nearest.forEachIndexed { index, pointIndex ->
            val label = labels[pointIndex]
            votes[label] = (votes[label] ?: 0.0) + weights[index]
        }
        val nearestByLabel = HashMap<String, Double>()
        nearest.forEachIndexed { index, pointIndex ->
            val label = labels[pointIndex]
            val current = nearestByLabel[label]
            if (current == null || distances[index] < current) nearestByLabel[label] = distances[index]
        }
        val winner = votes.entries.sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }
                .thenBy { nearestByLabel[it.key] ?: Double.MAX_VALUE }
        ).first().key
        val totalWeight = weights.sum()
        val confidence = if (totalWeight > 0.0) (votes[winner] ?: 0.0) / totalWeight else 0.0
        return Prediction(
            label = winner,
            confidence = confidence.coerceIn(0.0, 1.0),
            neighborLabels = nearest.map { labels[it] },
            distances = distances,
        )
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (j in 0 until dimension) {
            val diff = a[j] - b[j]
            sum += diff * diff
        }
        return sqrt(sum)
    }
}
