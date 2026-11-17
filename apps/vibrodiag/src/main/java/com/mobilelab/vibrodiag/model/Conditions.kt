package com.mobilelab.vibrodiag.model

/**
 * One of the five measured machine conditions. The ids are the data-contract
 * values from docs/DATA_DICTIONARY.md section 3.1 and must never be localised.
 */
data class Condition(
    val id: String,
    val label: String,
    val shortLabel: String,
    /** Operator instruction used on the recording screen. */
    val setup: String,
)

object Conditions {
    /** Groups per condition required by the data contract. */
    const val TARGET_GROUPS = 15

    val all: List<Condition> = listOf(
        Condition("C1_NORMAL_LOW", "风扇低档正常", "低档正常", "电风扇 1 档，扇叶原状"),
        Condition("C2_NORMAL_HIGH", "风扇高档正常", "高档正常", "电风扇最大档，扇叶原状"),
        Condition("C3_IMBALANCE", "扇叶轻微失衡", "轻微失衡", "1 片扇叶贴约 2 g 配重片"),
        Condition("C4_BLOCKED", "风道遮挡", "风道遮挡", "进风口遮挡 1/2"),
        Condition("C5_HAIRDRYER", "吹风机", "吹风机", "吹风机高速热风档"),
    )

    val ids: List<String> = all.map { it.id }

    fun byId(id: String): Condition? = all.firstOrNull { it.id == id }

    fun labelOf(id: String): String = byId(id)?.label ?: id

    fun shortLabelOf(id: String): String = byId(id)?.shortLabel ?: id

    fun indexOf(id: String): Int = all.indexOfFirst { it.id == id }

    fun requireIndex(id: String): Int = indexOf(id).coerceAtLeast(0)
}
