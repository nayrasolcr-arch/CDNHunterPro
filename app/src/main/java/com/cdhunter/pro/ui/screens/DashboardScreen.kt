package com.cdhunter.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.model.ScanStatus
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.db.ScanEntity
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.Routes
import com.cdhunter.pro.ui.components.Fmt
import com.cdhunter.pro.ui.components.KeyValueRow
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.components.StatCard
import com.cdhunter.pro.ui.components.EmptyState
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardVm(private val c: AppContainer) : ViewModel() {

    val progress = c.scanEngine.progress

    val selectedProviders = MutableStateFlow<List<String>>(emptyList())
    val candidates = MutableStateFlow(0)
    val best = MutableStateFlow<Double?>(null)
    val lastSuccess = MutableStateFlow<Long?>(null)

    val recentScans = c.database.scanDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            selectedProviders.value = c.settings.selectedProviders()
            candidates.value = c.candidateRepository.countAll()
            best.value = c.resultRepository.bestEver()?.score
            lastSuccess.value = c.resultRepository.lastSuccessAt()
        }
    }
}

@Composable
fun DashboardScreen(container: AppContainer, nav: NavController) {
    val vm: DashboardVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { DashboardVm(container) },
    )
    val progress by vm.progress.collectAsStateWithLifecycle()
    val providers by vm.selectedProviders.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val best by vm.best.collectAsStateWithLifecycle()
    val lastSuccess by vm.lastSuccess.collectAsStateWithLifecycle()
    val scans by vm.recentScans.collectAsStateWithLifecycle()

    LaunchedEffect(progress?.status) { vm.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dashboard_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    when (progress?.status) {
                        ScanStatus.RUNNING -> stringResource(R.string.status_running)
                        ScanStatus.PAUSED -> stringResource(R.string.status_paused)
                        else -> stringResource(R.string.status_idle)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    stringResource(R.string.stat_discovered), candidates.toString(),
                    Modifier.weight(1f),
                )
                StatCard(
                    stringResource(R.string.stat_tested), (progress?.tested ?: scans.firstOrNull()?.testedCount ?: 0).toString(),
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    stringResource(R.string.stat_reachable), (progress?.reachable ?: scans.firstOrNull()?.reachableCount ?: 0).toString(),
                    Modifier.weight(1f),
                )
                StatCard(
                    stringResource(R.string.stat_verified), (progress?.verified ?: scans.firstOrNull()?.verifiedCount ?: 0).toString(),
                    Modifier.weight(1f),
                    accent = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    stringResource(R.string.stat_errors), (progress?.errors ?: scans.firstOrNull()?.errorCount ?: 0).toString(),
                    Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.error,
                )
                StatCard(
                    stringResource(R.string.stat_progress),
                    if ((progress?.total ?: 0) > 0) {
                        "${((progress?.tested ?: 0) * 100) / (progress?.total ?: 1)}%"
                    } else {
                        if ((scans.firstOrNull()?.totalTargets ?: 0) > 0) {
                            "${((scans.firstOrNull()?.testedCount ?: 0) * 100) / (scans.firstOrNull()?.totalTargets ?: 1)}%"
                        } else "0%"
                    },
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    stringResource(R.string.stat_scan_speed),
                    progress?.let { "${it.ipPerSec.toInt()} ip/s" } ?: "–",
                    Modifier.weight(1f),
                )
                StatCard(
                    stringResource(R.string.stat_best_result),
                    best?.let { String.format("%.0f", it) } ?: "–",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            SectionCard(stringResource(R.string.stat_last_success)) {
                KeyValueRow(
                    stringResource(R.string.stat_last_success),
                    lastSuccess?.let { Fmt.time(it) } ?: stringResource(R.string.not_available),
                )
                KeyValueRow(
                    stringResource(R.string.selected_cdns),
                    providers.joinToString("، ") { CdnRegistry.displayName(it) }
                        .ifEmpty { stringResource(R.string.not_available) },
                )
            }
        }
        item {
            Button(
                onClick = {
                    val active = progress?.status in listOf(ScanStatus.RUNNING, ScanStatus.PAUSED)
                    nav.navigate(if (active) Routes.SCAN_PROGRESS else Routes.SCAN_SETUP)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (progress?.status == ScanStatus.PAUSED) {
                        stringResource(R.string.btn_resume_scan)
                    } else {
                        stringResource(R.string.btn_start_scan)
                    },
                )
            }
        }
        item { Text(stringResource(R.string.recent_scans), style = MaterialTheme.typography.titleMedium) }
        if (scans.isEmpty()) {
            item { EmptyState(stringResource(R.string.no_scan_yet)) }
        } else {
            items(scans.take(5)) { scan ->
                ScanRow(scan) { nav.navigate(Routes.results(scan.id)) }
            }
        }
        item { Text(stringResource(R.string.real_data_note), style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ScanRow(scan: ScanEntity, onClick: () -> Unit) {
    SectionCard(title = "#${scan.id} · ${scan.mode}") {
        KeyValueRow(stringResource(R.string.stat_progress), "${scan.testedCount}/${scan.totalTargets}")
        KeyValueRow(
            stringResource(R.string.stat_reachable),
            "${scan.reachableCount} · ${stringResource(R.string.stat_verified)}: ${scan.verifiedCount}",
        )
        KeyValueRow(stringResource(R.string.last_tested), Fmt.time(scan.startedAtMs))
        androidx.compose.material3.TextButton(onClick = onClick) {
            Text(stringResource(R.string.tab_results))
        }
    }
}
