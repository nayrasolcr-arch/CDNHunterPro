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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.db.SniResultEntity
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.scoring.ScoreReason
import com.cdhunter.pro.scoring.ScoringEngine
import com.cdhunter.pro.ui.components.EmptyState
import com.cdhunter.pro.ui.components.Fmt
import com.cdhunter.pro.ui.components.KeyValueRow
import com.cdhunter.pro.ui.components.LineChart
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.components.VerdictChip
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class IpDetailVm(private val c: AppContainer) : ViewModel() {
    val results = MutableStateFlow<List<com.cdhunter.pro.db.ResultEntity>>(emptyList())
    val sniResults = MutableStateFlow<List<SniResultEntity>>(emptyList())
    val testing = MutableStateFlow(false)

    private val json = Json { ignoreUnknownKeys = true }
    private val scoring = ScoringEngine()

    fun load(ip: String) {
        viewModelScope.launch {
            results.value = c.resultRepository.forIp(ip)
            sniResults.value = c.database.sniResultDao().forIp(ip)
        }
    }

    fun reasonsOf(r: com.cdhunter.pro.db.ResultEntity): List<ScoreReason> =
        scoring.deserializeReasons(r.scoreReasonsJson)

    fun seriesOf(r: com.cdhunter.pro.db.ResultEntity): List<Int> =
        r.latencySeriesJson?.let {
            runCatching { json.decodeFromString(ListSerializer(Int.serializer()), it) }.getOrDefault(emptyList())
        } ?: emptyList()

    /** Re-tests this IP right now (TCP stability + TLS + HTTP), standard depth. */
    fun retest(ip: String) {
        viewModelScope.launch {
            testing.value = true
            try {
                val cfg = com.cdhunter.pro.core.model.ScanConfig(
                    mode = com.cdhunter.pro.core.model.ScanMode.STANDARD,
                    providers = emptyList(),
                    ports = listOf(80, 443),
                    concurrency = 1,
                    timeoutMs = c.settings.timeoutMs(),
                    stabilityAttempts = 5,
                )
                val outcome = c.scanEngine.runProbePublic(
                    com.cdhunter.pro.core.model.Target(ip, null, listOf(80, 443)),
                    cfg,
                )
                c.resultRepository.storeOutcome(outcome, scanIdForManual())
                load(ip)
            } finally {
                testing.value = false
            }
        }
    }

    private suspend fun scanIdForManual(): Long {
        // Manual re-tests attach to the most recent scan when available.
        val last = c.database.scanDao().recentOnce().firstOrNull()
        return last?.id ?: -1L
    }
}

