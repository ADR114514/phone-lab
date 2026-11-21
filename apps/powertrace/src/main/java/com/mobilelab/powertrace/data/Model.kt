package com.mobilelab.powertrace.data

/** 批次（campaign）状态机状态。 */
enum class CampaignStatus {
    /** 已创建但尚未开始采集。 */
    DRAFT,

    /** 正在采集。 */
    RUNNING,

    /** 用户暂停，暂停期间不写入任何数据。 */
    PAUSED,

    /** 4 个工况全部完成。 */
    FINISHED,

    /** 用户放弃批次，已采数据保留。 */
    ABORTED,
    ;

    val label: String
        get() = when (this) {
            DRAFT -> "待开始"
            RUNNING -> "采集中"
            PAUSED -> "已暂停"
            FINISHED -> "已完成"
            ABORTED -> "已放弃"
        }

    val active: Boolean get() = this == RUNNING || this == PAUSED
}

/**
 * 单个工况的进度。所有时间均为 Unix epoch ms（wall clock），便于跨进程/重启恢复。
 *
 * elapsedMs 的语义：从工况开始到 now 的"净采集时间"，会扣除暂停时长。
 */
data class ConditionProgress(
    val condition: ConditionId,
    val block: Char,
    val samplesDone: Int = 0,
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val pausedTotalMs: Long = 0L,
    val pauseStartedMs: Long? = null,
    val earlyFinished: Boolean = false,
    val lateSamples: Int = 0,
) {
    val completed: Boolean get() = endMs != null || earlyFinished

    fun elapsedMs(nowMs: Long): Long {
        if (startMs <= 0L) return 0L
        val paused = pausedTotalMs + if (pauseStartedMs != null) (nowMs - pauseStartedMs).coerceAtLeast(0L) else 0L
        return (nowMs - startMs - paused).coerceAtLeast(0L)
    }

    /** 下一次采样（或工况收尾）的 wall clock 时间；无意义状态返回 null。 */
    fun nextDueAtMs(): Long? {
        if (startMs <= 0L || completed) return null
        val targetMs = if (samplesDone >= SamplingConfig.SAMPLES_PER_CONDITION) {
            SamplingConfig.CONDITION_DURATION_MS
        } else {
            samplesDone * SamplingConfig.INTERVAL_MS
        }
        return startMs + pausedTotalMs + targetMs
    }

    val samplesRemaining: Int
        get() = (SamplingConfig.SAMPLES_PER_CONDITION - samplesDone).coerceAtLeast(0)
}

/** 一个批次的完整持久化状态。 */
data class CampaignState(
    val campaignId: String,
    val sessionId: String,
    val dirPath: String,
    val notes: String,
    val order: List<ConditionId>,
    val createdAtMs: Long,
    val status: CampaignStatus = CampaignStatus.DRAFT,
    val currentIndex: Int = 0,
    val conditions: List<ConditionProgress>,
    val rawSeriesEnabled: Boolean = false,
    val finishedMs: Long? = null,
    /** 补测批次记录原始批次 ID（单工况重测时使用）。 */
    val rerunOf: String? = null,
    val lastTrafficRxBytes: Long? = null,
    val lastTrafficTxBytes: Long? = null,
    val lastTrafficAtMs: Long? = null,
    val lastSampleAtMs: Long? = null,
    val lastNote: String = "",
) {
    val current: ConditionProgress? get() = conditions.getOrNull(currentIndex)

    val completedConditions: Int get() = conditions.count { it.completed }

    val totalSamplesDone: Int get() = conditions.sumOf { it.samplesDone }

    val isFullCampaign: Boolean get() = order.size == 4

    val finished: Boolean get() = status == CampaignStatus.FINISHED || status == CampaignStatus.ABORTED
}

/** 一个 5 min 采样点的完整快照（对应数据字典 1.1 一行）。 */
data class SessionSample(
    val sampleIndex: Int,
    val elapsedSec: Long,
    val timestampMs: Long,
    val batteryLevelPct: Int? = null,
    val batteryVoltageMv: Int? = null,
    val batteryTempC: Double? = null,
    val currentNowUa: Int? = null,
    val chargeCounterUah: Int? = null,
    /** nWh 量级可超过 Int 上限（15 Wh = 1.5e10 nWh），必须用 Long。 */
    val energyCounterNwh: Long? = null,
    val thermalStatus: Int? = null,
    val cpuTempC: Double? = null,
    val cpuFreqAvgMhz: Int? = null,
    val cpuFreqMaxMhz: Int? = null,
    val batteryCycleCount: Int? = null,
    val batteryHealth: Int? = null,
    val charging: Boolean = false,
    val screenOn: Boolean = false,
    val screenBrightnessPct: Int? = null,
    val ambientLux: Double? = null,
    val netType: String = "NONE",
    val netRxKbps: Double? = null,
    val netTxKbps: Double? = null,
    val memAvailableMb: Int? = null,
    val memTotalMb: Int? = null,
    val fps: Double? = null,
    val foregroundPkg: String? = null,
    val note: String = "",
    /** 由电荷计差分或可信电流算出的功率（W）；为空表示本点无法得到可信功率。 */
    val computedPowerW: Double? = null,
    /** charge_counter / current_now / null（不可用）。 */
    val powerSource: String? = null,
) {
    /**
     * 功率优先用 [computedPowerW]（电荷计差分）。
     * 仅当电流量级可信（≥2 mA）时才回退到 voltage × |current|；
     * 否则返回 null（CSV 留空），避免把 -26 µA 算成 0.000 W 的假数据。
     */
    val powerW: Double? get() = computedPowerW
        ?: if (PowerMath.isCurrentPlausible(currentNowUa)) PowerMath.powerW(batteryVoltageMv, currentNowUa) else null
}

/** 原始 5 s 序列的一行（数据字典 1.4）。 */
data class RawSample(
    val elapsedSec: Long,
    val timestampMs: Long,
    val batteryLevelPct: Int? = null,
    val currentNowUa: Int? = null,
    val voltageMv: Int? = null,
    val batteryTempC: Double? = null,
    val thermalStatus: Int? = null,
    val cpuTempC: Double? = null,
    val cpuFreqAvgMhz: Int? = null,
    val screenOn: Boolean = false,
    val fps: Double? = null,
    val netRxKbps: Double? = null,
    val netTxKbps: Double? = null,
)
