package com.mobilelab.signalmap.data

import com.mobilelab.core.math.Stats

/**
 * Latency aggregation shared by the internet RTT probe, the gateway TCP probe
 * and the HTTP probe.
 *
 * Contract (DATA_DICTIONARY 2.3):
 *  - attempts is the planned sample count (e.g. 20 RTT attempts);
 *  - failed attempts are dropped from the statistics and only influence
 *    loss_pct;
 *  - a fully failed run yields empty statistics (null), never zeros.
 */
object RttMath {

    fun summarize(attempts: Int, latenciesMs: List<Double>): RttSummary {
        val valid = latenciesMs.filter { it.isFinite() && it >= 0.0 }
        val planned = attempts.coerceAtLeast(valid.size)
        if (valid.isEmpty()) {
            return RttSummary(
                attempts = planned,
                successes = 0,
                minMs = null, avgMs = null, maxMs = null,
                medianMs = null, p95Ms = null, jitterMs = null,
            )
        }
        return RttSummary(
            attempts = planned,
            successes = valid.size,
            minMs = Stats.min(valid),
            avgMs = Stats.mean(valid),
            maxMs = Stats.max(valid),
            medianMs = Stats.median(valid),
            p95Ms = Stats.percentile(valid, 0.95),
            // Sample standard deviation; a single sample is a real measurement
            // and is reported as zero jitter rather than "no data".
            jitterMs = if (valid.size >= 2) Stats.stdev(valid) else 0.0,
        )
    }

    /** Mean of the successful samples, or null when nothing succeeded. */
    fun meanOf(latenciesMs: List<Double>): Double? {
        val valid = latenciesMs.filter { it.isFinite() && it >= 0.0 }
        return if (valid.isEmpty()) null else Stats.mean(valid)
    }
}

/**
 * Enforces the "each repeat of a point is at least 30 s apart" rule. Pure
 * function so the counting UI and its unit test cannot drift apart.
 */
object RepeatGate {
    fun remainingMs(lastCompletedAtMs: Long?, nowMs: Long, intervalSec: Int): Long {
        if (lastCompletedAtMs == null || intervalSec <= 0) return 0L
        val due = lastCompletedAtMs + intervalSec * 1000L
        return (due - nowMs).coerceAtLeast(0L)
    }

    fun allowed(lastCompletedAtMs: Long?, nowMs: Long, intervalSec: Int): Boolean =
        remainingMs(lastCompletedAtMs, nowMs, intervalSec) <= 0L
}
