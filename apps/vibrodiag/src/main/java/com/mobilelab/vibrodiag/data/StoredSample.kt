package com.mobilelab.vibrodiag.data

import com.mobilelab.vibrodiag.features.FeatureVector
import java.io.File

/** File set of one acquisition (audio + accel + features + meta, flat per group dir). */
data class SamplePaths(
    val dir: File,
    val stem: String,
    val audio: File,
    val accel: File,
    val features: File,
    val meta: File,
) {
    companion object {
        fun of(dir: File, stem: String): SamplePaths = SamplePaths(
            dir = dir,
            stem = stem,
            audio = File(dir, stem + "_audio.wav"),
            accel = File(dir, stem + "_accel.csv"),
            features = File(dir, stem + "_features.csv"),
            meta = File(dir, stem + "_meta.json"),
        )
    }
}

/** A completed, feature-extracted acquisition loaded from the session directory. */
data class StoredSample(
    val conditionId: String,
    val conditionLabel: String,
    val groupIndex: Int,
    val paths: SamplePaths,
    val features: Map<String, Double>,
    val durationS: Double,
    val recordedAtMs: Long,
    /** True when the recording was interrupted (kept for auditing, excluded from the model). */
    val interrupted: Boolean = false,
) {
    val featureArray: DoubleArray get() = FeatureVector.toArray(features)

    val audioRmsDbfs: Double get() = features["audio_rms_dbfs"] ?: Double.NaN
    val audioPeakHz: Double get() = features["audio_peak_hz"] ?: Double.NaN
    val accelRms: Double get() = features["accel_rms"] ?: Double.NaN
}
