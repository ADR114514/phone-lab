package com.mobilelab.vibrodiag.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** kNN + z-score + LOOCV behaviour on a trivially separable data set. */
class KnnModelTest {

    private fun separableData(): List<LabeledFeatures> {
        val centres = listOf(
            "C1" to doubleArrayOf(0.0, 0.0),
            "C2" to doubleArrayOf(10.0, 0.0),
            "C3" to doubleArrayOf(0.0, 10.0),
            "C4" to doubleArrayOf(10.0, 10.0),
            "C5" to doubleArrayOf(5.0, 5.0),
        )
        val items = ArrayList<LabeledFeatures>()
        for ((label, centre) in centres) {
            val offsets = listOf(-0.3, 0.0, 0.3)
            for (offset in offsets) {
                items.add(LabeledFeatures(label, doubleArrayOf(centre[0] + offset, centre[1] - offset)))
            }
        }
        return items
    }

    @Test
    fun classifierLearnsSeparableData() {
        val items = separableData()
        val classifier = KnnClassifier(3)
        classifier.fit(items)
        val prediction = classifier.predict(doubleArrayOf(0.1, 0.2))
        assertNotNull(prediction)
        assertEquals("C1", prediction!!.label)
        assertTrue(prediction.confidence > 0.99)
        assertEquals(3, prediction.neighborLabels.size)
        assertEquals(15, classifier.trainingSamples)
        assertEquals(2, classifier.featureCount)
    }

    @Test
    fun loocvScoresPerfectlyOnSeparableData() {
        val items = separableData()
        val result = ModelEvaluation.loocv(items, listOf("C1", "C2", "C3", "C4", "C5"))
        assertEquals(15, result.total)
        assertEquals(15, result.correct)
        assertEquals(1.0, result.accuracy, 1e-9)
        val matrix = result.confusionMatrix()
        for (i in matrix.indices) {
            for (j in matrix.indices) {
                if (i == j) assertEquals(3, matrix[i][j]) else assertEquals(0, matrix[i][j])
            }
        }
        for (label in result.perConditionAccuracy().keys) {
            assertEquals(1.0, result.perConditionAccuracy().getValue(label), 1e-9)
        }
        assertEquals(3, result.perConditionCounts().getValue("C1"))
    }

    @Test
    fun loocvWithOneSamplePerClassCannotRecoverTheHeldOutClass() {
        // Degenerate training set documented by the evaluator: the held out class
        // is absent from the fold's training data, so every fold is wrong.
        val items = listOf(
            LabeledFeatures("A", doubleArrayOf(0.0, 0.0)),
            LabeledFeatures("B", doubleArrayOf(5.0, 5.0)),
        )
        val result = ModelEvaluation.loocv(items, listOf("A", "B"))
        assertEquals(2, result.total)
        assertEquals(0.0, result.accuracy, 1e-9)
    }

    @Test
    fun missingFeatureValuesAreImputedWithTrainingMean() {
        val items = listOf(
            LabeledFeatures("A", doubleArrayOf(1.0, Double.NaN)),
            LabeledFeatures("A", doubleArrayOf(2.0, 2.0)),
            LabeledFeatures("B", doubleArrayOf(9.0, 9.0)),
            LabeledFeatures("B", doubleArrayOf(10.0, 10.0)),
        )
        val classifier = KnnClassifier(3)
        classifier.fit(items)
        val prediction = classifier.predict(doubleArrayOf(1.5, Double.NaN))
        assertEquals("A", prediction!!.label)
    }

    @Test
    fun accuracyHelperCountsMatches() {
        assertEquals(0.5, ModelEvaluation.accuracy(listOf("A", "B"), listOf("A", "A")), 1e-9)
        assertEquals(0.0, ModelEvaluation.accuracy(emptyList(), emptyList()), 1e-9)
    }
}
