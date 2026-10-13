package com.cdhunter.pro.scoring

import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.model.ModelDefaults
import com.cdhunter.pro.core.model.Verdict
import com.cdhunter.pro.db.ResultEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Explainable scoring reasons (stored as codes, localized in the UI). */
@Serializable
enum class ScoreReason {
    DOWNLOAD_FAST, DOWNLOAD_OK, DOWNLOAD_SLOW, DOWNLOAD_MISSING,
    STABILITY_HIGH, STABILITY_MID, STABILITY_LOW,
    LATENCY_GOOD, LATENCY_MID, LATENCY_HIGH,
    JITTER_LOW, JITTER_MID, JITTER_HIGH,
    PROTOCOL_FULL, PROTOCOL_PARTIAL, PROTOCOL_NONE,
    SNI_VERIFIED,
}

/**
 * Configurable, explainable scoring engine (spec section 9).
 *
 * Principles enforced:
 *  - weights are user-editable and auto-normalised to sum 1.0;
 *  - every component is normalised to 0..1 over fixed, documented bounds;
 *  - MISSING data contributes 0 — absence is never rewarded;
 *  - reasons are emitted so the UI can explain every score.
 */
class ScoringEngine(
    private val weights: Map<String, Double> = ModelDefaults.DEFAULT_WEIGHTS,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val normWeights: Map<String, Double> = run {
        val sum = weights.values.sum()
        if (sum <= 0.0) ModelDefaults.DEFAULT_WEIGHTS
        else weights.mapValues { it.value / sum }
    }

    data class ComponentScore(val value: Double, val present: Boolean, val reasons: List<ScoreReason>)

    data class Scored(
        val score: Double,
        val reasons: List<ScoreReason>,
        val sufficient: Boolean,
    )

    fun score(result: ResultEntity): Scored {
        val components = listOf(
            downloadComponent(result),
            stabilityComponent(result),
            latencyComponent(result),
            jitterComponent(result),
            protocolComponent(result),
        )
        val weightedSum = components.fold(0.0) { acc, c -> acc + c.value }
        val reasons = components.flatMap { it.reasons }.distinct()
        // "sufficient" = at least latency/stability measured AND TCP performed
        val sufficient = result.attempts >= 1 && result.tcpConnectMs != null
        return Scored(score = weightedSum.coerceIn(0.0, 1.0) * 100.0, reasons = reasons, sufficient = sufficient)
    }

    private fun downloadComponent(r: ResultEntity): ComponentScore {
        val kbps = r.downloadKbps
        return if (kbps == null || kbps <= 0.0) {
            ComponentScore(0.0, false, listOf(ScoreReason.DOWNLOAD_MISSING))
        } else {
            // Fixed bounds: 0..10_000 KB/s (≈80 Mbps) map to 0..1.
            val v = (kbps / 10_000.0).coerceIn(0.0, 1.0)
            val reason = when {
                v >= 0.6 -> ScoreReason.DOWNLOAD_FAST
                v >= 0.2 -> ScoreReason.DOWNLOAD_OK
                else -> ScoreReason.DOWNLOAD_SLOW
            }
            ComponentScore(v * normWeights["download"]!!, true, listOf(reason))
        }
    }

    private fun stabilityComponent(r: ResultEntity): ComponentScore {
        if (r.attempts <= 0) return ComponentScore(0.0, false, emptyList())
        val rate = r.successes.toDouble() / r.attempts.toDouble()
        val reason = when {
            rate >= 0.9 -> ScoreReason.STABILITY_HIGH
            rate >= 0.5 -> ScoreReason.STABILITY_MID
            else -> ScoreReason.STABILITY_LOW
        }
        return ComponentScore(rate * normWeights["stability"]!!, true, listOf(reason))
    }

    private fun latencyComponent(r: ResultEntity): ComponentScore {
        val avg = r.latencyAvgMs
        return if (avg == null || avg <= 0) {
            ComponentScore(0.0, false, emptyList())
        } else {
            // Fixed bounds: 20..1200 ms inverted.
            val v = (1.0 - (avg - 20.0) / (1200.0 - 20.0)).coerceIn(0.0, 1.0)
            val reason = when {
                v >= 0.7 -> ScoreReason.LATENCY_GOOD
                v >= 0.35 -> ScoreReason.LATENCY_MID
                else -> ScoreReason.LATENCY_HIGH
            }
            ComponentScore(v * normWeights["latency"]!!, true, listOf(reason))
        }
    }

    private fun jitterComponent(r: ResultEntity): ComponentScore {
        val jitter = r.jitterMs
        return if (jitter == null) {
            ComponentScore(0.0, false, emptyList())
        } else {
            // Fixed bounds: 0..100 ms inverted.
            val v = (1.0 - jitter / 100.0).coerceIn(0.0, 1.0)
            val reason = when {
                v >= 0.7 -> ScoreReason.JITTER_LOW
                v >= 0.35 -> ScoreReason.JITTER_MID
                else -> ScoreReason.JITTER_HIGH
            }
            ComponentScore(v * normWeights["jitter"]!!, true, listOf(reason))
        }
    }

    private fun protocolComponent(r: ResultEntity): ComponentScore {
        var v = 0.0
        val reasons = mutableListOf<ScoreReason>()
        if (r.tcpOk) v += 0.2
        if (r.tlsOk == true) v += 0.3
        if (r.httpOk == true) v += 0.3
        if (r.httpVersion == "h2") v += 0.1
        if (r.verifiedSni != null || r.certMatched == true) v += 0.1
        when {
            v >= 0.9 -> reasons.add(ScoreReason.PROTOCOL_FULL)
            v > 0.0 -> reasons.add(ScoreReason.PROTOCOL_PARTIAL)
            else -> reasons.add(ScoreReason.PROTOCOL_NONE)
        }
        return ComponentScore(v * normWeights["protocol"]!!, true, reasons)
    }

    fun serializeReasons(reasons: List<ScoreReason>): String =
        json.encodeToString(ListSerializer(ScoreReason.serializer()), reasons)

    fun deserializeReasons(raw: String?): List<ScoreReason> =
        raw?.let {
            runCatching {
                json.decodeFromString(ListSerializer(ScoreReason.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()

    companion object {
        /**
         * Verdict derivation (spec section 9). EXPIRED is evaluated by the
         * repository (needs TTL + now); the rest is derived purely from
         * measured flags. Order matters: honest and conservative.
         */
        fun verdictOf(
            tcpOk: Boolean,
            tlsOk: Boolean?,
            certMatched: Boolean?,
            httpOk: Boolean?,
            verifiedSni: String?,
            attempts: Int,
            successes: Int,
        ): Verdict = when {
            attempts >= 2 && successes == 0 -> Verdict.UNREACHABLE
            !tcpOk -> Verdict.UNREACHABLE
            attempts < 2 && (tlsOk == null && httpOk == null) -> Verdict.INSUFFICIENT_DATA
            tlsOk == true && certMatched == true && httpOk == true ->
                // "Verified compatible" only after a REAL IP×SNI verified handshake.
                if (verifiedSni != null) Verdict.VERIFIED_COMPATIBLE
                else Verdict.CANDIDATE_NEEDS_TESTING
            tlsOk == true && httpOk == true -> Verdict.CANDIDATE_NEEDS_TESTING
            tlsOk == true || httpOk == true -> Verdict.REACHABLE_INCOMPATIBLE
            tcpOk -> Verdict.REACHABLE_INCOMPATIBLE
            else -> Verdict.INSUFFICIENT_DATA
        }
    }
}

/** Applies TTL expiry on top of the measured verdict. */
fun effectiveVerdict(
    result: ResultEntity,
    ttlHours: Int,
    nowMs: Long = System.currentTimeMillis(),
): Verdict {
    val base = runCatching { Verdict.valueOf(result.verdict) }.getOrDefault(Verdict.INSUFFICIENT_DATA)
    if (base == Verdict.UNREACHABLE) return base
    val ageMs = nowMs - result.testedAtMs
    return if (ageMs > ttlHours * 3_600_000L) Verdict.EXPIRED else base
}
