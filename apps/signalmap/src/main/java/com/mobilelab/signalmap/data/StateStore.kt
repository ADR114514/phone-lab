package com.mobilelab.signalmap.data

import android.content.Context

/**
 * Persists everything that must survive a process death: operator settings,
 * the editable station catalogue, per-point photo references, the progress
 * state machine and the active session directory.
 *
 * SharedPreferences is used instead of a JSON file on purpose: every field is a
 * primitive, so there is no parser to get wrong and a partial write can only
 * lose the value being written.
 */
class StateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- settings
    var settings: AppSettings
        get() = AppSettings(
            httpTestUrl = prefs.getString(KEY_HTTP_URL, AppSettings.DEFAULT_HTTP_URL)
                ?: AppSettings.DEFAULT_HTTP_URL,
            tcpTarget = prefs.getString(KEY_TCP_TARGET, AppSettings.DEFAULT_TCP_TARGET)
                ?: AppSettings.DEFAULT_TCP_TARGET,
            rttAttempts = prefs.getInt(KEY_RTT_ATTEMPTS, AppSettings.DEFAULT_RTT_ATTEMPTS),
            httpAttempts = prefs.getInt(KEY_HTTP_ATTEMPTS, AppSettings.DEFAULT_HTTP_ATTEMPTS),
            gatewayAttempts = prefs.getInt(KEY_GATEWAY_ATTEMPTS, AppSettings.DEFAULT_GATEWAY_ATTEMPTS),
            tcpTimeoutMs = prefs.getInt(KEY_TCP_TIMEOUT_MS, AppSettings.DEFAULT_TCP_TIMEOUT_MS),
            throughputEnabled = prefs.getBoolean(KEY_THROUGHPUT, false),
            throughputBytes = prefs.getInt(KEY_THROUGHPUT_BYTES, AppSettings.DEFAULT_THROUGHPUT_BYTES),
            throughputUrl = prefs.getString(KEY_THROUGHPUT_URL, AppSettings.DEFAULT_THROUGHPUT_URL)
                ?: AppSettings.DEFAULT_THROUGHPUT_URL,
            hashBssidOnExport = prefs.getBoolean(KEY_HASH_BSSID, true),
        ).sanitized()
        set(value) {
            val v = value.sanitized()
            prefs.edit()
                .putString(KEY_HTTP_URL, v.httpTestUrl)
                .putString(KEY_TCP_TARGET, v.tcpTarget)
                .putInt(KEY_RTT_ATTEMPTS, v.rttAttempts)
                .putInt(KEY_HTTP_ATTEMPTS, v.httpAttempts)
                .putInt(KEY_GATEWAY_ATTEMPTS, v.gatewayAttempts)
                .putInt(KEY_TCP_TIMEOUT_MS, v.tcpTimeoutMs)
                .putBoolean(KEY_THROUGHPUT, v.throughputEnabled)
                .putInt(KEY_THROUGHPUT_BYTES, v.throughputBytes)
                .putString(KEY_THROUGHPUT_URL, v.throughputUrl)
                .putBoolean(KEY_HASH_BSSID, v.hashBssidOnExport)
                .apply()
        }

    // ------------------------------------------------------------------ points
    fun loadPoints(): List<SurveyPoint> {
        val defaults = PresetPoints.defaults()
        val stored = prefs.getString(KEY_POINTS_ORDER, null)
        // A catalogue edited in a previous run is authoritative, but missing ids
        // are refilled from the presets so an upgrade never loses a station.
        val ids = stored?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: defaults.map { it.id }
        val points = ids.map { id ->
            val fallback = defaults.firstOrNull { it.id == id }
            SurveyPoint(
                id = id,
                name = prefs.getString(pointKey(id, "name"), fallback?.name ?: id) ?: id,
                type = PointType.fromId(prefs.getString(pointKey(id, "type"), fallback?.type?.id)),
                wallMaterial = WallMaterial.fromId(
                    prefs.getString(pointKey(id, "wall"), fallback?.wallMaterial?.id)
                ),
                obstruction = prefs.getString(pointKey(id, "obstruction"), fallback?.obstruction ?: "") ?: "",
                // "key present but empty" means the operator cleared the value;
                // only a missing key falls back to the preset.
                distanceM = if (prefs.contains(pointKey(id, "distance"))) {
                    prefs.getString(pointKey(id, "distance"), null)?.toDoubleOrNull()
                } else {
                    fallback?.distanceM
                },
                floor = if (prefs.contains(pointKey(id, "floor"))) {
                    prefs.getInt(pointKey(id, "floor"), 0).takeIf { it > 0 }
                } else {
                    fallback?.floor
                },
                photoFile = prefs.getString(pointKey(id, "photo"), "") ?: "",
            )
        }
        return points
    }

    fun savePoints(points: List<SurveyPoint>) {
        val editor = prefs.edit().putString(KEY_POINTS_ORDER, points.joinToString(",") { it.id })
        points.forEach { p ->
            editor.putString(pointKey(p.id, "name"), p.name)
                .putString(pointKey(p.id, "type"), p.type.id)
                .putString(pointKey(p.id, "wall"), p.wallMaterial.id)
                .putString(pointKey(p.id, "obstruction"), p.obstruction)
                .putString(pointKey(p.id, "distance"), p.distanceM?.toString() ?: "")
                .putInt(pointKey(p.id, "floor"), p.floor ?: 0)
                .putString(pointKey(p.id, "photo"), p.photoFile)
        }
        editor.apply()
    }

    // ---------------------------------------------------------------- progress
    fun loadProgress(): SurveyProgress = SurveyProgress.fromStorage(
        repeats = prefs.getString(KEY_PROGRESS_REPEATS, null),
        timestamps = prefs.getString(KEY_PROGRESS_TIMESTAMPS, null),
    )

    fun saveProgress(progress: SurveyProgress) {
        prefs.edit()
            .putString(KEY_PROGRESS_REPEATS, progress.toStorage())
            .putString(KEY_PROGRESS_TIMESTAMPS, progress.lastTimestampsToStorage())
            .apply()
    }

    // ----------------------------------------------------------------- session
    var sessionId: String?
        get() = prefs.getString(KEY_SESSION_ID, null)
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    var sessionDirName: String?
        get() = prefs.getString(KEY_SESSION_DIR, null)
        set(value) = prefs.edit().putString(KEY_SESSION_DIR, value).apply()

    var sessionStartedAtMs: Long
        get() = prefs.getLong(KEY_SESSION_STARTED, 0L)
        set(value) = prefs.edit().putLong(KEY_SESSION_STARTED, value).apply()

    /** Forgets the session so the next measurement start creates a fresh one. */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_DIR)
            .remove(KEY_SESSION_STARTED)
            .apply()
    }

    fun clearProgress() {
        prefs.edit()
            .remove(KEY_PROGRESS_REPEATS)
            .remove(KEY_PROGRESS_TIMESTAMPS)
            .apply()
    }

    private fun pointKey(id: String, field: String) = "point_${id}_$field"

    companion object {
        private const val PREFS_NAME = "signalmap_state"
        private const val KEY_HTTP_URL = "settings_http_url"
        private const val KEY_TCP_TARGET = "settings_tcp_target"
        private const val KEY_RTT_ATTEMPTS = "settings_rtt_attempts"
        private const val KEY_HTTP_ATTEMPTS = "settings_http_attempts"
        private const val KEY_GATEWAY_ATTEMPTS = "settings_gateway_attempts"
        private const val KEY_TCP_TIMEOUT_MS = "settings_tcp_timeout_ms"
        private const val KEY_THROUGHPUT = "settings_throughput"
        private const val KEY_THROUGHPUT_BYTES = "settings_throughput_bytes"
        private const val KEY_THROUGHPUT_URL = "settings_throughput_url"
        private const val KEY_HASH_BSSID = "settings_hash_bssid"
        private const val KEY_POINTS_ORDER = "points_order"
        private const val KEY_PROGRESS_REPEATS = "progress_repeats"
        private const val KEY_PROGRESS_TIMESTAMPS = "progress_last_ms"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_DIR = "session_dir"
        private const val KEY_SESSION_STARTED = "session_started_at"
    }
}
