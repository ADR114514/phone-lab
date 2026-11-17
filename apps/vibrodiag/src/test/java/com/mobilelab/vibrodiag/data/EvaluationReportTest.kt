package com.mobilelab.vibrodiag.data

import com.mobilelab.vibrodiag.features.FeatureNames
import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.ml.KnnClassifier
import com.mobilelab.vibrodiag.ml.LabeledFeatures
import com.mobilelab.vibrodiag.ml.ModelEvaluation
import com.mobilelab.vibrodiag.ml.NoiseEvaluationResult
import com.mobilelab.vibrodiag.ml.NoiseRow
import com.mobilelab.vibrodiag.model.Conditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Structural checks on evaluation_<sessionId>.json (DATA_DICTIONARY 3.6). */
class EvaluationReportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun syntheticItems(): List<LabeledFeatures> {
        val items = ArrayList<LabeledFeatures>()
        for ((index, condition) in Conditions.all.withIndex()) {
            for (repeat in 0 until 3) {
                val features = DoubleArray(FeatureNames.count) { column ->
                    if (column == 0) index.toDouble() * 10.0 + repeat * 0.1 else 0.0
                }
                items.add(LabeledFeatures(condition.id, features))
            }
        }
        return items
    }

    @Test
    fun featureCountMatchesFeatureSpec() {
        // 6 audio time + 7 audio spectral + 7 audio bands + 4 accel time + 3 accel spectral + 5 accel bands
        assertEquals(32, FeatureNames.count)
        assertEquals(38, FeatureNames.csvHeader.size)
    }

    @Test
    fun reportContainsTrainingBlindNoiseAndModelBlocks() {
        val loocv = ModelEvaluation.loocv(syntheticItems(), Conditions.ids)
        assertEquals(15, loocv.total)
        val noiseRows = ArrayList<NoiseRow>()
        for (type in listOf(NoiseType.WHITE, NoiseType.PINK)) {
            for (snr in listOf(20, 10, 5, 0, -5)) {
                for (condition in Conditions.all) noiseRows.add(NoiseRow(type, snr, condition.id, 15, 12))
            }
        }
        val noise = NoiseEvaluationResult(noiseRows, listOf(20, 10, 5, 0, -5), listOf(NoiseType.WHITE, NoiseType.PINK))
        val counts = Conditions.all.associate { it.id to 3 }
        val json = EvaluationReport.build(
            sessionId = "VD-20261118-001",
            generatedAtMs = 1730000000000L,
            trainingSamples = 15,
            loocv = loocv,
            blindRows = listOf(BlindCsvRow(1, "C1_NORMAL_LOW", "C1_NORMAL_LOW", 0.9, true)),
            noise = noise,
            conditionSamples = counts,
            notes = "安静实验室",
        )
        val file = folder.newFile("evaluation.json")
        EvaluationReport.write(file, json)
        val text = file.readText()
        assertTrue(text.contains("\"schema\":\"phonelab.vibro.eval/1\""))
        assertTrue(text.contains("\"session_id\":\"VD-20261118-001\""))
        assertTrue(text.contains("\"features\":32"))
        assertTrue(text.contains("\"model\":\"knn(k=3, zscore)\""))
        assertTrue(text.contains("\"loocv_accuracy\""))
        assertTrue(text.contains("\"confusion_matrix\""))
        assertTrue(text.contains("\"per_condition_accuracy\""))
        assertTrue(text.contains("\"blind_test\""))
        assertTrue(text.contains("\"trials\":1"))
        assertTrue(text.contains("\"noise\""))
        assertTrue(text.contains("\"snr_db\":20"))
        assertTrue(text.contains("\"white\":0.8"))
        assertTrue(text.contains("\"pink\":0.8"))
        assertTrue(text.contains("\"feature_count\":32"))
        assertTrue(text.contains("\"conditions\""))
        assertTrue(text.contains("安静实验室"))
    }

    @Test
    fun reportWorksWithoutLoocvOrNoise() {
        val json = EvaluationReport.build(
            sessionId = "VD-20261118-002",
            generatedAtMs = 1L,
            trainingSamples = 0,
            loocv = null,
            blindRows = emptyList(),
            noise = null,
            conditionSamples = Conditions.all.associate { it.id to 0 },
            notes = "",
        )
        assertTrue(json.contains("\"schema\":\"phonelab.vibro.eval/1\""))
        assertTrue(json.contains("\"training\":{\"samples\":0,\"features\":32"))
        assertTrue(json.contains("\"noise\":[]"))
    }

    @Test
    fun classifierPredictsSyntheticClassCentres() {
        val classifier = KnnClassifier(3)
        classifier.fit(syntheticItems())
        val query = DoubleArray(FeatureNames.count).also { it[0] = 31.0 }
        assertEquals("C4_BLOCKED", classifier.predict(query)!!.label)
    }
}
