package com.cdhunter.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.model.ScanConfig
import com.cdhunter.pro.core.model.ScanMode
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.Routes
import com.cdhunter.pro.ui.components.Fmt
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ScanSetupVm(private val c: AppContainer) : ViewModel() {

    val mode = MutableStateFlow(ScanMode.STANDARD)
    val providers = MutableStateFlow<List<String>>(emptyList())
    val usePort80 = MutableStateFlow(true)
    val usePort443 = MutableStateFlow(true)
    val concurrency = MutableStateFlow(16)
    val timeoutMs = MutableStateFlow(4_000)
    val samplePerRange = MutableStateFlow(4)
    val maxTargets = MutableStateFlow(2_000)
    val dataCapMb = MutableStateFlow(50)
    val wifiOnly = MutableStateFlow(false)
    val stopOnNetworkChange = MutableStateFlow(true)
    val targetsEstimate = MutableStateFlow(0)
    val rangeCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            mode.value = c.settings.scanMode()
            providers.value = c.settings.selectedProviders()
            concurrency.value = c.settings.concurrency()
            timeoutMs.value = c.settings.timeoutMs()
            samplePerRange.value = c.settings.samplePerRange()
            maxTargets.value = c.settings.maxTargets()
            dataCapMb.value = c.settings.dataCapMb()
            wifiOnly.value = c.settings.wifiOnly()
            stopOnNetworkChange.value = c.settings.stopOnNetworkChange()
            usePort80.value = c.settings.ports().contains(80)
            usePort443.value = c.settings.ports().contains(443)
            refreshEstimate()
        }
    }

    suspend fun refreshEstimate() {
        val cfg = buildConfig()
        val engine = c.scanEngine
        targetsEstimate.value = engine.buildTargets(cfg).size
        rangeCounts.value = c.settings.selectedProviders().associateWith { pid ->
            c.rangeRepository.countFor(pid) + if (pid == "imported") c.candidateRepository.countImported() else 0
        }
    }

    fun buildConfig(): ScanConfig = ScanConfig(
        mode = mode.value,
        providers = providers.value,
        ports = buildList {
            if (usePort80.value) add(80)
            if (usePort443.value) add(443)
        }.ifEmpty { listOf(443) },
        concurrency = concurrency.value,
        timeoutMs = timeoutMs.value,
        samplePerRange = samplePerRange.value,
        maxTargets = maxTargets.value,
        wifiOnly = wifiOnly.value,
        stopOnNetworkChange = stopOnNetworkChange.value,
        dataCapMb = dataCapMb.value,
        retries = c.settings.retries(),
        sniByProvider = c.settings.sniByProvider(),
    )

    fun start(onStarted: (String?) -> Unit) {
        viewModelScope.launch {
            val cfg = buildConfig()
            var res = c.scanEngine.start(cfg)
            if (res == com.cdhunter.pro.scan.ScanEngine.Result.NO_TARGETS) {
                // Safety net for an empty ranges table (e.g. data cleared): one
                // bounded, targeted live refresh of the providers that have no
                // ranges, then retry once before reporting failure.
                val emptyProviders = cfg.providers
                    .filter { it != com.cdhunter.pro.core.registry.CdnRegistry.IMPORTED_ID }
                    .filter { c.rangeRepository.countFor(it) == 0 }
                if (emptyProviders.isNotEmpty()) {
                    kotlinx.coroutines.withTimeoutOrNull(30_000) {
                        c.rangeRepository.updateProviders(emptyProviders)
                    }
                    res = c.scanEngine.start(cfg)
                }
            }
            when (res) {
                com.cdhunter.pro.scan.ScanEngine.Result.STARTED -> {
                    c.settings.setScanMode(cfg.mode)
                    c.settings.setSelectedProviders(cfg.providers)
                    onStarted(null)
                }
                com.cdhunter.pro.scan.ScanEngine.Result.NO_TARGETS ->
                    onStarted(c.context.getString(R.string.err_no_targets))
                com.cdhunter.pro.scan.ScanEngine.Result.WIFI_ONLY_BLOCKED ->
                    onStarted(c.context.getString(R.string.err_wifi_only))
                com.cdhunter.pro.scan.ScanEngine.Result.NO_NETWORK ->
                    onStarted(c.context.getString(R.string.err_no_internet))
                com.cdhunter.pro.scan.ScanEngine.Result.ALREADY_RUNNING -> onStarted(null)
            }
        }
    }
}

