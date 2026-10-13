package com.cdhunter.pro.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Runtime state of one CDN provider (active flag, last source update). */
@Entity(tableName = "provider_state")
data class ProviderStateEntity(
    @PrimaryKey val providerId: String,
    val active: Boolean = true,
    val lastUpdatedMs: Long? = null,
    val lastUpdateStatus: String? = null,
    val rangesCount: Int = 0,
    val sourceUrlOverride: String? = null,
)

/** One official CIDR range (providerId null = user-imported range). */
@Entity(
    tableName = "ip_ranges",
    indices = [Index(value = ["providerId", "cidr"], unique = true)],
)
data class IpRangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: String?,
    val cidr: String,
    val family: Int,
    val total: Long,
    val addedAtMs: Long,
)

/** A discovered/imported candidate IP. Unique index prevents duplicates. */
@Entity(tableName = "candidates", indices = [Index(value = ["ip"], unique = true)])
data class CandidateEntity(
    @PrimaryKey val ip: String,
    val family: Int,
    val providerId: String?,
    /** SOURCE_SAMPLE | IMPORT_TXT | IMPORT_CSV | MANUAL | RANGE_EXPANSION */
    val origin: String,
    val addedAtMs: Long,
)

/** One scan run — also acts as scan history. */
@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val status: String,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val configJson: String,
    val totalTargets: Int = 0,
    val testedCount: Int = 0,
    val reachableCount: Int = 0,
    val verifiedCount: Int = 0,
    val errorCount: Int = 0,
    val bytesUsed: Long = 0,
    /** Index into the persisted target list for pause/resume. */
    val cursorIndex: Int = 0,
    val heartbeatAtMs: Long = 0,
    val networkTokenAtStart: String? = null,
    val pauseReason: String? = null,
    val note: String? = null,
)

/** Test result of one (scanId, ip, port) probe combination. */
@Entity(
    tableName = "results",
    indices = [
        Index(value = ["scanId", "ip", "port"], unique = true),
        Index(value = ["scanId", "verdict"]),
    ],
)
data class ResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanId: Long,
    val ip: String,
    val port: Int,
    val providerId: String?,
    val tcpOk: Boolean,
    val tcpConnectMs: Int? = null,
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
    val latencySeriesJson: String? = null,
    val attempts: Int = 1,
    val successes: Int = 0,
    val errorCategory: String? = null,
    val errorMessage: String? = null,
    val testedAtMs: Long,
    val verdict: String,
    val score: Double? = null,
    val scoreReasonsJson: String? = null,
    /** SNI that produced a verified handshake, if any. */
    val verifiedSni: String? = null,
)

/** Result of one IP×SNI combination test. */
@Entity(
    tableName = "sni_results",
    indices = [Index(value = ["ip", "sni"], unique = true)],
)
data class SniResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ip: String,
    val sni: String,
    val providerId: String?,
    val ok: Boolean,
    val certMatched: Boolean,
    val tlsVersion: String? = null,
    val handshakeMs: Int? = null,
    val errorCategory: String? = null,
    val errorMessage: String? = null,
    val testedAtMs: Long,
    val expiresAtMs: Long,
)

/** History of exports produced by the user. */
@Entity(tableName = "export_log")
data class ExportLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val format: String,
    val createdAtMs: Long,
    val itemCount: Int,
    val detail: String? = null,
)

/** Local error report (never contains credentials or browsing data). */
@Entity(tableName = "error_log")
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val level: String,
    val tag: String,
    val message: String,
)

/** Simple key-value user settings (kept in Room per spec section 14). */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)
