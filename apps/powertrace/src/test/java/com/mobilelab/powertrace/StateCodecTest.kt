package com.mobilelab.powertrace

import com.mobilelab.powertrace.data.CampaignEngine
import com.mobilelab.powertrace.data.CampaignStatus
import com.mobilelab.powertrace.data.ConditionId
import com.mobilelab.powertrace.data.StateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SharedPreferences 状态编解码（崩溃恢复依赖）。 */
class StateCodecTest {

    @Test
    fun roundTripKeepsAllFieldsIncludingTrickyNotes() {
        var state = CampaignEngine.create(
            campaignId = "PC-20261118-007",
            sessionId = "PT-20261118-007",
            dirPath = "E:/phone function/phone-lab/experiments/powertrace/20261118_101500_PC-20261118-007",
            notes = "备注含 | 竖线; 分号\n换行 \"引号\" 中文",
            order = listOf(ConditionId.GAME, ConditionId.IDLE, ConditionId.VIDEO, ConditionId.NAVCAM),
            nowMs = 1_730_000_000_000L,
            rawSeriesEnabled = true,
            rerunOf = "PC-20261117-002",
        )
        state = CampaignEngine.start(state, 1_730_000_100_000L)
        state = CampaignEngine.recordSample(state, 1_730_000_100_000L, 0L)
        state = CampaignEngine.pause(state, 1_730_000_200_000L)
        state = state.copy(lastTrafficRxBytes = 123_456L, lastTrafficTxBytes = 789L, lastTrafficAtMs = 1_730_000_200_000L)

        val encoded = StateCodec.encode(state)
        val decoded = StateCodec.decode(encoded)!!
        assertEquals(state, decoded)
        assertEquals("备注含 | 竖线; 分号\n换行 \"引号\" 中文", decoded.notes)
        assertEquals(listOf(ConditionId.GAME, ConditionId.IDLE, ConditionId.VIDEO, ConditionId.NAVCAM), decoded.order)
        assertEquals(CampaignStatus.PAUSED, decoded.status)
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(StateCodec.decode(null))
        assertNull(StateCodec.decode(""))
        assertNull(StateCodec.decode("not-a-state"))
        assertNull(StateCodec.decode("PTSTATE1\u001Fbad"))
    }
}
