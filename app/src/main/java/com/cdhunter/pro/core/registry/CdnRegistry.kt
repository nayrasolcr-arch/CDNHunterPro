package com.cdhunter.pro.core.registry

/**
 * Static definition of a CDN provider. Range endpoints are the OFFICIAL
 * published IP-range lists of each provider. No IP is ever attributed to a
 * provider without one of these official sources or an explicit user import
 * (which is stored as provider "unknown/imported").
 */
data class RangeEndpoint(
    val url: String,
    val label: String,
)

data class CdnProviderDef(
    val id: String,
    val displayName: String,
    val homepage: String,
    val endpoints: List<RangeEndpoint>,
    val format: SourceFormat,
    /** SNI hostnames used for compatibility tests (user-extensible). */
    val defaultTestSnis: List<String>,
    /** Provider-specific CDN ports (Cloudflare supports non-standard HTTPS ports). */
    val extraHttpsPorts: List<Int> = emptyList(),
    /** Whether a controlled upload-speed destination exists for this provider. */
    val supportsUploadTest: Boolean = false,
    /** Download test URL template; {ip} is replaced by the SNI host. */
    val downloadPath: String? = null,
    val notes: String = "",
)

enum class SourceFormat { TEXT_LINES, GOOG_JSON, AWS_JSON, AZURE_JSON, FASTLY_JSON }

object CdnRegistry {

    const val IMPORTED_ID = "imported"
    const val IMPORTED_NAME = "Imported / unknown"

    val cloudflare = CdnProviderDef(
        id = "cloudflare",
        displayName = "Cloudflare",
        homepage = "https://www.cloudflare.com",
        endpoints = listOf(
            RangeEndpoint("https://www.cloudflare.com/ips-v4", "IPv4"),
            RangeEndpoint("https://www.cloudflare.com/ips-v6", "IPv6"),
        ),
        format = SourceFormat.TEXT_LINES,
        // These hostnames are served by Cloudflare edges and commonly usable
        // for CDN-fronting tests; users can extend the list in the UI.
        defaultTestSnis = listOf(
            "cloudflare.com",
            "www.cloudflare.com",
            "speed.cloudflare.com",
        ),
        extraHttpsPorts = listOf(2053, 2083, 2087, 2096, 8443),
        supportsUploadTest = true,
        downloadPath = "/cdn-cgi/trace",
    )

    val google = CdnProviderDef(
        id = "google",
        displayName = "Google Cloud CDN",
        homepage = "https://cloud.google.com/cdn",
        endpoints = listOf(
            RangeEndpoint("https://www.gstatic.com/ipranges/goog.json", "goog.json"),
        ),
        format = SourceFormat.GOOG_JSON,
        defaultTestSnis = listOf("www.google.com", "www.gstatic.com", "storage.googleapis.com"),
        notes = "goog.json covers all Google frontends (GFE), not only Cloud CDN.",
    )

    val cloudfront = CdnProviderDef(
        id = "cloudfront",
        displayName = "Amazon CloudFront",
        homepage = "https://aws.amazon.com/cloudfront/",
        endpoints = listOf(
            RangeEndpoint("https://ip-ranges.amazonaws.com/ip-ranges.json", "ip-ranges.json"),
        ),
        format = SourceFormat.AWS_JSON,
        // A working SNI needs a real distribution domain — user must add one.
        defaultTestSnis = emptyList(),
        notes = "Add your distribution domain (dXXXX.cloudfront.net) as SNI for verified tests.",
    )

    val azure = CdnProviderDef(
        id = "azure",
        displayName = "Microsoft Azure CDN",
        homepage = "https://azure.microsoft.com/products/cdn",
        endpoints = listOf(
            RangeEndpoint(
                // Verified live (HTTP 200). The file name is versioned weekly
                // upstream; if fetching fails, update the URL from
                // https://www.microsoft.com/en-us/download/details.aspx?id=56519
                "https://download.microsoft.com/download/7/1/d/71d86715-5596-4529-9b13-da13a5de5b63/" +
                    "ServiceTags_Public_20261005.json",
                "ServiceTags (AzureFrontDoor/Cdn)",
            ),
        ),
        format = SourceFormat.AZURE_JSON,
        defaultTestSnis = emptyList(),
        notes = "The ServiceTags file URL is versioned weekly upstream; keep it updated in advanced settings if fetching fails.",
    )

    val akamai = CdnProviderDef(
        id = "akamai",
        displayName = "Akamai",
        homepage = "https://www.akamai.com",
        endpoints = listOf(
            RangeEndpoint("https://ipranges.akamai.com/akamai-ipv4.txt", "IPv4"),
            RangeEndpoint("https://ipranges.akamai.com/akamai-ipv6.txt", "IPv6"),
        ),
        format = SourceFormat.TEXT_LINES,
        // These verify names are the same ones the Shir o Khorshid client uses
        // for Akamai edges.
        defaultTestSnis = listOf("a248.e.akamai.net", "a.akamaized.net", "www.akamai.com"),
    )

    val fastly = CdnProviderDef(
        id = "fastly",
        displayName = "Fastly",
        homepage = "https://www.fastly.com",
        endpoints = listOf(
            RangeEndpoint("https://api.fastly.com/public-ip-list", "public-ip-list"),
        ),
        format = SourceFormat.FASTLY_JSON,
        defaultTestSnis = listOf("www.fastly.com", "fastly.com", "api.fastly.com"),
    )

    val ALL: List<CdnProviderDef> = listOf(cloudflare, google, cloudfront, azure, akamai, fastly)

    fun byId(id: String): CdnProviderDef? = ALL.firstOrNull { it.id == id }

    fun displayName(id: String?): String =
        when (id) {
            null -> "Unknown"
            IMPORTED_ID -> IMPORTED_NAME
            else -> byId(id)?.displayName ?: id
        }
}
