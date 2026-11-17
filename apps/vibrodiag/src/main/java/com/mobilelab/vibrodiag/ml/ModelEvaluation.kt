package com.mobilelab.vibrodiag.ml

/** One leave-one-out fold: the held out truth and what the model predicted. */
data class FoldResult(val truth: String, val predicted: String, val confidence: Double)

/**
 * Leave-one-out cross validation result for the current training set.
 * The standardiser is refitted on every fold so the held out sample never
 * leaks into the z-score statistics.
 */
data class LoocvResult(
    val labels: List<String>,
    val folds: List<FoldResult>,
) {
    val total: Int get() = folds.size
    val correct: Int get() = folds.count { it.truth == it.predicted }
    val accuracy: Double get() = if (total > 0) correct.toDouble() / total else 0.0

    fun confusionMatrix(): List<List<Int>> = ModelEvaluation.confusion(labels, folds.map { it.truth }, folds.map { it.predicted })

    fun perConditionAccuracy(): Map<String, Double> =
        ModelEvaluation.perConditionAccuracy(labels, folds.map { it.truth }, folds.map { it.predicted })

    fun perConditionCounts(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (label in labels) counts[label] = 0
        for (fold in folds) if (counts.containsKey(fold.truth)) counts[fold.truth] = (counts[fold.truth] ?: 0) + 1
        return counts
    }
}

object ModelEvaluation {

    fun loocv(items: List<LabeledFeatures>, labels: List<String>, k: Int = ModelSpec.K): LoocvResult {
        val folds = ArrayList<FoldResult>()
        if (items.size < 2) return LoocvResult(labels, folds)
        for (index in items.indices) {
            val training = ArrayList<LabeledFeatures>(items.size - 1)
            for (j in items.indices) if (j != index) training.add(items[j])
            val classifier = KnnClassifier(k)
            classifier.fit(training)
            val prediction = classifier.predict(items[index].features) ?: continue
            folds.add(FoldResult(items[index].label, prediction.label, prediction.confidence))
        }
        return LoocvResult(labels, folds)
    }

    fun confusion(labels: List<String>, truths: List<String>, predictions: List<String>): List<List<Int>> {
        val index = labels.withIndex().associate { (i, label) -> label to i }
        val matrix = List(labels.size) { MutableList(labels.size) { 0 } }
        for (i in truths.indices) {
            val actual = index[truths[i]] ?: continue
            val predicted = index[predictions[i]] ?: continue
            matrix[actual][predicted] += 1
        }
        return matrix.map { it.toList() }
    }

    fun perConditionAccuracy(labels: List<String>, truths: List<String>, predictions: List<String>): Map<String, Double> {
        val total = LinkedHashMap<String, Int>()
        val correct = LinkedHashMap<String, Int>()
        for (label in labels) {
            total[label] = 0
            correct[label] = 0
        }
        for (i in truths.indices) {
            val truth = truths[i]
            if (!total.containsKey(truth)) continue
            total[truth] = (total[truth] ?: 0) + 1
            if (truth == predictions[i]) correct[truth] = (correct[truth] ?: 0) + 1
        }
        val out = LinkedHashMap<String, Double>()
        for (label in labels) {
            val count = total[label] ?: 0
            out[label] = if (count > 0) (correct[label] ?: 0).toDouble() / count else 0.0
        }
        return out
    }

    /** Accuracy of a prediction list against truth, ignoring unknown labels. */
    fun accuracy(truths: List<String>, predictions: List<String>): Double {
        if (truths.isEmpty()) return 0.0
        var correct = 0
        for (i in truths.indices) if (truths[i] == predictions[i]) correct++
        return correct.toDouble() / truths.size
    }
}
