package com.mobilelab.vibrodiag.data

import android.content.Context
import androidx.core.content.edit
import com.mobilelab.core.device.DeviceSnapshot
import com.mobilelab.core.device.deviceManifestJson
import com.mobilelab.core.files.Storage
import com.mobilelab.core.json.JsonObject
import com.mobilelab.core.log.LabLog
import com.mobilelab.core.time.TimeFmt
import com.mobilelab.vibrodiag.audio.AudioCapability
import com.mobilelab.vibrodiag.audio.RecordingOutput
import com.mobilelab.vibrodiag.features.FeaturePipeline
import com.mobilelab.vibrodiag.model.AppSettings
import com.mobilelab.vibrodiag.model.Condition
import com.mobilelab.vibrodiag.model.Conditions
import com.mobilelab.vibrodiag.sensor.AccelCapability
import java.io.File
import java.util.Locale

/**
 * Owns the on-device experiment directory for one session.
 *
 * Layout (DATA_DICTIONARY 3.7):
 *   experiments/vibrodiag/<stamp>_<sessionId>/
 *     manifest.json
 *     <conditionId>/g<NN>/g<NN>_<conditionId>_{audio.wav,accel.csv,features.csv,meta.json}
 *     dataset_index.csv
 *     blind_test_<sessionId>.csv
 *     noise_eval_<sessionId>.csv
 *     evaluation_<sessionId>.json
 *     session.log
 *     photos/
 *     blind/                 # blind test recordings (not part of the 75-sample dataset)
 */
