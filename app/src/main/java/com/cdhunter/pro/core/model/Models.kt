package com.cdhunter.pro.core.model

import kotlinx.serialization.Serializable

/** Scan depth modes as defined in the project spec (quick / standard / deep). */
enum class ScanMode { QUICK, STANDARD, DEEP }

/** Lifecycle status of a scan; persisted so scans survive process death. */
enum class ScanStatus { RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED }

/** Why a scan was paused. */
enum class PauseReason { NONE, USER, NETWORK_CHANGED, DATA_CAP, WIFI_ONLY }

/**
 * Result categories (section 9 of the spec). A verdict is only ever derived
 * from real measurements — never fabricated.
 */
enum class Verdict {
    VERIFIED_COMPATIBLE,
    CANDIDATE_NEEDS_TESTING,
    REACHABLE_INCOMPATIBLE,
    UNREACHABLE,
    EXPIRED,
    INSUFFICIENT_DATA
}

/** Classified network error categories (kept machine-readable for stats). */
enum class ErrorCategory {
    NONE,
    DNS,
    TCP_TIMEOUT,
    TCP_REFUSED,
    TCP_RESET,
    TLS_HANDSHAKE,
    TLS_CERT,
    HTTP_TIMEOUT,
    HTTP_ERROR,
    RATE_LIMITED_SUSPECTED,
    NETWORK_CHANGED,
    CANCELLED,
    UNKNOWN
}

/** Export formats supported by the export manager. */
enum class ExportFormat { TXT, CSV, JSON, SKH_IP, SKH_SNI }

/**
 * Immutable configuration of one scan run (persisted with the scan row so it
 * can be resumed after process death).
 */
@Serializable
data class ScanConfig(
    val mode: ScanMode = ScanMode.STANDARD,
    val providers: List<String> = listOf("cloudflare", "imported"),
    val ports: List<Int> = listOf(80, 443),
    val concurrency: Int = 16,
    val timeoutMs: Int = 4_000,
    val samplePerRange: Int = 4,
    val maxTargets: Int = 2_000,
    val wifiOnly: Boolean = false,
    val stopOnNetworkChange: Boolean = true,
    val dataCapMb: Int = 50,
    val retries: Int = 1,
    val deepTopN: Int = 10,
    val stabilityAttempts: Int = 3,
    val downloadTestBytes: Int = 300_000,
    val sniByProvider: Map<String, List<String>> = emptyMap(),
) {
    val dataCapBytes: Long get() = dataCapMb.toLong() * 1_000_000L

    fun sniFor(providerId: String?): List<String> =
        providerId?.let { sniByProvider[it].orEmpty() }.orEmpty()
}

/** One scan target: an IP with its attributed provider (may be null = unknown). */
data class Target(
    val ip: String,
    val providerId: String?,
    val ports: List<Int>,
)

/** Live progress snapshot exposed to the UI. */
data class ScanProgress(
    val scanId: Long,
    val status: ScanStatus,
    val mode: ScanMode,
    val tested: Int,
    val total: Int,
    val reachable: Int,
    val verified: Int,
    val errors: Int,
    val ipPerSec: Float,
    val etaSec: Long,
    val bytesUsed: Long,
    val dataCapBytes: Long,
    val pauseReason: PauseReason,
    val deepPhase: Boolean,
    val startedAtMs: Long,
)

/** Aggregate of one IP test run, later persisted as a ResultEntity row. */
data class ProbeOutcome(
    val ip: String,
    val port: Int,
    val providerId: String?,
    val tcpOk: Boolean,
    val tcpConnectMs: Int?,
    val tlsOk: Boolean? = null,
    val tlsHandshakeMs: Int? = null,
    val tlsVersion: String? = null,
    val certCn: String? = null,
    val certNotAfterMs: Long? = null,
    val certMatched: Boolean? = null,
    val httpOk: Boolean? = null,
    val httpStatus: Int? = null,
    val httpTtfbMs: Int? = null,
    val httpTotalMs: Int? = null,
    val httpVersion: String? = null,
    val downloadKbps: Double? = null,
    val uploadKbps: Double? = null,
    val latencyMinMs: Int? = null,
    val latencyAvgMs: Int? = null,
    val latencyMedianMs: Int? = null,
    val latencyMaxMs: Int? = null,
    val jitterMs: Int? = null,
    val latencySeries: List<Int> = emptyList(),
    val attempts: Int = 1,
    val successes: Int = 0,
    val errorCategory: ErrorCategory = ErrorCategory.NONE,
    val errorMessage: String? = null,
    val bytesUsed: Long = 0L,
    val testedAtMs: Long = 0L,
)

/** Result of one single TCP connect attempt. */
data class TcpAttempt(
    val ok: Boolean,
    val connectMs: Int,
    val category: ErrorCategory,
    val message: String?,
)

/** Result of a TLS probe with a specific SNI (empty SNI = no SNI extension). */
data class TlsProbe(
    val ok: Boolean,
    val handshakeMs: Int,
    val protocol: String?,
    val certCn: String?,
    val certNotAfterMs: Long?,
    val certMatched: Boolean,
    val category: ErrorCategory,
    val message: String?,
)

/** Result of an HTTP(S) probe against a specific host header/SNI + dial IP. */
data class HttpProbe(
    val ok: Boolean,
    val statusCode: Int?,
    val ttfbMs: Int?,
    val totalMs: Int,
    val version: String?,
    val downloadKbps: Double?,
    val bytesReceived: Long,
    val category: ErrorCategory,
    val message: String?,
)

/** Statistics computed from a series of latency samples. */
data class LatencyStats(
    val minMs: Int,
    val avgMs: Int,
    val medianMs: Int,
    val maxMs: Int,
    val jitterMs: Int,
    val successes: Int,
    val attempts: Int,
)

object ModelDefaults {
    /** Result TTL default (72h) — results older than this are marked EXPIRED. */
    const val RESULT_TTL_HOURS = 72

    /** Default scoring weights per spec section 9 (sum = 100). */
    val DEFAULT_WEIGHTS = mapOf(
        "download" to 0.30,
        "stability" to 0.25,
        "latency" to 0.20,
        "jitter" to 0.10,
        "protocol" to 0.15,
    )
}
