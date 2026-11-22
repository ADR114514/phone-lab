package com.mobilelab.signalmap.data

/**
 * Operator tunables from the settings screen. Defaults are the ones written in
 * docs/DATA_DICTIONARY.md 2.3 so an untouched install reproduces the course
 * measurement protocol exactly.
 */
data class AppSettings(
    val httpTestUrl: String = DEFAULT_HTTP_URL,
    val tcpTarget: String = DEFAULT_TCP_TARGET,
    val rttAttempts: Int = DEFAULT_RTT_ATTEMPTS,
    val httpAttempts: Int = DEFAULT_HTTP_ATTEMPTS,
    val gatewayAttempts: Int = DEFAULT_GATEWAY_ATTEMPTS,
    val tcpTimeoutMs: Int = DEFAULT_TCP_TIMEOUT_MS,
    val throughputEnabled: Boolean = false,
    val throughputBytes: Int = DEFAULT_THROUGHPUT_BYTES,
    val throughputUrl: String = DEFAULT_THROUGHPUT_URL,
    val hashBssidOnExport: Boolean = true,
) {
    val tcpHost: String get() = WifiMath.parseHostPort(tcpTarget, DEFAULT_TCP_PORT).first
    val tcpPort: Int get() = WifiMath.parseHostPort(tcpTarget, DEFAULT_TCP_PORT).second

    /**
     * Clamps every numeric field into a range the measurement pipeline can
     * honestly execute; invalid text falls back to the documented default.
     */
    fun sanitized(): AppSettings = copy(
        httpTestUrl = httpTestUrl.trim().ifBlank { DEFAULT_HTTP_URL },
        tcpTarget = tcpTarget.trim().ifBlank { DEFAULT_TCP_TARGET },
        rttAttempts = rttAttempts.coerceIn(1, 200),
        httpAttempts = httpAttempts.coerceIn(1, 50),
        gatewayAttempts = gatewayAttempts.coerceIn(1, 50),
        tcpTimeoutMs = tcpTimeoutMs.coerceIn(100, 10_000),
        throughputBytes = throughputBytes.coerceIn(64 * 1024, 2 * 1024 * 1024),
        throughputUrl = throughputUrl.trim().ifBlank { DEFAULT_THROUGHPUT_URL },
    )

    companion object {
        const val DEFAULT_HTTP_URL = "https://www.baidu.com/robots.txt"
    /** 备用地址：gstatic 在国内经常不可达（HTTP 探测全部失败），全部失败时自动回退到这里并在 note 记录。 */
    const val HTTP_FALLBACK_URL = "http://connect.rom.miui.com/generate_204"
        const val DEFAULT_TCP_TARGET = "223.5.5.5:443"
        const val DEFAULT_TCP_HOST = "223.5.5.5"
        const val DEFAULT_TCP_PORT = 443
        const val DEFAULT_RTT_ATTEMPTS = 20
        const val DEFAULT_HTTP_ATTEMPTS = 5
        const val DEFAULT_GATEWAY_ATTEMPTS = 5
        const val DEFAULT_TCP_TIMEOUT_MS = 1000
        const val DEFAULT_THROUGHPUT_BYTES = 512 * 1024
        const val DEFAULT_THROUGHPUT_URL = "https://speed.cloudflare.com/__down?bytes=524288"
        const val REPEATS_PER_POINT = 3
        const val REPEAT_INTERVAL_SEC = 30
    }
}
