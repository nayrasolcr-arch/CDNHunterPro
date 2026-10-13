package com.cdhunter.pro.repo

import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.core.source.RangeFetcher
import com.cdhunter.pro.db.IpRangeDao
import com.cdhunter.pro.db.IpRangeEntity
import com.cdhunter.pro.db.ProviderStateDao
import com.cdhunter.pro.db.ProviderStateEntity
import com.cdhunter.pro.core.util.Cidr
import kotlinx.coroutines.flow.Flow

/**
 * Manages official CDN ranges: fetch → validate → store, plus user-imported
 * CIDR ranges (providerId = null/imported). Never marks private or reserved
 * ranges as CDN ranges.
 */
class RangeRepository(
    private val rangeDao: IpRangeDao,
    private val providerStateDao: ProviderStateDao,
    private val fetcher: RangeFetcher,
) {

    data class UpdateSummary(
        val providerId: String,
        val ok: Boolean,
        val added: Int,
        val droppedInvalid: Int = 0,
        val droppedPrivate: Int = 0,
        val message: String? = null,
    )

    fun observeRanges(): Flow<List<IpRangeEntity>> = rangeDao.observeAll()

    fun observeProviderStates(): Flow<List<ProviderStateEntity>> = providerStateDao.observeAll()

    suspend fun rangesFor(providerId: String): List<IpRangeEntity> =
        rangeDao.allOnce().filter { it.providerId == providerId }

    /** Full snapshot used by the scan engine to build targets. */
    suspend fun rangesSnapshot(): List<IpRangeEntity> = rangeDao.allOnce()

    suspend fun countFor(providerId: String): Int = rangeDao.countFor(providerId)

    /** Fetches and stores the official ranges of all active providers. */
    suspend fun updateProviders(providerIds: List<String>): List<UpdateSummary> {
        val summaries = mutableListOf<UpdateSummary>()
        for (id in providerIds) {
            val def = CdnRegistry.byId(id)
            if (def == null) {
                summaries.add(UpdateSummary(id, false, 0, message = "unknown provider"))
                continue
            }
            summaries.add(updateProvider(def))
        }
        return summaries
    }

    suspend fun updateProvider(def: com.cdhunter.pro.core.registry.CdnProviderDef): UpdateSummary {
        return try {
            val result = fetcher.fetch(def)
            // Replace the stored ranges of this provider atomically.
            rangeDao.deleteFor(def.id)
            val now = System.currentTimeMillis()
            val entities = result.ranges.map { cidr ->
                val parsed = Cidr.parse(cidr)!!
                IpRangeEntity(
                    providerId = def.id,
                    cidr = cidr,
                    family = parsed.family,
                    total = parsed.total,
                    addedAtMs = now,
                )
            }
            val inserted = rangeDao.insertAll(entities).count { it != -1L }
            providerStateDao.upsert(
                ProviderStateEntity(
                    providerId = def.id,
                    active = true,
                    lastUpdatedMs = now,
                    lastUpdateStatus = if (result.errors.isEmpty()) "OK" else "PARTIAL: ${result.errors.joinToString("; ").take(120)}",
                    rangesCount = inserted,
                ),
            )
            UpdateSummary(def.id, true, inserted, result.droppedInvalid, result.droppedPrivate)
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            providerStateDao.upsert(
                ProviderStateEntity(
                    providerId = def.id,
                    active = true,
                    lastUpdatedMs = providerStateDao.get(def.id)?.lastUpdatedMs,
                    lastUpdateStatus = "FAILED: ${e.message?.take(120)}",
                    rangesCount = providerStateDao.get(def.id)?.rangesCount ?: 0,
                ),
            )
            UpdateSummary(def.id, false, 0, message = e.message)
        }
    }

    /** Adds user-imported CIDR ranges (not attributed to any official provider). */
    data class ImportSummary(val added: Int, val duplicates: Int, val invalid: Int)

    suspend fun importCidrs(text: String): ImportSummary {
        var invalid = 0
        var duplicates = 0
        val now = System.currentTimeMillis()
        val entities = mutableListOf<IpRangeEntity>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parsed = Cidr.parse(line)
                if (parsed == null || !com.cdhunter.pro.core.util.IpValidator.isPublicRoutable(parsed.base)) {
                    invalid++
                } else {
                    entities.add(
                        IpRangeEntity(
                            providerId = CdnRegistry.IMPORTED_ID,
                            cidr = "${parsed.base}/${parsed.prefix}",
                            family = parsed.family,
                            total = parsed.total,
                            addedAtMs = now,
                        ),
                    )
                }
            }
        val results = rangeDao.insertAll(entities)
        duplicates = results.count { it == -1L }
        return ImportSummary(entities.size - duplicates, duplicates, invalid)
    }

    suspend fun clearImportedRanges() = rangeDao.deleteFor(CdnRegistry.IMPORTED_ID)

    suspend fun clearAll() {
        rangeDao.clear()
        providerStateDao.clear()
    }
}
