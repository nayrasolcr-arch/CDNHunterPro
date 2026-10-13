package com.cdhunter.pro.sni

import com.cdhunter.pro.core.util.HostnameValidator
import com.cdhunter.pro.db.SniResultEntity
import com.cdhunter.pro.network.HttpTester
import com.cdhunter.pro.network.TlsTester
import com.cdhunter.pro.repo.ResultRepository
import com.cdhunter.pro.repo.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Independent IP×SNI compatibility engine (spec section 6).
 *
 * A combination is recorded "ok" ONLY when the TLS handshake succeeded with a
 * certificate that actually matches the SNI (platform verifier, never
 * bypassed) — an extra HTTP check is run and stored as part of the evidence.
 * Success here still does NOT prove proxy-tunnel compatibility; the UI must
 * keep saying so.
 */
class SniEngine(
    private val tlsTester: TlsTester,
    private val httpTester: HttpTester,
    private val resultRepository: ResultRepository,
    private val settings: SettingsRepository,
) {

    data class ComboResult(
        val ip: String,
        val sni: String,
        val ok: Boolean,
        val certMatched: Boolean,
        val tlsVersion: String?,
        val handshakeMs: Int?,
        val httpStatus: Int?,
        val errorCategory: String?,
        val errorMessage: String?,
    )

    fun validSnis(raw: List<String>): List<String> =
        raw.map { HostnameValidator.normalize(it) }
            .filter { HostnameValidator.isValid(it) }
            .distinct()

    suspend fun testCombinations(
        ips: List<String>,
        providerId: String?,
        snis: List<String>,
        timeoutMs: Int,
        concurrency: Int = 8,
        ipCap: Int = 20,
    ): List<ComboResult> {
        val validSni = validSnis(snis)
        if (validSni.isEmpty() || ips.isEmpty()) return emptyList()
        val selectedIps = ips.take(ipCap)
        val semaphore = Semaphore(concurrency.coerceIn(1, 32))
        val results = coroutineScope {
            selectedIps.flatMap { ip ->
                validSni.map { sni ->
                    async {
                        semaphore.withPermit { testOne(ip, sni, providerId, timeoutMs) }
                    }
                }
            }.awaitAll()
        }
        persist(results, providerId)
        return results
    }

    private suspend fun testOne(
        ip: String,
        sni: String,
        providerId: String?,
        timeoutMs: Int,
    ): ComboResult {
        val tls = tlsTester.probe(ip, 443, sni, timeoutMs, verifyHostname = true)
        if (!tls.ok) {
            return ComboResult(
                ip = ip, sni = sni, ok = false, certMatched = false,
                tlsVersion = null, handshakeMs = null, httpStatus = null,
                errorCategory = tls.category.name,
                errorMessage = tls.message,
            )
        }
        // TLS OK: run the HTTP check through the same IP+SNI as additional evidence.
        val http = httpTester.probe(
            ip = ip,
            hostHeaderOrSni = sni,
            port = 443,
            timeoutMs = timeoutMs,
            maxBytes = 32_000L,
        )
        return ComboResult(
            ip = ip,
            sni = sni,
            ok = tls.certMatched,
            certMatched = tls.certMatched,
            tlsVersion = tls.protocol,
            handshakeMs = tls.handshakeMs,
            httpStatus = http.statusCode,
            errorCategory = if (tls.certMatched) "NONE" else "TLS_CERT",
            errorMessage = if (tls.certMatched) null else "certificate did not match SNI",
        )
    }

    private suspend fun persist(results: List<ComboResult>, providerId: String?) {
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        val ttlMs = settings.resultTtlHours() * 3_600_000L
        resultRepository.storeSniResults(
            results.map { r ->
                SniResultEntity(
                    ip = r.ip,
                    sni = r.sni,
                    providerId = providerId,
                    ok = r.ok,
                    certMatched = r.certMatched,
                    tlsVersion = r.tlsVersion,
                    handshakeMs = r.handshakeMs,
                    errorCategory = r.errorCategory,
                    errorMessage = r.errorMessage,
                    testedAtMs = now,
                    expiresAtMs = now + ttlMs,
                )
            },
        )
    }

    /** Re-tests stale/expired rows (spec: re-test old results). */
    suspend fun retestExpired(ips: List<String>, providerId: String?, timeoutMs: Int): List<ComboResult> {
        val snis = validSnis(settings.sniFor(providerId ?: ""))
        return testCombinations(ips, providerId, snis, timeoutMs)
    }
}
