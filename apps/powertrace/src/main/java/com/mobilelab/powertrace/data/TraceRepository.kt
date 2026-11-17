package com.mobilelab.powertrace.data

import android.content.Context
import com.mobilelab.core.device.DeviceSnapshot
import com.mobilelab.core.device.collectDeviceSnapshot
import com.mobilelab.core.device.deviceManifestJson
import com.mobilelab.core.files.Storage
import com.mobilelab.core.json.JsonObject
import com.mobilelab.core.log.LabLog
import com.mobilelab.powertrace.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 批次状态与实验文件的落盘层。
 *
 * 目录结构遵循数据字典 1.5（core.Storage.newSessionDir 生成
 * `<external-files>/experiments/powertrace/<yyyyMMdd_HHmmss>_<campaignId>/`），
 * 会话 CSV 采用追加写，进程被杀后已写行不丢失。
 */
class TraceRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val statePrefs = appContext.getSharedPreferences("powertrace_state", Context.MODE_PRIVATE)
    private val settingPrefs = appContext.getSharedPreferences("powertrace_settings", Context.MODE_PRIVATE)
    private val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    private var cachedSnapshot: DeviceSnapshot? = null

    // ---------------------------------------------------------------- 状态持久化

    fun loadState(): CampaignState? = StateCodec.decode(statePrefs.getString(KEY_STATE, null))

    fun saveState(state: CampaignState?) {
        statePrefs.edit().apply {
            if (state == null) remove(KEY_STATE) else putString(KEY_STATE, StateCodec.encode(state))
        }.apply()
    }

    fun isRawSeriesEnabled(): Boolean = settingPrefs.getBoolean(KEY_RAW_SERIES, false)

    fun setRawSeriesEnabled(enabled: Boolean) {
        settingPrefs.edit().putBoolean(KEY_RAW_SERIES, enabled).apply()
    }

    // ---------------------------------------------------------------- ID / 目录

    /** 会话 ID `PT-<yyyyMMdd>-<序号三位>`，批次 ID `PC-<yyyyMMdd>-<序号>`。 */
    fun nextIdSequence(nowMs: Long): Int {
        val day = dayStamp.format(Date(nowMs))
        val key = "seq_$day"
        val stored = settingPrefs.getInt(key, 0)
        val root = runCatching { Storage.appRoot(appContext, APP_TAG) }.getOrNull()
        val onDisk = root?.listFiles().orEmpty().mapNotNull { dir ->
            Regex("PC-$day-(\\d{3})").find(dir.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        val next = maxOf(stored, onDisk) + 1
        settingPrefs.edit().putInt(key, next).apply()
        return next
    }

    fun formatId(prefix: String, nowMs: Long, sequence: Int): String =
        String.format(Locale.US, "%s-%s-%03d", prefix, dayStamp.format(Date(nowMs)), sequence)

    fun createSessionDir(campaignId: String): File =
        Storage.newSessionDir(appContext, APP_TAG, campaignId)

    fun sessionFile(dirPath: String, condition: ConditionId): File =
        File(dirPath, "session_${condition.id}.csv")

    fun rawFile(dirPath: String, condition: ConditionId): File =
        File(dirPath, "raw_${condition.id}.csv")

    fun campaignFile(dirPath: String, campaignId: String): File =
        File(dirPath, "campaign_$campaignId.csv")

    fun summaryFile(dirPath: String, campaignId: String): File =
        File(dirPath, "campaign_summary_$campaignId.csv")

    fun manifestFile(dirPath: String): File = File(dirPath, "manifest.json")

    fun logFile(dirPath: String): File = File(dirPath, "session.log")

    // ---------------------------------------------------------------- 会话 CSV 追加写

    /** 追加一行采样；返回是否新建了文件（用于 session.log 记录）。 */
    fun appendSessionSample(state: CampaignState, condition: ConditionId, sample: SessionSample): Boolean {
        val file = sessionFile(state.dirPath, condition)
        val row = CsvSchema.sessionRow(state.sessionId, condition, sample)
        val fresh = CsvText.appendRow(file, CsvSchema.SESSION_HEADER, row)
        if (fresh) LabLog.i(TAG, "new session file ${file.name} (header written)")
        return fresh
    }

    /** 原始 5 s 序列（设置里开启时才写）。 */
    fun appendRawSample(state: CampaignState, condition: ConditionId, sample: RawSample) {
        val file = rawFile(state.dirPath, condition)
        CsvText.appendRow(file, CsvSchema.RAW_HEADER, CsvSchema.rawRow(sample))
    }

    fun readSamples(dirPath: String, condition: ConditionId): List<SessionSample> {
        val file = sessionFile(dirPath, condition)
        if (!file.exists()) return emptyList()
        return runCatching { CsvSchema.parseSessionCsv(file.readText()) }
            .onFailure { LabLog.e(TAG, "parse ${file.name} failed", it) }
            .getOrDefault(emptyList())
    }

    fun readAllSamples(state: CampaignState): Map<ConditionId, List<SessionSample>> =
        state.order.associateWith { condition -> readSamples(state.dirPath, condition) }

    // ---------------------------------------------------------------- 日志 / manifest

    fun attachLog(dirPath: String) {
        runCatching { LabLog.attach(logFile(dirPath)) }
    }

    fun logEvent(message: String) {
        LabLog.i(TAG, message)
    }

    fun deviceSnapshot(): DeviceSnapshot {
        cachedSnapshot?.let { return it }
        val snapshot = collectDeviceSnapshot(appContext)
        cachedSnapshot = snapshot
        return snapshot
    }

    /**
     * 写 manifest.json：基础字段沿用 core 通用 manifest 规范（数据字典第 0 节），
     * 再追加 PowerTrace 的批次/工况字段。conditions 数组通过字符串拼接到基础 JSON 里，
     * 因为 core.JsonObject 不支持 putRaw 之外的嵌套数组构造。
     */
    fun writeManifest(state: CampaignState) {
        runCatching {
            val dir = File(state.dirPath)
            dir.mkdirs()
            val base = deviceManifestJson(
                appLabel = APP_LABEL,
                appVersion = BuildConfig.VERSION_NAME,
                projectCode = PROJECT_CODE,
                snapshot = deviceSnapshot(),
            )
            val conditionsJson = state.conditions.joinToString(",", "[", "]") { c ->
                JsonObject()
                    .put("conditionId", c.condition.id)
                    .put("conditionLabel", c.condition.label)
                    .put("block", c.block.toString())
                    .put("samplesDone", c.samplesDone)
                    .put("lateSamples", c.lateSamples)
                    .put("startMs", c.startMs)
                    .put("endMs", c.endMs)
                    .put("pausedTotalMs", c.pausedTotalMs)
                    .put("earlyFinished", c.earlyFinished)
                    .toJson()
            }
            val extra = JsonObject()
                .put("campaignId", state.campaignId)
                .put("sessionId", state.sessionId)
                .put("campaignStatus", state.status.name)
                .put("campaignNotes", state.notes)
                .put("conditionOrder", state.order.map { it.id })
                .put("samplesPerCondition", SamplingConfig.SAMPLES_PER_CONDITION)
                .put("samplingIntervalSec", SamplingConfig.INTERVAL_SEC)
                .put("conditionDurationSec", SamplingConfig.CONDITION_DURATION_SEC)
                .put("rawSeriesEnabled", state.rawSeriesEnabled)
                .put("samplesCollected", state.totalSamplesDone)
                .put("createdAt", state.createdAtMs)
                .put("finishedAt", state.finishedMs)
                .put("rerunOf", state.rerunOf)
                .put("dataDictionary", "docs/DATA_DICTIONARY.md (section 1, PowerTrace)")
                .put("powerConvention", "power_w = voltage_mv * abs(current_now_ua) / 1e9")
                .put("energyMethod", "rectangular integration over 6 x 300 s buckets per condition")
                .put("currentSignConvention", "raw current_now_ua keeps device sign; power_w uses abs()")
                .put("foregroundPkgNote", "empty unless PACKAGE_USAGE_STATS appop is granted")
                .put("fpsNote", "Choreographer frame rate of this app, empty when app is not foreground")
                .putRaw("conditions", conditionsJson)
                .toJson()
            val merged = base.removeSuffix("}") + "," + extra.removePrefix("{")
            manifestFile(state.dirPath).writeText(merged, Charsets.UTF_8)
        }.onFailure { LabLog.e(TAG, "writeManifest failed", it) }
    }

    private companion object {
        const val TAG = "PowerTraceRepo"
        const val APP_TAG = "powertrace"
        const val PROJECT_CODE = "02-power-thermal"
        const val APP_LABEL = "PowerTrace"
        const val KEY_STATE = "campaign_state"
        const val KEY_RAW_SERIES = "raw_series_enabled"
    }
}
