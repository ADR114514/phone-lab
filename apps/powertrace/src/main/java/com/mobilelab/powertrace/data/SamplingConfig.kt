package com.mobilelab.powertrace.data

/**
 * 采样计划常量，全部对应 spec 与数据字典 1.1 的列说明。
 *
 * 一次工况 = 30 min = 6 个采样点（elapsed 0/300/600/900/1200/1500 s）；
 * 第 6 个点之后还需等待 300 s 才满 30 min，因此工况总时长为 1800 s。
 */
object SamplingConfig {

    /** 每个工况的采样点数，对应 CSV 6 行。 */
    const val SAMPLES_PER_CONDITION = 6

    /** 采样间隔（秒），spec 固定 300 s，不可配置。 */
    const val INTERVAL_SEC = 300L
    const val INTERVAL_MS = INTERVAL_SEC * 1000L

    /** 工况总时长（秒）。 */
    const val CONDITION_DURATION_SEC = 1800L
    const val CONDITION_DURATION_MS = CONDITION_DURATION_SEC * 1000L

    /** 超过该延迟即标记 late_sample（spec：延迟 > 90 s）。 */
    const val LATE_TOLERANCE_MS = 90_000L

    /** 原始高频序列间隔（秒），默认关闭。 */
    const val RAW_INTERVAL_SEC = 5L

    /** 单次采样持锁上限（spec ≤ 8 s，不得整场持锁）。 */
    const val WAKE_LOCK_TIMEOUT_MS = 8_000L

    /** 使用情况访问取"最近 N 毫秒内 RESUMED"的包名。 */
    const val FOREGROUND_WINDOW_MS = 10_000L

    /** 光线传感器读取等待上限（毫秒）。 */
    const val LUX_READ_TIMEOUT_MS = 1_200L

    /** 第 index 个采样点（1 起）的名义 elapsed 秒数：0/300/…/1500。 */
    fun elapsedSecForSample(index: Int): Long =
        (index.coerceAtLeast(1) - 1) * INTERVAL_SEC

    /** UI 显示的采样间隔文本。 */
    fun intervalLabel(): String = "${INTERVAL_SEC} s"

    /** 一个工况的时长文本。 */
    fun conditionDurationLabel(): String = "30 min / 工况 · 每 5 min 采 1 次 · 共 6 点"
}
