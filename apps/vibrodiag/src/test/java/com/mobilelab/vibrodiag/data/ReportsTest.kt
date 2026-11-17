package com.mobilelab.vibrodiag.data

import com.mobilelab.vibrodiag.features.NoiseType
import com.mobilelab.vibrodiag.ml.NoiseRow
import com.mobilelab.vibrodiag.model.Conditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CSV row counts and column order for the three session level report files. */
class ReportsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sessionId = "VD-20261118-001"

    @Test
    fun datasetIndexHasOneRowPerCondition() {
        val file = folder.newFile("dataset_index.csv")
        val collected = Conditions.all.associate { it.id to 15 }
        Reports.writeDatasetIndex(file, sessionId, Conditions.all.map { it.id to it.label }, collected, 15)
        val table = CsvReader.readTable(file)
        assertNotNull(table)
        assertEquals(Reports.datasetIndexHeader, table!!.header)
        assertEquals(6, table.header.size)
        assertEquals(5, table.rows.size)
        for (row in table.rows) {
            assertEquals(sessionId, table.string(row, "session_id"))
            assertEquals(15, table.long(row, "groups_collected"))
            assertEquals(15, table.long(row, "target_groups"))
            assertEquals(1, table.long(row, "complete"))
        }
        assertEquals("C1_NORMAL_LOW", table.string(table.rows[0], "condition_id"))
        assertEquals("风扇低档正常", table.string(table.rows[0], "condition_label"))
    }

    @Test
    fun datasetIndexMarksIncompleteConditions() {
        val file = folder.newFile("dataset_index_incomplete.csv")
        val collected = Conditions.all.associate { it.id to 0 } + ("C3_IMBALANCE" to 7)
        Reports.writeDatasetIndex(file, sessionId, Conditions.all.map { it.id to it.label }, collected, 15)
        val table = CsvReader.readTable(file)!!
        val imbalance = table.rows.first { table.string(it, "condition_id") == "C3_IMBALANCE" }
        assertEquals(7, table.long(imbalance, "groups_collected"))
        assertEquals(0, table.long(imbalance, "complete"))
    }

    @Test
    fun blindTestCsvRoundTrips() {
        val file = folder.newFile("blind_test.csv")
        val results = listOf(
            BlindTrialResult(1, "C1_NORMAL_LOW", "风扇低档正常", "C1_NORMAL_LOW", "风扇低档正常", 0.93, "blind/t01_C1_NORMAL_LOW_features.csv", 1730000000000L),
            BlindTrialResult(2, "C2_NORMAL_HIGH", "风扇高档正常", "C3_IMBALANCE", "扇叶轻微失衡", 0.51, "blind/t02_C2_NORMAL_HIGH_features.csv", 1730000001000L),
            BlindTrialResult(3, "C5_HAIRDRYER", "吹风机", "C5_HAIRDRYER", "吹风机", 0.88, "blind/t03_C5_HAIRDRYER_features.csv", 1730000002000L),
        )
        Reports.writeBlindTest(file, sessionId, results, "knn(k=3, zscore)")
        val table = CsvReader.readTable(file)
        assertNotNull(table)
        assertEquals(Reports.blindTestHeader, table!!.header)
        assertEquals(9, table.header.size)
        assertEquals(3, table.rows.size)
        assertEquals(1, table.long(table.rows[0], "correct"))
        assertEquals(0, table.long(table.rows[1], "correct"))
        assertEquals(0.93, table.double(table.rows[0], "predicted_confidence"), 1e-9)
        assertEquals("knn(k=3, zscore)", table.string(table.rows[0], "model"))

        val parsed = Reports.readBlindRows(file)
        assertEquals(3, parsed.size)
        assertTrue(parsed[0].correct)
        assertEquals("C3_IMBALANCE", parsed[1].predictedConditionId)
        assertEquals(2, parsed.filter { it.correct }.size)
    }

    @Test
    fun noiseEvalCsvWritesFiftyRowsForTenSnrTypePairs() {
        val file = folder.newFile("noise_eval.csv")
        val rows = ArrayList<NoiseRow>()
        for (type in listOf(NoiseType.WHITE, NoiseType.PINK)) {
            for (snr in listOf(20, 10, 5, 0, -5)) {
                for (condition in Conditions.all) {
                    rows.add(NoiseRow(type, snr, condition.id, 15, 15 - (20 - snr).coerceAtLeast(0) / 5))
                }
            }
        }
        assertEquals(50, rows.size)
        Reports.writeNoiseEval(file, sessionId, rows, "knn(k=3, zscore)")
        val table = CsvReader.readTable(file)
        assertNotNull(table)
        assertEquals(Reports.noiseEvalHeader, table!!.header)
        assertEquals(9, table.header.size)
        assertEquals(50, table.rows.size)
        val parsed = Reports.readNoiseRows(file)
        assertEquals(50, parsed.size)
        val white20 = parsed.first { it.noiseType == NoiseType.WHITE && it.snrDb == 20 && it.conditionId == "C1_NORMAL_LOW" }
        assertEquals(15, white20.trials)
        assertEquals(15, white20.correct)
    }
}
