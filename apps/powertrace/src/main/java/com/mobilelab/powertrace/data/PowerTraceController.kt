package com.mobilelab.powertrace.data

import android.annotation.SuppressLint
import android.content.Context
import com.mobilelab.core.log.LabLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 单次 pump 的结果，服务据此决定是否继续调度。 */
sealed interface PumpOutcome {
    data class Sampled(val state: CampaignState, val sample: SessionSample) : PumpOutcome
    data class ConditionCompleted(val state: CampaignState, val condition: ConditionId) : PumpOutcome
    data class CampaignFinished(val state: CampaignState) : PumpOutcome
    data object Idle : PumpOutcome
    data object NotRunning : PumpOutcome
}

/**
 * 采集编排中枢：状态机推进 + 指标采集 + CSV 追加写 + 派生文件生成。
 *
 * 进程内单例，UI 与 SamplingService 共用（同一进程），状态通过 StateFlow 广播。
 * 所有公开 suspend 方法都在 IO 线程执行，并在 [mutex] 内串行化，
 * 因此 Handler 二次触发与 AlarmManager 唤醒同时到达也不会重复写行。
 */
object PowerTraceController {

    private const val TAG = "PowerTraceCtrl"

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var repo: TraceRepository

    // 进程内单例只持有 applicationContext（见 init），不会泄漏 Activity。
    @SuppressLint("StaticFieldLeak")
    private lateinit var metricsCollector: MetricsCollector
    private lateinit var exporter: ExportManager

    private val mutex = Mutex()

    private val _state = MutableStateFlow<CampaignState?>(null)
    val state: StateFlow<CampaignState?> = _state.asStateFlow()

    private val _latestSample = MutableStateFlow<SessionSample?>(null)
    val latestSample: StateFlow<SessionSample?> = _latestSample.asStateFlow()

    private val _conditionSamples = MutableStateFlow<List<SessionSample>>(emptyList())
    val conditionSamples: StateFlow<List<SessionSample>> = _conditionSamples.asStateFlow()

