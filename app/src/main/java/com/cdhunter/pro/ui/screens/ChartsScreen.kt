package com.cdhunter.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.db.ResultEntity
import com.cdhunter.pro.db.ScanEntity
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.ui.components.BarChart
import com.cdhunter.pro.ui.components.EmptyState
import com.cdhunter.pro.ui.components.LineChart
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChartsVm(private val c: AppContainer) : ViewModel() {
    val scans = c.database.scanDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val topLatency = MutableStateFlow<List<Pair<String, Float>>>(emptyList())
    val topSpeed = MutableStateFlow<List<Pair<String, Float>>>(emptyList())
    val topJitter = MutableStateFlow<List<Pair<String, Float>>>(emptyList())
    val history = MutableStateFlow<List<Float>>(emptyList())

    init { load() }

    fun load() {
        viewModelScope.launch {
            val latest = c.database.scanDao().recentOnce().firstOrNull() ?: return@launch
            val rows: List<ResultEntity> = c.resultRepository.forScan(latest.id)
                .filter { it.tcpOk }
            topLatency.value = rows.sortedBy { it.latencyAvgMs ?: Int.MAX_VALUE }
                .take(10).map { it.ip to (it.latencyAvgMs?.toFloat() ?: 0f) }
            topSpeed.value = rows.sortedByDescending { it.downloadKbps ?: 0.0 }
                .take(10).map { it.ip to (it.downloadKbps?.toFloat() ?: 0f) }
            topJitter.value = rows.sortedBy { it.jitterMs ?: Int.MAX_VALUE }
                .take(10).map { it.ip to (it.jitterMs?.toFloat() ?: 0f) }
            history.value = c.database.scanDao().recentOnce()
                .sortedBy { it.startedAtMs }
                .map { s ->
                    c.resultRepository.forScan(s.id)
                        .mapNotNull { it.score }
                        .maxOrNull()?.toFloat() ?: 0f
                }
        }
    }
}

@Composable
fun ChartsScreen(container: AppContainer, nav: NavController) {
    val vm: ChartsVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { ChartsVm(container) },
    )
    val latency by vm.topLatency.collectAsStateWithLifecycle()
    val speed by vm.topSpeed.collectAsStateWithLifecycle()
    val jitter by vm.topJitter.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { Text(stringResource(R.string.charts_title), style = MaterialTheme.typography.headlineMedium) }
        if (latency.isEmpty() && speed.isEmpty() && jitter.isEmpty()) {
            item { EmptyState(stringResource(R.string.no_chart_data)) }
        } else {
            item {
                SectionCard(stringResource(R.string.chart_latency)) {
                    BarChart(latency)
                }
            }
            item {
                SectionCard(stringResource(R.string.chart_speed)) {
                    BarChart(speed)
                }
            }
            item {
                SectionCard(stringResource(R.string.chart_jitter)) {
                    BarChart(jitter)
                }
            }
        }
        if (history.size >= 2) {
            item {
                SectionCard(stringResource(R.string.chart_history)) {
                    LineChart(history)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------- History

class HistoryVm(private val c: AppContainer) : ViewModel() {
    val scans = c.database.scanDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(scan: ScanEntity) {
        viewModelScope.launch {
            c.resultRepository.deleteForScan(scan.id)
            c.database.scanDao().delete(scan.id)
        }
    }
}

@Composable
fun HistoryScreen(container: AppContainer, nav: NavController) {
    val vm: HistoryVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { HistoryVm(container) },
    )
    val scans by vm.scans.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineMedium)
                androidx.compose.material3.OutlinedButton(onClick = { nav.navigate(com.cdhunter.pro.ui.Routes.COMPARE) }) {
                    Text(stringResource(R.string.compare_scans))
                }
            }
        }
        if (scans.isEmpty()) {
            item { EmptyState(stringResource(R.string.history_empty)) }
        } else {
            items(scans) { s ->
                ScanHistoryRow(s, onView = { nav.navigate(com.cdhunter.pro.ui.Routes.results(s.id)) }, onDelete = { vm.delete(s) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ScanHistoryRow(s: ScanEntity, onView: () -> Unit, onDelete: () -> Unit) {
    SectionCard(title = "#${s.id} · ${s.mode}") {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    R.string.stat_progress, s.testedCount, s.totalTargets,
                ) + " · ${stringResource(R.string.stat_reachable)} ${s.reachableCount} · ${stringResource(R.string.stat_verified)} ${s.verifiedCount}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            com.cdhunter.pro.ui.components.Fmt.time(s.startedAtMs) + " · " + s.status,
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.TextButton(onClick = onView) { Text(stringResource(R.string.tab_results)) }
            androidx.compose.material3.TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_scan)) }
        }
    }
}

