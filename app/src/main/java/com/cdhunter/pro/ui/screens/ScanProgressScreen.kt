package com.cdhunter.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.cdhunter.pro.core.model.PauseReason
import com.cdhunter.pro.core.model.ScanProgress
import com.cdhunter.pro.core.model.ScanStatus
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.components.Fmt
import com.cdhunter.pro.ui.components.KeyValueRow
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ScanProgressVm(private val c: AppContainer) : ViewModel() {
    val progress = c.scanEngine.progress
    val lastDbScan = MutableStateFlow<com.cdhunter.pro.db.ScanEntity?>(null)

    init {
        viewModelScope.launch {
            // Show the last scan even when the engine is idle (process restarted).
            if (progress.value == null) {
                lastDbScan.value = c.database.scanDao().activeScan()
                    ?: c.database.scanDao().recentOnce().firstOrNull()
            }
        }
    }

    fun pause() = c.scanEngine.pause(PauseReason.USER)

    fun resume(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(c.scanEngine.resume()) }
    }

    fun cancel() {
        viewModelScope.launch { c.scanEngine.cancel() }
    }
}

@Composable
fun ScanProgressScreen(container: AppContainer, nav: NavController) {
    val vm: ScanProgressVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { ScanProgressVm(container) },
    )
    val progress by vm.progress.collectAsStateWithLifecycle()
    val dbScan by vm.lastDbScan.collectAsStateWithLifecycle()

    val p = progress
    val status = p?.status
    val showDb = p == null

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.progress_title), style = MaterialTheme.typography.headlineMedium)

        if (showDb) {
            dbScan?.let { scan ->
                SectionCard(title = "#${scan.id} · ${scan.mode}") {
                    KeyValueRow(
                        stringResource(R.string.stat_progress),
                        "${scan.testedCount}/${scan.totalTargets}",
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (scan.totalTargets > 0) scan.testedCount.toFloat() / scan.totalTargets else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (scan.status == ScanStatus.PAUSED.name || scan.status == ScanStatus.RUNNING.name) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.resume { ok -> if (ok) { /* engine takes over */ } } }) {
                            Text(stringResource(R.string.resume_scan))
                        }
                    }
                }
            } ?: Text(stringResource(R.string.no_scan_yet))
        } else {
            SectionCard(title = "#${p.scanId} · ${p.mode}") {
                Text(
                    when (status) {
                        ScanStatus.RUNNING -> stringResource(R.string.scan_running)
                        ScanStatus.PAUSED -> when (p.pauseReason) {
                            PauseReason.NETWORK_CHANGED -> stringResource(R.string.scan_paused_network)
                            PauseReason.DATA_CAP -> stringResource(R.string.scan_paused_datacap)
                            PauseReason.WIFI_ONLY -> stringResource(R.string.scan_paused_wifi)
                            else -> stringResource(R.string.scan_paused)
                        }
                        ScanStatus.COMPLETED -> stringResource(R.string.scan_completed)
                        ScanStatus.CANCELLED -> stringResource(R.string.scan_cancelled)
                        ScanStatus.FAILED -> stringResource(R.string.scan_failed)
                        else -> stringResource(R.string.status_idle)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { if (p.total > 0) p.tested.toFloat() / p.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyValueRow(stringResource(R.string.stat_progress), stringResource(R.string.tested_of, p.tested, p.total))
                KeyValueRow(stringResource(R.string.remaining_ips), (p.total - p.tested).toString())
                KeyValueRow(stringResource(R.string.eta), Fmt.eta(p.etaSec))
                KeyValueRow(stringResource(R.string.stat_scan_speed), stringResource(R.string.scan_speed_ips, p.ipPerSec.toInt()))
                KeyValueRow(stringResource(R.string.reachable_count), p.reachable.toString())
                KeyValueRow(stringResource(R.string.stat_verified), p.verified.toString())
                KeyValueRow(stringResource(R.string.errors_count), p.errors.toString())
                KeyValueRow(
                    stringResource(R.string.data_cap_mb, p.dataCapBytes.toInt() / 1_000_000),
                    Fmt.bytes(p.bytesUsed),
                )
                if (p.deepPhase) {
                    Text(
                        stringResource(R.string.deep_phase),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    ScanStatus.RUNNING -> {
                        OutlinedButton(onClick = { vm.pause() }) { Text(stringResource(R.string.pause_scan)) }
                        OutlinedButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.cancel_scan)) }
                    }
                    ScanStatus.PAUSED -> {
                        Button(onClick = { vm.resume { } }) { Text(stringResource(R.string.resume_scan)) }
                        OutlinedButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.cancel_scan)) }
                    }
                    else -> {
                        Button(onClick = { nav.navigate(com.cdhunter.pro.ui.Routes.results(p.scanId)) }) {
                            Text(stringResource(R.string.tab_results))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
