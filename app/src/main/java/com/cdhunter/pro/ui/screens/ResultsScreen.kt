package com.cdhunter.pro.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.cdhunter.pro.core.model.Verdict
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.db.ResultEntity
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.scoring.effectiveVerdict
import com.cdhunter.pro.ui.Routes
import com.cdhunter.pro.ui.components.EmptyState
import com.cdhunter.pro.ui.components.Fmt
import com.cdhunter.pro.ui.components.VerdictChip
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ResultsVm(private val c: AppContainer, private val argScanId: Long) : ViewModel() {
    val scanId = MutableStateFlow(argScanId)
    val results = MutableStateFlow<List<ResultEntity>>(emptyList())
    val filter = MutableStateFlow<Verdict?>(null)
    val sort = MutableStateFlow(Sort.SCORE)
    val ttlHours = MutableStateFlow(72)
    val loaded = MutableStateFlow(false)

    enum class Sort { SCORE, LATENCY, SPEED, RECENT }

    init {
        viewModelScope.launch {
            val id = if (argScanId > 0) argScanId
            else c.database.scanDao().recentOnce().firstOrNull()?.id ?: -1
            scanId.value = id
            ttlHours.value = c.settings.resultTtlHours()
            load()
        }
    }

    fun load() {
        viewModelScope.launch {
            if (scanId.value > 0) {
                results.value = c.resultRepository.forScan(scanId.value)
            }
            loaded.value = true
        }
    }

    fun visible(): List<ResultEntity> {
        val ttl = ttlHours.value
        var list = results.value
        filter.value?.let { f ->
            list = list.filter { effectiveVerdict(it, ttl) == f }
        }
        return when (sort.value) {
            Sort.SCORE -> list.sortedByDescending { it.score ?: -1.0 }
            Sort.LATENCY -> list.sortedBy { it.latencyAvgMs ?: Int.MAX_VALUE }
            Sort.SPEED -> list.sortedByDescending { it.downloadKbps ?: -1.0 }
            Sort.RECENT -> list.sortedByDescending { it.testedAtMs }
        }
    }
}

@Composable
fun ResultsScreen(container: AppContainer, nav: NavController, argScanId: Long) {
    val vm: ResultsVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { ResultsVm(container, argScanId) },
    )
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val ttl = 72

    val visible = remember2(results, filter, sort) { vm.visible() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.results_title), style = MaterialTheme.typography.headlineMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = filter == null,
                onClick = { vm.filter.value = null },
                label = { Text(stringResource(R.string.filter_all)) },
            )
            FilterChip(
                selected = filter == Verdict.VERIFIED_COMPATIBLE,
                onClick = { vm.filter.value = Verdict.VERIFIED_COMPATIBLE },
                label = { Text(stringResource(R.string.filter_verified)) },
            )
            FilterChip(
                selected = filter == Verdict.CANDIDATE_NEEDS_TESTING,
                onClick = { vm.filter.value = Verdict.CANDIDATE_NEEDS_TESTING },
                label = { Text(stringResource(R.string.filter_candidate)) },
            )
            FilterChip(
                selected = filter == Verdict.REACHABLE_INCOMPATIBLE,
                onClick = { vm.filter.value = Verdict.REACHABLE_INCOMPATIBLE },
                label = { Text(stringResource(R.string.filter_reachable_bad)) },
            )
            FilterChip(
                selected = filter == Verdict.UNREACHABLE,
                onClick = { vm.filter.value = Verdict.UNREACHABLE },
                label = { Text(stringResource(R.string.filter_unreachable)) },
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ResultsVm.Sort.entries.forEach { s ->
                FilterChip(
                    selected = sort == s,
                    onClick = { vm.sort.value = s },
                    label = {
                        Text(
                            when (s) {
                                ResultsVm.Sort.SCORE -> stringResource(R.string.sort_score)
                                ResultsVm.Sort.LATENCY -> stringResource(R.string.sort_latency)
                                ResultsVm.Sort.SPEED -> stringResource(R.string.sort_speed)
                                ResultsVm.Sort.RECENT -> stringResource(R.string.sort_recent)
                            },
                        )
                    },
                )
            }
            OutlinedButton(
                onClick = { nav.navigate(Routes.export(vm.scanId.value)) },
            ) { Text(stringResource(R.string.export_results)) }
        }

        if (loaded && results.isEmpty()) {
            EmptyState(stringResource(R.string.results_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible) { r ->
                    ResultRow(r, ttl) { nav.navigate(Routes.ipDetail(java.net.URLEncoder.encode(r.ip, "UTF-8"))) }
                }
                item {
                    Text(
                        stringResource(R.string.unverified_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: ResultEntity, ttlHours: Int, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.ip, style = MaterialTheme.typography.titleMedium)
                Text(
                    r.score?.let { String.format("%.0f", it) } ?: "–",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                VerdictChip(effectiveVerdict(r, ttlHours))
                Text(
                    "${CdnRegistry.displayName(r.providerId)} · ${Fmt.ms(r.latencyAvgMs)} · ${Fmt.kbps(r.downloadKbps)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Small helper to derive a filtered/sorted list from multiple state flows. */
@Composable
private fun <T> remember2(
    vararg keys: Any?,
    calculation: () -> T,
): T = androidx.compose.runtime.remember(*keys, calculation = calculation)
