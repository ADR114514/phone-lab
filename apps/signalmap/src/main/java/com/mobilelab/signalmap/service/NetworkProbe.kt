package com.mobilelab.signalmap.service

import com.mobilelab.core.log.LabLog
import com.mobilelab.signalmap.data.RttMath
import com.mobilelab.signalmap.data.RttSummary
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Blocking network probes used by the measurement pipeline. Runs on the IO
 * dispatcher; every attempt is bounded by an explicit timeout so a dead campus
 * AP cannot hang the UI.
 */
class NetworkProbe {

    data class ProbeRun(val attempts: Int, val latenciesMs: List<Double>) {
        val failures: Int get() = (attempts - latenciesMs.size).coerceAtLeast(0)
        val allFailed: Boolean get() = latenciesMs.isEmpty()
        val summary: RttSummary get() = RttMath.summarize(attempts, latenciesMs)
    }

    data class HttpSample(
        val ttfbMs: Double?,
        val totalMs: Double?,
        val dnsMs: Double?,
        val code: Int,
        val error: String?,
    )

    data class HttpProbeRun(val attempts: Int, val samples: List<HttpSample>) {
        val failures: Int get() = samples.count { it.error != null || it.ttfbMs == null }
        val successes: Int get() = samples.size - failures
        val ttfbValues: List<Double> get() = samples.mapNotNull { it.ttfbMs }
        val totalValues: List<Double> get() = samples.mapNotNull { it.totalMs }
        val dnsValues: List<Double> get() = samples.mapNotNull { it.dnsMs }
        val avgTtfbMs: Double? get() = RttMath.meanOf(ttfbValues)
        val avgTotalMs: Double? get() = RttMath.meanOf(totalValues)
        val dominantError: String?
            get() = samples.mapNotNull { it.error }.firstOrNull()
    }

    /** One TCP connect attempt; returns the connect time in ms or null on failure. */
    fun tcpConnectOnce(host: String, port: Int, timeoutMs: Int): Double? {
        if (host.isBlank() || port !in 1..65535) return null
        val startNs = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.nanoTime() - startNs) / 1_000_000.0
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            LabLog.w(TAG, "tcp connect blocked: ${e.message}")
            null
        }
    }

    /**
     * Runs [attempts] sequential TCP connects. Successful samples are returned
     * in order; [onAttempt] receives every attempt (null = failure) so the UI
     * can draw a live RTT series.
     */
    fun tcpProbe(
        host: String,
        port: Int,
        attempts: Int,
        timeoutMs: Int,
        onAttempt: ((Double?) -> Unit)? = null,
    ): ProbeRun {
        val samples = ArrayList<Double>(attempts)
        repeat(attempts.coerceAtLeast(1)) {
            val sample = tcpConnectOnce(host, port, timeoutMs)
            if (sample != null) samples += sample
            onAttempt?.invoke(sample)
        }
        return ProbeRun(attempts.coerceAtLeast(1), samples)
    }

    /** HTTP GET run measuring time-to-first-byte and total duration per request. */
    fun httpProbe(
        url: String,
        attempts: Int,
        onAttempt: ((HttpSample) -> Unit)? = null,
    ): HttpProbeRun {
        val samples = ArrayList<HttpSample>(attempts)
        val parsed = runCatching { Request.Builder().url(url).build() }.getOrElse { error ->
            val failure = HttpSample(null, null, null, -1, "URL 无效: " + (error.message ?: url))
            repeat(attempts.coerceAtLeast(1)) { onAttempt?.invoke(failure) }
            return HttpProbeRun(attempts.coerceAtLeast(1), List(attempts.coerceAtLeast(1)) { failure })
        }
        repeat(attempts.coerceAtLeast(1)) {
            val sample = executeOnce(parsed)
            samples += sample
            onAttempt?.invoke(sample)
        }
        return HttpProbeRun(attempts.coerceAtLeast(1), samples)
    }

    private fun executeOnce(request: Request): HttpSample {
        val listener = TimingListener()
        val client = baseClient.newBuilder().eventListener(listener).build()
        val call: Call = client.newCall(request)
        return try {
            call.execute().use { response ->
                response.body?.bytes()
                val start = listener.callStartNs
                val ttfb = if (start > 0 && listener.headersStartNs > 0) {
                    (listener.headersStartNs - start) / 1_000_000.0
                } else {
                    null
                }
                val total = if (start > 0 && listener.bodyEndNs > 0) {
                    (listener.bodyEndNs - start) / 1_000_000.0
                } else {
                    ttfb
                }
                val dns = if (listener.dnsStartNs > 0 && listener.dnsEndNs > 0) {
                    (listener.dnsEndNs - listener.dnsStartNs) / 1_000_000.0
                } else {
                    null
                }
                HttpSample(ttfb, total, dns, response.code, null)
            }
        } catch (e: IOException) {
            HttpSample(null, null, null, -1, "网络异常: " + (e.message ?: e.javaClass.simpleName))
        } catch (e: RuntimeException) {
            HttpSample(null, null, null, -1, "请求失败: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Optional throughput probe: downloads at most [maxBytes] and reports
     * kbps. Returns null when the endpoint yields no body (e.g. a 204 URL).
     */
    fun throughputKbps(url: String, maxBytes: Int): Double? {
        val limit = maxBytes.coerceAtLeast(16 * 1024).toLong()
        val request = runCatching { Request.Builder().url(url).build() }.getOrElse { error ->
            LabLog.w(TAG, "throughput url invalid: ${error.message}")
            return null
        }
        return try {
            val startNs = System.nanoTime()
            baseClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LabLog.w(TAG, "throughput HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                var total = 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (total < limit) {
                        val want = kotlin.math.min(buffer.size.toLong(), limit - total).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read <= 0) break
                        total += read
                    }
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
                if (total <= 0 || elapsedMs <= 0.0) null else total * 8.0 / elapsedMs
            }
        } catch (e: IOException) {
            LabLog.w(TAG, "throughput failed: ${e.message}")
            null
        } catch (e: RuntimeException) {
            LabLog.w(TAG, "throughput failed: ${e.message}")
            null
        }
    }

    /** Records OkHttp phase timestamps without touching the request path. */
    private class TimingListener : EventListener() {
        @Volatile var callStartNs: Long = 0
        @Volatile var dnsStartNs: Long = 0
        @Volatile var dnsEndNs: Long = 0
        @Volatile var headersStartNs: Long = 0
        @Volatile var bodyEndNs: Long = 0

        override fun callStart(call: Call) {
            callStartNs = System.nanoTime()
        }

        override fun dnsStart(call: Call, domainName: String) {
            dnsStartNs = System.nanoTime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsEndNs = System.nanoTime()
        }

        override fun responseHeadersStart(call: Call) {
            headersStartNs = System.nanoTime()
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            bodyEndNs = System.nanoTime()
        }
    }

    companion object {
        private const val TAG = "NetworkProbe"
        private const val CONNECT_TIMEOUT_SEC = 5L
        private const val READ_TIMEOUT_SEC = 10L
        private const val CALL_TIMEOUT_SEC = 10L

        private val baseClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        const val GATEWAY_TIMEOUT_MS = 300
    }
}
