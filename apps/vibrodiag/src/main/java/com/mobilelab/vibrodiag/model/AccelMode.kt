package com.mobilelab.vibrodiag.model

/** Requested accelerometer sampling mode. AUTO prefers 200 Hz when supported. */
enum class AccelMode(val id: String, val label: String) {
    AUTO("auto", "自动"),
    RATE_200("200", "200 Hz"),
    RATE_50("50", "50 Hz");

    companion object {
        fun fromId(id: String): AccelMode = values().firstOrNull { it.id == id } ?: AUTO
    }
}
