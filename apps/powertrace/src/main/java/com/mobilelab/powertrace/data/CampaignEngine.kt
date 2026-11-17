package com.mobilelab.powertrace.data

/** 状态机在每个时刻的应做动作。 */
sealed interface DueAction {
    /** 到点采样；[lateMs] > [SamplingConfig.LATE_TOLERANCE_MS] 时需标记 late_sample。 */
    data class Sample(val sampleIndex: Int, val targetElapsedSec: Long, val lateMs: Long) : DueAction

    /** 采样点已满 6 个且满 30 min，收尾当前工况。 */
    data object CompleteCondition : DueAction

    /** 当前无动作（未到点或非 RUNNING）。 */
    data object Idle : DueAction
}

/**
 * 批次状态机。全部为纯函数：输入旧状态 + 当前时间，输出新状态，
 * 不做任何 IO，因此可以在 JVM 单元测试里完整跑 4×30 min。
 *
 * 关键规则（对应 spec）：
 *  - 采样点 elapsed = 0/300/600/900/1200/1500 s，第 1 点工况开始即采；
 *  - 第 6 点后仍需等到 elapsed ≥ 1800 s 才算满 30 min，然后进入下一工况；
 *  - 延迟 > 90 s 的采样照常写入并标记 late_sample，绝不静默丢弃；
 *  - 暂停期间 elapsed 不增长，恢复后不补采暂停期间的点（暂停不写入）。
 */
object CampaignEngine {

    fun create(
        campaignId: String,
        sessionId: String,
        dirPath: String,
        notes: String,
        order: List<ConditionId>,
        nowMs: Long,
        rawSeriesEnabled: Boolean = false,
        rerunOf: String? = null,
    ): CampaignState {
        require(order.isNotEmpty()) { "order must not be empty" }
        val conditions = order.mapIndexed { index, condition ->
            ConditionProgress(condition = condition, block = ConditionId.blockLetter(index))
        }
        return CampaignState(
            campaignId = campaignId,
            sessionId = sessionId,
            dirPath = dirPath,
            notes = notes,
            order = order,
            createdAtMs = nowMs,
            status = CampaignStatus.DRAFT,
            currentIndex = 0,
            conditions = conditions,
            rawSeriesEnabled = rawSeriesEnabled,
            rerunOf = rerunOf,
        )
    }

    /** DRAFT → RUNNING，并启动第一个工况（开始即采第 1 点）。 */
    fun start(state: CampaignState, nowMs: Long): CampaignState {
        if (state.status == CampaignStatus.RUNNING) return state
        if (state.conditions.isEmpty()) return state
        val conditions = state.conditions.toMutableList()
        val first = conditions[0]
        if (first.startMs <= 0L) {
            conditions[0] = first.copy(startMs = nowMs, endMs = null, earlyFinished = false)
        }
        return state.copy(status = CampaignStatus.RUNNING, conditions = conditions, finishedMs = null)
    }

    fun dueAction(state: CampaignState, nowMs: Long): DueAction {
        if (state.status != CampaignStatus.RUNNING) return DueAction.Idle
        val current = state.current ?: return DueAction.Idle
        if (current.completed) return DueAction.Idle
        if (current.startMs <= 0L) return DueAction.Idle
        val elapsed = current.elapsedMs(nowMs)
        if (current.samplesDone >= SamplingConfig.SAMPLES_PER_CONDITION) {
            return if (elapsed >= SamplingConfig.CONDITION_DURATION_MS) DueAction.CompleteCondition else DueAction.Idle
        }
        val targetMs = current.samplesDone * SamplingConfig.INTERVAL_MS
        // 允许 1 s 的提前量：Handler/闹钟回调到点时 elapsed 可能略小于名义值。
        if (elapsed + 1_000L < targetMs) return DueAction.Idle
        return DueAction.Sample(
            sampleIndex = current.samplesDone + 1,
            targetElapsedSec = SamplingConfig.elapsedSecForSample(current.samplesDone + 1),
            lateMs = (elapsed - targetMs).coerceAtLeast(0L),
        )
    }

    /** 记一次采样（无论迟到与否都写入）。 */
    fun recordSample(state: CampaignState, nowMs: Long, lateMs: Long = 0L): CampaignState {
        val index = state.currentIndex
        val current = state.current ?: return state
        val late = lateMs > SamplingConfig.LATE_TOLERANCE_MS
        val updated = current.copy(
            samplesDone = (current.samplesDone + 1).coerceAtMost(SamplingConfig.SAMPLES_PER_CONDITION),
            lateSamples = current.lateSamples + if (late) 1 else 0,
        )
        val conditions = state.conditions.toMutableList()
        conditions[index] = updated
        return state.copy(conditions = conditions, lastSampleAtMs = nowMs)
    }

