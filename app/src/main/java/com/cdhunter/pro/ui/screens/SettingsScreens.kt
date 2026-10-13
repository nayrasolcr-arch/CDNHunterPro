package com.cdhunter.pro.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.model.ModelDefaults
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.Routes
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SettingsVm(private val c: AppContainer) : ViewModel() {
    val theme = MutableStateFlow("system")
    val language = MutableStateFlow("fa")
    val concurrency = MutableStateFlow(16)
    val timeoutMs = MutableStateFlow(4_000)
    val wifiOnly = MutableStateFlow(false)
    val stopChange = MutableStateFlow(true)
    val dataCapMb = MutableStateFlow(50)
    val retries = MutableStateFlow(1)
    val ttlHours = MutableStateFlow(72)
    val samplePerRange = MutableStateFlow(4)
    val maxTargets = MutableStateFlow(2_000)
    val deepTopN = MutableStateFlow(10)
    val weights = MutableStateFlow(ModelDefaults.DEFAULT_WEIGHTS)

    init {
        viewModelScope.launch {
            c.settings.load()
            theme.value = c.settings.theme()
            language.value = c.settings.language()
            concurrency.value = c.settings.concurrency()
            timeoutMs.value = c.settings.timeoutMs()
            wifiOnly.value = c.settings.wifiOnly()
            stopChange.value = c.settings.stopOnNetworkChange()
            dataCapMb.value = c.settings.dataCapMb()
            retries.value = c.settings.retries()
            ttlHours.value = c.settings.resultTtlHours()
            samplePerRange.value = c.settings.samplePerRange()
            maxTargets.value = c.settings.maxTargets()
            deepTopN.value = c.settings.deepTopN()
            weights.value = c.settings.weights()
        }
    }

    fun setTheme(t: String) {
        theme.value = t
        viewModelScope.launch { c.settings.setTheme(t) }
    }

    fun setLanguage(l: String) {
        language.value = l
        viewModelScope.launch { c.settings.setLanguage(l) }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(l))
    }

    fun saveNetwork() {
        viewModelScope.launch {
            c.settings.set("concurrency", concurrency.value.toString())
            c.settings.set("timeout_ms", timeoutMs.value.toString())
            c.settings.set("wifi_only", wifiOnly.value.toString())
            c.settings.set("stop_on_network_change", stopChange.value.toString())
            c.settings.set("data_cap_mb", dataCapMb.value.toString())
        }
    }

    fun saveAdvanced() {
        viewModelScope.launch {
            c.settings.set("retries", retries.value.toString())
            c.settings.set("result_ttl_hours", ttlHours.value.toString())
            c.settings.set("sample_per_range", samplePerRange.value.toString())
            c.settings.set("max_targets", maxTargets.value.toString())
            c.settings.set("deep_top_n", deepTopN.value.toString())
            weights.value.forEach { (k, v) -> c.settings.setWeight(k, v) }
        }
    }

    fun resetWeights() {
        viewModelScope.launch {
            c.settings.resetWeights()
            weights.value = c.settings.weights()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            c.database.clearAllTables()
        }
    }
}

