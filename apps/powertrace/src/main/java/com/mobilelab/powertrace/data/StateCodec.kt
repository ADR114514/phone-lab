package com.mobilelab.powertrace.data

import java.util.Base64

/**
 * [CampaignState] 的持久化编码（SharedPreferences 单个 key 存储）。
 *
 * 用不可见分隔符 + Base64 编码自由文本（备注、目录路径），避免用户输入里的
 * `|` / `;` / 换行破坏解析；版本号前缀便于未来迁移。
 * 纯 JVM 实现，单元测试覆盖编解码往返。
 */
object StateCodec {

    private const val VERSION = "PTSTATE1"
    private const val SEP = "\u001F"
    private const val LIST = "\u001E"
    private const val FIELD = "\u001D"

    fun encode(state: CampaignState): String {
        val parts = mutableListOf<String>()
        parts += VERSION
        parts += enc(state.campaignId)
        parts += enc(state.sessionId)
        parts += enc(state.dirPath)
        parts += enc(state.notes)
        parts += state.order.joinToString(",") { it.id }
        parts += state.createdAtMs.toString()
        parts += state.status.name
        parts += state.currentIndex.toString()
        parts += if (state.rawSeriesEnabled) "1" else "0"
        parts += state.finishedMs?.toString().orEmpty()
        parts += enc(state.rerunOf ?: "")
        parts += state.lastTrafficRxBytes?.toString().orEmpty()
        parts += state.lastTrafficTxBytes?.toString().orEmpty()
        parts += state.lastTrafficAtMs?.toString().orEmpty()
        parts += state.lastSampleAtMs?.toString().orEmpty()
        parts += enc(state.lastNote)
        parts += state.conditions.joinToString(LIST) { c ->
            listOf(
                c.condition.id,
                c.block.toString(),
                c.samplesDone.toString(),
                c.startMs.toString(),
                c.endMs?.toString().orEmpty(),
                c.pausedTotalMs.toString(),
                c.pauseStartedMs?.toString().orEmpty(),
                if (c.earlyFinished) "1" else "0",
                c.lateSamples.toString(),
            ).joinToString(FIELD)
        }
        return parts.joinToString(SEP)
    }

    fun decode(raw: String?): CampaignState? {
        if (raw.isNullOrEmpty()) return null
        return runCatching {
            val parts = raw.split(SEP)
            if (parts.size < 18 || parts[0] != VERSION) return null
            val order = parts[5].split(",").mapNotNull { ConditionId.of(it) }
            if (order.isEmpty()) return null
            val conditions = parts[17].split(LIST).filter { it.isNotBlank() }.mapNotNull { chunk ->
                val f = chunk.split(FIELD)
                if (f.size < 9) return@mapNotNull null
                val condition = ConditionId.of(f[0]) ?: return@mapNotNull null
                ConditionProgress(
                    condition = condition,
                    block = f[1].firstOrNull() ?: 'A',
                    samplesDone = f[2].toIntOrNull() ?: 0,
                    startMs = f[3].toLongOrNull() ?: 0L,
                    endMs = f[4].toLongOrNull(),
                    pausedTotalMs = f[5].toLongOrNull() ?: 0L,
                    pauseStartedMs = f[6].toLongOrNull(),
                    earlyFinished = f[7] == "1",
                    lateSamples = f[8].toIntOrNull() ?: 0,
                )
            }
            if (conditions.isEmpty()) return null
            CampaignState(
                campaignId = dec(parts[1]),
                sessionId = dec(parts[2]),
                dirPath = dec(parts[3]),
                notes = dec(parts[4]),
                order = order,
                createdAtMs = parts[6].toLongOrNull() ?: 0L,
                status = CampaignStatus.entries.firstOrNull { it.name == parts[7] } ?: CampaignStatus.DRAFT,
                currentIndex = (parts[8].toIntOrNull() ?: 0).coerceIn(0, conditions.lastIndex),
                conditions = conditions,
                rawSeriesEnabled = parts[9] == "1",
                finishedMs = parts[10].toLongOrNull(),
                rerunOf = dec(parts[11]).ifEmpty { null },
                lastTrafficRxBytes = parts[12].toLongOrNull(),
                lastTrafficTxBytes = parts[13].toLongOrNull(),
                lastTrafficAtMs = parts[14].toLongOrNull(),
                lastSampleAtMs = parts[15].toLongOrNull(),
                lastNote = dec(parts[16]),
            )
        }.getOrNull()
    }

    private fun enc(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun dec(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrDefault("")
    }
}
