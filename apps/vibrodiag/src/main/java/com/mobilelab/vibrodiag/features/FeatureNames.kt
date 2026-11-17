package com.mobilelab.vibrodiag.features

/**
 * Feature column names in the exact order required by docs/feature_spec.md and
 * docs/DATA_DICTIONARY.md section 3.2. The order is part of the data contract:
 * append-only changes are allowed, reordering or renaming is not.
 */
object FeatureNames {

    val audioTime: List<String> = listOf(
        "audio_rms_dbfs",
        "audio_peak_dbfs",
        "audio_crest_db",
        "audio_zcr",
        "audio_kurtosis",
        "audio_skewness",
    )

    val audioSpectrum: List<String> = listOf(
        "audio_centroid_hz",
        "audio_rolloff85_hz",
        "audio_flatness",
        "audio_peak_hz",
        "audio_peak_mag",
        "audio_harmonic_ratio",
        "audio_hnr_db",
    )

    val audioBands: List<String> = listOf(
        "band_0_100",
        "band_100_300",
        "band_300_1k",
        "band_1k_2k",
        "band_2k_5k",
        "band_5k_10k",
        "band_10k_20k",
    )

    val accelTime: List<String> = listOf(
        "accel_rms",
        "accel_peak",
        "accel_crest",
        "accel_zcr",
    )

    val accelSpectrum: List<String> = listOf(
        "accel_peak_hz",
        "accel_centroid_hz",
        "accel_flatness",
    )

    val accelBands: List<String> = listOf(
        "accel_band_0_5",
        "accel_band_5_10",
        "accel_band_10_25",
        "accel_band_25_50",
        "accel_band_50_100",
    )

    /** Numeric model features, in CSV order. */
    val numeric: List<String> = audioTime + audioSpectrum + audioBands + accelTime + accelSpectrum + accelBands

    /** Label/metadata columns appended after the numeric features. */
    val labelColumns: List<String> = listOf(
        "condition_id",
        "condition_label",
        "group_index",
        "file_stem",
        "recorded_at_ms",
        "duration_s",
    )

    val csvHeader: List<String> = numeric + labelColumns

    val count: Int = numeric.size
}
