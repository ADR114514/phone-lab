package com.mobilelab.vibrodiag.data

import com.mobilelab.core.csv.CsvBuilder
import com.mobilelab.core.time.TimeFmt
import com.mobilelab.vibrodiag.features.FeatureNames
import com.mobilelab.vibrodiag.ml.NoiseRow
import java.io.File

/** One blind-test row (persisted in blind_test_<sessionId>.csv, DATA_DICTIONARY 3.4). */
data class BlindTrialResult(
    val trialIndex: Int,
    val trueConditionId: String,
    val trueConditionLabel: String,
    val predictedConditionId: String,
    val predictedConditionLabel: String,
    val confidence: Double,
    val featuresRelativePath: String,
    val recordedAtMs: Long,
) {
    val correct: Boolean get() = trueConditionId == predictedConditionId
}

/** Minimal parsed blind row used by the evaluation report. */
data class BlindCsvRow(
    val trialIndex: Int,
    val trueConditionId: String,
    val predictedConditionId: String,
    val confidence: Double,
    val correct: Boolean,
)

/**
 * Session level CSV reports. Column order is part of the data contract
 * (DATA_DICTIONARY 3.3-3.5), so keep headers as constants.
 */
object Reports {

    val datasetIndexHeader: List<String> = listOf(
        "session_id", "condition_id", "condition_label", "groups_collected", "target_groups", "complete",
    )

    val blindTestHeader: List<String> = listOf(
        "session_id", "trial_index", "condition_id_true", "condition_id_predicted", "predicted_confidence",
        "correct", "features_file", "model", "timestamp_iso",
    )

    val noiseEvalHeader: List<String> = listOf(
        "session_id", "snr_db", "condition_id", "trials", "correct", "accuracy",
        "model", "noise_type", "timestamp_iso",
    )

    fun writeDatasetIndex(file: File, sessionId: String, rows: List<Pair<String, String>>, collected: Map<String, Int>, target: Int) {
        val builder = CsvBuilder(datasetIndexHeader)
        for ((conditionId, conditionLabel) in rows) {
            val count = collected[conditionId] ?: 0
            builder.row(
                sessionId,
                conditionId,
                conditionLabel,
                count,
                target,
                if (count >= target) 1 else 0,
            )
        }
        builder.write(file)
    }

    fun writeBlindTest(file: File, sessionId: String, results: List<BlindTrialResult>, model: String) {
        val builder = CsvBuilder(blindTestHeader)
        for (result in results.sortedBy { it.trialIndex }) {
            builder.row(
                sessionId,
                result.trialIndex,
                result.trueConditionId,
                result.predictedConditionId,
                format(result.confidence),
                if (result.correct) 1 else 0,
                result.featuresRelativePath,
                model,
                TimeFmt.isoLocal(result.recordedAtMs),
            )
        }
        builder.write(file)
    }

    fun readBlindRows(file: File): List<BlindCsvRow> {
        val table = CsvReader.readTable(file) ?: return emptyList()
        return table.rows.mapNotNull { row ->
            val trueId = table.string(row, "condition_id_true")
            val predictedId = table.string(row, "condition_id_predicted")
            if (trueId.isEmpty() || predictedId.isEmpty()) null else BlindCsvRow(
                trialIndex = table.long(row, "trial_index").toInt(),
                trueConditionId = trueId,
                predictedConditionId = predictedId,
                confidence = table.double(row, "predicted_confidence"),
                correct = table.long(row, "correct") == 1L,
            )
        }
    }

    fun writeNoiseEval(file: File, sessionId: String, rows: List<NoiseRow>, model: String) {
        val builder = CsvBuilder(noiseEvalHeader)
        val stamp = TimeFmt.isoLocal(System.currentTimeMillis())
        for (row in rows) {
            builder.row(
                sessionId,
                row.snrDb,
                row.conditionId,
                row.trials,
                row.correct,
                format(row.accuracy),
                model,
                row.noiseType.id,
                stamp,
            )
        }
        builder.write(file)
    }

    fun readNoiseRows(file: File): List<NoiseRow> {
        val table = CsvReader.readTable(file) ?: return emptyList()
        return table.rows.mapNotNull { row ->
            val type = row.getOrNull(table.column("noise_type"))?.trim().orEmpty()
            val noiseType = com.mobilelab.vibrodiag.features.NoiseType.fromId(type) ?: return@mapNotNull null
            val conditionId = table.string(row, "condition_id")
            if (conditionId.isEmpty()) return@mapNotNull null
            NoiseRow(
                noiseType = noiseType,
                snrDb = table.long(row, "snr_db").toInt(),
                conditionId = conditionId,
                trials = table.long(row, "trials").toInt(),
                correct = table.long(row, "correct").toInt(),
            )
        }
    }

    fun format(value: Double): String =
        if (!value.isFinite()) "" else String.format(java.util.Locale.US, "%.4f", value)

    /** Header order check used by tests and the dataset screen. */
    fun expectedFeatureColumnCount(): Int = FeatureNames.csvHeader.size
}
