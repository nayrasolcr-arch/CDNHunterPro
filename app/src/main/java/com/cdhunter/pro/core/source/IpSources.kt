package com.cdhunter.pro.core.source

import com.cdhunter.pro.core.model.ErrorCategory
import com.cdhunter.pro.core.registry.CdnProviderDef
import com.cdhunter.pro.core.registry.SourceFormat
import com.cdhunter.pro.core.util.Cidr
import com.cdhunter.pro.core.util.IpValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when an official range source cannot be fetched or parsed. */
class SourceFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Pure parsers for the official source formats. All parsers are side-effect
 * free so they can be unit-tested against bundled fixtures without network.
 */
object SourceParsers {

    private val json = Json { ignoreUnknownKeys = true }

    /** Plain CIDR-per-line text (Cloudflare, Akamai). */
    fun parseTextLines(body: String): List<String> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .filter { Cidr.parse(it) != null }
            .toList()

    /** Google goog.json: object with a "prefixes" array of ipv4Prefix / ipv6Prefix. */
    fun parseGoogJson(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val prefixes = root["prefixes"]?.jsonArray ?: return emptyList()
        return prefixes.mapNotNull { el ->
            val o = el.jsonObject
            o["ipv4Prefix"]?.jsonPrimitive?.content
                ?: o["ipv6Prefix"]?.jsonPrimitive?.content
        }
    }

    /** AWS ip-ranges.json filtered to CloudFront service (v4 + v6). */
    fun parseAwsJson(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val out = mutableListOf<String>()
        root["prefixes"]?.jsonArray?.forEach { el ->
            val o = el.jsonObject
            val service = o["service"]?.jsonPrimitive?.content ?: ""
            val prefix = o["ip_prefix"]?.jsonPrimitive?.content
            if (prefix != null && service.equals("CLOUDFRONT", ignoreCase = true)) out.add(prefix)
        }
        root["ipv6_prefixes"]?.jsonArray?.forEach { el ->
            val o = el.jsonObject
            val service = o["service"]?.jsonPrimitive?.content ?: ""
            val prefix = o["ipv6_prefix"]?.jsonPrimitive?.content
            if (prefix != null && service.equals("CLOUDFRONT", ignoreCase = true)) out.add(prefix)
        }
        return out
    }

    /**
     * Azure ServiceTags JSON: an object with a "values" array; each value has a
     * name (e.g. AzureFrontDoor.Frontend) and properties.addressPrefixes.
     * Only FrontDoor/Cdn services are attributed to Azure CDN — the rest is
     * ignored on purpose.
     */
    fun parseAzureJson(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val values = root["values"]?.jsonArray ?: return emptyList()
        val out = mutableListOf<String>()
        values.forEach { el ->
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.content ?: ""
            if (name.contains("FrontDoor", ignoreCase = true) || name.contains("Cdn", ignoreCase = true)) {
                val props = o["properties"]?.jsonObject ?: return@forEach
                props["addressPrefixes"]?.jsonArray?.forEach { p ->
                    out.add(p.jsonPrimitive.content)
                }
            }
        }
        return out
    }

    /** Fastly public-ip-list: object with "addresses" (v4) and "ipv6" arrays. */
    fun parseFastlyJson(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val out = mutableListOf<String>()
        root["addresses"]?.jsonArray?.forEach { out.add(it.jsonPrimitive.content) }
        root["ipv6"]?.jsonArray?.forEach { out.add(it.jsonPrimitive.content) }
        return out
    }

    fun parse(format: SourceFormat, body: String): List<String> = try {
        when (format) {
            SourceFormat.TEXT_LINES -> parseTextLines(body)
            SourceFormat.GOOG_JSON -> parseGoogJson(body)
            SourceFormat.AWS_JSON -> parseAwsJson(body)
            SourceFormat.AZURE_JSON -> parseAzureJson(body)
            SourceFormat.FASTLY_JSON -> parseFastlyJson(body)
        }
    } catch (_: Exception) {
        // Malformed upstream payloads must never crash the sync loop.
        emptyList()
    }
}

/**
 * Fetches and validates official provider ranges.
 *
 * Validation pipeline (per spec): parse → CIDR-valid → public-routable →
 * deduplicate → keep. Private/reserved ranges are silently dropped and
 * counted, never presented as CDN ranges.
 */
class RangeFetcher(private val client: OkHttpClient) {

    data class FetchResult(
        val providerId: String,
        val ranges: List<String>,
        val droppedInvalid: Int,
        val droppedPrivate: Int,
        val totalBeforeDedup: Int,
        val errors: List<String>,
    )

    suspend fun fetch(def: CdnProviderDef): FetchResult {
        val all = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (endpoint in def.endpoints) {
            try {
                val body = withContext(Dispatchers.IO) { download(endpoint.url) }
                all += SourceParsers.parse(def.format, body)
            } catch (e: Exception) {
                errors.add("${endpoint.label}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (all.isEmpty() && errors.isNotEmpty()) {
            throw SourceFetchException(errors.joinToString("; "), errors.firstOrNull()?.let { IOException(it) })
        }
        val valid = mutableListOf<String>()
        var droppedInvalid = 0
        var droppedPrivate = 0
        for (r in all) {
            val parsed = Cidr.parse(r)
            if (parsed == null) {
                droppedInvalid++
                continue
            }
            if (!IpValidator.isPublicRoutable(parsed.base)) {
                droppedPrivate++
                continue
            }
            valid.add("${parsed.base}/${parsed.prefix}")
        }
        val deduped = Cidr.dedupeRanges(valid)
        return FetchResult(
            providerId = def.id,
            ranges = deduped,
            droppedInvalid = droppedInvalid,
            droppedPrivate = droppedPrivate,
            totalBeforeDedup = all.size,
            errors = errors,
        )
    }

    private fun download(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CDNHunterPro/1.0 (range sync)")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw SourceFetchException("HTTP ${resp.code}")
            val body = resp.body ?: throw SourceFetchException("empty body")
            return body.string()
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
