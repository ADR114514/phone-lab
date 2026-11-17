package com.mobilelab.powertrace

import com.mobilelab.powertrace.data.CampaignEngine
import com.mobilelab.powertrace.data.CampaignState
import com.mobilelab.powertrace.data.CampaignStatus
import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.DueAction
import com.mobilelab.powertrace.data.SamplingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批次状态机：4×6 点、暂停、迟到、提前结束、放弃、崩溃恢复。 */
class CampaignStateMachineTest {

    /** 真实 wall clock 量级（避免 0 与"未开始"哨兵值混淆）。 */
    private val base = 1_730_000_000_000L

    private fun newState(): CampaignState = CampaignEngine.create(
        campaignId = "PC-20261118-001",
        sessionId = "PT-20261118-001",
        dirPath = "/tmp/powertrace",
        notes = "",
        order = ConditionId.defaultOrder,
        nowMs = base,
    )

    private fun runToCompletion(initial: CampaignState): CampaignState {
        var state = initial
        var now = state.createdAtMs
        var guard = 0
        while (state.status != CampaignStatus.FINISHED && guard++ < 128) {
            when (val action = CampaignEngine.dueAction(state, now)) {
                is DueAction.Sample -> state = CampaignEngine.recordSample(state, now, action.lateMs)
                DueAction.CompleteCondition -> state = CampaignEngine.completeCondition(state, now)
                DueAction.Idle -> {
                    val next = CampaignEngine.nextDueAtMs(state) ?: break
                    now = next
                }
            }
        }
        return state
    }

    @Test
    fun fullCampaign_fourConditionsSixSamplesEach_is24Rows() {
        val finished = runToCompletion(CampaignEngine.start(newState(), base))
        assertEquals(CampaignStatus.FINISHED, finished.status)
        assertEquals(24, finished.totalSamplesDone)
        assertEquals(4, finished.completedConditions)
        finished.conditions.forEach { condition ->
            assertEquals(6, condition.samplesDone)
            assertNotNull(condition.endMs)
            assertTrue(condition.elapsedMs(condition.endMs!!) >= SamplingConfig.CONDITION_DURATION_MS - 1_000L)
        }
        assertEquals(listOf('A', 'B', 'C', 'D'), finished.conditions.map { it.block })
        assertNotNull(finished.finishedMs)
    }

    @Test
    fun firstSampleIsDueImmediatelyAtConditionStart() {
        val started = CampaignEngine.start(newState(), base)
        val action = CampaignEngine.dueAction(started, base)
        assertTrue(action is DueAction.Sample)
        action as DueAction.Sample
        assertEquals(1, action.sampleIndex)
        assertEquals(0L, action.targetElapsedSec)
        assertEquals(0L, action.lateMs)
        val after = CampaignEngine.recordSample(started, base, action.lateMs)
        assertTrue(CampaignEngine.dueAction(after, base + 1_000L) is DueAction.Idle)
    }

    @Test
    fun sampleElapsedAreMultiplesOf300() {
        var state = CampaignEngine.start(newState(), base)
        var now = base
        val elapsed = mutableListOf<Long>()
        var guard = 0
        while (state.totalSamplesDone < 6 && guard++ < 32) {
            when (val action = CampaignEngine.dueAction(state, now)) {
                is DueAction.Sample -> {
                    elapsed += action.targetElapsedSec
                    state = CampaignEngine.recordSample(state, now, action.lateMs)
                }
                DueAction.CompleteCondition -> error("不应在 6 点之前收尾")
                DueAction.Idle -> now = CampaignEngine.nextDueAtMs(state) ?: break
            }
        }
        assertEquals(listOf(0L, 300L, 600L, 900L, 1200L, 1500L), elapsed)
    }

    @Test
    fun lateSampleIsCountedNotDropped() {
        var state = CampaignEngine.start(newState(), base)
        state = CampaignEngine.recordSample(state, base, 0L)
        // 名义 300 s，实际 420 s 才采 → 迟到 120 s > 90 s
        val now = base + 420_000L
        val action = CampaignEngine.dueAction(state, now) as DueAction.Sample
        assertEquals(300L, action.targetElapsedSec)
        assertEquals(120_000L, action.lateMs)
        state = CampaignEngine.recordSample(state, now, action.lateMs)
        assertEquals(2, state.current!!.samplesDone)
        assertEquals(1, state.current!!.lateSamples)
    }

    @Test
    fun pauseAndResume_shiftScheduleByPausedDuration() {
        var state = CampaignEngine.start(newState(), base)
        state = CampaignEngine.recordSample(state, base, 0L)
        state = CampaignEngine.pause(state, base + 60_000L)
        assertEquals(CampaignStatus.PAUSED, state.status)
        assertNull(CampaignEngine.nextDueAtMs(state))
        state = CampaignEngine.resume(state, base + 120_000L)
        assertEquals(CampaignStatus.RUNNING, state.status)
        assertEquals(60_000L, state.current!!.pausedTotalMs)
        // 第 2 点名义应在 start + 60s(暂停) + 300s = base + 360_000
        assertEquals(base + 360_000L, CampaignEngine.nextDueAtMs(state))
        assertEquals(300_000L, state.current!!.elapsedMs(base + 360_000L))
    }

    @Test
    fun skipCondition_keepsSamplesAndAdvances() {
        var state = CampaignEngine.start(newState(), base)
        state = CampaignEngine.recordSample(state, base, 0L)
        state = CampaignEngine.recordSample(state, base + 300_000L, 0L)
        state = CampaignEngine.completeCondition(state, base + 600_000L, early = true)
        assertEquals(1, state.currentIndex)
        assertTrue(state.conditions[0].earlyFinished)
        assertEquals(2, state.conditions[0].samplesDone)
        assertEquals(CampaignStatus.RUNNING, state.status)
        assertEquals(base + 600_000L, state.conditions[1].startMs)
        assertEquals(0, state.conditions[1].samplesDone)
    }

    @Test
    fun abort_marksCurrentConditionEarlyAndStopsScheduling() {
        var state = CampaignEngine.start(newState(), base)
        state = CampaignEngine.recordSample(state, base, 0L)
        state = CampaignEngine.abort(state, base + 90_000L)
        assertEquals(CampaignStatus.ABORTED, state.status)
        assertTrue(state.conditions[0].earlyFinished)
        assertNull(CampaignEngine.nextDueAtMs(state))
        assertTrue(state.finished)
    }

    @Test
    fun recover_fixesMissingStartTimestampAfterProcessDeath() {
        val broken = CampaignEngine.start(newState(), base).let { state ->
            state.copy(conditions = state.conditions.mapIndexed { index, condition ->
                if (index == 0) condition.copy(startMs = 0L) else condition
            })
        }
        val recovered = CampaignEngine.recover(broken, base + 5_000L)
        assertEquals(base + 5_000L, recovered.conditions[0].startMs)
        assertEquals(recovered, CampaignEngine.recover(recovered, base + 9_000L))
    }

    @Test
    fun nextDueAtMs_isNullWhenNotRunning() {
        assertNull(CampaignEngine.nextDueAtMs(newState()))
        val paused = CampaignEngine.pause(CampaignEngine.start(newState(), base), base + 1_000L)
        assertNull(CampaignEngine.nextDueAtMs(paused))
    }
}