class SessionRepository internal constructor(
    val context: Context,
    val root: File,
    val sessionId: String,
) {

    val datasetIndexFile: File get() = File(root, "dataset_index.csv")
    val blindCsvFile: File get() = File(root, "blind_test_" + sessionId + ".csv")
    val noiseCsvFile: File get() = File(root, "noise_eval_" + sessionId + ".csv")
    val evaluationFile: File get() = File(root, "evaluation_" + sessionId + ".json")
    val sessionLogFile: File get() = File(root, "session.log")
    val photosDir: File get() = File(root, "photos")
    val blindDir: File get() = File(root, "blind")

    fun conditionDir(conditionId: String): File = File(root, conditionId)

    fun sampleDir(conditionId: String, groupIndex: Int): File =
        File(conditionDir(conditionId), String.format(Locale.US, "g%02d", groupIndex))

    fun groupStem(groupIndex: Int, conditionId: String): String =
        String.format(Locale.US, "g%02d_%s", groupIndex, conditionId)

    fun samplePaths(conditionId: String, groupIndex: Int): SamplePaths =
        SamplePaths.of(sampleDir(conditionId, groupIndex), groupStem(groupIndex, conditionId))

    fun blindSamplePaths(trialIndex: Int, conditionId: String): SamplePaths {
        val stem = String.format(Locale.US, "t%02d_%s", trialIndex, conditionId)
        return SamplePaths.of(blindDir, stem)
    }

    fun relativePath(file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace('\\', '/')

    fun interruptedMarker(paths: SamplePaths): File = File(paths.dir, ".interrupted")

    fun ensureLayout() {
        root.mkdirs()
        photosDir.mkdirs()
        if (!datasetIndexFile.exists()) writeDatasetIndex()
    }

    fun listSamples(): List<StoredSample> {
        val samples = ArrayList<StoredSample>()
        for (condition in Conditions.all) {
            val dir = conditionDir(condition.id)
            if (!dir.isDirectory) continue
            val groupDirs = dir.listFiles()?.filter { it.isDirectory && GROUP_DIR.matches(it.name) } ?: continue
            for (groupDir in groupDirs) {
                val group = groupDir.name.substring(1).toIntOrNull() ?: continue
                val paths = SamplePaths.of(groupDir, groupStem(group, condition.id))
                if (!paths.features.isFile) continue
                val features = FeaturesCsv.read(paths.features) ?: continue
                val table = CsvReader.readTable(paths.features)
                val row = table?.rows?.firstOrNull()
                val duration = if (table != null && row != null) table.double(row, "duration_s") else Double.NaN
                val recordedAt = if (table != null && row != null) table.long(row, "recorded_at_ms") else -1L
                samples.add(
                    StoredSample(
                        conditionId = condition.id,
                        conditionLabel = condition.label,
                        groupIndex = group,
                        paths = paths,
                        features = features,
                        durationS = duration,
                        recordedAtMs = recordedAt,
                        interrupted = interruptedMarker(paths).exists(),
                    )
                )
            }
        }
        return samples.sortedWith(compareBy({ Conditions.indexOf(it.conditionId) }, { it.groupIndex }))
    }

    /** Number of usable (non-interrupted, feature extracted) groups for a condition. */
    fun countComplete(conditionId: String): Int {
        var count = 0
        for (group in 1..Conditions.TARGET_GROUPS) {
            val paths = samplePaths(conditionId, group)
            if (paths.features.isFile && !interruptedMarker(paths).exists()) count++
        }
        return count
    }

    fun collectedCounts(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (condition in Conditions.all) counts[condition.id] = countComplete(condition.id)
        return counts
    }

    /** First incomplete group, or null when all 15 groups are usable. */
    fun nextGroup(conditionId: String): Int? {
        for (group in 1..Conditions.TARGET_GROUPS) {
            val paths = samplePaths(conditionId, group)
            if (!paths.features.isFile || interruptedMarker(paths).exists()) return group
        }
        return null
    }

    /** Condition with the fewest completed groups (the recording default). */
    fun leastCollectedCondition(): String {
        val counts = collectedCounts()
        return Conditions.all.minByOrNull { counts[it.id] ?: 0 }?.id ?: Conditions.all.first().id
    }

    fun deleteSample(sample: StoredSample) {
        val paths = sample.paths
        listOf(paths.audio, paths.accel, paths.features, paths.meta, interruptedMarker(paths)).forEach { file ->
            runCatching { file.delete() }
        }
        runCatching {
            if (paths.dir.listFiles().isNullOrEmpty()) paths.dir.delete()
        }
        writeDatasetIndex()
        LabLog.i(TAG, "deleted sample " + paths.stem)
    }

    /** 删除某工况全部 15 组（WAV/CSV/meta），并重建 dataset_index。返回删除的组数。 */
    fun deleteConditionSamples(conditionId: String): Int {
        var removed = 0
        for (group in 1..Conditions.TARGET_GROUPS) {
            val dir = sampleDir(conditionId, group)
            if (dir.exists()) {
                if (dir.deleteRecursively()) removed++
            }
        }
        conditionDir(conditionId).listFiles()
            ?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }
            ?.forEach { it.delete() }
        writeDatasetIndex()
        LabLog.i("SessionRepository", "deleted " + removed + " samples of " + conditionId)
        return removed
    }

    /** 删除本会话全部录音与派生评估（盲测/抗噪/评估 JSON），保留 manifest/session.log。 */
    fun deleteAllSamples(): Int {
        var removed = 0
        for (condition in Conditions.all) removed += deleteConditionSamples(condition.id)
        runCatching { clearBlindTestData() }
        runCatching { if (noiseCsvFile.exists()) noiseCsvFile.delete() }
        runCatching { if (evaluationFile.exists()) evaluationFile.delete() }
        writeDatasetIndex()
        LabLog.i("SessionRepository", "deleted all samples: " + removed + " groups")
        return removed
    }

    fun writeDatasetIndex() {
        val rows = Conditions.all.map { it.id to it.label }
        Reports.writeDatasetIndex(datasetIndexFile, sessionId, rows, collectedCounts(), Conditions.TARGET_GROUPS)
    }

    fun writeManifest(
        snapshot: DeviceSnapshot,
        audio: AudioCapability,
        accel: AccelCapability,
        appVersion: String,
    ) {
        val conditions = Conditions.all.map { condition ->
            JsonObject().put("id", condition.id).put("label", condition.label)
        }
        val extras = mapOf<String, Any?>(
            "sessionId" to sessionId,
            "conditions" to conditions,
            "targetGroups" to Conditions.TARGET_GROUPS,
            "audio" to JsonObject()
                .put("appSampleRate", audio.sampleRate)
                .put("channels", 1)
                .put("bits", 16)
                .put("resolvedSource", audio.sourceLabel)
                .put("unprocessedSupported", audio.unprocessedSupported)
                .put("micSupported", audio.micSupported)
                .put("minBufferBytes", audio.minBufferBytes)
                .put("outputSampleRateHz", audio.outputSampleRateHz)
                .put("note", audio.note),
            "accel" to JsonObject()
                .put("sensor", accel.sensorName)
                .put("vendor", accel.vendor)
                .put("minDelayUs", accel.minDelayUs)
                .put("maxDelayUs", accel.maxDelayUs)
                .put("maxRateHz", accel.maxRateHz)
                .put("supports200Hz", accel.supports200Hz)
                .put("requestedMode", accel.requestedMode.id)
                .put("resolvedPeriodUs", accel.resolvedPeriodUs)
                .put("resolvedRateHz", accel.resolvedRateHz),
        )
        Storage.writeText(
            File(root, "manifest.json"),
            deviceManifestJson("VibroDiag", appVersion, "03-vibro-diag", snapshot, extras),
        )
    }

    /**
     * Writes accel CSV, extracts the contract features from the saved WAV and
     * persists meta + dataset index. Throws when the audio file is unusable.
     */
    fun persistRecording(
        conditionId: String,
        groupIndex: Int,
        output: RecordingOutput,
        settings: AppSettings,
        interrupted: Boolean,
    ): StoredSample {
        val condition = Conditions.byId(conditionId)
            ?: throw IllegalArgumentException("未知工况 " + conditionId)
        val paths = samplePaths(conditionId, groupIndex)
        paths.dir.mkdirs()
        if (!paths.audio.isFile || paths.audio.length() <= 44L) {
            throw IllegalStateException("录音文件为空，无法保存")
        }
        AccelCsv.write(paths.accel, output.accelSamples, output.baseNanos, output.accelRateHz)
        val features = FeaturePipeline.extractFromFiles(paths.audio, paths.accel)
        FeaturesCsv.write(
            file = paths.features,
            features = features,
            conditionId = conditionId,
            conditionLabel = condition.label,
            groupIndex = groupIndex,
            fileStem = paths.stem,
            recordedAtMs = output.startedAtMs,
            durationS = output.durationS,
        )
        writeMeta(paths, condition, groupIndex, output, features, settings, interrupted, blind = false)
        if (interrupted) {
            runCatching { interruptedMarker(paths).writeText(output.error ?: "interrupted") }
        } else {
            runCatching { interruptedMarker(paths).delete() }
        }
        writeDatasetIndex()
        LabLog.i(
            TAG,
            "saved " + paths.stem + " duration=" + String.format(Locale.US, "%.2f", output.durationS) +
                "s source=" + output.audioSourceLabel + " accelSamples=" + output.accelSamples.size +
                " interrupted=" + interrupted,
        )
        return StoredSample(
            conditionId = conditionId,
            conditionLabel = condition.label,
            groupIndex = groupIndex,
            paths = paths,
            features = features,
            durationS = output.durationS,
            recordedAtMs = output.startedAtMs,
            interrupted = interrupted,
        )
    }

    fun persistBlindRecording(
        trialIndex: Int,
        conditionId: String,
        output: RecordingOutput,
        settings: AppSettings,
    ): StoredSample {
        val condition = Conditions.byId(conditionId)
            ?: throw IllegalArgumentException("未知工况 " + conditionId)
        val paths = blindSamplePaths(trialIndex, conditionId)
        paths.dir.mkdirs()
        if (!paths.audio.isFile || paths.audio.length() <= 44L) {
            throw IllegalStateException("盲测录音文件为空，无法保存")
        }
        AccelCsv.write(paths.accel, output.accelSamples, output.baseNanos, output.accelRateHz)
        val features = FeaturePipeline.extractFromFiles(paths.audio, paths.accel)
        FeaturesCsv.write(
            file = paths.features,
            features = features,
            conditionId = conditionId,
            conditionLabel = condition.label,
            groupIndex = trialIndex + 1,
            fileStem = paths.stem,
            recordedAtMs = output.startedAtMs,
            durationS = output.durationS,
        )
        writeMeta(paths, condition, trialIndex + 1, output, features, settings, interrupted = false, blind = true)
        LabLog.i(TAG, "saved blind sample " + paths.stem + " duration=" + String.format(Locale.US, "%.2f", output.durationS))
        return StoredSample(
            conditionId = conditionId,
            conditionLabel = condition.label,
            groupIndex = trialIndex,
            paths = paths,
            features = features,
            durationS = output.durationS,
            recordedAtMs = output.startedAtMs,
        )
    }

    fun clearBlindTestData() {
        runCatching { blindDir.deleteRecursively() }
        runCatching { blindCsvFile.delete() }
    }

    private fun writeMeta(
        paths: SamplePaths,
        condition: Condition,
        groupIndex: Int,
        output: RecordingOutput,
        features: Map<String, Double>,
        settings: AppSettings,
        interrupted: Boolean,
        blind: Boolean,
    ) {
        val audio = JsonObject()
            .put("sample_rate", output.audioSampleRate)
            .put("channels", 1)
            .put("bits", 16)
            .put("source", output.audioSourceLabel)
            .put("file", paths.audio.name)
            .put("rms_dbfs", features["audio_rms_dbfs"])
            .put("peak_dbfs", features["audio_peak_dbfs"])
        val accel = JsonObject()
            .put("sample_rate_hz", output.accelRateHz)
            .put("file", paths.accel.name)
            .put("rms_ms2", features["accel_rms"])
            .put("peak_ms2", features["accel_peak"])
            .put("samples", output.accelSamples.size)
        val meta = JsonObject()
            .put("schema", "phonelab.vibro/1")
            .put("session_id", sessionId)
            .put("condition_id", condition.id)
            .put("condition_label", condition.label)
            .put("group_index", groupIndex)
            .put("recorded_at_ms", output.startedAtMs)
            .put("duration_s", output.durationS)
            .put("audio", audio)
            .put("accel", accel)
            .put("placement", settings.placement)
            .put("device_distance_cm", settings.distanceCm)
            .put("noise_condition", settings.noiseCondition)
            .put("noise_spl_db", null)
            .put("operator", settings.operator)
            .put("notes", output.error ?: "")
            .put("interrupted", interrupted)
            // Blind-test recordings live in blind/ and are NOT part of the 75-sample
            // dataset; analysis scripts must skip them (rglob meta would see them).
            .put("blind_trial", blind)
            .put("exclude_from_dataset", blind)
        Storage.writeText(paths.meta, meta.toJson())
    }

    companion object {
        private const val TAG = "SessionRepository"
        private val GROUP_DIR = Regex("g\\d{2}")
    }
}

