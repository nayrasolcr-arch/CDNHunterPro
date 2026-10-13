package com.cdhunter.pro.repo

import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.model.ProbeOutcome
import com.cdhunter.pro.core.model.Verdict
import com.cdhunter.pro.db.ErrorLogDao
import com.cdhunter.pro.db.ErrorLogEntity
import com.cdhunter.pro.db.ResultDao
import com.cdhunter.pro.db.ResultEntity
import com.cdhunter.pro.db.SniResultDao
import com.cdhunter.pro.scoring.ScoringEngine
import com.cdhunter.pro.scoring.effectiveVerdict
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persists probe outcomes and provides read/aggregation queries used by the
 * UI. Also owns the score-recompute pass (spec: recompute from stored data).
 */
class ResultRepository(
    private val resultDao: ResultDao,
    private val sniDao: SniResultDao,
    private val errorLogDao: ErrorLogDao,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val scoring = ScoringEngine()

    fun toEntity(outcome: ProbeOutcome, scanId: Long, verifiedSni: String? = null): ResultEntity {
        val verdict = ScoringEngine.verdictOf(
            tcpOk = outcome.tcpOk,
            tlsOk = outcome.tlsOk,
            certMatched = outcome.certMatched,
            httpOk = outcome.httpOk,
            verifiedSni = verifiedSni,
            attempts = outcome.attempts,
            successes = outcome.successes,
        )
        val entity = ResultEntity(
            scanId = scanId,
            ip = outcome.ip,
            port = outcome.port,
            providerId = outcome.providerId,
            tcpOk = outcome.tcpOk,
            tcpConnectMs = outcome.tcpConnectMs,
            tlsOk = outcome.tlsOk,
            tlsHandshakeMs = outcome.tlsHandshakeMs,
            tlsVersion = outcome.tlsVersion,
            certCn = outcome.certCn,
            certNotAfterMs = outcome.certNotAfterMs,
            certMatched = outcome.certMatched,
            httpOk = outcome.httpOk,
            httpStatus = outcome.httpStatus,
            httpTtfbMs = outcome.httpTtfbMs,
            httpTotalMs = outcome.httpTotalMs,
            httpVersion = outcome.httpVersion,
            downloadKbps = outcome.downloadKbps,
            uploadKbps = outcome.uploadKbps,
            latencyMinMs = outcome.latencyMinMs,
            latencyAvgMs = outcome.latencyAvgMs,
            latencyMedianMs = outcome.latencyMedianMs,
            latencyMaxMs = outcome.latencyMaxMs,
            jitterMs = outcome.jitterMs,
            latencySeriesJson = json.encodeToString(
                ListSerializer(Int.serializer()), outcome.latencySeries,
            ),
            attempts = outcome.attempts,
            successes = outcome.successes,
            errorCategory = outcome.errorCategory.name,
            errorMessage = outcome.errorMessage,
            testedAtMs = outcome.testedAtMs,
            verdict = verdict.name,
            verifiedSni = verifiedSni,
        )
        return entity
    }

    suspend fun storeOutcome(outcome: ProbeOutcome, scanId: Long, verifiedSni: String? = null) {
        resultDao.upsert(toEntity(outcome, scanId, verifiedSni))
    }

    suspend fun storeAll(outcomes: List<ProbeOutcome>, scanId: Long) {
        resultDao.upsertAll(outcomes.map { toEntity(it, scanId) })
    }

    /** Recomputes scores for a whole scan from stored data (spec section 9). */
    suspend fun recomputeScores(scanId: Long) {
        val rows = resultDao.forScanOnce(scanId)
        val updated = rows.map { r ->
            val scored = scoring.score(r)
            r.copy(score = if (scored.sufficient) scored.score else null, scoreReasonsJson = scoring.serializeReasons(scored.reasons))
        }
        resultDao.upsertAll(updated)
    }

    suspend fun forScan(scanId: Long): List<ResultEntity> = resultDao.forScanOnce(scanId)

    fun observeForScan(scanId: Long): Flow<List<ResultEntity>> = resultDao.observeForScan(scanId)

    suspend fun bestEver(): ResultEntity? = resultDao.bestEver()

    suspend fun lastSuccessAt(): Long? = resultDao.lastSuccessAt()

    suspend fun countsFor(scanId: Long): Triple<Int, Int, Int> =
        Triple(resultDao.countReachable(scanId), resultDao.countVerified(scanId), resultDao.countErrors(scanId))

    suspend fun forIp(ip: String): List<ResultEntity> = resultDao.forIp(ip)

    /** SNI results that produced a verified handshake for a provider. */
    suspend fun verifiedSnis(providerId: String): List<com.cdhunter.pro.db.SniResultEntity> =
        sniDao.verifiedFor(providerId)

    /** Marks EXPIRED results per TTL for display purposes (computed lazily). */
    suspend fun withEffectiveVerdicts(scanId: Long, ttlHours: Int): List<ResultEntity> =
        forScan(scanId) // UI applies effectiveVerdict() per row

    suspend fun deleteForScan(scanId: Long) = resultDao.deleteForScan(scanId)

    // ---- error log ----
    suspend fun logError(tag: String, message: String, level: String = "ERROR") {
        runCatching {
            errorLogDao.insert(
                ErrorLogEntity(
                    atMs = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    // Never store credentials/tokens here by design.
                    message = message.take(500),
                ),
            )
            errorLogDao.trim()
        }
    }

    fun observeErrors(): Flow<List<ErrorLogEntity>> = errorLogDao.observeRecent()

    fun observeSniResults(): Flow<List<com.cdhunter.pro.db.SniResultEntity>> = sniDao.observeAll()

    suspend fun storeSniResults(rows: List<com.cdhunter.pro.db.SniResultEntity>) =
        sniDao.upsertAll(rows)
}