    private val _statusLine = MutableStateFlow("就绪")
    val statusLine: StateFlow<String> = _statusLine.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            repo = TraceRepository(appContext)
            metricsCollector = MetricsCollector(appContext)
            exporter = ExportManager(appContext)
            initialized = true
        }
    }

    private fun checkInitialized() {
        check(initialized) { "PowerTraceController.init() must be called first" }
    }

    // ------------------------------------------------------------------ 状态加载

    /** App/服务启动时从 SharedPreferences 恢复并做崩溃归一化（幂等）。 */
    suspend fun loadFromDisk(): CampaignState? = withContext(Dispatchers.IO) {
        checkInitialized()
        val loaded = repo.loadState()
        if (loaded == null) {
            _state.value = null
            _latestSample.value = null
            _conditionSamples.value = emptyList()
            return@withContext null
        }
        val recovered = CampaignEngine.recover(loaded, System.currentTimeMillis())
        if (recovered != loaded) {
            repo.saveState(recovered)
            LabLog.w(TAG, "campaign ${recovered.campaignId} state normalised after restart")
        }
        _state.value = recovered
        if (recovered.status.active) {
            repo.attachLog(recovered.dirPath)
            refreshHistory(recovered)
        }
        recovered
    }

    // ------------------------------------------------------------------ 批次生命周期

    /**
     * 新建批次：生成 PT/PC 编号、创建会话目录、写 manifest、置为 DRAFT。
     * 真正的采集由 [startCampaign] + SamplingService 启动。
     */
    suspend fun createCampaign(
        notes: String,
        order: List<ConditionId>,
        rawSeriesEnabled: Boolean,
        rerunOf: String? = null,
    ): CampaignState = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            createCampaignLocked(notes, order, rawSeriesEnabled, rerunOf)
        }
    }

    private suspend fun createCampaignLocked(
        notes: String,
        order: List<ConditionId>,
        rawSeriesEnabled: Boolean,
        rerunOf: String?,
    ): CampaignState {
        require(order.isNotEmpty()) { "至少选择一个工况" }
        val now = System.currentTimeMillis()
        val seq = repo.nextIdSequence(now)
        val sessionId = repo.formatId("PT", now, seq)
        val campaignId = repo.formatId("PC", now, seq)
        val dir = repo.createSessionDir(campaignId)
        var state = CampaignEngine.create(
            campaignId = campaignId,
            sessionId = sessionId,
            dirPath = dir.absolutePath,
            notes = notes.trim(),
            order = order,
            nowMs = now,
            rawSeriesEnabled = rawSeriesEnabled,
            rerunOf = rerunOf,
        )
        repo.saveState(state)
        repo.attachLog(state.dirPath)
        repo.writeManifest(state)
        LabLog.i(TAG, "campaign $campaignId created at ${dir.name}, order=${order.joinToString(",") { it.id }}")
        LabLog.i(TAG, "sampling interval 300 s, 6 points/condition, 30 min/condition; current sign convention: raw kept, power uses abs()")
        runCatching {
            val access = SystemAccess.snapshot(appContext)
            LabLog.i(
                TAG,
                "access: notifications=${access.notifications} exactAlarm=${access.exactAlarm} " +
                    "batteryUnrestricted=${access.batteryUnrestricted} usageStats=${access.usageStats} " +
                    "charging=${access.charging} autoBrightness=${access.autoBrightness} network=${access.network} " +
                    "location=${access.locationEnabled}",
            )
        }.onFailure { LabLog.w(TAG, "access snapshot failed: ${it.message}") }
        _state.value = state
        _latestSample.value = null
        _conditionSamples.value = emptyList()
        _statusLine.value = "批次 $campaignId 已创建"
        state = CampaignEngine.start(state, now)
        repo.saveState(state)
        repo.writeManifest(state)
        _state.value = state
        _statusLine.value = "开始采集：${state.current?.condition?.label ?: ""}"
        return state
    }

    /** DRAFT → RUNNING（若已 RUNNING 则幂等返回）。 */
    suspend fun startCampaign(): CampaignState? = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            val state = _state.value ?: repo.loadState() ?: return@withLock null
            if (state.status == CampaignStatus.RUNNING) return@withLock state
            if (state.status != CampaignStatus.DRAFT) return@withLock state
            val now = System.currentTimeMillis()
            val started = CampaignEngine.start(state, now)
            repo.saveState(started)
            repo.writeManifest(started)
            _state.value = started
            _statusLine.value = "开始采集：${started.current?.condition?.label ?: ""}"
            LabLog.i(TAG, "campaign ${started.campaignId} started")
            started
        }
    }

    /** 恢复运行（PAUSED → RUNNING）。 */
    suspend fun resumeCampaign(): CampaignState? = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            val state = _state.value ?: repo.loadState() ?: return@withLock null
            val now = System.currentTimeMillis()
            val resumed = CampaignEngine.resume(state, now)
            if (resumed == state) return@withLock state
            repo.saveState(resumed)
            repo.writeManifest(resumed)
            _state.value = resumed
            refreshHistory(resumed)
            _statusLine.value = "已继续采集"
            LabLog.i(TAG, "campaign ${state.campaignId} resumed")
            resumed
        }
    }

    /** 暂停：暂停期间不写入任何数据（elapsed 不增长）。 */
    suspend fun pauseCampaign(): CampaignState? = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            val state = _state.value ?: repo.loadState() ?: return@withLock null
            val now = System.currentTimeMillis()
            val paused = CampaignEngine.pause(state, now)
            if (paused == state) return@withLock state
            repo.saveState(paused)
            repo.writeManifest(paused)
            _state.value = paused
            _statusLine.value = "已暂停（不写入）"
            LabLog.i(TAG, "campaign ${state.campaignId} paused")
            paused
        }
    }

    /** 提前结束当前工况（保留已采数据），随后由服务立即进入下一工况。 */
    suspend fun skipCurrentCondition(): CampaignState? = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            var state = _state.value ?: repo.loadState() ?: return@withLock null
            val now = System.currentTimeMillis()
            if (state.status == CampaignStatus.PAUSED) {
                state = CampaignEngine.resume(state, now)
            }
            if (state.status != CampaignStatus.RUNNING) return@withLock state
            val skipped = state.current?.condition
            state = CampaignEngine.completeCondition(state, now, early = true)
            repo.saveState(state)
            _state.value = state
            LabLog.w(TAG, "condition ${skipped?.id} ended early by user (kept ${skipped?.let { _conditionSamples.value.size } ?: 0} samples)")
            if (state.status == CampaignStatus.FINISHED) {
                finalizeCampaign(state)
            } else {
                refreshHistory(state)
                repo.writeManifest(state)
                _statusLine.value = "已切换到下一工况：${state.current?.condition?.label ?: ""}"
            }
            state
        }
    }

    /** 放弃批次：数据保留，状态置 ABORTED，并生成可导出的部分数据。 */
    /**
     * 删除当前批次：会话目录（CSV/log/manifest）+ 持久化状态。
     * 采集中（RUNNING）禁止删除，避免服务仍在写文件；先暂停或放弃再删。
     */
    suspend fun deleteCampaignData(): Boolean = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            val state = _state.value ?: repo.loadState() ?: return@withLock false
            if (state.status == CampaignStatus.RUNNING) return@withLock false
            val deleted = repo.deleteSessionDir(state.dirPath)
            repo.clearCampaignState()
            _state.value = null
            _latestSample.value = null
            _conditionSamples.value = emptyList()
            _statusLine.value = ""
            LabLog.i(TAG, "campaign data deleted: " + state.campaignId + " dir=" + deleted)
            deleted
        }
    }

    suspend fun abortCampaign(): CampaignState? = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            val state = _state.value ?: repo.loadState() ?: return@withLock null
            if (state.finished) return@withLock state
            val now = System.currentTimeMillis()
            val aborted = CampaignEngine.abort(state, now)
            repo.saveState(aborted)
            _state.value = aborted
            runCatching { exporter.writeDerived(aborted) }
                .onFailure { LabLog.e(TAG, "abort: derived files failed", it) }
            repo.writeManifest(aborted)
            _statusLine.value = "批次已放弃（已采数据保留）"
            LabLog.w(TAG, "campaign ${state.campaignId} aborted")
            aborted
        }
    }

    /** 补测：以单工况新建一个批次，原批次 ID 记入 rerunOf。 */
    suspend fun createRerunCampaign(condition: ConditionId, notes: String): CampaignState {
        val current = _state.value ?: loadFromDisk()
        val extra = "补测 ${condition.label}" + (current?.let { "（原批次 ${it.campaignId}）" } ?: "")
        val merged = listOf(notes.trim(), extra).filter { it.isNotEmpty() }.joinToString("; ")
        return createCampaign(
            notes = merged,
            order = listOf(condition),
            rawSeriesEnabled = repo.isRawSeriesEnabled(),
            rerunOf = current?.campaignId,
        )
    }

    // ------------------------------------------------------------------ 采集推进

    /**
     * 推进状态机直到没有到期动作：
     * 采样本点 → 写 CSV/manifest/session.log → 若工况满 6 点且满 30 min 则收尾 → 下一工况立即采第 1 点。
     *
     * 幂等：同一点不会被写两次（Handler 与 AlarmManager 双触发安全）。
     */
    suspend fun pump(): PumpOutcome = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            var state = _state.value ?: repo.loadState() ?: return@withLock PumpOutcome.NotRunning
            if (state.status != CampaignStatus.RUNNING) return@withLock PumpOutcome.NotRunning
            var outcome: PumpOutcome = PumpOutcome.Idle
            var guard = 0
            while (guard++ < 8) {
                val now = System.currentTimeMillis()
                when (val action = CampaignEngine.dueAction(state, now)) {
                    is DueAction.Sample -> {
                        val result = performSample(state, action, now)
                        state = result.first
                        outcome = PumpOutcome.Sampled(state, result.second)
                    }

                    DueAction.CompleteCondition -> {
                        val finishedCondition = state.current?.condition
                        state = CampaignEngine.completeCondition(state, now, early = false)
                        repo.saveState(state)
                        _state.value = state
                        LabLog.i(TAG, "condition ${finishedCondition?.id} completed (30 min)")
                        if (state.status == CampaignStatus.FINISHED) {
                            finalizeCampaign(state)
                            return@withLock PumpOutcome.CampaignFinished(state)
                        }
                        refreshHistory(state)
                        repo.writeManifest(state)
                        _statusLine.value = "工况 ${finishedCondition?.label} 完成，进入 ${state.current?.condition?.label}"
                        outcome = PumpOutcome.ConditionCompleted(
                            state = state,
                            condition = finishedCondition ?: ConditionId.IDLE,
                        )
                    }

                    DueAction.Idle -> return@withLock outcome
                }
            }
            outcome
        }
    }

    private suspend fun performSample(
        state: CampaignState,
        action: DueAction.Sample,
        nowMs: Long,
    ): Pair<CampaignState, SessionSample> = withContext(Dispatchers.IO) {
        val condition = state.current?.condition ?: error("no current condition")
        val raw = metricsCollector.collect()

        // 网络速率用 TrafficStats 差分 / 实际间隔；首个样本没有上一基准，写空。
        val trafficElapsedMs = state.lastTrafficAtMs?.let { nowMs - it }
        val rxKbps = if (trafficElapsedMs != null && trafficElapsedMs > 0L) {
            val delta = if (state.lastTrafficRxBytes != null && raw.totalRxBytes != null) {
                raw.totalRxBytes - state.lastTrafficRxBytes
            } else {
                null
            }
            PowerMath.kbps(delta, trafficElapsedMs)
        } else {
            null
        }
        val txKbps = if (trafficElapsedMs != null && trafficElapsedMs > 0L) {
            val delta = if (state.lastTrafficTxBytes != null && raw.totalTxBytes != null) {
                raw.totalTxBytes - state.lastTrafficTxBytes
            } else {
                null
            }
            PowerMath.kbps(delta, trafficElapsedMs)
        } else {
            null
        }

        // 功率口径：优先电荷计差分（部分机型 CURRENT_NOW 只有 µA 级噪声，例如 OnePlus 12）。
        val previousCharge = repo.lastChargeSnapshot()
        var computedPowerW: Double? = null
        var powerSource: String? = null
        if (raw.chargeCounterUah != null && raw.batteryVoltageMv != null && previousCharge != null) {
            val dtMs = nowMs - previousCharge.timeMs
            if (dtMs in 60_000L..3_600_000L) {
                val deltaUah = previousCharge.chargeUah - raw.chargeCounterUah.toLong()
                val fromCharge = PowerMath.powerWFromCharge(deltaUah, raw.batteryVoltageMv, dtMs)
                if (fromCharge != null && fromCharge in 0.0..30.0) {
                    computedPowerW = fromCharge
                    powerSource = "charge_counter"
                }
            }
        }
        if (computedPowerW == null && PowerMath.isCurrentPlausible(raw.currentNowUa)) {
            computedPowerW = PowerMath.powerW(raw.batteryVoltageMv, raw.currentNowUa)
            powerSource = "current_now"
        }
        raw.chargeCounterUah?.let { repo.saveChargeSnapshot(it.toLong(), nowMs, raw.batteryVoltageMv ?: 0) }

        val late = action.lateMs > SamplingConfig.LATE_TOLERANCE_MS
        val flags = buildList {
            if (late) add("late_sample(+${action.lateMs / 1000}s)")
            if (raw.charging) add("charging_during_sample")
            if (condition == ConditionId.IDLE && raw.screenOn) add("screen_on_expected_off")
            if (computedPowerW == null) add("power_unavailable(charge_delta_pending_or_current_implausible)")
            else if (powerSource != null) add("power_source=" + powerSource)
            if (raw.unavailable.isNotEmpty()) add("unavailable:" + raw.unavailable.joinToString("/"))
        }
        val note = (flags + listOf(state.notes)).filter { it.isNotBlank() }.joinToString("; ")

        val sample = SessionSample(
            sampleIndex = action.sampleIndex,
            elapsedSec = action.targetElapsedSec,
            timestampMs = nowMs,
            batteryLevelPct = raw.batteryLevelPct,
            batteryVoltageMv = raw.batteryVoltageMv,
            batteryTempC = raw.batteryTempC,
            currentNowUa = raw.currentNowUa,
            chargeCounterUah = raw.chargeCounterUah,
            energyCounterNwh = raw.energyCounterNwh,
            thermalStatus = raw.thermalStatus,
            cpuTempC = raw.cpuTempC,
            cpuFreqAvgMhz = raw.cpuFreqAvgMhz,
            cpuFreqMaxMhz = raw.cpuFreqMaxMhz,
            batteryCycleCount = raw.batteryCycleCount,
            batteryHealth = raw.batteryHealth,
            charging = raw.charging,
            screenOn = raw.screenOn,
            screenBrightnessPct = raw.screenBrightnessPct,
            ambientLux = raw.ambientLux,
            netType = raw.netType,
            netRxKbps = rxKbps,
            netTxKbps = txKbps,
            memAvailableMb = raw.memAvailableMb,
            memTotalMb = raw.memTotalMb,
            fps = raw.fps,
            foregroundPkg = raw.foregroundPkg,
            note = note,
            computedPowerW = computedPowerW,
            powerSource = powerSource,
        )

        repo.appendSessionSample(state, condition, sample)
        LabLog.i(
            TAG,
            "sample #${sample.sampleIndex} ${condition.id} elapsed=${sample.elapsedSec}s " +
                "power=${Fmt.dec3(sample.powerW) ?: "n/a"}W temp=${Fmt.dec1(sample.batteryTempC) ?: "n/a"}C " +
                "thermal=${sample.thermalStatus ?: -1} current=${sample.currentNowUa ?: 0}uA" +
                if (late) " LATE ${action.lateMs / 1000}s" else "",
        )

        var updated = CampaignEngine.recordSample(state, nowMs, action.lateMs)
        updated = updated.copy(
            lastTrafficRxBytes = raw.totalRxBytes ?: state.lastTrafficRxBytes,
            lastTrafficTxBytes = raw.totalTxBytes ?: state.lastTrafficTxBytes,
            lastTrafficAtMs = nowMs,
            lastNote = note,
        )
        repo.saveState(updated)
        repo.writeManifest(updated)
        _state.value = updated
        _latestSample.value = sample
        _conditionSamples.value = (_conditionSamples.value + sample).sortedBy { it.sampleIndex }
        _statusLine.value = "已写入第 ${sample.sampleIndex}/${SamplingConfig.SAMPLES_PER_CONDITION} 点（${condition.label}）"
        updated to sample
    }

    /**
     * 原始 5 s 序列：由服务在工况运行期间定时调用（默认关闭）。
     * 仅在设备醒着时才能写入，属数据字典 1.4 的"可选"补充，不作为验收依据。
     */
    suspend fun sampleRawTick(): Boolean = withContext(Dispatchers.IO) {
        checkInitialized()
        mutex.withLock {
            val state = _state.value ?: return@withLock false
            if (state.status != CampaignStatus.RUNNING || !state.rawSeriesEnabled) return@withLock false
            val current = state.current ?: return@withLock false
            if (current.completed) return@withLock false
            val raw = metricsCollector.collect()
            val sample = RawSample(
                elapsedSec = current.elapsedMs(System.currentTimeMillis()) / 1000L,
                timestampMs = System.currentTimeMillis(),
                batteryLevelPct = raw.batteryLevelPct,
                currentNowUa = raw.currentNowUa,
                voltageMv = raw.batteryVoltageMv,
                batteryTempC = raw.batteryTempC,
                thermalStatus = raw.thermalStatus,
                cpuTempC = raw.cpuTempC,
                cpuFreqAvgMhz = raw.cpuFreqAvgMhz,
                screenOn = raw.screenOn,
                fps = raw.fps,
                netRxKbps = null,
                netTxKbps = null,
            )
            runCatching { repo.appendRawSample(state, current.condition, sample) }
                .onFailure { LabLog.e(TAG, "append raw sample failed", it) }
            true
        }
    }

    private fun finalizeCampaign(state: CampaignState) {
        runCatching {
            val bundle = exporter.writeDerived(state)
            val rows = bundle.samplesByCondition.values.sumOf { it.size }
            LabLog.i(TAG, "campaign ${state.campaignId} finished: ${bundle.summaries.size} conditions, $rows sampled rows")
        }.onFailure { LabLog.e(TAG, "finalize campaign failed", it) }
        repo.writeManifest(state)
        _statusLine.value = "批次完成：${state.conditions.count { it.completed }}/${state.conditions.size} 工况"
    }

    private fun refreshHistory(state: CampaignState) {
        val current = state.current
        if (current == null) {
            _conditionSamples.value = emptyList()
            _latestSample.value = null
            return
        }
        val samples = repo.readSamples(state.dirPath, current.condition)
        _conditionSamples.value = samples
        _latestSample.value = samples.lastOrNull()
    }

    // ------------------------------------------------------------------ 结果 / 导出

    suspend fun loadResults(writeDerived: Boolean = false): ExportManager.ResultsBundle? =
        withContext(Dispatchers.IO) {
            checkInitialized()
            val state = _state.value ?: repo.loadState() ?: return@withContext null
            if (writeDerived) {
                runCatching { exporter.writeDerived(state) }
                    .onFailure { LabLog.e(TAG, "writeDerived failed", it) }
            }
            exporter.loadBundle(state)
        }

    suspend fun zipBundle(): File? = withContext(Dispatchers.IO) {
        checkInitialized()
        val state = _state.value ?: repo.loadState() ?: return@withContext null
        exporter.zipBundle(state)
    }

    fun exportableFiles(): List<File> {
        checkInitialized()
        val state = _state.value ?: return emptyList()
        return exporter.exportableFiles(state)
    }

    fun sessionDirPath(): String? = _state.value?.dirPath

    fun nextDueAtMs(): Long? = _state.value?.let { CampaignEngine.nextDueAtMs(it) }

    fun isRawSeriesEnabled(): Boolean = if (initialized) repo.isRawSeriesEnabled() else false

    fun isStandbyGuardEnabled(): Boolean = repo.isStandbyGuardEnabled()

    fun setStandbyGuardEnabled(enabled: Boolean) = repo.setStandbyGuardEnabled(enabled)

    fun isOemBackgroundConfirmed(): Boolean = if (initialized) repo.isOemBackgroundConfirmed() else false

    fun setOemBackgroundConfirmed(value: Boolean) = repo.setOemBackgroundConfirmed(value)

    fun isOemSleepConfirmed(): Boolean = if (initialized) repo.isOemSleepConfirmed() else false

    fun setOemSleepConfirmed(value: Boolean) = repo.setOemSleepConfirmed(value)

    fun setRawSeriesEnabled(enabled: Boolean) {
        checkInitialized()
        repo.setRawSeriesEnabled(enabled)
    }

    fun deviceSnapshotInfo() = if (initialized) repo.deviceSnapshot() else null

    /** 供设置/设置页展示的数据根目录。 */
    fun experimentsRoot(): File? = if (initialized) {
        runCatching { com.mobilelab.core.files.Storage.appRoot(appContext, "powertrace") }.getOrNull()
    } else {
        null
    }
}
