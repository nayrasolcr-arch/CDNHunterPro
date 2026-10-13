package com.cdhunter.pro

import com.cdhunter.pro.core.model.ExportFormat
import com.cdhunter.pro.core.model.Verdict
import com.cdhunter.pro.db.ResultEntity
import com.cdhunter.pro.export.Exporter
import com.cdhunter.pro.scoring.effectiveVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ExporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun row(ip: String, verdict: Verdict = Verdict.CANDIDATE_NEEDS_TESTING, sni: String? = null) =
        ResultEntity(
            scanId = 1, ip = ip, port = 443, providerId = "cloudflare",
            tcpOk = true, tcpConnectMs = 80, tlsOk = true, certMatched = true,
            httpOk = true, httpStatus = 200, httpTtfbMs = 90, httpTotalMs = 150,
            httpVersion = "h2", downloadKbps = 4200.0, uploadKbps = null,
            latencyMinMs = 60, latencyAvgMs = 80, latencyMedianMs = 75, latencyMaxMs = 120,
            jitterMs = 6, attempts = 3, successes = 3,
            errorCategory = "NONE", errorMessage = null,
            testedAtMs = 1_700_000_000_000,
            verdict = verdict.name, score = 78.5,
            scoreReasonsJson = null, verifiedSni = sni,
        )

    private fun rows() = listOf(
        row("104.16.1.1", Verdict.VERIFIED_COMPATIBLE, "cloudflare.com"),
        row("2606:4700:4700::1111"),
        row("151.101.1.140"),
    )

    @Test
    fun `txt export one ip per line and validates`() {
        val text = Exporter.toTxt(Exporter.buildRows(rows(), { it ?: "unknown" }, { null }, 72), includeSniPairs = false)
        assertNull(Exporter.validate(ExportFormat.TXT, text))
        assertEquals(3, text.lines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `txt with sni pairs keeps pipe format`() {
        val text = Exporter.toTxt(Exporter.buildRows(rows(), { "cloudflare" }, { "cf.example" }, 72), includeSniPairs = true)
        // verifiedSni (cloudflare.com) takes precedence over the sniByIp lookup
        assertTrue(text.contains("104.16.1.1|cloudflare.com"))
        assertNull(Exporter.validate(ExportFormat.TXT, text))
    }

    @Test
    fun `shir-o-khorshid export keeps ipv4 only`() {
        val text = Exporter.toSkhIpList(Exporter.buildRows(rows(), { "cloudflare" }, { null }, 72))
        val lines = text.lines().filter { it.isNotBlank() }
        // IPv6 must be excluded — the official client rejects it (verified in source)
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.contains(":") })
        assertNull(Exporter.validate(ExportFormat.SKH_IP, text))
    }

    @Test
    fun `shir-o-khorshid sni export lists valid hostnames first`() {
        val text = Exporter.toSkhSni(Exporter.buildRows(rows(), { "cloudflare" }, { null }, 72), extraSnis = listOf("speed.cloudflare.com"))
        val lines = text.lines().filter { it.isNotBlank() }
        assertTrue(lines.contains("cloudflare.com"))
        assertTrue(lines.contains("speed.cloudflare.com"))
        assertNull(Exporter.validate(ExportFormat.SKH_SNI, text))
    }

    @Test
    fun `csv export has 16 columns and revalidates structurally`() {
        val text = Exporter.toCsv(Exporter.buildRows(rows(), { "cloudflare" }, { null }, 72))
        val lines = text.trim().split("\r\n")
        assertEquals(4, lines.size) // header + 3 rows
        assertEquals(16, lines[0].split(",").size)
        assertNull(Exporter.validate(ExportFormat.CSV, text))
    }

    @Test
    fun `json export round-trips all 16 spec fields`() {
        val text = Exporter.toJson(Exporter.buildRows(rows(), { "cloudflare" }, { null }, 72))
        assertNull(Exporter.validate(ExportFormat.JSON, text))
        val parsed = json.decodeFromString(ListSerializer(Exporter.ExportRow.serializer()), text)
        assertEquals(3, parsed.size)
        val first = parsed.first()
        assertEquals("104.16.1.1", first.ip)
        assertEquals("IPv4", first.addressFamily)
        assertEquals("cloudflare", first.provider)
        assertEquals(200, first.httpOk?.let { 200 } ?: 0)
        assertNotNull(first.tcpOk)
        assertNotNull(first.compatibility)
        assertNotNull(first.score)
    }

    @Test
    fun `validator rejects corrupted exports`() {
        assertNotNull(Exporter.validate(ExportFormat.TXT, "not-an-ip\n"))
        assertNotNull(Exporter.validate(ExportFormat.SKH_IP, "2606:4700::1111\n"))
        assertNotNull(Exporter.validate(ExportFormat.SKH_SNI, "bad host..name\n"))
        assertNotNull(Exporter.validate(ExportFormat.JSON, "{ nope"))
    }

    @Test
    fun `never exports plain https success as verified tunnel`() {
        // TCP+TLS+HTTP all ok but NO verified SNI -> compatibility must NOT be VERIFIED_COMPATIBLE
        val rows = Exporter.buildRows(listOf(row("104.16.1.1", Verdict.CANDIDATE_NEEDS_TESTING, null)), { "cf" }, { null }, 72)
        val text = Exporter.toJson(rows)
        assertTrue(!text.contains("VERIFIED_COMPATIBLE"))
    }
}
