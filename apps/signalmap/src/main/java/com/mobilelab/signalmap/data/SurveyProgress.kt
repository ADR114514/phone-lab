package com.mobilelab.signalmap.data

/**
 * Progress state machine of a 8 x 3 survey.
 *
 * The state only records *facts* (which repeat indices have been written to
 * disk and when), never a "current step" cursor: the next target is derived
 * from the facts. That makes the state safe to rebuild after a crash by
 * replaying the rows of survey_<session>.csv.
 */
data class SurveyProgress(
    val completed: Map<String, List<Int>> = emptyMap(),
    val lastCompletedAtMs: Map<String, Long> = emptyMap(),
) {
    fun repeats(pointId: String): Int = completed[pointId]?.size ?: 0

    fun isPointDone(pointId: String, repeatsPerPoint: Int = DataContract.REPEATS_PER_POINT): Boolean =
        repeats(pointId) >= repeatsPerPoint

    fun totalCompleted(): Int = completed.values.sumOf { it.size }

    fun allDone(points: List<SurveyPoint>, repeatsPerPoint: Int = DataContract.REPEATS_PER_POINT): Boolean =
        points.isNotEmpty() && points.all { isPointDone(it.id, repeatsPerPoint) }

    fun register(pointId: String, repeatIndex: Int, atMs: Long): SurveyProgress {
        val previous = completed[pointId].orEmpty()
        val next = (previous + repeatIndex).distinct().sorted()
        return copy(
            completed = completed + (pointId to next),
            lastCompletedAtMs = lastCompletedAtMs + (pointId to atMs),
        )
    }

    /** First point (in catalogue order) that still misses a repeat. */
    fun nextTarget(points: List<SurveyPoint>, repeatsPerPoint: Int = DataContract.REPEATS_PER_POINT): MeasurementTarget? {
        for (point in points) {
            val done = completed[point.id].orEmpty().toSet()
            val missing = (1..repeatsPerPoint).firstOrNull { it !in done } ?: continue
            return MeasurementTarget(point, missing)
        }
        return null
    }

    fun lastCompletedAt(pointId: String): Long? = lastCompletedAtMs[pointId]

    /** 1-based index of the next missing repeat for one station, null when done. */
    fun nextRepeatIndex(pointId: String, repeatsPerPoint: Int = DataContract.REPEATS_PER_POINT): Int? {
        val done = completed[pointId].orEmpty().toSet()
        return (1..repeatsPerPoint).firstOrNull { it !in done }
    }

    /**
     * Rebuilds progress from the rows that survived on disk. Keeps timestamps
     * that are already known and uses [fallbackTimestampMs] for recovered rows,
     * so the 30 s inter-repeat gate stays sane after a crash.
     */
    fun reconciledWith(rows: List<SurveyRow>, fallbackTimestampMs: Long): SurveyProgress {
        var completedMap = completed
        var timestamps = lastCompletedAtMs
        rows.forEach { row ->
            if (row.pointId.isEmpty()) return@forEach
            val current = completedMap[row.pointId].orEmpty()
            if (row.repeatIndex !in current) {
                completedMap = completedMap + (row.pointId to (current + row.repeatIndex).distinct().sorted())
            }
            if (timestamps[row.pointId] == null) {
                timestamps = timestamps + (row.pointId to fallbackTimestampMs)
            }
        }
        return SurveyProgress(completedMap, timestamps)
    }

    fun toStorage(): String = completed.entries
        .filter { it.value.isNotEmpty() }
        .joinToString("|") { (id, repeats) -> id + "=" + repeats.sorted().joinToString(",") }

    fun lastTimestampsToStorage(): String = lastCompletedAtMs.entries
        .joinToString("|") { (id, ms) -> id + "=" + ms }

    companion object {
        val EMPTY = SurveyProgress()

        fun fromStorage(repeats: String?, timestamps: String?): SurveyProgress {
            val completed = LinkedHashMap<String, List<Int>>()
            repeats?.split('|')?.forEach { segment ->
                val id = segment.substringBefore('=', "").trim()
                if (id.isEmpty()) return@forEach
                val values = segment.substringAfter('=', "")
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .distinct()
                    .sorted()
                completed[id] = values
            }
            val timestampsMap = LinkedHashMap<String, Long>()
            timestamps?.split('|')?.forEach { segment ->
                val id = segment.substringBefore('=', "").trim()
                val value = segment.substringAfter('=', "").trim().toLongOrNull() ?: return@forEach
                if (id.isNotEmpty()) timestampsMap[id] = value
            }
            return SurveyProgress(completed, timestampsMap)
        }
    }
}