// ---------------------------------------------------------------- Compare

class CompareVm(private val c: AppContainer) : ViewModel() {
    val scans = c.database.scanDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scanA = MutableStateFlow<ScanEntity?>(null)
    val scanB = MutableStateFlow<ScanEntity?>(null)
    val stats = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())

    fun compare() {
        viewModelScope.launch {
            val a = scanA.value ?: return@launch
            val b = scanB.value ?: return@launch
            val ra = c.resultRepository.forScan(a.id)
            val rb = c.resultRepository.forScan(b.id)
            fun avg(v: List<ResultEntity>, f: (ResultEntity) -> Double?): Double {
                val list = v.mapNotNull(f)
                return if (list.isEmpty()) 0.0 else list.average()
            }
            fun verified(v: List<ResultEntity>) = v.count { it.verdict == com.cdhunter.pro.core.model.Verdict.VERIFIED_COMPATIBLE.name }
            fun reachable(v: List<ResultEntity>) = v.count { it.tcpOk }
            fun fmt(d: Double) = String.format("%.1f", d)
            stats.value = listOf(
                Triple("IPs", a.testedCount.toString(), b.testedCount.toString()),
                Triple(
                    string2("reachable"),
                    reachable(ra).toString(),
                    reachable(rb).toString(),
                ),
                Triple(string2("verified"), verified(ra).toString(), verified(rb).toString()),
                Triple(string2("latency"), fmt(avg(ra) { it.latencyAvgMs?.toDouble() }), fmt(avg(rb) { it.latencyAvgMs?.toDouble() })),
                Triple(string2("jitter"), fmt(avg(ra) { it.jitterMs?.toDouble() }), fmt(avg(rb) { it.jitterMs?.toDouble() })),
                Triple(string2("speed"), fmt(avg(ra) { it.downloadKbps }), fmt(avg(rb) { it.downloadKbps })),
                Triple(string2("score"), fmt(avg(ra) { it.score }), fmt(avg(rb) { it.score })),
            )
        }
    }

    private fun string2(key: String): String = key
}

@Composable
fun CompareScreen(container: AppContainer, nav: NavController) {
    val vm: CompareVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { CompareVm(container) },
    )
    val scans by vm.scans.collectAsStateWithLifecycle()
    val scanA by vm.scanA.collectAsStateWithLifecycle()
    val scanB by vm.scanB.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.headlineMedium) }
        item {
            if (scans.size < 2) {
                EmptyState(stringResource(R.string.select_two_scans))
            } else {
                SectionCard(stringResource(R.string.select_two_scans)) {
                    Text("A:")
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        scans.take(6).forEach { s ->
                            androidx.compose.material3.FilterChip(
                                selected = scanA?.id == s.id,
                                onClick = { vm.scanA.value = s },
                                label = { Text("#${s.id}") },
                            )
                        }
                    }
                    Text("B:")
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        scans.take(6).forEach { s ->
                            androidx.compose.material3.FilterChip(
                                selected = scanB?.id == s.id,
                                onClick = { vm.scanB.value = s },
                                label = { Text("#${s.id}") },
                            )
                        }
                    }
                    androidx.compose.material3.Button(
                        onClick = { vm.compare() },
                        enabled = scanA != null && scanB != null,
                    ) { Text(stringResource(R.string.compare_title)) }
                }
            }
        }
        items(stats) { (label, a, b) ->
            SectionCard(title = compareLabel(label)) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("A: $a", style = MaterialTheme.typography.bodyMedium)
                    Text("B: $b", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun compareLabel(key: String): String = when (key) {
    "reachable" -> stringResource(R.string.stat_reachable)
    "verified" -> stringResource(R.string.stat_verified)
    "latency" -> stringResource(R.string.latency)
    "jitter" -> stringResource(R.string.jitter)
    "speed" -> stringResource(R.string.download_speed)
    "score" -> stringResource(R.string.score)
    "IPs" -> "IPs"
    else -> key
}