@Composable
fun ScanSetupScreen(container: AppContainer, nav: NavController) {
    val vm: ScanSetupVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { ScanSetupVm(container) },
    )
    val mode by vm.mode.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val p80 by vm.usePort80.collectAsStateWithLifecycle()
    val p443 by vm.usePort443.collectAsStateWithLifecycle()
    val concurrency by vm.concurrency.collectAsStateWithLifecycle()
    val timeout by vm.timeoutMs.collectAsStateWithLifecycle()
    val sample by vm.samplePerRange.collectAsStateWithLifecycle()
    val maxTargets by vm.maxTargets.collectAsStateWithLifecycle()
    val dataCap by vm.dataCapMb.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val stopChange by vm.stopOnNetworkChange.collectAsStateWithLifecycle()
    val estimate by vm.targetsEstimate.collectAsStateWithLifecycle()
    val rangeCounts by vm.rangeCounts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshEstimate() }
    LaunchedEffect(providers, sample, maxTargets) { vm.refreshEstimate() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineMedium)

        SectionCard(stringResource(R.string.scan_mode)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == ScanMode.QUICK,
                    onClick = { vm.mode.value = ScanMode.QUICK },
                    label = { Text(stringResource(R.string.mode_quick)) },
                )
                FilterChip(
                    selected = mode == ScanMode.STANDARD,
                    onClick = { vm.mode.value = ScanMode.STANDARD },
                    label = { Text(stringResource(R.string.mode_standard)) },
                )
                FilterChip(
                    selected = mode == ScanMode.DEEP,
                    onClick = { vm.mode.value = ScanMode.DEEP },
                    label = { Text(stringResource(R.string.mode_deep)) },
                )
            }
            Text(
                when (mode) {
                    ScanMode.QUICK -> stringResource(R.string.mode_quick_desc)
                    ScanMode.STANDARD -> stringResource(R.string.mode_standard_desc)
                    ScanMode.DEEP -> stringResource(R.string.mode_deep_desc)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard(stringResource(R.string.select_cdns)) {
            // Show EVERY known provider (official registry + imported) with its
            // real checked state — the old hardcoded checked=true made removed
            // providers impossible to re-select.
            val allIds = com.cdhunter.pro.core.registry.CdnRegistry.ALL.map { it.id } +
                com.cdhunter.pro.core.registry.CdnRegistry.IMPORTED_ID
            allIds.forEach { pid ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = pid in providers,
                        onCheckedChange = { on ->
                            vm.providers.value = if (on) providers + pid else providers - pid
                        },
                    )
                    Text(com.cdhunter.pro.core.registry.CdnRegistry.displayName(pid))
                }
            }
            OutlinedButton(onClick = { nav.navigate(Routes.CDN_SELECT) }) {
                Text(stringResource(R.string.select_cdns) + " ▸")
            }
            OutlinedButton(onClick = { nav.navigate(Routes.RANGE_MANAGER) }) {
                Text(stringResource(R.string.manage_ranges) + " ▸")
            }
            allIds.forEach { pid ->
                val count = rangeCounts[pid] ?: 0
                Text("${com.cdhunter.pro.core.registry.CdnRegistry.displayName(pid)}: ${stringResource(R.string.ranges_count, count)}", style = MaterialTheme.typography.bodySmall)
            }
            if (rangeCounts.values.all { it == 0 } && rangeCounts.isNotEmpty()) {
                Text(
                    stringResource(R.string.ranges_none_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(stringResource(R.string.ports)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = p80, onCheckedChange = { vm.usePort80.value = it })
                Text(stringResource(R.string.port_80))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = p443, onCheckedChange = { vm.usePort443.value = it })
                Text(stringResource(R.string.port_443))
            }
        }

        SectionCard(stringResource(R.string.network_settings)) {
            Text(stringResource(R.string.concurrency, concurrency), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = concurrency.toFloat(),
                onValueChange = { vm.concurrency.value = it.toInt() },
                valueRange = 1f..64f,
            )
            Text(stringResource(R.string.concurrency_hint), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.timeout_ms, timeout), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = timeout.toFloat(),
                onValueChange = { vm.timeoutMs.value = (it / 500).toInt() * 500 },
                valueRange = 1000f..15_000f,
            )
            Text(stringResource(R.string.timeout_hint), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.sample_per_range, sample), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = sample.toFloat(),
                onValueChange = { vm.samplePerRange.value = it.toInt() },
                valueRange = 1f..16f,
            )

            Text(stringResource(R.string.data_cap_mb, dataCap), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = dataCap.toFloat(),
                onValueChange = { vm.dataCapMb.value = it.toInt() },
                valueRange = 5f..500f,
            )
            Text(stringResource(R.string.data_cap_warning), style = MaterialTheme.typography.bodySmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = wifiOnly, onCheckedChange = { vm.wifiOnly.value = it })
                Text(stringResource(R.string.wifi_only), Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = stopChange, onCheckedChange = { vm.stopOnNetworkChange.value = it })
                Text(stringResource(R.string.stop_on_network_change), Modifier.padding(start = 8.dp))
            }
        }

        SectionCard(stringResource(R.string.targets_estimate, estimate)) {
            Text(
                stringResource(R.string.too_many_targets, maxTargets).takeIf { estimate >= maxTargets } ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { vm.start { msg -> vm.message.value = msg; if (msg == null) nav.navigate(Routes.SCAN_PROGRESS) } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.start_scan))
        }
        Spacer(Modifier.height(24.dp))
    }
}