@Composable
fun SettingsScreen(container: AppContainer, nav: NavController) {
    val vm: SettingsVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { SettingsVm(container) },
    )
    val theme by vm.theme.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    var confirmClear by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        SectionCard(stringResource(R.string.general_settings)) {
            Text(stringResource(R.string.language))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = language == "fa",
                    onClick = { vm.setLanguage("fa") },
                    label = { Text("فارسی") },
                )
                FilterChip(
                    selected = language == "en",
                    onClick = { vm.setLanguage("en") },
                    label = { Text("English") },
                )
            }
            Text(stringResource(R.string.theme))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = theme == "system",
                    onClick = { vm.setTheme("system") },
                    label = { Text(stringResource(R.string.theme_system)) },
                )
                FilterChip(
                    selected = theme == "dark",
                    onClick = { vm.setTheme("dark") },
                    label = { Text(stringResource(R.string.theme_dark)) },
                )
                FilterChip(
                    selected = theme == "light",
                    onClick = { vm.setTheme("light") },
                    label = { Text(stringResource(R.string.theme_light)) },
                )
            }
        }

        OutlinedButton(onClick = { nav.navigate(Routes.NETWORK_SETTINGS) }) {
            Text(stringResource(R.string.network_settings) + " ▸")
        }
        OutlinedButton(onClick = { nav.navigate(Routes.ADVANCED_SETTINGS) }) {
            Text(stringResource(R.string.advanced_settings) + " ▸")
        }
        OutlinedButton(onClick = { nav.navigate(Routes.ERROR_LOG) }) {
            Text(stringResource(R.string.error_log) + " ▸")
        }
        OutlinedButton(onClick = { nav.navigate(Routes.ABOUT) }) {
            Text(stringResource(R.string.about_title) + " ▸")
        }
        OutlinedButton(onClick = { nav.navigate(Routes.SNI) }) {
            Text(stringResource(R.string.sni_title) + " ▸")
        }
        OutlinedButton(onClick = { nav.navigate(Routes.CHARTS) }) {
            Text(stringResource(R.string.charts_title) + " ▸")
        }
        OutlinedButton(onClick = { nav.navigate(Routes.HISTORY) }) {
            Text(stringResource(R.string.history_title) + " ▸")
        }
        Button(
            onClick = { confirmClear = true },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.clear_data))
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_data)) },
            text = { Text(stringResource(R.string.clear_data_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearAllData(); confirmClear = false }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.no)) } },
        )
    }
}

