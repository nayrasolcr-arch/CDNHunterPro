package com.cdhunter.pro.repo

import android.content.Context
import com.cdhunter.pro.core.util.Cidr
import com.cdhunter.pro.core.util.IpValidator
import com.cdhunter.pro.db.IpRangeDao
import com.cdhunter.pro.db.IpRangeEntity
import com.cdhunter.pro.db.ProviderStateDao
import com.cdhunter.pro.db.ProviderStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Seeds the ip_ranges table from the OFFICIAL ranges bundled inside the APK
 * (assets/bundled_ranges.json — fetched from each provider's official endpoint
 * at build time, see scripts/build_bundled_ranges.py).
 *
 * Why: on a fresh install the ranges table was empty, so the very first scan
 * had zero targets and silently produced no results. With the bundled
 * snapshot the scanner always has real, official targets on first launch,
 * even when the live endpoints are unreachable from the user's network.
 * Live sync (CdnSelectScreen → "Update now") remains the primary source and
 * replaces bundled rows provider-by-provider on success.
 *
 * Every bundled CIDR goes through the exact same validation pipeline as live
 * sync: CIDR-parse → public-routable → dedupe-by-unique-index. Provider state
 * rows are explicitly marked BUNDLED so the UI never presents them as a
 * fresh live fetch.
 */
class RangeSeeder(
    private val rangeDao: IpRangeDao,
    private val providerStateDao: ProviderStateDao,
) {

    @Serializable
    private data class BundledProvider(val id: String, val cidrs: List<String> = emptyList())

    @Serializable
    private data class BundledAsset(
        val fetchedAt: String = "",
        val providers: List<BundledProvider> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Number of ranges available in the bundled snapshot (0 if unreadable). */
    suspend fun bundledProviderIds(context: Context): List<String> = loadAsset(context)
        ?.providers
        ?.filter { it.cidrs.isNotEmpty() }
        ?.map { it.id }
        ?: emptyList()

    /** Seeds only when the ranges table is completely empty. Returns count inserted. */
    suspend fun seedIfNeeded(context: Context): Int {
        if (rangeDao.countAll() > 0) return 0
        return seed(context)
    }

    /** Force-seeds bundled ranges for providers that currently have none. */
    suspend fun seed(context: Context): Int {
        val asset = loadAsset(context) ?: return 0
        val now = System.currentTimeMillis()
        var total = 0
        for (provider in asset.providers) {
            if (provider.cidrs.isEmpty()) continue
            if (rangeDao.countFor(provider.id) > 0) continue
            val entities = provider.cidrs.mapNotNull { cidr ->
                val parsed = Cidr.parse(cidr) ?: return@mapNotNull null
                if (!IpValidator.isPublicRoutable(parsed.base)) return@mapNotNull null
                IpRangeEntity(
                    providerId = provider.id,
                    cidr = "${parsed.base}/${parsed.prefix}",
                    family = parsed.family,
                    total = parsed.total,
                    addedAtMs = now,
                )
            }
            if (entities.isEmpty()) continue
            rangeDao.insertAll(entities)
            total += entities.size
            providerStateDao.upsert(
                ProviderStateEntity(
                    providerId = provider.id,
                    active = true,
                    lastUpdatedMs = now,
                    lastUpdateStatus = "BUNDLED (official snapshot ${asset.fetchedAt})",
                    rangesCount = entities.size,
                ),
            )
        }
        return total
    }

    private suspend fun loadAsset(context: Context): BundledAsset? = runCatching {
        val raw = withContext(Dispatchers.IO) {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }
        json.decodeFromString(BundledAsset.serializer(), raw)
    }.getOrNull()

    private companion object {
        const val ASSET_NAME = "bundled_ranges.json"
    }
}
