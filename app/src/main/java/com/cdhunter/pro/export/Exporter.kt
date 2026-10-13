package com.cdhunter.pro.export

import com.cdhunter.pro.core.model.ExportFormat
import com.cdhunter.pro.core.util.IpValidator
import com.cdhunter.pro.db.ResultEntity
import com.cdhunter.pro.scoring.effectiveVerdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Pure export builders + format validators (spec section 15).
 *
 * Hard rule enforced here: a plain HTTPS success is NEVER exported as a
 * "verified tunnel" — the compatibility column only reports what was actually
 * measured, and "verified" requires a matched-cert IP×SNI handshake.
 */
object Exporter {

    @Serializable
    data class ExportRow(
        val ip: String,
        val addressFamily: String,
        val provider: String,
        val source: String,
        val testedAtMs: Long,
        val tcpOk: Boolean,
        val tlsOk: Boolean?,
        val httpOk: Boolean?,
        val latencyMs: Int?,
        val jitterMs: Int?,
        val downloadKbps: Double?,
        val successRatePercent: Double?,
        val sniTested: String?,
        val compatibility: String,
        val score: Double?,
        val failReason: String?,
    )

    private val json = Json { prettyPrint = true }

    fun buildRows(
        results: List<ResultEntity>,
        providerNames: (String?) -> String,
        sniByIp: (String) -> String?,
        ttlHours: Int,
    ): List<ExportRow> =
        results.map { r ->
            val verdict = effectiveVerdict(r, ttlHours)
            ExportRow(
                ip = r.ip,
                addressFamily = if (IpValidator.isValidIpv6(r.ip)) "IPv6" else "IPv4",
                provider = providerNames(r.providerId),
                source = r.providerId?.let { "official-ranges:$it" } ?: "imported",
                testedAtMs = r.testedAtMs,
                tcpOk = r.tcpOk,
                tlsOk = r.tlsOk,
                httpOk = r.httpOk,
                latencyMs = r.latencyAvgMs,
                jitterMs = r.jitterMs,
                downloadKbps = r.downloadKbps,
                successRatePercent = if (r.attempts > 0) (r.successes * 100.0 / r.attempts) else null,
                sniTested = r.verifiedSni ?: sniByIp(r.ip),
                compatibility = verdict.name,
                score = r.score,
                failReason = r.errorMessage ?: r.errorCategory?.let { if (it == "NONE") null else it },
            )
        }

    // ------------------------------------------------------------ TXT / SKH

    /** TXT: one IP per line, optionally "ip|sni" when a verified SNI exists. */
    fun toTxt(rows: List<ExportRow>, includeSniPairs: Boolean): String =
        rows.joinToString("\n") { r ->
            if (includeSniPairs && !r.sniTested.isNullOrBlank()) "${r.ip}|${r.sniTested}" else r.ip
        }

    /**
     * Shir o Khorshid IP list — verified against the official client source
     * (TunnelManager.parseCdnFrontingCustomIpCandidates): IPv4 or IPv4-CIDR
     * only, one per line, duplicates dropped. IPv6 is intentionally excluded
     * because the client rejects it.
     */
    fun toSkhIpList(rows: List<ExportRow>): String =
        rows.map { it.ip }
            .filter { IpValidator.isValidIpv4(it) }
            .distinct()
            .joinToString("\n")

    /**
     * Shir o Khorshid SNI field — verified against
     * parseCdnFrontingCustomSniList: the client uses only the FIRST valid
     * hostname, so the best-scoring verified SNI is exported first.
     */
    fun toSkhSni(rows: List<ExportRow>, extraSnis: List<String> = emptyList()): String =
        (rows.mapNotNull { it.sniTested } + extraSnis)
            .filter { com.cdhunter.pro.core.util.HostnameValidator.isValid(it) }
            .distinct()
            .joinToString("\n")

    // ---------------------------------------------------------------- CSV

    fun toCsv(rows: List<ExportRow>): String {
        val header = listOf(
            "ip", "address_family", "provider", "source", "tested_at_ms",
            "tcp_ok", "tls_ok", "http_ok", "latency_ms", "jitter_ms",
            "download_kbps", "success_rate_percent", "sni_tested",
            "compatibility", "score", "fail_reason",
        )
        return buildString {
            append(header.joinToString(",")).append("\r\n")
            for (r in rows) {
                append(csvEscape(r.ip)).append(",")
                append(csvEscape(r.addressFamily)).append(",")
                append(csvEscape(r.provider)).append(",")
                append(csvEscape(r.source)).append(",")
                append(r.testedAtMs).append(",")
                append(r.tcpOk).append(",")
                append(csvEscape(r.tlsOk?.toString())).append(",")
                append(csvEscape(r.httpOk?.toString())).append(",")
                append(csvEscape(r.latencyMs?.toString())).append(",")
                append(csvEscape(r.jitterMs?.toString())).append(",")
                append(csvEscape(r.downloadKbps?.toString())).append(",")
                append(csvEscape(r.successRatePercent?.toString())).append(",")
                append(csvEscape(r.sniTested)).append(",")
                append(csvEscape(r.compatibility)).append(",")
                append(csvEscape(r.score?.toString())).append(",")
                append(csvEscape(r.failReason)).append("\r\n")
            }
        }
    }

    private fun csvEscape(v: String?): String {
        if (v == null) return ""
        return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v
    }

    // --------------------------------------------------------------- JSON

    fun toJson(rows: List<ExportRow>): String =
        json.encodeToString(ListSerializer(ExportRow.serializer()), rows)

    // ---------------------------------------------------------- validation

    /**
     * Validates generated output by RE-PARSING it. Returns null when valid,
     * or a short error description. Used before every export action.
     */
    fun validate(format: ExportFormat, text: String): String? {
        return when (format) {
        ExportFormat.TXT -> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            val bad = lines.count { line ->
                val ip = line.split("|").first().trim()
                !IpValidator.isValidIp(ip)
            }
            if (bad > 0) "$bad invalid IP lines" else if (lines.isEmpty()) "empty" else null
        }
        ExportFormat.SKH_IP -> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            val bad = lines.count { !IpValidator.isValidIpv4(it.trim()) }
            if (bad > 0) "$bad lines are not IPv4 (client accepts IPv4 only)" else if (lines.isEmpty()) "empty" else null
        }
        ExportFormat.SKH_SNI -> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            val bad = lines.count { !com.cdhunter.pro.core.util.HostnameValidator.isValid(it.trim()) }
            if (bad > 0) "$bad invalid hostnames" else if (lines.isEmpty()) "empty" else null
        }
        ExportFormat.CSV -> {
            try {
                val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
                if (lines.size < 2) return "no data rows"
                val cols = lines[0].split(",").size
                // naive but strict-enough structural check on unquoted rows
                val bad = lines.drop(1).count { it.split(",").size != cols }
                if (bad > 0) "$bad rows with wrong column count" else null
            } catch (e: Exception) {
                e.message
            }
        }
        ExportFormat.JSON -> try {
            json.decodeFromString(ListSerializer(ExportRow.serializer()), text)
            null
        } catch (e: Exception) {
            e.message?.take(120)
        }
        }
    }
}
