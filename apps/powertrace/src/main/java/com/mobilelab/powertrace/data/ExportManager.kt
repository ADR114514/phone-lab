package com.mobilelab.powertrace.data

import android.content.Context
import com.mobilelab.core.files.Storage
import com.mobilelab.core.log.LabLog
import java.io.File
import java.util.Locale

/**
 * 派生文件生成与导出（数据字典 1.2 / 1.3 / 1.5）。
 *
 * 所有派生文件都从真实落盘的 session_*.csv 重新读取后计算，
 * 不依赖内存缓存，因此进程重启/崩溃恢复后依旧可追溯到原始采样行。
 */
class ExportManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val repo = TraceRepository(appContext)

    data class ResultsBundle(
        val state: CampaignState,
        val summaries: List<ConditionSummary>,
        val samplesByCondition: Map<ConditionId, List<SessionSample>>,
        val campaignCsv: File?,
        val summaryCsv: File?,
    )

    /** 只读：加载已落盘的采样点并计算工况汇总。 */
    fun loadBundle(state: CampaignState): ResultsBundle {
        val samples = repo.readAllSamples(state)
        val nowMs = System.currentTimeMillis()
        val summaries = state.conditions.map { progress ->
            val conditionSamples = samples[progress.condition].orEmpty()
            SummaryCalculator.summarize(
                condition = progress.condition,
                block = progress.block,
                samples = conditionSamples,
                durationMin = durationMinutes(progress, nowMs),
                extraNotes = summaryNote(state, progress),
            )
        }
        return ResultsBundle(
            state = state,
            summaries = summaries,
            samplesByCondition = samples,
            campaignCsv = repo.campaignFile(state.dirPath, state.campaignId).takeIf { it.exists() },
            summaryCsv = repo.summaryFile(state.dirPath, state.campaignId).takeIf { it.exists() },
        )
    }

    /**
     * 生成/刷新批次 CSV（最多 24 行）、工况汇总 CSV，并刷新 manifest.json。
     * 幂等：随时可以重写，行内容完全来自 session CSV。
     */
    fun writeDerived(state: CampaignState): ResultsBundle {
        val bundle = loadBundle(state)
        writeCampaignCsv(state, bundle)
        writeSummaryCsv(state, bundle)
        repo.writeManifest(state)
        return bundle
    }

    private fun writeCampaignCsv(state: CampaignState, bundle: ResultsBundle) {
        val rows = ArrayList<List<Any?>>()
        state.conditions.forEach { progress ->
            val samples = bundle.samplesByCondition[progress.condition].orEmpty().sortedBy { it.sampleIndex }
            for (sample in samples) {
                rows += CsvSchema.campaignRow(state.campaignId, progress.block, progress.condition, sample)
            }
        }
        runCatching {
            CsvText.writeFull(
                repo.campaignFile(state.dirPath, state.campaignId),
                CsvSchema.CAMPAIGN_HEADER,
                rows,
            )
            LabLog.i(TAG, "campaign csv written: ${rows.size} rows")
        }.onFailure { LabLog.e(TAG, "write campaign csv failed", it) }
    }

    private fun writeSummaryCsv(state: CampaignState, bundle: ResultsBundle) {
        runCatching {
            CsvText.writeFull(
                repo.summaryFile(state.dirPath, state.campaignId),
                CsvSchema.SUMMARY_HEADER,
                bundle.summaries.map { it.toRow() },
            )
            LabLog.i(TAG, "summary csv written: ${bundle.summaries.size} rows")
        }.onFailure { LabLog.e(TAG, "write summary csv failed", it) }
    }

    /** 打包整个会话目录（manifest/4×session/派生 CSV/session.log）。 */
    fun zipBundle(state: CampaignState): File? = runCatching {
        val dir = File(state.dirPath)
        if (!dir.exists()) return null
        writeDerived(state)
        val exports = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val zip = File(exports, "${dir.name}.zip")
        Storage.zipDirectory(dir, zip)
        zip
    }.onFailure { LabLog.e(TAG, "zip bundle failed", it) }.getOrNull()

    /** 可逐个导出的文件列表（按数据字典 1.5 顺序）。 */
    fun exportableFiles(state: CampaignState): List<File> {
        val dir = File(state.dirPath)
        val ordered = mutableListOf<File>()
        repo.manifestFile(state.dirPath).takeIf { it.exists() }?.let { ordered += it }
        state.conditions.forEach { progress ->
            repo.sessionFile(state.dirPath, progress.condition).takeIf { it.exists() }?.let { ordered += it }
        }
        repo.campaignFile(state.dirPath, state.campaignId).takeIf { it.exists() }?.let { ordered += it }
        repo.summaryFile(state.dirPath, state.campaignId).takeIf { it.exists() }?.let { ordered += it }
        state.conditions.forEach { progress ->
            repo.rawFile(state.dirPath, progress.condition).takeIf { it.exists() }?.let { ordered += it }
        }
        repo.logFile(state.dirPath).takeIf { it.exists() }?.let { ordered += it }
        return if (ordered.isEmpty()) dir.listFiles()?.sortedBy { it.name }.orEmpty() else ordered
    }

    /** 一个工况的实际采集时长（分钟），用于 1.3 的 duration_min。 */
    private fun durationMinutes(progress: ConditionProgress, nowMs: Long): Double {
        if (progress.startMs <= 0L) return 0.0
        val endMs = progress.endMs ?: nowMs
        val paused = progress.pausedTotalMs +
            (progress.pauseStartedMs?.let { (endMs - it).coerceAtLeast(0L) } ?: 0L)
        val net = (endMs - progress.startMs - paused).coerceAtLeast(0L)
        return net / 60_000.0
    }

    private fun summaryNote(state: CampaignState, progress: ConditionProgress): String {
        val parts = mutableListOf<String>()
        if (state.notes.isNotBlank()) parts += state.notes
        if (progress.earlyFinished) parts += "用户提前结束本工况"
        if (state.status == CampaignStatus.ABORTED) parts += "批次被放弃（部分数据）"
        if (state.rerunOf != null) parts += "补测批次，原批次 ${state.rerunOf}"
        return parts.joinToString("; ")
    }

    companion object {
        private const val TAG = "PowerTraceExport"

        /** 导出 ZIP 的建议文件名。 */
        fun zipDisplayName(state: CampaignState): String =
            String.format(Locale.US, "%s_%s.zip", File(state.dirPath).name, state.campaignId)
    }
}