/** Creates/opens the app's working session and restores it across process restarts. */
object SessionRepositoryFactory {

    const val APP_TAG = "vibrodiag"
    private const val PREFS = "vibrodiag_session"
    private const val KEY_DIR = "session_dir"
    private val SESSION_ID = Regex("VD-\\d{8}-\\d{3}")

    fun openOrCreate(context: Context): SessionRepository {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DIR, null)
        if (!stored.isNullOrBlank()) {
            val dir = File(stored)
            val sessionId = extractSessionId(dir.name)
            if (dir.isDirectory && sessionId != null) {
                LabLog.attach(File(dir, "session.log"))
                val repository = SessionRepository(app, dir, sessionId)
                repository.ensureLayout()
                LabLog.i("Session", "opened " + sessionId + " at " + dir.absolutePath)
                return repository
            }
        }
        return createNew(app)
    }

    fun createNew(context: Context): SessionRepository {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val day = TimeFmt.fileStamp(now).substring(0, 8)
        val appRoot = Storage.appRoot(app, APP_TAG)
        var sequence = (appRoot.listFiles()?.count { it.name.contains("_VD-") } ?: 0) + 1
        var sessionId: String
        var dir: File
        while (true) {
            sessionId = "VD-" + day + "-" + String.format(Locale.US, "%03d", sequence)
            val clash = appRoot.listFiles()?.any { it.name.contains(sessionId) } == true
            if (!clash) {
                dir = Storage.newSessionDir(app, APP_TAG, sessionId)
                break
            }
            sequence++
        }
        dir.mkdirs()
        File(dir, "photos").mkdirs()
        val repository = SessionRepository(app, dir, sessionId)
        repository.ensureLayout()
        repository.writeDatasetIndex()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_DIR, dir.absolutePath)
        }
        LabLog.attach(File(dir, "session.log"))
        LabLog.i("Session", "created " + sessionId + " at " + dir.absolutePath)
        return repository
    }

    fun extractSessionId(dirName: String): String? = SESSION_ID.find(dirName)?.value
}