@Composable
fun IpDetailScreen(container: AppContainer, nav: NavController, ip: String) {
    val vm: IpDetailVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { IpDetailVm(container) },
    )
    val results by vm.results.collectAsStateWithLifecycle()
    val sniResults by vm.sniResults.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    androidx.compose.runtime.LaunchedEffect(ip) { vm.load(ip) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ip, style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(ip)) }) {
                Text(stringResource(R.string.copy_ip))
            }
        }

        results.firstOrNull()?.let { r ->
            SectionCard(title = stringResource(R.string.detail_title)) {
                Row {
                    VerdictChip(
                        com.cdhunter.pro.scoring.effectiveVerdict(r, container.settings.resultTtlHours()),
                    )
                }
                KeyValueRow(stringResource(R.string.provider), CdnRegistry.displayName(r.providerId))
                KeyValueRow(stringResource(R.string.tcp_result), "${r.tcpOk} · ${Fmt.ms(r.tcpConnectMs)}")
                KeyValueRow(stringResource(R.string.tls_result), "${r.tlsOk ?: "–"} · ${Fmt.ms(r.tlsHandshakeMs)}")
                KeyValueRow(stringResource(R.string.tls_version), r.tlsVersion ?: "–")
                KeyValueRow(stringResource(R.string.http_result), "${r.httpOk ?: "–"} · ${r.httpStatus ?: "–"} · ${r.httpVersion ?: "–"}")
                KeyValueRow(stringResource(R.string.latency), "min ${Fmt.ms(r.latencyMinMs)} / avg ${Fmt.ms(r.latencyAvgMs)} / max ${Fmt.ms(r.latencyMaxMs)}")
                KeyValueRow(stringResource(R.string.jitter), Fmt.ms(r.jitterMs))
                KeyValueRow(stringResource(R.string.download_speed), Fmt.kbps(r.downloadKbps))
                KeyValueRow(
                    stringResource(R.string.success_rate),
                    Fmt.pct(if (r.attempts > 0) r.successes * 100.0 / r.attempts else null),
                )
                KeyValueRow(stringResource(R.string.attempts), "${r.successes}/${r.attempts}")
                KeyValueRow(stringResource(R.string.cert_cn), r.certCn ?: "–")
                KeyValueRow(
                    if (r.certMatched == true) stringResource(R.string.cert_matched) else stringResource(R.string.cert_not_matched),
                    r.certNotAfterMs?.let { Fmt.time(it) } ?: "–",
                )
                KeyValueRow(stringResource(R.string.error_category), r.errorCategory ?: "–")
                KeyValueRow(stringResource(R.string.last_tested), Fmt.time(r.testedAtMs))
            }

            SectionCard(title = stringResource(R.string.reasons)) {
                val reasons = vm.reasonsOf(r)
                if (reasons.isEmpty()) {
                    Text(stringResource(R.string.not_available), style = MaterialTheme.typography.bodySmall)
                } else {
                    reasons.forEach { reason ->
                        Text("• ${reasonLabel(reason)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            val series = vm.seriesOf(r).map { it.toFloat() }.filter { it > 0 }
            if (series.size >= 2) {
                SectionCard(title = stringResource(R.string.latency_history)) {
                    LineChart(values = series)
                }
            }
        } ?: EmptyState(stringResource(R.string.results_empty))

        if (sniResults.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.sni_results)) {
                sniResults.forEach { s ->
                    KeyValueRow(s.sni, if (s.ok) stringResource(R.string.sni_ok) else stringResource(R.string.sni_fail))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.retest(ip) }, enabled = !testing) {
                Text(if (testing) stringResource(R.string.loading) else stringResource(R.string.rescan_ip))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun reasonLabel(reason: ScoreReason): String = when (reason) {
    ScoreReason.DOWNLOAD_FAST -> stringResource(R.string.weight_download) + ": ✓✓"
    ScoreReason.DOWNLOAD_OK -> stringResource(R.string.weight_download) + ": ✓"
    ScoreReason.DOWNLOAD_SLOW -> stringResource(R.string.weight_download) + ": ✗"
    ScoreReason.DOWNLOAD_MISSING -> stringResource(R.string.weight_download) + ": " + stringResource(R.string.filter_insufficient)
    ScoreReason.STABILITY_HIGH -> stringResource(R.string.weight_stability) + ": ✓✓"
    ScoreReason.STABILITY_MID -> stringResource(R.string.weight_stability) + ": ✓"
    ScoreReason.STABILITY_LOW -> stringResource(R.string.weight_stability) + ": ✗"
    ScoreReason.LATENCY_GOOD -> stringResource(R.string.weight_latency) + ": ✓✓"
    ScoreReason.LATENCY_MID -> stringResource(R.string.weight_latency) + ": ✓"
    ScoreReason.LATENCY_HIGH -> stringResource(R.string.weight_latency) + ": ✗"
    ScoreReason.JITTER_LOW -> stringResource(R.string.weight_jitter) + ": ✓✓"
    ScoreReason.JITTER_MID -> stringResource(R.string.weight_jitter) + ": ✓"
    ScoreReason.JITTER_HIGH -> stringResource(R.string.weight_jitter) + ": ✗"
    ScoreReason.PROTOCOL_FULL -> stringResource(R.string.weight_protocol) + ": ✓✓"
    ScoreReason.PROTOCOL_PARTIAL -> stringResource(R.string.weight_protocol) + ": ✓"
    ScoreReason.PROTOCOL_NONE -> stringResource(R.string.weight_protocol) + ": ✗"
    ScoreReason.SNI_VERIFIED -> "SNI ✓"
}