    /**
     * 收尾当前工况：写入 endMs（含暂停扣除），然后启动下一工况或结束批次。
     * [early] = true 表示用户提前结束，已采数据保留。
     */
    fun completeCondition(state: CampaignState, nowMs: Long, early: Boolean = false): CampaignState {
        val index = state.currentIndex
        val current = state.current ?: return state
        val paused = current.pausedTotalMs +
            (current.pauseStartedMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L)
        val finished = current.copy(
            endMs = nowMs,
            pausedTotalMs = paused,
            pauseStartedMs = null,
            earlyFinished = early,
        )
        val conditions = state.conditions.toMutableList()
        conditions[index] = finished
        val nextIndex = index + 1
        return if (nextIndex > state.conditions.lastIndex) {
            state.copy(
                conditions = conditions,
                status = CampaignStatus.FINISHED,
                finishedMs = nowMs,
            )
        } else {
            val next = conditions[nextIndex]
            conditions[nextIndex] = next.copy(
                startMs = nowMs,
                endMs = null,
                earlyFinished = false,
                samplesDone = 0,
                pausedTotalMs = 0L,
                pauseStartedMs = null,
                lateSamples = 0,
            )
            state.copy(conditions = conditions, currentIndex = nextIndex, status = CampaignStatus.RUNNING)
        }
    }

    fun pause(state: CampaignState, nowMs: Long): CampaignState {
        if (state.status != CampaignStatus.RUNNING) return state
        val index = state.currentIndex
        val current = state.current ?: return state
        val conditions = state.conditions.toMutableList()
        conditions[index] = current.copy(pauseStartedMs = nowMs)
        return state.copy(conditions = conditions, status = CampaignStatus.PAUSED)
    }

    fun resume(state: CampaignState, nowMs: Long): CampaignState {
        if (state.status != CampaignStatus.PAUSED) return state
        val index = state.currentIndex
        val current = state.current ?: return state
        val pauseStart = current.pauseStartedMs ?: nowMs
        val conditions = state.conditions.toMutableList()
        conditions[index] = current.copy(
            pausedTotalMs = current.pausedTotalMs + (nowMs - pauseStart).coerceAtLeast(0L),
            pauseStartedMs = null,
        )
        return state.copy(conditions = conditions, status = CampaignStatus.RUNNING)
    }

    /** 用户放弃批次：保留已采数据，状态置 ABORTED，暂停计时归位。 */
    fun abort(state: CampaignState, nowMs: Long): CampaignState {
        if (state.finished) return state
        val index = state.currentIndex
        val current = state.current
        val conditions = state.conditions.toMutableList()
        if (current != null && !current.completed) {
            val paused = current.pausedTotalMs +
                (current.pauseStartedMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L)
            conditions[index] = current.copy(
                endMs = nowMs,
                pausedTotalMs = paused,
                pauseStartedMs = null,
                earlyFinished = true,
            )
        }
        return state.copy(conditions = conditions, status = CampaignStatus.ABORTED, finishedMs = nowMs)
    }

    /** 下一次需要唤醒的时间（采样或工况收尾）；无则 null。 */
    fun nextDueAtMs(state: CampaignState): Long? {
        if (state.status != CampaignStatus.RUNNING) return null
        return state.current?.nextDueAtMs()
    }

    /** 进程被杀/重启后的状态归一化：补齐缺失的时间戳，清掉悬空暂停。 */
    fun recover(state: CampaignState, nowMs: Long): CampaignState {
        if (state.conditions.isEmpty()) return state
        val conditions = state.conditions.toMutableList()
        var changed = false
        for (i in conditions.indices) {
            val c = conditions[i]
            var n = c
            if (state.status == CampaignStatus.RUNNING && n.startMs <= 0L && n.endMs == null) {
                n = n.copy(startMs = nowMs)
            }
            if (state.status == CampaignStatus.PAUSED && n.pauseStartedMs == null && n.endMs == null) {
                n = n.copy(pauseStartedMs = nowMs)
            }
            if (n.pauseStartedMs != null && state.status == CampaignStatus.RUNNING) {
                n = n.copy(pauseStartedMs = null)
            }
            if (n != c) {
                conditions[i] = n
                changed = true
            }
        }
        return if (changed) state.copy(conditions = conditions) else state
    }

    /** 距离下一次动作的毫秒数（UI 倒计时用）；无则 null。 */
    fun remainingMs(state: CampaignState, nowMs: Long): Long? =
        nextDueAtMs(state)?.let { (it - nowMs).coerceAtLeast(0L) }

    /** 下一个采样点序号（1..6）；工况已满返回 null。 */
    fun nextSampleIndex(state: CampaignState): Int? {
        val current = state.current ?: return null
        if (current.completed) return null
        if (current.samplesDone >= SamplingConfig.SAMPLES_PER_CONDITION) return null
        return current.samplesDone + 1
    }
}
