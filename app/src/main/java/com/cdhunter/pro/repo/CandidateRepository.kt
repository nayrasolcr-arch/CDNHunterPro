package com.cdhunter.pro.repo

import com.cdhunter.pro.core.util.IpValidator
import com.cdhunter.pro.db.CandidateDao
import com.cdhunter.pro.db.CandidateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Imports and stores candidate IPs from TXT/CSV/manual input.
 * Validation: strict IP check + public-routability; duplicates are rejected
 * by the unique DB index and reported honestly.
 */
class CandidateRepository(private val candidateDao: CandidateDao) {

    data class ImportSummary(
        val valid: Int,
        val added: Int,
        val duplicates: Int,
        val invalid: Int,
    )

    fun observeCount(): kotlinx.coroutines.flow.Flow<Int> =
        kotlinx.coroutines.flow.flow {
            emit(candidateDao.count())
        }

    suspend fun countAll(): Int = candidateDao.count()

    suspend fun countImported(): Int = candidateDao.countImported()

    suspend fun allOnce(): List<CandidateEntity> = candidateDao.allOnce()

    /** Plain TXT: one IP per line (tolerates commas/semicolons like the SK client). */
    suspend fun importTxt(text: String): ImportSummary =
        importLines(text.split(Regex("[\\s,;]+")))

    /** CSV: detects an IP column (header or first parseable column per row). */
    suspend fun importCsv(text: String): ImportSummary {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return ImportSummary(0, 0, 0, 0)
        // Optional header detection.
        val body = if (lines.first().lowercase().contains("ip")) lines.drop(1) else lines
        val ips = body.mapNotNull { row ->
            row.split(",").firstOrNull { col ->
                IpValidator.isValidIp(col.trim())
            }?.trim()
        }
        return importLines(ips)
    }

    /** JSON array of strings or objects containing an "ip" field. */
    suspend fun importJson(text: String): ImportSummary = try {
        val json = Json { ignoreUnknownKeys = true }
        val ips = when (val el = json.parseToJsonElement(text)) {
            is JsonArray -> el.mapNotNull { item ->
                when (item) {
                    is kotlinx.serialization.json.JsonPrimitive -> item.content
                    is JsonObject -> item["ip"]?.jsonPrimitive?.content
                    else -> null
                }
            }
            is JsonObject -> listOfNotNull(el["ip"]?.jsonPrimitive?.content)
            else -> emptyList()
        }
        importLines(ips)
    } catch (_: Exception) {
        ImportSummary(0, 0, 0, 1)
    }

    suspend fun addManual(ip: String): ImportSummary = importLines(listOf(ip))

    private suspend fun importLines(tokens: List<String>): ImportSummary {
        var invalid = 0
        var duplicates = 0
        var added = 0
        val now = System.currentTimeMillis()
        val entities = mutableListOf<CandidateEntity>()
        for (token in tokens) {
            val ip = token.trim()
            if (ip.isEmpty()) continue
            if (!IpValidator.isValidIp(ip) || !IpValidator.isPublicRoutable(ip)) {
                invalid++
                continue
            }
            entities.add(
                CandidateEntity(
                    ip = ip,
                    family = IpValidator.family(ip),
                    providerId = null,
                    origin = "IMPORT_TXT",
                    addedAtMs = now,
                ),
            )
        }
        if (entities.isNotEmpty()) {
            val results = candidateDao.insertAll(entities)
            duplicates = results.count { it == -1L }
            added = entities.size - duplicates
        }
        return ImportSummary(entities.size, added, duplicates, invalid)
    }

    suspend fun clearImported() = candidateDao.clearImported()

    suspend fun clearAll() = candidateDao.clear()
}
