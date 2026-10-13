package com.cdhunter.pro

import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.model.ModelDefaults
import com.cdhunter.pro.core.model.Verdict
import com.cdhunter.pro.db.ResultEntity
import com.cdhunter.pro.scoring.ScoringEngine
import com.cdhunter.pro.scoring.effectiveVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringEngineTest {

    private fun result(
        tcpOk: Boolean = true,
        tcpConnectMs: Int? = 100,
        tlsOk: Boolean? = null,
        certMatched: Boolean? = null,
        httpOk: Boolean? = null,
        httpVersion: String? = null,
        downloadKbps: Double? = null,
        latencyAvgMs: Int? = 100,
        jitterMs: Int? = 5,
        attempts: Int = 3,
        successes: Int = 3,
        verifiedSni: String? = null,
    ) = ResultEntity(
        scanId = 1, ip = "1.2.3.4", port = 443, providerId = "cloudflare",
        tcpOk = tcpOk, tcpConnectMs = tcpConnectMs, tlsOk = tlsOk, certMatched = certMatched,
        httpOk = httpOk, httpVersion = httpVersion, downloadKbps = downloadKbps,
        latencyAvgMs = latencyAvgMs, jitterMs = jitterMs,
        attempts = attempts, successes = successes,
        testedAtMs = System.currentTimeMillis(),
        verdict = "INSUFFICIENT_DATA",
        verifiedSni = verifiedSni,
    )

    // ------------------------------------------------------- verdict rules

    @Test
    fun `all tcp attempts failed means unreachable`() {
        val v = ScoringEngine.verdictOf(tcpOk = false, tlsOk = null, certMatched = null, httpOk = null, verifiedSni = null, attempts = 3, successes = 0)
        assertEquals(Verdict.UNREACHABLE, v)
    }

    @Test
    fun `verified sni with full tls and http success is verified compatible`() {
        val v = ScoringEngine.verdictOf(
            tcpOk = true, tlsOk = true, certMatched = true, httpOk = true,
            verifiedSni = "cloudflare.com", attempts = 3, successes = 3,
        )
        assertEquals(Verdict.VERIFIED_COMPATIBLE, v)
    }

    @Test
    fun `full success without sni evidence is only a candidate`() {
        val v = ScoringEngine.verdictOf(
            tcpOk = true, tlsOk = true, certMatched = true, httpOk = true,
            verifiedSni = null, attempts = 3, successes = 3,
        )
        assertEquals(Verdict.CANDIDATE_NEEDS_TESTING, v)
    }

    @Test
    fun `tls ok but cert mismatch is reachable incompatible`() {
        val v = ScoringEngine.verdictOf(
            tcpOk = true, tlsOk = true, certMatched = false, httpOk = null,
            verifiedSni = null, attempts = 3, successes = 3,
        )
        assertEquals(Verdict.REACHABLE_INCOMPATIBLE, v)
    }

    @Test
    fun `tcp ok tls failed is reachable incompatible`() {
        val v = ScoringEngine.verdictOf(
            tcpOk = true, tlsOk = false, certMatched = null, httpOk = null,
            verifiedSni = null, attempts = 3, successes = 3,
        )
        assertEquals(Verdict.REACHABLE_INCOMPATIBLE, v)
    }

    @Test
    fun `too little data is insufficient`() {
        val v = ScoringEngine.verdictOf(
            tcpOk = true, tlsOk = null, certMatched = null, httpOk = null,
            verifiedSni = null, attempts = 1, successes = 1,
        )
        assertEquals(Verdict.INSUFFICIENT_DATA, v)
    }

    @Test
    fun `ttl expiry marks expired`() {
        val r = result().copy(testedAtMs = System.currentTimeMillis() - 100 * 3_600_000L, verdict = "CANDIDATE_NEEDS_TESTING")
        assertEquals(Verdict.EXPIRED, effectiveVerdict(r, ttlHours = 72))
        val fresh = result().copy(verdict = "CANDIDATE_NEEDS_TESTING")
        assertEquals(Verdict.CANDIDATE_NEEDS_TESTING, effectiveVerdict(fresh, ttlHours = 72))
    }

    // ------------------------------------------------------- scoring rules

    @Test
    fun `default weights normalise to full score range`() {
        assertEquals(1.0, ModelDefaults.DEFAULT_WEIGHTS.values.sum(), 1e-9)
    }

    @Test
    fun `missing download data gets zero and reason not fast`() {
        val engine = ScoringEngine()
        val scored = engine.score(result(downloadKbps = null))
        assertTrue(scored.reasons.contains(com.cdhunter.pro.scoring.ScoreReason.DOWNLOAD_MISSING))
        // protocol partial (tcp+tls+http missing except latency/stability/jitter)
        assertTrue(scored.score < 100.0)
    }

    @Test
    fun `full perfect result scores high`() {
        val engine = ScoringEngine()
        val scored = engine.score(
            result(
                tlsOk = true, certMatched = true, httpOk = true, httpVersion = "h2",
                downloadKbps = 12_000.0, latencyAvgMs = 30, jitterMs = 2,
                attempts = 3, successes = 3, verifiedSni = "cloudflare.com",
            ),
        )
        assertTrue("expected high score, got ${'$'}{scored.score}", scored.score > 85.0)
    }

    @Test
    fun `bad latency reduces score`() {
        val engine = ScoringEngine()
        val good = engine.score(result(latencyAvgMs = 30)).score
        val bad = engine.score(result(latencyAvgMs = 1_100)).score
        assertTrue(bad < good)
    }

    @Test
    fun `custom weights are respected and normalised`() {
        val engine = ScoringEngine(
            mapOf("download" to 1.0, "stability" to 1.0, "latency" to 1.0, "jitter" to 1.0, "protocol" to 1.0),
        )
        val scored = engine.score(result(downloadKbps = 10_000.0))
        // all components weigh equally; score must stay in range
        assertTrue(scored.score in 0.0..100.0)
    }

    @Test
    fun `error category tcp refused is permanent for retry policy`() {
        assertEquals(false, com.cdhunter.pro.core.util.RetryPolicy.shouldRetry(ErrorCategory.TCP_REFUSED))
        assertEquals(true, com.cdhunter.pro.core.util.RetryPolicy.shouldRetry(ErrorCategory.TCP_TIMEOUT))
    }
}
