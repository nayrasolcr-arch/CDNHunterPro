package com.cdhunter.pro.network

import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.model.LatencyStats

/**
 * Computes latency statistics (min/avg/median/max/jitter/success-rate) from
 * measured TCP samples. Pure math — unit-tested.
 */
object SpeedStats {

    fun fromSamples(samplesMs: List<Int>, attempts: Int, successes: Int): LatencyStats {
        val ok = samplesMs.filter { it > 0 }
        if (ok.isEmpty()) {
            return LatencyStats(
                minMs = 0, avgMs = 0, medianMs = 0, maxMs = 0, jitterMs = 0,
                successes = successes, attempts = attempts,
            )
        }
        val sorted = ok.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
        val avg = ok.average().toInt()
        // Jitter = mean absolute difference between consecutive samples (RFC 3550 style).
        val jitter = if (ok.size >= 2) {
            var sum = 0.0
            for (i in 1 until ok.size) sum += kotlin.math.abs(ok[i] - ok[i - 1])
            (sum / (ok.size - 1)).toInt()
        } else {
            0
        }
        return LatencyStats(
            minMs = sorted.first(),
            avgMs = avg,
            medianMs = median,
            maxMs = sorted.last(),
            jitterMs = jitter,
            successes = successes,
            attempts = attempts,
        )
    }

    /** Marks a possible rate-limit when quality degrades across attempts (never certain). */
    fun suspectRateLimit(firstHalfAvg: Int, secondHalfAvg: Int): Boolean =
        firstHalfAvg > 0 && secondHalfAvg > firstHalfAvg * 2
}

/** Convenience wrapper combining TCP latency measurement + statistics. */
class SpeedTester(private val tcpTester: TcpTester) {

    suspend fun measure(
        ip: String,
        port: Int,
        timeoutMs: Int,
        attempts: Int,
    ): Pair<LatencyStats, List<Int>> {
        val attemptsList = tcpTester.measureLatency(ip, port, timeoutMs, attempts)
        val successes = attemptsList.count { it.ok }
        val samples = attemptsList.map { it.connectMs }
        val okSamples = attemptsList.filter { it.ok }.map { it.connectMs }
        val stats = SpeedStats.fromSamples(okSamples, attemptsList.size, successes)
        return stats to samples
    }
}
