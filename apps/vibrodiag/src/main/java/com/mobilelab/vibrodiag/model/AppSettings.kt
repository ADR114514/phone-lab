package com.mobilelab.vibrodiag.model

import android.content.Context
import androidx.core.content.edit
import com.mobilelab.vibrodiag.features.NoiseType

/** User configurable acquisition/evaluation defaults, persisted in SharedPreferences. */
data class AppSettings(
    val durationS: Int = DEFAULT_DURATION_S,
    val accelMode: AccelMode = AccelMode.AUTO,
    val defaultNoiseType: NoiseType = NoiseType.WHITE,
    val defaultSnrDb: Int = 10,
    val placement: String = DEFAULT_PLACEMENT,
    val distanceCm: Int = 30,
    val noiseCondition: String = "quiet",
    val operator: String = "",
) {
    fun normalized(): AppSettings = copy(
        durationS = durationS.coerceIn(MIN_DURATION_S, MAX_DURATION_S),
        distanceCm = distanceCm.coerceIn(0, 200),
    )

    companion object {
        const val MIN_DURATION_S = 5
        const val MAX_DURATION_S = 30
        const val DEFAULT_DURATION_S = 15
        const val DEFAULT_PLACEMENT = "手机平放于风扇底座正前方 30cm 桌面"

        private const val PREFS = "vibrodiag_settings"
        private const val KEY_DURATION = "duration_s"
        private const val KEY_ACCEL_MODE = "accel_mode"
        private const val KEY_NOISE_TYPE = "noise_type"
        private const val KEY_SNR = "snr_db"
        private const val KEY_PLACEMENT = "placement"
        private const val KEY_DISTANCE = "distance_cm"
        private const val KEY_NOISE_CONDITION = "noise_condition"
        private const val KEY_OPERATOR = "operator"

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                durationS = prefs.getInt(KEY_DURATION, DEFAULT_DURATION_S),
                accelMode = AccelMode.fromId(prefs.getString(KEY_ACCEL_MODE, AccelMode.AUTO.id) ?: AccelMode.AUTO.id),
                defaultNoiseType = NoiseType.fromId(prefs.getString(KEY_NOISE_TYPE, NoiseType.WHITE.id) ?: NoiseType.WHITE.id)
                    ?: NoiseType.WHITE,
                defaultSnrDb = prefs.getInt(KEY_SNR, 10),
                placement = prefs.getString(KEY_PLACEMENT, DEFAULT_PLACEMENT) ?: DEFAULT_PLACEMENT,
                distanceCm = prefs.getInt(KEY_DISTANCE, 30),
                noiseCondition = prefs.getString(KEY_NOISE_CONDITION, "quiet") ?: "quiet",
                operator = prefs.getString(KEY_OPERATOR, "") ?: "",
            ).normalized()
        }

        fun save(context: Context, settings: AppSettings) {
            val normalized = settings.normalized()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putInt(KEY_DURATION, normalized.durationS)
                putString(KEY_ACCEL_MODE, normalized.accelMode.id)
                putString(KEY_NOISE_TYPE, normalized.defaultNoiseType.id)
                putInt(KEY_SNR, normalized.defaultSnrDb)
                putString(KEY_PLACEMENT, normalized.placement)
                putInt(KEY_DISTANCE, normalized.distanceCm)
                putString(KEY_NOISE_CONDITION, normalized.noiseCondition)
                putString(KEY_OPERATOR, normalized.operator)
            }
        }
    }
}
