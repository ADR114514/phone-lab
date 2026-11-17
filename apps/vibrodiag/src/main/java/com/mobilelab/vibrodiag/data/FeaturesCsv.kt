package com.mobilelab.vibrodiag.data

import com.mobilelab.core.csv.CsvBuilder
import com.mobilelab.vibrodiag.features.FeatureNames
import java.io.File
import java.util.Locale

/**
 * Serialisation of one sample's features.csv. Numeric values use "%.6f" (not
 * the core default four decimals) so that the cross-language comparison with
 * the Python pipeline stays inside the 1e-3 relative tolerance. Undefined
 * values are written as an empty field.
 */
object FeaturesCsv {

    fun formatFeature(value: Double): String =
        if (!value.isFinite()) "" else String.format(Locale.US, "%.6f", value)

    fun write(
        file: File,
        features: Map<String, Double>,
        conditionId: String,
        conditionLabel: String,
        groupIndex: Int,
        fileStem: String,
        recordedAtMs: Long,
        durationS: Double,
    ) {
        val builder = CsvBuilder(FeatureNames.csvHeader)
        val row = ArrayList<Any?>(FeatureNames.csvHeader.size)
        for (name in FeatureNames.numeric) {
            row.add(formatFeature(features[name] ?: Double.NaN))
        }
        row.add(conditionId)
        row.add(conditionLabel)
        row.add(groupIndex)
        row.add(fileStem)
        row.add(recordedAtMs)
        row.add(formatFeature(durationS))
        builder.row(row)
        builder.write(file)
    }

    /** Returns the numeric feature map (missing/blank columns become NaN). */
    fun read(file: File): Map<String, Double>? {
        val table = CsvReader.readTable(file) ?: return null
        if (table.rows.isEmpty()) return null
        val row = table.rows.first()
        val out = LinkedHashMap<String, Double>(FeatureNames.numeric.size)
        for (name in FeatureNames.numeric) {
            val index = table.column(name)
            val value = if (index >= 0 && index < row.size) row[index].trim().toDoubleOrNull() else null
            out[name] = value ?: Double.NaN
        }
        return out
    }

    fun readLabels(file: File): CsvTable? = CsvReader.readTable(file)
}
