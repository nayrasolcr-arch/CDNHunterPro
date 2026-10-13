package com.cdhunter.pro.repo

import com.cdhunter.pro.core.model.ModelDefaults
import com.cdhunter.pro.core.model.ScanMode
import com.cdhunter.pro.db.SettingsDao
import com.cdhunter.pro.db.SettingsEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * User settings stored in Room (key-value), cached in memory.
 * All scan defaults live here so scans are fully reproducible.
 */
class SettingsRepository(private val dao: SettingsDao) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _all = MutableStateFlow<Map<String, String>>(emptyMap())
    val all: StateFlow<Map<String, String>> = _all

    suspend fun load() {
        val rows = dao.allOnce()
        _all.value = rows.associate { it.key to it.value }
    }

    suspend fun set(key: String, value: String) {
        dao.upsert(SettingsEntity(key, value))
        _all.value = _all.value + (key to value)
    }

    private fun str(key: String, def: String): String = _all.value[key] ?: def
    private fun int(key: String, def: Int): Int = _all.value[key]?.toIntOrNull() ?: def
    private fun bool(key: String, def: Boolean): Boolean =
        _all.value[key]?.let { it == "true" } ?: def
    private fun double(key: String, def: Double): Double =
        _all.value[key]?.toDoubleOrNull() ?: def

    // ---- selections ----
    fun selectedProviders(): List<String> =
        json.decodeFromString(ListSerializer(String.serializer()), str("selected_providers", "[\"cloudflare\",\"imported\"]"))

    suspend fun setSelectedProviders(ids: List<String>) =
        set("selected_providers", json.encodeToString(ListSerializer(String.serializer()), ids))

    fun scanMode(): ScanMode = runCatching { ScanMode.valueOf(str("scan_mode", "STANDARD")) }
        .getOrDefault(ScanMode.STANDARD)

    suspend fun setScanMode(mode: ScanMode) = set("scan_mode", mode.name)

    // ---- network ----
    fun concurrency(): Int = int("concurrency", 16).coerceIn(1, 64)
    fun timeoutMs(): Int = int("timeout_ms", 4_000).coerceIn(500, 20_000)
    fun wifiOnly(): Boolean = bool("wifi_only", false)
    fun stopOnNetworkChange(): Boolean = bool("stop_on_network_change", true)
    fun dataCapMb(): Int = int("data_cap_mb", 50).coerceIn(1, 2_000)
    fun retries(): Int = int("retries", 1).coerceIn(0, 5)
    fun samplePerRange(): Int = int("sample_per_range", 4).coerceIn(1, 64)
    fun maxTargets(): Int = int("max_targets", 2_000).coerceIn(50, 20_000)
    fun deepTopN(): Int = int("deep_top_n", 10).coerceIn(1, 50)
    fun downloadTestBytes(): Int = int("download_test_bytes", 300_000).coerceIn(50_000, 5_000_000)
    fun resultTtlHours(): Int = int("result_ttl_hours", ModelDefaults.RESULT_TTL_HOURS).coerceIn(1, 24 * 30)
    fun ports(): List<Int> = buildList {
        if (bool("use_port_80", true)) add(80)
        if (bool("use_port_443", true)) add(443)
    }.ifEmpty { listOf(443) }

    // ---- weights ----
    fun weight(key: String): Double = double("weight_$key", ModelDefaults.DEFAULT_WEIGHTS[key] ?: 0.0)
    suspend fun setWeight(key: String, value: Double) = set("weight_$key", value.toString())

    fun weights(): Map<String, Double> =
        ModelDefaults.DEFAULT_WEIGHTS.keys.associateWith { weight(it) }

    suspend fun resetWeights() {
        ModelDefaults.DEFAULT_WEIGHTS.forEach { (k, v) -> setWeight(k, v) }
    }

    // ---- SNI lists per provider ----
    fun sniFor(providerId: String): List<String> {
        val raw = _all.value["sni_$providerId"] ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun setSniFor(providerId: String, snis: List<String>) =
        set("sni_$providerId", json.encodeToString(ListSerializer(String.serializer()), snis))

    fun sniByProvider(): Map<String, List<String>> =
        _all.value.keys.filter { it.startsWith("sni_") }.associate { key ->
            key.removePrefix("sni_") to sniFor(key.removePrefix("sni_"))
        }.filterValues { it.isNotEmpty() }

    // ---- appearance ----
    fun theme(): String = str("theme", "system")
    suspend fun setTheme(t: String) = set("theme", t)
    fun language(): String = str("language", "fa")
    suspend fun setLanguage(l: String) = set("language", l)
}
