package com.cdhunter.pro.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cdhunter.pro.R
import com.cdhunter.pro.core.model.ExportFormat
import com.cdhunter.pro.core.registry.CdnRegistry
import com.cdhunter.pro.db.ResultEntity
import com.cdhunter.pro.di.AppContainer
import com.cdhunter.pro.export.Exporter
import com.cdhunter.pro.ui.components.EmptyState
import com.cdhunter.pro.ui.components.SectionCard
import com.cdhunter.pro.ui.vm.vmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExportVm(private val c: AppContainer, argScanId: Long) : ViewModel() {
    val scans = c.database.scanDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scanId = MutableStateFlow(argScanId)
    val format = MutableStateFlow(ExportFormat.TXT)
    val includeSni = MutableStateFlow(false)
    val onlyGood = MutableStateFlow(false)
    val prepared = MutableStateFlow<com.cdhunter.pro.export.ExportManager.Prepared?>(null)
    val qrImages = MutableStateFlow<List<android.graphics.Bitmap>>(emptyList())
    val feedback = MutableStateFlow<String?>(null)
    val history = c.exportManager.observeLog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (argScanId <= 0) {
            viewModelScope.launch {
                scanId.value = c.database.scanDao().recentOnce().firstOrNull()?.id ?: -1
            }
        }
    }

    suspend fun buildText(): Pair<String, Int>? {
        val id = scanId.value
        if (id <= 0) return null
        val rows0 = c.resultRepository.forScan(id)
        val rows: List<ResultEntity> = if (onlyGood.value) {
            rows0.filter {
                it.verdict == com.cdhunter.pro.core.model.Verdict.VERIFIED_COMPATIBLE.name ||
                    it.verdict == com.cdhunter.pro.core.model.Verdict.CANDIDATE_NEEDS_TESTING.name
            }
        } else rows0
        if (rows.isEmpty()) return null
        // Precompute verified SNI per IP (suspend lookups) before pure export building.
        val sniMap = rows.associate { r ->
            r.ip to c.database.sniResultDao().forIp(r.ip).firstOrNull { it.ok }?.sni
        }
        val exportRows = Exporter.buildRows(
            results = rows,
            providerNames = { CdnRegistry.displayName(it) },
            sniByIp = { sniMap[it] },
            ttlHours = c.settings.resultTtlHours(),
        )
        val text = when (format.value) {
            ExportFormat.TXT -> Exporter.toTxt(exportRows, includeSni.value)
            ExportFormat.CSV -> Exporter.toCsv(exportRows)
            ExportFormat.JSON -> Exporter.toJson(exportRows)
            ExportFormat.SKH_IP -> Exporter.toSkhIpList(exportRows)
            ExportFormat.SKH_SNI -> Exporter.toSkhSni(exportRows)
        }
        return text to exportRows.size
    }

    fun regenerate() {
        viewModelScope.launch {
            qrImages.value = emptyList()
            val built = buildText()
            if (built == null) {
                prepared.value = null
                feedback.value = c.context.getString(R.string.export_empty)
                return@launch
            }
            val (text, count) = built
            val prep = c.exportManager.prepare(format.value, text, count)
            prepared.value = prep
            feedback.value = prep.validationError?.let {
                c.context.getString(R.string.export_invalid, it)
            } ?: c.context.getString(R.string.export_validated)
        }
    }

    fun copy(onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val p = prepared.value ?: return@launch
            val ok = c.exportManager.copyToClipboard(p)
            onDone(if (ok) c.context.getString(R.string.export_copied) else feedback.value)
        }
    }

    fun share(onIntent: (Intent?) -> Unit) {
        viewModelScope.launch {
            onIntent(c.exportManager.share(prepared.value ?: return@launch))
        }
    }

    fun save(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val p = prepared.value ?: return@launch
            val f = c.exportManager.saveFile(p)
            onResult(f?.absolutePath ?: feedback.value)
        }
    }

    fun makeQr() {
        viewModelScope.launch {
            val p = prepared.value ?: return@launch
            qrImages.value = c.exportManager.qrCodes(p)
            if (qrImages.value.isEmpty()) feedback.value = c.context.getString(R.string.qr_too_large)
        }
    }
}

@Composable
fun ExportScreen(container: AppContainer, nav: NavController, argScanId: Long) {
    val vm: ExportVm = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = vmFactory { ExportVm(container, argScanId) },
    )
    val scans by vm.scans.collectAsStateWithLifecycle()
    val scanId by vm.scanId.collectAsStateWithLifecycle()
    val format by vm.format.collectAsStateWithLifecycle()
    val includeSni by vm.includeSni.collectAsStateWithLifecycle()
    val onlyGood by vm.onlyGood.collectAsStateWithLifecycle()
    val prepared by vm.prepared.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val qrImages by vm.qrImages.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(scanId, format, includeSni, onlyGood) { vm.regenerate() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineMedium) }

        item {
            SectionCard(title = stringResource(R.string.export_format)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ExportFormat.TXT to stringResource(R.string.format_txt),
                        ExportFormat.CSV to stringResource(R.string.format_csv),
                    ).forEach { (f, label) ->
                        FilterChip(selected = format == f, onClick = { vm.format.value = f }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ExportFormat.JSON to stringResource(R.string.format_json),
                        ExportFormat.SKH_IP to stringResource(R.string.format_sk),
                        ExportFormat.SKH_SNI to stringResource(R.string.format_sk_sni),
                    ).forEach { (f, label) ->
                        FilterChip(selected = format == f, onClick = { vm.format.value = f }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSni, onCheckedChange = { vm.includeSni.value = it })
                    Text(stringResource(R.string.include_sni_pairs))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onlyGood, onCheckedChange = { vm.onlyGood.value = it })
                    Text(stringResource(R.string.only_verified_export))
                }
            }
        }

        if (format == ExportFormat.SKH_IP || format == ExportFormat.SKH_SNI) {
            item {
                Text(
                    stringResource(R.string.sk_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        prepared?.let { p ->
            item {
                SectionCard(title = "${p.itemCount} × ${p.format}") {
                    Text(
                        p.text.take(600) + if (p.text.length > 600) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.copy { msg -> msg?.let { clipboard.setText(AnnotatedString(p.text)) } } }) {
                        Text(stringResource(R.string.copy))
                    }
                    OutlinedButton(onClick = { vm.share { intent -> intent?.let { context.startActivity(it) } } }) {
                        Text(stringResource(R.string.share))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.save { path -> vm.feedback.value = path } }) {
                        Text(stringResource(R.string.save))
                    }
                    OutlinedButton(onClick = { vm.makeQr() }) {
                        Text(stringResource(R.string.qr_code))
                    }
                }
                feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        } ?: item { EmptyState(stringResource(R.string.export_empty)) }

        items(qrImages.size) { i ->
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = qrImages[i].asImageBitmap(),
                    contentDescription = "QR ${i + 1}",
                    modifier = Modifier.size(260.dp),
                )
            }
            Text("#${i + 1}/${qrImages.size}", style = MaterialTheme.typography.bodySmall)
        }

        item {
            SectionCard(title = stringResource(R.string.export_history)) {
                history.take(8).forEach { h ->
                    Text("${h.format} · ${h.itemCount} · ${com.cdhunter.pro.ui.components.Fmt.time(h.createdAtMs)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