@Composable
fun NetworkSettingsScreen(container: AppContainer, nav: NavController) {
    val vm: SettingsVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { SettingsVm(container) },
    )
    val concurrency by vm.concurrency.collectAsStateWithLifecycle()
    val timeout by vm.timeoutMs.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val stopChange by vm.stopChange.collectAsStateWithLifecycle()
    val dataCap by vm.dataCapMb.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.network_settings), style = MaterialTheme.typography.headlineMedium)

        SectionCard(stringResource(R.string.concurrency, concurrency)) {
            Slider(concurrency.toFloat(), { vm.concurrency.value = it.toInt() }, valueRange = 1f..64f)
            Text(stringResource(R.string.concurrency_hint), style = MaterialTheme.typography.bodySmall)
        }
        SectionCard(stringResource(R.string.timeout_ms, timeout)) {
            Slider(
                timeout.toFloat(),
                { vm.timeoutMs.value = (it / 500).toInt() * 500 },
                valueRange = 1000f..15_000f,
            )
            Text(stringResource(R.string.timeout_hint), style = MaterialTheme.typography.bodySmall)
        }
        SectionCard(stringResource(R.string.data_cap_mb, dataCap)) {
            Slider(dataCap.toFloat(), { vm.dataCapMb.value = it.toInt() }, valueRange = 5f..500f)
            Text(stringResource(R.string.data_cap_desc), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = wifiOnly, onCheckedChange = { vm.wifiOnly.value = it })
            Column(Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.wifi_only))
                Text(stringResource(R.string.wifi_only_desc), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = stopChange, onCheckedChange = { vm.stopChange.value = it })
            Column(Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.stop_on_network_change))
                Text(stringResource(R.string.stop_change_desc), style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = { vm.saveNetwork(); nav.popBackStack() }) { Text(stringResource(R.string.save)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun AdvancedSettingsScreen(container: AppContainer, nav: NavController) {
    val vm: SettingsVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { SettingsVm(container) },
    )
    val retries by vm.retries.collectAsStateWithLifecycle()
    val ttl by vm.ttlHours.collectAsStateWithLifecycle()
    val sample by vm.samplePerRange.collectAsStateWithLifecycle()
    val maxTargets by vm.maxTargets.collectAsStateWithLifecycle()
    val deepTopN by vm.deepTopN.collectAsStateWithLifecycle()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val weightSum = (weights.values.sum() * 100).toInt()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.advanced_settings), style = MaterialTheme.typography.headlineMedium)

        SectionCard(stringResource(R.string.retry_attempts, retries)) {
            Slider(retries.toFloat(), { vm.retries.value = it.toInt() }, valueRange = 0f..5f)
        }
        SectionCard(stringResource(R.string.result_ttl_hours, ttl)) {
            Slider(ttl.toFloat(), { vm.ttlHours.value = (it / 6).toInt() * 6 }, valueRange = 6f..720f)
        }
        SectionCard(stringResource(R.string.sample_per_range, sample)) {
            Slider(sample.toFloat(), { vm.samplePerRange.value = it.toInt() }, valueRange = 1f..16f)
        }
        SectionCard(stringResource(R.string.max_targets, maxTargets)) {
            Slider(
                maxTargets.toFloat(),
                { vm.maxTargets.value = (it / 100).toInt() * 100 },
                valueRange = 100f..20_000f,
            )
        }
        SectionCard(stringResource(R.string.mode_deep) + " · " + deepTopN) {
            Slider(deepTopN.toFloat(), { vm.deepTopN.value = it.toInt() }, valueRange = 1f..50f)
        }

        SectionCard(stringResource(R.string.weight_download, (weights["download"]!! * 100).toInt())) {
            Slider(weights["download"]!!.toFloat(), { vm.weights.value = vm.weights.value + ("download" to it.toDouble()) }, valueRange = 0f..1f)
        }
        SectionCard(stringResource(R.string.weight_stability, (weights["stability"]!! * 100).toInt())) {
            Slider(weights["stability"]!!.toFloat(), { vm.weights.value = vm.weights.value + ("stability" to it.toDouble()) }, valueRange = 0f..1f)
        }
        SectionCard(stringResource(R.string.weight_latency, (weights["latency"]!! * 100).toInt())) {
            Slider(weights["latency"]!!.toFloat(), { vm.weights.value = vm.weights.value + ("latency" to it.toDouble()) }, valueRange = 0f..1f)
        }
        SectionCard(stringResource(R.string.weight_jitter, (weights["jitter"]!! * 100).toInt())) {
            Slider(weights["jitter"]!!.toFloat(), { vm.weights.value = vm.weights.value + ("jitter" to it.toDouble()) }, valueRange = 0f..1f)
        }
        SectionCard(stringResource(R.string.weight_protocol, (weights["protocol"]!! * 100).toInt())) {
            Slider(weights["protocol"]!!.toFloat(), { vm.weights.value = vm.weights.value + ("protocol" to it.toDouble()) }, valueRange = 0f..1f)
        }
        Text(
            stringResource(R.string.weights_sum_warning, weightSum).takeIf { weightSum != 100 } ?: "",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { vm.resetWeights() }) { Text(stringResource(R.string.reset_weights)) }

        Button(onClick = { vm.saveAdvanced(); nav.popBackStack() }) { Text(stringResource(R.string.save)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ErrorLogScreen(container: AppContainer, nav: NavController) {
    val errors by container.resultRepository.observeErrors()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.error_log), style = MaterialTheme.typography.headlineMedium)
        if (errors.isEmpty()) {
            Text(stringResource(R.string.error_log_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(errors.size) { i ->
                    val e = errors[i]
                    SectionCard(title = e.tag + " · " + com.cdhunter.pro.ui.components.Fmt.time(e.atMs)) {
                        Text(e.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(container: AppContainer, nav: NavController) {
    val version = try {
        container.context.packageManager.getPackageInfo(container.context.packageName, 0).versionName
    } catch (_: Exception) {
        "?"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium)
        SectionCard(stringResource(R.string.version, version ?: "?")) {
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(stringResource(R.string.privacy_full_title)) {
            Text(stringResource(R.string.privacy_full_body), style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(stringResource(R.string.limitations_title)) {
            Text(stringResource(R.string.limitations_body), style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(stringResource(R.string.github_repo)) {
            Text("https://github.com/nayrasolcr-arch/CDNHunterPro", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.license_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.report_security), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}
